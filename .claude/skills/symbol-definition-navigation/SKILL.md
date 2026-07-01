# Skill: symbol-definition-navigation

## 概要

NORMALモードの **Shift+K（`K`）** でカーソル位置の識別子（クラス・メソッド・フィールド変数・定数）の
**宣言箇所**へジャンプする機能。Eclipse/IntelliJ IDEA の "Open Declaration" 相当を、単一キーに統合して提供する。
`gr`（go to references）で**参照箇所**を一覧表示する機能も別途用意している。

既存の `K` キー（⑩ `jdk-api-navigation`）はJDKの**クラス名**のみをジャンプ対象としており、
フィールド・定数・メソッドの宣言／自プロジェクト内のシンボルにはジャンプできなかった。
本Skillはその2つのギャップ（自プロジェクトのシンボル、JDKのメンバー）を埋める。

### キーバインド設計の変更履歴

当初はEclipse/IntelliJ流ではなく、Vim流に `gd`（go to definition）/`gr`（go to references）の
2キーとして実装したが、「キーバインドをシンプルにしたい。Eclipse/IntelliJ IDEA の探索システムを
参考に、単一の Shift+K で JDK とプロジェクト内のクラス・メソッド・フィールド/定数の定義元に
飛べるようにしたい」という要望を受け、`gd` の機能を既存の `K`（`lookupJdkDoc()`）に統合し、
`gd` キーバインドは削除した。`gr`（参照一覧）は定義ジャンプとは性質が異なる別機能のため維持している。

## 対象範囲

| ジャンプ元の識別子 | 対応 |
|---|---|
| 自プロジェクト内で宣言されたフィールド・定数・メソッド・クラス | `K` でファイル横断ジャンプ（最優先） |
| JDKクラス名 | `K` でJDKソース疑似バッファを開く（従来通り） |
| JDKクラスのフィールド（定数）・メソッド（`ClassName.member` の形でカーソルがある場合） | `K` でJDKソース疑似バッファ内の宣言行へジャンプ |
| 任意の識別子の使用箇所（参照） | `gr` で `*grep*` 疑似バッファに一覧表示、Enterでジャンプ（既存 ⑬ `project-wide-search` 基盤を再利用） |
| ネイティブ（C/C++）実装側の参照・ヘッダ宣言・呼び出し元 | jdk-source疑似バッファ内で `gr` を押すと `lib/openjdk-native/` を検索対象にする |
| 直前の `K` ジャンプの前にいた位置 | `Shift+J` で一つ戻る（ファイルを跨いだ場合は元のファイルも再度開く） |

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

`K`（`jdk.doc`、`KeymapRegistry` で `Shift+K` にバインド済み）を単一の入口として統合した。
`gr` のみ既存の `g` プレフィックス（`goto.pending`、`gg` で先頭行へ移動する仕組み）に
2打鍵目として追加している。`KeymapRegistry` を経由せず、`gg` と同じ生キー比較で分岐している
（`ModalEditor.onKeyPressed()` の `pendingSequence.equals("g")` ブロック内）。

```java
if (prev == 'g' && matches(keyCode, keyChar, KeyEvent.VK_R, 'r')) { goToReferences(); return; }
```

### lookupJdkDoc()（Shift+K、統合後）

`inJdkSourceBuffer` でない通常バッファ上では、既存のJDKクラス検索ロジックの**手前**に
プロジェクト内シンボル検索を追加した:

1. `wordAtCursor()` で識別子を取得。空ならステータスバーにエラー表示して終了。
2. `inJdkSourceBuffer` でなければ `ProjectSymbolResolver.resolve()` でプロジェクト内の宣言を検索。
   - 見つかった場合、宣言ファイルが現在のファイルと同じなら単にカーソル移動、
     別ファイルなら `loadFromFile()`（内部で `pushBuffer()` 済み）でファイルを開いてから
     カーソルを宣言行へ移動して終了する。
