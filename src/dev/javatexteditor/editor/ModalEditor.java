package dev.javatexteditor.editor;

import dev.javatexteditor.analysis.AutoImportHandler;
import dev.javatexteditor.analysis.CompileDiagnostic;
import dev.javatexteditor.analysis.JdkClassIndex;
import dev.javatexteditor.analysis.JdkJavadocReader;
import dev.javatexteditor.analysis.JdkTypeInfo;
import dev.javatexteditor.analysis.OpenjdkSourceTracer;
import dev.javatexteditor.analysis.ProjectSymbolResolver;
import dev.javatexteditor.analysis.ReceiverTypeResolver;
import dev.javatexteditor.buffer.UndoablePieceTable;
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
import dev.javatexteditor.telescope.TelescopeItem;
import dev.javatexteditor.telescope.TelescopePicker;
import dev.javatexteditor.tutorial.Tutorial;
import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    private enum Mode { NORMAL, INSERT, COMMAND, VISUAL, VISUAL_LINE, VISUAL_BLOCK, SEARCH, FILESEARCH, TELESCOPE, IMPORT_SELECT, FILER }
    private enum FileSearchType { NAME, GREP }

    /** ソフトタブのインデント幅（スペース数）。 */
    private static final int TAB_WIDTH = 4;
    private static final String INDENT_UNIT = " ".repeat(TAB_WIDTH);
    /** 補完ポップアップに出す最大候補数（Ctrl+Space / Alt+/ 共通）。 */
    private static final int COMPLETION_MAX_RESULTS = 10;

    private UndoablePieceTable buffer;
    private final EditorCanvas canvas; // null の場合はGUIなし（テスト用）
    private final KeymapRegistry keymap = new KeymapRegistry();
    private Mode mode = Mode.NORMAL;
    // INSERT → NORMAL 復帰時に呼ばれるコールバック（バックグラウンドコンパイル等）
    private Runnable onReturnToNormal = null;
    // ファイル保存成功時に呼ばれるコールバック（バックグラウンドコンパイル等）
    private Runnable onSave = null;
    // Ctrl+Shift+O（organize imports）時に呼ばれるコールバック（コンパイル→auto-import）
    private Runnable onOrganizeImports = null;
    // handleAutoImport で全候補処理完了後に呼ぶコールバック（未使用 import 削除等）
    private Runnable onImportComplete = null;
    private int cursorRow = 0;
    private int cursorCol = 0;
    private int anchorRow = 0;
    private int anchorCol = 0;
    private String yankRegister = "";
    private enum YankType { CHAR, LINE, BLOCK }
    private YankType yankType = YankType.CHAR;
    private String pendingSequence = ""; // yy / dd / SPC+g+g 等の多打鍵シーケンス管理
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
    private Runnable closeBlockedCallback = null; // 最後の1ペインで :q を拒否するとき呼ぶ
    private Runnable splitHorizontalCallback = null; // sv: 左右分割
    private Runnable splitVerticalCallback   = null; // ss: 上下分割
    private Runnable movePanePrevCallback    = null; // sh/sk: 前のペインへ
    private Runnable movePaneNextCallback    = null; // sl/sj: 次のペインへ
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
    private String cdSavedBufferText = null;   // 選択中に退避した元バッファのテキスト
    private String cdSavedFilePath = null;
    private int cdSavedCursorRow = 0;
    private int cdSavedCursorCol = 0;
    private String cdSavedCommandText = ""; // キャンセル時に COMMAND モードへ復元する入力途中の文字列
    // :e タブ補完状態（:cd と同じく一時退避→復元パターン）
    private List<String> edCandidates = List.of(); // 候補ファイル/ディレクトリ名（末尾 "/" はディレクトリのみ）
    private String edCandidateParentPart = "";
    private boolean edSelectionActive = false;
    private String edSavedBufferText = null;
    private String edSavedFilePath = null;
    private int edSavedCursorRow = 0;
    private int edSavedCursorCol = 0;
    private String edSavedCommandText = "";
    // filer モード状態
    private List<DirEntry> filerEntries = List.of();
    private List<DirEntry> filerFiltered = List.of();
    private int filerSelectedIdx = 0;
    private boolean filerSearchMode = false;
    private final StringBuilder filerQuery = new StringBuilder();
    // jdk-source 疑似バッファ: K キーで開いた JDK ソース表示中に保持する情報
    private String savedBufferText = null;       // 元バッファのテキスト
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

    public void setExitCallback(Runnable callback) {
        this.exitCallback = callback;
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

    /** 現在開いているファイルのパスを返す（未設定の場合は null）。 */
    public String getCurrentFilePath() { return currentFilePath; }

    public void processKey(int keyCode, char keyChar, int modifiers) {
        // 最初のキー操作でスプラッシュ画面を消去する
        if (canvas != null && canvas.isShowSplash()) {
            canvas.setShowSplash(false);
        }
        if ((mode == Mode.VISUAL || mode == Mode.VISUAL_LINE || mode == Mode.VISUAL_BLOCK)
                && keyCode == KeyEvent.VK_ESCAPE) {
            mode = Mode.NORMAL;
            pendingSequence = "";
            syncCanvas();
            return;
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
            case IMPORT_SELECT -> processImportSelectKey(keyCode, modifiers);
            case FILER         -> processFilerKey(keyCode, keyChar, modifiers);
        }
        syncCanvas();
    }

    // -------------------------------------------------------------------------
    // NORMALモード処理
    // -------------------------------------------------------------------------

    private void processNormalKey(int keyCode, char keyChar, int modifiers) {
        boolean ctrlDown = (modifiers & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;

        // Ctrl+U: 前のバッファへ / Ctrl+P: 次のバッファへ
        if (ctrlDown && keyCode == KeyEvent.VK_U) {
            if (historyIdx > 0) {
                restoreBuffer(historyIdx - 1);
            } else {
                statusMessage = "これ以上前のバッファはありません";
            }
            return;
        }
        if (ctrlDown && keyCode == KeyEvent.VK_P) {
            if (historyIdx >= 0 && historyIdx < bufferHistory.size() - 1) {
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

        // 2打鍵シーケンス（yy / dd）の処理
        if (!pendingSequence.isEmpty()) {
            String seq = pendingSequence;
            pendingSequence = "";
            statusMessage = "";
            char prev = seq.charAt(0);
            if (prev == 'y' && matches(keyCode, keyChar, KeyEvent.VK_Y, 'y')) { yankCurrentLine(); return; }
            if (prev == 'd' && matches(keyCode, keyChar, KeyEvent.VK_D, 'd')) { deleteCurrentLine(); return; }
            if (prev == 'g' && matches(keyCode, keyChar, KeyEvent.VK_G, 'g')) { moveFileStart(); return; }
            if (prev == 'g' && keyChar == 'r') { goToReferences(false); return; }
            // gR（Shift+R）: bang付き。node_modules 等のデフォルトスキップ対象も含め全ファイルを検索する
            if (prev == 'g' && keyChar == 'R') { goToReferences(true); return; }
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
            // \f: ファイル名検索, \g: ファイル内容grep
            if (prev == '\\') {
                if (matches(keyCode, keyChar, KeyEvent.VK_F, 'f')) { enterFileSearch(FileSearchType.NAME); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_G, 'g')) { enterFileSearch(FileSearchType.GREP); return; }
                // マッチしない場合は通常処理へ
            }
            // [g / [d: 診断ジャンプシーケンス
            if (seq.equals("[")) {
                if (matches(keyCode, keyChar, KeyEvent.VK_G, 'g')) { jumpToNextDiagnostic(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_D, 'd')) { jumpToPrevDiagnostic(); return; }
                // マッチしない場合は通常処理へ
            }
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
            case "paste.after" -> pasteAfter();
            case "paste.before" -> pasteBefore();
            case "yank.pending" -> pendingSequence = "y";
            case "delete.pending" -> pendingSequence = "d";
            case "goto.pending"   -> { pendingSequence = "g"; statusMessage = "g-"; }
            case "diag.pending"   -> { pendingSequence = "["; statusMessage = "[-"; }
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
            case "organize.imports" -> organizeImports();
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
                    case "organize.imports" -> organizeImports();
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
        if (wordIndex != null && wordIndex.isReady()) {
            for (dev.javatexteditor.analysis.CompletionItem item : queryWordCompletion(prefix)) {
                if (seen.add(item.label())) merged.add(item);
            }
        }
        if (completionIndex != null && completionIndex.isReady() && merged.size() < COMPLETION_MAX_RESULTS) {
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
     * prefix 自体（カーソル直前の、今まさに入力中の未完成な語）は bufferWords から除く。
     * 除かないとカーソル位置の識別子トークンが常に「prefix と完全一致する単語」として
     * 候補に混入し、何も入力していないのに補完候補が出る／選択しても何も変わらない、
     * という無意味な結果になる。
     */
    private java.util.List<dev.javatexteditor.analysis.CompletionItem> queryWordCompletion(String prefix) {
        java.util.Set<String> bufferWords = dev.javatexteditor.analysis.WordIndex.extractWords(buffer.getText());
        bufferWords.remove(prefix);
        java.util.List<String> words = wordIndex.query(prefix, COMPLETION_MAX_RESULTS, bufferWords);
        java.util.List<dev.javatexteditor.analysis.CompletionItem> items = new java.util.ArrayList<>(words.size());
        for (String w : words) {
            items.add(new dev.javatexteditor.analysis.CompletionItem(w, "wd"));
        }
        return items;
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
            if (mode == Mode.COMMAND) mode = Mode.NORMAL;

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
        searchMatches = List.of();
        currentMatchIdx = -1;
    }

    /**
     * 複数候補が見つかった場合、telescope オーバーレイではなく既存の
     * *grep* や jdk-source と同様の「疑似バッファ」としてエディタ画面上に候補一覧を表示する。
     * 現在編集中のバッファは cdSaved* に退避し、Enter で選択 / q でキャンセルすると復元する。
     */
    private void openCdCandidateBuffer(String originalCmd, String parentPart, List<String> candidates) {
        cdSavedBufferText = buffer.getText();
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
        buffer = new UndoablePieceTable(cdSavedBufferText != null ? cdSavedBufferText : "");
        currentFilePath = cdSavedFilePath;
        cursorRow = cdSavedCursorRow;
        cursorCol = cdSavedCursorCol;
        resetSearchAndResultState();
        cdSelectionActive = false;
        cdSavedBufferText = null;
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
        edSavedBufferText = buffer.getText();
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
            if (mode == Mode.COMMAND) mode = Mode.NORMAL;
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
        buffer = new UndoablePieceTable(edSavedBufferText != null ? edSavedBufferText : "");
        currentFilePath = edSavedFilePath;
        cursorRow = edSavedCursorRow;
        cursorCol = edSavedCursorCol;
        resetSearchAndResultState();
        edSelectionActive = false;
        edSavedBufferText = null;
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
    // TELESCOPE モード処理（SPC+f / SPC+/ / SPC+b）
    // -------------------------------------------------------------------------

    private void enterTelescope(String pickerType) {
        Path baseDir = getProjectRoot();
        switch (pickerType) {
            case "files"   -> telescopePicker = new FilePicker(baseDir);
            case "grep"    -> telescopePicker = new GrepPicker(baseDir);
            case "buffers" -> {
                List<BufferPicker.BufferEntry> entries = (bufferListSupplier != null)
                    ? bufferListSupplier.get() : List.of();
                telescopePicker = new BufferPicker(entries);
            }
            default -> { return; }
        }
        telescopeQuery.setLength(0);
        telescopeSelectedIdx = 0;
        telescopeResults = telescopePicker.filter("");
        mode = Mode.TELESCOPE;
        statusMessage = "";
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
        // Ctrl+N / Ctrl+P でリスト移動
        boolean ctrlDown = (modifiers & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;
        if (ctrlDown && keyCode == KeyEvent.VK_N) {
            moveTelescope(1); return;
        }
        if (ctrlDown && keyCode == KeyEvent.VK_P) {
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

    private void moveTelescope(int delta) {
        if (telescopeResults.isEmpty()) return;
        telescopeSelectedIdx = Math.max(0, Math.min(telescopeResults.size() - 1,
            telescopeSelectedIdx + delta));
    }

    private void refreshTelescope() {
        if (telescopePicker == null) return;
        telescopeResults = telescopePicker.filter(telescopeQuery.toString());
        telescopeSelectedIdx = 0;
    }

    private void openTelescopeSelection() {
        if (telescopePicker == null || telescopeResults.isEmpty()) { exitTelescope(); return; }
        TelescopeItem item = telescopeResults.get(telescopeSelectedIdx);
        exitTelescope();
        if (item.filePath() == null) return;
        try {
            Path target = Path.of(item.filePath());
            String content = Files.readString(target).replace("\r\n", "\n");
            buffer = new UndoablePieceTable(content);
            currentFilePath = target.toString();
            fileNameResults = null;
            grepResults = null;
            cursorRow = Math.max(0, item.lineNumber());
            cursorCol = 0;
            statusMessage = "\"" + target.getFileName() + "\" opened";
            if (onFileOpened != null) {
                onFileOpened.accept(new BufferPicker.BufferEntry(target.getFileName().toString(), currentFilePath));
            }
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
        }
    }

    /** Ctrl+U / Ctrl+P: バッファレジストリ内で delta 分移動したバッファを開く。 */
    private void switchToRelativeBuffer(int delta) {
        if (bufferListSupplier == null) return;
        List<BufferPicker.BufferEntry> entries = bufferListSupplier.get();
        if (entries.isEmpty()) return;
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
            : Math.floorMod(currentIdx + delta, entries.size());
        BufferPicker.BufferEntry target = entries.get(nextIdx);
        if (target.filePath() == null) return;
        try {
            Path p = Path.of(target.filePath());
            String content = Files.readString(p).replace("\r\n", "\n");
            buffer = new UndoablePieceTable(content);
            currentFilePath = p.toString();
            fileNameResults = null;
            grepResults = null;
            cursorRow = 0;
            cursorCol = 0;
            statusMessage = "\"" + p.getFileName() + "\" switched";
            if (onFileOpened != null) {
                onFileOpened.accept(new BufferPicker.BufferEntry(p.getFileName().toString(), currentFilePath));
            }
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
        }
    }

    private void exitTelescope() {
        mode = Mode.NORMAL;
        telescopePicker = null;
        telescopeResults = List.of();
        telescopeQuery.setLength(0);
        telescopeSelectedIdx = 0;
        if (canvas != null) canvas.setTelescopeState(false, "", "", List.of(), 0, "");
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
            String content = Files.readString(target).replace("\r\n", "\n");
            buffer = new UndoablePieceTable(content);
            currentFilePath = target.toString();
            fileNameResults = null;
            cursorRow = 0;
            cursorCol = 0;
            statusMessage = "\"" + relPath + "\" opened";
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
            p = Pattern.compile(pattern);
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
        if (cmd.equals("w")) {
            saveToFile(currentFilePath);
        } else if (cmd.startsWith("w ")) {
            String path = cmd.substring(2).trim();
            if (saveToFile(path)) {
                currentFilePath = path;
            }
        } else if (cmd.equals("e") || cmd.equals("enew")) {
            newBuffer();
        } else if (cmd.startsWith("e ")) {
            String path = cmd.substring(2).trim();
            loadFromFile(path);
        } else if (cmd.equals("tutor") || cmd.equals("Tutor") || cmd.equals("tutorial")) {
            openTutorial();
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
        } else if (cmd.equals("pwd")) {
            statusMessage = getProjectRoot().toString();
        } else if (cmd.startsWith("cd ")) {
            changeDirectory(cmd.substring(3).trim());
        } else if (cmd.equals("sp") || cmd.equals("split")) {
            if (splitVerticalCallback != null) splitVerticalCallback.run();
        } else if (cmd.equals("vs") || cmd.equals("vsplit") || cmd.equals("vsp")) {
            if (splitHorizontalCallback != null) splitHorizontalCallback.run();
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
        } else {
            statusMessage = "E: unknown command '" + cmd + "'";
        }
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
            Files.writeString(targetPath, buffer.getText());
            statusMessage = "\"" + targetPath + "\" written";
            if (onSave != null) onSave.run();
            return true;
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
            return false;
        }
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

        // ~ を展開
        String expanded = expandHome(pathSpec);

        // 絶対パスか相対パスか判定
        Path target = Path.of(expanded);
        if (target.isAbsolute()) {
            return target.toString();
        }

        // 相対パスの場合、projectRoot を基準に解決
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

    private void loadFromFile(String path) {
        Path p = Path.of(path);
        if (!Files.exists(p)) {
            pushBuffer();
            buffer = new UndoablePieceTable("");
            currentFilePath = path;
            cursorRow = 0;
            cursorCol = 0;
            resetSearchAndResultState();
            statusMessage = "\"" + path + "\" [新規ファイル]";
            return;
        }
        try {
            pushBuffer();
            String content = Files.readString(p).replace("\r\n", "\n");
            buffer = new UndoablePieceTable(content);
            currentFilePath = path;
            cursorRow = 0;
            cursorCol = 0;
            resetSearchAndResultState();
            statusMessage = "\"" + path + "\" opened";
            if (onFileOpened != null) {
                String name = Path.of(path).getFileName().toString();
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
            String content = Files.readString(target).replace("\r\n", "\n");
            buffer = new UndoablePieceTable(content);
            currentFilePath = target.toString();
            inJdkSourceBuffer = false;
            grepResults = null;
            // 目的の行へジャンプ（1-indexed → 0-indexed）
            cursorRow = Math.max(0, r.lineNumber() - 1);
            cursorCol = 0;
            statusMessage = "\"" + r.filePath() + "\" line " + r.lineNumber();
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // VISUALモード処理（文字単位）
    // -------------------------------------------------------------------------

    private void processVisualKey(int keyCode, char keyChar, int modifiers) {
        String action = keymap.resolve(KeymapRegistry.Mode.VISUAL, keyCode, keyChar, modifiers);
        if (action == null) return;

        Runnable custom = keymap.getCustomAction(action);
        if (custom != null) { custom.run(); return; }

        switch (action) {
            case "enter.normal" -> mode = Mode.NORMAL;
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
                mode = Mode.NORMAL;
            }
            case "delete" -> {
                yankRegister = getSelectedText();
                yankType = YankType.CHAR;
                deleteSelected();
                mode = Mode.NORMAL;
                clampCursorForNormal();
            }
        }
    }

    // -------------------------------------------------------------------------
    // VISUAL LINEモード処理（行単位）
    // -------------------------------------------------------------------------

    private void processVisualLineKey(int keyCode, char keyChar, int modifiers) {
        String action = keymap.resolve(KeymapRegistry.Mode.VISUAL_LINE, keyCode, keyChar, modifiers);
        if (action == null) return;

        Runnable custom = keymap.getCustomAction(action);
        if (custom != null) { custom.run(); return; }

        switch (action) {
            case "enter.normal" -> mode = Mode.NORMAL;
            case "cursor.left"  -> moveCursor(0, -1);
            case "cursor.right" -> moveCursor(0, 1);
            case "cursor.down"  -> moveCursor(1, 0);
            case "cursor.up"    -> moveCursor(-1, 0);
            case "file.end"     -> moveFileEnd();
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
                mode = Mode.NORMAL;
            }
            case "delete" -> {
                int r1 = Math.min(anchorRow, cursorRow);
                int r2 = Math.max(anchorRow, cursorRow);
                yankRegister = buildLineRangeText(r1, r2);
                yankType = YankType.LINE;
                deleteLineRange(r1, r2);
                mode = Mode.NORMAL;
            }
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
            mode = Mode.NORMAL;
            clampCursorForNormal();
            return;
        }

        String action = keymap.resolve(KeymapRegistry.Mode.VISUAL_BLOCK, keyCode, keyChar, modifiers);
        if (action == null) return;

        Runnable custom = keymap.getCustomAction(action);
        if (custom != null) { custom.run(); return; }

        switch (action) {
            case "enter.normal" -> mode = Mode.NORMAL;
            case "cursor.left"  -> moveCursor(0, -1);
            case "cursor.right" -> moveCursor(0, 1);
            case "cursor.down"  -> moveCursor(1, 0);
            case "cursor.up"    -> moveCursor(-1, 0);
            case "yank" -> {
                yankRegister = buildBlockText();
                yankType = YankType.BLOCK;
                cursorRow = Math.min(anchorRow, cursorRow);
                cursorCol = Math.min(anchorCol, cursorCol);
                mode = Mode.NORMAL;
                clampCursorForNormal();
            }
            case "delete" -> {
                yankRegister = buildBlockText();
                yankType = YankType.BLOCK;
                deleteBlock();
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

    private String[] getLines() {
        return buffer.getText().split("\n", -1);
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
    // FILERモード処理（:cd 実行後に表示されるディレクトリ一覧オーバーレイ）
    // -------------------------------------------------------------------------

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
            enterFiler();
        } catch (Exception ex) {
            statusMessage = "E: " + ex.getMessage();
        }
    }

    private void processFilerKey(int keyCode, char keyChar, int modifiers) {
        boolean ctrlDown = (modifiers & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0;

        if (filerSearchMode) {
            if (keyCode == KeyEvent.VK_ESCAPE) {
                filerSearchMode = false;
                filerQuery.setLength(0);
                filerFiltered = filerEntries;
                filerSelectedIdx = 0;
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
                }
                return;
            }
            if (ctrlDown && keyCode == KeyEvent.VK_N) { moveSelection(1);  return; }
            if (ctrlDown && keyCode == KeyEvent.VK_P) { moveSelection(-1); return; }
            if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ' && !ctrlDown) {
                filerQuery.append(keyChar);
                filerFiltered = DirectoryLister.filterEntries(filerEntries, filerQuery.toString());
                filerSelectedIdx = 0;
            }
        } else {
            if (keyCode == KeyEvent.VK_ESCAPE) {
                mode = Mode.NORMAL;
                return;
            }
            if (keyCode == KeyEvent.VK_ENTER) {
                openSelectedEntry();
                return;
            }
            if (ctrlDown && keyCode == KeyEvent.VK_N) { moveSelection(1);  return; }
            if (ctrlDown && keyCode == KeyEvent.VK_P) { moveSelection(-1); return; }
            if (keyChar == '/') {
                filerSearchMode = true;
                filerQuery.setLength(0);
            }
        }
    }

    private void moveSelection(int delta) {
        if (filerFiltered.isEmpty()) return;
        filerSelectedIdx = Math.max(0, Math.min(filerFiltered.size() - 1, filerSelectedIdx + delta));
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
            mode = Mode.NORMAL;
            loadFromFile(entry.path().toString());
        }
    }

    private String buildFilerPreview() {
        if (filerFiltered.isEmpty()) {
            return filerSearchMode ? "(no match)" : "(empty)";
        }
        DirEntry entry = filerFiltered.get(Math.min(filerSelectedIdx, filerFiltered.size() - 1));
        try {
            if (entry.kind() == DirEntry.Kind.DIRECTORY) {
                List<DirEntry> children = DirectoryLister.listDirectoryEntries(entry.path());
                if (children.isEmpty()) return "(empty directory)";
                StringBuilder sb = new StringBuilder();
                int limit = Math.min(children.size(), 20);
                for (int i = 0; i < limit; i++) {
                    DirEntry c = children.get(i);
                    sb.append(c.kind() == DirEntry.Kind.DIRECTORY ? c.name() + "/" : c.name()).append('\n');
                }
                if (children.size() > limit) sb.append("... (").append(children.size() - limit).append(" more)");
                return sb.toString();
            } else {
                StringBuilder sb = new StringBuilder();
                try (var reader = Files.newBufferedReader(entry.path())) {
                    String line;
                    int count = 0;
                    while (count < 30 && (line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                        count++;
                    }
                }
                return sb.toString();
            }
        } catch (IOException e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    private void syncCanvas() {
        if (canvas != null) {
            canvas.setText(buffer.getText());
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
            String[] lines = buffer.getText().split("\n", -1);
            String curLine = (cursorRow < lines.length) ? lines[cursorRow] : "";
            canvas.ensureCursorColVisible(cursorCol, curLine);
            if (mode == Mode.COMMAND) {
                canvas.setCommandLineText(":" + commandBuffer.toString());
            } else if (mode == Mode.SEARCH) {
                canvas.setCommandLineText("/" + searchBuffer.toString());
            } else if (mode == Mode.FILESEARCH) {
                String prefix = (fileSearchType == FileSearchType.NAME) ? "\\f" : "\\g";
                canvas.setCommandLineText(prefix + fileSearchBuffer.toString());
            } else if (!statusMessage.isEmpty()) {
                canvas.setCommandLineText(statusMessage);
            } else {
                canvas.setCommandLineText(null);
            }

            // telescope オーバーレイ更新
            if (mode == Mode.TELESCOPE && telescopePicker != null) {
                String preview = telescopeResults.isEmpty() ? "" :
                    telescopePicker.preview(telescopeResults.get(telescopeSelectedIdx));
                canvas.setTelescopeState(true, telescopePicker.title(),
                    telescopeQuery.toString(), telescopeResults, telescopeSelectedIdx, preview);
            } else if (mode == Mode.IMPORT_SELECT) {
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
            } else if (mode == Mode.FILER) {
                // filer オーバーレイ: DirEntry → TelescopeItem に変換して既存描画を再利用
                List<TelescopeItem> items = new ArrayList<>();
                for (DirEntry e : filerFiltered) {
                    String display = e.kind() == DirEntry.Kind.DIRECTORY ? e.name() + "/" : e.name();
                    items.add(new TelescopeItem(display, e.path().toString(), 0, 0));
                }
                Path root = getProjectRoot();
                String dirName = root.getFileName() != null ? root.getFileName().toString() : root.toString();
                String filerTitle = filerSearchMode ? dirName + " /" : dirName;
                String filerQ = filerSearchMode ? filerQuery.toString() : "";
                canvas.setTelescopeState(true, filerTitle, filerQ, items, filerSelectedIdx, buildFilerPreview());
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
    public boolean isCompletionActive()   { return completionActive; }
    public java.util.List<dev.javatexteditor.analysis.CompletionItem> getCompletionItems() { return completionItems; }
    public boolean isCdSelectionActive()  { return cdSelectionActive; }
    public List<String> getCdCandidates() { return cdCandidates; }
    public boolean isEditSelectionActive() { return edSelectionActive; }
    public List<String> getEditCandidates() { return edCandidates; }
    public void setBuffer(UndoablePieceTable newBuffer) { this.buffer = newBuffer; }
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
    private Path getProjectRoot() {
        return (projectRoot != null) ? projectRoot : Path.of(System.getProperty("user.dir"));
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
            autoImportHandler.resolveCandidates(diags, buffer.getText());
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

    private void processImportSelectKey(int keyCode, int modifiers) {
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
        if ((ctrlDown && keyCode == KeyEvent.VK_N) || keyCode == KeyEvent.VK_DOWN) {
            if (importSelectIdx < importSelectFqns.size() - 1) importSelectIdx++;
            return;
        }
        if ((ctrlDown && keyCode == KeyEvent.VK_P) || keyCode == KeyEvent.VK_UP) {
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
        lookupJdkDocAndJump(before.text());
        boolean moved = cursorRow != before.row() || cursorCol != before.col()
            || !java.util.Objects.equals(currentFilePath, before.filePath());
        if (moved) {
            lastJumpOrigin = before;
        }
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
        try {
            Future<T> future = executor.submit(task::get);
            return future.get(PROJECT_SYMBOL_SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
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
                Optional<OpenjdkSourceTracer.CSymbolLocation> loc = sourceTracer.findCSymbol(word);
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
            savedBufferText = buffer.getText();
            savedFilePath = currentFilePath;
            savedCursorRow = cursorRow;
            savedCursorCol = cursorCol;
        }
        buffer = new UndoablePieceTable(content);
        currentFilePath = title;
        grepResults = null;
        fileNameResults = null;
        cursorRow = 0;
        cursorCol = 0;
        inJdkSourceBuffer = true;
        jdkSourceIsNative = isNative;
        setStatusMessage("q: close  [" + title + "]");
    }

    /** JDK ソース疑似バッファを閉じて元バッファに戻る。 */
    private void closeJdkSourceBuffer() {
        if (!inJdkSourceBuffer) return;
        buffer = new UndoablePieceTable(savedBufferText != null ? savedBufferText : "");
        currentFilePath = savedFilePath;
        cursorRow = savedCursorRow;
        cursorCol = savedCursorCol;
        inJdkSourceBuffer = false;
        jdkSourceIsNative = false;
        savedBufferText = null;
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

    /** メソッド宣言 → フィールド（定数）宣言の順で疑似バッファ内の宣言行へジャンプする。 */
    private void jumpToMember(String name) {
        if (jumpToMethod(name)) return;
        if (jumpToField(name)) return;
        setStatusMessage("Declaration of " + name + " not found in source  q: close");
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
