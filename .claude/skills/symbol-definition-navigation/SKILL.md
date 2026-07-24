---
name: symbol-definition-navigation
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、Shift+K（クラス・メソッド・フィールドの定義へジャンプ）/Shift+J（一つ前の位置へ戻る）/gr（参照一覧）を設計・実装・修正する際に使用する。「定義へジャンプしたい」「変数名.メンバー名から宣言を探す（レシーバ型解決）」「参照検索」といった相談、またProjectSymbolResolver/ReceiverTypeResolverやlookupJdkDoc周辺を触る作業に着手する前に、必ず最初に参照すること。⑩jdk-api-navigationのKキー記述を上書きする最新仕様。"
---

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
| jdk-source疑似バッファ内で、表示中のクラス自身が持つメンバーへの修飾なし呼び出し（同一クラス内のオーバーロード呼び出しを含む） | `K` で疑似バッファ内の宣言行へジャンプ（後述「バグ修正: jdk-source疑似バッファ内で修飾なしの識別子が同一クラスの他メンバーへジャンプできない」参照） |
| 直前の `K` ジャンプの前にいた位置 | `Shift+J` で一つ戻る（ファイルを跨いだ場合は元のファイルも再度開く） |

**スコープ外**（既知の制限。将来必要になれば別Skillで拡張）:

> **2026-07 追記**: 下記「Eclipse JDT 流バインディング解決（Shift+K の最優先段）」節の追加により、
> バインディング解決が成功するケースではこれらの制限（型解決なし・オーバーロード区別なし・
> 修飾なしJDKメンバー未解決）は解消される。以下の記述は**フォールバック経路
> （バインディング解決が失敗した場合に使われる従来のヒューリスティック）の制限**として残る。
- 修飾なしの識別子（例: 単に `MAX_VALUE` とだけ書かれている）から**通常バッファ上で**JDKメンバーへは
  解決しない。JDKのどのクラスのメンバーか一意に決まらないため。`Integer.MAX_VALUE` のように
  `ClassName.member` の形でカーソルが `member` 上にある場合のみ対応する。
  ただし、既に jdk-source 疑似バッファでそのクラスのソースを表示している最中は「どのクラスか」は
  自明（表示中のクラス自身）なので、この制限は適用されない（上表・後述のバグ修正参照）。
- オーバーロードされたメソッドの区別はしない（同名の最初の宣言にジャンプする）。
- 型解決は行わない（`SourceAnalyzer` は parse-only）。変数の型を辿ってメンバーを特定する
  ような真の「型に基づく」解決はしない。名前ベースの検索である。ただし後述の
  「レシーバ型解決」は軽量な正規表現ヒューリスティックで宣言型を推定する（javacによる
  意味解析ではない）ことで、この制約の範囲内のまま「変数名.メンバー名」の誤ジャンプを防ぐ。

## レシーバ型解決: 「変数名.メソッド名」「変数名.フィールド名」への対応

**背景となった不具合**: 当初の実装は `K` を押した時のカーソル位置の識別子（`wordAtCursor()`）
**だけ**で宣言を検索しており、`obj.method()` の `obj`（レシーバ）を一切見ていなかった。
そのため以下のような問題があった。

- 同名メソッド/フィールドが複数クラスに存在すると、`obj` の型に関係なく最初に見つかった
  無関係なクラスの宣言へジャンプしてしまう。
- インスタンス変数がJDK型（例: `List<String> list; list.add(...)` の `list`）の場合、
  `list` は文字通りのクラス名ではないため、既存の `ClassName.member`（`classAndMethodAtCursor()`）
  ロジックが素通りしてしまい、JDKメンバーへ全くジャンプできなかった。

### 設計判断（ユーザーとの合意事項）

| 論点 | 決定 |
|---|---|
| 型解決の方式 | javacのフル型解決（`Trees.getTypeMirror`等）は使わない。軽量な正規表現ヒューリスティックで「`Type varName`」の形の宣言をテキストとして探す。CLAUDE.mdの「parse-onlyでシンプルに」という既存方針を維持するため。 |
| 対象スコープ | ローカル変数宣言・メソッド引数・フィールド宣言（インスタンス/static）・`this.フィールド名.member` 形式。すべて同一の正規表現パターンでカバーする（下記参照）。 |
| 検索の絞り込み | レシーバの型が自プロジェクトのクラスだと分かった場合、そのクラスのファイルだけにメンバー検索を限定する。継承元（親クラス）のファイルへは辿らない（スコープ外。誤って無関係クラスにヒットするくらいなら「見つからない」方が安全という判断）。 |
| JDK型のレシーバ | レシーバの型がJDKクラスだと分かった場合、既存のJDKメンバー解決ロジック（`tryJdkMember()`、旧`classAndMethodAtCursor()`のJDK処理部分）にそのまま接続する。プロジェクト内かJDKかを型解決の結果に応じて自動的に振り分ける。 |
| フォールバック | 型解決に失敗した場合（宣言が見つからない、複雑な式、ジェネリクス推論不可など）は、従来通り識別子名だけでの検索にフォールバックする（見つからないよりはまし、という判断）。 |