3. プロジェクト内で見つからなければ、`jdkIndex` の準備状態を確認した上で、従来通り
   jdk-source疑似バッファ内でのnativeトレース → `classAndMethodAtCursor()`
   （⑩ で実装済みの検出ロジック。メソッド専用ではなく「`.` の前後の識別子ペア」を
   返す汎用ロジックなのでフィールドにもそのまま使える）による `ClassName.member`
   形式のJDKメンバー解決 → 単純なJDKクラス名検索、の順に試みる。
4. どれにも該当しなければ `"Not found in JDK: " + word` を表示する（従来の挙動）。

### jumpBack()（Shift+J）— 一つ前の参照へ戻る

`K` によるジャンプが実際にカーソル・バッファを動かした場合にのみ、ジャンプ直前の状態
（`BufferSnapshot(text, filePath, row, col)`。既存の `bufferHistory`/Ctrl+U と同じレコード型を再利用）
を `lastJumpOrigin` に1件だけ保存する。保存は `lookupJdkDoc()` 自体ではなく、
それを薄くラップした関数で行う（内部ロジック本体は `lookupJdkDocAndJump()` にリネームして分離）:

```java
private void lookupJdkDoc() {
    BufferSnapshot before = new BufferSnapshot(buffer.getText(), currentFilePath, cursorRow, cursorCol);
    lookupJdkDocAndJump(before.text());
    boolean moved = cursorRow != before.row() || cursorCol != before.col()
        || !java.util.Objects.equals(currentFilePath, before.filePath());
    if (moved) {
        lastJumpOrigin = before;
    }
}
```

`jumpBack()` は `lastJumpOrigin` を1回限り消費して復元する（スタックではなく単一スロット。
「一個前の参照に戻る」という要望どおり、複数階層の戻る/進むは実装していない）。
ファイルを跨いだジャンプだった場合は `buffer`/`currentFilePath` を丸ごと元のテキストで置き換え、
`*jdk-source:` から始まるファイルパスであれば `inJdkSourceBuffer` フラグも復元する。
ジャンプ履歴が無い状態で押した場合は `"No previous jump to go back to"` を表示する。

`KeymapRegistry` に `Shift+J` → `jump.back` アクションとして登録し、
`ModalEditor` のアクション分岐に `case "jump.back" -> jumpBack();` を追加した
（`gr` のような生キー比較の特殊扱いではなく、通常の `KeymapRegistry` 経由のバインドで実現できる。
`Shift+J` は既存キーマップと衝突しないため）。

既存の Ctrl+U（バッファ履歴）や jdk-source 疑似バッファの `q`（`closeJdkSourceBuffer()`）とは
独立した仕組みであることに注意。`K` が同一ファイル内でカーソルだけを動かすケース
（`bufferHistory` に何も積まれない）は Ctrl+U では戻れないため、`Shift+J` が唯一の手段になる。

### goToReferences() — ネイティブ実装側の参照にも対応

`wordAtCursor()` の結果を語境界付き正規表現に変換し、既存の `executeGrep()`
（⑬ `project-wide-search` / `:grep` コマンドと共通）にそのまま渡す。
`*grep*` 疑似バッファが開き、Enterで結果行へジャンプできる（既存の `jumpToGrepResult()` がそのまま使える）。

**jdk-source 疑似バッファ内で `gr` を押した場合**、`OpenjdkSourceTracer.hasNativeSrcDir()` が
true なら検索対象を `lib/openjdk-native/` に切り替える。`ProjectSearcher.search()` は拡張子を
問わずディレクトリ配下を再帰的に grep するため、`.c`/`.cpp`/`.h` すべてが対象になり、
「ヘッダの宣言」「他の呼び出し箇所」「関連する参照」を1回の `gr` で横断的に見つけられる
（"参照を辿る"・"ヘッダを参照する"・"どこから呼ばれているか" という要望はすべて、
ネイティブソースツリー全体への語境界grepという単一の実装で満たせる）。

