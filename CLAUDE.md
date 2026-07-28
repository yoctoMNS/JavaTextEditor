# CLAUDE.md

## プロジェクト概要

Vimのモーダル編集とEmacsの拡張性の良い所を統合した、Java SE製の軽量テキストエディタ。学習目的と実用目的を両立させる。

## 技術スタック・制約（厳守）

- **言語**: Java 21 (LTS)。`record`、パターンマッチング、テキストブロックなど Java 21 時点の標準機能は積極的に使ってよい。
- **依存ライブラリ**: 一切使用しない。本番コードはJava SE標準APIのみで実装する。
- **ビルドツール**: 使用しない。`javac`を直接呼び出す。Maven/Gradleの`pom.xml`・`build.gradle`は作成しない。
- **テスト**: JUnit等のテストフレームワークは使用しない。`main`メソッドを持つ自作テストハーネスで検証する（理由: テストの仕組み自体もJava SE標準機能だけで理解できることを学習目的の一部としているため）。
- **想定ファイル規模**: 数百行〜数十万行のファイルを編集対象とする。実装時は常にこの規模を意識すること（例: `String`の`+`連結や文書全体の再構築をループ内で行わない）。

これらの制約に反する提案（外部ライブラリの追加、ビルドツールの導入など）はしないこと。

## コマンド

```bash
# ビルド（src/配下の全.javaファイルをbuild/にコンパイル）
./scripts/build.sh

# テスト（build.shの後、src/+test/をコンパイルし、*Testクラスのmainメソッドを実行）
./scripts/test.sh

# 実行（Mainクラスを起動）
./scripts/run.sh
```

## ディレクトリ構成

```
project-root/
├── CLAUDE.md
├── docs/
│   ├── manual/                           ← 利用者向け全機能マニュアル（README.mdからリンク）
│   ├── requirements.md
│   ├── implementation-history.md
│   └── REFACTORING_PLAN.md
├── .claude/
│   └── skills/                          ← 設計知識はここに集約する（下記ロードマップ参照）
│       ├── editor-buffer-architecture/
│       ├── gui-rendering-pipeline/
│       └── font-and-statusline-animation/
├── src/
│   └── dev/javatexteditor/
│       ├── Main.java
│       ├── buffer/
│       │   ├── Piece.java
│       │   └── PieceTable.java
│       └── ui/
│           ├── Theme.java
│           └── EditorCanvas.java
├── test/
│   └── dev/javatexteditor/
│       ├── buffer/
│       │   └── PieceTableTest.java
│       └── ui/
│           ├── EditorCanvasTest.java
│           └── VisualPreview.java
├── scripts/
│   ├── build.sh
│   ├── test.sh
│   └── run.sh
└── build/                                ← コンパイル出力先（.gitignore対象）
```

パッケージ名: `dev.javatexteditor`（確定済み）

## 決定済みの設計事項

| 項目 | 決定内容 | 詳細・実装コード |
|---|---|---|
| バッファ構造 | ピーステーブル方式 | `.claude/skills/editor-buffer-architecture/SKILL.md` |
| アンドゥ/リドゥ | ピースリストのスナップショット方式 | `.claude/skills/editor-buffer-architecture/references/piece-table-delete-and-undo.md` |
| 拡張言語 | Lispインタプリタの自作ではなく、`javax.tools.JavaCompiler`（JDK標準API）による動的コンパイルでJavaそのものを拡張言語として使う | 未作成（`extension-language-runtime`スキルで設計予定） |
| GUI描画v1 | Swing/AWT・単一バッファ静的表示。全角文字幅対応、NORMAL=ブロック/INSERT=縦棒カーソル | `.claude/skills/gui-rendering-pipeline/SKILL.md` |
| GUI描画v1 〜 v3（実装済み） | v2=縦スクロール・v3=横スクロール＋JSplitPaneウィンドウ分割 | `.claude/skills/gui-rendering-pipeline/references/future-phases.md` |

## ロードマップ（Skill一覧）

| # | Skill名 | 担当領域 | 状態 |
|---|---|---|---|
| ① | `editor-buffer-architecture` | バッファ・データ構造 | ✅ 完了（15/15テスト・getTextInRange/offsetOfLine追加済み） |
| ② | `modal-editing-engine` | Vimモーダル編集（Insert中のEmacs式カーソル移動含む） | ✅ v5 完了（151/151テスト・NORMAL/INSERT/COMMAND/VISUAL/VISUAL LINE） |
| ③ | `extension-language-runtime` | Java動的コンパイルによる拡張機構 | ✅ v1 完了（9/9テスト） |
| ④ | `keymap-conflict-resolution` | Vim式モーダルキー / Emacs式カーソル移動の共存 | ✅ Phase 3 完了（38/38テスト・getKeymap()/registerAction() でプラグインがキーバインド登録可能） |
| ⑤ | `gui-rendering-pipeline` | Swing/AWT GUI描画 | ✅ v3 完了（22/22テスト・縦横スクロール・JSplitPane・Ctrl+W） |
| ⑥ | `plugin-api-design` | プラグイン向け公開API | ✅ 完了（39/39テスト・getLine/offsetAt/setCursor/isNormalMode/getKeymap追加） |
| ⑦ | `editor-testing-strategy` | 境界値・大規模ファイルのテスト戦略 | ✅ 完了（101テスト追加・計394/394テスト全PASS） |
| ⑧ | `java-source-analysis` | Compiler Tree APIによるAST解析・auto-import索引基盤 | ✅ 完了（49/49テスト・import索引/シンボル索引/graceful degradation） |
| ⑨ | `javac-compile-integration` | javac連携・コンパイルエラー表示 | ✅ 完了（15/15テスト・ガター描画・波下線・INSERTモード離脱フック） |
| ⑩ | `jdk-api-navigation` | JDKクラス/メソッド/フィールドの参照・ナビゲーション | ✅ 完了（18/18テスト・K キー・jrt:/ 索引・リフレクション表示） |
| ⑪ | `javadoc-viewer` | ローカルJavadoc(HTML)のエディタ内表示 | ✅ 完了（15/15テスト・graceful degradation・`K`キーでサマリ表示） |
| ⑫ | `openjdk-source-tracing` | JNI/HotSpotレベルのソーストレース | 🚧 Phase 1（`src/hotspot/share`共通ソースを`lib/openjdk-native/hotspot/`に取得・`gr`/`findCSymbol`の検索対象に追加・`.hpp`拡張子対応。os/cpu固有部は未対応、33/33テスト） |
| ⑬ | `project-wide-search` | 作業ディレクトリ配下のgrep的検索 | ✅ 完了（19/19テスト・`:grep`コマンド・Enter でジャンプ） |
| ⑭ | `multi-file-refactoring` | シンボル単位の複数ファイルリファクタリング | ✅ 完了（25テスト・`:rename`コマンド・語境界マッチ・`*rename*`疑似バッファ） |
| ⑯ | `auto-import-handler` | 未定義シンボルの import 自動挿入 | ✅ 完了（26/26テスト・INSERT→NORMAL フック・候補1件自動挿入・複数候補選択UI） |
| ⑰ | `font-and-statusline-animation` | ビットマップフォント埋め込み・ステータスラインの歩行キャラクターアニメーション | ✅ Skill追加（設計知識のみ・実装は⑤完了後） |
| ⑱ | `text-search` | Vim式バッファ内文字列検索（/・*・#・n・N・正規表現・ハイライト） | ✅ 完了（34/34テスト） |
| ⑲ | `file-search` | \fファイル名検索・\gファイル内容grep（NORMALモード・疑似バッファ表示） | ✅ 完了（43/43テスト） |
| ⑳ | `telescope-picker` | telescope.vim風ファジーファインダー（SPC+f/SPC+//SPC+b・あいまい検索は維持しつつ`\f`/`\g`と同じ疑似バッファ表示） | ✅ 完了（28/28テスト・FilePicker/GrepPicker/BufferPicker・FuzzyMatcher。3ペインオーバーレイは2026-07に廃止、詳細はSkill参照） |
| ㉑ | `simple-filer` | `:cd` 実行後に表示されるディレクトリ一覧・ファイルブラウザ（FILERモード） | ✅ 完了（46/46テスト） |
| ㉒ | `editor-tutorial` | `:tutor`/`:tutorial` で開く vimtutor 形式の対話型チュートリアル | ✅ 完了（9/9テスト） |
| ㉓ | `symbol-definition-navigation` | Shift+K（定義へジャンプ、Eclipse/IntelliJ流に統合）/Shift+J（一つ前の参照へ戻る）/`gr`（参照一覧、jdk-source疑似バッファ内では`lib/openjdk-native/`のネイティブ実装側を検索） | ✅ 完了（28/28テスト・`ProjectSymbolResolver`・⑩の`jumpToMethod`を`jumpToMember`に一般化してJDKフィールドにも対応。`gd`は`K`に統合し廃止。`executeGrep`/`jumpToGrepResult`をbaseDir一般化して⑫`openjdk-source-tracing`のnative参照検索を先行実装） |
| ㉔ | `windows-batch-and-subprocess` | `scripts/*.bat`編集・Javaからのサブプロセス出力読み取りの恒久ルール（ASCII専用・ブロック内丸括弧禁止・`native.encoding`） | ✅ Skill追加（⑫実装時のバグ3連鎖から抽出した開発プロセス知識。機能実装は伴わない） |
| ㉕ | `modal-visual-block-selection` | Vim矩形選択（`Ctrl+V`・VISUAL BLOCK）のモード追加・ヤンク/削除/ペースト/矩形挿入(`I`/`A`)/矩形変更(`c`)/矩形置換(`r`)・描画 | ✅ 完了（12テスト・`YankType.BLOCK`追加・ペースト時の新規行自動生成・矩形挿入は既存INSERTモードを再利用し状態フラグで複製する方式） |
| ㉖ | `vim-substitution` | Vim式置換コマンド`:s`（現在行・`%s`全行・`'<,'>s`Visual選択範囲・`N,Ms`行番号範囲・正規表現・`g`/`i`フラグ・`\1`/`&`置換） | ✅ 完了（18/18テスト・VISUAL/VISUAL_LINE/VISUAL_BLOCKの`:`キーで`'<,'>`自動入力・区切り文字は`/`以外も可・undoグルーピングなし＝`indentLines()`と同じ既存トレードオフを踏襲） |
| ㉗ | `vim-macro-recording` | Vim式マクロ（`q{register}`記録・`q`終了・`@{register}`再生・`@@`直前マクロ再実行・大文字レジスタ追記） | ✅ 完了（29/29テスト・記録は`processKey()`入口1箇所で生キーを捕捉・マクロ専用レジスタは既存の`yankRegister`とは独立・記録中の入れ子`@`呼び出しは展開せず呼び出し2キーのみ記録・`count`付き再生(`3@a`)は汎用count機構が存在しないためスコープ外） |
| ㉘ | `vim-case-conversion` | Vim式大文字小文字変換（NORMALの`~`・`guu`/`gUU`/`g~~`、VISUAL/VISUAL_LINE/VISUAL_BLOCKの`u`/`U`/`~`） | ✅ 完了（23/23テスト・operator-pendingモーション（`guiw`等）は②の既存スコープ外判断を踏襲し未対応・doubled-letter方式のみ実装） |
| ㉙ | `classfile-viewer` | `.class`ファイルを開いた際のJVM仕様通りの構造ビュー表示（マジックナンバー/定数プール/フィールド/メソッド/属性）・`:nimo`コマンドによるニーモニック（javap -c風）バイトコード逆アセンブル表示 | ✅ 完了（60/60テスト・`dev.javatexteditor.classfile`パッケージ新設・`readFileContentForBuffer`にマジックナンバー判定を追加・`:nimo`は`outputErrorLinesOwner`と同じ参照一致による自動失効パターン。`:b`コマンド（Mode.BINARY）とは別物の読み取り専用プレビューとしてマージ済み） |
| ㉚ | `markdown-viewer` | `.md`/`.markdown`ファイルを開いた際は生ソースを表示し、`:view`コマンドで見出し下線・リスト正規化・インライン記法除去等を行う読み取り専用の閲覧ビューへ切り替え、`:mark`でソースへ戻す | ✅ 完了（98/98テスト・`dev.javatexteditor.markdown`パッケージ新設・`MarkdownRenderer`はSwing非依存の純粋ロジックで出力はASCII印字可能文字のみに限定・`classFileBufferOwner`と同じ参照一致による自動失効パターン・`currentFilePath`をnullにする読み取り専用プレビュー方式（jdk-source方式は不採用、理由はSKILL.md参照）） |

### 依存関係（Skillを作る順序の制約）

| Skill | 依存先（先に固まっていないと着手すべきでない） |
|---|---|
| ① | なし（最初に着手・実機検証済みにすること） |
| ②③⑤⑦⑧⑪⑬ | ① |
| ④ | ②③ |
| ⑥ | ③ |
| ⑨⑩⑭⑯ | ①⑧（⑧の索引・AST解析基盤を再利用するため。⑨のコンパイルエラーから未定義シンボルを抽出） |
| ⑫ | ⑩（nativeメソッドのナビゲーションを拡張する機能のため） |
| ㉓ | ①⑧⑩⑬（⑧のSourceAnalyzer/SymbolEntryと⑬のProjectSearcherでプロジェクト内シンボルを検索し、⑩のJDKクラス解決・`classAndMethodAtCursor()`を再利用してJDKメンバーにも対応） |

**補足**: ⑧〜⑭はいずれも「裏側のロジック」と「画面への表示」が分かれている。ロジック部分は上表の依存関係で着手できるが、実際に画面に結果を表示する部分は⑤の完成が前提になる。

**⑧ と ⑯ の関係**: ⑧ `java-source-analysis` は「既存の import 文を読む索引」と「シンボルを解析する基盤」のみ提供。⑯ `auto-import-handler` は ⑧ の索引と ⑨ のコンパイルエラー（未定義シンボル）を組み合わせて、「import 文の自動挿入」UI を実装する（✅ 完了）。

**注意**: `TextEditorSettings.java`（テーマ等の設定ファイル）は通常の`.java`ファイルとして他のソースと一緒に`javac`でビルドするだけで良く、③（`extension-language-runtime`の動的コンパイル機構）には依存しない。③は「エディタ起動中に新しいプラグイン/マクロをその場で読み込む」という、より高度な用途専用。設定ファイルとプラグイン機構を混同しないこと。

## FILERモードの設計決定事項

- **`Mode.FILER`** を新設。TELESCOPE/FILESEARCH/IMPORT_SELECT と同様、`KeymapRegistry` をバイパスし `processFilerKey()` で直接キーを処理する。
- **`currentDirectory` は `projectRoot` と統合**（`getProjectRoot()`経由で参照）。別フィールドは作らない。WD_MANAGER リスナーが同期的に全エディタの `projectRoot` を更新するため、`:cd` 成功後に `enterFiler()` を呼ぶとその時点で正しい `projectRoot` が読める。
- **`changeWdCallback` の型を `Consumer<Path>` から `Function<Path, String>` に変更**。成功時 null、失敗時日本語エラー文字列を返す。ModalEditor が成功/失敗を同期的に判定して FILER 遷移またはエラー表示を行う。
- **processCommandKey Enter ハンドラ**: `mode = Mode.NORMAL` を `if (mode == Mode.COMMAND) mode = Mode.NORMAL` に変更。`enterFiler()` が `mode = Mode.FILER` をセットした後に上書きされることを防ぐ。
- **描画は疑似バッファ方式（2026-07 に変更）**: 当初は `EditorCanvas.setTelescopeState()` / `drawTelescopeOverlay()` を FILER モードでも流用していた（IMPORT_SELECT と同じオーバーレイパターン）が、「`:cd` でディレクトリ移動している間も telescope 風のオーバーレイ画面が表示されてしまう」という指摘を受け、telescope-picker（SPC+f/SPC+b等）と同じ「`\f`/`\g`と同じヘッダ行＋結果一覧をbufferに直接描画」方式に統一した。`ModalEditor.renderFilerBuffer()` が `*filer* <projectRoot> — N件` ヘッダ＋エントリ一覧を `buffer` に描画し、選択中の項目は実カーソル（`cursorRow = filerSelectedIdx + 1`）で示す。プレビュー欄（`buildFilerPreview()`）は telescope 同様に廃止した。`:cd` 実行時（`changeDirectory()`）にのみ元バッファを `filerSaved*` に退避し、`Esc`（`exitFiler()`）で復元する。サブディレクトリへの再帰移動（`openSelectedEntry()` でディレクトリを選ぶ場合）は `enterFiler()` を呼び直すだけで保存はしない（telescope のセッション開始が1箇所なのに対し、FILER は `:cd` 一回の起動から何度もディレクトリを移動できるため、退避は「外部から FILER に入る瞬間」の1箇所に限定する必要がある）。詳細は `.claude/skills/telescope-picker/SKILL.md` の「追記（2026-07）」を参照。
- **純粋ロジックの分離**: ディレクトリ列挙・フィルタは `dev.javatexteditor.search.DirectoryLister` に独立させ、ModalEditor はオーケストレーション（状態管理・キー処理）のみ担う。
- **ファイルオープンの再利用**: `openSelectedEntry()` がファイルを選択した際は `exitFiler()` で元バッファへ復元してから既存の `loadFromFile(String)` を呼び出す（`loadFromFile` 内の `pushBuffer()` が正しい元バッファを履歴に積むために必要。復元を挟まないと疑似バッファのテキストが誤って履歴に積まれてしまう）。

## チュートリアルモード（㉒ editor-tutorial）の設計決定事項

- **自動採点はしない**。vimtutor と同じ設計判断: チュートリアルは「読みながら実際にそのテキストを編集する」だけの通常バッファであり、キー入力の正誤判定・進捗トラッキングの仕組みは持たない。理由: 自動採点を入れるとモード追加（専用Mode・状態機械）が必要になり、「学習目的のシンプルさ」という本プロジェクトの方針に反するため。
- **本文は `dev.javatexteditor.tutorial.Tutorial.CONTENT`（Java 21 テキストブロック）として埋め込む**。`scripts/build.sh` は `.java` ファイルしかコンパイルせずリソースファイルをコピーしないため、外部の `.txt` ファイルを `lib/` 等に同梱する方式は採れない。
- **`:tutor` コマンドは `:enew`（`newBuffer()`）や `:grep`（`executeGrep()`）と同じ「疑似バッファ」パターンを踏襲する**: `pushBuffer()` で現在のバッファを履歴に積み、`currentFilePath = null` の新規 `UndoablePieceTable` に差し替える。`:w` で保存しようとすると（保存先がないため）通常の "no file name" エラーになるのは意図した挙動。
- **`Ctrl+U`（バッファ履歴の前へ）でチュートリアルを開く前のバッファに戻れる**。これは新規実装ではなく、既存のバッファ履歴機構（`bufferHistory`/`historyIdx`）がそのまま使える。
- コマンド名は `:tutor`（vimtutor 由来）と `:tutorial`（分かりやすさのためのエイリアス）の両方を受け付ける。

## 作業ディレクトリ・`:pwd`/`:cd`の設計決定事項

- **ステータス行中央への作業ディレクトリの常時/切替表示機能は廃止**。一度 `EditorCanvas.pwdVisible` フラグと `TextEditorSettings.java`（表示ON/OFF既定値・文言フォーマット集約用に新設）で実装したが、「画面中央にカレントディレクトリを表示する仕組み自体が不要」という判断により全面削除した。`:pwd` コマンドは以前からある「`statusMessage` にフルパスを設定し、`commandLineText` としてステータス行左側に一時表示する」という既存動作のみを残す（このプロジェクトにおけるコマンド実行結果メッセージの標準パターンで、`:oi`/`:remove-import` 等と同じ）。ステータス行中央表示のような新規UI要素は今後も要求がない限り追加しない。
- **`:cd` の `~` 展開は `ModalEditor.expandHome()` で行う**（維持）。`Path.resolve()` は `~` を特別扱いしないため、`getProjectRoot().resolve(pathStr)` に渡す前に文字列レベルで `System.getProperty("user.home")` に置換する。`~`単体・`~/...`・`~\...`（Windows想定）の3パターンに対応。展開後は絶対パスになるため、`Path.resolve()` の「絶対パスを渡すとそれがそのまま返る」仕様にそのまま乗せられる。
- **`WorkingDirectoryManager` は前回終了時のディレクトリを `Preferences` に永続化する仕組みを持っていたが廃止した**。「起動時の既定作業ディレクトリは常にホームディレクトリ」という要件と矛盾していたため（一度 `:cd` で別ディレクトリに移動すると、その値が `Preferences` に保存され、以後の起動では常にそのディレクトリが既定値になってしまっていた）。現在の初期値決定順は「起動時ヒント（開いたファイルの親ディレクトリ）→ホームディレクトリ→`user.dir`」のみで、セッションをまたいだ永続化は行わない。
- **`:cd` のパス入力に TAB キーでのシェル風パス補完を追加した**。COMMAND モードで `commandBuffer` が `"cd"`/`"cd "` で始まる場合のみ TAB を横取りし（`ModalEditor.handleCdTabCompletion()`）、`DirectoryLister.listDirectoryEntries()` で候補ディレクトリ（`DirEntry.Kind.DIRECTORY` のみ、ファイルは対象外）を列挙して入力中の末尾セグメントを前方一致（大小無視）でフィルタする。候補0件は何もしない、1件はその場で `commandBuffer` を `"cd " + 親パス + 名前 + "/"` に補完して COMMAND モードのまま継続する（続けて TAB で深掘りできるよう末尾に区切り文字を付与）。
- **複数候補の表示は telescope オーバーレイを使わず、`*grep*`/jdk-source と同じ「疑似バッファ」パターンで実現した**（`ModalEditor.openCdCandidateBuffer()`）。当初 IMPORT_SELECT/FILER と同様に `EditorCanvas.setTelescopeState()` のオーバーレイで実装したが、「`:cd` 入力中はオーバーレイではなくテキストエディタ本体の画面に出力し、既に開いているファイル/バッファがあれば新しいバッファを開いてその画面を再利用してほしい」という要求により、jdk-source 疑似バッファ（`savedBufferText`/`inJdkSourceBuffer` 等）と同型の「一時退避 → 復元」方式に変更した。専用フィールド `cdSelectionActive`/`cdSavedBufferText`/`cdSavedFilePath`/`cdSavedCursorRow`/`cdSavedCursorCol`/`cdSavedCommandText` に現在編集中のバッファと入力途中のコマンド文字列を退避し、`*cd候補* <親パス> — N件` というヘッダ行＋候補ディレクトリ名（各行 `名前/`）だけの新規バッファに差し替えて通常の NORMAL モードで表示する（bufferHistory は使わない。ここでの退避は「TAB→選択→自動復帰」という一往復の完結した操作であり、Ctrl+U で行き来する永続的な履歴に載せると選択時に上書きされてスナップショットが壊れるため）。NORMAL モードで Enter を押すとカーソル行（行0はヘッダなので `cursorRow - 1` が候補インデックス）の候補を選び、退避していた元バッファを復元した上で COMMAND モードへ戻り `commandBuffer` を補完済みの文字列にする（`applySelectedCdCandidate()`）。`q` を押すと選択をキャンセルし、元バッファと TAB 押下前の `commandBuffer` 文字列をそのまま復元して COMMAND モードに戻る（`cancelCdSelection()`）。この Enter/q の判定は `processNormalKey()` 内で `grepResults != null` や `inJdkSourceBuffer && keyChar == 'q'` と同じ位置に追加しており、疑似バッファの割り込みキー処理として確立済みの並びに揃えている。
- **`EditorCanvas` に残っていた作業ディレクトリのホバーツールチップ表示を削除した**（バグ修正）。上記「ステータス行中央への作業ディレクトリの常時/切替表示機能は廃止」の際、中央表示（`pwdVisible`）は消したものの、`setWorkingDirectory(Path)` が `setToolTipText()` を呼びステータス行付近にマウスを合わせると `getToolTipText(MouseEvent)` オーバーライドがフルパスをポップアップ表示する経路が消し漏れとして残っていた。「カーソルを画面に合わせるとカレントディレクトリのパスがポップアップ表示される」という報告を受けて特定・削除。`EditorCanvas.workingDirectory` フィールド・`setWorkingDirectory()`・`getToolTipText(MouseEvent)` オーバーライドを削除し、`Main.java` 側の `canvas.setWorkingDirectory(wd)` 呼び出し2箇所（初期化時・`WD_MANAGER` の変更リスナー内）も削除した。ホバー時にパスを表示する仕組み自体を今後も追加しない（中央表示を廃止した際の判断と同じ理由）。

## `:main <target>` コマンド（java/javac の実際の起動点へのジャンプ）の設計決定事項

- **「起動点」は HotSpot 本体（`JVM_GC` 等）ではなく launcher の入口を指す**。`java` は launcher バイナリの native `main()`（`src/java.base/share/native/launcher/main.c`。ここから `JLI_Launch()` を呼び、コマンドライン引数から実行対象の main class を解決する）。`javac` は launcher が直接呼ぶ `com.sun.tools.javac.Main.main(String[])`（jdk.compiler モジュール）で、javac 専用の native/JNI 実装は存在しない。`java Foo.java` の単一ソース実行（JEP 330）も同じ native `main()` を経由し、その後 `LauncherHelper` 内部でソース/クラスファイルの判定が行われる。
- **ターゲット→ジャンプ先の対応は `dev.javatexteditor.analysis.EntryPointIndex`（新設）の `Map<String, Target>` に集約**した。`Target` は `NativeLauncher(relativePath, symbol)` と `JavaSource(moduleName, fqcn, memberName)` の sealed record 2種。`jar`/`javadoc`/`jshell` 等の追加はこの Map にエントリを足すだけでよく、`ModalEditor` 側の switch は変更不要。
- **native 側は `OpenjdkSourceTracer.findCSymbolInFile(relativePath, symbol)` を新設**した。既存の `findCSymbol(symbol)` は `lib/openjdk-native/` 全木を走査するため、"main" のように同名のC関数定義が複数ファイル（`java`/`javac`/`jshell` 等、各ツールの launcher 用 `main.c`）に存在するケースで曖昧になる。`:main` はどのファイルを見るべきか既知（`EntryPointIndex` にファイルパスを持たせている）なので、ファイルを直接指定して読む1ファイル限定版を追加し曖昧さを排除した。
- **Java 側は `OpenjdkSourceTracer.readJavaSourceByFqcn(moduleName, fqcn)` を新設**した。既存の `readJavaSource(Class<?> cls)` はリフレクションで実際に `Class` をロードする必要があるが、`com.sun.tools.javac.Main` は `jdk.compiler` モジュールに属し、クラスパス実行時のデフォルトのモジュール解決（`java.se` 相当のみ）では `Class.forName()` が失敗しうる。文字列ベースの FQCN + モジュール名から直接 `src.zip` のエントリを引く版を追加することで、対象モジュールを実行時に解決可能にする必要をなくした。
- **ジャンプ後の疑似バッファ表示は既存の `openCSymbolBuffer()`（native）/ `openJdkSourceBuffer()` + `jumpToMember()`（Java）をそのまま再利用**しており、`:main` 専用の描画・状態管理コードは追加していない（`K`/`gr` と同じ `q` で閉じる・`Ctrl+U`/`Shift+J` 等の既存疑似バッファ機構がそのまま効く）。
- **未対応ターゲット・引数なしはエラーメッセージに対応ターゲット一覧を含めて表示するのみ**で、バッファには影響しない（`:oi`/`:remove-import` 等と同じ `statusMessage` エラーパターン）。ターゲット名は大文字小文字を区別しない。
- **テストの制約**: `lib/openjdk-native/`・`lib/src.zip` は `scripts/setup.sh` で別途取得する外部リソースのため、CI/開発コンテナには存在しない。`OpenjdkSourceTracer` の新規メソッドは一時ディレクトリ・偽装 zip で単体テスト済み（`OpenjdkSourceTracingTest`）だが、`ModalEditor` 経由の `:main java`/`:main javac` 統合テスト（`MainCommandTest`）は実際のジャンプ成功までは検証できず、"unknown command" にならず graceful degradation することの確認に留まる（⑫ `openjdk-source-tracing`・㉓ `symbol-definition-navigation` と同じ既知のテストギャップ）。

## 単語補完（Alt+/）の設計決定事項

- **INSERT モードで `Alt+/` を押すと、作業ディレクトリ配下の単語・クラス名・変数名・定数名・メソッド名を補完できる**（Vim の `i_CTRL-N` 相当）。当初はユーザー要望通り `Ctrl+N` に割り当てようとしたが、INSERT モードの `Ctrl+N` は `.claude/skills/keymap-conflict-resolution/SKILL.md` で既に Emacs 式「カーソル下移動」に確定済みであり、ユーザーに確認したところ「別キーを使う」を選択したため `Alt+/` を新規に割り当てた（`Ctrl+N` のカーソル下移動は維持）。
- **既存の `CompletionIndex`（Ctrl+Space、当時は javac の AST 解析で JDK クラス名 + プロジェクトのクラス/メソッド/フィールド名を収集していた）とは別に `dev.javatexteditor.analysis.WordIndex` を新設した**。`CompletionIndex` は宣言されたシンボルしか拾えず、ローカル変数・定数・Java 以外のファイルの単語は対象外なため、「単語」を要求するこの機能には正規表現ベースの軽量なインデックスが必要だったため。AST解析（javac 呼び出し）が不要な分、`CompletionIndex` よりビルドが高速。（のちに `CompletionIndex` 側のプロジェクト AST 解析は重すぎるため廃止され、Ctrl+Space も本節末尾の「Ctrl+Space 補完を WordIndex に一本化」のとおり `WordIndex` を使うようになった。）
- **高速化のため TreeSet + `subSet(prefix, prefix+Character.MAX_VALUE)` による O(log n + k) のプレフィックス検索を採用**（k = 一致件数のみを走査。全件を毎回スコアリングする `CompletionIndex.query()` より高速）。ビルドは `Files.walkFileTree` を1回だけバックグラウンド仮想スレッドで実行し、`.git`/`build`/`target`/`node_modules`等の慣例的なスキップ対象ディレクトリと、2MB超のファイル・既知のテキスト拡張子以外のファイルを除外して高速化・バイナリファイル誤読を防いでいる。ビルド完了後の `TreeSet` は不変として扱い、参照の差し替え（`volatile` フィールド）だけでスレッド間可視性を保証するため、通常の読み取りに `synchronized` は不要（ロックフリー）。
- **現在編集中バッファの単語も候補にマージする**（`WordIndex.extractWords()` をトリガー時にその場で実行）。保存前の未確定な単語（例: 書きかけの変数名）もディスクスキャンでは拾えないため。ただし **カーソル直前の「今まさに入力中の未完成なプレフィックス」自体は必ず除外する**（`ModalEditor.queryWordCompletion()` で `bufferWords.remove(prefix)`）。除かないとカーソル位置のトークンが常に「prefix と完全一致する単語」として候補に混入し、何も意味のある入力をしていなくても補完ポップアップが開いてしまう・デフォルト選択（先頭候補）が入力中の文字列そのものになり選んでも何も変わらない、という無意味な結果になるため。
- **`completionActive`/`completionItems`/`completionSelectedIdx`/`completionPrefix`（Ctrl+Space のシンボル補完と共用のフィールド）をそのまま流用**し、`completionIsWordMode` フラグだけを追加してどちらのインデックスに対して再クエリするかを切り替えている。ポップアップのナビゲーション（↑↓・Tab/Enter・Esc）・描画（`EditorCanvas.setCompletionState()`）は完全に共通化されており、専用の UI コードは増やしていない。`CompletionItem.kind()` には新しい種別文字列 `"wd"`（2文字。既存の `"cls"/"mth"/"fld"` と同じ描画幅に収まるよう選定）を使う。
- **プロジェクト全体のスキャンは起動時に1回だけ**（`Main.java` で `WordIndex.build(projectRoot)`）。`CompletionIndex` 同様、`:cd` での作業ディレクトリ変更時に再構築する仕組みは持たない（既存の `CompletionIndex` にもない挙動であり、スコープを広げないため）。

### Ctrl+Space 補完を WordIndex に一本化（メンバー/ローカル変数/定数の重い AST 解析を廃止）

- **`CompletionIndex` は JDK クラス名（`"cls"`）のみを保持するようになった**。以前は `addProjectSymbols()` が `Files.walk(projectRoot)` で全 `.java` ファイルを列挙し `SourceAnalyzer`（javac AST 解析）でメソッド/フィールドまで収集していたが、プロジェクト全ファイルへの AST 解析はファイル数に比例して重く（Shift+K フリーズ修正の節と同種の問題）、既に軽量な正規表現ベースの `WordIndex` と役割が重複していたため廃止した。`CompletionIndex.build(jdkIndex)`/`buildSync(jdkIndex)` は `projectRoot`/`SourceAnalyzer` 引数を取らなくなった。`refreshProjectSymbols()`（元々未使用・「既知の未接続・二重定義」節 3. 参照）も同時に削除した。
- **Ctrl+Space（`triggerCompletion()`/`recheckCompletion()`）は `queryMergedCompletion()` で `wordIndex`（作業ディレクトリ配下のファイル + 現在バッファの単語。フィールド/メソッド/ローカル変数/定数を含む）を最優先し、その後に `completionIndex`（JDK クラス名のみ）を重複除去のうえ追加するようになった**。ユーザーからの明示的な要望（「Javaクラス API の補完はクラス名のみにし、残りの単語補完候補は作業ディレクトリのファイル群を最優先にする」）に基づく。`Alt+/`（`triggerWordCompletion()`/`recheckWordCompletion()`、`completionIsWordMode == true`）は従来どおり `wordIndex` のみを使う独立した経路のままで変更していない。
- **`COMPLETION_MAX_RESULTS` の上限は両ソース合算で1つ**: `queryMergedCompletion()` は `wordIndex` 側の結果だけで上限に達した場合、`completionIndex`（クラス名）を問い合わせずに打ち切る。これが「作業ディレクトリのファイル群を最優先」の実装そのもの。

### 補完候補の並び順を Vim の i_CTRL-N に合わせる（`WordIndex.extractWordsByProximity` 新設）

- **経緯**: 従来 `WordIndex.query()` はディスク索引・現在バッファの単語の両方を一つの `TreeSet` にまとめており、結果は常にアルファベット順だった（カーソルとの位置関係は一切考慮しない）。Vim の `i_CTRL-N`（既定の `'complete'` 値 `.,w,b,u,t,i`）はカレントバッファを最優先ソースとし、その中でも「カーソル位置から前方（ファイル末尾方向）に近い出現を先に、末尾に達したら先頭へ折り返してカーソル手前まで」という近接順で候補を並べる。この挙動に合わせてほしいという要望に基づき、並び替えアルゴリズムを変更した。
- **`WordIndex.extractWordsByProximity(text, cursorOffset, prefix)` を新設**した。現在編集中バッファのテキスト全文を2周だけ走査する（1周目: `cursorOffset`以降〜末尾、2周目: 先頭〜`cursorOffset`手前）。マッチ位置は正規表現の走査順で常に昇順に見つかるため、それぞれの周回内はカーソルに近い順に自然と並ぶ。重複語は最初に見つかった出現（＝カーソルに近い方）だけを残す。カーソル位置そのものの語（今まさに入力中の未確定なプレフィックス）は、位置が `cursorOffset` と一致するトークンとして明示的に除外する（従来の `bufferWords.remove(prefix)` は文字列一致で全箇所を消してしまっていたため、同名の別の完成した識別子まで誤って除外するケースがあった。位置ベースの除外に変えたことでこの副作用がなくなった）。
- **`WordIndex.query(prefix, maxResults, extraWords)` の合成順序を変更**した。`extraWords` は「呼び出し側が渡した順序をそのまま尊重する」契約に変え（従来は `TreeSet` に追加してアルファベット順に強制的に均されていた）、`extraWords`（＝ `extractWordsByProximity` で近接順に並べたバッファ内の単語）を最優先で詰め、埋まらなかった残り枠だけをディスク索引（`words.subSet` の辞書順）から補うようにした。これにより「カレントバッファを最優先ソースとする」という Vim の `'complete'` の思想を、単一の関数呼び出しの中でそのまま反映できる。
- **`ModalEditor.queryWordCompletion()`** は、入力中プレフィックスの先頭位置を新設の `prefixStartOffset()`（`extractCompletionPrefix()`/`applyCompletion()` と同じ「識別子境界を後方に辿る」ロジックを流用した単純な複製。3箇所目の重複だが、CLAUDE.md の「3行の重複は早すぎる抽象化よりよい」という方針に従いあえて共通化しなかった）で求め、`WordIndex.extractWordsByProximity()` の結果を `wordIndex.query()` の `extraWords` に渡すように変更した。`Alt+/`（`triggerWordCompletion`/`recheckWordCompletion`）・`Ctrl+Space`（`triggerCompletion`/`recheckCompletion`、内部で `queryWordCompletion()` を呼ぶ `queryMergedCompletion()` 経由）の両方が同じ経路を通るため、この並び替えは両キーに自動的に反映される。
- **ディスク索引（他ファイル）側の並びは変更していない**: `words.subSet()` によるアルファベット順のまま。Vim の `'complete'` も他バッファ/タグ/includeファイルの探索順は「近接」ではなく別の基準（読み込み順など）であり、ディスク全体走査には位置の概念がそもそも存在しないため、無理に近接順を模倣せず現状維持とした。
- **意図的に見送った拡張**: `CompletionIndex`（JDKクラス名、`CompletionScorer` によるスコアリング）側の並び順は今回変更していない。`queryMergedCompletion()` は「`wordIndex` の近接順結果を先頭に、残り枠だけ `completionIndex` のスコア順結果を追加する」という継ぎ接ぎ構造のままであり、2つの異なるランキング方式を単一の統一スコアに揃える改修は本タスクのスコープ外（要望はあくまで「単語補完の並び順を Vim に合わせる」ことだったため）。

