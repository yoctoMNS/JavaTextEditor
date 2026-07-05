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
| ㉔ | `windows-batch-and-subprocess` | `scripts/*.bat`編集・Javaからのサブプロセス出力読み取りの恒久ルール（ASCII専用・ブロック内丸括弧禁止・`native.encoding`） | ✅ Skill追加（⑫実装時のバグ3連鎖から抽出した開発プロセス知識。機能実装は伴わない） |
| ㉕ | `modal-visual-block-selection` | Vim矩形選択（`Ctrl+V`・VISUAL BLOCK）のモード追加・ヤンク/削除/ペースト/矩形挿入(`I`/`A`)/矩形変更(`c`)/矩形置換(`r`)・描画 | ✅ 完了（12テスト・`YankType.BLOCK`追加・ペースト時の新規行自動生成・矩形挿入は既存INSERTモードを再利用し状態フラグで複製する方式） |

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

## 作業時の方針

- 何かを実装・設計する前に、関連する`.claude/skills/`配下のSKILL.mdを必ず確認すること。
- 既存のSkillの内容と矛盾する実装をしようとしている場合は、実装を進める前にユーザーに確認すること。
- 新しい設計判断を行った場合、その判断と理由を該当するSKILL.md（またはこのCLAUDE.md）に書き残すこと。口頭の会話だけで終わらせない。

## 既知の未接続・二重定義（リファクタ調査 2026-07 時点）

次の開発者が片側だけ修正する事故を防ぐための記録。いずれも「消してよいか／どちらが正か」の仕様判断が未決定のため、判断せずに残してある（docs/REFACTORING_PLAN.md の P-10〜P-13・P-21・U-7 参照）。

1. **NORMAL モード Ctrl+U/P のバッファ切替が二重実装**: `ModalEditor.processNormalKey` 冒頭のハードコード（`bufferHistory` スナップショット方式）が優先され、`KeymapRegistry` の `buffer.prev`/`buffer.next`（`switchToRelativeBuffer` = レジストリ一覧巡回方式）には既定キーから到達しない。プラグインが別キーに `buffer.prev` を束縛すれば後者にも到達可能なため、安易に削除しないこと。2実装のどちらを正とするかは未決定。
2. **COMMAND モードの registry 束縛は機能しない**: `processCommandKey` は KeymapRegistry を参照せず ESC/Enter/TAB をハードコードで処理するため、`KeymapRegistry` の COMMAND モード束縛（`enter.normal`/`execute.command`）は現状到達不能。外部（プラグイン）からの参照想定が不明なため削除しない。
3. **`CompletionIndex.refreshProjectSymbols()` は未使用**: 本番・テストとも呼び出しゼロ。Javadoc の「保存時に呼ぶ」想定で呼ぶ場合は、`ready==true` 後にバックグラウンドで `TreeMap` を更新すると EDT の `query()` と同期なしで競合するため、不変マップ差し替え等の並行更新対策が先に必要。
4. **`extension/` パッケージ（PluginLoader ほか）は本番経路から未接続**: `:plugin` 等の起動コマンドが未実装のため、動的コンパイル・プラグイン機構はテストからしか呼ばれない（ロードマップ③⑥は機構としては完了、UI 接続のみ未着手）。
5. **疑似バッファ退避2系統の相互作用は未定義**: jdk-source 疑似バッファ（`saved*` フィールド群）と `*cd候補*` 疑似バッファ（`cdSaved*` フィールド群）を重ねて使った場合の挙動は未定義・未テスト。
6. **`ScrollTest` の2ケース（halfPageUp 系）は恒常的に FAIL する**: Ctrl+U の仕様変更（半ページスクロール → バッファ履歴を前へ）にテストが追従しておらず、ベースライン時点で 18/20 PASS。テストを更新するかキー割当てを戻すかは未決定（REFACTORING_PLAN.md U-7）。どちらの修正も仕様判断を伴うため「ついでに」直さないこと。