```java
private void goToReferences() {
    String word = wordAtCursor();
    if (word.isEmpty()) { setStatusMessage("No identifier at cursor"); return; }
    String pattern = "\\b" + Pattern.quote(word) + "\\b";
    if (inJdkSourceBuffer && sourceTracer.hasNativeSrcDir()) {
        executeGrep(pattern, sourceTracer.getNativeSrcDir().get());
        return;
    }
    executeGrep(pattern);
}
```

#### executeGrep / jumpToGrepResult の baseDir 一般化

従来の `executeGrep(String pattern)` は検索起点を `getProjectRoot()` に固定していたため、
`lib/openjdk-native/` のような別ディレクトリを検索できなかった。オーバーロードを追加し、
起点ディレクトリを外から指定できるようにした:

```java
private void executeGrep(String pattern) {
    executeGrep(pattern, getProjectRoot());
}
private void executeGrep(String pattern, Path baseDir) { ... } // 実体。baseDir を grepBaseDir に保存
```

`grepResults` の各 `SearchResult.filePath()` は起点ディレクトリからの相対パスなので、
`jumpToGrepResult()` 側も `getProjectRoot()` 固定ではなく新設の `grepBaseDir` フィールドを
使って絶対パスに解決するよう変更した。また、grep結果を開いた実ファイルは疑似バッファでは
ないため `inJdkSourceBuffer = false` にリセットする（そうしないと、native参照grep経由で
別の実ファイルを開いた後も `q` が誤って古い jdk-source 状態への復帰を試みてしまう）。

#### OpenjdkSourceTracer.getNativeSrcDir()

`hasNativeSrcDir()` は真偽値のみを返していたため、grep起点として使えるパスを取得する
`getNativeSrcDir(): Optional<Path>` を追加した（既存の `findCSymbol()` 等と同じ
`nativeSrcDir` フィールドをそのまま公開するだけで、探索ロジックの変更はない）。

## バグ修正: Javaソース閲覧中に無関係なCシンボルへ誤ジャンプする問題

**症状**: `public native void gc();` の `gc` にカーソルを置いて Shift+K を押すと、
`Runtime.gc()` のネイティブ実装とは全く無関係な `JNIEXPORT int main(int argc, char **argv)`
（JLIランチャーの `main.c`）の114行目 `argc = JLI_GetStdArgc();` へジャンプしてしまうバグがあった。

**根本原因は2つの複合**:

1. **`ModalEditor.lookupJdkDocAndJump()` のゲート漏れ**: jdk-source疑似バッファ内でのK処理には
   (A) C/C++シンボル定義ジャンプ（`findCSymbol`）と (B) JavaソースからのJNIネイティブトレース
   の2経路があるが、(A) は「`inJdkSourceBuffer` かつ `currentFilePath` が `*jdk-source:` で
   始まる」ことしか条件にしておらず、これは **Javaクラスソースを表示している場合にも true**
   になってしまう（`*jdk-source:java.lang.Runtime*` のようなタイトルも同じプレフィックスを持つため）。
   結果、Javaソース閲覧中に `K` を押すと本来通るべき (B) より先に (A) が発動し、
   `word`（例: `"gc"`）をそのまま **C言語シンボルとして** `lib/openjdk-native/` 全体から検索してしまっていた。
2. **`OpenjdkSourceTracer.findDefinitionLine()` の部分文字列マッチ**: C定義行の判定が
   `line.contains(symbol + "(")` という単純な部分文字列一致だったため、`"gc"` を探すと
   `"argc("` （`"JLI_GetStdArgc();"` 等）のような、たまたま `"gc("` を含む**別の識別子の内部**
   にも誤ってマッチしていた。

**修正**:

