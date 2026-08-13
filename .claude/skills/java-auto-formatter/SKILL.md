---
name: java-auto-formatter
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、.javaファイル保存時に発火するメンバー自動並び替え（フィールド/コンストラクタ/メソッド/ネスト型の規約順ソート）を設計・実装・修正する際に使用する。「保存時にJavaのメンバーを並び替えたい」「フィールドをstatic->instanceの順にしたい」「メソッドをコールグラフでStep-down配置したい」「equals/hashCode/toStringを最下部にまとめたい」「jdk-source疑似バッファやJDK標準パッケージを誤って並び替えてしまう」「recordのコンパクトコンストラクタの扱い」「保存フックのメモリ使用量・GC負担を抑えたい」といった相談、またdev.javatexteditor.format.JavaMemberFormatter/SourceLexer/MethodCallGraphSorter/JavaAutoFormatGuardやModalEditorのapplyJavaAutoFormatIfEligible周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# Skill: java-auto-formatter

## 概要

`.java` ファイルを `:w` 等で保存する直前に、CLAUDE.md 記載の規約に従ってclass/interface/enum/recordのメンバーを並び替える機能。

**2026-08-13 に設計を全面変更**: 当初は Compiler Tree API（`javax.tools.JavaCompiler`）ベースの実装だったが、「保存フックとして極限まで高速・低メモリに動作し、GC負担を最小化する」という要件のもと、**手組みの軽量Lexer + 正規表現分類 + 文字列検索ベースの軽量コールグラフ**による完全ステートレスな実装に置き換えた（ユーザーの明示的指示・確認済み。旧設計との経緯は本ファイル末尾の「設計変更の経緯」参照）。

## 実装済みクラス

```
src/dev/javatexteditor/format/
├── MemberKind.java             メンバーの分類（FIELD/INIT_BLOCK/CONSTRUCTOR/METHOD/NESTED_TYPE/ENUM_CONSTANTS/OTHER）
├── MemberSlice.java            1メンバー分の [start,end) インデックスと軽量メタデータのみを持つrecord
├── SourceLexer.java            状態遷移だけで動く波括弧深さトラッカー（AST/トークン列を一切構築しない）
├── MethodCallGraphSorter.java  メソッド名出現ベースの軽量コールグラフ + Step-down DFS + アルファベット順ソート
├── JavaMemberFormatter.java    本体。format(String) -> String（null=無変更/対象外/失敗）
└── JavaAutoFormatGuard.java    保存時の発動可否を判定するフェイルセーフ（isEligible。旧設計から変更なし）
```

呼び出し元: `ModalEditor.saveToFile()` 内 `applyJavaAutoFormatIfEligible()`（`Files.writeString()` の直前）。

## 【最重要】完全なステートレス設計（メモリリーク防止の絶対条件）

- `JavaMemberFormatter`/`SourceLexer`/`MethodCallGraphSorter`/`JavaAutoFormatGuard` は **static フィールドを一切持たない**。全メソッドが static かつ引数と戻り値だけで完結する純粋関数として実装されている。
- Lexerの解析結果（`SourceLexer.BodyRegion`、`MemberSlice[]`）・コールグラフの隣接リスト・ソート結果は、すべて `formatClass()`/`sort()` メソッド内の**ローカル変数**（`List`/`int[]`/`Map`）としてのみ存在する。メソッドを抜けた瞬間に参照が切れ、即座にGC対象になる。
- 100個以上のファイルを同時に開いていても、非アクティブなバッファに対してフォーマッタ由来のデータが残り続けることは無い（そもそも保存アクションが実行された単一バッファのテキストに対してしか呼ばれず、呼び出しの度に完全に使い捨てのローカルオブジェクトだけを生成する）。
- `com.sun.source.tree.*`・`javax.tools.JavaCompiler` は一切使用しない（javacの内部AST/シンボル表を保持するコストを避けるため）。

## 解析アプローチ（手組み軽量Lexer）

### メンバースライス抽出（`SourceLexer.sliceClassBody`）

文字列の先頭から1文字ずつ状態遷移で読み進め、以下を安全に読み飛ばす:
- 文字列リテラル（`"..."`）・テキストブロック（`"""..."""`）・文字リテラル（`'.'`）
- 行コメント（`//...`）・ブロックコメント（`/*...*/`）

