package dev.javatexteditor.search;

import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;

/**
 * Emacs式インクリメンタルサーチ（C-s / C-r、NORMAL/INSERT両モードで有効）のテストハーネス。
 *
 * NORMALモードでは Ctrl+R は元々 redo に割り当てられていたが、isearch の後方検索起動キーへ
 * 変更し、redo は Ctrl+Shift+R へ移動した（text-search skill参照）。
 */
public class EmacsIsearchTest {

    private static int pass = 0;
    private static int fail = 0;

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

    private static void ctrlS(ModalEditor ed) {
        sendCode(ed, KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK);
    }

    private static void ctrlR(ModalEditor ed) {
        sendCode(ed, KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK);
    }

    private static void ctrlShiftR(ModalEditor ed) {
        sendCode(ed, KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
    }

    private static void enterInsert(ModalEditor ed) {
        sendChar(ed, 'i');
    }

    private static void type(ModalEditor ed, String s) {
        for (char c : s.toCharArray()) sendChar(ed, c);
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

    static void testCtrlSStartsIsearchInNormalMode() {
        ModalEditor ed = editor("foo bar foo");
        // NORMALモードのままでも C-s で isearch が起動する
        ctrlS(ed);
        assertTrue("C-s starts isearch in NORMAL", ed.isEmacsIsearchActive());
        assertTrue("still logically NORMAL mode", ed.isNormalMode());
        assertTrue("forward by default", ed.isIsearchForward());
    }

    static void testCtrlRStartsBackwardIsearchInNormalMode() {
        ModalEditor ed = editor("foo bar foo");
        ed.setCursor(0, 11);
        ctrlR(ed);
        assertTrue("C-r starts isearch in NORMAL", ed.isEmacsIsearchActive());
        assertFalse("backward by default", ed.isIsearchForward());
    }

    static void testCtrlShiftRStillRedoesInNormalMode() {
        ModalEditor ed = editor("hello");
        sendChar(ed, 'i'); sendChar(ed, 'X');
        sendCode(ed, KeyEvent.VK_ESCAPE);
        sendChar(ed, 'u'); // undo -> "hello"
        assertEq("undo back to hello", 5, ed.getText().length());
        ctrlShiftR(ed); // redo -> isearchではなくredoが起動することを確認
        assertFalse("Ctrl+Shift+R does not start isearch", ed.isEmacsIsearchActive());
        assertTrue("Ctrl+Shift+R redo restored Xhello", ed.getText().equals("Xhello"));
    }

    static void testTypingJumpsToNearestForwardMatchInNormalMode() {
        ModalEditor ed = editor("foo bar foo");
        // cursor at col 0
        ctrlS(ed);
        type(ed, "foo");
        assertEq("jump to nearest forward match (NORMAL)", 8, ed.getCursorCol());
    }

    static void testRepeatedCtrlSAdvancesFurtherInNormalMode() {
        ModalEditor ed = editor("foo bar foo baz foo");
        ctrlS(ed);
        type(ed, "foo");
        int first = ed.getCursorCol();
        ctrlS(ed);
        int second = ed.getCursorCol();
        assertTrue("second match further than first (NORMAL)", second > first);
    }

    static void testBackspaceInNormalMode() {
        ModalEditor ed = editor("foo bar foo");
        ctrlS(ed);
        type(ed, "fooX");
        assertTrue("no match for fooX (NORMAL)", ed.getSearchMatches().isEmpty());
        sendCode(ed, KeyEvent.VK_BACK_SPACE);
        assertEq("backspace re-jumps to foo match (NORMAL)", 8, ed.getCursorCol());
    }

    static void testEnterCommitsAndReturnsToPlainNormalMode() {
        ModalEditor ed = editor("foo bar foo");
        ctrlS(ed);
        type(ed, "bar");
        sendCode(ed, KeyEvent.VK_ENTER);
        assertFalse("isearch ended", ed.isEmacsIsearchActive());
        assertTrue("still NORMAL mode", ed.isNormalMode());
        assertEq("cursor stayed at match", 4, ed.getCursorCol());
        // 通常のNORMAL入力に戻っている（x でカーソル位置の1文字が消せることを確認）
        sendChar(ed, 'x');
        assertFalse("normal command works again", ed.getText().contains("bar"));
    }

    static void testEscapeCancelsAndRestoresCursorInNormalMode() {
        ModalEditor ed = editor("foo bar foo");
        // cursor at col 0
        ctrlS(ed);
        type(ed, "bar");
        assertEq("jumped to bar", 4, ed.getCursorCol());
        sendCode(ed, KeyEvent.VK_ESCAPE);
        assertFalse("isearch cancelled", ed.isEmacsIsearchActive());
        assertTrue("still NORMAL mode", ed.isNormalMode());
        assertEq("cursor restored to origin", 0, ed.getCursorCol());
    }

    static void testHighlightClearedAfterCommitInNormalMode() {
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("foo bar foo", canvas);
        ctrlS(ed);
        type(ed, "foo");
        assertFalse("highlight present during isearch (NORMAL)", canvas.getSearchHighlights().isEmpty());
        sendCode(ed, KeyEvent.VK_ENTER);
        assertTrue("highlight cleared after commit (NORMAL)", canvas.getSearchHighlights().isEmpty());
    }

    static void testUnhandledKeyEndsIsearchAndFallsThroughInNormalMode() {
        ModalEditor ed = editor("foo bar foo");
        ctrlS(ed);
        type(ed, "bar");
        // Ctrl+B は isearch 専用キーではないので、isearch を終了させてから
        // 通常の NORMAL 用 Ctrl+B（scroll.page.up）として処理される（例外を投げず完走することを確認）
        sendCode(ed, KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK);
        assertFalse("isearch ended by unrelated key (NORMAL)", ed.isEmacsIsearchActive());
        assertTrue("still NORMAL mode after fallback", ed.isNormalMode());
    }

    static void testPendingSequenceDiscardedWhenIsearchStarts() {
        ModalEditor ed = editor("foo\nbar\nfoo");
        // 'g' で gg（ファイル先頭ジャンプ）の1打鍵目を保留させる
        sendChar(ed, 'g');
        // ここで C-s を押すと、保留中の 'g' は破棄されて isearch が起動する
        ctrlS(ed);
        assertTrue("isearch started despite pending 'g'", ed.isEmacsIsearchActive());
        sendCode(ed, KeyEvent.VK_ESCAPE); // isearchをキャンセルして抜ける
        // 直後に 'g' 単独を押しても gg 化せず goto.pending の1打鍵目として保留するだけになる
        // （古い pendingSequence と結合してファイル末尾等へ暴走しないことの確認）
        int rowBefore = ed.getCursorRow();
        sendChar(ed, 'g');
        assertEq("stray 'g' does not jump anywhere by itself", rowBefore, ed.getCursorRow());
    }

    static void testCtrlSStartsIsearchInInsertMode() {
        ModalEditor ed = editor("foo bar foo");
        enterInsert(ed);
        ctrlS(ed);
        assertTrue("C-s starts isearch in INSERT", ed.isEmacsIsearchActive());
        assertTrue("still logically INSERT mode", ed.isInsertMode());
        assertTrue("forward by default", ed.isIsearchForward());
    }

    static void testCtrlRStartsBackwardIsearch() {
        ModalEditor ed = editor("foo bar foo");
        enterInsert(ed);
        ed.setCursor(0, 11);
        ctrlR(ed);
        assertTrue("C-r starts isearch", ed.isEmacsIsearchActive());
        assertFalse("backward by default", ed.isIsearchForward());
    }

    static void testTypingJumpsToNearestForwardMatch() {
        ModalEditor ed = editor("foo bar foo");
        enterInsert(ed);
        // cursor at col 0
        ctrlS(ed);
        type(ed, "foo");
        // "foo" candidates at col 0 and col 8; strictly after cursor(0) -> col 8
        assertEq("jump to nearest forward match", 8, ed.getCursorCol());
    }

    static void testTypingJumpsToNearestBackwardMatch() {
        ModalEditor ed = editor("foo bar foo");
        enterInsert(ed);
        ed.setCursor(0, 11);
        ctrlR(ed);
        type(ed, "foo");
        // strictly before cursor(11) -> col 8
        assertEq("jump to nearest backward match", 8, ed.getCursorCol());
    }

    static void testRepeatedCtrlSAdvancesFurther() {
        ModalEditor ed = editor("foo bar foo baz foo");
        enterInsert(ed);
        ctrlS(ed);
        type(ed, "foo");
        int first = ed.getCursorCol();
        ctrlS(ed);
        int second = ed.getCursorCol();
        assertTrue("second match further than first", second > first);
    }

    static void testCtrlRReversesDirectionAfterCtrlS() {
        ModalEditor ed = editor("foo bar foo baz foo");
        enterInsert(ed);
        ctrlS(ed);
        type(ed, "foo");
        int afterFirstJump = ed.getCursorCol();
        ctrlS(ed); // さらに前方(下方向)の候補へ進む
        int afterAdvance = ed.getCursorCol();
        assertTrue("advance moved further forward", afterAdvance > afterFirstJump);
        ctrlR(ed); // 方向反転: 直前の候補へ戻るはず
        assertEq("C-r goes back to previous match", afterFirstJump, ed.getCursorCol());
    }

    static void testBackspaceNarrowsQueryAndRejumps() {
        ModalEditor ed = editor("foo bar foo");
        enterInsert(ed);
        ctrlS(ed);
        type(ed, "fooX");
        // "fooX" has no match, so search matches empty
        assertTrue("no match for fooX", ed.getSearchMatches().isEmpty());
        sendCode(ed, KeyEvent.VK_BACK_SPACE);
        // back to "foo" which matches
        assertEq("backspace re-jumps to foo match", 8, ed.getCursorCol());
    }

    static void testEnterCommitsAndReturnsToPlainInsert() {
        ModalEditor ed = editor("foo bar foo");
        enterInsert(ed);
        ctrlS(ed);
        type(ed, "bar");
        sendCode(ed, KeyEvent.VK_ENTER);
        assertFalse("isearch ended", ed.isEmacsIsearchActive());
        assertTrue("still INSERT mode", ed.isInsertMode());
        assertEq("cursor stayed at match", 4, ed.getCursorCol());
        // 通常の INSERT 入力に戻っている（改行ではなく文字挿入されることを確認）
        sendChar(ed, 'Z');
        assertTrue("typed char inserted normally", ed.getText().contains("Zbar"));
    }

    static void testEscapeCancelsAndRestoresCursor() {
        ModalEditor ed = editor("foo bar foo");
        enterInsert(ed);
        // cursor at col 0
        ctrlS(ed);
        type(ed, "bar");
        assertEq("jumped to bar", 4, ed.getCursorCol());
        sendCode(ed, KeyEvent.VK_ESCAPE);
        assertFalse("isearch cancelled", ed.isEmacsIsearchActive());
        assertTrue("still INSERT mode (Esc during isearch does not leave INSERT)", ed.isInsertMode());
        assertEq("cursor restored to origin", 0, ed.getCursorCol());
    }

    static void testHighlightClearedAfterCommit() {
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("foo bar foo", canvas);
        enterInsert(ed);
        ctrlS(ed);
        type(ed, "foo");
        assertFalse("highlight present during isearch", canvas.getSearchHighlights().isEmpty());
        sendCode(ed, KeyEvent.VK_ENTER);
        assertTrue("highlight cleared after commit", canvas.getSearchHighlights().isEmpty());
    }

    static void testUnhandledKeyEndsIsearchAndFallsThrough() {
        ModalEditor ed = editor("foo bar foo");
        enterInsert(ed);
        ctrlS(ed);
        type(ed, "bar");
        int matchCol = ed.getCursorCol();
        // Ctrl+B は isearch 専用キー(C-s/C-r/BS/Enter/Esc/印字文字)のいずれでもないので、
        // isearch を終了させてから通常の INSERT 用 Ctrl+B（cursor.left）として処理される
        sendCode(ed, KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK);
        assertFalse("isearch ended by unrelated key", ed.isEmacsIsearchActive());
        assertEq("cursor moved left from match", matchCol - 1, ed.getCursorCol());
    }

    static void testCaseInsensitive() {
        ModalEditor ed = editor("Foo bar FOO");
        enterInsert(ed);
        ctrlS(ed);
        type(ed, "foo");
        assertEq("case-insensitive match", 8, ed.getCursorCol());
    }

    static void testNoMatchShowsStatus() {
        ModalEditor ed = editor("hello");
        enterInsert(ed);
        ctrlS(ed);
        type(ed, "xyz");
        assertTrue("status indicates not found", ed.getStatusMessage().contains("I-search"));
    }

    public static void main(String[] args) {
        // NORMALモード
        testCtrlSStartsIsearchInNormalMode();
        testCtrlRStartsBackwardIsearchInNormalMode();
        testCtrlShiftRStillRedoesInNormalMode();
        testTypingJumpsToNearestForwardMatchInNormalMode();
        testRepeatedCtrlSAdvancesFurtherInNormalMode();
        testBackspaceInNormalMode();
        testEnterCommitsAndReturnsToPlainNormalMode();
        testEscapeCancelsAndRestoresCursorInNormalMode();
        testHighlightClearedAfterCommitInNormalMode();
        testUnhandledKeyEndsIsearchAndFallsThroughInNormalMode();
        testPendingSequenceDiscardedWhenIsearchStarts();

        // INSERTモード
        testCtrlSStartsIsearchInInsertMode();
        testCtrlRStartsBackwardIsearch();
        testTypingJumpsToNearestForwardMatch();
        testTypingJumpsToNearestBackwardMatch();
        testRepeatedCtrlSAdvancesFurther();
        testCtrlRReversesDirectionAfterCtrlS();
        testBackspaceNarrowsQueryAndRejumps();
        testEnterCommitsAndReturnsToPlainInsert();
        testEscapeCancelsAndRestoresCursor();
        testHighlightClearedAfterCommit();
        testUnhandledKeyEndsIsearchAndFallsThrough();
        testCaseInsensitive();
        testNoMatchShowsStatus();

        System.out.println("\n=== EmacsIsearchTest: " + pass + " passed, " + fail + " failed ===");
        if (fail > 0) System.exit(1);
        System.exit(0);   // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
    }
}
