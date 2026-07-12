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

## F10/F11/F12（`*compile*`/`*run*`疑似バッファ）のリアルタイムログ表示・標準エラー赤字化

- **要望**: javac/javaコマンド実行時のバッファ画面を、完了を待たずリアルタイムにログ表示し、かつ標準エラー出力を赤字で区別してほしいという依頼。従来は`ProjectBuilder.compile()`/`Main.runJavaClass()`ともプロセス・コンパイルタスクの完了を同期的に待ってから`ModalEditor.showCompileResult()`/`showRunOutput()`で一括描画しており、実行中は`*compile*`/`*run*`疑似バッファが完全に固まって見えていた。
- **F11/F12（実行）: 標準出力/標準エラーの分離が前提条件だった**。従来`ProcessBuilder.redirectErrorStream(true)`で両方をマージしてから読んでいたため、そもそも「どの行が標準エラー由来か」という情報が読み取り時点で失われていた。`redirectErrorStream(true)`を外し、`process.getInputStream()`（標準出力）・`process.getErrorStream()`（標準エラー）をそれぞれ独立した仮想スレッド（`Main.startRunOutputReader()`）で読み、1行読むたび`SwingUtilities.invokeLater()`で`ModalEditor.appendRunOutputLine(line, isError)`を呼んで`*run*`疑似バッファへ即座に追記する。`process.waitFor()`の後に両読み取りスレッドを`join()`してから終了コードを確定させる（プロセス終了後もパイプに残ったバッファの読み切りを保証するため）。標準出力/標準エラーの2スレッドが独立に`invokeLater`するため、実際のプロセス内での出力順序と厳密には一致しない場合がある（両ストリームがそれぞれ内部バッファリングされるため）ことは既知の制約として許容した。
- **F10/F12（コンパイル）: `javax.tools.JavaCompiler`の`DiagnosticListener`は元々コールバック型でストリーミング向き**。従来は`DiagnosticCollector`（`task.call()`完了後に`getDiagnostics()`で一括取得するラッパー）を使っていたが、`DiagnosticListener<JavaFileObject>`を自前実装に差し替えるだけで、javacが診断を検出するたび同期的に呼ばれるコールバックとして扱える。`ProjectBuilder.compile(Path, List<Path>, Consumer<BuildDiagnostic> onDiagnostic)`オーバーロードを新設し、診断1件ごとに`onDiagnostic`へ通知しつつ内部リストにも蓄積して最終`BuildResult`を返す（`compile(Path, List<Path>)`は空コンシューマを渡す後方互換オーバーロードとして維持）。`onDiagnostic`はコンパイルスレッド上で同期的に呼ばれるため、UIスレッドへのディスパッチ（`SwingUtilities.invokeLater`）は呼び出し側（`Main.doCompile()`）の責務とし、`ProjectBuilder`自体はSwingに依存しない設計を維持した。
- **`ModalEditor`側の疑似バッファAPIは「開始→ストリーミング追記→確定」の3段階に分割した**（`beginCompileOutput()`/`appendCompileDiagnostic()`/`finishCompileOutput()`、`beginRunOutput()`/`appendRunOutputLine()`/`finishRunOutput()`）。既存の一括表示API（`showCompileResult()`/`showRunOutput()`、`BuildOutputCommandTest`が依存）は非ストリーミング用途（テスト・後方互換）としてそのまま残し、変更していない。`finishCompileOutput()`は`showCompileResult()`へ単純委譲する（javacの`command`文字列はソース走査後でないと確定せず、開始時点では先頭行の行数が未確定なため、ストリーミング中の追記内容を逐次パッチするより最終結果から丸ごと再構築する方が単純で確実）。一方`finishRunOutput()`は、`java`コマンド文字列がプロセス起動前から確定しているため疑似バッファの行数がストリーミング中ずっと変わらないことを利用し、2行目（ステータス行）のプレースホルダ文字列だけを`buffer.delete()`+`buffer.insert()`でその場置換する（ストリーミングで追記済みの出力行は一切再構築しない）。
- **行番号ベースの赤字マーキングは「バッファ参照との一致」で自動失効させる方式にした**。`ModalEditor`には`buffer = new UndoablePieceTable(...)`という再代入が約25箇所に散らばっており（`:grep`・FILER・telescope・cd候補等の全疑似バッファ切替）、赤字対象行の集合（`outputErrorLines`、`Set<Integer>`）を都度クリアして回るのは取りこぼしのリスクが高い。そこで`outputErrorLinesOwner`（その`Set`がどの`buffer`インスタンスに対応するかを参照一致で覚えておくフィールド）を追加し、`syncCanvas()`が`canvas.setErrorLines(...)`へ渡す直前に`outputErrorLinesOwner == buffer`を判定する方式にした。ユーザーが`*run*`/`*compile*`表示中に他の疑似バッファ（`:grep`等）へ切り替えると`buffer`が新しいインスタンスに再代入されるため、この判定だけで古い赤字行が別バッファに漏れ出ることを防げる。既存の25箇所の`buffer = new UndoablePieceTable(...)`呼び出しには一切手を入れていない。
- **`EditorCanvas`の赤字描画は既存の`ERROR_COLOR`定数（ガター描画で使用済み）と、UI文字列描画専用の`uiGlyphCache`（`(codePoint, cellW, cellH, rgb)`をキーに任意色のグリフをキャッシュする仕組み。telescope・ステータス行等で既に使用）をそのまま流用した**。本文専用の`glyphCacheFg`（テーマの前景色に固定・キーはcodePointのみ）とは別に、赤字対象行だけ`getUiGlyph(codePoint, cellW, cellH, ERROR_COLOR)`を呼ぶよう`drawLineWithFullWidthSupport()`に`isErrorLine`引数を追加した。新しい色管理の仕組みは追加せず、既存のキャッシュ機構2つの使い分けだけで実現している。`EditorCanvas.setErrorLines(Set<Integer>)`が公開APIで、`syncCanvas()`から毎回呼ばれる。
- **副次的に修正した点（`syncCanvas()`の可視性）**: 調査の過程で、従来`doCompile()`/`runJavaClass()`が完了後に`editor.showCompileResult(result)`/`showRunOutput(...)`を呼んだ直後、`canvas.repaint()`だけを呼び`canvas.setText(...)`（＝`private syncCanvas()`経由でのみ呼ばれる）を呼んでいなかったことに気づいた。`EditorCanvas`は`cachedLines`をキャッシュしており`setText()`でのみ更新されるため、この経路では新しいバッファ内容が画面に反映されず、次にユーザーが何かキーを押す（＝`processKey()`経由で`syncCanvas()`が呼ばれる）までF10/F11/F12の結果が可視化されない可能性があった。`syncCanvas()`を`private`から`public`に変更し、`Main.java`の`doCompile()`/`runJavaClass()`・新設の`startRunOutputReader()`から`canvas.repaint()`の代わりに`editor.syncCanvas()`を呼ぶようにした（`syncCanvas()`内部で最終的に`canvas.repaint()`相当の再描画も行われるため置き換えで問題ない）。
- **意図的にスコープ外とした点**: 標準出力/標準エラーの読み取りスレッドが個別に`invokeLater`するため、大量出力時にSwingのイベントキューへの積み上げがボトルネックになりうるが、コンパイル/実行ログの規模（通常は数十〜数百行程度）を想定したスコープでは許容した。スロットリング（例: N行または一定時間ごとにまとめて反映）は要望になく未実装。標準入力を要求するプログラム（`Scanner`等）が正しく動作しない既存の制約（F10/F11/F12の設計決定事項の節を参照）は変更していない。
