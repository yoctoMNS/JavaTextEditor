---
name: java-auto-formatter
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、.javaファイル保存時に発火するメンバー自動並び替え（フィールド/コンストラクタ/メソッド/ネスト型の規約順ソート）を設計・実装・修正する際に使用する。「保存時にJavaのメンバーを並び替えたい」「フィールドをstatic->instanceの順にしたい」「オーバーロードメソッドをまとめたい」「equals/hashCode/toStringを最下部にまとめたい」「jdk-source疑似バッファやJDK標準パッケージを誤って並び替えてしまう」「recordのコンパクトコンストラクタの扱い」といった相談、またdev.javatexteditor.format.JavaMemberFormatter/JavaAutoFormatGuardやModalEditorのapplyJavaAutoFormatIfEligible周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# Skill: java-auto-formatter

## 概要

`.java` ファイルを `:w` 等で保存する直前に、CLAUDE.md 記載の規約（static→instanceフィールド、可視性順、コンストラクタ引数なし優先、オーバーロードのクラスタリング、Object override（equals/hashCode/toString/clone）の最下部集約、ネスト型への再帰適用等）に従ってクラス/インタフェース/enum/recordのメンバーを並び替える機能。

## 実装済みクラス

```
src/dev/javatexteditor/format/
├── DeclKind.java              CLASS/INTERFACE/ENUM/RECORD/OTHER（アノテーション型等は対象外）
├── MemberCategory.java        メンバーの分類（フィールド/コンストラクタ/メソッド種別/ネスト型 等）
├── MemberBlock.java           1メンバー分の「隙間+本文テキスト」とソートキーを保持する値ホルダ
├── JavaMemberFormatter.java   本体。format(String) -> String（null=無変更/対象外/失敗）
└── JavaAutoFormatGuard.java   保存時の発動可否を判定するフェイルセーフ（isEligible）
```

呼び出し元: `ModalEditor.saveToFile()` 内 `applyJavaAutoFormatIfEligible()`（`Files.writeString()` の直前、バイナリ保存分岐の外側）。ガードを通過し実際に並び替えが発生した場合のみ `buffer.delete()+insert()` でバッファ自体も書き換え、`clampCursorAfterUndoRedo()` でカーソル位置を補正する（undo で元に戻せる）。

## 解析方針（アプローチAのハイブリッド）

波括弧を自前でカウントするLexerは採用していない（文字列リテラル・テキストブロック・アノテーション引数の `{}` を誤認識するリスクがあるため）。かといって純粋なAST再生成（プリティプリント）は改行・インデント・コメント位置を破壊する。

採用したのは以下のハイブリッド方式:
- **メンバーの境界特定**: `javax.tools.JavaCompiler` の parse-only タスク（`dev.javatexteditor.analysis.SourceAnalyzer` と同じ手法、`-proc:none` + `JavacTask.parse()`）で得た `com.sun.source.tree.*` のASTから、`SourcePositions.getStartPosition`/`getEndPosition` で文字オフセットを取得する。
- **実際のテキスト操作**: そのオフセットを使った「原文の部分文字列をそのまま入れ替える」だけ。ノードの再生成は一切行わない。

`SourcePositions.getEndPosition()` は `DiagnosticCollector` を `compiler.getTask()` に渡さないと常に `NOPOS` を返す既知の挙動（`dev.javatexteditor.analysis.BindingDefinitionResolver` と同じ注意点）があるため、必ず `DiagnosticCollector` を渡すこと。

## コメント・Javadoc・アノテーションの扱い

各メンバーの「直前メンバー終端 〜 自分の開始位置」の間のテキスト（空行・コメント・Javadocを含む）を、そのメンバー自身に不可分な接頭辞（`leadingGap`）として保持する。並び替え時は `leadingGap + ownText` を1ブロックとして丸ごと移動させるため、コメント・Javadocは常に元々付いていたメンバーと一緒に移動する。

同一行末コメント（`int x; // ...`）は、隙間の1行目に空白以外の内容があれば前方のメンバーへ回収してから隙間を分割する（`splitTrailingComment`）。これを怠ると、次のメンバーへ誤って付け替わる。

アノテーションは `ModifiersTree` の開始位置を使い、メンバー本体より前にあれば実質的な開始位置として採用する（`effectiveStart`）。

## クラス本体の開き `{` の特定（注釈の配列引数を誤認識しない）

`findBodyOpenBrace` は、クラスの全注釈の終端位置より後ろだけを検索範囲にして最初の `{` を探す。extends/implements節は型参照のみで `{}` を含み得ないため、これで確実にクラス本体の開き括弧そのものを取得できる（`@Anno(value={1,2,3}) class Foo {` のような注釈引数中の配列初期化子の `{` と誤認識しない）。

**重要**: この `{` の位置は、後述の「先頭メンバーの隙間追従」と「recordのcomponent除外」の両方の土台になっている。

## 設計判断ログ（実装中に発見した2つのバグと修正）

### 1. 先頭メンバーが並び替えで先頭でなくなる際の隙間の消失（2026-08-13）

最初の実装では「開き `{` 〜 元々の先頭メンバー」の隙間を丸ごと `prefix`（常に最初に出力される固定テキスト）に含め、先頭メンバー自身の `leadingGap` を空文字列にしていた。これは「元々先頭だったメンバーが並び替え後も先頭でいる」という誤った前提に依存しており、実際に並び替えでそのメンバーが2番目以降に移動すると、直前のメンバーとの間に何の区切りも無くテキストが連結される不具合があった（例: `public static int s;void bar() {}` のように改行が消える）。

