package dev.javatexteditor.ui;

import dev.javatexteditor.analysis.CompileDiagnostic;
import dev.javatexteditor.analysis.DiagnosticKind;
import dev.javatexteditor.system.SystemStatsMonitor;
import dev.javatexteditor.telescope.TelescopeItem;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyEvent;
import java.awt.font.TextHitInfo;
import java.awt.im.InputContext;
import java.awt.im.InputMethodRequests;
import java.awt.image.BufferedImage;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.text.CharacterIterator;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.function.Consumer;

public class EditorCanvas extends JPanel implements InputMethodListener {

    private String text = "";
    // text の行分割結果のキャッシュ。setText() でのみ再計算し、
    // paintComponent 内で毎回 text.split() を呼ぶコスト（数十万行規模のファイルで顕著）を避ける。
    private String[] cachedLines = { "" };
    private int cursorRow = 0;
    private int cursorCol = 0;
    // (行数:トータル文字数) 形式のカーソル位置ラベル。ModalEditor.syncCanvas() が
    // キー入力1回につき1度だけ計算してキャッシュを差し替える。30fpsのanimTimerによる
    // repaintのたびにここで再計算すると数十万行規模のファイルで重くなるため、
    // 描画側（drawStatusLine）は保持済みの文字列をそのまま描くだけにする。
    private String cursorPositionLabel = "(1:1)";
    private boolean insertMode = false;
    private boolean visualMode = false;
    private boolean visualLineMode = false;
    private boolean visualBlockMode = false;
    private Theme theme = Theme.LIGHT_MODE;
    // 構文ハイライト（SyntaxHighlighter参照）。言語はModalEditor.syncCanvas()経由でセットされる。
    private SourceLanguage language = SourceLanguage.NONE;
    private static final boolean[] EMPTY_BOOLEAN_ARRAY = new boolean[0];
    // 行 i がブロックコメントの内側から始まるかの事前計算結果。setText()でlines参照または
    // languageが変わった時だけ再計算する（Phase 2のcanvasTextCacheと同じ参照一致による失効判定）。
    private boolean[] blockCommentStartsAt = EMPTY_BOOLEAN_ARRAY;
    private String[] blockCommentLinesOwner = null;
    private SourceLanguage blockCommentLangOwner = null;
    private int scrollRow = 0;
    private int scrollCol = 0;              // 横スクロール（セル単位）
    // :wrap / :nowrap（画面端での折り返し表示）。true時は横スクロール(scrollCol)を使わず、
    // 長い行を視覚的に複数のスクリーン行へ折り返して表示する。詳細は
    // .claude/skills/gui-rendering-pipeline/SKILL.md「:wrap / :nowrap（画面端での折り返し表示）」参照。
    private boolean wrapEnabled = false;
    private int cachedLineHeight = 20;      // 初回 paint 前の近似値
    private int cachedCharWidth  = 10;      // 初回 paint 前の近似値
    private String commandLineText = null;  // null = 通常のモード表示
    private int selAnchorRow = -1;
    private int selAnchorCol = -1;
    private int selCursorRow = -1;
    private int selCursorCol = -1;

    // スプラッシュ画面フラグ（true のとき通常テキストの代わりにスプラッシュを描画）
    private boolean showSplash = false;

    // 画像プレビュー（C2方式: EditorCanvas単一のまま内部に画像描画モードを持つ。CardLayout不採用）。
    // 実際の拡縮・描画ロジックは ImageRenderer に委譲する。scrollRow/scrollCol は既存の
    // テキストスクロールと全く同じフィールドをパンオフセット（セル単位）として流用する（F3）。
    private BufferedImage imageBuffer = null;
    private boolean imageViewActive = false;
    private boolean imageAutoFit = true;
    private double imageZoom = ImageRenderer.DEFAULT_ZOOM;
    private boolean imageLoading = false;

    // IME変換中の未確定文字列（preedit）。確定前のためバッファには含まれないが、
    // カーソル位置にオーバーレイ表示することでリアルタイムに何を入力中か分かるようにする。
    // ネイティブIME側の候補ウィンドウ（getTextLocation参照）とは表示位置を意図的にずらし、
    // 重ならないようにしている。
    private String composedText = "";
    // IMEが確定した文字列を呼び出し側（Main.java）へ通知するコールバック
    private Consumer<String> imeCommitHandler;

    // 検索ハイライト: 各要素 {row, startCol, endCol}（endCol は exclusive）
    private List<int[]> searchHighlights = List.of();
    private static final Color SEARCH_HIGHLIGHT_COLOR = new Color(0xFF, 0xE0, 0x00, 0x90);

    // 入力補完ポップアップ状態
    private CompletionView completion = CompletionView.hidden();
    /**
     * 補完ポップアップに一度に見せる候補数。これを超える分はスクロールで見せる
     * （IntelliJ IDEA の候補一覧も同様に、件数に関わらず高さを一定に保つ）。
     */
    private static final int COMPLETION_VISIBLE_ROWS = 10;

    // telescope オーバーレイ状態
    private TelescopeView telescope = TelescopeView.hidden();

    // 診断情報（エラー・警告）。空リストのときはガターを描画しない。
    private List<CompileDiagnostic> diagnostics = List.of();
    // 行番号 → 最も優先度の高い診断種別（ERROR > WARNING）
    private Map<Integer, DiagnosticKind> diagByLine = Map.of();

    // F10/F11: *compile*/*run* 疑似バッファのリアルタイムログ表示用。
    // 標準エラー出力・ERROR診断の行番号集合（この行だけ ERROR_COLOR で描画する）。
    private Set<Integer> errorLines = Set.of();

    // 半角ASCIIフォントのセルサイズ（Ctrl+Shift+矢印で変更可能）。
    // cellW/cellH は「今アクティブなフォントの現在サイズ」を保持する唯一の実体。
    // 2026-08-04までは setFontChoice() でフォントごとに独立したサイズを退避・復元していたが、
    // 「フォントファミリーの変更はサイズに影響しない（常に直前のサイズを引き継ぐ）」という
    // 仕様に変更したため退避機構は廃止した（経緯は decision-log.md 参照）。
    private int cellW = MiscFixedBold9x15.BASE_CELL_W;
    private int cellH = MiscFixedBold9x15.BASE_CELL_H;

    // Ctrl+Shift+矢印でセルサイズを変更した直後、現在の幅×高さ(px)を3秒間だけ
    // 画面右上に表示して自動的に消えるオーバーレイ。sizeOverlayHideTimerは
    // 変更のたびにrestart()し、直近の変更から3秒間だけ表示され続けるようにする。
    private boolean sizeOverlayVisible = false;
    private final Timer sizeOverlayHideTimer = new Timer(3000, e -> {
        sizeOverlayVisible = false;
        requestRepaint();
    });

    // 半角ASCIIは既定でX11 misc-fixed Bold 9x15（実機xtermの `ps` 出力から特定した本物の
    // ビットマップデータ）を cellW×cellH に合わせて縦横独立にニアレストネイバー拡縮して
    // ラスタライズする（MiscFixedBold9x15参照）。:font コマンド（setFontChoice）で
    // IbmPlexMonoFont（TTFベクター）へ切り替え可能。
    private FontChoice fontChoice = FontChoice.MISC_FIXED;
    private MonoFont bitmapFont = MiscFixedBold9x15.INSTANCE;

    // グリフキャッシュ: codePoint → レンダリング済み BufferedImage（透明背景・fg色）
    // セルサイズまたはテーマが変わったら invalidateGlyphCache() でクリアする
    private final Map<Integer, BufferedImage> glyphCacheFg  = new HashMap<>();
    private final Map<Integer, BufferedImage> glyphCacheBg  = new HashMap<>();

    // telescope・ステータス行・補完ポップアップ等、本文以外のUI文字列描画用グリフキャッシュ。
    // 本文用キャッシュ（glyphCacheFg/Bg）と違い任意の色・セルサイズを扱うためキーにそれらを含む。
    private record UiGlyphKey(int codePoint, int cellW, int cellH, int rgb) {}
    private final Map<UiGlyphKey, BufferedImage> uiGlyphCache = new HashMap<>();

    // 本文中の非ASCII文字（日本語コメント等、MiscFixedBold9x15が非対応でSwingフォントに
    // フォールバックする文字）用グリフキャッシュ。以前はキャッシュせず毎paintごとに
    // g2.drawString()でラスタライズしていたため、フォントサイズを大きくするほど
    // 1文字あたりの再描画コストが増え、カーソル移動・スクロールの体感が重くなっていた
    // （2026-08 フォントサイズ別描画コスト調査、詳細は gui-rendering-pipeline スキル参照）。
    // ASCII本文キャッシュ(glyphCacheFg)と同じ理由でセルサイズ・テーマが変わったら
    // invalidateGlyphCache() でクリアする。UiGlyphKeyを再利用しているが、非ASCII文字の
    // codePointはbitmapFont.isSupported()の範囲(0x20-0x7E)と重ならないためuiGlyphCacheとの
    // キー衝突は起きない一方、意味的に別キャッシュのため独立したMapにしてある。
    private final Map<UiGlyphKey, BufferedImage> nonAsciiGlyphCache = new HashMap<>();

    // 非ASCII文字描画用フォールバック Swing フォント（セルサイズに合わせて動的生成）
    private Font swingFont = null;
    private int  swingFontCellH = 0;   // swingFont を生成した時の cellH

    private static final Font  SPLASH_FONT   = new Font(Font.MONOSPACED, Font.PLAIN, 16);
    private static final Color ERROR_COLOR   = new Color(0xCC, 0x33, 0x33);
    private static final Color WARNING_COLOR = new Color(0xCC, 0x99, 0x00);
    private static final boolean IS_LINUX =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("nux");

    // -------------------------------------------------------------------------
    // ステータスラインのウォーキングパーソンアニメーション
    // -------------------------------------------------------------------------
    private final long  animStartMs = System.currentTimeMillis();
    // アニメーションは毎ティック画面全体を repaint() すると、
    // 4K等の大画面・大規模ファイルで本文全体の再描画（行分割・グリフ描画）が
    // 30fpsで走ってしまい重くなるため、ステータス行の帯だけを再描画対象にする。
    private static final int ANIM_FRAME_INTERVAL_MS = 1000 / 30; // 30fps
    private final Timer animTimer = new Timer(ANIM_FRAME_INTERVAL_MS, e -> repaintStatusLine());
    private boolean timerResolutionPinHeld = false;
    // ウィンドウ分割時、ウォーキングパーソンは現在アクティブなペインにのみ表示する。
    // 非アクティブなペインでも時刻表示は継続するため、drawStatusLine() 側で
    // このフラグを見て drawWalkingPerson() の呼び出しだけを抑制する。
    private boolean activePane = true;
    public void setActivePane(boolean activePane) { this.activePane = activePane; }

    // ステータスライン右端の時刻表示（24時間表記）
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private void repaintStatusLine() {
        int lh = (cachedLineHeight > 0) ? cachedLineHeight : 20;
        repaint(0, Math.max(0, getHeight() - lh - 4), getWidth(), lh + 4);
    }

    // -------------------------------------------------------------------------
    // Windows タイマー分解能ピン留め
    // -------------------------------------------------------------------------
    // Windows では、いずれかのスレッドが短い Thread.sleep() を実行している間だけ
    // JVM がシステムタイマー分解能を約1msに引き上げる（HotSpotのos::sleep実装）。
    // そのため javax.swing.Timer（内部的に Object.wait を使う）は、キー入力や IME 処理で
    // 短いスリープが発生している間だけ滑らかに動き、アイドル時は既定のタイマー分解能
    // （数十ms単位）にジッターして「キー入力していないとアニメーションが滑らかにならない」
    // という症状になっていた（Linux では発生しない。OS依存の既知のJVM挙動）。
    // 対策として、エディタ画面が表示されている間だけ 1ms スリープを繰り返す
    // 低優先度デーモンスレッドを立て、タイマー分解能を引き上げたままにする。
    private static final Object TIMER_RESOLUTION_PIN_LOCK = new Object();
    private static Thread timerResolutionPinThread = null;
    private static int timerResolutionPinRefCount = 0;

    private static void acquireTimerResolutionPin() {
        synchronized (TIMER_RESOLUTION_PIN_LOCK) {
            if (timerResolutionPinRefCount++ == 0) {
                timerResolutionPinThread = new Thread(() -> {
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            Thread.sleep(1);
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }, "anim-timer-resolution-pin");
                timerResolutionPinThread.setDaemon(true);
                timerResolutionPinThread.setPriority(Thread.MIN_PRIORITY);
                timerResolutionPinThread.start();
            }
        }
    }