波括弧 `{ }` のネスト深さを追跡し、クラス直下（開き{直後、深さ1）にある各メンバーの `[開始, 終了)` インデックスだけを返す。ASTやトークン列など重い中間構造は一切構築しない。

**2つの追加ヒューリスティック**（誤認識を防ぐための工夫）:
1. **丸括弧の深さ0の位置だけを対象にする**: 注釈引数中の配列初期化子（`@Foo(x={1,2,3})`）の `{` を本体の開始と誤認識しないため、丸括弧の深さを同時に追跡し、深さ0の `{`/`}` だけを対象にする。
2. **直前の非空白文字による「本体ブレース」判定**: フィールドの配列初期化子（`int[] a = {1,2,3};`）の `{` を本体の開始と誤認識しないよう、`{` の直前の非空白文字が `= , { ]` のいずれかであれば「本体を持たないブレース」と判定し、対応する `}` が来てもメンバー境界を確定させない（境界はその後の `;` まで継続する）。

各メンバーのスライス境界は「直前メンバーの終端 〜 自分の終端」で定義されるため、**先頭の空白・コメント・Javadocは自動的にそのメンバー自身の一部として一体化される**（Tree API版のような leadingGap/ownText の分離が不要になった）。並び替えはスライス単位で丸ごと入れ替えるだけなので、コメント・Javadocは常に元々付いていたメンバーと一緒に移動する。

同一行末コメント（`int x; // ...`）は、隣接スライスの先頭行に空白以外の内容があるかを見て、あれば前のメンバー側へ回収してから境界を引き直す（`JavaMemberFormatter.formatClass` 内の同一行末コメント回収ループ）。

### メンバーの分類（正規表現）

切り出した各スライスの先頭部分（Lexerと同じ丸括弧深さ追跡で `{`/`;` の位置を特定した「ヘッダーテキスト」）を正規表現で分類する:
- `MODIFIER_PREFIX`: 修飾子・注釈の連続を先頭から貪欲に消費し、残り（`rest`）を得る。**可視性判定はこの `modPrefix` 部分文字列だけに対して行う**ため、フィールド初期化子の文字列リテラル中に `"static config"` のような語があっても誤検出しない。
- `NESTED_TYPE_PATTERN` / コンストラクタ判定（`rest` が `className` で始まるか） / `NAME_BEFORE_PAREN`（メソッド名抽出）の順に判定し、どれにも当てはまらなければ `FIELD` にフォールバックする。

### コンストラクタの判定（record のコンパクト/正規コンストラクタ含む）

- 通常のコンストラクタ: `rest` が `className(` で始まる。
- record のコンパクトコンストラクタ: `rest` が `className` そのもので、丸括弧が無く直接 `{` に続く（`public Point { ... }`）。

### enum定数

enumの**先頭スライスを無条件に `ENUM_CONSTANTS` として扱い、内部を一切分解・並び替えない**（列挙子はカンマ区切りで1つのセミコロン終端スライスとして自然にひとかたまりになるため、Tree API版で必要だった「ordinal保護のための特別な比較器」が不要になった）。列挙子ごとに固有クラス本体を持つ高度な構文（`RED { void foo(){} }, ...`）は非対応（スコープ外、下記参照）。

### record header component の除外

`record Point(int x, int y) { ... }` のヘッダー成分 `x, y` は、Lexerの「クラス本体の開き{」より前に位置するため、`sliceClassBody` が本体の探索を開き{より後ろに限定することで自動的に除外される（Tree API版で発生した「`ClassTree.getMembers()` が record component を暗黙のフィールドとして含んでしまう」問題は、この設計では構造的に発生しない）。

## メソッドの並び替え（Step-down DFS + アルファベット順、`MethodCallGraphSorter`）

1. **オーバーロード統合**: 同名メソッドは1グループとして扱う。最終出力では常に連続配置し、グループ内は引数の少ない順（同数なら元の出現順）。
2. **除外対象**: `equals`/`hashCode`/`toString`/`clone` は通常のコールグラフ解析から除外し、無条件でメソッド群の最後に配置する（順序はグループ内アルファベット順）。
3. **軽量コールグラフ**: 各メソッド名について、他の全メソッド名が自インスタンスへの呼び出しとしてボディテキストに出現するかを正規表現でチェックし、隣接リスト（`Map<String, Set<String>>`）を構築する。型解決は一切行わない（同名の無関係なローカル変数・別クラスのメソッド呼び出しと誤認識するリスクはあるが、意図的な軽量トレードオフ）。
   - **呼び出し判定パターン（`MethodCallGraphSorter.callPattern`）**: 単なる `\bNAME\b` では `obj.methodName()`（別インスタンス経由）や `int methodName = 0;`（同名変数）まで誤って「呼び出し」と検出してしまう不具合が2026-08-13に見つかり修正済み。現在のパターンは
     `(?:(?<=\bthis\.)|(?<!\.))\bNAME\s*\(` で、(a) 名前の直後（空白許容）に `(` が続くことを要求して変数アクセスと区別し、(b) 後読みで「直前が `this.`」または「直前が `.` でない」の場合だけを許容することで、暗黙のthis呼び出し（`methodName(`）と明示的な `this.methodName(` だけを対象にし、`obj.methodName(`/`super.methodName(` を除外する。
4. **ルート決定**: 「他のどのメソッドからも名前が出現しないメソッド」**または**「public/protectedなメソッド」をルートとし、アルファベット順にソートする。この2条件は独立した`OR`条件のため、「一度も呼ばれないprivateメソッド」も単独でルートになり得る（呼び出し元のpublicメソッドより名前がアルファベット順で早ければ、そちらが先に出力される。これは仕様通りの挙動）。
5. **Step-down DFS**: ルートから順に、配置→そのボディに出現した未配置の呼び出し先をアルファベット順にソート→再帰的に下へ配置、を繰り返す。
6. **孤立メソッドの回収**: DFS完了後、配置されなかったメソッドをアルファベット順にソートして追記する（equals等よりは前）。

## 他の要素の並び替え順序

- **フィールド**: 静的 → インスタンスの順。各グループ内は可視性順（public → protected → package-private → private）。
- **初期化ブロック**: static → instance。
- **コンストラクタ**: 引数なし → 引数ありの順（record以外）。record は compact/canonical を先頭、それ以外は元の順序を維持（下記スコープ外参照）。
- **ネストされた型**: 元の相対順序を維持したまま、再帰的に同じ規則を適用する。
- **interface**: フィールドは修飾子の有無によらず暗黙の `public static` として扱う（Java言語仕様通り）。

## 保存時のフェイルセーフ（`JavaAutoFormatGuard`。旧設計から変更なし）

`ModalEditor.saveToFile()` 内、`Files.writeString()` の直前で以下をすべて満たす場合のみ発動する:

1. `inJdkSourceBuffer == false`（jdk-source疑似バッファ = Shift+Kジャンプ先を除外）
2. `currentFilePath` が `null` でなく `*` で始まらない（`*compile*` 等の疑似バッファを一律除外）
3. 拡張子が `.java`（大文字小文字を無視）
4. 保存先パスに `src.zip` / `openjdk-native` を含まない
5. 保存先の実パスが `java.home`（JDKインストールディレクトリ）配下でない
6. 既存ファイルなら書き込み可能

さらに `JavaMemberFormatter.format()` 自身が、正規表現で抽出した `package` 宣言が `java.`/`javax.`/`jdk.`/`sun.` で始まっていたら無条件に `null`（無変更）を返す二重チェックを持つ。波括弧の深さが最後まで0に戻らない（構文エラー等で不均衡な）場合も `SourceLexer.sliceClassBody` が `null` を返し、安全側で無変更となる。例外・`StackOverflowError` は `format()` の呼び出し口で握りつぶし、保存処理自体を絶対に壊さない。

## スコープ外にした項目（意図的な判断）

- **型解決を伴う正確なコールグラフ**: `MethodCallGraphSorter` は正規表現の文字列マッチのみで「呼んでいるらしい」を判定する。`obj.methodName(`/`super.methodName(`/同名変数へのアクセスは2026-08-13の修正で除外済みだが、**同名の別クラスのインスタンスに対する `this` 経由の疑似呼び出し**（型解決が無いため区別不能）や、コメント・文字列リテラル内に偶然 `this.methodName(` という文字列が含まれる場合の誤検出は原理的に防げない。速度・メモリ優先のトレードオフとして許容する。
- **一度も呼ばれないprivateメソッドが単独でルートになる**: 前述の通り、仕様の `OR` 条件をそのまま実装した結果。呼び出し元より先に出力されることがある。
- **recordの正規コンストラクタとカスタムコンストラクタの判別**: compact constructor の判定のみ行い、それ以外（canonical vs custom）は元の順序を維持する。record component の型・個数の情報がLexerベースでは取得困難なため。
- **enum定数ごとの固有クラス本体**（`RED { void foo(){} }, GREEN, ...`）: 非対応。全体が1つのスライスとして扱われるため内容は保持されるが、内部の並び替えは行わない。
- **interfaceのObject override最下部集約**: CLAUDE.mdの規約がclass（と暗黙的にenum）にしか言及していないため未適用。

## 設計変更の経緯（2026-08-13）

初版はCompiler Tree APIベースだったが、「保存フックとして極限まで高速・低メモリに動作し、巨大なASTをメモリに保持しない」という新たな絶対要件が提示され、ユーザーに確認の上（既存Skillとの矛盾を明示して承認を得た）、本ファイル記載の手組みLexerベースの実装に全面置き換えた。Tree API版で発生した2つのバグ（先頭メンバーの隙間追従・record header componentの誤混入）は、スライス境界の定義変更（各スライスが自分の直前の隙間を自然に内包する設計）とLexerの探索範囲限定（開き{より後ろだけを対象にする設計）により、新設計では構造的に再発しない。

## テスト

```
test/dev/javatexteditor/format/JavaMemberFormatterTest.java   28/28 PASS
test/dev/javatexteditor/format/JavaAutoFormatGuardTest.java   10/10 PASS
```
