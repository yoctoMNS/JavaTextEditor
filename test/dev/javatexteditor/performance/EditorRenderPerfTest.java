package dev.javatexteditor.performance;

import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;

/**
 * 軽量化リファクタリング Phase 2 の性能テスト。
 * 10万行文書でのカーソル移動・文字入力が、キー入力ごとの全文再構築なし
 *（移動時）/1回（編集時）で完了することを実行時間で検証する。
 * Phase 2 以前の実装（1キーごとに getText()×2 + split×2）ではカーソル移動
 * 1000回だけで数十秒規模になり、このテストは完走しない。
 */
public class EditorRenderPerfTest {
    private static int pass = 0;
    private static int total = 0;

    private static final long THRESHOLD_MOVE_1000  = 2000; // 10万行文書で 'j'×1000
    private static final long THRESHOLD_TYPE_100   = 5000; // 10万行文書で100文字入力（1キー1回の全文再構築は許容）

    public static void main(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100_000; i++) sb.append("line").append(i).append("\n");
        String bigText = sb.toString();

        testCursorMove1000(bigText);
        testTyping100(bigText);

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
        System.exit(fail > 0 ? 1 : 0);
    }

    static void testCursorMove1000(String bigText) {
        ModalEditor ed = new ModalEditor(bigText, new EditorCanvas());
        press(ed, 'j'); // 初回キャッシュ生成はウォームアップとして計測外
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) press(ed, 'j');
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[PERF] 10万行でカーソル移動1000回: " + elapsed + "ms (threshold=" + THRESHOLD_MOVE_1000 + "ms)");
        checkPerf("カーソル移動1000回が閾値内", THRESHOLD_MOVE_1000, elapsed);
        check("移動後の再構築回数が1回のまま", true, ed.getCanvasTextRebuildCount() <= 1);
    }

    static void testTyping100(String bigText) {
        ModalEditor ed = new ModalEditor(bigText, new EditorCanvas());
        press(ed, 'i');
        long start = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) press(ed, 'x');
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[PERF] 10万行で100文字入力: " + elapsed + "ms (threshold=" + THRESHOLD_TYPE_100 + "ms)");
        checkPerf("100文字入力が閾値内", THRESHOLD_TYPE_100, elapsed);
        check("入力がテキスト先頭に反映", true, ed.getText().startsWith("x"));
    }

    static void press(ModalEditor ed, char c) {
        ed.processKey(KeyEvent.getExtendedKeyCodeForChar(c), c, 0);
    }

    static void checkPerf(String name, long threshold, long actual) {
        total++;
        boolean ok = actual <= threshold;
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> actual=" + actual + "ms threshold=" + threshold + "ms");
        if (ok) pass++;
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
