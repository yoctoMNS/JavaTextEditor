package dev.vimacs.extension;

import dev.vimacs.editor.KeymapRegistry;
import dev.vimacs.editor.ModalEditor;

/**
 * ModalEditor を EditorContext にアダプトする実装。
 * プラグインが ModalEditor の内部に直接依存しないように疎結合を保つ。
 */
public class SimpleEditorContext implements EditorContext {

    private final ModalEditor editor;

    public SimpleEditorContext(ModalEditor editor) {
        this.editor = editor;
    }

    @Override public String getText()      { return editor.getText(); }
    @Override public int length()          { return editor.getText().length(); }
    @Override public int getLineCount()    { return editor.getLineCount(); }
    @Override public String getLine(int row) { return editor.getLine(row); }

    @Override public int getCursorRow()    { return editor.getCursorRow(); }
    @Override public int getCursorCol()    { return editor.getCursorCol(); }
    @Override public int offsetAt(int row, int col) { return editor.offsetAt(row, col); }
    @Override public void setCursor(int row, int col) { editor.setCursor(row, col); }

    @Override public boolean isNormalMode() { return editor.isNormalMode(); }
    @Override public boolean isInsertMode() { return editor.isInsertMode(); }

    @Override
    public void insertAtOffset(int offset, String text) {
        editor.insertAtOffset(offset, text);
    }

    @Override
    public void deleteRange(int startOffset, int endOffset) {
        editor.deleteRange(startOffset, endOffset);
    }

    @Override
    public void setStatusMessage(String message) {
        editor.setStatusMessage(message);
    }

    @Override
    public KeymapRegistry getKeymap() {
        return editor.getKeymap();
    }
}
