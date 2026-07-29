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
 * IBM Plex Mono(:font 1)選択中は7x15（IbmPlexMonoFont.BASE_CELL_W/H）の整数倍固定10段階
 * （N=0〜9、cellW=7*(N+1)・cellH=15*(N+1)）の、MiscFixedとは独立した同形式の絶対値テーブル
 * （2026-07-29決定。詳細はfont-and-statusline-animationスキル参照）。
 */
public class FontSizeCommandTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) {
        testStepZeroIsBaseSize();
        testStepNineIsMaxSize();
        testStepIsIdempotentRegardlessOfCurrentSize();
        testInvalidArgShowsErrorAndDoesNotChangeSize();
        testOutOfRangeStepIsRejected();
        testNoCanvasShowsError();
        testCommandReturnsToNormalMode();
        testPlexMonoUsesOwnAbsoluteTable();
        testPlexMonoFsDoesNotAffectMiscFixedStash();

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

    static void testPlexMonoUsesOwnAbsoluteTable() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(63, 90);
        ModalEditor ed = new ModalEditor("abc", canvas);
        ed.setFontChoice(FontChoice.IBM_PLEX_MONO);
        sendCommand(ed, "fs 0");
        check(":font 1中の:fs 0はPlex Mono基準サイズ幅7になる（現在サイズに依存しない）", 7, canvas.getCellW());
        check(":font 1中の:fs 0はPlex Mono基準サイズ高さ15になる（現在サイズに依存しない）", 15, canvas.getCellH());

        sendCommand(ed, "fs 2");
        check(":font 1中の:fs 2は幅21になる (7*3)", 21, canvas.getCellW());
        check(":font 1中の:fs 2は高さ45になる (15*3)", 45, canvas.getCellH());

        sendCommand(ed, "fs 9");
        check(":font 1中の:fs 9は幅70になる (7*10)", 70, canvas.getCellW());
        check(":font 1中の:fs 9は高さ150になる (15*10)", 150, canvas.getCellH());
    }

    static void testPlexMonoFsDoesNotAffectMiscFixedStash() {
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("abc", canvas);

        canvas.setFontChoice(FontChoice.IBM_PLEX_MONO);
        ed.setFontChoice(FontChoice.IBM_PLEX_MONO);
        sendCommand(ed, "fs 9");
        check(":font 1で:fs 9を実行した直後は幅70になる", 70, canvas.getCellW());
        check(":font 1で:fs 9を実行した直後は高さ150になる", 150, canvas.getCellH());

        canvas.setFontChoice(FontChoice.MISC_FIXED);
        check("Plex Monoの:fsはMiscFixedの既定サイズ(幅9)に影響しない", 9, canvas.getCellW());
        check("Plex Monoの:fsはMiscFixedの既定サイズ(高さ15)に影響しない", 15, canvas.getCellH());
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
