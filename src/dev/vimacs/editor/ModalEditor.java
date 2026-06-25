package dev.vimacs.editor;

import dev.vimacs.buffer.PieceTable;
import dev.vimacs.buffer.UndoablePieceTable;
import dev.vimacs.ui.EditorCanvas;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * NORMAL / INSERT / COMMAND の3モードを管理し、カーソル位置管理と
 * PieceTable / EditorCanvas の橋渡しを担うクラス。
 *
 * キー入力の処理は processKey(keyCode, keyChar, modifiers) で受け取る。
 * keyCode / modifiers は java.awt.event.KeyEvent の定数を使用する。
 */
public class ModalEditor {

    private enum Mode { NORMAL, INSERT, COMMAND, VISUAL }

    private UndoablePieceTable buffer;
    private final EditorCanvas canvas; // null の場合はGUIなし（テスト用）
    private Mode mode = Mode.NORMAL;
    private int cursorRow = 0;
    private int cursorCol = 0;
    private int anchorRow = 0;
    private int anchorCol = 0;
    private String yankRegister = "";
    private final StringBuilder commandBuffer = new StringBuilder();
    private String currentFilePath = null;
    private String statusMessage = "";
    private Runnable exitCallback = () -> System.exit(0);

    public ModalEditor(String initialText) {
        this.buffer = new UndoablePieceTable(initialText);
        this.canvas = null;
    }

    public ModalEditor(String initialText, EditorCanvas canvas) {
        this.buffer = new UndoablePieceTable(initialText);
        this.canvas = canvas;
        syncCanvas();
    }

    public ModalEditor(String initialText, String filePath, EditorCanvas canvas) {
        this.buffer = new UndoablePieceTable(initialText);
        this.currentFilePath = filePath;
        this.canvas = canvas;
        syncCanvas();
    }

    public void setExitCallback(Runnable callback) {
        this.exitCallback = callback;
    }

    public void processKey(int keyCode, char keyChar, int modifiers) {
        boolean ctrl = (modifiers & KeyEvent.CTRL_DOWN_MASK) != 0;
        if (mode == Mode.NORMAL && ctrl && keyCode == KeyEvent.VK_R) {
            buffer.redo();
            clampCursorAfterUndoRedo();
            syncCanvas();
            return;
        }
        if (mode == Mode.VISUAL && keyCode == KeyEvent.VK_ESCAPE) {
            mode = Mode.NORMAL;
            syncCanvas();
            return;
        }
        switch (mode) {
            case INSERT  -> processInsertKey(keyCode, keyChar, modifiers);
            case COMMAND -> processCommandKey(keyCode, keyChar);
            case NORMAL  -> processNormalKey(keyChar);
            case VISUAL  -> processVisualKey(keyChar);
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
            case 'i' -> {
                mode = Mode.INSERT;
                statusMessage = "";
            }
            case 'a' -> {
                String[] lines = getLines();
                int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
                cursorCol = Math.min(cursorCol + 1, lineLen);
                mode = Mode.INSERT;
                statusMessage = "";
            }
            case 'o' -> {
                String[] lines = getLines();
                int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
                int endOfLine = offsetAt(cursorRow, lineLen);
                buffer.insert(endOfLine, "\n");
                cursorRow++;
                cursorCol = 0;
                mode = Mode.INSERT;
                statusMessage = "";
            }
            case ':' -> {
                commandBuffer.setLength(0);
                statusMessage = "";
                mode = Mode.COMMAND;
            }
            case 'u' -> {
                buffer.undo();
                clampCursorAfterUndoRedo();
            }
            case 'v' -> {
                anchorRow = cursorRow;
                anchorCol = cursorCol;
                mode = Mode.VISUAL;
            }
            case 'x' -> {
                deleteCharAtCursor();
            }
            case 'p' -> {
                pasteAfter();
            }
            case 'P' -> {
                pasteBefore();
            }
        }
    }

    // -------------------------------------------------------------------------
    // INSERTモード処理
    // -------------------------------------------------------------------------

