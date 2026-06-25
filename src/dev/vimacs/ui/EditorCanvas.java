package dev.vimacs.ui;

import javax.swing.JPanel;
import java.awt.*;

public class EditorCanvas extends JPanel {

    private String text = "";
    private int cursorRow = 0;
    private int cursorCol = 0;
    private boolean insertMode = false;
    private boolean visualMode = false;
    private Theme theme = Theme.LIGHT_MODE;
    private int scrollRow = 0;
    private int cachedLineHeight = 20; // 初回 paint 前の近似値
    private String commandLineText = null; // null = 通常のモード表示
    private int selAnchorRow = -1;
    private int selAnchorCol = -1;
    private int selCursorRow = -1;
    private int selCursorCol = -1;

    private static final Font FONT = new Font(Font.MONOSPACED, Font.PLAIN, 16);

    public void setText(String text) { this.text = text; repaint(); }
    public void setCursor(int row, int col) { this.cursorRow = row; this.cursorCol = col; repaint(); }
    public void setInsertMode(boolean insertMode) { this.insertMode = insertMode; repaint(); }
    public void setTheme(Theme theme) { this.theme = theme; repaint(); }
    public void setScrollRow(int scrollRow) { this.scrollRow = Math.max(0, scrollRow); repaint(); }
    public int getScrollRow() { return scrollRow; }
    public void setCommandLineText(String text) { this.commandLineText = text; repaint(); }
    public void setVisualMode(boolean visualMode) { this.visualMode = visualMode; repaint(); }
    public void setSelection(int anchorRow, int anchorCol, int cursorRow, int cursorCol) {
        this.selAnchorRow = anchorRow;
        this.selAnchorCol = anchorCol;
        this.selCursorRow = cursorRow;
        this.selCursorCol = cursorCol;
        repaint();
    }
    public void clearSelection() {
        this.selAnchorRow = -1;
        repaint();
    }

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

        // 1. 背景を塗る
        g2.setColor(theme.background);
        g2.fillRect(0, 0, getWidth(), getHeight());

        // 1.5 選択ハイライト（VISUALモード時）
        if (visualMode && selAnchorRow >= 0) {
            drawSelectionHighlight(g2, text.split("\n", -1), charWidth, lineHeight);
        }

        // 2. 表示行範囲（scrollRow 〜 scrollRow+visibleRows）のみ描画する
        g2.setColor(theme.foreground);
        String[] lines = text.split("\n", -1);
        int visibleRows = computeVisibleRows(lineHeight);
        int lastRow = Math.min(lines.length, scrollRow + visibleRows);
        for (int row = scrollRow; row < lastRow; row++) {
            int screenRow = row - scrollRow;
            int y = (screenRow + 1) * lineHeight;
            drawLineWithFullWidthSupport(g2, lines[row], 0, y, charWidth);
        }

        // 3. カーソルを描画する（スクロールオフセット考慮）
        drawCursor(g2, lines, charWidth, lineHeight);

        // 4. ステータス行を描画する（画面最下部）
        drawStatusLine(g2, lineHeight);
    }

    /** 全角文字を考慮しながら1行を描画する */
    private void drawLineWithFullWidthSupport(Graphics2D g2, String line, int xStart, int y, int charWidth) {
        int x = xStart;
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            int cellWidth = charCellWidth(codePoint);
            g2.drawString(new String(Character.toChars(codePoint)), x, y);
            x += charWidth * cellWidth;
            i += Character.charCount(codePoint);
        }
    }

    private void drawCursor(Graphics2D g2, String[] lines, int charWidth, int lineHeight) {
        int screenRow = cursorRow - scrollRow;
        if (screenRow < 0 || screenRow >= computeVisibleRows(lineHeight)) return; // 非表示範囲

        String line = (cursorRow < lines.length) ? lines[cursorRow] : "";
        int x = xForCol(line, cursorCol, charWidth);
        int yTop = screenRow * lineHeight;

        if (insertMode) {
            // INSERTモード: 縦棒カーソル（2px幅）
            g2.setColor(theme.foreground);
            g2.fillRect(x, yTop, 2, lineHeight);
        } else {
            // NORMALモード: ブロックカーソル（全角文字は2セル分の幅で描く）
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
                                         int charWidth, int lineHeight) {
        int r1 = selAnchorRow, c1 = selAnchorCol;
        int r2 = selCursorRow, c2 = selCursorCol;
        if (r1 > r2 || (r1 == r2 && c1 > c2)) {
            int tr = r1; r1 = r2; r2 = tr;
            int tc = c1; c1 = c2; c2 = tc;
        }

        g2.setColor(theme.accent);

        for (int row = Math.max(r1, scrollRow);
             row <= Math.min(r2, scrollRow + computeVisibleRows(lineHeight) - 1);
             row++) {
            int screenRow = row - scrollRow;
            int yTop = screenRow * lineHeight;
            String line = (row < lines.length) ? lines[row] : "";

            int colStart = (row == r1) ? c1 : 0;
            int colEnd = (row == r2) ? c2 : Math.max(0, line.length() - 1);

            int xStart = xForCol(line, colStart, charWidth);
            int xEnd = xForCol(line, Math.min(colEnd + 1, line.length()), charWidth);
            if (xEnd <= xStart) xEnd = xStart + charWidth;

            g2.fillRect(xStart, yTop, xEnd - xStart, lineHeight);
        }
    }

    private void drawStatusLine(Graphics2D g2, int lineHeight) {
        int y = getHeight() - 4;
        g2.setColor(theme.accent);
        g2.fillRect(0, y - lineHeight, getWidth(), lineHeight);
        g2.setColor(theme.background);
        String label = (commandLineText != null) ? commandLineText
                     : visualMode ? "-- VISUAL --"
                     : insertMode ? "-- INSERT --"
                     : "-- NORMAL --";
        g2.drawString(label, 4, y - 4);
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