    private static void releaseTimerResolutionPin() {
        synchronized (TIMER_RESOLUTION_PIN_LOCK) {
            if (--timerResolutionPinRefCount == 0 && timerResolutionPinThread != null) {
                timerResolutionPinThread.interrupt();
                timerResolutionPinThread = null;
            }
        }
    }

    public EditorCanvas() {
        sizeOverlayHideTimer.setRepeats(false);
        animTimer.start();
        acquireTimerResolutionPin();
        timerResolutionPinHeld = true;
        // JPanelは既定でisFocusable()==falseのため、setFocusable(true)を呼ばないと
        // requestFocusInWindow()が常に失敗し、実際のAWTフォーカスオーナーになれない。
        // InputContext（IME）は「本物のフォーカスオーナー」であるコンポーネントにしか
        // 関連付けられないため、これが無いとInputMethodListener/InputMethodRequestsを
        // 実装しても一切呼ばれない（このプロジェクトのキー入力自体はMain.javaのグローバル
        // KeyEventDispatcherがウィンドウ単位で処理するため、フォーカスが無くても通常入力は
        // 動いてしまい、この不整合に気づきにくかった）。
        setFocusable(true);
        enableInputMethods(true);
        addInputMethodListener(this);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // OS(特にLinux/X11)がウィンドウ露出等をきっかけに生成するシステム側の再描画要求を
        // 無視させ、歩行アニメーションの描画は animTimer 駆動の repaint() だけに一本化する。
        // ピア生成後(addNotify後)でないと効果が確定しないため、ここで呼ぶ。
        setIgnoreRepaint(true);
        animTimer.start();
        if (!timerResolutionPinHeld) {
            acquireTimerResolutionPin();
            timerResolutionPinHeld = true;
        }
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        animTimer.stop();
        if (timerResolutionPinHeld) {
            releaseTimerResolutionPin();
            timerResolutionPinHeld = false;
        }
    }

    /**
     * IMEを半角英数字入力モードに切り替える。
     * INSERTモードからNORMALモードに遷移する際に呼ぶことで、
     * 日本語IMEが有効なままNORMALモードのキーバインドを誤入力するのを防ぐ。
     *
     * Windows(Microsoft IME)は英語キーボードレイアウトを別途追加していない限り
     * Locale.ENGLISHに対応するInputMethodが登録されておらず、selectInputMethod()
     * だけではUnsupportedOperationExceptionになり何も起こらない（Windows実機で確認）。
     * setCompositionEnabled(false)はIMM32のImmSetOpenStatus(FALSE)相当を呼び、
     * 単一のIME（追加のレイアウト無し）でも直接入力(半角英数字)へ切り替えられるため、
     * こちらを主手段としつつ、Linux(fcitx/ibus等)向けにselectInputMethod()も
     * 併用する（どちらか一方しか対応していないプラットフォームでも他方の例外を
     * 握りつぶすだけで済むようにする）。
     *
     * 【2026-07 訂正】以前はLinux向けに`ibus engine xkb:us::eng`をProcessBuilderで
     * 呼び出すフォールバックを追加していたが、これはIBusの「アクティブなエンジンそのもの」
     * を日本語エンジン（Mozc等）から`xkb:us`（IMEなしの素のキーボードレイアウト）へ
     * 完全に切り替えてしまう操作であり、「IME内の半角/全角モードを切り替える」という
     * 意図とは全くの別物だった。Mozc実機で確認したところ、この呼び出しによりMozc
     * エンジン自体が非活性化され、以後INSERTモードへ戻ってもMozcの変換・予測候補
     * （入力補完）が一切機能しなくなる重大な副作用があったため撤去した。
     *
     * 代わりにLinux向けには`java.awt.Robot`で「英数」キー（`KeyEvent.VK_ALPHANUMERIC`、
     * JIS配列の物理的な英数キーに対応するAWT仮想キー）のキー押下を合成する方式にした。
     * これはIME側から見て「英数キーが物理的に押された」のと区別が付かないシステム全体への
     * キーイベントで、Mozcの既定キーマップでは前候補選択状態(precomposition)から
     * "IMEOff"（＝半角直接入力への切替）にバインドされている。エンジンそのものを
     * 切り替えないため、IME自体（変換候補・予測入力等）は活性化されたまま維持される。
     * X11のRobot実装は、現在のキーボードレイアウトに対象キーシムのキーコードが
     * 存在しない場合でも一時的なキーコード割り当て（XChangeKeyboardMapping）で
     * 送出できるようJDK内部で処理されるため、日本語109/106キーボードでない環境
     * （例: US配列+IME）でも動作することが期待できる。ヘッドレス環境・Robot生成失敗
     * （AWTException）はいずれもtry/catchで握りつぶし、既存のsetCompositionEnabled/
     * selectInputMethodの結果に影響しない独立した追加処理として扱う。
     */
    public void switchToHalfWidth() {
        InputContext ic = getInputContext();
        if (ic != null) {
            try {
                ic.setCompositionEnabled(false);
            } catch (Exception ignored) {}
            try {
                ic.selectInputMethod(Locale.ENGLISH);
            } catch (Exception ignored) {}
        }
        if (IS_LINUX && !GraphicsEnvironment.isHeadless()) {
            try {
                Robot robot = new Robot();
                robot.keyPress(KeyEvent.VK_ALPHANUMERIC);
                robot.keyRelease(KeyEvent.VK_ALPHANUMERIC);
            } catch (Exception ignored) {}
        }
    }

    /** IMEが確定した文字列を受け取るコールバックを設定する（Main.javaから配線）。 */
    public void setImeCommitHandler(Consumer<String> handler) {
        this.imeCommitHandler = handler;
    }

    /** INSERT→NORMAL遷移時など、変換中の未確定文字列の表示を消す。 */
    public void clearImeComposition() {
        this.composedText = "";
        requestRepaint();
    }

    @Override
    public InputMethodRequests getInputMethodRequests() {
        return imeRequests;
    }

    /**
     * IMEの変換状態が変わるたびに呼ばれる。確定済み部分（getCommittedCharacterCount()より前）は
     * imeCommitHandler へ通知し、未確定部分（変換中の文字列）は composedText に保持して
     * カーソル位置へリアルタイムにオーバーレイ表示する（drawImeComposition参照）。
     */
    @Override
    public void inputMethodTextChanged(InputMethodEvent event) {
        AttributedCharacterIterator iter = event.getText();
        int committedCount = event.getCommittedCharacterCount();
        StringBuilder committed = new StringBuilder();
        StringBuilder composing = new StringBuilder();
        if (iter != null) {
            int idx = 0;
            for (char c = iter.first(); c != CharacterIterator.DONE; c = iter.next(), idx++) {
                if (idx < committedCount) {
                    committed.append(c);
                } else {
                    composing.append(c);
                }
            }
        }
        composedText = composing.toString();
        event.consume();
        if (committed.length() > 0 && imeCommitHandler != null) {
            imeCommitHandler.accept(committed.toString());
        }
        requestRepaint();
    }

    @Override
    public void caretPositionChanged(InputMethodEvent event) {
        event.consume();
    }

    /**
     * IMEに対し、変換候補ウィンドウの表示位置・コミット済みテキストの問い合わせに応答する。
     * これを実装しない（＝getInputMethodRequests()がnullを返す）と、IMEはカーソル位置を
     * 一切知る術がなく、変換中の文字列を表示する浮動ウィンドウがカーソルと無関係な位置
     * （画面端等）に表示されてしまう。
     *
     * 【2026-07修正】以前はここで「カーソル位置より2行分下」という人為的なオフセットを
     * 返していた（自前の drawImeComposition() オーバーレイ＝現在行の表示とネイティブ側の
     * 変換候補ウィンドウが重ならないようにする狙いだったが、実機未検証のままの推測値だった）。
     * しかしWindows 11実機での検証で、この人為的なオフセットが原因で逆に不具合が起きることが
     * 判明した: カーソルが画面下寄りにある状態でさらに2行分下の座標を報告すると、その座標が
     * 画面（モニタ）下端を超えてしまい、Windows側のIMEが「上に反転して表示する」判断を行う際の
     * 基準点も人為的にずれたものになる。その結果、反転後の候補ウィンドウがカーソルから大きく
     * 離れた位置（画面上部の無関係な行）に重なって表示されてしまっていた。
     *
     * 正しい修正は、カーソルの実際の画面座標をそのまま返すこと。IME側（Windows/macOS/Linux
     * いずれも）はこの座標を基準に、画面の残り空間を見て候補ウィンドウを下または上に自動配置する
     * ロジックを標準で持っており、これは他の多くの非JTextComponent系ネイティブアプリ（ターミナル
     * エミュレータ等）が採用している一般的な方式でもある。人為的なオフセットで「先回りして」
     * ずらす必要はなく、むしろその方が実際の画面端付近での誤配置を誘発する。
     *
     * ネイティブの候補ウィンドウは通常この矩形の下端（= 現在行の下）を起点に表示されるため、
     * 自前の drawImeComposition() オーバーレイ（現在行そのもの）と直接重なることは基本的にない。
     * なお、変換中の未確定文字列自体をネイティブ側が別途カーソル直上に描画する場合（プラット
     * フォームのover-the-spot挙動）、自前オーバーレイと同じ文字列が二重に見える可能性は残るが、
     * これは同一内容の重複描画であり、今回報告された「候補ウィンドウが無関係なテキストと重なって
     * 読めなくなる」問題とは別種の軽微な既知の制約として許容する（実機再検証が必要な場合は
     * .claude/skills/gui-rendering-pipeline/SKILL.md 参照）。
     */
    private final InputMethodRequests imeRequests = new InputMethodRequests() {
        @Override
        public Rectangle getTextLocation(TextHitInfo offset) {
            Point base;
            try {
                base = getLocationOnScreen();
            } catch (IllegalComponentStateException e) {
                base = new Point(0, 0);
            }
            int lineHeight = cachedLineHeight > 0 ? cachedLineHeight : 16;
            int charWidth  = cachedCharWidth  > 0 ? cachedCharWidth  : 8;
            int gutterWidth = gutterWidthFor(charWidth);
            String line = (cursorRow < cachedLines.length) ? cachedLines[cursorRow] : "";
            int screenRow;
            int x;
            if (wrapEnabled) {
                int[] pos = wrapScreenPosition(cachedLines, cursorRow, cursorCol, charWidth, gutterWidth);
                if (pos != null) {
                    screenRow = pos[0];
                    x = pos[1];
                } else {
                    screenRow = cursorRow - scrollRow;
                    x = gutterWidth;
                }
            } else {
                screenRow = cursorRow - scrollRow;
                x = xForCol(line, cursorCol, charWidth) - scrollCol * charWidth + gutterWidth;
            }
            int y = screenRow * lineHeight;
            return new Rectangle(base.x + x, base.y + y, 1, lineHeight);
        }

        @Override
        public TextHitInfo getLocationOffset(int x, int y) { return null; }

        @Override
        public int getInsertPositionOffset() { return 0; }

        @Override
        public AttributedCharacterIterator getCommittedText(
                int beginIndex, int endIndex, AttributedCharacterIterator.Attribute[] attributes) {
            return new AttributedString("").getIterator();
        }

        @Override
        public int getCommittedTextLength() { return 0; }

        @Override
        public AttributedCharacterIterator cancelLatestCommittedText(
                AttributedCharacterIterator.Attribute[] attributes) {
            return null;
        }

        @Override
        public AttributedCharacterIterator getSelectedText(
                AttributedCharacterIterator.Attribute[] attributes) {
            return null;
        }
    };

    public void setText(String text) {
        setText(text, text.split("\n", -1));
    }

    /**
     * 行分割済み配列を伴う高速経路（ModalEditor.syncCanvas() のキャッシュ用。軽量化 Phase 2）。
     * lines は必ず text.split("\n", -1) と同一内容であること。
     * 渡された配列はコピーせずそのまま保持するため、呼び出し側は以後この配列を変更してはならない。
     */
    public void setText(String text, String[] lines) {
        this.text = text;
        this.cachedLines = lines;
        if (language != SourceLanguage.NONE) {
            if (lines != blockCommentLinesOwner || language != blockCommentLangOwner) {
                // 直前の結果が同じ言語で残っていれば差分更新（変化した行の周辺のみ再走査）、
                // 無ければ（初回・言語切替・行数変化）全文再計算にフォールバックする。
                // どちらの経路でも SyntaxHighlighter 側で内容比較により正しさを検証済み。
                blockCommentStartsAt = (blockCommentLinesOwner != null && language == blockCommentLangOwner)
                    ? SyntaxHighlighter.computeBlockCommentStartsIncremental(
                        blockCommentLinesOwner, blockCommentStartsAt, lines, language)
                    : SyntaxHighlighter.computeBlockCommentStarts(lines, language);
                blockCommentLinesOwner = lines;
                blockCommentLangOwner = language;
            }
        } else {
            blockCommentStartsAt = EMPTY_BOOLEAN_ARRAY;
            blockCommentLinesOwner = null;
            blockCommentLangOwner = null;
        }
        requestRepaint();
    }

