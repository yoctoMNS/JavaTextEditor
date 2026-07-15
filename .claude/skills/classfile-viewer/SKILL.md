---
name: classfile-viewer
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、JVMバイトコードを含む.classファイルを開いた際の構造ビュー表示と、:nimoコマンドによるニーモニック（javap -c風）バイトコード逆アセンブル表示を設計・実装する際に使用する。「.classファイルを開くと文字化けする」「クラスファイルの構造を仕様通りに表示したい」「バイトコードをニーモニックで見たい」「定数プールを解析したい」といった相談、またdev.javatexteditor.classfileパッケージやModalEditorのreadFileContentForBuffer/:nimoコマンド周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# .classファイルビューア（構造ビュー・`:nimo`ニーモニックビュー）

## このスキルが解決すること

`.class`ファイル（JVMバイトコードを含む）を`:e`・FILER・telescope・`\f`/`\g`/`gr`・Ctrl+U/Ctrl+P
など、既存のファイルを開くあらゆる経路で開いた際に、既存の「バイナリはhexdump」扱いにせず、
JVM仕様（The Java Virtual Machine Specification, Chapter 4）通りの構造を文字化けせず読める形式
（構造ビュー）で表示する。さらに構造ビュー表示中に`:nimo`コマンドを実行すると、各メソッドの
バイトコードをニーモニック（`javap -c`風の命令列テキスト）に逆アセンブルしたビューに切り替える。

## 実装アーキテクチャ

### パッケージ構成（`dev.javatexteditor.classfile`、Swing非依存の純粋ロジック）

- `ConstantPoolEntry`: sealed interfaceで定数プールの全17種のタグ（Utf8/Integer/Float/Long/Double/
  Class/String/Fieldref/Methodref/InterfaceMethodref/NameAndType/MethodHandle/MethodType/Dynamic/
  InvokeDynamic/Module/Package）+ Long/Double用の`LongDoublePlaceholder`をrecordとして表現する。
  同一ファイル内にネストしたrecordのみのため`permits`節は省略（Java 21のsealed仕様）。
- `AttributeInfo`/`MemberInfo`: attribute_info（§4.7）・field_info/method_info（§4.5/4.6、
  構造が同一のため共用）をそのまま表すrecord。`AttributeInfo.info()`は未解釈の生バイト列。
- `ClassFile`: ClassFile構造（§4.1）全体のrecord。`utf8At`/`classNameAt`/`describeConstant`で
  定数プール参照を解決するヘルパーを持つ。**`constantPool`のインデックス0とLong/Double直後の
  スロットは仕様上null/プレースホルダーになるため`List.of`ではなく`Arrays.asList`で保持する**
  （`List.of`はnull要素を許さずNPEになるため、これは`ClassFileParser`実装時に一度踏んだ落とし穴）。
- `ByteReader`（package-private）: u1/u2/u4/u8読み取り + Modified UTF-8デコード（`java.io.DataInput`
  と同じアルゴリズム）専用の下請け。範囲外読み取りは`ClassFileFormatException`（checked）を送出する。
- `ClassFileParser.parse(byte[])`: magicチェックからconstant pool・access flags・this/super class・
  interfaces・fields・methods・attributesまで一括パースする。**壊れた`.class`（マジック不一致・
  途中で切れている・未知のタグ）は必ず`ClassFileFormatException`を送出し、呼び出し側
  （`ModalEditor.readFileContentForBuffer`）が既存のhexdumpバイナリプレビューにフォールバック
  できるようにする**（graceful degradation。⑨javac-compile-integration等、既存スキルの方針を踏襲）。
- `AccessFlags`: クラス/フィールド/メソッドそれぞれのaccess_flagsビット→文字列リスト変換
  （`ACC_SUPER`と`ACC_SYNCHRONIZED`が同じ0x0020のように、文脈依存でビット意味が変わる点に注意）。
- `Opcodes`: opcode 0〜201（標準命令）+ 202/254/255（breakpoint/impdep1/impdep2、JVM実装内部用の
  予約命令）のニーモニック名テーブル。`mnemonic(int)`は未定義のopcodeに`null`を返す。
