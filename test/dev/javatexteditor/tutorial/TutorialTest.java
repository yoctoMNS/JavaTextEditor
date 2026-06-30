package dev.javatexteditor.tutorial;

import dev.javatexteditor.editor.ModalEditor;
import java.awt.event.KeyEvent;

/**
 * Tutorial.CONTENT の内容検証と、ModalEditor の :tutor コマンド統合テスト。
 */
public class TutorialTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testContentNotEmpty();
        testContentHasAllLessons();
        testContentNoStrayEscapes();

        testTutorCommandOpensBuffer();
        testTutorCommandClearsFilePath();
        testTutorCommandResetsCursor();
        testTutorCommandPushesPreviousBuffer();
        testTutorAliasTutorial();
        testTutorialBufferIsEditable();

        System.out.println("\n=== Tutorial: " + passed + "/" + (passed + failed) + " PASS ===");
        if (failed > 0) System.exit(1);
    }

    // =========================================================================
    // Tutorial.CONTENT 単体テスト
    // =========================================================================

    static void testContentNotEmpty() {
        assertTrue("content not empty", Tutorial.CONTENT != null && !Tutorial.CONTENT.isBlank());
        passed("testContentNotEmpty");
    }

    static void testContentHasAllLessons() {
        for (int i = 1; i <= 13; i++) {
            assertTrue("contains lesson " + i, Tutorial.CONTENT.contains("レッスン " + i + ":"));
        }
        assertTrue("mentions :tutor", Tutorial.CONTENT.contains(":tutor"));
        assertTrue("mentions hjkl basics", Tutorial.CONTENT.contains("h j k l"));
        passed("testContentHasAllLessons");
    }

    static void testContentNoStrayEscapes() {
        // \f (ファイル名検索) と \g (内容grep) のキーバインド表記が
        // 文字どおりバックスラッシュ付きで出力されていることを確認する
        // （テキストブロックの \\f / \\g エスケープが正しく機能しているか）。
        assertTrue("literal backslash-f present", Tutorial.CONTENT.contains("\\f "));
        assertTrue("literal backslash-g present", Tutorial.CONTENT.contains("\\g "));
        // フォームフィードや未知のエスケープが紛れ込んでいないこと
        assertTrue("no form-feed char", Tutorial.CONTENT.indexOf('\f') == -1);
        passed("testContentNoStrayEscapes");
    }

    // =========================================================================
    // ModalEditor 統合テスト
    // =========================================================================

    static void testTutorCommandOpensBuffer() {
        ModalEditor editor = new ModalEditor("元のファイルの内容");
        sendCommand(editor, "tutor");
        assertTrue("buffer shows tutorial header",
            editor.getText().contains("JavaTextEditor チュートリアルへようこそ"));
        passed("testTutorCommandOpensBuffer");
    }

    static void testTutorCommandClearsFilePath() {
        ModalEditor editor = new ModalEditor("元のファイルの内容");
        sendCommand(editor, "tutor");
        assertEquals("no current file path", null, editor.getCurrentFilePath());
        passed("testTutorCommandClearsFilePath");
    }

    static void testTutorCommandResetsCursor() {
        ModalEditor editor = new ModalEditor("line1\nline2\nline3");
        // カーソルを先頭以外に動かしてから :tutor を実行する
        editor.processKey(KeyEvent.VK_UNDEFINED, 'j', 0);
        editor.processKey(KeyEvent.VK_UNDEFINED, 'j', 0);
        sendCommand(editor, "tutor");
        assertEquals("cursor row reset", 0, editor.getCursorRow());
        assertEquals("cursor col reset", 0, editor.getCursorCol());
        passed("testTutorCommandResetsCursor");
    }

    static void testTutorCommandPushesPreviousBuffer() {
        ModalEditor editor = new ModalEditor("元のバッファの内容です");
        sendCommand(editor, "tutor");
        assertTrue("tutorial buffer active",
            editor.getText().contains("チュートリアルへようこそ"));
        // Ctrl+U で直前のバッファ（履歴）に戻れること
        editor.processKey(KeyEvent.VK_U, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK);
        assertTrue("returned to previous buffer", editor.getText().contains("元のバッファの内容です"));
        passed("testTutorCommandPushesPreviousBuffer");
    }

    static void testTutorAliasTutorial() {
        ModalEditor editor = new ModalEditor("hello");
        sendCommand(editor, "tutorial");
        assertTrue("alias :tutorial works",
            editor.getText().contains("チュートリアルへようこそ"));
        passed("testTutorAliasTutorial");
    }

    static void testTutorialBufferIsEditable() {
        ModalEditor editor = new ModalEditor("hello");
        sendCommand(editor, "tutor");
        String before = editor.getText();
        // NORMAL モードで x を1回押すとカーソル位置の1文字が削除できること
        editor.processKey(KeyEvent.VK_UNDEFINED, 'x', 0);
        assertTrue("buffer mutated by x", !editor.getText().equals(before));
        passed("testTutorialBufferIsEditable");
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
        if (!condition) fail(name, "true", "false");
    }

    private static void assertEquals(String name, Object expected, Object actual) {
        boolean ok = (expected == null) ? actual == null : expected.equals(actual);
        if (!ok) fail(name, String.valueOf(expected), String.valueOf(actual));
    }

    private static void passed(String name) {
        passed++;
        System.out.println("[OK] " + name);
    }

    private static void fail(String name, String expected, String actual) {
        failed++;
        System.out.println("[FAIL] " + name + " expected=<" + expected + "> actual=<" + actual + ">");
    }
}
