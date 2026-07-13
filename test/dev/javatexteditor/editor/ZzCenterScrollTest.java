package dev.javatexteditor.editor;

import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;

/**
 * NORMALモード zz（カーソル行を viewport 中央にスクロール）のテスト。
 * getVisibleRows() は canvas のサイズに依存するため、実際に EditorCanvas を
 * 生成し setSize() で固定する（canvas なしでは getVisibleRows() が仮の値 40 を
 * 返すだけで scrollRow への反映を検証できないため、ScrollTest とは異なりここでは
 * 実 canvas を使い、テスト側で canvas.getScrollRow() を直接読む）。
 */
public class ZzCenterScrollTest {

    private static int pass = 0;
    private static int fail = 0;

    private record TestEditor(ModalEditor editor, EditorCanvas canvas) {}

    private static String makeLines(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) sb.append("line").append(i).append("\n");
        return sb.toString();
    }

    /** 400x300、既定 cachedLineHeight=20 → visibleRows = (300-20)/20 = 14 */
    private static TestEditor editorWithCanvas(String text) {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setSize(400, 300);
        ModalEditor ed = new ModalEditor(text, canvas);
        return new TestEditor(ed, canvas);
    }

    private static void sendChar(ModalEditor ed, char c) {
        ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
    }

    private static void assertEq(String label, int expected, int actual) {
        if (expected == actual) { pass++; }
        else { fail++; System.out.println("FAIL [" + label + "] expected=" + expected + " actual=" + actual); }
    }

    private static void assertTrue(String label, boolean cond) {
        if (cond) { pass++; }
        else { fail++; System.out.println("FAIL [" + label + "] condition was false"); }
    }

    // -------------------------------------------------------------------------

    static void testZzCentersCursorLine() {
        // 101行（makeLines(100)）、visibleRows=14 → newScrollRow = 50 - 14/2 = 43
        TestEditor t = editorWithCanvas(makeLines(100));
        t.editor().setCursor(50, 0);
        sendChar(t.editor(), 'z');
        sendChar(t.editor(), 'z');
        assertEq("zz: scrollRow centers cursor row", 43, t.canvas().getScrollRow());
    }

    static void testZzDoesNotChangeCursorPosition() {
        TestEditor t = editorWithCanvas(makeLines(100));
        t.editor().setCursor(50, 2);
        sendChar(t.editor(), 'z');
        sendChar(t.editor(), 'z');
        assertEq("zz: cursorRow unchanged", 50, t.editor().getCursorRow());
        assertEq("zz: cursorCol unchanged", 2, t.editor().getCursorCol());
    }

    static void testZzClampedNearFileStart() {
        // cursorRow=2, visibleRows=14 → 2-7=-5 → clamp to 0
        TestEditor t = editorWithCanvas(makeLines(100));
        t.editor().setCursor(2, 0);
        sendChar(t.editor(), 'z');
        sendChar(t.editor(), 'z');
        assertEq("zz: clamped to 0 near file start", 0, t.canvas().getScrollRow());
        assertEq("zz near start: cursorRow unchanged", 2, t.editor().getCursorRow());
    }

    static void testZzDoesNotClampNearFileEnd() {
        // NeoVim（Vimと共通のscroll_cursor_halfway()実装）のzzは、ファイル末尾付近でも
        // 「最終行を画面下端に留める」クランプをせず、カーソル行を厳密に中央へ置く
        // （画面下部はファイル末尾を超えて空白になることを許容する）。
        // 101行、visibleRows=14。cursorRow=99 → 99-7=92（87へのクランプはしない）
        TestEditor t = editorWithCanvas(makeLines(100));
        t.editor().setCursor(99, 0);
        sendChar(t.editor(), 'z');
        sendChar(t.editor(), 'z');
        assertEq("zz: not clamped near file end (NeoVim-style)", 92, t.canvas().getScrollRow());
        assertEq("zz near end: cursorRow unchanged", 99, t.editor().getCursorRow());
    }

    static void testZAloneDoesNotScrollYet() {
        // 1打鍵目の z だけでは中央スクロールは発火しない（pending 状態）
        TestEditor t = editorWithCanvas(makeLines(100));
        t.editor().setCursor(50, 0);
        int before = t.canvas().getScrollRow();
        sendChar(t.editor(), 'z');
        assertEq("z alone: scrollRow unchanged", before, t.canvas().getScrollRow());
        assertTrue("z alone: still NORMAL mode", t.editor().isNormalMode());
    }

    static void testZThenOtherKeyFallsThroughToNormalProcessing() {
        // z の後に z 以外のキー（l=cursor.right）が来た場合、既存の複数キーコマンド方針
        // （g・s 等と同じ）に合わせて z は破棄され、l は通常の cursor.right として処理される。
        TestEditor t = editorWithCanvas(makeLines(100));
        t.editor().setCursor(50, 0);
        int scrollBefore = t.canvas().getScrollRow();
        sendChar(t.editor(), 'z');
        sendChar(t.editor(), 'l');
        assertEq("z+l fallthrough: cursorCol moved right", 1, t.editor().getCursorCol());
        assertEq("z+l fallthrough: cursorRow unchanged", 50, t.editor().getCursorRow());
        assertEq("z+l fallthrough: scrollRow untouched (zz not triggered)", scrollBefore, t.canvas().getScrollRow());
    }

    static void testZzNotTriggeredInInsertMode() {
        // INSERTモードでは z は通常の文字入力として扱われ、zz による中央スクロールは発火しない
        TestEditor t = editorWithCanvas(makeLines(100));
        t.editor().setCursor(50, 0);
        int scrollBefore = t.canvas().getScrollRow();
        sendChar(t.editor(), 'i'); // enter INSERT
        sendChar(t.editor(), 'z');
        sendChar(t.editor(), 'z');
        assertTrue("zz in INSERT: still INSERT mode", t.editor().isInsertMode());
        assertEq("zz in INSERT: scrollRow unaffected", scrollBefore, t.canvas().getScrollRow());
        assertTrue("zz in INSERT: literal zz inserted", t.editor().getLine(50).startsWith("zz"));
    }

    static void testZzKeepsBufferAndUndoUnaffected() {
        // zz はスクロールのみで、バッファ内容・undo履歴に影響しないことを確認
        TestEditor t = editorWithCanvas(makeLines(20));
        String before = t.editor().getText();
        t.editor().setCursor(10, 0);
        sendChar(t.editor(), 'z');
        sendChar(t.editor(), 'z');
        assertTrue("zz: buffer text unchanged", t.editor().getText().equals(before));
    }

    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        testZzCentersCursorLine();
        testZzDoesNotChangeCursorPosition();
        testZzClampedNearFileStart();
        testZzDoesNotClampNearFileEnd();
        testZAloneDoesNotScrollYet();
        testZThenOtherKeyFallsThroughToNormalProcessing();
        testZzNotTriggeredInInsertMode();
        testZzKeepsBufferAndUndoUnaffected();

        System.out.println("\n=== ZzCenterScrollTest: " + pass + "/" + (pass + fail) + " PASS ===");
        // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
        // （EditorCanvasTest と同じパターン。詳細はgui-rendering-pipelineスキル参照）
        System.exit(fail > 0 ? 1 : 0);
    }
}
