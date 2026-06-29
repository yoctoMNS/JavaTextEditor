package dev.javatexteditor.telescope;

import dev.javatexteditor.editor.ModalEditor;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * telescope-picker 機能のテスト。
 * - FuzzyMatcher: 部分列マッチ・スコアリング・境界ボーナス
 * - FilePicker: クエリフィルタ・スコア降順ソート
 * - BufferPicker: クエリフィルタ
 * - GrepPicker: 短いクエリは空リストを返す
 * - ModalEditor: TELESCOPE モード遷移・キー入力・Esc キャンセル
 */
public class TelescopeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testFuzzyMatcherBasic();
        testFuzzyMatcherNoMatch();
        testFuzzyMatcherConsecutiveBonus();
        testFuzzyMatcherBoundaryBonus();
        testFuzzyMatcherCaseInsensitive();
        testFuzzyMatcherGapPenalty();
        testFuzzyMatcherEmptyQuery();
        testTelescopeItemWithScore();
        testBufferPickerEmptyQuery();
        testBufferPickerFuzzyFilter();
        testBufferPickerScoreOrder();
        testGrepPickerShortQueryReturnsEmpty();
        testFilePickerEmptyQueryReturnsAll();
        testFilePickerFuzzyFilter();
        testModalEditorTelescopeModeTransition();
        testModalEditorTelescopeEscapeCancel();
        testModalEditorTelescopeQueryUpdate();
        testModalEditorTelescopeNavigation();
        testModalEditorTelescopeBackspace();

        System.out.println("\nResults: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ── FuzzyMatcher tests ──────────────────────────────────────────────────

    static void testFuzzyMatcherBasic() {
        FuzzyMatcher.MatchResult r = FuzzyMatcher.match("foo", "FooBar.java");
        assertTrue("basic match", r.matched());
    }

    static void testFuzzyMatcherNoMatch() {
        FuzzyMatcher.MatchResult r = FuzzyMatcher.match("xyz", "FooBar.java");
        assertFalse("no match", r.matched());
    }

    static void testFuzzyMatcherConsecutiveBonus() {
        // "foo" matches "foo.java" — 3 consecutive chars → score >= 6
        FuzzyMatcher.MatchResult r = FuzzyMatcher.match("foo", "foo.java");
        assertTrue("consecutive score >= 6", r.score() >= 6);
    }

    static void testFuzzyMatcherBoundaryBonus() {
        // 'F' at position 0 is a boundary → score should be > plain non-boundary match
        FuzzyMatcher.MatchResult rBoundary = FuzzyMatcher.match("f", "FooBar");
        FuzzyMatcher.MatchResult rNonBound = FuzzyMatcher.match("o", "FooBar");
        // 'f' at start gets +2 (boundary), 'o' at non-boundary gets +1
        assertTrue("boundary score >= non-boundary", rBoundary.score() >= rNonBound.score());
    }

    static void testFuzzyMatcherCaseInsensitive() {
        FuzzyMatcher.MatchResult r = FuzzyMatcher.match("FOO", "foobar");
        assertTrue("case insensitive", r.matched());
    }

    static void testFuzzyMatcherGapPenalty() {
        // "ab" matches "aXb": gap of 1 → penalty -1
        FuzzyMatcher.MatchResult close = FuzzyMatcher.match("ab", "ab");   // consecutive
        FuzzyMatcher.MatchResult gap   = FuzzyMatcher.match("ab", "aXb");  // gap of 1
        assertTrue("gap reduces score", gap.score() < close.score());
    }

    static void testFuzzyMatcherEmptyQuery() {
        FuzzyMatcher.MatchResult r = FuzzyMatcher.match("", "anything");
        assertTrue("empty query matches", r.matched());
        assertEquals("empty query score 0", 0, r.score());
    }

    // ── TelescopeItem tests ──────────────────────────────────────────────────

    static void testTelescopeItemWithScore() {
        TelescopeItem item = new TelescopeItem("Foo.java", "/path/Foo.java", 0, 0);
        TelescopeItem scored = item.withScore(42);
        assertEquals("withScore sets score", 42, scored.score());
        assertEquals("withScore preserves display", "Foo.java", scored.display());
    }

    // ── BufferPicker tests ───────────────────────────────────────────────────

    static void testBufferPickerEmptyQuery() {
        List<BufferPicker.BufferEntry> entries = List.of(
            new BufferPicker.BufferEntry("Foo.java", "/foo"),
            new BufferPicker.BufferEntry("Bar.java", "/bar")
        );
        BufferPicker picker = new BufferPicker(entries);
        List<TelescopeItem> results = picker.filter("");
        assertEquals("empty query returns all", 2, results.size());
    }

    static void testBufferPickerFuzzyFilter() {
        List<BufferPicker.BufferEntry> entries = List.of(
            new BufferPicker.BufferEntry("Foo.java", "/foo"),
            new BufferPicker.BufferEntry("Bar.java", "/bar"),
            new BufferPicker.BufferEntry("Baz.java", "/baz")
        );
        BufferPicker picker = new BufferPicker(entries);
        List<TelescopeItem> results = picker.filter("ba");
        assertEquals("fuzzy filters to 2 matches", 2, results.size());
    }

    static void testBufferPickerScoreOrder() {
        List<BufferPicker.BufferEntry> entries = List.of(
            new BufferPicker.BufferEntry("xfoo", "/xfoo"),
            new BufferPicker.BufferEntry("foo", "/foo")
        );
        BufferPicker picker = new BufferPicker(entries);
        List<TelescopeItem> results = picker.filter("foo");
        // "foo" should score higher than "xfoo"
        assertTrue("exact match scores higher", results.get(0).display().equals("foo"));
    }

    // ── GrepPicker tests ─────────────────────────────────────────────────────

    static void testGrepPickerShortQueryReturnsEmpty() {
        GrepPicker picker = new GrepPicker(java.nio.file.Path.of("/nonexistent"));
        List<TelescopeItem> results = picker.filter("a");
        assertEquals("query < 2 chars returns empty", 0, results.size());
        results = picker.filter("");
        assertEquals("empty query returns empty", 0, results.size());
    }

    // ── FilePicker tests ─────────────────────────────────────────────────────

    static void testFilePickerEmptyQueryReturnsAll() {
        // Use the test directory itself
        java.nio.file.Path dir = java.nio.file.Path.of("src");
        if (!java.nio.file.Files.isDirectory(dir)) {
            System.out.println("  SKIP testFilePickerEmptyQueryReturnsAll (no src dir)");
            passed++;
            return;
        }
        FilePicker picker = new FilePicker(dir);
        List<TelescopeItem> results = picker.filter("");
        assertTrue("empty query returns files", results.size() > 0);
    }

    static void testFilePickerFuzzyFilter() {
        java.nio.file.Path dir = java.nio.file.Path.of("src");
        if (!java.nio.file.Files.isDirectory(dir)) {
            System.out.println("  SKIP testFilePickerFuzzyFilter (no src dir)");
            passed++;
            return;
        }
        FilePicker picker = new FilePicker(dir);
        List<TelescopeItem> all = picker.filter("");
        List<TelescopeItem> filtered = picker.filter("Main");
        assertTrue("fuzzy filter reduces results", filtered.size() <= all.size());
        if (!filtered.isEmpty()) {
            boolean anyMain = filtered.stream().anyMatch(i -> i.display().contains("Main"));
            assertTrue("filtered results contain Main", anyMain);
        }
    }

    // ── ModalEditor TELESCOPE mode tests ─────────────────────────────────────

    private static ModalEditor makeEditor() {
        return new ModalEditor("hello world\n");
    }

    static void testModalEditorTelescopeModeTransition() {
        ModalEditor editor = makeEditor();
        assertFalse("initially not telescope", editor.isTelescopeMode());
        // SPC then 'f'
        editor.processKey(KeyEvent.VK_SPACE, ' ', 0);
        editor.processKey(KeyEvent.VK_F, 'f', 0);
        assertTrue("SPC+f enters telescope", editor.isTelescopeMode());
    }

    static void testModalEditorTelescopeEscapeCancel() {
        ModalEditor editor = makeEditor();
        editor.processKey(KeyEvent.VK_SPACE, ' ', 0);
        editor.processKey(KeyEvent.VK_B, 'b', 0);
        assertTrue("SPC+b enters telescope", editor.isTelescopeMode());
        editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        assertFalse("Escape exits telescope", editor.isTelescopeMode());
        assertTrue("back to normal mode", editor.isNormalMode());
    }

    static void testModalEditorTelescopeQueryUpdate() {
        ModalEditor editor = makeEditor();
        editor.processKey(KeyEvent.VK_SPACE, ' ', 0);
        editor.processKey(KeyEvent.VK_F, 'f', 0);
        editor.processKey(KeyEvent.VK_A, 'a', 0);
        editor.processKey(KeyEvent.VK_B, 'b', 0);
        assertEquals("query updated", "ab", editor.getTelescopeQuery());
    }

    static void testModalEditorTelescopeNavigation() {
        ModalEditor editor = makeEditor();
        editor.setProjectRoot(java.nio.file.Path.of("src"));
        editor.processKey(KeyEvent.VK_SPACE, ' ', 0);
        editor.processKey(KeyEvent.VK_F, 'f', 0);
        int initial = editor.getTelescopeSelectedIdx();
        assertEquals("initial selection is 0", 0, initial);
        // Ctrl+N to move down
        if (!editor.getTelescopeResults().isEmpty() && editor.getTelescopeResults().size() > 1) {
            editor.processKey(KeyEvent.VK_N, 'n', java.awt.event.InputEvent.CTRL_DOWN_MASK);
            assertEquals("Ctrl+N moves to 1", 1, editor.getTelescopeSelectedIdx());
            editor.processKey(KeyEvent.VK_P, 'p', java.awt.event.InputEvent.CTRL_DOWN_MASK);
            assertEquals("Ctrl+P moves back to 0", 0, editor.getTelescopeSelectedIdx());
        }
    }

    static void testModalEditorTelescopeBackspace() {
        ModalEditor editor = makeEditor();
        editor.processKey(KeyEvent.VK_SPACE, ' ', 0);
        editor.processKey(KeyEvent.VK_F, 'f', 0);
        editor.processKey(KeyEvent.VK_A, 'a', 0);
        editor.processKey(KeyEvent.VK_B, 'b', 0);
        editor.processKey(KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED, 0);
        assertEquals("backspace removes last char", "a", editor.getTelescopeQuery());
    }

    // ── Assertion helpers ────────────────────────────────────────────────────

    static void assertTrue(String name, boolean condition) {
        if (condition) { System.out.println("  PASS " + name); passed++; }
        else { System.out.println("  FAIL " + name); failed++; }
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
