package dev.vimacs.ui;

import javax.swing.JPanel;
import java.awt.*;

public class EditorCanvas extends JPanel {

    private String text = "";
    private int cursorRow = 0;
    private int cursorCol = 0;
    private boolean insertMode = false;
    private Theme theme = Theme.LIGHT_MODE;

    private static final Font FONT = new Font(Font.MONOSPACED, Font.PLAIN, 16);

    public void setText(String text) { this.text = text; repaint(); }
    public void setCursor(int row, int col) { this.cursorRow = row; this.cursorCol = col; repaint(); }
    public void setInsertMode(boolean insertMode) { this.insertMode = insertMode; repaint(); }
    public void setTheme(Theme theme) { this.theme = theme; repaint(); }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setFont(FONT);
        FontMetrics fm = g2.getFontMetrics();
        int charWidth = fm.charWidth('M');
        int lineHeight = fm.getHeight();

        // 1. 背景を塗る
        g2.setColor(theme.background);
        g2.fillRect(0, 0, getWidth(), getHeight());

        // 2. テキストを行ごとに描画する
        // コンポーネント範囲外への描画はSwingが自動的に無視するため、
        // ウィンドウからあふれた行・文字の個別チェックは不要。
        g2.setColor(theme.foreground);
        String[] lines = text.split("\n", -1);
        for (int row = 0; row < lines.length; row++) {
            int y = (row + 1) * lineHeight;
            drawLineWithFullWidthSupport(g2, lines[row], 0, y, charWidth);
        }

        // 3. カーソルを描画する
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
        // 簡易版: 全角文字を含む行では誤差が出る。正確な実装はfuture-phases.mdを参照。
        int x = cursorCol * charWidth;
        int yTop = cursorRow * lineHeight;

        if (insertMode) {
            // INSERTモード: 縦棒カーソル（2px幅）
            g2.setColor(theme.foreground);
            g2.fillRect(x, yTop, 2, lineHeight);
        } else {
            // NORMALモード: ブロックカーソル（色を反転させて文字を浮き出す）
            g2.setColor(theme.foreground);
            g2.fillRect(x, yTop, charWidth, lineHeight);
            if (cursorRow < lines.length) {
                String line = lines[cursorRow];
                if (cursorCol < line.length()) {
                    // サロゲートペア対応のため charAt ではなく codePointAt を使う
                    int codePoint = line.codePointAt(cursorCol);
                    g2.setColor(theme.background);
                    g2.drawString(new String(Character.toChars(codePoint)), x, (cursorRow + 1) * lineHeight);
                }
            }
        }
    }

    private void drawStatusLine(Graphics2D g2, int lineHeight) {
        int y = getHeight() - 4;
        g2.setColor(theme.accent);
        g2.fillRect(0, y - lineHeight, getWidth(), lineHeight);
        g2.setColor(theme.background);
        String modeLabel = insertMode ? "-- INSERT --" : "-- NORMAL --";
        g2.drawString(modeLabel, 4, y - 4);
    }

    /**
     * 1文字（コードポイント）が全角（2セル分）か半角（1セル分）かを判定する。
     * 厳密なUnicode East Asian Width判定は複雑だが、Javaプログラミング用途では
     * CJK・ひらがな・カタカナの範囲を押さえれば実用上十分。
     */
    static int charCellWidth(int codePoint) {
        if (codePoint >= 0x3040 && codePoint <= 0x30FF) return 2; // ひらがな・カタカナ
        if (codePoint >= 0x4E00 && codePoint <= 0x9FFF) return 2; // CJK統合漢字
        if (codePoint >= 0xFF00 && codePoint <= 0xFFEF) return 2; // 全角英数・記号
        return 1;
    }
}
