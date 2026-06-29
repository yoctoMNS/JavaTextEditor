package dev.javatexteditor.ui;

import dev.javatexteditor.analysis.CompileDiagnostic;
import dev.javatexteditor.analysis.DiagnosticKind;
import dev.javatexteditor.telescope.TelescopeItem;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.*;
import java.awt.im.InputContext;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

public class EditorCanvas extends JPanel {

    private String text = "";
    private int cursorRow = 0;
    private int cursorCol = 0;
    private boolean insertMode = false;
    private boolean visualMode = false;
    private boolean visualLineMode = false;
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

    // ビットマップフォントのセルサイズ（Ctrl+Shift+矢印で変更可能）
    private int cellW = BitmapFont10x20.BASE_CELL_W;
    private int cellH = BitmapFont10x20.BASE_CELL_H;

    // グリフキャッシュ: codePoint → レンダリング済み BufferedImage（透明背景・fg色）
    // セルサイズまたはテーマが変わったら invalidateGlyphCache() でクリアする
    private final Map<Integer, BufferedImage> glyphCacheFg  = new HashMap<>();
    private final Map<Integer, BufferedImage> glyphCacheBg  = new HashMap<>();

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
    private final Timer animTimer   = new Timer(40, e -> repaint());

    public EditorCanvas() {
        animTimer.start();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        animTimer.start();
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        animTimer.stop();
    }

    /**
     * IMEを半角英数字入力モードに切り替える。
     * INSERTモードからNORMALモードに遷移する際に呼ぶことで、
     * 日本語IMEが有効なままNORMALモードのキーバインドを誤入力するのを防ぐ。
     */
    public void switchToHalfWidth() {
        InputContext ic = getInputContext();
        if (ic != null) {
            try {
                ic.selectInputMethod(Locale.ENGLISH);
            } catch (Exception ignored) {}
        }
    }

    public void setText(String text) { this.text = text; repaint(); }
    public void setCursor(int row, int col) { this.cursorRow = row; this.cursorCol = col; repaint(); }
    public void setInsertMode(boolean insertMode) { this.insertMode = insertMode; repaint(); }
    public void setTheme(Theme theme) { this.theme = theme; invalidateGlyphCache(); repaint(); }
    public void setScrollRow(int scrollRow) { this.scrollRow = Math.max(0, scrollRow); repaint(); }
    public int getScrollRow() { return scrollRow; }
    public void setScrollCol(int col) { this.scrollCol = Math.max(0, col); repaint(); }
    public int getScrollCol() { return scrollCol; }
    public int getVisibleRows() { return computeVisibleRows(cachedLineHeight > 0 ? cachedLineHeight : 16); }
    public void setCommandLineText(String text) { this.commandLineText = text; repaint(); }
    public void setVisualMode(boolean visualMode) { this.visualMode = visualMode; repaint(); }
    public void setVisualLineMode(boolean visualLineMode) { this.visualLineMode = visualLineMode; repaint(); }
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

    private void invalidateGlyphCache() {
        glyphCacheFg.clear();
        glyphCacheBg.clear();
    }

    private BufferedImage getGlyphFg(int cp) {
        return glyphCacheFg.computeIfAbsent(cp,
            k -> BitmapFont10x20.renderGlyph(k, cellW, cellH, theme.foreground.getRGB()));
    }

