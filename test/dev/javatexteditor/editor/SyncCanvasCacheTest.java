package dev.javatexteditor.editor;

import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;

/**
 * 軽量化リファクタリング Phase 2（syncCanvas のテキスト再構築キャッシュ）の回帰テスト。
 * テキストが変化しないキー入力（カーソル移動）では buffer.getText() の全文再構築が走らないこと、
 * テキストが変化した場合はキー入力1回につき1度だけ再構築されることを
 * getCanvasTextRebuildCount() で検証する。
 */
public class SyncCanvasCacheTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) {
        testCursorMovementDoesNotRebuild();
        testTypingRebuildsOncePerKey();
        testUndoRebuildsOnce();
        testBufferSwapInvalidatesCache();

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
        System.exit(fail > 0 ? 1 : 0);
    }

    static ModalEditor newEditorWithCanvas(String text) {
        return new ModalEditor(text, new EditorCanvas());
    }

    static void pressChar(ModalEditor ed, char c) {
        ed.processKey(KeyEvent.getExtendedKeyCodeForChar(c), c, 0);
    }

    static void testCursorMovementDoesNotRebuild() {
        System.out.println("[カーソル移動ではテキストを再構築しない]");
        ModalEditor ed = newEditorWithCanvas("line0\nline1\nline2\nline3\nline4");
        pressChar(ed, 'j'); // 初回 syncCanvas でキャッシュ生成
        long base = ed.getCanvasTextRebuildCount();
        for (int i = 0; i < 50; i++) {
            pressChar(ed, 'j');
            pressChar(ed, 'k');
            pressChar(ed, 'l');
            pressChar(ed, 'h');
        }
        check("移動200回で再構築回数が増えない", base, ed.getCanvasTextRebuildCount());
        check("移動後もテキスト不変", "line0\nline1\nline2\nline3\nline4", ed.getText());
    }

    static void testTypingRebuildsOncePerKey() {
        System.out.println("[文字入力はキー1回につき再構築1回]");
        ModalEditor ed = newEditorWithCanvas("abc");
        pressChar(ed, 'i'); // INSERTへ（テキスト不変）
        long base = ed.getCanvasTextRebuildCount();
        pressChar(ed, 'X');
        pressChar(ed, 'Y');
        pressChar(ed, 'Z');
        check("3文字入力で再構築ちょうど3回", base + 3, ed.getCanvasTextRebuildCount());
        check("入力結果", "XYZabc", ed.getText());
    }

    static void testUndoRebuildsOnce() {
        System.out.println("[undo はテキスト変化として1回だけ再構築する]");
        ModalEditor ed = newEditorWithCanvas("abc");
        pressChar(ed, 'i');
        pressChar(ed, 'X');
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        long base = ed.getCanvasTextRebuildCount();
        pressChar(ed, 'u');
        check("undo で再構築1回", base + 1, ed.getCanvasTextRebuildCount());
        check("undo 後のテキスト", "abc", ed.getText());
    }

    static void testBufferSwapInvalidatesCache() {
        System.out.println("[バッファ差し替え（:enew）でキャッシュが自動失効する]");
        ModalEditor ed = newEditorWithCanvas("abc");
        pressChar(ed, 'j'); // キャッシュ生成
        long base = ed.getCanvasTextRebuildCount();
        pressChar(ed, ':');
        pressChar(ed, 'e');
        pressChar(ed, 'n');
        pressChar(ed, 'e');
        pressChar(ed, 'w');
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0); // buffer が新インスタンスに差し替わる
        check("差し替え後に再構築が発生する", true, ed.getCanvasTextRebuildCount() > base);
        check("差し替え後のテキストは空", "", ed.getText());
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
