package dev.javatexteditor.search;

import dev.javatexteditor.editor.ModalEditor;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * gR / :grep! / \g! / \f!（bang付き検索: デフォルトスキップ対象を無視して全ファイルを検索する）
 * の挙動を検証する（mainメソッド形式のテストハーネス）。
 *
 * ProjectSearcher/FileNameSearcher が node_modules をデフォルトでスキップし、
 * fullScan=true 指定時のみそれを無視することを確認する。
 */
public class BangSearchTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testProjectSearcherSkipsNodeModulesByDefault();
        testProjectSearcherFullScanIncludesNodeModules();
        testFileNameSearcherSkipsNodeModulesByDefault();
        testFileNameSearcherFullScanIncludesNodeModules();
        testGrLowercaseSkipsNodeModules();
        testGrUppercaseIncludesNodeModules();
        testGrepCommandBangIncludesNodeModules();
        testGrepCommandWithoutBangSkipsNodeModules();
        testFileSearchGrepBangIncludesNodeModules();
        testFileSearchNameBangIncludesNodeModules();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void assertTrue(String name, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name);
            fail++;
        }
    }

    /** node_modules/lib.js に needle を1つ、直下の Main.java にも needle を1つ置いたテスト用ディレクトリを作る。 */
    private static Path setupDirWithNodeModules(String prefix) throws IOException {
        Path dir = Files.createTempDirectory(prefix);
        Path nodeModules = dir.resolve("node_modules");
        Files.createDirectories(nodeModules);
        Files.writeString(nodeModules.resolve("lib.js"), "needle\n");
        Files.writeString(dir.resolve("Main.java"), "needle\n");
        return dir;
    }

    static void testProjectSearcherSkipsNodeModulesByDefault() throws IOException {
        Path dir = setupDirWithNodeModules("bang-ps-default");
        List<SearchResult> results = new ProjectSearcher().search(dir, "needle");
        assertTrue("ProjectSearcher: デフォルトは node_modules をスキップする（1件のみヒット）",
            results.size() == 1);
    }

    static void testProjectSearcherFullScanIncludesNodeModules() throws IOException {
        Path dir = setupDirWithNodeModules("bang-ps-fullscan");
        List<SearchResult> results = new ProjectSearcher().search(dir, "needle", true);
        assertTrue("ProjectSearcher: fullScan=true なら node_modules も含めてヒットする（2件）",
            results.size() == 2);
    }

    static void testFileNameSearcherSkipsNodeModulesByDefault() throws IOException {
        Path dir = setupDirWithNodeModules("bang-fns-default");
        List<Path> results = new FileNameSearcher().search(dir, "\\.(js|java)$");
        assertTrue("FileNameSearcher: デフォルトは node_modules をスキップする（Main.javaのみ）",
            results.size() == 1);
    }

    static void testFileNameSearcherFullScanIncludesNodeModules() throws IOException {
        Path dir = setupDirWithNodeModules("bang-fns-fullscan");
        List<Path> results = new FileNameSearcher().search(dir, "\\.(js|java)$", true);
        assertTrue("FileNameSearcher: fullScan=true なら node_modules 内の lib.js も見つかる（2件）",
            results.size() == 2);
    }

    static void testGrLowercaseSkipsNodeModules() throws IOException {
        Path dir = setupDirWithNodeModules("bang-gr-lower");
        ModalEditor ed = new ModalEditor("needle", dir.resolve("Main.java").toString(), null);
        ed.setProjectRoot(dir);
        ed.setCursor(0, 0); // "needle" の上

        ed.processKey(KeyEvent.VK_G, 'g', 0);
        ed.processKey(KeyEvent.VK_R, 'r', 0);

        assertTrue("gr（小文字）: *grep* 疑似バッファに切り替わり node_modules を含まない1件のみ",
            ed.getStatusMessage().contains("1 match"));
    }

    static void testGrUppercaseIncludesNodeModules() throws IOException {
        Path dir = setupDirWithNodeModules("bang-gr-upper");
        ModalEditor ed = new ModalEditor("needle", dir.resolve("Main.java").toString(), null);
        ed.setProjectRoot(dir);
        ed.setCursor(0, 0);

        ed.processKey(KeyEvent.VK_G, 'g', 0);
        ed.processKey(KeyEvent.VK_R, 'R', KeyEvent.SHIFT_DOWN_MASK);

        assertTrue("gR（大文字・bang付き）: node_modules 内も含めて2件ヒットする",
            ed.getStatusMessage().contains("2 match"));
    }

    static void testGrepCommandWithoutBangSkipsNodeModules() throws IOException {
        Path dir = setupDirWithNodeModules("bang-cmd-nobang");
        ModalEditor ed = new ModalEditor("hello", null, null);
        ed.setProjectRoot(dir);

        typeCommand(ed, ":grep needle");

        assertTrue(":grep（bangなし）: node_modules をスキップして1件のみ",
            ed.getStatusMessage().contains("1 match"));
    }

    static void testGrepCommandBangIncludesNodeModules() throws IOException {
        Path dir = setupDirWithNodeModules("bang-cmd-bang");
        ModalEditor ed = new ModalEditor("hello", null, null);
        ed.setProjectRoot(dir);

        typeCommand(ed, ":grep! needle");

        assertTrue(":grep!（bang付き）: node_modules も含めて2件ヒットする",
            ed.getStatusMessage().contains("2 match"));
    }

    static void testFileSearchGrepBangIncludesNodeModules() throws IOException {
        Path dir = setupDirWithNodeModules("bang-fs-grep");
        ModalEditor ed = new ModalEditor("hello", null, null);
        ed.setProjectRoot(dir);

        // \g!needle と入力する（\ -> g で FILESEARCH(GREP) へ入り、"!needle" を打鍵後Enter）
        ed.processKey(KeyEvent.VK_BACK_SLASH, '\\', 0);
        ed.processKey(KeyEvent.VK_G, 'g', 0);
        for (char c : "!needle".toCharArray()) {
            ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        }
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);

        assertTrue("\\g!: bang付きなら node_modules も含めて2件ヒットする",
            ed.getStatusMessage().contains("2 match"));
    }

    static void testFileSearchNameBangIncludesNodeModules() throws IOException {
        Path dir = setupDirWithNodeModules("bang-fs-name");
        ModalEditor ed = new ModalEditor("hello", null, null);
        ed.setProjectRoot(dir);

        // \f!lib と入力する（\ -> f で FILESEARCH(NAME) へ入り、"!lib" を打鍵後Enter）
        ed.processKey(KeyEvent.VK_BACK_SLASH, '\\', 0);
        ed.processKey(KeyEvent.VK_F, 'f', 0);
        for (char c : "!lib".toCharArray()) {
            ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        }
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);

        assertTrue("\\f!: bang付きなら node_modules 内の lib.js も見つかる（1件）",
            ed.getStatusMessage().contains("1 match"));
    }

    /** COMMAND モードへ入り、指定文字列（先頭の ':' 含む）を打鍵して Enter する簡易ヘルパー。 */
    private static void typeCommand(ModalEditor ed, String colonCommand) {
        ed.processKey(0, ':', 0);
        for (char c : colonCommand.substring(1).toCharArray()) {
            ed.processKey(0, c, 0);
        }
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }
}
