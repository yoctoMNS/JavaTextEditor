package dev.javatexteditor.search;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import dev.javatexteditor.editor.ModalEditor;

/**
 * ProjectSearcher と ModalEditor の :grep コマンド統合テスト。
 */
public class ProjectSearchTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // --- ProjectSearcher 単体テスト ---
        testSearchFindsMatches();
        testSearchNoMatches();
        testSearchRegex();
        testSearchSkipsBinaryFiles();
        testSearchSkipsGitDirectory();
        testSearchRelativePaths();
        testSearchEmptyDirectory();
        testSearchMissingDirectory();
        testSearchMultipleMatchesPerFile();
        testSearchCaseSensitive();

        // --- ModalEditor 統合テスト ---
        testGrepCommandLoadsResults();
        testGrepCommandNoPattern();
        testGrepCommandBadPattern();
        testGrepCommandNoMatches();
        testEnterJumpsToFile();
        testEnterOnHeaderLineDoesNothing();
        testGrepResultsClearedOnFileOpen();

        // --- 追加テスト ---
        testSearchResultToDisplayLine();
        testSearchResultRecord();

        System.out.println("\n=== ProjectSearch: " + passed + "/" + (passed + failed) + " PASS ===");
        if (failed > 0) System.exit(1);
    }

    // -------------------------------------------------------------------------
    // ProjectSearcher 単体テスト
    // -------------------------------------------------------------------------

    static void testSearchFindsMatches() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "hello.txt", "hello world\nfoo bar\nhello again\n");
        var results = new ProjectSearcher().search(dir, "hello");
        assertEquals("match count", 2, results.size());
        assertEquals("first match line", 1, results.get(0).lineNumber());
        assertEquals("second match line", 3, results.get(1).lineNumber());
        assertEquals("first match content", "hello world", results.get(0).lineContent());
        passed("testSearchFindsMatches");
    }

    static void testSearchNoMatches() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "data.txt", "foo\nbar\nbaz\n");
        var results = new ProjectSearcher().search(dir, "xyz");
        assertEquals("no match", 0, results.size());
        passed("testSearchNoMatches");
    }

    static void testSearchRegex() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "code.java", "int x = 42;\nString s = \"hello\";\nlong y = 99;\n");
        var results = new ProjectSearcher().search(dir, "\\d+");
        assertEquals("regex match count", 2, results.size());
        passed("testSearchRegex");
    }

    static void testSearchSkipsBinaryFiles() throws Exception {
        Path dir = createTempDir();
        // NUL バイトを含むバイナリファイル
        byte[] binary = new byte[]{0x48, 0x65, 0x6C, 0x6C, 0x00, 0x6F};
        Files.write(dir.resolve("binary.bin"), binary);
        var results = new ProjectSearcher().search(dir, "Hell");
        assertEquals("binary skipped", 0, results.size());
        passed("testSearchSkipsBinaryFiles");
    }

    static void testSearchSkipsGitDirectory() throws Exception {
        Path dir = createTempDir();
        Path gitDir = dir.resolve(".git");
        Files.createDirectories(gitDir);
        writeFile(gitDir, "COMMIT_EDITMSG", "fix: important commit message\n");
        writeFile(dir, "README.md", "important readme\n");
        var results = new ProjectSearcher().search(dir, "important");
        assertEquals("git dir skipped, only readme", 1, results.size());
        assertEquals("file path", "README.md", results.get(0).filePath());
        passed("testSearchSkipsGitDirectory");
    }

    static void testSearchRelativePaths() throws Exception {
        Path dir = createTempDir();
        Path subdir = dir.resolve("src/main");
        Files.createDirectories(subdir);
        writeFile(subdir, "App.java", "public class App {}\n");
        var results = new ProjectSearcher().search(dir, "App");
        assertEquals("one match", 1, results.size());
        String path = results.get(0).filePath();
        assertTrue("relative path uses /", path.contains("/") || !path.contains("\\"));
        assertTrue("contains subdirs", path.contains("App.java"));
        assertFalse("not absolute", path.startsWith("/"));
        passed("testSearchRelativePaths");
    }

    static void testSearchEmptyDirectory() throws Exception {
        Path dir = createTempDir();
        var results = new ProjectSearcher().search(dir, "anything");
        assertEquals("empty dir", 0, results.size());
        passed("testSearchEmptyDirectory");
    }

    static void testSearchMissingDirectory() {
        Path missing = Path.of("/tmp/does_not_exist_" + System.nanoTime());
        var results = new ProjectSearcher().search(missing, "pattern");
        assertEquals("missing dir returns empty", 0, results.size());
        passed("testSearchMissingDirectory");
    }

    static void testSearchMultipleMatchesPerFile() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "log.txt", "ERROR: disk full\nINFO: ok\nERROR: timeout\n");
        var results = new ProjectSearcher().search(dir, "ERROR");
        assertEquals("two errors", 2, results.size());
        assertEquals("first line", 1, results.get(0).lineNumber());
        assertEquals("third line", 3, results.get(1).lineNumber());
        passed("testSearchMultipleMatchesPerFile");
    }

    static void testSearchCaseSensitive() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "case.txt", "Hello\nhello\nHELLO\n");
        var results = new ProjectSearcher().search(dir, "hello");
        assertEquals("case sensitive: only lowercase", 1, results.size());
        assertEquals("line 2", 2, results.get(0).lineNumber());
        passed("testSearchCaseSensitive");
    }

    // -------------------------------------------------------------------------
    // ModalEditor 統合テスト
    // -------------------------------------------------------------------------

    static void testGrepCommandLoadsResults() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "a.txt", "foo bar\nbaz\nfoo end\n");

        String savedDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());
        try {
            ModalEditor editor = new ModalEditor("");
            // :grep foo
            sendCommand(editor, "grep foo");
            String text = editor.getText();
            assertTrue("header in buffer", text.contains("*grep*"));
            assertTrue("match in buffer", text.contains("a.txt:1:"));
            assertTrue("second match", text.contains("a.txt:3:"));
            String status = editor.getStatusMessage();
            assertTrue("status shows count", status.contains("2"));
            passed("testGrepCommandLoadsResults");
        } finally {
            System.setProperty("user.dir", savedDir);
        }
    }

    static void testGrepCommandNoPattern() {
        ModalEditor editor = new ModalEditor("");
        sendCommand(editor, "grep ");
        assertTrue("error message", editor.getStatusMessage().startsWith("E:"));
        passed("testGrepCommandNoPattern");
    }

    static void testGrepCommandBadPattern() {
        ModalEditor editor = new ModalEditor("");
        sendCommand(editor, "grep [invalid");
        assertTrue("error message", editor.getStatusMessage().startsWith("E: bad pattern"));
        passed("testGrepCommandBadPattern");
    }

    static void testGrepCommandNoMatches() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "empty.txt", "nothing here\n");
        String savedDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());
        try {
            ModalEditor editor = new ModalEditor("");
            sendCommand(editor, "grep zzz_no_match");
            assertTrue("no match message", editor.getStatusMessage().contains("no matches"));
            passed("testGrepCommandNoMatches");
        } finally {
            System.setProperty("user.dir", savedDir);
        }
    }

    static void testEnterJumpsToFile() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "jump.txt", "line one\nfind me here\nline three\n");
        String savedDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());
        try {
            ModalEditor editor = new ModalEditor("");
            sendCommand(editor, "grep find");
            // カーソルは row=0 (ヘッダ)。row=1 が最初の結果
            // j キーで1行下へ
            editor.processKey(KeyEvent.VK_UNDEFINED, 'j', 0);
            // Enter でジャンプ
            editor.processKey(KeyEvent.VK_ENTER, '\n', 0);
            String text = editor.getText();
            assertTrue("jumped to file content", text.contains("find me here"));
            String status = editor.getStatusMessage();
            assertTrue("status shows file", status.contains("jump.txt"));
            assertTrue("status shows line", status.contains("2"));
            passed("testEnterJumpsToFile");
        } finally {
            System.setProperty("user.dir", savedDir);
        }
    }

    static void testEnterOnHeaderLineDoesNothing() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "x.txt", "match here\n");
        String savedDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());
        try {
            ModalEditor editor = new ModalEditor("");
            sendCommand(editor, "grep match");
            // cursorRow=0 (ヘッダ行) で Enter
            editor.processKey(KeyEvent.VK_ENTER, '\n', 0);
            // バッファはまだ grep 結果
            String text = editor.getText();
            assertTrue("still grep buffer", text.contains("*grep*"));
            passed("testEnterOnHeaderLineDoesNothing");
        } finally {
            System.setProperty("user.dir", savedDir);
        }
    }

    static void testGrepResultsClearedOnFileOpen() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "src.txt", "hello\n");
        String savedDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());
        try {
            ModalEditor editor = new ModalEditor("");
            sendCommand(editor, "grep hello");
            assertTrue("grep buffer loaded", editor.getText().contains("*grep*"));
            // :e で別ファイルを開くと grep モードが解除される
            sendCommand(editor, "e " + dir.resolve("src.txt").toAbsolutePath());
            String text = editor.getText();
            assertFalse("grep buffer cleared", text.contains("*grep*"));
            assertTrue("file content loaded", text.contains("hello"));
            passed("testGrepResultsClearedOnFileOpen");
        } finally {
            System.setProperty("user.dir", savedDir);
        }
    }

    // -------------------------------------------------------------------------
    // SearchResult record テスト
    // -------------------------------------------------------------------------

    static void testSearchResultToDisplayLine() {
        SearchResult r = new SearchResult("src/Main.java", 42, "public class Main {");
        assertEquals("display line",
            "src/Main.java:42: public class Main {",
            r.toDisplayLine());
        passed("testSearchResultToDisplayLine");
    }

    static void testSearchResultRecord() {
        SearchResult r = new SearchResult("foo.txt", 7, "bar");
        assertEquals("filePath", "foo.txt", r.filePath());
        assertEquals("lineNumber", 7, r.lineNumber());
        assertEquals("lineContent", "bar", r.lineContent());
        passed("testSearchResultRecord");
    }

    // -------------------------------------------------------------------------
    // ヘルパー
    // -------------------------------------------------------------------------

    private static Path createTempDir() throws IOException {
        return Files.createTempDirectory("jte-search-test-");
    }

    private static void writeFile(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    private static void sendCommand(ModalEditor editor, String cmd) {
        // : を押してコマンドモードへ
        editor.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : cmd.toCharArray()) {
            editor.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        }
        editor.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    private static void assertEquals(String label, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            System.err.println("FAIL [" + label + "]: expected=" + expected + " actual=" + actual);
            failed++;
            throw new AssertionError(label);
        }
    }

    private static void assertTrue(String label, boolean condition) {
        if (!condition) {
            System.err.println("FAIL [" + label + "]");
            failed++;
            throw new AssertionError(label);
        }
    }

    private static void assertFalse(String label, boolean condition) {
        assertTrue(label, !condition);
    }

    private static void passed(String testName) {
        System.out.println("PASS: " + testName);
        passed++;
    }
}
