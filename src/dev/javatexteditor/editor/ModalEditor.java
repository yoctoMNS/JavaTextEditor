package dev.javatexteditor.editor;

import dev.javatexteditor.analysis.AutoImportHandler;
import dev.javatexteditor.analysis.BindingDefinitionResolver;
import dev.javatexteditor.analysis.CompileDiagnostic;
import dev.javatexteditor.analysis.EntryPointIndex;
import dev.javatexteditor.analysis.JdkClassIndex;
import dev.javatexteditor.analysis.JdkJavadocReader;
import dev.javatexteditor.analysis.JdkTypeInfo;
import dev.javatexteditor.analysis.OpenjdkSourceTracer;
import dev.javatexteditor.analysis.ProjectSymbolResolver;
import dev.javatexteditor.analysis.ReceiverTypeResolver;
import dev.javatexteditor.buffer.BinaryFileDetector;
import dev.javatexteditor.buffer.HexDumpFormatter;
import dev.javatexteditor.buffer.UndoablePieceTable;
import dev.javatexteditor.classfile.ClassFile;
import dev.javatexteditor.classfile.ClassFileFormatException;
import dev.javatexteditor.classfile.ClassFileFormatter;
import dev.javatexteditor.classfile.ClassFileParser;
import dev.javatexteditor.classfile.MnemonicFormatter;
import dev.javatexteditor.projectbuild.BuildDiagnostic;
import dev.javatexteditor.projectbuild.BuildResult;
import dev.javatexteditor.refactor.RenameRefactorer;
import dev.javatexteditor.refactor.RenameResult;
import dev.javatexteditor.search.DirEntry;
import dev.javatexteditor.search.DirectoryLister;
import dev.javatexteditor.search.FileNameSearcher;
import dev.javatexteditor.search.ProjectSearcher;
import dev.javatexteditor.search.SearchResult;
import dev.javatexteditor.telescope.BufferPicker;
import dev.javatexteditor.telescope.FilePicker;
import dev.javatexteditor.telescope.GrepPicker;
import dev.javatexteditor.telescope.MainClassPicker;
import dev.javatexteditor.telescope.TelescopeItem;
import dev.javatexteditor.telescope.TelescopePicker;
import dev.javatexteditor.tutorial.Tutorial;
import dev.javatexteditor.ui.EditorCanvas;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * NORMAL / INSERT / COMMAND / VISUAL / VISUAL_LINE の5モードを管理し、
 * カーソル位置管理と PieceTable / EditorCanvas の橋渡しを担うクラス。
 *
 * キー入力の処理は processKey(keyCode, keyChar, modifiers) で受け取る。
 * keyCode / modifiers は java.awt.event.KeyEvent の定数を使用する。
 */
public class ModalEditor {

    private enum Mode { NORMAL, INSERT, COMMAND, VISUAL, VISUAL_LINE, VISUAL_BLOCK, SEARCH, FILESEARCH, TELESCOPE, IMPORT_SELECT, FILER, CLASSPATH_INPUT, BINARY, TERMINAL }
    private enum FileSearchType { NAME, GREP }

    /** ソフトタブのインデント幅（スペース数）。 */
    private static final int TAB_WIDTH = 4;
    private static final String INDENT_UNIT = " ".repeat(TAB_WIDTH);
    /** 補完ポップアップに出す最大候補数（Ctrl+Space / Alt+/ 共通）。 */
    private static final int COMPLETION_MAX_RESULTS = 10;
    /**
     * Ctrl+Space の統合クエリで JDK クラス名（CompletionIndex）用に必ず確保する最小枠。
     * wordIndex 側の一致件数が多い識別子（"get"/"S" 等）だと単語だけで COMPLETION_MAX_RESULTS を
     * 埋め尽くしてしまい、JDK クラス名が一切候補に出てこない不具合があったため、
     * wordIndex への問い合わせ自体をこの枠の分だけ小さくして JDK クラス名の表示機会を保証する。
     */
    private static final int COMPLETION_CLASS_RESERVED_SLOTS = 3;

    private UndoablePieceTable buffer;
    private final EditorCanvas canvas; // null の場合はGUIなし（テスト用）
    private final KeymapRegistry keymap = new KeymapRegistry();
    private Mode mode = Mode.NORMAL;
    // :wrap / :nowrap（画面端での折り返し表示）。既定はnowrap相当（横スクロール）。
    // 詳細は .claude/skills/gui-rendering-pipeline/SKILL.md 参照。
    private boolean wrapEnabled = false;
    // INSERT → NORMAL 復帰時に呼ばれるコールバック（バックグラウンドコンパイル等）
    private Runnable onReturnToNormal = null;
    // ファイル保存成功時に呼ばれるコールバック（バックグラウンドコンパイル等）
    private Runnable onSave = null;
    // Ctrl+Shift+O（organize imports）時に呼ばれるコールバック（コンパイル→auto-import）
    private Runnable onOrganizeImports = null;
    // handleAutoImport で全候補処理完了後に呼ぶコールバック（未使用 import 削除等）
    private Runnable onImportComplete = null;
    // processKey() の末尾でバッファのversionが変化したときにだけ呼ばれるコールバック
    // （NORMAL/VISUALモードのdd/p/u/Ctrl+R等、onReturnToNormal/onSaveの対象外となる
    //  バッファ変更操作でも診断の再解析を追従させるため。デバウンスはMain側の責務）。
    private Runnable onBufferChanged = null;
    private long lastNotifiedBufferVersion = -1;
    // Vim方式の共有バッファ（複数ペインで同一ファイルを開いた場合、真に同じ UndoablePieceTable
    // インスタンスを参照させることでリアルタイムに編集が反映される）。
    // liveBufferLookup: ファイルを開く際、同じ絶対パスを他ペインが既に開いていればその生きた
    // バッファ参照を返す（無ければ null＝ディスクから新規読み込み）。Main.java が全ペインを
    // 横断して検索できるよう Function として注入する。
    private java.util.function.Function<String, UndoablePieceTable> liveBufferLookup = null;
    // processKey() でバッファのversionが変化した直後に呼ばれ、同じバッファを共有する他ペインの
    // 画面を再描画させるためのフック（onBufferChangedと発火タイミングは同じだが用途が異なるため
    // 独立したコールバックにした。Main.java 側で全ペインを横断して同一参照のバッファを持つ
    // 他Leafのsyncを行う）。
    private Runnable onSharedBufferSync = null;
    private int cursorRow = 0;
    private int cursorCol = 0;
    private int anchorRow = 0;
    private int anchorCol = 0;
    // ペイン分割（複数ModalEditorインスタンス）をまたいでヤンク内容を共有するため static にする。
    // Vimのレジスタはウィンドウ単位ではなくエディタプロセス単位で共有されるのと同じ意味論。
    private static String yankRegister = "";
    private enum YankType { CHAR, LINE, BLOCK }
    private static YankType yankType = YankType.CHAR;
    private enum CaseOp { UPPER, LOWER, TOGGLE }
    private String pendingSequence = ""; // yy / dd / SPC+g+g 等の多打鍵シーケンス管理
    // vim-macro-recording: q{register}記録 / @{register}再生。
    // yankRegister（ヤンクバッファ）とは独立したマクロ専用レジスタストレージ。
    private record RecordedKey(int keyCode, char keyChar, int modifiers) {}
    private boolean macroRecording = false;
    private char macroRecordingRegister;
    private final List<RecordedKey> macroRecordBuffer = new ArrayList<>();
    private final Map<Character, List<RecordedKey>> macroRegisters = new HashMap<>();
    private char lastPlayedMacroRegister = '\0'; // @@ 用
    private int macroReplayDepth = 0; // 再生中の再帰の深さ（記録の二重展開防止・無限再帰ガード）
    private static final int MACRO_MAX_REPLAY_DEPTH = 1000;
    // Visual '>'/'<' 用の count 前置き入力（例: "3>" は shiftwidth*3）。数字キー以外が来たら破棄する。
    private String visualCountBuffer = "";
    // NORMAL の r（1文字置換）専用の count 前置き入力（例: "3r"）。汎用の "3j" 等のカウント付き
    // モーションは②modal-editing-engineスキルでスコープ外のまま（visualCountBufferと同じ理由づけ）。
    private String normalCountBuffer = "";
    private int pendingReplaceCount = 1;
    private final IndentSettings indentSettings = new IndentSettings();
    // gv: 直前の Visual 選択（種別・アンカー・カーソル）を記憶する。'>'/'<' 実行後も
    // 更新済みの範囲で保存されるため、'>gv' のような再選択運用がそのまま機能する。
    private enum VisualKind { CHAR, LINE, BLOCK }
    private boolean lastVisualValid = false;
    private VisualKind lastVisualKind = VisualKind.CHAR;
    private int lastVisualAnchorRow = 0;
    private int lastVisualAnchorCol = 0;
    private int lastVisualCursorRow = 0;
    private int lastVisualCursorCol = 0;
    // VISUAL BLOCK: I/A/c による矩形挿入（INSERTモード中に1行だけ入力させ、
    // ESC/Ctrl+]で抜けるときに他の行の同じ列へ複製する）
    private boolean blockInsertActive = false;
    private boolean blockInsertPad = false; // true(A): 短い行を空白埋めして複製 / false(I,c): 短い行はスキップ
    private int blockInsertR1 = 0;
    private int blockInsertR2 = 0;
    private int blockInsertCol = 0;
    private int blockInsertStartOffset = 0;
    private boolean blockInsertAborted = false; // 複数行入力（Enter）が起きたら複製を諦める
    private final StringBuilder commandBuffer = new StringBuilder();
    private String currentFilePath = null;
    private String statusMessage = "";
    private Runnable exitCallback = () -> System.exit(0);
    private Runnable exitAllCallback = () -> System.exit(0); // :qa/:qa! 用。既定はexitCallbackと同じ全終了
    private Runnable closeBlockedCallback = null; // 最後の1ペインで :q を拒否するとき呼ぶ
    private Runnable splitHorizontalCallback = null; // sv: 左右分割
    private Runnable splitVerticalCallback   = null; // ss: 上下分割
    private Runnable movePanePrevCallback    = null; // sh/sk: 前のペインへ
    private Runnable movePaneNextCallback    = null; // sl/sj: 次のペインへ
    // :wa/:qa/:qa! が対象とする「開いている全編集対象」を返す。既定は自分自身のみ（単一ペイン相当）で、
    // 画面分割時は Main.java が全ペインの ModalEditor を返すよう差し替える（movePanePrevCallback等と同じ配線方式）。
    private Supplier<List<ModalEditor>> allEditorsSupplier = () -> List.of(this);
    // JDK API ナビゲーション用インデックス（バックグラウンドで構築）
    private JdkClassIndex jdkIndex = null;
    private final JdkJavadocReader javadocReader = new JdkJavadocReader();
    private final OpenjdkSourceTracer sourceTracer = new OpenjdkSourceTracer();
    // auto-import: 複数候補が見つかった場合に選択待ちとなる状態
    private AutoImportHandler autoImportHandler = null;
    // 選択待ち候補: 単純名 → FQN 候補リストのペアを順番通りに保持
    private final List<Map.Entry<String, List<String>>> pendingImports = new ArrayList<>();
    private int pendingImportIdx = 0; // 現在選択中の単純名インデックス
    // IMPORT_SELECT モード: モーダルオーバーレイで FQN を選択させる
    private List<String> importSelectFqns = List.of();   // 現在表示中の候補 FQN リスト
    private String importSelectSymbol = "";               // 現在対象のシンボル名
    private int importSelectIdx = 0;                      // 選択中インデックス
    // テスト用: handleAutoImportFromCandidates で設定される適用コールバック
    private java.util.function.Consumer<String> pendingImportApply = null;
    // auto-import 完了時に「N件挿入済み」を表示するための挿入カウンタ
    private int importAppliedCount = 0;
    // project-wide-search: grep 結果バッファ
    private final ProjectSearcher projectSearcher = new ProjectSearcher();
    private List<SearchResult> grepResults = null; // null = 通常バッファ
    private Path grepBaseDir = null; // grepResults の各 filePath() が相対的な起点ディレクトリ
    private final RenameRefactorer renameRefactorer = new RenameRefactorer();
    // symbol-definition-navigation: gd (go to definition) / gr (go to references)
    private final ProjectSymbolResolver projectSymbolResolver = new ProjectSymbolResolver();
    // symbol-definition-navigation: "instanceVar.member" のレシーバ型推定（軽量ヒューリスティック）
    private final ReceiverTypeResolver receiverTypeResolver = new ReceiverTypeResolver();
    // Shift+K の最優先段: Eclipse JDT 流のバインディング解決（javac 属性付け + Trees.getElement）。
    // enableBindingDefinitionLookup() が呼ばれるまで無効（＝既存ヒューリスティックのみの従来動作）。
    // Main.java が本番配線で有効化し、実行機構（仮想スレッド + SwingUtilities.invokeLater）を注入する。
    // テストは同期実行機構（Runnable::run）で有効化することで processKey 直後の同期 assert を維持できる。
    private final BindingDefinitionResolver bindingDefinitionResolver = new BindingDefinitionResolver();
    private boolean bindingLookupEnabled = false;
    private Consumer<Runnable> bindingLookupExecutor = Runnable::run;
    private Consumer<Runnable> bindingLookupUiDispatcher = Runnable::run;
    // stale 結果破棄用の世代カウンタ。非同期解析の完了前に再度 Shift+K が押された場合、
    // 古い方の結果は適用せず黙って捨てる（Eclipse がジャンプ要求をキャンセルするのと同じ発想）。
    private long bindingLookupGeneration = 0;
    // Shift+K (jdk.doc) のプロジェクト全体検索がEDTをフリーズさせないための上限時間。
    // 作業ディレクトリが巨大（既定値がホームディレクトリになりうるため）だと
    // ProjectSymbolResolver.resolve() の全文grepに時間がかかることがあるため、
    // タイムアウトした場合は諦めて JDK 側の検索にフォールバックする。
    private static final long PROJECT_SYMBOL_SEARCH_TIMEOUT_MS = 1500;
    // 診断ジャンプ用: canvas なしのテスト環境でも保持できるようにする
    private List<CompileDiagnostic> localDiagnostics = List.of();
    // ファイル名検索 / ファイル内容grep（\f / \g）
    private Path projectRoot = null; // null のとき user.dir を使用
    private final FileNameSearcher fileNameSearcher = new FileNameSearcher();
    private final StringBuilder fileSearchBuffer = new StringBuilder();
    private FileSearchType fileSearchType = FileSearchType.NAME;
    private List<String> fileNameResults = null; // null = 通常バッファ
    // F10/F11/F12: 追加クラスパス入力（CLASSPATH_INPUTモード）
    private final StringBuilder classpathInputBuffer = new StringBuilder();
    private String classpathInputLabel = "";
    private Consumer<List<Path>> classpathCallback;
    // テキスト内検索状態
    private final StringBuilder searchBuffer = new StringBuilder();
    private String  lastSearchPattern = "";
    private boolean lastSearchForward = true;
    private List<int[]> searchMatches = List.of(); // 各要素: {offset, length}
    private int currentMatchIdx = -1;
    // telescope モード状態
    private TelescopePicker telescopePicker = null;
    private final StringBuilder telescopeQuery = new StringBuilder();
    private List<TelescopeItem> telescopeResults = List.of();
    private int telescopeSelectedIdx = 0;
    /** TELESCOPE モードの Enter が「ファイルを開く」か「F11 の main クラス選択」かを切り替える。 */
    private enum TelescopePurpose { NAVIGATE, RUN_MAIN_CLASS }
    private TelescopePurpose telescopePurpose = TelescopePurpose.NAVIGATE;
    private Consumer<String> onRunMainClassSelected;
    // telescope 疑似バッファ（\f/\g と同じ表示方式）の退避状態。
    // jdk-source の saved*/*cd候補* の cdSaved* と同じ「一時退避→キャンセル時に復元」パターンを踏襲するが、
    // 各セッションは独立したフィールド群を持つ（3系統の相互作用は未定義のまま。CLAUDE.md「既知の未接続・二重定義」参照）。
    // Vim方式の共有バッファ: テキストのスナップショットではなく UndoablePieceTable の参照そのものを
    // 退避・復元する。文字列経由で復元すると（他ペインと共有していた場合でも）新規インスタンスに
    // なってしまい共有が切れるため。
    private UndoablePieceTable telescopeSavedBuffer = null;
    private String telescopeSavedFilePath = null;
    private int telescopeSavedCursorRow = 0;
    private int telescopeSavedCursorCol = 0;
    // telescope 起動時にたまたま grep/file-search 結果バッファの上にいた場合、その一覧も退避して復元する
    // （telescope が buffer を上書きするため、退避しないと Esc キャンセル後に Enter ジャンプが効かなくなる）。
    private List<SearchResult> telescopeSavedGrepResults = null;
    private Path telescopeSavedGrepBaseDir = null;
    private List<String> telescopeSavedFileNameResults = null;
    // telescope 起動時にバッファ一覧を供給するコールバック（Main.java から設定）
    private java.util.function.Supplier<List<BufferPicker.BufferEntry>> bufferListSupplier = null;
    // ファイルを開いたとき（telescope/`:e`）にバッファレジストリへ登録するコールバック
    private java.util.function.Consumer<BufferPicker.BufferEntry> onFileOpened = null;
    // BufferPicker で `d` を押してバッファを削除するコールバック
    private java.util.function.Consumer<BufferPicker.BufferEntry> onBufferDelete = null;
    // 作業ディレクトリ変更コールバック（Main.java から WorkingDirectoryManager に委譲）
    // 成功時は null, 失敗時は日本語エラーメッセージを返す。
    private java.util.function.Function<Path, String> changeWdCallback = null;
    // :cd タブ補完状態（候補が複数件のとき *cd候補* 疑似バッファを開いて選択させる。
    // jdk-source 疑似バッファ（saved*/inJdkSourceBuffer）と同じ「一時退避→復元」パターン）
    private List<String> cdCandidates = List.of(); // 候補ディレクトリ名（末尾 "/" は含まない）
    private String cdCandidateParentPart = ""; // 補完対象パスのうち末尾ディレクトリ名より前の部分（区切り文字含む）
    private boolean cdSelectionActive = false; // true の間は *cd候補* 疑似バッファを表示中
    private UndoablePieceTable cdSavedBuffer = null; // 選択中に退避した元バッファの参照（共有バッファを保つため）
    private String cdSavedFilePath = null;
    private int cdSavedCursorRow = 0;
    private int cdSavedCursorCol = 0;
    private String cdSavedCommandText = ""; // キャンセル時に COMMAND モードへ復元する入力途中の文字列
    // :e タブ補完状態（:cd と同じく一時退避→復元パターン）
    private List<String> edCandidates = List.of(); // 候補ファイル/ディレクトリ名（末尾 "/" はディレクトリのみ）
    private String edCandidateParentPart = "";
    private boolean edSelectionActive = false;
    private UndoablePieceTable edSavedBuffer = null; // 選択中に退避した元バッファの参照（共有バッファを保つため）
    private String edSavedFilePath = null;
    private int edSavedCursorRow = 0;
    private int edSavedCursorCol = 0;
    private String edSavedCommandText = "";
    // filer モード状態（\f/\g/telescope と同じ疑似バッファ表示。saved* は元バッファの一時退避）
    private List<DirEntry> filerEntries = List.of();
    private List<DirEntry> filerFiltered = List.of();
    private int filerSelectedIdx = 0;
    private boolean filerSearchMode = false;
    private final StringBuilder filerQuery = new StringBuilder();
    private UndoablePieceTable filerSavedBuffer = null; // 選択中に退避した元バッファの参照（共有バッファを保つため）
    private String filerSavedFilePath = null;
    private int filerSavedCursorRow = 0;
    private int filerSavedCursorCol = 0;
    // Mode.BINARY 状態（:b コマンドで任意のバッファと相互トグルする hexdump 編集モード）。
    // buffer 自体が「唯一の真実」（hexdumpテキストを直接1文字ずつ上書き編集する）ため、
    // 別途バイト配列をキャッシュしない。binaryByteCount のみバイト総数として保持し、
    // カーソル可動域のクランプと保存時の HexDumpFormatter.parse() 呼び出しに使う。
    // binaryModeOwner は「この buffer インスタンスが Mode.BINARY 用に作られたものか」を
    // 参照一致で判定するための目印（outputErrorLinesOwner と同じ設計。詳細はCLAUDE.md参照）。
    private UndoablePieceTable binaryModeOwner = null;
    private int binaryByteCount = 0;
    private int binaryCursorOffset = 0;
    private boolean binaryNibblePending = false; // true = 直前に高位4bitを入力済み、次の1桁で低位4bitを確定

    // -------------------------------------------------------------------------
    // Mode.TERMINAL（Ctrl+Shift+T / :term コマンド）
    // エディタプロセス全体で1つだけ生存する対話型シェルセッションのため、yankRegister と同じ理由で
    // static にする（どのペインから :term/Ctrl+Shift+T しても同じセッション・同じバッファを共有する）。
    // 実際のプロセス起動・標準入出力の読み書き（TerminalSession）はSwingに依存しないよう
    // Main.java 側に置き、ここではコールバック経由でのみやり取りする
    // （BindingDefinitionResolver の「実行機構の注入方式」と同じ設計。詳細はCLAUDE.md参照）。
    private static UndoablePieceTable terminalBuffer = null;
    private static boolean terminalAlive = false;
    private static final StringBuilder terminalPendingInput = new StringBuilder();
    private static java.util.Set<Integer> terminalErrorLines = new java.util.HashSet<>();
    private static int terminalNextRow = 0;
    private static final String PSEUDO_TERMINAL_PATH = "*terminal*";
    // ペインごとの退避状態（FILER/telescopeと同じ「一時退避→復元」パターン、インスタンスフィールド）
    private UndoablePieceTable terminalSavedBuffer = null;
    private String terminalSavedFilePath = null;
    private int terminalSavedCursorRow = 0;
    private int terminalSavedCursorCol = 0;
    private Runnable terminalStartCallback = null;
    private java.util.function.Consumer<String> terminalWriteCallback = null;
    private Runnable terminalKillCallback = null;

    // jdk-source 疑似バッファ: K キーで開いた JDK ソース表示中に保持する情報
    private UndoablePieceTable savedBuffer = null; // 元バッファの参照（共有バッファを保つため）
    private String savedFilePath = null;         // 元バッファのファイルパス（null可）
    private int savedCursorRow = 0;
    private int savedCursorCol = 0;
    private boolean inJdkSourceBuffer = false;   // true のとき q で元に戻る
    // true = 現在の jdk-source 疑似バッファは C/C++ ネイティブソース（.c/.cpp/.h やJNIスニペット）。
    // false = Java ソース（クラス本体）。K の native シンボル探索(A)を誤って
    // Javaソース閲覧中に発動させない（"gc" が "argc(" に誤マッチする等）ためのガード。
    private boolean jdkSourceIsNative = false;

    // 入力補完状態（INSERT モード内で管理）
    private dev.javatexteditor.analysis.CompletionIndex completionIndex = null;
    private boolean completionActive = false;
    private java.util.List<dev.javatexteditor.analysis.CompletionItem> completionItems = java.util.List.of();
    private int completionSelectedIdx = 0;
    private String completionPrefix = "";
    // 単語補完（Alt+/）: 作業ディレクトリ配下の全単語・クラス名・変数名等から補完する。
    // Ctrl+N は INSERT モードで Emacs 式「カーソル下移動」に割り当て済み（keymap-conflict-resolution
    // スキル参照）のため、単語補完のトリガーは Alt+/ を使う。
    private dev.javatexteditor.analysis.WordIndex wordIndex = null;
    private boolean completionIsWordMode = false; // true の間は recheckCompletion() が wordIndex を使う
    // バッファ履歴（Ctrl+U で前へ・Ctrl+P で次へ）
    private record BufferSnapshot(String text, String filePath, int row, int col) {}
    private final List<BufferSnapshot> bufferHistory = new ArrayList<>();
    private int historyIdx = -1; // -1 = 未初期化（最初の pushBuffer で初期化）
    // Shift+K で定義ジャンプする直前の位置（Shift+J で一つ前に戻るため）
    private BufferSnapshot lastJumpOrigin = null;

    public ModalEditor(String initialText) {
        this.buffer = new UndoablePieceTable(initialText);
        this.canvas = null;
        initHistory();
    }

    public ModalEditor(String initialText, EditorCanvas canvas) {
        this.buffer = new UndoablePieceTable(initialText);
        this.canvas = canvas;
        initHistory();
        syncCanvas();
    }

    public ModalEditor(String initialText, String filePath, EditorCanvas canvas) {
        this.buffer = new UndoablePieceTable(initialText);
        this.currentFilePath = filePath;
        this.canvas = canvas;
        initHistory();
        syncCanvas();
    }

    private void initHistory() {
        bufferHistory.add(new BufferSnapshot(buffer.getText(), currentFilePath, 0, 0));
        historyIdx = 0;
        lastNotifiedBufferVersion = buffer.getVersion();
    }

    public void setBufferListSupplier(java.util.function.Supplier<List<BufferPicker.BufferEntry>> supplier) {
        this.bufferListSupplier = supplier;
    }

    public void setOnFileOpened(java.util.function.Consumer<BufferPicker.BufferEntry> callback) {
        this.onFileOpened = callback;
    }

    public void setOnBufferDelete(java.util.function.Consumer<BufferPicker.BufferEntry> callback) {
        this.onBufferDelete = callback;
    }

    /** F11: telescope の main クラス選択（Enter）を、ファイルを開く代わりに実行要求として受け取るコールバック。 */
    public void setOnRunMainClassSelected(Consumer<String> callback) {
        this.onRunMainClassSelected = callback;
    }

    public void setExitCallback(Runnable callback) {
        this.exitCallback = callback;
    }

    /** :qa/:qa! で全終了するときに呼ばれるコールバックを差し替える（既定は System.exit(0)）。 */
    public void setExitAllCallback(Runnable callback) {
        this.exitAllCallback = callback;
    }

    public void setCloseBlockedCallback(Runnable callback) {
        this.closeBlockedCallback = callback;
    }

    public void setSplitHorizontalCallback(Runnable callback) {
        this.splitHorizontalCallback = callback;
    }

    public void setSplitVerticalCallback(Runnable callback) {
        this.splitVerticalCallback = callback;
    }

    public void setMovePanePrevCallback(Runnable callback) {
        this.movePanePrevCallback = callback;
    }

    public void setMovePaneNextCallback(Runnable callback) {
        this.movePaneNextCallback = callback;
    }

    /** :wa/:qa/:qa! の対象一覧を差し替える。画面分割時に Main.java から全ペイン分を渡すために使う。 */
    public void setAllEditorsSupplier(Supplier<List<ModalEditor>> supplier) {
        this.allEditorsSupplier = supplier;
    }

    /** 最後の保存以降に編集操作が行われたか（:wa/:qa 判定用）。 */
    public boolean isModified() {
        return buffer.isModified();
    }

    /**
     * INSERT モードから NORMAL モードに戻ったときに呼ばれるコールバックを登録する。
     * バックグラウンドコンパイルのトリガーとして使用する。
     */
    public void setOnReturnToNormal(Runnable callback) {
        this.onReturnToNormal = callback;
    }

    /**
     * ファイル保存（:w / :wq）が成功したときに呼ばれるコールバックを登録する。
     * バックグラウンドコンパイルのトリガーとして使用する。
     */
    public void setOnSave(Runnable callback) {
        this.onSave = callback;
    }

    /**
     * Ctrl+Shift+O（organize imports）が呼ばれたときのコールバックを登録する。
     * Main.java でコンパイル→auto-import挿入→未使用import削除の順に実行する処理を渡す。
     */
    public void setOnOrganizeImports(Runnable callback) {
        this.onOrganizeImports = callback;
    }

    /** handleAutoImport の全候補処理完了後に1回だけ呼ばれるコールバックを設定する。 */
    public void setOnImportComplete(Runnable callback) {
        this.onImportComplete = callback;
    }

    /** processKey() の結果バッファのversionが変化したときにだけ呼ばれるコールバックを設定する。 */
    public void setOnBufferChanged(Runnable callback) {
        this.onBufferChanged = callback;
    }

    /**
     * Vim方式の共有バッファ用: 同じ絶対パスを他ペインが既に開いていればその UndoablePieceTable
     * 参照を返す関数を登録する（Main.java が全ペインを横断して検索する）。未設定・該当なしの場合は
     * 従来通りディスクから新規読み込みする。
     */
    public void setLiveBufferLookup(java.util.function.Function<String, UndoablePieceTable> lookup) {
        this.liveBufferLookup = lookup;
    }

    /**
     * processKey() でバッファのversionが変化した直後に呼ばれるコールバックを設定する。
     * Main.java が同じバッファ参照を持つ他ペインの syncCanvas() を呼ぶために使う。
     */
    public void setOnSharedBufferSync(Runnable callback) {
        this.onSharedBufferSync = callback;
    }

    /** テスト・呼び出し側の再解析要否判定用。バッファ内容が変わるたびに増分する。 */
    public long getBufferVersion() { return buffer.getVersion(); }

    /** 現在開いているファイルのパスを返す（未設定の場合は null）。 */
    public String getCurrentFilePath() { return currentFilePath; }

    public void processKey(int keyCode, char keyChar, int modifiers) {
        // 最初のキー操作でスプラッシュ画面を消去する
        if (canvas != null && canvas.isShowSplash()) {
            canvas.setShowSplash(false);
        }
        if ((mode == Mode.VISUAL || mode == Mode.VISUAL_LINE || mode == Mode.VISUAL_BLOCK)
                && keyCode == KeyEvent.VK_ESCAPE) {
            saveLastVisualFromCurrentMode();
            mode = Mode.NORMAL;
            pendingSequence = "";
            syncCanvas();
            return;
        }
        // vim-macro-recording: 記録中は生キーをそのまま記録する。
        // 記録終了キー（NORMALモードでのq）自体と、マクロ再生中に内部生成されるキー
        // （macroReplayDepth>0）は記録対象から除外する（詳細はSkillのreference参照）。
        boolean isMacroStopKey = macroRecording && mode == Mode.NORMAL && keyChar == 'q';
        if (macroRecording && macroReplayDepth == 0 && !isMacroStopKey) {
            macroRecordBuffer.add(new RecordedKey(keyCode, keyChar, modifiers));
        }
        switch (mode) {
            case INSERT       -> processInsertKey(keyCode, keyChar, modifiers);
            case COMMAND      -> processCommandKey(keyCode, keyChar);
            case NORMAL       -> processNormalKey(keyCode, keyChar, modifiers);
            case VISUAL       -> processVisualKey(keyCode, keyChar, modifiers);
            case VISUAL_LINE  -> processVisualLineKey(keyCode, keyChar, modifiers);
            case VISUAL_BLOCK -> processVisualBlockKey(keyCode, keyChar, modifiers);
            case SEARCH        -> processSearchKey(keyCode, keyChar);
            case FILESEARCH    -> processFileSearchKey(keyCode, keyChar);
            case TELESCOPE     -> processTelescopeKey(keyCode, keyChar, modifiers);
            case IMPORT_SELECT -> processImportSelectKey(keyCode, keyChar, modifiers);
            case FILER         -> processFilerKey(keyCode, keyChar, modifiers);
            case CLASSPATH_INPUT -> processClasspathInputKey(keyCode, keyChar);
            case BINARY        -> processBinaryKey(keyCode, keyChar, modifiers);
            case TERMINAL       -> processTerminalKey(keyCode, keyChar, modifiers);
        }
        syncCanvas();
        long currentVersion = buffer.getVersion();
        if (currentVersion != lastNotifiedBufferVersion) {
            lastNotifiedBufferVersion = currentVersion;
            if (onBufferChanged != null) onBufferChanged.run();
            if (onSharedBufferSync != null) onSharedBufferSync.run();
        }
    }

    // -------------------------------------------------------------------------
    // NORMALモード処理
    // -------------------------------------------------------------------------