- `ModalEditor` に `jdkSourceIsNative`（現在のjdk-source疑似バッファがC/C++実ファイルか
  JNIスニペットかを示すフラグ。Javaクラスソースなら false）を追加し、`openJdkSourceBuffer()`
  に `boolean isNative` 引数を追加して呼び出し側で明示的に指定するようにした。
  (A) は `jdkSourceIsNative` が true の場合のみ実行するようゲートし、Javaソース閲覧中に
  C言語シンボル探索が発動しないようにした。
- `jumpBack()`（Shift+J）でこの状態を復元する際は `BufferSnapshot` に `isNative` を
  保持していないため、タイトルの拡張子（`.c`/`.cpp`/`.h`）または内容が `"[native] "` で
  始まるかから逆算する `looksLikeNativeJdkSource()` ヘルパーで推測する。
- `OpenjdkSourceTracer.findDefinitionLine()` の判定を `\bsymbol\s*\(` という単語境界付き
  正規表現に変更し、`"argc("` のような他の識別子の部分文字列に誤マッチしないようにした。
- テスト用に `OpenjdkSourceTracer(Path srcZipPath, Path nativeSrcDirOverride)` コンストラクタを
  追加し、実際の `lib/openjdk-native/` が無い環境でも一時ディレクトリで `findCSymbol()` の
  回帰テストを書けるようにした（`test/dev/javatexteditor/analysis/OpenjdkSourceTracingTest.java`
  に `testFindCSymbolDoesNotMatchSubstringOfOtherIdentifier()` /
  `testFindCSymbolMatchesRealDefinition()` を追加）。

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

`test/dev/javatexteditor/editor/JumpBackTest.java`

- 同一ファイル内: Shift+K でフィールド宣言へジャンプ→Shift+Jで元の行・列へ戻る
- ファイルを跨ぐケース: Shift+K で別ファイルを開く→Shift+Jで元のファイル・行・列へ戻る
- ジャンプ履歴が無い状態で Shift+J を押してもカーソルは動かない
- `KeymapRegistry` で Shift+J が `jump.back` にバインドされている

`test/dev/javatexteditor/editor/NativeReferenceSearchTest.java`

- `gr` によるプロジェクト全体の参照検索が baseDir 一般化後も回帰していないことの確認
  （`*grep*` 疑似バッファへの切り替え、Enterでのファイルオープン）
- `OpenjdkSourceTracer.getNativeSrcDir().isPresent()` が `hasNativeSrcDir()` と一致すること

**既知のテストギャップ**: `goToReferences()` の native 分岐（jdk-source疑似バッファ内で `gr` を
押した際に `lib/openjdk-native/` を検索する経路）は、CI/開発コンテナに `lib/openjdk-native/`
自体が存在しない（`scripts/setup.sh` で別途取得する外部リソースのため）ため自動テストできない。
既存の `OpenjdkSourceTracingTest` も同じ理由で native ディレクトリ実在時の挙動は未検証であり、
本Skillもその制約を引き継いでいる。実機（native ソースを取得済みの環境）での手動確認が必要。

## 依存関係

- ①（バッファ: `getText()` の取得元）
- ⑧ `java-source-analysis`（`SourceAnalyzer` / `SymbolEntry` / `SymbolKind` をそのまま再利用。新規の解析ロジックは追加していない）
- ⑬ `project-wide-search`（`ProjectSearcher` によるファイル絞り込み、`executeGrep()` による参照一覧表示。native参照検索も同じ`ProjectSearcher`を別の起点ディレクトリに向けて再利用している）
- ⑩ `jdk-api-navigation`（JDKクラス解決・`classAndMethodAtCursor()` の再利用、`jumpToMethod`→`jumpToMember` への一般化）
- ⑫ `openjdk-source-tracing`（`OpenjdkSourceTracer.hasNativeSrcDir()`/`getNativeSrcDir()` を用いて native ソースツリーの存在を判定・grep起点として利用。CLAUDE.mdのロードマップでは⑫は「未着手」だが、`gr`のnative分岐追加によりその一部を先行して実装した形になる）