- `CodeAttribute`: Code属性（§4.7.3）のパース（max_stack/max_locals/code/exception_table/attributes）。
- `BytecodeDisassembler.disassemble(byte[] code, ClassFile cf)`: Code属性の`code`バイト列を
  `Instruction(offset, text)`のリストへ逆アセンブルする。分岐命令（`if*`/`goto`/`jsr`/`goto_w`/`jsr_w`/
  `ifnull`/`ifnonnull`）は相対オフセットを命令開始位置からの**絶対オフセット**に解決して表示する。
  `tableswitch`/`lookupswitch`は4バイト境界へのパディング（**メソッド全体のcode配列先頭からの
  絶対位置**基準。命令ごとの相対位置ではない）を経て可変長オペランドを読む。`wide`は次の1バイトで
  修飾対象命令を判定し、`iinc`のみ定数もu2幅で読む点が他の`*load`/`*store`/`ret`と異なる。
  定数プール参照を伴うopcode（`ldc`系・`getstatic`等・`invoke*`・`new`/`anewarray`/`checkcast`/
  `instanceof`/`multianewarray`）は`ClassFile.describeConstant()`で`"  // Method java/lang/Object.\"<init>\":()V"`
  のようなjavap風コメントを付与する（**この関数だけはパッケージ名を"/"区切りのまま**返す。
  単独のClassEntryを表示する`describeConstant`のClass分岐は`"/"→"."`変換するが、
  Methodref等の`refDescription`経由では変換しない`classNameAt`を使うため、混同しないこと）。
- `ClassFileFormatter.format(ClassFile, String fileName)`: `.class`を開いた際のデフォルト表示
  （構造ビュー）。magic/version/access flags/this_class/super_class/interfaces/fields/methods/
  attributes/constant pool全件を、javap -vに近い情報量で出す。**バイトコードの命令列そのものは
  ここでは出さず**、Code属性は`stack=.., locals=.., code_length=.. bytes`の1行サマリのみ表示する
  （命令列の全文表示は`:nimo`専用、という役割分担を明確に分ける）。
- `MnemonicFormatter.format(ClassFile, String fileName)`: `:nimo`コマンドの出力本体。全メソッドに
  ついて`BytecodeDisassembler`で逆アセンブルした命令列を並べる。Code属性を持たないメソッド
  （abstract/native）は`"(Codeなし — abstract/nativeメソッド)"`とだけ表示する。

### `ModalEditor`側の結線

- **`readFileContentForBuffer(Path)`の`FileLoadResult`にフィールドを追加**した
  （`record FileLoadResult(String text, boolean binary, byte[] classFileBytes, String classFileDisplayName)`）。
  読み込んだバイト列の先頭4バイトが`.class`マジックナンバー(`0xCAFEBABE`)と一致する場合のみ
  `ClassFileParser.parse()`を試み、成功すれば`ClassFileFormatter.format()`の結果を`text`に、
  生バイト列を`classFileBytes`に格納して返す（`binary()`は`true`。既存のバイナリ判定と同じ
  「`currentFilePath`はnullのまま＝保存不可」パターンにそのまま乗せるため）。
  **パースに失敗した場合（壊れた`.class`）は例外を握りつぶして既存の`BinaryFileDetector`/
  `HexDumpFormatter`によるhexdumpフォールバックへ素通りする**（この一本の関数が全てのファイルを
  開く経路の共通入口のため、ここでの判定だけで済む。この関数を呼ぶ5箇所の一覧は
  binary-file-open周りのCLAUDE.md該当節を参照）。
- **`:nimo`コマンド用の状態は「参照一致による自動失効」パターンを踏襲**した
  （`classFileBytes`/`classFileName`/`classFileBufferOwner`の3フィールド。F10/F11の
  `outputErrorLinesOwner`と全く同じ設計: `buffer`は約25箇所で`buffer = new UndoablePieceTable(...)`
  と再代入されるため、逐一クリアするより「`classFileBufferOwner == buffer`のときだけ`:nimo`が
  有効」という参照ベースの自動失効の方が取りこぼしがない）。
  `trackClassFileBuffer(FileLoadResult)`が`readFileContentForBuffer`を呼ぶ全5箇所
  （`loadFromFile`/`openTelescopeSelection`/`switchToRelativeBuffer`/`jumpToFileNameResult`/
  `jumpToGrepResult`）で`buffer = new UndoablePieceTable(result.text())`の直後に呼ばれ、
  `.class`検出時は`classFileBufferOwner = buffer`（今読み込んだ新しいbuffer参照）を記録し、
  それ以外（テキスト・hexdumpバイナリ）ではフィールドをすべて`null`に戻す。
