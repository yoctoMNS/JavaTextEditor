package dev.javatexteditor.editor;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IMPORT_SELECT モード（telescope モーダルによる複数 import 候補選択）のテスト。
 *
 * GUI なし（canvas=null）で ModalEditor を生成して動作を検証する。
 * handleAutoImportFromCandidates を使い、実際のコンパイル解析なしにフローをテストする。
 */
public class ImportSelectTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testSingleCandidateAutoApplied();
        testMultiCandidateEntersImportSelectMode();
        testImportSelectEnterAppliesFirst();
        testImportSelectEscapeSkips();
        testCtrlNMovesSelectionDown();
        testCtrlPMovesSelectionUp();
        testDownKeyMovesDown();
        testUpKeyMovesUp();
        testBoundaryAtTop();
        testBoundaryAtBottom();
        testTwoSymbolsProcessedSequentially();
        testOnImportCompleteCalledAfterSkip();
        testOnImportCompleteCalledAfterSelection();
        testMixedSingleAndMultiCandidates();

        System.out.println("\n=== ImportSelectTest: " + passed + "/" + (passed + failed) + " PASS ===");
        if (failed > 0) System.exit(1);
    }

    // ── ヘルパー ──────────────────────────────────────────────────────────────

    record TestContext(ModalEditor editor, List<String> applied) {}

    static TestContext make(Map<String, List<String>> candidates) {
        ModalEditor editor = new ModalEditor("");
        List<String> applied = new ArrayList<>();
        editor.handleAutoImportFromCandidates(candidates, applied::add);
        return new TestContext(editor, applied);
    }

    static Map<String, List<String>> single() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("List", List.of("java.util.List"));
        return m;
    }

    static Map<String, List<String>> multi() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("List", List.of("java.util.List", "java.awt.List"));
        return m;
    }

    static Map<String, List<String>> twoSymbols() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("List", List.of("java.util.List", "java.awt.List"));
        m.put("Map",  List.of("java.util.Map",  "java.util.concurrent.ConcurrentMap"));
        return m;
    }

    static Map<String, List<String>> mixed() {
        // List は1候補（自動挿入）、Map は2候補（モーダル）
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("List", List.of("java.util.List"));
        m.put("Map",  List.of("java.util.Map", "java.util.concurrent.ConcurrentMap"));
        return m;
    }

    static void enter(ModalEditor e) {
        e.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
    }

    static void esc(ModalEditor e) {
        e.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
    }

    static void down(ModalEditor e) {
        e.processKey(KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED, 0);
    }

    static void up(ModalEditor e) {
        e.processKey(KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED, 0);
    }

    static void ctrlN(ModalEditor e) {
        e.processKey(KeyEvent.VK_N, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK);
    }

    static void ctrlP(ModalEditor e) {
        e.processKey(KeyEvent.VK_P, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK);
    }

    // ── テストケース ──────────────────────────────────────────────────────────

    static void testSingleCandidateAutoApplied() {
        TestContext ctx = make(single());
        assertFalse("single: not in IMPORT_SELECT", ctx.editor().isImportSelectMode());
        assertTrue("single: normal mode", ctx.editor().isNormalMode());
        assertEquals("single: 1 import applied", 1, ctx.applied().size());
        assertEquals("single: java.util.List", "java.util.List", ctx.applied().get(0));
    }

    static void testMultiCandidateEntersImportSelectMode() {
        TestContext ctx = make(multi());
        assertTrue("multi: in IMPORT_SELECT", ctx.editor().isImportSelectMode());
        assertFalse("multi: not normal yet", ctx.editor().isNormalMode());
        assertEquals("multi: nothing applied yet", 0, ctx.applied().size());
    }

    static void testImportSelectEnterAppliesFirst() {
        TestContext ctx = make(multi());
        enter(ctx.editor());
        assertFalse("after Enter: not IMPORT_SELECT", ctx.editor().isImportSelectMode());
        assertTrue("after Enter: normal mode", ctx.editor().isNormalMode());
        assertEquals("applied 1 import", 1, ctx.applied().size());
        assertEquals("applied first candidate", "java.util.List", ctx.applied().get(0));
    }

    static void testImportSelectEscapeSkips() {
        TestContext ctx = make(multi());
        esc(ctx.editor());
        assertFalse("after Esc: not IMPORT_SELECT", ctx.editor().isImportSelectMode());
        assertTrue("after Esc: normal mode", ctx.editor().isNormalMode());
        assertEquals("Esc: nothing applied", 0, ctx.applied().size());
    }

    static void testCtrlNMovesSelectionDown() {
        TestContext ctx = make(multi());
        ctrlN(ctx.editor());
        enter(ctx.editor());
        assertEquals("Ctrl+N then Enter: applied index 1", "java.awt.List", ctx.applied().get(0));
    }

    static void testCtrlPMovesSelectionUp() {
        TestContext ctx = make(multi());
        ctrlN(ctx.editor()); // → 1
        ctrlP(ctx.editor()); // → 0
        enter(ctx.editor());
        assertEquals("Ctrl+N then Ctrl+P: back to index 0", "java.util.List", ctx.applied().get(0));
    }

    static void testDownKeyMovesDown() {
        TestContext ctx = make(multi());
        down(ctx.editor());
        enter(ctx.editor());
        assertEquals("DOWN then Enter: index 1", "java.awt.List", ctx.applied().get(0));
    }

    static void testUpKeyMovesUp() {
        TestContext ctx = make(multi());
        down(ctx.editor()); // → 1
        up(ctx.editor());   // → 0
        enter(ctx.editor());
        assertEquals("DOWN then UP then Enter: index 0", "java.util.List", ctx.applied().get(0));
    }

    static void testBoundaryAtTop() {
        TestContext ctx = make(multi());
        up(ctx.editor()); // index 0 → 0 (クランプ)
        enter(ctx.editor());
        assertEquals("UP at top stays at 0", "java.util.List", ctx.applied().get(0));
    }

    static void testBoundaryAtBottom() {
        TestContext ctx = make(multi());
        down(ctx.editor()); // → 1
        down(ctx.editor()); // 1 → 1 (クランプ: 候補は2件)
        enter(ctx.editor());
        assertEquals("DOWN at bottom stays at last", "java.awt.List", ctx.applied().get(0));
    }

    static void testTwoSymbolsProcessedSequentially() {
        TestContext ctx = make(twoSymbols());
        // 1つ目: List
        assertTrue("first symbol: IMPORT_SELECT", ctx.editor().isImportSelectMode());
        enter(ctx.editor()); // List → java.util.List を選択
        // 2つ目: Map
        assertTrue("second symbol: IMPORT_SELECT", ctx.editor().isImportSelectMode());
        enter(ctx.editor()); // Map → java.util.Map を選択
        // 完了
        assertTrue("all done: normal mode", ctx.editor().isNormalMode());
        assertEquals("2 imports applied", 2, ctx.applied().size());
        assertEquals("first: java.util.List", "java.util.List", ctx.applied().get(0));
        assertEquals("second: java.util.Map",  "java.util.Map",  ctx.applied().get(1));
    }

    static void testOnImportCompleteCalledAfterSkip() {
        ModalEditor editor = new ModalEditor("");
        boolean[] called = {false};
        editor.setOnImportComplete(() -> called[0] = true);
        editor.handleAutoImportFromCandidates(multi(), fqn -> {});
        esc(editor); // skip → 全完了
        assertTrue("onImportComplete called after skip", called[0]);
    }

    static void testOnImportCompleteCalledAfterSelection() {
        ModalEditor editor = new ModalEditor("");
        boolean[] called = {false};
        editor.setOnImportComplete(() -> called[0] = true);
        editor.handleAutoImportFromCandidates(multi(), fqn -> {});
        enter(editor); // select → 全完了
        assertTrue("onImportComplete called after selection", called[0]);
    }

    static void testMixedSingleAndMultiCandidates() {
        // List は1候補（自動適用）、Map は2候補（モーダル）
        TestContext ctx = make(mixed());
        // List は即時適用済み
        assertEquals("List auto-applied", 1, ctx.applied().size());
        assertEquals("List fqn", "java.util.List", ctx.applied().get(0));
        // Map のモーダルが開いているはず
        assertTrue("Map: IMPORT_SELECT", ctx.editor().isImportSelectMode());
        enter(ctx.editor()); // Map → java.util.Map を選択
        assertEquals("Map applied", 2, ctx.applied().size());
        assertEquals("Map fqn", "java.util.Map", ctx.applied().get(1));
        assertTrue("done: normal mode", ctx.editor().isNormalMode());
    }

    // ── アサーション ─────────────────────────────────────────────────────────

    static void assertTrue(String name, boolean condition) {
        if (condition) { System.out.println("  PASS " + name); passed++; }
        else           { System.out.println("  FAIL " + name); failed++; }
    }

    static void assertFalse(String name, boolean condition) {
        assertTrue(name, !condition);
    }

    static void assertEquals(String name, int expected, int actual) {
        if (expected == actual) { System.out.println("  PASS " + name); passed++; }
        else { System.out.println("  FAIL " + name + " (expected=" + expected + ", actual=" + actual + ")"); failed++; }
    }

    static void assertEquals(String name, String expected, String actual) {
        if (expected.equals(actual)) { System.out.println("  PASS " + name); passed++; }
        else { System.out.println("  FAIL " + name + " (expected=" + expected + ", actual=" + actual + ")"); failed++; }
    }
}
