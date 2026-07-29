package dev.javatexteditor.search;

import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;

/**
 * テキスト内文字列検索（*、#、n、N）のテストハーネス。
 *
 * かつて存在した `/` によるパターン入力検索（Vim式、Enterで確定してn/Nで移動）は、
 * Emacs式インクリメンタルサーチ（C-s/C-r。NORMAL/INSERT両モードで有効。
 * {@link EmacsIsearchTest} 参照）に一本化するため廃止した。
 * *（カーソル位置の単語を前方検索）・#（後方検索）・n/N（次/前マッチへ移動）は
 * Vimとの互換性のため引き続き提供する。
 */
public class TextSearchTest {

    private static int pass = 0;
    private static int fail = 0;

    // --- ヘルパー ---

    private static ModalEditor editor(String text) {
        return new ModalEditor(text);
    }

    private static void sendChar(ModalEditor ed, char c) {
        ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
    }

    private static void sendCode(ModalEditor ed, int code, int modifiers) {
        ed.processKey(code, KeyEvent.CHAR_UNDEFINED, modifiers);
    }

    private static void sendCode(ModalEditor ed, int code) {
        sendCode(ed, code, 0);
    }

    private static void assertEq(String label, int expected, int actual) {
        if (expected == actual) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL [" + label + "] expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(String label, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL [" + label + "]");
        }
    }

    private static void assertFalse(String label, boolean cond) {
        assertTrue(label, !cond);
    }

    // --- テスト ---

    static void testSearchMatchCount() {
        ModalEditor ed = editor("aa bb aa cc aa");
        sendChar(ed, '*'); // cursor on first "aa"
        assertEq("3 matches", 3, ed.getSearchMatches().size());
    }

    static void testNJumpsToNextMatch() {
        ModalEditor ed = editor("foo bar foo baz foo");
        sendChar(ed, '*'); // jumps past col=0, so col=8
        int firstCol = ed.getCursorCol();
        sendChar(ed, 'n');
        int secondCol = ed.getCursorCol();
        assertTrue("n moved forward", secondCol > firstCol || secondCol == 0); // wrap possible
    }

    static void testNWrapsAround() {
        ModalEditor ed = editor("foo");
        sendChar(ed, '*');
        // only 1 match; pressing n stays there (wraps to same)
        int col = ed.getCursorCol();
        sendChar(ed, 'n');
        assertEq("n wraps to same when 1 match", col, ed.getCursorCol());
    }

    static void testBigNGoesBackward() {
        ModalEditor ed = editor("foo bar foo");
        sendChar(ed, '*');
        // After search: cursor at col=8 (matches[1])
        sendChar(ed, 'N');
        // N reverses direction -> should go to matches[0] at col=0
        assertEq("N goes to first match", 0, ed.getCursorCol());
    }

    static void testStarSearchWordForward() {
        ModalEditor ed = editor("hello world hello");
        // cursor at col=0 on "hello"
        sendChar(ed, '*');
        // should jump to second "hello" at col=12
        assertEq("* jumps to second hello", 12, ed.getCursorCol());
        assertTrue("lastSearchPattern contains hello",
            ed.getLastSearchPattern().contains("hello"));
    }

    static void testHashSearchWordBackward() {
        ModalEditor ed = editor("hello world hello");
        // Move to second "hello" first
        ed.setCursor(0, 12);
        sendChar(ed, '#');
        // should jump backward to first "hello" at col=0
        assertEq("# jumps backward to first hello", 0, ed.getCursorCol());
    }

    static void testStarNoWordAtCursor() {
        ModalEditor ed = editor("   spaces   ");
        // cursor at col=0 which is a space
        sendChar(ed, '*');
        assertTrue("no match msg", ed.getStatusMessage().contains("No word"));
        assertTrue("still NORMAL", ed.isNormalMode());
    }

    static void testSearchAcrossLines() {
        ModalEditor ed = editor("abc\ndef\nabc");
        // cursor at row0,col0 is on "abc"
        sendChar(ed, '*');
        // 次の "abc" は row=2
        assertEq("wrap: row 2", 2, ed.getCursorRow());
    }

    static void testStatusShowsMatchCount() {
        ModalEditor ed = editor("aa aa aa");
        sendChar(ed, '*');
        assertTrue("status shows count", ed.getStatusMessage().contains("3"));
    }

    static void testSearchClearedOnNewSearch() {
        ModalEditor ed = editor("foo bar foo");
        sendChar(ed, '*'); // cursor on "foo" (col0)
        int firstMatchCount = ed.getSearchMatches().size();
        ed.setCursor(0, 4); // "bar"
        sendChar(ed, '*');
        int secondMatchCount = ed.getSearchMatches().size();
        assertEq("old 2 matches for foo", 2, firstMatchCount);
        assertEq("new 1 match for bar", 1, secondMatchCount);
    }

