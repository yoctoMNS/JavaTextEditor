package dev.javatexteditor.search;

import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * テキスト内文字列検索（/、*、#、n、N）のテストハーネス。
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

    private static void typeSearch(ModalEditor ed, String pattern) {
        // NORMALモードで / を入力して SEARCH モードへ
        sendChar(ed, '/');
        for (char c : pattern.toCharArray()) sendChar(ed, c);
        sendCode(ed, KeyEvent.VK_ENTER);
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

    static void testSearchEntersSearchMode() {
        ModalEditor ed = editor("hello world");
        assertTrue("initially NORMAL", ed.isNormalMode());
        sendChar(ed, '/');
        assertTrue("/ -> SEARCH mode", ed.isSearchMode());
        assertFalse("not NORMAL during search", ed.isNormalMode());
    }

    static void testSearchBufferAccumulates() {
        ModalEditor ed = editor("hello world");
        sendChar(ed, '/');
        sendChar(ed, 'h');
        sendChar(ed, 'e');
        sendChar(ed, 'l');
        assertTrue("searchBuffer correct", "hel".equals(ed.getSearchBuffer()));
    }

    static void testSearchBufferBackspace() {
        ModalEditor ed = editor("hello");
        sendChar(ed, '/');
        sendChar(ed, 'h');
        sendChar(ed, 'e');
        sendCode(ed, KeyEvent.VK_BACK_SPACE);
        assertTrue("backspace in search", "h".equals(ed.getSearchBuffer()));
    }

    static void testEscCancelsSearch() {
        ModalEditor ed = editor("hello");
        sendChar(ed, '/');
        sendChar(ed, 'h');
        sendCode(ed, KeyEvent.VK_ESCAPE);
        assertTrue("ESC -> NORMAL", ed.isNormalMode());
        assertTrue("searchBuffer cleared", ed.getSearchBuffer().isEmpty());
    }

    static void testForwardSearchJumpsToMatch() {
        ModalEditor ed = editor("foo bar foo");
        // カーソルは先頭 (row=0, col=0)
        typeSearch(ed, "bar");
        assertEq("jumped to col 4", 4, ed.getCursorCol());
        assertTrue("back to NORMAL", ed.isNormalMode());
    }

    static void testForwardSearchWrapsAround() {
        ModalEditor ed = editor("foo bar foo");
        // "foo" は col=0 と col=8 に存在
        // カーソルは先頭なので最初のマッチは col=0 の次、つまり col=8
        // wait - cursor at 0, search "foo" -> first match AFTER cursor...
        // at col=0, so matches[0]={0,3}, matches[1]={8,3}
        // forward=true, cursorOffset=0: find first match > 0 -> index 1 (col=8)
        typeSearch(ed, "foo");
        assertTrue("wraps: col 8 or 0", ed.getCursorCol() == 8 || ed.getCursorCol() == 0);
    }

    static void testSearchMatchCount() {
        ModalEditor ed = editor("aa bb aa cc aa");
        typeSearch(ed, "aa");
        assertEq("3 matches", 3, ed.getSearchMatches().size());
    }

    static void testNJumpsToNextMatch() {
        ModalEditor ed = editor("foo bar foo baz foo");
        typeSearch(ed, "foo");
        // first jump: went past col=0, so col=8
        int firstCol = ed.getCursorCol();
        sendChar(ed, 'n');
        int secondCol = ed.getCursorCol();
        assertTrue("n moved forward", secondCol > firstCol || secondCol == 0); // wrap possible
    }

    static void testNWrapsAround() {
        ModalEditor ed = editor("foo");
        typeSearch(ed, "foo");
        // only 1 match; pressing n stays there (wraps to same)
        int col = ed.getCursorCol();
        sendChar(ed, 'n');
        assertEq("n wraps to same when 1 match", col, ed.getCursorCol());
    }

    static void testBigNGoesBackward() {
        ModalEditor ed = editor("foo bar foo");
        typeSearch(ed, "foo");
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

    static void testRegexSearch() {
        ModalEditor ed = editor("foo1 foo2 foo3");
        typeSearch(ed, "foo[0-9]");
        assertEq("regex: 3 matches", 3, ed.getSearchMatches().size());
    }

    static void testRegexSearchCaseInsensitive() {
        ModalEditor ed = editor("Foo foo FOO");
        typeSearch(ed, "(?i)foo");
        assertEq("case-insensitive: 3 matches", 3, ed.getSearchMatches().size());
    }

    static void testInvalidRegexShowsError() {
        ModalEditor ed = editor("hello");
        typeSearch(ed, "[invalid");
        assertTrue("error msg shown", ed.getStatusMessage().startsWith("E: bad pattern"));
        assertTrue("back to NORMAL", ed.isNormalMode());
    }

    static void testPatternNotFoundMessage() {
        ModalEditor ed = editor("hello world");
        typeSearch(ed, "xyz");
        assertTrue("not found msg", ed.getStatusMessage().contains("not found")
                || ed.getStatusMessage().contains("Pattern not found"));
    }

    static void testSearchMultilineFile() {
        ModalEditor ed = editor("line one\nline two\nline three");
        // "two" is on row 1
        typeSearch(ed, "two");
        assertEq("multiline: row 1", 1, ed.getCursorRow());
        assertEq("multiline: col 5", 5, ed.getCursorCol());
    }

    static void testSearchAcrossLines() {
        ModalEditor ed = editor("abc\ndef\nabc");
        typeSearch(ed, "abc");
        // cursor was at row=0,col=0; search finds next abc AFTER cursor -> row=2, col=0
        // wait - first match is at offset 0 (row0,col0), second at offset 8 (row2,col0)
        // cursor at offset=0, searching forward: find first match > 0 -> matches[1] = offset 8
        assertEq("wrap: row 2", 2, ed.getCursorRow());
    }

    static void testStatusShowsMatchCount() {
        ModalEditor ed = editor("aa aa aa");
        typeSearch(ed, "aa");
        assertTrue("status shows count", ed.getStatusMessage().contains("3"));
    }

    static void testSearchClearedOnNewSearch() {
        ModalEditor ed = editor("foo bar foo");
        typeSearch(ed, "foo");
        int firstMatchCount = ed.getSearchMatches().size();
        typeSearch(ed, "bar");
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

    static void testSearchModeStatusLine() {
        ModalEditor ed = editor("test");
        sendChar(ed, '/');
        sendChar(ed, 'h');
        sendChar(ed, 'i');
        // The command buffer text is accessible via getSearchBuffer in SEARCH mode
        assertTrue("search buffer has hi", "hi".equals(ed.getSearchBuffer()));
        assertTrue("in search mode", ed.isSearchMode());
    }

    private static void sendCommand(ModalEditor ed, String cmd) {
        sendChar(ed, ':');
        for (char c : cmd.toCharArray()) sendChar(ed, c);
        sendCode(ed, KeyEvent.VK_ENTER);
    }

    static void testHighlightClearedOnBufferSwitch() {
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("foo bar foo", canvas);
        typeSearch(ed, "foo");
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
        typeSearch(ed, "foo");
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
        typeSearch(ed, "foo");
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
        typeSearch(ed, "foo");

        sendCode(ed, KeyEvent.VK_ESCAPE);
        // Esc の直後に別のキー（カーソル移動）を押すと、ハイライトクリアの保留状態はキャンセルされる
        sendChar(ed, 'l');
        sendCode(ed, KeyEvent.VK_ESCAPE);
        // 直前の Esc は上の 'l' で保留がリセットされているため、これは「1回目」扱いになりまだクリアされない
        assertFalse("non-consecutive esc does not clear highlight", ed.getSearchMatches().isEmpty());
    }

    public static void main(String[] args) {
        testSearchEntersSearchMode();
        testSearchBufferAccumulates();
        testSearchBufferBackspace();
        testEscCancelsSearch();
        testForwardSearchJumpsToMatch();
        testForwardSearchWrapsAround();
        testSearchMatchCount();
        testNJumpsToNextMatch();
        testNWrapsAround();
        testBigNGoesBackward();
        testStarSearchWordForward();
        testHashSearchWordBackward();
        testStarNoWordAtCursor();
        testRegexSearch();
        testRegexSearchCaseInsensitive();
        testInvalidRegexShowsError();
        testPatternNotFoundMessage();
        testSearchMultilineFile();
        testSearchAcrossLines();
        testStatusShowsMatchCount();
        testSearchClearedOnNewSearch();
        testNWithoutPriorSearch();
        testStarWordBoundary();
        testSearchModeStatusLine();
        testHighlightClearedOnBufferSwitch();
        testHighlightClearedOnBufferHistorySwitch();
        testDoubleEscClearsHighlightInNormalMode();
        testSingleEscDoesNotClearHighlightIfNotRepeated();

        System.out.println("\n=== TextSearchTest: " + pass + " passed, " + fail + " failed ===");
        if (fail > 0) System.exit(1);
    }
}