### Ctrl+Space で JDK クラス名が候補に出ない不具合の修正（`COMPLETION_CLASS_RESERVED_SLOTS` 新設）

- **症状**: Ctrl+Space の補完ポップアップに JDK API クラス（`"cls"`、`CompletionIndex` 由来）がまったく出てこない、という報告があった。
- **原因**: `queryMergedCompletion()` は `wordIndex`（作業ディレクトリ配下の単語 + 現在バッファ）を最優先で `COMPLETION_MAX_RESULTS`（10件）まるごとの上限で問い合わせていた。実プロジェクトでは大半のプレフィックスに対して単語一致だけで10件埋まってしまうため、`completionIndex`（JDKクラス名）を問い合わせる分岐（`merged.size() < COMPLETION_MAX_RESULTS`）に到達する前に枠が尽き、JDK クラス名が実質的に一切表示されなくなっていた（上記「意図的に見送った拡張」の順序自体は既存方針どおりだが、wordIndex 側に上限を掛けていなかったのは見落としだった）。
- **修正**: `COMPLETION_CLASS_RESERVED_SLOTS`（3）を新設し、`completionIndex` が利用可能な場合は `wordIndex` への問い合わせ上限を `COMPLETION_MAX_RESULTS - COMPLETION_CLASS_RESERVED_SLOTS`（7件）に縮小してから `completionIndex` に残り枠（wordIndex が7件未満しか返さなければその分JDKクラス名が増える）を渡すようにした。`queryWordCompletion(String)` はオーバーロード `queryWordCompletion(String, int maxResults)` を追加する形にし、Alt+/ 専用の `triggerWordCompletion()`/`recheckWordCompletion()` は従来どおり `COMPLETION_MAX_RESULTS` フル件数のまま変更していない（Alt+/ はそもそも `wordIndex` 単独の機能であり、JDKクラス名と競合する場面がないため）。
- **意図的に変更しなかった点**: 「wordIndex優先・JDKクラス名は残り枠」という基本方針（上記「Ctrl+Space 補完を WordIndex に一本化」節）自体は変更していない。あくまで wordIndex 側が枠を独占して JDK クラス名の表示機会が事実上ゼロになる、という副作用だけを解消した。
- **テスト**: `test/dev/javatexteditor/editor/WordCompletionTest.java` に `testCtrlSpaceIncludesJdkClassEvenWhenWordMatchesFillBudget()` を追加。同一プレフィックスの単語を12個用意して wordIndex 単独で10件枠を埋め尽くす状況を作り、それでも Ctrl+Space の候補に `kind=="cls"` の JDK クラス名が含まれることを確認する。

## `dev.javatexteditor.completion2` パッケージ（未接続の独立コンポーネント）

- **経緯**: 「Vimの `i_CTRL-N` 相当の単語補完を `CompletionCandidate`/`CompletionSession`/`CompletionEngine`/`TokenScanner`/`EditorKeyHandler`/`CompletionController`/`CompletionPopupModel` という指定クラス構成で実装してほしい」という依頼があったが、既に本エディタの `Alt+/` 単語補完は `WordIndex`/`CompletionIndex`/`ModalEditor`/`EditorCanvas` を使う設計として完成済み（上記「単語補完（Alt+/）の設計決定事項」節）であり、指定構成は既存実装と矛盾する。CLAUDE.mdの方針（既存設計と矛盾する実装は着手前に確認）に従い確認を試みたが、確認手段が使えない状況だったため、既存の本番経路（`ModalEditor`/`EditorCanvas`/`WordIndex`/`CompletionIndex`、Alt+/ キーの実際の割り当て）には一切手を入れず、`src/dev/javatexteditor/completion2/` に独立パッケージとして指定クラス構成をそのまま実装する形で対応した。
- **本番未接続**: `Main.java`・`ModalEditor.java`・`EditorCanvas.java`・`KeymapRegistry` からは一切参照されない。`Alt+/` キーは本番エディタでは従来どおり `ModalEditor`/`WordIndex` 側が処理する（本パッケージのキー割り当てとは独立に動作し、競合しない）。
- **中身**: `CompletionCandidate`（record）・`TokenScanner`（`[A-Za-z0-9_$]+` トークン走査）・`CompletionEngine`（プレフィックス前方一致・カーソル近接優先の候補計算、Swing非依存の純粋ロジック）・`CompletionSession`（1回の補完セッションの状態・巡回、Swing非依存）・`CompletionPopupModel`（`AbstractListModel` ベースのUI側モデル）・`CompletionController`（`JTextComponent`/`Document`/`UndoManager`と結線しCompoundEditで1候補入れ替え=1Undo単位にする統合層）・`EditorKeyHandler`（Alt+/・Shift+Alt+/・Esc・Enter/Tabのキー変換のみを担う薄いアダプタ）。ロジック層（Candidate/TokenScanner/Engine/Session）とUI層（Controller/KeyHandler/PopupModel）を分離しており、将来Swing以外に差し替える場合はUI層のみ置き換えればよい設計。
- **テスト**: `test/dev/javatexteditor/completion2/CompletionEngineTest.java`（15/15、本プロジェクトの自作mainハーネス方式）。`CompletionPopupDemo.java` は `VisualPreview.java` と同様の手動デモ（`*Test.java` 命名ではないため `test.sh` からは自動実行されない。ディスプレイのある環境でのみ手動実行する想定）。
- **今後の判断待ち**: 本パッケージを実際に本番経路へ接続するか、既存の `WordIndex` ベース実装を置き換えるか、あるいは削除するかはユーザーの判断待ち。次にこのパッケージに触れる開発者は、まずユーザーに方針を確認してから進めること。

## Shift+K フリーズ修正（`ProjectSearcher` の巨大ファイル上限）

- **症状**: NORMAL モードで `Shift+K`（`jdk.doc`。定義ジャンプ）を押すとエディタがフリーズすることがあった。
- **原因**: `Shift+K` → `ModalEditor.lookupJdkDoc()` → `ProjectSymbolResolver.resolve()` は `dev.javatexteditor.search.ProjectSearcher.search()` で作業ディレクトリ配下を**同期的（EDT上）に**全文grepする。作業ディレクトリの既定値はヒントが無ければユーザーのホームディレクトリ（`WorkingDirectoryManager` 参照）になり得るため、ファイルを開かずに `K` を押すと巨大なホームディレクトリ全体を対象に検索が走る。`ProjectSearcher` には `WordIndex`（Alt+/ の単語補完索引。`.claude/skills` の単語補完節参照）が既に持っている「2MB超のファイルは読み飛ばす」上限が無く、巨大なログ/ダンプ/メディアファイル1つを読み込むだけで数十秒〜フリーズしたように見える遅延が発生していた。
- **修正**: `ProjectSearcher.search()` の `visitFile` に `attrs.size() <= MAX_FILE_SIZE_BYTES`（2MB、`WordIndex` と同じ値）のガードを追加し、巨大ファイルの全文読み込みを回避するようにした（`src/dev/javatexteditor/search/ProjectSearcher.java`）。
- **意図的に変更しなかった点**: `ProjectSearcher` はディレクトリスキップ対象を `.git`/`build`/`target` のみに限定しており（`FileNameSearcher.SKIP_DIRS`/`WordIndex` が使う `node_modules`/`.idea` 等は対象外）、これはコード内コメントに「意図的」と明記された既存の設計判断のため今回は変更していない。ディレクトリスキップ範囲を広げる場合は別途ユーザーに確認すること。
- **追加修正（2回目）**: 2MB上限を入れた後も、ファイル数自体が非常に多いディレクトリ（例: ホームディレクトリ配下に数十万ファイル）ではフリーズが再発した（実機で確認: `BufferedImage` の上で `Shift+K` を押すと10秒以上応答なし）。`ProjectSymbolResolver.resolve()`/`resolveMemberInType()` は EDT 上で同期的に呼ばれるため、対象ファイル数が多いだけで（1ファイルずつは軽くても）合計時間が容易に数秒〜十数秒に達し、UI がフリーズしたように見えていた。
  - `lookupJdkDoc()` の呼び出し前提（カーソル移動をその場で判定する同期設計）を崩さずに直すため、`ModalEditor.withSearchTimeout()`（`Executors.newVirtualThreadPerTaskExecutor()` + `Future.get(timeout, ...)`）を新設し、`PROJECT_SYMBOL_SEARCH_TIMEOUT_MS`（1500ms）でプロジェクト全体検索を打ち切るようにした。タイムアウトした場合は検索を諦めて `Optional.empty()` を返し、呼び出し側の既存の JDK 側フォールバック（`jdkIndex.lookup()`）にそのまま委ねる（＝コード変更は `projectSymbolResolver.resolve(...)` / `resolveMemberInType(...)` の3呼び出し箇所を `withSearchTimeout(() -> ...)` で包むだけで、ジャンプ判定ロジック自体は変更していない）。
  - **意図的に完全非同期化はしなかった**: `lookupJdkDocAndJump()` は検索結果を使って `buffer`/`cursorRow`/`mode` 等を直接書き換える設計になっており、真の非同期化（`SwingUtilities.invokeLater` でのUI反映）にはこれらの分岐を「計算」と「反映」に分離する大きな再設計が必要になる。タイムアウトで上限を設ける方式なら、既存の同期的なジャンプ判定ロジックに手を入れずに「無制限フリーズ」を「最大1.5秒の待ち」に確実に抑えられるため、今回のスコープではこちらを採用した。
  - **未対応の残課題**: タイムアウト後もバックグラウンドの検索スレッド自体は（`walkFileTree`が割り込み不可のため）動き続ける。頻繁に `Shift+K` を押すとバックグラウンドスレッドが積み重なる可能性があるが、仮想スレッドでありCPUバウンドではなくI/Oバウンドな処理のため実害は小さいと判断し、明示的なキャンセル機構は今回は追加していない。根本的に解消する場合は `lookupJdkDoc()` 全体の非同期化（結果を invokeLater 経由で反映する設計）が必要。
    （2026-07 軽量化リファクタリング Phase 3 で解消: `withTimeout()` がタイムアウト時に `future.cancel(true)` を呼び、`ProjectSearcher` 側は walk の `TERMINATE`・並列 grep タスク冒頭の割り込みチェックで協調的に停止するため、検索スレッドは積み重ならない。あわせて `search()` は「逐次パス収集→仮想スレッド並列 grep」の2段階になり、結果順序・同期契約・1500ms タイムアウト・2MB 上限・スキップ規則は従来と同一。）
- **追加調査（3回目）: `SwingUtilities.invokeLater` を使った真の非同期化は見送り、`gr`/`:grep` にも同じタイムアウトを追加**。ユーザーから「タイムアウトを入れても一瞬固まる」「バックグラウンドスレッド化した方が効率的なら実装してほしい」と依頼があり、`lookupJdkDoc()`（Shift+K）と `executeGrep()`（`gr`/`:grep`）の双方について、バックグラウンドスレッドで検索し `SwingUtilities.invokeLater` でUIへ結果を反映する「真の非同期化」が可能か調査した。
  - **見送った理由**: このプロジェクトのテストは `.claude/skills/editor-testing-strategy` に従い、`ed.processKey(...)` を呼んだ**直後に**同期的に結果を `assertEquals` する自作ハーネス（JUnit不使用・イベントループを回さない）である。実際に `test/dev/javatexteditor/editor/NativeReferenceSearchTest.java`（`gr` 押下直後に `*grep*` 疑似バッファへの切り替えを同期assert）と `test/dev/javatexteditor/editor/JumpBackTest.java`（Shift+K押下直後に同期assert）の2つが、まさにこの同期契約に依存している。`SwingUtilities.invokeLater` で結果反映を遅延させると、これらのテストは「バッファがまだ切り替わっていない」タイミングでassertすることになり、素朝に失敗する（テスト自体をポーリング式に書き換える大掛かりな改修が別途必要になる）。CLAUDE.mdが重視する「学習目的のシンプルさ」（JUnit不使用・素朴なmainメソッド式ハーネス）とも相性が悪いため、今回は非同期化を見送った。
  - **代わりに採用した対策**: `executeGrep()`（`gr`・`:grep` の両方が経由する）の `projectSearcher.search()` 呼び出しは、これまでタイムアウトが一切無く完全に無制限にEDTをブロックしていた（Shift+K側は前回修正済みだったが、grep側は見落としがあった）。`withSearchTimeout()` を汎用化した `withTimeout()` で同じ `PROJECT_SYMBOL_SEARCH_TIMEOUT_MS`（1500ms）の上限を適用し、タイムアウト時は「search timed out」を表示するようにした。同期契約は維持したまま、最悪ケースの固まる時間を「無制限」から「最大1.5秒」に抑える。
  - **今回あえて変更しなかった値**: `PROJECT_SYMBOL_SEARCH_TIMEOUT_MS` 自体（1500ms）は据え置いた。実測で `resolve("buffer", ...)` がこのリポジトリ規模で約450msかかったため、これより大幅に短くすると通常サイズのプロジェクトでもタイムアウトしてJDK側フォールバックに落ちてしまう（＝本来見つかるはずのシンボルが見つからなくなる）リスクがあり、テストのCI環境が遅い場合のフレーキー化も懸念される。真に「一瞬も固まらない」体験を実現するには、上記の非同期化（テストハーネスの再設計込み）が必要であり、今回のスコープを超えるため見送った。
- **調査（4回目）でタイムアウトの実測値を確認**: `ProjectSearcher.search()` を小さなテキストファイルで実測したところ、15,000ファイルで552ms・50,000ファイルで2,391ms・150,000ファイルで4,621ms（このコンテナの高速SSD・ウイルススキャン無し環境）。単一スレッドでの逐次I/O走査という性質上、対象ファイル数に比例して時間がかかり、この速い環境でも約3〜4万ファイルを超えると1500msのタイムアウトに達することを確認した。作業ディレクトリの既定値がホームディレクトリになりうる（`~/.cache`/`~/.npm`/`~/.m2`/ブラウザキャッシュ等で数万〜数十万ファイルに達するのが普通）ことと、`ProjectSearcher` が `node_modules`/`.gradle`/`.idea`等をスキップしない（③の「意図的」という既存コメント）ことの組み合わせが、タイムアウトの最大の要因であると特定した。
- **`gR` / `:grep!` / `\f!` / `\g!`（bang付き全ファイル検索）を追加し、上記③の「意図的に変更しない」という判断を正式に更新した**。ユーザーから「bangを付けたら作業ディレクトリ配下の全ファイルを走査するように」と明示的な指示があったため、これを機に `ProjectSearcher` のデフォルト挙動を `FileNameSearcher.SKIP_DIRS`（`.git`/`build`/`target`/`.gradle`/`node_modules`/`.idea`/`.vscode`）に統一した。
  - **`ProjectSearcher.search(baseDir, pattern)`** はデフォルトで `FileNameSearcher.SKIP_DIRS`（`DEFAULT_SKIP_DIRS`として参照）を適用するようになった（旧: `.git`/`build`/`target`のみ）。`search(baseDir, pattern, boolean fullScan)` オーバーロードを追加し、`fullScan=true` の場合はディレクトリスキップを一切行わない。
  - **`FileNameSearcher.search(baseDir, pattern)`** にも同様の `search(baseDir, pattern, boolean fullScan)` オーバーロードを追加した（`\f!` 用）。
  - **`gr`（NORMAL モード2打鍵、`goToReferences(false)`）はそのまま維持**し、**`gR`（`g` の後に Shift+R）を新設して `goToReferences(true)`** とした。`prev=='g'` の2打鍵シーケンス判定は既存の `matches(keyCode, keyChar, ...)` ヘルパーが `keyCode` 一致を優先するため大文字/小文字を区別できず（`VK_R` は shift の有無に関係なく同じ）、この2つの判定だけは `keyChar == 'r'` / `keyChar == 'R'` の直接比較に変更している。vimに無い独自拡張だが、`d`/`D`・`c`/`C`・`p`/`P` 等「小文字=通常、大文字=強制的な版」という vim の慣例に倣った命名。
  - **`:grep!` colon コマンド**を追加した（`cmd.startsWith("grep! ")` を `cmd.startsWith("grep ")` より先に判定）。vimの `:w!`/`:q!` と同じ「bang = 強制/無視」の慣例に合わせた。
  - **`\f!pattern` / `\g!pattern`**: FILESEARCH モードの入力バッファ（`fileSearchBuffer`）の**先頭文字が `!` かどうか**で判定する（キー入力のタイミングではなく、Enter時にバッファ全体を見て判定するため、`\f`/`\g` 2打鍵の実行タイミングを変える必要がなく安全）。`!`はパターンから取り除いてから検索に渡す。
  - **タイムアウト・2MB上限は bang の有無に関わらず両方とも適用される**（`fullScan` はあくまで「どのディレクトリを対象にするか」の指定であり、「EDTを保護する安全装置」とは独立した軸のため）。
  - **テスト**: `test/dev/javatexteditor/search/BangSearchTest.java`（10テスト）で、`ProjectSearcher`/`FileNameSearcher`単体と、`gr`/`gR`・`:grep`/`:grep!`・`\g!`・`\f!`経由の統合動作の両方を確認済み。

## F10/F11/F12（プロジェクト全体のコンパイル・実行）の設計決定事項

ユーザーとの事前確認により以下の仕様で確定した（実装前に対話で1つずつ決定）。

- **対象は「エディタが現在開いている作業ディレクトリ（`:cd`で設定した`projectRoot`）配下の任意のJavaプロジェクト」**であり、JavaTextEditor自身を自己ビルドする専用機能ではない（汎用IDE的機能）。
- **F10（コンパイル）**: `javax.tools.JavaCompiler` で `projectRoot` 配下の全 `.java` を走査してコンパイルし、`.class` を `bin`（`dev.javatexteditor.projectbuild.ProjectBuilder.OUTPUT_DIR_NAME`）に出力する。外部 `javac` プロセスは起動しない。ソース走査は `FileNameSearcher.SKIP_DIRS`（`.git`/`build`/`target`/`.gradle`/`node_modules`/`.idea`/`.vscode`）に加え出力先自身の `bin/` もスキップする。`bin/` の実際の配置場所は `ProjectBuilder.binDirFor()` が解決する（後述の追記参照）。
- **F11（実行）**: 対象クラスは `dev.javatexteditor.projectbuild.MainClassFinder` が `projectRoot` 配下を正規表現で走査し `public static void main(String[])` を持つクラスを索引化して決定する（javac AST解析は使わない。WordIndexと同じ「軽量な正規表現ベース」の理由づけ）。1件なら即実行、複数あれば ⑳ `telescope-picker` の疑似バッファ選択UI（`MainClassPicker`。2026-07以降は`\f`/`\g`と同じ表示方式。旧: 3ペインオーバーレイ）を流用して選択させる。`bin/` に `.class` が1つもない（＝F10未実行）場合はエラー表示のみで実行しない。実行は `ProcessBuilder` による別プロセス起動（`java -cp <binDir> <FQCN>`、`binDir` は `ProjectBuilder.binDirFor()` で解決）とし、対象アプリのGUI/標準入出力がエディタ自身のJVMを汚染しないようにした。既に前回起動した実行プロセスが生きていれば `destroy()` してから起動し直す（多重実行防止）。
- **F12**: F10を実行し、成功した場合のみ続けてF11相当（mainクラス解決→実行）を行う。F10が失敗した場合はF11側の処理を行わない。
- **出力表示**: コンパイル結果・実行結果はいずれも既存の `:grep`/`:rename` と同じ「疑似バッファ」パターン（`*compile*`・`*run*`。`pushBuffer()`を呼ばず直接 `buffer` を差し替えるため、Ctrl+Uの履歴には積まれない）で表示する。専用のガター描画・オーバーレイは追加していない。
- **有効モード**: NORMALモードのみ。`F2`（診断表示）と同様、`KeymapRegistry` を経由せず `Main.java` のグローバルキーイベントディスパッチャで直接ハードコード処理する（Fキー全般がこの方式）。
- **スレッド設計**: コンパイル・mainクラス検索・プロセス実行はいずれも `Thread.ofVirtual()` のバックグラウンドスレッドで行い、`SwingUtilities.invokeLater` でEDTに結果を反映する（既存の `runCompileAnalysis`／auto-import と同じ非同期パターン）。プロセスの標準出力/標準エラーは `redirectErrorStream(true)` でマージして捕捉し、プロセス終了後にまとめて `*run*` バッファへ表示する（ストリーミング表示はしない）。そのため、標準入力を要求するプログラム（`Scanner`等によるインタラクティブ入力）は正しく動作しない既知の制約として残る。
- **新規クラス**: `dev.javatexteditor.projectbuild.BuildResult`/`BuildDiagnostic`/`ProjectBuilder`/`MainClassFinder`、`dev.javatexteditor.telescope.MainClassPicker`。`BuildDiagnostic` は既存の `analysis.CompileDiagnostic`（現在編集中の1ファイルのガター表示専用、filePathを持たない）とは別レコードにした。F10は複数ファイルの診断をfilePath付きで扱う必要があり、用途が異なるため意図的に分離した。
- **テスト**: `test/dev/javatexteditor/build/ProjectBuilderTest.java`（`test/dev/javatexteditor/projectbuild/ProjectBuilderTest.java`。23テスト）で `ProjectBuilder`/`MainClassFinder` の純粋ロジックを検証。実際の子プロセス起動を伴う `Main.runJavaClass` はGUI/OS依存のため自動テスト対象外（既知のテストギャップ。⑫⑳と同様の理由）。
- **既知の制約**: OSやウィンドウマネージャによっては `F11` がフルスクリーン切り替え等のショートカットと衝突する場合があるが、アプリケーション側では制御できないため対応しない。
- **追記: `bin/` の配置場所を「`src` フォルダの親ディレクトリ」に固定した（`ProjectBuilder.binDirFor()`）**。当初は単純に `projectRoot`（`:cd` で設定した現在の作業ディレクトリ）直下の `bin/` を使っていたが、「現在作業しているディレクトリが `src` フォルダ配下だとしても、`src` フォルダが存在するディレクトリまで遡って `bin/` を確認・作成し、そこにまとめてほしい」という要望があった。理由: `:cd` でプロジェクトルート配下の深いパッケージディレクトリ（例: `src/dev/javatexteditor/` 配下）に移動した状態で F10/F11/F12 を実行すると、従来実装では `bin/` がそのパッケージディレクトリの中に作られてしまい、`src` と `bin` が兄弟ディレクトリでなくなる問題があった。
  - **`ProjectBuilder.binDirFor(projectRoot)`**（新設・`public`）が唯一の解決経路。内部の `resolveProjectBaseDir(projectRoot)` が `projectRoot` から祖先ディレクトリを1段ずつ遡り、`src` を直下に持つ最初のディレクトリ（＝プロジェクトルート）を見つけてその配下の `bin/` を返す。`compile()`（`Files.createDirectories` で無ければ作成）・`hasCompiledClasses()`・`Main.runJavaClass()`（実行時クラスパス）の3箇所すべてがこの同じメソッドを経由するため、コンパイル先と実行時クラスパスは常に一致する。
  - **どの祖先にも `src` が見つからない場合は従来どおり `projectRoot/bin` にフォールバックする**。`src` を持たない一時ディレクトリ構成で書かれていた既存の `ProjectBuilderTest` 群（`Files.createTempDirectory` 直下に `.java` を置くだけで `src/` を作らない）との後方互換のため。
  - **`MainClassFinder` のソース走査起点は変更していない**（引き続き `projectRoot` 配下を走査する）。今回の要望は「`bin/` をどこに置くか」に限定されており、mainクラス探索の対象範囲を変える話ではないため。
- **追記: F10/F11/F12押下時にユーザー指定の追加クラスパス（複数ディレクトリ）を入力できるようにした**。
  - **経緯**: `res/`（画像等のリソース）フォルダは自動ではクラスパスに追加されない（`bin/`のみが既定のクラスパス）ため、`ClassLoader.getResource()`等でリソースを読みたい場合は明示的にクラスパス指定が必要、という質問への回答を兼ねて実装した。
  - **UI**: `ModalEditor`に`Mode.CLASSPATH_INPUT`を新設した。`enterClasspathInput(String label, Consumer<List<Path>> callback)`でF10/F11/F12いずれかのラベルとともに入力待ちに入り、ステータス行に`"F10 classpath (カンマ区切り, Enter=確定, Esc=スキップ): "`のようなプロンプトを表示する（FILESEARCH/COMMAND等と同じ、`KeymapRegistry`を経由せず`processClasspathInputKey()`で直接キーを処理する疑似モードパターンを踏襲）。Enterでカンマ区切り文字列を`resolveRelativeToProjectRoot()`（`:e`/`:w`と共通）でprojectRoot基準の絶対パスへ解決した`List<Path>`を確定する。**Escは「クラスパス追加をスキップする」の意味であり、コンパイル/実行そのものは中断せず空リストのまま続行する**（ユーザー要望通り）。
  - **F10（コンパイル）**: `ProjectBuilder.compile(Path, List<Path>)`オーバーロードを追加し、非空なら`StandardJavaFileManager.setLocation(StandardLocation.CLASS_PATH, ...)`でjavacのクラスパスに追加する。従来の`compile(Path)`は空リストを渡す後方互換オーバーロードとして残した。
  - **F11（実行）**: `Main.runJavaClass()`に`List<Path> extraClasspath`引数を追加し、`java -cp <binDir><pathSeparator>extra1<pathSeparator>extra2...`のように`bin/`の後ろに連結する。**`bin/`は常にクラスパスの先頭に含まれる**（ユーザー指定が空でも実行は従来通り動作する）。
  - **F12**: F10とF11で同じ`extraClasspath`（1回のプロンプトで入力した値）を再利用する。F10とF11で別々に尋ねることはしない。
  - **main複数候補時の持ち越し**: F11でmainクラスが複数見つかりtelescope-picker（`MainClassPicker`）で選択待ちになる場合、`ModalEditor.setOnRunMainClassSelected`は`createLeaf()`内で1回だけ固定登録されているコールバックのため、選択確定時点では元のクロージャに`extraClasspath`を持たせられない。そのため`Main.pendingRunExtraClasspath`（`static List<Path>`）に一時保存し、選択確定時のコールバックがそこを読む方式にした（`runningProcess`と同種の「単一static状態で足りる」という既存の割り切りに倣った。複数ペインで同時にF11を使うケースはスコープ外）。
  - **意図的にスコープ外とした点**: 追加クラスパスの入力履歴・補完（`:cd`のTAB補完のような）は実装していない。毎回手入力が必要。
- **追記: `*compile*`/`*run*` 疑似バッファの先頭行に、実際に発行したjavac/java相当のコマンド文字列を表示するようにした**。
  - **F10/F12（コンパイル）**: `BuildResult`に`String command`フィールドを追加した。`ProjectBuilder.compile()`が実際に`javax.tools.JavaCompiler`へ渡したオプション（`-d <binDir>`・`-cp <extraClasspath>`（指定時のみ）・`-proc:none`）と全ソースファイルの絶対パスから`"javac -d ... [-cp ...] -proc:none <src1> <src2> ..."`という表示用コマンド文字列を組み立て、`BuildResult.command()`として返す。ソース走査失敗・対象ファイル0件・コンパイラ未検出・出力先作成失敗などコンパイルを実際には試みなかった早期リターンでは`command()`は空文字列になる（＝表示しない）。実際にはJDK標準APIによるin-process呼び出しであり外部`javac`プロセスは起動しないが（本ファイル冒頭のF10の設計決定事項どおり）、ユーザーへの透明性のためAPIに渡した内容を等価なコマンドライン表記に変換して見せている。
  - **F11/F12（実行）**: `Main.runJavaClass()`はもともと`ProcessBuilder("java", "-cp", classpath, fqcn)`で実プロセスを起動しているため、この実引数から組み立てた`"java -cp <classpath> <fqcn>"`をそのまま`ModalEditor.showRunOutput(command, fqcn, output, exitCode)`の第1引数として渡す（F10と異なり、これは本当に実行されたコマンドそのもの）。
  - **表示位置**: `showCompileResult()`/`showRunOutput()`は`command`が非空ならバッファの1行目に配置し、2行目以降に従来どおりの`*compile* SUCCESS/FAILED — N file(s)`／`*run* <fqcn> — exit code N`サマリと診断/実行出力が続く。
  - **テスト**: `ProjectBuilderTest`に3テスト追加（`command()`がjavacで始まる・binDir/ソースパスを含む・`-cp`と追加クラスパスを含む・早期リターン時は空文字列）。`test/dev/javatexteditor/editor/BuildOutputCommandTest.java`（新設・3テスト）で`showCompileResult`/`showRunOutput`がバッファ1行目にコマンドを配置すること、`command`が空の場合は1行目を追加しないことを検証。
- **バグ修正: クラスパス入力プロンプトがキー入力なしでは描画されない不具合**。`enterClasspathInput()`（F10/F11/F12押下時にステータス行へプロンプトを表示するメソッド）は`Main.java`のグローバルキーイベントディスパッチャから`processKey()`を経由せず直接呼ばれる。`syncCanvas()`（`buffer`/`statusMessage`等のモデル状態を`EditorCanvas`へ反映する唯一の経路）は`processKey()`の末尾でのみ呼ばれる`private`メソッドのため、`enterClasspathInput()`はモデル状態（`mode = CLASSPATH_INPUT`）を変更するだけで画面には一切反映されず、次にユーザーが何かキーを押して`processKey()`が呼ばれて初めて（そのキー処理の結果と合わせて）ようやくプロンプトが表示される、という不具合があった。
  - **修正**: `enterClasspathInput()`の末尾に`syncCanvas()`呼び出しを追加した。
  - **同根の不具合を横展開して修正**: `Main.java`のバックグラウンドスレッド完了コールバック（`SwingUtilities.invokeLater`内）から`processKey()`を経由せず直接呼ばれる他の公開メソッド（`showCompileResult()`・`showRunOutput()`・`enterMainClassPicker()`）も同じ理由で`syncCanvas()`を呼んでいなかったため、同様に末尾へ追加した（`canvas.repaint()`をMain.java側で呼んでいても、`repaint()`は`EditorCanvas`が保持するキャッシュ済みの`text`/`commandLineText`フィールドを再描画するだけで、`syncCanvas()`が行う「`ModalEditor`の`buffer`から`EditorCanvas`へ値をコピーする」処理の代わりにはならない）。
  - **テスト用に`EditorCanvas.getCommandLineText()`を新設**した（既存の`setCommandLineText()`とペアになる読み取り専用アクセサ。他の`get*`/`is*`アクセサと同じ「テスト・外部連携用」の位置づけ）。`ClasspathInputTest`に`testPromptRendersImmediatelyWithoutKeyPress()`を追加し、`enterClasspathInput()`直後（キー入力なし）に`canvas.getCommandLineText()`がプロンプト文言を返すことを回帰テストとして固定した。

## F10/F11/F12（`*compile*`/`*run*`疑似バッファ）でEscを押すと表示前の元バッファに戻る

- **要望**: `*compile*`/`*run*`疑似バッファを表示中にEscキーを押したら、F10/F11/F12を実行する直前に開いていたバッファへ戻れるようにしてほしい。
- **実装**: jdk-source疑似バッファ（`saved*`/`inJdkSourceBuffer`）と同じ「一時退避→復元」パターンを踏襲した。`outputSavedBuffer`/`outputSavedFilePath`/`outputSavedCursorRow`/`outputSavedCursorCol`/`outputBufferActive`（`ModalEditor`）を新設し、`saveBufferBeforeOutput()`を`beginCompileOutput()`/`beginRunOutput()`（F10/F11/F12の実際のストリーミング開始点）の冒頭で呼ぶ。`outputBufferActive`が既に`true`の場合（F12のcompile→run連続表示、または同じ疑似バッファ表示中の連続F10等）は再保存せず、**最初に退避した内容を保持し続ける**ようにした（F12でEscを押した場合に`*compile*`ではなく本当にF12押下前のバッファへ戻れるようにするため）。
- **キー処理**: `processNormalKey()`の`inJdkSourceBuffer`のqハンドラと同じ並びに、`outputBufferActive && keyCode == KeyEvent.VK_ESCAPE`の早期分岐を追加した（既存のESC早期分岐＝`pendingSequence`を`"ESC"`にする無条件上書き、より**前**に置く必要がある。後に置くと既存のESC処理に食われて疑似バッファ判定に到達しない）。`closeOutputBuffer()`が復元処理を行い、`closeJdkSourceBuffer()`と同じ構造（`buffer`/`currentFilePath`/`cursorRow`/`cursorCol`を退避値へ戻し、退避フィールドをクリア）にした。
- **`showCompileResult()`/`showRunOutput()`にも`saveBufferBeforeOutput()`を追加**した。Main.javaからの本番経路は必ず`beginCompileOutput()`/`beginRunOutput()`を先に呼ぶため実質的に到達済みだが、テスト等からこれらを直接呼ぶ場合にも同じEsc復帰が効くようにするための防御的な配置（`outputBufferActive`の二重保存防止ガードがあるため副作用はない）。
- **意図的にスコープ外とした点**: Ctrl+U/Ctrl+P・SPC+b（BufferPicker）からの`*compile*`/`*run*`アクセス（本ファイル既存節「F10/F11をSPC+bからいつでも再度開けるようにした」参照）とは独立した別経路。Escによる退避復元は「直前に開いていたバッファへの一発復帰」のみを提供し、SPC+bのキャッシュ機構（`lastCompileBufferText`/`lastRunBufferText`）とは連動しない。
- **テスト**: `test/dev/javatexteditor/editor/BuildOutputCommandTest.java`に3テスト追加（計20テスト）。`*compile*`表示中のEsc・`*run*`表示中のEsc・F12相当（compile→run連続表示）でのEscがそれぞれ元のバッファ内容へ正しく戻ることを検証。

## `:pr`コマンド（F10/F11/F12用プロジェクトルートの固定）の設計決定事項

