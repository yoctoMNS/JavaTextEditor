# Skill: symbol-definition-navigation

## 概要

NORMALモードの `gd`（go to definition）でカーソル位置の識別子（フィールド変数・定数・メソッド）の
**宣言箇所**へジャンプし、`gr`（go to references）で**参照箇所**を一覧表示する機能。

既存の `K` キー（⑩ `jdk-api-navigation`）はJDKの**クラス名**のみをジャンプ対象としており、
フィールド・定数・メソッドの宣言／自プロジェクト内のシンボルにはジャンプできなかった。
本Skillはその2つのギャップ（自プロジェクトのシンボル、JDKのメンバー）を埋める。

## 対象範囲

| ジャンプ元の識別子 | 対応 |
|---|---|
| 自プロジェクト内で宣言されたフィールド・定数・メソッド・クラス | `gd` でファイル横断ジャンプ |
| JDKクラスのフィールド（定数）・メソッド（`ClassName.member` の形でカーソルがある場合） | `gd` でJDKソース疑似バッファ内の宣言行へジャンプ |
| 任意の識別子の使用箇所（参照） | `gr` で `*grep*` 疑似バッファに一覧表示、Enterでジャンプ（既存 ⑬ `project-wide-search` 基盤を再利用） |

**スコープ外**（既知の制限。将来必要になれば別Skillで拡張）:
- 修飾なしの識別子（例: 単に `MAX_VALUE` とだけ書かれている）からJDKメンバーへは解決しない。
  JDKのどのクラスのメンバーか一意に決まらないため。`Integer.MAX_VALUE` のように
  `ClassName.member` の形でカーソルが `member` 上にある場合のみ対応する。
- オーバーロードされたメソッドの区別はしない（同名の最初の宣言にジャンプする）。
- 型解決は行わない（`SourceAnalyzer` は parse-only）。変数の型を辿ってメンバーを特定する
  ような真の「型に基づく」解決はしない。名前ベースの検索である。

## 実装済みクラス

| クラス | 場所 | 役割 |
|---|---|---|
| `ProjectSymbolResolver` | `src/dev/javatexteditor/analysis/ProjectSymbolResolver.java` | プロジェクト全体からシンボル宣言箇所を検索 |

## 設計方針: 2段階解決（フル解析を避ける）

数十万行規模のプロジェクトを想定しているため（CLAUDE.md参照）、`gd` のたびに
プロジェクト全体を `SourceAnalyzer`（Compiler Tree API）でフル解析するのは高コスト。
⑭ `multi-file-refactoring`（`RenameRefactorer`）と同じ2段階方式を踏襲する。

```
Phase 1: ProjectSearcher で \bname\b にマッチするファイルを高速に絞り込む（正規表現の行スキャン）
Phase 2: 絞り込んだ候補ファイルだけを SourceAnalyzer.analyzeFile() でAST解析し、
         SymbolEntry.name() が一致する宣言を探す
```

候補ファイルが多数でも、Phase 2 は実際に識別子を含むファイルのみに限定されるため、
無関係な大量のファイルを解析するコストは発生しない。

## 未保存バッファの扱い

現在編集中でファイルにまだ保存されていない変更を正しく扱うため、
`ProjectSymbolResolver.resolve()` は最初に **現在のバッファのテキスト**
（`buffer.getText()`、ディスク上の内容ではない）を `analyzeText()` で解析する。
見つからなければディスク上の他ファイルを Phase 1/2 で検索する
（このとき現在のファイルはスキップ済みとして除外する）。

## ModalEditor への接続

```java
private final ProjectSymbolResolver projectSymbolResolver = new ProjectSymbolResolver();
```

`gd` / `gr` は既存の `g` プレフィックス（`goto.pending`、`gg` で先頭行へ移動する仕組み）に
2打鍵目として追加した。`KeymapRegistry` を経由せず、`gg` と同じ生キー比較で分岐している
（`ModalEditor.onKeyPressed()` の `pendingSequence.equals("g")` ブロック内）。

```java
if (prev == 'g' && matches(keyCode, keyChar, KeyEvent.VK_D, 'd')) { goToDefinition(); return; }
if (prev == 'g' && matches(keyCode, keyChar, KeyEvent.VK_R, 'r')) { goToReferences(); return; }
```

### goToDefinition()