    /**
     * 構文ハイライト対象の言語を切り替える。ModalEditor.syncCanvas() が現在の
     * currentFilePath から SourceLanguage.detect() で判定した値を、setText() より
     * 前に呼ぶ想定（setText() 内のブロックコメント状態キャッシュが言語切替を検知できるように）。
     */
    public void setLanguage(SourceLanguage language) {
        this.language = (language != null) ? language : SourceLanguage.NONE;
        requestRepaint();
    }
    public void setCursor(int row, int col) { this.cursorRow = row; this.cursorCol = col; requestRepaint(); }
    public void setCursorPositionLabel(String label) { this.cursorPositionLabel = label; requestRepaint(); }
    public void setInsertMode(boolean insertMode) { this.insertMode = insertMode; requestRepaint(); }
    public void setTheme(Theme theme) {
        // syncCanvas() は1キー入力ごとに呼ばれるため、値が変化していない場合は
        // グリフキャッシュを無駄に破棄しない（setFontChoice()と同じガード方式）。
        if (this.theme == theme) return;
        this.theme = theme;
        invalidateGlyphCache();
        requestRepaint();
    }
    public Theme getTheme() { return theme; }

    private static MonoFont monoFontFor(FontChoice fc) {
        return switch (fc) {
            case MISC_FIXED     -> MiscFixedBold9x15.INSTANCE;
            case IBM_PLEX_MONO  -> IbmPlexMonoFont.INSTANCE;
            case JETBRAINS_MONO -> JetBrainsMonoFont.INSTANCE;
            case COMIC_MONO     -> ComicMonoFont.INSTANCE;
        };
    }

    /**
     * 半角ASCIIフォントを切り替える（:font コマンド）。両ペインから独立に設定可能。
     *
     * <p>フォントの種類（ビットマップ実装 {@link #bitmapFont}）だけを差し替え、
     * {@link #cellW}/{@link #cellH}（フォントサイズ）には一切触れない。2026-08-04より前は
     * フォントごとに最後に使ったサイズを独立して覚えておく退避・復元機構を持っていたが、
     * 「フォントファミリーの変更はサイズに影響しない（切替直前のサイズをそのまま引き継ぐ）」
     * という仕様に変更したため撤回した（経緯は decision-log.md
     * 「EditorCanvas.setFontChoice() のフォント別セルサイズ退避機構を撤回」節参照）。
     */
    public void setFontChoice(FontChoice fontChoice) {
        if (this.fontChoice == fontChoice) return;
        this.fontChoice = fontChoice;
        this.bitmapFont = monoFontFor(fontChoice);
        invalidateGlyphCache();
        requestRepaint();
    }
    public FontChoice getFontChoice() { return fontChoice; }
    public void setScrollRow(int scrollRow) { this.scrollRow = Math.max(0, scrollRow); requestRepaint(); }
    public int getScrollRow() { return scrollRow; }
    public void setScrollCol(int col) { this.scrollCol = Math.max(0, col); requestRepaint(); }
    public int getScrollCol() { return scrollCol; }
    public void setWrapEnabled(boolean wrapEnabled) { this.wrapEnabled = wrapEnabled; requestRepaint(); }
    public boolean isWrapEnabled() { return wrapEnabled; }
    public int getVisibleRows() { return computeVisibleRows(cachedLineHeight > 0 ? cachedLineHeight : 16); }
    public void setCommandLineText(String text) { this.commandLineText = text; requestRepaint(); }
    public String getCommandLineText() { return commandLineText; }
    public String getCursorPositionLabel() { return cursorPositionLabel; }
    public void setVisualMode(boolean visualMode) { this.visualMode = visualMode; requestRepaint(); }
    public void setVisualLineMode(boolean visualLineMode) { this.visualLineMode = visualLineMode; requestRepaint(); }
    public void setVisualBlockMode(boolean visualBlockMode) { this.visualBlockMode = visualBlockMode; requestRepaint(); }
    public void setSelection(int anchorRow, int anchorCol, int cursorRow, int cursorCol) {
        this.selAnchorRow = anchorRow;
        this.selAnchorCol = anchorCol;
        this.selCursorRow = cursorRow;
        this.selCursorCol = cursorCol;
        requestRepaint();
    }
    /**
     * 選択範囲の描画状態をまとめて差し替える。
     *
     * <p>種類を表す3つの boolean と4つの座標を必ず一組で設定するため、
     * 個別の {@code setVisualMode}/{@code setVisualLineMode}/{@code setVisualBlockMode}/
     * {@code setSelection}/{@code clearSelection} を順に呼ぶ場合と違って、
     * 途中の辻褄の合わない状態が生じない。新しい呼び出しはこちらを使うこと。
     */
    // -------------------------------------------------------------------------
    // 再描画のまとめ
    // -------------------------------------------------------------------------

    /** batchUpdate() 実行中は 0 より大きい。個々の setter はこの間 再描画を予約しない。 */
    private int updateBatchDepth = 0;

    /**
     * 複数の状態をまとめて更新し、最後に1度だけ再描画を予約する。
     *
     * <p>{@code ModalEditor.syncCanvas()} は1キー入力ごとに10個ほどの setter を呼ぶが、
     * setter が個別に {@code repaint()} を呼ぶと同じ回数だけ再描画が予約される。
     * Swing は次の描画までに予約をまとめるため表示結果は変わらないものの、
     * 「1回の更新は1回の再描画」という意図をコード上で表せないため、まとめる口を用意した。
     *
     * <p>入れ子で呼んでも安全で、一番外側を抜けたときに1度だけ予約する。
     */
    public void batchUpdate(Runnable updates) {
        updateBatchDepth++;
        try {
            updates.run();
        } finally {
            updateBatchDepth--;
        }
        if (updateBatchDepth == 0) {
            repaint();
        }
    }

    /** setter から呼ぶ再描画の予約。まとめ更新中は予約せず、まとめ終わりに1度だけ行う。 */
    private void requestRepaint() {
        if (updateBatchDepth == 0) {
            repaint();
        }
    }

    public void setSelectionView(SelectionView view) {
        SelectionView v = (view != null) ? view : SelectionView.none();
        this.visualMode      = v.isActive();
        this.visualLineMode  = v.isLine();
        this.visualBlockMode = v.isBlock();
        this.selAnchorRow = v.anchorRow();
        this.selAnchorCol = v.anchorCol();
        this.selCursorRow = v.cursorRow();
        this.selCursorCol = v.cursorCol();
        requestRepaint();
    }

    public void clearSelection() {
        this.selAnchorRow = -1;
        this.visualLineMode = false;
        this.visualBlockMode = false;
        requestRepaint();
    }

    /** 補完ポップアップの描画状態をまとめて差し替える。 */
    public void setCompletionView(CompletionView view) {
        this.completion = (view != null) ? view : CompletionView.hidden();
        requestRepaint();
    }

    /** 候補一覧オーバーレイの描画状態をまとめて差し替える。 */
    public void setTelescopeView(TelescopeView view) {
        this.telescope = (view != null) ? view : TelescopeView.hidden();
        requestRepaint();
    }

    /**
     * 候補一覧オーバーレイの状態をセットする。
     *
     * <p>移行期間中の委譲。新しい呼び出しは {@link #setTelescopeView(TelescopeView)} を使うこと。
     * 既存の呼び出し側との互換のために残してある。
     */
    public void setTelescopeState(boolean active, String title, String query,
            List<TelescopeItem> results, int selectedIdx, String preview) {
        setTelescopeView(new TelescopeView(active, title, query, results, selectedIdx, preview));
    }

    public void setSearchHighlights(List<int[]> highlights) {
        this.searchHighlights = (highlights != null) ? List.copyOf(highlights) : List.of();
        requestRepaint();
    }

    public List<int[]> getSearchHighlights() { return searchHighlights; }

    // -------------------------------------------------------------------------
    // フォントセルサイズ調整（Ctrl+Shift+矢印）
    // -------------------------------------------------------------------------

    /**
     * 文字セル幅を delta px 変更する（範囲: 5〜90）。両ペインから呼ばれる。
     * 上限は setInitialCellSize() と同じ90に揃えてある（:fs 9 の90x150到達後に
     * 矢印操作すると即座に40へ引き戻される非対称を防ぐため。2026-07-29決定）。
     */
    public void adjustCellWidth(int delta) {
        cellW = Math.max(5, Math.min(90, cellW + delta));
        invalidateGlyphCache();
        cachedCharWidth = cellW;
        showSizeOverlay();
        requestRepaint();
    }

    /**
     * 文字セル高さを delta px 変更する（範囲: 8〜150）。両ペインから呼ばれる。
     * 上限は setInitialCellSize() と同じ150に揃えてある（理由は adjustCellWidth() 参照）。
     */
    public void adjustCellHeight(int delta) {
        cellH = Math.max(8, Math.min(150, cellH + delta));
        invalidateGlyphCache();
        cachedLineHeight = cellH;
        showSizeOverlay();
        requestRepaint();
    }

    /**
     * 現在のフォントセル幅×高さ(px)を画面右上に3秒間だけ表示し、自動的に消す。
     * Ctrl+Shift+矢印での変更のたびに呼ばれ、変更が連続した場合は表示中の3秒を
     * 都度リセットする（restart()により、最後の変更から3秒間表示され続ける）。
     */
    private void showSizeOverlay() {
        sizeOverlayVisible = true;
        sizeOverlayHideTimer.restart();
    }

    /** テスト用: 現在サイズオーバーレイが表示中かどうかを返す。 */
    public boolean isSizeOverlayVisible() { return sizeOverlayVisible; }

    public int getCellW() { return cellW; }
    public int getCellH() { return cellH; }

    /**
     * 起動時に一度だけ、絶対値でセルサイズを設定する（4K等の高解像度ディスプレイでフォントが
     * 小さすぎるのを防ぐため）。以後はユーザーが Ctrl+Shift+矢印で自由に変更できる。
     * 上限は :fs コマンド（MiscFixedの9x15整数倍10段階、最大90x150）を収める値まで
     * 拡張してある（2026-07-29決定。下限はCtrl+Shift+矢印の既存挙動を壊さないため維持）。
     */
    public void setInitialCellSize(int w, int h) {
        cellW = Math.max(5, Math.min(90, w));
        cellH = Math.max(8, Math.min(150, h));
        invalidateGlyphCache();
        cachedCharWidth = cellW;
        cachedLineHeight = cellH;
    }

    private void invalidateGlyphCache() {
        glyphCacheFg.clear();
        glyphCacheBg.clear();
        uiGlyphCache.clear();
        nonAsciiGlyphCache.clear();
    }

    private BufferedImage getUiGlyph(int codePoint, int cw, int ch, Color color) {
        UiGlyphKey key = new UiGlyphKey(codePoint, cw, ch, color.getRGB());
        return uiGlyphCache.computeIfAbsent(key,
            k -> bitmapFont.renderGlyph(codePoint, cw, ch, color.getRGB()));
    }

    /**
     * 本文中の非ASCII文字（MiscFixedBold9x15非対応、Swingフォントにフォールバックする文字）を
     * 1文字ぶんのBufferedImageとしてレンダリングしキャッシュする。cw/chはASCII本文と同じ
     * 1セル分の幅・高さ（全角文字はxForCol/charCellWidth側で2セル幅として扱われているため、
     * ここでは1セル分だけ描画すれば足りる。呼び出し側は widthMult 個ぶん並べて配置しない点に注意
     * ——描画自体は1グリフ画像で完結し、2セル目には何も描かれないが、全角文字の視覚的な字面は
     * 概ね1セル幅に収まるビットマップフォントの描き方に合わせている）。
     */
    private BufferedImage getNonAsciiGlyph(int codePoint, int cw, int ch, Color color) {
        UiGlyphKey key = new UiGlyphKey(codePoint, cw, ch, color.getRGB());
        return nonAsciiGlyphCache.computeIfAbsent(key,
            k -> renderNonAsciiGlyph(codePoint, cw, ch, color));
    }