- **経緯**: 「実行時にクラスパスを追加すると`:cd`で移動した現在ディレクトリ基準で解決されてしまう。クラスパスはプロジェクトルート基準にしてほしい」という不具合報告から。調査したところ、`ModalEditor`の`projectRoot`フィールド（＝`getProjectRoot()`）は実体としては`:cd`の作業ディレクトリそのもの（`WD_MANAGER`リスナーが`setProjectRoot()`で同期）であり、grep/telescope/FILER/`:e`/`:w`/auto-import/シンボル解決/F10/F11/F12のすべてがこの単一の値を共有していた。つまり「プロジェクトルート」と「`:cd`作業ディレクトリ」が同一だったのが原因。
- **方針決定**: `AskUserQuestion`で3点確認して確定した。①適用範囲＝**F10/F11/F12全体**（クラスパス解決に加え、コンパイルのソース走査・`bin/`配置・mainクラス探索・実行時作業ディレクトリも`:pr`ルート基準）。grep/telescope/FILER/`:e`/`:w`は従来どおり`:cd`現在ディレクトリを使い続ける。②指定方法＝**引数なしで現在ディレクトリを記憶**（`:pr`単体。別ディレクトリで再度`:pr`を打てば上書き）。③確認手段あり・解除コマンドは不要。
- **実装（`:cd`とは独立した第2のルート）**: `ModalEditor`に`projectRootOverride`（`Path`、`null`のとき`:cd`追従）を新設した。既存の`projectRoot`/`getProjectRoot()`（`:cd`作業ディレクトリ）には一切手を入れていない。新設の`getBuildRoot()`が`projectRootOverride != null ? projectRootOverride : getProjectRoot()`を返し、F10/F11/F12だけがこれを基準にする。
  - **`:pr`** = `projectRootOverride = getProjectRoot()`（その時点の`:cd`現在ディレクトリを記憶）。`:cd`でサブディレクトリへ移動しても`getBuildRoot()`は動かない。セッション中のみ保持し永続化しない（起動時`null`。`WorkingDirectoryManager`の`Preferences`永続化を廃止した既存判断と同じ思想）。
  - **`:pr?`** = 現在のプロジェクトルートを確認する（`:set option?`と同じvim風。未設定なら「未設定（:cd 追従: <dir>）」と表示）。設定コマンド（`:pr`単体）が「現在ディレクトリを記憶」の意味に確定したため、確認は別の綴りが必要で`:pr?`にした。解除コマンドは作っていない（別ディレクトリでの`:pr`上書きで足りるとのユーザー判断）。両方とも`executeCommand()`の`equals`完全一致分岐で、`:pwd`/`:cd`の隣に追加。`:pr`/`:pr?`は他コマンドと文字列衝突しない。
- **クラスパス解決の変更点**: `parseClasspathInput()`が使っていた`resolveRelativeToProjectRoot()`（`:e`/`:w`と共用・`getProjectRoot()`基準）から、新設の`resolveRelativeToBuildRoot()`（`getBuildRoot()`基準）へ切り替えた。`:e`/`:w`のパス解決は`getProjectRoot()`基準のまま変更していない（ユーザー方針どおり`:cd`追従）。絶対パス・`~`展開の扱いは両者で共通。
- **`Main.java`側の変更点**: F10/F11/F12のビルド/実行に関わる`editor.getProjectRoot()`呼び出し4箇所（`triggerRun`のbin判定・`triggerCompileAndRun`・`doCompile`・`setOnRunMainClassSelected`のmainクラス選択後実行）を`editor.getBuildRoot()`へ置換した。`bin/`は`ProjectBuilder.binDirFor()`が渡されたルートから`src`を持つ祖先を辿るため、`:pr`ルートが`src`を持てば従来どおり正しく解決される。**インラインのコンパイル診断（ガター表示、`analyzeWithProject`）は`WD_MANAGER.getWorkingDirectory()`基準のまま変更していない**（F10/F11/F12とは別機能でありユーザー指定スコープ外）。
- **テスト**: `test/dev/javatexteditor/editor/ProjectRootCommandTest.java`（新設・7テスト）。`:pr`が現在ディレクトリを記憶・上書き・`:pr?`の未設定/設定表示・`getBuildRoot()`のフォールバックと`:cd`非追従・そして中核として「`:pr`固定後に`:cd`でサブディレクトリへ移動しても追加クラスパスの相対パスが`:pr`ルート基準で解決される」ことを検証。`ClasspathInputTest`(12)・`BuildOutputCommandTest`(14)・`MainCommandTest`(7)ほか既存テストは無修正で全PASS（回帰なし。既知の`ScrollTest`2件FAILのみベースラインどおり）。

## `SystemStatsMonitor`（ステータス行のCPU/GPU表示）の設計決定事項

- **CPU項目は「温度」と「使用率(%)」の間で2度差し戻しがあった末、最終的に「使用率(%)」で確定した**。経緯: ①最初に温度→使用率に変更 → ②「Linux/Windows/Macいずれでも温度を表示できるようにしてほしい」という差し戻しでOS別3分岐の温度取得（Linux=`/sys/class/thermal`、Windows=WMI経由PowerShell、macOS=`osx-cpu-temp`）を実装 → ③「Windows11でCPU温度がN/Aになる」という報告に対し、native実装（C/C++・JNI）での解決を提案されたが、CLAUDE.md本文の「依存ライブラリ一切使用しない・javac直接呼び出し」という根本方針と矛盾するためユーザーに確認 → ④ユーザーが方針転換し「CPU/GPUとも温度ではなく使用率にし、N/Aになる場合はそもそも表示しない」との指示で最終確定。今後この項目を再び温度表示に戻す提案をする場合、上記②のOS別3分岐実装（Windows WMI/`MSAcpi_ThermalZoneTemperature`が多くの機種で非対応・macOSの`osx-cpu-temp`が未導入だと動かない、という既知の制約）を再発明しないよう、まずこの節を参照すること。
- **native実装（C/C++・JNI）は採用しなかった**。理由は上記の通りCLAUDE.mdの「依存ライブラリ一切不使用・`javac`直接呼び出し・ビルドツール不使用」という根本方針と衝突するため（JNIは3プラットフォーム分のネイティブライブラリのビルド・配布、Cコンパイラの導入、`scripts/build.sh`の拡張を必要とし、学習目的の「javac一発でビルドできる」というシンプルさを損なう）。この判断はユーザーへの確認の上で行われた。将来的にどうしても温度取得の精度を上げたい場合でも、まずJava標準API・OS標準コマンドの組み合わせで対応できないか検討し、native実装は最終手段とすること。
- **CPU使用率**は `com.sun.management.OperatingSystemMXBean#getCpuLoad()` から算出する（`readCpuUsagePercent()`）。JDK標準の実装がLinux/Windows/macOSいずれにも同梱しているシステム全体のCPU使用率取得APIのため、OS判定・外部コマンドいずれも不要で全プラットフォームで動作する。
- **GPU使用率**は`nvidia-smi --query-gpu=utilization.gpu`から算出する（`readGpuUsagePercent()`）。`nvidia-smi`コマンド自体はNVIDIAドライバがLinux/Windows双方でインストール時にPATHへ追加するため、OS判定なしで共通に試すだけでよい。GPU非搭載機・非NVIDIA GPU環境（AMD/Intel統合GPU等）・macOS（NVIDIAドライバ非提供）ではコマンド起動自体が失敗し、値が取れない。
- **取得できなかった項目は`N/A`と表示せず、ラベルから丸ごと省略する**（`refresh()`が`List<String> parts`に取得できた項目だけ追加し`String.join(" | ", parts)`で結合）。「ノートPCにGPUが無いのは想定内なので`N/A`は不要、そもそも表示しないでほしい」というユーザー要望に基づく。全項目が取得できない場合は`cachedLabel`が空文字列になり、`EditorCanvas`側の既存の`if (!statsLabel.isEmpty())`ガードでシステムステータス自体が非表示になる（この分岐自体は変更していない）。
- **サブプロセス起動・待機・エンコーディング処理は`runCommand(String... command)`ヘルパーに集約している**（GPU使用率取得の`nvidia-smi`呼び出しのみで使用）。`native.encoding`での読み取りは`.claude/skills/windows-batch-and-subprocess/SKILL.md`のルール3準拠（Windows環境でのnvidia-smi出力の文字化け対策）。
- **意図的に変更しなかった点**: メモリ使用率（`readMemoryUsagePercent()`）はJDK標準APIで元からクロスプラットフォームに動作するため変更していない。2秒間隔のバックグラウンド更新・EDT非ブロッキング読み取りの設計もそのまま維持した。

## 検索・補完機能の大文字小文字区別に関する設計決定事項

ユーザーから「単語検索・ファイル検索・グレップ検索・入力補完はすべて大文字小文字を区別せずヒットさせてほしい」という要望があり、各機能の実装を横断的に確認した。

- **`\g`/`gr`/`gR`/`:grep`/`:grep!`（`ProjectSearcher.search()`）**: 変更前は `Pattern.compile(pattern)` で大文字小文字を区別していた。`Pattern.CASE_INSENSITIVE` を追加した（`src/dev/javatexteditor/search/ProjectSearcher.java`）。
- **`/` パターン検索・`*`/`#` 単語検索（`ModalEditor.executeSearch()`）**: 同様に `Pattern.CASE_INSENSITIVE` を追加した。詳細・注意点は `.claude/skills/text-search/SKILL.md` の「注意点」節を参照。
- **`\f`（`FileNameSearcher`）は変更不要だった**: 実装当初から `Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)` で大文字小文字を区別しない設計になっていた。
- **Alt+/ 単語補完（`WordIndex`）は変更不要だった**: `TreeMap` のキーを `word.toLowerCase(Locale.ROOT)` に正規化してプレフィックス検索する設計になっており、元から大文字小文字を区別しない。
- **Ctrl+Space 補完（`CompletionIndex`/`CompletionScorer`）・SPC+f/SPC+//SPC+b（telescope `FuzzyMatcher`）は変更不要だった**: `CompletionScorer` は大文字小文字区別なしプレフィックス一致をスコアリング対象に含み、`FuzzyMatcher` は `query.toLowerCase()`/`target.toLowerCase()` で比較しており、いずれも元から大文字小文字を区別しない。
- **`:s` 置換コマンドは対象外**: `g`/`i` フラグで大文字小文字区別を明示的に切り替えられる設計（`.claude/skills/vim-substitution/SKILL.md`）であり、Vim互換の意味論を保つため既定を無条件で大文字小文字無視には変更していない。

## 作業時の方針

- 何かを実装・設計する前に、関連する`.claude/skills/`配下のSKILL.mdを必ず確認すること。
- 既存のSkillの内容と矛盾する実装をしようとしている場合は、実装を進める前にユーザーに確認すること。
- 新しい設計判断を行った場合、その判断と理由を該当するSKILL.md（またはこのCLAUDE.md）に書き残すこと。口頭の会話だけで終わらせない。

## 既知の未接続・二重定義（リファクタ調査 2026-07 時点）

次の開発者が片側だけ修正する事故を防ぐための記録。いずれも「消してよいか／どちらが正か」の仕様判断が未決定のため、判断せずに残してある（docs/REFACTORING_PLAN.md の P-10〜P-13・P-21・U-7 参照）。

1. **（2026-07 解消済み）NORMAL モード Ctrl+U/P のバッファ切替が二重実装だった問題**: 以前は `ModalEditor.processNormalKey` 冒頭のハードコード（`bufferHistory` スナップショット方式）が無条件に優先され、`switchToRelativeBuffer`（`Main.BUFFER_REGISTRY` を巡回する本来の `:bnext`/`:bprev` 相当の実装）には既定キーから到達しなかった。詳細は本ファイル末尾の「Ctrl+U/Ctrl+P のバッファ切替（:bnext/:bprev 方式への統一）」節を参照。
2. **COMMAND モードの registry 束縛は機能しない**: `processCommandKey` は KeymapRegistry を参照せず ESC/Enter/TAB をハードコードで処理するため、`KeymapRegistry` の COMMAND モード束縛（`enter.normal`/`execute.command`）は現状到達不能。外部（プラグイン）からの参照想定が不明なため削除しない。
3. **`CompletionIndex.refreshProjectSymbols()` は未使用**: 本番・テストとも呼び出しゼロ。Javadoc の「保存時に呼ぶ」想定で呼ぶ場合は、`ready==true` 後にバックグラウンドで `TreeMap` を更新すると EDT の `query()` と同期なしで競合するため、不変マップ差し替え等の並行更新対策が先に必要。
4. **`extension/` パッケージ（PluginLoader ほか）は本番経路から未接続**: `:plugin` 等の起動コマンドが未実装のため、動的コンパイル・プラグイン機構はテストからしか呼ばれない（ロードマップ③⑥は機構としては完了、UI 接続のみ未着手）。
5. **疑似バッファ退避2系統の相互作用は未定義**: jdk-source 疑似バッファ（`saved*` フィールド群）と `*cd候補*` 疑似バッファ（`cdSaved*` フィールド群）を重ねて使った場合の挙動は未定義・未テスト。
6. **`ScrollTest` の2ケース（halfPageUp 系）は恒常的に FAIL する**: Ctrl+U の仕様変更（半ページスクロール → バッファ履歴を前へ）にテストが追従しておらず、ベースライン時点で 18/20 PASS。テストを更新するかキー割当てを戻すかは未決定（REFACTORING_PLAN.md U-7）。どちらの修正も仕様判断を伴うため「ついでに」直さないこと。

## Ctrl+Alt+矢印によるペインリサイズの設計決定事項

画面分割中に現在のアクティブペインの縦横幅を伸縮する機能。実装前に `AskUserQuestion` でユーザーに以下を確認済み（詳細は `.claude/skills/gui-rendering-pipeline/references/pane-resize.md` 参照）。

- **矢印キーの意味は「現在ペインを伸縮する」に固定**した（tmux的な「境界を矢印方向へ動かす」方式は不採用）。Right/Down＝現在ペインを常に拡大、Left/Up＝常に縮小。分割の左右/上下どちら側に現在ペインがあっても意味が変わらない。
- **1回の入力あたりの増減量は固定ピクセル数**（`PANE_RESIZE_STEP_PX`）。Ctrl+Shift+矢印（フォントセルサイズ変更）と同じくキーリピートでの連続操作を想定。
- **入れ子分割（`:split`/`:vsplit`の組み合わせ）時は、アクティブペインの Swing コンポーネント階層を親方向へ辿り、キーの方向に対応する `orientation` を持つ最初の祖先 `JSplitPane` だけを調整する**。見つからなければ何もしない。`Main.java` の `PaneNode`/`Split` ツリー（構造管理専用で実際の `JSplitPane` インスタンスは持たない）は使わず、実際に画面に貼られている Swing コンポーネント階層を直接辿る方式にした（`buildComponent` はリーフの `EditorCanvas` を中間ラッパーなしで直接 `JSplitPane` の子にするため、`canvas.getParent()` を辿るだけで済む）。
- **最小ペインサイズは固定ピクセル数**（`PANE_RESIZE_MIN_PX`）でクランプする。
- **`KeymapRegistry` を経由せず、`Ctrl+W`（ペインフォーカス切替）・`Ctrl+Shift+矢印`（フォントセルサイズ変更）と同じ `Main.java` のグローバル `KeyboardFocusManager` dispatcher で直接処理する**。モードに依存せず動作する（テキスト編集操作ではなくウィンドウレイアウト操作のため）。
- **リサイズ結果は `:split`/`:vsplit`/ペインを閉じる操作をまたいで保持されない**（`rebuildLayout` が `JSplitPane` を毎回作り直すため）。既存の分割実装がそもそも `dividerLocation` を保持する仕組みを持っていないため、新規の劣化ではなく既存設計の延長として許容し、今回はスコープ外とした。
- **既知の制約**: `Ctrl+Alt+矢印` はOS/ウィンドウマネージャのグローバルショートカット（Linuxの仮想デスクトップ切り替え等）と衝突し得るが、ユーザーの明示的な指定に基づき採用した。
- **純粋ロジックの分離**: dividerLocationの新しい値を計算する部分だけを `dev.javatexteditor.ui.PaneResizeCalculator`（新設・Swing非依存）に分離し、`test/dev/javatexteditor/ui/PaneResizeCalculatorTest.java` で検証した。`Main.java` 側の実際の配線（Swing階層探索・`KeyboardFocusManager`）はF10/F11/F12と同様GUI依存のため自動テスト対象外（既知のギャップ）。

## Ctrl+U/Ctrl+P のバッファ切替（:bnext/:bprev 方式への統一）

- **不具合報告**: SPC+f（telescope）で複数ファイルを開いた場合、Ctrl+U/Ctrl+Pを押しても他の開いているファイルへ切り替わらなかった。
- **原因**: `ModalEditor.openTelescopeSelection()` はファイルを開く際に `buffer`/`currentFilePath` を直接差し替えるだけで、`pushBuffer()`（`bufferHistory` への追加）を呼んでいなかった。一方 `processNormalKey` 冒頭の Ctrl+U/Ctrl+P ハードコードは `bufferHistory`/`historyIdx` のみを見ていたため、telescope 経由で開いたファイルはこの履歴に反映されず、切替が効かなかった。
  - なお `switchToRelativeBuffer()`（`Main.BUFFER_REGISTRY` を `floorMod` で循環する、本来の `:bnext`/`:bprev` 相当の実装）は既に存在していたが、`KeymapRegistry` の `buffer.prev`/`buffer.next` 経由でしか到達できず、既定の Ctrl+U/Ctrl+P キーからは呼ばれていなかった（旧・既知の未接続問題1.）。
- **修正方針（ユーザー確認済み）**: Ctrl+U/Ctrl+P を完全に `switchToRelativeBuffer()` 一本化はせず、**ハイブリッド方式**を採用した。
  - **`currentFilePath != null`（ファイルを開いている通常のバッファ）の場合**: `switchToRelativeBuffer(-1)`/`switchToRelativeBuffer(+1)` を呼び、`Main.BUFFER_REGISTRY`（＝これまでに開いたファイルの一覧。`onFileOpened` コールバックで telescope/`:e`/FILER 等すべての経路から登録される）を循環する、Vimの `:bprev`/`:bnext` と同じ意味論で切り替える。
  - **`currentFilePath == null`（`:tutor`・`:enew` 等、ファイルパスを持たない疑似バッファ）の場合**: 従来どおり `bufferHistory`/`historyIdx` のスナップショット方式にフォールバックする。`BUFFER_REGISTRY` はファイルパスを持つエントリしか保持できないため、この方式を残さないと「チュートリアルを開く前のバッファに Ctrl+U で戻れる」という既存機能（チュートリアルモード節参照）が失われるため。
  - キー割り当て自体は変更していない（Ctrl+U=前方向/`:bprev`相当、Ctrl+P=次方向/`:bnext`相当のまま）。
- **`switchToRelativeBuffer()` の副変更**: 開いているファイルバッファが自分1件のみ（＝他に切替先がない）場合に何もフィードバックせず無反応だった挙動を、「他に開いているファイルバッファがありません」という `statusMessage` を出すように変更した。
- **意図的に変更しなかった点**: `switchToRelativeBuffer()` は既存同様、切替先ファイルを毎回ディスクから読み直す（`Files.readString`）。未保存の編集内容を保持したままバッファを切り替える仕組みは元から無く、今回もスコープ外（`openTelescopeSelection()` も同じ挙動）。
- **テスト**: `test/dev/javatexteditor/editor/BufferSwitchTest.java`（当初9テスト）。`Main.java` の `BUFFER_REGISTRY`/`registerBuffer` 相当をテスト内に最小限のフェイク実装として再現し、複数ファイルを開いた状態での Ctrl+P（前方）・Ctrl+U（後方）と、`:enew` のようなファイルパスなしバッファでの Ctrl+U フォールバックの両方を検証している。

### `:bnext`/`:bprev`/`:bn`/`:bp` コマンド追加とラップアラウンド廃止（後日変更）

- **経緯**: 「Vim完全互換ではなく複数バッファ間を`:bnext`/`:bprev`で移動できるだけのシンプル実装がほしい。末尾/先頭ではラップアラウンドしない」という依頼があったが、既存の `switchToRelativeBuffer()`（上記節）は `Math.floorMod` で末尾→先頭に循環する実装済みだった。新規に `id`/`name`/`content` を持つ独立の配列とAPIを作る案（依頼の字面どおり）は、この既存のCtrl+U/Ctrl+P実装と直接衝突するため、着手前にユーザーに確認し「既存を仕様変更（ラップアラウンド廃止）」を選択してもらった。
- **変更内容**: `switchToRelativeBuffer(delta)` の境界処理を `Math.floorMod` から `Math.max(0, Math.min(entries.size() - 1, currentIdx + delta))` のクランプ方式に変更し、末尾で`:bnext`/Ctrl+P・先頭で`:bprev`/Ctrl+Uを行ってもその場に留まるようにした（境界到達時は `statusMessage` で「これ以上次/前のバッファはありません」と表示）。`executeCommand()` に `bnext`/`bn`（`switchToRelativeBuffer(+1)`）と `bprev`/`bp`（`switchToRelativeBuffer(-1)`）を追加し、Ctrl+P/Ctrl+Uキーと同じ内部メソッドを呼ぶ形にした（新規の `Buffer{id,name,content}` 配列・`addBuffer()`等のAPIは作っていない。既存の `BUFFER_REGISTRY`/`BufferPicker.BufferEntry` 構造をそのまま利用）。
- **意図的に変更しなかった点**: `currentFilePath == null`（`:enew`等の疑似バッファ）時の `bufferHistory` フォールバック方式・Ctrl+U/Ctrl+Pのキー割り当てそのもの・ファイル内容をディスクから読み直す挙動（未保存編集を保持しない）は変更していない。
- **テスト**: `BufferSwitchTest` に4テスト追加（計18テスト）。既存2テストは循環（wrap）を前提にしていたためクランプ挙動に合わせて更新し、新規4テストで `:bnext`/`:bprev`/`:bn`/`:bp` コマンドの前進・後退・境界クランプを検証した。

### F10/F11 の *compile* / *run* 疑似バッファを統一バッファ一覧（Ctrl+U/Ctrl+P・SPC+b）に統合

