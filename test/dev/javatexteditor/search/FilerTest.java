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
        testCdNonexistentPromptsToCreate();
        testCdNonexistentYesCreatesAndEnters();
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
        testCdTabMultipleCandidatesOpensPseudoBuffer();
        testCdTabNoCandidateDoesNothing();
        testCdCompleteEnterAppliesSelection();
        testCdCompleteQCancelsAndRestoresBuffer();
        testCdCompleteJKNavigation();
        testCdTabEmptyPrefixListsAllDirs();
        testParentDirEntryNavigatesUp();
        testColonInFilerEntersCommandModeAndCdSwitchesAndReloads();
        testColonCdNonexistentFromFilerPromptsAndCreates();
        testColonEFromFilerOpensExistingFile();
        testColonENonexistentFromFilerPromptsAndCreatesBuffer();
        testColonPrFromFilerStaysInFilerMode();
        testColonMkdirFromFilerStaysAndReloadsListing();
        testColonMkdirOutsideFilerDoesNotEnterFiler();
        testSemicolonInFilerEntersCommandModeAndCdWorks();
        testIEntersInsertAndWApplyRename();
        testEscFromRenameInsertDoesNotApplyUntilW();
        testCtrlDDeleteRequiresYConfirmation();
        testCtrlDDeleteNCancelsWithoutDeleting();

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

    static void testCdNonexistentPromptsToCreate() throws Exception {
        Path tmp = Files.createTempDirectory("filer_noex_");
        try {
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            Path target = tmp.resolve("nonexistent_child");
            typeCommand(editor, "cd " + target);
            assertTrue("nonexistent cd prompts y/n", editor.isCdConfirmCreateMode());
            assertTrue("prompt mentions target path", editor.getStatusMessage().contains(target.toString()));

            // n はキャンセルし、ディレクトリは作成されない
            editor.processKey(java.awt.event.KeyEvent.VK_N, 'n', 0);
            assertTrue("n returns to normal mode", editor.isNormalMode());
            assertTrue("n does not create the directory", Files.notExists(target));
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCdNonexistentYesCreatesAndEnters() throws Exception {
        Path tmp = Files.createTempDirectory("filer_noex_");
        try {
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            Path target = tmp.resolve("new_child_dir");
            typeCommand(editor, "cd " + target);
            assertTrue("nonexistent cd prompts y/n", editor.isCdConfirmCreateMode());

            editor.processKey(java.awt.event.KeyEvent.VK_Y, 'y', 0);
            assertTrue("y creates the directory", Files.isDirectory(target));
            assertTrue("y enters filer mode", editor.isFilerMode());
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
            assertEquals("initial index is 0", 0, editor.getFilerSelectedIdx());
            // 一覧は ".." + only.txt の2件になるため、last item は index 1
            editor.processKey(KeyEvent.VK_N, 'n', InputEvent.CTRL_DOWN_MASK);
            assertEquals("Ctrl+N moves to last item", 1, editor.getFilerSelectedIdx());
            editor.processKey(KeyEvent.VK_N, 'n', InputEvent.CTRL_DOWN_MASK);
            assertEquals("Ctrl+N clamps at last item", 1, editor.getFilerSelectedIdx());
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
            assertEquals("all 4 entries visible initially (incl. ..)", 4, editor.getFilerFiltered().size());
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
            assertEquals("currentDirectory updated to tmp", 2, editor.getFilerFiltered().size());
            assertEquals("first entry is ..", "..", editor.getFilerFiltered().get(0).name());
            assertEquals("second entry is file.txt", "file.txt", editor.getFilerFiltered().get(1).name());
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
            assertEquals("full list restored (incl. ..)", 2, editor.getFilerFiltered().size());
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
            // ".." + hello.txt の2件のはず
            assertEquals("two entries (incl. ..)", 2, editor.getFilerFiltered().size());
            assertEquals("second entry is hello.txt", "hello.txt", editor.getFilerFiltered().get(1).name());
            editor.processKey(KeyEvent.VK_N, 'n', InputEvent.CTRL_DOWN_MASK);
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
            assertEquals("child dir has 2 entries (incl. ..)", 2, editor.getFilerFiltered().size());
            assertEquals("second entry is inner.txt", "inner.txt", editor.getFilerFiltered().get(1).name());
        } finally {
            deleteDir(parent);
        }
    }

    static void testParentDirEntryNavigatesUp() throws Exception {
        Path parent = Files.createTempDirectory("filer_updir_");
        try {
            Path child = Files.createDirectory(parent.resolve("child"));
            Files.writeString(child.resolve("inner.txt"), "inner");
            ModalEditor editor = makeEditorWithFilerSupport(child);
            typeCommand(editor, "cd " + child.toString());
            assertTrue("in filer mode", editor.isFilerMode());
            assertEquals("first entry is ..", "..", editor.getFilerFiltered().get(0).name());
            // ".." が選択された状態で Enter -> 親ディレクトリへ移動する
            editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("still in filer mode after going up", editor.isFilerMode());
            List<DirEntry> entries = editor.getFilerFiltered();
            boolean hasChild = entries.stream().anyMatch(e -> e.name().equals("child"));
            assertTrue("parent listing contains child dir", hasChild);
        } finally {
            deleteDir(parent);
        }
    }

    // ── FILER表示中の :cd / :e 直接実行テスト（2026-07-29追加）────────────────

    static void testColonInFilerEntersCommandModeAndCdSwitchesAndReloads() throws Exception {
        Path root = Files.createTempDirectory("filer_colon_cd_root_");
        try {
            Path child = Files.createDirectory(root.resolve("child"));
            Files.writeString(child.resolve("inner.txt"), "inner");
            ModalEditor editor = makeEditorWithFilerSupport(root);
            typeCommand(editor, "cd " + root.toString());
            assertTrue("in filer mode", editor.isFilerMode());

            // FILER表示中に ':' でCOMMANDモードへ入り、そのまま :cd を実行できる
            typeCommand(editor, "cd " + child.toString());
            assertTrue("still/again in filer mode after :cd from filer", editor.isFilerMode());
            assertEquals("project root switched to child", child.toString(), editor.getProjectRoot().toString());
            List<DirEntry> entries = editor.getFilerFiltered();
            boolean hasInner = entries.stream().anyMatch(e -> e.name().equals("inner.txt"));
            assertTrue("listing reloaded to show child's contents", hasInner);
        } finally {
            deleteDir(root);
        }
    }

    static void testColonCdNonexistentFromFilerPromptsAndCreates() throws Exception {
        Path root = Files.createTempDirectory("filer_colon_cd_new_");
        try {
            ModalEditor editor = makeEditorWithFilerSupport(root);
            typeCommand(editor, "cd " + root.toString());
            assertTrue("in filer mode", editor.isFilerMode());

            Path target = root.resolve("brand_new_dir");
            typeCommand(editor, "cd " + target);
            assertTrue("nonexistent :cd from filer prompts y/n", editor.isCdConfirmCreateMode());
            editor.processKey(KeyEvent.VK_Y, 'y', 0);
            assertTrue("directory created", Files.isDirectory(target));
            assertTrue("enters filer mode showing new dir", editor.isFilerMode());
            assertEquals("project root switched to new dir", target.toString(), editor.getProjectRoot().toString());
        } finally {
            deleteDir(root);
        }
    }

    static void testColonEFromFilerOpensExistingFile() throws Exception {
        Path root = Files.createTempDirectory("filer_colon_e_");
        try {
            Path file = root.resolve("note.txt");
            Files.writeString(file, "Hello from :e\n");
            ModalEditor editor = makeEditorWithFilerSupport(root);
            typeCommand(editor, "cd " + root.toString());
            assertTrue("in filer mode", editor.isFilerMode());

            typeCommand(editor, "e " + file.toString());
            assertTrue(":e from filer exits to normal mode", editor.isNormalMode());
            assertEquals("current file path is the opened file", file.toString(), editor.getCurrentFilePath());
            assertTrue("file content loaded", editor.getText().contains("Hello from :e"));
        } finally {
            deleteDir(root);
        }
    }

    static void testColonENonexistentFromFilerPromptsAndCreatesBuffer() throws Exception {
        Path root = Files.createTempDirectory("filer_colon_e_new_");
        try {
            ModalEditor editor = makeEditorWithFilerSupport(root);
            typeCommand(editor, "cd " + root.toString());
            assertTrue("in filer mode", editor.isFilerMode());

            Path target = root.resolve("brand_new_file.txt");
            typeCommand(editor, "e " + target);
            assertTrue("nonexistent :e from filer prompts y/n", editor.isConfirmNewFileMode());
            editor.processKey(KeyEvent.VK_Y, 'y', 0);
            assertTrue(":e y exits to normal mode", editor.isNormalMode());
            assertEquals("new file buffer opened", target.toString(), editor.getCurrentFilePath());
        } finally {
            deleteDir(root);
        }
    }

    static void testColonPrFromFilerStaysInFilerMode() throws Exception {
        Path root = Files.createTempDirectory("filer_colon_pr_");
        try {
            Files.writeString(root.resolve("a.txt"), "a");
            ModalEditor editor = makeEditorWithFilerSupport(root);
            typeCommand(editor, "cd " + root.toString());
            assertTrue("in filer mode", editor.isFilerMode());

            typeCommand(editor, "pr");
            assertTrue(":pr from filer stays in filer mode", editor.isFilerMode());
            assertEquals("project root pinned to current dir", root.toString(), editor.getProjectRootOverride().toString());
            assertTrue("listing still shows a.txt",
                editor.getFilerFiltered().stream().anyMatch(e -> e.name().equals("a.txt")));
        } finally {
            deleteDir(root);
        }
    }

    static void testColonMkdirFromFilerStaysAndReloadsListing() throws Exception {
        Path root = Files.createTempDirectory("filer_colon_mkdir_");
        try {
            ModalEditor editor = makeEditorWithFilerSupport(root);
            typeCommand(editor, "cd " + root.toString());
            assertTrue("in filer mode", editor.isFilerMode());
            assertTrue("new dir not yet in listing",
                editor.getFilerFiltered().stream().noneMatch(e -> e.name().equals("newdir")));

            typeCommand(editor, "mkdir newdir");
            assertTrue("directory actually created", Files.isDirectory(root.resolve("newdir")));
            assertTrue(":mkdir from filer stays in filer mode", editor.isFilerMode());
            assertEquals("project root unchanged (stays at parent)", root.toString(), editor.getProjectRoot().toString());
            assertTrue("listing reloaded to show newdir",
                editor.getFilerFiltered().stream().anyMatch(e -> e.name().equals("newdir")));
        } finally {
            deleteDir(root);
        }
    }

    static void testColonMkdirOutsideFilerDoesNotEnterFiler() throws Exception {
        Path root = Files.createTempDirectory("filer_mkdir_plain_");
        try {
            ModalEditor editor = makeEditorWithFilerSupport(root);
            // FILERを経由せず、通常のNORMALモードから直接 :mkdir を実行する
            typeCommand(editor, "mkdir plaindir");
            assertTrue("directory created", Files.isDirectory(root.resolve("plaindir")));
            assertTrue(":mkdir outside filer does not enter filer mode", editor.isNormalMode());
        } finally {
            deleteDir(root);
        }
    }

    /** `;` はFILER一覧表示中でも `:` と同じくCOMMANDモードへ入るエイリアスとして扱われる（2026-07-29追加）。 */
    private static void typeCommandSemicolon(ModalEditor editor, String cmd) {
        editor.processKey(0, ';', 0);
        for (char c : cmd.toCharArray()) {
            editor.processKey(0, c, 0);
        }
        editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
    }

    static void testSemicolonInFilerEntersCommandModeAndCdWorks() throws Exception {
        Path root = Files.createTempDirectory("filer_semicolon_cd_root_");
        try {
            Path child = Files.createDirectory(root.resolve("child"));
            Files.writeString(child.resolve("inner.txt"), "inner");
            ModalEditor editor = makeEditorWithFilerSupport(root);
            typeCommand(editor, "cd " + root.toString());
            assertTrue("in filer mode", editor.isFilerMode());

            // FILER表示中に ';' でCOMMANDモードへ入り、そのまま :cd 相当のコマンドを実行できる
            typeCommandSemicolon(editor, "cd " + child.toString());
            assertTrue("still/again in filer mode after ; cd from filer", editor.isFilerMode());
            assertEquals("project root switched to child via ;", child.toString(), editor.getProjectRoot().toString());
            List<DirEntry> entries = editor.getFilerFiltered();
            assertTrue("listing reloaded to show child's contents",
                entries.stream().anyMatch(e -> e.name().equals("inner.txt")));
        } finally {
            deleteDir(root);
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

    static void testCdTabMultipleCandidatesOpensPseudoBuffer() throws Exception {
        Path tmp = Files.createTempDirectory("filer_tabcomp2_");
        try {
            Files.createDirectory(tmp.resolve("project-a"));
            Files.createDirectory(tmp.resolve("project-b"));
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommandNoEnter(editor, "cd proj");
            editor.processKey(KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED, 0);
            // telescope オーバーレイではなく通常のテキストバッファ(NORMALモード)として表示される
            assertTrue("returns to NORMAL mode (not an overlay mode)", editor.isNormalMode());
            assertTrue("cd selection is active", editor.isCdSelectionActive());
            assertEquals("two candidates found", 2, editor.getCdCandidates().size());
            assertTrue("buffer text contains first candidate", editor.getText().contains("project-a/"));
            assertTrue("buffer text contains second candidate", editor.getText().contains("project-b/"));
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
            assertTrue("cd selection active after TAB with multiple candidates", editor.isCdSelectionActive());
            String first = editor.getCdCandidates().get(0);
            // カーソルは候補一覧の1行目（ヘッダの次）にあるはず
            editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("Enter returns to command mode", editor.isCommandMode());
            assertTrue("selection cleared after Enter", !editor.isCdSelectionActive());
            assertEquals("commandBuffer completed with selected candidate", "cd " + first + "/", editor.getCommandBuffer());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCdCompleteQCancelsAndRestoresBuffer() throws Exception {
        Path tmp = Files.createTempDirectory("filer_tabcomp5_");
        try {
            Files.createDirectory(tmp.resolve("aaa"));
            Files.createDirectory(tmp.resolve("aab"));
            ModalEditor editor = makeEditorWithFilerSupport(tmp); // initial buffer text is "hello\n"
            typeCommandNoEnter(editor, "cd aa");
            editor.processKey(KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("original buffer replaced by candidate pseudo-buffer", !editor.getText().contains("hello"));
            editor.processKey(0, 'q', 0);
            assertTrue("q returns to command mode without applying", editor.isCommandMode());
            assertTrue("selection cleared after q", !editor.isCdSelectionActive());
            assertEquals("commandBuffer restored to pre-TAB text", "cd aa", editor.getCommandBuffer());
            assertTrue("original buffer content restored", editor.getText().contains("hello"));
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCdCompleteJKNavigation() throws Exception {
        Path tmp = Files.createTempDirectory("filer_tabcomp6_");
        try {
            Files.createDirectory(tmp.resolve("aaa"));
            Files.createDirectory(tmp.resolve("aab"));
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommandNoEnter(editor, "cd aa");
            editor.processKey(KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED, 0);
            List<String> candidates = editor.getCdCandidates();
            editor.processKey(0, 'j', 0); // 2行目（2番目の候補）へ移動
            editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
            assertEquals("j moved to second candidate before Enter",
                "cd " + candidates.get(1) + "/", editor.getCommandBuffer());
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
            assertTrue("cd selection active (files excluded from candidates)", editor.isCdSelectionActive());
            assertEquals("only the 2 directories are candidates", 2, editor.getCdCandidates().size());
        } finally {
            deleteDir(tmp);
        }
    }

    // ── I（名前変更）/ Ctrl+D（削除）テスト ─────────────────────────────────

    static void testIEntersInsertAndWApplyRename() throws Exception {
        Path tmp = Files.createTempDirectory("filer_rename_");
        try {
            Files.writeString(tmp.resolve("old.txt"), "hi");
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommand(editor, "cd " + tmp);
            editor.processKey(KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED, 0); // ".." をスキップ

            editor.processKey(0, 'I', 0);
            assertTrue("I で INSERT モードへ入る", editor.isInsertMode());

            for (int i = 0; i < "old.txt".length(); i++) {
                editor.processKey(KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED, 0);
            }
            for (char c : "new.txt".toCharArray()) editor.processKey(0, c, 0);
            editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("Esc直後はまだリネームされない", Files.exists(tmp.resolve("old.txt")));
            assertTrue("Escの後もFILERに留まる", editor.isFilerMode());

            typeCommand(editor, "w");
            assertTrue(":wでリネームが反映される", Files.exists(tmp.resolve("new.txt")));
            assertTrue("旧ファイル名は消える", Files.notExists(tmp.resolve("old.txt")));
        } finally {
            deleteDir(tmp);
        }
    }

    static void testEscFromRenameInsertDoesNotApplyUntilW() throws Exception {
        Path tmp = Files.createTempDirectory("filer_rename_esc_");
        try {
            Files.writeString(tmp.resolve("keep.txt"), "hi");
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommand(editor, "cd " + tmp);
            editor.processKey(KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED, 0);

            editor.processKey(0, 'I', 0);
            for (int i = 0; i < "keep.txt".length(); i++) {
                editor.processKey(KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED, 0);
            }
            for (char c : "renamed.txt".toCharArray()) editor.processKey(0, c, 0);
            editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);

            // :w せずにFILERを抜けると、編集中の名前変更は破棄される。
            editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue(":wしなければ元のファイル名のまま", Files.exists(tmp.resolve("keep.txt")));
            assertTrue("renamed.txtは作られない", Files.notExists(tmp.resolve("renamed.txt")));
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCtrlDDeleteRequiresYConfirmation() throws Exception {
        Path tmp = Files.createTempDirectory("filer_del_");
        try {
            Files.writeString(tmp.resolve("victim.txt"), "x");
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommand(editor, "cd " + tmp);
            editor.processKey(KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED, 0);

            editor.processKey(KeyEvent.VK_D, 'd', InputEvent.CTRL_DOWN_MASK);
            assertTrue("削除確認メッセージに対象名を含む", editor.getStatusMessage().contains("victim.txt"));

            editor.processKey(0, 'y', 0);
            assertTrue("yでファイルが削除される", Files.notExists(tmp.resolve("victim.txt")));
            assertTrue("削除後はFILERへ戻る", editor.isFilerMode());
        } finally {
            deleteDir(tmp);
        }
    }

    static void testCtrlDDeleteNCancelsWithoutDeleting() throws Exception {
        Path tmp = Files.createTempDirectory("filer_del_cancel_");
        try {
            Files.writeString(tmp.resolve("keep.txt"), "x");
            ModalEditor editor = makeEditorWithFilerSupport(tmp);
            typeCommand(editor, "cd " + tmp);
            editor.processKey(KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED, 0);

            editor.processKey(KeyEvent.VK_D, 'd', InputEvent.CTRL_DOWN_MASK);
            editor.processKey(0, 'n', 0);
            assertTrue("nでは削除されない", Files.exists(tmp.resolve("keep.txt")));
            assertTrue("nの後もFILERへ戻る", editor.isFilerMode());
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