### 実装

`dev.javatexteditor.analysis.ReceiverTypeResolver`（新規）が型推定を担当する。

```java
public Optional<String> resolveType(String[] lines, int cursorRow, String varName)
```

カーソル行から上方向（`cursorRow` から `0` へ）に近い順で、`(?:^|[^.\w])(Type)\s+varName\b\s*(?:=|;|,|\)|:)`
という正規表現を各行に適用し、最初にマッチした行の型を採用する（近似的なスコープ判定: 「直近の
宣言が最も有力」という単純な仮定。真のブロックスコープ解析はしない）。末尾の記号
（`=`/`;`/`,`/`)`/`:`）でローカル変数宣言・フィールド宣言・メソッド引数・拡張for文の4パターンを
一つの正規表現でカバーしている。ジェネリクス（`List<String>`）・配列（`Cat[]`）は基底の型名に
正規化する（`List`/`Cat`）。`return`/`new`/`this` 等、型と誤認しやすい予約語は
`NON_TYPE_KEYWORDS` で除外している（例: `return obj;` を `Type=return` と誤解析しない）。

`ProjectSymbolResolver` に追加した `resolveMemberInType(baseDir, currentFilePath,
currentBufferText, typeName, memberName)` は、まず `typeName` という名前のクラス/インタフェース/
enumが自プロジェクト内で宣言されたファイルを探し（見つからなければ即 `Optional.empty()` を返し、
呼び出し側にJDK解決を促す）、見つかった場合はそのファイルだけを対象に `memberName` を検索する。

`ModalEditor.lookupJdkDocAndJump()` 内の `tryResolveQualifiedMember(receiver, member,
bufferTextSnapshot)` が両者を接続するオーケストレーション層:

```java
private boolean tryResolveQualifiedMember(String receiver, String member, String bufferTextSnapshot) {
    // (a) receiver 自体が自プロジェクトのクラス名（static呼び出し）であるケース
    Optional<SymbolLocation> asStatic = projectSymbolResolver.resolveMemberInType(
        getProjectRoot(), currentFilePath, bufferTextSnapshot, receiver, member);
    if (asStatic.isPresent()) { jumpToSymbolLocation(asStatic.get(), member); return true; }

    // (b) receiver をローカル変数/引数/フィールドとみなし、宣言型を推定する
    Optional<String> type = receiverTypeResolver.resolveType(getLines(), cursorRow, receiver);
    if (type.isEmpty()) return false;

    Optional<SymbolLocation> viaType = projectSymbolResolver.resolveMemberInType(
        getProjectRoot(), currentFilePath, bufferTextSnapshot, type.get(), member);
    if (viaType.isPresent()) { jumpToSymbolLocation(viaType.get(), member); return true; }

    // 型がJDKクラスなら既存のJDKメンバー解決に委譲
    if (jdkIndex != null && jdkIndex.isReady() && tryJdkMember(type.get(), member)) return true;
    return false;
}
```

`lookupJdkDocAndJump()` は `!inJdkSourceBuffer` の最優先ブロックで、まず
`classAndMethodAtCursor()`（カーソルが `x.y` の `y` 上にあるかを判定する既存ヘルパー。
レシーバが実際にクラス名か変数名かは区別せず構文的に検出するだけ）で `[receiver, member]` を得て
`tryResolveQualifiedMember()` を試し、それが `false` を返した場合のみ従来の
「識別子名だけでの `projectSymbolResolver.resolve(word)`」にフォールバックする。

`tryJdkMember(className, methodName)` は、従来 `lookupJdkDocAndJump()` に直書きされていた
「`ClassName.methodName` の `methodName` 上にある場合の native トレース/ソースジャンプ」処理を
そのまま切り出したもの。`className` がJDKクラスとして解決できなければ `false` を返し、
呼び出し側（旧来の `Integer.MAX_VALUE` のような直接クラス名指定のケースと、新規の型解決結果を
渡すケースの両方）が次の手段を試せるようにしている。この切り出しにより、
「レシーバの型を推定した結果がJDKクラスだった」ケースと「レシーバが最初からクラス名として
書かれている」ケースの両方で同じJDK解決ロジックを再利用できる。


## バグ修正: 「変数名.メソッド名」の変数名側（レシーバ）にカーソルがあると K が何もヒットしない

**症状**: `h.doWork()` のような呼び出しで、カーソルが `doWork`（member側）ではなく `h`（レシーバ側）に
あるまま Shift+K を押すと "Not found in JDK: h" になり、ジャンプできなかった。

