package dev.vimacs.ui;

import dev.vimacs.analysis.CompileDiagnostic;
import dev.vimacs.analysis.DiagnosticKind;
import javax.swing.JPanel;
import java.awt.*;
import java.util.List;
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

    // 診断情報（エラー・警告）。空リストのときはガターを描画しない。
    private List<CompileDiagnostic> diagnostics = List.of();
    // 行番号 → 最も優先度の高い診断種別（ERROR > WARNING）
    private Map<Integer, DiagnosticKind> diagByLine = Map.of();

    private static final Font FONT = new Font(Font.MONOSPACED, Font.PLAIN, 16);
    private static final Color ERROR_COLOR   = new Color(0xCC, 0x33, 0x33);
    private static final Color WARNING_COLOR = new Color(0xCC, 0x99, 0x00);

    public void setText(String text) { this.text = text; repaint(); }
    public void setCursor(int row, int col) { this.cursorRow = row; this.cursorCol = col; repaint(); }
    public void setInsertMode(boolean insertMode) { this.insertMode = insertMode; repaint(); }
    public void setTheme(Theme theme) { this.theme = theme; repaint(); }
    public void setScrollRow(int scrollRow) { this.scrollRow = Math.max(0, scrollRow); repaint(); }
    public int getScrollRow() { return scrollRow; }
    public void setScrollCol(int col) { this.scrollCol = Math.max(0, col); repaint(); }
    public int getScrollCol() { return scrollCol; }
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
        g2.setFont(FONT);
        FontMetrics fm = g2.getFontMetrics();
        int charWidth = fm.charWidth('M');
        int lineHeight = fm.getHeight();
        cachedLineHeight = lineHeight;
        cachedCharWidth = charWidth;

        // ガター幅: 診断がある場合のみ "E " / "W " / "  " 2文字分を確保
        int gutterWidth = diagnostics.isEmpty() ? 0 : 2 * charWidth;
        int scrollOffsetX = scrollCol * charWidth;

        // 1. 背景を塗る
        g2.setColor(theme.background);
        g2.fillRect(0, 0, getWidth(), getHeight());

        // 1.5 選択ハイライト（VISUALモード時）
        if (visualMode && selAnchorRow >= 0) {
            drawSelectionHighlight(g2, text.split("\n", -1),
                charWidth, lineHeight, scrollOffsetX, gutterWidth);
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
    }

    /**
     * 全角文字を考慮しながら1行を描画する。
     * gutterWidth ピクセルをオフセットとして加算し、scrollOffsetX 分左にシフトして描画する。
     */
    private void drawLineWithFullWidthSupport(Graphics2D g2, String line, int y,
            int charWidth, int scrollOffsetX, int gutterWidth) {
        int x = gutterWidth - scrollOffsetX;
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            int cellWidth = charCellWidth(codePoint);
            int charPixelWidth = charWidth * cellWidth;
            if (x + charPixelWidth > 0 && x < getWidth()) {
                g2.drawString(new String(Character.toChars(codePoint)), x, y);
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
                g2.setColor(theme.background);
                g2.drawString(new String(Character.toChars(codePoint)), x, (screenRow + 1) * lineHeight);
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
