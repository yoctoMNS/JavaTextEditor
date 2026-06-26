package dev.vimacs.editor;

import dev.vimacs.buffer.PieceTable;
import dev.vimacs.buffer.UndoablePieceTable;
import dev.vimacs.ui.EditorCanvas;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * NORMAL / INSERT / COMMAND / VISUAL / VISUAL_LINE の5モードを管理し、
 * カーソル位置管理と PieceTable / EditorCanvas の橋渡しを担うクラス。
 *
 * キー入力の処理は processKey(keyCode, keyChar, modifiers) で受け取る。
 * keyCode / modifiers は java.awt.event.KeyEvent の定数を使用する。
 */
public class ModalEditor {

    private enum Mode { NORMAL, INSERT, COMMAND, VISUAL, VISUAL_LINE }

    private UndoablePieceTable buffer;
    private final EditorCanvas canvas; // null の場合はGUIなし（テスト用）
    private final KeymapRegistry keymap = new KeymapRegistry();
    private Mode mode = Mode.NORMAL;
    private int cursorRow = 0;
    private int cursorCol = 0;
    private int anchorRow = 0;
    private int anchorCol = 0;
    private String yankRegister = "";
    private String yankType = "char"; // "char" or "line"
    private char pendingNormalChar = 0; // yy / dd の2打鍵シーケンス管理
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
        if ((mode == Mode.VISUAL || mode == Mode.VISUAL_LINE) && keyCode == KeyEvent.VK_ESCAPE) {
            mode = Mode.NORMAL;
            pendingNormalChar = 0;
            syncCanvas();
            return;
        }
        switch (mode) {
            case INSERT      -> processInsertKey(keyCode, keyChar, modifiers);
            case COMMAND     -> processCommandKey(keyCode, keyChar);
            case NORMAL      -> processNormalKey(keyCode, keyChar, modifiers);
            case VISUAL      -> processVisualKey(keyCode, keyChar, modifiers);
            case VISUAL_LINE -> processVisualLineKey(keyCode, keyChar, modifiers);
        }
        syncCanvas();
    }

    // -------------------------------------------------------------------------
    // NORMALモード処理
    // -------------------------------------------------------------------------

    private void processNormalKey(int keyCode, char keyChar, int modifiers) {
        // 2打鍵シーケンス（yy / dd）の処理
        if (pendingNormalChar != 0) {
            char prev = pendingNormalChar;
            pendingNormalChar = 0;
            if (prev == 'y' && keyChar == 'y') { yankCurrentLine(); return; }
            if (prev == 'd' && keyChar == 'd') { deleteCurrentLine(); return; }
            // シーケンスが成立しなかった場合は落下してキーを通常処理
        }

        String action = keymap.resolve(KeymapRegistry.Mode.NORMAL, keyCode, keyChar, modifiers);
        if (action == null) return;

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
            case "delete.char" -> deleteCharAtCursor();
            case "paste.after" -> pasteAfter();
            case "paste.before" -> pasteBefore();
            case "yank.pending" -> pendingNormalChar = 'y';
            case "delete.pending" -> pendingNormalChar = 'd';
        }
    }

    // -------------------------------------------------------------------------
    // INSERTモード処理
    // -------------------------------------------------------------------------

    private void processInsertKey(int keyCode, char keyChar, int modifiers) {
        String action = keymap.resolve(KeymapRegistry.Mode.INSERT, keyCode, keyChar, modifiers);

        if (action != null) {
            switch (action) {
                case "enter.normal" -> {
                    mode = Mode.NORMAL;
                    clampCursorForNormal();
                }
                case "delete.before" -> handleBackspace();
                case "insert.newline" -> {
                    buffer.insert(offsetOfCursor(), "\n");
                    cursorRow++;
                    cursorCol = 0;
                }
                case "cursor.right" -> moveCursor(0, 1);
                case "cursor.left"  -> moveCursor(0, -1);
                case "cursor.down"  -> moveCursor(1, 0);
                case "cursor.up"    -> moveCursor(-1, 0);
            }
        } else if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ') {
            // 通常文字の挿入（キーバインドに登録されていない文字）
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
    // VISUALモード処理（文字単位）
    // -------------------------------------------------------------------------

    private void processVisualKey(int keyCode, char keyChar, int modifiers) {
        String action = keymap.resolve(KeymapRegistry.Mode.VISUAL, keyCode, keyChar, modifiers);
        if (action == null) return;

        switch (action) {
            case "cursor.left"  -> moveCursor(0, -1);
            case "cursor.right" -> moveCursor(0, 1);
            case "cursor.down"  -> moveCursor(1, 0);
            case "cursor.up"    -> moveCursor(-1, 0);
            case "yank" -> {
                yankRegister = getSelectedText();
                yankType = "char";
                mode = Mode.NORMAL;
            }
            case "delete" -> {
                yankRegister = getSelectedText();
                yankType = "char";
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

        switch (action) {
            case "cursor.left"  -> moveCursor(0, -1);
            case "cursor.right" -> moveCursor(0, 1);
            case "cursor.down"  -> moveCursor(1, 0);
            case "cursor.up"    -> moveCursor(-1, 0);
            case "yank" -> {
                int r1 = Math.min(anchorRow, cursorRow);
                int r2 = Math.max(anchorRow, cursorRow);
                yankRegister = buildLineRangeText(r1, r2);
                yankType = "line";
                cursorRow = r1;
                cursorCol = 0;
                mode = Mode.NORMAL;
            }
            case "delete" -> {
                int r1 = Math.min(anchorRow, cursorRow);
                int r2 = Math.max(anchorRow, cursorRow);
                yankRegister = buildLineRangeText(r1, r2);
                yankType = "line";
                deleteLineRange(r1, r2);
                mode = Mode.NORMAL;
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
        yankType = "line";
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
        if ("line".equals(yankType)) {
            pasteLineAfter();
        } else {
            pasteCharAfter();
        }
    }

    private void pasteBefore() {
        if (yankRegister.isEmpty()) return;
        if ("line".equals(yankType)) {
            pasteLineBefore();
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

    private void syncCanvas() {
        if (canvas != null) {
            canvas.setText(buffer.getText());
            canvas.setCursor(cursorRow, cursorCol);
            canvas.setInsertMode(mode == Mode.INSERT);

            boolean isVisual     = (mode == Mode.VISUAL);
            boolean isVisualLine = (mode == Mode.VISUAL_LINE);
            canvas.setVisualMode(isVisual || isVisualLine);
            canvas.setVisualLineMode(isVisualLine);

            if (isVisual) {
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

    public String getText()            { return buffer.getText(); }
    public int getCursorRow()          { return cursorRow; }
    public int getCursorCol()          { return cursorCol; }
    public boolean isInsertMode()      { return mode == Mode.INSERT; }
    public boolean isCommandMode()     { return mode == Mode.COMMAND; }
    public boolean isVisualMode()      { return mode == Mode.VISUAL; }
    public boolean isVisualLineMode()  { return mode == Mode.VISUAL_LINE; }
    public String getStatusMessage()   { return statusMessage; }
    public String getCommandBuffer()   { return commandBuffer.toString(); }
    public String getYankRegister()    { return yankRegister; }
    public String getYankType()        { return yankType; }

    // プラグイン向けバッファ操作
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
}
