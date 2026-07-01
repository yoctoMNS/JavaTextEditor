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
│   └── requirements.md
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
| ⑳ | `telescope-picker` | telescope.vim風ファジーファインダー（SPC+f/SPC+//SPC+b・3ペインオーバーレイ） | ✅ 完了（28/28テスト・FilePicker/GrepPicker/BufferPicker・FuzzyMatcher） |
| ㉑ | `simple-filer` | `:cd` 実行後に表示されるディレクトリ一覧・ファイルブラウザ（FILERモード） | ✅ 完了（46/46テスト） |
| ㉒ | `editor-tutorial` | `:tutor`/`:tutorial` で開く vimtutor 形式の対話型チュートリアル | ✅ 完了（9/9テスト） |
| ㉓ | `symbol-definition-navigation` | Shift+K（定義へジャンプ、Eclipse/IntelliJ流に統合）/Shift+J（一つ前の参照へ戻る）/`gr`（参照一覧、jdk-source疑似バッファ内では`lib/openjdk-native/`のネイティブ実装側を検索） | ✅ 完了（28/28テスト・`ProjectSymbolResolver`・⑩の`jumpToMethod`を`jumpToMember`に一般化してJDKフィールドにも対応。`gd`は`K`に統合し廃止。`executeGrep`/`jumpToGrepResult`をbaseDir一般化して⑫`openjdk-source-tracing`のnative参照検索を先行実装） |

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
- **描画の再利用**: `EditorCanvas.setTelescopeState()` / `drawTelescopeOverlay()` を FILER モードでも流用（IMPORT_SELECT と同じパターン）。`DirEntry` を `TelescopeItem` に変換して渡す。
- **純粋ロジックの分離**: ディレクトリ列挙・フィルタは `dev.javatexteditor.search.DirectoryLister` に独立させ、ModalEditor はオーケストレーション（状態管理・キー処理）のみ担う。
- **ファイルオープンの再利用**: `openSelectedEntry()` がファイルを選択した際は既存の `loadFromFile(String)` を呼び出す（pushBuffer・onFileOpened コールバック等が確実に動く）。

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

## 作業時の方針

- 何かを実装・設計する前に、関連する`.claude/skills/`配下のSKILL.mdを必ず確認すること。
- 既存のSkillの内容と矛盾する実装をしようとしている場合は、実装を進める前にユーザーに確認すること。
- 新しい設計判断を行った場合、その判断と理由を該当するSKILL.md（またはこのCLAUDE.md）に書き残すこと。口頭の会話だけで終わらせない。
