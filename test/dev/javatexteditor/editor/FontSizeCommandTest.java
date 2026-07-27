package dev.javatexteditor.editor;

import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;

/**
 * :fs <倍率>（フォントセルサイズを現在の何倍かに変更）コマンドの
 * 回帰テスト（mainメソッド形式・JUnit不使用）。
 */
public class FontSizeCommandTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) {
        testDoubleSize();
        testOneAndHalfTimesRounds();
        testOneQuarterTimesRounds();
        testInvalidArgShowsErrorAndDoesNotChangeSize();
        testZeroOrNegativeArgIsRejected();
        testNoCanvasShowsError();
        testCommandReturnsToNormalMode();

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        System.exit(fail > 0 ? 1 : 0);
    }

    static ModalEditor newEditorWithCanvas(String text) {
        return new ModalEditor(text, new EditorCanvas());
    }

    static void sendCommand(ModalEditor editor, String cmd) {
        editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        editor.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : cmd.toCharArray()) {
            editor.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        }
        editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
    }

    static void testDoubleSize() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(9, 15);
        ModalEditor ed = new ModalEditor("abc", canvas);
        sendCommand(ed, "fs 2");
        check(":fs 2 で幅が2倍になる", 18, canvas.getCellW());
        check(":fs 2 で高さが2倍になる", 30, canvas.getCellH());
    }

    static void testOneAndHalfTimesRounds() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(9, 15);
        ModalEditor ed = new ModalEditor("abc", canvas);
        sendCommand(ed, "fs 1.5");
        check(":fs 1.5 で幅が四捨五入される (9*1.5=13.5->14)", 14, canvas.getCellW());
        check(":fs 1.5 で高さが四捨五入される (15*1.5=22.5->23)", 23, canvas.getCellH());
    }

    static void testOneQuarterTimesRounds() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(9, 15);
        ModalEditor ed = new ModalEditor("abc", canvas);
        sendCommand(ed, "fs 1.25");
        check(":fs 1.25 で幅が四捨五入される (9*1.25=11.25->11)", 11, canvas.getCellW());
        check(":fs 1.25 で高さが四捨五入される (15*1.25=18.75->19)", 19, canvas.getCellH());
    }

    static void testInvalidArgShowsErrorAndDoesNotChangeSize() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(9, 15);
        ModalEditor ed = new ModalEditor("abc", canvas);
        sendCommand(ed, "fs abc");
        check("不正な:fs引数はサイズを変更しない (幅)", 9, canvas.getCellW());
        check("不正な:fs引数はサイズを変更しない (高さ)", 15, canvas.getCellH());
        check("不正な:fs引数はエラーメッセージ", true, ed.getStatusMessage().startsWith("E:"));
    }

    static void testZeroOrNegativeArgIsRejected() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(9, 15);
        ModalEditor ed = new ModalEditor("abc", canvas);
        sendCommand(ed, "fs 0");
        check("0倍はサイズを変更しない", 9, canvas.getCellW());
        check("0倍はエラーメッセージ", true, ed.getStatusMessage().startsWith("E:"));

        sendCommand(ed, "fs -1");
        check("負の倍率はサイズを変更しない", 9, canvas.getCellW());
        check("負の倍率はエラーメッセージ", true, ed.getStatusMessage().startsWith("E:"));
    }

    static void testNoCanvasShowsError() {
        ModalEditor ed = new ModalEditor("abc");
        sendCommand(ed, "fs 2");
        check("canvasなしの:fsはエラーメッセージ", true, ed.getStatusMessage().startsWith("E:"));
    }

    static void testCommandReturnsToNormalMode() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "fs 2");
        check(":fs 実行後はNORMALモードに戻る", true, ed.isNormalMode());
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