    static void testNWithoutPriorSearch() {
        ModalEditor ed = editor("hello");
        // Press n without any prior search
        sendChar(ed, 'n');
        assertTrue("no pattern msg or stays NORMAL", ed.isNormalMode());
    }

    static void testStarWordBoundary() {
        ModalEditor ed = editor("foo foobar foo");
        // cursor on first "foo" -> * should match whole words only
        sendChar(ed, '*');
        // "foobar" should NOT match \bfoo\b; only "foo" at col=0 and col=11 match
        assertEq("word boundary: 2 matches", 2, ed.getSearchMatches().size());
    }

    private static void sendCommand(ModalEditor ed, String cmd) {
        sendChar(ed, ':');
        for (char c : cmd.toCharArray()) sendChar(ed, c);
        sendCode(ed, KeyEvent.VK_ENTER);
    }

    static void testHighlightClearedOnBufferSwitch() {
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("foo bar foo", canvas);
        sendChar(ed, '*');
        assertEq("before switch: 2 matches", 2, ed.getSearchMatches().size());
        assertFalse("before switch: canvas has highlights", canvas.getSearchHighlights().isEmpty());

        // :enew で新規バッファへ切り替える（旧バッファのハイライトが残ってはいけない）
        sendCommand(ed, "enew");

        assertTrue("after switch: no search matches", ed.getSearchMatches().isEmpty());
        assertTrue("after switch: canvas highlights cleared", canvas.getSearchHighlights().isEmpty());
    }

    static void testHighlightClearedOnBufferHistorySwitch() {
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("foo bar foo", canvas);
        sendChar(ed, '*');
        assertFalse("history: canvas has highlights before", canvas.getSearchHighlights().isEmpty());

        sendCommand(ed, "enew");
        // Ctrl+U: 前のバッファへ戻る。ここでも新しいバッファ側の状態としてハイライトが残ってはいけない
        sendCode(ed, KeyEvent.VK_U, KeyEvent.CTRL_DOWN_MASK);

        assertTrue("history: no search matches after switch back", ed.getSearchMatches().isEmpty());
        assertTrue("history: canvas highlights cleared after switch back", canvas.getSearchHighlights().isEmpty());
    }

    static void testDoubleEscClearsHighlightInNormalMode() {
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("foo bar foo", canvas);
        sendChar(ed, '*');
        assertEq("before esc: 2 matches", 2, ed.getSearchMatches().size());

        // 1回目の Esc: まだクリアしない
        sendCode(ed, KeyEvent.VK_ESCAPE);
        assertEq("after 1st esc: still 2 matches", 2, ed.getSearchMatches().size());
        assertTrue("after 1st esc: still in normal mode", ed.isNormalMode());

        // 2回目の Esc: 強制的にハイライトをクリアする
        sendCode(ed, KeyEvent.VK_ESCAPE);
        assertTrue("after 2nd esc: no search matches", ed.getSearchMatches().isEmpty());
        assertTrue("after 2nd esc: canvas highlights cleared", canvas.getSearchHighlights().isEmpty());
    }

    static void testSingleEscDoesNotClearHighlightIfNotRepeated() {
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("foo bar foo", canvas);
        sendChar(ed, '*');

        sendCode(ed, KeyEvent.VK_ESCAPE);
        // Esc の直後に別のキー（カーソル移動）を押すと、ハイライトクリアの保留状態はキャンセルされる
        sendChar(ed, 'l');
        sendCode(ed, KeyEvent.VK_ESCAPE);
        // 直前の Esc は上の 'l' で保留がリセットされているため、これは「1回目」扱いになりまだクリアされない
        assertFalse("non-consecutive esc does not clear highlight", ed.getSearchMatches().isEmpty());
    }

    public static void main(String[] args) {
        testSearchMatchCount();
        testNJumpsToNextMatch();
        testNWrapsAround();
        testBigNGoesBackward();
        testStarSearchWordForward();
        testHashSearchWordBackward();
        testStarNoWordAtCursor();
        testSearchAcrossLines();
        testStatusShowsMatchCount();
        testSearchClearedOnNewSearch();
        testNWithoutPriorSearch();
        testStarWordBoundary();
        testHighlightClearedOnBufferSwitch();
        testHighlightClearedOnBufferHistorySwitch();
        testDoubleEscClearsHighlightInNormalMode();
        testSingleEscDoesNotClearHighlightIfNotRepeated();

        System.out.println("\n=== TextSearchTest: " + pass + " passed, " + fail + " failed ===");
        if (fail > 0) System.exit(1);
        System.exit(0);   // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
    }
}