**原因**: `classAndMethodAtCursor()` はカーソルが `.` の**直後**の識別子（member側）にある場合しか
`[receiver, member]` を検出できない設計になっていた。レシーバ側にカーソルがある場合、
`wordAtCursor()` は単に `"h"` という単一の識別子を返すだけになり、その後の
`projectSymbolResolver.resolve(..., "h")` はプロジェクト内のフィールド・メソッド・クラス宣言しか
対象にしていない（`SourceAnalyzer` はローカル変数・引数を一切シンボルとして収集しない設計のため）。
結果、ローカル変数・引数の名前は原理的にこの検索へ絶対にヒットせず、JDK側の検索（`jdkIndex.lookup("h")`）
にも当然ヒットしないため、"Not found" になっていた。

**修正**: `ReceiverTypeResolver` に `resolveDeclarationLine(lines, cursorRow, varName)` を追加した。
既存の `resolveType()`（型名を返す）と同じ「直近の宣言が最有力」という正規表現ヒューリスティック
（`(?:^|[^.\w])(Type)\s+varName\b\s*(?:=|;|,|\)|:)`）を共有しつつ、型名ではなく**その宣言が
見つかった行番号**を返す。内部的には `resolveType`/`resolveDeclarationLine` 共通のプライベート
`record Declaration(int row, String type)` を返す `findNearestDeclaration()` に一本化した。

`ModalEditor.lookupJdkDocAndJump()` に、プロジェクト内シンボル検索（`projectSymbolResolver.resolve()`）
が失敗した直後の位置に `jumpToLocalDeclaration(word)` を追加した。`word` 自身がローカル変数・引数の
名前であれば `receiverTypeResolver.resolveDeclarationLine()` で宣言行を探し、見つかれば同一ファイル内
（ローカル変数はファイルを跨がないため）でその行へジャンプする。`jdkIndex` の準備状態を問わず
（`!inJdkSourceBuffer` ブロック内、JDK索引チェックより前）動作するため、JDK索引が未構築でも
プロジェクト内のローカル変数ジャンプは即座に機能する。

**意図的な設計判断**:
- この検索は `word` が既存の経路（qualified member 解決・プロジェクトシンボル検索）で
  1件もヒットしなかった場合の**最後のフォールバック**として追加した。フィールド/メソッド/クラス名の
  解決を優先させるため、既存のヒットを奪わない。
- カーソルが member 側（`doWork`）にある場合は従来通り `classAndMethodAtCursor()` +
  `tryResolveQualifiedMember()` が処理する。今回追加したフォールバックは、そちらが不発だった
  場合（＝レシーバ側にカーソルがある、または修飾されていない単なる変数参照）にのみ効く。
- ジャンプ先の列は常に `0`（行頭）にした。既存の `jumpToSymbolLocation()` と同じ精度（行単位）に
  揃えるためで、変数名の正確な列位置を計算する追加ロジックは導入していない。

**テスト**: `ReceiverTypeResolverTest` に `resolveDeclarationLine()` 用の3テストを追加（ローカル変数・
メソッド引数の宣言行を発見できること、未宣言の変数は空を返すこと）。`JumpBackTest` に
`testShiftKOnLocalVariableReceiverJumpsToDeclaration`/`testShiftKOnLocalVariableReceiverThenJumpBack`
を追加し、`h.doWork()` の `h` 側にカーソルを置いた状態での Shift+K → 宣言行ジャンプ → Shift+J
での復帰までを ModalEditor 経由で検証した。

## インスタンスメソッド呼び出し（継承されたメソッド）への対応

上記の「レシーバ型解決」だけでは、レシーバの宣言型**自身**が持たないメンバー
（スーパークラスから継承しているメソッド）を呼び出した場合に、無関係な同名メソッドへ
誤ジャンプするバグがあった（例: `Derived extends Base` で `Base` にしか無い `helper()` を
`d.helper()` の形で呼ぶと、プロジェクト内の無関係な `Other.helper()` へ飛んでしまう）。

`ProjectSymbolResolver.resolveMemberInType()` を、対象クラスにメンバーが無ければ
`SymbolEntry.superTypeName()`（`SourceAnalyzer` が `extends` 節から抽出した親クラス名）を
辿ってスーパークラスのファイルも探す「型階層探索」に拡張して修正した。
JDKクラスの継承（`ArrayList` 等を継承した自作クラス）や `implements`（インタフェースの
デフォルトメソッド）は今回のスコープ外。設計判断の詳細・既知の制限は
`references/instance-method-hierarchy-resolution.md` を参照。

## 実装済みクラス

| クラス | 場所 | 役割 |
|---|---|---|
| `ProjectSymbolResolver` | `src/dev/javatexteditor/analysis/ProjectSymbolResolver.java` | プロジェクト全体からシンボル宣言箇所を検索。`resolveMemberInType()` は型名を指定してそのクラスのファイルだけに絞ったメンバー検索を行う（見つからなければ `superTypeName` を辿ってスーパークラスも探す） |
| `ReceiverTypeResolver` | `src/dev/javatexteditor/analysis/ReceiverTypeResolver.java` | 「変数名.メンバー名」の変数名から、軽量な正規表現ヒューリスティックで宣言型を推定する |

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
- `resolveMemberInType()`: 同名メソッドが複数クラスに存在しても、型を指定すれば正しいクラスの
  ファイルだけから見つかる（レシーバ型解決バグの再現テスト）