- **要望**: 「一回開いたバッファは全て一つのバッファリストに含め、Ctrl+U/Ctrl+Pで前後移動・SPC+bで一覧選択できるようにしてほしい。コンパイル結果・実行結果（`*compile*`/`*run*`）のバッファも同じ一覧に含め、Ctrl+Dを押さない限りカーソル下の指定バッファ以外は消えないようにしてほしい」という依頼。
- **調査結果**: SPC+b（`enterTelescope("buffers")`）は元々 `bufferListSupplier`（`Main.BUFFER_REGISTRY` 由来の実ファイル一覧）に加え、`lastCompileBufferText`/`lastRunBufferText`（直近のF10/F11内容キャッシュ）が非nullの場合だけ `*compile*`/`*run*` エントリをその場で追加表示する、という**SPC+b専用の場当たり的な統合**が既にあった。しかし ①`switchToRelativeBuffer()`（Ctrl+U/Ctrl+P の実体）はこの統合を経由せず `bufferListSupplier` のみを見ていたため `*compile*`/`*run*` へは到達できず、②SPC+b内のCtrl+D（バッファ削除、前節「BufferPickerのバッファ削除」参照）は `onBufferDelete`（`Main.BUFFER_REGISTRY` からの除外）しか行わないため、`*compile*`/`*run*` エントリに対しては実質何も削除されない黙殺バグになっていた。
- **修正方針**: `*compile*`/`*run*` を `Main.BUFFER_REGISTRY`（static・全ペイン共有）へ登録する案は採らず、**ModalEditorインスタンスごとのキャッシュのまま、SPC+bとCtrl+U/Ctrl+Pの両方が同じ1つのヘルパーから一覧を組み立てる**方式にした。理由: `BuildOutputCommandTest.testSpcBOmitsPseudoBuffersBeforeTheyExist`（F10/F11未実行の新規`ModalEditor`ではSPC+b候補に出ないことを検証する既存テスト）が、キャッシュをインスタンス単位に保つ設計に依存しているため。また F10/F11 は常に「今操作しているペイン」に対して実行される機能であり、そのペイン内でCtrl+U/Ctrl+P・SPC+bから戻れれば要望を満たせるため、複数ペイン間でのグローバル共有は過剰と判断した（`Main.BUFFER_REGISTRY` は実ファイルの内容をディスクから再取得できるため共有可能だが、`*compile*`/`*run*` はディスクに実体を持たない一過性の内容であり、生成した本人のペイン以外から中身を取得する手段がない）。
  - **`ModalEditor.allKnownBufferEntries()`（新設）**: `bufferListSupplier.get()`（実ファイル）＋ `lastCompileBufferText`/`lastRunBufferText` が非nullなら `*compile*`/`*run*` を末尾に追加、という一覧を返す。`enterTelescope("buffers")` と `switchToRelativeBuffer()` の両方がこれを使うことで、「同じ一つのバッファリスト」を実現している。
  - **`compileBufferOwner`/`runBufferOwner`（新設フィールド、`UndoablePieceTable`）**: `outputErrorLinesOwner` と同じ「参照一致による自動失効」パターン。`showCompileResult()`/`beginCompileOutput()` で `compileBufferOwner = buffer`、`showRunOutput()`/`beginRunOutput()` で `runBufferOwner = buffer` をセットするだけで、他のあらゆる `buffer = new UndoablePieceTable(...)` 差し替え箇所（25箇所超）に手を入れなくても、バッファが差し替わった時点で自動的に「もう *compile*/*run* を見ていない」と判定できる。Ctrl+U/Ctrl+P の分岐条件（`currentFilePath != null` → 統一一覧を使う）に `isViewingPseudoOutputBuffer()`（`compileBufferOwner == buffer || runBufferOwner == buffer`）を追加し、`*compile*`/`*run*` 表示中も統一一覧側の経路を使うようにした（それ以外の `currentFilePath == null` な疑似バッファ、`:enew`/`:tutor`等は従来どおり `bufferHistory` フォールバックのまま）。
  - **`currentBufferEntryIdentity()`（新設）**: 統一一覧内での現在地判定用。実ファイルは `currentFilePath`、`*compile*`/`*run*` 表示中は owner 参照一致から `PSEUDO_COMPILE_PATH`/`PSEUDO_RUN_PATH` を返す。`switchToRelativeBuffer()` はこれで現在のインデックスを特定してから delta 分移動する。
  - **`openBufferEntry(target, lineNumber, verb)`（新設）**: `openTelescopeSelection()`（SPC+b選択）と `switchToRelativeBuffer()`（Ctrl+U/Ctrl+P）が重複して持っていた「実ファイル/`.class`プレビュー/バイナリ/`*compile*`・`*run*`疑似バッファをそれぞれ判定して開く」30行超のロジックを1箇所に統合した（CLAUDE.mdの「3行の重複は早すぎる抽象化よりよい」方針の範囲を明らかに超える重複だったため）。`*compile*`/`*run*` の復元は新設の `restorePseudoOutputBuffer(path)` に切り出し、`lastCompileBufferText`/`lastRunBufferText` からバッファを再構築して対応する owner フィールドを再設定する。
  - **Ctrl+D（`processTelescopeKey`）の修正**: カーソル下の項目が `PSEUDO_COMPILE_PATH`/`PSEUDO_RUN_PATH` の場合は `onBufferDelete`（実ファイル用、`Main.BUFFER_REGISTRY` 除外）を呼ばず、代わりに `lastCompileBufferText`/`lastRunBufferText` を直接 `null` にする。これにより削除後は `allKnownBufferEntries()` の結果から完全に外れ、新たにF10/F11を実行するまでSPC+b・Ctrl+U/Ctrl+Pのどちらからも二度と現れない（＝要望どおり「Ctrl+Dを押した対象だけが消え、それ以外は消えない」を`*compile*`/`*run*`にも適用）。
- **既知の限界**: `*compile*`/`*run*` は生成したペイン（`ModalEditor`インスタンス）のローカルキャッシュのままのため、`:split`/`:vsplit` で分割した**別のペイン**からは同じ内容にCtrl+U/Ctrl+P・SPC+bで到達できない（Ctrl+U/Ctrl+Pの節の「意図的に変更しなかった点」・共有バッファ機能の「スコープ外」節と同種の、複数ペイン間の同期は今回もスコープ外という判断）。単一ペインでの利用（F10/F11を押した直後にそのペイン内でCtrl+U/Ctrl+P・SPC+bを使う、という主要な使い方）では要望どおり動作する。
- **テスト**: `test/dev/javatexteditor/editor/BufferSwitchTest.java` に2テスト追加（計41テスト）。`testCtrlUCtrlPReachCompileAndRunBuffers`（統一一覧 `[実ファイル, *compile*, *run*]` をCtrl+U/Ctrl+Pで前後移動できること、末尾での非ラップアラウンド）、`testCtrlDOnCompileEntryRemovesItPermanently`（SPC+b内でカーソルを`*compile*`に合わせてCtrl+Dを押すと`*compile*`のみが一覧から消え`*run*`は残ること、削除後はCtrl+Pで実ファイルから直接`*run*`へ移動し`*compile*`を経由しないこと）を検証。

## `:wa` / `:qa` / `:qa!`（Vim互換の全保存・全終了コマンド）の設計決定事項

- **調査結果**: 実装前の時点で、このエディタには「最後の保存以降に変更があったか」を示す modified フラグが**一切存在しなかった**（`grep`で確認済み）。そのため既存の `:q` は常に無条件で終了/ペインを閉じており、`:q!`（強制終了）自体も未実装だった（`:q!` は unknown command になる）。「開いている全編集対象」に相当する単位は、単一 `ModalEditor` インスタンス＝1ペインであり、`:split`/`:vsplit` で複数ペインに分かれている場合のみ複数の生きた編集対象が同時に存在する（`Main.BUFFER_REGISTRY` は「これまでに開いたファイル一覧」であり、ペインを離れた時点でその内容は破棄されるため、生きた編集対象には数えない。Ctrl+U/Ctrl+Pの節の「意図的に変更しなかった点」と同じ前提）。
- **modified フラグの追加**: `UndoablePieceTable`（`insert`/`delete`/`undo`/`redo` の4メソッドのみがテキストを変更する唯一の入口）に `private boolean modified` を追加し、この4メソッドで `true` に、`markSaved()`（保存成功時に `ModalEditor.saveToFile()` から呼ぶ）で `false` に戻す方式にした。全編集操作が最終的にこの4メソッドを通ることを利用し、`ModalEditor` 側の個々の編集メソッド（`deleteCurrentLine`/`deleteBlock`等、数十箇所）には一切手を入れていない。
  - **既知の制約**: undo/redoで保存時点と文字列として一致する内容に戻っても `modified` は `false` に戻らない（「編集操作が行われたか」だけを見る単純な近似で、内容の厳密比較はしない）。Vim本家もこの近似に近い挙動をするため、実用上の乖離は小さいと判断した。
- **`:wa`（`saveAll()`）**: `allEditorsSupplier.get()`（後述）が返す全 `ModalEditor` のうち `buffer.isModified()` が `true` のものだけ、既存の `saveToFile(currentFilePath)` をそのまま呼んで保存する。`saveToFile` が `private` でも同一クラス内であれば他インスタンスのフィールド/メソッドにアクセスできるという Java の仕様を利用し、保存ロジックの複製・新規 public API 追加を避けた。失敗したファイルはパス（またはファイル名未設定時は `"(no file name)"`）を集めて `E: failed to save: ...` にまとめて表示し、どれが失敗したか分かるようにした。1件も対象が無ければ `"no changes to save"` を表示する（Vim は無言だが、単一バッファ実装であることが分かりやすいよう明示メッセージにした）。
- **`:qa`/`:qa!`（`quitAll(boolean force)`）**: `force=false` の場合、対象全 `ModalEditor` の `isModified()` を走査し、1件でも `true` があれば `E37: No write since last change for: <path1>, <path2> ... (add ! to override)` を表示して終了しない（Vimの実際のE37メッセージを踏襲）。`force=true`（`:qa!`）は判定をスキップして常に終了する。
  - **終了処理は `exitAllCallback`（既定 `System.exit(0)`）経由**にした。既存の `:q` 用 `exitCallback` は「ペインが複数あれば現在のペインだけ閉じる」という `:q` 固有の意味論を持つため流用せず、`:qa`/`:qa!` は分割の有無に関わらず常にアプリケーション全体を終了するという Vim の `:qa` の意味論に合わせて独立のコールバックにした（テストから `System.exit(0)` を差し替え可能にする目的も兼ねる。既存の `exitCallback` も同じ理由でテスト時に差し替え可能になっている）。
- **`allEditorsSupplier`**: `Supplier<List<ModalEditor>>` フィールドで、既定値は `() -> List.of(this)`（自分自身のみ）。単一ペイン運用時はこれで「全保存＝現在保存」「全終了＝現在の終了判定」という後方互換な近似になる。`Main.java` の `refreshCallbacks()` で `setMovePanePrevCallback` 等と同じ配線パターンにより `() -> allLeaves(root[0]).stream().map(Leaf::editor).toList()` に差し替え、画面分割中は実際に全ペインを対象にする（分割構成が変わるたびに再評価されるようSupplierの中で都度 `allLeaves(root[0])` を呼ぶ。固定リストをキャプチャしない）。
- **コマンド解析**: `executeCommand(String cmd)` 内の `if-else` チェーンに `cmd.equals("wa")`/`cmd.equals("wall")`（`:w`/`:w `より前）、`cmd.equals("qa")`/`cmd.equals("qall")`、`cmd.equals("qa!")`/`cmd.equals("qall!")`（`:q`/`:wq`より前）を追加した。すべて完全一致（`equals`）判定のため、`:q`（1文字）・`:q!`（未実装のまま、影響なし）・`:wq` と文字列としても衝突しない。`vim`の`:qall`/`:wall`エイリアスも合わせて実装した。
- **テスト**: `test/dev/javatexteditor/editor/WaQaCommandTest.java`（新設・14テスト）。単一バッファでの `:wa`（保存成功/変更なし/ファイル名未設定時の失敗報告）、複数 `ModalEditor`（`allEditorsSupplier` 差し替え）をまたいだ `:wa`（変更のあるものだけ保存）・`:qa`（いずれかに未保存があれば拒否）、`:qa!` の強制終了、既存 `:w`/`:q`（無条件終了という既存の未チェック挙動が変わっていないこと）との非衝突・非デグレを確認済み。

## 自動 import 挿入（⑯ auto-import-handler）の並び順を Eclipse 互換に修正

- **不具合**: `AutoImportHandler.applyImport()`/`applyImports()` は、新規 import 行を常に「既存 import 群の最後」に単純追記するだけで、並び替えを一切行っていなかった。そのため自動挿入を繰り返すと `java.util.Map` の後に `java.util.List` が来る、といったEclipseの「Organize Imports」とは異なる順序になっていた。
- **修正方針**: 新規 import を追加する際、既存 import 行もすべて解析し直し、import ブロック全体を Eclipse のデフォルト設定（Preferences > Java > Code Style > Organize Imports の既定値）と同じアルゴリズムで書き直す方式にした（単純追記ではなく「ブロック全体の再構築」）。
  - **グループ順**: `java` → `javax` → `org` → `com` → どれにも一致しない「その他」の順（`AutoImportHandler.IMPORT_GROUP_ORDER`）。パッケージ名の前方一致（完全一致 or `prefix + "."` で始まる）でグループを判定する。
  - **グループ内の並び**: FQN の `String#compareTo` によるアルファベット順（Eclipseの既定と同じ単純な文字列比較。大文字が小文字より前に来る）。
  - **static import**: 非 static のブロックより前に独立したブロックとして配置し、static ブロック自身も同じグループ順・アルファベット順で並べる。
  - **空行**: グループとグループの間には空行を1行だけ入れ（グループ内には入れない）、static ブロックと非 static ブロックの間にも同様に空行を1行入れる。
  - 実装は `AutoImportHandler.insertAndReorganize()`（新設・private）が担う。既存 import 行を正規表現（`IMPORT_LINE_PATTERN`）で再解析し、新規 fqn を加えた全 `ImportLine` 集合を `formatImportBlock()` で整形した文字列に組み立て、既存の import ブロック区間（最初の import 行〜最後の import 行）をまるごと delete して置き換える。既存の import が1件も無い場合は従来どおり `findImportInsertOffset()` の位置に新規ブロックを挿入する。
- **意図的にスコープ外とした点**: このアルゴリズムは `applyImport`/`applyImports`（新規 import 挿入時）にのみ適用した。`removeImport`/`removeUnusedImports`（既存 import の削除のみ）は並び替えを伴わないため変更していない。また、ユーザーが手で書いた import の並びを能動的に「整理」する `:organize-imports` 相当のコマンドは現状存在しない（`Ctrl+Shift+O` の `onOrganizeImports` は未使用 import の削除のみを行うコマンドで、並び替えは行わない。混同しないこと）。
- **テスト**: `test/dev/javatexteditor/analysis/AutoImportHandlerTest.java` に3テスト追加（計51テスト）。同一グループ内でのアルファベット順（`testApplyImportAfterExistingImport`、既存のアサーションを新仕様に合わせて更新）、`java`/`javax`/`com`混在時のグループ順＋グループ間空行（`testApplyImportEclipseGroupOrder`）、複数 import 一括追加時の同一グループ内ソート（`testApplyImportsSortsWithinSameGroup`）、static import が非 static より前に来ること＋境界の空行（`testApplyImportStaticBeforeNormal`）を検証。

## `Main.isJavaBuffer()` の判定基準変更（ファイルパス未設定時はデフォルトでJavaバッファ扱いしない）

- **不具合報告**: `.java`以外のファイルを開いていない状態（`currentFilePath == null`。`:enew`等の疑似バッファ）でも、auto-import・コンパイル解析（`setupCompileAnalysis()`のINSERT→NORMAL遷移・保存・Ctrl+Shift+O・バッファ変更デバウンス）が実行されてしまっていた。
- **原因**: `isJavaBuffer()`は`path == null || path.endsWith(".java")`という判定で、ファイルパス未設定を「従来どおり解析対象に含める」設計だった（2026-07-14導入時点の意図的な判断。本ファイル内の直前の修正コミットのJavadocに経緯あり）。
- **修正**: ユーザーの明示的な指示により、`path != null && path.endsWith(".java")`に変更した。**`.java`という拡張子が明示的に確定して初めてJavaバッファとして扱い、ファイルパス未設定時はデフォルトでJavaバッファとして扱わない**。`:enew`等の疑似バッファでauto-importやコンパイル解析が走らなくなるのは意図した挙動（`:w foo.java`等で`.java`拡張子付きのパスを設定して初めて解析対象になる）。

## 自動 import 挿入がプロジェクト内の別パッケージのクラスに対して働かない不具合の修正

- **不具合**: JDK標準APIクラス（例: `List`）は未定義シンボルとして自動でimport文が挿入されるが、
  自分のプロジェクトの別パッケージに作成した自作クラスは候補にすら出ず、自動挿入されなかった。
- **原因**: `AutoImportHandler.resolveCandidates()` が呼ぶ `ImportSuggester.suggest(simpleName)` は
  `JdkClassIndex.lookup(simpleName)` のみを見ており、そもそも自プロジェクトのクラスを探す経路が
  存在しなかった（JDKクラス索引はJDKのjrt:/を走査するもので、プロジェクトのソースは対象外）。
- **修正**: `dev.javatexteditor.analysis.ProjectClassSuggester`（新設）を追加した。`ProjectSearcher`
  で baseDir 配下を `\b(?:class|interface|enum|record)\s+SimpleName\b` にgrepし、ヒットしたファイルの
  うち「ファイル名がsimpleNameと一致するもの」（＝Javaの「publicトップレベル型はファイル名と一致する」
  慣例を利用し、内部クラス等の誤検出を避ける）だけを対象に、そのファイルの `package` 宣言を正規表現で
  読んでFQNを組み立てる。`ImportSuggester` に `suggest(String simpleName, Path baseDir)` オーバーロードを
  追加し、JDK候補と`ProjectClassSuggester`候補を`LinkedHashSet`でマージして返すようにした（`baseDir`が
  `null`の場合は従来どおりJDKのみを返す`suggest(String)`と同じ結果になる後方互換オーバーロード）。
  `AutoImportHandler.resolveCandidates()`にも同様に`baseDir`を取るオーバーロードを追加し、
  `ModalEditor.handleAutoImport()`から`getProjectRoot()`を渡すようにした。
- **キャッシュを持たない設計にした理由**: `WordIndex`/`CompletionIndex`のような起動時1回きりの索引ではなく、
  呼び出しの都度`ProjectSearcher`でディスクを検索する設計にした。これにより新規作成したばかりのファイル・
  別パッケージのファイルもインデックス再構築なしに即座に候補へ反映される（後述の「新規作成ファイルの
  コンパイル結果反映」不具合と同種の「作ってすぐ使える」という要件のため）。プロジェクト規模が大きい場合の
  性能劣化は、既存の`gr`/`Shift+K`と同じ`ProjectSearcher`を使うため同じ特性（2MB超のファイルはスキップ、
  タイムアウトは掛かっていない）を引き継ぐ。今回はauto-import自体がバックグラウンド仮想スレッドで実行
  されるため（`Main.runCompileAnalysis`）、既存のShift+K/grepで問題になったEDTブロッキングの心配はない。

## auto-import選択ポップアップの無限再発とimport挿入位置がpackage文より前になる不具合の修正（2026-07-25）

「Javaファイルを保存したタイミングでauto-importが実行され、複数候補がある場合の選択ポップアップ
（IMPORT_SELECTモード）が、何度選択しても際限なく再表示され続ける」「import文がpackage文より前に
挿入されてしまう」という2件の報告を調査したところ、**どちらも独立した別々のバグではなく、一部は
同じ根本原因（UTF-8 BOM未除去）を共有し、もう一部は別の根本原因（コンパイル解析の二重発火）を持つ**
ことが判明した。実機を操作できない環境のため、`ModalEditor`/`AutoImportHandler`/`CompileAnalyzer`を
直接呼び出す再現コード（`javac`実プロセスを使う本物の診断）で両方とも再現・修正確認済み。

- **根本原因1（両方の症状を同時に説明する）: UTF-8 BOM（先頭のEF BB BF = U+FEFF）が除去されずに
  バッファへ読み込まれていた**。`ModalEditor.readFileContentForBuffer(Path)`（`:e`・FILER・telescope・
  `\f`/`\g`・`gr`・Ctrl+U/Ctrl+P経由の全ファイルオープンが通る唯一の読み込み口。「`currentFilePath`
  の絶対パス統一」節と同じ「5箇所の分散処理を1箇所に集約済み」の恩恵で、直すべき箇所は1つで済んだ）が
  `new String(bytes, StandardCharsets.UTF_8)`でバイト列を文字列化する際、BOMの3バイトはそのまま
  U+FEFF文字としてデコードされる（BOMを検出して読み飛ばすのは`StandardJavaFileManager`のようなファイル
  単位のAPIが内部で行う処理であり、バイト列から`String`を直接構築する経路では行われないため）。
  実機再現の結果、これが2つの症状それぞれの直接原因になっていた:
  1. **import挿入位置がpackage文より前になる**: `AutoImportHandler.findImportInsertOffset()`は
     `line.stripLeading().startsWith("package ")`という単純な文字列比較でpackage行を検出するが、
     `String.stripLeading()`はU+FEFFを空白文字として扱わない（`Character.isWhitespace(0xFEFF)`は
     `false`）。そのため先頭にBOMが残っていると1行目が`"package "`で始まると判定されず、package行
     そのものが「見つからない」扱いになり、挿入オフセットが`0`（＝ファイルの本当の先頭＝BOM文字の前）
     になってしまう。
  2. **選択ポップアップの無限再発**: BOM付きのソースを`javax.tools.JavaCompiler`（このエディタの
     コンパイル解析はメモリ上の`String`をそのまま`JavaFileObject`として渡すため、ファイル単位の
     BOMスキップは効かない）に渡すと、`illegal character`から始まる構文エラーの連鎖が
     発生し、ファイル全体が正しく解析できない状態になる。この状態では自動生成された未定義シンボルの
     診断（`cannot find symbol`）が、importを何回挿入しても消えない（BOMという構文エラーそのものは
     解消されないため）。`AutoImportHandler.resolveCandidates()`は診断からこの未解決シンボルを毎回
     再検出し続けるため、`IMPORT_SELECT`の選択ポップアップが際限なく再表示される。
  - **修正**: `readFileContentForBuffer()`の末尾、UTF-8デコード直後に
    `if (text.startsWith("\uFEFF")) text = text.substring(1);`を追加し、先頭のBOM文字を1文字読み込み
    時点で除去するようにした。全ファイルオープン経路がこの1メソッドを通るため、修正箇所は1箇所のみ。
    保存（`:w`）はバッファの内容（＝BOM除去済み）をそのまま書き出すため、以後保存し直したファイルは
    BOMなしになる（意図した正規化。BOMをJavaソースに残すことに実用上のメリットはなく、往復での
    バイト完全一致を保証する設計にはしていない。この点はバイナリエディタ（`:b`）やクリップボードの
    ISO-8859-1可逆変換のような「バイト列を厳密に保持する」設計とは異なる別カテゴリの機能である）。
- **根本原因2（無限再発の症状を単独でも起こしうる、根本原因1とは独立の不具合）: コンパイル解析トリガの
  二重発火によるレース**。`Main.setupCompileAnalysis()`は`editor.setOnReturnToNormal(...)`（INSERT離脱時）
  と`editor.setOnSave(trigger)`（保存時）の両方に、実質同じ`trigger`（コンパイル解析→`handleAutoImport`）
  を登録している。INSERTモードから直接保存する`"save.from.insert"`アクション（`Ctrl+[`/`Ctrl+]`）は
  `onReturnToNormal`を呼んだ**直後**に`saveToFile()`（内部で`onSave`を呼ぶ）も呼ぶため、**同一内容に
  対して2つのバックグラウンド仮想スレッドが同時にコンパイル解析を開始する**（`:w`と`Esc`をほぼ同時に
  行った場合も、非同期解析が完了する前に両方のトリガーが発火すれば同様に発生しうる）。2つの解析は
  完了順序が保証されないため、以下のような実害のある競合が発生することを実機再現で確認した:
  1. 解析A完了 → 曖昧候補（例: `Date`は`java.util.Date`/`java.sql.Date`の2択）を選択UIで表示。
  2. ユーザーが`java.util.Date`を選択 → 正しく`import`が挿入される。
  3. 解析B（**古い**、選択前の診断結果を使っている）が遅れて完了 → `handleAutoImport()`を再実行。
     `resolveCandidates()`は現在のバッファ（既に`java.util.Date`をimport済み）から「既にimport済み」を
     除外するため、候補が`[java.sql.Date]`の1件だけに見えてしまい、**確認なしで自動的に誤ったimportが
     追加される**。結果、`java.util.Date`と`java.sql.Date`が同時にimportされ
     `reference to Date is ambiguous`という新たなコンパイルエラーが発生し、しかもこのエラーメッセージは
     `findMissingSymbols()`の`"symbol:"`パターンにマッチしないため自動修復もされず、恒久的に壊れた状態
     で残る。候補がJDKクラス索引の都合で3択以上になるシンボル（例:「List」は`java.util.List`/
     `java.awt.List`/`com.sun.tools.javac.util.List`の3択）では、この競合が起きるたびに残り候補数が
     1つずつ減っていくため、ユーザーには「選択してもポップアップが再度出る」ように見え、ユーザーが
     混乱して保存を繰り返す（＝トリガーの二重発火をさらに繰り返す）ことで症状が長引く悪循環になりうる。
  - **修正**: `Main.setupCompileAnalysis()`に`AtomicLong compileGeneration`（編集対象ごとに1つ、
    クロージャで保持）を導入した。`runCompileAnalysis()`は解析開始時に`generation.incrementAndGet()`で
    「自分の世代番号」を取得し、バックグラウンドスレッド完了時（`SwingUtilities.invokeLater`内）に
    `generation.get() != myGeneration`であれば（＝自分より新しい解析要求が発行済みなら）診断反映も
    `handleAutoImport()`呼び出しも行わずに黙って破棄する。これにより「後から届いた古い診断で状態が
    上書きされる」という競合そのものを構造的になくした（`outputErrorLinesOwner`/`binaryModeOwner`と
    同系の「参照/世代一致による古い結果の自動破棄」パターンの応用）。二重発火自体（`save.from.insert`が
    `onReturnToNormal`と`onSave`を両方呼ぶこと）は今回あえて温存した——`onReturnToNormal`はIME関連の
    副作用（`switchToHalfWidth`/`clearImeComposition`）も担っており、`"save.from.insert"`から
    どちらか一方だけを呼ぶよう手術するより、「複数の解析要求が競合しても最後の1つだけが必ず勝つ」
    という世代ガードの方が、他の非同期トリガー経路（デバウンスタイマー等）まで含めて汎用的に安全。
- **テスト**: `test/dev/javatexteditor/editor/BinaryFileOpenTest.java`に2テスト追加
  （`testOpenUtf8FileWithBomStripsBom`/`testOpenUtf8FileWithoutBomUnaffected`）し、`:e`経由でBOM付き
  ファイルを開くとBOMが除去されpackage文が先頭に来ることを回帰テストとして固定した。`Main.java`の
  世代ガード自体はGUI/バックグラウンドスレッドに依存するため、F10/F11/F12や他の非同期トリガーと同様
  自動テスト対象外（既知のテストギャップ）だが、`AutoImportHandler`/`CompileAnalyzer`/`ModalEditor`を
  直接呼び出す再現コードで、修正前は`import java.sql.Date;`が確認なしに追加され
  `reference to Date is ambiguous`エラーが残ることを確認し、`readFileContentForBuffer()`のBOM除去
  単体でも「BOM付きファイルではimportがpackage文より前に挿入され続ける」症状が解消されることを
  個別に確認済み。

## `currentFilePath` の絶対パス統一と新規ファイル作成時の不具合修正

複数の不具合報告（「新しく作ったファイルが再度開かないと正しくコンパイル結果が反映されない」
「新しくファイルを作ったらバッファの遷移が0個になってしまう」）を調査したところ、共通の原因が
`currentFilePath`のパス形式の不整合にあると判明した。

- **原因1（コンパイル結果の不具合）**: `:w path`（相対パス指定の保存）は`resolveSavePath()`で
  `getProjectRoot()`を基準に絶対パスへ解決してディスクに書き込んでいたが、保存後に
  `currentFilePath`へ代入していたのは解決前の生の相対パス文字列だった（`saveToFile()`内で
  `currentFilePath`を更新する処理自体が存在しなかった）。一方 `CompileAnalyzer.analyzeSourceWithProject()`
  はプロジェクト全体を`Files.walk(projectRoot)`で絶対パス列として読み直し、
  `!filePath.equals(p.toString())`で「現在編集中のファイル」をディスク再読込対象から除外している。
  `filePath`（＝相対パスのままの`currentFilePath`）と`p.toString()`（絶対パス）は文字列として
  一致しないため、この除外が機能せず、同じクラスがバッファ内容とディスク内容の二重で解析対象に
  含まれてしまい、"duplicate class"等の誤ったコンパイルエラーが出ていた。FILER/telescope等
  「絶対パスで`currentFilePath`を設定する」経路でファイルを開き直す（＝ユーザー報告の「再度開く」）と
  この不一致が解消されて正しく直る、という現象だった。
- **原因2（バッファ遷移0個の不具合）**: `loadFromFile()`は「ファイルがまだ存在しない（＝新規ファイル）」
  分岐で`onFileOpened`コールバックを呼んでいなかった（既存ファイルを開く分岐だけ呼んでいた）。
  そのため新規作成したファイルは`Main.BUFFER_REGISTRY`に一切登録されず、`currentFilePath != null`に
  なった時点でCtrl+U/Ctrl+Pが`switchToRelativeBuffer()`（`BUFFER_REGISTRY`循環方式）に切り替わる
  設計（「Ctrl+U/Ctrl+Pのバッファ切替」節参照）と組み合わさり、`BUFFER_REGISTRY`のエントリ数が
  実質的に「新規ファイルを開く前から開いていたファイルの数」のまま変わらないにもかかわらず、
  現在のファイルがそこに存在しないため`entries.size() <= 1`等の条件で「他に開いているファイル
  バッファがありません」となり、元々開いていたファイルへ戻れなくなっていた。
- **修正**:
  1. `saveToFile()`成功時に`currentFilePath`を常に解決後の絶対パス（`targetPath.toString()`）へ
     更新するようにした。これにより`:w`で新規保存・別名保存したファイルも、以後は他のファイルを
     開く経路（FILER/telescope/`switchToRelativeBuffer()`等）と同じ絶対パス形式で統一される。
  2. `executeCommand()`の`"w "`分岐にあった`currentFilePath = path`（相対パスのままの誤った代入）を
     削除した（`saveToFile()`側で正しく絶対パスに更新されるため冗長かつ不正確だった）。
  3. `executeCommand()`の`"e "`分岐は`resolveRelativeToProjectRoot()`（`resolveSavePath()`から
     共通化・新設）で解決した絶対パスを`loadFromFile()`に渡すようにした。
  4. `loadFromFile()`の「新規ファイル」分岐にも、既存ファイル分岐と同様の`onFileOpened`呼び出しを
     追加し、保存前の新規ファイルも`BUFFER_REGISTRY`に登録されるようにした。
  5. `saveToFile()`成功時にも`onFileOpened`を呼ぶようにした（`:enew`で作った無名バッファを
     初めて`:w`で保存した場合など、`loadFromFile()`を経由しないケースを補うため）。
- **意図的に変更しなかった点**: 「切替先ファイルを毎回ディスクから読み直す」「未保存の編集内容を
  保持したままバッファを切り替える仕組みは持たない」という`switchToRelativeBuffer()`の既存の
  トレードオフ（「Ctrl+U/Ctrl+Pのバッファ切替」節参照）は変更していない。保存前の新規ファイルを
  `BUFFER_REGISTRY`に登録したことで、保存前に他のバッファへ切り替えると`switchToRelativeBuffer()`
  が存在しないパスを`Files.readString()`しようとして`IOException`になるが、これは既存のエラー
  表示パターン（`statusMessage`に`"E: " + e.getMessage()`）にそのまま乗るため、新規のエラー処理は
  追加していない。

## Shift+Enter が INSERT モードで何も入力できない不具合の修正

- **不具合**: INSERTモードでShift+Enterを押しても改行できなかった（Enterキー単体は改行できる）。
- **原因**: `KeymapRegistry`のINSERTモード用バインドは`KeyBinding.ofCode(KeyEvent.VK_ENTER, 0, "insert.newline")`
  のように修飾キーなし（`modifiers=0`）でのみ登録されていた。`KeymapRegistry.resolve()`はkeyCodeベースの
  完全一致（`"VK" + keyCode + ":" + modifiers`）を先に試すため、Shift+Enter（`modifiers=SHIFT_DOWN_MASK`）は
  一致せずアクション解決に失敗する。keyCharベースのフォールバックも、Enterキーの`keyChar`（`'\n'`、0x0A）は
  `ofChar()`で登録されたバインドが存在しないため空振りし、最終的に`processInsertKey()`の
  「印字可能文字を挿入する」分岐（`keyChar >= ' '`）にも該当しない（0x0A < 0x20）ため、
  結果的に何も起きなかった。
- **修正**: `KeymapRegistry.loadDefaults()`に
  `bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK, "insert.newline"), "insert.newline")`
  を追加し、Shift+Enterも通常のEnterと同じ`"insert.newline"`アクションに解決されるようにした。
  NORMAL/COMMAND/VISUAL系モードのEnter（ジャンプ・コマンド実行等）は今回のバグ報告の対象外のため
  変更していない。

## NORMALモード `r`（1文字置換）コマンドの実装

- **キーバインド**: `KeymapRegistry`のNORMALモードに`bind(Mode.NORMAL, KeyBinding.ofChar('r', "replace.char.pending"), "replace.char.pending")`を追加した。
  素の`r`は既存バインドと衝突しない（`Ctrl+R`=redo、VISUAL BLOCKの`r`=`block.replace.pending`は別モードのため独立）。
- **カウント接頭辞（`3r`等）はr専用の軽量な数字バッファ`normalCountBuffer`で実装した**。NORMALモードには
  `3j`/`3dd`のような汎用カウント機構が元々存在せず（②modal-editing-engineスキルで「未実装（スコープ外）」と
  明記済み）、dd/yyの2打鍵シーケンスとも共通化できる仕組みがなかったため、既存のVisual `>`/`<`用
  `visualCountBuffer`/`consumeVisualCount()`（`.claude/skills/modal-editing-engine/SKILL.md`参照）と
  同型の専用バッファ・専用ヘルパー（`consumeNormalCount()`）を追加した。digit以外のキーが来た時点で
  `consumeNormalCount()`が呼ばれてバッファは無条件に破棄される（`visualCountBuffer`と同じ「他のキーで破棄」方式）。
  汎用カウント機構自体をこの実装で導入したわけではない。
- **2打鍵目（置換文字入力）の処理は`pendingSequence == "r"`をprocessNormalKey内のdd/yyと同じ多打鍵
  シーケンス処理ブロックに追加する形で実装した**（VISUAL BLOCKの`r`と同じ「キーマップ解決を経由せず
  押された文字をそのまま置換文字として使う」パターン）。Escによるキャンセルは、NORMALモード冒頭の
  既存のESC早期分岐（`pendingSequence`を`"ESC"`で無条件上書きする、dd/yy等と共通の仕組み）がそのまま
  効くため専用コードは追加していない。
- **置換ロジック（`replaceCharAtCursor(char, int)`）は`buffer.delete()`＋`buffer.insert()`の2操作**。
  `toggleCaseUnderCursor()`/VISUAL BLOCKの`replaceBlockChar()`と同じ既存の「1つの論理編集がdelete+insertの
  2 undo単位になる」トレードオフをそのまま踏襲した（①のピーステーブルは`insert`/`delete`単位でスナップショットを
  取る設計のため、専用のグルーピング機構がない限りこの粒度になる。CLAUDE.mdの`:s`置換コマンドの節と同じ
  既知のトレードオフ）。カウント分の置換は`replaceCount`文字の一括delete＋一括insertで行うため、
  カウントの大小に関わらず常に2 undo単位のまま増えない。
- **行末を超えるカウントは`cursorCol + count > line.length()`で判定し、何も変更せず無音で中断する**
  （既存の無効操作時の挙動＝ビープ音やエラーメッセージなしのno-opに合わせた）。
- **置換後のカーソル位置**: `cursorCol + count - 1`（置換した最後の文字の位置）に置く。INSERTモードへは
  遷移しない。
- **テスト**: `test/dev/javatexteditor/editor/ReplaceCharTest.java`（新設・7テスト）。カウントなし置換・
  カウント付き置換・カウントが行末超過時のno-op・Escキャンセル（キャンセル後の`r`が正常動作することも確認）・
  カーソル位置・NORMALモード維持・undo（delete+insertの2 undo単位のため`u`2回で復元）を検証。

## システムクリップボード連携（Ctrl+Shift+C / Ctrl+Shift+V）の実装

- **キーバインド**: `KeymapRegistry`に新規アクション`clipboard.copy`（VISUAL/VISUAL_LINE/VISUAL_BLOCKの3モード、
  `Ctrl+Shift+C`）・`clipboard.paste`（NORMAL/INSERTの2モード、`Ctrl+Shift+V`）を追加した。既存の
  `Ctrl+V`=`enter.visual.block`（NORMALモード、修飾子は`CTRL_DOWN_MASK`のみ）とは修飾子の組み合わせが異なる
  （`CTRL_DOWN_MASK | SHIFT_DOWN_MASK`）ため`KeymapRegistry.resolve()`の完全一致判定上衝突しない
  （`organize.imports`のCtrl+Shift+Oと同型のパターン）。内部ヤンクレジスタ（`yankRegister`/`yankType`、y/d/p系）
  とは完全に独立させた別経路とした（`y`でヤンクした内容が意図せずOSクリップボードを上書きする、または
  逆に外部アプリでコピーした内容が`p`で貼り付けられてしまう、という混同を避けるため）。
- **コピー（`copyToSystemClipboard(String)`）**: VISUAL/VISUAL_LINE/VISUAL_BLOCK各モードの既存`"yank"`
  action（`getSelectedText()`/`buildLineRangeText()`/`buildBlockText()`で選択範囲を文字列化する処理）を
  そのまま再利用し、`java.awt.datatransfer.StringSelection`で`Toolkit.getDefaultToolkit().getSystemClipboard()`
  へ書き込む。コピー後はVimの`y`と同じくNORMALモードへ戻り、カーソルは選択開始位置に戻す（`y`の既存の
  カーソル移動規約をそのまま踏襲）。
- **貼り付け（`pasteFromSystemClipboard(boolean asNormalMode)`）**: `DataFlavor.stringFlavor`が取得できれば
  そのままテキストとして`buffer.insert(offsetOfCursor(), text)`する。NORMALモードでは`P`と同じ「カーソル位置に
  挿入しクランプする」動作、INSERTモードでは通常の文字入力と同じ「挿入してクランプしない」動作にした
  （`asNormalMode`引数で分岐）。
- **画像・音声等バイナリのクリップボード内容の扱い**: ユーザー要望により「バイナリそのものを貼り付ける」仕様と
  した。`DataFlavor.stringFlavor`が使えない場合、`contents.getTransferDataFlavors()`から
  `InputStream`を返すFlavor（`imageFlavor`等の非テキストDataFlavorはJavaのClipboard APIでは基本的に
  `InputStream`経由でバイト列として読める）を探し、`readClipboardBinary()`で生バイト列を読み出す。
  読み出したバイト列は`new String(bytes, StandardCharsets.ISO_8859_1)`で文字列化してバッファへ挿入する
  （ISO-8859-1は1バイト=1コードポイントの可逆マッピングのため、`String.getBytes(StandardCharsets.ISO_8859_1)`
  で元のバイト列をそのまま復元できる＝「バイナリそのもの」をエディタのString型バッファに格納する手段として
  採用した。UTF-8等の他エンコーディングは不正なバイト列で例外/文字化けが起きるため使えない）。テキスト
  バッファに制御文字やNUL等が挿入されるため画面表示は乱れるが、これは「バイナリそのものを貼り付ける」という
  要件の直接的な帰結であり、意図した動作。
- **ヘッドレス環境（`GraphicsEnvironment.isHeadless()==true`、DISPLAY未設定）でのフェイルセーフ**:
  `Toolkit.getSystemClipboard()`はヘッドレス環境で`HeadlessException`を送出することを実機確認した
  （このコンテナ自体がヘッドレス）。`copyToSystemClipboard()`/`pasteFromSystemClipboard()`双方とも
  クリップボード取得部分を`try/catch(Exception)`で囲み、失敗時は`statusMessage`に`"E: clipboard ..."`を
  設定するのみでクラッシュしない・モード遷移は正常に完了する設計にした（`:main`/`gr`等の既存のgraceful
  degradationパターンと同じ）。
- **テスト**: `test/dev/javatexteditor/editor/ClipboardTest.java`（新設・11テスト）。VISUAL/VISUAL_LINE/
  VISUAL_BLOCKでのCtrl+Shift+C押下後にNORMALへ戻りクラッシュしないこと、NORMAL/INSERTでのCtrl+Shift+V
  押下後もモードが崩れずクラッシュしないこと、既存の`Ctrl+V`（VISUAL BLOCK突入）と`Ctrl+Shift+V`が衝突しない
  ことを検証。このコンテナがヘッドレスのため実際のOSクリップボードとの往復（コピーした文字列が本当に
  貼り付けられるか）は検証できておらず、`GraphicsEnvironment.isHeadless()`で分岐しヘッドレス時は
  エラーメッセージになることのみ確認する既知のテストギャップとして残る（⑫openjdk-source-tracing・
  ⑳telescope-pickerと同種）。ヘッドフル環境での実クリップボード往復・画像/音声バイナリの貼り付けは
  手動確認が必要。

### クリップボード貼り付けが対応できないコンテンツがあった不具合の修正（imageFlavor・javaFileListFlavor対応）

- **不具合**: 「いかなるクリップボードにコピーされている内容も貼り付けられるようにしてほしい」という要望を受けて
  調査したところ、`readClipboardBinary()`は`contents.getTransferDataFlavors()`のうち`InputStream`を返す
  `DataFlavor`（`getRepresentationClass()`が`InputStream`のサブタイプ）しか扱っておらず、それ以外は
  `"unsupported clipboard content"`になっていた。実際には以下2ケースが素通りしていた。
  1. **`DataFlavor.imageFlavor`**: 表現クラスが`java.awt.Image`であり`InputStream`ではない。Windowsの
     スクリーンショットツール等、OSクリップボードが画像を`CF_BITMAP`/`CF_DIB`相当で公開する場合はこの
     Flavorのみが提供され、ストリーム系Flavorが存在しないことがある（＝上記「画像・音声等バイナリの
     クリップボード内容の扱い」節が「imageFlavor等の非テキストDataFlavorは基本的にInputStream経由で読める」
     と記述していたのは不正確だった。実際にはOS・アプリ依存で、ストリーム系Flavorが存在しない画像コピーも
     普通に起こりうる）。
  2. **`DataFlavor.javaFileListFlavor`**: ファイルマネージャ等で「ファイルそのもの」をコピーした場合に
     公開されるFlavor。表現クラスは`java.util.List`（`List<File>`）であり、`InputStream`でも文字列でもない。
- **修正**: `pasteFromSystemClipboard()`の分岐を「stringFlavor → javaFileListFlavor → ストリーム系/
  imageFlavorのバイナリ」の3段に拡張した。
  - `javaFileListFlavor`はファイルの**中身**ではなく、絶対パスを1行1件の改行区切り文字列に変換して
    挿入するようにした（`readClipboardFilePaths()`）。ファイル一覧そのものは「生バイト列」という概念を
    持たない（0〜複数件のファイル参照であり、巨大ファイル・ディレクトリを含みうる）ため、既存の
    「バイナリそのものを貼り付ける」方針をそのまま適用せず、他アプリとの相互運用で実際に使い道がある
    「パス文字列としての貼り付け」を採用した（多くのターミナル/エディタがファイルD&D時にパスを
    挿入するのと同じ挙動）。
  - `imageFlavor`は`readClipboardBinary()`のフォールバックとして追加した。`contents.getTransferData(
    DataFlavor.imageFlavor)`で得た`java.awt.Image`を`ImageIO.write(..., "png", ...)`でPNGバイト列へ
    エンコードし、既存の「バイナリそのもの＝ISO-8859-1で可逆的に文字列化してバッファへ挿入」という
    方針にそのまま乗せた（`BufferedImage`以外の`Image`実装は`Graphics2D.drawImage()`で一度描画して
    `BufferedImage`化してからエンコードする）。
- **純粋ロジックの分離**: パス結合（`joinFilePaths`）とPNGエンコード（`encodeImageAsPng`/内部の
  `toBufferedImage`）を`dev.javatexteditor.editor.ClipboardBinaryCodec`（新設・package-private）に
  切り出した。`Transferable`/`Clipboard`（実クリップボードへの依存）を一切持たない純粋ロジックのため、
  実クリップボードなしでヘッドレス環境でも完全にテストできる（`ClipboardTest`が「実クリップボード往復は
  ヘッドレスでは検証不能」としていた既知のギャップを、変換ロジック部分に限り解消した）。`ModalEditor`
  側は`readClipboardFilePaths()`/`readClipboardBinary()`からこのクラスへ委譲するだけになっている。
- **意図的にスコープ外とした点**: `javaFileListFlavor`でファイルの中身（バイト列）を読み込んで挿入する
  という選択肢もあったが、複数ファイル・ディレクトリ・巨大ファイルを指しうるため「貼り付け」1操作の
  意味論として過剰と判断し採用しなかった。テキスト系Flavor（`text/html`・`text/rtf`等）は`stringFlavor`
  が同時に公開されるのが通例のため（既存実装で問題なく動作）、今回追加のハンドリングはしていない。
- **テスト**: `test/dev/javatexteditor/editor/ClipboardBinaryCodecTest.java`（新設・6テスト）で
  `joinFilePaths`（単一/複数/空リスト）と`encodeImageAsPng`（ISO-8859-1往復でのバイト完全一致・
  デコード後のピクセル復元）を検証。`ClipboardTest`（既存11テスト）は変更なしで引き続き全PASS。

## F10/F11/F12（`*compile*`/`*run*`疑似バッファ）のリアルタイムログ表示・標準エラー赤字化

- **要望**: javac/javaコマンド実行時のバッファ画面を、完了を待たずリアルタイムにログ表示し、かつ標準エラー出力を赤字で区別してほしいという依頼。従来は`ProjectBuilder.compile()`/`Main.runJavaClass()`ともプロセス・コンパイルタスクの完了を同期的に待ってから`ModalEditor.showCompileResult()`/`showRunOutput()`で一括描画しており、実行中は`*compile*`/`*run*`疑似バッファが完全に固まって見えていた。
- **F11/F12（実行）: 標準出力/標準エラーの分離が前提条件だった**。従来`ProcessBuilder.redirectErrorStream(true)`で両方をマージしてから読んでいたため、そもそも「どの行が標準エラー由来か」という情報が読み取り時点で失われていた。`redirectErrorStream(true)`を外し、`process.getInputStream()`（標準出力）・`process.getErrorStream()`（標準エラー）をそれぞれ独立した仮想スレッド（`Main.startRunOutputReader()`）で読み、1行読むたび`SwingUtilities.invokeLater()`で`ModalEditor.appendRunOutputLine(line, isError)`を呼んで`*run*`疑似バッファへ即座に追記する。`process.waitFor()`の後に両読み取りスレッドを`join()`してから終了コードを確定させる（プロセス終了後もパイプに残ったバッファの読み切りを保証するため）。標準出力/標準エラーの2スレッドが独立に`invokeLater`するため、実際のプロセス内での出力順序と厳密には一致しない場合がある（両ストリームがそれぞれ内部バッファリングされるため）ことは既知の制約として許容した。
- **F10/F12（コンパイル）: `javax.tools.JavaCompiler`の`DiagnosticListener`は元々コールバック型でストリーミング向き**。従来は`DiagnosticCollector`（`task.call()`完了後に`getDiagnostics()`で一括取得するラッパー）を使っていたが、`DiagnosticListener<JavaFileObject>`を自前実装に差し替えるだけで、javacが診断を検出するたび同期的に呼ばれるコールバックとして扱える。`ProjectBuilder.compile(Path, List<Path>, Consumer<BuildDiagnostic> onDiagnostic)`オーバーロードを新設し、診断1件ごとに`onDiagnostic`へ通知しつつ内部リストにも蓄積して最終`BuildResult`を返す（`compile(Path, List<Path>)`は空コンシューマを渡す後方互換オーバーロードとして維持）。`onDiagnostic`はコンパイルスレッド上で同期的に呼ばれるため、UIスレッドへのディスパッチ（`SwingUtilities.invokeLater`）は呼び出し側（`Main.doCompile()`）の責務とし、`ProjectBuilder`自体はSwingに依存しない設計を維持した。
- **`ModalEditor`側の疑似バッファAPIは「開始→ストリーミング追記→確定」の3段階に分割した**（`beginCompileOutput()`/`appendCompileDiagnostic()`/`finishCompileOutput()`、`beginRunOutput()`/`appendRunOutputLine()`/`finishRunOutput()`）。既存の一括表示API（`showCompileResult()`/`showRunOutput()`、`BuildOutputCommandTest`が依存）は非ストリーミング用途（テスト・後方互換）としてそのまま残し、変更していない。`finishCompileOutput()`は`showCompileResult()`へ単純委譲する（javacの`command`文字列はソース走査後でないと確定せず、開始時点では先頭行の行数が未確定なため、ストリーミング中の追記内容を逐次パッチするより最終結果から丸ごと再構築する方が単純で確実）。一方`finishRunOutput()`は、`java`コマンド文字列がプロセス起動前から確定しているため疑似バッファの行数がストリーミング中ずっと変わらないことを利用し、2行目（ステータス行）のプレースホルダ文字列だけを`buffer.delete()`+`buffer.insert()`でその場置換する（ストリーミングで追記済みの出力行は一切再構築しない）。
- **行番号ベースの赤字マーキングは「バッファ参照との一致」で自動失効させる方式にした**。`ModalEditor`には`buffer = new UndoablePieceTable(...)`という再代入が約25箇所に散らばっており（`:grep`・FILER・telescope・cd候補等の全疑似バッファ切替）、赤字対象行の集合（`outputErrorLines`、`Set<Integer>`）を都度クリアして回るのは取りこぼしのリスクが高い。そこで`outputErrorLinesOwner`（その`Set`がどの`buffer`インスタンスに対応するかを参照一致で覚えておくフィールド）を追加し、`syncCanvas()`が`canvas.setErrorLines(...)`へ渡す直前に`outputErrorLinesOwner == buffer`を判定する方式にした。ユーザーが`*run*`/`*compile*`表示中に他の疑似バッファ（`:grep`等）へ切り替えると`buffer`が新しいインスタンスに再代入されるため、この判定だけで古い赤字行が別バッファに漏れ出ることを防げる。既存の25箇所の`buffer = new UndoablePieceTable(...)`呼び出しには一切手を入れていない。
- **`EditorCanvas`の赤字描画は既存の`ERROR_COLOR`定数（ガター描画で使用済み）と、UI文字列描画専用の`uiGlyphCache`（`(codePoint, cellW, cellH, rgb)`をキーに任意色のグリフをキャッシュする仕組み。telescope・ステータス行等で既に使用）をそのまま流用した**。本文専用の`glyphCacheFg`（テーマの前景色に固定・キーはcodePointのみ）とは別に、赤字対象行だけ`getUiGlyph(codePoint, cellW, cellH, ERROR_COLOR)`を呼ぶよう`drawLineWithFullWidthSupport()`に`isErrorLine`引数を追加した。新しい色管理の仕組みは追加せず、既存のキャッシュ機構2つの使い分けだけで実現している。`EditorCanvas.setErrorLines(Set<Integer>)`が公開APIで、`syncCanvas()`から毎回呼ばれる。
- **副次的に修正した点（`syncCanvas()`の可視性）**: 調査の過程で、従来`doCompile()`/`runJavaClass()`が完了後に`editor.showCompileResult(result)`/`showRunOutput(...)`を呼んだ直後、`canvas.repaint()`だけを呼び`canvas.setText(...)`（＝`private syncCanvas()`経由でのみ呼ばれる）を呼んでいなかったことに気づいた。`EditorCanvas`は`cachedLines`をキャッシュしており`setText()`でのみ更新されるため、この経路では新しいバッファ内容が画面に反映されず、次にユーザーが何かキーを押す（＝`processKey()`経由で`syncCanvas()`が呼ばれる）までF10/F11/F12の結果が可視化されない可能性があった。`syncCanvas()`を`private`から`public`に変更し、`Main.java`の`doCompile()`/`runJavaClass()`・新設の`startRunOutputReader()`から`canvas.repaint()`の代わりに`editor.syncCanvas()`を呼ぶようにした（`syncCanvas()`内部で最終的に`canvas.repaint()`相当の再描画も行われるため置き換えで問題ない）。
- **意図的にスコープ外とした点**: 標準出力/標準エラーの読み取りスレッドが個別に`invokeLater`するため、大量出力時にSwingのイベントキューへの積み上げがボトルネックになりうるが、コンパイル/実行ログの規模（通常は数十〜数百行程度）を想定したスコープでは許容した。スロットリング（例: N行または一定時間ごとにまとめて反映）は要望になく未実装。標準入力を要求するプログラム（`Scanner`等）が正しく動作しない既存の制約（F10/F11/F12の設計決定事項の節を参照）は変更していない。

## F10/F11（`*compile*`/`*run*`疑似バッファ）をSPC+bからいつでも再度開けるようにした

- **要望**: javac/java実行結果（`*compile*`/`*run*`疑似バッファ）を、他のバッファへ移動した後でも
  SPC+b（BufferPicker）からいつでも再度開けるようにしてほしい、という依頼。
- **原因**: `showCompileResult()`/`showRunOutput()`はいずれも`currentFilePath = null`のまま`buffer`を
  直接差し替える疑似バッファ方式（本ファイル冒頭のF10/F11/F12節の「出力表示」参照）のため、
  `Main.BUFFER_REGISTRY`（`onFileOpened`経由・`filePath`必須）にも`bufferHistory`（`pushBuffer()`を
  呼ばない設計）にも一切載らない。そのため一度別のバッファへ移動すると、SPC+b は元より Ctrl+U/Ctrl+P
  でも二度と戻れなかった。
- **修正**: `ModalEditor`に`lastCompileBufferText`/`lastRunBufferText`（`String`、直近の疑似バッファ
  本文全体のキャッシュ）を追加し、`showCompileResult()`/`showRunOutput()`実行のたびに更新するようにした。
  `enterTelescope("buffers")`（SPC+b）は`bufferListSupplier`の結果に加え、キャッシュが非nullの場合のみ
  `BufferPicker.BufferEntry("*compile*", "*compile*")`/`("*run*", "*run*")`という**ファイルパスの代わりに
  固定の疑似パス文字列を`filePath`に持つエントリ**を追加で表示する（`BufferPicker.BufferEntry`の
  Javadocに元々「nullパスはファイルなし疑似バッファ」という記述はあったが、選択時の処理
  `openTelescopeSelection()`は`item.filePath() == null`を早期returnするだけで実際には未対応だった）。
  `openTelescopeSelection()`に`"*compile*"`/`"*run*"`という固定文字列との一致判定を追加し、一致した
  場合はディスクの`Files.readString()`ではなくキャッシュ済み`String`から`buffer`を復元する。
- **意図的な設計判断**:
  - F10/F11を一度も実行していない間はSPC+bの候補に`*compile*`/`*run*`は出さない（要望は「実行結果を
    いつでも開けるように」であり、実行前から空のプレースホルダーを出す要望ではないため）。
  - キャッシュは直近1回分のみ保持する（`*compile*`/`*run*`それぞれ1エントリ）。複数回分の実行履歴を
    スタックする機構は要望に含まれておらず、既存の`*grep*`/`*rename*`等の疑似バッファも同様に「直前の
    1回分のみ」という設計のため踏襲した。
  - Ctrl+U/Ctrl+Pでの往復には対応しない（今回の要望はSPC+bのみ）。`currentFilePath == null`の疑似
    バッファをCtrl+U/Ctrl+Pの対象に含めると、既存の`bufferHistory`フォールバック（`:enew`/`:tutor`用）
    との重複・優先順位の整理が別途必要になり、スコープが広がるため見送った。
- **テスト**: `test/dev/javatexteditor/editor/BuildOutputCommandTest.java`に4テスト追加（計14テスト）。
  F10/F11未実行時はSPC+bの候補に出ないこと、実行後は候補に出て選択すると元の内容が復元されること、
  別の疑似バッファ（`*run*`）へ画面が差し替わった後でも`*compile*`のキャッシュが保持され続けることを
  検証。

## 任意のファイル種別を開けるようにする対応（バイナリファイルの読み取り専用hexdumpプレビュー）

- **要望**: 「いかなる種類のファイルもテキストエディタで開くことができるように」という依頼。
- **調査結果**: `loadFromFile()`（`:e`コマンド・FILER・Shift+K定義ジャンプが経由）に加え、
  `openTelescopeSelection()`（SPC+f等）・`switchToRelativeBuffer()`（Ctrl+U/Ctrl+P・`:bnext`/`:bprev`）・
  `jumpToFileNameResult()`（`\f`）・`jumpToGrepResult()`（`\g`/`gr`）の計4箇所が独立して
  `Files.readString(path)`（charset省略＝UTF-8決め打ち）を呼んでおり、ファイルを開く処理が5系統に
  分散していた。バイナリ・非UTF-8ファイルは`MalformedInputException`（`IOException`のサブクラス）で
  失敗し、クラッシュはしないが`statusMessage`にJDK内部のそっけないエラーが出るだけで開けなかった。
- **方針決定**: 実装前に`AskUserQuestion`で2点確認した。
  1. 非UTF-8ファイルの扱い: 「バイナリと判定したファイルは編集不可の読み取り専用プレビュー
     （hexdump風表示）として開く」を選択（クリップボード機能のISO-8859-1可逆変換をそのまま
     編集可能にする案、主要日本語エンコーディングを自動判定する案は不採用）。
  2. ファイルサイズ上限: 「上限なし」を選択（`WordIndex`/`ProjectSearcher`の2MB上限は流用しない）。
- **実装**:
  - `dev.javatexteditor.buffer.BinaryFileDetector`（新設）: NULバイトを含む、または
    `UTF_8.newDecoder().onMalformedInput/onUnmappableCharacter(REPORT)`で厳密デコードに失敗する
    バイト列をバイナリと判定する純粋ロジック（Swing非依存）。`new String(bytes, UTF_8)`は不正
    バイト列を`U+FFFD`に静かに置換してしまい判定に使えないため、あえて`CharsetDecoder`を直接使う。
  - `dev.javatexteditor.buffer.HexDumpFormatter`（新設）: `hexdump -C`/`xxd`と同じ配置
    （オフセット8桁・16進数16バイト・ASCII表現、印字不可能な文字は`.`）の読み取り専用プレビュー
    テキストを生成する。1行目に`*binary* <ファイル名> — N bytes — read-only preview`という
    ヘッダを置く（既存のgrep結果・filer一覧等の疑似バッファと同じ「ヘッダ行＋本体」構成を踏襲）。
  - `ModalEditor.readFileContentForBuffer(Path)`（新設・private、`FileLoadResult(String text,
    boolean binary)`を返す）にファイル読み込みロジックを一本化し、上記5箇所すべてから呼ぶように
    変更した（FILERの`openSelectedEntry()`とShift+Kの定義ジャンプは`loadFromFile()`経由のため
    追加変更なしで自動的に対応済み）。
  - **`binary()==true`の場合、`currentFilePath`をnullのままにする設計にした**。これにより`:w`での
    保存は既存の「no file name」エラーに自然にフォールバックし、元ファイルへの誤保存（バイト破損）を
    構造的に防止する。新たな「読み取り専用モード」のフラグやキー入力ブロック機構は追加していない
    （既存の`*grep*`/`*compile*`等の疑似バッファと全く同じ「ファイルパスなし＝保存不可」パターンを
    そのまま踏襲しただけで、`modal-editing-engine`側の変更は不要だった）。
  - 保存側のバイナリ書き戻し（クリップボード機能と同じISO-8859-1可逆変換等）は実装していない。
    「読み取り専用プレビュー」という方針決定のため、そもそも保存経路自体が不要という判断。
    クリップボード機能（Ctrl+Shift+V）のISO-8859-1可逆変換は「バイナリを貼り付けて編集可能にする」
    という別要件のための設計であり、今回はあえて踏襲しなかった点に注意（混同しないこと）。
- **意図的にスコープ外とした点**:
  - ファイルサイズ上限は設けていない（ユーザー選択）。数GB級のファイルを開くと
    `Files.readAllBytes`＋hexdump文字列構築がヒープを圧迫し`OutOfMemoryError`のリスクが残る、
    という既知の制約として許容した。
  - Shift_JIS/EUC-JP等、UTF-8以外の日本語テキストファイルも（UTF-8として不正なバイト列を含むため）
    バイナリと同じ扱い（hexdumpプレビュー、編集不可）になる。エンコーディング自動判定は
    ユーザーが不採用と判断した選択肢のため、日本語テキストとして正しく開く機能は未実装。
- **テスト**: `test/dev/javatexteditor/buffer/BinaryFileDetectorTest.java`（新設・7テスト）・
  `HexDumpFormatterTest.java`（新設・6テスト）・`test/dev/javatexteditor/editor/BinaryFileOpenTest.java`
  （新設・4テストメソッド／7アサーション）。`:e`コマンドでバイナリファイルを開いてもクラッシュしない
  こと・hexdumpプレビューが表示されること・`currentFilePath`が`null`になること・`:w`保存を試みても
  元ファイルのバイト列が変化しないこと・通常のUTF-8テキストファイルは従来通り開けることを検証。
  **（2026-07 追記: 下記「`:b`コマンド」節により、この読み取り専用プレビュー方式は廃止された。
  `BinaryFileOpenTest.java`は編集可能なMode.BINARYを検証するテストへ全面的に書き換え済み。
  このセクションの記述は経緯の記録として残す。）**

## `:b`コマンド（Mode.BINARY — hexdumpをその場で編集できるバイナリエディタ）

上記「任意のファイル種別を開けるようにする対応」で導入した読み取り専用hexdumpプレビューを、
「`:b`コマンドで明示的にバイナリエディタとして起動・編集できるようにしてほしい」という要望を受けて
編集可能なMode.BINARYへ全面的に置き換えた。実装前に`AskUserQuestion`でユーザーと3点確認している。

- **`:b`の対象は「現在編集中のバッファ」**（引数なし）。`:e <path>`のように別ファイルを開く機能ではなく、
  今まさに開いている・編集中のバッファ内容をバイナリ表示とテキスト表示の間でトグルする。
- **既存の読み取り専用プレビューは廃止し、`:b`の編集可能版へ統一した**。非UTF-8ファイルの自動判定
  オープン（`:e`・FILER・telescope・`\f`/`\g`・`gr`・Ctrl+U/Ctrl+P経由の計5箇所、いずれも
  `readFileContentForBuffer()`を呼ぶ）は、従来`currentFilePath`を`null`にして保存不可の
  読み取り専用テキストを表示していたが、今後はすべて`enterBinaryMode()`を呼びMode.BINARYへ入る
  （`currentFilePath`は実際のファイルパスを保持し、`:w`で保存できる）。
- **カーソルは1バイト単位**（h/l/矢印で±1バイト、j/k/矢印で±16バイト＝1行）。テキスト文字単位の
  カーソルではなく、hexdump上の「16進数2桁のペア」を1つの移動単位として扱う。末尾/先頭で止まり
  ラップアラウンドしない（`:bnext`/`:bprev`のクランプ方式と統一）。
- **上書き入力は16進数字2桁の自動確定・自動前進方式**（HxD等の一般的なバイナリエディタと同じ）。
  カーソル位置のバイトに対し1桁目は高位4bit・2桁目は低位4bitを確定し、2桁目確定時に自動的に
  次のバイトへ前進する。ASCII欄は表示のみで直接編集はできない（16進数字入力からの再計算で
  自動更新される）。挿入・削除・末尾への追記は一切できない（ファイルサイズは常に固定）。

### アーキテクチャ

- **`buffer`（既存の`UndoablePieceTable`）自体を唯一の真実（source of truth）とし、別途
  `byte[]`をキャッシュしない**。hexdumpテキストは`HexDumpFormatter.format()`で初期描画した後、
  1バイト編集のたびに対応する16進数2桁＋ASCII欄1文字だけを`buffer.delete()`+`buffer.insert()`で
  直接上書きする（既存の`r`コマンド・`toggleCaseUnderCursor()`と同じ「1論理編集が複数delete+insert
  undo単位になる」トレードオフをそのまま踏襲）。編集操作は必ずこの1文字delete+insertの経路のみを
  通るため、hexdumpの固定レイアウト（行数・列位置）は編集中も常に保たれる。これにより、
  「hexdumpテキストを解析してバイト列に戻す」`:w`時の`HexDumpFormatter.parse()`が安全に成立する
  （構造が壊れていないことを編集経路そのものが保証しているため、パース失敗を心配する必要がない）。
  当初検討した「別途`byte[]`を保持し編集の都度同期する」設計は、undo/redoが実際に書き換えるのは
  `buffer`のテキストの方だけなので、別配列を持つと**undo後に配列とテキストがズレる**バグを
  構造的に抱えることが分かり、不採用にした。
- **`HexDumpFormatter`に`hexDigitColumn(i)`/`asciiColumn(i)`（行内バイトインデックス0〜15から
  列位置を計算する固定レイアウト契約）と`parse(text, byteCount)`（`format()`の逆変換）を追加した**。
  `format()`自体は列計算をインラインの`StringBuilder`ループのまま維持し、新設した2つの列計算
  ヘルパーと数学的に同じ結果になることをテスト（`HexDumpFormatterTest`）で確認している。
  `parse()`はレイアウトが壊れている場合（本来到達しないはずだが）`NumberFormatException`/
  `StringIndexOutOfBoundsException`/`IllegalStateException`を送出し、呼び出し側（`saveToFile()`・
  `exitBinaryModeToText()`）で捕捉してエラーメッセージ表示に変換する。
- **`binaryModeOwner`（`UndoablePieceTable`型フィールド）で「現在の`buffer`がMode.BINARY用に
  作られたインスタンスか」を参照一致判定する**。F10/F11の`outputErrorLinesOwner`と全く同じ設計
  （CLAUDE.md該当節参照）。この参照一致方式のおかげで、Mode.BINARY中に`:grep`・`:cd`・telescope
  選択等どの経路で`buffer`が別インスタンスに差し替わっても、それらの30箇所超の既存コードには
  一切手を入れずに「もうバイナリバッファではない」という状態変化を自動的に検知できる。
- **COMMAND モードのEnterハンドラ（2箇所）を`modeAfterCommand()`ヘルパー経由に変更した**。
  従来「`if (mode == Mode.COMMAND) mode = Mode.NORMAL;`」と無条件にNORMALへ戻していたのを、
  `binaryModeOwner == buffer`なら`Mode.BINARY`へ戻すよう変更。これにより、Mode.BINARY中に
  `:`でCOMMANDモードへ入り`:w`等（`:b`以外）を実行した場合も正しくMode.BINARYへ復帰する
  （さもないとhexdumpの固定レイアウトの上にNORMALモードの通常編集キーが効いてしまい構造が壊れる）。
  `:b`自身（`toggleBinaryMode()`）は`mode`を明示的に変更するため、このガードには依存しない。
- **バイナリ→テキストのトグル（2回目の`:b`）は、`HexDumpFormatter.parse()`で復元したバイト列が
  UTF-8として妥当な場合のみ成功する**。妥当でない場合（NULバイトを含む、または不正なバイト列＝
  真のバイナリファイル等）はテキスト化すると内容が破壊されるため、エラーメッセージ
  （`"E: not valid UTF-8 text — staying in binary mode"`）を出してMode.BINARYのまま留まる。
  テキスト→バイナリのトグルは常に成功する（`buffer.getText().getBytes(UTF_8)`は必ず妥当なバイト列
  になるため）。**バイナリ側の編集内容はトグルのたびに失われず往復する**（ディスクから読み直さず、
  常に「今の`buffer`の内容」を変換元にするため）。
- **`saveToFile()`のバイナリ判定は`mode == Mode.BINARY`ではなく`binaryModeOwner == buffer`のみで
  行う**。`:w`は`executeCommand()`（COMMANDモード中）から呼ばれるため、その時点では`mode`はまだ
  `Mode.COMMAND`であり`Mode.BINARY`への復帰は`executeCommand()`から戻った後の`modeAfterCommand()`
  が行う。実装時に`mode == Mode.BINARY`を条件に含めてしまい、`:w`が常にhexdumpの生テキストを
  そのままファイルへ書き込んでしまう（元ファイルのバイト列を破壊する）バグを一度作り込んで
  テストで検出・修正した経緯がある。次にこの判定を触る開発者は同じ罠に注意すること。
- **`modified`（未保存変更）フラグは既存の`UndoablePieceTable`の仕組みをそのまま利用**しており、
  Mode.BINARY専用の変更は不要だった（1バイト編集は必ず`buffer.delete()`+`insert()`を経由するため、
  既存の`insert`/`delete`が`modified = true`にする仕組みがそのまま機能する）。
- **既知の制約（意図的に対応しなかった点）**: `:b`でテキスト⇔バイナリをトグルすると、切替前の
  バッファに未保存の変更（`isModified()==true`）があっても、切替後は新しい`UndoablePieceTable`
  インスタンスに差し替わるため`modified`は`false`から始まる（内容自体は失われないが、
  `:wa`/`:qa`の未保存検知はトグル直後だけ正しく働かない可能性がある）。これは`loadFromFile()`・
  `switchToRelativeBuffer()`等、既存の全バッファ切替経路が持つのと同じ性質（Ctrl+U/Ctrl+P節の
  「未保存の編集内容を保持したままバッファを切り替える仕組みは持たない」と同種）であり、
  今回新たに導入した制約ではないため、あえてハック的な回避策は追加していない。
- **意図的にスコープ外とした点**: 挿入・削除によるファイルサイズ変更、ASCII欄からの直接編集、
  `gg`/`G`等の追加ジャンプキー、複数バイトの範囲選択・矩形編集（VISUAL BLOCKとの統合）は
  いずれも今回の要望（16進数値の上書きのみ）の範囲外のため未実装。

### テスト

- `test/dev/javatexteditor/buffer/HexDumpFormatterTest.java`に4テスト追加（計10テスト）:
  `parse(format(bytes))`が単一行・複数行(20バイト)いずれも完全に一致すること、0バイトの往復、
  `hexDigitColumn`/`asciiColumn`の単調増加性。
- `test/dev/javatexteditor/editor/BinaryEditModeTest.java`（新設・8テストメソッド/16アサーション）:
  16進数2桁上書きと`:w`保存後のファイル内容、1桁目/2桁目でのカーソル自動前進、先頭/末尾での
  カーソルクランプ、undo（`u`）による編集の取り消し、`:b`によるテキスト⇔バイナリの内容保持往復、
  バイナリモード中の編集がトグル後のテキストに反映されること、不正なUTF-8バイト列はテキストへ
  戻せずMode.BINARYのまま留まること、Mode.BINARY中に`:w`等`:b`以外のコマンドを実行しても
  Mode.BINARYへ復帰すること。
- `test/dev/javatexteditor/editor/BinaryFileOpenTest.java`は新方式に合わせて全面的に書き換えた
  （読み取り専用前提のアサーションを、Mode.BINARYへ入ること・`currentFilePath`が実パスになること・
  無編集での`:w`がバイト列を完全に保つことの検証に置き換えた）。

## 軽量性リファクタリング計画（2026-07-15 策定・Phase 1〜3）

「軽量エディタ」の主張と実装の間に4つの深刻なギャップ（①`PieceTable`のピース結合欠如による編集セッション全体のO(K²)劣化・②`syncCanvas()`の1キー入力あたり4回のO(n)全文再構築・③Shift+K/`gr`/`:grep`のO(ファイル数)逐次走査とタイムアウト後のスレッド残留・④編集中の文書サイズ比例メモリチャーン）があることを実コード調査で確認し、解消計画を策定した（計画書・実行指示書は別ブランチ`claude/editor-performance-analysis-3no2jf`の`docs/PERF_REFACTORING_PLAN.md`/`docs/PERF_REFACTORING_INSTRUCTIONS.md`に存在し、mainマージ待ち。両ドキュメントがmainに反映され次第、この節からのリンクを有効化する）。

| Phase | 対象 | 状態 |
|---|---|---|
| 1 | `PieceTable`: 連続挿入のピース結合・`length()`のO(1)キャッシュ・`addBuffer.toString()`コピー排除・`offsetOfLine()`の全文再構築排除 | ✅ 完了（`insert()`が「オフセット==ピース境界かつ直前ピースがaddBuffer末尾を指す」場合にピースを伸長する結合を追加。`length()`は`totalLength`フィールドでO(1)化。`getText()`/`getTextInRange()`は`addBuffer.toString()`によるADDピースごとの追加バッファ全体コピーを廃止しCharSequence範囲appendに変更。`offsetOfLine()`は全文再構築なしのピース直接走査に変更。undoスナップショット（`List.copyOf`によるピース参照コピー）とは独立のため1insert=1undoの粒度は不変（PieceTableTest Test 17で固定）。PieceTableTest 26/26・LargeFileTest 16/16 PASS。連続タイピング2万キー相当が1〜2ms（旧実装ではO(K²)的にピース数に比例して劣化する設計だった）） |
| 2 | `syncCanvas()`: `getVersion()`＋バッファ参照一致による全文再構築キャッシュ（カーソル移動キーでは再構築ゼロ、編集キーでは1回のみ） | ✅ 完了（`refreshCanvasTextCache()`を新設し`canvasTextOwner`＝バッファ参照一致＋`canvasTextVersion`＝`getVersion()`一致で失効判定。`syncCanvas()`内の2箇所の`buffer.getText()`直接呼び出しをキャッシュ経由に置換。**実装中に指示書の想定を超える発見があった**: `ModalEditor.getLines()`（`moveCursor()`等69箇所から呼ばれる別経路）が`syncCanvas()`とは独立に`buffer.getText().split("\n",-1)`を呼んでおり、カーソル移動1キーごとに全文再構築する支配的なホットパスだったため、同じキャッシュを`getLines()`にも適用した。`EditorRenderPerfTest`（10万行文書でカーソル移動1000回）はこの修正前は4612ms（閾値2000ms超過でFAIL）、修正後は18ms（256倍高速化）。`SyncCanvasCacheTest` 8/8・`EditorRenderPerfTest` 4/4 PASS） |
| 3 | `ProjectSearcher`: 「逐次パス収集→仮想スレッド並列grep」の2段階化・タイムアウト時の`future.cancel(true)`による協調キャンセル（結果順序・同期契約・1500ms/2MB/スキップ規則は不変） | ✅ 完了（`search()`を`collectCandidateFiles()`（逐次walk・従来の2MB上限/スキップ規則を維持）→`grepFilesInParallel()`（仮想スレッドper-fileで並列grep、Future submit順にget連結し結果順序を従来と同一に保つ）の2段階に再構成。`ModalEditor.withTimeout()`のcatch節に`future.cancel(true)`を追加し、`ProjectSearcher`側のwalk（`TERMINATE`）・grepタスク（冒頭の割り込みチェック）が協調的に停止するようにした（従来の「タイムアウト後もバックグラウンド検索スレッドが残り続ける」既知の残課題を解消）。`ParallelGrepTest` 8/8（結果順序の決定性・ファイル内行昇順・2MB上限/NULバイナリ/SKIP_DIRSの維持・fullScan動作を検証）。既存の`BangSearchTest` 8/8・`NativeReferenceSearchTest` 11/11・`JumpBackTest` 49/49・`ProjectSearchTest` 21/21も無修正で全PASS） |

**✅ 2026-07-15 全フェーズ完了**（Phase 1〜3・PR #146/#147/#148）。問題④（編集中のメモリチャーン）はPhase 1（addBufferコピー排除）＋Phase 2（再構築キャッシュ）で解消済み。ファイル全体を単一Stringで保持する内部表現と「サイズ上限なし」は2026-07の確定済みユーザー判断のため変更していない（数百MB級ファイルのOOMリスクは既知の制約として残る）。ピーステーブルのツリー化・ビューポート限定描画・検索の完全非同期化・検索インデックス化は計画時点の非ゴールとして未着手のまま。

（各Phase完了時、実行者がこの表の状態・関連SKILL.md・上記2ドキュメントを更新する。ベースライン: 全70テストクラス中69クラスPASS・`ScrollTest`のみ既知2件FAIL＝仕様判断未決のため修正禁止）

## Shift+K 定義ジャンプの Eclipse JDT 流バインディング解決化（完全非同期・2026-07）

「Eclipse JDT のアルゴリズムを参考に Shift+K の定義ジャンプを強化したい。JVM/HotSpot 部分のアルゴリズムには一切触れない」という依頼に基づく、㉓ `symbol-definition-navigation` の拡張。実装前に `AskUserQuestion` で「①既存 Shift+K の内部強化・失敗時は既存ヒューリスティックへフォールバック ②JDT のバインディング解決（resolveBindings→NodeFinder→IBinding→宣言要素）を `javax.tools.JavaCompiler`＋Compiler Tree API（`Trees.getElement()`）で再現 ③完全非同期化 ④JDK シンボルも JDT 流で解決（FQCN から src.zip ジャンプへ接続）」の4点を確認・確定した。詳細は `.claude/skills/symbol-definition-navigation/SKILL.md` の「Eclipse JDT 流バインディング解決」節を参照。

- **`dev.javatexteditor.analysis.BindingDefinitionResolver`（新設）**が JDT 相当の解決を担う。現在バッファ＋projectRoot 配下の全 `.java` を compilation unit として `JavacTask.parse()`+`analyze()`（属性付け）し、`TreePathScanner` でカーソル位置の最内ノードを特定（NodeFinder 相当）、`Trees.getElement()`→`Trees.getPath()` で宣言位置へ辿る。オーバーロード区別・ブロックスコープ・implements/extends 経由の継承メンバーが正確に解決できるようになった（従来の正規表現ヒューリスティックでは原理的に不可能だった領域）。結果は `ProjectLocation`／`JdkElementLocation`／`NotFound` の sealed 3種。
- **「非同期化は見送り」という過去の判断（本ファイル「追加調査（3回目）」節）を、ユーザーの明示選択によりこの新設経路に限って転換した**。ただしテストの同期契約（`processKey` 直後の同期 assert）は壊していない: `ModalEditor.enableBindingDefinitionLookup(backgroundExecutor, uiDispatcher)` による**実行機構の注入方式**とし、既定は無効（＝従来動作のまま。既存テスト群は無修正で全 PASS）、本番（`Main.createLeaf()`）だけが仮想スレッド＋`SwingUtilities.invokeLater` を配線する。テストは `Runnable::run`（同期）または `Deque<Runnable>`（擬似非同期）を注入して決定的に検証する。**フォールバックの既存ヒューリスティック自体は従来どおり EDT 上の同期実行＋`withTimeout()` 1500ms のまま変更していない**。
- **stale 結果ガード**: 世代カウンタ＋バッファ参照一致＋`buffer.getVersion()`＋カーソル位置＋モードを解析要求時に捕捉し、結果適用時に1つでも変わっていたら黙って破棄する（`outputErrorLinesOwner`/`binaryModeOwner` と同系の参照一致パターンの応用）。解析スレッド自体の明示キャンセルはしない（javac の属性付けに協調キャンセル点が無いため。適用は常に最後の要求1件のみ）。
- **JVM/HotSpot・native トレース経路（`OpenjdkSourceTracer` の C/C++ 検索・`findCSymbol`・jdk-source 疑似バッファ内の K）は依頼どおり一切変更していない**。jdk-source 疑似バッファ内の Shift+K はバインディング解決の対象外（従来の同期フローのまま）。JDK 要素へのジャンプは `readJavaSourceByFqcn()`（`:main` 用に実装済み）＋既存の `openJdkSourceBuffer()`/`jumpToMember()` の再利用のみで、`jdkIndex` の準備状態にも依存しない。
- **安全弁**: プロジェクト走査は `FileNameSearcher.SKIP_DIRS` と同じ集合をスキップし、`.java` が `MAX_SOURCE_FILES`（2000）を超えたら解析を断念してフォールバックに委ねる（作業ディレクトリの既定値がホームディレクトリになりうるため）。構文エラー等の javac 内部例外は catch して NotFound に変換する（graceful degradation）。
- **javac 利用上のハマりどころ2件をスキルに記録済み**: DiagnosticListener 未登録だと終了位置テーブルが作られずノード探索が全滅する／javac は `JavaFileObject` を `ClientCodeWrapper` でラップするため照合は参照一致ではなく URI で行う。
- **テスト**: `BindingDefinitionResolverTest`（10テスト/25アサーション）・`BindingDefinitionJumpTest`（8テスト/16アサーション、無効時の従来動作維持・フォールバック・擬似非同期・stale ガード3種を含む）。全体は 75/76 クラス PASS（唯一の FAIL は既知ベースラインの `ScrollTest` 2件＝仕様判断未決のため修正禁止、変更なし）。

## `:split`/`:vsplit`で同一ファイルを複数ペインに開いた際のリアルタイム同期（Vim方式の共有バッファ）

- **不具合報告**: 複数ペインで同一ファイルを開いて片方を編集・保存しても、もう片方の画面に反映されない。調査したところ、`:split`/`:vsplit`（`Main.setupSplitCallbacks()`）は分割時点の`getText()`（Stringスナップショット）を新しい`Leaf`に渡すだけで、各`Leaf`（＝`ModalEditor`インスタンス）が完全に独立した`UndoablePieceTable`を持つ設計だった。ファイルを開く経路（`:e`/telescope/FILER/`gr`/`\g`/Ctrl+U/Ctrl+P）も同様に、開くたびに`buffer = new UndoablePieceTable(result.text())`でディスクから新規インスタンスを作っており、ペイン間・バッファ間の同期機構が一切存在しなかった。
- **方針決定**: `AskUserQuestion`で3案（① Vim方式の真の共有バッファ・② 保存時のみ自動リロード・③ 保存時に他ペインへ変更を通知して確認）を提示し、ユーザーが①を選択した。実装コストは最も高いが、「同じファイルを指す全ペインが同じ`PieceTable`インスタンスを参照し、1文字打つたびに他ペインの画面にも即座に反映される（カーソル位置はペインごとに独立）」という本家Vimの`:split`と同じ意味論を採用した。
- **中核メカニズム**:
  - `UndoablePieceTable`自体は変更不要だった。`insert`/`delete`/`undo`/`redo`のたびに`version`が増分する既存の仕組み（軽量性リファクタリングPhase 2で`syncCanvas()`のキャッシュ失効判定用に追加済み）と、`isModified()`/`markSaved()`（`:wa`/`:qa`用）がそのまま「複数ペインで共有される単一の真実」として機能する。undo/redoスタックも同一インスタンスの内部状態のため、Vimと同様にバッファ単位で共有される（どちらのペインで`u`を押しても同じ編集履歴を辿る）。
  - **`ModalEditor.acquireBufferForOpen(String absolutePath, String text)`（新設・private）**: ファイルを開く6箇所（`loadFromFile`の新規/既存ファイル分岐×2・`openTelescopeSelection`・`switchToRelativeBuffer`・`jumpToFileNameResult`・`jumpToGrepResult`の非バイナリ・非class-preview分岐）が、`buffer = new UndoablePieceTable(result.text())`の代わりにこのメソッドを経由するよう統一した。`liveBufferLookup`（`Function<String, UndoablePieceTable>`、Main.java から注入）が同じ絶対パスを持つ他ペインの生きたバッファを返せばそれをそのまま再利用し（ディスクから読んだ`text`は捨てる＝他ペインの未保存編集を破棄しない）、無ければ従来通り新規インスタンスを作る。
  - **`Main.findLiveBuffer(PaneNode root, String absolutePath)`**: `allLeaves(root)`を横断し、`currentFilePath`が一致する最初のペインの`getBuffer()`を返す。`refreshCallbacks()`内で全リーフに`leaf.editor().setLiveBufferLookup(path -> findLiveBuffer(root[0], path))`を配線する（分割・ペイン close のたびに呼ばれる`refreshCallbacks()`の既存パターンをそのまま踏襲）。
  - **`Main.syncSiblingBuffers(PaneNode root, Leaf source)`**: `source`と同じ`getBuffer()`参照を持つ他ペイン全てに対し、`setCursor(getCursorRow(), getCursorCol())`を呼ぶ（`syncCanvas()`を直接呼ぶのではなく`setCursor`経由にしたのは、他ペインの編集でカーソル位置が行数を超えてしまうケースを`setCursor`内の既存クランプ処理でついでに解決できるため）。`ModalEditor`側に`onSharedBufferSync`（`Runnable`、既存の`onBufferChanged`とは独立した新規コールバック）を追加し、`processKey()`末尾の「バッファversionが変化した時だけ」発火するガード（既存の`lastNotifiedBufferVersion`判定）にそのまま相乗りさせた。これにより`KEY_PRESSED`/`KEY_TYPED`/IMEコミット、3つのキー入力経路すべてで自動的にカバーされる（`processKey()`は単一の公開メソッドで、3経路とも最終的にこれを呼ぶ設計のため、Main.java側の呼び出し箇所を個別に変更する必要がなかった）。`refreshCallbacks()`内で`leaf.editor().setOnSharedBufferSync(() -> syncSiblingBuffers(root[0], leaf))`を配線する。
  - **`:split`/`:vsplit`（`Main.shareBufferWithSplit(Leaf source, Leaf newLeaf)`）**: `createLeaf()`が内部で構築した新規`UndoablePieceTable`を`newLeaf.editor().setBuffer(source.editor().getBuffer())`で即座に置き換え、`setCursor()`で分割元と同じカーソル位置に揃える。`liveBufferLookup`に頼らず最初から共有状態でペインが生まれるため、分割直後の1文字目から同期が効く。
- **5箇所ある「pseudo-buffer退避→復元」機構をString snapshotからUndoablePieceTable参照へ変更した**: telescope（`telescopeSavedBuffer`）・`:cd`候補（`cdSavedBuffer`）・`:e`候補（`edSavedBuffer`）・FILER（`filerSavedBuffer`）・jdk-source（`savedBuffer`）の5系統は、いずれも元は`xSavedBufferText = buffer.getText()`で退避し復帰時に`buffer = new UndoablePieceTable(xSavedBufferText)`で**新規インスタンス**を作り直していた。共有バッファ実装前は無害だったが、共有バッファ導入後にこれを放置すると「共有ファイルを開いたペインでSPC+f（telescope）を押してEscでキャンセルしただけで、そのペインのバッファ参照が新規インスタンスにすり替わり他ペインとの共有が黙って切れる」という壊れやすい状態になる。5系統とも「フィールド型をString→UndoablePieceTableに変更し、保存は`xSavedBuffer = buffer`、復元は`buffer = xSavedBuffer`（nullなら`new UndoablePieceTable("")`にフォールバック）」という機械的な置き換えで統一し、この問題を解消した。
- **スコープ外（意図的に対象外とした経路）**: Mode.BINARY（`:b`・非UTF-8ファイルの自動判定オープン）と`.class`ファイルの読み取り専用構造ビューは`acquireBufferForOpen()`を経由させていない。バイナリ判定はファイルの生バイト列に対して両ペイン独立に決定的に行われるため、あるペインがバイナリと判定した時点でそのペインは`enterBinaryMode()`に分岐し`liveBufferLookup`のあるコードパス自体に到達しない（もう一方のペインも独立に同じ判定をするため、経路上そもそも共有が必要な状況が生じない）。`newBuffer()`（`:enew`）や`:grep`/`:compile`/`:run`等の疑似バッファ（`currentFilePath == null`）も対象外（そもそも共有すべき実ファイルパスが存在しない）。
- **テスト**: `test/dev/javatexteditor/editor/SharedBufferTest.java`（新設・13テスト）。`Main.findLiveBuffer()`相当を最小のフェイクペインレジストリとして再現し、①同一ファイルを2ペインで開くと同じ`UndoablePieceTable`参照になること、②片方の編集がもう片方の`getText()`に即座に反映されること、③`onSharedBufferSync`がバッファ変更時のみ発火しカーソル移動のみでは発火しないこと、④`:split`相当（`setBuffer`+`setCursor`）でカーソル位置を引き継ぎつつ共有されること、⑤telescope（SPC+f→Esc）の退避・復元を挟んでも共有が維持されること、⑥異なるファイルは共有されないこと、⑦undoがペインではなくバッファ単位で共有されること、を検証。既存の全74テストクラス（`ScrollTest`除く）は無修正で引き続き全PASS（回帰なし）。
- **既知の制約**: `Main.java`側の実際のGUI配線（複数`JFrame`ペインを実際に操作して画面が即座に更新されるかの目視確認）は、本プロジェクトの他の多くのGUI依存機能（⑫⑳等）と同様に自動テスト対象外の既知のギャップ。ヘッドレスコンテナでのXvfb起動によるアプリ起動確認（クラッシュしないこと）のみ行った。

## getter/setter生成の `\a` プレフィックス追加、Ctrl+Shift+O の @Override 挿入への差し替え

「`\gg`=getter生成・`\gs`=setter生成・`\gd`=getter/setter両方生成・`Ctrl+Shift+O`=`@Override`+改行挿入」というキーバインド追加の依頼を受けて調査したところ、2つの既存機能との衝突が判明した。実装前に`AskUserQuestion`で両方ともユーザーに確認した。

- **`\g`（ファイル内容grep検索、⑲`file-search`）との衝突**: `\`（バックスラッシュ）は既に`filesearch.pending`にバインドされており、続く`f`/`g`の2打鍵目で即座に`enterFileSearch(NAME/GREP)`へ遷移する（⑲参照）。`\gg`をこのまま追加すると、`\g`の時点で既にGREP検索モードへ入ってしまい、3打鍵目の`g`はgrepクエリ文字列の先頭文字として食われてしまうため、原理的に共存できない。ユーザーに確認し「`\g`の挙動は変更せず、別プレフィックスを使う」を選択、続けて具体的な文字も確認し`\a`（accessorの頭文字）に決定した。
  - **実装**: `\a`の3打鍵目（`g`/`s`/`d`）は`seq.equals("\\a")`の判定として追加し、既存の`prev == '\\'`（`\f`/`\g`の2打鍵目判定）より**前**に置く必要がある（`gu`/`gU`/`g~`と同じ理由。`prev`は`seq.charAt(0)`のため、`\a`の3打鍵目でも`prev`は`'\\'`のまま変わらず、後に置くと3打鍵目が誤って2打鍵目として再度pending状態に戻ってしまう）。`\`の2打鍵目に`a`のケースを追加し、`pendingSequence = "\\a"`にして3打鍵目を待つ。
  - **既存機能の再利用**: getter/setter生成のロジック自体（`GetterSetterGenerator`・`generateGetter()`/`generateSetter()`/`generateGetterAndSetter()`）は本タスク着手前から`SPC g g`/`SPC g s`/`SPC g d`として実装済みだった（ドキュメント化されていなかったため見落としやすい。次にこの機能を触る開発者は本節と本skillの設計判断ログを参照すること）。`\ag`/`\as`/`\ad`は同じprivateメソッドを追加で呼ぶだけで、`SPC g g/s/d`のバインドは削除していない（両方から呼べる）。
- **`Ctrl+Shift+O`（Eclipse互換のimport整理、`organize.imports`）との衝突**: NORMAL/INSERT両モードで既に`organize.imports`にバインド済みだった。ユーザーに確認し「Ctrl+Shift+Oの挙動をこの機能に差し替える」を選択。
  - **`KeymapRegistry`のCtrl+Shift+Oバインド（NORMAL/INSERT）をアクション名`organize.imports`から`insert.override`へ変更**し、`ModalEditor`側のswitch文2箇所（NORMAL用・INSERT用）も対応する`case`を`insertOverrideStub()`呼び出しへ差し替えた。`organizeImports()`private メソッド自体・その内部実装は一切変更していない。
  - **organize imports機能は消えていない**: `SPC+i+o`（`seq.equals(" i")`の2打鍵目`o`）と`:oi`/`:organize-imports`コマンドは従来どおり`organizeImports()`を直接呼んでおり、`KeymapRegistry`のアクション名解決を経由しないため今回の変更の影響を受けない。到達経路がCtrl+Shift+Oの1つ減っただけで、機能自体は健在。
  - **`insertOverrideStub()`の設計**: 既存の`insertNewlineWithIndent()`（INSERT中のEnterキー）と全く同じ契約を踏襲した。カーソル行の先頭インデントを検出し、`"@Override\n" + indent`を**カーソルの生の位置**（`offsetOfCursor()`、列を強制的に動かさない）にそのまま挿入する。この契約上、カーソル列が0（インデント文字より前）で呼ぶとインデントが二重になる（`insertNewlineWithIndent()`をこの位置でEnterとして使った場合も同じ結果になる、本機能固有のバグではなく既存メソッドと共通の性質）。実装時に一度、カーソル列0で検証するテストを書いて実際にこの重複を確認し、テスト側を「インデントのみの空行の行末（列=indentLen、メソッドを書く直前の実際の使い方）にカーソルを置いてから呼ぶ」という既存の自動インデントテスト（`testAutoIndentPreserve`等）と同じ慣例に修正して解消した（実装側は変更していない）。NORMAL/INSERTいずれから呼んでも常にINSERTモードへ遷移する（後続のメソッドシグネチャ入力をそのまま続けられるようにするため）。
- **テスト**: `ModalEditorTest`に6テスト追加（`\ag`/`\as`/`\ad`・Ctrl+Shift+O）、`CompileTriggerCallbackTest`の`testOnOrganizeImportsFiresOnCtrlShiftO`を`testOnOrganizeImportsFiresOnLeaderIO`（SPC+i+o経由に変更）へ差し替え、新規`testCtrlShiftOInsertsOverrideStub`を追加。`RobotKeyInputTest`の`testOrganizeImportsCtrlShiftO`（実キーイベントでの検証）も`testCtrlShiftOInsertsOverrideStub`へ全面書き換えし、Xvfbを起動して実際にRobotキー入力で動作確認済み（PASS）。詳細な設計判断ログは`.claude/skills/keymap-conflict-resolution/SKILL.md`の設計判断ログ表に追記した。

## TERMINALモード（`Ctrl+Shift+T` / `:term`）の全機能削除（2026-07-21）

上記で実装していたMode.TERMINAL（OS標準シェルの対話型ターミナル）は、「テキストエディタとしての用途から外れている」というユーザー判断により全機能を削除した。

- **削除したもの**: `src/dev/javatexteditor/terminal/`パッケージ全体（`TerminalSession`・`AnsiEscapeFilter`）、対応するテスト（`test/dev/javatexteditor/terminal/*`・`test/dev/javatexteditor/editor/TerminalModeTest.java`）、`ModalEditor.java`のMode.TERMINAL関連フィールド・メソッド全て（`enterTerminal`/`exitTerminal`/`toggleTerminalMode`/`processTerminalKey`/`appendTerminalOutput`等）、`:cd`とターミナルの双方向同期ロジック（`syncEditorWorkingDirectoryFromTypedCommand`/`quotePathForShellCd`/`isWindowsOs`/`stripMatchingQuotes`）、`EditorCanvas.java`の`terminalMode`フィールド・ステータス行表示、`Main.java`のCtrl+Shift+Tグローバルキーディスパッチ・`TerminalSession`起動/書き込みコールバック配線・SPC+bの`*terminal*`疑似バッファエントリ。
- **意図的に維持したもの**: `WorkingDirectoryManager`・`:cd`コマンド自体（ターミナル連携以外の部分）は無変更。F10/F11/F12（`*compile*`/`*run*`疑似バッファ）は別機能のため無変更。
- **再度この種の機能を検討する場合**: 真のPTY実装が不可能という制約（CLAUDE.md本文の「外部ライブラリ一切不使用」方針）は変わらないため、以前の設計判断（本セクション削除前の記述）を参照する前に、まずこの機能自体がエディタの目的に合うか確認すること。

## C言語開発支援（Java機能のC言語対応・2026-07-23）

「今までのJava言語での機能をC言語にも対応させる」という要望に基づき、言語別の開発支援を追加した。実装前にマニュアル・チュートリアルを最新化した上で、Javaで実現している「プロジェクト全体のコンパイル・実行（F10/F11/F12）」「インライン診断（ガター/波下線）」「未定義シンボルの自動補助（auto-import）」に対応するC版を実装した。

- **外部コンパイラ起動という方針転換の位置づけ**: 本ファイル冒頭の技術制約「依存ライブラリ一切不使用・javac直接呼び出し」は**ビルド（このエディタ自身のビルド）**に関するルールであり、変わっていない。C対応はエディタ本体のビルドに新しい依存を持ち込むものではなく、**実行時にユーザーのマシンの外部Cコンパイラ（gcc→clang→cc）を`ProcessBuilder`で起動する**という、F11がJava実行時に`java`を別プロセス起動しているのと同じ既存パターンの踏襲である。CにはJDKの`javax.tools.JavaCompiler`に相当するインプロセスの標準APIが存在しないため、外部起動以外の現実的な選択肢がない。過去に`SystemStatsMonitor`のnative温度取得で「JNI/native実装はCLAUDE.mdの根本方針と衝突するため不採用」とした判断とは別物である（あちらはエディタ自身のビルドにCコンパイラ・JNIビルドを持ち込む話。こちらはランタイムに存在すれば使う・無ければ静かに無効化する外部プロセス起動）。
- **言語判定は現在バッファの拡張子で行う（`Main.isCBuffer()`）**。`.c`/`.h`ならC、`.java`ならJava、それ以外・パス未設定の疑似バッファはどちらでもない。既存の`isJavaBuffer()`（`.java`のみ）と同型で、`currentFilePath`がnullの疑似バッファは対象外という判断も踏襲。F10/F11/F12のグローバルキーディスパッチ（`Main.java`）と`setupCompileAnalysis()`のトリガ（INSERT→NORMAL/保存/バッファ変更デバウンス）・`onOrganizeImports`コールバックが、この判定で Java版/C版へ分岐する。
- **新規クラス（3つ、いずれも既存のJava版の対応物を参考にした）**:
  - `dev.javatexteditor.projectbuild.CProjectBuilder`: `ProjectBuilder`（javac版）のC版。projectRoot配下の全`.c`を`gcc -Wall -o <bin>/a.out <全.c>`で**1つの実行ファイル**にコンパイル・リンクする。診断は`redirectErrorStream(true)`でまとめて読み、`path:line:col: error|warning|note: msg`形式を正規表現でパースして`BuildDiagnostic`（Java版と共用のrecord）に変換する（note行はガター非表示のためnull、javacのNOTE相当）。`bin/`の配置は`ProjectBuilder.binDirFor()`と同じ「srcの兄弟」規則を`resolveProjectBaseDir()`で再実装（10行程度の複製。CLAUDE.mdの「3行の重複は早すぎる抽象化よりよい」方針に従い共通抽出しなかった）。`compile()`冒頭で`projectRoot`を絶対パスへ正規化する（相対projectRoot + `pb.directory()`でソースパスが二重解決される事故の防御。実運用の`getBuildRoot()`は既に絶対だが防御的に統一）。`BuildResult`（成功可否・件数・診断・エラーメッセージ・コマンド文字列）もJava版と共用。
  - `dev.javatexteditor.analysis.CCompileAnalyzer`: `CompileAnalyzer`（javac版）のC版。現在バッファ1つを一時`.c`ファイルへ書き出し`gcc -fsyntax-only -Wall`で解析、`CompileDiagnostic`（Java版と共用のrecord・0-indexed）のリストを返す。実ファイルパスが分かる場合はその親ディレクトリを`-I`探索パスに追加し（ローカル`#include "foo.h"`の解決）、診断は一時ファイル自身に属するものだけへフィルタする（システムヘッダ由来の診断を除外）。コンパイラ未検出時は`AnalysisException`（Java版でJavaCompilerが無い場合と同じ扱い）。
  - `dev.javatexteditor.analysis.CIncludeManager`: auto-import（`AutoImportHandler`/`ImportSuggester`）のC版に相当する**純粋ロジック**（Swing・サブプロセス非依存で単体テスト容易）。既知の標準ライブラリシンボル→標準ヘッダの対応表（stdio/stdlib/string/math/ctype/time/stdbool/stddef/stdint/assert/errno/unistd）を持ち、(a) gcc診断メッセージから未定義シンボル名を抽出（`extractSymbolFromMessage`。implicit declaration/unknown type name/undeclared/clangのundeclared identifier）、(b) ソース走査で使用中の既知シンボルを検出（`usedKnownSymbols`）の2経路で「不足しているヘッダ」を算出し、既存の最後の`#include`直後（無ければ先頭コメント群の直後）へアルファベット順に挿入する（`insertOffset`/`formatIncludeBlock`/`addIncludes`）。
- **ModalEditorへの追加は最小限（`applyCIncludes(List<String>)`の1メソッドのみ）**。`CIncludeManager`が算出したヘッダ群を`buffer.insert(offset, block)`でバッファへ反映する（Java版`AutoImportHandler.applyImport`と同じ「buffer.insertのみ・カーソル非追従」のトレードオフを踏襲）。`*compile*`/`*run*`疑似バッファの表示メソッド（`beginCompileOutput`/`appendCompileDiagnostic`/`finishCompileOutput`/`beginRunOutput`/`appendRunOutputLine`/`finishRunOutput`/`showCompileResult`/`showRunOutput`）はいずれも`BuildResult`/`command`/`fqcn`を受け取るだけで言語非依存のため、**C版のために一切変更せずそのまま再利用**した。赤字マーキング（`outputErrorLinesOwner`参照一致）・SPC+bからの再オープン（`lastCompileBufferText`/`lastRunBufferText`）もそのまま効く。
- **Main.javaのC版配線**: `triggerCompileC`/`triggerRunC`/`triggerCompileAndRunC`/`doCompileC`/`runCExecutable`（`runJavaClass`のC版。実行ファイルを直接`ProcessBuilder`起動、stdout/stderrを別スレッドで読み`*run*`へ赤字含めリアルタイム表示）と、`runCAnalysis`（ガター診断＋診断ベースの`#include`自動挿入）・`organizeCIncludes`（ソース走査ベースの`#include`一括整理、`Space i o`/`:oi`のC版）を追加。**C版はJava版のクラスパス入力プロンプト（`enterClasspathInput`）を挟まず直接コンパイルする**（Cにクラスパスの概念がないため）。
- **既に言語非依存で追加対応不要だったもの**: Alt+/単語補完（`WordIndex`は元から`.c`/`.h`/`.cpp`を走査対象に含む）・`:grep`/`\g`/`gr`（`ProjectSearcher`は正規表現grep）・`:rename`（語境界の文字列置換）・モーダル編集全般。マニュアル`docs/manual/11-c-tooling.md`に「もともと言語に依存しない機能」として明記した。
- **意図的にスコープ外（CにはJDK固有の概念が無く対応物が存在しない）**: Getter/Setter生成・`K`のJDK Javadoc/リフレクション表示・OpenJDK nativeトレース・`:main java`/`:main javac`。Cの識別子への`K`はプロジェクト正規表現検索（`gr`基盤）で部分的に働くのみ（型解決を伴う厳密ジャンプ＝Javaのbinding解決相当はしない）。
- **複数mainの制約（意図的な仕様）**: 全`.c`を1実行ファイルにリンクするため、それぞれ`main()`を持つ独立した複数プログラムが同一projectRoot配下にあると`multiple definition of main`リンクエラーになる（`*compile*`にそのまま表示）。「1プログラム＝複数ファイル」という典型構成を主対象とする。標準入力を要求する対話的プログラムが正しく動かない点はJava版F11と同じ既知の制約。
- **マニュアル・チュートリアルの最新化**: 要望どおり実装前提としてドキュメントを更新した。`docs/manual/11-c-tooling.md`（新設）・マニュアル目次（`README.md`）・`04-java-tooling.md`（**`Ctrl+Shift+O`がorganize importsから`insert.override`＝@Override挿入へ変更済みだった記述の誤りを修正**し、F10/F11/F12の言語自動切替を追記）・`10-keybindings-reference.md`（同じ`Ctrl+Shift+O`の誤り修正・F10/F11/F12のC対応追記）・ルート`README.md`（特徴にC言語開発支援を追加）・`:tutor`本文（`Tutorial.CONTENT`にレッスン16「C言語の開発支援」を新設、以降を1つずつ繰り下げて全19レッスンに。レッスン15の`Ctrl+Shift+O`の誤りも修正）。
- **テスト**: `CProjectBuilderTest`（21・診断パース/bin解決/実gccコンパイル。gcc無い環境ではコンパイル系をskip）・`CCompileAnalyzerTest`（11・診断パース/パスフィルタ/実gcc解析。同skip）・`CIncludeManagerTest`（29・純粋ロジックで常時実行）・`CIncludeApplyTest`（6・ModalEditor.applyCIncludesの編集反映）・`TutorialTest`（レッスン数を18→19へ更新、C言語レッスンの存在を追加検証）。実際の子プロセス起動を伴うF10/F11/F12のGUI配線（`Main.runCExecutable`等）はGUI/OS依存のため自動テスト対象外（⑫⑳やJava版`runJavaClass`と同じ既知のテストギャップ）。全体は既存のベースラインFAIL（`ScrollTest`2件・`ModalEditorTest`1件＝いずれも本変更前から失敗、仕様判断未決のため修正禁止）を除き全PASS。

## C言語の Shift+K 定義ジャンプ（2026-07-24）

「C言語のファイルでShift+Kを押すと、ヘッダの定義元またはプロジェクト内の定義元へジャンプ（ヘッダ→実装までたどれるように）。ヘッダファイル参照(`#include`)上ではそのヘッダを開く。Windows/Linux双方対応」という要望に基づく。㉓ `symbol-definition-navigation` のC版拡張。

- **新規クラス `dev.javatexteditor.analysis.CDefinitionResolver`**: Swing非依存の純粋ロジック＋ディスク走査。`resolve(source, currentFile, cursorRow, cursorCol, projectRoot)` が `Location(file, line, label)` または null を返す。Javaの `BindingDefinitionResolver`（javac属性付け）と異なり、Cにはインプロセスの型解決APIが無いため、`gr`/`:grep` と同じ割り切りで正規表現ベースのctags風ヒューリスティックにした。`Path`/`Files`/正規表現のみでWindows/Linux双方に対応。
- **カーソル位置による2分岐**:
  1. `#include "foo.h"` / `#include <foo.h>` の行（カーソル列は問わない）→ ヘッダを開く。引用符形式はまず編集中ファイルの親ディレクトリ、次にプロジェクト全体（パス末尾一致→basename一致）、山括弧形式はプロジェクト全体→標準インクルードディレクトリ（`/usr/include`・`/usr/local/include` を直接resolve）の順。
  2. 識別子の上 → プロジェクト配下の `.c`/`.h` を走査し、`classifyDefinition(line, word)` で各行を分類。優先度は **関数実装（本体`{`）＞マクロ（`#define`）＞型（`struct`/`enum`/`union`/`typedef`）＞関数プロトタイプ（`;`終端）**。関数実装が見つかった時点で即確定（「ヘッダの宣言→`.c`の実装」をたどる要件の核）。関数定義/プロトタイプの判定は「行頭から『戻り値の型トークン + word(』で始まる」ことを要求し、`x = foo(...)` や `return`・裸の `foo(...)` 呼び出しを誤検出しないようにした。
- **安全装置**: `FileNameSearcher.SKIP_DIRS`（`.git`/`build`/`node_modules`等）と2MB上限（`WordIndex`/`ProjectSearcher`と同値）を適用。`walkFileTree` の `visitFile` 冒頭で `Thread.currentThread().isInterrupted()` を見て `TERMINATE` する（下記タイムアウトの協調キャンセル用）。
- **ModalEditor への配線**: `lookupJdkDoc()`（K キーの入口）冒頭に「`isCFilePath(currentFilePath)` かつ `!inJdkSourceBuffer` なら `lookupCDefinition(before)` へ振り分けて return」を追加。既存のJava経路（JDTバインディング解決・ヒューリスティック）には一切手を入れていない。`lookupCDefinition` はプロジェクト全体走査を既存の `withTimeout()`（1500ms・タイムアウト時 `future.cancel(true)`）で包み、EDTの長時間フリーズを防ぐ（Java側ヒューリスティック経路と同じ扱い＝真の非同期化はせず同期契約を維持）。ジャンプは既存の `loadFromFile()` を再利用し、`recordJumpOriginIfMoved()` で `Shift+J`（`jumpBack`）の復帰元を1件記録する（Java の K と全く同じ復帰機構がそのまま効く）。`isCFilePath` は `.c`/`.h`/`.cc`/`.cpp`/`.cxx`/`.hpp`/`.hh`/`.hxx` を対象にする独立ヘルパー（`Main.isCBuffer` は `.c`/`.h` のみだが、K は C++系ヘッダも開けた方が便利なため範囲を広げた。用途が異なるため共通化せず別定義）。
- **同期契約の維持**: `bindingLookupEnabled` は既定で無効のため、C経路も含めK は `processKey` 直後に同期 assert できる（editor-testing-strategy の同期契約に沿う）。`CDefinitionJumpTest` はこの前提で `processKey(VK_K, 'K', SHIFT)` 直後に同期検証している。
- **意図的にスコープ外**: 型解決を伴う厳密な解決（同名の別関数・スコープ・`receiver.member` の型推定）はしない（正規表現の限界。Javaの binding 解決相当はC非対応のまま）。`printf` 等プロジェクト内に定義の無い標準ライブラリ関数の識別子は `C: definition not found` を表示するのみ（バッファ非変更）。グローバル変数定義の分類は誤検出が多いため対象外にした。
- **テスト**: `CDefinitionResolverTest`（30・`classifyDefinition`/`wordAt` 単体＋一時プロジェクトでの include解決/関数実装優先/マクロ/同一ファイル定義/keyword除外/not found）・`CDefinitionJumpTest`（10・ModalEditor経由の実ジャンプ＋Shift+J復帰）。既存のベースラインFAIL（`ScrollTest`2件・`ModalEditorTest`1件＝本変更前から失敗、仕様判断未決のため修正禁止）を除き全PASS。
- **マニュアル・チュートリアル**: `docs/manual/11-c-tooling.md` に「定義ジャンプ（Shift+K / K）」節を新設し、旧「Cの`K`は部分的にしか働かない」記述を更新。`10-keybindings-reference.md` の `K`/`Shift+J` 行にC対応を追記。`:tutor` のレッスン16（C言語の開発支援）に K/Shift+J の説明を追加。ルート `README.md` のC特徴に定義ジャンプを追記。

## Windows でも Shift+K が標準ライブラリへジャンプできるようにする修正（2026-07-25）

「WindowsではShift+Kを押してもヘッダーやヘッダー内に定義されている関数や定数、構造体などにジャンプすることができない」という報告を受けた調査で、㉓の C 版（前節「C言語の Shift+K 定義ジャンプ」）に実装当初から2つの実害あるバグがあったことが判明し、修正した。

- **バグ1（Windows で機能しなかった直接原因）**: `#include <foo.h>`（山括弧形式）の標準ヘッダ解決に使っていた `SYSTEM_INCLUDE_DIRS` が `/usr/include`・`/usr/local/include` という **Linux 専用パスのハードコード**だった。Windows（MinGW-w64/MSYS2 等）にはこれらのディレクトリが存在しないため、標準ヘッダへのジャンプは Windows では常に失敗していた。
- **バグ2（プラットフォームを問わず存在した、より本質的な欠落）**: `resolveSymbol()`（識別子上での K）は**プロジェクト配下しか走査しておらず、標準インクルードディレクトリを一切見ていなかった**。そのため `#include` 行そのもの以外の場所——つまり `printf(...)` や `size_t x` のような通常の使用箇所——で `printf`/`NULL`/`size_t` の上に カーソルを置いて K を押しても、プラットフォームに関係なく常に "not found" になっていた。ユーザー報告の「関数や定数、構造体にジャンプできない」はこちらが本体で、バグ1はその一部（`#include` 行自体からのジャンプ）に過ぎなかった。
- **修正方針（ユーザーが「Javaのように実際のソースが必要であれば、Gitでlib内に持ち込んでもよい」と許可）**: 静的な header のバンドル・vendoring は採用せず、**実際にインストールされている C コンパイラ（`gcc`→`clang`→`cc`。既存の検出順と同じ）に `<compiler> -E -v <一時.cファイル>` で問い合わせ、コンパイラ自身が報告する標準検索パス（`#include <...> search starts here:` 〜 `End of search list.` の間の行）を動的に取得する**方式にした。これは CMake 等のビルドツールがコンパイラのデフォルトインクルードパスを検出する際に使う標準的な手法で、Windows（MinGW-w64/MSYS2 の gcc）でも Linux（glibc の gcc）でも、その環境に実際に入っているツールチェーンをそのまま反映するため正確に動く。vendoring 案（Git経由でヘッダをlib/に持ち込む）より正確（インストール済みコンパイラのバージョン・ターゲットと必ず一致する）かつ簡潔（追加のセットアップスクリプト・ライセンス考慮が不要）と判断し、この方式を採用した。ユーザーの許可は「実ソースへのアクセスが必要ならそれでよい」という前提の確認であり、具体的な実現手段（vendoring vs 動的検出）はこちらで選んだ。
  - `CDefinitionResolver.discoverSystemIncludeDirs()`（新設）が一時的な空 `.c` ファイルに対し `<compiler> -E -v` を実行し、`parseIncludeSearchPaths()`（新設・package-private static、gcc/clang 共通のテキスト書式をパースする純粋ロジックでサブプロセス非依存にテスト可能）で検索パス一覧を抽出する。結果は `systemIncludeDirsCache`（`volatile List<Path>`）に JVM 内で1回だけキャッシュする（プロセス起動を伴うため）。
  - **キャッシュ汚染への配慮**: `ModalEditor.withTimeout()`（1500ms）に検出処理の途中で割り込まれた場合、不完全な結果を「検出失敗」として永続キャッシュしない（`Thread.currentThread().isInterrupted()` を検出直後にチェックし、真なら `systemIncludeDirsAttempted` を立てずに返す）。これを怠ると、たまたま最初の K 押下でコンパイラの初回起動が遅かっただけで、以後のセッション全体で標準ヘッダへのジャンプが機能しなくなる事故につながる。同様に、サブプロセスが割り込み時に残留しないよう `finally` で `process.destroyForcibly()` を呼ぶ。
- **性能・正確性上の重大な設計変更（実機検証で発覚）**: 当初「標準インクルードディレクトリ配下を丸ごと総当たり」する実装（`systemHeaderFiles()`。`Files.walkFileTree` で全ファイル列挙しキャッシュ）を試したが、実機（`/usr/include` 配下に openssl・X11・valgrind 等の無関係な大量ライブラリが同居する一般的な Linux 環境）で検証した結果、2つの実害が判明し、**「現在のファイルが実際に `#include` しているヘッダ（そこから辿れるヘッダも含む）だけを対象にする幅優先探索」に設計変更した**（`resolveSymbolInIncludedHeaders()`。`MAX_HEADER_SCAN=300` で上限。`ResolvedHeader` レコードで「system由来か」を保持）。
  1. **速度**: `size_t` を検索する初回呼び出しが3.8秒かかった（`/usr/include` 配下の全ファイルを毎回読み込んで正規表現照合するため）。`ModalEditor.withTimeout()` の1500msを恒常的に超過し、実運用では常にタイムアウトしていた。
  2. **誤検出**: `printf` を検索したところ `/usr/include/valgrind/valgrind.h` の**コメント中の説明文**（"...subsequently calls printf(), there's a high chance..."）が「関数定義」として誤検出された。全ディレクトリ総当たりだと、無関係なライブラリのドキュメンテーションコメントがヒットする確率が大きく上がる。
  - 「現在のファイルが実際に見えるヘッダだけを探す」という制約は、実際の C コンパイラのシンボル可視性規則（インクルードしていないヘッダの宣言はそもそも見えない）とも一致しており、正しさと速さを同時に改善する。修正後、`size_t`（`#include <stdlib.h>` 経由で間接的に `stddef.h` へ到達）は 178ms（初回）/ 74ms（2回目）に収まることを実測確認した。
- **副次的に発覚したバグ3（コメント誤検出）**: 上記の valgrind.h 誤検出を機に、`classifyDefinition()` が C の `/* ... */` ブロックコメント・`//` 行コメントを一切考慮していないことが判明した（ブロックコメント中の "if you call ... printf(), ..." という散文が、行頭が英字で始まり `(` を含み `,` で終わるため「関数定義」の正規表現に偶然マッチしていた）。**`stripComments()`（新設・package-private static、行をまたぐブロックコメント状態を1パスで引き継ぐ）を追加し、`resolveSymbol()`/`resolveSymbolInIncludedHeaders()`の双方で `Files.readAllLines()` 直後に適用するようにした**。文字列リテラル内の `//`/`/*`（例: `"http://example.com"`）は考慮しない簡易実装（`gr`/`:grep` と同じ正規表現ヒューリスティックの延長と位置づけ、完全な字句解析はスコープ外）。
- **意図的に許容した既知の限界**: 複数行にまたがる関数シグネチャ（例: glibc の `__fortify_function` 版 `printf` が `戻り値の型` と `関数名(...)` を別の行に分けて書いているケース）は「行頭が『戻り値の型 + word(』で始まる」という単一行前提の既存ヒューリスティックでは検出できず、この場合はより優先度の低い候補（同名のマクロ定義等）にフォールバックする。これは既存 Javadoc に明記された「型解決を伴う厳密な解決はしない（正規表現の限界）」の範囲内の挙動であり、今回のバグ修正のスコープ外として様子見とした。
- **テスト**: `CDefinitionResolverTest` に22テスト追加（計52）: `stripComments`（行コメント・複数行ブロックコメント・同一行ブロックコメント）・コメント中のシンボル誤検出防止の統合テスト・`#include` を辿った BFS 探索（直接ヘッダに無く間接的に辿ったヘッダにある場合に見つかる／見つからない場合）・`parseIncludeSearchPaths`（gcc書式・clangの "(framework directory)" 注記除去・実在しないディレクトリの除外・マーカーなし出力）・実コンパイラに依存する統合テスト2件（`hasCompiler()` チェックで無い環境は `CProjectBuilderTest` 等と同じ skip パターン）。既存の30テスト・`CDefinitionJumpTest`（10）は無修正で全PASS。全体は既存のベースラインFAIL（`ScrollTest`2件・`ModalEditorTest`1件）を除き全PASS。
- **マニュアル・チュートリアル**: `docs/manual/11-c-tooling.md` の「定義ジャンプ」節を更新し、標準ライブラリ識別子（`printf`/`NULL`/`size_t`等）へのジャンプが実際に機能することを明記（従来の「プロジェクト内に定義の無い標準ライブラリ関数は not found になる」という記述は誤りだったため削除・訂正）。「標準ライブラリへのジャンプ（Windows / Linux 両対応）」節を新設し、動的検出の仕組み・探索範囲を限定した理由を説明。`:tutor` レッスン16に標準ライブラリへのジャンプの説明を追加。ルート `README.md` の C 特徴列を更新。symbol-definition-navigation スキルの「C言語の Shift+K」節も同様に更新した。

## gcc の診断メッセージがロケール翻訳される環境で標準ヘッダ検出が機能しない不具合の修正（2026-07-25 続報）

前節の修正をユーザーが実機（日本語ロケールのWindows + 古いMinGW.org GCC 6.3.0）で検証したところ、依然としてShift+Kが標準ヘッダへジャンプしないという再報告があった。ユーザーが提示した `gcc -v` の生出力を確認したところ、`COLLECT_GCC`/`Target` 等の一部行は英語のままだが、`configure` の説明文や `gcc バージョン` に相当する行が文字化けしており、**この gcc ビルドは診断メッセージをシステムロケール（日本語）に翻訳して出力している**ことが直接確認できた。

- **原因**: `discoverSystemIncludeDirs()` が呼ぶ `<compiler> -E -v` の出力のうち、標準ヘッダ検索パス一覧を区切る見出し行（`#include <...> search starts here:` / `End of search list.`）を英語の固定文字列と一致させて判定していた。gcc は gettext ベースの国際化を全面的に採用しており、これらの見出し行も他の診断文言と同様にロケールに応じて翻訳されうる。翻訳された環境では英語文字列が一致せず `parseIncludeSearchPaths()` が常に空リストを返し、結果として標準ヘッダへのジャンプ機能全体が沈黙して機能しなくなっていた（前節で修正した「OS別パスのハードコード」とは別の、独立した2つ目の同種バグ）。
- **修正（二段構え）**:
  1. **`discoverSystemIncludeDirs()` のサブプロセス起動時に `LC_ALL=C`・`LANG=C` を環境変数として設定**し、gcc/clangに対し可能な限り翻訳なし（Cロケール）の出力を要求するようにした。多くの環境ではこれだけで解決する。
  2. **ただし環境変数だけに依存せず、`parseIncludeSearchPaths()` 自体を英語見出し文字列に一切依存しない構造的な解析に書き換えた**（`LC_ALL`が無視される・一部翻訳が残る等の外れ値ケースへの防御）。gcc がこの一覧を出力する際の書式――各行が「半角スペース1個＋絶対パス」という固定レイアウト――はgcc本体のC言語コードが直接生成するものであり、翻訳対象の文字列（gettextの`_()`マクロで囲われた部分）には含まれないため、ロケールに関わらず不変であることを利用した。`hasExactlyOneLeadingSpace()`（行頭が半角スペースちょうど1個）と`looksLikeAbsolutePath()`（Unix形式`/...`またはWindowsドライブレター形式`X:\...`/`X:/...`）で判定し、最終的に`Files.isDirectory()`で実在確認する（この3段の絞り込みにより、たまたま同じ見た目の無関係な行が誤って混入するリスクは低い）。
- **意図的な設計判断**: 「見出し文字列マッチング」を完全に廃止し、構造的検出のみに一本化した（両方式を並行して残すと、どちらが実際に効いているのか分かりにくくなり、かつ英語文字列マッチングは翻訳環境では常に不発なので実質的に不要なコードになるため）。
- **テスト**: `CDefinitionResolverTest`に4テスト追加（計56）。見出し文字列を実際に日本語のダミーテキストに置き換えても構造的解析だけでディレクトリ一覧が正しく抽出できること（`testParseIncludeSearchPathsIgnoresLocalizedMarkerText`）、半角スペース1個で始まるが絶対パスの形をしていない無関係な行は無視されること（`testParseIncludeSearchPathsIgnoresIndentedProse`）を検証。既存の52テスト・`CDefinitionJumpTest`（10）は無修正で全PASS。実機（Linux・実gcc）での`printf`/`size_t`解決も本修正後に再確認済み（281ms/459ms、1500msタイムアウト内）。
- **既知の限界**: この修正はMinGW.orgの非常に古いGCC（6.3.0、2016年頃）を含め実機検証していないため、当該環境で実際に解決するかは未確認（テスト実行環境にはこの特定のツールチェーンが無い）。理屈上は構造的検出がロケールに依存しないため機能するはずだが、ユーザーからの追加報告を待つ。

## 日本語ロケールのWindowsで「¥」（円記号）がバックスラッシュとして認識されず標準ヘッダ検出が機能しない不具合の修正（2026-07-25 続報2）

前節の修正後、ユーザーが実際に `gcc -E -v` の完全な出力（標準エラー出力も含む）を提供してくれたことで、根本原因が確定した。

- **原因**: 実機（MinGW.org GCC 6.3.0、日本語ロケールのWindows）の出力を確認したところ、ディレクトリ区切り文字が実際には次のように出力されていた:
  ```
   c:¥mingw¥bin¥../lib/gcc/mingw32/6.3.0/include
  ```
  本来のバックスラッシュ（`\`, 0x5C）の代わりに **円記号（¥, U+00A5）** が使われている。これは日本語Windows環境でCP932/Shift_JISコードページがバイト0x5Cを円記号のグリフとして扱う、古くからよく知られた慣習に由来する（DOS時代からの経緯で、実際のバイト値はASCIIバックスラッシュと同じでも、フォント・エンコーディングの解釈によって円記号として現れることがある）。前節で「英語見出し文字列に依存しない構造的検出」に変更済みだったが、`looksLikeAbsolutePath()` はバックスラッシュ（`\`）とスラッシュ（`/`）しか区切り文字として認識しておらず、円記号は非対応だった。そのため実際の検索パス行が1件も「絶対パスに見える」と判定されず、`systemIncludeDirs()` が常に空リストを返し、標準ヘッダへのジャンプ機能全体が引き続き機能していなかった。
- **修正**: `looksLikeAbsolutePath()` に半角円記号（`¥`）・全角円記号（`￥`）もドライブレター直後の区切り文字として認識するよう追加した。ただし**認識するだけでは不十分**: Javaの`Path`実装は円記号を区切り文字として一切認識しないため（`Path.of("c:¥foo¥bar")`は "c:¥foo¥bar" という1つの奇妙なファイル名として扱われ、ネストしたディレクトリとして解釈されない）、`Path`を組み立てる前に新設の`normalizeYenSigns()`で本物のバックスラッシュへ変換してから`Path.of()`に渡すようにした。
- **副次的な改善**: 見出し行の判定を「行頭が半角スペースちょうど1個」から「行頭が半角スペース1個以上」に緩和した（`hasLeadingIndent()`）。今回の不具合とは直接関係しないが、将来的な書式の微妙な違い（インデント幅の差異等）に対する追加の耐性として、ついでに緩和した。
- **テスト**: `CDefinitionResolverTest`に9テスト追加（計65）。半角/全角円記号を区切り文字として認識すること・本物のバックスラッシュも引き続き認識されること・円記号を本物のバックスラッシュへ変換すること・円記号を含まない文字列は変更されないこと（`looksLikeAbsolutePath`/`normalizeYenSigns`を直接呼ぶ純粋な文字列テスト）に加え、ユーザーが実際に提供してくれた `gcc -E -v` の出力の文言・構造をほぼそのまま再現した統合テスト（`testParseIncludeSearchPathsVerbatimJapaneseMinGWOutput`）を追加した。既存の56テストは無修正で全PASS。
- **既知の限界**: 円記号→バックスラッシュ変換後に`Files.isDirectory()`で実在確認する経路は、Windowsのドライブレター形式パスの実在確認を伴うため、このテスト実行環境（Linux）では end-to-end に検証できない（`looksLikeAbsolutePath`/`normalizeYenSigns`の文字列レベルの単体テストで代替）。実機（当該のMinGW.org GCC 6.3.0環境）での最終確認はユーザーからの報告待ち。

## Cバッファの入力補完（Ctrl+Space/Alt+/）候補をプロジェクトルート配下 + includeヘッダに限定（2026-07-25）

「Cバッファの入力補完候補は、プロジェクトルート以下の関数・定数・変数と、`#include`しているヘッダー内の単語群だけにし、Javaクラス名は一切出さないでほしい」という要望に基づく修正。

- **不具合**: 従来 `queryMergedCompletion()`（Ctrl+Space の統合クエリ）は言語判定を一切せず、`wordIndex`（プロジェクトルート配下の単語）に加えて常に `completionIndex`（JDKクラス名のみを保持、"cls"）を混ぜていた。そのため `.c`/`.h` ファイルを開いていても `String`/`List` 等のJavaクラス名が候補に出てしまっていた。また、Cで最も候補として欲しい「`#include` しているヘッダ内のシンボル」（`printf`・`size_t`・自作ヘッダのマクロ/関数プロトタイプ等）は、そのヘッダがプロジェクトルート配下に無い限り（＝標準ヘッダ等）候補に一切出なかった。
- **修正1（Javaクラス名の除外）**: `queryMergedCompletion()` の `classAvailable` 判定に `!isCFilePath(currentFilePath)` を追加し、Cバッファでは `completionIndex` を一切問い合わせないようにした（既存の `isCFilePath()`＝㉓Shift+K定義ジャンプで導入済みの `.c`/`.h`/`.cc`/`.cpp`/`.cxx`/`.hpp`/`.hh`/`.hxx` 判定をそのまま再利用）。
- **修正2（includeヘッダの単語を候補に追加）**: `CDefinitionResolver`（Shift+K定義ジャンプの解決器。㉓参照）に `includedHeaderWords(source, currentFile, projectRoot)` を新設した。既存の `resolveSymbolInIncludedHeaders()` が使っている「現在のファイルが実際に `#include` しているヘッダ（そこから辿れるヘッダも含む）だけを幅優先で辿る」`enqueueIncludes()`/`MAX_HEADER_SCAN` の仕組みをそのまま再利用し、1シンボルを探す代わりにヘッダ内容全体から `WordIndex.extractWords()` で識別子を集める版として実装した。標準インクルードディレクトリを丸ごと総当たりしない（＝実際に見えるヘッダだけを対象にする）という既存の設計判断（無関係なライブラリの誤検出・数千ファイル走査による低速化を避けるため）をそのまま踏襲している。
  - `queryWordCompletion(prefix, maxResults)`（Alt+/ と Ctrl+Space の単語補完部分が共通で通る唯一の経路）に、Cバッファのときだけ `wordIndex` の結果が埋まらなかった残り枠を `cHeaderWords()`（前方一致フィルタ＋アルファベット順）で補う分岐を追加した。1箇所に集約したことで、Alt+/・Ctrl+Space の両方に自動的に反映される（既存の「近接順ソートが両キーに自動反映される」設計方針と同じパターン）。
- **性能への配慮（キャッシュ）**: `includedHeaderWords()` はディスク走査（プロジェクト内探索・場合によっては初回のみgccプロセス起動）を伴うため、補完ポップアップ表示中の毎キー入力で無条件に呼ぶと重い（Ctrl+Space/Alt+/ は `recheckCompletion()`/`recheckWordCompletion()` 経由で1文字入力するたびに再クエリされる設計のため）。`cHeaderWords()`（新設）は `currentFilePath + "|" + CIncludeManager.existingIncludes(source)`（＝`#include`行の構成）をキャッシュキーにし、includeの並び・内容が変わらない限り再走査しない。通常のコード編集（`#include`行以外の変更）ではキャッシュキーが変わらないため、実質的にヘッダ変更時とファイル切替時のみ再走査される。
- **意図的にスコープ外とした点**: `wordIndex` 自体（プロジェクトルート配下の走査）は起動時1回きりのビルドのままで、Cバッファ専用に再構築タイミングを変える等の変更はしていない（既存の「Alt+/ 単語補完の設計決定事項」節の制約をそのまま継承）。ヘッダ側の候補には種別 `kind` を新設せず、既存の `"wd"`（wordIndex由来）をそのまま流用した（UI描画コードの変更を避けるため）。
- **テスト**: `test/dev/javatexteditor/editor/CWordCompletionTest.java`（新設・4テストメソッド/8アサーション）。Cバッファでは `wordIndex` 側の候補だけで枠が埋まってもJDKクラス名（`kind=="cls"`）が一切出ないこと、`#include` したヘッダ内の関数宣言・マクロ定数がそれぞれ Ctrl+Space・Alt+/ の候補に含まれること、Javaバッファでは従来どおりJDKクラス名が候補に含まれること（回帰なし）を検証。既存の `WordCompletionTest`（8/8）を含む全テストは無修正で引き続き全PASS（ベースラインFAIL＝`ScrollTest`2件・`ModalEditorTest`1件を除く）。

## auto-import が JDK 内部の非公開クラス・`java.lang` を候補にしてしまう不具合の修正（2026-07-26）

「`String` クラスを使うと自動 import が働き、`import` 不要にもかかわらず import を要求してくる。挙句
`パッケージcom.sun.org.apache.xpath.internal.operationsは表示不可です`（モジュールjava.xmlで宣言され
ているがエクスポートされていない）と`同じ単純名の型がjava.lang.Stringの単一型インポートによって
すでに定義されています`という2つのコンパイルエラーが消えなくなる」という報告を受けて調査した。

- **原因**: `JdkClassIndex.buildIndex()` は jrt:/ 配下の `.class` ファイルを単純に全走査するだけで、
  モジュールがそのパッケージを実際にエクスポートしているか・クラスが `public` かを一切見ていなかった。
  JDKには単純名が衝突する実装専用クラスが存在する（実機確認: `java.lang.String` の他に
  `com.sun.org.apache.xpath.internal.operations.String`＝java.xmlモジュール内部のXPath実装クラスが
  同じ単純名 `String` で存在する。`Method`についても`java.lang.reflect.Method`の他に
  `com.sun.jdi.Method`・`sun.jvm.hotspot.oops.Method`が同じ単純名で存在する）。`ImportSuggester`は
  `JdkClassIndex.lookup(simpleName)`の結果をそのままフィルタなしで返すため、`AutoImportHandler`から
  見ると「Stringの候補が2件」に見え、`ModalEditor.handleAutoImport()`の「候補1件→即挿入、複数件→
  選択UI」という分岐に乗ってしまう。ユーザーが（あるいは何らかのカスケードエラーで再度自動選択された
  際に）内部クラス側を挿入してしまうと、javacの「パッケージが非公開」エラーと、`java.lang.String`
  という暗黙のimportと衝突する「同じ単純名の型が既に定義されている」エラーの2つが連鎖して発生し、
  一度壊れると`resolveCandidates()`が同じ未解決シンボルを再検出し続けるため消えなくなる
  （本ファイル既存の「根本原因2」節の`Date`/`List`の曖昧解決バグと同系統の問題）。
- **修正1（非公開・非公開パッケージの除外、`JdkClassIndex`）**: `buildIndex()`の走査時に
  `ModuleFinder.ofSystem().findAll()`から全モジュールの`ModuleDescriptor`を一度だけ読み、
  各モジュールが**無条件（非qualified）にエクスポートしているパッケージ集合**を
  `Map<String, Set<String>>`（モジュール名→パッケージ集合）として事前計算した
  （`loadExportedPackagesByModule()`）。走査中の各クラスについて、そのパッケージが
  対応モジュールの非qualifiedエクスポート集合に含まれない場合は索引に追加しない
  （`isExportedPackage()`）。qualified export（特定モジュールにのみ公開）は、このエディタが
  生成する一般的なユーザーコード（unnamed module）からは利用できないため対象外とした。
  実機確認: `com.sun.org.apache.xpath.internal.operations`はjava.xmlのexportsに一切含まれず
  （java.xmlは`com.sun.org.apache.xpath.internal`等ごく一部のみqualified exportしているのみ）、
  `sun.jvm.hotspot.oops`もjdk.hotspot.agentのexportsに含まれない（qualified exportは
  `sun.jvm.hotspot.debugger.remote`のみ）ため、どちらも正しく索引から除外されることを確認済み。
  - **`ModuleLayer.boot().findModule()`ではなく`ModuleFinder.ofSystem()`を使う理由**: 実装当初
    `ModuleLayer.boot()`（現在のJVM起動時に実際に解決されたモジュールのみを含む）で判定しようと
    したが、`jdk.hotspot.agent`のような「jrt:/にクラスファイルは実在するが、通常の起動では
    boot layerに含まれないオプションモジュール」は`findModule()`が空を返し、「判定不能なので
    除外しない（安全側）」のフォールバックに落ちて内部クラスが索引に残ってしまうことを実機確認
    した。`ModuleFinder.ofSystem()`はboot layerの解決状態に依存せずランタイムイメージ内の
    全モジュールの`module-info`を静的に読めるため、この問題が起きない。
  - **索引全体から除外する設計にした理由**: この索引は⑩jdk-api-navigation（K）・Ctrl+Space補完
    （`CompletionIndex.allSimpleNames()`経由）とも共有されており、どの用途であっても「外部から
    importできない＝実質参照できない内部実装クラス」を候補に出すことに意味がないため、
    auto-import専用のフィルタではなく索引レベルで除外した。`java.lang.String`のような
    「エクスポートされてはいるが単にimport不要」なケースとは性質が異なる（java.lang.String
    自体は正当な公開APIであり、K・補完では引き続き有効な候補であるべきのため後述の修正2と
    分離した）。
- **修正2（`java.lang`パッケージの除外、`ImportSuggester`）**: `java.lang`直下のクラス
  （`String`/`Math`/`Thread`等）は全てのコンパイル単位に暗黙にimportされるため、import文の
  候補として提示すること自体が誤り。`ImportSuggester.suggest(String)`/`suggest(String, Path)`の
  両方に`filterImportable()`を追加し、候補の**パッケージ名が完全一致で`"java.lang"`**のものを
  除外するようにした（前方一致にすると`java.lang.reflect.Method`のようなimportが必要な
  サブパッケージまで誤って除外してしまうため、`packageOf(fqn).equals("java.lang")`で判定する）。
  `JdkClassIndex`自体は変更していない（Ctrl+Space補完・Kキーではjava.lang.String等の一般的な
  クラス名が引き続き候補・検索対象になるべきのため、ImportSuggesterという「import候補を返す」
  役割の層だけに閉じた変更にした）。
- **修正後の実機確認**: `suggest("String")` → `[]`（java.lang.Stringのみだった候補が空になり、
  そもそも未解決シンボルとして扱われず何も起きなくなる）、`suggest("List")` →
  `[java.util.List, java.awt.List]`（正当な曖昧候補は従来どおり残る）、`suggest("Method")` →
  `[java.lang.reflect.Method, com.sun.jdi.Method]`（`sun.jvm.hotspot.oops.Method`のみ除外され、
  jdk.jdiの`com.sun.jdi.Method`はエクスポート済みの正当な候補として残る）。
- **テスト**: 既存の`JdkClassIndexTest`（18/18）・`AutoImportHandlerTest`（53/53）は無修正で
  全PASSを確認（`lookup("String")`が`java.lang.String`を含むことを検証する既存テストは、
  内部クラスの重複が消えても引き続き成立する）。全体は既存のベースラインFAIL（`ScrollTest`2件・
  `ModalEditorTest`1件＝本変更前から失敗、仕様判断未決のため修正禁止）を除き全PASS。

## auto-import 挿入直後、波下線（診断）の表示位置が実際のエラー行とずれる不具合の修正（2026-07-26）

- **症状**: auto-importで`import`文が自動挿入されると、既に画面に表示されていたエラー行の
  波下線・ガター（E/Wマーカー）が、挿入前のソースを基準にした古い行番号のまま取り残され、
  実際にエラーが存在する行（importの挿入で下にずれた後の行）とはズレた位置に表示され続けていた。
- **原因**: `Main.runCompileAnalysis()`は、バックグラウンドで得たコンパイル診断`diags`
  （挿入前のソースを基準に行番号を計算済み）を`canvas.setDiagnostics(diags)`で先に画面へ
  反映してから`editor.handleAutoImport(diags)`を呼んでいた。`handleAutoImport()`内部では
  `AutoImportHandler.applyImport()`が`import`文をバッファへ挿入する（`insertAndReorganize()`が
  package文の直後などコード本体より前に行を追加する）ため、挿入位置より後ろにある全ての行が
  下にずれる。しかし表示済みの`diags`はこの挿入を一切知らず、古い行番号のまま
  `EditorCanvas`にキャッシュされ続けていた（IMPORT_SELECTモードで複数候補から選択して
  挿入する経路＝`exitImportSelect()`でも同様の問題があった）。
- **修正**: `ModalEditor`に`shiftDiagnosticsAfterImportEdit(before, after)`（新設・private）を
  追加した。現在表示中の診断（`canvas.getDiagnostics()`または`localDiagnostics`）の行番号を、
  import挿入前後のテキストの行数差（`countLines(after) - countLines(before)`）だけ一律で
  シフトし直し、`setDiagnostics()`で再反映する。import文は常にコード本体より前（先頭寄り）
  にのみ挿入されるため、既存の診断は例外なく挿入位置より後ろにあり、一律シフトで正しく
  補正できるという前提を利用した。`handleAutoImport()`の単一候補自動挿入パスと、
  `exitImportSelect()`の複数候補選択確定パスの両方に、バッファ変更の前後で
  `shiftDiagnosticsAfterImportEdit()`を呼ぶよう追加した。
- **意図的にスコープ外とした点**: import削除（`removeImport`/`removeUnusedImports`）・
  Ctrl+Shift+Oの`@Override`挿入（`insertOverrideStub()`）など、他のバッファ変更経路は
  診断シフトの対象にしていない。いずれも保存直後にコンパイル解析が再トリガーされ
  診断そのものが作り直される経路のため、古い診断が画面に残り続けるという実害がなく、
  今回の不具合（auto-import挿入直後、再解析が完了する前の一瞬〜数百msの間だけ表示される
  古い診断がズレる）とは性質が異なると判断した。
- **テスト**: `test/dev/javatexteditor/editor/AutoImportDiagnosticShiftTest.java`（新設・
  3テストメソッド/7アサーション）。単一候補自動挿入・複数候補選択（IMPORT_SELECT経由）の
  両方で、import挿入前に表示していた診断の行番号が、挿入後の実際のエラー行と一致するよう
  補正されることを検証。挿入対象がない場合（候補ゼロ）は診断行が変化しないことも確認。

## Markdownビューア（`:view`/`:mark`）の新規実装（2026-07-27）

「`.md`ファイルを開いたら最初はそのままのソースを表示し、`:view`コマンドでMarkdownファイルに
限りViewerとして描画、`:mark`で元のソース表示に戻したい」という要望に基づく新規機能。詳細な
設計判断は`.claude/skills/markdown-viewer/SKILL.md`に集約した。ここでは経緯と、確認が取れな
かった箇所についてどう判断したかを記録する。

- **実装前に`AskUserQuestion`で2点（①レンダリング方式をプレーン整形表示にするか色付きレンダ
  リングにするか、②`:view`中を保存不可にするか）を確認しようとしたが、応答が得られなかった**。
  CLAUDE.md「作業時の方針」の「既存のSkillの内容と矛盾する実装をしようとしている場合は、実装を
  進める前にユーザーに確認すること」という原則と、本ファイル中の他の多くの機能（F10/F11/F12・
  `:pr`・クリップボード・共有バッファ等）が実装前にユーザー確認を経ている慣行を踏まえ、双方の
  質問とも**推奨案（選択肢の1番目）をそのまま採用して実装を進めた**。次にこの機能へ触れる開発者
  は、ユーザーから別方針の指示があれば以下の決定を上書きしてよい。
  1. **レンダリング方式はプレーン整形表示を採用**（色付きレンダリングは不採用）。理由:
     このエディタの本文描画は等幅ビットマップフォントのグリッド描画で、フォントスタイル変更は
     元々不可能。色分けは技術的には可能（構文ハイライト・error行と同じ`uiGlyphCache`機構で1文字
     ごとに任意色を付けられる）だが、それには`SyntaxHighlighter`/`SourceLanguage`相当の新しい
     トークン化・色付けロジックと`EditorCanvas`側の配線が別途必要になり、実装コストと変更範囲が
     大きく増える。プレーン整形表示なら`MarkdownRenderer`が生成したテキストを既存の疑似バッファ
     表示（`*grep*`/`*binary*`/`*class*`と同じ「buffer差し替え」パターン）にそのまま乗せるだけで
     済み、`EditorCanvas`に一切手を入れずに実現できるため、こちらを選んだ。
  2. **`:view`中は`:w`を不可にする方式を採用**（`:b`のように同じファイルパスのまま編集・保存
     できる方式は不採用）。理由: `:view`はあくまで「読み取り専用のプレビュー」であり、誤って
     `:w`すると実`.md`ファイルがレンダリング後のテキストで上書きされてしまう実害がある。
     `.class`構造ビュー（classfile-viewer）と同じ「`currentFilePath`をnullにする」方式を踏襲した
     （詳細・他方式との比較はSKILL.md「なぜ『読み取り専用プレビュー』をclassfile-viewer方式に
     したか」節を参照）。
- **出力する記号はASCII印字可能文字(0x20-0x7E)のみに限定した**。telescope選択行マーカーを
  `"▸ "`から`"> "`へ変更した既存の教訓（`.claude/skills/gui-rendering-pipeline/SKILL.md`参照。
  ビットマップフォント非対応文字はSwingフォールバック描画になり、`charCellWidth()`が想定する
  セル幅とフォールバック描画の実際の幅がずれるリスクがある）を踏まえた判断。見出しの下線は
  `=`/`-`、水平線は`-`、引用は`| `、箇条書きは`- `、タスクリストは`[ ]`/`[x]`で表現する。
- **新規パッケージ`dev.javatexteditor.markdown`**（`MarkdownRenderer`のみ、Swing非依存の純粋
  ロジック）。`ModalEditor`には`isMarkdownBuffer()`/`enterMarkdownView()`/`exitMarkdownView()`
  と`markdownViewOwner`/`markdownViewSaved*`フィールドを追加し、`executeCommand()`に`:view`/
  `:mark`を配線した。`Main.java`・`EditorCanvas.java`は無変更（Fキーのようなグローバルディス
  パッチも不要で、既存のCOMMANDモード経由の疑似バッファパターンだけで完結する機能のため）。
- **テスト**: `test/dev/javatexteditor/markdown/MarkdownRendererTest.java`（72テスト、純粋ロジ
  ック）・`test/dev/javatexteditor/editor/MarkdownViewTest.java`（26テスト、`ModalEditor`統合。
  `:view`/`:mark`往復でバッファ参照が同一オブジェクトのまま保たれること＝共有バッファ整合性を
  含む）。既存のベースラインFAIL（`ScrollTest`2件・`ModalEditorTest`1件＝いずれも本変更前から
  失敗、仕様判断未決のため修正禁止）を除き全PASS。

## ModalEditor 神クラス解体リファクタリング 第1弾（2026-07-27）

「プロジェクト全体のコードが大きくなりすぎて読みづらい。SOLID原則に沿った美しいクラス設計へ、
ただし規模を小さく・デグレを起こさないよう慎重に」という依頼に基づく、`ModalEditor`（6,850行・
355メソッドの神クラス）の段階的な解体。**今回は第1弾であり、まだ続きがある**（後述「次に着手すべき候補」）。

### 進め方（次の担当者もこの手順を踏襲すること）

1. **着手前に全テストのベースラインを取る**。`./scripts/test.sh` は1クラスでも落ちると途中で止まるため、
   各テストクラスを個別JVM＋`timeout 180`で回し、クラスごとの合否を1ファイルに記録する方式を使った。
   ベースライン（本リファクタリング開始時点、全91テストクラス）:
   - 89クラス PASS
   - `dev.javatexteditor.editor.ScrollTest` — 2件FAIL（Ctrl+Uの仕様変更にテストが未追従。
     **仕様判断未決のため修正禁止**。既存の「既知の未接続・二重定義」6.・REFACTORING_PLAN U-7 参照）
   - `dev.javatexteditor.editor.ModalEditorTest` — 1件FAIL（同じくベースラインからの既知FAIL）
2. **1つの抽出につき1コミット**。抽出 → `./scripts/build.sh` → 影響範囲のテストクラスだけを個別実行 →
   コミット、を繰り返す。中核状態に触れた回（バッファ履歴）と最終回だけ全91クラスを回し、
   **ベースラインとの差分がゼロであること**を `diff` で機械的に確認した。
3. **公開シグネチャは一切変更しない**。テスト側が `getYankType()` の文字列値や
   `getSearchMatches()` の `List<int[]>` 型に依存しているため（REFACTORING_PLAN §1.5）。
   今回の抽出はすべて「内部フィールドと private メソッドの移動」に留めてある。

### 抽出したクラス（すべて `dev.javatexteditor.editor` パッケージ・package-private）

| 新クラス | 抽出元の責務 | 主な改善点 |
|---|---|---|
| `SystemClipboardAccess` | OSクリップボードの読み書き（Ctrl+Shift+C / Ctrl+Shift+V） | 「何が取れたか」を sealed interface `ReadResult`（`Content`/`Empty`/`Failure`）で返す。`ModalEditor` 側は `switch` のパターンマッチ3行になり、「OSと話す処理」と「テキストを編集する処理」が分離された |
| `MacroRecorder` | Vim式マクロの記録・再生（`q`/`@`/`@@`） | 散在していた状態7個を集約。再生は `KeyReplayer` 関数型インタフェース経由でキーを呼び出し側へ差し戻すため `ModalEditor` への逆依存がない。失敗理由は `PlayOutcome` enum で返し、日本語文言は `reportMacroOutcome()` 1箇所に集約 |
| `UserPathResolver` | コマンド行のパス文字列 → 絶対パス | `resolveRelativeToProjectRoot()` と `resolveRelativeToBuildRoot()` は基準ディレクトリが違うだけの完全な重複だった。`resolveAgainst(baseDir, pathSpec)` 1本に統合 |
| `BufferHistory` + `BufferSnapshot` | Ctrl+U/Ctrl+P の疑似バッファ履歴 | `historyIdx >= 0 && historyIdx < bufferHistory.size() - 1` のような境界条件の書き下しを `hasPrevious()`/`hasNext()` に置換。「現在位置より後ろを切り捨てる」「離れる直前の状態を書き戻す」という暗黙ルールを `push()`/`moveTo()` の内側へ隠蔽。`BufferSnapshot` は履歴とShift+Kの復帰点の2箇所で共有されるためトップレベル record へ昇格 |
| `CompletionPopupState` | 補完ポップアップの状態（Ctrl+Space / Alt+/ 共通） | 5フィールドを毎回まとめて書き換える暗黙の不変条件を `openWith()`/`close()` に隠蔽。4箇所に重複していたクランプ式 `Math.min(idx+1, size-1)` を `selectNext()`/`selectPrevious()` へ集約。Ctrl+N と ↓、Ctrl+P と ↑ が完全に同一動作だったため4ブロック→2ブロックへ統合 |
| `BufferTextSearch` | `/`・`n`・`N` の一致箇所計算 | 前方/後方でほぼ同一だった2つの走査ループを `selectNearest()` 1本に統合し、Vim互換の「ファイル端で折り返す」規則に名前を付けた。`{offset, length}` の `int[]` 表現は `getSearchMatches()` の公開シグネチャ維持のため**あえて record 化していない** |

結果: `ModalEditor.java` は 6,850行 → 6,657行（-193行）。行数の減少幅より、
**5つの状態クラスタが名前付きの型として独立し、`ModalEditor` から「フィールドを直接いじる」記述が消えたこと**が本質。

### 意図的に手を付けなかった箇所（次の担当者が「直し忘れ」と誤解しないための記録）

- **識別子プレフィックスの後方走査が3箇所に重複**（`extractCompletionPrefix()`／`prefixStartOffset()`／
  `applyCompletion()` 内のループ）。CLAUDE.md 既存節「補完候補の並び順を Vim の i_CTRL-N に合わせる」で
  **「3行の重複は早すぎる抽象化よりよい」という方針に従いあえて共通化しなかった**と明記済みの
  記録済み設計判断のため、今回も覆さなかった。共通化する場合はユーザーに確認すること。
- **`ScrollTest` 2件・`ModalEditorTest` 1件の既知FAIL**。仕様判断が未決のため「ついでに」直さない
  （既存節「既知の未接続・二重定義」6. と同じ扱い）。
- **`processNormalKey()`（約360行）・`executeCommand()`（約100行の if-else 連鎖）・`syncCanvas()`** 等の
  巨大メソッド本体。分割にはモード遷移の意味論に踏み込む判断が必要で、今回の「小さく安全に」という
  依頼の範囲を超えるため見送った。
- **`EditorCanvas`（1,863行）・`Main`（1,282行）**。今回は最大の神クラス1つに絞った。

### 次に着手すべき候補（優先度順）

1. ~~**`ModalEditor` の疑似バッファ退避・復元の系統統一**~~ → **✅ 第2弾で完了**（下記参照）
2. **`executeCommand()` の if-else 連鎖 → コマンド名 → ハンドラの `Map` 化**。`:wa`/`:qa` の
   `equals` 完全一致判定と `startsWith` 判定が混在しており、追加順序に依存する暗黙の優先順位がある。
   順序依存を壊さないことの検証が必須。
3. **FILER／TELESCOPE／FILESEARCH／IMPORT_SELECT／CLASSPATH_INPUT の疑似モード群**。いずれも
   `KeymapRegistry` をバイパスして `processXxxKey()` で直接キーを処理する同型の構造をしており、
   共通のインタフェース（例: `ModalScreen`）へ寄せられる可能性がある。

## ModalEditor 神クラス解体リファクタリング 第2弾（2026-07-27）— 疑似バッファ退避の統一

第1弾の「次に着手すべき候補」1. を実施した。進め方（着手前の全クラスベースライン取得 →
1抽出1コミット → Phase 完了時にベースラインとの `diff` ゼロを機械的に確認 → 公開シグネチャ不変）は
第1弾と同じ手順を踏襲している。

### 何が重複していたか

疑似バッファを表示している間、その裏に隠れる「元の編集状態」を預かる仕組みが**7系統**に分かれ、
いずれも `xxxSavedBuffer` / `xxxSavedFilePath` / `xxxSavedCursorRow` / `xxxSavedCursorCol` という
**同じ4フィールドを重複して持っていた**（計28フィールド）。保存側・復元側とも代入順まで一致し、
復元時の `saved != null ? saved : new UndoablePieceTable("")` というフォールバックも7箇所に散っていた。

対象7系統: telescope / `:cd`候補 / `:e`候補 / FILER / jdk-source / `*compile*`・`*run*` / Markdown閲覧ビュー。

### 何をしたか

- **`PseudoBufferStash`（新設）**: 4点セットを預かるだけの型。空フォールバックを `buffer()` の中に
  1つだけ置き、7箇所の三項演算子の重複を解消した。
- **`ModalEditor.saveToStash()` / `restoreFromStash()`**: 各系統の保存・復元が1行になった。
- **28フィールド → 7フィールド**。`ModalEditor` は 6,657行 → 6,603行。

### 引き継ぎ上の重要な設計判断（次の担当者は必ず読むこと）

1. **バッファは「本文の写し」ではなく生きた `UndoablePieceTable` の参照として預かる。**
   Vim方式の共有バッファ（同一ファイルを複数ペインで開くと同一インスタンスを共有する）を
   壊さないために必須。ここで新インスタンスを作り直すと、疑似バッファを開いて閉じただけで
   そのペインが共有から静かに外れる。本文をコピーする `BufferSnapshot`（Ctrl+U/Ctrl+P の履歴用）
   とは役割が異なるので**混同しないこと**。
2. **型は共通化したが、インスタンスは用途ごとに7つ独立させたまま**にした。1つに統合すると
   「疑似バッファを重ねて開いた場合の挙動は未定義・未テスト」（本ファイル「既知の未接続・二重定義」5.）
   という現状の意味論を意図せず変えてしまう。**この未定義事項は第2弾でも解消していない**。
   統合するなら、まず重ね合わせ時の仕様をユーザーと確定させること。
3. **各系統に固有の「おまけの状態」は stash に入れなかった**:
   - telescope … `telescopeSavedGrepResults` / `telescopeSavedGrepBaseDir` / `telescopeSavedFileNameResults`
   - `:cd`候補 / `:e`候補 … `cdSavedCommandText` / `edSavedCommandText`（COMMAND モード復帰用）

   いずれも「疑似バッファの退避」とは別の関心事のため `ModalEditor` 側に残してある。
4. **既存の細かな差異はそのまま維持**した。例: `restoreCdSavedBuffer()` は
   `resetSearchAndResultState()` を呼ぶが `restoreEditSavedBuffer()` は呼ばない。
   統一したくなるが、挙動を変えないことを優先して手を付けていない。

### 検証

全93テストクラスを個別JVMで実行し、ベースラインと `diff` で完全一致を確認。
既知FAIL は `ScrollTest` 2件・`ModalEditorTest` 1件のみで増減なし（いずれも仕様判断未決のため修正禁止）。

### 次に着手すべき候補（更新）

第1弾の候補はすべて完了した（1.＝第2弾、2.＝第3弾、`processNormalKey()` の分割＝第4弾、
3.＝第5弾）。次の候補は `EditorCanvas`（1,863行・119フィールド・setter 25個）と
`Main`（1,282行）だが、いずれも公開APIやGUI配線に触れるため影響範囲が広い（第5弾末尾を参照）。

## ModalEditor 神クラス解体リファクタリング 第3弾（2026-07-27）— :コマンドの表化

第1弾の「次に着手すべき候補」2. を実施した。手順（着手前の全クラスベースライン取得 → 1抽出1コミット →
Phase 完了時にベースラインとの `diff` ゼロを機械的に確認 → 公開シグネチャ不変）は第1〜2弾と同じ。

### 何が問題だったか

`executeCommand()` は 96行・**35分岐の if-else 連鎖**で、`cmd.equals(...)` の完全一致判定と
`cmd.startsWith(...)` の前置一致判定が交互に現れていた。そのため「どの分岐が先に来るか」が
暗黙の仕様になっており、コマンドを1つ足すたびに連鎖のどこへ差し込むべきかを読み解く必要があった。

### 何をしたか

- **`CommandRegistry`（新設）**: 「完全一致をすべて調べる → 前置一致を登録順に調べる →
  見つからなければ false を返す」という振り分けの仕組みだけを持つ。個々のコマンドが
  何をするかは知らないため、`ModalEditor` 抜きで単体テストできる。
- **`ModalEditor.buildCommandRegistry()`**: 「どんなコマンドが存在するか」の一覧。
  `r.on(this::saveAll, "wa", "wall")` のように別名をまとめて宣言でき、
  用途ごとにコメントで区切った表として読める。**コマンド追加は分岐の挿入ではなく表への1行追加**になった。
- `:q` / `:wq` / `:pr` / `:pr?` のインラインだったブロックは `closeCurrentPane()` /
  `saveAndCloseCurrentPane()` / `pinProjectRoot()` / `reportProjectRoot()` として名前を付けて切り出した。

### 並び替えの安全性をどう担保したか（次の担当者が最も気にすべき点）

元の連鎖は完全一致と前置一致が交互だったが、新実装は**完全一致を全部先に**調べる。
これが挙動を変えないことを、着手前に次の4点で機械的に検証した。

1. 完全一致のコマンド名35個はすべて一意（重複なし）
2. **完全一致の名前は1つも空白を含まず、前置一致の接頭辞はすべて空白で終わる**
   → 完全一致の名前が前置一致にマッチすることはありえず、前置一致する文字列が
     完全一致の名前と等しくなることもありえない（＝両集合は provably disjoint）
3. 前置一致どうしで、一方が他方の接頭辞になっているものは無い
   （`"grep! "` と `"grep "` は互いに接頭辞ではない。ただし登録順は元の順序どおり維持した）
4. 引数の切り出しは全11接頭辞で `substring(接頭辞の長さ).trim()` に統一されている

さらに移行後、正規表現で表を機械的に読み出し、**元の35個の完全一致名と11個の接頭辞が
過不足なく登録されている**ことを確認した（欠落・追加ともゼロ）。

### 意図的に維持した順序

- **`:s` 置換（`handleSubstituteCommand`）は表より前**。範囲指定（`%`・`'<,'>`・`N,M`）や
  可変の区切り文字を持ち他と形が違うため、表には載せず専用の述語のまま最初に判定する。
  なお `sPart_isSubstitute()` は2文字目が英数字なら false を返すので、`:sp`/`:split` を
  横取りすることはない（この性質に依存している）。
- **行番号ジャンプ（`\d+`）と unknown command は表より後**。どのコマンド名にも一致しなかった
  場合の最後の受け皿という位置づけを保つため。
- **`":grep! "` は `":grep "` より先に登録**。前置一致は登録順に評価されるため
  （実際には互いに接頭辞ではないので順序に依存しないが、元の意図を残した）。

### テスト

`test/dev/javatexteditor/editor/CommandRegistryTest.java`（新設・12アサーション）。
別名の共有・引数の trim・空引数・登録順評価・完全一致と前置一致が混ざらないこと・後勝ち登録を検証。
全94テストクラスを個別JVMで実行し、着手前ベースラインと `diff` で完全一致（既知FAIL の増減なし）。

## ModalEditor 神クラス解体リファクタリング 第4弾（2026-07-27）— processNormalKey の分割

単一メソッドとして本プロジェクト最大だった `processNormalKey()`（**356行**）を分割した。
手順（着手前の全クラスベースライン取得 → 1スライス1コミット → Phase 完了時に `diff` ゼロを確認 →
公開シグネチャ不変）は第1〜3弾と同じ。**356行 → 100行**になった。

### 3スライスの内訳

| スライス | 切り出した内容 | 行数 |
|---|---|---|
| 4-1 | `handleNormalModeInterrupt()` — 画面に出ているもので意味が決まる割り込みキー（疑似バッファを閉じる/ジャンプする Enter・q・Esc、Ctrl+U/Ctrl+P、マクロ記録終了の q）。11個の早期 return | 約80行 |
| 4-2 | `handlePendingSequence()` — `dd`/`yy`/`gg`/`gu`/`gU`/`g~`/`gr`/`gv`/`r`/`zz`/`[g`/`[d`/`s`系/`\f`/`\g`/`\a`系/`SPC`系の2打鍵目以降。41個の早期 return | 132行 |
| 4-3 | 60ケースの switch のうち複数行だった19ケースに名前を付け、対応表として読める形にした | 約60行 |

### 早期 return の順序制約をどう扱ったか（次の担当者が最も気にすべき点）

このメソッドは早期 return の**並び順そのものが仕様**である。分割にあたっては
「連続した領域をまるごと1つの boolean メソッドへ移す」方式に限定し、**個々の分岐を並べ替えていない**。
判定順が変わらないことを構造的に保証するのが狙い。

- `handleNormalModeInterrupt()` の11個の return はすべて「横取りして処理した」→ `return true`、
  末尾に `return false` を追加。
- `handlePendingSequence()` の41個の return も同様。入口で `pendingSequence` を空に戻す既存の挙動を
  そのまま維持しているため、「どの分岐にも当たらなければ保留は破棄され、押されたキーは通常のキーとして
  扱われる」という Vim 同等の落下挙動は変わらない。3打鍵目を待つ分岐（`gu`・`\a`・`SPC g` 等）が
  `pendingSequence` を改めて設定してから true を返す点も同じ。
- 順序に意味がある箇所（マクロ終了の `q` は `pendingSequence` の途中状態に関わらず最優先／
  `*compile*`/`*run*` の Esc は通常の Esc 処理より前／`seq.equals("gu")` 等の3打鍵目の完了判定は
  2打鍵目の遷移判定より先）は、それぞれの Javadoc に**なぜその順序でなければならないか**を明記した。

### `enterVisualMode()` に隠れていた既存の非対称性

`v` / `V` / `Ctrl+V` の3ケースを1メソッドに統合する際、**`V`（行選択）だけは `anchorCol` を
更新しない**という既存の挙動があることが分かった（行選択は列を持たないため）。条件付きで維持し、
コメントで理由を明記してある。この挙動が変わっていないことは、リフレクションで
`anchorRow`/`anchorCol` を直接覗くプローブを**リファクタ前後の両バイナリで実行**し、
3モードすべて同一の結果（`v`→列を更新 / `V`→据え置き / `Ctrl+V`→列を更新）であることを確認した。
ここを「3つとも同じだろう」と単純化すると `V` の選択範囲が静かに壊れるので注意すること。

### 8つの `*.pending` ケースの集約

`macro.record` / `macro.play` / `goto` / `diag` / `screen.center` / `split` / `leader` / `filesearch` の
8ケースはいずれも「`pendingSequence` と `statusMessage` を組で設定する」という同一形だったため、
`beginSequence(sequence, indicator)` へ集約した。多打鍵シーケンスを追加するときはこれを呼ぶ。

### 検証

全94テストクラスを個別JVMで実行し、着手前ベースラインと `diff` で完全一致。
既知FAIL は `ScrollTest` 2件・`ModalEditorTest` 1件のみで増減なし（いずれも仕様判断未決のため修正禁止）。

## ModalEditor 神クラス解体リファクタリング 第5弾（2026-07-27）— 疑似モード群

第1弾の「次に着手すべき候補」3.（FILER/TELESCOPE/FILESEARCH/IMPORT_SELECT/CLASSPATH_INPUT の
疑似モード群）を実施した。手順は第1〜4弾と同じ。

### 計画時の仮説は外れた（重要な記録）

第1弾では「5つの `processXxxKey()` はいずれも `KeymapRegistry` をバイパスする同型の構造なので、
共通のインタフェース（例: `ModalScreen`）へ寄せられる可能性がある」と記録していたが、
**実際に5つを読み比べたところ、この仮説は成り立たなかった**。実態は次の3種類に分かれる。

| 種別 | 該当 | 形 |
|---|---|---|
| 1行入力プロンプト | `processSearchKey` / `processFileSearchKey` / `processClasspathInputKey` | Esc・Backspace・Enter・印字可能文字の4分岐だけ |
| 一覧選択のみ | `processImportSelectKey` | 自由入力なし。Esc・Enter・上下移動だけ |
| 入力＋一覧のハイブリッド | `processTelescopeKey` / `processFilerKey` | 打鍵ごとに絞り込み再実行、Ctrl+D 等の固有キーあり |

これらを1つのインタフェースに押し込むと、実装クラスの大半のメソッドが空になるか、
分岐フラグを持つことになり、かえって読みにくくなる。**共通インタフェース化は見送った。**
代わりに、種別をまたいで実際に重複していた2つの塊だけを抽出した。

### 実際に抽出したもの

1. **`handleTextPromptKey(keyCode, keyChar, input, onCancel, onCommit)`** — 上表「1行入力プロンプト」の
   3つは分岐の順序まで一致する完全な重複だった。画面ごとに違うのは「どの入力欄か」
   「取り消したら何をするか」「確定したら何をするか」の3点だけなので、それだけを引数で受け取る。
   3箇所にあった Backspace の空チェックが1箇所になった。
   `\f`/`\g` の bang 処理は `runFileSearch()` として名前を付けて切り出した。
2. **`isSelectNextKey` / `isSelectPrevKey`（Ctrl+N・Ctrl+P / ↓・↑）と
   `isVimNextKey` / `isVimPrevKey`（j / k）** — 一覧移動キーの判定が5画面10箇所に重複していた。
   **あえて2組に分けたのが要点**: `j`/`k` を移動キーに使えるのは自由入力のない画面だけで、
   telescope や FILER の検索モードでは `j` は文字入力として扱わなければならない
   （移動に割り当てると "j" を含む名前を検索できなくなる）。
   分けたことで、どの画面が `j`/`k` を受け付けるかが呼び出し側の
   `isSelectNextKey(...) || isVimNextKey(...)` という式そのもので読めるようになった。
   従来はコメントを読まないと区別できなかった。

### 検証

全94テストクラスを個別JVMで実行し、着手前ベースラインと `diff` で完全一致。
既知FAIL は `ScrollTest` 2件・`ModalEditorTest` 1件のみで増減なし。

### 次に着手すべき候補

`ModalEditor` 側の大きな重複はこれで一巡した。残るのは別クラスになる。

1. **`EditorCanvas`（1,863行・private フィールド119個・public setter 25個・draw 系メソッド17個）**。
   `syncCanvas()` が毎キー入力ごとに25個の setter を26回呼ぶ構造で、描画状態の受け渡しを
   値オブジェクト（`SelectionView`/`CompletionView`/`TelescopeView`/`DiagnosticsView` 等）へ
   束ねる余地がある。ただし setter は `Main` と `EditorCanvasTest`(51)・`RobotKeyInputTest`・
   `KeyboardSimulationTest` から使われる**公開API**であり、第1〜5弾で守ってきた
   「公開シグネチャは変更しない」ルールと正面から衝突する。着手するなら、
   旧 setter を委譲として残す移行期間を設けるか、ルールの一時的な緩和をユーザーと合意すること。
2. **`Main`（1,282行）** — `PaneTree` / `GlobalKeyDispatcher` / `BuildRunner`(F10–12) /
   `IndexBootstrap` へ分けられる。ただし GUI 配線は自動テストできない既知のギャップがあるため、
   純粋ロジックを先に抜くこと。

## EditorCanvas リファクタリング 第6弾（2026-07-27）— 描画状態の値オブジェクト化

`EditorCanvas`（1,863行・private フィールド119個・public setter 25個）に着手した。
**ユーザーの明示的な選択により「旧 setter を委譲として残す移行期間を設ける」方式を採用**している
（第5弾末尾で提示した2案のうちの①）。これにより第1〜5弾の「公開シグネチャは変更しない」ルールは
そのまま維持され、既存の呼び出し側（`Main`・`EditorCanvasTest`・`RobotKeyInputTest`・
`KeyboardSimulationTest`・各 Preview）は1行も変えずに動く。

### 抽出した3つの値オブジェクト（`dev.javatexteditor.ui`）

| レコード | まとめた対象 | 旧API |
|---|---|---|
| `CompletionView` | 補完ポップアップの6フィールド | `setCompletionState(6引数)` → `setCompletionView()` へ委譲 |
| `TelescopeView` | 候補一覧オーバーレイの6フィールド | `setTelescopeState(6引数)` → `setTelescopeView()` へ委譲 |
| `SelectionView` | 選択範囲の3 boolean + 4座標 | 旧5メソッドは据え置き。`setSelectionView()` を新設 |

`CompletionView` / `TelescopeView` は**内部表現もレコードに置き換えた**（フィールド6個→1個）。
描画側にあった `completionActive && !completionLabels.isEmpty()` のような組み合わせ条件は
`hasVisibleItems()`、範囲チェック付きの種別取得は `kindAt(i)` としてレコード側に集約した。

### `SelectionView` だけ内部表現を置き換えていない理由（重要）

`visualMode` / `visualLineMode` / `visualBlockMode` の3 boolean は本来
`Kind`（NONE/CHARACTER/LINE/BLOCK）という1つの4状態の値であり、enum 化したくなる。
**しかし内部表現の置き換えは見送った。**

理由: 既存の `setVisualMode` / `setVisualLineMode` / `setVisualBlockMode` / `clearSelection` は
互いに独立した部分更新として呼ばれており、`EditorCanvasTest` が**実際に順序を入れ替えて**
呼んでいる（`setVisualLineMode(true)` → `setVisualMode(true)` の逆順、
`clearSelection()` の後に `setVisualMode(false)` など）。
これらを4状態の列挙へ写す変換は呼び出し順によって解釈が割れる。しかも当該テストは
**描画が例外なく完了することしか確認しない smoke test** であり、誤った変換を検出できない。
「変換を間違えても気づけない」状況で内部表現を変えるのは割に合わないと判断した。

内部表現を enum 化する場合は、先に `EditorCanvasTest` を「描いた結果の選択範囲を検証する」
テストへ作り替えること。それなしに着手してはならない。

### 挙動同一性の確認方法

`SelectionView` は上記のとおりテストが値を検証しないため、
**`EditorCanvas` の選択関連7フィールドをリフレクションで直接覗くプローブを
リファクタ前後の両バイナリで実行**し、NORMAL / `v` / `V` / `Ctrl+V` の4モードすべてで
完全に同一（選択なし時に座標が -1 になる点まで一致）であることを確認した。
第4弾の `anchorCol` 検証と同じ手法。

なお、この種のプローブを書く際は **`main` の末尾に `System.exit(0)` を必ず入れること**。
`EditorCanvas` のコンストラクタがステータス行アニメ用の Swing Timer を start するため、
入れないと JVM が終了しない（REFACTORING_PLAN P-23 と同じ現象）。

### 検証

全94テストクラスを個別JVMで実行し、着手前ベースラインと `diff` で完全一致。
既知FAIL は `ScrollTest` 2件・`ModalEditorTest` 1件のみで増減なし。

### 次に着手すべき候補

1. ~~**`EditorCanvas` の残り**~~ → **✅ 第7弾で完了**（下記参照）
2. **`Main`（1,282行）**: `PaneTree` / `GlobalKeyDispatcher` / `BuildRunner`(F10–12) /
   `IndexBootstrap` へ分けられる。GUI 配線は自動テストできない既知のギャップがあるため、
   純粋ロジックを先に抜くこと。

## EditorCanvas リファクタリング 第7弾（2026-07-27）— 再描画のまとめとガター幅の集約

第6弾末尾で「描画タイミングを変える変更はテストで検出できないため、着手するなら目視確認の手段を
用意すること」と条件を付けていた項目。**その手段（ピクセル単位の描画比較）を先に用意してから**着手した。

### 先に用意した検証手段: 描画結果のピクセルハッシュ比較

`EditorCanvas` は `BufferedImage` へ直接 `paint()` できる（`EditorCanvasTest` と同じ方式で、
Xvfb もディスプレイも不要）。これを使い、NORMAL / INSERT / VISUAL / VISUAL LINE / VISUAL BLOCK /
COMMAND / SEARCH / DIAGNOSTICS / LIGHT+WRAP の9シナリオを描画してピクセルを SHA-256 で
ハッシュ化するプローブを作り、**リファクタ前後の両バイナリで実行して完全一致を確認**した。

**この種の検証を行う人が必ず踏む落とし穴が2つある。**

1. **ステータス行は実行ごとに描画が変わる。** 歩行キャラクターのアニメーションが
   `System.currentTimeMillis() - animStartMs` で位置を決めるため、同一ビルドでも実行のたびに
   ハッシュが変わる。**本文領域だけ**（画像の下端48pxを除く）をハッシュ対象にすることで決定的になる
   （同一ビルド3回で同一ハッシュになることを確認してから使うこと）。
   コマンド行の内容はステータス行にあるため、必要なら `getCommandLineText()` で別途検証する。
2. **`ModalEditor` の既定テーマは `DARK_MODE`、`EditorCanvas` 側の既定は `LIGHT_MODE`。**
   `syncCanvas()` が前者を push するので、テーマ切替を検証するシナリオで `DARK_MODE` を
   指定しても no-op になる。当初これに気づかず、別シナリオとハッシュが一致したことで発覚した。
   テーマ差を見たいなら `LIGHT_MODE` を指定すること。

### 実施した2件

1. **`gutterWidthFor(charWidth)`** — 「診断が無ければ幅0、あればマーカー2セル分」というガター幅の
   規則が本文描画・カーソル描画・折り返し計算の3箇所に同じ式で書かれていた。描画位置がずれないために
   3箇所は必ず同じ値でなければならないため、1箇所に集約した。
2. **再描画のまとめ（`batchUpdate`）** — 各 setter が個別に `repaint()` を呼ぶため、
   `syncCanvas()`（1キー入力につき1度）が**10回**の再描画を予約していた。
   `batchUpdate(Runnable)` を新設し、実行中は `updateBatchDepth` を立てて個々の setter は
   `requestRepaint()`（まとめ中は予約しない）を呼ぶようにした。`syncCanvas()` の本体を
   これで包み、**1キー入力あたりの再描画予約が 10回 → 1回**になった（計測プローブで実測）。
   - Swing は次の描画までに予約をまとめるため**表示結果は元から同じ**であり、これは性能改善ではなく
     「1回の更新は1回の再描画」という意図をコードに表すための変更である。
   - ステータス行アニメの `repaintStatusLine()` は領域指定版 `repaint(x,y,w,h)` を使うため
     この仕組みとは無関係（アニメは従来どおり30fpsで動く）。
   - `syncCanvas()` の本体はすべてラムダ内に入るため、ローカル変数のキャプチャ問題は生じない。

### 検証

全94テストクラスを個別JVMで実行し、着手前ベースラインと `diff` で完全一致。
既知FAIL は `ScrollTest` 2件・`ModalEditorTest` 1件のみで増減なし。
加えて上記のピクセルハッシュ9シナリオが前後で完全一致。

### 次に着手すべき候補

~~**`Main`（1,282行）**~~ → **✅ 第8弾で純粋ロジック部分を完了**（下記参照）。
`EditorCanvas` 側に残る改善余地としては `paintContent()` から17個の draw 系メソッドへの
分岐の整理があるが、描画順序の入れ替えは上記ピクセル比較で検出できるので、
着手するならその手段を再利用すること。

## Main リファクタリング 第8弾（2026-07-27）— 純粋ロジックの切り出し

第7弾末尾の指針「GUI 配線は自動テストできないため、**純粋ロジックを先に抜くこと**」に従い、
`Main`（1,282行）から Swing に依存しない3つの塊だけを抜いた。
`Main` は 1,282行 → 1,189行。

### 抽出した3クラス（すべて新規テスト付き）

| クラス | 内容 | テスト |
|---|---|---|
| `PaneTree` | 画面分割のツリー構造（`PaneNode`/`Leaf`/`Split`）と `allLeaves`/`splitLeaf`/`removeLeaf` | `PaneTreeTest`（14） |
| `ui.DisplayMetrics` | 起動時の拡大率・フォントセル・ウィンドウサイズの算出 | `DisplayMetricsTest`（10） |
| `BufferRegistry` | 開いたファイルの一覧（SPC+b / Ctrl+U / Ctrl+P の巡回対象） | `BufferRegistryTest`（8） |

**`PaneTree` の抽出が最も価値が大きい**: 分割・ペインクローズのロジックは
「GUI 依存だからテストできない」と見なされ**これまでテストが1件も無かった**が、
実際には `JSplitPane` を組み立てるのは `Main.buildComponent()` の役目であり、
ツリー操作自体は Swing に一切依存しない純粋ロジックだった。
操作はどれもリーフの中身を見ず参照の同一性だけで対象を探すため、
`new Leaf(null, null)` を並べるだけで構造を検証できる。

`DisplayMetrics` も同様に「画面情報を調べる処理」（`Main` に残した）と
「そこから倍率とサイズを計算する処理」（切り出した）が混ざっていたため、
分けることで実ディスプレイなしに検証できるようになった。

### 意図的に統合しなかったもの（重要）

**拡張子による言語判定が3種類あるが、これらは統合してはならない。**
一見重複に見えるが、対象とする拡張子の集合が用途ごとに異なる。

| 判定 | 対象拡張子 | 用途 |
|---|---|---|
| `Main.isCBuffer` | `.c` `.h` のみ | F10/F11/F12 のCツールチェーン振り分け・C診断 |
| `ModalEditor.isCFilePath` | `.c` `.h` `.cc` `.cpp` `.cxx` `.hpp` `.hh` `.hxx` | Shift+K の定義ジャンプ |
| `ui.SourceLanguage.detect` | 上と同じ広い集合 | 構文ハイライト |

統合すると C++ ファイルが C コンパイラへ回される等、挙動が変わる。

### 手を付けなかった箇所

F10/F11/F12 のビルド・実行群（`triggerCompile`/`doCompile`/`runJavaClass`/
`runCExecutable`/`startRunOutputReader` 等、約230行）は `Main` の static 状態
（`PROJECT_BUILDER`・`runningProcess`・`pendingRunExtraClasspath`）と EDT ディスパッチに
深く結びついており、切り出しても子プロセス起動と GUI 反映は自動テストできない
（既知のギャップ）。純粋ロジックが尽きた時点で止める方針に従い、今回は対象外とした。
同様に `KeyboardFocusManager` のグローバルディスパッチャ・`refreshCallbacks` の配線も残している。

### 検証

全97テストクラス（新規3クラス含む）を個別JVMで実行し、既存94クラスの結果が
着手前ベースラインと `diff` で完全一致。既知FAIL は `ScrollTest` 2件・
`ModalEditorTest` 1件のみで増減なし。
加えて描画結果のピクセルハッシュ（9シナリオ）が変更前と完全一致することを確認した。

## `Main` クラス解体リファクタリング 第9弾（2026-07-27〜28、段階0〜7・全完了）

第8弾で純粋ロジックを抜いた後も残っていた `Main`（1,189行・GUI組み立て・グローバルキー処理・
サービス生成・F10/F11/F12・診断連携が同居）を、Composition Root（`main()` のみを持つ
最小クラス）へ縮小する計画（`docs/MAIN_DECOMPOSITION_PLAN.md`）を策定・全段階実行した。
判定基準は一貫して「`Main` に残ってよいのは部品を組み立てる記述だけ。`if`/`for` を含む処理は
1行も置かない」。段階6・7はそれぞれ専用のサブ段階分割計画書（`docs/STAGE6_OPTION_C_PLAN.md`・
`docs/STAGE7_PLAN.md`）を作り「1サブ段階=1コミット」で慎重に進めた。

`Main.java`: 1,189行 → **15行**（`EditorApplication.launch(args)` を呼ぶだけ）。
新設パッケージ `dev.javatexteditor.app` に11クラス（`SetupBootstrap`/`AnalysisServices`/
`LiveDiagnostics`/`DiagnosticPopup`/`RunningProcessHolder`/`JavaBuildRunner`/`CBuildRunner`/
`ProcessOutputPump` 相当の出力読み取り/`PaneManager`/`EditorHost`/`GlobalKeyDispatcher`/
`EditorApplication`）が生まれた。段階ごとの詳細・気づきは `docs/MAIN_DECOMPOSITION_PLAN.md` §9
（進捗記録欄）と両サブ計画書に集約済み。ここでは「次にこの領域を触る開発者が必ず知っておくべき
設計判断」だけを抜粋する。

- **`runningProcess`（F11/F12で起動した子プロセス）は Java 版・C 版の両ビルドランナーで
  意図的に1つを共有し続ける**（`RunningProcessHolder`）。「F11 で Java を実行した後、C を F11 で
  実行すると先の Java プロセスが `destroy()` される」という**言語をまたいだ多重実行防止**が
  既存仕様であり、`JavaBuildRunner`/`CBuildRunner` が別々のフィールドとして持つと挙動が変わる
  （両方が同時に走れてしまう）。両ランナーのコンストラクタに必ず同一インスタンスを渡すこと。
  `volatile` は付けていない（切り出し前の `Main.runningProcess` も付いていなかったため、
  「振る舞いを変えない」という段階2の制約に従い据え置いた。EDT とバックグラウンド仮想スレッドから
  可視性保証なく読み書きされている既知の課題として残っている。付けるかどうかは別途判断）。
- **`compileGeneration`（auto-import の世代ガード、CLAUDE.md「auto-import選択ポップアップの
  無限再発」節参照）は `LiveDiagnostics.install(editor, canvas)` 呼び出しのたびにローカルで
  新規生成し、クロージャで捕捉する方式のまま維持した**。`LiveDiagnostics` は全ペインで共有される
  単一インスタンスだが、世代カウンタ自体をインスタンスフィールドにすると**編集対象（ペイン）を
  またいで世代が共有されてしまい、別ペインの解析結果が互いを打ち消し合う**新規バグになる。
  分割ウィンドウ使用時にのみ顕在化する種類の不具合のため、次にこのクラスへ手を入れる開発者は
  「1インスタンスを全ペインで共有している」という事実を必ず意識すること。
- **`AnalysisServices`（JDKクラス索引・補完索引）の生成は `EditorApplication` の
  `static final` フィールドの初期化子のままにしてあり、`launch()` メソッドの中や
  `SwingUtilities.invokeLater` の中には絶対に移してはならない**。`JdkClassIndex.build()` は
  非同期でバックグラウンド構築を**開始する合図**にすぎないため、呼び出しタイミングが遅れると
  起動直後の Ctrl+Space / Shift+K が空振りする（クラッシュしないため自動テストでは検知できない）。
  段階5で確定したこの制約は、段階7で `Main` → `EditorApplication` へ置き場所が変わった後も
  `javap -c -p EditorApplication.class` の `<clinit>` 逆アセンブルで初期化順序が保たれていることを
  確認済み。親計画書のスケッチにあった `StartupArgs` という新規抽象クラスは**採用しなかった**
  （段階5で確立済みの static final フィールド方式をそのまま延長する方が、新しい抽象を増やさずに
  同じ保証を維持できるため）。
- **`EditorHost`（`PaneManager` が実装する、`ModalEditor` → ペイン管理への窓口インタフェース）
  導入時は旧 setter を削除せず、移行期間方式（第6弾で確立済みのパターン）を踏襲した**。
  `ModalEditor.setHost(EditorHost)` は内部で個別 setter（`setSplitHorizontalCallback` 等
  8個）へ委譲する形にし、旧公開シグネチャは1つも削除していない。
  **例外が1つだけある**: `setHost()` は `setCloseBlockedCallback` を意図的に配線しない。
  `ModalEditor.closeCurrentPane()`/`saveAndCloseCurrentPane()` は
  `closeBlockedCallback != null` という**null自体を「閉じられない」の判定条件**として使っており、
  ここへ何か（no-op であっても）配線すると `:q`/`:wq` が常に無効化される。段階6-5の
  Xvfb+Robot手動検証で実際にこの回帰を発見・修正した（詳細は `docs/STAGE6_OPTION_C_PLAN.md`
  段階6-5節）。次に `setHost()` 経由の配線を拡張する開発者は、個々の setter が
  「未設定=null」をどう解釈しているか（単に無視するのか、それとも分岐条件として使うのか）を
  必ず個別に確認すること。
- **自動テストでは検出できない領域が2段階（段階6・7）で明確になった**。`ModalEditor`/
  `EditorCanvas` の97〜99テストクラス全件が PASS していても、ペイン分割・フォーカス移動・
  `:q`・共有バッファ同期・グローバルキーディスパッチャ（`pressedHandled` の二重処理防止）・
  IME確定経路はいずれも自動テストの対象外で、`Xvfb` + `java.awt.Robot`（または
  `InputMethodEvent` の直接発火）による手動検証でしか壊れていないことを確認できない
  （段階6-5の `:q` 回帰は好例。97クラス全ての `diff` が空でも回帰は検出されなかった）。
  次にこの領域（`PaneManager`/`EditorHost`/`GlobalKeyDispatcher`/`EditorApplication`）を
  変更する開発者は、`diff` が空であることだけをもって「壊れていない」と判断しないこと。
- **IME確定経路（`canvas.setImeCommitHandler` → `editor.processKey(0, ch, 0)`）は
  `GlobalKeyDispatcher`/`pressedHandled` と完全に独立した別経路である**（AWTの
  `InputMethodEvent`/`inputMethodTextChanged` 機構を通り、`KEY_PRESSED`/`KEY_TYPED`
  のどちらも経由しない）。段階7の手動検証では、`PaneManager.createLeaf()` が実際に配線する
  ラムダ本体を再現しつつ `InputMethodEvent` を `canvas.inputMethodTextChanged()` へ
  直接発火する方式で、この経路が壊れていないこと（「あ」の確定で1文字だけ挿入されること）を
  確認した。実IMEの無いヘッドレス環境で日本語入力を検証する際の標準手順として、次に
  この領域を触る開発者はこの手法を再利用してよい。

**意図的に見送った既存の残課題（変更なし）**: `PaneTree`/`BufferRegistry`/
`WorkingDirectoryManager` を `dev.javatexteditor` 直下から `app/` へ移動する件（R-1）・
`ModalEditor`/`EditorCanvas` のさらなる分割（R-2/R-3）・パッケージ境界の機械的検査（R-4）・
既知の失敗3件の仕様確定（R-5）・`Main` に対する自動テストの新設（R-6）はいずれも
`docs/MAIN_DECOMPOSITION_PLAN.md` §8 に記載のとおり本計画のスコープ外のまま。

## `:e` で存在しないファイルを指定した際の新規作成確認（2026-07-28）

「`:e` コマンドで指定されたファイルが存在しない場合は、対象のディレクトリに作成するかを
y/n で尋ね、y なら新規作成・n なら何もしない」という要望に基づく変更。

- **不具合ではなく仕様変更**: 従来 `:e newfile.txt` は確認なしで即座に空の新規バッファを
  作成していた（本ファイル既存の「`currentFilePath` の絶対パス統一と新規ファイル作成時の
  不具合修正」節で扱っていたのは「新規作成後にバッファ履歴へ登録されない」という別の不具合で、
  作成すること自体は無条件だった）。今回はその「無条件で作成する」動作を「確認してから作成する」
  へ変更した。
- **実装**: `Mode.CONFIRM_NEW_FILE`（新設）を追加した。`:e <path>` の実行は
  `requestLoadFromFile(path)`（新設）に差し替え、`Files.exists()` で存在確認した上で
  ①存在すれば従来どおり `loadFromFile()` を即座に呼ぶ、②存在しなければ `pendingNewFilePath`
  にパスを保持したまま `Mode.CONFIRM_NEW_FILE` へ遷移し、ステータス行に
  `"<path>" は存在しません。新規作成しますか？ (y/n)` を表示する、の2分岐にした。
  `processConfirmNewFileKey()` が `y`/`Y` で `loadFromFile()`（＝実際の新規作成）を呼び、
  `n`/`N`/Esc で何もせず `Mode.NORMAL` に戻る（バッファ・`currentFilePath`とも無変更）。
  それ以外のキーは無視し y/n/Esc の入力を待ち続ける。
- **他の疑似モード群（FILESEARCH/CLASSPATH_INPUT等）と同型のパターンを踏襲**: `KeymapRegistry`
  を経由せず `processKey()` のモード分岐に `CONFIRM_NEW_FILE` を追加するだけで完結する、
  既存の「1行入力プロンプト」系（第5弾リファクタリングの `handleTextPromptKey` 分類）と同じ
  設計方針。ただし本機能は自由入力を持たない単純な y/n/Esc 判定のみのため、
  `handleTextPromptKey()` は使わず専用の `processConfirmNewFileKey()` を新設した。
- **`loadFromFile()` の他の呼び出し元（FILER・telescope・`gr`・`\g`・Shift+K定義ジャンプ等）は
  変更していない**。これらは検索結果・ディレクトリ一覧など「実在が既に分かっているパス」を
  開く経路であり、存在しないパスを渡すことは想定されていないため、確認プロンプトを挟む
  必要はないと判断した。
- **テスト**: `test/dev/javatexteditor/editor/ConfirmNewFileTest.java`（新設・11アサーション）。
  y確認での新規作成・n/Escでの作成キャンセル（ファイル未作成・バッファ内容維持）・既存ファイルは
  確認なしで即座に開かれることを検証。既存の `BufferSwitchTest.testNewFileCreatedViaColonEIsRegistered()`
  は `:e newfile.txt` 実行後に `y` キー入力を追加する形で新仕様に合わせて更新した。

## `:w`/`:enew` への新規作成確認の拡張と、`:e`/`:cd` 同様の TAB 補完（2026-07-28）

上記の `:e` の y/n 確認を「`:w` と `:enew` にも同じ仕様を追加してほしい。また `:e`/`:cd` と
同様に TAB キーでディレクトリ・ファイル名の入力補完ができるようにしてほしい」という要望に
基づき拡張した。

- **確認の仕組みを汎用化**: `Mode.CONFIRM_NEW_FILE` 自体は変更せず、「y が押されたときに
  何をするか」を `Runnable` として保持する `pendingNewFileAction`（新設）を追加した。
  `requestLoadFromFile(path)`（:e/:enew 用）は `() -> loadFromFile(path)` を、新設した
  `requestSaveToFile(pathSpec)`（:w 用）は `() -> saveToFile(pathSpec)` を渡す。両者は
  `requestConfirmNewFile(displayPath, onConfirmed)`（新設）という共通の入口を経由する。
  `processConfirmNewFileKey()` は `y`/`Y` でこの `Runnable` を実行するだけになり、:e/:enew/:w の
  分岐を個別に持たない。
- **`:w`（保存）**: `requestSaveToFile(pathSpec)` が `resolveSavePath()`（既存、`s/pattern/repl/`
  置換・`~`展開・相対パス解決を担う）で解決した絶対パスの実在を `Files.exists()` で確認し、
  ①存在すれば従来どおり `saveToFile()` を即座に呼ぶ（＝既存ファイルへの上書き保存は確認なし）、
  ②存在しなければ y/n 確認を挟む、の2分岐にした。**引数なしの bare `:w`（`currentFilePath` を
  保存先とする場合）も同じ経路を通る**——`:enew`/`:e` で作った「まだ一度もディスクに書き出して
  いない新規ファイル」に対する最初の `:w` は、`currentFilePath` こそ設定済みだが実体は
  存在しないため、こちらも確認対象になる。2回目以降の `:w`（同じファイルへの上書き）はファイルが
  既に存在するため確認なしになる。
- **`:wq`（保存して閉じる）・`:wa`（全保存）は対象外のまま**: `saveAndCloseCurrentPane()` は
  引き続き `saveToFile()` を直接呼ぶ。`:wq` の最中に y/n 確認へ分岐すると「保存に成功したときだけ
  閉じる」という戻り値ベースの単純な制御フローが崩れ、確認待ちのまま中途半端にペインを閉じる
  条件分岐が別途必要になる。ユーザーからの要望は `:w`/`:enew` に限定されていたため、複雑化を
  避けてスコープ外とした。同様に `:wa`（`allEditorsSupplier` 経由で複数編集対象を一括保存）も
  対象外とした——1回の `:wa` で複数の確認プロンプトが連続して出る設計は使い勝手が悪く、
  かつ「未保存の変更があるものだけを黙って全部保存する」という既存の意味論とも相性が悪いため。
- **`:enew <path>`（新規: パス引数付き）**: 従来 `:enew` は常に無名の空バッファを作るだけで
  パス引数を一切受け付けていなかった（`"e"`/`"enew"` の完全一致のみ登録、前置一致は `"e "` の
  みで `"enew "` は無かった）。`r.onPrefix("enew ", path -> requestLoadFromFile(...))` を追加し、
  `:enew <path>` を `:e <path>` と全く同じ意味（存在すれば開く、存在しなければ y/n 確認の上で
  新規作成）にした。引数なしの `:enew`（無名バッファ作成）は無変更。
- **TAB 補完を `:e` から `:enew`/`:w` へ一般化**: 従来 `handleEditTabCompletion()` は
  `"e"`/`"e "` 決め打ちだった。`handleEditTabCompletion(String verb)` に変更し、
  `processCommandKey()` の TAB 分岐で `cmd` が `"e "`/`"enew "`/`"w "` のいずれで始まるかに応じて
  対応する `verb`（`"e"`/`"enew"`/`"w"`）を渡すようにした。渡された `verb` は新設の `edVerb`
  フィールドに保持し、補完確定時（`applyEditCandidate()`／複数候補時の `*e候補*` 疑似バッファで
  Enter を押した後の `applySelectedEditCandidate()`）に `verb + " " + path` の形で
  `commandBuffer`/`executeCommand()` を組み立てる際に使う。**判定順序に注意**: `"enew"` は
  `"e"` で始まるため、TAB 分岐では `"enew"`/`"enew "` の判定を `"e"`/`"e "` より先に置く
  必要がある（`cmd.startsWith("e ")` は `"enew foo"` にはマッチしないため実害はないが、
  `cmd.equals("e")` 単体分岐と `"enew"` 単体分岐の順序を誤ると意図が読み取りにくくなるため、
  安全側で先に置いた）。
  - 候補一覧（ファイル・ディレクトリ）を作る実体のロジック（`DirectoryLister` 呼び出し・
    前方一致フィルタ・0件/1件/複数件の分岐）は3つの verb で完全に共通のため、`:cd` 用の
    `handleCdTabCompletion()`（ディレクトリのみが対象で挙動が異なるため独立のまま）とは別に、
    1つの汎用メソッドに統合した（`:e`/`:enew`/`:w` の3つ目を機械的に複製すると
    CLAUDE.mdの「3行の重複は早すぎる抽象化よりよい」の許容範囲を明らかに超えるため、
    ここは統合した）。
  - `*e候補*` 疑似バッファの見出し文言・退避フィールド（`edStash`/`edCandidates`等）は
    verb 間で共有する（同時に2つの verb で補完中になることはないため問題ない）。
- **テスト**: `test/dev/javatexteditor/editor/WriteAndEnewConfirmTest.java`（新設・19アサーション）。
  `:w <新規パス>` の y/n 確認（作成/キャンセル）・既存パスへの `:w` は確認なし・
  `:enew` で作った未保存の新規ファイルへの bare `:w` も確認対象になること・
  `:enew <path>` の y/n 確認（作成/キャンセル）・`:enew <既存パス>` は確認なしで即座に開くこと・
  `:w`/`:enew` それぞれの TAB 補完（単一候補で即座に補完）を検証。
  既存の `BufferSwitchTest.testSaveNewBufferRegistersAbsolutePath()`（`:enew` → `:w newname.txt`
  という新規ファイル保存の回帰テスト）は `:w newname.txt` 実行後に `y` キー入力を追加する形で
  新仕様に合わせて更新した。全93テストクラスを個別JVMで実行し、既知のベースラインFAIL
  （`ScrollTest` 2件・`ModalEditorTest` 1件）以外はすべてPASSであることを確認済み。