    private BufferedImage getGlyphBg(int cp) {
        return glyphCacheBg.computeIfAbsent(cp,
            k -> BitmapFont10x20.renderGlyph(k, cellW, cellH, theme.background.getRGB()));
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
        Graphics2D g2 = (Graphics2D) g;

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
        if (visualMode && selAnchorRow >= 0) {
            drawSelectionHighlight(g2, text.split("\n", -1),
                charWidth, lineHeight, scrollOffsetX, gutterWidth);
        }

        // 1.6 検索ハイライト（/pattern、*、# による検索結果）
        if (!searchHighlights.isEmpty()) {
            drawSearchHighlights(g2, text.split("\n", -1), charWidth, lineHeight, scrollOffsetX, gutterWidth);
        }

        // 2. 表示行範囲（scrollRow 〜 scrollRow+visibleRows）のみ描画する
        g2.setColor(theme.foreground);
        String[] lines = text.split("\n", -1);
        int visibleRows = computeVisibleRows(lineHeight);
        int lastRow = Math.min(lines.length, scrollRow + visibleRows);
        for (int row = scrollRow; row < lastRow; row++) {
            int screenRow = row - scrollRow;
            int y = (screenRow + 1) * lineHeight;
            drawLineWithFullWidthSupport(g2, lines[row], y, charWidth, scrollOffsetX, gutterWidth);
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

        Font overlayFont = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(10, lineHeight - 2));
        g2.setFont(overlayFont);
        FontMetrics fm = g2.getFontMetrics(overlayFont);
        int fh = fm.getHeight();
        int pad = 4;

        // プロンプト行
        g2.setColor(theme.accent);
        g2.fillRect(ox, oy, overlayW, fh + pad * 2);
        g2.setColor(theme.background);
        String promptText = telescopeTitle + "  > " + telescopeQuery + "_";
        g2.drawString(promptText, ox + pad, oy + fh + pad);

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

        g2.setColor(theme.foreground);
        for (int i = visStart; i < telescopeResults.size() && (i - visStart) < maxResultRows; i++) {
            TelescopeItem item = telescopeResults.get(i);
            int ry = bodyY + (i - visStart + 1) * fh;
            if (i == telescopeSelectedIdx) {
                g2.setColor(theme.accent);
                g2.fillRect(ox + 1, ry - fh + 2, resultsW - 2, fh);
                g2.setColor(theme.background);
            } else {
                g2.setColor(theme.foreground);
            }
            String label = (i == telescopeSelectedIdx ? "▸ " : "  ") + item.display();
            String clipped = clipToWidth(label, fm, resultsW - pad * 2);
            g2.drawString(clipped, ox + pad, ry);
        }

        // Preview ペイン
        g2.setColor(theme.foreground);
        String[] previewLines = telescopePreview.split("\n", -1);
        int previewW = overlayW - resultsW;
        int py = bodyY + fh;
        for (int i = 0; i < previewLines.length && (py - bodyY) < bodyH; i++) {
            String clipped = clipToWidth(previewLines[i], fm, previewW - pad * 2);
            g2.drawString(clipped, previewX + pad, py);
            py += fh;
        }
    }

    /**
     * カーソル直下に補完候補のドロップダウンを描画する。
     * テキスト本体より前面・telescope より後面に描画される。
     */
    private void drawCompletionPopup(Graphics2D g2, int charWidth, int lineHeight, int gutterWidth) {
        if (completionLabels.isEmpty()) return;

        Font popupFont = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(10, lineHeight - 2));
        g2.setFont(popupFont);
        FontMetrics fm = g2.getFontMetrics(popupFont);
        int fh = fm.getHeight();
        int pad = 4;
        int kindW = fm.stringWidth("mth") + pad; // kind ラベルの幅

        // 最長ラベル幅を計算してポップアップ幅を決定
        int maxLabelW = 0;
        for (String label : completionLabels) {
            maxLabelW = Math.max(maxLabelW, fm.stringWidth(label));
        }
        int popupW = kindW + maxLabelW + pad * 3;
        int popupH = completionLabels.size() * fh + pad * 2;

        // カーソル行の文字列でセルオフセットを計算（全角対応）
        String[] lines = text.split("\n", -1);
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
            int iy = popupY + pad + (i + 1) * fh - fm.getDescent();
            int rowTop = popupY + pad + i * fh;

