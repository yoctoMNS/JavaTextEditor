package dev.javatexteditor.editor;

import dev.javatexteditor.editor.ModalEditor;
import java.awt.event.KeyEvent;

/**
 * ページスクロール・1行スクロール・画面内ジャンプのテスト。
 * canvas なし環境では getVisibleRows() が仮の値 40 を返すため、
 * 大きなバッファ（100行以上）でスクロール量を検証する。
 */
public class ScrollTest {

    private static int pass = 0;
    private static int fail = 0;

    // -------------------------------------------------------------------------
    // ヘルパー
    // -------------------------------------------------------------------------

    /** n 行のテキストを生成する */
    private static String makeLines(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) sb.append("line").append(i).append("\n");
        return sb.toString();
    }

    private static ModalEditor editor(String text) {
        return new ModalEditor(text);
    }

    private static void ctrl(ModalEditor ed, int vk) {
        ed.processKey(vk, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK);
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
    // Ctrl+F / Ctrl+B (1ページスクロール)
    // -------------------------------------------------------------------------

    static void testPageDownMovesCursor() {
        // canvas なし: getVisibleRows()=40、100行ファイルで Ctrl+F するとカーソルが 40 行下がる
        ModalEditor ed = editor(makeLines(100));
        ctrl(ed, KeyEvent.VK_F);
        assertEq("pageDown: cursor moved down by pageSize", 40, ed.getCursorRow());
    }

    static void testPageDownDoesNotExceedLastLine() {
        // makeLines(30) → 31行。Ctrl+F(40行)すると clamp されて最終行(30)になる
        ModalEditor ed = editor(makeLines(30));
        ctrl(ed, KeyEvent.VK_F);
        assertEq("pageDown: clamp to last line", 30, ed.getCursorRow());
    }

    static void testPageUpMovesCursor() {
        ModalEditor ed = editor(makeLines(100));
        // 先にファイル末尾へ
        ctrl(ed, KeyEvent.VK_F);
        ctrl(ed, KeyEvent.VK_F);
        int after2pages = ed.getCursorRow(); // 80
        ctrl(ed, KeyEvent.VK_B);
        assertEq("pageUp: cursor moved up by pageSize", after2pages - 40, ed.getCursorRow());
    }

    static void testPageUpAtTopStaysZero() {
        ModalEditor ed = editor(makeLines(100));
        ctrl(ed, KeyEvent.VK_B);
        assertEq("pageUp: already at top stays 0", 0, ed.getCursorRow());
    }

    static void testMultiplePageDowns() {
        ModalEditor ed = editor(makeLines(200));
        ctrl(ed, KeyEvent.VK_F);
        ctrl(ed, KeyEvent.VK_F);
        assertEq("2x pageDown: cursor row 80", 80, ed.getCursorRow());
    }

    // -------------------------------------------------------------------------
    // Ctrl+D / Ctrl+U (半ページスクロール)
    // -------------------------------------------------------------------------

    static void testHalfPageDown() {
        // getVisibleRows()=40 → 半ページ=20
        ModalEditor ed = editor(makeLines(100));
        ctrl(ed, KeyEvent.VK_D);
        assertEq("halfPageDown: cursor moved by 20", 20, ed.getCursorRow());
    }

    static void testHalfPageUp() {
        ModalEditor ed = editor(makeLines(100));
        ctrl(ed, KeyEvent.VK_D);
        ctrl(ed, KeyEvent.VK_D);
        int row = ed.getCursorRow(); // 40
        ctrl(ed, KeyEvent.VK_U);
        assertEq("halfPageUp: cursor moved up by 20", row - 20, ed.getCursorRow());
    }

    static void testHalfPageDownClamp() {
        // makeLines(10) → 11行（最終行=空行=index 10）
        ModalEditor ed = editor(makeLines(10));
        ctrl(ed, KeyEvent.VK_D);
        assertEq("halfPageDown: clamped to last line", 10, ed.getCursorRow());
    }

    static void testHalfPageUpAtTopStaysZero() {
        ModalEditor ed = editor(makeLines(50));
        ctrl(ed, KeyEvent.VK_U);
        assertEq("halfPageUp: at top stays 0", 0, ed.getCursorRow());
    }

    // -------------------------------------------------------------------------
    // Ctrl+E / Ctrl+Y (1行スクロール、カーソル位置固定)
    // -------------------------------------------------------------------------

    static void testScrollLineDownKeepsCursorRow() {
        // canvas なしでは scrollLines は canvas.setScrollRow() を呼ばないだけで
        // カーソル行は変わらない（クランプもない）
        ModalEditor ed = editor(makeLines(100));
        ed.processKey(KeyEvent.VK_UNDEFINED, 'j', 0); // カーソルを1行目に
        ed.processKey(KeyEvent.VK_UNDEFINED, 'j', 0);
        ed.processKey(KeyEvent.VK_UNDEFINED, 'j', 0); // row=3
        int before = ed.getCursorRow();
        ctrl(ed, KeyEvent.VK_E);
        // canvas なし: canvas == null なので早期 return → カーソル不変
        assertEq("scrollLineDown(no canvas): cursor unchanged", before, ed.getCursorRow());
    }

    static void testScrollLineUpKeepsCursorRow() {
        ModalEditor ed = editor(makeLines(100));
        ed.processKey(KeyEvent.VK_UNDEFINED, 'j', 0);
        ed.processKey(KeyEvent.VK_UNDEFINED, 'j', 0);
        int before = ed.getCursorRow();
        ctrl(ed, KeyEvent.VK_Y);
        assertEq("scrollLineUp(no canvas): cursor unchanged", before, ed.getCursorRow());
    }

    // -------------------------------------------------------------------------
    // H / M / L (画面内ジャンプ)
    // -------------------------------------------------------------------------

    static void testScreenTopJump() {
        // canvas なし: scrollRow=0, visibleRows=40
        // H → row=0
        ModalEditor ed = editor(makeLines(100));
        // まずカーソルを中央付近に移動
        ctrl(ed, KeyEvent.VK_D); // row=20
        sendChar(ed, 'H');
        // scrollRow=0 なので H → cursorRow=0
        assertEq("H: jump to scroll top (row 0)", 0, ed.getCursorRow());
    }

    static void testScreenMiddleJump() {
        // canvas なし: scrollRow=0, visibleRows=40, lastVisible=min(39,99)=39
        // M → (0+39)/2 = 19
        ModalEditor ed = editor(makeLines(100));
        sendChar(ed, 'M');
        assertEq("M: jump to screen middle", 19, ed.getCursorRow());
    }

    static void testScreenBottomJump() {
        // canvas なし: scrollRow=0, visibleRows=40, lastVisible=min(39,99)=39
        // L → 39
        ModalEditor ed = editor(makeLines(100));
        sendChar(ed, 'L');
        assertEq("L: jump to screen bottom", 39, ed.getCursorRow());
    }

    static void testScreenBottomClampedToFileEnd() {
        // makeLines(20) → 21行、lastVisible = min(39,20) = 20
        ModalEditor ed = editor(makeLines(20));
        sendChar(ed, 'L');
        assertEq("L: clamped to file end", 20, ed.getCursorRow());
    }

    static void testScreenTopAfterPageDown() {
        // Ctrl+F で scrollRow が 40 に移動し、H で scrollRow=40 の先頭行へ
        ModalEditor ed = editor(makeLines(100));
        ctrl(ed, KeyEvent.VK_F);   // cursorRow=40
        sendChar(ed, 'H');          // H → cursorRow=scrollRow ... but canvas=null
        // canvas なし: scrollRow=0 のまま（scrollPage が canvas==null なら canvas.setScrollRow() 呼ばない）
        // scrollPage の実装を確認: canvas == null のブランチは cursorRow のみ更新
        // H では scrollRow=canvas.getScrollRow() だが canvas==null → scrollRow=0 → H=0
        assertEq("H after pageDown (no canvas): row 0", 0, ed.getCursorRow());
    }

    static void testScreenMiddleSmallFile() {
        // 5行 → visibleRows=40, lastVisible=4, M=(0+4)/2=2
        ModalEditor ed = editor(makeLines(5));
        sendChar(ed, 'M');
        assertEq("M small file: row 2", 2, ed.getCursorRow());
    }

    // -------------------------------------------------------------------------
    // エッジケース
    // -------------------------------------------------------------------------

    static void testPageDownFromMiddle() {
        ModalEditor ed = editor(makeLines(200));
        ctrl(ed, KeyEvent.VK_D); // row=20
        ctrl(ed, KeyEvent.VK_F); // row=20+40=60
        assertEq("pageDown from middle: row 60", 60, ed.getCursorRow());
    }

    static void testPageDownAtLastPage() {
        // makeLines(50) → 51行、最終行=index 50
        ModalEditor ed = editor(makeLines(50));
        ctrl(ed, KeyEvent.VK_F);
        ctrl(ed, KeyEvent.VK_F);
        assertEq("pageDown at last page: clamped to 50", 50, ed.getCursorRow());
    }

    static void testHalfPageInterleaved() {
        ModalEditor ed = editor(makeLines(100));
        ctrl(ed, KeyEvent.VK_D); // 20
        ctrl(ed, KeyEvent.VK_D); // 40
        ctrl(ed, KeyEvent.VK_U); // 20
        ctrl(ed, KeyEvent.VK_D); // 40
        assertEq("halfPage interleaved: row 40", 40, ed.getCursorRow());
    }

    // -------------------------------------------------------------------------
    // main
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        testPageDownMovesCursor();
        testPageDownDoesNotExceedLastLine();
        testPageUpMovesCursor();
        testPageUpAtTopStaysZero();
        testMultiplePageDowns();
        testHalfPageDown();
        testHalfPageUp();
        testHalfPageDownClamp();
        testHalfPageUpAtTopStaysZero();
        testScrollLineDownKeepsCursorRow();
        testScrollLineUpKeepsCursorRow();
        testScreenTopJump();
        testScreenMiddleJump();
        testScreenBottomJump();
        testScreenBottomClampedToFileEnd();
        testScreenTopAfterPageDown();
        testScreenMiddleSmallFile();
        testPageDownFromMiddle();
        testPageDownAtLastPage();
        testHalfPageInterleaved();

        System.out.println("\n=== ScrollTest: " + pass + "/" + (pass + fail) + " PASS ===");
        if (fail > 0) System.exit(1);
    }
}