- **`showClassFileMnemonic()`（`:nimo`本体）**: `classFileBufferOwner != buffer`（＝別バッファへ
  切り替え済み）または`classFileBytes == null`（＝そもそも`.class`を見ていない）なら
  `"E: not viewing a .class file"`を表示するだけで何もしない。それ以外は`classFileBytes`を
  再度`ClassFileParser.parse()`し、`MnemonicFormatter.format()`の結果で`buffer`を差し替える
  （`*compile*`/`*run*`と同じ「`pushBuffer()`を呼ばず直接`buffer`を差し替える」疑似バッファ
  パターン）。**mnemonicビューへ切り替えた後も`classFileBufferOwner = buffer`（新しいbuffer参照）
  を再設定する**ことで、mnemonicビュー表示中にもう一度`:nimo`を押せば同じ内容が再表示されるだけ、
  という一貫した挙動になる（構造ビューからmnemonicビューへの一方向切り替えのみで、`:nimo`に
  「戻る」機能は無い。元の構造ビューに戻りたい場合は再度ファイルを開き直す）。

## 意図的にスコープ外とした点

- **バイトコードの書き換え・再アセンブルは実装していない**。読み取り専用ビューア（binary-file-open
  スキルのhexdumpプレビューと同じ位置づけ）であり、`currentFilePath`は常にnullのため`:w`は
  既存の「no file name」エラーに自然にフォールバックする。
- **属性の完全解析はしていない**。構造ビューで内容まで解決するのは`Code`（サマリのみ）・
  `ConstantValue`・`SourceFile`の3種のみで、それ以外（`LineNumberTable`/`LocalVariableTable`/
  `StackMapTable`/`BootstrapMethods`/`Signature`/annotations系等）は`"<名前> (Nバイト)"`という
  汎用の1行サマリに留める。行番号対応表・ローカル変数名表示・注釈内容の表示は将来の拡張候補。
- **`InvokeDynamic`/`Dynamic`のブートストラップメソッド本体（`BootstrapMethods`属性）の解決は
  していない**。`describeConstant`はbootstrap_method_attr_indexを番号のまま表示するに留まり、
  実際にどのメソッドハンドルが使われるかまでは追わない（ラムダ式のinvokedynamic逆アセンブルは
  番号+name:descriptorまでの表示になる）。
- **モジュール情報（`module-info.class`）の`Module`/`ModulePackages`/`ModuleMainClass`等の
  専用属性デコードはしていない**。`ConstantPoolEntry.ModuleEntry`/`PackageEntry`のタグ自体は
  読めるため`module-info.class`もクラッシュせず構造ビューは表示できるが、モジュール宣言
  （requires/exports/opens等）の内容までは解析しない。

## テスト

`test/dev/javatexteditor/classfile/`配下（自作mainハーネス方式）。
- `TestClassBytes`（`*Test.java`以外の命名でtest.shの自動実行対象外）: `javax.tools.JavaCompiler`
  で実際にJavaソースをコンパイルし本物の`.class`バイト列を用意する下請け（`ProjectBuilder`と同じ
  API利用パターン）。手書きのバイト列フィクスチャより確実に仕様通りの入力を検証できる。
- `ClassFileParserTest`: magic/version/this_class/super_class/fields/methods解決、壊れた
  バイト列（マジック不一致・途中切断）で`ClassFileFormatException`が送出されることを検証。
- `BytecodeDisassemblerTest`: 実際にコンパイルしたメソッド（コンストラクタ・`add`・`main`）の
  ニーモニックに`aload_0`/`invokespecial`/`iadd`/`getstatic`等が含まれること、手組みのバイト列で
  `ifeq`分岐・`tableswitch`・`lookupswitch`・`wide iload`の絶対オフセット解決を検証。
- `ClassFileFormatterTest`/`MnemonicFormatterTest`: ヘッダ・各セクション・abstractメソッドの
  「Codeなし」表示を検証。
- `test/dev/javatexteditor/editor/ClassFileViewTest.java`（`ModalEditor`統合）: `:e`で`.class`を
  開くと構造ビュー（`*class*`ヘッダ）が表示され`currentFilePath`がnullになること、`:nimo`で
  ニーモニックビュー（`*nimo*`ヘッダ）に切り替わること、`.class`を見ていない状態での`:nimo`が
  エラーになること、**別バッファへ切り替えた後は`:nimo`が自動的に無効化される**こと（参照一致
  失効パターンの回帰テスト）、壊れた`.class`がhexdumpにフォールバックすることを検証。