            if (i == completionSelectedIdx) {
                g2.setColor(theme.accent);
                g2.fillRect(popupX + 1, rowTop, popupW - 2, fh);
                // kind ラベル（選択行）
                g2.setColor(theme.background);
                String kind = (i < completionKinds.size()) ? completionKinds.get(i) : "";
                g2.drawString(kind, popupX + pad, iy);
                g2.drawString(completionLabels.get(i), popupX + pad + kindW, iy);
            } else {
                // kind ラベルをアクセント色で
                g2.setColor(theme.accent);
                String kind = (i < completionKinds.size()) ? completionKinds.get(i) : "";
                g2.drawString(kind, popupX + pad, iy);
                g2.setColor(theme.foreground);
                g2.drawString(completionLabels.get(i), popupX + pad + kindW, iy);
            }
        }
    }

    private static String clipToWidth(String s, FontMetrics fm, int maxWidth) {
        if (fm.stringWidth(s) <= maxWidth) return s;
        while (s.length() > 0 && fm.stringWidth(s + "…") > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    /**
     * 全角文字を考慮しながら1行を描画する。
     * ASCII(0x20-0x7E): BitmapFont10x20 でピクセルレンダリング。
     * それ以外: Swing フォント（g2 に設定済み）で描画。
     * y はベースライン（セル底辺）の Y 座標。
     */
    private void drawLineWithFullWidthSupport(Graphics2D g2, String line, int y,
            int charWidth, int scrollOffsetX, int gutterWidth) {
        int x = gutterWidth - scrollOffsetX;
        int cellTopOffset = cellH; // y - cellTopOffset = cellTopY
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            int widthMult = charCellWidth(codePoint);
            int charPixelWidth = charWidth * widthMult;
            if (x + charPixelWidth > 0 && x < getWidth()) {
                if (BitmapFont10x20.isSupported(codePoint)) {
                    g2.drawImage(getGlyphFg(codePoint), x, y - cellTopOffset, null);
                } else {
                    g2.setColor(theme.foreground);
                    g2.drawString(new String(Character.toChars(codePoint)), x, y);
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

        if (insertMode) {
            g2.setColor(theme.foreground);
            g2.fillRect(x, yTop, 2, lineHeight);
        } else {
            int codePoint = (cursorCol < line.length()) ? line.codePointAt(cursorCol) : -1;
            int blockWidth = charWidth * (codePoint != -1 ? charCellWidth(codePoint) : 1);
            g2.setColor(theme.foreground);
            g2.fillRect(x, yTop, blockWidth, lineHeight);
            if (codePoint != -1) {
                if (BitmapFont10x20.isSupported(codePoint)) {
                    g2.drawImage(getGlyphBg(codePoint), x, yTop, null);
                } else {
                    g2.setColor(theme.background);
                    g2.drawString(new String(Character.toChars(codePoint)), x, (screenRow + 1) * lineHeight);
                }
            }
        }
    }

    /** ガター列に診断マーカー（E / W）を描画する */
    private void drawGutter(Graphics2D g2, int charWidth, int lineHeight, int gutterWidth) {
        int visibleRows = computeVisibleRows(lineHeight);
        int lastRow = Math.min(
            text.split("\n", -1).length, scrollRow + visibleRows);

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

        if (visualLineMode) {
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
        FontMetrics fm = g2.getFontMetrics();
        int statusH = lineHeight;
        int areaH   = getHeight() - statusH;  // ステータス行を除いた描画高さ
        int areaW   = getWidth();

        String[] lines = {
            "Java Text Editor",
            "",
            "モーダル編集（Vim 式）× 高い拡張性（Emacs 式）",
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
                // キーバインド行：キー部分をアクセントカラーで、説明部分を前景色で
                int tabIdx = line.indexOf("  ", 2);
                if (tabIdx > 0) {
                    String key  = line.substring(0, tabIdx).stripTrailing();
                    String desc = line.substring(tabIdx);
                    int keyW = fm.stringWidth(key);
                    int xKey = (areaW / 2) - 140;
                    g2.setColor(theme.accent);
                    g2.drawString(key, xKey, y);
                    g2.setColor(theme.foreground);
                    g2.drawString(desc, xKey + keyW, y);
                } else {
                    g2.setColor(theme.foreground);
                    g2.drawString(line, (areaW - fm.stringWidth(line)) / 2, y);
                }
            } else if (!line.isBlank()) {
                // サブタイトル・説明文：センタリング・前景色
                boolean isHint = line.contains("キーを押すと");
                g2.setColor(isHint ? theme.accent : theme.foreground);
                if (isHint) g2.setFont(SPLASH_FONT.deriveFont(Font.ITALIC));
                int w = fm.stringWidth(line);
                g2.drawString(line, (areaW - w) / 2, y);
                if (isHint) g2.setFont(normalFont);
            }
        }
    }

    private void drawStatusLine(Graphics2D g2, int lineHeight) {
        int y = getHeight() - 4;
        g2.setColor(theme.accent);
        g2.fillRect(0, y - lineHeight, getWidth(), lineHeight);
        g2.setColor(theme.background);
        String label = (commandLineText != null) ? commandLineText
                     : visualLineMode ? "-- VISUAL LINE --"
                     : visualMode     ? "-- VISUAL --"
                     : insertMode     ? "-- INSERT --"
                     :                  "-- NORMAL --";
        g2.drawString(label, 4, y - 4);

        // 右端に診断件数を表示
        if (!diagnostics.isEmpty()) {
            long errCount  = diagnostics.stream()
                .filter(d -> d.kind() == DiagnosticKind.ERROR).count();
            long warnCount = diagnostics.stream()
                .filter(d -> d.kind() == DiagnosticKind.WARNING).count();
            String diagLabel = buildDiagLabel(errCount, warnCount);
            FontMetrics fm = g2.getFontMetrics();
            int labelWidth = fm.stringWidth(diagLabel);
            g2.drawString(diagLabel, getWidth() - labelWidth - 4, y - 4);
        }

        // ウォーキングパーソンアニメーション（左→右へ走り抜ける）
        drawWalkingPerson(g2, y - lineHeight + 1, lineHeight);
    }

    private void drawWalkingPerson(Graphics2D g2, int statusTopY, int lineHeight) {
        double elapsed = (System.currentTimeMillis() - animStartMs) / 1000.0;
        int scale  = Math.max(1, lineHeight / WalkingPersonSprite.PERSON_H);
        int frame  = WalkingPersonSprite.calcFrame(elapsed);
        int x      = WalkingPersonSprite.calcX(elapsed, getWidth(), scale);
        // スプライトをステータスライン内に縦中央揃えする
        int spriteH = WalkingPersonSprite.PERSON_H * scale;
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
