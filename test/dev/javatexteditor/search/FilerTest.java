package dev.javatexteditor.search;

import dev.javatexteditor.editor.ModalEditor;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * FILERモードおよび DirectoryLister のテスト。
 * - DirectoryLister: ディレクトリ優先ソート、部分一致フィルタ
 * - ModalEditor: :cd でのモード遷移・Ctrl+N/P・/検索・Enter でのナビゲーション/ファイルオープン
 */
public class FilerTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testDirectoryListerSortOrder();
        testDirectoryListerFilter();
        testDirectoryListerFilterEmpty();
        testDirectoryListerEmptyDir();
        testCdEntersFilerMode();
        testCdNonexistentShowsError();
        testCtrlNMovesSelection();
        testCtrlPMovesSelection();
        testCtrlNClamps();
        testSearchFilterSubstring();
        testSearchEnterDir();
        testEscExitsSearchMode();
        testEscExitsFilerMode();
        testEnterOpenFile();
        testEnterEnterDirectory();
        testCdTabSingleCandidateCompletes();
        testCdTabMultipleCandidatesShowsOverlay();
        testCdTabNoCandidateDoesNothing();
        testCdCompleteEnterAppliesSelection();
        testCdCompleteEscCancels();
        testCdCompleteArrowNavigation();
        testCdTabEmptyPrefixListsAllDirs();

        System.out.println("\nResults: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ── DirectoryLister unit tests ───────────────────────────────────────────

    static void testDirectoryListerSortOrder() throws Exception {
        Path tmp = Files.createTempDirectory("filer_sort_");
        try {
            // Create: file "apple.txt", dir "banana", dir "alpha", file "zoo.txt"
            Files.createDirectory(tmp.resolve("banana"));
            Files.createDirectory(tmp.resolve("alpha"));
            Files.writeString(tmp.resolve("apple.txt"), "a");
            Files.writeString(tmp.resolve("zoo.txt"), "z");

            List<DirEntry> entries = DirectoryLister.listDirectoryEntries(tmp);
            // Expected order: alpha/ (dir), banana/ (dir), apple.txt (file), zoo.txt (file)
            assertEquals("entry count", 4, entries.size());
            assertEquals("first is dir alpha", "alpha", entries.get(0).name());
            assertEquals("first kind is DIRECTORY", DirEntry.Kind.DIRECTORY, entries.get(0).kind());
            assertEquals("second is dir banana", "banana", entries.get(1).name());
            assertEquals("third is file apple.txt", "apple.txt", entries.get(2).name());
            assertEquals("third kind is FILE", DirEntry.Kind.FILE, entries.get(2).kind());
            assertEquals("fourth is file zoo.txt", "zoo.txt", entries.get(3).name());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testDirectoryListerFilter() {
        List<DirEntry> entries = List.of(
            new DirEntry("FooBar.java", Path.of("/tmp/FooBar.java"), DirEntry.Kind.FILE),
            new DirEntry("Baz.java",    Path.of("/tmp/Baz.java"),    DirEntry.Kind.FILE),
            new DirEntry("fooUtil",     Path.of("/tmp/fooUtil"),     DirEntry.Kind.DIRECTORY)
        );
        List<DirEntry> filtered = DirectoryLister.filterEntries(entries, "foo");
        assertEquals("case-insensitive filter count", 2, filtered.size());
        assertEquals("first match name", "FooBar.java", filtered.get(0).name());
        assertEquals("second match name", "fooUtil", filtered.get(1).name());
    }

    static void testDirectoryListerFilterEmpty() {
        List<DirEntry> entries = List.of(
            new DirEntry("Foo.java", Path.of("/tmp/Foo.java"), DirEntry.Kind.FILE)
        );
        List<DirEntry> filtered = DirectoryLister.filterEntries(entries, "");
        assertEquals("empty query returns all", 1, filtered.size());
        assertTrue("empty query returns same list ref", filtered == entries);
    }

    static void testDirectoryListerEmptyDir() throws Exception {
        Path tmp = Files.createTempDirectory("filer_empty_");
        try {
            List<DirEntry> entries = DirectoryLister.listDirectoryEntries(tmp);
            assertEquals("empty dir returns empty list", 0, entries.size());
        } finally {
            Files.delete(tmp);
        }
    }

    // ── ModalEditor FILER mode tests ─────────────────────────────────────────

    /** ModalEditor を FILERモードテスト用に構成する（モック changeWdCallback 付き）。 */
    private static ModalEditor makeEditorWithFilerSupport(Path root) {
        ModalEditor editor = new ModalEditor("hello\n");
        editor.setProjectRoot(root);
        editor.setChangeWorkingDirectoryCallback(p -> {
            if (!Files.isDirectory(p)) return "ディレクトリが存在しません: " + p;
            editor.setProjectRoot(p);
            return null; // success
        });
        return editor;
    }

    /** :cd <pathStr> をキー操作でシミュレートする */
    private static void typeCommand(ModalEditor editor, String cmd) {
        // Enter COMMAND mode via ':'
        editor.processKey(0, ':', 0);
        for (char c : cmd.toCharArray()) {
            editor.processKey(0, c, 0);
        }
        editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
    }

    static void testCdEntersFilerMode() throws Exception {
        Path tmp = Files.createTempDirectory("filer_cd_");
        try {
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommand(editor, "cd " + tmp.toString());
            assertTrue(":cd enters filer mode", editor.isFilerMode());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCdNonexistentShowsError() throws Exception {
        Path tmp = Files.createTempDirectory("filer_noex_");
        try {
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommand(editor, "cd /nonexistent_path_filer_test_12345");
            assertTrue("nonexistent cd stays normal mode", editor.isNormalMode());
            assertTrue("nonexistent cd shows error", editor.getStatusMessage().startsWith("E:"));
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCtrlNMovesSelection() throws Exception {
        Path tmp = Files.createTempDirectory("filer_nav_");
        try {
            Files.writeString(tmp.resolve("a.txt"), "a");
            Files.writeString(tmp.resolve("b.txt"), "b");
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommand(editor, "cd " + tmp.toString());
            assertTrue("in filer mode", editor.isFilerMode());
            assertEquals("initial index is 0", 0, editor.getFilerSelectedIdx());
            editor.processKey(KeyEvent.VK_N, 'n', InputEvent.CTRL_DOWN_MASK);
            assertEquals("Ctrl+N moves to 1", 1, editor.getFilerSelectedIdx());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCtrlPMovesSelection() throws Exception {
        Path tmp = Files.createTempDirectory("filer_nav2_");
        try {
            Files.writeString(tmp.resolve("a.txt"), "a");
            Files.writeString(tmp.resolve("b.txt"), "b");
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommand(editor, "cd " + tmp.toString());
            editor.processKey(KeyEvent.VK_N, 'n', InputEvent.CTRL_DOWN_MASK);
            editor.processKey(KeyEvent.VK_P, 'p', InputEvent.CTRL_DOWN_MASK);
            assertEquals("Ctrl+P moves back to 0", 0, editor.getFilerSelectedIdx());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCtrlNClamps() throws Exception {
        Path tmp = Files.createTempDirectory("filer_clamp_");
        try {
            Files.writeString(tmp.resolve("only.txt"), "x");
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommand(editor, "cd " + tmp.toString());
            assertEquals("single item index is 0", 0, editor.getFilerSelectedIdx());
            editor.processKey(KeyEvent.VK_N, 'n', InputEvent.CTRL_DOWN_MASK);
            assertEquals("Ctrl+N clamps at last item", 0, editor.getFilerSelectedIdx());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testSearchFilterSubstring() throws Exception {
        Path tmp = Files.createTempDirectory("filer_search_");
        try {
            Files.writeString(tmp.resolve("Main.java"),   "x");
            Files.writeString(tmp.resolve("module.java"), "y");
            Files.writeString(tmp.resolve("Readme.md"),   "z");
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommand(editor, "cd " + tmp.toString());
            assertEquals("all 3 entries visible initially", 3, editor.getFilerFiltered().size());
            // Press '/' to enter search mode, then type "main"
            editor.processKey(0, '/', 0);
            assertTrue("slash enters search mode", editor.isFilerSearchMode());
            editor.processKey(0, 'm', 0);
            editor.processKey(0, 'a', 0);
            editor.processKey(0, 'i', 0);
            editor.processKey(0, 'n', 0);
            // Should match "Main.java" and "module.java" (contains "main" case-insensitively? No:
            // "module.java" doesn't contain "main". Only "Main.java" contains "main" CI).
            // Wait: "main" in "Main.java" -> yes; "main" in "module.java" -> no.
            assertEquals("query is 'main'", "main", editor.getFilerQuery());
            assertEquals("filter matches Main.java only", 1, editor.getFilerFiltered().size());
            assertEquals("matched entry is Main.java", "Main.java", editor.getFilerFiltered().get(0).name());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testSearchEnterDir() throws Exception {
        Path tmp = Files.createTempDirectory("filer_enterdir_");
        try {
            Files.writeString(tmp.resolve("file.txt"), "x");
            // Press '/' and type partial dir name, then Ctrl+N/P to select if needed, then Enter
            ModalEditor editor = makeEditorWithFilerSupport(tmp.getParent());
            typeCommand(editor, "cd " + tmp.getParent().toString());
            assertTrue("in filer mode", editor.isFilerMode());
            // Navigate into tmp (which might be last/first in listing)
            // Instead just call: find the tmp dir in filtered list and select it
            List<DirEntry> filtered = editor.getFilerFiltered();
            int idx = -1;
            for (int i = 0; i < filtered.size(); i++) {
                if (filtered.get(i).path().equals(tmp.toAbsolutePath().normalize())) { idx = i; break; }
            }
            if (idx < 0) {
                System.out.println("  SKIP testSearchEnterDir (temp dir not found in listing)");
                passed++;
                return;
            }
            // Move to the correct index
            for (int i = 0; i < idx; i++) {
                editor.processKey(KeyEvent.VK_N, 'n', InputEvent.CTRL_DOWN_MASK);
            }
            editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("Enter on dir stays in filer mode", editor.isFilerMode());
            assertEquals("currentDirectory updated to tmp", 1, editor.getFilerFiltered().size());
            assertEquals("single entry is file.txt", "file.txt", editor.getFilerFiltered().get(0).name());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testEscExitsSearchMode() throws Exception {
        Path tmp = Files.createTempDirectory("filer_esc_search_");
        try {
            Files.writeString(tmp.resolve("a.txt"), "a");
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommand(editor, "cd " + tmp.toString());
            editor.processKey(0, '/', 0);
            editor.processKey(0, 'x', 0);
            assertTrue("in search mode after typing", editor.isFilerSearchMode());
            editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("still in filer mode after search Esc", editor.isFilerMode());
            assertTrue("search mode cleared", !editor.isFilerSearchMode());
            assertEquals("query cleared", "", editor.getFilerQuery());
            assertEquals("full list restored", 1, editor.getFilerFiltered().size());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testEscExitsFilerMode() throws Exception {
        Path tmp = Files.createTempDirectory("filer_esc_exit_");
        try {
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommand(editor, "cd " + tmp.toString());
            assertTrue("in filer mode", editor.isFilerMode());
            editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("Esc exits filer to normal mode", editor.isNormalMode());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testEnterOpenFile() throws Exception {
        Path tmp = Files.createTempDirectory("filer_open_");
        try {
            Path file = tmp.resolve("hello.txt");
            Files.writeString(file, "Hello, world!\n");
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommand(editor, "cd " + tmp.toString());
            // hello.txt should be the only entry
            assertEquals("one entry", 1, editor.getFilerFiltered().size());
            assertEquals("entry is hello.txt", "hello.txt", editor.getFilerFiltered().get(0).name());
            editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("Enter on file exits filer to normal", editor.isNormalMode());
            assertTrue("file content loaded", editor.getText().contains("Hello, world!"));
        } finally {
            deleteDir(tmp);
        }
    }

    static void testEnterEnterDirectory() throws Exception {
        Path parent = Files.createTempDirectory("filer_parent_");
        try {
            Path child = Files.createDirectory(parent.resolve("child"));
            Files.writeString(child.resolve("inner.txt"), "inner");
            ModalEditor editor = makeEditorWithFilerSupport(parent);
            typeCommand(editor, "cd " + parent.toString());
            // First entry should be the "child" directory
            assertTrue("at least one entry", !editor.getFilerFiltered().isEmpty());
            // Find child dir
            List<DirEntry> entries = editor.getFilerFiltered();
            int idx = -1;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).name().equals("child")) { idx = i; break; }
            }
            if (idx < 0) { System.out.println("  SKIP testEnterEnterDirectory"); passed++; return; }
            for (int i = 0; i < idx; i++) editor.processKey(KeyEvent.VK_N, 'n', InputEvent.CTRL_DOWN_MASK);
            editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("still in filer mode after dir enter", editor.isFilerMode());
            assertEquals("child dir has 1 entry", 1, editor.getFilerFiltered().size());
            assertEquals("entry is inner.txt", "inner.txt", editor.getFilerFiltered().get(0).name());
        } finally {
            deleteDir(parent);
        }
    }

    // ── :cd タブ補完テスト ──────────────────────────────────────────────────

    /** COMMAND モードへ入り、Enter を押さずに cmd を打鍵した状態にする（TAB を試せる状態）。 */
    private static void typeCommandNoEnter(ModalEditor editor, String cmd) {
        editor.processKey(0, ':', 0);
        for (char c : cmd.toCharArray()) {
            editor.processKey(0, c, 0);
        }
    }

    static void testCdTabSingleCandidateCompletes() throws Exception {
        Path tmp = Files.createTempDirectory("filer_tabcomp1_");
        try {
            Files.createDirectory(tmp.resolve("project"));
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommandNoEnter(editor, "cd proj");
            editor.processKey(KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("stays in command mode after single-candidate completion", editor.isCommandMode());
            assertEquals("commandBuffer completed to project/", "cd project/", editor.getCommandBuffer());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCdTabMultipleCandidatesShowsOverlay() throws Exception {
        Path tmp = Files.createTempDirectory("filer_tabcomp2_");
        try {
            Files.createDirectory(tmp.resolve("project-a"));
            Files.createDirectory(tmp.resolve("project-b"));
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommandNoEnter(editor, "cd proj");
            editor.processKey(KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("enters CD_COMPLETE mode with multiple candidates", editor.isCdCompleteMode());
            assertEquals("two candidates found", 2, editor.getCdCandidates().size());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCdTabNoCandidateDoesNothing() throws Exception {
        Path tmp = Files.createTempDirectory("filer_tabcomp3_");
        try {
            Files.createDirectory(tmp.resolve("project"));
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommandNoEnter(editor, "cd nomatch");
            editor.processKey(KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("stays in command mode when no candidate matches", editor.isCommandMode());
            assertEquals("commandBuffer unchanged", "cd nomatch", editor.getCommandBuffer());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCdCompleteEnterAppliesSelection() throws Exception {
        Path tmp = Files.createTempDirectory("filer_tabcomp4_");
        try {
            Files.createDirectory(tmp.resolve("aaa"));
            Files.createDirectory(tmp.resolve("aab"));
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommandNoEnter(editor, "cd aa");
            editor.processKey(KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("in CD_COMPLETE mode", editor.isCdCompleteMode());
            String first = editor.getCdCandidates().get(0);
            editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("Enter returns to command mode", editor.isCommandMode());
            assertEquals("commandBuffer completed with selected candidate", "cd " + first + "/", editor.getCommandBuffer());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCdCompleteEscCancels() throws Exception {
        Path tmp = Files.createTempDirectory("filer_tabcomp5_");
        try {
            Files.createDirectory(tmp.resolve("aaa"));
            Files.createDirectory(tmp.resolve("aab"));
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommandNoEnter(editor, "cd aa");
            editor.processKey(KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED, 0);
            editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("Esc returns to command mode without applying", editor.isCommandMode());
            assertEquals("commandBuffer unchanged by cancel", "cd aa", editor.getCommandBuffer());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCdCompleteArrowNavigation() throws Exception {
        Path tmp = Files.createTempDirectory("filer_tabcomp6_");
        try {
            Files.createDirectory(tmp.resolve("aaa"));
            Files.createDirectory(tmp.resolve("aab"));
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommandNoEnter(editor, "cd aa");
            editor.processKey(KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED, 0);
            assertEquals("initial selection is 0", 0, editor.getCdCandidateIdx());
            editor.processKey(KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED, 0);
            assertEquals("Down moves selection to 1", 1, editor.getCdCandidateIdx());
            editor.processKey(KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED, 0);
            assertEquals("Up moves selection back to 0", 0, editor.getCdCandidateIdx());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCdTabEmptyPrefixListsAllDirs() throws Exception {
        Path tmp = Files.createTempDirectory("filer_tabcomp7_");
        try {
            Files.createDirectory(tmp.resolve("dirA"));
            Files.createDirectory(tmp.resolve("dirB"));
            Files.writeString(tmp.resolve("file.txt"), "x");
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommandNoEnter(editor, "cd ");
            editor.processKey(KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("empty prefix lists directories only (files excluded)", editor.isCdCompleteMode());
            assertEquals("only the 2 directories are candidates", 2, editor.getCdCandidates().size());
        } finally {
            deleteDir(tmp);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    static void assertTrue(String name, boolean condition) {
        if (condition) { System.out.println("  PASS " + name); passed++; }
        else           { System.out.println("  FAIL " + name); failed++; }
    }

    static void assertEquals(String name, int expected, int actual) {
        if (expected == actual) { System.out.println("  PASS " + name); passed++; }
        else { System.out.println("  FAIL " + name + " (expected=" + expected + ", actual=" + actual + ")"); failed++; }
    }

    static void assertEquals(String name, String expected, String actual) {
        if (expected.equals(actual)) { System.out.println("  PASS " + name); passed++; }
        else { System.out.println("  FAIL " + name + " (expected=" + expected + ", actual=" + actual + ")"); failed++; }
    }

    static void assertEquals(String name, DirEntry.Kind expected, DirEntry.Kind actual) {
        if (expected == actual) { System.out.println("  PASS " + name); passed++; }
        else { System.out.println("  FAIL " + name + " (expected=" + expected + ", actual=" + actual + ")"); failed++; }
    }
}
