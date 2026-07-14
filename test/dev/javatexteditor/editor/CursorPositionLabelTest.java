package dev.javatexteditor.editor;

import dev.javatexteditor.ui.EditorCanvas;

/**
 * ステータスバー用カーソル位置ラベル "(行数:トータル文字数)" のテストハーネス
 * （mainメソッド形式・JUnit不使用）。
 */
public class CursorPositionLabelTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testInitialPositionOnEmptyBuffer();
        testMovesRightUpdatesTotalChars();
        testSecondLineAccountsForNewline();
        testFullWidthCharCountsAsOne();

        System.out.printf("%nPASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
        // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
        // （JumpBackTest.java と同じ既知の対策）。
        System.exit(fail > 0 ? 1 : 0);
    }

    static void testInitialPositionOnEmptyBuffer() {
        System.out.println("[初期カーソル位置は(1:1)]");
        EditorCanvas canvas = new EditorCanvas();
        new ModalEditor("abc", canvas);
        check("(1:1)", "(1:1)", canvas.getCursorPositionLabel());
    }

    static void testMovesRightUpdatesTotalChars() {
        System.out.println("[l で右移動するとトータル文字数が増える]");
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("abc", canvas);
        pressChar(ed, 'l');
        pressChar(ed, 'l');
        check("2回右移動後は(1:3)", "(1:3)", canvas.getCursorPositionLabel());
    }

    static void testSecondLineAccountsForNewline() {
        System.out.println("[2行目では前の行の長さ+改行分を加算する]");
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("abcde\nxy", canvas);
        pressChar(ed, 'j'); // 2行目へ
        check("2行目1文字目は(2:7)", "(2:7)", canvas.getCursorPositionLabel());
        pressChar(ed, 'l');
        check("2行目2文字目は(2:8)", "(2:8)", canvas.getCursorPositionLabel());
    }

    static void testFullWidthCharCountsAsOne() {
        System.out.println("[全角文字も半角文字も1文字として数える]");
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("あいう", canvas);
        pressChar(ed, 'l');
        pressChar(ed, 'l');
        check("全角3文字目は(1:3)", "(1:3)", canvas.getCursorPositionLabel());
    }

    // ユーティリティ
    // -------------------------------------------------------------------------

    static void pressChar(ModalEditor ed, char keyChar) {
        ed.processKey(0, keyChar, 0);
    }

    static void check(String label, String expected, String actual) {
        boolean ok = expected.equals(actual);
        System.out.printf("[%s] %s -> expected=%s actual=%s%n", ok ? "OK" : "NG", label, expected, actual);
        if (ok) pass++; else fail++;
    }
}