- `resolveMemberInType()`: 型自体がプロジェクト内に見つからない（JDK型等）場合は
  `Optional.empty()` を返し、呼び出し側にJDK解決を促す
- `resolveMemberInType()`: 型は見つかるがそのクラスにメンバーが無い場合も `Optional.empty()`
  （他クラスへのフォールバック検索はしない）

`test/dev/javatexteditor/analysis/ReceiverTypeResolverTest.java`

- ローカル変数宣言・メソッド引数・フィールド宣言・拡張for文のいずれからも宣言型を推定できる
- ジェネリクス（`List<String>` → `List`）・配列（`Cat[]` → `Cat`）は基底の型名に正規化される
- カーソルに近い宣言が優先される（近似的なスコープ判定）
- `return`/`this` 等の予約語を型と誤認しない
- 宣言が見つからない変数は `Optional.empty()` を返す

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

## バグ修正: ネストした（内部/静的ネスト）クラス内のメソッド・フィールドが見つからない

**症状**: `Outer` クラスの中にネストした `Inner` クラスがあり、`Inner` の中で自分自身の兄弟
メソッドを無資格呼び出し（例: `Inner` 内の `caller()` から同じ `Inner` の `helper()` を
`helper();` の形で呼ぶ）した状態で Shift+K を押しても、宣言箇所へジャンプできなかった。

**原因**: `SourceAnalyzer.collectTopLevelSymbols()`（`src/dev/javatexteditor/analysis/SourceAnalyzer.java`）
がネストした `ClassTree`（内部クラス・静的ネストクラス）のメンバーを収集対象から意図的に除外していた
（`default -> {} // ネストしたクラスは収集しない`）。`ProjectSymbolResolver.resolve()` は
`SourceAnalyzer` が返す `SourceIndex.symbols()` のみを見るため、ネストクラスのメソッド・フィールドは
そもそも索引に存在せず、`K` からは原理的に発見不可能だった。

なお、トップレベルクラス1つだけのファイルでの無資格呼び出し・`this.method()`・自クラス修飾
`ClassName.staticMethod()` はいずれも `resolve()` が現在バッファのテキストを最優先で解析する
仕組み（本SKILL.mdの「未保存バッファの扱い」節）により、この修正以前から正しく動作していた。
不具合はネストクラスのケースに限定される。

**修正**: `SourceAnalyzer.collectClassSymbols()`（旧 `collectTopLevelSymbols()` のクラス処理部分を
再帰可能な形に切り出したもの）を新設し、クラスメンバーのループ内で `ClassTree` 型のメンバー
（ネストした型宣言）に遭遇した場合、同じメソッドを再帰呼び出しするようにした。これにより:

- ネストクラス自身も `CLASS`/`INTERFACE`/`ENUM` 種別の `SymbolEntry` として収集されるようになった
  （多重ネストにも対応。同名のネストクラス自身へも `K` でジャンプできる）。
- ネストクラスの `MethodTree`/`VariableTree`（メソッド・フィールド・コンストラクタ）も同じ
  フラットな `symbols` リストに追加されるため、`ProjectSymbolResolver.resolve()`（名前ベース検索、
  無変更）がそのまま発見できるようになった。

**意図的な設計判断**:
- 既存テスト `SourceAnalyzerTest.test_nestedClassNotIncluded()` は「ネストクラス自身がCLASS種別と
  して収集されないこと」をアサートしていたが、この修正でネストクラスも積極的に収集する方針へ
  転換したため、ユーザー確認の上でテストを `test_nestedClassIncluded()`（Outer・Inner 両方が
  CLASS種別で見つかることを検証）に更新した。
- 型解決なし・名前ベース検索・オーバーロード区別なしという既存方針は変更していない。複数のネスト
  クラスに同名メソッドが存在する場合の優先順位付け（型階層探索等）は導入しておらず、従来通り
  「最初に見つかったもの」へジャンプする。
- レシーバ修飾付き呼び出し（`outerInstance.innerInstance.method()` のような、ネストクラスの
  インスタンスを経由した呼び出し）から `resolveMemberInType()` 経由でネストクラスのメンバーへ
  ジャンプする経路の拡張は今回のスコープ外とした（`resolveMemberInType()`/`findClassFile()`
  自体は無変更）。今回の修正はネストクラス自身がCLASS種別で収集されるようになった副次効果として
  `resolveMemberInType(baseDir, ..., "Inner", "helper")` のような呼び出しも動作するようになった
  可能性が高いが、専用のテストは追加していない（必要になれば別途検証すること）。

**テスト**: `SourceAnalyzerTest.test_nestedClassIncluded()`（Outer・Inner がいずれもCLASS種別で
見つかる）・`test_nestedClassMembersIncluded()`（ネストクラスのメソッド・フィールドが収集される）、
`ProjectSymbolResolverTest.test_findMethodInNestedClass()`（ディスク上のファイルに対し
`resolve()` でネストクラスのメソッド宣言を発見できることを確認）。

