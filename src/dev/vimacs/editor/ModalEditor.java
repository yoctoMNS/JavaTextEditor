package dev.vimacs.editor;

import dev.vimacs.buffer.PieceTable;
import dev.vimacs.ui.EditorCanvas;
import java.awt.event.KeyEvent;

/**
 * NORMALモードとINSERTモードの状態管理、カーソル位置管理、
 * PieceTableとEditorCanvasの橋渡しを担うクラス。
 *
 * キー入力の処理は processKey(keyCode, keyChar, modifiers) で受け取る。
 * keyCode / modifiers は java.awt.event.KeyEvent の定数を使用する。
 */
public class ModalEditor {

    private final PieceTable buffer;
    private EditorCanvas canvas; // null の場合はGUIなし（テスト用）
    private boolean insertMode = false;
    private int cursorRow = 0;
    private int cursorCol = 0;

    public ModalEditor(String initialText) {
        this.buffer = new PieceTable(initialText);
    }

    public ModalEditor(String initialText, EditorCanvas canvas) {
        this.buffer = new PieceTable(initialText);
        this.canvas = canvas;
        syncCanvas();
    }

    /**
     * キー入力を処理する。
     *
     * @param keyCode   KeyEvent.VK_* 定数
     * @param keyChar   入力文字（なければ KeyEvent.CHAR_UNDEFINED）
     * @param modifiers KeyEvent.CTRL_DOWN_MASK 等のビットマスク
     */
    public void processKey(int keyCode, char keyChar, int modifiers) {
        if (insertMode) {
            processInsertKey(keyCode, keyChar, modifiers);
        } else {
            processNormalKey(keyChar);
        }
        syncCanvas();
    }

    // -------------------------------------------------------------------------
    // NORMALモード処理
    // -------------------------------------------------------------------------

    private void processNormalKey(char keyChar) {
        switch (keyChar) {
            case 'h' -> moveCursor(0, -1);
            case 'l' -> moveCursor(0, 1);
            case 'j' -> moveCursor(1, 0);
            case 'k' -> moveCursor(-1, 0);
            case 'i' -> insertMode = true;
            case 'a' -> {
                // カーソル直後（1つ右）に挿入位置を合わせてINSERTへ
                String[] lines = getLines();
                int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
                cursorCol = Math.min(cursorCol + 1, lineLen);
                insertMode = true;
            }
            case 'o' -> {
                // 現在行の末尾に改行を挿入し、次行冒頭でINSERTへ
                String[] lines = getLines();
                int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
                int endOfLine = offsetAt(cursorRow, lineLen);
                buffer.insert(endOfLine, "\n");
                cursorRow++;
                cursorCol = 0;
                insertMode = true;
            }
            // v1ではその他のキーはNORMALモードで無視する
        }
    }

    // -------------------------------------------------------------------------
    // INSERTモード処理
    // -------------------------------------------------------------------------

    private void processInsertKey(int keyCode, char keyChar, int modifiers) {
        boolean ctrl = (modifiers & KeyEvent.CTRL_DOWN_MASK) != 0;

        if (keyCode == KeyEvent.VK_ESCAPE) {
            insertMode = false;
            // NORMALモードではカーソルが行末より1つ手前が最大
            clampCursorForNormal();

        } else if (keyCode == KeyEvent.VK_BACK_SPACE) {
            handleBackspace();

        } else if (keyCode == KeyEvent.VK_ENTER) {
            buffer.insert(offsetOfCursor(), "\n");
            cursorRow++;
            cursorCol = 0;

        } else if (ctrl && keyCode == KeyEvent.VK_F) {
            moveCursor(0, 1);
        } else if (ctrl && keyCode == KeyEvent.VK_B) {
            moveCursor(0, -1);
        } else if (ctrl && keyCode == KeyEvent.VK_N) {
            moveCursor(1, 0);
        } else if (ctrl && keyCode == KeyEvent.VK_P) {
            moveCursor(-1, 0);

        } else if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ') {
            // 通常の印字可能文字
            buffer.insert(offsetOfCursor(), String.valueOf(keyChar));
            cursorCol++;
        }
    }

    private void handleBackspace() {
        if (cursorCol > 0) {
            // 現在行の直前の文字を削除
            buffer.delete(offsetOfCursor() - 1, 1);
            cursorCol--;
        } else if (cursorRow > 0) {
            // 行頭でのBackspace: 前行末の改行を削除して行を結合
            String[] linesBefore = getLines();
            int prevLineLen = linesBefore[cursorRow - 1].length();
            buffer.delete(offsetOfCursor() - 1, 1);
            cursorRow--;
            cursorCol = prevLineLen;
        }
    }

    // -------------------------------------------------------------------------
    // カーソル移動
    // -------------------------------------------------------------------------

    private void moveCursor(int dRow, int dCol) {
        String[] lines = getLines();
        if (dRow != 0) {
            int newRow = Math.max(0, Math.min(cursorRow + dRow, lines.length - 1));
            int newLineLen = newRow < lines.length ? lines[newRow].length() : 0;
            int maxCol = insertMode ? newLineLen : Math.max(0, newLineLen - 1);
            cursorRow = newRow;
            cursorCol = Math.min(cursorCol, maxCol);
        } else {
            int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
            int maxCol = insertMode ? lineLen : Math.max(0, lineLen - 1);
            cursorCol = Math.max(0, Math.min(cursorCol + dCol, maxCol));
        }
    }

    /** NORMALモード復帰時にカーソル列を最大 lineLen-1 に収める */
    private void clampCursorForNormal() {
        String[] lines = getLines();
        int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
        if (lineLen > 0) {
            cursorCol = Math.min(cursorCol, lineLen - 1);
        } else {
            cursorCol = 0;
        }
    }

    // -------------------------------------------------------------------------
    // オフセット計算
    // -------------------------------------------------------------------------

    /** バッファ先頭から (row, col) までのオフセットを返す */
    private int offsetAt(int row, int col) {
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
    // GUI同期
    // -------------------------------------------------------------------------

    private void syncCanvas() {
        if (canvas != null) {
            canvas.setText(buffer.getText());
            canvas.setCursor(cursorRow, cursorCol);
            canvas.setInsertMode(insertMode);
        }
    }

    // -------------------------------------------------------------------------
    // パブリックアクセサ（テスト・外部連携用）
    // -------------------------------------------------------------------------

    public String getText()       { return buffer.getText(); }
    public int getCursorRow()     { return cursorRow; }
    public int getCursorCol()     { return cursorCol; }
    public boolean isInsertMode() { return insertMode; }
}
