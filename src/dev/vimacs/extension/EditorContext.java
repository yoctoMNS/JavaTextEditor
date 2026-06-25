package dev.vimacs.extension;

/**
 * プラグインがエディタを操作するための窓口。
 * ModalEditor の内部実装から切り離し、プラグインを疎結合に保つ。
 */
public interface EditorContext {
    String getText();
    int getCursorRow();
    int getCursorCol();

    /** offset は PieceTable の文字オフセット（行頭から数えた絶対位置）。 */
    void insertAtOffset(int offset, String text);
    void deleteRange(int startOffset, int endOffset);
    void setStatusMessage(String message);

    /** 現在のバッファの総文字数。 */
    int length();
}
