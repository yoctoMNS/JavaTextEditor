package dev.javatexteditor.extension;

import dev.javatexteditor.editor.KeymapRegistry;

/**
 * プラグインがエディタを操作するための窓口。
 * ModalEditor の内部実装から切り離し、プラグインを疎結合に保つ。
 */
public interface EditorContext {

    // ---- テキスト読み取り -------------------------------------------------------

    /** バッファ全体のテキストを返す。 */
    String getText();

    /** バッファの総文字数。 */
    int length();

    /** 総行数（getText().split("\n", -1).length と等価）。 */
    int getLineCount();

    /**
     * 指定行のテキスト（改行文字を含まない）。行番号は 0 始まり。
     * 範囲外の行番号を渡した場合は空文字列を返す。
     */
    String getLine(int row);

    // ---- テキスト操作 ----------------------------------------------------------

    /** offset は PieceTable の文字オフセット（先頭から数えた絶対位置）。 */
    void insertAtOffset(int offset, String text);

    /** startOffset から endOffset（排他）の範囲を削除する。 */
    void deleteRange(int startOffset, int endOffset);

    // ---- カーソル読み取り -------------------------------------------------------

    int getCursorRow();
    int getCursorCol();

    /**
     * (row, col) を絶対文字オフセットに変換する。
     * プラグインがカーソル位置の周辺テキストを操作するときに使う。
     */
    int offsetAt(int row, int col);

    // ---- カーソル操作 ----------------------------------------------------------

    /**
     * カーソルを (row, col) に移動する。
     * 範囲外の値は自動的にクランプされる（例外は投げない）。
     */
    void setCursor(int row, int col);

    // ---- モード問い合わせ -------------------------------------------------------

    /** NORMAL モードのとき true。 */
    boolean isNormalMode();

    /** INSERT モードのとき true。 */
    boolean isInsertMode();

    // ---- UI -------------------------------------------------------------------

    /** エディタのステータスバーにメッセージを表示する。 */
    void setStatusMessage(String message);

    // ---- キーマップ -----------------------------------------------------------

    /**
     * モード別キーマップレジストリを返す。
     * プラグインはこれを通じてキーバインドを追加・上書きしたり、
     * 独自アクションハンドラを登録できる。
     */
    KeymapRegistry getKeymap();
}
