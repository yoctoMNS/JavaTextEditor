package dev.javatexteditor.editor;

import dev.javatexteditor.ui.EditorCanvas;
import dev.javatexteditor.ui.FontChoice;
import java.awt.event.KeyEvent;

/**
 * :fs <N>（フォントセルサイズの絶対値テーブル参照コマンド）の
 * 回帰テスト（mainメソッド形式・JUnit不使用）。
 *
 * MiscFixed(:font 0)選択中は9x15の整数倍固定10段階（N=0〜9、
 * cellW=9*(N+1)・cellH=15*(N+1)）のべき等な絶対値テーブル。
 * IBM Plex Mono(:font 1)選択中は暫定的に旧来の「現在サイズへの倍率」方式を維持する
 * （2026-07-29決定。詳細はfont-and-statusline-animationスキル参照）。
 */
public class FontSizeCommandTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) {
        testStepZeroIsBaseSize();
        testStepNineIsMaxSize();
        testStepIsIdempotentRegardlessOfCurrentSize();
        testSequentialStepSwitchesAlwaysLandOnSameAbsoluteSize();
        testInvalidArgShowsErrorAndDoesNotChangeSize();
        testOutOfRangeStepIsRejected();
        testNoCanvasShowsError();
        testCommandReturnsToNormalMode();
        testPlexMonoStillUsesMultiplier();

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

    static void testStepZeroIsBaseSize() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(27, 45);
        ModalEditor ed = new ModalEditor("abc", canvas);
        sendCommand(ed, "fs 0");
        check(":fs 0 で幅が基準サイズ9になる", 9, canvas.getCellW());
        check(":fs 0 で高さが基準サイズ15になる", 15, canvas.getCellH());
    }

    static void testStepNineIsMaxSize() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(9, 15);
        ModalEditor ed = new ModalEditor("abc", canvas);
        sendCommand(ed, "fs 9");
        check(":fs 9 で幅が90になる (9*10)", 90, canvas.getCellW());
        check(":fs 9 で高さが150になる (15*10)", 150, canvas.getCellH());
    }

    static void testStepIsIdempotentRegardlessOfCurrentSize() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(63, 90);
        ModalEditor ed = new ModalEditor("abc", canvas);
        sendCommand(ed, "fs 3");
        check(":fs 3 は現在サイズに関わらず幅36になる (9*4)", 36, canvas.getCellW());
        check(":fs 3 は現在サイズに関わらず高さ60になる (15*4)", 60, canvas.getCellH());
    }

    /**
     * :fs 3 の後に :fs 1 に戻れなくなる不具合の再発防止テスト。
     * 同一のcanvas/editorインスタンスに対し、:fs N を昇順・降順・ランダムな順序で
     * 連続実行しても、各Nは常にテーブル上の同じ絶対値(9*(N+1) x 15*(N+1))になることを確認する。
     */
    static void testSequentialStepSwitchesAlwaysLandOnSameAbsoluteSize() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(9, 15);
        ModalEditor ed = new ModalEditor("abc", canvas);

        int[] order = {0, 3, 1, 9, 5, 3, 1, 0, 2, 8, 1};
        for (int step : order) {
            sendCommand(ed, "fs " + step);
            int expectedW = 9 * (step + 1);
            int expectedH = 15 * (step + 1);
            check(":fs " + step + " は実行順序に関わらず幅が " + expectedW + " になる",
                expectedW, canvas.getCellW());
            check(":fs " + step + " は実行順序に関わらず高さが " + expectedH + " になる",
                expectedH, canvas.getCellH());
        }
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

    static void testOutOfRangeStepIsRejected() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(9, 15);
        ModalEditor ed = new ModalEditor("abc", canvas);
        sendCommand(ed, "fs 10");
        check("10段階の範囲外(10)はサイズを変更しない", 9, canvas.getCellW());
        check("範囲外はエラーメッセージ", true, ed.getStatusMessage().startsWith("E:"));

        sendCommand(ed, "fs -1");
        check("負のNはサイズを変更しない", 9, canvas.getCellW());
        check("負のNはエラーメッセージ", true, ed.getStatusMessage().startsWith("E:"));
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

    static void testPlexMonoStillUsesMultiplier() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(9, 15);
        ModalEditor ed = new ModalEditor("abc", canvas);
        ed.setFontChoice(FontChoice.IBM_PLEX_MONO);
        sendCommand(ed, "fs 2");
        check("Plex Mono中の:fs 2は現在サイズの2倍になる (幅)", 18, canvas.getCellW());
        check("Plex Mono中の:fs 2は現在サイズの2倍になる (高さ)", 30, canvas.getCellH());
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