    private BufferedImage renderNonAsciiGlyph(int codePoint, int cw, int ch, Color color) {
        int widthMult = charCellWidth(codePoint);
        int w = Math.max(1, cw * widthMult);
        int h = Math.max(1, ch);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gg = img.createGraphics();
        gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        gg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        gg.setFont(getSwingFont());
        gg.setColor(color);
        int baselineY = h - bitmapFont.descentPixels(h);
        gg.drawString(new String(Character.toChars(codePoint)), 0, baselineY);
        gg.dispose();
        return img;
    }

    /**
     * telescope・ステータス行・補完ポップアップ等、本文以外のUI文字列を misc-fixed Bold
     * ビットマップフォントで描画する（本文の drawLineWithFullWidthSupport と同じ配色規則:
     * ASCIIはビットマップフォント、それ以外（日本語等）は Swing フォールバックフォント）。
     * y はセル下端（ベースライン）のY座標。
     */
    private void drawUiText(Graphics2D g2, String s, int x, int y, int cw, int ch, Color color) {
        int swingBaselineY = y - bitmapFont.descentPixels(ch);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int widthMult = charCellWidth(cp);
            if (bitmapFont.isSupported(cp)) {
                g2.drawImage(getUiGlyph(cp, cw, ch, color), x, y - ch, null);
            } else {
                Color prev = g2.getColor();
                g2.setColor(color);
                g2.drawString(new String(Character.toChars(cp)), x, swingBaselineY);
                g2.setColor(prev);
            }
            x += cw * widthMult;
            i += Character.charCount(cp);
        }
    }

    /** drawUiText() で描画した際のピクセル幅を返す。 */
    private int uiTextWidth(String s, int cw) {
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            width += cw * charCellWidth(cp);
            i += Character.charCount(cp);
        }
        return width;
    }

    /** uiTextWidth() ベースで maxWidth に収まるよう末尾を "…" で省略する。 */
    private String clipToUiWidth(String s, int cw, int maxWidth) {
        if (uiTextWidth(s, cw) <= maxWidth) return s;
        while (s.length() > 0 && uiTextWidth(s + "…", cw) > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    private BufferedImage getGlyphFg(int cp) {
        return glyphCacheFg.computeIfAbsent(cp,
            k -> bitmapFont.renderGlyph(k, cellW, cellH, theme.foreground.getRGB()));
    }

    private BufferedImage getGlyphBg(int cp) {
        return glyphCacheBg.computeIfAbsent(cp,
            k -> bitmapFont.renderGlyph(k, cellW, cellH, theme.background.getRGB()));
    }

    private Font getSwingFont() {
        if (swingFont == null || swingFontCellH != cellH) {
            swingFont = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(8, (int)(cellH * 0.75)));
            swingFontCellH = cellH;
        }
        return swingFont;
    }

    /** スプラッシュ画面の表示/非表示を切り替える。 */
    public void setShowSplash(boolean show) {
        this.showSplash = show;
        requestRepaint();
    }

    /**
     * 画像プレビュー表示状態をまとめて反映する。ModalEditor.syncCanvas() の batchUpdate 内から呼ぶ。
     * active=false のときは他のフィールドの値に関わらず通常のテキスト描画へ戻る。
     */
    public void setImageView(BufferedImage image, boolean active, boolean autoFit, double zoom, boolean loading) {
        this.imageBuffer = image;
        this.imageViewActive = active;
        this.imageAutoFit = autoFit;
        this.imageZoom = zoom;
        this.imageLoading = loading;
        requestRepaint();
    }

    public boolean isImageViewActive() { return imageViewActive; }

    public boolean isShowSplash() { return showSplash; }

    /**
     * *compile* / *run* 疑似バッファの行のうち、赤字（標準エラー出力・ERROR診断）で描画する
     * 行番号の集合をセットする。空集合なら全行が通常の前景色で描画される。
     */
    public void setErrorLines(Set<Integer> errorLines) {
        this.errorLines = (errorLines != null) ? Set.copyOf(errorLines) : Set.of();
        requestRepaint();
    }

    /**
     * コンパイル診断リストをセットして再描画する。
     * 空リストを渡すとガター・アンダーラインが消える。
     */
    public void setDiagnostics(List<CompileDiagnostic> diagnostics) {
        this.diagnostics = (diagnostics != null) ? List.copyOf(diagnostics) : List.of();
        // 行番号ごとに最優先の種別を集計（ERRORが優先）
        Map<Integer, DiagnosticKind> map = new HashMap<>();
        for (CompileDiagnostic d : this.diagnostics) {
            map.merge(d.lineNumber(), d.kind(),
                (existing, incoming) ->
                    (incoming == DiagnosticKind.ERROR) ? DiagnosticKind.ERROR : existing);
        }
        this.diagByLine = Map.copyOf(map);
        requestRepaint();
    }

    /**
     * 行番号ガター（診断マーカー E/W を出す左端の余白）の幅を返す。
     *
     * <p>診断が1件も無いときはガター自体を出さないので幅0。
     * ある場合はマーカー1文字ぶんとその右の余白で2セル分を確保する。
     * 描画の複数箇所（本文・カーソル・折り返し計算）が同じ値を使う必要があるため、
     * この計算はここ1箇所に置く。
     */
    private int gutterWidthFor(int charWidth) {
        return diagnostics.isEmpty() ? 0 : 2 * charWidth;
    }

    /** 現在保持している診断リストを返す（テスト用）。 */
    public List<CompileDiagnostic> getDiagnostics() { return diagnostics; }

    /**
     * カーソル行が表示範囲に収まるよう scrollRow を調整する。
     * ModalEditor がカーソル移動後に呼ぶことでスクロールを追従させる。
     */
    public void ensureCursorVisible(int cursorRow) {
        if (wrapEnabled) {
            ensureCursorVisibleWrapped(cursorRow);
            return;
        }
        int visibleRows = computeVisibleRows(cachedLineHeight);
        if (cursorRow < scrollRow) {
            scrollRow = cursorRow;
            requestRepaint();
        } else if (cursorRow >= scrollRow + visibleRows) {
            scrollRow = Math.max(0, cursorRow - visibleRows + 1);
            requestRepaint();
        }
    }

    // 大きなジャンプ(G・gg・:行番号等)でこの行数を超えて scrollRow から離れている場合、
    // 折返し数を1行ずつ積算する正確な計算を打ち切り、近似値にフォールバックする
    // （数十万行規模のファイルでO(距離)の計算が際限なく重くなるのを防ぐ）。
    private static final int WRAP_SCROLL_SCAN_LIMIT = 4096;

    /**
     * wrap有効時、カーソル行の折返しが画面に収まるよう scrollRow を調整する。
     * scrollRow は常に文書行の境界（折返しの途中ではなく行頭）を指す前提を維持する。
     */
    private void ensureCursorVisibleWrapped(int cursorRow) {
        int visibleRows = computeVisibleRows(cachedLineHeight);
        if (cursorRow < scrollRow) {
            scrollRow = cursorRow;
            requestRepaint();
            return;
        }
        if (cursorRow - scrollRow > WRAP_SCROLL_SCAN_LIMIT) {
            // 近似: 正確な折返し計算はせず、カーソル行がおおよそ画面内に収まる位置へ寄せる
            scrollRow = Math.max(0, cursorRow - visibleRows + 1);
            requestRepaint();
            return;
        }
        int visibleCols = computeVisibleColsForWrap();
        int used = 0;
        for (int r = scrollRow; r <= cursorRow && r < cachedLines.length; r++) {
            used += wrapSegmentCount(cachedLines[r], visibleCols);
        }
        boolean changed = false;
        while (used > visibleRows && scrollRow < cursorRow) {
            used -= wrapSegmentCount(cachedLines[scrollRow], visibleCols);
            scrollRow++;
            changed = true;
        }
        if (changed) requestRepaint();
    }

    /**
     * カーソル列が表示範囲に収まるよう scrollCol を調整する。
     * ModalEditor がカーソル移動後に呼ぶことで横スクロールを追従させる。
     *
     * @param col      カーソルの文字インデックス（line の何文字目か）
     * @param line     カーソルがいる行の文字列（全角幅計算に使用）
     */
    public void ensureCursorColVisible(int col, String line) {
        if (wrapEnabled) {
            // wrap有効時は横スクロールを行わない（長い行は折返して表示するため）
            if (scrollCol != 0) { scrollCol = 0; requestRepaint(); }
            return;
        }
        int cursorCells = cellsForCol(line, col);
        int visibleCols = computeVisibleCols();
        if (cursorCells < scrollCol) {
            scrollCol = cursorCells;
            requestRepaint();
        } else if (visibleCols > 0 && cursorCells >= scrollCol + visibleCols) {
            scrollCol = cursorCells - visibleCols + 1;
            requestRepaint();
        }
    }

    /** 行頭から col 文字目までの合計セル幅を返す（全角=2、半角=1） */
    private static int cellsForCol(String line, int col) {
        int cells = 0, count = 0;
        for (int i = 0; i < line.length() && count < col; ) {
            int cp = line.codePointAt(i);
            cells += charCellWidth(cp);
            i += Character.charCount(cp);
            count++;
        }
        return cells;
    }

    private int computeVisibleCols() {
        if (cachedCharWidth <= 0) return 80;
        return Math.max(1, getWidth() / cachedCharWidth);
    }

    /** ステータス行1行を除いた領域に収まる行数を返す */
    private int computeVisibleRows(int lineHeight) {
        if (lineHeight <= 0) return 1;
        return Math.max(1, (getHeight() - lineHeight) / lineHeight);
    }

    // -------------------------------------------------------------------------
    // :wrap / :nowrap（画面端での折り返し表示）
    // -------------------------------------------------------------------------

    /** wrap時、1画面行に収まるセル数（ガター幅を除いた実効幅）を返す */
    private int computeVisibleColsForWrap() {
        int charWidth = (cachedCharWidth > 0) ? cachedCharWidth : cellW;
        if (charWidth <= 0) return 80;
        int gutterWidth = gutterWidthFor(charWidth);
        return Math.max(1, (getWidth() - gutterWidth) / charWidth);
    }

    /**
     * wrap時、1論理行を画面上のセグメント（各要素は {開始charIndex, 終了charIndex(exclusive)}）に
     * 分割する。全角文字（2セル）がセグメント境界をまたがないよう、セル単位で貪欲に区切る。
     * 空行でも必ず1セグメント（{0,0}）を返す。
     */
    private static List<int[]> wrapSegments(String line, int visibleCols) {
        List<int[]> segs = new java.util.ArrayList<>();
        int start = 0;
        int cells = 0;
        int i = 0;
        while (i < line.length()) {
            int cp = line.codePointAt(i);
            int w = charCellWidth(cp);
            int len = Character.charCount(cp);
            if (cells > 0 && cells + w > visibleCols) {
                segs.add(new int[]{start, i});
                start = i;
                cells = 0;
            }
            cells += w;
            i += len;
        }
        segs.add(new int[]{start, line.length()});
        return segs;
    }

    private static int wrapSegmentCount(String line, int visibleCols) {
        return wrapSegments(line, visibleCols).size();
    }

    /** wrap時の画面描画1行分。docRow の [segStart, segEnd) の範囲がこのスクリーン行に表示される。 */
    private record WrapRow(int docRow, int segStart, int segEnd) {}

    /** scrollRow を起点に、画面に収まる分（最大 visibleRows 行）の折返しプランを構築する。 */
    private List<WrapRow> buildWrapPlan(String[] lines, int visibleRows, int visibleCols) {
        List<WrapRow> plan = new java.util.ArrayList<>(visibleRows);
        int row = scrollRow;
        while (plan.size() < visibleRows && row < lines.length) {
            for (int[] seg : wrapSegments(lines[row], visibleCols)) {
                if (plan.size() >= visibleRows) break;
                plan.add(new WrapRow(row, seg[0], seg[1]));
            }
            row++;
        }
        return plan;
    }

    /** 構築済みの wrapPlan から、指定した文書上の行・列が描画されるスクリーン行とX座標を探す。 */
    private static int[] findSegmentPixel(List<WrapRow> wrapPlan, String line, int docRow, int col,
            int charWidth, int gutterWidth) {
        for (int screenRow = 0; screenRow < wrapPlan.size(); screenRow++) {
            WrapRow wr = wrapPlan.get(screenRow);
            if (wr.docRow() != docRow) continue;
            boolean isLastSeg = wr.segEnd() == line.length();
            if (col >= wr.segStart() && (col < wr.segEnd() || isLastSeg)) {
                int x = xForCol(line, col, charWidth) - xForCol(line, wr.segStart(), charWidth) + gutterWidth;
                return new int[]{screenRow, x};
            }
        }
        return null;
    }

    /**
     * wrap時、指定した文書上の行・列が画面上のどのスクリーン行・X座標(ピクセル)に描画されるかを
     * scrollRow から辿って計算する（wrapPlan が手元にない paintContent() 外からの呼び出し用。
     * IME候補ウィンドウの位置計算で使う）。対象行が scrollRow より前の場合は null を返す。
     */
    private int[] wrapScreenPosition(String[] lines, int docRow, int col, int charWidth, int gutterWidth) {
        if (docRow < scrollRow || docRow >= lines.length) return null;
        int visibleCols = computeVisibleColsForWrap();
        int screenRow = 0;
        for (int r = scrollRow; r < docRow; r++) {
            screenRow += wrapSegmentCount(lines[r], visibleCols);
        }
        String line = lines[docRow];
        for (int[] seg : wrapSegments(line, visibleCols)) {
            boolean isLastSeg = seg[1] == line.length();
            if (col >= seg[0] && (col < seg[1] || isLastSeg)) {
                int x = xForCol(line, col, charWidth) - xForCol(line, seg[0], charWidth) + gutterWidth;
                return new int[]{screenRow, x};
            }
            screenRow++;
        }
        return null;
    }

    /**
     * 行内の [colStart, colEndExclusive) 範囲のハイライトを、wrapPlan上の該当スクリーン行すべてに
     * 分割して描画する（選択範囲・検索ハイライトで共用）。範囲が0幅の場合（空行等）は
     * 非wrap時と同様に1文字分だけハイライトする。
     */
    private void drawWrappedRangeSpan(Graphics2D g2, List<WrapRow> wrapPlan, String line, int docRow,
            int colStart, int colEndExclusive, int charWidth, int lineHeight, int gutterWidth) {
        boolean any = false;
        for (int screenRow = 0; screenRow < wrapPlan.size(); screenRow++) {
            WrapRow wr = wrapPlan.get(screenRow);
            if (wr.docRow() != docRow) continue;
            int from = Math.max(colStart, wr.segStart());
            int to = Math.min(colEndExclusive, wr.segEnd());
            if (from >= to) continue;
            any = true;
            int xStart = xForCol(line, from, charWidth) - xForCol(line, wr.segStart(), charWidth) + gutterWidth;
            int xEnd = xForCol(line, to, charWidth) - xForCol(line, wr.segStart(), charWidth) + gutterWidth;
            int drawStart = Math.max(xStart, gutterWidth);
            int drawEnd = Math.min(xEnd, getWidth());
            if (drawStart < drawEnd) {
                g2.fillRect(drawStart, screenRow * lineHeight, drawEnd - drawStart, lineHeight);
            }
        }
        if (!any && colEndExclusive <= colStart) {
            int[] pos = findSegmentPixel(wrapPlan, line, docRow, colStart, charWidth, gutterWidth);
            if (pos != null) {
                int drawStart = Math.max(pos[1], gutterWidth);
                int drawEnd = Math.min(pos[1] + charWidth, getWidth());
                if (drawStart < drawEnd) {
                    g2.fillRect(drawStart, pos[0] * lineHeight, drawEnd - drawStart, lineHeight);
                }
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        try {
            paintContent((Graphics2D) g);
        } finally {
            // Linux(X11)では描画コマンドがクライアント側でバッファされ画面への反映タイミングが
            // 不定になり、アニメーションが微妙にカクつく（Windowsでは発生しない既知の差異）。
            // paintComponent の後に明示的にフラッシュしてジッターを抑える。
            Toolkit.getDefaultToolkit().sync();
        }
    }

    private void paintContent(Graphics2D g2) {
        // ビットマップフォント(MiscFixedBold9x15)のグリフはニアレストネイバーで拡縮済みだが、
        // 非ASCIIフォールバック（Swingフォント）でのdrawString呼び出し（ステータス行・スプラッシュ・
        // telescope・補完ポップアップ等）にはヒントが効いていなかったため、この g2 を使う全描画に
        // 共通で適用されるようここで一度だけ設定する（以下の draw* メソッドは全て同じ g2 を共有する）。
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // ビットマップフォントのセルサイズを使用する
        int charWidth  = cellW;
        int lineHeight = cellH;
        cachedLineHeight = lineHeight;
        cachedCharWidth  = charWidth;

        // 非ASCII文字の描画用に Swing フォントをセット（ステータス行・ガター等でも使用）
        g2.setFont(getSwingFont());

        // ガター幅: 診断がある場合のみ "E " / "W " / "  " 2文字分を確保
        int gutterWidth = gutterWidthFor(charWidth);
        int scrollOffsetX = wrapEnabled ? 0 : scrollCol * charWidth;

        // 再描画範囲がステータス行の帯に収まっている場合（歩行アニメーションのティック）は、
        // 本文（数十万行規模になりうる）の再描画を丸ごとスキップし、ステータス行だけ塗り直す。
        Rectangle clip = g2.getClipBounds();
        boolean statusLineOnly = clip != null && !showSplash && !telescope.active()
            && !completion.hasVisibleItems()
            && clip.y >= getHeight() - lineHeight - 8;
        if (statusLineOnly) {
            g2.setColor(theme.background);
            g2.fillRect(clip.x, clip.y, clip.width, clip.height);
            drawStatusLine(g2, lineHeight);
            return;
        }

        // 1. 背景を塗る
        g2.setColor(theme.background);
        g2.fillRect(0, 0, getWidth(), getHeight());

        // スプラッシュ表示中は通常テキストを描画せずスプラッシュを描いてステータス行だけ出す
        if (showSplash) {
            drawSplashScreen(g2, charWidth, lineHeight);
            g2.setFont(getSwingFont()); // splash が内部でフォントを変えるのでリセット
            drawStatusLine(g2, lineHeight);
            return;
        }

        // 画像プレビュー（C2方式）: 実処理は ImageRenderer に委譲する薄い分岐のみここに置く。
        if (imageViewActive) {
            int viewportH = getHeight() - lineHeight; // 最下部1行はステータス行に確保する
            if (imageLoading || imageBuffer == null) {
                ImageRenderer.paintCenteredMessage(g2, ImageRenderer.LOADING_TEXT,
                    0, 0, getWidth(), viewportH, theme.foreground);
            } else {
                ImageRenderer.paint(g2, imageBuffer, 0, 0, getWidth(), viewportH,
                    imageAutoFit, imageZoom, scrollCol, scrollRow, charWidth, lineHeight);
            }
            drawStatusLine(g2, lineHeight);
            return;
        }

        String[] lines = cachedLines;
        int visibleRows = computeVisibleRows(lineHeight);
        List<WrapRow> wrapPlan = wrapEnabled
            ? buildWrapPlan(lines, visibleRows, computeVisibleColsForWrap())
            : null;

        // 1.5 選択ハイライト（VISUALモード時）
        if (visualMode && selAnchorRow >= 0) {
            drawSelectionHighlight(g2, lines,
                charWidth, lineHeight, scrollOffsetX, gutterWidth, wrapPlan);
        }

        // 1.6 検索ハイライト（/pattern、*、# による検索結果）
        if (!searchHighlights.isEmpty()) {
            drawSearchHighlights(g2, lines, charWidth, lineHeight, scrollOffsetX, gutterWidth, wrapPlan);
        }

        // 2. 表示行範囲（scrollRow 〜 scrollRow+visibleRows。wrap時は折返し込みの画面行数）のみ描画する
        g2.setColor(theme.foreground);
        int voidScreenRowStart;
        if (wrapPlan != null) {
            for (int screenRow = 0; screenRow < wrapPlan.size(); screenRow++) {
                WrapRow wr = wrapPlan.get(screenRow);
                String seg = lines[wr.docRow()].substring(wr.segStart(), wr.segEnd());
                int y = (screenRow + 1) * lineHeight;
                List<SyntaxToken> tokens = tokensForSegment(lines, wr.docRow(), wr.segStart(), wr.segEnd());
                drawLineWithFullWidthSupport(g2, seg, y, charWidth, 0, gutterWidth,
                    errorLines.contains(wr.docRow()), tokens);
            }
            voidScreenRowStart = wrapPlan.size();
        } else {
            int lastRow = Math.min(lines.length, scrollRow + visibleRows);
            for (int row = scrollRow; row < lastRow; row++) {
                int screenRow = row - scrollRow;
                int y = (screenRow + 1) * lineHeight;
                List<SyntaxToken> tokens = tokensForRow(lines, row);
                drawLineWithFullWidthSupport(g2, lines[row], y, charWidth, scrollOffsetX, gutterWidth,
                    errorLines.contains(row), tokens);
            }
            voidScreenRowStart = Math.max(0, lastRow - scrollRow);
        }

        // 2.5 zz等でファイル末尾を超えてスクロールした場合、行が存在しない領域を
        //     テーマの通常背景色ではなく純粋な白(ライト)/黒(ダーク)で明示的に塗る。
        //     カーソルはこの領域には存在し得ない（cursorRowは常に有効な行番号にクランプされる）。
        if (voidScreenRowStart < visibleRows) {
            int voidY = voidScreenRowStart * lineHeight;
            g2.setColor(theme == Theme.LIGHT_MODE ? Color.WHITE : Color.BLACK);
            g2.fillRect(0, voidY, getWidth(), visibleRows * lineHeight - voidY);
        }

        // 3. カーソルを描画する（縦・横スクロールオフセット考慮）
        drawCursor(g2, lines, charWidth, lineHeight, scrollOffsetX, gutterWidth, wrapPlan);

        // 4. ガター（診断マーカー）を描画する
        if (gutterWidth > 0) {
            drawGutter(g2, charWidth, lineHeight, gutterWidth, wrapPlan);
        }

        // 5. エラー・警告アンダーラインを描画する
        if (!diagByLine.isEmpty()) {
            drawDiagnosticUnderlines(g2, lines, charWidth, lineHeight, scrollOffsetX, gutterWidth, wrapPlan);
        }

        // 6. ステータス行を描画する（画面最下部）
        drawStatusLine(g2, lineHeight);

        // 7. telescope オーバーレイ（最前面に描画）
        if (telescope.active()) {
            drawTelescopeOverlay(g2, lineHeight);
        }

        // 8. 入力補完ポップアップ（telescope より前面）
        if (completion.hasVisibleItems()) {
            drawCompletionPopup(g2, charWidth, lineHeight, gutterWidth);
        }

        // 9. フォントセルサイズ変更直後の一時オーバーレイ（画面右上、最前面）
        if (sizeOverlayVisible) {
            drawSizeOverlay(g2, charWidth, lineHeight);
        }
    }

    /** Ctrl+Shift+矢印でのセルサイズ変更直後、右上に「幅 x 高さ px」を表示する。 */
    private void drawSizeOverlay(Graphics2D g2, int charWidth, int lineHeight) {
        String label = cellW + " x " + cellH + " px";
        int pad = 6;
        int textW = uiTextWidth(label, charWidth);
        int boxW = textW + pad * 2;
        int boxH = lineHeight + pad * 2;
        int x = getWidth() - boxW - 8;
        int y = 8;

        g2.setColor(theme.accent);
        g2.fillRect(x, y, boxW, boxH);
        drawUiText(g2, label, x + pad, y + pad + lineHeight, charWidth, lineHeight, theme.background);
    }

    private void drawTelescopeOverlay(Graphics2D g2, int lineHeight) {
        int W = getWidth();
        int H = getHeight();
        int overlayW = (int)(W * 0.85);
        int overlayH = (int)(H * 0.75);
        int ox = (W - overlayW) / 2;
        int oy = (H - overlayH) / 2;

        // 半透明背景
        g2.setColor(new Color(0, 0, 0, 180));
        g2.fillRect(0, 0, W, H);

        // オーバーレイ枠
        g2.setColor(theme.background);
        g2.fillRect(ox, oy, overlayW, overlayH);
        g2.setColor(theme.accent);
        g2.drawRect(ox, oy, overlayW, overlayH);

        // 本文と同じ misc-fixed Bold のセルサイズをそのまま使う
        int cw = cellW;
        int fh = lineHeight;
        int pad = 4;

        // プロンプト行
        g2.setColor(theme.accent);
        g2.fillRect(ox, oy, overlayW, fh + pad * 2);
        String promptText = telescope.promptLine() + "_";
        drawUiText(g2, promptText, ox + pad, oy + fh + pad, cw, fh, theme.background);

        int bodyY = oy + fh + pad * 2 + 1;
        int bodyH = overlayH - (fh + pad * 2 + 1);

        // 仕切り線（Results 40% / Preview 60%）
        int resultsW = (int)(overlayW * 0.40);
        int previewX = ox + resultsW;
        g2.setColor(theme.accent);
        g2.drawLine(previewX, bodyY, previewX, oy + overlayH);

        // Results ペイン
        int maxResultRows = bodyH / fh;
        int visStart = Math.max(0, telescope.selectedIdx() - maxResultRows + 1);
        if (telescope.selectedIdx() < visStart) visStart = telescope.selectedIdx();

        for (int i = visStart; i < telescope.results().size() && (i - visStart) < maxResultRows; i++) {
            TelescopeItem item = telescope.results().get(i);
            int ry = bodyY + (i - visStart + 1) * fh;
            Color rowColor;
            if (i == telescope.selectedIdx()) {
                g2.setColor(theme.accent);
                g2.fillRect(ox + 1, ry - fh, resultsW - 2, fh);
                rowColor = theme.background;
            } else {
                rowColor = theme.foreground;
            }
            String label = (i == telescope.selectedIdx() ? "> " : "  ") + item.display();
            String clipped = clipToUiWidth(label, cw, resultsW - pad * 2);
            drawUiText(g2, clipped, ox + pad, ry, cw, fh, rowColor);
        }

        // Preview ペイン
        String[] previewLines = telescope.preview().split("\n", -1);
        int previewW = overlayW - resultsW;
        int py = bodyY + fh;
        for (int i = 0; i < previewLines.length && (py - bodyY) < bodyH; i++) {
            String clipped = clipToUiWidth(previewLines[i], cw, previewW - pad * 2);
            drawUiText(g2, clipped, previewX + pad, py, cw, fh, theme.foreground);
            py += fh;
        }
    }

    /**
     * カーソル直下に補完候補のドロップダウンを描画する。
     * テキスト本体より前面・telescope より後面に描画される。
     */
    private void drawCompletionPopup(Graphics2D g2, int charWidth, int lineHeight, int gutterWidth) {
        if (completion.rows().isEmpty()) return;

        int cw = cellW;
        int fh = lineHeight;
        int pad = 4;
        int kindW = uiTextWidth("mth", cw) + pad; // kind ラベルの幅

        // 表示するのは選択位置を含む一定件数だけ（IntelliJ の候補一覧と同じくスクロールする）
        List<CompletionView.Row> rows = completion.rows();
        int visibleCount = Math.min(rows.size(), COMPLETION_VISIBLE_ROWS);
        int firstVisible = firstVisibleCompletionRow(completion.selectedIdx(), rows.size(), visibleCount);

        // 「名前 + 引数リスト」と「型」の2列分の幅を測ってポップアップ幅を決める
        int maxMainW = 0;
        int maxTypeW = 0;
        for (int i = firstVisible; i < firstVisible + visibleCount; i++) {
            CompletionView.Row row = rows.get(i);
            maxMainW = Math.max(maxMainW, uiTextWidth(row.label() + row.tailText(), cw));
            maxTypeW = Math.max(maxTypeW, uiTextWidth(row.typeText(), cw));
        }
        int typeGap = (maxTypeW > 0) ? uiTextWidth("  ", cw) : 0;
        int popupW = kindW + maxMainW + typeGap + maxTypeW + pad * 3;
        int popupH = visibleCount * fh + pad * 2;

        // カーソル行の文字列でセルオフセットを計算（全角対応）
        String[] lines = cachedLines;
        int anchorScreenRow;
        int popupX;
        if (wrapEnabled) {
            int[] pos = wrapScreenPosition(lines, completion.anchorRow(), completion.anchorCol(), charWidth, gutterWidth);
            if (pos != null) {
                anchorScreenRow = pos[0];
                popupX = pos[1];
            } else {
                anchorScreenRow = completion.anchorRow() - scrollRow;
                popupX = gutterWidth;
            }
        } else {
            String anchorLine = (completion.anchorRow() < lines.length) ? lines[completion.anchorRow()] : "";
            int cellOffset = cellsForCol(anchorLine, completion.anchorCol());
            anchorScreenRow = completion.anchorRow() - scrollRow;
            popupX = gutterWidth + cellOffset * charWidth - scrollCol * charWidth;
        }
        int popupY = (anchorScreenRow + 1) * lineHeight; // カーソル行の下

        // 画面右端・下端をはみ出さないよう調整
        if (popupX + popupW > getWidth()) {
            popupX = Math.max(0, getWidth() - popupW);
        }
        if (popupY + popupH > getHeight() - lineHeight) {
            // 上に出す
            popupY = anchorScreenRow * lineHeight - popupH;
        }

        // ポップアップ背景・枠
        Color popupBg = new Color(
            Math.max(0, theme.background.getRed()   - 20),
            Math.max(0, theme.background.getGreen() - 20),
            Math.max(0, theme.background.getBlue()  - 20));
        g2.setColor(popupBg);
        g2.fillRect(popupX, popupY, popupW, popupH);
        g2.setColor(theme.accent);
        g2.drawRect(popupX, popupY, popupW, popupH);

        // 各候補を描画（種別タグ / 名前 + 引数リスト / 右寄せの型）
        Color dimColor = blend(theme.foreground, theme.background, 0.45f);
        for (int i = firstVisible; i < firstVisible + visibleCount; i++) {
            CompletionView.Row row = rows.get(i);
            int screenIdx = i - firstVisible;
            int iy = popupY + pad + (screenIdx + 1) * fh;
            int rowTop = popupY + pad + screenIdx * fh;
            boolean selected = (i == completion.selectedIdx());

            Color mainColor = selected ? theme.background : theme.foreground;
            Color tagColor  = selected ? theme.background : theme.accent;
            Color subColor  = selected ? theme.background : dimColor;
            if (selected) {
                g2.setColor(theme.accent);
                g2.fillRect(popupX + 1, rowTop, popupW - 2, fh);
            }

            drawUiText(g2, row.kind(), popupX + pad, iy, cw, fh, tagColor);
            int x = popupX + pad + kindW;
            // 入力に一致した文字だけ色を変えて、なぜこの候補が出ているのかを見えるようにする
            for (int c = 0; c < row.label().length(); c++) {
                String ch = row.label().substring(c, c + 1);
                Color color = (!selected && row.isHighlighted(c)) ? theme.accent : mainColor;
                drawUiText(g2, ch, x, iy, cw, fh, color);
                x += uiTextWidth(ch, cw);
            }
            if (!row.tailText().isEmpty()) {
                drawUiText(g2, row.tailText(), x, iy, cw, fh, subColor);
            }
            if (!row.typeText().isEmpty()) {
                int typeX = popupX + popupW - pad - uiTextWidth(row.typeText(), cw);
                drawUiText(g2, row.typeText(), typeX, iy, cw, fh, subColor);
            }
        }

        // 表示しきれていない候補があることを右端の目印で示す
        if (rows.size() > visibleCount) {
            g2.setColor(theme.accent);
            int barH = Math.max(fh, popupH * visibleCount / rows.size());
            int barY = popupY + (popupH - barH) * firstVisible / Math.max(1, rows.size() - visibleCount);
            g2.fillRect(popupX + popupW - 3, barY, 2, barH);
        }
    }

    /**
     * 選択中の候補が必ず見えるようにスクロール位置（先頭に表示する候補の番号）を決める。
     * 選択が表示範囲の端に来たときだけずらす、一般的なリスト表示と同じ挙動。
     */
    private static int firstVisibleCompletionRow(int selectedIdx, int total, int visibleCount) {
        if (total <= visibleCount) return 0;
        int first = selectedIdx - visibleCount / 2;
        first = Math.max(0, Math.min(first, total - visibleCount));
        return first;
    }

    /** 2色を ratio（0.0=c1, 1.0=c2）で混ぜる。淡色表示（引数リスト・型）に使う。 */
    private static Color blend(Color c1, Color c2, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        return new Color(
            Math.round(c1.getRed()   * (1 - r) + c2.getRed()   * r),
            Math.round(c1.getGreen() * (1 - r) + c2.getGreen() * r),
            Math.round(c1.getBlue()  * (1 - r) + c2.getBlue()  * r));
    }

    /**
     * 全角文字を考慮しながら1行を描画する。
     * ASCII(0x20-0x7E): MiscFixedBold9x15 (X11 misc-fixed Bold 9x15) でレンダリング。
     * それ以外: Swing フォント（g2 に設定済み）で描画。
     * y はベースライン（セル底辺）の Y 座標。
     */
    private void drawLineWithFullWidthSupport(Graphics2D g2, String line, int y,
            int charWidth, int scrollOffsetX, int gutterWidth, boolean isErrorLine,
            List<SyntaxToken> tokens) {
        int x = gutterWidth - scrollOffsetX;
        int cellTopOffset = cellH; // y - cellTopOffset = cellTopY
        int tokenIdx = 0;
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            int widthMult = charCellWidth(codePoint);
            int charPixelWidth = charWidth * widthMult;
            if (x + charPixelWidth > 0 && x < getWidth()) {
                Color color = theme.foreground;
                if (!isErrorLine && tokens != null && !tokens.isEmpty()) {
                    while (tokenIdx < tokens.size() - 1 && tokens.get(tokenIdx).end() <= i) tokenIdx++;
                    SyntaxToken tok = tokens.get(tokenIdx);
                    if (i >= tok.start() && i < tok.end()) {
                        color = syntaxColor(tok.kind());
                    }
                }
                if (bitmapFont.isSupported(codePoint)) {
                    // errorLines 指定行のみ ERROR_COLOR の別キャッシュ（uiGlyphCache）で描画する。
                    // 通常行・DEFAULT色は本文専用キャッシュ（glyphCacheFg、テーマ色固定）のまま
                    // 高速に保ち、それ以外の構文色は汎用キャッシュ（uiGlyphCache）を使う。
                    BufferedImage glyph;
                    if (isErrorLine) {
                        glyph = getUiGlyph(codePoint, cellW, cellH, ERROR_COLOR);
                    } else if (color == theme.foreground) {
                        glyph = getGlyphFg(codePoint);
                    } else {
                        glyph = getUiGlyph(codePoint, cellW, cellH, color);
                    }
                    g2.drawImage(glyph, x, y - cellTopOffset, null);
                } else {
                    Color drawColor = isErrorLine ? ERROR_COLOR : color;
                    BufferedImage glyph = getNonAsciiGlyph(codePoint, charWidth, cellH, drawColor);
                    g2.drawImage(glyph, x, y - cellTopOffset, null);
                }
            }
            x += charPixelWidth;
            i += Character.charCount(codePoint);
            if (x >= getWidth()) break;
        }
    }

    private Color syntaxColor(SyntaxKind kind) {
        return switch (kind) {
            case COMMENT -> theme.syntaxComment;
            case STRING -> theme.syntaxString;
            case TYPE -> theme.syntaxType;
            case NUMBER -> theme.syntaxNumber;
            case PREPROCESSOR -> theme.syntaxPreprocessor;
            case KEYWORD -> theme.syntaxKeyword;
            case SYMBOL -> theme.syntaxSymbol;
            case OPERATOR -> theme.syntaxOperator;
            default -> theme.foreground;
        };
    }

    /** 行rowのトークン列を計算する。language==NONEの場合はハイライトなし(null)。 */
    private List<SyntaxToken> tokensForRow(String[] lines, int row) {
        if (language == SourceLanguage.NONE) return null;
        boolean startsInBlockComment = (row >= 0 && row < blockCommentStartsAt.length) && blockCommentStartsAt[row];
        return SyntaxHighlighter.tokenizeLine(lines[row], language, startsInBlockComment).tokens();
    }

    /** wrap時、docRowの全体トークンを[segStart,segEnd)に切り出しセグメント相対座標へ変換する。 */
    private List<SyntaxToken> tokensForSegment(String[] lines, int docRow, int segStart, int segEnd) {
        List<SyntaxToken> full = tokensForRow(lines, docRow);
        if (full == null) return null;
        List<SyntaxToken> clipped = new ArrayList<>(full.size());
        for (SyntaxToken t : full) {
            int s = Math.max(t.start(), segStart);
            int e = Math.min(t.end(), segEnd);
            if (s < e) clipped.add(new SyntaxToken(s - segStart, e - segStart, t.kind()));
        }
        return clipped;
    }

    private void drawCursor(Graphics2D g2, String[] lines, int charWidth,
            int lineHeight, int scrollOffsetX, int gutterWidth, List<WrapRow> wrapPlan) {
        String line = (cursorRow < lines.length) ? lines[cursorRow] : "";
        int screenRow;
        int x;
        if (wrapPlan != null) {
            int[] pos = findSegmentPixel(wrapPlan, line, cursorRow, cursorCol, charWidth, gutterWidth);
            if (pos == null) return;
            screenRow = pos[0];
            x = pos[1];
        } else {
            screenRow = cursorRow - scrollRow;
            if (screenRow < 0 || screenRow >= computeVisibleRows(lineHeight)) return;
            x = xForCol(line, cursorCol, charWidth) - scrollOffsetX + gutterWidth;
        }
        int yTop = screenRow * lineHeight;

        if (x + charWidth < 0 || x >= getWidth()) return;

        if (!composedText.isEmpty()) {
            drawImeComposition(g2, x, yTop, lineHeight);
            return;
        }

        int codePoint = (cursorCol < line.length()) ? line.codePointAt(cursorCol) : -1;
        int blockWidth = charWidth * (codePoint != -1 ? charCellWidth(codePoint) : 1);
        g2.setColor(theme.foreground);
        g2.fillRect(x, yTop, blockWidth, lineHeight);
        if (codePoint != -1) {
            if (bitmapFont.isSupported(codePoint)) {
                g2.drawImage(getGlyphBg(codePoint), x, yTop, null);
            } else {
                g2.setColor(theme.background);
                int swingBaselineY = (screenRow + 1) * lineHeight - bitmapFont.descentPixels(lineHeight);
                g2.drawString(new String(Character.toChars(codePoint)), x, swingBaselineY);
            }
        }
    }

    /**
     * IME変換中の未確定文字列（composedText）をカーソル位置にリアルタイムでオーバーレイ表示する。
     * 変換中であることが分かるよう下線（テーマのaccent色）を引く。ネイティブIME側の候補
     * ウィンドウ（getInputMethodRequests().getTextLocation()参照）とは表示位置を意図的に
     * ずらしており、重ならない。
     */
    private void drawImeComposition(Graphics2D g2, int x, int yTop, int lineHeight) {
        int w = uiTextWidth(composedText, cellW);
        g2.setColor(theme.background);
        g2.fillRect(x, yTop, w, lineHeight);
        drawUiText(g2, composedText, x, yTop + lineHeight, cellW, lineHeight, theme.foreground);
        // drawLineだとAA(アンチエイリアス)により1pxのストロークが上下2行に分かれてぼやけるため、
        // fillRectで1行分を確実に塗りつぶす。
        g2.setColor(theme.accent);
        g2.fillRect(x, yTop + lineHeight - 1, w, 1);
    }

    /** ガター列に診断マーカー（E / W）を描画する */
    private void drawGutter(Graphics2D g2, int charWidth, int lineHeight, int gutterWidth, List<WrapRow> wrapPlan) {
        // ガター背景（テーマ背景より少し暗く）
        g2.setColor(theme.background.darker());
        g2.fillRect(0, 0, gutterWidth, getHeight() - lineHeight);

        if (wrapPlan != null) {
            for (int screenRow = 0; screenRow < wrapPlan.size(); screenRow++) {
                WrapRow wr = wrapPlan.get(screenRow);
                if (wr.segStart() != 0) continue; // 折返しの継続行にはマーカーを出さない
                DiagnosticKind kind = diagByLine.get(wr.docRow());
                if (kind == null) continue;
                int y = (screenRow + 1) * lineHeight;
                g2.setColor(kind == DiagnosticKind.ERROR ? ERROR_COLOR : WARNING_COLOR);
                g2.drawString(kind == DiagnosticKind.ERROR ? "E" : "W", 0, y);
            }
            return;
        }

        int visibleRows = computeVisibleRows(lineHeight);
        int lastRow = Math.min(cachedLines.length, scrollRow + visibleRows);
        for (int row = scrollRow; row < lastRow; row++) {
            DiagnosticKind kind = diagByLine.get(row);
            if (kind == null) continue;
            int screenRow = row - scrollRow;
            int y = (screenRow + 1) * lineHeight;
            g2.setColor(kind == DiagnosticKind.ERROR ? ERROR_COLOR : WARNING_COLOR);
            g2.drawString(kind == DiagnosticKind.ERROR ? "E" : "W", 0, y);
        }
    }

    /** エラー・警告行のテキスト下に波線状アンダーラインを描画する */
    private void drawDiagnosticUnderlines(Graphics2D g2, String[] lines,
            int charWidth, int lineHeight, int scrollOffsetX, int gutterWidth, List<WrapRow> wrapPlan) {
        if (wrapPlan != null) {
            for (int screenRow = 0; screenRow < wrapPlan.size(); screenRow++) {
                WrapRow wr = wrapPlan.get(screenRow);
                DiagnosticKind kind = diagByLine.get(wr.docRow());
                if (kind == null) continue;
                String line = lines[wr.docRow()];
                int segPixelWidth = xForCol(line, wr.segEnd(), charWidth) - xForCol(line, wr.segStart(), charWidth);
                if (segPixelWidth == 0) segPixelWidth = charWidth; // 空行は1文字分
                int yUnder = (screenRow + 1) * lineHeight + 1;
                int xStart = gutterWidth;
                int xEnd = Math.min(xStart + segPixelWidth, getWidth());
                drawWaveUnderline(g2, kind, xStart, xEnd, yUnder);
            }
            return;
        }

        int visibleRows = computeVisibleRows(lineHeight);
        int lastRow = Math.min(lines.length, scrollRow + visibleRows);

        for (int row = scrollRow; row < lastRow; row++) {
            DiagnosticKind kind = diagByLine.get(row);
            if (kind == null) continue;
            int screenRow = row - scrollRow;
            int yBase = (screenRow + 1) * lineHeight; // ベースライン
            int yUnder = yBase + 1; // アンダーラインのY座標

            // 行全体の幅（文字数 × セル幅）を計算
            String line = (row < lines.length) ? lines[row] : "";
            int linePixelWidth = 0;
            for (int i = 0; i < line.length(); ) {
                int cp = line.codePointAt(i);
                linePixelWidth += charCellWidth(cp) * charWidth;
                i += Character.charCount(cp);
            }
            if (linePixelWidth == 0) linePixelWidth = charWidth; // 空行は1文字分

            int xStart = gutterWidth - scrollOffsetX;
            int xEnd = xStart + linePixelWidth;
            xStart = Math.max(xStart, gutterWidth);
            xEnd = Math.min(xEnd, getWidth());
            if (xStart >= xEnd) continue;

            drawWaveUnderline(g2, kind, xStart, xEnd, yUnder);
        }
    }

    /** 波線状のアンダーラインを [xStart, xEnd) の範囲に描画する（4pxごとに上下に振動）。 */
    private void drawWaveUnderline(Graphics2D g2, DiagnosticKind kind, int xStart, int xEnd, int yUnder) {
        if (xStart >= xEnd) return;
        g2.setColor(kind == DiagnosticKind.ERROR ? ERROR_COLOR : WARNING_COLOR);
        int amplitude = 1;
        int period = 4;
        for (int x = xStart; x < xEnd - period; x += period) {
            g2.drawLine(x,           yUnder + amplitude,
                        x + period/2, yUnder - amplitude);
            g2.drawLine(x + period/2, yUnder - amplitude,
                        x + period,   yUnder + amplitude);
        }
    }

    /**
     * カーソル列インデックス col の先頭から col 文字分の
     * セル幅の合計をピクセルで返す。
     * 全角文字（2セル）と半角文字（1セル）を正確に区別する。
     */
    private static int xForCol(String line, int col, int charWidth) {
        int x = 0;
        int count = 0;
        for (int i = 0; i < line.length() && count < col; ) {
            int cp = line.codePointAt(i);
            x += charCellWidth(cp) * charWidth;
            i += Character.charCount(cp);
            count++;
        }
        return x;
    }

    /**
     * 選択範囲のanchor/cursorを描画用のr1/c1(左上)〜r2/c2(右下)へ正規化する。
     * 矩形選択(blockMode)は行と列を独立にmin/maxする（左右上下どの方向へドラッグしても
     * 矩形の左上/右下が一意に決まる）。文字単位選択は「1本の連続テキスト」としての
     * 前後関係で行と列をまとめてswapする（列だけ独立にmin/maxすると、行が入れ替わる
     * ケースで文字順序と矛盾する）。
     */
    static int[] normalizeSelectionBounds(boolean blockMode,
            int anchorRow, int anchorCol, int cursorRow, int cursorCol) {
        if (blockMode) {
            return new int[] {
                Math.min(anchorRow, cursorRow), Math.min(anchorCol, cursorCol),
                Math.max(anchorRow, cursorRow), Math.max(anchorCol, cursorCol)
            };
        }
        int r1 = anchorRow, c1 = anchorCol, r2 = cursorRow, c2 = cursorCol;
        if (r1 > r2 || (r1 == r2 && c1 > c2)) {
            int tr = r1; r1 = r2; r2 = tr;
            int tc = c1; c1 = c2; c2 = tc;
        }
        return new int[] {r1, c1, r2, c2};
    }

    private void drawSelectionHighlight(Graphics2D g2, String[] lines,
            int charWidth, int lineHeight, int scrollOffsetX, int gutterWidth, List<WrapRow> wrapPlan) {
        int[] bounds = normalizeSelectionBounds(visualBlockMode,
                selAnchorRow, selAnchorCol, selCursorRow, selCursorCol);
        int r1 = bounds[0], c1 = bounds[1], r2 = bounds[2], c2 = bounds[3];

        g2.setColor(theme.accent);

        if (wrapPlan != null) {
            drawSelectionHighlightWrapped(g2, wrapPlan, lines, r1, c1, r2, c2, charWidth, lineHeight, gutterWidth);
            return;
        }

        if (visualBlockMode) {
            for (int row = Math.max(r1, scrollRow);
                 row <= Math.min(r2, scrollRow + computeVisibleRows(lineHeight) - 1);
                 row++) {
                int screenRow = row - scrollRow;
                int yTop = screenRow * lineHeight;
                String line = (row < lines.length) ? lines[row] : "";

                int xStart = xForCol(line, Math.min(c1, line.length()), charWidth) - scrollOffsetX + gutterWidth;
                int xEnd   = xForCol(line, Math.min(c2 + 1, line.length()), charWidth) - scrollOffsetX + gutterWidth;
                if (xEnd <= xStart) xEnd = xStart + charWidth;
                int drawStart = Math.max(xStart, gutterWidth);
                int drawEnd   = Math.min(xEnd, getWidth());
                if (drawStart < drawEnd) {
                    g2.fillRect(drawStart, yTop, drawEnd - drawStart, lineHeight);
                }
            }
        } else if (visualLineMode) {
            for (int row = Math.max(r1, scrollRow);
                 row <= Math.min(r2, scrollRow + computeVisibleRows(lineHeight) - 1);
                 row++) {
                int screenRow = row - scrollRow;
                g2.fillRect(gutterWidth, screenRow * lineHeight,
                    getWidth() - gutterWidth, lineHeight);
            }
        } else {
            for (int row = Math.max(r1, scrollRow);
                 row <= Math.min(r2, scrollRow + computeVisibleRows(lineHeight) - 1);
                 row++) {
                int screenRow = row - scrollRow;
                int yTop = screenRow * lineHeight;
                String line = (row < lines.length) ? lines[row] : "";

                int colStart = (row == r1) ? c1 : 0;
                int colEnd = (row == r2) ? c2 : Math.max(0, line.length() - 1);

                int xStart = xForCol(line, colStart, charWidth) - scrollOffsetX + gutterWidth;
                int xEnd   = xForCol(line, Math.min(colEnd + 1, line.length()), charWidth)
                             - scrollOffsetX + gutterWidth;
                if (xEnd <= xStart) xEnd = xStart + charWidth;
                int drawStart = Math.max(xStart, gutterWidth);
                int drawEnd   = Math.min(xEnd, getWidth());
                if (drawStart < drawEnd) {
                    g2.fillRect(drawStart, yTop, drawEnd - drawStart, lineHeight);
                }
            }
        }
    }

    /** wrap時の選択ハイライト。VISUAL LINEは折返し先の各スクリーン行も全幅で塗り、
     *  それ以外は drawWrappedRangeSpan() で行内範囲をセグメントごとに分割して塗る。 */
    private void drawSelectionHighlightWrapped(Graphics2D g2, List<WrapRow> wrapPlan, String[] lines,
            int r1, int c1, int r2, int c2, int charWidth, int lineHeight, int gutterWidth) {
        if (visualLineMode) {
            for (int screenRow = 0; screenRow < wrapPlan.size(); screenRow++) {
                WrapRow wr = wrapPlan.get(screenRow);
                if (wr.docRow() >= r1 && wr.docRow() <= r2) {
                    g2.fillRect(gutterWidth, screenRow * lineHeight, getWidth() - gutterWidth, lineHeight);
                }
            }
            return;
        }
        for (int row = r1; row <= r2; row++) {
            if (row < 0 || row >= lines.length) continue;
            String line = lines[row];
            int colStart, colEndExclusive;
            if (visualBlockMode) {
                colStart = Math.min(c1, line.length());
                colEndExclusive = Math.min(c2 + 1, line.length());
            } else {
                colStart = (row == r1) ? c1 : 0;
                int colEnd = (row == r2) ? c2 : Math.max(0, line.length() - 1);
                colEndExclusive = Math.min(colEnd + 1, line.length());
            }
            drawWrappedRangeSpan(g2, wrapPlan, line, row, colStart, colEndExclusive, charWidth, lineHeight, gutterWidth);
        }
    }

    private void drawSearchHighlights(Graphics2D g2, String[] lines, int charWidth,
            int lineHeight, int scrollOffsetX, int gutterWidth, List<WrapRow> wrapPlan) {
        g2.setColor(SEARCH_HIGHLIGHT_COLOR);
        if (wrapPlan != null) {
            for (int[] h : searchHighlights) {
                int row = h[0], c1 = h[1], c2 = h[2];
                if (row < 0 || row >= lines.length) continue;
                drawWrappedRangeSpan(g2, wrapPlan, lines[row], row, c1, c2, charWidth, lineHeight, gutterWidth);
            }
            return;
        }
        int visibleRows = computeVisibleRows(lineHeight);
        for (int[] h : searchHighlights) {
            int row = h[0], c1 = h[1], c2 = h[2];
            if (row < scrollRow || row >= scrollRow + visibleRows) continue;
            int screenRow = row - scrollRow;
            int yTop = screenRow * lineHeight;
            String line = (row < lines.length) ? lines[row] : "";
            int xStart = xForCol(line, c1, charWidth) - scrollOffsetX + gutterWidth;
            int xEnd   = xForCol(line, c2, charWidth) - scrollOffsetX + gutterWidth;
            if (xEnd <= xStart) xEnd = xStart + charWidth;
            int drawStart = Math.max(xStart, gutterWidth);
            int drawEnd   = Math.min(xEnd, getWidth());
            if (drawStart < drawEnd) {
                g2.fillRect(drawStart, yTop, drawEnd - drawStart, lineHeight);
            }
        }
    }

    /**
     * Vim 風のスプラッシュ画面を描画する。
     * テキストエリア中央に概要テキストを表示し、ステータス行直上まで使う。
     */
    private void drawSplashScreen(Graphics2D g2, int charWidth, int lineHeight) {
        // フォントを明示的に設定してから FontMetrics を取得
        g2.setFont(SPLASH_FONT);
        FontMetrics fm = g2.getFontMetrics();
        int statusH = lineHeight;
        int areaH   = getHeight() - statusH;  // ステータス行を除いた描画高さ
        int areaW   = getWidth();

        String[] lines = {
            "Java Text Editor",
            "",
            "A lightweight text editor built on Java SE",
            "",
            "version 1.0.0  |  Java " + System.getProperty("java.version"),
            "",
            "─────────────────────────────────────────",
            "",
            "  i        Enter INSERT mode (type text)",
            "  Esc      Return to NORMAL mode",
            "  :e <path>  Open a file",
            "  :w <path>  Save a file",
            "  :q         Quit",
            "  K        Show JDK API info for the symbol under the cursor",
            "  :tutor or :tutorial  Open the interactive tutorial",
            "  Ctrl+W              Switch between left/right panes",
            "  Ctrl+Shift+↑↓←→  Change the active pane's font size",
            "",
            "─────────────────────────────────────────",
            "",
            "Press any key to start editing",
        };

        // タイトル行（index 0）は大きなフォントで描く
        Font titleFont  = SPLASH_FONT.deriveFont(Font.BOLD, SPLASH_FONT.getSize() + 8f);
        Font normalFont = SPLASH_FONT;

        // 全行の合計高さを計算して垂直中央揃え
        int totalH = lines.length * lineHeight + 8; // タイトルのフォントサイズ差分
        int startY = Math.max(lineHeight, (areaH - totalH) / 2) + lineHeight;

        // キーバインド行のブロック全体を水平中央揃えするため、最大幅を先に求める
        int maxKeyLineW = 0;
        for (String line : lines) {
            if (line.startsWith("  ") && !line.isBlank()) {
                maxKeyLineW = Math.max(maxKeyLineW, fm.stringWidth(line));
            }
        }
        int keyBlockX = (areaW - maxKeyLineW) / 2;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int y = startY + i * lineHeight;
            if (y > areaH) break;

            if (i == 0) {
                // タイトル：センタリング・アクセントカラー・太字
                g2.setFont(titleFont);
                g2.setColor(theme.accent);
                int w = g2.getFontMetrics().stringWidth(line);
                g2.drawString(line, (areaW - w) / 2, y);
                g2.setFont(normalFont);
            } else if (line.startsWith("─")) {
                // 区切り線：dim カラー（前景を少し暗く）
                g2.setColor(theme.foreground.darker());
                int w = fm.stringWidth(line);
                g2.drawString(line, (areaW - w) / 2, y);
            } else if (line.startsWith("  ") && !line.isBlank()) {
                // キーバインド行：行全体を水平中央から描画
                g2.setColor(theme.foreground);
                g2.drawString(line, keyBlockX, y);
            } else if (!line.isBlank()) {
                // サブタイトル・説明文：センタリング・前景色
                boolean isHint = line.contains("Press any key");
                g2.setColor(isHint ? theme.accent : theme.foreground);
                if (isHint) {
                    g2.setFont(SPLASH_FONT.deriveFont(Font.ITALIC));
                    fm = g2.getFontMetrics();
                }
                int w = fm.stringWidth(line);
                g2.drawString(line, (areaW - w) / 2, y);
                if (isHint) {
                    g2.setFont(normalFont);
                    fm = g2.getFontMetrics();
                }
            }
        }
    }

    private void drawStatusLine(Graphics2D g2, int lineHeight) {
        int y = getHeight() - 4;
        g2.setColor(theme.accent);
        g2.fillRect(0, y - lineHeight, getWidth(), lineHeight);
        String label = (commandLineText != null) ? commandLineText
                     : visualBlockMode ? "-- VISUAL BLOCK --"
                     : visualLineMode ? "-- VISUAL LINE --"
                     : visualMode     ? "-- VISUAL --"
                     : insertMode     ? "-- INSERT --"
                     :                  "-- NORMAL --";
        drawUiText(g2, label, 4, y, cellW, lineHeight, theme.background);

        // 右端に現在時刻（24時間表記）を表示
        String clockLabel = LocalTime.now().format(CLOCK_FORMAT);
        int clockWidth = uiTextWidth(clockLabel, cellW);
        int rightX = getWidth() - clockWidth - 4;
        drawUiText(g2, clockLabel, rightX, y, cellW, lineHeight, theme.background);

        // CPU使用率・GPU使用率・メモリ使用率（取得できた項目のみ"|"区切り）は時刻表示の左隣に表示。
        // カーソル位置（行数:トータル文字数）はCPU使用率の隣に"|"区切りで表示する。
        String statsLabel = SystemStatsMonitor.INSTANCE.getStatusLabel();
        String rightStatsLabel = statsLabel.isEmpty()
            ? cursorPositionLabel
            : cursorPositionLabel + " | " + statsLabel;
        int statsWidth = uiTextWidth(rightStatsLabel, cellW);
        rightX -= statsWidth + cellW; // 時刻表示との間に1文字分の余白
        drawUiText(g2, rightStatsLabel, rightX, y, cellW, lineHeight, theme.background);

        // 診断件数はシステムステータス表示のさらに左隣に表示
        if (!diagnostics.isEmpty()) {
            long errCount  = diagnostics.stream()
                .filter(d -> d.kind() == DiagnosticKind.ERROR).count();
            long warnCount = diagnostics.stream()
                .filter(d -> d.kind() == DiagnosticKind.WARNING).count();
            String diagLabel = buildDiagLabel(errCount, warnCount);
            int labelWidth = uiTextWidth(diagLabel, cellW);
            rightX -= labelWidth + cellW; // システムステータス表示との間に1文字分の余白
            drawUiText(g2, diagLabel, rightX, y, cellW, lineHeight, theme.background);
        }

        // ウォーキングパーソンアニメーション（左→右へ走り抜ける）。
        // ウィンドウ分割時は現在アクティブなペインにのみ表示する。
        if (activePane) {
            drawWalkingPerson(g2, y - lineHeight + 1, lineHeight);
        }
    }

    private void drawWalkingPerson(Graphics2D g2, int statusTopY, int lineHeight) {
        double elapsed = (System.currentTimeMillis() - animStartMs) / 1000.0;
        double scale = WalkingPersonSprite.heightScale(lineHeight);
        int frame  = WalkingPersonSprite.calcFrame(elapsed);
        int x      = WalkingPersonSprite.calcX(elapsed, getWidth(), scale);
        // スプライトの高さは常に lineHeight（=文字の高さ）にちょうど一致するため、
        // ステータスライン内でずれることなく描画される。
        int spriteH = (int) Math.round(WalkingPersonSprite.PERSON_H * scale);
        int y = statusTopY + (lineHeight - spriteH) / 2;
        // ステータスライン背景色（accent）に対して視認性の高い色を選択する
        Color spriteColor = contrastColor(theme.accent, theme.foreground, theme.background);
        WalkingPersonSprite.drawFrame(g2, frame, x, y, scale, spriteColor);
    }

    /** accent に対してより高いコントラストを持つ方の色を返す。 */
    private static Color contrastColor(Color accent, Color a, Color b) {
        return (luminance(a) - luminance(accent)) * (luminance(a) - luminance(accent))
             > (luminance(b) - luminance(accent)) * (luminance(b) - luminance(accent))
             ? a : b;
    }

    private static double luminance(Color c) {
        // sRGB 相対輝度（BT.709 係数）
        double r = c.getRed()   / 255.0;
        double g = c.getGreen() / 255.0;
        double bl = c.getBlue() / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
    }

    private static String buildDiagLabel(long errors, long warnings) {
        if (errors > 0 && warnings > 0) {
            return errors + " error" + (errors > 1 ? "s" : "")
                + ", " + warnings + " warning" + (warnings > 1 ? "s" : "");
        } else if (errors > 0) {
            return errors + " error" + (errors > 1 ? "s" : "");
        } else {
            return warnings + " warning" + (warnings > 1 ? "s" : "");
        }
    }

    /**
     * 1文字（コードポイント）が全角（2セル分）か半角（1セル分）かを判定する。
     * 厳密なUnicode East Asian Width判定は複雑だが、Javaプログラミング用途では
     * CJK・ひらがな・カタカナの範囲を押さえれば実用上十分。
     */
    public static int charCellWidth(int codePoint) {
        if (codePoint >= 0x3000 && codePoint <= 0x303F) return 2; // CJK記号・句読点（「」『』、。〜等）
        if (codePoint >= 0x3040 && codePoint <= 0x30FF) return 2; // ひらがな・カタカナ
        if (codePoint >= 0x4E00 && codePoint <= 0x9FFF) return 2; // CJK統合漢字
        if (codePoint >= 0xFF01 && codePoint <= 0xFF60) return 2; // 全角英数・記号（全角ASCII相当）
        if (codePoint >= 0xFFE0 && codePoint <= 0xFFE6) return 2; // 全角記号（￠￡￢￤￥￦）
        if (codePoint >= 0x25A0 && codePoint <= 0x25FF) return 2; // 幾何学記号（■□▲△▼▽◆◇○◎●等）
        if (codePoint >= 0x2460 && codePoint <= 0x24FF) return 2; // 囲み英数字（①②③丸数字等）
        return 1; // 半角カタカナ(0xFF61-0xFF9F)等はここでdefaultの1になる
    }
}