## バグ修正: jdk-source疑似バッファ内で修飾なしの識別子が同一クラスの他メンバーへジャンプできない

**症状**: `javax.imageio.ImageIO` の `read(File)` メソッドを jdk-source 疑似バッファで表示中、
本体内の `BufferedImage bi = read(stream);`（同じ `ImageIO` クラスの別オーバーロード
`read(ImageInputStream)` を無資格で呼び出している箇所）の `read` にカーソルを置いて Shift+K を
押しても、オーバーロード先のメソッド宣言へジャンプできなかった。

**原因**: `lookupJdkDocAndJump()` の jdk-source 疑似バッファ内処理には (A) C/C++ シンボル定義
ジャンプと (B) Java FQN バッファ内での native メソッドトレースの2経路しかなく、いずれも
「native実装かどうか」の判定にしか使えなかった。`read(stream)` のような**非native**の
通常メソッド呼び出しはどちらにも該当せず、そのままブロックを素通りしてしまう。その後、
カーソル位置は `classAndMethodAtCursor()`（`"レシーバ.member"` の形のみ検出、`.` が無い
無資格呼び出しは検出できない）にも該当せず、最終手段として `jdkIndex.lookup(word)`
（`word` を**クラス名**として検索）が試みられるが、`"read"` はクラス名ではないため
`"Not found in JDK: read"` になっていた。「対象範囲」節に記載の通り、修飾なしの識別子から
JDKメンバーへ解決しないのは元々意図した制限（「JDKのどのクラスのメンバーか一意に決まらない」
ため）だったが、**既に jdk-source 疑似バッファでそのクラスのソースを表示している最中に限っては
「どのクラスか」は自明（表示中のクラス自身）**であり、この制限を適用する理由がないケースだった。

**修正**: `lookupJdkDocAndJump()` の jdk-source 疑似バッファ処理ブロックに (C) を追加した。
`!jdkSourceIsNative`（現在表示中のバッファが C/C++ ではなく Java クラスソースである）かつ
`classAndMethodAtCursor() == null`（カーソルがレシーバ修飾付きの `.member` 上ではない、
＝無資格呼び出し）の場合、現在表示中のバッファ自身に対して既存の `jumpToMember(word)`
（メソッド宣言 → フィールド宣言の順で探す、⑩ で実装済みのヘルパー）をそのまま呼ぶ。
現在のバッファが既に対象クラスのソースそのものであるため、新規の検索ロジックは一切不要で、
既存のヘルパーをもう一箇所から呼ぶだけで実現できた。

- `jumpToMember(String)` の戻り値を `void` から `boolean`（ジャンプに成功したら `true`）に
  変更した。既存の2箇所の呼び出し元（`tryJdkMember()`・`jumpToJavaSourceEntry()`）はいずれも
  最終手段としての呼び出しで戻り値を使っていなかったため、`void` 文として呼び出しても
  そのままコンパイルが通り、動作は変更していない（失敗時に "not found" メッセージを出す
  従来の挙動もそのまま）。新設の呼び出し元だけが戻り値を見て、失敗時は次の
  フォールバック（`classAndMethodAtCursor()` によるqualified解決 → `jdkIndex.lookup(word)`
  によるクラス名解決）に処理を委ねる。
- 既存の「オーバーロードされたメソッドの区別はしない（同名の最初の宣言にジャンプする）」という
  ⑩由来の制限はそのまま踏襲した。`read(stream)` から実際に呼ばれている
  `read(ImageInputStream)` そのものではなく、`read` という名前の**最初に宣言された**
  オーバーロード（多くの場合 `read(File)` 自身）へジャンプする可能性があるが、
  「何もヒットしない」よりは実用上有用と判断し、型解決による厳密なオーバーロード区別は
  今回もスコープ外とした。

**意図的にスコープ外とした点**:
- 通常バッファ（`!inJdkSourceBuffer`）上での修飾なし識別子からJDKメンバーへの解決は
  今回も対象外のまま（「対象範囲」節の既存の制限を参照）。あくまで「既に対象クラスの
  ソースを表示中」という限定的な状況でのみ、クラスの曖昧さが原理的に存在しないことを
  利用した拡張である。
- ネイティブ実装をトレースする (A)/(B) との優先順位は変更していない。(C) は (A)/(B) が
  どちらも不発だった場合のみ試みる、既存のフォールバック連鎖の末尾に追加した。

