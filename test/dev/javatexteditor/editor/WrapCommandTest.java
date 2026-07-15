package dev.javatexteditor.editor;

import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;

/**
 * ":wrap"（画面端での折り返し表示を有効化）/ ":nowrap"（無効化・既定）の
 * コマンド解析・状態管理のテスト。実際の折返し描画は EditorCanvasTest 側で検証する。
 * mainメソッド形式のテストハーネス（JUnit不使用）。
 */
public class WrapCommandTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testDefaultIsNoWrap();
        testWrapCommandEnablesWrap();
        testNowrapCommandDisablesWrap();
        testWrapDoesNotChangeMode();
        testUnknownWrapVariantIsUnknownCommand();
        testWrapStateSyncsToCanvas();

        System.out.println("\n=== WrapCommand: " + passed + "/" + (passed + failed) + " PASS ===");
        if (failed > 0) System.exit(1);
    }

    static void testDefaultIsNoWrap() {
        ModalEditor ed = new ModalEditor("hello");
        assertTrue("既定はnowrap相当(isWrapEnabled==false)", !ed.isWrapEnabled());
        passed("testDefaultIsNoWrap");
    }

    static void testWrapCommandEnablesWrap() {
        ModalEditor ed = new ModalEditor("hello");
        sendCommand(ed, "wrap");
        assertTrue(":wrap でisWrapEnabled==true", ed.isWrapEnabled());
        passed("testWrapCommandEnablesWrap");
    }

    static void testNowrapCommandDisablesWrap() {
        ModalEditor ed = new ModalEditor("hello");
        sendCommand(ed, "wrap");
        assertTrue(":wrap 直後はtrue", ed.isWrapEnabled());
        sendCommand(ed, "nowrap");
        assertTrue(":nowrap でisWrapEnabled==false", !ed.isWrapEnabled());
        passed("testNowrapCommandDisablesWrap");
    }

    static void testWrapDoesNotChangeMode() {
        ModalEditor ed = new ModalEditor("hello");
        sendCommand(ed, "wrap");
        assertTrue(":wrap 実行後はNORMALモードに戻る", ed.isNormalMode());
        passed("testWrapDoesNotChangeMode");
    }

    static void testUnknownWrapVariantIsUnknownCommand() {
        ModalEditor ed = new ModalEditor("hello");
        sendCommand(ed, "wrapx");
        assertTrue("未知のコマンドはエラー表示", ed.getStatusMessage().contains("unknown command"));
        assertTrue("未知のコマンドではwrapEnabledは変化しない", !ed.isWrapEnabled());
        passed("testUnknownWrapVariantIsUnknownCommand");
    }

    static void testWrapStateSyncsToCanvas() {
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("hello", canvas);
        sendCommand(ed, "wrap");
        assertTrue("syncCanvas()経由でcanvas.isWrapEnabled()もtrueになる", canvas.isWrapEnabled());
        sendCommand(ed, "nowrap");
        assertTrue("syncCanvas()経由でcanvas.isWrapEnabled()もfalseになる", !canvas.isWrapEnabled());
        passed("testWrapStateSyncsToCanvas");
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private static void sendCommand(ModalEditor editor, String cmd) {
        editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        editor.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : cmd.toCharArray()) {
            editor.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        }
        editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
    }

    private static void assertTrue(String name, boolean condition) {
        if (!condition) fail(name);
    }

    private static void passed(String name) {
        passed++;
        System.out.println("[OK] " + name);
    }

    private static void fail(String name) {
        failed++;
        System.out.println("[FAIL] " + name);
    }
}
