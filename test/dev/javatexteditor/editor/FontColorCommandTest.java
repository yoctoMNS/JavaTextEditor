package dev.javatexteditor.editor;

import dev.javatexteditor.ui.EditorCanvas;
import dev.javatexteditor.ui.FontChoice;
import dev.javatexteditor.ui.Theme;
import java.awt.event.KeyEvent;

/**
 * :font 0/1（MiscFixed / IBM Plex Mono）・:color 0/1（ダーク / ライト）コマンドの
 * 回帰テスト（mainメソッド形式・JUnit不使用）。
 */
public class FontColorCommandTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) {
        testDefaultsAreMiscFixedAndDark();
        testFontZeroSelectsMiscFixed();
        testFontOneSelectsIbmPlexMono();
        testColorZeroSelectsDark();
        testColorOneSelectsLight();
        testInvalidFontArgShowsError();
        testInvalidColorArgShowsError();
        testCanvasReflectsChoicesAfterSync();
        testCommandReturnsToNormalMode();

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
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

    static void testDefaultsAreMiscFixedAndDark() {
        ModalEditor ed = newEditorWithCanvas("abc");
        check("既定フォントはMiscFixed", FontChoice.MISC_FIXED, ed.getFontChoice());
        check("既定テーマはダークモード", Theme.DARK_MODE, ed.getTheme());
    }

    static void testFontZeroSelectsMiscFixed() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "font 1");
        check(":font 1 直後はIBM Plex Mono", FontChoice.IBM_PLEX_MONO, ed.getFontChoice());
        sendCommand(ed, "font 0");
        check(":font 0 でMiscFixedへ戻る", FontChoice.MISC_FIXED, ed.getFontChoice());
    }

    static void testFontOneSelectsIbmPlexMono() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "font 1");
        check(":font 1 でIBM Plex Monoになる", FontChoice.IBM_PLEX_MONO, ed.getFontChoice());
    }

    static void testColorZeroSelectsDark() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "color 1");
        sendCommand(ed, "color 0");
        check(":color 0 でダークモードになる", Theme.DARK_MODE, ed.getTheme());
    }

    static void testColorOneSelectsLight() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "color 1");
        check(":color 1 でライトモードになる", Theme.LIGHT_MODE, ed.getTheme());
    }

    static void testInvalidFontArgShowsError() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "font 9");
        check("不正な:font引数はフォントを変更しない", FontChoice.MISC_FIXED, ed.getFontChoice());
        check("不正な:font引数はエラーメッセージ", true, ed.getStatusMessage().startsWith("E:"));
    }

    static void testInvalidColorArgShowsError() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "color 9");
        check("不正な:color引数はテーマを変更しない", Theme.DARK_MODE, ed.getTheme());
        check("不正な:color引数はエラーメッセージ", true, ed.getStatusMessage().startsWith("E:"));
    }

    static void testCanvasReflectsChoicesAfterSync() {
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("abc", canvas);
        sendCommand(ed, "font 1");
        sendCommand(ed, "color 1");
        check("canvasにIBM Plex Monoが反映される", FontChoice.IBM_PLEX_MONO, canvas.getFontChoice());
        check("canvasにライトモードが反映される", Theme.LIGHT_MODE, canvas.getTheme());
    }

    static void testCommandReturnsToNormalMode() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "font 1");
        check(":font 実行後はNORMALモードに戻る", true, ed.isNormalMode());
        sendCommand(ed, "color 1");
        check(":color 実行後はNORMALモードに戻る", true, ed.isNormalMode());
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