**テスト**: `test/dev/javatexteditor/editor/JumpBackTest.java` に
`testShiftKUnqualifiedSameClassOverloadJump()` を追加した。`java.lang.Integer.parseInt(String)`
の本体内から同一クラスの `parseInt(String, int)` を無資格で呼び出す箇所（実際の JDK ソースの
バージョン差異を吸収するため、ハードコードした行番号ではなく `parseInt(` を含む非宣言行を
バッファテキストから動的に探す方式にした）で Shift+K を押し、jdk-source 疑似バッファ内の
宣言行（アクセス修飾子を伴う `parseInt(` の行）へジャンプすることを確認する。他の jdk-source
系テストと同じく、実行環境に `lib/src.zip`（`scripts/setup.sh` で取得する外部リソース）が
無い場合は graceful degradation としてテストをスキップする。本修正の実装時には、一時的に
`java.base/java/lang/Integer.java` に本テスト同様の疑似コンテンツを含む `src.zip` を作成し
`lib/src.zip` に配置した状態で手動実行し、修正前は "Not found in JDK: parseInt" になっていた
ものが修正後は同一クラス内の宣言行へ正しくジャンプすることを実機確認済み（確認用の
`lib/src.zip` はコミットせず削除済み）。

## 依存関係

- ①（バッファ: `getText()` の取得元）
- ⑧ `java-source-analysis`（`SourceAnalyzer` / `SymbolEntry` / `SymbolKind` をそのまま再利用。新規の解析ロジックは追加していない）
- ⑬ `project-wide-search`（`ProjectSearcher` によるファイル絞り込み、`executeGrep()` による参照一覧表示。native参照検索も同じ`ProjectSearcher`を別の起点ディレクトリに向けて再利用している）
- ⑩ `jdk-api-navigation`（JDKクラス解決・`classAndMethodAtCursor()` の再利用、`jumpToMethod`→`jumpToMember` への一般化）
- ⑫ `openjdk-source-tracing`（`OpenjdkSourceTracer.hasNativeSrcDir()`/`getNativeSrcDir()` を用いて native ソースツリーの存在を判定・grep起点として利用。CLAUDE.mdのロードマップでは⑫は「未着手」だが、`gr`のnative分岐追加によりその一部を先行して実装した形になる）

## Eclipse JDT 流バインディング解決（2026-07 追加・Shift+K の最優先段）

「Eclipse JDT のアルゴリズムを参考に Shift+K の定義ジャンプを作りたい。JVM/HotSpot 部分の
アルゴリズムには一切触れない」という依頼に基づく拡張。実装前に `AskUserQuestion` で
以下4点を確認・確定した。

| 論点 | 決定 |
|---|---|
| 既存 Shift+K との関係 | **内部を強化・失敗時は既存へフォールバック**。キー・UI・Shift+J は現状維持。既存の正規表現ヒューリスティック（`ProjectSymbolResolver`/`ReceiverTypeResolver`）は削除せず、バインディング解決が失敗した場合のフォールバックとして全経路を温存する。 |
| 再現レベル | **バインディング解決を再現**。JDT の「ASTParser+resolveBindings → NodeFinder → IBinding → 宣言要素」を JDK 標準 API（`javax.tools.JavaCompiler` + Compiler Tree API）で実装する。 |
| 性能・フリーズ対策 | **完全非同期化**（ユーザーが明示選択）。バックグラウンド仮想スレッドで解析し `SwingUtilities.invokeLater` で反映する。 |
| JDK シンボル | **JDK シンボルも JDT 流で解決**。`Trees.getElement()` の結果が JDK クラスの Element なら FQCN・モジュール名から src.zip 疑似バッファジャンプへ接続する。 |

JNI/HotSpot の native トレース経路（`OpenjdkSourceTracer` の C/C++ 検索・`findCSymbol`・
`tryJdkMember` の native 分岐）は依頼どおり**一切変更していない**。jdk-source 疑似バッファ内の
Shift+K も従来どおり同期の既存フロー（native トレース等）のままで、バインディング解決の対象外。

### JDT との対応表（BindingDefinitionResolver）

`src/dev/javatexteditor/analysis/BindingDefinitionResolver.java`（新設・Swing非依存・ステートレス）。

| Eclipse JDT | 本実装 |
|---|---|
| `ASTParser.setResolveBindings(true)` | `JavacTask.parse()` + `analyze()`（属性付け。現在バッファ + projectRoot 配下の全 `.java` を compilation unit として渡す。⑨ `CompileAnalyzer.analyzeSourceWithProject()` と同じ方式） |
| `NodeFinder.perform(ast, offset, 0)` | `TreePathScanner` によるカーソルオフセットを `[start, end)` に含む最内ノード探索（親→子の走査順により最後に記録されたノードが最内になる） |
| `node.resolveBinding()` (`IBinding`) | `Trees.getElement(TreePath)` |
| `binding.getJavaElement()` → 宣言位置 | `Trees.getPath(Element)` → `SourcePositions.getStartPosition()` + `LineMap` |

結果は sealed interface `Resolution` の3種:
`ProjectLocation(filePath, lineNumber, column, kindLabel)`（プロジェクト内ソース。filePath が
null なら現在の無名バッファ内）／`JdkElementLocation(moduleName, fqcn, memberName)`（ソース外＝
プラットフォームクラスパスの要素。`trees.getPath()` が null になることで判別。memberName が null
ならクラス自体。ネストクラスは src.zip のエントリがトップレベル単位のため最外殻の型まで遡って
FQCN を決め、ネストクラス名・コンストラクタはメンバー名側に倒す）／`NotFound(reason)`。

