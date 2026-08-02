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
│   ├── decision-log.md                   ← 機能追加・不具合修正ごとの詳細な設計判断ログ
│   └── archive/                          ← 完了済みのリファクタリング実行計画書・提案書
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
| ⑱ | `text-search` | 単語検索（*・#・n・N、Vim互換）＋Emacs式インクリメンタルサーチ（C-s/C-r、NORMAL/INSERT両モード）。旧`/`によるVim式パターン検索は2026-07-29に廃止しEmacs式へ一本化 | ✅ 完了（*/#/n/N 28/28テスト・Emacs式52/52テスト） |
| ⑲ | `file-search` | \fファイル名検索・\gファイル内容grep（NORMALモード・疑似バッファ表示） | ✅ 完了（43/43テスト） |
| ⑳ | `telescope-picker` | telescope.vim風ファジーファインダー（SPC+f/SPC+//SPC+b・あいまい検索は維持しつつ`\f`/`\g`と同じ疑似バッファ表示） | ✅ 完了（28/28テスト・FilePicker/GrepPicker/BufferPicker・FuzzyMatcher。3ペインオーバーレイは2026-07に廃止、詳細はSkill参照） |
| ㉑ | `simple-filer` | `:cd` 実行後に表示されるディレクトリ一覧・ファイルブラウザ（FILERモード）。`I`で名前編集（`:w`確定）・Ctrl+Dで削除（y/n確認） | ✅ 完了（51/51テスト） |
| ㉒ | `editor-tutorial` | `:tutor`/`:tutorial` で開く vimtutor 形式の対話型チュートリアル | ✅ 完了（9/9テスト） |
| ㉓ | `symbol-definition-navigation` | Shift+K（定義へジャンプ、Eclipse/IntelliJ流に統合）/Shift+J（一つ前の参照へ戻る）/`gr`（参照一覧、jdk-source疑似バッファ内では`lib/openjdk-native/`のネイティブ実装側を検索） | ✅ 完了（28/28テスト・`ProjectSymbolResolver`・⑩の`jumpToMethod`を`jumpToMember`に一般化してJDKフィールドにも対応。`gd`は`K`に統合し廃止。`executeGrep`/`jumpToGrepResult`をbaseDir一般化して⑫`openjdk-source-tracing`のnative参照検索を先行実装） |
| ㉔ | `windows-batch-and-subprocess` | `scripts/*.bat`編集・Javaからのサブプロセス出力読み取りの恒久ルール（ASCII専用・ブロック内丸括弧禁止・`native.encoding`） | ✅ Skill追加（⑫実装時のバグ3連鎖から抽出した開発プロセス知識。機能実装は伴わない） |
| ㉕ | `modal-visual-block-selection` | Vim矩形選択（`Ctrl+V`・VISUAL BLOCK）のモード追加・ヤンク/削除/ペースト/矩形挿入(`I`/`A`)/矩形変更(`c`)/矩形置換(`r`)・描画 | ✅ 完了（12テスト・`YankType.BLOCK`追加・ペースト時の新規行自動生成・矩形挿入は既存INSERTモードを再利用し状態フラグで複製する方式） |
| ㉖ | `vim-substitution` | Vim式置換コマンド`:s`（現在行・`%s`全行・`'<,'>s`Visual選択範囲・`N,Ms`行番号範囲・`.,+Ns`・magic正規表現・`g`/`i`/`c`/`e`フラグ・`\1`/`&`/`\u`\U`\l`\L`置換） | ✅ 完了（新規`dev.javatexteditor.substitute`パッケージ＝`VimSubstituteCommandParser`/`VimRegexTranslator`/`VimReplacementBuilder`/`VimSubstituteExecutor`に本番経路を一本化。旧実装は`*Legacy`サフィックスで参照実装としてのみ残置。`c`フラグは`Mode.SUBSTITUTE_CONFIRM`で1件ずつ確認） |
| ㉗ | `vim-macro-recording` | Vim式マクロ（`q{register}`記録・`q`終了・`@{register}`再生・`@@`直前マクロ再実行・大文字レジスタ追記） | ✅ 完了（29/29テスト・記録は`processKey()`入口1箇所で生キーを捕捉・マクロ専用レジスタは既存の`yankRegister`とは独立・記録中の入れ子`@`呼び出しは展開せず呼び出し2キーのみ記録・`count`付き再生(`3@a`)は汎用count機構が存在しないためスコープ外） |
| ㉘ | `vim-case-conversion` | Vim式大文字小文字変換（NORMALの`~`・`guu`/`gUU`/`g~~`、VISUAL/VISUAL_LINE/VISUAL_BLOCKの`u`/`U`/`~`） | ✅ 完了（23/23テスト・operator-pendingモーション（`guiw`等）は②の既存スコープ外判断を踏襲し未対応・doubled-letter方式のみ実装） |
| ㉙ | `classfile-viewer` | `.class`ファイルを開いた際のJVM仕様通りの構造ビュー表示（マジックナンバー/定数プール/フィールド/メソッド/属性）・`:nimo`コマンドによるニーモニック（javap -c風）バイトコード逆アセンブル表示 | ✅ 完了（60/60テスト・`dev.javatexteditor.classfile`パッケージ新設・`readFileContentForBuffer`にマジックナンバー判定を追加・`:nimo`は`outputErrorLinesOwner`と同じ参照一致による自動失効パターン。`:b`コマンド（Mode.BINARY）とは別物の読み取り専用プレビューとしてマージ済み） |
| ㉚ | `markdown-viewer` | `.md`/`.markdown`ファイルを開いた際は生ソースを表示し、`:view`コマンドで見出し下線・リスト正規化・インライン記法除去等を行う読み取り専用の閲覧ビューへ切り替え、`:mark`でソースへ戻す | ✅ 完了（98/98テスト・`dev.javatexteditor.markdown`パッケージ新設・`MarkdownRenderer`はSwing非依存の純粋ロジックで出力はASCII印字可能文字のみに限定・`classFileBufferOwner`と同じ参照一致による自動失効パターン・`currentFilePath`をnullにする読み取り専用プレビュー方式（jdk-source方式は不採用、理由はSKILL.md参照）） |
| ㉛ | `image-preview` | 画像ファイル（png/jpg/jpeg/gif/bmp）を開いた際の全画面プレビュー（アスペクト比維持の自動フィット・`+`/`-`/`0`による手動ズーム・`hjkl`/矢印キーによるパン）、`SwingWorker`による非同期読み込み、読み込み失敗時はMode.BINARYへ自動フォールバック | ✅ 完了（38/38テスト・`Main.isImageFile()`（拡張子+`ImageIO.read()`二段判定）・`ui.ImageRenderer`（Swing非依存の拡縮計算+描画委譲）・新規`Mode.IMAGE`（`Mode.BINARY`と同じ独立モード方式、`KeymapRegistry`不使用）・`imageModeOwner`は既存の参照一致による自動失効パターンを踏襲・パンは既存の`scrollRow`/`scrollCol`をそのまま流用しJScrollPane/CardLayoutは不採用） |

| ㉜ | `intellij-style-completion` | Javaバッファの入力補完をIntelliJ IDEA方式にする（`obj.`のメンバー補完＋ハイブリッド型解決・候補のシグネチャ/型表示・CamelCase/単語境界マッチ・一致品質→近接度→使用頻度の並べ替え・メソッド確定時の`()`挿入・クラス確定時のimport挿入） | ✅ 完了（新規64テスト・キーバインドは一切変更なし＝Ctrl+Space/自動表示/Tab・Enterのまま・Alt+/のVim式近接順は不変・PLAIN位置でjavacを使わない判断は費用対効果によるスコープ外） |

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

## 詳細な設計判断の経緯

機能追加・不具合修正1件ごとの詳細な経緯（議論の背景・却下した代替案・実機検証結果など）は
`docs/decision-log.md`に時系列で集約している（2026-07-28、本ファイルの肥大化を防ぐため分離）。
実装前に確認すべき第一の参照先は引き続き`.claude/skills/`配下の関連SKILL.mdであり、
それでも「なぜこの実装になっているか」が分からない場合にのみ`docs/decision-log.md`内の
該当節（見出しはキーワード検索可能）を参照すること。新しい設計判断を記録する際も、
対応する`.claude/skills/`のSKILL.mdを優先し、無ければ`docs/decision-log.md`へ追記する
（下記「作業時の方針」参照）。

`docs/decision-log.md`に記録されている節の一覧（見出しのみ。詳細は同ファイル参照）:

- FILERモードの設計決定事項
- チュートリアルモード（㉒ editor-tutorial）の設計決定事項
- 作業ディレクトリ・`:pwd`/`:cd`の設計決定事項
- `:main <target>` コマンド（java/javac の実際の起動点へのジャンプ）の設計決定事項
- 単語補完（Alt+/）の設計決定事項
- `dev.javatexteditor.completion2` パッケージ（未接続の独立コンポーネント）
- Shift+K フリーズ修正（`ProjectSearcher` の巨大ファイル上限）
- F10/F11/F12（プロジェクト全体のコンパイル・実行）の設計決定事項
- F10/F11/F12（`*compile*`/`*run*`疑似バッファ）でEscを押すと表示前の元バッファに戻る
- `:pr`コマンド（F10/F11/F12用プロジェクトルートの固定）の設計決定事項
- `SystemStatsMonitor`（ステータス行のCPU/GPU表示）の設計決定事項
- 検索・補完機能の大文字小文字区別に関する設計決定事項
- Ctrl+Alt+矢印によるペインリサイズの設計決定事項
- Ctrl+U/Ctrl+P のバッファ切替（:bnext/:bprev 方式への統一）
- `:wa` / `:qa` / `:qa!`（Vim互換の全保存・全終了コマンド）の設計決定事項
- 自動 import 挿入（⑯ auto-import-handler）の並び順を Eclipse 互換に修正
- `Main.isJavaBuffer()` の判定基準変更（ファイルパス未設定時はデフォルトでJavaバッファ扱いしない）
- 自動 import 挿入がプロジェクト内の別パッケージのクラスに対して働かない不具合の修正
- auto-import選択ポップアップの無限再発とimport挿入位置がpackage文より前になる不具合の修正（2026-07-25）
- `currentFilePath` の絶対パス統一と新規ファイル作成時の不具合修正
- Shift+Enter が INSERT モードで何も入力できない不具合の修正
- NORMALモード `r`（1文字置換）コマンドの実装
- システムクリップボード連携（Ctrl+Shift+C / Ctrl+Shift+V）の実装
- F10/F11/F12（`*compile*`/`*run*`疑似バッファ）のリアルタイムログ表示・標準エラー赤字化
- F10/F11（`*compile*`/`*run*`疑似バッファ）をSPC+bからいつでも再度開けるようにした
- 任意のファイル種別を開けるようにする対応（バイナリファイルの読み取り専用hexdumpプレビュー）
- `:b`コマンド（Mode.BINARY — hexdumpをその場で編集できるバイナリエディタ）
- 軽量性リファクタリング計画（2026-07-15 策定・Phase 1〜3）
- Shift+K 定義ジャンプの Eclipse JDT 流バインディング解決化（完全非同期・2026-07）
- `:split`/`:vsplit`で同一ファイルを複数ペインに開いた際のリアルタイム同期（Vim方式の共有バッファ）
- getter/setter生成の `\a` プレフィックス追加、Ctrl+Shift+O の @Override 挿入への差し替え
- TERMINALモード（`Ctrl+Shift+T` / `:term`）の全機能削除（2026-07-21）
- C言語開発支援（Java機能のC言語対応・2026-07-23）
- C言語の Shift+K 定義ジャンプ（2026-07-24）
- Windows でも Shift+K が標準ライブラリへジャンプできるようにする修正（2026-07-25）
- gcc の診断メッセージがロケール翻訳される環境で標準ヘッダ検出が機能しない不具合の修正（2026-07-25 続報）
- 日本語ロケールのWindowsで「¥」（円記号）がバックスラッシュとして認識されず標準ヘッダ検出が機能しない不具合の修正（2026-07-25 続報2）
- Cバッファの入力補完（Ctrl+Space/Alt+/）候補をプロジェクトルート配下 + includeヘッダに限定（2026-07-25）
- auto-import が JDK 内部の非公開クラス・`java.lang` を候補にしてしまう不具合の修正（2026-07-26）
- auto-import 挿入直後、波下線（診断）の表示位置が実際のエラー行とずれる不具合の修正（2026-07-26）
- Markdownビューア（`:view`/`:mark`）の新規実装（2026-07-27）
- ModalEditor 神クラス解体リファクタリング 第1弾（2026-07-27）
- ModalEditor 神クラス解体リファクタリング 第2弾（2026-07-27）— 疑似バッファ退避の統一
- ModalEditor 神クラス解体リファクタリング 第3弾（2026-07-27）— :コマンドの表化
- ModalEditor 神クラス解体リファクタリング 第4弾（2026-07-27）— processNormalKey の分割
- ModalEditor 神クラス解体リファクタリング 第5弾（2026-07-27）— 疑似モード群
- EditorCanvas リファクタリング 第6弾（2026-07-27）— 描画状態の値オブジェクト化
- EditorCanvas リファクタリング 第7弾（2026-07-27）— 再描画のまとめとガター幅の集約
- Main リファクタリング 第8弾（2026-07-27）— 純粋ロジックの切り出し
- `Main` クラス解体リファクタリング 第9弾（2026-07-27〜28、段階0〜7・全完了）
- `:e` で存在しないファイルを指定した際の新規作成確認（2026-07-28）
- `:w`/`:enew` への新規作成確認の拡張と、`:e`/`:cd` 同様の TAB 補完（2026-07-28）

## 作業時の方針

- 何かを実装・設計する前に、関連する`.claude/skills/`配下のSKILL.mdを必ず確認すること。
- 既存のSkillの内容と矛盾する実装をしようとしている場合は、実装を進める前にユーザーに確認すること。
- 新しい設計判断を行った場合、その判断と理由を該当するSKILL.md（またはこのCLAUDE.md）に書き残すこと。口頭の会話だけで終わらせない。

## プロジェクト構成の維持ルール（毎回必須・2026-07-28制定）

`CLAUDE.md`が1,774行・308KBまで肥大化し、Skillの完了記録と実体（SKILL.md）が食い違う等の
問題が実際に発生したため（経緯は`docs/decision-log.md`冒頭を参照）、再発防止のため以下を
**セッションの規模に関わらず常時・自動的に**守ること。ユーザーから明示的な依頼が無くても、
下記に該当する変更を行う際は必ず適用する。

1. **`CLAUDE.md`本体には「今のルール」だけを書く。「経緯・議論・却下案・バグ調査の詳細」は書かない。**
   - 書いてよい内容: 技術制約・コマンド・ディレクトリ構成・決定済み設計事項の要約表・ロードマップ・
     依存関係・作業方針・このルール自体、のみ。
   - 新しい機能を実装した／不具合を直した際の「なぜこの実装にしたか」という詳細な経緯は、
     対応する`.claude/skills/<name>/SKILL.md`の「設計判断ログ」節に書く。該当するSkillが
     無ければ、新規に`.claude/skills/<name>/SKILL.md`を作成するか（後述2）、無理なら
     `docs/decision-log.md`の末尾に追記する。**`CLAUDE.md`本体へ長い経緯を追記することは禁止**。
   - 目安として`CLAUDE.md`が300行を超えそうになったら、直近で追記した内容のうち
     「常時参照が必要な結論」以外を`docs/decision-log.md`または該当SKILL.mdへ移すこと。

2. **ロードマップ表に「✅完了」と書く機能には、必ず対応する`.claude/skills/<name>/SKILL.md`を
   同じ作業の中で作成する（後回しにしない）。** Skillを1つ完了させたら、その場で
   ①ロードマップ表の更新 ②`.claude/skills/<name>/SKILL.md`の作成・更新 の両方を終える。
   片方だけを行って次のタスクへ進まないこと。

3. **完了して以後は変更しないリファクタリング実行計画書・提案書の類（「〜計画書」「〜実行指示書」）は、
   完了が確定した時点で`docs/archive/`へ移動する。** `docs/`直下に置いたままにしない。移動時は
   `README.md`・`docs/manual/README.md`・`CLAUDE.md`・`docs/decision-log.md`内の参照パスも
   同時に更新すること。

4. **現状と矛盾する古いメモ・引き継ぎファイル（例: 特定時点のスナップショットで内容が更新されない
   もの）は、内容が古くなった時点で削除する。** 「念のため残す」を理由に放置しない。

5. **月1回程度の目安で（または大きな区切りの作業の後に）、次の3点をセルフチェックする**:
   - `CLAUDE.md`の行数・サイズが目に見えて増えていないか
   - ロードマップの「✅完了」件数と`.claude/skills/`のディレクトリ数が一致しているか
     （`ls .claude/skills/ | wc -l`とロードマップの✅件数を比較）
   - `docs/`直下に完了済みの計画書・古いメモが溜まっていないか
   ずれを見つけたら、ユーザーへの確認を待たずにこのルールに従って是正してよい
   （構成整理は「既存のSkillの内容と矛盾する実装」には当たらないため、上記「作業時の方針」の
   事前確認ルールの対象外とする。ただしファイルの削除・大きな移動を伴う場合は、変更内容を
   簡潔に報告すること）。

## 既知の未接続・二重定義（リファクタ調査 2026-07 時点）

次の開発者が片側だけ修正する事故を防ぐための記録。いずれも「消してよいか／どちらが正か」の仕様判断が未決定のため、判断せずに残してある（docs/archive/REFACTORING_PLAN.md の P-10〜P-13・P-21・U-7 参照）。

1. **（2026-07 解消済み）NORMAL モード Ctrl+U/P のバッファ切替が二重実装だった問題**: 以前は `ModalEditor.processNormalKey` 冒頭のハードコード（`bufferHistory` スナップショット方式）が無条件に優先され、`switchToRelativeBuffer`（`Main.BUFFER_REGISTRY` を巡回する本来の `:bnext`/`:bprev` 相当の実装）には既定キーから到達しなかった。詳細は本ファイル末尾の「Ctrl+U/Ctrl+P のバッファ切替（:bnext/:bprev 方式への統一）」節を参照。
2. **COMMAND モードの registry 束縛は機能しない**: `processCommandKey` は KeymapRegistry を参照せず ESC/Enter/TAB をハードコードで処理するため、`KeymapRegistry` の COMMAND モード束縛（`enter.normal`/`execute.command`）は現状到達不能。外部（プラグイン）からの参照想定が不明なため削除しない。
3. **（2026-08 解消済み）`WordIndex` が保存後も更新されない問題**: 以前は `WordIndex`/`CompletionIndex` ともエディタ起動時に1回だけ構築され、以後は再構築されなかった（新規作成・保存したクラス名/メソッド名が他ファイルの補完候補に出ない不具合）。`WordIndex.updateFile(Path)` によるファイル単位の差分更新を追加し、`LiveDiagnostics.install()` の `onSave` フックから呼ぶことで解決した。`CompletionIndex`（JDKクラス名のみを保持）は起動時1回のままで変更していない（JDKクラス構成は実行中に増減しないため）。詳細は `.claude/skills/intellij-style-completion/SKILL.md` 参照。
4. **`extension/` パッケージ（PluginLoader ほか）は本番経路から未接続**: `:plugin` 等の起動コマンドが未実装のため、動的コンパイル・プラグイン機構はテストからしか呼ばれない（ロードマップ③⑥は機構としては完了、UI 接続のみ未着手）。
5. **疑似バッファ退避2系統の相互作用は未定義**: jdk-source 疑似バッファ（`saved*` フィールド群）と `*cd候補*` 疑似バッファ（`cdSaved*` フィールド群）を重ねて使った場合の挙動は未定義・未テスト。
6. **`ScrollTest` の2ケース（halfPageUp 系）は恒常的に FAIL する**: Ctrl+U の仕様変更（半ページスクロール → バッファ履歴を前へ）にテストが追従しておらず、ベースライン時点で 18/20 PASS。テストを更新するかキー割当てを戻すかは未決定（docs/archive/REFACTORING_PLAN.md U-7）。どちらの修正も仕様判断を伴うため「ついでに」直さないこと。