修正: `findBodyOpenBrace` で開き `{` の位置を先に求め、「class宣言〜開き{」までの不変の `header` と、「開き{〜先頭メンバー開始」の `headGap` を分離。`headGap` は先頭メンバー自身の `leadingGap` として扱い、並び替え後にそのメンバーがどこに移動しても追従するようにした。

### 2. recordのheader component が本文メンバーとして誤混入する（2026-08-13）

`ClassTree.getMembers()` は、record の場合ヘッダーの record component（例: `record Point(int x, int y)` の `x`, `y`）を暗黙の `VariableTree` として含む。その開始位置は本文（`{` より後ろ）ではなく、ヘッダー側（`{` より前）を指す。これをフィルタせずに他の本文メンバーと同列に扱うと、「メンバーは常にクラス本体 `{`〜`}` の中にある」という本実装全体の前提が崩れ、`findBodyOpenBrace` の検索範囲計算や `prefix`/`suffix` の切り出しが全体的に破綻する（保存しても何も変化しない、または内容が欠落する）。

修正: `findBodyOpenBrace` を「先頭メンバーの位置」に依存せず独立に（`classStart`〜`classEnd` 全体を検索範囲にして）先に計算するよう順序を入れ替え、その後 `ct.getMembers()` を「開始位置が開き `{` より後ろのものだけ」にフィルタしてから本文メンバーとして扱うようにした。この修正は record 以外の宣言種別にも一律に適用しており、副作用はない（他の宣言種別で `{` より前に位置するメンバーが紛れ込むケースは無いため）。

いずれも `test/dev/javatexteditor/format/JavaMemberFormatterTest.java` に回帰テストを追加済み（「先頭メンバーが移動しても隙間が正しく追従する」「recordコンポーネントは本文メンバーとして扱われない」）。

## enum定数の扱い（絶対に相互の順序を変えない）

enum定数は `ordinal()` の意味に直結するため、比較器（`comparator`）は `category == ENUM_CONSTANT` の場合、tierが等しければ即座に `0` を返し、`List.sort` の安定性に完全に委ねている。enum定数の直後にある `;`（定数とそれ以降のメンバーの区切り）は、後続メンバーがどれだけ並び替わっても「定数ブロックのすぐ後ろ」に固定する特別処理（`enumSeparator`）を入れている。

## 保存時のフェイルセーフ（`JavaAutoFormatGuard`）

`ModalEditor.saveToFile()` 内、`Files.writeString()` の直前で以下をすべて満たす場合のみ発動する:

1. `inJdkSourceBuffer == false`（jdk-source疑似バッファ = Shift+Kジャンプ先を除外）
2. `currentFilePath` が `null` でなく `*` で始まらない（`*compile*` 等の疑似バッファを一律除外）
3. 拡張子が `.java`（大文字小文字を無視。`LiveDiagnostics.isJavaBuffer()` と同じ規約）
4. 保存先パスに `src.zip` / `openjdk-native` を含まない
5. 保存先の実パスが `java.home`（JDKインストールディレクトリ）配下でない
6. 既存ファイルなら書き込み可能

さらに `JavaMemberFormatter.format()` 自身が、parse後の `CompilationUnitTree.getPackageName()` が `java.`/`javax.`/`jdk.`/`sun.` で始まっていたら無条件に `null`（無変更）を返す二重チェックを持つ（パスベースの判定をすり抜けても、AST由来の確実な情報で最終防衛する設計）。構文エラーがあるソースも同様に無変更で返す。

## スコープ外にした項目（意図的な判断）

- **Step-downルール**（呼び出し元メソッドの直下に呼び出し先privateメソッドを配置）: 呼び出しグラフ解析が必要で費用対効果が低いため未実装。代わりに、同名メソッド（オーバーロード）の初出順を維持しつつクラスタリングすることで、疑似的に近い効果を得ている。
- **recordの正規コンストラクタとカスタムコンストラクタの判別**: compact constructor の判定（`isCompactRecordConstructor`。コンストラクタ自身のパラメータの開始位置がレコードヘッダー側=自身の開始位置より前を指すかどうかで判定する、純粋に位置情報だけの安全な方法）のみ行い、それ以外（canonical vs custom）は元の順序を維持する。record component の情報（型・個数）が parse-only では取得困難で、誤った判定基準（引数の少なさ等）を使うと正規/カスタムを逆に並べてしまうリスクがあるため。
- **interfaceのObject override最下部集約**: CLAUDE.md の規約が明示的に class（と暗黙的にenum）にしか言及していないため、interfaceには適用していない。

## テスト

```
test/dev/javatexteditor/format/JavaMemberFormatterTest.java   22/22 PASS
test/dev/javatexteditor/format/JavaAutoFormatGuardTest.java   10/10 PASS
```

`JavaMemberFormatterTest` は class/interface/enum/record それぞれの並び順、可視性順、オーバーロードのクラスタリング、Object override集約、静的/インスタンス初期化ブロック、Javadoc/コメント/アノテーションの追従、文字列リテラル中の `{}` の非破壊、ネスト型再帰、enum定数の順序保持、JDKパッケージ/構文エラー時の無変更、冪等性、ヘッダー部分の非破壊を検証する。