**javac 利用上の落とし穴（ハマりどころ・2件）**:
1. **`DiagnosticListener` を必ず登録する**こと。javac は DiagnosticListener が登録されている場合
   のみ AST の終了位置テーブルを保持する（`genEndPos`）。登録しないと
   `SourcePositions.getEndPosition()` が常に NOPOS を返し、ノード探索が一切機能しない。
2. **`JavaFileObject` の照合は URI で行う**こと。javac はユーザー提供の `JavaFileObject` を
   `ClientCodeWrapper` でラップするため、`CompilationUnitTree.getSourceFile()` は渡した
   インスタンスと参照一致（`==`）せず、`IdentityHashMap` も機能しない（実装時にこのバグを
   一度作り込み、全件 NotFound になった）。

**安全弁**: `MAX_SOURCE_FILES`（2000）を超えるプロジェクトは解析を断念して NotFound を返す
（作業ディレクトリの既定値はホームディレクトリになりうるため）。ディレクトリ走査は
`FileNameSearcher.SKIP_DIRS` と同じ集合をスキップする（search パッケージへの依存を避けるため
定数は複製。値を変える場合は両方を揃えること）。構文エラー等で javac 内部が例外を出しても
catch して NotFound に変換する（⑧ と同じ graceful degradation）。

### ModalEditor への接続（完全非同期・実行機構の注入）

- **既定は無効**: `ModalEditor` 単体では従来のヒューリスティック経路のみ（既存テスト群が無修正で
  通る根拠）。`enableBindingDefinitionLookup(Consumer<Runnable> backgroundExecutor,
  Consumer<Runnable> uiDispatcher)` で有効化し、実行機構を注入する。
  - 本番（`Main.createLeaf()`）: `Thread.ofVirtual()` 起動 + `SwingUtilities::invokeLater`。
  - テスト: `Runnable::run` ×2（同期実行）で「processKey 直後に同期 assert」の既存契約を維持、
    または `Deque<Runnable>` にタスクを溜めて後から手動実行する擬似非同期で stale ガードを検証。
- **CLAUDE.md「非同期化見送り」判断との関係**: 過去に Shift+K の非同期化は「テストの同期契約を
  壊す」ため見送られたが（CLAUDE.md「追加調査（3回目）」）、今回は AskUserQuestion で
  ユーザーが「完全非同期化」を明示選択した。実行機構注入方式により本番のみ非同期・テストは
  同期のままで両立し、既存テストの書き換えは不要だった。**フォールバック経路（既存
  ヒューリスティック）自体は従来どおり EDT 上で同期実行**され、`withTimeout()` 1500ms の
  既存保護がそのまま効く（非同期化されたのは新設のバインディング解決段のみ）。
- **stale 結果ガード**: 解析要求時に世代カウンタ（`bindingLookupGeneration`）・バッファ参照・
  `buffer.getVersion()`・カーソル位置を捕捉し、`applyBindingResolution()` の冒頭で1つでも
  変わっていたら結果を黙って破棄する（編集・バッファ切替・カーソル移動・モード遷移・
  新しい Shift+K のいずれでも失効。Eclipse がジャンプ要求をキャンセルするのと同じ発想）。
  破棄されるのは「結果の適用」だけで、走り出した解析スレッド自体はキャンセルしない
  （仮想スレッドが完了まで走るが、連打しても世代ガードで結果は最後の1件しか適用されない）。
- **JDK 要素への接続**: `jumpToJdkElement()` が `OpenjdkSourceTracer.readJavaSourceByFqcn(
  moduleName, fqcn)`（`:main` コマンド用に実装済みの文字列ベース版）で src.zip から直接ソースを
  引き、既存の `openJdkSourceBuffer()` + `jumpToMember()` を再利用する。JDK 索引（`jdkIndex`）の
  準備状態に依存しない（バインディング解決は javac が直接プラットフォームクラスパスを見るため、
  索引の構築完了を待つ必要がない）。src.zip が無い環境では false を返してフォールバックする。
- **ジャンプ後の列は 0**（既存 `jumpToSymbolLocation()` の慣例に統一。`ProjectLocation.column`
  は保持しているが現状未使用）。`recordJumpOriginIfMoved()` により Shift+J の復帰も従来どおり動作する。

### テスト

- `test/dev/javatexteditor/analysis/BindingDefinitionResolverTest.java`（10テスト/25アサーション）:
  オーバーロード区別（実引数の型で正しい宣言を選ぶ）・ローカル変数がフィールドをシャドウする
  ブロックスコープ・別ファイル解決・implements 経由のインタフェース default メソッド
  （既存ヒューリスティックでは原理的に不可能だった4象限）、JDK メンバー
  （`List.add` → java.base/java.util.List/add）・JDK クラス・ネスト JDK クラス（`Map.Entry` →
  java.util.Map + Entry）、無名バッファ・構文エラー・空白位置の NotFound。
