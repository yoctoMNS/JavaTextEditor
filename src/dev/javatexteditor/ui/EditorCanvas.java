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
import java.awt.font.TextHitInfo;
import java.awt.im.InputContext;
import java.awt.im.InputMethodRequests;
import java.awt.image.BufferedImage;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.text.CharacterIterator;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
    private int scrollRow = 0;
    private int scrollCol = 0;              // 横スクロール（セル単位）
    private int cachedLineHeight = 20;      // 初回 paint 前の近似値
    private int cachedCharWidth  = 10;      // 初回 paint 前の近似値
    private String commandLineText = null;  // null = 通常のモード表示
    private int selAnchorRow = -1;
    private int selAnchorCol = -1;
    private int selCursorRow = -1;
    private int selCursorCol = -1;

    // スプラッシュ画面フラグ（true のとき通常テキストの代わりにスプラッシュを描画）
    private boolean showSplash = false;

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
    private boolean completionActive = false;
    private List<String> completionLabels = List.of();
    private List<String> completionKinds  = List.of();
    private int completionSelectedIdx = 0;
    private int completionAnchorRow = 0;
    private int completionAnchorCol = 0;

    // telescope オーバーレイ状態
    private boolean telescopeActive = false;
    private String telescopeTitle = "";
    private String telescopeQuery = "";
    private List<TelescopeItem> telescopeResults = List.of();
    private int telescopeSelectedIdx = 0;
    private String telescopePreview = "";

    // 診断情報（エラー・警告）。空リストのときはガターを描画しない。
    private List<CompileDiagnostic> diagnostics = List.of();
    // 行番号 → 最も優先度の高い診断種別（ERROR > WARNING）
    private Map<Integer, DiagnosticKind> diagByLine = Map.of();

    // F10/F11: *compile*/*run* 疑似バッファのリアルタイムログ表示用。
    // 標準エラー出力・ERROR診断の行番号集合（この行だけ ERROR_COLOR で描画する）。
    private Set<Integer> errorLines = Set.of();

    // 半角ASCIIフォントのセルサイズ（Ctrl+Shift+矢印で変更可能）
    private int cellW = TtfMonoFont.BASE_CELL_W;
    private int cellH = TtfMonoFont.BASE_CELL_H;

    // 半角ASCIIは IBM Plex Mono Regular (TTF) を cellW×cellH に合わせて
    // 非等方向にスケールしてラスタライズする（TtfMonoFont参照）。
    private final TtfMonoFont ttfFont = TtfMonoFont.INSTANCE;

    // グリフキャッシュ: codePoint → レンダリング済み BufferedImage（透明背景・fg色）
    // セルサイズまたはテーマが変わったら invalidateGlyphCache() でクリアする
    private final Map<Integer, BufferedImage> glyphCacheFg  = new HashMap<>();
    private final Map<Integer, BufferedImage> glyphCacheBg  = new HashMap<>();

    // telescope・ステータス行・補完ポップアップ等、本文以外のUI文字列描画用グリフキャッシュ。
    // 本文用キャッシュ（glyphCacheFg/Bg）と違い任意の色・セルサイズを扱うためキーにそれらを含む。
    private record UiGlyphKey(int codePoint, int cellW, int cellH, int rgb) {}
    private final Map<UiGlyphKey, BufferedImage> uiGlyphCache = new HashMap<>();

    // 非ASCII文字描画用フォールバック Swing フォント（セルサイズに合わせて動的生成）
    private Font swingFont = null;
    private int  swingFontCellH = 0;   // swingFont を生成した時の cellH

    private static final Font  SPLASH_FONT   = new Font(Font.MONOSPACED, Font.PLAIN, 16);
    private static final Color ERROR_COLOR   = new Color(0xCC, 0x33, 0x33);
    private static final Color WARNING_COLOR = new Color(0xCC, 0x99, 0x00);

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
     */
    public void switchToHalfWidth() {
        InputContext ic = getInputContext();
        if (ic == null) return;
        try {
            ic.setCompositionEnabled(false);
        } catch (Exception ignored) {}
        try {
            ic.selectInputMethod(Locale.ENGLISH);
        } catch (Exception ignored) {}
    }

    /** IMEが確定した文字列を受け取るコールバックを設定する（Main.javaから配線）。 */
    public void setImeCommitHandler(Consumer<String> handler) {
        this.imeCommitHandler = handler;
    }

    /** INSERT→NORMAL遷移時など、変換中の未確定文字列の表示を消す。 */
    public void clearImeComposition() {
        this.composedText = "";
        repaint();
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
        repaint();
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
     * 一方で、この浮動ウィンドウ（ネイティブ側）自体にも変換中の文字列が表示されるため、
     * カーソルのすぐ下（1行分だけ下）を返すと、EditorCanvas自前の drawImeComposition() の
     * 表示（カーソル位置そのもの＝現在行）と重なって見えることが実機検証で判明した。
     * そのため、意図的にさらに1行分（計2行分）下にずらした位置を返し、自前のリアルタイム
     * 入力表示（現在行）とネイティブ側の変換候補ウィンドウ（2行下）が重ならないようにしている。
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
            int gutterWidth = diagnostics.isEmpty() ? 0 : 2 * charWidth;
            int screenRow = cursorRow - scrollRow;
            String line = (cursorRow < cachedLines.length) ? cachedLines[cursorRow] : "";
            int x = xForCol(line, cursorCol, charWidth) - scrollCol * charWidth + gutterWidth;
            int y = screenRow * lineHeight;
            return new Rectangle(base.x + x, base.y + y + 2 * lineHeight, 1, lineHeight);
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
        this.text = text;
        this.cachedLines = text.split("\n", -1);
        repaint();
    }
    public void setCursor(int row, int col) { this.cursorRow = row; this.cursorCol = col; repaint(); }
    public void setCursorPositionLabel(String label) { this.cursorPositionLabel = label; repaint(); }
    public void setInsertMode(boolean insertMode) { this.insertMode = insertMode; repaint(); }
    public void setTheme(Theme theme) { this.theme = theme; invalidateGlyphCache(); repaint(); }
    public void setScrollRow(int scrollRow) { this.scrollRow = Math.max(0, scrollRow); repaint(); }
    public int getScrollRow() { return scrollRow; }
    public void setScrollCol(int col) { this.scrollCol = Math.max(0, col); repaint(); }
    public int getScrollCol() { return scrollCol; }
    public int getVisibleRows() { return computeVisibleRows(cachedLineHeight > 0 ? cachedLineHeight : 16); }
    public void setCommandLineText(String text) { this.commandLineText = text; repaint(); }
    public String getCommandLineText() { return commandLineText; }
    public String getCursorPositionLabel() { return cursorPositionLabel; }
    public void setVisualMode(boolean visualMode) { this.visualMode = visualMode; repaint(); }
    public void setVisualLineMode(boolean visualLineMode) { this.visualLineMode = visualLineMode; repaint(); }
    public void setVisualBlockMode(boolean visualBlockMode) { this.visualBlockMode = visualBlockMode; repaint(); }
    public void setSelection(int anchorRow, int anchorCol, int cursorRow, int cursorCol) {
        this.selAnchorRow = anchorRow;
        this.selAnchorCol = anchorCol;
        this.selCursorRow = cursorRow;
        this.selCursorCol = cursorCol;
        repaint();
    }
    public void clearSelection() {
        this.selAnchorRow = -1;
        this.visualLineMode = false;
        this.visualBlockMode = false;
        repaint();
    }

    /**
     * 入力補完ポップアップの状態をセットする。
     * labels / kinds は CompletionItem のリストから変換して渡す。
     */
    public void setCompletionState(boolean active, List<String> labels, List<String> kinds,
                                   int selectedIdx, int anchorRow, int anchorCol) {
        this.completionActive       = active;
        this.completionLabels       = (labels != null) ? List.copyOf(labels)  : List.of();
        this.completionKinds        = (kinds  != null) ? List.copyOf(kinds)   : List.of();
        this.completionSelectedIdx  = selectedIdx;
        this.completionAnchorRow    = anchorRow;
        this.completionAnchorCol    = anchorCol;
        repaint();
    }

    public void setTelescopeState(boolean active, String title, String query,
            List<TelescopeItem> results, int selectedIdx, String preview) {
        this.telescopeActive    = active;
        this.telescopeTitle     = title != null ? title : "";
        this.telescopeQuery     = query != null ? query : "";
        this.telescopeResults   = results != null ? results : List.of();
        this.telescopeSelectedIdx = selectedIdx;
        this.telescopePreview   = preview != null ? preview : "";
        repaint();
    }

    public void setSearchHighlights(List<int[]> highlights) {
        this.searchHighlights = (highlights != null) ? List.copyOf(highlights) : List.of();
        repaint();
    }

    public List<int[]> getSearchHighlights() { return searchHighlights; }

    // -------------------------------------------------------------------------
    // フォントセルサイズ調整（Ctrl+Shift+矢印）
    // -------------------------------------------------------------------------

    /** 文字セル幅を delta px 変更する（範囲: 5〜40）。両ペインから呼ばれる。 */
    public void adjustCellWidth(int delta) {
        cellW = Math.max(5, Math.min(40, cellW + delta));
        invalidateGlyphCache();
        cachedCharWidth = cellW;
        repaint();
    }

    /** 文字セル高さを delta px 変更する（範囲: 8〜80）。両ペインから呼ばれる。 */
    public void adjustCellHeight(int delta) {
        cellH = Math.max(8, Math.min(80, cellH + delta));
        invalidateGlyphCache();
        cachedLineHeight = cellH;
        repaint();
    }

    public int getCellW() { return cellW; }
    public int getCellH() { return cellH; }

    /**
     * 起動時に一度だけ、絶対値でセルサイズを設定する（4K等の高解像度ディスプレイでフォントが
     * 小さすぎるのを防ぐため）。以後はユーザーが Ctrl+Shift+矢印で自由に変更できる。
     */
    public void setInitialCellSize(int w, int h) {
        cellW = Math.max(5, Math.min(40, w));
        cellH = Math.max(8, Math.min(80, h));
        invalidateGlyphCache();
        cachedCharWidth = cellW;
        cachedLineHeight = cellH;
    }

    private void invalidateGlyphCache() {
        glyphCacheFg.clear();
        glyphCacheBg.clear();
        uiGlyphCache.clear();
    }

    private BufferedImage getUiGlyph(int codePoint, int cw, int ch, Color color) {
        UiGlyphKey key = new UiGlyphKey(codePoint, cw, ch, color.getRGB());
        return uiGlyphCache.computeIfAbsent(key,
            k -> ttfFont.renderGlyph(codePoint, cw, ch, color.getRGB()));
    }

    /**
     * telescope・ステータス行・補完ポップアップ等、本文以外のUI文字列を IBM Plex Mono
     * ビットマップフォントで描画する（本文の drawLineWithFullWidthSupport と同じ配色規則:
     * ASCIIはビットマップフォント、それ以外（日本語等）は Swing フォールバックフォント）。
     * y はセル下端（ベースライン）のY座標。
     */
    private void drawUiText(Graphics2D g2, String s, int x, int y, int cw, int ch, Color color) {
        int swingBaselineY = y - ttfFont.descentPixels(ch);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int widthMult = charCellWidth(cp);
            if (ttfFont.isSupported(cp)) {
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
            k -> ttfFont.renderGlyph(k, cellW, cellH, theme.foreground.getRGB()));
    }

    private BufferedImage getGlyphBg(int cp) {
        return glyphCacheBg.computeIfAbsent(cp,
            k -> ttfFont.renderGlyph(k, cellW, cellH, theme.background.getRGB()));
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
        repaint();
    }

    public boolean isShowSplash() { return showSplash; }

    /**
     * *compile* / *run* 疑似バッファの行のうち、赤字（標準エラー出力・ERROR診断）で描画する
     * 行番号の集合をセットする。空集合なら全行が通常の前景色で描画される。
     */
    public void setErrorLines(Set<Integer> errorLines) {
        this.errorLines = (errorLines != null) ? Set.copyOf(errorLines) : Set.of();
        repaint();
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
        repaint();
    }

    /** 現在保持している診断リストを返す（テスト用）。 */
    public List<CompileDiagnostic> getDiagnostics() { return diagnostics; }

    /**
     * カーソル行が表示範囲に収まるよう scrollRow を調整する。
     * ModalEditor がカーソル移動後に呼ぶことでスクロールを追従させる。
     */
    public void ensureCursorVisible(int cursorRow) {
        int visibleRows = computeVisibleRows(cachedLineHeight);
        if (cursorRow < scrollRow) {
            scrollRow = cursorRow;
            repaint();
        } else if (cursorRow >= scrollRow + visibleRows) {
            scrollRow = Math.max(0, cursorRow - visibleRows + 1);
            repaint();
        }
    }

    /**
     * カーソル列が表示範囲に収まるよう scrollCol を調整する。
     * ModalEditor がカーソル移動後に呼ぶことで横スクロールを追従させる。
     *
     * @param col      カーソルの文字インデックス（line の何文字目か）
     * @param line     カーソルがいる行の文字列（全角幅計算に使用）
     */
    public void ensureCursorColVisible(int col, String line) {
        int cursorCells = cellsForCol(line, col);
        int visibleCols = computeVisibleCols();
        if (cursorCells < scrollCol) {
            scrollCol = cursorCells;
            repaint();
        } else if (visibleCols > 0 && cursorCells >= scrollCol + visibleCols) {
            scrollCol = cursorCells - visibleCols + 1;
            repaint();
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
        // ビットマップフォント(TtfMonoFont)のグリフはラスタライズ時に既にアンチエイリアス済みだが、
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
        int gutterWidth = diagnostics.isEmpty() ? 0 : 2 * charWidth;
        int scrollOffsetX = scrollCol * charWidth;

        // 再描画範囲がステータス行の帯に収まっている場合（歩行アニメーションのティック）は、
        // 本文（数十万行規模になりうる）の再描画を丸ごとスキップし、ステータス行だけ塗り直す。
        Rectangle clip = g2.getClipBounds();
        boolean statusLineOnly = clip != null && !showSplash && !telescopeActive
            && !(completionActive && !completionLabels.isEmpty())
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

        // 1.5 選択ハイライト（VISUALモード時）
        String[] lines = cachedLines;
        if (visualMode && selAnchorRow >= 0) {
            drawSelectionHighlight(g2, lines,
                charWidth, lineHeight, scrollOffsetX, gutterWidth);
        }

        // 1.6 検索ハイライト（/pattern、*、# による検索結果）
        if (!searchHighlights.isEmpty()) {
            drawSearchHighlights(g2, lines, charWidth, lineHeight, scrollOffsetX, gutterWidth);
        }

        // 2. 表示行範囲（scrollRow 〜 scrollRow+visibleRows）のみ描画する
        g2.setColor(theme.foreground);
        int visibleRows = computeVisibleRows(lineHeight);
        int lastRow = Math.min(lines.length, scrollRow + visibleRows);
        for (int row = scrollRow; row < lastRow; row++) {
            int screenRow = row - scrollRow;
            int y = (screenRow + 1) * lineHeight;
            drawLineWithFullWidthSupport(g2, lines[row], y, charWidth, scrollOffsetX, gutterWidth,
                errorLines.contains(row));
        }

        // 2.5 zz等でファイル末尾を超えてスクロールした場合、行が存在しない領域を
        //     テーマの通常背景色ではなく純粋な白(ライト)/黒(ダーク)で明示的に塗る。
        //     カーソルはこの領域には存在し得ない（cursorRowは常に有効な行番号にクランプされる）。
        int voidScreenRowStart = Math.max(0, lastRow - scrollRow);
        if (voidScreenRowStart < visibleRows) {
            int voidY = voidScreenRowStart * lineHeight;
            g2.setColor(theme == Theme.LIGHT_MODE ? Color.WHITE : Color.BLACK);
            g2.fillRect(0, voidY, getWidth(), visibleRows * lineHeight - voidY);
        }

        // 3. カーソルを描画する（縦・横スクロールオフセット考慮）
        drawCursor(g2, lines, charWidth, lineHeight, scrollOffsetX, gutterWidth);

        // 4. ガター（診断マーカー）を描画する
        if (gutterWidth > 0) {
            drawGutter(g2, charWidth, lineHeight, gutterWidth);
        }

        // 5. エラー・警告アンダーラインを描画する
        if (!diagByLine.isEmpty()) {
            drawDiagnosticUnderlines(g2, lines, charWidth, lineHeight, scrollOffsetX, gutterWidth);
        }

        // 6. ステータス行を描画する（画面最下部）
        drawStatusLine(g2, lineHeight);

        // 7. telescope オーバーレイ（最前面に描画）
        if (telescopeActive) {
            drawTelescopeOverlay(g2, lineHeight);
        }

        // 8. 入力補完ポップアップ（telescope より前面）
        if (completionActive && !completionLabels.isEmpty()) {
            drawCompletionPopup(g2, charWidth, lineHeight, gutterWidth);
        }
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

        // 本文と同じ IBM Plex Mono のセルサイズをそのまま使う
        int cw = cellW;
        int fh = lineHeight;
        int pad = 4;

        // プロンプト行
        g2.setColor(theme.accent);
        g2.fillRect(ox, oy, overlayW, fh + pad * 2);
        String promptText = telescopeTitle + "  > " + telescopeQuery + "_";
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
        int visStart = Math.max(0, telescopeSelectedIdx - maxResultRows + 1);
        if (telescopeSelectedIdx < visStart) visStart = telescopeSelectedIdx;

        for (int i = visStart; i < telescopeResults.size() && (i - visStart) < maxResultRows; i++) {
            TelescopeItem item = telescopeResults.get(i);
            int ry = bodyY + (i - visStart + 1) * fh;
            Color rowColor;
            if (i == telescopeSelectedIdx) {
                g2.setColor(theme.accent);
                g2.fillRect(ox + 1, ry - fh, resultsW - 2, fh);
                rowColor = theme.background;
            } else {
                rowColor = theme.foreground;
            }
            String label = (i == telescopeSelectedIdx ? "> " : "  ") + item.display();
            String clipped = clipToUiWidth(label, cw, resultsW - pad * 2);
            drawUiText(g2, clipped, ox + pad, ry, cw, fh, rowColor);
        }

        // Preview ペイン
        String[] previewLines = telescopePreview.split("\n", -1);
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
        if (completionLabels.isEmpty()) return;

        int cw = cellW;
        int fh = lineHeight;
        int pad = 4;
        int kindW = uiTextWidth("mth", cw) + pad; // kind ラベルの幅

        // 最長ラベル幅を計算してポップアップ幅を決定
        int maxLabelW = 0;
        for (String label : completionLabels) {
            maxLabelW = Math.max(maxLabelW, uiTextWidth(label, cw));
        }
        int popupW = kindW + maxLabelW + pad * 3;
        int popupH = completionLabels.size() * fh + pad * 2;

        // カーソル行の文字列でセルオフセットを計算（全角対応）
        String[] lines = cachedLines;
        String anchorLine = (completionAnchorRow < lines.length) ? lines[completionAnchorRow] : "";
        int cellOffset = cellsForCol(anchorLine, completionAnchorCol);

        int anchorScreenRow = completionAnchorRow - scrollRow;
        int popupX = gutterWidth + cellOffset * charWidth - scrollCol * charWidth;
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

        // 各候補を描画
        for (int i = 0; i < completionLabels.size(); i++) {
            int iy = popupY + pad + (i + 1) * fh;
            int rowTop = popupY + pad + i * fh;
            String kind = (i < completionKinds.size()) ? completionKinds.get(i) : "";

            if (i == completionSelectedIdx) {
                g2.setColor(theme.accent);
                g2.fillRect(popupX + 1, rowTop, popupW - 2, fh);
                // kind ラベル（選択行）
                drawUiText(g2, kind, popupX + pad, iy, cw, fh, theme.background);
                drawUiText(g2, completionLabels.get(i), popupX + pad + kindW, iy, cw, fh, theme.background);
            } else {
                // kind ラベルをアクセント色で
                drawUiText(g2, kind, popupX + pad, iy, cw, fh, theme.accent);
                drawUiText(g2, completionLabels.get(i), popupX + pad + kindW, iy, cw, fh, theme.foreground);
            }
        }
    }

    /**
     * 全角文字を考慮しながら1行を描画する。
     * ASCII(0x20-0x7E): TtfMonoFont (IBM Plex Mono Regular) でレンダリング。
     * それ以外: Swing フォント（g2 に設定済み）で描画。
     * y はベースライン（セル底辺）の Y 座標。
     */
    private void drawLineWithFullWidthSupport(Graphics2D g2, String line, int y,
            int charWidth, int scrollOffsetX, int gutterWidth, boolean isErrorLine) {
        int x = gutterWidth - scrollOffsetX;
        int cellTopOffset = cellH; // y - cellTopOffset = cellTopY
        int swingBaselineY = y - ttfFont.descentPixels(cellH);
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            int widthMult = charCellWidth(codePoint);
            int charPixelWidth = charWidth * widthMult;
            if (x + charPixelWidth > 0 && x < getWidth()) {
                if (ttfFont.isSupported(codePoint)) {
                    // errorLines 指定行のみ ERROR_COLOR の別キャッシュ（uiGlyphCache）で描画する。
                    // 通常行は本文専用キャッシュ（glyphCacheFg、テーマ色固定）のまま高速に保つ。
                    BufferedImage glyph = isErrorLine
                        ? getUiGlyph(codePoint, cellW, cellH, ERROR_COLOR)
                        : getGlyphFg(codePoint);
                    g2.drawImage(glyph, x, y - cellTopOffset, null);
                } else {
                    g2.setColor(isErrorLine ? ERROR_COLOR : theme.foreground);
                    g2.drawString(new String(Character.toChars(codePoint)), x, swingBaselineY);
                }
            }
            x += charPixelWidth;
            i += Character.charCount(codePoint);
            if (x >= getWidth()) break;
        }
    }

    private void drawCursor(Graphics2D g2, String[] lines, int charWidth,
            int lineHeight, int scrollOffsetX, int gutterWidth) {
        int screenRow = cursorRow - scrollRow;
        if (screenRow < 0 || screenRow >= computeVisibleRows(lineHeight)) return;

        String line = (cursorRow < lines.length) ? lines[cursorRow] : "";
        int x = xForCol(line, cursorCol, charWidth) - scrollOffsetX + gutterWidth;
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
            if (ttfFont.isSupported(codePoint)) {
                g2.drawImage(getGlyphBg(codePoint), x, yTop, null);
            } else {
                g2.setColor(theme.background);
                int swingBaselineY = (screenRow + 1) * lineHeight - ttfFont.descentPixels(lineHeight);
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
    private void drawGutter(Graphics2D g2, int charWidth, int lineHeight, int gutterWidth) {
        int visibleRows = computeVisibleRows(lineHeight);
        int lastRow = Math.min(cachedLines.length, scrollRow + visibleRows);

        // ガター背景（テーマ背景より少し暗く）
        g2.setColor(theme.background.darker());
        g2.fillRect(0, 0, gutterWidth, getHeight() - lineHeight);

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
            int charWidth, int lineHeight, int scrollOffsetX, int gutterWidth) {
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

            g2.setColor(kind == DiagnosticKind.ERROR ? ERROR_COLOR : WARNING_COLOR);
            // 波線: 4pxごとに上下に振動
            int amplitude = 1;
            int period = 4;
            for (int x = xStart; x < xEnd - period; x += period) {
                g2.drawLine(x,           yUnder + amplitude,
                            x + period/2, yUnder - amplitude);
                g2.drawLine(x + period/2, yUnder - amplitude,
                            x + period,   yUnder + amplitude);
            }
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

    private void drawSelectionHighlight(Graphics2D g2, String[] lines,
            int charWidth, int lineHeight, int scrollOffsetX, int gutterWidth) {
        int r1 = selAnchorRow, c1 = selAnchorCol;
        int r2 = selCursorRow, c2 = selCursorCol;
        if (r1 > r2 || (r1 == r2 && c1 > c2)) {
            int tr = r1; r1 = r2; r2 = tr;
            int tc = c1; c1 = c2; c2 = tc;
        }

        g2.setColor(theme.accent);

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

    private void drawSearchHighlights(Graphics2D g2, String[] lines, int charWidth,
            int lineHeight, int scrollOffsetX, int gutterWidth) {
        g2.setColor(SEARCH_HIGHLIGHT_COLOR);
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
            "Java SE 製の軽量テキストエディタ",
            "",
            "version 1.0.0  |  Java " + System.getProperty("java.version"),
            "",
            "─────────────────────────────────────────",
            "",
            "  i        INSERTモードへ（文字入力）",
            "  Esc      NORMALモードへ戻る",
            "  :e <path>  ファイルを開く",
            "  :w <path>  ファイルを保存",
            "  :q         終了",
            "  K        カーソル位置の JDK API を表示",
            "  :tutor または :tutorial  対話型チュートリアルを開く",
            "  Ctrl+W              左右ペイン切り替え",
            "  Ctrl+Shift+↑↓←→  アクティブペインのフォントサイズ変更",
            "",
            "─────────────────────────────────────────",
            "",
            "何かキーを押すと編集を開始します",
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
                boolean isHint = line.contains("キーを押すと");
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
        if (codePoint >= 0x3040 && codePoint <= 0x30FF) return 2; // ひらがな・カタカナ
        if (codePoint >= 0x4E00 && codePoint <= 0x9FFF) return 2; // CJK統合漢字
        if (codePoint >= 0xFF00 && codePoint <= 0xFFEF) return 2; // 全角英数・記号
        return 1;
    }
}