    private void processInsertKey(int keyCode, char keyChar, int modifiers) {
        boolean ctrl = (modifiers & KeyEvent.CTRL_DOWN_MASK) != 0;

        if (keyCode == KeyEvent.VK_ESCAPE) {
            mode = Mode.NORMAL;
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
            buffer.insert(offsetOfCursor(), String.valueOf(keyChar));
            cursorCol++;
        }
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
            mode = Mode.NORMAL;

        } else if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ') {
            commandBuffer.append(keyChar);
        }
    }

    private void executeCommand(String cmd) {
        if (cmd.equals("w")) {
            saveToFile(currentFilePath);
        } else if (cmd.startsWith("w ")) {
            String path = cmd.substring(2).trim();
            if (saveToFile(path)) {
                currentFilePath = path;
            }
        } else if (cmd.startsWith("e ")) {
            String path = cmd.substring(2).trim();
            loadFromFile(path);
        } else if (cmd.equals("q")) {
            exitCallback.run();
        } else if (cmd.equals("wq")) {
            if (saveToFile(currentFilePath)) {
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
            Files.writeString(Path.of(path), buffer.getText());
            statusMessage = "\"" + path + "\" written";
            return true;
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
            return false;
        }
    }

    private void loadFromFile(String path) {
        try {
            String content = Files.readString(Path.of(path)).replace("\r\n", "\n");
            buffer = new UndoablePieceTable(content);
            currentFilePath = path;
            cursorRow = 0;
            cursorCol = 0;
            statusMessage = "\"" + path + "\" opened";
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // VISUALモード処理
    // -------------------------------------------------------------------------

    private void processVisualKey(char keyChar) {
        switch (keyChar) {
            case 'h' -> moveCursor(0, -1);
            case 'l' -> moveCursor(0, 1);
            case 'j' -> moveCursor(1, 0);
            case 'k' -> moveCursor(-1, 0);
            case 'y' -> {
                yankRegister = getSelectedText();
                mode = Mode.NORMAL;
            }
            case 'd' -> {
                yankRegister = getSelectedText();
                deleteSelected();
                mode = Mode.NORMAL;
                clampCursorForNormal();
            }
        }
    }

    // -------------------------------------------------------------------------
    // カーソル移動
    // -------------------------------------------------------------------------

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
    // VISUALモード用ヘルパーメソッド
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

    private void deleteCharAtCursor() {
        String[] lines = getLines();
        int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
        if (lineLen == 0) return;
        buffer.delete(offsetOfCursor(), 1);
        clampCursorForNormal();
    }

    private void pasteAfter() {
        if (yankRegister.isEmpty()) return;
        int offset = Math.min(offsetOfCursor() + 1, buffer.length());
        buffer.insert(offset, yankRegister);
        int newOffset = offset + yankRegister.length() - 1;
        moveCursorToOffset(newOffset);
        clampCursorForNormal();
    }

    private void pasteBefore() {
        if (yankRegister.isEmpty()) return;
        int currentOffset = offsetOfCursor();
        buffer.insert(currentOffset, yankRegister);
        int newOffset = currentOffset + yankRegister.length() - 1;
        moveCursorToOffset(newOffset);
        clampCursorForNormal();
    }

    // -------------------------------------------------------------------------
    // GUI同期
    // -------------------------------------------------------------------------

    private void syncCanvas() {
        if (canvas != null) {
            canvas.setText(buffer.getText());
            canvas.setCursor(cursorRow, cursorCol);
            canvas.setInsertMode(mode == Mode.INSERT);
            canvas.setVisualMode(mode == Mode.VISUAL);
            if (mode == Mode.VISUAL) {
                canvas.setSelection(anchorRow, anchorCol, cursorRow, cursorCol);
            } else {
                canvas.clearSelection();
            }
            canvas.ensureCursorVisible(cursorRow);
            if (mode == Mode.COMMAND) {
                canvas.setCommandLineText(":" + commandBuffer.toString());
            } else if (!statusMessage.isEmpty()) {
                canvas.setCommandLineText(statusMessage);
            } else {
                canvas.setCommandLineText(null);
            }
        }
    }

    // -------------------------------------------------------------------------
    // パブリックアクセサ（テスト・外部連携用）
    // -------------------------------------------------------------------------

    public String getText()          { return buffer.getText(); }
    public int getCursorRow()        { return cursorRow; }
    public int getCursorCol()        { return cursorCol; }
    public boolean isInsertMode()    { return mode == Mode.INSERT; }
    public boolean isCommandMode()   { return mode == Mode.COMMAND; }
    public boolean isVisualMode()    { return mode == Mode.VISUAL; }
    public String getStatusMessage() { return statusMessage; }
    public String getCommandBuffer() { return commandBuffer.toString(); }
    public String getYankRegister()  { return yankRegister; }
}