- `test/dev/javatexteditor/editor/BindingDefinitionJumpTest.java`（8テスト/16アサーション）:
  無効時（既定）は従来の「同名の最初の宣言」へジャンプすること（既存動作の回帰テスト）、
  有効時は正しいオーバーロードへジャンプすること、ファイル跨ぎ + Shift+J 復帰、
  解決不能シンボルのヒューリスティックフォールバック、擬似非同期での遅延適用、
  stale ガード3種（編集・カーソル移動・新しい Shift+K による世代交代）。
- **既知のテストギャップ**: 本番配線（仮想スレッド + invokeLater）そのものは GUI 依存のため
  自動テスト対象外（F10/F11 等と同じ）。src.zip 実在時の `jumpToJdkElement()` の実ジャンプも
  コンテナに src.zip が無いため未検証（⑩⑫と同じ制約）。

### 意図的にスコープ外とした点

- 解析スレッドの明示キャンセル（javac の属性付けは協調キャンセル点を持たないため、
  世代ガードによる「結果の破棄」のみで対処）。
- `ProjectLocation.column` を使った列単位ジャンプ（既存の行単位・列0の慣例を優先）。
- バインディング解決の結果キャッシュ・インクリメンタル解析（毎回フル属性付け。
  MAX_SOURCE_FILES と非同期化で実用上問題ないと判断）。
- jdk-source 疑似バッファ内でのバインディング解決（表示専用ソースであり compilation unit として
  意味解析する対象ではない。native トレース含め既存フローを維持）。

## C言語の Shift+K（2026-07-24 追加、2026-07-25 標準ヘッダ対応を修正・同日ロケール翻訳問題も修正）

C言語（`.c`/`.h`）バッファでは、`lookupJdkDoc()`（K の入口）冒頭で `isCFilePath(currentFilePath) && !inJdkSourceBuffer` を判定し、Java 経路（JDT バインディング解決・ヒューリスティック）とは別の `lookupCDefinition()` へ振り分ける。実体は `dev.javatexteditor.analysis.CDefinitionResolver`（正規表現ベースの ctags 風）。設計判断の詳細・分類優先度・安全装置・テストは CLAUDE.md 「C言語の Shift+K 定義ジャンプ（2026-07-24）」「Windows でも Shift+K が標準ライブラリへジャンプできるようにする修正（2026-07-25）」節を正とする。要点のみ:

- `#include "foo.h"`/`<foo.h>` 行 → そのヘッダを開く（引用符=同ディレクトリ優先→プロジェクト全体、山括弧=プロジェクト全体→標準インクルードディレクトリ）。
- 識別子 → まずプロジェクト内を、関数実装(`{`)＞マクロ(`#define`)＞型(`struct`/`enum`/`union`/`typedef`)＞プロトタイプ(`;`) の順で探す。関数実装を最優先することで「ヘッダの宣言→`.c`の実装」をたどれる。見つからなければ現在のファイルが実際に `#include` しているヘッダ（そこから辿れるヘッダも含む）を幅優先で探索し、標準ライブラリの宣言（`printf`/`NULL`/`size_t` 等）にも対応する。
- **標準インクルードディレクトリは OS別パスのハードコードではなく、実際にインストールされている C コンパイラ（gcc→clang→cc）に `<compiler> -E -v` で問い合わせて動的に検出する**（Windows の MinGW-w64/MSYS2 でも Linux の glibc でも、その環境の実際のツールチェーンを反映するため正しく動く）。検出結果は JVM 内で1回だけキャッシュする。標準ヘッダ探索は「現在のファイルが実際に #include しているヘッダとその先」だけに限定し、標準インクルードディレクトリ全体を総当たりしない（全走査すると無関係な大量ライブラリのコメントからの誤検出・数秒級の遅延という2つの実害が実機検証で判明したため）。
- コメント（`/* ... */`・`//`）はマッチング前に除去する（`stripComments`）。除去しないと、コメント中に偶然シンボル名が現れるだけで誤って定義行と判定されてしまう。
- **`parseIncludeSearchPaths()` は英語見出し文字列（`search starts here`等）に依存しない**。gcc は診断メッセージ全体をロケールに応じて翻訳することがあり（日本語Windows実機で確認済み）、翻訳された環境では英語文字列マッチングが常に不発になる。代わりに「半角スペース1個＋絶対パス」というgcc本体が直接生成する固定レイアウトの構造で判定する（ロケールに依存しない）。サブプロセス起動時に`LC_ALL=C`/`LANG=C`も設定するが、それだけに頼らず構造的検出を主とする。
- `withTimeout()`（1500ms）で全走査を打ち切り、`recordJumpOriginIfMoved()` で Shift+J 復帰元を記録（Java の K と共通機構）。Java 経路は無変更。