    private void processNormalKey(int keyCode, char keyChar, int modifiers) {
        boolean ctrlDown = (modifiers & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;

        // Ctrl+U: :bprev相当 / Ctrl+P: :bnext相当。
        // 現在のバッファがファイルを持つ場合は switchToRelativeBuffer()（BUFFER_REGISTRY を
        // 循環する本来の :bnext/:bprev 方式）を使う。ファイルパスを持たないバッファ（:tutor・:enew
        // 等の疑似バッファ）は BUFFER_REGISTRY の対象外のため、従来の bufferHistory スナップショット
        // 方式にフォールバックする（詳細は modal-editing-engine スキル参照）。
        if (ctrlDown && keyCode == KeyEvent.VK_U) {
            if (currentFilePath != null) {
                switchToRelativeBuffer(-1);
            } else if (historyIdx > 0) {
                restoreBuffer(historyIdx - 1);
            } else {
                statusMessage = "これ以上前のバッファはありません";
            }
            return;
        }
        if (ctrlDown && keyCode == KeyEvent.VK_P) {
            if (currentFilePath != null) {
                switchToRelativeBuffer(+1);
            } else if (historyIdx >= 0 && historyIdx < bufferHistory.size() - 1) {
                restoreBuffer(historyIdx + 1);
            } else {
                statusMessage = "これ以上次のバッファはありません";
            }
            return;
        }

        // jdk-source 疑似バッファ: q で元バッファに戻る
        if (inJdkSourceBuffer && keyChar == 'q') {
            closeJdkSourceBuffer();
            return;
        }

        // *cd候補* 疑似バッファ: Enter で選択、q でキャンセルして元バッファへ戻る
        if (cdSelectionActive && keyCode == KeyEvent.VK_ENTER) {
            applySelectedCdCandidate();
            return;
        }
        if (cdSelectionActive && keyChar == 'q') {
            cancelCdSelection();
            return;
        }

        // *e候補* 疑似バッファ: Enter で選択、q でキャンセルして元バッファへ戻る
        if (edSelectionActive && keyCode == KeyEvent.VK_ENTER) {
            applySelectedEditCandidate();
            return;
        }
        if (edSelectionActive && keyChar == 'q') {
            cancelEditSelection();
            return;
        }

        // grep 結果バッファ: Enter でその行の結果ファイルへジャンプ
        if (grepResults != null && keyCode == KeyEvent.VK_ENTER) {
            jumpToGrepResult();
            return;
        }

        // ファイル名検索結果バッファ: Enter でそのファイルを開く
        if (fileNameResults != null && keyCode == KeyEvent.VK_ENTER) {
            jumpToFileNameResult();
            return;
        }

        // マクロ記録中の q: 多打鍵シーケンス（pendingSequence）の途中状態に関わらず
        // 最優先で記録を終了する（vim-macro-recording skill の「qの優先順位」参照）。
        if (macroRecording && keyChar == 'q') {
            stopMacroRecording();
            pendingSequence = "";
            return;
        }

        // Esc: NORMALモードでは既定では何も割り当てられていないが、
        // 連続2回押すと検索ハイライトを強制的にクリアする。
        // 他の保留中シーケンス（dd/yy等）が残っていた場合はここで破棄する（Vim同様、Escは保留操作をキャンセルする）。
        if (keyCode == KeyEvent.VK_ESCAPE) {
            if (pendingSequence.equals("ESC")) {
                pendingSequence = "";
                clearSearchHighlights();
                statusMessage = "";
            } else {
                pendingSequence = "ESC";
            }
            return;
        }

        // 2打鍵シーケンス（yy / dd）の処理
        if (!pendingSequence.isEmpty()) {
            String seq = pendingSequence;
            pendingSequence = "";
            statusMessage = "";
            char prev = seq.charAt(0);
            if (prev == 'y' && matches(keyCode, keyChar, KeyEvent.VK_Y, 'y')) { yankCurrentLine(); return; }
            if (prev == 'd' && matches(keyCode, keyChar, KeyEvent.VK_D, 'd')) { deleteCurrentLine(); return; }
            // q{register}: マクロ記録開始（小文字=新規, 大文字=既存レジスタへ追記）
            if (prev == 'q') {
                if (Character.isLetter(keyChar)) {
                    startMacroRecording(keyChar);
                } else {
                    statusMessage = "無効なレジスタです";
                }
                return;
            }
            // @{register} / @@: マクロ再生
            if (prev == '@') {
                if (keyChar == '@') {
                    replayLastMacro();
                } else if (Character.isLetter(keyChar)) {
                    playMacro(Character.toLowerCase(keyChar));
                } else {
                    statusMessage = "無効なレジスタです";
                }
                return;
            }
            if (prev == 'g' && matches(keyCode, keyChar, KeyEvent.VK_G, 'g')) { moveFileStart(); return; }
            if (prev == 'g' && keyChar == 'r') { goToReferences(false); return; }
            // gR（Shift+R）: bang付き。node_modules 等のデフォルトスキップ対象も含め全ファイルを検索する
            if (prev == 'g' && keyChar == 'R') { goToReferences(true); return; }
            // gv: 直前の Visual 選択（種別・範囲）を再選択する
            if (prev == 'g' && keyChar == 'v') { restoreLastVisual(); return; }
            // gu/gU/g~: 大文字小文字変換。yy/dd と同じ doubled-letter 方式で行全体に適用する
            // （operator-pending モーションは②modal-editing-engineでスコープ外のため、3打鍵目は
            // 常に同じ文字の繰り返しのみを受け付ける）。
            // 3打鍵目の完了判定（seq.equals("gu") 等）を先に置くこと: prev は seq.charAt(0) であり
            // seq="g"/"gu" のどちらでも 'g' になるため、下の2打鍵目の遷移判定を先に置くと
            // 3打鍵目の 'u'/'U'/'~' を「2打鍵目」として誤って再度 pending 状態に戻してしまう。
            if (seq.equals("gu") && keyChar == 'u') { applyCaseToLines(cursorRow, cursorRow, CaseOp.LOWER); return; }
            if (seq.equals("gU") && keyChar == 'U') { applyCaseToLines(cursorRow, cursorRow, CaseOp.UPPER); return; }
            if (seq.equals("g~") && keyChar == '~') { applyCaseToLines(cursorRow, cursorRow, CaseOp.TOGGLE); return; }
            if (seq.equals("g") && keyChar == 'u') { pendingSequence = "gu"; statusMessage = "gu-"; return; }
            if (seq.equals("g") && keyChar == 'U') { pendingSequence = "gU"; statusMessage = "gU-"; return; }
            if (seq.equals("g") && keyChar == '~') { pendingSequence = "g~"; statusMessage = "g~-"; return; }
            if (prev == 's' && matches(keyCode, keyChar, KeyEvent.VK_V, 'v')) {
                if (splitHorizontalCallback != null) splitHorizontalCallback.run();
                return;
            }
            if (prev == 's' && matches(keyCode, keyChar, KeyEvent.VK_S, 's')) {
                if (splitVerticalCallback != null) splitVerticalCallback.run();
                return;
            }
            if (prev == 's' && (matches(keyCode, keyChar, KeyEvent.VK_H, 'h') || matches(keyCode, keyChar, KeyEvent.VK_K, 'k'))) {
                if (movePanePrevCallback != null) movePanePrevCallback.run();
                return;
            }
            if (prev == 's' && (matches(keyCode, keyChar, KeyEvent.VK_L, 'l') || matches(keyCode, keyChar, KeyEvent.VK_J, 'j'))) {
                if (movePaneNextCallback != null) movePaneNextCallback.run();
                return;
            }
            // r の2打目: キーマップ解決を経由せず、押された文字をそのまま置換文字として使う
            // （VISUAL BLOCK の r と同じパターン。Esc によるキャンセルは上のESC早期分岐が
            // pendingSequence を "ESC" で上書きすることで既に処理済みのため、ここでは扱わない）
            if (prev == 'r') {
                if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ') {
                    replaceCharAtCursor(keyChar, pendingReplaceCount);
                }
                pendingReplaceCount = 1;
                return;
            }
            // \a+?: \a の3打鍵目（getter/setter生成）。\g（grep検索）とは別プレフィックスにするため、
            // \gg/\gs/\gd ではなく \ag/\as/\ad にした（SPC g g/s/d と同じ generateGetter 等を再利用）。
            // seq.equals("\\a") の判定は下の prev == '\\' 判定より先に置く必要がある
            // （gu/gU/g~ と同じ理由: prev は seq.charAt(0) のため \a の3打鍵目でも '\\' に一致してしまう）。
            if (seq.equals("\\a")) {
                if (matches(keyCode, keyChar, KeyEvent.VK_G, 'g')) { generateGetter(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_S, 's')) { generateSetter(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_D, 'd')) { generateGetterAndSetter(); return; }
                // マッチしない場合は通常処理へ
            }
            // \f: ファイル名検索, \g: ファイル内容grep, \a: getter/setter生成プレフィックス（2打鍵目）
            if (prev == '\\') {
                if (matches(keyCode, keyChar, KeyEvent.VK_F, 'f')) { enterFileSearch(FileSearchType.NAME); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_G, 'g')) { enterFileSearch(FileSearchType.GREP); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_A, 'a')) { pendingSequence = "\\a"; statusMessage = "\\a-"; return; }
                // マッチしない場合は通常処理へ
            }
            // [g / [d: 診断ジャンプシーケンス
            if (seq.equals("[")) {
                if (matches(keyCode, keyChar, KeyEvent.VK_G, 'g')) { jumpToNextDiagnostic(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_D, 'd')) { jumpToPrevDiagnostic(); return; }
                // マッチしない場合は通常処理へ
            }
            // zz: カーソル行をviewport中央にスクロール（zt/zbは未実装のためzのみ受け付ける）
            if (prev == 'z' && matches(keyCode, keyChar, KeyEvent.VK_Z, 'z')) { centerCursorLineInViewport(); return; }
            // SPC+g+? シーケンス（SPC+g の2打鍵の後）
            if (seq.equals(" g")) {
                if (matches(keyCode, keyChar, KeyEvent.VK_G, 'g')) { generateGetter(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_S, 's')) { generateSetter(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_D, 'd')) { generateGetterAndSetter(); return; }
                // マッチしない場合は通常処理へ
            } else if (seq.equals(" i")) {
                // SPC+i+? シーケンス（import 操作）
                if (matches(keyCode, keyChar, KeyEvent.VK_O, 'o')) { organizeImports(); return; }
                // マッチしない場合は通常処理へ
            } else if (prev == ' ') {
                // SPC キー: 1打鍵目
                if (matches(keyCode, keyChar, KeyEvent.VK_H, 'h')) { moveLineStartNonBlank(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_L, 'l')) { moveLineEnd(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_K, 'k')) { moveFileStart(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_J, 'j')) { moveFileEnd(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_G, 'g')) {
                    pendingSequence = " g";
                    statusMessage = "SPC-g-";
                    return;
                }
                if (matches(keyCode, keyChar, KeyEvent.VK_I, 'i')) {
                    pendingSequence = " i";
                    statusMessage = "SPC-i-";
                    return;
                }
                // SPC+f: telescope file picker
                if (matches(keyCode, keyChar, KeyEvent.VK_F, 'f')) { enterTelescope("files"); return; }
                // SPC+/: telescope grep
                if (keyChar == '/') { enterTelescope("grep"); return; }
                // SPC+b: telescope buffers
                if (matches(keyCode, keyChar, KeyEvent.VK_B, 'b')) { enterTelescope("buffers"); return; }
            }
            // シーケンスが成立しなかった場合は落下してキーを通常処理
        }

        // r の count 前置き（例: "3r"）。Visual '>'/'<' の visualCountBuffer と同じ方式で、
        // digit以外のキーが来たら次の行で無条件に破棄される（consumeNormalCount()）。
        if (Character.isDigit(keyChar) && (keyChar != '0' || !normalCountBuffer.isEmpty())) {
            normalCountBuffer += keyChar;
            return;
        }
        int replaceCount = consumeNormalCount();

        String action = keymap.resolve(KeymapRegistry.Mode.NORMAL, keyCode, keyChar, modifiers);
        if (action == null) return;

        Runnable custom = keymap.getCustomAction(action);
        if (custom != null) { custom.run(); return; }

        switch (action) {
            case "cursor.left" -> moveCursor(0, -1);
            case "cursor.right" -> moveCursor(0, 1);
            case "cursor.down" -> moveCursor(1, 0);
            case "cursor.up" -> moveCursor(-1, 0);
            case "enter.insert" -> {
                mode = Mode.INSERT;
                statusMessage = "";
            }
            case "enter.insert.after" -> {
                String[] lines = getLines();
                int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
                cursorCol = Math.min(cursorCol + 1, lineLen);
                mode = Mode.INSERT;
                statusMessage = "";
            }
            case "enter.insert.newline" -> {
                String[] lines = getLines();
                int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
                int endOfLine = offsetAt(cursorRow, lineLen);
                buffer.insert(endOfLine, "\n");
                cursorRow++;
                cursorCol = 0;
                mode = Mode.INSERT;
                statusMessage = "";
            }
            case "enter.command" -> {
                commandBuffer.setLength(0);
                statusMessage = "";
                mode = Mode.COMMAND;
            }
            case "undo" -> {
                buffer.undo();
                clampCursorAfterUndoRedo();
            }
            case "redo" -> {
                buffer.redo();
                clampCursorAfterUndoRedo();
            }
            case "case.toggle.char" -> toggleCaseUnderCursor();
            case "replace.char.pending" -> {
                pendingReplaceCount = replaceCount;
                pendingSequence = "r";
                statusMessage = "r-";
            }
            case "enter.visual" -> {
                anchorRow = cursorRow;
                anchorCol = cursorCol;
                mode = Mode.VISUAL;
            }
            case "enter.visual.line" -> {
                anchorRow = cursorRow;
                mode = Mode.VISUAL_LINE;
            }
            case "enter.visual.block" -> {
                anchorRow = cursorRow;
                anchorCol = cursorCol;
                mode = Mode.VISUAL_BLOCK;
            }
            case "delete.char" -> deleteCharAtCursor();
            case "delete.to.eol" -> deleteToEndOfLine();
            case "paste.after" -> pasteAfter();
            case "paste.before" -> pasteBefore();
            case "clipboard.paste" -> pasteFromSystemClipboard(true);
            case "yank.pending" -> pendingSequence = "y";
            case "delete.pending" -> pendingSequence = "d";
            case "macro.record.pending" -> { pendingSequence = "q"; statusMessage = "q-"; }
            case "macro.play.pending"   -> { pendingSequence = "@"; statusMessage = "@-"; }
            case "goto.pending"   -> { pendingSequence = "g"; statusMessage = "g-"; }
            case "diag.pending"   -> { pendingSequence = "["; statusMessage = "[-"; }
            case "screen.center.pending" -> { pendingSequence = "z"; statusMessage = "z-"; }
            case "split.pending"       -> { pendingSequence = "s";  statusMessage = "s-"; }
            case "leader.pending"      -> { pendingSequence = " "; statusMessage = "SPC-"; }
            case "filesearch.pending"  -> { pendingSequence = "\\"; statusMessage = "\\-"; }
            case "line.swap.down" -> swapLineDown();
            case "line.swap.up"   -> swapLineUp();
            case "word.forward"  -> moveWordForward();
            case "word.backward" -> moveWordBackward();
            case "word.end"      -> moveWordEnd();
            case "line.start"          -> moveLineStart();
            case "line.start.nonblank" -> moveLineStartNonBlank();
            case "line.end"            -> moveLineEnd();
            case "file.start"          -> moveFileStart();
            case "file.end"            -> moveFileEnd();
            case "jdk.doc" -> lookupJdkDoc();
            case "jump.back" -> jumpBack();
            case "insert.override" -> insertOverrideStub();
            case "search.enter" -> {
                searchBuffer.setLength(0);
                mode = Mode.SEARCH;
                lastSearchForward = true;
                statusMessage = "";
            }
            case "search.next" -> jumpToNextMatch(lastSearchForward);
            case "search.prev" -> jumpToNextMatch(!lastSearchForward);
            case "search.star" -> searchWordAtCursor(true);
            case "search.hash" -> searchWordAtCursor(false);
            // ページスクロール
            case "scroll.page.down" -> scrollPage(true,  false);
            case "scroll.page.up"   -> scrollPage(false, false);
            case "scroll.half.down" -> scrollPage(true,  true);
            case "scroll.half.up"   -> scrollPage(false, true);
            case "scroll.line.down" -> scrollLines(1);
            case "scroll.line.up"   -> scrollLines(-1);
            // 画面内ジャンプ
            case "screen.top"    -> jumpToScreenRow(0);
            case "screen.middle" -> jumpToScreenRow(1);
            case "screen.bottom" -> jumpToScreenRow(2);
            case "buffer.prev"   -> switchToRelativeBuffer(-1);
            case "buffer.next"   -> switchToRelativeBuffer(+1);
            case "motion.match.pair" -> jumpToMatchingBracket();
        }
    }

    // -------------------------------------------------------------------------
    // INSERTモード処理
    // -------------------------------------------------------------------------

    private void processInsertKey(int keyCode, char keyChar, int modifiers) {
        // Ctrl+Space → 補完トリガー（completionActive 状態に関わらず再トリガー）
        if ((modifiers & KeyEvent.CTRL_DOWN_MASK) != 0 && keyCode == KeyEvent.VK_SPACE) {
            triggerCompletion();
            syncCanvas();
            return;
        }

        // Alt+/ → 単語補完トリガー（作業ディレクトリ配下の単語・クラス名・変数名・メソッド名等）。
        // Ctrl+N は INSERT モードで既に Emacs 式カーソル下移動に割り当て済みのため使わない。
        if ((modifiers & KeyEvent.ALT_DOWN_MASK) != 0 && keyCode == KeyEvent.VK_SLASH) {
            triggerWordCompletion();
            syncCanvas();
            return;
        }

        // 補完ポップアップが開いているときのナビゲーションキー処理
        if (completionActive) {
            switch (keyCode) {
                case KeyEvent.VK_DOWN -> {
                    completionSelectedIdx = Math.min(completionSelectedIdx + 1,
                                                     completionItems.size() - 1);
                    syncCompletionCanvas();
                    syncCanvas();
                    return;
                }
                case KeyEvent.VK_UP -> {
                    completionSelectedIdx = Math.max(completionSelectedIdx - 1, 0);
                    syncCompletionCanvas();
                    syncCanvas();
                    return;
                }
                case KeyEvent.VK_TAB, KeyEvent.VK_ENTER -> {
                    applyCompletion();
                    syncCanvas();
                    return;
                }
                case KeyEvent.VK_ESCAPE -> {
                    dismissCompletion();
                    syncCanvas();
                    return;
                }
            }
        }

        String action = keymap.resolve(KeymapRegistry.Mode.INSERT, keyCode, keyChar, modifiers);

        if (action != null) {
            Runnable custom = keymap.getCustomAction(action);
            if (custom != null) {
                custom.run();
            } else {
                switch (action) {
                    case "enter.normal" -> {
                        dismissCompletion();
                        finalizeBlockInsertIfActive();
                        mode = Mode.NORMAL;
                        clampCursorForNormal();
                        if (onReturnToNormal != null) onReturnToNormal.run();
                    }
                    case "delete.before" -> {
                        handleBackspace();
                        recheckCompletion();
                    }
                    case "insert.newline" -> {
                        dismissCompletion();
                        blockInsertAborted = true; // 矩形挿入は単一行入力のみ対応（複数行化したら複製を諦める）
                        insertNewlineWithIndent();
                    }
                    case "insert.tab" -> insertTab();
                    case "save.from.insert" -> {
                        dismissCompletion();
                        finalizeBlockInsertIfActive();
                        mode = Mode.NORMAL;
                        clampCursorForNormal();
                        if (onReturnToNormal != null) onReturnToNormal.run();
                        saveToFile(currentFilePath);
                    }
                    case "delete.next" -> {
                        String[] _lines = getLines();
                        int _lineLen = cursorRow < _lines.length ? _lines[cursorRow].length() : 0;
                        if (cursorCol < _lineLen) {
                            buffer.delete(offsetOfCursor(), 1);
                        }
                    }
                    case "delete.to.eol" -> {
                        String[] _lines2 = getLines();
                        String _line = cursorRow < _lines2.length ? _lines2[cursorRow] : "";
                        int _toDelete = _line.length() - cursorCol;
                        if (_toDelete > 0) {
                            buffer.delete(offsetOfCursor(), _toDelete);
                        }
                    }
                    case "delete.word.before" -> {
                        deleteWordBefore();
                        recheckCompletion();
                    }
                    case "cursor.right"  -> { dismissCompletion(); moveCursor(0, 1); }
                    case "cursor.left"   -> { dismissCompletion(); moveCursor(0, -1); }
                    case "cursor.down"   -> { dismissCompletion(); moveCursor(1, 0); }
                    case "cursor.up"     -> { dismissCompletion(); moveCursor(-1, 0); }
                    case "word.forward"  -> { dismissCompletion(); moveWordForward(); }
                    case "word.backward" -> { dismissCompletion(); moveWordBackward(); }
                    case "word.end"      -> { dismissCompletion(); moveWordEnd(); }
                    case "line.start"    -> { dismissCompletion(); moveLineStart(); }
                    case "line.end"      -> { dismissCompletion(); moveLineEnd(); }
                    case "file.start"    -> { dismissCompletion(); moveFileStart(); }
                    case "file.end"      -> { dismissCompletion(); moveFileEnd(); }
                    case "insert.override" -> { dismissCompletion(); insertOverrideStub(); }
                    case "clipboard.paste" -> pasteFromSystemClipboard(false);
                }
            }
        } else if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ') {
            if (keyChar == '}') {
                insertCloseBrace();
            } else {
                buffer.insert(offsetOfCursor(), String.valueOf(keyChar));
                cursorCol++;
            }
            // 文字挿入後: 常に補完を再クエリ（1文字から自動表示）
            recheckCompletion();
        }
    }

    // -------------------------------------------------------------------------
    // 入力補完ロジック
    // -------------------------------------------------------------------------

    /** Ctrl+Space で補完ポップアップを起動する。 */
    private void triggerCompletion() {
        boolean classReady = completionIndex != null && completionIndex.isReady();
        boolean wordReady = wordIndex != null && wordIndex.isReady();
        if (!classReady && !wordReady) {
            setStatusMessage("補完インデックス構築中...");
            return;
        }
        String prefix = extractCompletionPrefix();
        if (prefix.isEmpty()) {
            dismissCompletion();
            return;
        }
        java.util.List<dev.javatexteditor.analysis.CompletionItem> items = queryMergedCompletion(prefix);
        if (items.isEmpty()) {
            dismissCompletion();
            setStatusMessage("補完候補なし: " + prefix);
            return;
        }
        activateCompletion(prefix, items, false);
    }

    /**
     * Ctrl+Space（クラス名 + 単語補完の統合クエリ）の実体。
     * 作業ディレクトリ配下のファイル/現在バッファの単語（フィールド・メソッド・ローカル変数・
     * 定数など、WordIndex 由来）を最優先で並べ、その後に JDK クラス名（CompletionIndex、"cls"）を
     * 重複を除いて追加する。
     *
     * かつては CompletionIndex がプロジェクト全ファイルを javac AST 解析してメソッド/フィールドも
     * 収集していたが、処理が重いため廃止し（CLAUDE.md 参照）、軽量な正規表現ベースの WordIndex に
     * 一本化した。CompletionIndex は JDK クラス名のみを保持する。
     */
    private java.util.List<dev.javatexteditor.analysis.CompletionItem> queryMergedCompletion(String prefix) {
        java.util.List<dev.javatexteditor.analysis.CompletionItem> merged = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        boolean classAvailable = completionIndex != null && completionIndex.isReady();
        // JDK クラス名の枠を確保するため、wordIndex 側にはあらかじめ縮小した上限を渡す
        // （classAvailable が false の場合は満枠まで使ってよい）。
        int wordBudget = classAvailable
            ? Math.max(0, COMPLETION_MAX_RESULTS - COMPLETION_CLASS_RESERVED_SLOTS)
            : COMPLETION_MAX_RESULTS;
        if (wordIndex != null && wordIndex.isReady()) {
            for (dev.javatexteditor.analysis.CompletionItem item : queryWordCompletion(prefix, wordBudget)) {
                if (seen.add(item.label())) merged.add(item);
            }
        }
        if (classAvailable && merged.size() < COMPLETION_MAX_RESULTS) {
            for (dev.javatexteditor.analysis.CompletionItem item
                    : completionIndex.query(prefix, COMPLETION_MAX_RESULTS)) {
                if (merged.size() >= COMPLETION_MAX_RESULTS) break;
                if (seen.add(item.label())) merged.add(item);
            }
        }
        return merged;
    }

    /** Alt+/ で単語補完ポップアップを起動する（作業ディレクトリ配下の単語 + 現在バッファの単語）。 */
    private void triggerWordCompletion() {
        if (wordIndex == null || !wordIndex.isReady()) {
            setStatusMessage("単語インデックス構築中...");
            return;
        }
        String prefix = extractCompletionPrefix();
        if (prefix.isEmpty()) {
            dismissCompletion();
            return;
        }
        java.util.List<dev.javatexteditor.analysis.CompletionItem> items = queryWordCompletion(prefix);
        if (items.isEmpty()) {
            dismissCompletion();
            setStatusMessage("補完候補なし: " + prefix);
            return;
        }
        activateCompletion(prefix, items, true);
    }

    /**
     * wordIndex（作業ディレクトリ全体、バックグラウンドで正規表現ビルド済み）と
     * 現在編集中バッファ（保存前の未確定な単語も拾うため毎回その場で抽出）をマージして検索する。
     *
     * 候補の並び順は Vim の i_CTRL-N に合わせる: 現在バッファ内でカーソル位置から前方（末尾方向）
     * に最も近い出現を先頭にし、末尾まで達したら先頭へ折り返してカーソル手前までを続け
     * （{@link dev.javatexteditor.analysis.WordIndex#extractWordsByProximity}）、それでも
     * maxResults 枠が埋まらない場合だけディスク索引（辞書順）から補う。
     * カーソル位置そのものの語（今まさに入力中の未完成なプレフィックス）は
     * extractWordsByProximity 側で除外済み。
     */
    private java.util.List<dev.javatexteditor.analysis.CompletionItem> queryWordCompletion(String prefix) {
        return queryWordCompletion(prefix, COMPLETION_MAX_RESULTS);
    }

    /** maxResults を明示的に指定する版。Ctrl+Space 統合クエリが JDK クラス名の枠を確保するために使う。 */
    private java.util.List<dev.javatexteditor.analysis.CompletionItem> queryWordCompletion(String prefix, int maxResults) {
        int cursorOffset = prefixStartOffset();
        java.util.List<String> bufferWordsOrdered = dev.javatexteditor.analysis.WordIndex
            .extractWordsByProximity(buffer.getText(), cursorOffset, prefix);
        java.util.List<String> words = wordIndex.query(prefix, maxResults, bufferWordsOrdered);
        java.util.List<dev.javatexteditor.analysis.CompletionItem> items = new java.util.ArrayList<>(words.size());
        for (String w : words) {
            items.add(new dev.javatexteditor.analysis.CompletionItem(w, "wd"));
        }
        return items;
    }

    /** 入力中プレフィックス（カーソル直前の識別子）の先頭位置を、バッファ全体でのオフセットとして返す。 */
    private int prefixStartOffset() {
        String[] lines = getLines();
        if (cursorRow >= lines.length) return offsetAt(cursorRow, 0);
        String line = lines[cursorRow];
        int col = Math.min(cursorCol, line.length());
        int start = col;
        while (start > 0
               && (Character.isLetterOrDigit(line.charAt(start - 1))
                   || line.charAt(start - 1) == '_')) {
            start--;
        }
        return offsetAt(cursorRow, start);
    }

    /** 補完候補リストを有効化して canvas に反映する（4つのトリガ/再クエリ経路の共通末尾処理）。 */
    private void activateCompletion(String prefix,
            java.util.List<dev.javatexteditor.analysis.CompletionItem> items, boolean wordMode) {
        completionPrefix      = prefix;
        completionItems       = items;
        completionSelectedIdx = 0;
        completionActive      = true;
        completionIsWordMode  = wordMode;
        syncCompletionCanvas();
    }

    /** 補完ポップアップを閉じる。 */
    private void dismissCompletion() {
        if (!completionActive) return;
        completionActive = false;
        completionItems  = java.util.List.of();
        completionPrefix = "";
        completionIsWordMode = false;
        syncCompletionCanvas();
    }

    /**
     * 文字を挿入・削除した後に補完候補を再クエリする。
     * インデックス未完了・候補なし・プレフィックスなしのときはサイレントに閉じる。
     * completionActive の状態に関わらず常に呼んでよい。
     * completionIsWordMode に応じて単語補完/シンボル補完のどちらを再クエリするか切り替える。
     */
    private void recheckCompletion() {
        if (completionIsWordMode) {
            recheckWordCompletion();
            return;
        }
        boolean classReady = completionIndex != null && completionIndex.isReady();
        boolean wordReady = wordIndex != null && wordIndex.isReady();
        if (!classReady && !wordReady) return;
        String prefix = extractCompletionPrefix();
        if (prefix.isEmpty()) {
            if (completionActive) dismissCompletion();
            return;
        }
        java.util.List<dev.javatexteditor.analysis.CompletionItem> items = queryMergedCompletion(prefix);
        if (items.isEmpty()) {
            if (completionActive) dismissCompletion();
            return;
        }
        // このメソッドは冒頭の completionIsWordMode ガードにより wordMode=false の文脈でしか到達しない
        activateCompletion(prefix, items, false);
    }

    private void recheckWordCompletion() {
        if (wordIndex == null) {
            dismissCompletion();
            return;
        }
        String prefix = extractCompletionPrefix();
        if (prefix.isEmpty()) {
            dismissCompletion();
            return;
        }
        java.util.List<dev.javatexteditor.analysis.CompletionItem> items = queryWordCompletion(prefix);
        if (items.isEmpty()) {
            dismissCompletion();
            return;
        }
        // このメソッドに到達する時点で completionIsWordMode == true（recheckCompletion 経由）
        activateCompletion(prefix, items, true);
    }

    /** 現在選択中の補完候補をバッファに適用する。 */
    private void applyCompletion() {
        if (!completionActive || completionItems.isEmpty()) return;
        dev.javatexteditor.analysis.CompletionItem item =
            completionItems.get(completionSelectedIdx);
        String label = item.label();

        // カーソル前の識別子プレフィックスを削除してラベルを挿入
        String[] lines = getLines();
        String line = (cursorRow < lines.length) ? lines[cursorRow] : "";
        int col = Math.min(cursorCol, line.length());
        int start = col;
        while (start > 0
               && (Character.isLetterOrDigit(line.charAt(start - 1))
                   || line.charAt(start - 1) == '_')) {
            start--;
        }
        int prefixLen = col - start;
        int offsetStart = offsetAt(cursorRow, start);
        if (prefixLen > 0) {
            buffer.delete(offsetStart, prefixLen);
        }
        buffer.insert(offsetStart, label);
        cursorCol = start + label.length();
        dismissCompletion();
    }

    /** カーソル直前の Java 識別子プレフィックスを返す。 */
    private String extractCompletionPrefix() {
        String[] lines = getLines();
        if (cursorRow >= lines.length) return "";
        String line = lines[cursorRow];
        int col = Math.min(cursorCol, line.length());
        int start = col;
        while (start > 0
               && (Character.isLetterOrDigit(line.charAt(start - 1))
                   || line.charAt(start - 1) == '_')) {
            start--;
        }
        return line.substring(start, col);
    }

    /** 補完状態を EditorCanvas に反映する。 */
    private void syncCompletionCanvas() {
        if (canvas == null) return;
        if (!completionActive || completionItems.isEmpty()) {
            canvas.setCompletionState(false,
                java.util.List.of(), java.util.List.of(), 0, 0, 0);
            return;
        }
        java.util.List<String> labels = completionItems.stream()
            .map(dev.javatexteditor.analysis.CompletionItem::label).toList();
        java.util.List<String> kinds = completionItems.stream()
            .map(dev.javatexteditor.analysis.CompletionItem::kind).toList();
        canvas.setCompletionState(true, labels, kinds,
            completionSelectedIdx, cursorRow, cursorCol);
    }

    private static final java.util.Set<Character> CLOSING_PAIRS =
        java.util.Set.of(')', ']', '}', '"', '\'', '>');

    private void insertTab() {
        String[] lines = getLines();
        String line = cursorRow < lines.length ? lines[cursorRow] : "";
        if (cursorCol < line.length() && CLOSING_PAIRS.contains(line.charAt(cursorCol))) {
            cursorCol++;
        } else {
            buffer.insert(offsetOfCursor(), INDENT_UNIT);
            cursorCol += TAB_WIDTH;
        }
    }

    private void swapLineDown() {
        String[] lines = getLines();
        if (cursorRow >= lines.length - 1) return;
        int r = cursorRow;
        String lineA = lines[r];
        String lineB = lines[r + 1];
        // Replace lines r and r+1 atomically
        int startA = offsetAt(r, 0);
        int lenAB = lineA.length() + 1 + lineB.length();
        buffer.delete(startA, lenAB);
        buffer.insert(startA, lineB + "\n" + lineA);
        cursorRow++;
    }

    private void swapLineUp() {
        String[] lines = getLines();
        if (cursorRow == 0) return;
        int r = cursorRow;
        String lineA = lines[r - 1];
        String lineB = lines[r];
        int startA = offsetAt(r - 1, 0);
        int lenAB = lineA.length() + 1 + lineB.length();
        buffer.delete(startA, lenAB);
        buffer.insert(startA, lineB + "\n" + lineA);
        cursorRow--;
    }

    private void insertCloseBrace() {
        String[] lines = getLines();
        String currentLine = cursorRow < lines.length ? lines[cursorRow] : "";
        // 現在行がインデントのみ（空白だけ）の場合、インデントを1レベル下げてから } を挿入
        if (!currentLine.isEmpty() && currentLine.chars().allMatch(c -> c == ' ' || c == '\t')) {
            int removeLen = Math.min(TAB_WIDTH, cursorCol);
            if (removeLen > 0) {
                int lineStart = offsetAt(cursorRow, 0);
                buffer.delete(lineStart, removeLen);
                cursorCol -= removeLen;
            }
        }
        buffer.insert(offsetOfCursor(), "}");
        cursorCol++;
    }

    private void insertNewlineWithIndent() {
        String[] lines = getLines();
        String currentLine = cursorRow < lines.length ? lines[cursorRow] : "";

        // 現在行の先頭インデント（スペース・タブ）を取得
        int indentLen = 0;
        while (indentLen < currentLine.length()
                && (currentLine.charAt(indentLen) == ' ' || currentLine.charAt(indentLen) == '\t')) {
            indentLen++;
        }
        String indent = currentLine.substring(0, indentLen);

        // カーソル直前の非空白文字が '{' なら追加インデント
        String beforeCursor = currentLine.substring(0, Math.min(cursorCol, currentLine.length())).stripTrailing();
        if (!beforeCursor.isEmpty() && beforeCursor.charAt(beforeCursor.length() - 1) == '{') {
            indent += INDENT_UNIT;
        }

        buffer.insert(offsetOfCursor(), "\n" + indent);
        cursorRow++;
        cursorCol = indent.length();
    }

    /**
     * Ctrl+Shift+O: "@Override" + 改行を現在行のインデントに揃えて挿入し、
     * オーバーライドするメソッドのシグネチャだけをその場で書けるように準備する。
     * NORMAL/INSERT いずれから呼ばれても INSERT モードへ入る（続けてシグネチャを打鍵させるため）。
     * このキーには元々 organizeImports() が割り当てられていたが、ユーザー確認の上で差し替えた。
     * organizeImports() 自体は SPC+i+o / :oi / :organize-imports から引き続き呼べるため変更していない。
     */
    private void insertOverrideStub() {
        String[] lines = getLines();
        String currentLine = cursorRow < lines.length ? lines[cursorRow] : "";
        int indentLen = 0;
        while (indentLen < currentLine.length()
                && (currentLine.charAt(indentLen) == ' ' || currentLine.charAt(indentLen) == '\t')) {
            indentLen++;
        }
        String indent = currentLine.substring(0, indentLen);
        buffer.insert(offsetOfCursor(), "@Override\n" + indent);
        cursorRow++;
        cursorCol = indent.length();
        mode = Mode.INSERT;
        statusMessage = "";
    }

    private void handleBackspace() {
        if (cursorCol > 0) {
            buffer.delete(offsetOfCursor() - 1, 1);
            cursorCol--;
        } else if (cursorRow > 0) {
            String[] linesBefore = getLines();
            int prevLineLen = linesBefore[cursorRow - 1].length();
            buffer.delete(offsetOfCursor() - 1, 1);
            cursorRow--;
            cursorCol = prevLineLen;
        }
    }

    /**
     * Ctrl+W: カーソル直前の1単語を削除する（行頭をまたがない）。
     * Vim の動作に準拠: まず空白をスキップし、次に単語文字（英数字・_）または
     * 非単語文字（記号等）のまとまりをまとめて削除する。
     */
    private void deleteWordBefore() {
        if (cursorCol == 0) return; // 行頭では何もしない
        String[] lines = getLines();
        String line = cursorRow < lines.length ? lines[cursorRow] : "";
        int pos = cursorCol; // 削除の起点（exclusive）

        // 1. 直前の空白をスキップ
        while (pos > 0 && Character.isWhitespace(line.charAt(pos - 1))) {
            pos--;
        }
        if (pos == 0) {
            // 空白だけだった場合: 空白をまとめて削除
            int count = cursorCol - pos;
            buffer.delete(offsetOfCursor() - count, count);
            cursorCol = pos;
            return;
        }

        // 2. 直前の文字種（単語文字 or 非単語文字）に応じてまとまりを削除
        boolean prevIsWord = isWordChar(line.charAt(pos - 1));
        while (pos > 0 && isWordChar(line.charAt(pos - 1)) == prevIsWord
                && !Character.isWhitespace(line.charAt(pos - 1))) {
            pos--;
        }

        int count = cursorCol - pos;
        buffer.delete(offsetOfCursor() - count, count);
        cursorCol = pos;
    }

    // -------------------------------------------------------------------------
    // COMMMANDモード処理
    // -------------------------------------------------------------------------

    /**
     * COMMAND モードでコマンド実行後に戻るべきモードを判定する。
     * buffer が Mode.BINARY 用に作られたインスタンス（binaryModeOwner）と参照一致するなら
     * :w 等の他コマンド実行後もそのまま BINARY へ戻す（さもないと hexdump の固定レイアウトの
     * 上に通常の NORMAL 編集キーが効いてしまい構造が壊れる）。:b 自身が呼ばれた場合は
     * toggleBinaryMode() が mode を明示的に変更済みのため、この判定は素通りする
     * （呼び出し元は mode==COMMAND のときのみこの戻り値を使うガードになっている）。
     */
    private Mode modeAfterCommand() {
        return (binaryModeOwner == buffer) ? Mode.BINARY : Mode.NORMAL;
    }

    private void processCommandKey(int keyCode, char keyChar) {
        if (keyCode == KeyEvent.VK_ESCAPE) {
            commandBuffer.setLength(0);
            mode = Mode.NORMAL;

        } else if (keyCode == KeyEvent.VK_BACK_SPACE) {
            if (commandBuffer.length() > 0) {
                commandBuffer.deleteCharAt(commandBuffer.length() - 1);
            }

        } else if (keyCode == KeyEvent.VK_ENTER) {
            executeCommand(commandBuffer.toString());
            commandBuffer.setLength(0);
            if (mode == Mode.COMMAND) mode = modeAfterCommand();

        } else if (keyCode == KeyEvent.VK_TAB) {
            String cmd = commandBuffer.toString();
            if (cmd.equals("cd") || cmd.startsWith("cd ")) {
                handleCdTabCompletion();
            } else if (cmd.equals("e") || cmd.startsWith("e ")) {
                handleEditTabCompletion();
            }

        } else if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ') {
            commandBuffer.append(keyChar);
        }
    }

    // -------------------------------------------------------------------------
    // :cd タブ補完（COMMAND モードで "cd " 入力中に TAB を押すと候補を補完する）
    // -------------------------------------------------------------------------

    /**
     * commandBuffer が "cd" / "cd " で始まる場合のみ有効。
     * 候補0件 → 何もしない、1件 → その場で補完、複数件 → *cd候補* 疑似バッファで選択させる。
     */
    private void handleCdTabCompletion() {
        String cmd = commandBuffer.toString();
        String pathStr;
        if (cmd.equals("cd") || cmd.startsWith("cd ")) {
            pathStr = cmd.length() > 2 ? cmd.substring(2).stripLeading() : "";
        } else {
            return; // cd 以外のコマンドでは補完しない
        }

        String expanded = expandHome(pathStr);
        int sepIdx = Math.max(expanded.lastIndexOf('/'), expanded.lastIndexOf('\\'));
        String parentPart = sepIdx >= 0 ? expanded.substring(0, sepIdx + 1) : "";
        String prefix = sepIdx >= 0 ? expanded.substring(sepIdx + 1) : expanded;

        Path parentDir;
        try {
            parentDir = parentPart.isEmpty()
                ? getProjectRoot()
                : getProjectRoot().resolve(parentPart).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return;
        }

        List<String> candidates;
        try {
            String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
            candidates = new ArrayList<>();
            for (DirEntry e : DirectoryLister.listDirectoryEntries(parentDir)) {
                if (e.kind() == DirEntry.Kind.DIRECTORY
                        && e.name().toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                    candidates.add(e.name());
                }
            }
        } catch (IOException ex) {
            statusMessage = "E: " + ex.getMessage();
            return;
        }

        if (candidates.isEmpty()) {
            return;
        }
        if (candidates.size() == 1) {
            applyCdCandidate(parentPart, candidates.get(0));
            return;
        }
        openCdCandidateBuffer(cmd, parentPart, candidates);
    }

    /** commandBuffer を "cd " + parentPart + name + "/" に置き換える（続けて補完できるよう末尾に区切り文字を付与）。 */
    private void applyCdCandidate(String parentPart, String name) {
        commandBuffer.setLength(0);
        commandBuffer.append("cd ").append(parentPart).append(name).append("/");
    }

    /** バッファを差し替える際に、旧バッファ由来の検索・結果リスト状態を破棄する。
     *  （grep結果 / ファイル名検索結果 / テキスト内検索マッチ）。
     *  注意: inJdkSourceBuffer / cdSelectionActive はここでは触らない（呼び出し元ごとに扱いが異なるため）。 */
    private void resetSearchAndResultState() {
        grepResults = null;
        fileNameResults = null;
        // searchMatches/currentMatchIdx のクリアだけでなく、EditorCanvas側に描画済みの
        // ハイライト矩形（旧バッファの行・列基準）も消さないと、バッファ切替後も前バッファの
        // ハイライトが画面に残り続けるバグになる（clearSearchHighlights()に一本化して防止）。
        clearSearchHighlights();
    }

    /**
     * 複数候補が見つかった場合、telescope オーバーレイではなく既存の
     * *grep* や jdk-source と同様の「疑似バッファ」としてエディタ画面上に候補一覧を表示する。
     * 現在編集中のバッファは cdSaved* に退避し、Enter で選択 / q でキャンセルすると復元する。
     */
    private void openCdCandidateBuffer(String originalCmd, String parentPart, List<String> candidates) {
        cdSavedBuffer = buffer;
        cdSavedFilePath = currentFilePath;
        cdSavedCursorRow = cursorRow;
        cdSavedCursorCol = cursorCol;
        cdSavedCommandText = originalCmd;
        cdCandidates = candidates;
        cdCandidateParentPart = parentPart;
        cdSelectionActive = true;

        StringBuilder sb = new StringBuilder();
        sb.append("*cd候補* ").append(parentPart.isEmpty() ? "." : parentPart)
          .append(" — ").append(candidates.size()).append("件\n");
        for (String name : candidates) {
            sb.append(name).append("/\n");
        }
        buffer = new UndoablePieceTable(sb.toString());
        currentFilePath = null;
        cursorRow = 1;
        cursorCol = 0;
        resetSearchAndResultState();
        commandBuffer.setLength(0);
        mode = Mode.NORMAL;
        statusMessage = "cd候補: " + candidates.size() + "件 — Enter で選択、q でキャンセル";
    }

    /** cd候補疑似バッファ内でカーソルがある行の候補を選択し、元のバッファへ戻って :cd 入力を継続する。 */
    private void applySelectedCdCandidate() {
        int idx = cursorRow - 1; // 行0はヘッダ
        if (idx < 0 || idx >= cdCandidates.size()) {
            statusMessage = "E: no candidate at this line";
            return;
        }
        String name = cdCandidates.get(idx);
        String parentPart = cdCandidateParentPart;
        restoreCdSavedBuffer();
        mode = Mode.COMMAND;
        applyCdCandidate(parentPart, name);
        statusMessage = "";
    }

    /** cd候補疑似バッファをキャンセルし、元のバッファと入力中のコマンド文字列を復元する。 */
    private void cancelCdSelection() {
        String savedCmd = cdSavedCommandText;
        restoreCdSavedBuffer();
        mode = Mode.COMMAND;
        commandBuffer.setLength(0);
        commandBuffer.append(savedCmd);
        statusMessage = "";
    }

    private void restoreCdSavedBuffer() {
        buffer = cdSavedBuffer != null ? cdSavedBuffer : new UndoablePieceTable("");
        currentFilePath = cdSavedFilePath;
        cursorRow = cdSavedCursorRow;
        cursorCol = cdSavedCursorCol;
        resetSearchAndResultState();
        cdSelectionActive = false;
        cdSavedBuffer = null;
        cdSavedFilePath = null;
        cdSavedCommandText = "";
    }

    // -------------------------------------------------------------------------
    // :e タブ補完（COMMAND モードで "e " 入力中に TAB を押すと候補を補完する）
    // -------------------------------------------------------------------------

    /**
     * commandBuffer が "e" / "e " で始まる場合のみ有効。
     * ファイルとディレクトリの両方を候補に含める（ディレクトリは末尾に "/" を付ける）。
     * 候補0件 → 何もしない、1件 → その場で補完、複数件 → *e候補* 疑似バッファで選択させる。
     */
    private void handleEditTabCompletion() {
        String cmd = commandBuffer.toString();
        String pathStr;
        if (cmd.equals("e") || cmd.startsWith("e ")) {
            pathStr = cmd.length() > 1 ? cmd.substring(1).stripLeading() : "";
        } else {
            return;
        }

        String expanded = expandHome(pathStr);
        int sepIdx = Math.max(expanded.lastIndexOf('/'), expanded.lastIndexOf('\\'));
        String parentPart = sepIdx >= 0 ? expanded.substring(0, sepIdx + 1) : "";
        String prefix = sepIdx >= 0 ? expanded.substring(sepIdx + 1) : expanded;

        Path parentDir;
        try {
            parentDir = parentPart.isEmpty()
                ? getProjectRoot()
                : getProjectRoot().resolve(parentPart).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return;
        }

        List<String> candidates;
        try {
            String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
            candidates = new ArrayList<>();
            for (DirEntry e : DirectoryLister.listDirectoryEntries(parentDir)) {
                String name = e.name();
                if (name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                    if (e.kind() == DirEntry.Kind.DIRECTORY) {
                        candidates.add(name + "/");
                    } else {
                        candidates.add(name);
                    }
                }
            }
        } catch (IOException ex) {
            statusMessage = "E: " + ex.getMessage();
            return;
        }

        if (candidates.isEmpty()) {
            return;
        }
        if (candidates.size() == 1) {
            applyEditCandidate(parentPart, candidates.get(0));
            return;
        }
        openEditCandidateBuffer(cmd, parentPart, candidates);
    }

    private void applyEditCandidate(String parentPart, String name) {
        commandBuffer.setLength(0);
        commandBuffer.append("e ").append(parentPart).append(name);
    }

    private void openEditCandidateBuffer(String originalCmd, String parentPart, List<String> candidates) {
        edSavedBuffer = buffer;
        edSavedFilePath = currentFilePath;
        edSavedCursorRow = cursorRow;
        edSavedCursorCol = cursorCol;
        edSavedCommandText = originalCmd;
        edCandidates = candidates;
        edCandidateParentPart = parentPart;
        edSelectionActive = true;

        StringBuilder sb = new StringBuilder();
        sb.append("*e候補* ").append(parentPart.isEmpty() ? "." : parentPart)
          .append(" — ").append(candidates.size()).append("件\n");
        for (String name : candidates) {
            sb.append(name).append("\n");
        }
        buffer = new UndoablePieceTable(sb.toString());
        currentFilePath = null;
        cursorRow = 1;
        cursorCol = 0;
        resetSearchAndResultState();
        commandBuffer.setLength(0);
        mode = Mode.NORMAL;
        statusMessage = "e候補: " + candidates.size() + "件 — Enter で選択、q でキャンセル";
    }

    private void applySelectedEditCandidate() {
        int idx = cursorRow - 1;
        if (idx < 0 || idx >= edCandidates.size()) {
            statusMessage = "E: no candidate at this line";
            return;
        }
        String name = edCandidates.get(idx);
        String parentPart = edCandidateParentPart;
        restoreEditSavedBuffer();
        String fullPath = parentPart + name;

        if (name.endsWith("/")) {
            mode = Mode.COMMAND;
            applyEditCandidate(parentPart, name);
            statusMessage = "";
        } else {
            mode = Mode.COMMAND;
            commandBuffer.setLength(0);
            commandBuffer.append("e ").append(fullPath);
            executeCommand(commandBuffer.toString());
            commandBuffer.setLength(0);
            if (mode == Mode.COMMAND) mode = modeAfterCommand();
        }
    }

    private void cancelEditSelection() {
        String savedCmd = edSavedCommandText;
        restoreEditSavedBuffer();
        mode = Mode.COMMAND;
        commandBuffer.setLength(0);
        commandBuffer.append(savedCmd);
        statusMessage = "";
    }

    private void restoreEditSavedBuffer() {
        buffer = edSavedBuffer != null ? edSavedBuffer : new UndoablePieceTable("");
        currentFilePath = edSavedFilePath;
        cursorRow = edSavedCursorRow;
        cursorCol = edSavedCursorCol;
        resetSearchAndResultState();
        edSelectionActive = false;
        edSavedBuffer = null;
        edSavedFilePath = null;
        edSavedCommandText = "";
    }

    // -------------------------------------------------------------------------
    // SEARCHモード処理
    // -------------------------------------------------------------------------

    private void processSearchKey(int keyCode, char keyChar) {
        if (keyCode == KeyEvent.VK_ESCAPE) {
            searchBuffer.setLength(0);
            mode = Mode.NORMAL;
            clearSearchHighlights();
        } else if (keyCode == KeyEvent.VK_BACK_SPACE) {
            if (searchBuffer.length() > 0) {
                searchBuffer.deleteCharAt(searchBuffer.length() - 1);
            }
        } else if (keyCode == KeyEvent.VK_ENTER) {
            String pattern = searchBuffer.toString();
            mode = Mode.NORMAL;
            if (!pattern.isEmpty()) {
                lastSearchPattern = pattern;
                lastSearchForward = true;
                executeSearch(pattern, true);
            }
        } else if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ') {
            searchBuffer.append(keyChar);
        }
    }

    // -------------------------------------------------------------------------
    // FILESEARCHモード処理（\f = ファイル名検索, \g = ファイル内容grep）
    // -------------------------------------------------------------------------

    private void enterFileSearch(FileSearchType type) {
        fileSearchType = type;
        fileSearchBuffer.setLength(0);
        mode = Mode.FILESEARCH;
        statusMessage = "";
    }

    private void processFileSearchKey(int keyCode, char keyChar) {
        if (keyCode == KeyEvent.VK_ESCAPE) {
            fileSearchBuffer.setLength(0);
            mode = Mode.NORMAL;
        } else if (keyCode == KeyEvent.VK_BACK_SPACE) {
            if (fileSearchBuffer.length() > 0) {
                fileSearchBuffer.deleteCharAt(fileSearchBuffer.length() - 1);
            }
        } else if (keyCode == KeyEvent.VK_ENTER) {
            String input = fileSearchBuffer.toString();
            mode = Mode.NORMAL;
            if (!input.isEmpty()) {
                // 先頭が '!' なら bang 指定（\f! / \g!）: デフォルトスキップ対象を無視して全ファイル検索
                boolean fullScan = input.startsWith("!");
                String pattern = fullScan ? input.substring(1) : input;
                if (!pattern.isEmpty()) {
                    if (fileSearchType == FileSearchType.NAME) {
                        executeFileNameSearch(pattern, fullScan);
                    } else {
                        executeGrep(pattern, getProjectRoot(), fullScan);
                    }
                }
            }
        } else if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ') {
            fileSearchBuffer.append(keyChar);
        }
    }

    // -------------------------------------------------------------------------
    // CLASSPATH_INPUT モード処理（F10/F11/F12: 実行/コンパイル前の追加クラスパス入力）
    // -------------------------------------------------------------------------

    /**
     * F10/F11/F12 押下時に呼ぶ。追加クラスパス（プロジェクト配下の複数ディレクトリ、カンマ区切り）を
     * 入力させる。Enterで確定（0件でもよい）・Escでキャンセル（＝追加クラスパスなしとして即続行）。
     * いずれの場合も callback は必ず1回呼ばれ、コンパイル/実行そのものは中断しない
     * （Escは「クラスパス追加をスキップする」であり「コンパイル/実行そのものをキャンセルする」ではない）。
     */
    public void enterClasspathInput(String label, Consumer<List<Path>> callback) {
        classpathInputLabel = label;
        classpathCallback = callback;
        classpathInputBuffer.setLength(0);
        mode = Mode.CLASSPATH_INPUT;
        statusMessage = "";
        // F10/F11/F12はMain.javaのグローバルキーディスパッチャから直接呼ばれ、processKey()経由の
        // syncCanvas()呼び出しを通らない。ここで呼ばないとキー入力が来るまでステータス行の
        // プロンプト文言が描画されない（実際に報告されたバグ）。
        syncCanvas();
    }

    private void processClasspathInputKey(int keyCode, char keyChar) {
        if (keyCode == KeyEvent.VK_ESCAPE) {
            finishClasspathInput(List.of());
        } else if (keyCode == KeyEvent.VK_BACK_SPACE) {
            if (classpathInputBuffer.length() > 0) {
                classpathInputBuffer.deleteCharAt(classpathInputBuffer.length() - 1);
            }
        } else if (keyCode == KeyEvent.VK_ENTER) {
            finishClasspathInput(parseClasspathInput(classpathInputBuffer.toString()));
        } else if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ') {
            classpathInputBuffer.append(keyChar);
        }
    }

    private void finishClasspathInput(List<Path> extraClasspath) {
        mode = Mode.NORMAL;
        classpathInputBuffer.setLength(0);
        Consumer<List<Path>> cb = classpathCallback;
        classpathCallback = null;
        if (cb != null) cb.accept(extraClasspath);
    }

    /** カンマ区切りの入力を projectRoot 基準の絶対パスへ解決する（空要素は無視）。 */
    private List<Path> parseClasspathInput(String input) {
        List<Path> result = new ArrayList<>();
        for (String raw : input.split(",")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            result.add(Path.of(resolveRelativeToProjectRoot(trimmed)));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // TELESCOPE モード処理（SPC+f / SPC+/ / SPC+b）
    // \f（*file-search*）/ \g（*grep*）と同じ「疑似バッファに結果一覧を表示し、Enterでジャンプ」
    // 方式で表示する（旧: EditorCanvas の3ペインオーバーレイ。あいまい検索・スコアリング自体は維持）。
    // -------------------------------------------------------------------------

    private void enterTelescope(String pickerType) {
        Path baseDir = getProjectRoot();
        switch (pickerType) {
            case "files"   -> telescopePicker = new FilePicker(baseDir);
            case "grep"    -> telescopePicker = new GrepPicker(baseDir);
            case "buffers" -> {
                List<BufferPicker.BufferEntry> entries = new ArrayList<>(
                    (bufferListSupplier != null) ? bufferListSupplier.get() : List.of());
                // F10/F11/F12 の *compile*/*run* 疑似バッファは currentFilePath == null
                // （ディスク上のファイルを持たない）ため BUFFER_REGISTRY 経由では管理できない。
                // 直近の内容を lastCompileBufferText/lastRunBufferText にキャッシュしておき、
                // SPC+b で常に選択肢に含める（選択時は openTelescopeSelection() でディスクではなく
                // このキャッシュから復元する）。
                if (lastCompileBufferText != null) {
                    entries.add(new BufferPicker.BufferEntry("*compile*", PSEUDO_COMPILE_PATH));
                }
                if (lastRunBufferText != null) {
                    entries.add(new BufferPicker.BufferEntry("*run*", PSEUDO_RUN_PATH));
                }
                // *terminal* も同様に currentFilePath == null のため BUFFER_REGISTRY 管理外。
                // 一度でも :term/Ctrl+Shift+T したことがあれば（terminalBuffer != null）候補に含める。
                // *compile*/*run* と異なり静的スナップショットではなく生きた共有バッファのため、
                // 選択時（openTelescopeSelection）は enterTerminal(false) で現在の内容をそのまま表示する。
                if (terminalBuffer != null) {
                    entries.add(new BufferPicker.BufferEntry("*terminal*", PSEUDO_TERMINAL_PATH));
                }
                telescopePicker = new BufferPicker(entries);
            }
            default -> { return; }
        }
        telescopePurpose = TelescopePurpose.NAVIGATE;
        beginTelescopeSession();
    }

    /** F11: mainメソッドを持つクラスが複数見つかった場合、\f/\g と同じ疑似バッファ方式で選択させる。 */
    public void enterMainClassPicker(List<String> fqcns) {
        telescopePicker = new MainClassPicker(fqcns);
        telescopePurpose = TelescopePurpose.RUN_MAIN_CLASS;
        beginTelescopeSession();
        statusMessage = "実行するmainクラスを選択してください（Enterで実行、Escでキャンセル）";
        // Main.javaのバックグラウンドスレッド完了コールバックから呼ばれ、processKey()経由の
        // syncCanvas()呼び出しを通らないため、ここで呼ばないと次のキー入力まで描画が更新されない。
        syncCanvas();
    }

    /**
     * telescope セッション（SPC+f/SPC+//SPC+b・F11）を開始する共通処理。
     * 現在のバッファ（grep/file-search結果バッファを含む）を telescopeSaved* に退避し、
     * *picker* 疑似バッファに差し替える。キャンセル時（Esc）は退避した状態にそのまま戻す。
     */
    private void beginTelescopeSession() {
        telescopeSavedBuffer = buffer;
        telescopeSavedFilePath = currentFilePath;
        telescopeSavedCursorRow = cursorRow;
        telescopeSavedCursorCol = cursorCol;
        telescopeSavedGrepResults = grepResults;
        telescopeSavedGrepBaseDir = grepBaseDir;
        telescopeSavedFileNameResults = fileNameResults;
        telescopeQuery.setLength(0);
        telescopeSelectedIdx = 0;
        telescopeResults = telescopePicker.filter("");
        mode = Mode.TELESCOPE;
        statusMessage = "";
        renderTelescopeBuffer();
    }

    /**
     * telescopeResults/telescopeSelectedIdx の現在値を \f（*file-search*）・\g（*grep*）と同じ
     * 「ヘッダ行＋結果1行ずつ」の疑似バッファ形式で buffer に描画する。
     * 選択中の候補は専用のハイライトではなく、cursorRow をその行に合わせることで示す
     * （実際のテキストカーソルがそのまま選択マーカーになる）。
     */
    private void renderTelescopeBuffer() {
        String title = (telescopePicker != null) ? telescopePicker.title() : "";
        String query = telescopeQuery.toString();
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(title).append('*');
        if (!query.isEmpty()) sb.append(' ').append(query);
        sb.append(" — ").append(telescopeResults.size()).append(" result(s)\n");
        for (TelescopeItem item : telescopeResults) {
            sb.append(item.display()).append('\n');
        }
        buffer = new UndoablePieceTable(sb.toString());
        currentFilePath = null;
        grepResults = null;
        fileNameResults = null;
        clearSearchHighlights();
        cursorRow = telescopeResults.isEmpty() ? 0 : telescopeSelectedIdx + 1;
        cursorCol = 0;
    }

    private void processTelescopeKey(int keyCode, char keyChar, int modifiers) {
        if (keyCode == KeyEvent.VK_ESCAPE) {
            exitTelescope();
            return;
        }
        if (keyCode == KeyEvent.VK_ENTER) {
            openTelescopeSelection();
            return;
        }
        if (keyCode == KeyEvent.VK_BACK_SPACE) {
            if (telescopeQuery.length() > 0) {
                telescopeQuery.deleteCharAt(telescopeQuery.length() - 1);
                refreshTelescope();
            }
            return;
        }
        // Ctrl+N / Ctrl+P、および矢印キー(↓↑)でリスト移動
        // クエリに自由入力があるため j/k は文字入力として扱う必要があり、移動キーには割り当てない。
        boolean ctrlDown = (modifiers & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;
        if ((ctrlDown && keyCode == KeyEvent.VK_N) || keyCode == KeyEvent.VK_DOWN) {
            moveTelescope(1); return;
        }
        if ((ctrlDown && keyCode == KeyEvent.VK_P) || keyCode == KeyEvent.VK_UP) {
            moveTelescope(-1); return;
        }
        // BufferPicker 中に 'd': 選択バッファをレジストリから削除
        if (keyChar == 'd' && !ctrlDown && telescopePicker instanceof BufferPicker) {
            if (!telescopeResults.isEmpty() && onBufferDelete != null) {
                TelescopeItem item = telescopeResults.get(telescopeSelectedIdx);
                onBufferDelete.accept(new BufferPicker.BufferEntry(item.display(), item.filePath()));
                // リストを再取得して表示を更新
                List<BufferPicker.BufferEntry> updated = bufferListSupplier != null
                    ? bufferListSupplier.get() : List.of();
                telescopePicker = new BufferPicker(updated);
                refreshTelescope();
            }
            return;
        }
        // 通常文字入力
        if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ' && !ctrlDown) {
            telescopeQuery.append(keyChar);
            refreshTelescope();
        }
    }

    /** 結果リスト自体は変わらないので、選択行に合わせて実カーソルを動かすだけでよい。 */
    private void moveTelescope(int delta) {
        if (telescopeResults.isEmpty()) return;
        telescopeSelectedIdx = Math.max(0, Math.min(telescopeResults.size() - 1,
            telescopeSelectedIdx + delta));
        cursorRow = telescopeSelectedIdx + 1;
        cursorCol = 0;
    }

    private void refreshTelescope() {
        if (telescopePicker == null) return;
        telescopeResults = telescopePicker.filter(telescopeQuery.toString());
        telescopeSelectedIdx = 0;
        renderTelescopeBuffer();
    }

    private void openTelescopeSelection() {
        if (telescopePicker == null || telescopeResults.isEmpty()) { exitTelescope(); return; }
        TelescopeItem item = telescopeResults.get(telescopeSelectedIdx);
        TelescopePurpose purpose = telescopePurpose;
        exitTelescope();
        if (purpose == TelescopePurpose.RUN_MAIN_CLASS) {
            if (onRunMainClassSelected != null) onRunMainClassSelected.accept(item.display());
            return;
        }
        if (PSEUDO_TERMINAL_PATH.equals(item.filePath())) {
            enterTerminal(false);
            return;
        }
        if (PSEUDO_COMPILE_PATH.equals(item.filePath()) || PSEUDO_RUN_PATH.equals(item.filePath())) {
            String text = PSEUDO_COMPILE_PATH.equals(item.filePath())
                ? lastCompileBufferText : lastRunBufferText;
            if (text == null) return;
            buffer = new UndoablePieceTable(text);
            currentFilePath = null;
            fileNameResults = null;
            grepResults = null;
            clearSearchHighlights();
            cursorRow = 0;
            cursorCol = 0;
            statusMessage = "\"" + item.filePath() + "\" reopened";
            return;
        }
        if (item.filePath() == null) return;
        try {
            Path target = Path.of(item.filePath());
            FileLoadResult result = readFileContentForBuffer(target);
            fileNameResults = null;
            grepResults = null;
            clearSearchHighlights();
            if (result.classFileBytes() != null) {
                buffer = new UndoablePieceTable(result.text());
                currentFilePath = null;
                cursorRow = 0;
                cursorCol = 0;
                statusMessage = "\"" + target.getFileName() + "\" [class, read-only preview]";
            } else if (result.binary()) {
                enterBinaryMode(result.rawBytes(), target.getFileName().toString(), target.toString());
                statusMessage = "\"" + target.getFileName() + "\" [binary] " + result.rawBytes().length + " bytes";
            } else {
                buffer = acquireBufferForOpen(target.toString(), result.text());
                currentFilePath = target.toString();
                cursorRow = Math.max(0, item.lineNumber());
                cursorCol = 0;
                statusMessage = "\"" + target.getFileName() + "\" opened";
            }
            trackClassFileBuffer(result);
            if (result.classFileBytes() == null && onFileOpened != null) {
                onFileOpened.accept(new BufferPicker.BufferEntry(target.getFileName().toString(), currentFilePath));
            }
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
        }
    }

    /**
     * Ctrl+U / Ctrl+P / :bprev / :bnext: バッファレジストリ内で delta 分移動したバッファを開く。
     * 端（先頭/末尾）に達している場合はラップアラウンドせずそのまま留まる（vimの:bnext/:bprevと同じ）。
     */
    private void switchToRelativeBuffer(int delta) {
        if (bufferListSupplier == null) return;
        List<BufferPicker.BufferEntry> entries = bufferListSupplier.get();
        if (entries.size() <= 1) {
            statusMessage = "他に開いているファイルバッファがありません";
            return;
        }
        int currentIdx = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).filePath() != null &&
                    entries.get(i).filePath().equals(currentFilePath)) {
                currentIdx = i;
                break;
            }
        }
        int nextIdx = (currentIdx == -1)
            ? (delta > 0 ? 0 : entries.size() - 1)
            : Math.max(0, Math.min(entries.size() - 1, currentIdx + delta));
        if (nextIdx == currentIdx) {
            statusMessage = delta > 0 ? "これ以上次のバッファはありません" : "これ以上前のバッファはありません";
            return;
        }
        BufferPicker.BufferEntry target = entries.get(nextIdx);
        if (target.filePath() == null) return;
        try {
            Path p = Path.of(target.filePath());
            FileLoadResult result = readFileContentForBuffer(p);
            fileNameResults = null;
            grepResults = null;
            clearSearchHighlights();
            if (result.classFileBytes() != null) {
                buffer = new UndoablePieceTable(result.text());
                currentFilePath = null;
                cursorRow = 0;
                cursorCol = 0;
                statusMessage = "\"" + p.getFileName() + "\" [class, read-only preview]";
            } else if (result.binary()) {
                enterBinaryMode(result.rawBytes(), p.getFileName().toString(), p.toString());
                statusMessage = "\"" + p.getFileName() + "\" [binary] " + result.rawBytes().length + " bytes";
            } else {
                buffer = acquireBufferForOpen(p.toString(), result.text());
                currentFilePath = p.toString();
                cursorRow = 0;
                cursorCol = 0;
                statusMessage = "\"" + p.getFileName() + "\" switched";
            }
            trackClassFileBuffer(result);
            if (result.classFileBytes() == null && onFileOpened != null) {
                onFileOpened.accept(new BufferPicker.BufferEntry(p.getFileName().toString(), currentFilePath));
            }
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
        }
    }

    /** telescope セッションを終了し、beginTelescopeSession() で退避した元バッファに戻す。 */
    private void exitTelescope() {
        mode = Mode.NORMAL;
        telescopePicker = null;
        telescopePurpose = TelescopePurpose.NAVIGATE;
        telescopeResults = List.of();
        telescopeQuery.setLength(0);
        telescopeSelectedIdx = 0;
        buffer = telescopeSavedBuffer != null ? telescopeSavedBuffer : new UndoablePieceTable("");
        currentFilePath = telescopeSavedFilePath;
        cursorRow = telescopeSavedCursorRow;
        cursorCol = telescopeSavedCursorCol;
        grepResults = telescopeSavedGrepResults;
        grepBaseDir = telescopeSavedGrepBaseDir;
        fileNameResults = telescopeSavedFileNameResults;
        telescopeSavedBuffer = null;
        telescopeSavedFilePath = null;
        telescopeSavedGrepResults = null;
        telescopeSavedGrepBaseDir = null;
        telescopeSavedFileNameResults = null;
    }

    private void executeFileNameSearch(String pattern) {
        executeFileNameSearch(pattern, false);
    }

    /**
     * \f: ファイル名パターンで baseDir 配下を検索し、結果を疑似バッファに表示する。
     * 大文字小文字を区別しない正規表現として扱う。
     *
     * @param fullScan true の場合 {@link FileNameSearcher#SKIP_DIRS}（node_modules等）を
     *                 無視して全ファイルを対象にする（\f! の「bang」指定用）。
     */
    private void executeFileNameSearch(String pattern, boolean fullScan) {
        Path baseDir = getProjectRoot();
        List<Path> results;
        try {
            results = fileNameSearcher.search(baseDir, pattern, fullScan);
        } catch (java.util.regex.PatternSyntaxException e) {
            statusMessage = "E: bad pattern: " + e.getDescription();
            return;
        }
        if (results.isEmpty()) {
            fileNameResults = List.of();
            statusMessage = "file-search: no matches for /" + pattern + "/";
            return;
        }

        String bangLabel = fullScan ? "!" : "";
        StringBuilder sb = new StringBuilder();
        sb.append("*file-search").append(bangLabel).append("* /").append(pattern).append("/ — ")
            .append(results.size()).append(" match(es)\n");
        List<String> paths = new ArrayList<>();
        for (Path p : results) {
            String rel = p.toString().replace('\\', '/');
            sb.append(rel).append("\n");
            paths.add(rel);
        }
        fileNameResults = paths;
        buffer = new UndoablePieceTable(sb.toString());
        currentFilePath = null;
        grepResults = null;
        clearSearchHighlights();
        cursorRow = 0;
        cursorCol = 0;
        statusMessage = "file-search" + bangLabel + ": " + results.size() + " match(es) — Enter to open";
    }

    /**
     * ファイル名検索結果バッファ内でカーソル行のファイルを開く。
     * cursorRow==0 はヘッダ行なのでジャンプ対象外。
     */
    private void jumpToFileNameResult() {
        if (fileNameResults == null) return;
        int resultIdx = cursorRow - 1;
        if (resultIdx < 0 || resultIdx >= fileNameResults.size()) {
            statusMessage = "E: no result at this line";
            return;
        }
        String relPath = fileNameResults.get(resultIdx);
        Path base = (projectRoot != null) ? projectRoot : Path.of(System.getProperty("user.dir"));
        Path target = base.resolve(relPath);
        try {
            FileLoadResult result = readFileContentForBuffer(target);
            fileNameResults = null;
            clearSearchHighlights();
            if (result.classFileBytes() != null) {
                buffer = new UndoablePieceTable(result.text());
                currentFilePath = null;
                cursorRow = 0;
                cursorCol = 0;
                statusMessage = "\"" + relPath + "\" [class, read-only preview]";
            } else if (result.binary()) {
                enterBinaryMode(result.rawBytes(), target.getFileName().toString(), target.toString());
                statusMessage = "\"" + relPath + "\" [binary] " + result.rawBytes().length + " bytes";
            } else {
                buffer = acquireBufferForOpen(target.toString(), result.text());
                currentFilePath = target.toString();
                cursorRow = 0;
                cursorCol = 0;
                statusMessage = "\"" + relPath + "\" opened";
            }
            trackClassFileBuffer(result);
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
        }
    }

    /**
     * パターン（正規表現）でバッファ全体を検索し、最初のマッチへジャンプする。
     * forward=true なら現在カーソルより後方の最初のマッチへ、false なら前方の最後のマッチへ。
     */
    private void executeSearch(String pattern, boolean forward) {
        Pattern p;
        try {
            p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            statusMessage = "E: bad pattern: " + e.getDescription();
            return;
        }

        String text = buffer.getText();
        Matcher m = p.matcher(text);
        List<int[]> matches = new ArrayList<>();
        while (m.find()) {
            int len = m.end() - m.start();
            matches.add(new int[]{m.start(), len > 0 ? len : 1});
        }

        if (matches.isEmpty()) {
            statusMessage = "Pattern not found: " + pattern;
            searchMatches = List.of();
            currentMatchIdx = -1;
            updateSearchHighlights();
            return;
        }

        searchMatches = matches;
        int cursorOffset = offsetOfCursor();

        if (forward) {
            currentMatchIdx = -1;
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i)[0] > cursorOffset) {
                    currentMatchIdx = i;
                    break;
                }
            }
            if (currentMatchIdx < 0) currentMatchIdx = 0; // wrap around
        } else {
            currentMatchIdx = -1;
            for (int i = matches.size() - 1; i >= 0; i--) {
                if (matches.get(i)[0] < cursorOffset) {
                    currentMatchIdx = i;
                    break;
                }
            }
            if (currentMatchIdx < 0) currentMatchIdx = matches.size() - 1; // wrap around
        }

        moveCursorToOffset(searchMatches.get(currentMatchIdx)[0]);
        statusMessage = "/" + pattern + "  [" + (currentMatchIdx + 1) + "/" + matches.size() + "]";
        updateSearchHighlights();
    }

    /** n/N: 最後の検索方向（または逆方向）で次マッチへジャンプ。 */
    private void jumpToNextMatch(boolean forward) {
        if (!lastSearchPattern.isEmpty() && searchMatches.isEmpty()) {
            executeSearch(lastSearchPattern, forward);
            return;
        }
        if (searchMatches.isEmpty()) {
            statusMessage = "E: no previous search pattern";
            return;
        }
        if (forward) {
            currentMatchIdx = (currentMatchIdx + 1) % searchMatches.size();
        } else {
            currentMatchIdx = (currentMatchIdx - 1 + searchMatches.size()) % searchMatches.size();
        }
        moveCursorToOffset(searchMatches.get(currentMatchIdx)[0]);
        statusMessage = "/" + lastSearchPattern + "  [" + (currentMatchIdx + 1) + "/" + searchMatches.size() + "]";
        updateSearchHighlights();
    }

    /** * / #: カーソル位置の単語を完全一致で検索（単語境界 \b を使用）。 */
    private void searchWordAtCursor(boolean forward) {
        String word = wordAtCursor();
        if (word.isEmpty()) {
            statusMessage = "No word at cursor";
            return;
        }
        String pattern = "\\b" + Pattern.quote(word) + "\\b";
        lastSearchPattern = pattern;
        lastSearchForward = forward;
        executeSearch(pattern, forward);
    }

    /** 検索ハイライトをクリアする。 */
    private void clearSearchHighlights() {
        searchMatches = List.of();
        currentMatchIdx = -1;
        if (canvas != null) canvas.setSearchHighlights(List.of());
    }

    /**
     * searchMatches（オフセット単位）をキャンバス向け行単位セグメント {row, startCol, endCol} に変換し渡す。
     * マルチライン・マッチは行ごとに分割する。
     */
    private void updateSearchHighlights() {
        if (canvas == null) return;
        if (searchMatches.isEmpty()) {
            canvas.setSearchHighlights(List.of());
            return;
        }
        String[] lines = getLines();
        List<int[]> highlights = new ArrayList<>();
        for (int[] match : searchMatches) {
            int start = match[0];
            int end   = start + match[1];
            int[] startRC = offsetToRowCol(start, lines);
            int[] endRC   = offsetToRowCol(end,   lines);
            int r1 = startRC[0], c1 = startRC[1];
            int r2 = endRC[0],   c2 = endRC[1];
            if (r1 == r2) {
                highlights.add(new int[]{r1, c1, c2});
            } else {
                highlights.add(new int[]{r1, c1, lines[r1].length()});
                for (int r = r1 + 1; r < r2; r++) {
                    highlights.add(new int[]{r, 0, lines[r].length()});
                }
                highlights.add(new int[]{r2, 0, c2});
            }
        }
        canvas.setSearchHighlights(highlights);
    }

    private static int[] offsetToRowCol(int offset, String[] lines) {
        int pos = 0;
        for (int i = 0; i < lines.length; i++) {
            int lineEnd = pos + lines[i].length();
            if (offset <= lineEnd) return new int[]{i, offset - pos};
            pos = lineEnd + 1;
        }
        return new int[]{lines.length - 1, lines[lines.length - 1].length()};
    }

    private void executeCommand(String cmd) {
        if (handleSubstituteCommand(cmd)) {
            // 置換コマンドとして処理済み
        } else if (cmd.equals("wa") || cmd.equals("wall")) {
            saveAll();
        } else if (cmd.equals("w")) {
            saveToFile(currentFilePath);
        } else if (cmd.startsWith("w ")) {
            String path = cmd.substring(2).trim();
            saveToFile(path); // 成功時、絶対パスへの currentFilePath 更新は saveToFile 内で行う
        } else if (cmd.equals("e") || cmd.equals("enew")) {
            newBuffer();
        } else if (cmd.startsWith("e ")) {
            String path = cmd.substring(2).trim();
            loadFromFile(resolveRelativeToProjectRoot(path));
        } else if (cmd.equals("b")) {
            toggleBinaryMode();
        } else if (cmd.equals("term") || cmd.equals("terminal")) {
            executeTermCommand();
        } else if (cmd.equals("tutor") || cmd.equals("Tutor") || cmd.equals("tutorial")) {
            openTutorial();
        } else if (cmd.equals("nimo")) {
            showClassFileMnemonic();
        } else if (cmd.equals("main") || cmd.startsWith("main ")) {
            executeMain(cmd.equals("main") ? "" : cmd.substring(5).trim());
        } else if (cmd.startsWith("grep! ")) {
            // bang付き: node_modules 等のデフォルトスキップ対象も含め全ファイルを検索する
            String pattern = cmd.substring(6).trim();
            executeGrep(pattern, getProjectRoot(), true);
        } else if (cmd.startsWith("grep ")) {
            String pattern = cmd.substring(5).trim();
            executeGrep(pattern);
        } else if (cmd.startsWith("rename ")) {
            String args = cmd.substring(7).trim();
            executeRename(args);
        } else if (cmd.equals("oi") || cmd.equals("organize-imports")) {
            organizeImports();
        } else if (cmd.startsWith("remove-import ")) {
            String fqn = cmd.substring("remove-import ".length()).trim();
            executeRemoveImport(fqn);
        } else if (cmd.equals("wrap")) {
            wrapEnabled = true;
        } else if (cmd.equals("nowrap")) {
            wrapEnabled = false;
        } else if (cmd.equals("pwd")) {
            statusMessage = getProjectRoot().toString();
        } else if (cmd.startsWith("cd ")) {
            changeDirectory(cmd.substring(3).trim());
        } else if (cmd.equals("bnext") || cmd.equals("bn")) {
            switchToRelativeBuffer(+1);
        } else if (cmd.equals("bprev") || cmd.equals("bp")) {
            switchToRelativeBuffer(-1);
        } else if (cmd.equals("sp") || cmd.equals("split")) {
            if (splitVerticalCallback != null) splitVerticalCallback.run();
        } else if (cmd.equals("vs") || cmd.equals("vsplit") || cmd.equals("vsp")) {
            if (splitHorizontalCallback != null) splitHorizontalCallback.run();
        } else if (cmd.equals("qa") || cmd.equals("qall")) {
            quitAll(false);
        } else if (cmd.equals("qa!") || cmd.equals("qall!")) {
            quitAll(true);
        } else if (cmd.equals("q")) {
            if (closeBlockedCallback != null) {
                closeBlockedCallback.run();
            } else {
                exitCallback.run();
            }
        } else if (cmd.equals("wq")) {
            if (closeBlockedCallback != null) {
                closeBlockedCallback.run();
            } else if (saveToFile(currentFilePath)) {
                exitCallback.run();
            }
        } else if (cmd.matches("\\d+")) {
            jumpToLineNumber(Integer.parseInt(cmd));
        } else {
            statusMessage = "E: unknown command '" + cmd + "'";
        }
    }

    /**
     * ":X"（Xは1始まりの行番号）コマンド。X行目の先頭へカーソルを移動する。
     * Xが1未満、または総行数を超える場合はエラーメッセージのみ設定しカーソルは動かさない。
     * 詳細は .claude/skills/line-number-jump/SKILL.md 参照。
     */
    private void jumpToLineNumber(int oneBasedLine) {
        int totalLines = getLines().length;
        if (oneBasedLine < 1 || oneBasedLine > totalLines) {
            statusMessage = "E: invalid line number";
            return;
        }
        cursorRow = oneBasedLine - 1;
        cursorCol = 0;
    }

    // -------------------------------------------------------------------------
    // Vim式置換コマンド（:s / :%s / :'<,'>s / :N,Ms）
    // 詳細は .claude/skills/vim-substitution/SKILL.md 参照
    // -------------------------------------------------------------------------

    private static final Pattern SUBSTITUTE_RANGE_PATTERN = Pattern.compile("^(\\d+),(\\d+)(s.*)$");

    /**
     * cmd が置換コマンド（範囲プレフィックス + s + 区切り文字...）の形なら実行して true を返す。
     * そうでなければ何もせず false を返し、呼び出し側の既存分岐にフォールスルーさせる。
     */
    private boolean handleSubstituteCommand(String cmd) {
        int r1, r2;
        String sPart;

        if (cmd.startsWith("%")) {
            String[] lines = getLines();
            r1 = 0;
            r2 = Math.max(0, lines.length - 1);
            sPart = cmd.substring(1);
        } else if (cmd.startsWith("'<,'>")) {
            sPart = cmd.substring(5);
            if (!sPart_isSubstitute(sPart)) return false;
            if (!lastVisualValid) {
                statusMessage = "E: no previous visual selection";
                return true;
            }
            r1 = Math.min(lastVisualAnchorRow, lastVisualCursorRow);
            r2 = Math.max(lastVisualAnchorRow, lastVisualCursorRow);
        } else {
            Matcher rangeMatcher = SUBSTITUTE_RANGE_PATTERN.matcher(cmd);
            if (rangeMatcher.matches()) {
                r1 = Integer.parseInt(rangeMatcher.group(1)) - 1;
                r2 = Integer.parseInt(rangeMatcher.group(2)) - 1;
                sPart = rangeMatcher.group(3);
            } else {
                r1 = r2 = cursorRow;
                sPart = cmd;
            }
        }

        if (!sPart_isSubstitute(sPart)) return false;

        executeSubstitute(r1, r2, sPart);
        return true;
    }

    /** sPart が "s" + 区切り文字（英数字・空白以外の1文字）から始まる置換コマンドの形か判定する。 */
    private boolean sPart_isSubstitute(String sPart) {
        if (sPart.length() < 2 || sPart.charAt(0) != 's') return false;
        char delim = sPart.charAt(1);
        return !Character.isLetterOrDigit(delim) && !Character.isWhitespace(delim);
    }

    /** [r1, r2]（0始まり・両端含む）の各行に対して s/pattern/replacement/flags を適用する。 */
    private void executeSubstitute(int r1, int r2, String sPart) {
        char delimiter = sPart.charAt(1);
        String[] parts = sPart.substring(2).split(Pattern.quote(String.valueOf(delimiter)), 3);

        String rawPattern = parts.length > 0 ? parts[0] : "";
        String rawReplacement = parts.length > 1 ? parts[1] : "";
        String flags = parts.length > 2 ? parts[2] : "";

        String patternStr = rawPattern.isEmpty() ? lastSearchPattern : rawPattern;
        if (patternStr == null || patternStr.isEmpty()) {
            statusMessage = "E: no previous substitute pattern";
            return;
        }

        boolean global = flags.contains("g");
        boolean ignoreCase = flags.contains("i");

        Pattern compiledPattern;
        try {
            compiledPattern = Pattern.compile(patternStr, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException ex) {
            statusMessage = "E: invalid regex: " + ex.getMessage();
            return;
        }

        String javaReplacement = translateVimReplacement(rawReplacement);

        String[] lines = getLines();
        int maxRow = Math.max(0, lines.length - 1);
        r1 = Math.max(0, Math.min(r1, maxRow));
        r2 = Math.max(0, Math.min(r2, maxRow));
        if (r1 > r2) { int tmp = r1; r1 = r2; r2 = tmp; }

        int totalReplacements = 0;
        int linesChanged = 0;
        int lastChangedRow = -1;

        for (int row = r1; row <= r2; row++) {
            String[] currentLines = getLines();
            if (row >= currentLines.length) break;
            String line = currentLines[row];

            Matcher counter = compiledPattern.matcher(line);
            int matchCount = 0;
            while (counter.find()) {
                matchCount++;
                if (!global) break;
            }
            if (matchCount == 0) continue;

            Matcher m = compiledPattern.matcher(line);
            String replaced = global ? m.replaceAll(javaReplacement) : m.replaceFirst(javaReplacement);
            if (!replaced.equals(line)) {
                int lineStart = offsetAt(row, 0);
                buffer.delete(lineStart, line.length());
                buffer.insert(lineStart, replaced);
                totalReplacements += matchCount;
                linesChanged++;
                lastChangedRow = row;
            }
        }

        if (totalReplacements == 0) {
            statusMessage = "E: pattern not found: " + patternStr;
            return;
        }

        if (!rawPattern.isEmpty()) {
            lastSearchPattern = rawPattern;
        }
        cursorRow = lastChangedRow;
        cursorCol = 0;
        clampCursorForNormal();
        statusMessage = totalReplacements + " substitutions on " + linesChanged + " lines";
    }

    /**
     * Vim式置換文字列（\1..\9=後方参照, &=マッチ全体, \&/\\=エスケープ）を
     * Java の Matcher 置換構文（$1..$9, $0, リテラル$のエスケープ）に変換する。
     */
    private String translateVimReplacement(String vimReplacement) {
        StringBuilder sb = new StringBuilder();
        int len = vimReplacement.length();
        for (int i = 0; i < len; i++) {
            char c = vimReplacement.charAt(i);
            if (c == '\\' && i + 1 < len) {
                char next = vimReplacement.charAt(i + 1);
                if (next >= '0' && next <= '9') {
                    sb.append('$').append(next);
                } else if (next == '&' || next == '\\') {
                    sb.append(next == '\\' ? "\\\\" : next);
                } else {
                    sb.append(next);
                }
                i++;
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '&') {
                sb.append("$0");
            } else if (c == '$') {
                sb.append("\\$");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean saveToFile(String path) {
        if (path == null || path.isEmpty()) {
            statusMessage = "E: no file name";
            return false;
        }
        try {
            String resolvedPath = resolveSavePath(path);
            if (resolvedPath == null) {
                statusMessage = "E: cannot determine save path";
                return false;
            }
            Path targetPath = Path.of(resolvedPath).toAbsolutePath();
            Files.createDirectories(targetPath.getParent());
            // mode ではなく binaryModeOwner の参照一致で判定する。:w は COMMAND モードの
            // executeCommand() 内から呼ばれるため、この時点では mode はまだ COMMAND のまま
            // （BINARY への復帰は processCommandKey の Enter ハンドラが executeCommand() から
            // 戻った後に modeAfterCommand() で行う）。
            if (binaryModeOwner == buffer) {
                byte[] bytes;
                try {
                    bytes = HexDumpFormatter.parse(buffer.getText(), binaryByteCount);
                } catch (RuntimeException e) {
                    statusMessage = "E: corrupted binary buffer: " + e.getMessage();
                    return false;
                }
                Files.write(targetPath, bytes);
                statusMessage = "\"" + targetPath + "\" written (" + bytes.length + " bytes)";
            } else {
                Files.writeString(targetPath, buffer.getText());
                statusMessage = "\"" + targetPath + "\" written";
            }
            buffer.markSaved();
            // 相対パス指定 or 新規ファイルの初回保存でも、以後は常に絶対パスで
            // currentFilePath を保持する（FILER/telescope 等の他経路と形式を揃え、
            // switchToRelativeBuffer() の BUFFER_REGISTRY 突合を機能させるため）。
            currentFilePath = targetPath.toString();
            if (onFileOpened != null) {
                onFileOpened.accept(new BufferPicker.BufferEntry(
                    targetPath.getFileName().toString(), currentFilePath));
            }
            if (onSave != null) onSave.run();
            return true;
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // :wa / :qa / :qa!（Vim互換の全保存・全終了）
    // 対象は allEditorsSupplier が返す全 ModalEditor（既定は自分自身のみ＝単一ペイン相当。
    // 画面分割時は Main.java が setAllEditorsSupplier() で全ペイン分に差し替える）。
    // saveToFile()/currentFilePath は private だが、同一クラス内であれば他インスタンスのメンバーにも
    // アクセスできるという Java の仕様を利用し、新規のpublicメソッドを増やさずに済ませている。
    // -------------------------------------------------------------------------

    /** :wa / :wall。未保存変更のある全編集対象のみ保存する。 */
    private void saveAll() {
        List<ModalEditor> editors = allEditorsSupplier.get();
        int savedCount = 0;
        List<String> failed = new ArrayList<>();
        for (ModalEditor ed : editors) {
            if (!ed.buffer.isModified()) continue;
            String label = ed.currentFilePath != null ? ed.currentFilePath : "(no file name)";
            if (ed.saveToFile(ed.currentFilePath)) {
                savedCount++;
            } else {
                failed.add(label);
            }
        }
        if (!failed.isEmpty()) {
            statusMessage = "E: failed to save: " + String.join(", ", failed);
        } else if (savedCount == 0) {
            statusMessage = "no changes to save";
        } else {
            statusMessage = savedCount + " file(s) written";
        }
    }

    /**
     * :qa / :qall（force=false）と :qa! / :qall!（force=true）。
     * force=false の場合、未保存変更のある編集対象が1つでもあれば全終了を拒否しメッセージを出す。
     * force=true の場合は未保存変更を破棄してアプリケーション全体を終了する。
     * :q と異なり、画面分割中でも「現在のペインを閉じる」ではなく常にアプリケーション全体を終了する
     * （Vimの :qa がウィンドウ分割の有無に関わらずアプリケーションを終了するのと同じ意味論）。
     */
    private void quitAll(boolean force) {
        List<ModalEditor> editors = allEditorsSupplier.get();
        if (!force) {
            List<String> unsaved = new ArrayList<>();
            for (ModalEditor ed : editors) {
                if (ed.buffer.isModified()) {
                    unsaved.add(ed.currentFilePath != null ? ed.currentFilePath : "[No Name]");
                }
            }
            if (!unsaved.isEmpty()) {
                statusMessage = "E37: No write since last change for: "
                        + String.join(", ", unsaved) + " (add ! to override)";
                return;
            }
        }
        exitAllCallback.run();
    }

    /**
     * :w path のパス部分を解決する。
     * - 相対パス: projectRoot を基準に解決
     * - ~ で始まる: ホームディレクトリを展開
     * - s/pattern/replacement/ の形式: currentFilePath のファイル名に正規表現置換を適用
     * - 絶対パス: そのまま使用
     */
    private String resolveSavePath(String pathSpec) {
        if (pathSpec == null || pathSpec.isEmpty()) {
            return currentFilePath; // :w だけなら現在のファイルに保存
        }

        // 正規表現置換の形式: s/pattern/replacement/ または s/pattern/replacement/g など
        if (pathSpec.startsWith("s/") || pathSpec.startsWith("s\\")) {
            return applyRegexSubstituteToPath(pathSpec);
        }

        return resolveRelativeToProjectRoot(pathSpec);
    }

    /**
     * ~ 展開の上、絶対パスならそのまま・相対パスなら projectRoot を基準に絶対パス化する。
     * `:e path`/`:w path` の両方で使う共通の解決ロジック（FILER/telescope 等、他の
     * ファイルを開く経路がいずれも絶対パスを currentFilePath に格納するのと形式を揃えるため）。
     */
    private String resolveRelativeToProjectRoot(String pathSpec) {
        String expanded = expandHome(pathSpec);
        Path target = Path.of(expanded);
        if (target.isAbsolute()) {
            return target.toString();
        }
        return getProjectRoot().resolve(expanded).toAbsolutePath().toString();
    }

    /**
     * 正規表現置換パターンを currentFilePath に適用する。
     * 形式: s/pattern/replacement/ または s/pattern/replacement/g
     * 例: s/^.*\//new_/ → ファイル名をいじる
     *     s/\.java$/.bak/   → 拡張子を変更
     */
    private String applyRegexSubstituteToPath(String pattern) {
        if (currentFilePath == null || currentFilePath.isEmpty()) {
            statusMessage = "E: no current file to apply regex to";
            return null;
        }

        try {
            // s/pattern/replacement/flags の形式を解析
            String delimiter = String.valueOf(pattern.charAt(1)); // / または \
            String[] parts = pattern.substring(2).split(Pattern.quote(delimiter), 3);

            if (parts.length < 2) {
                statusMessage = "E: invalid regex substitute syntax";
                return null;
            }

            String regexPattern = parts[0];
            String replacement = parts.length > 1 ? parts[1] : "";
            String flags = parts.length > 2 ? parts[2] : "";

            // フラグを解析（g = グローバル、デフォルトは最初の1つだけ置換）
            boolean global = flags.contains("g");

            Path currentPath = Path.of(currentFilePath);
            String filename = currentPath.getFileName().toString();
            String parent = currentPath.getParent().toString();

            // 正規表現置換を実行
            String newFilename;
            if (global) {
                newFilename = filename.replaceAll(regexPattern, replacement);
            } else {
                newFilename = filename.replaceFirst(regexPattern, replacement);
            }

            if (newFilename.equals(filename)) {
                statusMessage = "E: regex did not match filename";
                return null;
            }

            return Path.of(parent, newFilename).toString();
        } catch (Exception ex) {
            statusMessage = "E: regex error: " + ex.getMessage();
            return null;
        }
    }

    private void newBuffer() {
        pushBuffer();
        buffer = new UndoablePieceTable("");
        currentFilePath = null;
        cursorRow = 0;
        cursorCol = 0;
        resetSearchAndResultState();
        statusMessage = "[新規バッファ]";
    }

    /**
     * :tutor — vimtutor 同様、実際に編集して学ぶ対話型チュートリアルを開く。
     * 保存先を持たない通常のバッファとして開くため、ここで学んだ操作
     * （x/dd/yy/p/u 等）がそのままこのバッファ上で機能する。
     */
    private void openTutorial() {
        pushBuffer();
        buffer = new UndoablePieceTable(Tutorial.CONTENT);
        currentFilePath = null;
        cursorRow = 0;
        cursorCol = 0;
        resetSearchAndResultState();
        statusMessage = "チュートリアルを開きました — :q で終了、Ctrl+U で元のバッファに戻れます";
    }

    /**
     * ディスク上のファイルをバッファへ格納できるテキストへ変換した結果。
     * classFileBytes は .class ファイル（マジックナンバー一致）を開いた場合のみ非null（:nimo用に
     * 生バイト列を保持する。この場合 binary() は false のままにし、Mode.BINARY には入らず
     * 読み取り専用の構造ビューとして表示する。currentFilePath は他の読み取り専用プレビュー同様
     * null 扱いにすること）。binary()==true の場合、text() は使わず rawBytes() を
     * {@link #enterBinaryMode} に渡すこと（呼び出し側は5箇所あり、いずれも同じ分岐パターンを踏襲する）。
     */
    private record FileLoadResult(String text, boolean binary, byte[] rawBytes, byte[] classFileBytes, String classFileDisplayName) {}

    private static final int CLASS_FILE_MAGIC_0 = 0xCA;
    private static final int CLASS_FILE_MAGIC_1 = 0xFE;
    private static final int CLASS_FILE_MAGIC_2 = 0xBA;
    private static final int CLASS_FILE_MAGIC_3 = 0xBE;

    /** バイト列の先頭4バイトがJVM仕様のクラスファイル・マジックナンバー(0xCAFEBABE)と一致するか。 */
    private static boolean looksLikeClassFile(byte[] bytes) {
        return bytes.length >= 4
                && (bytes[0] & 0xFF) == CLASS_FILE_MAGIC_0
                && (bytes[1] & 0xFF) == CLASS_FILE_MAGIC_1
                && (bytes[2] & 0xFF) == CLASS_FILE_MAGIC_2
                && (bytes[3] & 0xFF) == CLASS_FILE_MAGIC_3;
    }

    /**
     * pathの内容を読み込みバッファ用テキストに変換する。UTF-8として妥当なテキストは
     * そのまま（\r\n→\n正規化のうえ）返す。マジックナンバーが .class ファイルと一致する場合は
     * {@link dev.javatexteditor.classfile.ClassFileParser} で構造解析し、JVM仕様通りの
     * 文字化けしない構造ビュー（{@link dev.javatexteditor.classfile.ClassFileFormatter}）を
     * 読み取り専用プレビューとして返す。解析に失敗した場合（壊れた.class等）は通常のバイナリ判定
     * にフォールスルーする。NULバイトを含む、またはUTF-8として不正なバイト列（画像・実行ファイル等
     * のバイナリ、UTF-16等の他エンコーディング）は Mode.BINARY（{@link #enterBinaryMode}）へ
     * 渡すための生バイト列を返す（{@link BinaryFileDetector} 参照）。
     */
    private FileLoadResult readFileContentForBuffer(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String displayName = path.getFileName().toString();
        if (looksLikeClassFile(bytes)) {
            try {
                ClassFile classFile = ClassFileParser.parse(bytes);
                String text = ClassFileFormatter.format(classFile, displayName);
                return new FileLoadResult(text, false, null, bytes, displayName);
            } catch (ClassFileFormatException e) {
                // 壊れた.class: 通常のバイナリ判定（Mode.BINARY）にフォールスルーする
            }
        }
        if (BinaryFileDetector.isBinary(bytes)) {
            return new FileLoadResult(null, true, bytes, null, null);
        }
        String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
        return new FileLoadResult(text, false, null, null, null);
    }

    /**
     * Vim方式の共有バッファ: 同じ絶対パスを他ペインが既に開いていれば（liveBufferLookup経由で）
     * その生きた UndoablePieceTable 参照をそのまま再利用し、無ければ読み込んだテキストから
     * 新規インスタンスを作る。前者の場合はディスクから読んだ text は使われず捨てられる
     * （他ペインの未保存の編集内容を破棄しないため）。ファイルを開く6箇所すべてがこのメソッドを
     * 経由することで、真の共有バッファ（1文字編集するたびに他ペインの画面にも即座に反映される）
     * を実現する。
     */
    private UndoablePieceTable acquireBufferForOpen(String absolutePath, String text) {
        if (liveBufferLookup != null) {
            UndoablePieceTable existing = liveBufferLookup.apply(absolutePath);
            if (existing != null) return existing;
        }
        return new UndoablePieceTable(text);
    }

    /**
     * readFileContentForBuffer() が .class ファイルを検出した場合、:nimo コマンド用に
     * 生バイト列とファイル名を記録する。classFileBufferOwner に buffer 参照そのものを控えておくことで、
     * 別のバッファへ切り替わった時点で参照不一致により自動的に :nimo が無効化される
     * （outputErrorLinesOwner と同じ「参照一致による自動失効」パターンを踏襲）。
     * buffer への代入（`buffer = new UndoablePieceTable(result.text())`）の直後に呼ぶこと。
     */
    private void trackClassFileBuffer(FileLoadResult result) {
        if (result.classFileBytes() != null) {
            classFileBytes = result.classFileBytes();
            classFileName = result.classFileDisplayName();
            classFileBufferOwner = buffer;
        } else {
            classFileBytes = null;
            classFileName = null;
            classFileBufferOwner = null;
        }
    }

    // -------------------------------------------------------------------------
    // Mode.BINARY（:b コマンド・非UTF-8ファイルの自動判定オープンで入る、
    // hexdumpをその場で1バイトずつ上書き編集できるバイナリエディタ）
    // -------------------------------------------------------------------------

    /**
     * bytes を hexdump 表示にして Mode.BINARY へ遷移する。:b コマンド（現在のバッファをbyte[]化）と
     * 非UTF-8ファイルの自動判定オープン（5箇所のファイルオープン経路）の共通入口。
     * buffer 自体を編集の唯一の真実として扱うため、bytes 自体はここでの初期描画にのみ使い、
     * 以後は保持しない（{@link #applyHexDigit} は buffer のテキストを直接書き換える）。
     */
    private void enterBinaryMode(byte[] bytes, String fileName, String filePath) {
        binaryByteCount = bytes.length;
        binaryCursorOffset = 0;
        binaryNibblePending = false;
        buffer = new UndoablePieceTable(HexDumpFormatter.format(bytes, fileName));
        binaryModeOwner = buffer;
        currentFilePath = filePath;
        mode = Mode.BINARY;
        syncBinaryCursorPosition();
    }

    /**
     * Mode.BINARY → 通常テキスト表示へトグルする。buffer の hexdumpテキストを
     * {@link HexDumpFormatter#parse} でバイト列に復元し、それがUTF-8として妥当な場合のみ
     * テキスト化して戻る。妥当でない場合（真のバイナリファイル等）は変換すると内容が
     * 破壊されるため、エラーメッセージを出して Mode.BINARY のまま留まる。
     */
    private boolean exitBinaryModeToText() {
        byte[] bytes;
        try {
            bytes = HexDumpFormatter.parse(buffer.getText(), binaryByteCount);
        } catch (RuntimeException e) {
            statusMessage = "E: corrupted binary buffer: " + e.getMessage();
            return false;
        }
        if (BinaryFileDetector.isBinary(bytes)) {
            statusMessage = "E: not valid UTF-8 text — staying in binary mode";
            return false;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        buffer = new UndoablePieceTable(text);
        binaryModeOwner = null;
        mode = Mode.NORMAL;
        cursorRow = 0;
        cursorCol = 0;
        statusMessage = "text view";
        return true;
    }

    /**
     * :b — 現在のバッファを Mode.BINARY とテキスト表示の間でトグルする。
     * バイナリへ入る側は「今まさに編集中のバッファ内容」をUTF-8バイト列化する
     * （ディスクから読み直さない。CLAUDE.md「:bコマンドの対象」の決定に従う）。
     */
    private void toggleBinaryMode() {
        if (binaryModeOwner == buffer) {
            exitBinaryModeToText();
        } else {
            byte[] bytes = buffer.getText().getBytes(StandardCharsets.UTF_8);
            String fileName = currentFilePath != null
                ? Path.of(currentFilePath).getFileName().toString() : "[No Name]";
            enterBinaryMode(bytes, fileName, currentFilePath);
        }
    }

    /** binaryCursorOffset（バイト単位）を hexdumpテキスト上の cursorRow/cursorCol へ変換する。 */
    private void syncBinaryCursorPosition() {
        if (binaryByteCount == 0) {
            cursorRow = 0;
            cursorCol = 0;
            return;
        }
        int byteInLine = binaryCursorOffset % HexDumpFormatter.BYTES_PER_LINE;
        cursorRow = 1 + binaryCursorOffset / HexDumpFormatter.BYTES_PER_LINE;
        cursorCol = HexDumpFormatter.hexDigitColumn(byteInLine) + (binaryNibblePending ? 1 : 0);
    }

    /** 1バイト単位でカーソルを移動する。末尾/先頭で止まる（ラップアラウンドしない）。 */
    private void moveBinaryCursor(int deltaBytes) {
        if (binaryByteCount == 0) return;
        binaryCursorOffset = Math.max(0, Math.min(binaryByteCount - 1, binaryCursorOffset + deltaBytes));
        binaryNibblePending = false;
        syncBinaryCursorPosition();
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * カーソル位置のバイトへ16進数1桁を上書きする。1桁目は高位4bit・2桁目は低位4bitを確定し
     * 自動的に次のバイトへ前進する（HxD等の一般的なバイナリエディタと同じ挙動）。
     * hexdumpの固定レイアウト上、対象の16進数2桁とASCII欄1文字それぞれを1文字delete+insertで
     * 直接書き換える（既存の replaceCharAtCursor と同じ「1論理編集が複数undo単位になる」トレードオフ）。
     */
    private void applyHexDigit(char c) {
        if (binaryByteCount == 0) return;
        int byteInLine = binaryCursorOffset % HexDumpFormatter.BYTES_PER_LINE;
        int hexCol = HexDumpFormatter.hexDigitColumn(byteInLine);
        int asciiCol = HexDumpFormatter.asciiColumn(byteInLine);
        int digitCol = hexCol + (binaryNibblePending ? 1 : 0);

        int digitOffset = offsetAt(cursorRow, digitCol);
        buffer.delete(digitOffset, 1);
        buffer.insert(digitOffset, String.valueOf(Character.toLowerCase(c)));

        int hexPairOffset = offsetAt(cursorRow, hexCol);
        String hexPair = buffer.getTextInRange(hexPairOffset, hexPairOffset + 2);
        int byteVal;
        try {
            byteVal = Integer.parseInt(hexPair, 16);
        } catch (NumberFormatException e) {
            byteVal = 0;
        }
        char asciiChar = (byteVal >= 0x20 && byteVal < 0x7F) ? (char) byteVal : '.';
        int asciiOffset = offsetAt(cursorRow, asciiCol);
        buffer.delete(asciiOffset, 1);
        buffer.insert(asciiOffset, String.valueOf(asciiChar));

        if (binaryNibblePending) {
            binaryNibblePending = false;
            moveBinaryCursor(1);
        } else {
            binaryNibblePending = true;
            syncBinaryCursorPosition();
        }
    }

    private void processBinaryKey(int keyCode, char keyChar, int modifiers) {
        if (keyChar == ':') {
            commandBuffer.setLength(0);
            statusMessage = "";
            mode = Mode.COMMAND;
            return;
        }
        if (keyCode == KeyEvent.VK_LEFT || keyChar == 'h') { moveBinaryCursor(-1); return; }
        if (keyCode == KeyEvent.VK_RIGHT || keyChar == 'l') { moveBinaryCursor(1); return; }
        if (keyCode == KeyEvent.VK_DOWN || keyChar == 'j') { moveBinaryCursor(HexDumpFormatter.BYTES_PER_LINE); return; }
        if (keyCode == KeyEvent.VK_UP || keyChar == 'k') { moveBinaryCursor(-HexDumpFormatter.BYTES_PER_LINE); return; }
        boolean ctrlDown = (modifiers & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;
        if (!ctrlDown && keyChar == 'u') { buffer.undo(); clampBinaryCursorAfterUndoRedo(); return; }
        if (ctrlDown && keyCode == KeyEvent.VK_R) { buffer.redo(); clampBinaryCursorAfterUndoRedo(); return; }
        if (!ctrlDown && isHexDigit(keyChar)) { applyHexDigit(keyChar); return; }
    }

    // -------------------------------------------------------------------------
    // Mode.TERMINAL（Ctrl+Shift+T / :term コマンド、OS標準の対話型シェルを子プロセスとして起動する）
    //
    // 真のPTYを実装できない（CLAUDE.mdの「外部ライブラリ一切不使用」方針上、PTYにはJNIが必要で
    // 不採用。SystemStatsMonitorのCPU温度取得と同じ判断）ため、以下は既知の制約として受け入れている:
    //   - vim/less/top等フルスクリーンプログラムは正しく描画されない（raw modeがない）。
    //   - Ctrl+Cは本物のSIGINT転送ができないため、プロセスを強制終了する代替動作にする
    //     （以後は新しいセッションとして :term で再起動する運用）。
    //   - シェル側のreadline（行編集）はttyが無いと動かないため、ユーザーが入力した文字は
    //     シェルからエコーバックされない。ここでローカルエコーし、Enterで1行分をまとめて送信する。
    //
    // 実プロセスの起動・標準入出力の読み書き（TerminalSession）はSwingに依存しないため
    // Main.java 側が所有し、ModalEditor はコールバック（terminalStartCallback/terminalWriteCallback/
    // terminalKillCallback）経由でのみやり取りする（BindingDefinitionResolverの「実行機構の注入方式」
    // と同じ設計。詳細はCLAUDE.md参照）。
    // -------------------------------------------------------------------------

    /**
     * TERMINAL モードへ入る。restartIfDead=false（Ctrl+Shift+T によるトグル）は、既存セッションが
     * 死んでいてもそのまま静的なログを表示するだけで再起動しない（見返すだけの用途を壊さないため）。
     * restartIfDead=true（:term コマンド）は、セッションが存在しないか死んでいれば新しいバッファ・
     * 新しいシェルプロセスを作り直す。
     */
    private void enterTerminal(boolean restartIfDead) {
        boolean needsNewSession = (terminalBuffer == null) || (restartIfDead && !terminalAlive);
        terminalSavedBuffer = buffer;
        terminalSavedFilePath = currentFilePath;
        terminalSavedCursorRow = cursorRow;
        terminalSavedCursorCol = cursorCol;
        if (needsNewSession) {
            terminalBuffer = new UndoablePieceTable("");
            terminalErrorLines = new java.util.HashSet<>();
            terminalNextRow = 0;
            terminalPendingInput.setLength(0);
        }
        buffer = terminalBuffer;
        currentFilePath = null;
        clearSearchHighlights();
        grepResults = null;
        fileNameResults = null;
        mode = Mode.TERMINAL;
        moveCursorToTerminalEnd();
        if (needsNewSession) {
            terminalAlive = true; // 起動失敗時は markTerminalStartFailed() が false に戻す
            if (terminalStartCallback != null) terminalStartCallback.run();
        }
    }

    /** TERMINAL セッションを終了し、enterTerminal() で退避した元バッファに戻す（プロセス自体は生存し続ける）。 */
    private void exitTerminal() {
        mode = Mode.NORMAL;
        buffer = terminalSavedBuffer != null ? terminalSavedBuffer : new UndoablePieceTable("");
        currentFilePath = terminalSavedFilePath;
        cursorRow = terminalSavedCursorRow;
        cursorCol = terminalSavedCursorCol;
        terminalSavedBuffer = null;
        terminalSavedFilePath = null;
    }

    /** Ctrl+Shift+T: TERMINAL モードをトグルする（Main.java のグローバルキーディスパッチャから呼ばれる）。 */
    public void toggleTerminalMode() {
        if (mode == Mode.TERMINAL) {
            exitTerminal();
        } else {
            enterTerminal(false);
        }
    }

    /** :term / :terminal コマンド。既存セッションが死んでいれば新しいシェルプロセスで作り直す。 */
    private void executeTermCommand() {
        if (mode != Mode.TERMINAL) enterTerminal(true);
    }

    /** カーソルを terminalBuffer 末尾（プロンプト直後）へ移動する。 */
    private void moveCursorToTerminalEnd() {
        String[] lines = getLines();
        cursorRow = Math.max(0, lines.length - 1);
        cursorCol = lines.length > 0 ? lines[cursorRow].length() : 0;
    }

    private void processTerminalKey(int keyCode, char keyChar, int modifiers) {
        if (!terminalAlive) return; // プロセス終了後はキー入力を無視し、ログの閲覧のみ許可する
        boolean ctrlDown = (modifiers & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;
        if (ctrlDown && keyCode == KeyEvent.VK_C) {
            // 真のSIGINTは転送できないため、プロセスを強制終了する（destroyForcibly相当）。
            if (terminalKillCallback != null) terminalKillCallback.run();
            return;
        }
        if (keyCode == KeyEvent.VK_ENTER) {
            String line = terminalPendingInput.toString();
            terminalPendingInput.setLength(0);
            buffer.insert(buffer.length(), "\n");
            moveCursorToTerminalEnd();
            if (terminalWriteCallback != null) terminalWriteCallback.accept(line + "\n");
            return;
        }
        if (keyCode == KeyEvent.VK_BACK_SPACE) {
            if (terminalPendingInput.length() > 0) {
                terminalPendingInput.deleteCharAt(terminalPendingInput.length() - 1);
                if (buffer.length() > 0) buffer.delete(buffer.length() - 1, 1);
                moveCursorToTerminalEnd();
            }
            return;
        }
        if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ') {
            // シェル側のエコーは無効（ttyが無いため）のため、ここでローカルエコーする。
            terminalPendingInput.append(keyChar);
            buffer.insert(buffer.length(), String.valueOf(keyChar));
            moveCursorToTerminalEnd();
        }
    }

    /**
     * Main.java: シェルの標準出力/標準エラーを1チャンク読むたび呼ばれる。行区切りを待たず即座に
     * 追記する（プロンプトは末尾に改行を含まないため、行単位だと表示されなくなってしまう）。
     * \r\n/\r は \n に正規化する（プログレスバー等の「同一行上書き」はサポートせず、行の羅列として
     * 表示する意図的な単純化。詳細はCLAUDE.md参照）。
     */
    public void appendTerminalOutput(String chunk, boolean isError) {
        if (terminalBuffer == null) return;
        String normalized = chunk.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) return;
        int newlineCount = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '\n') newlineCount++;
        }
        terminalBuffer.insert(terminalBuffer.length(), normalized);
        if (isError) {
            for (int i = 0; i <= newlineCount; i++) terminalErrorLines.add(terminalNextRow + i);
        }
        terminalNextRow += newlineCount;
        followTerminalCursorIfShowing();
    }

    /** terminalBuffer を表示中のペインであれば、カーソルを末尾へ追従させる（他ペインからの出力更新時にも使う）。 */
    public void followTerminalCursorIfShowing() {
        if (buffer == terminalBuffer) {
            moveCursorToTerminalEnd();
        }
    }

    /** Main.java: シェルプロセスが終了した（exit入力・Ctrl+C強制終了含む）ときに呼ばれる。 */
    public void markTerminalExited(int exitCode) {
        if (terminalBuffer == null) return;
        terminalAlive = false;
        String text = terminalBuffer.getText();
        String prefix = (!text.isEmpty() && !text.endsWith("\n")) ? "\n" : "";
        appendTerminalOutput(prefix + "[process exited with code " + exitCode + "]\n", false);
    }

    /** Main.java: シェルプロセスの起動自体に失敗した（コマンドが見つからない等）ときに呼ばれる。 */
    public void markTerminalStartFailed(String message) {
        if (terminalBuffer == null) return;
        terminalAlive = false;
        appendTerminalOutput("[failed to start terminal: " + message + "]\n", true);
    }

    public void setTerminalStartCallback(Runnable cb) { this.terminalStartCallback = cb; }
    public void setTerminalWriteCallback(java.util.function.Consumer<String> cb) { this.terminalWriteCallback = cb; }
    public void setTerminalKillCallback(Runnable cb) { this.terminalKillCallback = cb; }
    public boolean isTerminalMode() { return mode == Mode.TERMINAL; }
    /** エディタプロセス全体で共有される単一のターミナルバッファ参照（未起動なら null）。Main.java が
     *  出力反映後にどのペインが同じ参照を表示中かを判定するために使う（terminalBuffer自体はprivate static）。 */
    public static UndoablePieceTable getSharedTerminalBuffer() { return terminalBuffer; }

    /** undo/redo後、buffer側の行数変化はない前提だがカーソルが範囲外になっていないかだけ保険として揃える。 */
    private void clampBinaryCursorAfterUndoRedo() {
        binaryNibblePending = false;
        if (binaryByteCount > 0) {
            binaryCursorOffset = Math.max(0, Math.min(binaryByteCount - 1, binaryCursorOffset));
        }
        syncBinaryCursorPosition();
    }

    private void loadFromFile(String path) {
        Path p = Path.of(path);
        if (!Files.exists(p)) {
            pushBuffer();
            buffer = acquireBufferForOpen(path, "");
            currentFilePath = path;
            cursorRow = 0;
            cursorCol = 0;
            resetSearchAndResultState();
            statusMessage = "\"" + path + "\" [新規ファイル]";
            // 既存ファイルを開く場合と同様に登録する。登録しないと switchToRelativeBuffer()
            // が BUFFER_REGISTRY 上でこのバッファを見つけられず、Ctrl+U/Ctrl+P で
            // 元々開いていた他のバッファへ戻れなくなる（新規ファイル作成直後の既知の不具合）。
            if (onFileOpened != null) {
                String name = Path.of(path).getFileName().toString();
                onFileOpened.accept(new BufferPicker.BufferEntry(name, path));
            }
            return;
        }
        try {
            pushBuffer();
            FileLoadResult result = readFileContentForBuffer(p);
            resetSearchAndResultState();
            String name = p.getFileName().toString();
            if (result.classFileBytes() != null) {
                buffer = new UndoablePieceTable(result.text());
                currentFilePath = null;
                cursorRow = 0;
                cursorCol = 0;
                statusMessage = "\"" + path + "\" [class, read-only preview]";
            } else if (result.binary()) {
                enterBinaryMode(result.rawBytes(), name, path);
                statusMessage = "\"" + path + "\" [binary] " + result.rawBytes().length + " bytes";
            } else {
                buffer = acquireBufferForOpen(path, result.text());
                currentFilePath = path;
                cursorRow = 0;
                cursorCol = 0;
                statusMessage = "\"" + path + "\" opened";
            }
            trackClassFileBuffer(result);
            if (result.classFileBytes() == null && onFileOpened != null) {
                onFileOpened.accept(new BufferPicker.BufferEntry(name, path));
            }
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
        }
    }

    /** 現在のバッファ状態を履歴に追加し historyIdx を末尾へ進める。 */
    private void pushBuffer() {
        BufferSnapshot snap = new BufferSnapshot(
            buffer.getText(), currentFilePath, cursorRow, cursorCol);
        // 現在位置より後ろの履歴を切り捨て
        if (historyIdx >= 0 && historyIdx < bufferHistory.size() - 1) {
            bufferHistory.subList(historyIdx + 1, bufferHistory.size()).clear();
        }
        bufferHistory.add(snap);
        historyIdx = bufferHistory.size() - 1;
    }

    /** 履歴インデックス idx のバッファを復元する。 */
    private void restoreBuffer(int idx) {
        // 現在のバッファ状態を現在の履歴スロットに上書き保存
        if (historyIdx >= 0 && historyIdx < bufferHistory.size()) {
            bufferHistory.set(historyIdx, new BufferSnapshot(
                buffer.getText(), currentFilePath, cursorRow, cursorCol));
        }
        historyIdx = idx;
        BufferSnapshot snap = bufferHistory.get(idx);
        buffer = new UndoablePieceTable(snap.text());
        currentFilePath = snap.filePath();
        cursorRow = snap.row();
        cursorCol = snap.col();
        resetSearchAndResultState();
        String label = (snap.filePath() != null) ? "\"" + snap.filePath() + "\"" : "[新規バッファ]";
        statusMessage = label + " (" + (idx + 1) + "/" + bufferHistory.size() + ")";
    }

    private void executeGrep(String pattern) {
        executeGrep(pattern, getProjectRoot(), false);
    }

    private void executeGrep(String pattern, Path baseDir) {
        executeGrep(pattern, baseDir, false);
    }

    /**
     * baseDir 配下を grep して *grep* 疑似バッファに結果を表示する。
     * {@link ProjectSearcher#search} は作業ディレクトリ配下（既定値がホームディレクトリになりうる）を
     * 同期的に全文走査するため、Shift+K（{@link #withSearchTimeout}参照）と同様に
     * {@link #PROJECT_SYMBOL_SEARCH_TIMEOUT_MS} で打ち切り、EDT が長時間ブロックされるのを防ぐ。
     *
     * @param fullScan true の場合 {@link ProjectSearcher#DEFAULT_SKIP_DIRS}（node_modules等）を
     *                 無視して全ファイルを対象にする（gR / :grep! / \g! の「bang」指定用）。
     */
    private void executeGrep(String pattern, Path baseDir, boolean fullScan) {
        if (pattern.isEmpty()) {
            statusMessage = "E: no pattern";
            return;
        }
        try {
            Pattern.compile(pattern);
        } catch (java.util.regex.PatternSyntaxException e) {
            statusMessage = "E: bad pattern: " + e.getDescription();
            return;
        }
        List<SearchResult> results = withTimeout(() -> projectSearcher.search(baseDir, pattern, fullScan));
        if (results == null) {
            statusMessage = "grep: search timed out（作業ディレクトリが大きすぎる可能性があります）";
            return;
        }
        if (results.isEmpty()) {
            statusMessage = "grep: no matches for /" + pattern + "/";
            return;
        }

        // 結果をバッファに読み込む
        String bangLabel = fullScan ? "!" : "";
        StringBuilder sb = new StringBuilder();
        sb.append("*grep").append(bangLabel).append("* /").append(pattern).append("/ — ")
            .append(results.size()).append(" match(es)\n");
        for (SearchResult r : results) {
            sb.append(r.toDisplayLine()).append("\n");
        }
        grepResults = results;
        grepBaseDir = baseDir;
        fileNameResults = null;
        buffer = new UndoablePieceTable(sb.toString());
        currentFilePath = null;
        clearSearchHighlights();
        cursorRow = 0;
        cursorCol = 0;
        statusMessage = "grep" + bangLabel + ": " + results.size() + " match(es) — Enter to jump";
    }

    /**
     * :rename <oldName> <newName> — プロジェクト全体で識別子を一括リネームする。
     * 結果は *rename* 疑似バッファに表示する。
     */
    private void executeRename(String args) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            statusMessage = "E: usage: rename <oldName> <newName>";
            return;
        }
        String oldName = parts[0];
        String newName = parts[1];

        Path baseDir = getProjectRoot();
        List<RenameResult> results;
        try {
            results = renameRefactorer.rename(baseDir, oldName, newName);
        } catch (IllegalArgumentException e) {
            statusMessage = "E: " + e.getMessage();
            return;
        }

        if (results.isEmpty()) {
            statusMessage = "rename: no occurrences of '" + oldName + "' found";
            return;
        }

        String displayText = RenameRefactorer.buildDisplayText(oldName, newName, results);
        buffer = new UndoablePieceTable(displayText);
        currentFilePath = null;
        grepResults = null;
        cursorRow = 0;
        cursorCol = 0;

        int totalReplacements = results.stream().mapToInt(RenameResult::replacementCount).sum();
        long errorCount = results.stream().filter(r -> !r.success()).count();
        if (errorCount > 0) {
            statusMessage = "rename: " + totalReplacements + " replacement(s) in "
                + results.size() + " file(s), " + errorCount + " error(s)";
        } else {
            statusMessage = "rename: " + totalReplacements + " replacement(s) in "
                + results.size() + " file(s)";
        }
    }

    /**
     * grep 結果バッファ内でカーソルがある行の結果ファイルを開き、該当行に移動する。
     * cursorRow==0 はヘッダ行なのでジャンプ対象外。
     */
    private void jumpToGrepResult() {
        if (grepResults == null) return;
        // 行0はヘッダ、行1以降が結果
        int resultIdx = cursorRow - 1;
        if (resultIdx < 0 || resultIdx >= grepResults.size()) {
            statusMessage = "E: no result at this line";
            return;
        }
        SearchResult r = grepResults.get(resultIdx);
        Path base = (grepBaseDir != null) ? grepBaseDir : getProjectRoot();
        Path target = base.resolve(r.filePath());
        try {
            FileLoadResult result = readFileContentForBuffer(target);
            inJdkSourceBuffer = false;
            grepResults = null;
            clearSearchHighlights();
            if (result.classFileBytes() != null) {
                buffer = new UndoablePieceTable(result.text());
                currentFilePath = null;
                cursorRow = 0;
                cursorCol = 0;
                statusMessage = "\"" + r.filePath() + "\" [class, read-only preview]";
            } else if (result.binary()) {
                enterBinaryMode(result.rawBytes(), target.getFileName().toString(), target.toString());
                statusMessage = "\"" + r.filePath() + "\" [binary] " + result.rawBytes().length + " bytes";
            } else {
                buffer = acquireBufferForOpen(target.toString(), result.text());
                currentFilePath = target.toString();
                cursorRow = Math.max(0, r.lineNumber() - 1);
                cursorCol = 0;
                statusMessage = "\"" + r.filePath() + "\" line " + r.lineNumber();
            }
            trackClassFileBuffer(result);
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // VISUALモード処理（文字単位）
    // -------------------------------------------------------------------------

    private void processVisualKey(int keyCode, char keyChar, int modifiers) {
        // '>'/'<' の前置き count（例: "3>"）。数字以外が来たら次の action 解決時に破棄される。
        if (Character.isDigit(keyChar) && (keyChar != '0' || !visualCountBuffer.isEmpty())) {
            visualCountBuffer += keyChar;
            return;
        }
        int count = consumeVisualCount();

        String action = keymap.resolve(KeymapRegistry.Mode.VISUAL, keyCode, keyChar, modifiers);
        if (action == null) return;

        Runnable custom = keymap.getCustomAction(action);
        if (custom != null) { custom.run(); return; }

        switch (action) {
            case "enter.normal" -> { saveLastVisualFromCurrentMode(); mode = Mode.NORMAL; }
            case "cursor.left"   -> moveCursor(0, -1);
            case "cursor.right"  -> moveCursor(0, 1);
            case "cursor.down"   -> moveCursor(1, 0);
            case "cursor.up"     -> moveCursor(-1, 0);
            case "word.forward"        -> moveWordForward();
            case "word.backward"       -> moveWordBackward();
            case "word.end"            -> moveWordEnd();
            case "line.start"          -> moveLineStart();
            case "line.start.nonblank" -> moveLineStartNonBlank();
            case "line.end"            -> moveLineEnd();
            case "file.end"            -> moveFileEnd();
            case "motion.match.pair"   -> jumpToMatchingBracket();
            case "scroll.page.down" -> scrollPage(true,  false);
            case "scroll.page.up"   -> scrollPage(false, false);
            case "scroll.half.down" -> scrollPage(true,  true);
            case "scroll.half.up"   -> scrollPage(false, true);
            case "yank" -> {
                yankRegister = getSelectedText();
                yankType = YankType.CHAR;
                // Vim 仕様: y 後はカーソルを選択開始位置に戻す
                int startOffset = Math.min(offsetAt(anchorRow, anchorCol), offsetOfCursor());
                moveCursorToOffset(startOffset);
                saveLastVisualFromCurrentMode();
                mode = Mode.NORMAL;
            }
            case "delete" -> {
                yankRegister = getSelectedText();
                yankType = YankType.CHAR;
                deleteSelected();
                saveLastVisualFromCurrentMode();
                mode = Mode.NORMAL;
                clampCursorForNormal();
            }
            case "clipboard.copy" -> {
                copyToSystemClipboard(getSelectedText());
                int startOffset = Math.min(offsetAt(anchorRow, anchorCol), offsetOfCursor());
                moveCursorToOffset(startOffset);
                saveLastVisualFromCurrentMode();
                mode = Mode.NORMAL;
            }
            case "indent.right", "indent.left" -> {
                // charwise Visual でも対象は選択に含まれる全行（linewise と同じ扱い）
                int r1 = Math.min(anchorRow, cursorRow);
                int r2 = Math.max(anchorRow, cursorRow);
                indentLines(r1, r2, action.equals("indent.left"), count);
                // gv 用に「更新済みの範囲」としてこの時点のアンカー/カーソルを保存してから、
                // 実際のカーソルは r1 行頭の非空白へ移動する（Vim 準拠）。
                saveLastVisualFromCurrentMode();
                cursorRow = r1;
                moveLineStartNonBlank();
                mode = Mode.NORMAL;
                clampCursorForNormal();
            }
            case "case.lower", "case.upper", "case.toggle" -> {
                applyCaseToSelection(caseOpFor(action));
                saveLastVisualFromCurrentMode();
                mode = Mode.NORMAL;
            }
            case "enter.command.visual" -> enterCommandFromVisual();
        }
    }

    // -------------------------------------------------------------------------
    // VISUAL LINEモード処理（行単位）
    // -------------------------------------------------------------------------

    private void processVisualLineKey(int keyCode, char keyChar, int modifiers) {
        if (Character.isDigit(keyChar) && (keyChar != '0' || !visualCountBuffer.isEmpty())) {
            visualCountBuffer += keyChar;
            return;
        }
        int count = consumeVisualCount();

        String action = keymap.resolve(KeymapRegistry.Mode.VISUAL_LINE, keyCode, keyChar, modifiers);
        if (action == null) return;

        Runnable custom = keymap.getCustomAction(action);
        if (custom != null) { custom.run(); return; }

        switch (action) {
            case "enter.normal" -> { saveLastVisualFromCurrentMode(); mode = Mode.NORMAL; }
            case "cursor.left"  -> moveCursor(0, -1);
            case "cursor.right" -> moveCursor(0, 1);
            case "cursor.down"  -> moveCursor(1, 0);
            case "cursor.up"    -> moveCursor(-1, 0);
            case "file.end"     -> moveFileEnd();
            case "motion.match.pair" -> jumpToMatchingBracket();
            case "scroll.page.down" -> scrollPage(true,  false);
            case "scroll.page.up"   -> scrollPage(false, false);
            case "scroll.half.down" -> scrollPage(true,  true);
            case "scroll.half.up"   -> scrollPage(false, true);
            case "yank" -> {
                int r1 = Math.min(anchorRow, cursorRow);
                int r2 = Math.max(anchorRow, cursorRow);
                yankRegister = buildLineRangeText(r1, r2);
                yankType = YankType.LINE;
                cursorRow = r1;
                cursorCol = 0;
                saveLastVisualFromCurrentMode();
                mode = Mode.NORMAL;
            }
            case "delete" -> {
                int r1 = Math.min(anchorRow, cursorRow);
                int r2 = Math.max(anchorRow, cursorRow);
                yankRegister = buildLineRangeText(r1, r2);
                yankType = YankType.LINE;
                deleteLineRange(r1, r2);
                saveLastVisualFromCurrentMode();
                mode = Mode.NORMAL;
            }
            case "clipboard.copy" -> {
                int r1 = Math.min(anchorRow, cursorRow);
                int r2 = Math.max(anchorRow, cursorRow);
                copyToSystemClipboard(buildLineRangeText(r1, r2));
                cursorRow = r1;
                cursorCol = 0;
                saveLastVisualFromCurrentMode();
                mode = Mode.NORMAL;
            }
            case "indent.right", "indent.left" -> {
                int r1 = Math.min(anchorRow, cursorRow);
                int r2 = Math.max(anchorRow, cursorRow);
                indentLines(r1, r2, action.equals("indent.left"), count);
                saveLastVisualFromCurrentMode();
                cursorRow = r1;
                moveLineStartNonBlank();
                mode = Mode.NORMAL;
                clampCursorForNormal();
            }
            case "case.lower", "case.upper", "case.toggle" -> {
                int r1 = Math.min(anchorRow, cursorRow);
                int r2 = Math.max(anchorRow, cursorRow);
                applyCaseToLines(r1, r2, caseOpFor(action));
                cursorRow = r1;
                cursorCol = 0;
                saveLastVisualFromCurrentMode();
                mode = Mode.NORMAL;
            }
            case "enter.command.visual" -> enterCommandFromVisual();
        }
    }

    // -------------------------------------------------------------------------
    // VISUAL BLOCKモード処理（矩形選択）
    // -------------------------------------------------------------------------

    private void processVisualBlockKey(int keyCode, char keyChar, int modifiers) {
        // r の2打目: キーマップ解決を経由せず、押された文字をそのまま置換文字として使う
        // （yy/dd の2打目が生キー比較で処理されるのと同じパターン。②スキル参照）
        if (pendingSequence.equals("r")) {
            pendingSequence = "";
            statusMessage = "";
            if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ') {
                replaceBlockChar(keyChar);
            }
            saveLastVisualFromCurrentMode();
            mode = Mode.NORMAL;
            clampCursorForNormal();
            return;
        }

        if (Character.isDigit(keyChar) && (keyChar != '0' || !visualCountBuffer.isEmpty())) {
            visualCountBuffer += keyChar;
            return;
        }
        int count = consumeVisualCount();

        String action = keymap.resolve(KeymapRegistry.Mode.VISUAL_BLOCK, keyCode, keyChar, modifiers);
        if (action == null) return;

        Runnable custom = keymap.getCustomAction(action);
        if (custom != null) { custom.run(); return; }

        switch (action) {
            case "enter.normal" -> { saveLastVisualFromCurrentMode(); mode = Mode.NORMAL; }
            case "cursor.left"  -> moveCursor(0, -1);
            case "cursor.right" -> moveCursor(0, 1);
            case "cursor.down"  -> moveCursor(1, 0);
            case "cursor.up"    -> moveCursor(-1, 0);
            case "motion.match.pair" -> jumpToMatchingBracket();
            case "yank" -> {
                yankRegister = buildBlockText();
                yankType = YankType.BLOCK;
                cursorRow = Math.min(anchorRow, cursorRow);
                cursorCol = Math.min(anchorCol, cursorCol);
                saveLastVisualFromCurrentMode();
                mode = Mode.NORMAL;
                clampCursorForNormal();
            }
            case "delete" -> {
                yankRegister = buildBlockText();
                yankType = YankType.BLOCK;
                deleteBlock();
                saveLastVisualFromCurrentMode();
                mode = Mode.NORMAL;
                clampCursorForNormal();
            }
            case "clipboard.copy" -> {
                copyToSystemClipboard(buildBlockText());
                cursorRow = Math.min(anchorRow, cursorRow);
                cursorCol = Math.min(anchorCol, cursorCol);
                saveLastVisualFromCurrentMode();
                mode = Mode.NORMAL;
                clampCursorForNormal();
            }
            case "block.replace.pending" -> { pendingSequence = "r"; statusMessage = "r-"; }
            case "block.insert.before" -> enterBlockInsert(false);
            case "block.insert.after"  -> enterBlockInsert(true);
            case "block.change" -> {
                int r1 = Math.min(anchorRow, cursorRow);
                int r2 = Math.max(anchorRow, cursorRow);
                int c1 = Math.min(anchorCol, cursorCol);
                yankRegister = buildBlockText();
                yankType = YankType.BLOCK;
                deleteBlock();
                beginBlockInsert(r1, r2, c1, false);
            }
            case "indent.right", "indent.left" -> {
                int r1 = Math.min(anchorRow, cursorRow);
                int r2 = Math.max(anchorRow, cursorRow);
                int c1 = Math.min(anchorCol, cursorCol);
                indentBlock(r1, r2, c1, action.equals("indent.left"), count);
                saveLastVisualFromCurrentMode();
                cursorRow = r1;
                cursorCol = c1;
                mode = Mode.NORMAL;
                clampCursorForNormal();
            }
            case "case.lower", "case.upper", "case.toggle" -> {
                applyCaseToBlock(caseOpFor(action));
                saveLastVisualFromCurrentMode();
                mode = Mode.NORMAL;
                clampCursorForNormal();
            }
            case "enter.command.visual" -> enterCommandFromVisual();
        }
    }

    /** I(before)/A(after): 矩形の左端/右端の列にカーソルを合わせINSERTモードへ入る */
    private void enterBlockInsert(boolean after) {
        int r1 = Math.min(anchorRow, cursorRow);
        int r2 = Math.max(anchorRow, cursorRow);
        int c1 = Math.min(anchorCol, cursorCol);
        int c2 = Math.max(anchorCol, cursorCol);
        int col = after ? c2 + 1 : c1;
        beginBlockInsert(r1, r2, col, after);
    }

    /**
     * 矩形挿入の共通セットアップ。r1行目を挿入対象列まで整え（pad指定時のみ空白埋め）、
     * INSERTモードへ遷移する。ESC/Ctrl+]でNORMALへ戻るとき finalizeBlockInsertIfActive() が
     * r1行に入力された文字列を r1+1〜r2 行の同じ列へ複製する。
     */
    private void beginBlockInsert(int r1, int r2, int col, boolean pad) {
        String[] lines = getLines();
        String line = r1 < lines.length ? lines[r1] : "";
        if (pad && line.length() < col) {
            buffer.insert(offsetAt(r1, line.length()), " ".repeat(col - line.length()));
        }
        blockInsertActive = true;
        blockInsertPad = pad;
        blockInsertR1 = r1;
        blockInsertR2 = r2;
        blockInsertCol = col;
        blockInsertAborted = false;
        cursorRow = r1;
        String[] paddedLines = getLines();
        int lineLen = r1 < paddedLines.length ? paddedLines[r1].length() : 0;
        cursorCol = Math.min(col, lineLen);
        blockInsertStartOffset = offsetOfCursor();
        mode = Mode.INSERT;
    }

    /**
     * 矩形挿入のINSERTモードを抜けるときに呼ぶ。r1行に入力された文字列を捕捉し、
     * r1+1〜r2行の同じ列へ複製する。Enterで複数行にまたがった場合（blockInsertAborted）は
     * 複製せず諦める（矩形挿入は単一行入力のみ対応というスコープの簡略化）。
     */
    private void finalizeBlockInsertIfActive() {
        if (!blockInsertActive) return;
        blockInsertActive = false;
        if (blockInsertAborted) return;
        int endOffset = offsetOfCursor();
        int start = Math.min(blockInsertStartOffset, endOffset);
        int end = Math.max(blockInsertStartOffset, endOffset);
        String typed = buffer.getText().substring(start, end);
        if (typed.isEmpty()) return;
        for (int row = blockInsertR1 + 1; row <= blockInsertR2; row++) {
            String[] lines = getLines();
            if (row >= lines.length) continue;
            String line = lines[row];
            if (line.length() < blockInsertCol) {
                if (!blockInsertPad) continue;
                buffer.insert(offsetAt(row, line.length()), " ".repeat(blockInsertCol - line.length()));
            }
            buffer.insert(offsetAt(row, blockInsertCol), typed);
        }
    }

    /** VISUAL BLOCK の r: 矩形範囲の各文字を指定文字で置換する（複数文字でも1文字ずつ同じ文字に） */
    private void replaceBlockChar(char ch) {
        int r1 = Math.min(anchorRow, cursorRow);
        int r2 = Math.max(anchorRow, cursorRow);
        int c1 = Math.min(anchorCol, cursorCol);
        int c2 = Math.max(anchorCol, cursorCol);
        String replacement = String.valueOf(ch);
        for (int row = r1; row <= r2; row++) {
            String[] lines = getLines();
            if (row >= lines.length) continue;
            String line = lines[row];
            int start = Math.min(c1, line.length());
            int end = Math.min(c2 + 1, line.length());
            if (start >= end) continue;
            int len = end - start;
            buffer.delete(offsetAt(row, start), len);
            buffer.insert(offsetAt(row, start), replacement.repeat(len));
        }
        cursorRow = r1;
        cursorCol = c1;
    }

    /** 矩形選択範囲（列は文字インデックス、両端含む）の各行テキストを "\n" 区切りで返す */
    private String buildBlockText() {
        int r1 = Math.min(anchorRow, cursorRow);
        int r2 = Math.max(anchorRow, cursorRow);
        int c1 = Math.min(anchorCol, cursorCol);
        int c2 = Math.max(anchorCol, cursorCol);
        String[] lines = getLines();
        StringBuilder sb = new StringBuilder();
        for (int row = r1; row <= r2; row++) {
            String line = row < lines.length ? lines[row] : "";
            int start = Math.min(c1, line.length());
            int end = Math.min(c2 + 1, line.length());
            if (row > r1) sb.append('\n');
            if (start < end) sb.append(line, start, end);
        }
        return sb.toString();
    }

    /** 矩形選択範囲を各行から削除する（行は消えない。短い行は無変更） */
    private void deleteBlock() {
        int r1 = Math.min(anchorRow, cursorRow);
        int r2 = Math.max(anchorRow, cursorRow);
        int c1 = Math.min(anchorCol, cursorCol);
        int c2 = Math.max(anchorCol, cursorCol);
        for (int row = r1; row <= r2; row++) {
            String[] lines = getLines();
            if (row >= lines.length) continue;
            String line = lines[row];
            int start = Math.min(c1, line.length());
            int end = Math.min(c2 + 1, line.length());
            if (start < end) {
                buffer.delete(offsetAt(row, start), end - start);
            }
        }
        cursorRow = r1;
        cursorCol = c1;
    }

    /**
     * 矩形ヤンクを cursorRow/insertCol から貼り付ける。
     * 行数が足りない場合は末尾に新規行を自動生成し、列が足りない行は空白で埋める。
     */
    private void pasteBlock(int insertCol) {
        String[] segs = yankRegister.split("\n", -1);
        int startRow = cursorRow;
        for (int i = 0; i < segs.length; i++) {
            int targetRow = startRow + i;
            String[] lines = getLines();
            while (targetRow >= lines.length) {
                buffer.insert(buffer.length(), "\n");
                lines = getLines();
            }
            String line = lines[targetRow];
            if (line.length() < insertCol) {
                buffer.insert(offsetAt(targetRow, line.length()),
                        " ".repeat(insertCol - line.length()));
            }
            if (!segs[i].isEmpty()) {
                buffer.insert(offsetAt(targetRow, insertCol), segs[i]);
            }
        }
        cursorRow = startRow;
        cursorCol = insertCol;
        clampCursorForNormal();
    }

    // -------------------------------------------------------------------------
    // %（対応する括弧へジャンプ）
    // -------------------------------------------------------------------------

    /**
     * カーソル位置が括弧（開き/閉じ）の上にある場合、対応する相手へカーソルを移動する。
     * 括弧の上になければ何もしない（Vim 本家は行内を前方検索して最初の括弧を探すが、
     * 本実装ではスコープを絞り「カーソルが括弧上にあるときのみ」に限定している。
     * 「既知の差分」セクション参照）。
     *
     * NORMAL では単純にカーソル移動、VISUAL/VISUAL_LINE/VISUAL_BLOCK ではアンカーを
     * 保持したまま呼び出し元がこのメソッドを呼ぶだけで選択範囲が更新される
     * （cursorRow/cursorCol を書き換えるのみで、anchorRow/anchorCol には触れないため）。
     * operator-pending（将来の d% 等）から使う場合も、offsetOfCursor() と
     * MatchPairs.findMatch() の組み合わせだけで「移動先 offset」を計算できるため、
     * このメソッドをそのまま流用するか、同じ2行をオペレータ側から呼べばよい。
     */
    private void jumpToMatchingBracket() {
        int offset = offsetOfCursor();
        MatchPairs.findMatch(buffer.getText(), offset).ifPresent(this::moveCursorToOffset);
    }

    // -------------------------------------------------------------------------
    // Visual '>' / '<' インデントシフト
    // -------------------------------------------------------------------------

    /** visualCountBuffer を読み取って count（未指定なら1）を返し、バッファをリセットする。 */
    private int consumeVisualCount() {
        if (visualCountBuffer.isEmpty()) return 1;
        int count = Integer.parseInt(visualCountBuffer);
        visualCountBuffer = "";
        return count;
    }

    /** normalCountBuffer を読み取って count（未指定なら1）を返し、バッファをリセットする（r 専用）。 */
    private int consumeNormalCount() {
        if (normalCountBuffer.isEmpty()) return 1;
        int count = Integer.parseInt(normalCountBuffer);
        normalCountBuffer = "";
        return count;
    }

    /** r1〜r2行（両端含む）を行頭から count*shiftwidth 分シフトする（charwise/linewise 共通）。 */
    private void indentLines(int r1, int r2, boolean left, int count) {
        for (int row = r1; row <= r2; row++) {
            String[] lines = getLines();
            if (row >= lines.length) continue;
            String line = lines[row];
            String shifted = Indenter.shiftLine(line, count, left, indentSettings);
            if (!shifted.equals(line)) {
                int lineStart = offsetAt(row, 0);
                buffer.delete(lineStart, line.length());
                buffer.insert(lineStart, shifted);
            }
        }
    }

    /**
     * VISUAL BLOCK 専用のインデントシフト。行全体ではなく、矩形の左端列(c1)だけを
     * 基準にシフトする（CLAUDE.md の方針どおり、この挙動は本家Vimの「blockwiseでも
     * linewiseと同じく行全体をシフトする」挙動とは異なる、本プロジェクト独自の
     * 明示的な仕様。詳細は「既知の差分」参照）。
     * 右シフトは c1 位置に count*shiftwidth 分のインデントを挿入する。
     * 左シフトは c1 の直前にある空白文字を count*shiftwidth 分だけ取り除く。
     */
    private void indentBlock(int r1, int r2, int c1, boolean left, int count) {
        int deltaWidth = indentSettings.getShiftwidth() * count;
        for (int row = r1; row <= r2; row++) {
            String[] lines = getLines();
            if (row >= lines.length) continue;
            String line = lines[row];
            if (left) {
                int col = Math.min(c1, line.length());
                int removedWidth = 0;
                int start = col;
                while (start > 0 && removedWidth < deltaWidth) {
                    char ch = line.charAt(start - 1);
                    if (ch != ' ' && ch != '\t') break;
                    removedWidth += (ch == '\t') ? indentSettings.getTabstop() : 1;
                    start--;
                }
                if (start < col) {
                    buffer.delete(offsetAt(row, start), col - start);
                }
            } else {
                if (line.isBlank()) continue; // 空行は右シフトで変更しない
                if (line.length() < c1) continue; // ブロック開始列に届かない行は対象外
                String ins = Indenter.buildIndent(deltaWidth, indentSettings);
                buffer.insert(offsetAt(row, c1), ins);
            }
        }
    }

    /** テスト・将来の設定UIから shiftwidth/tabstop/expandtab/shiftround を変更するための入口。 */
    public IndentSettings getIndentSettings() {
        return indentSettings;
    }

    // -------------------------------------------------------------------------
    // 大文字小文字変換（~ / gu / gU / g~ / Visual u / U / ~）
    // -------------------------------------------------------------------------

    /** KeymapRegistry のアクション名から CaseOp を決定する（VISUAL系3モードで共用）。 */
    private CaseOp caseOpFor(String action) {
        return switch (action) {
            case "case.lower" -> CaseOp.LOWER;
            case "case.upper" -> CaseOp.UPPER;
            default -> CaseOp.TOGGLE; // "case.toggle"
        };
    }

    private String convertCase(String s, CaseOp op) {
        return switch (op) {
            case UPPER -> s.toUpperCase(Locale.ROOT);
            case LOWER -> s.toLowerCase(Locale.ROOT);
            case TOGGLE -> {
                StringBuilder sb = new StringBuilder(s.length());
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (Character.isUpperCase(c)) sb.append(Character.toLowerCase(c));
                    else if (Character.isLowerCase(c)) sb.append(Character.toUpperCase(c));
                    else sb.append(c);
                }
                yield sb.toString();
            }
        };
    }

    /** NORMAL `~`: カーソル位置の1文字を toggle case し、カーソルを1つ右へ進める（Vim既定の 'notildeop' 相当）。 */
    private void toggleCaseUnderCursor() {
        String[] lines = getLines();
        if (cursorRow >= lines.length) return;
        String line = lines[cursorRow];
        if (cursorCol >= line.length()) return;
        char c = line.charAt(cursorCol);
        String converted = convertCase(String.valueOf(c), CaseOp.TOGGLE);
        if (!converted.equals(String.valueOf(c))) {
            int offset = offsetAt(cursorRow, cursorCol);
            buffer.delete(offset, 1);
            buffer.insert(offset, converted);
        }
        cursorCol = Math.min(cursorCol + 1, Math.max(0, line.length() - 1));
    }

    /**
     * NORMAL の r: カーソル位置から count 文字を ch で一括置換する（count省略時は1）。
     * 行末までの残り文字数を超える場合は何もしない（Vim の r と同じ、無効操作として中断）。
     * カーソルは置換した最後の文字の位置に残す（INSERT へは遷移しない）。
     */
    private void replaceCharAtCursor(char ch, int count) {
        String[] lines = getLines();
        if (cursorRow >= lines.length) return;
        String line = lines[cursorRow];
        if (cursorCol + count > line.length()) return;
        int offset = offsetAt(cursorRow, cursorCol);
        buffer.delete(offset, count);
        buffer.insert(offset, String.valueOf(ch).repeat(count));
        cursorCol = cursorCol + count - 1;
    }

    /**
     * r1〜r2 行（両端含む）の行全体を大文字小文字変換する。VISUAL_LINE の u/U/~ と、
     * NORMAL の guu/gUU/g~~（doubled-letter で現在行のみに適用）の共通実装。
     * indentLines() と同じ「行ごとに getLines() を取り直してから delete+insert する」パターン。
     */
    private void applyCaseToLines(int r1, int r2, CaseOp op) {
        for (int row = r1; row <= r2; row++) {
            String[] lines = getLines();
            if (row >= lines.length) continue;
            String line = lines[row];
            String converted = convertCase(line, op);
            if (!converted.equals(line)) {
                int lineStart = offsetAt(row, 0);
                buffer.delete(lineStart, line.length());
                buffer.insert(lineStart, converted);
            }
        }
    }

    /** VISUAL（文字単位）の u/U/~: 選択範囲を変換し、カーソルを選択開始位置へ戻す（getSelectedText と同じ範囲規約）。 */
    private void applyCaseToSelection(CaseOp op) {
        int o1 = offsetAt(anchorRow, anchorCol);
        int o2 = offsetOfCursor();
        int start = Math.min(o1, o2);
        int end = Math.max(o1, o2);
        if (end < buffer.length()) {
            end = Math.min(end + 1, buffer.length());
        }
        String text = buffer.getText().substring(start, end);
        String converted = convertCase(text, op);
        if (!converted.equals(text)) {
            buffer.delete(start, end - start);
            buffer.insert(start, converted);
        }
        moveCursorToOffset(start);
    }

    /** VISUAL BLOCK の u/U/~: 矩形の列範囲(c1〜c2、両端含む)だけを行ごとに変換する（replaceBlockChar と同型）。 */
    private void applyCaseToBlock(CaseOp op) {
        int r1 = Math.min(anchorRow, cursorRow);
        int r2 = Math.max(anchorRow, cursorRow);
        int c1 = Math.min(anchorCol, cursorCol);
        int c2 = Math.max(anchorCol, cursorCol);
        for (int row = r1; row <= r2; row++) {
            String[] lines = getLines();
            if (row >= lines.length) continue;
            String line = lines[row];
            int start = Math.min(c1, line.length());
            int end = Math.min(c2 + 1, line.length());
            if (start >= end) continue;
            String seg = line.substring(start, end);
            String converted = convertCase(seg, op);
            if (!converted.equals(seg)) {
                buffer.delete(offsetAt(row, start), end - start);
                buffer.insert(offsetAt(row, start), converted);
            }
        }
        cursorRow = r1;
        cursorCol = c1;
    }

    // -------------------------------------------------------------------------
    // gv（直前の Visual 選択の再選択）
    // -------------------------------------------------------------------------

    /** 現在の Visual 系モードのアンカー・カーソル・種別を「直前の Visual 選択」として保存する。 */
    private void saveLastVisualFromCurrentMode() {
        VisualKind kind = switch (mode) {
            case VISUAL -> VisualKind.CHAR;
            case VISUAL_LINE -> VisualKind.LINE;
            case VISUAL_BLOCK -> VisualKind.BLOCK;
            default -> null;
        };
        if (kind == null) return;
        lastVisualValid = true;
        lastVisualKind = kind;
        lastVisualAnchorRow = anchorRow;
        lastVisualAnchorCol = anchorCol;
        lastVisualCursorRow = cursorRow;
        lastVisualCursorCol = cursorCol;
    }

    /** Visual系モードで ':' を押した時: 選択範囲を保存し "'<,'>" 入力済みの COMMAND モードへ入る。 */
    private void enterCommandFromVisual() {
        saveLastVisualFromCurrentMode();
        commandBuffer.setLength(0);
        commandBuffer.append("'<,'>");
        mode = Mode.COMMAND;
    }

    /** gv: 直前の Visual 選択（範囲・種別）を復元する。未保存なら何もしない。 */
    private void restoreLastVisual() {
        if (!lastVisualValid) return;
        String[] lines = getLines();
        int maxRow = Math.max(0, lines.length - 1);
        anchorRow = Math.min(lastVisualAnchorRow, maxRow);
        cursorRow = Math.min(lastVisualCursorRow, maxRow);
        int anchorLineLen = anchorRow < lines.length ? lines[anchorRow].length() : 0;
        int cursorLineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
        anchorCol = Math.min(lastVisualAnchorCol, Math.max(0, anchorLineLen - 1));
        cursorCol = Math.min(lastVisualCursorCol, Math.max(0, cursorLineLen - 1));
        mode = switch (lastVisualKind) {
            case CHAR -> Mode.VISUAL;
            case LINE -> Mode.VISUAL_LINE;
            case BLOCK -> Mode.VISUAL_BLOCK;
        };
    }

    // -------------------------------------------------------------------------
    // マクロ記録・再生（vim-macro-recording skill 参照）
    // -------------------------------------------------------------------------

    /** register: 小文字なら新規記録、大文字なら既存内容への追記。 */
    private void startMacroRecording(char register) {
        char reg = Character.toLowerCase(register);
        macroRecordBuffer.clear();
        if (Character.isUpperCase(register)) {
            List<RecordedKey> existing = macroRegisters.get(reg);
            if (existing != null) macroRecordBuffer.addAll(existing);
        }
        macroRecording = true;
        macroRecordingRegister = reg;
        statusMessage = "recording @" + reg;
    }

    private void stopMacroRecording() {
        macroRegisters.put(macroRecordingRegister, List.copyOf(macroRecordBuffer));
        macroRecording = false;
        statusMessage = "";
    }

    private void playMacro(char register) {
        List<RecordedKey> keys = macroRegisters.get(register);
        if (keys == null || keys.isEmpty()) {
            statusMessage = "レジスタ " + register + " は空です";
            return;
        }
        lastPlayedMacroRegister = register;
        executeMacroKeys(keys);
    }

    private void replayLastMacro() {
        if (lastPlayedMacroRegister == '\0') {
            statusMessage = "直前に実行したマクロがありません";
            return;
        }
        playMacro(lastPlayedMacroRegister);
    }

    /** 記録済みキー列を processKey() へ再投入して再生する。無限再帰は深さ上限で打ち切る。 */
    private void executeMacroKeys(List<RecordedKey> keys) {
        if (macroReplayDepth >= MACRO_MAX_REPLAY_DEPTH) {
            statusMessage = "マクロの再帰が深すぎます（中断しました）";
            return;
        }
        macroReplayDepth++;
        try {
            for (RecordedKey k : keys) {
                processKey(k.keyCode(), k.keyChar(), k.modifiers());
            }
        } finally {
            macroReplayDepth--;
        }
    }

    // -------------------------------------------------------------------------
    // カーソル移動
    // -------------------------------------------------------------------------

    /** keyCode または keyChar のどちらかが期待値と一致すれば true。 */
    private static boolean matches(int keyCode, char keyChar, int expectedCode, char expectedChar) {
        if (keyCode != KeyEvent.VK_UNDEFINED && keyCode == expectedCode) return true;
        return keyChar != KeyEvent.CHAR_UNDEFINED && keyChar == expectedChar;
    }

    private void moveCursor(int dRow, int dCol) {
        String[] lines = getLines();
        boolean isInsert = (mode == Mode.INSERT);
        if (dRow != 0) {
            int newRow = Math.max(0, Math.min(cursorRow + dRow, lines.length - 1));
            int newLineLen = newRow < lines.length ? lines[newRow].length() : 0;
            int maxCol = isInsert ? newLineLen : Math.max(0, newLineLen - 1);
            cursorRow = newRow;
            cursorCol = Math.min(cursorCol, maxCol);
        } else {
            int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
            int maxCol = isInsert ? lineLen : Math.max(0, lineLen - 1);
            cursorCol = Math.max(0, Math.min(cursorCol + dCol, maxCol));
        }
    }

    private void clampCursorForNormal() {
        String[] lines = getLines();
        int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
        if (lineLen > 0) {
            cursorCol = Math.min(cursorCol, lineLen - 1);
        } else {
            cursorCol = 0;
        }
    }

    private void clampCursorAfterUndoRedo() {
        String[] lines = getLines();
        cursorRow = Math.min(cursorRow, Math.max(0, lines.length - 1));
        int lineLen = (cursorRow < lines.length) ? lines[cursorRow].length() : 0;
        cursorCol = Math.min(cursorCol, Math.max(0, lineLen - 1));
    }

    // -------------------------------------------------------------------------
    // オフセット計算
    // -------------------------------------------------------------------------

    public int offsetAt(int row, int col) {
        String[] lines = getLines();
        int offset = 0;
        for (int i = 0; i < row && i < lines.length; i++) {
            offset += lines[i].length() + 1; // +1 は改行文字
        }
        int lineLen = row < lines.length ? lines[row].length() : 0;
        return offset + Math.min(col, lineLen);
    }

    private int offsetOfCursor() {
        return offsetAt(cursorRow, cursorCol);
    }

    // 軽量化リファクタリング Phase 2: 当初 syncCanvas() 内の2箇所のみを対象にする想定だったが、
    // 実測（EditorRenderPerfTest）により getLines() こそがカーソル移動のたびに呼ばれる支配的な
    // ホットパスであることが判明した（本メソッドは moveCursor() 等から69箇所で呼ばれ、
    // 呼ばれるたびに buffer.getText().split("\n", -1) で文書全体を再構築していた）。
    // syncCanvas() と全く同じキャッシュ（canvasTextOwner/canvasTextVersion）を経由させることで、
    // canvas の有無に関わらず「テキスト未変更なら再構築ゼロ」という Phase 2 の目標をこの経路にも適用する。
    private String[] getLines() {
        refreshCanvasTextCache();
        return canvasCachedLines;
    }

    // -------------------------------------------------------------------------
    // VISUALモード（文字単位）ヘルパー
    // -------------------------------------------------------------------------

    private String getSelectedText() {
        int o1 = offsetAt(anchorRow, anchorCol);
        int o2 = offsetOfCursor();
        int start = Math.min(o1, o2);
        int end = Math.max(o1, o2);
        if (end < buffer.length()) {
            end = Math.min(end + 1, buffer.length());
        }
        return buffer.getText().substring(start, end);
    }

    private void deleteSelected() {
        int o1 = offsetAt(anchorRow, anchorCol);
        int o2 = offsetOfCursor();
        int start = Math.min(o1, o2);
        int end = Math.max(o1, o2);
        if (end < buffer.length()) {
            end = Math.min(end + 1, buffer.length());
        }
        buffer.delete(start, end - start);
        moveCursorToOffset(start);
    }

    // -------------------------------------------------------------------------
    // VISUAL LINE / dd / yy 行単位ヘルパー
    // -------------------------------------------------------------------------

    /** r1〜r2 行（両端含む）のテキストを "\n" 区切りで返す。各行末に \n を付与する */
    private String buildLineRangeText(int r1, int r2) {
        String[] lines = getLines();
        StringBuilder sb = new StringBuilder();
        for (int i = r1; i <= r2 && i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /** 現在行をヤンクレジスタに保存する（行末 \n 付き）*/
    private void yankCurrentLine() {
        String[] lines = getLines();
        if (cursorRow >= lines.length) return;
        yankRegister = lines[cursorRow] + "\n";
        yankType = YankType.LINE;
    }

    /** 現在行を削除してヤンクレジスタに保存する */
    private void deleteCurrentLine() {
        yankCurrentLine();
        String[] lines = getLines();
        int lineStart = offsetAt(cursorRow, 0);
        int lineLen = lines[cursorRow].length();

        if (cursorRow < lines.length - 1) {
            // 最終行でない: 行テキスト + 末尾 \n を削除
            buffer.delete(lineStart, lineLen + 1);
        } else if (cursorRow > 0) {
            // 最終行かつ他の行がある: 直前の \n も含めて削除
            buffer.delete(lineStart - 1, lineLen + 1);
            cursorRow--;
        } else {
            // ドキュメント唯一の行
            buffer.delete(lineStart, lineLen);
        }

        String[] newLines = getLines();
        cursorRow = Math.min(cursorRow, Math.max(0, newLines.length - 1));
        int newLineLen = cursorRow < newLines.length ? newLines[cursorRow].length() : 0;
        cursorCol = Math.min(cursorCol, Math.max(0, newLineLen - 1));
        if (cursorCol < 0) cursorCol = 0;
    }

    /** カーソル位置から行末までを削除してヤンクレジスタに保存する（Vim の D 相当） */
    private void deleteToEndOfLine() {
        String[] lines = getLines();
        if (cursorRow >= lines.length) return;
        String line = lines[cursorRow];
        if (cursorCol >= line.length()) return;
        String deleted = line.substring(cursorCol);
        yankRegister = deleted;
        yankType = YankType.CHAR;
        buffer.delete(offsetOfCursor(), deleted.length());
        cursorCol = Math.max(0, cursorCol - 1);
    }

    /** r1〜r2 行（両端含む）を削除する。カーソルを r1 にリセットする */
    private void deleteLineRange(int r1, int r2) {
        String[] lines = getLines();

        int deleteStart;
        int deleteLength;
        if (r2 < lines.length - 1) {
            // 最終行に届かない: r1〜r2 の行テキスト + 各末尾 \n を削除
            deleteStart = offsetAt(r1, 0);
            int deleteEnd = offsetAt(r2 + 1, 0);
            deleteLength = deleteEnd - deleteStart;
        } else if (r1 > 0) {
            // 最終行まで含み、前行がある: 直前の \n も含めて削除
            deleteStart = offsetAt(r1, 0) - 1;
            deleteLength = buffer.length() - deleteStart;
        } else {
            // すべての行を削除
            deleteStart = 0;
            deleteLength = buffer.length();
        }

        buffer.delete(deleteStart, deleteLength);

        String[] newLines = getLines();
        cursorRow = Math.min(r1, Math.max(0, newLines.length - 1));
        cursorCol = 0;
        clampCursorForNormal();
    }

    // -------------------------------------------------------------------------
    // ペースト（p / P）
    // -------------------------------------------------------------------------

    private void pasteAfter() {
        if (yankRegister.isEmpty()) return;
        if (yankType == YankType.LINE) {
            pasteLineAfter();
        } else if (yankType == YankType.BLOCK) {
            pasteBlock(cursorCol + 1);
        } else {
            pasteCharAfter();
        }
    }

    private void pasteBefore() {
        if (yankRegister.isEmpty()) return;
        if (yankType == YankType.LINE) {
            pasteLineBefore();
        } else if (yankType == YankType.BLOCK) {
            pasteBlock(cursorCol);
        } else {
            pasteCharBefore();
        }
    }

    private void pasteCharAfter() {
        int offset = Math.min(offsetOfCursor() + 1, buffer.length());
        buffer.insert(offset, yankRegister);
        int newOffset = offset + yankRegister.length() - 1;
        moveCursorToOffset(newOffset);
        clampCursorForNormal();
    }

    private void pasteCharBefore() {
        int currentOffset = offsetOfCursor();
        buffer.insert(currentOffset, yankRegister);
        int newOffset = currentOffset + yankRegister.length() - 1;
        moveCursorToOffset(newOffset);
        clampCursorForNormal();
    }

    // -------------------------------------------------------------------------
    // システムクリップボード連携（Ctrl+Shift+C / Ctrl+Shift+V）
    // -------------------------------------------------------------------------

    /** 内部ヤンクレジスタとは独立に、指定テキストをOSのシステムクリップボードへコピーする。 */
    private void copyToSystemClipboard(String text) {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(text), null);
            statusMessage = text.length() + " bytes copied to clipboard";
        } catch (Exception e) {
            statusMessage = "E: clipboard copy failed: " + e.getMessage();
        }
    }

    /**
     * システムクリップボードの内容をカーソル位置へ貼り付ける。文字列（stringFlavor）が
     * 取得できればそのまま挿入する。ファイルマネージャ等でコピーしたファイル（javaFileListFlavor）
     * の場合は絶対パスを1行1件で挿入する。画像（imageFlavor）・音声等の非テキストデータの場合は、
     * ストリーム系 DataFlavor またはImageからエンコードした生バイト列を、ISO-8859-1
     * （1バイト=1文字の可逆マッピング）でデコードしてバイト列そのものをバッファへ挿入する
     * （getBytes(ISO_8859_1)で元のバイト列を復元可能）。
     *
     * @param asNormalMode true の場合 NORMAL モードと同じカーソルクランプを行う（P相当）。
     *                     false の場合 INSERT モード中の挿入として扱い、クランプしない。
     */
    private void pasteFromSystemClipboard(boolean asNormalMode) {
        Transferable contents;
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            contents = clipboard.getContents(null);
        } catch (Exception e) {
            // ヘッドレス環境等、システムクリップボードにそもそもアクセスできない場合
            statusMessage = "E: clipboard unavailable: " + e.getMessage();
            return;
        }
        if (contents == null) {
            statusMessage = "clipboard is empty";
            return;
        }
        String text;
        try {
            if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                text = (String) contents.getTransferData(DataFlavor.stringFlavor);
            } else if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                text = readClipboardFilePaths(contents);
            } else {
                byte[] bytes = readClipboardBinary(contents);
                if (bytes == null) {
                    statusMessage = "unsupported clipboard content";
                    return;
                }
                text = new String(bytes, StandardCharsets.ISO_8859_1);
            }
        } catch (UnsupportedFlavorException | IOException e) {
            statusMessage = "E: clipboard paste failed: " + e.getMessage();
            return;
        }
        if (text.isEmpty()) return;

        int offset = offsetOfCursor();
        buffer.insert(offset, text);
        moveCursorToOffset(offset + text.length());
        if (asNormalMode) {
            clampCursorForNormal();
        }
    }

    /** ファイルマネージャ等でコピーされたファイル一覧を、絶対パスを改行区切りにした文字列へ変換する。 */
    @SuppressWarnings("unchecked")
    private String readClipboardFilePaths(Transferable contents) throws UnsupportedFlavorException, IOException {
        List<File> files = (List<File>) contents.getTransferData(DataFlavor.javaFileListFlavor);
        return ClipboardBinaryCodec.joinFilePaths(files);
    }

    /**
     * 文字列・ファイル一覧以外の DataFlavor（image/audio 等）から生バイト列を読み出す。
     * ストリーム系 DataFlavor を優先し、見つからなければ imageFlavor（java.awt.Image、
     * スクリーンショットツール等が公開する非ストリーム形式）をPNGへエンコードして返す。
     * いずれも取得不能なら null。
     */
    private byte[] readClipboardBinary(Transferable contents) throws UnsupportedFlavorException, IOException {
        for (DataFlavor flavor : contents.getTransferDataFlavors()) {
            if (!InputStream.class.isAssignableFrom(flavor.getRepresentationClass())) continue;
            try (InputStream in = (InputStream) contents.getTransferData(flavor)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                return out.toByteArray();
            }
        }
        if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            Object data = contents.getTransferData(DataFlavor.imageFlavor);
            if (data instanceof Image image) {
                return ClipboardBinaryCodec.encodeImageAsPng(image);
            }
        }
        return null;
    }

    /** 行ヤンク: カーソル行の下に貼り付け、カーソルを貼り付け行へ移動 */
    private void pasteLineAfter() {
        String[] lines = getLines();
        boolean isLastLine = (cursorRow == lines.length - 1);
        String content = yankRegister.endsWith("\n")
                ? yankRegister
                : yankRegister + "\n";

        if (!isLastLine) {
            int nextLineStart = offsetAt(cursorRow + 1, 0);
            buffer.insert(nextLineStart, content);
        } else {
            // 最終行: 末尾に "\n" + 行テキスト（末尾 \n なし）を追加
            String withoutTrailingNewline = content.substring(0, content.length() - 1);
            buffer.insert(buffer.length(), "\n" + withoutTrailingNewline);
        }
        cursorRow++;
        cursorCol = 0;
    }

    /** 行ヤンク: カーソル行の上に貼り付け、カーソルを貼り付け行へ移動 */
    private void pasteLineBefore() {
        int lineStart = offsetAt(cursorRow, 0);
        String content = yankRegister.endsWith("\n")
                ? yankRegister
                : yankRegister + "\n";
        buffer.insert(lineStart, content);
        // cursorRow はそのまま（貼り付け行がカーソル行になる）
        cursorCol = 0;
    }

    // -------------------------------------------------------------------------
    // 1文字削除・カーソルオフセット変換
    // -------------------------------------------------------------------------

    private void deleteCharAtCursor() {
        String[] lines = getLines();
        int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
        if (lineLen == 0) return;
        buffer.delete(offsetOfCursor(), 1);
        clampCursorForNormal();
    }

    // -------------------------------------------------------------------------
    // 単語・行・ファイル単位の移動
    // -------------------------------------------------------------------------

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void moveWordForward() {
        String text = buffer.getText();
        int len = text.length();
        int offset = offsetOfCursor();
        // 現在位置の単語文字をスキップ
        while (offset < len && isWordChar(text.charAt(offset))) offset++;
        // 空白・記号をスキップして次の単語先頭へ
        while (offset < len && !isWordChar(text.charAt(offset)) && text.charAt(offset) != '\n') offset++;
        moveCursorToOffset(offset);
    }

    private void moveWordBackward() {
        String text = buffer.getText();
        int offset = offsetOfCursor();
        if (offset == 0) return;
        offset--;
        // 空白・記号を後退スキップ
        while (offset > 0 && !isWordChar(text.charAt(offset))) offset--;
        // 単語文字を後退スキップして単語先頭へ
        while (offset > 0 && isWordChar(text.charAt(offset - 1))) offset--;
        moveCursorToOffset(offset);
    }

    private void moveWordEnd() {
        String text = buffer.getText();
        int len = text.length();
        int offset = offsetOfCursor();
        if (offset >= len - 1) return;
        offset++;
        // 空白・記号をスキップ
        while (offset < len && !isWordChar(text.charAt(offset))) offset++;
        // 単語末尾へ
        while (offset < len - 1 && isWordChar(text.charAt(offset + 1))) offset++;
        moveCursorToOffset(offset);
    }

    private void moveLineStart() {
        cursorCol = 0;
    }

    private void moveLineStartNonBlank() {
        String[] lines = getLines();
        String line = cursorRow < lines.length ? lines[cursorRow] : "";
        int col = 0;
        while (col < line.length() && (line.charAt(col) == ' ' || line.charAt(col) == '\t')) col++;
        cursorCol = col;
    }

    private void moveLineEnd() {
        String[] lines = getLines();
        int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
        boolean isInsert = (mode == Mode.INSERT);
        cursorCol = isInsert ? lineLen : Math.max(0, lineLen - 1);
    }

    private void moveFileStart() {
        cursorRow = 0;
        cursorCol = 0;
    }

    private void moveFileEnd() {
        String[] lines = getLines();
        cursorRow = Math.max(0, lines.length - 1);
        int lineLen = lines[cursorRow].length();
        boolean isInsert = (mode == Mode.INSERT);
        cursorCol = isInsert ? lineLen : Math.max(0, lineLen - 1);
    }

    /**
     * Ctrl+F/B (full page) または Ctrl+D/U (half page) スクロール。
     * Vim の挙動: スクロール量だけ scrollRow とカーソル行を同時に移動し、
     * カーソルが画面外に出た場合は画面端の行に補正する。
     *
     * @param down  true=下方向, false=上方向
     * @param half  true=半ページ, false=1ページ
     */
    private void scrollPage(boolean down, boolean half) {
        int pageSize = getVisibleRows();
        int amount = half ? Math.max(1, pageSize / 2) : Math.max(1, pageSize);
        int totalLines = getLines().length;

        if (down) {
            int newScrollRow = Math.min(canvas != null
                    ? canvas.getScrollRow() + amount
                    : cursorRow,
                Math.max(0, totalLines - 1));
            cursorRow = Math.min(cursorRow + amount, totalLines - 1);
            if (canvas != null) canvas.setScrollRow(newScrollRow);
        } else {
            int newScrollRow = Math.max(0,
                canvas != null ? canvas.getScrollRow() - amount : cursorRow - amount);
            cursorRow = Math.max(0, cursorRow - amount);
            if (canvas != null) canvas.setScrollRow(newScrollRow);
        }
        // カーソル列をその行の範囲内に収める
        String[] lines = getLines();
        int lineLen = lines[cursorRow].length();
        cursorCol = Math.min(cursorCol, Math.max(0, lineLen - 1));
        syncCanvas();
    }

    /**
     * Ctrl+E/Y: 画面を n 行スクロールするが、カーソル行番号は変えない。
     * カーソルが画面外に押し出された場合は画面端に補正する。
     *
     * @param lines 正=下方向（画面が下へ流れる）、負=上方向
     */
    private void scrollLines(int lines) {
        if (canvas == null) return;
        int newScrollRow = Math.max(0, canvas.getScrollRow() + lines);
        canvas.setScrollRow(newScrollRow);
        int visibleRows = getVisibleRows();
        // カーソルが画面外に出た場合は画面端に補正
        if (cursorRow < newScrollRow) {
            cursorRow = newScrollRow;
        } else if (cursorRow >= newScrollRow + visibleRows) {
            cursorRow = newScrollRow + visibleRows - 1;
        }
        String[] docLines = getLines();
        cursorRow = Math.max(0, Math.min(cursorRow, docLines.length - 1));
        int lineLen = docLines[cursorRow].length();
        cursorCol = Math.min(cursorCol, Math.max(0, lineLen - 1));
        syncCanvas();
    }

    /**
     * H/M/L: 現在の表示範囲内の特定行へカーソルを移動する。
     *
     * @param pos 0=先頭行(H), 1=中央行(M), 2=末尾行(L)
     */
    private void jumpToScreenRow(int pos) {
        int scrollRow = canvas != null ? canvas.getScrollRow() : 0;
        int visibleRows = getVisibleRows();
        int totalLines = getLines().length;
        int lastVisible = Math.min(scrollRow + visibleRows - 1, totalLines - 1);

        cursorRow = switch (pos) {
            case 0 -> scrollRow;
            case 1 -> (scrollRow + lastVisible) / 2;
            default -> lastVisible;
        };
        String[] docLines = getLines();
        int lineLen = docLines[cursorRow].length();
        cursorCol = Math.min(cursorCol, Math.max(0, lineLen - 1));
        syncCanvas();
    }

    /**
     * zz: カーソル行を viewport の垂直中央付近に表示する。カーソルの論理位置（行・列）は変更しない。
     * ファイル先頭付近では scrollRow を 0 にクランプするが、ファイル末尾付近では上限クランプを行わない
     * （Vim本家のzzと同じ挙動。文書末尾を超えた範囲は EditorCanvas 側が空白領域として描画する）。
     */
    private void centerCursorLineInViewport() {
        if (canvas == null) return;
        int visibleRows = getVisibleRows();
        int newScrollRow = Math.max(0, cursorRow - visibleRows / 2);
        canvas.setScrollRow(newScrollRow);
    }

    /** 表示可能行数を返す（canvas がなければ仮の値 40）。 */
    private int getVisibleRows() {
        return canvas != null ? canvas.getVisibleRows() : 40;
    }

    private void moveCursorToOffset(int offset) {
        String[] lines = getLines();
        int pos = 0;
        for (int i = 0; i < lines.length; i++) {
            int lineEnd = pos + lines[i].length();
            if (offset <= lineEnd) {
                cursorRow = i;
                cursorCol = offset - pos;
                return;
            }
            pos = lineEnd + 1;
        }
        cursorRow = Math.max(0, lines.length - 1);
        cursorCol = lines[cursorRow].length();
    }

    // -------------------------------------------------------------------------
    // GUI同期
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // FILERモード処理（:cd 実行後に表示されるディレクトリ一覧。\f/\g/telescope と同じ
    // 「ヘッダ行＋結果1行ずつ」の疑似バッファ表示。EditorCanvas のオーバーレイは使わない）
    // -------------------------------------------------------------------------

    /** ディレクトリ移動先の一覧を取得し直し、疑似バッファを再描画する（ディレクトリ間の再帰移動でも呼ぶため保存は行わない）。 */
    private void enterFiler() {
        try {
            filerEntries = DirectoryLister.listDirectoryEntries(getProjectRoot());
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
            filerEntries = List.of();
        }
        filerFiltered = filerEntries;
        filerSelectedIdx = 0;
        filerSearchMode = false;
        filerQuery.setLength(0);
        mode = Mode.FILER;
        renderFilerBuffer();
    }

    /**
     * filerFiltered/filerSelectedIdx の現在値を \f（*file-search*）・\g（*grep*）・telescope と同じ
     * 「ヘッダ行＋結果1行ずつ」の疑似バッファ形式で buffer に描画する。
     * 選択中の候補は専用のハイライトではなく、cursorRow をその行に合わせることで示す。
     */
    private void renderFilerBuffer() {
        Path root = getProjectRoot();
        StringBuilder sb = new StringBuilder();
        sb.append("*filer* ").append(root.toString().replace('\\', '/'));
        if (filerSearchMode) sb.append(" /").append(filerQuery);
        sb.append(" — ").append(filerFiltered.size()).append("件\n");
        for (DirEntry e : filerFiltered) {
            sb.append(e.kind() == DirEntry.Kind.DIRECTORY ? e.name() + "/" : e.name()).append('\n');
        }
        buffer = new UndoablePieceTable(sb.toString());
        currentFilePath = null;
        grepResults = null;
        fileNameResults = null;
        clearSearchHighlights();
        cursorRow = filerFiltered.isEmpty() ? 0 : filerSelectedIdx + 1;
        cursorCol = 0;
    }

    /**
     * 先頭の {@code ~} をホームディレクトリに展開する。OSに関係なく {@code ~}・{@code ~/...}・{@code ~\...} を認識する。
     * {@code Path.resolve()} は絶対パスを渡すとそれをそのまま返す仕様のため、展開後は resolve に委ねてよい。
     */
    private static String expandHome(String pathStr) {
        if (pathStr.equals("~")) {
            return System.getProperty("user.home", "");
        }
        if (pathStr.startsWith("~/") || pathStr.startsWith("~\\")) {
            return System.getProperty("user.home", "") + File.separator + pathStr.substring(2);
        }
        return pathStr;
    }

    private void changeDirectory(String pathStr) {
        try {
            pathStr = expandHome(pathStr);
            Path target = getProjectRoot().resolve(pathStr).toAbsolutePath().normalize();
            if (changeWdCallback == null) {
                statusMessage = "E: working directory handler not set";
                return;
            }
            String err = changeWdCallback.apply(target);
            if (err != null) {
                statusMessage = "E: " + err;
                return;
            }
            filerSavedBuffer = buffer;
            filerSavedFilePath = currentFilePath;
            filerSavedCursorRow = cursorRow;
            filerSavedCursorCol = cursorCol;
            enterFiler();
        } catch (Exception ex) {
            statusMessage = "E: " + ex.getMessage();
        }
    }

    /** FILER セッションを終了し、changeDirectory() で退避した元バッファに戻す。 */
    private void exitFiler() {
        mode = Mode.NORMAL;
        buffer = filerSavedBuffer != null ? filerSavedBuffer : new UndoablePieceTable("");
        currentFilePath = filerSavedFilePath;
        cursorRow = filerSavedCursorRow;
        cursorCol = filerSavedCursorCol;
        filerSavedBuffer = null;
        filerSavedFilePath = null;
    }

    private void processFilerKey(int keyCode, char keyChar, int modifiers) {
        boolean ctrlDown = (modifiers & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;

        if (filerSearchMode) {
            if (keyCode == KeyEvent.VK_ESCAPE) {
                filerSearchMode = false;
                filerQuery.setLength(0);
                filerFiltered = filerEntries;
                filerSelectedIdx = 0;
                renderFilerBuffer();
                return;
            }
            if (keyCode == KeyEvent.VK_ENTER) {
                openSelectedEntry();
                return;
            }
            if (keyCode == KeyEvent.VK_BACK_SPACE) {
                if (filerQuery.length() > 0) {
                    filerQuery.deleteCharAt(filerQuery.length() - 1);
                    filerFiltered = DirectoryLister.filterEntries(filerEntries, filerQuery.toString());
                    filerSelectedIdx = 0;
                    renderFilerBuffer();
                }
                return;
            }
            // 自由入力(検索クエリ)があるため j/k は文字入力として扱い、移動キーには割り当てない。
            if ((ctrlDown && keyCode == KeyEvent.VK_N) || keyCode == KeyEvent.VK_DOWN) { moveSelection(1);  return; }
            if ((ctrlDown && keyCode == KeyEvent.VK_P) || keyCode == KeyEvent.VK_UP)   { moveSelection(-1); return; }
            if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ' && !ctrlDown) {
                filerQuery.append(keyChar);
                filerFiltered = DirectoryLister.filterEntries(filerEntries, filerQuery.toString());
                filerSelectedIdx = 0;
                renderFilerBuffer();
            }
        } else {
            if (keyCode == KeyEvent.VK_ESCAPE) {
                exitFiler();
                return;
            }
            if (keyCode == KeyEvent.VK_ENTER) {
                openSelectedEntry();
                return;
            }
            // 自由入力のない一覧表示中は j/k(Vim式)・矢印キー・Ctrl+N/Pのいずれでも移動できる。
            if ((ctrlDown && keyCode == KeyEvent.VK_N) || keyCode == KeyEvent.VK_DOWN || (!ctrlDown && keyChar == 'j')) { moveSelection(1);  return; }
            if ((ctrlDown && keyCode == KeyEvent.VK_P) || keyCode == KeyEvent.VK_UP   || (!ctrlDown && keyChar == 'k')) { moveSelection(-1); return; }
            if (keyChar == '/') {
                filerSearchMode = true;
                filerQuery.setLength(0);
                renderFilerBuffer();
            }
        }
    }

    /** 結果リスト自体は変わらないので、選択行に合わせて実カーソルを動かすだけでよい（telescope の moveTelescope() と同じ）。 */
    private void moveSelection(int delta) {
        if (filerFiltered.isEmpty()) return;
        filerSelectedIdx = Math.max(0, Math.min(filerFiltered.size() - 1, filerSelectedIdx + delta));
        cursorRow = filerSelectedIdx + 1;
        cursorCol = 0;
    }

    private void openSelectedEntry() {
        if (filerFiltered.isEmpty()) return;
        DirEntry entry = filerFiltered.get(filerSelectedIdx);
        if (entry.kind() == DirEntry.Kind.DIRECTORY) {
            if (changeWdCallback == null) {
                statusMessage = "E: working directory handler not set";
                return;
            }
            String err = changeWdCallback.apply(entry.path());
            if (err != null) {
                statusMessage = "E: " + err;
                return;
            }
            enterFiler();
        } else {
            exitFiler();
            loadFromFile(entry.path().toString());
        }
    }

    // ===== syncCanvas() 用テキストキャッシュ（軽量化リファクタリング Phase 2） =====
    // buffer.getText() と split("\n", -1) は文書全体を再構築する O(n) 処理で、
    // 以前は syncCanvas() が1キー入力ごとに getText() を2回・split を2回実行していた
    //（EditorCanvas.setText() 内の split を含む）。テキストが変化しないキー（カーソル移動等）
    // では再構築自体が不要なため、UndoablePieceTable.getVersion()（insert/delete/undo/redo で
    // 必ず増分する既存の版数）と buffer インスタンスの参照一致で有効性を判定するキャッシュを持つ。
    // buffer は疑似バッファ切替等で別インスタンスに差し替わることがあるが、outputErrorLinesOwner /
    // binaryModeOwner と同じ「参照一致による自動失効」パターンにより、差し替え箇所（約30箇所）に
    // 一切手を入れずキャッシュも自動失効する。
    private UndoablePieceTable canvasTextOwner = null;
    private long canvasTextVersion = -1;
    private String canvasCachedText = "";
    private String[] canvasCachedLines = { "" };
    // テスト用: キャッシュミスで全文再構築が起きた回数（SyncCanvasCacheTest が参照）
    private long canvasTextRebuildCount = 0;

    private void refreshCanvasTextCache() {
        if (canvasTextOwner == buffer && canvasTextVersion == buffer.getVersion()) {
            return; // テキスト未変更: 再構築しない（カーソル移動等はここを通る）
        }
        canvasCachedText = buffer.getText();
        canvasCachedLines = canvasCachedText.split("\n", -1);
        canvasTextOwner = buffer;
        canvasTextVersion = buffer.getVersion();
        canvasTextRebuildCount++;
    }

    public void syncCanvas() {
        if (canvas != null) {
            refreshCanvasTextCache();
            canvas.setText(canvasCachedText, canvasCachedLines);
            canvas.setWrapEnabled(wrapEnabled);
            java.util.Set<Integer> errorLines;
            if (outputErrorLinesOwner == buffer) {
                errorLines = outputErrorLines;
            } else if (buffer == terminalBuffer) {
                errorLines = terminalErrorLines;
            } else {
                errorLines = java.util.Set.of();
            }
            canvas.setErrorLines(errorLines);
            canvas.setCursor(cursorRow, cursorCol);
            canvas.setInsertMode(mode == Mode.INSERT);

            boolean isVisual      = (mode == Mode.VISUAL);
            boolean isVisualLine  = (mode == Mode.VISUAL_LINE);
            boolean isVisualBlock = (mode == Mode.VISUAL_BLOCK);
            canvas.setVisualMode(isVisual || isVisualLine || isVisualBlock);
            canvas.setVisualLineMode(isVisualLine);
            canvas.setVisualBlockMode(isVisualBlock);

            if (isVisual || isVisualBlock) {
                canvas.setSelection(anchorRow, anchorCol, cursorRow, cursorCol);
            } else if (isVisualLine) {
                canvas.setSelection(anchorRow, 0, cursorRow, 0);
            } else {
                canvas.clearSelection();
            }

            canvas.ensureCursorVisible(cursorRow);
            String[] lines = canvasCachedLines;
            String curLine = (cursorRow < lines.length) ? lines[cursorRow] : "";
            canvas.ensureCursorColVisible(cursorCol, curLine);

            // ステータスバー用カーソル位置ラベル "(行数:トータル文字数)"。
            // 全角/半角とも1文字として数える（String基準のcursorCol/lines[].length()をそのまま使うため、
            // 画面幅を2倍で扱う uiTextWidth 等の全角対応ロジックとは無関係）。
            // canvasCachedLines を再利用し、buffer.getText() の再構築を増やさない。
            // syncCanvas() はキー入力1回につき1度だけ呼ばれるため、ここで計算しキャッシュしておく。
            // EditorCanvas側（30fpsのanimTimerでrepaintされるdrawStatusLine）では再計算しない設計。
            int totalChars = 0;
            for (int i = 0; i < cursorRow && i < lines.length; i++) {
                totalChars += lines[i].length() + 1; // +1 は改行文字
            }
            totalChars += Math.min(cursorCol, curLine.length()) + 1;
            canvas.setCursorPositionLabel("(" + (cursorRow + 1) + ":" + totalChars + ")");
            if (mode == Mode.COMMAND) {
                canvas.setCommandLineText(":" + commandBuffer.toString());
            } else if (mode == Mode.SEARCH) {
                canvas.setCommandLineText("/" + searchBuffer.toString());
            } else if (mode == Mode.FILESEARCH) {
                String prefix = (fileSearchType == FileSearchType.NAME) ? "\\f" : "\\g";
                canvas.setCommandLineText(prefix + fileSearchBuffer.toString());
            } else if (mode == Mode.TELESCOPE && telescopePicker != null) {
                canvas.setCommandLineText(telescopePicker.title() + "  > " + telescopeQuery.toString());
            } else if (mode == Mode.CLASSPATH_INPUT) {
                canvas.setCommandLineText(classpathInputLabel
                    + " classpath (カンマ区切り, Enter=確定, Esc=スキップ): "
                    + classpathInputBuffer.toString());
            } else if (!statusMessage.isEmpty()) {
                canvas.setCommandLineText(statusMessage);
            } else {
                canvas.setCommandLineText(null);
            }

            // TELESCOPE/FILER は \f/\g と同じ疑似バッファ表示（buffer に直接描画済み）のため
            // オーバーレイは使わない。IMPORT_SELECT のみ従来どおりオーバーレイを使う。
            if (mode == Mode.IMPORT_SELECT) {
                // import 候補選択モーダル: TelescopeItem リストとして表示する
                List<TelescopeItem> items = new ArrayList<>();
                for (String fqn : importSelectFqns) {
                    items.add(new TelescopeItem(fqn, null, 0, 0));
                }
                int sym = pendingImportIdx + 1;
                int total = pendingImports.size() + pendingImportIdx + 1; // 残り含む総数
                String title = "Import: " + importSelectSymbol
                    + "  [" + sym + "/" + total + "]";
                canvas.setTelescopeState(true, title, "", items, importSelectIdx, "");
            } else {
                canvas.setTelescopeState(false, "", "", List.of(), 0, "");
            }
        }
    }

    // -------------------------------------------------------------------------
    // パブリックアクセサ（テスト・外部連携用）
    // -------------------------------------------------------------------------

    public KeymapRegistry getKeymap()   { return keymap; }
    public String getText()            { return buffer.getText(); }
    /** テスト用: syncCanvas() のテキストキャッシュが再構築された回数（軽量化 Phase 2）。 */
    public long getCanvasTextRebuildCount() { return canvasTextRebuildCount; }
    public boolean isWrapEnabled()     { return wrapEnabled; }
    public int getCursorRow()          { return cursorRow; }
    public int getCursorCol()          { return cursorCol; }
    public boolean isNormalMode()      { return mode == Mode.NORMAL; }
    public boolean isInsertMode()      { return mode == Mode.INSERT; }
    public boolean isCommandMode()     { return mode == Mode.COMMAND; }
    public boolean isVisualMode()      { return mode == Mode.VISUAL; }
    public boolean isVisualLineMode()  { return mode == Mode.VISUAL_LINE; }
    public boolean isVisualBlockMode() { return mode == Mode.VISUAL_BLOCK; }
    public boolean isSearchMode()         { return mode == Mode.SEARCH; }
    public boolean isTelescopeMode()      { return mode == Mode.TELESCOPE; }
    public boolean isImportSelectMode()   { return mode == Mode.IMPORT_SELECT; }
    public boolean isFilerMode()          { return mode == Mode.FILER; }
    public boolean isBinaryMode()         { return mode == Mode.BINARY; }
    public boolean isClasspathInputMode() { return mode == Mode.CLASSPATH_INPUT; }
    public String getClasspathInputBuffer() { return classpathInputBuffer.toString(); }
    public boolean isCompletionActive()   { return completionActive; }
    public java.util.List<dev.javatexteditor.analysis.CompletionItem> getCompletionItems() { return completionItems; }
    public boolean isCdSelectionActive()  { return cdSelectionActive; }
    public List<String> getCdCandidates() { return cdCandidates; }
    public boolean isEditSelectionActive() { return edSelectionActive; }
    public List<String> getEditCandidates() { return edCandidates; }
    public void setBuffer(UndoablePieceTable newBuffer) { this.buffer = newBuffer; }
    /** Vim方式の共有バッファ用: 現在のバッファ参照を返す（他ペインとの参照一致判定に使う）。 */
    public UndoablePieceTable getBuffer() { return buffer; }
    public int getFilerSelectedIdx()      { return filerSelectedIdx; }
    public List<DirEntry> getFilerFiltered() { return filerFiltered; }
    public boolean isFilerSearchMode()    { return filerSearchMode; }
    public String getFilerQuery()         { return filerQuery.toString(); }
    public String getTelescopeQuery()     { return telescopeQuery.toString(); }
    public List<TelescopeItem> getTelescopeResults() { return telescopeResults; }
    public int getTelescopeSelectedIdx()  { return telescopeSelectedIdx; }
    public void setProjectRoot(Path root)    { this.projectRoot = root; }
    public void setChangeWorkingDirectoryCallback(java.util.function.Function<Path, String> cb) {
        this.changeWdCallback = cb;
    }
    public Path getProjectRoot() {
        return (projectRoot != null) ? projectRoot : Path.of(System.getProperty("user.dir"));
    }

    // F10/F11: *compile*/*run* 疑似バッファのリアルタイムログ表示用の状態。
    // outputErrorLinesOwner は「この行番号集合がどの buffer インスタンスに対応するか」を
    // 参照一致で識別するためのフィールド。buffer は ~25箇所で `buffer = new UndoablePieceTable(...)`
    // と再代入されるため、逐一クリアするより「今の buffer と一致するときだけ有効」という
    // 参照ベースの自動失効の方が取りこぼしがない（syncCanvas() 側で判定する）。
    private java.util.Set<Integer> outputErrorLines = java.util.Set.of();
    private UndoablePieceTable outputErrorLinesOwner = null;
    // ストリーミング追記時、次に追記する行が何行目になるかのカウンタ（末尾追記のみのため単純な+1でよい）。
    private int outputNextRow = 0;
    // *run* 疑似バッファ2行目（ステータス行）を完了時に書き換えるためのプレースホルダ文字列と開始オフセット。
    private String runHeaderPlaceholder = "";
    private int runHeaderOffset = 0;

    /**
     * F10: コンパイル結果を *compile* 疑似バッファに表示する。
     * :grep/:rename と同じパターン（pushBuffer せず直接 buffer を差し替え、Ctrl+U 履歴には積まない）。
     * ERROR診断・エラーメッセージの行は赤字表示するため行番号を outputErrorLines に記録する。
     */
    // F10/F11/F12: *compile*/*run* 疑似バッファは currentFilePath == null のためファイル経路の
    // バッファ切替では追跡できない。SPC+b から常に再度開けるよう直近の内容をここにキャッシュする。
    private String lastCompileBufferText = null;
    private String lastRunBufferText = null;
    private static final String PSEUDO_COMPILE_PATH = "*compile*";
    private static final String PSEUDO_RUN_PATH = "*run*";

    // .classファイルビューア（:nimo コマンド）用の状態。classFileBufferOwner は
    // outputErrorLinesOwner と同じ「参照一致による自動失効」パターン: buffer が別の
    // 疑似バッファ/ファイルに差し替わった時点で参照が一致しなくなり :nimo は自動的に無効化される。
    private byte[] classFileBytes = null;
    private String classFileName = null;
    private UndoablePieceTable classFileBufferOwner = null;

    /** :nimo — 現在開いている.classファイル構造ビューをニーモニック（javap -c風）表示に切り替える。 */
    private void showClassFileMnemonic() {
        if (classFileBytes == null || classFileBufferOwner != buffer) {
            statusMessage = "E: not viewing a .class file";
            return;
        }
        try {
            ClassFile parsed = ClassFileParser.parse(classFileBytes);
            String text = MnemonicFormatter.format(parsed, classFileName);
            buffer = new UndoablePieceTable(text);
            currentFilePath = null;
            clearSearchHighlights();
            cursorRow = 0;
            cursorCol = 0;
            // 引き続き同じ.classバイト列を指しているので、mnemonic表示バッファもこの後の
            // 追跡対象として維持する（もう一度 :nimo しても同じ内容が再表示されるだけ）。
            classFileBufferOwner = buffer;
            statusMessage = "\"" + classFileName + "\" mnemonic view";
        } catch (ClassFileFormatException e) {
            statusMessage = "E: failed to disassemble: " + e.getMessage();
        }
    }

    public void showCompileResult(BuildResult result) {
        StringBuilder sb = new StringBuilder();
        java.util.Set<Integer> errorRows = new java.util.HashSet<>();
        int row = 0;
        if (result.command() != null && !result.command().isEmpty()) {
            sb.append(result.command()).append('\n');
            row++;
        }
        sb.append("*compile* ").append(result.success() ? "SUCCESS" : "FAILED")
          .append(" — ").append(result.fileCount()).append(" file(s)\n");
        row++;
        if (result.errorMessage() != null) {
            errorRows.add(row);
            sb.append(result.errorMessage()).append('\n');
            row++;
        }
        for (BuildDiagnostic d : result.diagnostics()) {
            if (d.isError()) errorRows.add(row);
            sb.append(d.isError() ? "ERROR " : "WARNING ")
              .append(d.filePath()).append(':').append(d.lineNumber() + 1)
              .append(':').append(d.column() + 1).append(": ")
              .append(d.message()).append('\n');
            row++;
        }
        lastCompileBufferText = sb.toString();
        buffer = new UndoablePieceTable(lastCompileBufferText);
        currentFilePath = null;
        grepResults = null;
        fileNameResults = null;
        cursorRow = 0;
        cursorCol = 0;
        outputErrorLines = errorRows;
        outputErrorLinesOwner = buffer;
        statusMessage = result.success()
            ? "compile: success (" + result.fileCount() + " file(s))"
            : "compile: FAILED";
        // Main.javaのバックグラウンドスレッド完了コールバックから呼ばれ、processKey()経由の
        // syncCanvas()呼び出しを通らないため、ここで呼ばないと次のキー入力まで描画が更新されない。
        syncCanvas();
    }

    /** F11: 実行結果を *run* 疑似バッファに表示する。showCompileResult と同じ疑似バッファパターン。 */
    public void showRunOutput(String command, String fqcn, String output, int exitCode) {
        StringBuilder sb = new StringBuilder();
        if (command != null && !command.isEmpty()) {
            sb.append(command).append('\n');
        }
        sb.append("*run* ").append(fqcn).append(" — exit code ").append(exitCode).append('\n');
        sb.append(output);
        lastRunBufferText = sb.toString();
        buffer = new UndoablePieceTable(lastRunBufferText);
        currentFilePath = null;
        grepResults = null;
        fileNameResults = null;
        cursorRow = 0;
        cursorCol = 0;
        statusMessage = "run: " + fqcn + " exited with code " + exitCode;
        // showCompileResult と同じ理由（バックグラウンドスレッド完了コールバックから呼ばれるため）で
        // syncCanvas() を明示的に呼ぶ。
        syncCanvas();
    }

    /**
     * F10: コンパイル開始時に *compile* 疑似バッファをプレースホルダで表示する
     * （javac実行中に diagnostic が届くたびリアルタイムで追記していく起点）。
     */
    public void beginCompileOutput() {
        buffer = new UndoablePieceTable("*compile* 実行中...\n");
        currentFilePath = null;
        grepResults = null;
        fileNameResults = null;
        cursorRow = 0;
        cursorCol = 0;
        outputErrorLines = new java.util.HashSet<>();
        outputErrorLinesOwner = buffer;
        outputNextRow = 1;
        statusMessage = "コンパイル中...";
    }

    /** F10: javacが診断を1件報告するたび *compile* 疑似バッファの末尾へリアルタイム追記する。 */
    public void appendCompileDiagnostic(BuildDiagnostic d) {
        String line = (d.isError() ? "ERROR " : "WARNING ")
            + d.filePath() + ':' + (d.lineNumber() + 1) + ':' + (d.column() + 1) + ": " + d.message();
        appendOutputLine(line, d.isError());
    }

    /**
     * F10: コンパイル完了後、最終結果で *compile* 疑似バッファを確定表示する。
     * ストリーミング中の追記内容は破棄し、showCompileResult() と同じ最終整形で丸ごと描き直す
     * （command 行の有無で先頭の行数が変わりうるため、逐次パッチではなく確定結果からの再構築にする）。
     */
    public void finishCompileOutput(BuildResult result) {
        showCompileResult(result);
    }

    /**
     * F11: 実行開始時に *run* 疑似バッファをプレースホルダで表示する
     * （コマンド行は実行前から確定しているため、以後 finishRunOutput() まで行数は変わらない）。
     */
    public void beginRunOutput(String command, String fqcn) {
        StringBuilder sb = new StringBuilder();
        String header0 = (command != null) ? command : "";
        if (!header0.isEmpty()) sb.append(header0).append('\n');
        runHeaderOffset = sb.length();
        runHeaderPlaceholder = "*run* " + fqcn + " — 実行中...";
        sb.append(runHeaderPlaceholder).append('\n');
        buffer = new UndoablePieceTable(sb.toString());
        currentFilePath = null;
        grepResults = null;
        fileNameResults = null;
        cursorRow = 0;
        cursorCol = 0;
        outputErrorLines = new java.util.HashSet<>();
        outputErrorLinesOwner = buffer;
        outputNextRow = header0.isEmpty() ? 1 : 2;
        statusMessage = "run: " + fqcn + " を実行中...";
    }

    /**
     * F11: 実行中プロセスの標準出力/標準エラー出力を1行ずつ *run* 疑似バッファへリアルタイム追記する。
     * isError=true（標準エラー由来）の行は赤字表示するため行番号を outputErrorLines に記録する。
     */
    public void appendRunOutputLine(String line, boolean isError) {
        appendOutputLine(line, isError);
    }

    /**
     * F11: プロセス終了後、*run* 疑似バッファ2行目のプレースホルダを実際の終了コードへ書き換える。
     * SPC+b（BufferPicker）から再度開けるよう、確定した最終テキストを lastRunBufferText へも反映する
     * （showRunOutput() と同じ役割。ストリーミング経路は showRunOutput() を経由しないため必須）。
     */
    public void finishRunOutput(String fqcn, int exitCode) {
        if (outputErrorLinesOwner != buffer) return; // 実行中に別バッファへ切り替わっていたら何もしない
        String finalHeader = "*run* " + fqcn + " — exit code " + exitCode;
        buffer.delete(runHeaderOffset, runHeaderPlaceholder.length());
        buffer.insert(runHeaderOffset, finalHeader);
        statusMessage = "run: " + fqcn + " exited with code " + exitCode;
        lastRunBufferText = buffer.getText();
    }

    /** beginCompileOutput()/beginRunOutput() が作った疑似バッファの末尾へ1行追記する共通処理。 */
    private void appendOutputLine(String line, boolean isError) {
        if (outputErrorLinesOwner != buffer) return; // ストリーミング中に別バッファへ切り替わっていたら何もしない
        buffer.insert(buffer.length(), line + "\n");
        if (isError) outputErrorLines.add(outputNextRow);
        cursorRow = outputNextRow;
        cursorCol = 0;
        outputNextRow++;
    }
    public boolean isFileSearchMode()     { return mode == Mode.FILESEARCH; }
    public boolean isFileNameSearch()     { return mode == Mode.FILESEARCH && fileSearchType == FileSearchType.NAME; }
    public boolean isFileGrepSearch()     { return mode == Mode.FILESEARCH && fileSearchType == FileSearchType.GREP; }
    public String getFileSearchBuffer()   { return fileSearchBuffer.toString(); }
    public List<String> getFileNameResults() { return fileNameResults; }
    public String getStatusMessage()      { return statusMessage; }
    public String getCommandBuffer()      { return commandBuffer.toString(); }
    public String getSearchBuffer()       { return searchBuffer.toString(); }
    public String getLastSearchPattern()  { return lastSearchPattern; }
    public List<int[]> getSearchMatches() { return searchMatches; }
    public int getCurrentMatchIdx()       { return currentMatchIdx; }
    public String getYankRegister()    { return yankRegister; }
    public String getYankType()        { return yankType == YankType.LINE ? "line" : "char"; }
    // vim-macro-recording: テスト・プラグイン向けアクセサ
    public boolean isRecordingMacro()  { return macroRecording; }
    public boolean hasMacro(char register) {
        List<RecordedKey> keys = macroRegisters.get(Character.toLowerCase(register));
        return keys != null && !keys.isEmpty();
    }
    public int getMacroLength(char register) {
        List<RecordedKey> keys = macroRegisters.get(Character.toLowerCase(register));
        return keys == null ? 0 : keys.size();
    }

    // プラグイン向けバッファ操作
    public int getLineCount() {
        return getLines().length;
    }

    public String getLine(int row) {
        String[] lines = getLines();
        return (row >= 0 && row < lines.length) ? lines[row] : "";
    }

    public void setCursor(int row, int col) {
        String[] lines = getLines();
        cursorRow = Math.max(0, Math.min(row, lines.length - 1));
        int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
        cursorCol = Math.max(0, Math.min(col, lineLen));
        syncCanvas();
    }

    public void insertAtOffset(int offset, String text) {
        buffer.insert(offset, text);
        syncCanvas();
    }

    public void deleteRange(int startOffset, int endOffset) {
        if (startOffset < endOffset) {
            buffer.delete(startOffset, endOffset - startOffset);
            syncCanvas();
        }
    }

    public void setStatusMessage(String message) {
        this.statusMessage = message;
        syncCanvas();
    }

    /** JDK クラスインデックスを設定する（Main.java からバックグラウンド構築後に呼ぶ）。 */
    public void setJdkClassIndex(JdkClassIndex index) {
        this.jdkIndex = index;
    }

    /** 入力補完インデックスを設定する。 */
    public void setCompletionIndex(dev.javatexteditor.analysis.CompletionIndex index) {
        this.completionIndex = index;
    }

    /** Alt+/ 単語補完インデックスを設定する。 */
    public void setWordIndex(dev.javatexteditor.analysis.WordIndex index) {
        this.wordIndex = index;
    }

    /** auto-import ハンドラを設定する。 */
    public void setAutoImportHandler(AutoImportHandler handler) {
        this.autoImportHandler = handler;
    }

    /**
     * canvas なしのテスト環境で診断ジャンプを検証するために診断リストをセットする。
     * canvas がある場合は canvas 側の診断が優先される。
     */
    public void setDiagnostics(List<CompileDiagnostic> diags) {
        this.localDiagnostics = (diags != null) ? List.copyOf(diags) : List.of();
        if (canvas != null) canvas.setDiagnostics(this.localDiagnostics);
    }

    /**
     * コンパイル診断から未解決シンボルを検出し、import の自動挿入または選択を行う。
     * 候補が1件の場合は即座に挿入。複数の場合は選択待ちモードへ。
     * Must be called on the EDT.
     */
    public void handleAutoImport(List<CompileDiagnostic> diags) {
        if (autoImportHandler == null) return;
        Map<String, List<String>> candidates =
            autoImportHandler.resolveCandidates(diags, buffer.getText(), getProjectRoot());
        if (candidates.isEmpty()) {
            statusMessage = "auto-import: 挿入対象なし";
            if (onImportComplete != null) { onImportComplete.run(); onImportComplete = null; }
            syncCanvas();
            return;
        }

        List<Map.Entry<String, List<String>>> entries = new ArrayList<>(candidates.entrySet());

        // 候補が1件のみのシンボルをまず自動挿入
        importAppliedCount = 0;
        List<Map.Entry<String, List<String>>> multi = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : entries) {
            if (e.getValue().size() == 1) {
                autoImportHandler.applyImport(e.getValue().get(0), buffer);
                importAppliedCount++;
            } else {
                multi.add(e);
            }
        }

        if (!multi.isEmpty()) {
            pendingImports.clear();
            pendingImports.addAll(multi);
            pendingImportIdx = 0;
            enterImportSelect();
        } else {
            statusMessage = "auto-import: " + importAppliedCount + "件 挿入済み";
            if (onImportComplete != null) { onImportComplete.run(); onImportComplete = null; }
        }
        syncCanvas();
    }

    /**
     * テスト用: candidates マップを直接渡して import 選択フローを開始する。
     * 候補1件 → 即適用（applyImportCallback を呼ぶ）、複数候補 → IMPORT_SELECT モードへ。
     * @param candidates  シンボル名 → FQN候補リストのマップ
     * @param applyImportCallback  選択された FQN を受け取って適用するコールバック
     */
    public void handleAutoImportFromCandidates(
            Map<String, List<String>> candidates,
            java.util.function.Consumer<String> applyImportCallback) {
        if (candidates.isEmpty()) {
            if (onImportComplete != null) { onImportComplete.run(); onImportComplete = null; }
            return;
        }
        List<Map.Entry<String, List<String>>> entries = new ArrayList<>(candidates.entrySet());
        List<Map.Entry<String, List<String>>> multi = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : entries) {
            if (e.getValue().size() == 1) {
                applyImportCallback.accept(e.getValue().get(0));
            } else {
                multi.add(e);
            }
        }
        if (!multi.isEmpty()) {
            // IMPORT_SELECT モード中の適用コールバックをカスタム化するため一時フィールドに保存
            pendingImportApply = applyImportCallback;
            pendingImports.clear();
            pendingImports.addAll(multi);
            pendingImportIdx = 0;
            enterImportSelect();
        } else {
            if (onImportComplete != null) { onImportComplete.run(); onImportComplete = null; }
        }
        syncCanvas();
    }

    /** 選択待ちの import が存在するかどうか（IMPORT_SELECT モード中も含む）。 */
    public boolean hasImportPending() {
        return !pendingImports.isEmpty() || mode == Mode.IMPORT_SELECT;
    }

    // -------------------------------------------------------------------------
    // IMPORT_SELECT モード処理（複数 import 候補をモーダルオーバーレイで選択）
    // -------------------------------------------------------------------------

    /** 現在の pendingImports[pendingImportIdx] に対する選択モーダルを開く。 */
    private void enterImportSelect() {
        if (pendingImports.isEmpty()) return;
        Map.Entry<String, List<String>> entry = pendingImports.get(pendingImportIdx);
        importSelectSymbol = entry.getKey();
        importSelectFqns   = entry.getValue();
        importSelectIdx    = 0;
        mode = Mode.IMPORT_SELECT;
        statusMessage = "";
    }

    private void processImportSelectKey(int keyCode, char keyChar, int modifiers) {
        boolean ctrlDown = (modifiers & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;

        if (keyCode == KeyEvent.VK_ESCAPE) {
            // スキップして次の候補へ
            exitImportSelect(false);
            return;
        }
        if (keyCode == KeyEvent.VK_ENTER) {
            // 選択して import 挿入
            exitImportSelect(true);
            return;
        }
        // 自由入力のない選択専用画面のため j/k(Vim式)も移動キーとして使える。
        if ((ctrlDown && keyCode == KeyEvent.VK_N) || keyCode == KeyEvent.VK_DOWN || (!ctrlDown && keyChar == 'j')) {
            if (importSelectIdx < importSelectFqns.size() - 1) importSelectIdx++;
            return;
        }
        if ((ctrlDown && keyCode == KeyEvent.VK_P) || keyCode == KeyEvent.VK_UP || (!ctrlDown && keyChar == 'k')) {
            if (importSelectIdx > 0) importSelectIdx--;
            return;
        }
    }

    /** 選択完了（apply=true）またはスキップ（apply=false）して次の候補へ進む。 */
    private void exitImportSelect(boolean apply) {
        if (apply && importSelectIdx < importSelectFqns.size()) {
            String fqn = importSelectFqns.get(importSelectIdx);
            if (pendingImportApply != null) {
                pendingImportApply.accept(fqn);
            } else if (autoImportHandler != null) {
                autoImportHandler.applyImport(fqn, buffer);
            }
            importAppliedCount++;
        }
        mode = Mode.NORMAL;
        if (canvas != null) canvas.setTelescopeState(false, "", "", List.of(), 0, "");
        advanceImportPrompt();
    }

    /** 現在のシンボルの選択を完了（または skip）して次の pendingImport へ進む。 */
    private void advanceImportPrompt() {
        pendingImportIdx++;
        if (pendingImportIdx >= pendingImports.size()) {
            pendingImports.clear();
            pendingImportIdx = 0;
            pendingImportApply = null;
            if (importAppliedCount > 0) {
                statusMessage = "auto-import: " + importAppliedCount + "件 挿入済み";
            } else {
                statusMessage = "auto-import: 挿入対象なし";
            }
            if (onImportComplete != null) {
                onImportComplete.run();
                onImportComplete = null;
            }
        } else {
            enterImportSelect();
        }
    }

    /**
     * gr (go to references): カーソル位置の識別子の使用箇所を grep 検索する。
     * jdk-source 疑似バッファ内で native ソース（lib/openjdk-native/）が利用可能な場合は
     * そちらを検索対象にする（呼び出し箇所・ヘッダ宣言を含め C/C++ 側の参照を横断的に探す）。
     * それ以外は通常通りプロジェクト全体を検索する。
     *
     * @param fullScan true なら gR（bang付き）: node_modules 等のデフォルトスキップ対象も
     *                 含め、作業ディレクトリ配下を一切除外せず全ファイルを検索する。
     */
    private void goToReferences(boolean fullScan) {
        String word = wordAtCursor();
        if (word.isEmpty()) {
            setStatusMessage("No identifier at cursor");
            return;
        }
        String pattern = "\\b" + Pattern.quote(word) + "\\b";
        if (inJdkSourceBuffer && sourceTracer.hasNativeSrcDir()) {
            executeGrep(pattern, sourceTracer.getNativeSrcDir().get(), fullScan);
            return;
        }
        executeGrep(pattern, getProjectRoot(), fullScan);
    }

    /**
     * NORMALモードの Shift+K（K）キー: カーソル位置の識別子の宣言箇所へジャンプする
     * （Eclipse/IntelliJ IDEA の "Open Declaration" 相当）。
     * 自プロジェクト内のクラス・メソッド・フィールド・定数を優先して検索し、
     * 見つからなければ JDK のクラス／"ClassName.member" 形式のメンバー宣言を試みる。
     * src.zip がなければステータスバーへのフォールバック表示。
     * カーソルが "ClassName.methodName" の上にある場合、または jdk-source 疑似バッファ内で
     * メソッド名の上にカーソルがある場合は native メソッドのトレースも試みる。
     */
    private void lookupJdkDoc() {
        BufferSnapshot before = new BufferSnapshot(buffer.getText(), currentFilePath, cursorRow, cursorCol);

        // 最優先段: Eclipse JDT 流のバインディング解決（有効化されている場合のみ）。
        // jdk-source 疑似バッファ内は対象外（表示専用のJDKソース/ネイティブスニペットであり、
        // プロジェクトの compilation unit として意味解析する対象ではない。ネイティブトレース等の
        // 既存フローにそのまま任せる）。
        if (bindingLookupEnabled && !inJdkSourceBuffer) {
            String word = wordAtCursor();
            if (word.isEmpty()) {
                setStatusMessage("No identifier at cursor");
                return;
            }
            final long generation = ++bindingLookupGeneration;
            final UndoablePieceTable bufferAtRequest = buffer;
            final long versionAtRequest = buffer.getVersion();
            final int offset = offsetOfCursor();
            final Path root = getProjectRoot();
            final String filePathAtRequest = currentFilePath;
            setStatusMessage("Resolving definition of " + word + "...");
            bindingLookupExecutor.accept(() -> {
                BindingDefinitionResolver.Resolution resolution;
                try {
                    resolution = bindingDefinitionResolver.resolve(
                        before.text(), filePathAtRequest, offset, root);
                } catch (Exception e) {
                    resolution = new BindingDefinitionResolver.NotFound("analysis failed: " + e);
                }
                final BindingDefinitionResolver.Resolution result = resolution;
                bindingLookupUiDispatcher.accept(() ->
                    applyBindingResolution(result, before, generation,
                        bufferAtRequest, versionAtRequest));
            });
            return;
        }

        lookupJdkDocAndJump(before.text());
        recordJumpOriginIfMoved(before);
    }

    /**
     * Shift+K の JDT 流バインディング解決を有効化し、実行機構を注入する。
     *
     * @param backgroundExecutor 解析タスクを実行する機構。本番（Main.java）は仮想スレッド起動、
     *                           テストは Runnable::run（同期実行）を渡す。
     * @param uiDispatcher       解析結果をエディタ状態へ反映する機構。本番は
     *                           SwingUtilities::invokeLater、テストは Runnable::run を渡す。
     */
    public void enableBindingDefinitionLookup(Consumer<Runnable> backgroundExecutor,
                                              Consumer<Runnable> uiDispatcher) {
        this.bindingLookupEnabled = true;
        this.bindingLookupExecutor = backgroundExecutor;
        this.bindingLookupUiDispatcher = uiDispatcher;
    }

    /** Shift+K ジャンプ後、実際にカーソル・バッファが動いた場合のみ Shift+J 用の復帰元を記録する。 */
    private void recordJumpOriginIfMoved(BufferSnapshot before) {
        boolean moved = cursorRow != before.row() || cursorCol != before.col()
            || !java.util.Objects.equals(currentFilePath, before.filePath());
        if (moved) {
            lastJumpOrigin = before;
        }
    }

    /**
     * バインディング解決の結果をエディタ状態へ反映する（本番では EDT 上で実行される）。
     * 非同期解析の完了までにエディタ側の状況が変わっていた場合（編集・バッファ切替・
     * モード遷移・カーソル移動・新しい Shift+K）は、古い結果を適用せず黙って破棄する。
     * 解決失敗（NotFound）や src.zip 不在などで反映できない場合は、従来の
     * ヒューリスティック解決（{@link #lookupJdkDocAndJump(String)}）へフォールバックする。
     */
    private void applyBindingResolution(BindingDefinitionResolver.Resolution result,
                                        BufferSnapshot before, long generation,
                                        UndoablePieceTable bufferAtRequest, long versionAtRequest) {
        if (generation != bindingLookupGeneration) return;
        if (buffer != bufferAtRequest || buffer.getVersion() != versionAtRequest) return;
        if (mode != Mode.NORMAL) return;
        if (cursorRow != before.row() || cursorCol != before.col()) return;

        switch (result) {
            case BindingDefinitionResolver.ProjectLocation loc -> {
                jumpToBindingLocation(loc);
                recordJumpOriginIfMoved(before);
            }
            case BindingDefinitionResolver.JdkElementLocation jdk -> {
                if (jumpToJdkElement(jdk)) {
                    recordJumpOriginIfMoved(before);
                } else {
                    fallbackToHeuristicLookup(before);
                }
            }
            case BindingDefinitionResolver.NotFound nf -> fallbackToHeuristicLookup(before);
        }
        syncCanvas();
    }

    /** バインディング解決が不発だった場合の従来経路（正規表現ヒューリスティック＋JDK索引）。 */
    private void fallbackToHeuristicLookup(BufferSnapshot before) {
        lookupJdkDocAndJump(before.text());
        recordJumpOriginIfMoved(before);
    }

    /** バインディング解決で得たプロジェクト内宣言位置へジャンプする。 */
    private void jumpToBindingLocation(BindingDefinitionResolver.ProjectLocation loc) {
        // filePath == null は「現在の無名バッファ内の宣言」。currentFilePath と一致する場合も
        // 同一ファイル内ジャンプ（既存の jumpToSymbolLocation() と同じ分岐構造・列0の慣例に揃える）。
        if (loc.filePath() == null || loc.filePath().equals(currentFilePath)) {
            cursorRow = loc.lineNumber();
            cursorCol = 0;
        } else {
            loadFromFile(loc.filePath());
            cursorRow = loc.lineNumber();
            cursorCol = 0;
        }
        String fileLabel = (loc.filePath() != null) ? loc.filePath() : "[current buffer]";
        setStatusMessage("→ " + loc.kindLabel() + "  " + fileLabel + ":" + (loc.lineNumber() + 1));
    }

    /**
     * バインディング解決の結果が JDK（プラットフォームクラスパス）側の要素だった場合、
     * src.zip から該当クラスのソースを疑似バッファで開き、メンバー宣言行へジャンプする。
     * src.zip が無い・エントリが見つからない場合は false（呼び出し側がフォールバックする）。
     * ネイティブ（JNI/HotSpot）トレースはこの経路では行わない（既存の tryJdkMember 経路のまま）。
     */
    private boolean jumpToJdkElement(BindingDefinitionResolver.JdkElementLocation jdk) {
        if (!sourceTracer.hasSrcZip()) return false;
        Optional<String> src = sourceTracer.readJavaSourceByFqcn(jdk.moduleName(), jdk.fqcn());
        if (src.isEmpty()) return false;
        openJdkSourceBuffer("*jdk-source:" + jdk.fqcn() + "*", src.get());
        if (jdk.memberName() != null) {
            jumpToMember(jdk.memberName());
        }
        return true;
    }

    /**
     * Shift+J（jump.back）: 直前の Shift+K 定義ジャンプの前にいた位置へ一つ戻る。
     * ジャンプ元がファイルを跨いでいた場合はそのファイルを再度開く。
     */
    private void jumpBack() {
        if (lastJumpOrigin == null) {
            setStatusMessage("No previous jump to go back to");
            return;
        }
        BufferSnapshot origin = lastJumpOrigin;
        lastJumpOrigin = null;
        buffer = new UndoablePieceTable(origin.text());
        currentFilePath = origin.filePath();
        cursorRow = origin.row();
        cursorCol = origin.col();
        inJdkSourceBuffer = origin.filePath() != null && origin.filePath().startsWith("*jdk-source:");
        jdkSourceIsNative = inJdkSourceBuffer && looksLikeNativeJdkSource(origin.filePath(), origin.text());
        resetSearchAndResultState();
        String label = (origin.filePath() != null) ? "\"" + origin.filePath() + "\"" : "[新規バッファ]";
        setStatusMessage("← back to " + label + " line " + (origin.row() + 1));
    }

    /**
     * プロジェクト全体検索（{@link ProjectSymbolResolver}経由の全文grep）を
     * {@link #PROJECT_SYMBOL_SEARCH_TIMEOUT_MS} で打ち切る。
     * 作業ディレクトリの既定値はホームディレクトリになりうるため、巨大なディレクトリ配下では
     * 検索に非常に時間がかかることがある。EDT 上で無制限に待つと Shift+K のたびに
     * エディタ全体がフリーズしたように見えるため、タイムアウトしたら検索を諦めて
     * （バックグラウンドの検索スレッドは走らせたままにして）呼び出し側の JDK 側フォールバックに委ねる。
     */
    private <T> Optional<T> withSearchTimeout(Supplier<Optional<T>> task) {
        return Optional.ofNullable(withTimeout(() -> task.get().orElse(null)));
    }

    /**
     * プロジェクト全体検索を伴う任意の処理（{@link ProjectSearcher}/{@link ProjectSymbolResolver}経由）を
     * {@link #PROJECT_SYMBOL_SEARCH_TIMEOUT_MS} で打ち切る、{@link #withSearchTimeout} の汎用版。
     * タイムアウト・例外時は null を返す（呼び出し側で「検索できなかった」ことを判定する）。
     */
    private <T> T withTimeout(Supplier<T> task) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<T> future = null;
        try {
            future = executor.submit(task::get);
            return future.get(PROJECT_SYMBOL_SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // タイムアウトした検索タスクへ割り込み、ProjectSearcher 側の協調キャンセル
            //（walk の TERMINATE / 並列 grep タスクの早期リターン）を発動させる。
            // これが無いとタイムアウト後もバックグラウンドの検索が走り続け、
            // Shift+K を連打するとスレッドが積み重なる既知の残課題があった（軽量化 Phase 3 で解消）。
            future.cancel(true);
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            executor.shutdown();
        }
    }

    private void lookupJdkDocAndJump(String bufferTextSnapshot) {
        String word = wordAtCursor();
        if (word.isEmpty()) {
            setStatusMessage("No identifier at cursor");
            return;
        }

        // 自プロジェクト内のフィールド・定数・メソッド・クラス宣言を最優先で検索する
        // （jdk-source 疑似バッファ内では対象外。ネイティブトレース等の既存フローに任せる）
        if (!inJdkSourceBuffer) {
            // カーソルが "レシーバ.member" 上にある場合、まずレシーバの型を手掛かりに絞り込む
            // （型が分かれば同名の無関係なメンバーへの誤ジャンプを避けられる）。
            String[] qualified = classAndMethodAtCursor();
            if (qualified != null && tryResolveQualifiedMember(qualified[0], qualified[1], bufferTextSnapshot)) {
                return;
            }

            Optional<ProjectSymbolResolver.SymbolLocation> loc = withSearchTimeout(() ->
                projectSymbolResolver.resolve(getProjectRoot(), currentFilePath, bufferTextSnapshot, word));
            if (loc.isPresent()) {
                jumpToSymbolLocation(loc.get(), word);
                return;
            }

            // プロジェクト内シンボル（フィールド/メソッド/クラス）としても見つからない場合、
            // word 自身がローカル変数・引数の名前である可能性を試す。ローカル変数は
            // SourceAnalyzer のシンボル索引に含まれないため、上の resolve() では原理的に
            // 見つからない。"変数名.メソッド名" の変数名側（レシーバ）にカーソルがある
            // ケースはこれに該当し、従来は classAndMethodAtCursor() が member 側にしか
            // 反応しないため、レシーバへの K が何もヒットせず JDK 検索まで落ちて
            // "Not found" になっていた。
            if (jumpToLocalDeclaration(word)) {
                return;
            }
        }

        if (jdkIndex == null || !jdkIndex.isReady()) {
            setStatusMessage("JDK index building...");
            return;
        }

        // jdk-source 疑似バッファ内での K: C シンボル定義 → Java native トレースの順で試みる
        if (inJdkSourceBuffer && currentFilePath != null && currentFilePath.startsWith("*jdk-source:")) {

            // (A) C/C++ シンボル定義ジャンプ（lib/openjdk-native/ を検索）
            // 現在の疑似バッファが実際に C/C++ ソースを表示している場合のみ試みる。
            // Java クラスソース閲覧中（jdkSourceIsNative == false）にこれを行うと、
            // "gc" が native ソース内の無関係な識別子（例: "argc(" の部分文字列）に
            // 誤ってマッチし、全く関係ない箇所へジャンプしてしまうバグがあった。
            if (jdkSourceIsNative && sourceTracer.hasNativeSrcDir()) {
                // まず現在表示中ファイル自身を優先して探す。findCSymbol() は
                // lib/openjdk-native/ 全木を無順序に走査し最初に見つかった定義を返すため、
                // 同名の static ヘルパー関数が他ファイルにもあると無関係な別ファイルへ
                // 誤ジャンプしてしまう（JNIグルーコード・HotSpotいずれもありがちな命名）。
                // 現在のファイルパスは "*jdk-source:<相対パス>*" という命名規則
                // （openCSymbolBuffer() 参照）から復元できる。ただしこの相対パスは
                // CSymbolLocation.relativePath() が返す「nativeSrcDir の親ディレクトリ
                // からの相対パス」（例: "openjdk-native/java.base/share/native/..."）であり、
                // findCSymbolInFile() が期待する「nativeSrcDir 自身からの相対パス」
                // （例: "java.base/share/native/..."。EntryPointIndex 参照）とは
                // 先頭セグメント（nativeSrcDir のディレクトリ名）1つ分ずれているため、
                // それを取り除いてから渡す。
                String currentRelativePath = currentFilePath
                    .replaceFirst("^\\*jdk-source:", "")
                    .replaceAll("\\*$", "");
                Optional<OpenjdkSourceTracer.CSymbolLocation> loc = Optional.empty();
                Optional<Path> nativeSrcDir = sourceTracer.getNativeSrcDir();
                if (nativeSrcDir.isPresent()) {
                    String nativeSrcDirName = nativeSrcDir.get().getFileName().toString() + "/";
                    if (currentRelativePath.startsWith(nativeSrcDirName)) {
                        String withinNativeSrcDir = currentRelativePath.substring(nativeSrcDirName.length());
                        loc = sourceTracer.findCSymbolInFile(withinNativeSrcDir, word);
                    }
                }
                if (loc.isEmpty()) {
                    loc = sourceTracer.findCSymbol(word);
                }
                if (loc.isPresent()) {
                    openCSymbolBuffer(loc.get());
                    return;
                }
            }

            // (B) Java FQN バッファ内（Array.java 等）での native メソッドトレース
            String fqn = currentFilePath
                .replaceFirst("^\\*jdk-source:", "")
                .replaceAll("\\*$", "");
            if (!jdkSourceIsNative && fqn.contains(".") && !fqn.contains(" ")) {
                Optional<Class<?>> cls = jdkIndex.loadClass(fqn);
                if (cls.isPresent()) {
                    OpenjdkSourceTracer.TracingResult result = sourceTracer.trace(cls.get(), word);
                    if (result.isNative()) {
                        String simpleName = fqn.contains(".")
                            ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                        if (result.sourceFile().isPresent() && result.snippet().isPresent()) {
                            openJdkSourceBuffer(
                                "*jdk-source:" + simpleName + "." + word + "*",
                                "[native] " + result.jniMangledName() + "\n"
                                    + "Source: " + result.sourceFile().get() + "\n\n"
                                    + result.snippet().get(),
                                true
                            );
                        } else {
                            setStatusMessage(result.toStatusLine());
                        }
                        return;
                    }
                }
            }

            // (C) 非native Javaクラスソース内で、修飾なしの識別子（同一クラスの他メンバーへの
            // 無資格呼び出し。例: ImageIO.read(File) の本体内から同じクラスの
            // read(ImageInputStream) をオーバーロード呼び出しする "read(stream)" のような箇所）に
            // カーソルがある場合、現在表示中の疑似バッファ自身がそのクラスのソースなので、
            // そのままバッファ内を検索するだけで宣言（オーバーロード含む）へジャンプできる。
            // 上の (A)/(B) はどちらも native 実装の有無を判定するだけで、通常の非native
            // メソッド/フィールドの同一クラス内ジャンプには未対応だった。
            if (!jdkSourceIsNative && classAndMethodAtCursor() == null && jumpToMember(word)) {
                return;
            }
        }

        // カーソルが "ClassName.methodName" の methodName 上にある場合: native トレースを試みる
        String[] classAndMethod = classAndMethodAtCursor();
        if (classAndMethod != null && tryJdkMember(classAndMethod[0], classAndMethod[1])) {
            return;
        }

        List<String> candidates = jdkIndex.lookup(word);
        if (candidates.isEmpty()) {
            setStatusMessage("Not found in JDK: " + word);
            return;
        }
        String best = pickBestFqn(candidates);
        Optional<Class<?>> cls = jdkIndex.loadClass(best);
        String extra = candidates.size() > 1 ? " (+" + (candidates.size() - 1) + " more)" : "";

        // src.zip があればソースを疑似バッファで開く
        if (cls.isPresent() && sourceTracer.hasSrcZip()) {
            Optional<String> src = sourceTracer.readJavaSource(cls.get());
            if (src.isPresent()) {
                openJdkSourceBuffer("*jdk-source:" + best + "*", src.get());
                if (!extra.isEmpty()) setStatusMessage("K: opened " + best + extra);
                return;
            }
        }

        // フォールバック: ステータスバーに情報表示
        if (cls.isPresent()) {
            setStatusMessage(buildDocLine(best, cls.get(), extra));
        } else {
            setStatusMessage(best + " (cannot load)" + extra);
        }
    }

    /**
     * カーソルが "receiver.member" 上にある場合、receiver の型を手掛かりに member の宣言を検索する。
     * (a) receiver 自身が自プロジェクトのクラス名（static呼び出し）として解決できればそのクラスの
     *     ファイルに限定して member を検索する。
     * (b) それで見つからなければ receiver をローカル変数/引数/フィールドとみなし、
     *     {@link ReceiverTypeResolver} で宣言型を推定する。推定できた型が自プロジェクトのクラスなら
     *     そのクラスのファイルに限定して member を検索し、JDKのクラスなら既存の
     *     {@link #tryJdkMember(String, String)} に委譲する。
     * 解決できた場合 true（ジャンプ済み）、できなければ false（呼び出し側は名前だけの検索にフォールバックする）。
     */
    private boolean tryResolveQualifiedMember(String receiver, String member, String bufferTextSnapshot) {
        Optional<ProjectSymbolResolver.SymbolLocation> asStatic = withSearchTimeout(() ->
            projectSymbolResolver.resolveMemberInType(getProjectRoot(), currentFilePath, bufferTextSnapshot, receiver, member));
        if (asStatic.isPresent()) {
            jumpToSymbolLocation(asStatic.get(), member);
            return true;
        }

        Optional<String> type = receiverTypeResolver.resolveType(getLines(), cursorRow, receiver);
        if (type.isEmpty()) {
            return false;
        }
        String typeName = type.get();

        Optional<ProjectSymbolResolver.SymbolLocation> viaType = withSearchTimeout(() ->
            projectSymbolResolver.resolveMemberInType(getProjectRoot(), currentFilePath, bufferTextSnapshot, typeName, member));
        if (viaType.isPresent()) {
            jumpToSymbolLocation(viaType.get(), member);
            return true;
        }

        if (jdkIndex != null && jdkIndex.isReady() && tryJdkMember(typeName, member)) {
            return true;
        }
        return false;
    }

    /**
     * word がローカル変数・メソッド引数の名前だった場合、その宣言行へジャンプする。
     * ローカル変数はプロジェクト内シンボル索引（フィールド/メソッド/クラスのみ対象）にも
     * JDK索引にも存在しないため、"変数名.メソッド名" の変数名側にカーソルを置いて K を
     * 押した場合や、単に変数の使用箇所で K を押した場合に何もヒットせず失敗していた。
     * {@link ReceiverTypeResolver} と同じ「直近の宣言が最有力」という正規表現ヒューリスティックで
     * カーソル行から上方向に探し、見つかった行（同一ファイル内、ローカル変数はファイルを
     * 跨がないため）へジャンプする。見つかった場合 true、見つからなければ false。
     */
    private boolean jumpToLocalDeclaration(String word) {
        Optional<Integer> declRow = receiverTypeResolver.resolveDeclarationLine(getLines(), cursorRow, word);
        if (declRow.isEmpty()) {
            return false;
        }
        cursorRow = declRow.get();
        cursorCol = 0;
        setStatusMessage("→ " + word + " (local variable)  line " + (cursorRow + 1));
        return true;
    }

    /** ProjectSymbolResolver.SymbolLocation へジャンプし、ステータスバーに結果を表示する。 */
    private void jumpToSymbolLocation(ProjectSymbolResolver.SymbolLocation l, String word) {
        if (currentFilePath != null && l.filePath().equals(currentFilePath)) {
            cursorRow = l.lineNumber();
            cursorCol = 0;
        } else {
            loadFromFile(l.filePath());
            cursorRow = l.lineNumber();
            cursorCol = 0;
        }
        setStatusMessage("→ " + word + " (" + l.kind() + ")  "
            + l.filePath() + ":" + (l.lineNumber() + 1));
    }

    /**
     * className が JDK のクラスとして解決できる場合に限り、そのクラスにおける methodName
     * （メソッドまたはフィールド）の宣言へジャンプ（native ならトレース結果を表示）する。
     * className が JDK クラスとして解決できない場合は false を返す（呼び出し側は次の手段を試す）。
     */
    private boolean tryJdkMember(String className, String methodName) {
        List<String> classCandidates = jdkIndex.lookup(className);
        if (classCandidates.isEmpty()) {
            return false;
        }
        String fqn = pickBestFqn(classCandidates);
        Optional<Class<?>> cls = jdkIndex.loadClass(fqn);
        if (cls.isEmpty()) {
            return false;
        }
        OpenjdkSourceTracer.TracingResult result = sourceTracer.trace(cls.get(), methodName);
        if (result.isNative()) {
            if (result.sourceFile().isPresent() && result.snippet().isPresent()) {
                openJdkSourceBuffer(
                    "*jdk-source:" + className + "." + methodName + "*",
                    "[native] " + result.jniMangledName() + "\n"
                        + "Source: " + result.sourceFile().get() + "\n\n"
                        + result.snippet().get(),
                    true
                );
            } else {
                setStatusMessage(result.toStatusLine());
            }
            return true;
        }
        // non-native: ソースにジャンプ（メソッド宣言 → フィールド宣言の順で探す）
        Optional<String> src = sourceTracer.readJavaSource(cls.get());
        if (src.isPresent()) {
            openJdkSourceBuffer("*jdk-source:" + fqn + "*", src.get());
            jumpToMember(methodName);
            return true;
        }
        return false;
    }

    /** FQN 候補リストから java.lang > java.util > その他 の優先順で1件選ぶ。 */
    private String pickBestFqn(List<String> candidates) {
        return candidates.stream()
            .filter(f -> f.startsWith("java.lang.")).findFirst()
            .orElseGet(() -> candidates.stream()
                .filter(f -> f.startsWith("java.util.")).findFirst()
                .orElse(candidates.get(0)));
    }

    /** C シンボルの定義ファイルをフルで疑似バッファに開き、定義行へジャンプする。 */
    private void openCSymbolBuffer(OpenjdkSourceTracer.CSymbolLocation loc) {
        String content;
        try {
            content = Files.readString(loc.absolutePath(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            setStatusMessage("E: cannot read " + loc.relativePath() + ": " + e.getMessage());
            return;
        }
        openJdkSourceBuffer("*jdk-source:" + loc.relativePath() + "*", content, true);
        // 定義行へカーソルを移動
        cursorRow = loc.lineNumber();
        cursorCol = 0;
        setStatusMessage("→ line " + (loc.lineNumber() + 1) + "  [" + loc.relativePath() + "]  q: close");
    }

    /**
     * ":main <target>" コマンド。java/javac コマンドの実際の起動点（launcher entry point）へジャンプする。
     * ターゲット名と実際のジャンプ先の対応は EntryPointIndex に集約しており、
     * jar/javadoc/jshell 等への拡張はそちらにエントリを追加するだけでよい。
     */
    private void executeMain(String rawArg) {
        if (rawArg.isEmpty()) {
            setStatusMessage("E: :main requires a target (supported: "
                + String.join(", ", EntryPointIndex.supportedTargets()) + ")");
            return;
        }
        Optional<EntryPointIndex.Target> target = EntryPointIndex.lookup(rawArg);
        if (target.isEmpty()) {
            setStatusMessage("E: unknown :main target '" + rawArg + "' (supported: "
                + String.join(", ", EntryPointIndex.supportedTargets()) + ")");
            return;
        }
        switch (target.get()) {
            case EntryPointIndex.Target.NativeLauncher nl -> jumpToNativeLauncherEntry(nl);
            case EntryPointIndex.Target.JavaSource js -> jumpToJavaSourceEntry(js);
        }
    }

    private void jumpToNativeLauncherEntry(EntryPointIndex.Target.NativeLauncher nl) {
        if (!sourceTracer.hasNativeSrcDir()) {
            setStatusMessage("E: native JDK source not available (run scripts/setup.sh)");
            return;
        }
        Optional<OpenjdkSourceTracer.CSymbolLocation> loc =
            sourceTracer.findCSymbolInFile(nl.relativePath(), nl.symbol());
        if (loc.isEmpty()) {
            setStatusMessage("E: entry point '" + nl.symbol() + "' not found in " + nl.relativePath());
            return;
        }
        openCSymbolBuffer(loc.get());
    }

    private void jumpToJavaSourceEntry(EntryPointIndex.Target.JavaSource js) {
        if (!sourceTracer.hasSrcZip()) {
            setStatusMessage("E: JDK source (src.zip) not available (run scripts/setup.sh)");
            return;
        }
        Optional<String> src = sourceTracer.readJavaSourceByFqcn(js.moduleName(), js.fqcn());
        if (src.isEmpty()) {
            setStatusMessage("E: source for " + js.fqcn() + " not found in src.zip");
            return;
        }
        openJdkSourceBuffer("*jdk-source:" + js.fqcn() + "*", src.get());
        jumpToMember(js.memberName());
    }

    /**
     * jump.back で復元する jdk-source 疑似バッファのタイトル/内容から、
     * それが C/C++ ネイティブソースだったかを推測する（BufferSnapshot には
     * isNative フラグを保持していないため、既知のパターンから逆算する）。
     * 実ファイル（.c/.cpp/.h）は拡張子で、JNIスニペットは "[native] " プレフィックスで判定する。
     */
    private static boolean looksLikeNativeJdkSource(String title, String content) {
        String inner = title.replaceFirst("^\\*jdk-source:", "").replaceAll("\\*$", "");
        if (inner.endsWith(".c") || inner.endsWith(".cpp") || inner.endsWith(".h")) return true;
        return content.startsWith("[native] ");
    }

    /** JDK Javaソースの疑似バッファを開く。元バッファの状態を退避する。 */
    private void openJdkSourceBuffer(String title, String content) {
        openJdkSourceBuffer(title, content, false);
    }

    /**
     * JDK ソース疑似バッファを開く。元バッファの状態を退避する。
     * @param isNative 開く内容が C/C++ ネイティブソース（実ファイルまたはJNIスニペット）かどうか。
     *                 Java クラスソースを開く場合は false。
     */
    private void openJdkSourceBuffer(String title, String content, boolean isNative) {
        if (!inJdkSourceBuffer) {
            savedBuffer = buffer;
            savedFilePath = currentFilePath;
            savedCursorRow = cursorRow;
            savedCursorCol = cursorCol;
        }
        buffer = new UndoablePieceTable(content);
        currentFilePath = title;
        grepResults = null;
        fileNameResults = null;
        clearSearchHighlights();
        cursorRow = 0;
        cursorCol = 0;
        inJdkSourceBuffer = true;
        jdkSourceIsNative = isNative;
        setStatusMessage("q: close  [" + title + "]");
    }

    /** JDK ソース疑似バッファを閉じて元バッファに戻る。 */
    private void closeJdkSourceBuffer() {
        if (!inJdkSourceBuffer) return;
        buffer = savedBuffer != null ? savedBuffer : new UndoablePieceTable("");
        currentFilePath = savedFilePath;
        cursorRow = savedCursorRow;
        cursorCol = savedCursorCol;
        inJdkSourceBuffer = false;
        jdkSourceIsNative = false;
        savedBuffer = null;
        clearSearchHighlights();
        setStatusMessage("Returned from JDK source");
    }

    /** 疑似バッファ内でメソッド名の宣言行を探してカーソルを移動する。 */
    private boolean jumpToMethod(String methodName) {
        String[] lines = buffer.getText().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains(methodName + "(") &&
                    (line.contains("public") || line.contains("protected")
                     || line.contains("private") || line.contains("native"))) {
                cursorRow = i;
                cursorCol = 0;
                setStatusMessage("→ " + methodName + " (line " + (i + 1) + ")  q: close");
                return true;
            }
        }
        return false;
    }

    /** 疑似バッファ内でフィールド（定数含む）名の宣言行を探してカーソルを移動する。 */
    private boolean jumpToField(String fieldName) {
        Pattern wordBoundary = Pattern.compile("\\b" + Pattern.quote(fieldName) + "\\b");
        String[] lines = buffer.getText().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains(fieldName + "(")) continue; // メソッド呼び出し/宣言は除外
            if (!wordBoundary.matcher(line).find()) continue;
            if ((line.contains("public") || line.contains("protected")
                    || line.contains("private") || line.contains("static") || line.contains("final"))
                    && (line.contains(";") || line.contains("="))) {
                cursorRow = i;
                cursorCol = 0;
                setStatusMessage("→ " + fieldName + " (line " + (i + 1) + ")  q: close");
                return true;
            }
        }
        return false;
    }

    /**
     * メソッド宣言 → フィールド（定数）宣言の順で疑似バッファ内の宣言行へジャンプする。
     * 見つかってジャンプできた場合 true、見つからなかった場合 false を返す
     * （呼び出し側が他の解決手段へフォールバックできるようにするため。従来からの
     * 呼び出し元は戻り値を無視しており、その場合は失敗時に "not found" メッセージを
     * 表示する従来通りの挙動になる）。
     */
    private boolean jumpToMember(String name) {
        if (jumpToMethod(name)) return true;
        if (jumpToField(name)) return true;
        setStatusMessage("Declaration of " + name + " not found in source  q: close");
        return false;
    }

    /**
     * Javadoc サマリが取得できればそれを優先し、なければ JdkTypeInfo のフォールバック表示を返す。
     * suffix は "(+N more)" などの付加文字列。
     */
    private String buildDocLine(String fqn, Class<?> cls, String suffix) {
        Optional<String> summary = javadocReader.readSummary(fqn);
        if (summary.isPresent()) {
            String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
            return simpleName + ": " + summary.get() + suffix;
        }
        return JdkTypeInfo.from(cls).toStatusLine() + suffix;
    }

    /**
     * カーソルが "ClassName.methodName" の methodName 上にある場合、
     * [className, methodName] の配列を返す。そうでなければ null。
     */
    private String[] classAndMethodAtCursor() {
        String[] lines = getLines();
        if (cursorRow >= lines.length) return null;
        String line = lines[cursorRow];
        if (cursorCol >= line.length()) return null;
        char ch = line.charAt(cursorCol);
        if (!Character.isJavaIdentifierPart(ch)) return null;
        // メソッド名の開始位置を探す
        int start = cursorCol;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) start--;
        // 直前が '.' であることを確認
        if (start == 0 || line.charAt(start - 1) != '.') return null;
        // '.' の前にクラス名があることを確認
        int dotPos = start - 1;
        if (dotPos == 0 || !Character.isJavaIdentifierPart(line.charAt(dotPos - 1))) return null;
        int classEnd = dotPos;
        int classStart = classEnd - 1;
        while (classStart > 0 && Character.isJavaIdentifierPart(line.charAt(classStart - 1))) classStart--;
        // メソッド名
        int end = cursorCol;
        while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) end++;
        String className = line.substring(classStart, classEnd);
        String methodName = line.substring(start, end);
        if (className.isEmpty() || methodName.isEmpty()) return null;
        return new String[]{className, methodName};
    }

    // ---- Getter / Setter 自動生成 ----

    /** カーソル行のフィールド宣言を解析する（純粋ロジックは GetterSetterGenerator 側）。 */
    private String[] parseFieldAtCursor() {
        String[] lines = getLines();
        String line = cursorRow < lines.length ? lines[cursorRow] : "";
        return GetterSetterGenerator.parseFieldDeclaration(line);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** カーソル行フィールドの getter を生成してクラス末尾 '}' 直前に挿入する。 */
    private void generateGetter() {
        String[] field = parseFieldAtCursor();
        if (field == null) { statusMessage = "Getter: フィールド宣言が見つかりません"; syncCanvas(); return; }
        String type = field[0];
        String name = field[1];
        String prefix = type.equals("boolean") ? "is" : "get";
        String method = GetterSetterGenerator.buildGetter(type, name,
                GetterSetterGenerator.detectIndent(getLines()));
        insertBeforeLastBrace(method);
        statusMessage = prefix + capitalize(name) + "() を生成しました";
        syncCanvas();
    }

    /** カーソル行フィールドの setter を生成してクラス末尾 '}' 直前に挿入する。 */
    private void generateSetter() {
        String[] field = parseFieldAtCursor();
        if (field == null) { statusMessage = "Setter: フィールド宣言が見つかりません"; syncCanvas(); return; }
        String type = field[0];
        String name = field[1];
        String method = GetterSetterGenerator.buildSetter(type, name,
                GetterSetterGenerator.detectIndent(getLines()));
        insertBeforeLastBrace(method);
        statusMessage = "set" + capitalize(name) + "() を生成しました";
        syncCanvas();
    }

    /** getter と setter の両方を生成する。 */
    private void generateGetterAndSetter() {
        String[] field = parseFieldAtCursor();
        if (field == null) { statusMessage = "Getter/Setter: フィールド宣言が見つかりません"; syncCanvas(); return; }
        String type = field[0];
        String name = field[1];
        String prefix = type.equals("boolean") ? "is" : "get";
        String methods = GetterSetterGenerator.buildGetterAndSetter(type, name,
                GetterSetterGenerator.detectIndent(getLines()));
        insertBeforeLastBrace(methods);
        statusMessage = prefix + capitalize(name) + "()/set" + capitalize(name) + "() を生成しました";
        syncCanvas();
    }

    /**
     * 現在行より後にある診断の中で最も近い行へジャンプする（[g）。
     * 診断が空・canvas なし・見つからない場合はステータスメッセージを出す。
     */
    private List<CompileDiagnostic> currentDiagnostics() {
        return canvas != null ? canvas.getDiagnostics() : localDiagnostics;
    }

    private void jumpToNextDiagnostic() {
        List<CompileDiagnostic> diags = currentDiagnostics();
        if (diags.isEmpty()) { statusMessage = "診断なし"; syncCanvas(); return; }
        int next = -1;
        for (CompileDiagnostic d : diags) {
            if (d.lineNumber() > cursorRow) {
                if (next < 0 || d.lineNumber() < next) next = d.lineNumber();
            }
        }
        if (next < 0) {
            // 折り返し: 先頭の診断へ
            next = diags.stream().mapToInt(CompileDiagnostic::lineNumber).min().orElse(-1);
        }
        if (next >= 0) {
            cursorRow = next;
            cursorCol = 0;
            statusMessage = "";
        } else {
            statusMessage = "診断なし";
        }
        syncCanvas();
    }

    /**
     * 現在行より前にある診断の中で最も近い行へジャンプする（[d）。
     * 診断が空・canvas なし・見つからない場合はステータスメッセージを出す。
     */
    private void jumpToPrevDiagnostic() {
        List<CompileDiagnostic> diags = currentDiagnostics();
        if (diags.isEmpty()) { statusMessage = "診断なし"; syncCanvas(); return; }
        int prev = -1;
        for (CompileDiagnostic d : diags) {
            if (d.lineNumber() < cursorRow) {
                if (prev < 0 || d.lineNumber() > prev) prev = d.lineNumber();
            }
        }
        if (prev < 0) {
            // 折り返し: 末尾の診断へ
            prev = diags.stream().mapToInt(CompileDiagnostic::lineNumber).max().orElse(-1);
        }
        if (prev >= 0) {
            cursorRow = prev;
            cursorCol = 0;
            statusMessage = "";
        } else {
            statusMessage = "診断なし";
        }
        syncCanvas();
    }

    /** 未使用の import をすべて削除する（SPC+i+o / :oi）。 */
    /** Main.java の onOrganizeImports コールバックから EDT 上で呼ばれる：未使用 import 削除のみ。 */
    public void organizeImportsRemoveUnused() {
        if (autoImportHandler == null) return;
        List<String> removed = autoImportHandler.removeUnusedImports(buffer);
        if (removed.isEmpty()) {
            statusMessage = "import 整理完了（削除なし）";
        } else {
            statusMessage = removed.size() + " 件の import を削除しました";
        }
        syncCanvas();
    }

    private void organizeImports() {
        if (onOrganizeImports != null) {
            // Main.java 側でコンパイル→auto-import挿入→未使用削除の全処理を実行
            onOrganizeImports.run();
            return;
        }
        // コールバック未設定の場合は未使用 import 削除のみ（テスト環境等）
        if (autoImportHandler == null) {
            statusMessage = "E: AutoImportHandler が設定されていません";
            syncCanvas();
            return;
        }
        List<String> removed = autoImportHandler.removeUnusedImports(buffer);
        if (removed.isEmpty()) {
            statusMessage = "未使用 import なし";
        } else {
            statusMessage = removed.size() + " 件の import を削除しました";
        }
        syncCanvas();
    }

    /** 特定 FQN の import を削除する（:remove-import <fqn>）。 */
    private void executeRemoveImport(String fqn) {
        if (fqn.isEmpty()) {
            statusMessage = "E: FQN を指定してください";
            syncCanvas();
            return;
        }
        if (autoImportHandler == null) {
            statusMessage = "E: AutoImportHandler が設定されていません";
            syncCanvas();
            return;
        }
        boolean removed = autoImportHandler.removeImport(fqn, buffer);
        statusMessage = removed ? "import " + fqn + " を削除しました"
                                : "E: import " + fqn + " が見つかりません";
        syncCanvas();
    }

    /** ファイル末尾の '}' を探し、その直前にテキストを挿入する。 */
    private void insertBeforeLastBrace(String text) {
        String content = buffer.getText();
        int pos = content.lastIndexOf('}');
        if (pos < 0) {
            // '}' が見つからなければ末尾に追記
            buffer.insert(content.length(), text);
        } else {
            buffer.insert(pos, text);
        }
        // カーソルを挿入直後へ
        String newContent = buffer.getText();
        int insertedPos = (pos < 0 ? content.length() : pos) + text.length();
        // 挿入後の行列を再計算
        int row = 0, col = 0;
        for (int i = 0; i < insertedPos && i < newContent.length(); i++) {
            if (newContent.charAt(i) == '\n') { row++; col = 0; } else { col++; }
        }
        cursorRow = row;
        cursorCol = Math.max(0, col - 1);
    }

    /** カーソル位置の Java 識別子（単語）を返す。識別子がなければ空文字列。 */
    private String wordAtCursor() {
        String[] lines = getLines();
        if (cursorRow >= lines.length) return "";
        String line = lines[cursorRow];
        if (cursorCol >= line.length()) return "";
        // カーソル位置が識別子文字でなければ空
        char ch = line.charAt(cursorCol);
        if (!Character.isJavaIdentifierPart(ch)) return "";
        // 左方向に識別子の先頭を探す
        int start = cursorCol;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) start--;
        // 右方向に識別子の末尾を探す
        int end = cursorCol;
        while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) end++;
        return line.substring(start, end);
    }
}