1. `wordAtCursor()` で識別子を取得。空ならステータスバーにエラー表示して終了。
2. `ProjectSymbolResolver.resolve()` でプロジェクト内の宣言を検索。
   - 見つかった場合、宣言ファイルが現在のファイルと同じなら単にカーソル移動、
     別ファイルなら `loadFromFile()`（内部で `pushBuffer()` 済み）でファイルを開いてから
     カーソルを宣言行へ移動する。
3. プロジェクト内で見つからなければ、カーソルが `ClassName.member` の `member` 上にあるかを
   `classAndMethodAtCursor()`（⑩ で実装済みの検出ロジックを流用。メソッド専用ではなく
   「`.` の前後の識別子ペア」を返す汎用ロジックなのでフィールドにもそのまま使える）で判定し、
   JDKクラスとして解決できれば疑似バッファを開いて `jumpToMember()` で宣言行を探す。
4. どちらでも見つからなければ `"Definition not found: " + word` を表示する。

### goToReferences()

`wordAtCursor()` の結果を語境界付き正規表現に変換し、既存の `executeGrep()`
（⑬ `project-wide-search` / `:grep` コマンドと共通）にそのまま渡す。
`*grep*` 疑似バッファが開き、Enterで結果行へジャンプできる（既存の `jumpToGrepResult()` がそのまま使える）。
新しいUIは実装していない — 既存のgrep結果ジャンプ機構をそのまま再利用している。

```java
private void goToReferences() {
    String word = wordAtCursor();
    if (word.isEmpty()) { setStatusMessage("No identifier at cursor"); return; }
    executeGrep("\\b" + Pattern.quote(word) + "\\b");
}
```

## JDKソース疑似バッファ内でのフィールド宣言ジャンプ（⑩の拡張）

⑩ `jdk-api-navigation` の `jumpToMethod()` はメソッド宣言行（`name(` を含み、
アクセス修飾子を含む行）しか検出できず、フィールド・定数（例: `Integer.MAX_VALUE`）では
クラスソースは開かれるが該当行へはジャンプしないという既知のギャップがあった。

これを `jumpToMember(name)` に一般化し、まずメソッド宣言として探し、
見つからなければフィールド宣言として探すよう拡張した:

```java
private boolean jumpToMethod(String methodName) { ... } // 既存: name( + アクセス修飾子
private boolean jumpToField(String fieldName) { ... }   // 新規: 語境界一致 + アクセス修飾子
                                                          //       + ";" または "=" を含む行
                                                          //       + name( を含む行は除外（呼び出しと誤認しないため）
private void jumpToMember(String name) {
    if (jumpToMethod(name)) return;
    if (jumpToField(name)) return;
    setStatusMessage("Declaration of " + name + " not found in source  q: close");
}
```

`K` キー（`lookupJdkDoc()`）の非nativeメンバージャンプ箇所も `jumpToMethod()` から
`jumpToMember()` に置き換え済み。これにより `K` キーでも `Integer.MAX_VALUE` のような
定数の宣言行へ正しくジャンプできるようになった（副次的な修正）。

これらのヒューリスティックはJDKソースの**テキスト**を対象にした簡易パターンマッチであり、
AST解析ではない（jdk-source疑似バッファは表示専用でコンパイル対象ではないため、
⑧ `java-source-analysis` の仕組みをここに使う必要はない）。

## テスト

`test/dev/javatexteditor/analysis/ProjectSymbolResolverTest.java`

- 単一ファイル内のフィールド宣言を発見できる
- 単一ファイル内のメソッド宣言を発見できる
- 複数ファイルにまたがるプロジェクトで、宣言のあるファイルを正しく発見できる
- 現在のバッファ（未保存の内容）を最優先で検索する
- 存在しないシンボル名は `Optional.empty()` を返す
- クラス宣言も発見できる（フィールド・メソッドと同じ検索経路を使う）

## 依存関係

- ①（バッファ: `getText()` の取得元）
- ⑧ `java-source-analysis`（`SourceAnalyzer` / `SymbolEntry` / `SymbolKind` をそのまま再利用。新規の解析ロジックは追加していない）
- ⑬ `project-wide-search`（`ProjectSearcher` によるファイル絞り込み、`executeGrep()` による参照一覧表示）
- ⑩ `jdk-api-navigation`（JDKクラス解決・`classAndMethodAtCursor()` の再利用、`jumpToMethod`→`jumpToMember` への一般化）
