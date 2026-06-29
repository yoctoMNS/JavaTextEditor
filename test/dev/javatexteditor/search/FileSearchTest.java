package dev.javatexteditor.search;

import dev.javatexteditor.editor.ModalEditor;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ファイル名検索（\f）とファイル内容grep（\g）のテストハーネス。
 */
public class FileSearchTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // FileNameSearcher 単体テスト
        testSearchFindsMatchingFiles();
        testSearchCaseInsensitive();
        testSearchRegexPattern();
        testSearchSkipsGitDir();
        testSearchSkipsBuildDir();
        testSearchEmptyDir();
        testSearchMissingDir();
        testSearchReturnsRelativePaths();
        testSearchNoMatches();
        testSearchSubdirectory();

        // ModalEditor 統合テスト: \f (ファイル名検索)
        testFileNameSearchModeEnter();
        testFileNameSearchBufferInput();
        testFileNameSearchBackspace();
        testFileNameSearchEscCancel();
        testFileNameSearchEnterExecutes();
        testFileNameSearchResultsPopulate();
        testFileNameSearchResultsClearedOnNewSearch();
        testFileNameSearchEnterNoResults();

        // ModalEditor 統合テスト: \g (ファイル内容grep)
        testFileGrepModeEnter();
        testFileGrepBufferInput();
        testFileGrepEscCancel();
        testFileGrepEnterExecutes();

        // モード判定アクセサ
        testIsFileSearchMode();
        testIsFileNameSearch();
        testIsFileGrepSearch();
        testGetFileSearchBuffer();
        testGetFileNameResults();

        System.out.println("\n=== FileSearch: " + passed + "/" + (passed + failed) + " PASS ===");
        if (failed > 0) System.exit(1);
    }

    // -------------------------------------------------------------------------
    // ヘルパー
    // -------------------------------------------------------------------------

    private static void sendChar(ModalEditor ed, char c) {
        ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
    }

    private static void sendCode(ModalEditor ed, int code, int modifiers) {
        ed.processKey(code, KeyEvent.CHAR_UNDEFINED, modifiers);
    }

    private static void sendCode(ModalEditor ed, int code) {
        sendCode(ed, code, 0);
    }

    /** NORMALモードで \f を入力してFILESEARCHモードへ */
    private static void enterFileNameSearch(ModalEditor ed) {
        ed.processKey(KeyEvent.VK_BACK_SLASH, KeyEvent.CHAR_UNDEFINED, 0);
        ed.processKey(KeyEvent.VK_UNDEFINED, 'f', 0);
    }

    /** NORMALモードで \g を入力してFILESEARCHモードへ */
    private static void enterFileGrepSearch(ModalEditor ed) {
        ed.processKey(KeyEvent.VK_BACK_SLASH, KeyEvent.CHAR_UNDEFINED, 0);
        ed.processKey(KeyEvent.VK_UNDEFINED, 'g', 0);
    }

    private static Path createTempDir() throws IOException {
        return Files.createTempDirectory("filesearchtest-");
    }

    private static void writeFile(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    private static Path mkdir(Path parent, String name) throws IOException {
        Path d = parent.resolve(name);
        Files.createDirectories(d);
        return d;
    }

    private static void assertEquals(String label, Object expected, Object actual) {
        if (expected.equals(actual)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL [" + label + "] expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(String label, boolean cond) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL [" + label + "] expected=true actual=false");
        }
    }

    private static void assertFalse(String label, boolean cond) {
        assertTrue(label, !cond);
    }

    private static void passed(String name) {
        // テスト名ベースのパスは各テスト内で assertEquals/assertTrue を呼ぶ
    }

    // -------------------------------------------------------------------------
    // FileNameSearcher 単体テスト
    // -------------------------------------------------------------------------

    static void testSearchFindsMatchingFiles() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "Foo.java", "");
        writeFile(dir, "Bar.java", "");
        writeFile(dir, "Foo.txt", "");
        var results = new FileNameSearcher().search(dir, "Foo");
        assertEquals("testSearchFindsMatchingFiles: count", 2, results.size());
    }

    static void testSearchCaseInsensitive() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "FooBar.java", "");
        var results = new FileNameSearcher().search(dir, "foobar");
        assertEquals("testSearchCaseInsensitive: count", 1, results.size());
    }

    static void testSearchRegexPattern() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "Foo1.java", "");
        writeFile(dir, "Foo2.java", "");
        writeFile(dir, "Bar.java", "");
        var results = new FileNameSearcher().search(dir, "Foo\\d\\.java");
        assertEquals("testSearchRegexPattern: count", 2, results.size());
    }

    static void testSearchSkipsGitDir() throws Exception {
        Path dir = createTempDir();
        Path gitDir = mkdir(dir, ".git");
        writeFile(gitDir, "config", "");
        writeFile(dir, "Main.java", "");
        var results = new FileNameSearcher().search(dir, "config");
        assertEquals("testSearchSkipsGitDir: should not find .git/config", 0, results.size());
    }

    static void testSearchSkipsBuildDir() throws Exception {
        Path dir = createTempDir();
        Path buildDir = mkdir(dir, "build");
        writeFile(buildDir, "Main.class", "");
        writeFile(dir, "Main.java", "");
        var results = new FileNameSearcher().search(dir, "Main");
        assertEquals("testSearchSkipsBuildDir: only Main.java", 1, results.size());
    }

    static void testSearchEmptyDir() throws Exception {
        Path dir = createTempDir();
        var results = new FileNameSearcher().search(dir, ".*");
        assertEquals("testSearchEmptyDir: empty", 0, results.size());
    }

    static void testSearchMissingDir() throws Exception {
        Path dir = Path.of("/nonexistent/path/xyz");
        var results = new FileNameSearcher().search(dir, ".*");
        assertEquals("testSearchMissingDir: empty", 0, results.size());
    }

    static void testSearchReturnsRelativePaths() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "Hello.java", "");
        var results = new FileNameSearcher().search(dir, "Hello");
        assertEquals("testSearchReturnsRelativePaths: relative", Path.of("Hello.java"), results.get(0));
    }

    static void testSearchNoMatches() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "Foo.java", "");
        var results = new FileNameSearcher().search(dir, "ZZZ");
        assertEquals("testSearchNoMatches: count", 0, results.size());
    }

    static void testSearchSubdirectory() throws Exception {
        Path dir = createTempDir();
        Path sub = mkdir(dir, "sub");
        writeFile(sub, "Deep.java", "");
        var results = new FileNameSearcher().search(dir, "Deep");
        assertEquals("testSearchSubdirectory: count", 1, results.size());
        assertEquals("testSearchSubdirectory: path", Path.of("sub/Deep.java"), results.get(0));
    }

    // -------------------------------------------------------------------------
    // ModalEditor 統合テスト: \f
    // -------------------------------------------------------------------------

    static void testFileNameSearchModeEnter() {
        ModalEditor ed = new ModalEditor("hello");
        assertFalse("before: not filesearch", ed.isFileSearchMode());
        enterFileNameSearch(ed);
        assertTrue("after: is filesearch", ed.isFileSearchMode());
        assertTrue("after: is name search", ed.isFileNameSearch());
        assertFalse("after: not grep search", ed.isFileGrepSearch());
    }

    static void testFileNameSearchBufferInput() {
        ModalEditor ed = new ModalEditor("hello");
        enterFileNameSearch(ed);
        sendChar(ed, 'F');
        sendChar(ed, 'o');
        sendChar(ed, 'o');
        assertEquals("testFileNameSearchBufferInput: buffer", "Foo", ed.getFileSearchBuffer());
    }

    static void testFileNameSearchBackspace() {
        ModalEditor ed = new ModalEditor("hello");
        enterFileNameSearch(ed);
        sendChar(ed, 'F');
        sendChar(ed, 'o');
        sendChar(ed, 'o');
        sendCode(ed, KeyEvent.VK_BACK_SPACE);
        assertEquals("testFileNameSearchBackspace: buffer after delete", "Fo", ed.getFileSearchBuffer());
    }

    static void testFileNameSearchEscCancel() {
        ModalEditor ed = new ModalEditor("hello");
        enterFileNameSearch(ed);
        sendChar(ed, 'F');
        sendCode(ed, KeyEvent.VK_ESCAPE);
        assertFalse("testFileNameSearchEscCancel: back to normal", ed.isFileSearchMode());
        assertEquals("testFileNameSearchEscCancel: buffer cleared", "", ed.getFileSearchBuffer());
    }

    static void testFileNameSearchEnterExecutes() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "Alpha.java", "");
        writeFile(dir, "Beta.java", "");
        ModalEditor ed = new ModalEditor("hello");
        ed.setProjectRoot(dir);
        enterFileNameSearch(ed);
        sendChar(ed, 'A');
        sendChar(ed, 'l');
        sendCode(ed, KeyEvent.VK_ENTER);
        assertFalse("testFileNameSearchEnterExecutes: returned to normal", ed.isFileSearchMode());
    }

    static void testFileNameSearchResultsPopulate() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "Alpha.java", "");
        writeFile(dir, "Beta.java", "");
        ModalEditor ed = new ModalEditor("hello");
        ed.setProjectRoot(dir);
        enterFileNameSearch(ed);
        sendChar(ed, 'A');
        sendCode(ed, KeyEvent.VK_ENTER);
        List<String> results = ed.getFileNameResults();
        assertTrue("testFileNameSearchResultsPopulate: results not null", results != null);
        assertTrue("testFileNameSearchResultsPopulate: has match", results.size() >= 1);
        assertTrue("testFileNameSearchResultsPopulate: contains Alpha.java",
                results.stream().anyMatch(r -> r.contains("Alpha.java")));
    }

    static void testFileNameSearchResultsClearedOnNewSearch() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "Alpha.java", "");
        ModalEditor ed = new ModalEditor("hello");
        ed.setProjectRoot(dir);
        // 1回目
        enterFileNameSearch(ed);
        sendChar(ed, 'A');
        sendCode(ed, KeyEvent.VK_ENTER);
        // 2回目（パターン変更）
        enterFileNameSearch(ed);
        sendChar(ed, 'Z');
        sendChar(ed, 'Z');
        sendCode(ed, KeyEvent.VK_ENTER);
        List<String> results = ed.getFileNameResults();
        // ZZ に一致するファイルがないのでリストは空
        assertTrue("testFileNameSearchResultsClearedOnNewSearch: results not null", results != null);
        assertTrue("testFileNameSearchResultsClearedOnNewSearch: empty list", results.isEmpty());
    }

    static void testFileNameSearchEnterNoResults() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "Alpha.java", "");
        ModalEditor ed = new ModalEditor("hello");
        ed.setProjectRoot(dir);
        enterFileNameSearch(ed);
        sendChar(ed, 'Z');
        sendChar(ed, 'Z');
        sendChar(ed, 'Z');
        sendCode(ed, KeyEvent.VK_ENTER);
        assertFalse("testFileNameSearchEnterNoResults: back to normal", ed.isFileSearchMode());
    }

    // -------------------------------------------------------------------------
    // ModalEditor 統合テスト: \g
    // -------------------------------------------------------------------------

    static void testFileGrepModeEnter() {
        ModalEditor ed = new ModalEditor("hello");
        assertFalse("before: not filesearch", ed.isFileSearchMode());
        enterFileGrepSearch(ed);
        assertTrue("after: is filesearch", ed.isFileSearchMode());
        assertFalse("after: not name search", ed.isFileNameSearch());
        assertTrue("after: is grep search", ed.isFileGrepSearch());
    }

    static void testFileGrepBufferInput() {
        ModalEditor ed = new ModalEditor("hello");
        enterFileGrepSearch(ed);
        sendChar(ed, 't');
        sendChar(ed, 'o');
        sendChar(ed, 'd');
        sendChar(ed, 'o');
        assertEquals("testFileGrepBufferInput: buffer", "todo", ed.getFileSearchBuffer());
    }

    static void testFileGrepEscCancel() {
        ModalEditor ed = new ModalEditor("hello");
        enterFileGrepSearch(ed);
        sendChar(ed, 't');
        sendCode(ed, KeyEvent.VK_ESCAPE);
        assertFalse("testFileGrepEscCancel: back to normal", ed.isFileSearchMode());
        assertEquals("testFileGrepEscCancel: buffer cleared", "", ed.getFileSearchBuffer());
    }

    static void testFileGrepEnterExecutes() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "Foo.java", "hello world\nfoo bar\n");
        ModalEditor ed = new ModalEditor("hello");
        ed.setProjectRoot(dir);
        enterFileGrepSearch(ed);
        sendChar(ed, 'h');
        sendChar(ed, 'e');
        sendChar(ed, 'l');
        sendChar(ed, 'l');
        sendChar(ed, 'o');
        sendCode(ed, KeyEvent.VK_ENTER);
        assertFalse("testFileGrepEnterExecutes: back to normal", ed.isFileSearchMode());
    }

    // -------------------------------------------------------------------------
    // アクセサ
    // -------------------------------------------------------------------------

    static void testIsFileSearchMode() {
        ModalEditor ed = new ModalEditor("hello");
        assertFalse("testIsFileSearchMode: initial false", ed.isFileSearchMode());
        enterFileNameSearch(ed);
        assertTrue("testIsFileSearchMode: true after \\f", ed.isFileSearchMode());
    }

    static void testIsFileNameSearch() {
        ModalEditor ed = new ModalEditor("hello");
        enterFileNameSearch(ed);
        assertTrue("testIsFileNameSearch", ed.isFileNameSearch());
        assertFalse("testIsFileNameSearch: not grep", ed.isFileGrepSearch());
    }

    static void testIsFileGrepSearch() {
        ModalEditor ed = new ModalEditor("hello");
        enterFileGrepSearch(ed);
        assertTrue("testIsFileGrepSearch", ed.isFileGrepSearch());
        assertFalse("testIsFileGrepSearch: not name", ed.isFileNameSearch());
    }

    static void testGetFileSearchBuffer() {
        ModalEditor ed = new ModalEditor("hello");
        enterFileNameSearch(ed);
        sendChar(ed, 'x');
        sendChar(ed, 'y');
        assertEquals("testGetFileSearchBuffer", "xy", ed.getFileSearchBuffer());
    }

    static void testGetFileNameResults() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "Result.java", "");
        ModalEditor ed = new ModalEditor("hello");
        ed.setProjectRoot(dir);
        assertTrue("testGetFileNameResults: null before search", ed.getFileNameResults() == null);
        enterFileNameSearch(ed);
        sendChar(ed, 'R');
        sendCode(ed, KeyEvent.VK_ENTER);
        assertTrue("testGetFileNameResults: not null after search", ed.getFileNameResults() != null);
    }
}
