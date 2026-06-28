package dev.javatexteditor.refactor;

import dev.javatexteditor.editor.ModalEditor;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * RenameRefactorer と ModalEditor の :rename コマンド統合テスト。
 */
public class MultiFileRefactoringTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // --- RenameRefactorer 単体テスト ---
        testRenameBasic();
        testRenameMultipleFiles();
        testRenameWordBoundary();
        testRenameNoMatches();
        testRenamePreservesLineEndings();
        testRenameMultipleOccurrencesPerLine();
        testRenameBlankOldNameThrows();
        testRenameBlankNewNameThrows();
        testRenameNonExistentDirectory();
        testRenameResultRecord();
        testRenameResultToDisplayLine();
        testRenameResultErrorDisplayLine();
        testBuildDisplayText();
        testBuildDisplayTextWithErrors();

        // --- ModalEditor 統合テスト ---
        testRenameCommandBasic();
        testRenameCommandNoArgs();
        testRenameCommandOnlyOneArg();
        testRenameCommandNoMatches();
        testRenameCommandDisplayBuffer();

        System.out.println("\n=== MultiFileRefactoring: " + passed + "/" + (passed + failed) + " PASS ===");
        if (failed > 0) System.exit(1);
    }

    // =========================================================================
    // RenameRefactorer 単体テスト
    // =========================================================================

    static void testRenameBasic() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "A.java", "public class Foo {\n    void Foo() {}\n}\n");
        var refactorer = new RenameRefactorer();
        List<RenameResult> results = refactorer.rename(dir, "Foo", "Bar");
        assertEquals("file count", 1, results.size());
        assertEquals("replacement count", 2, results.get(0).replacementCount());
        assertTrue("success", results.get(0).success());
        String content = readFile(dir, "A.java");
        assertEquals("renamed content", "public class Bar {\n    void Bar() {}\n}\n", content);
        passed("testRenameBasic");
    }

    static void testRenameMultipleFiles() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "Main.java", "import com.example.MyClass;\npublic class Main { MyClass x; }\n");
        writeFile(dir, "Other.java", "// uses MyClass\nMyClass obj = new MyClass();\n");
        var refactorer = new RenameRefactorer();
        List<RenameResult> results = refactorer.rename(dir, "MyClass", "RenamedClass");
        assertEquals("file count", 2, results.size());
        String main = readFile(dir, "Main.java");
        assertTrue("Main.java renamed", main.contains("RenamedClass") && !main.contains("MyClass"));
        String other = readFile(dir, "Other.java");
        assertTrue("Other.java renamed", other.contains("RenamedClass") && !other.contains("MyClass"));
        passed("testRenameMultipleFiles");
    }

    static void testRenameWordBoundary() throws Exception {
        Path dir = createTempDir();
        // "Foo" だけを置換し "FooBar" や "aFoo" は変えない
        writeFile(dir, "test.java", "Foo FooBar aFoo foo\n");
        var refactorer = new RenameRefactorer();
        List<RenameResult> results = refactorer.rename(dir, "Foo", "Baz");
        assertEquals("file count", 1, results.size());
        assertEquals("only word-boundary match", 1, results.get(0).replacementCount());
        String content = readFile(dir, "test.java");
        assertEquals("word boundary", "Baz FooBar aFoo foo\n", content);
        passed("testRenameWordBoundary");
    }

    static void testRenameNoMatches() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "empty.java", "public class Empty {}\n");
        var refactorer = new RenameRefactorer();
        List<RenameResult> results = refactorer.rename(dir, "NonExistent", "Whatever");
        assertEquals("no matches → empty list", 0, results.size());
        passed("testRenameNoMatches");
    }

    static void testRenamePreservesLineEndings() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "lines.java", "line1\nline2 Foo\nline3\n");
        var refactorer = new RenameRefactorer();
        refactorer.rename(dir, "Foo", "Bar");
        String content = readFile(dir, "lines.java");
        assertEquals("line endings preserved", "line1\nline2 Bar\nline3\n", content);
        passed("testRenamePreservesLineEndings");
    }

    static void testRenameMultipleOccurrencesPerLine() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "multi.java", "Foo(Foo, Foo)\n");
        var refactorer = new RenameRefactorer();
        List<RenameResult> results = refactorer.rename(dir, "Foo", "Bar");
        assertEquals("3 occurrences", 3, results.get(0).replacementCount());
        String content = readFile(dir, "multi.java");
        assertEquals("all replaced", "Bar(Bar, Bar)\n", content);
        passed("testRenameMultipleOccurrencesPerLine");
    }

    static void testRenameBlankOldNameThrows() throws Exception {
        Path dir = createTempDir();
        var refactorer = new RenameRefactorer();
        try {
            refactorer.rename(dir, "", "Bar");
            failed("testRenameBlankOldNameThrows: expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            passed("testRenameBlankOldNameThrows");
        }
    }

    static void testRenameBlankNewNameThrows() throws Exception {
        Path dir = createTempDir();
        var refactorer = new RenameRefactorer();
        try {
            refactorer.rename(dir, "Foo", "  ");
            failed("testRenameBlankNewNameThrows: expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            passed("testRenameBlankNewNameThrows");
        }
    }

    static void testRenameNonExistentDirectory() throws Exception {
        Path dir = Path.of("/tmp/__does_not_exist_xyz__");
        var refactorer = new RenameRefactorer();
        List<RenameResult> results = refactorer.rename(dir, "Foo", "Bar");
        assertEquals("non-existent dir → empty", 0, results.size());
        passed("testRenameNonExistentDirectory");
    }

    static void testRenameResultRecord() {
        var r = new RenameResult("path/Foo.java", 3, true, null);
        assertEquals("filePath", "path/Foo.java", r.filePath());
        assertEquals("replacementCount", 3, r.replacementCount());
        assertTrue("success", r.success());
        passed("testRenameResultRecord");
    }

    static void testRenameResultToDisplayLine() {
        var r = new RenameResult("src/Main.java", 5, true, null);
        assertEquals("display line", "src/Main.java: 5 replacement(s)", r.toDisplayLine());
        passed("testRenameResultToDisplayLine");
    }

    static void testRenameResultErrorDisplayLine() {
        var r = new RenameResult("src/Broken.java", 0, false, "permission denied");
        String line = r.toDisplayLine();
        assertTrue("error display", line.contains("ERROR") && line.contains("permission denied"));
        passed("testRenameResultErrorDisplayLine");
    }

    static void testBuildDisplayText() {
        List<RenameResult> results = List.of(
            new RenameResult("a/Foo.java", 2, true, null),
            new RenameResult("b/Foo.java", 1, true, null)
        );
        String text = RenameRefactorer.buildDisplayText("Foo", "Bar", results);
        assertTrue("header present", text.startsWith("*rename*"));
        assertTrue("oldName in header", text.contains("Foo"));
        assertTrue("newName in header", text.contains("Bar"));
        assertTrue("total replacements", text.contains("3 replacement(s)"));
        assertTrue("file count", text.contains("2 file(s)"));
        passed("testBuildDisplayText");
    }

    static void testBuildDisplayTextWithErrors() {
        List<RenameResult> results = List.of(
            new RenameResult("ok.java", 1, true, null),
            new RenameResult("bad.java", 0, false, "write failed")
        );
        String text = RenameRefactorer.buildDisplayText("X", "Y", results);
        assertTrue("error count in header", text.contains("1 error(s)"));
        passed("testBuildDisplayTextWithErrors");
    }

    // =========================================================================
    // ModalEditor 統合テスト
    // =========================================================================

    static void testRenameCommandBasic() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "Hello.java", "class Hello {}\n");
        String origUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());
        try {
            ModalEditor editor = new ModalEditor("// current file");
            sendCommand(editor, "rename Hello World");
            String status = editor.getStatusMessage();
            assertTrue("status contains replacements", status.contains("replacement(s)"));
            String bufText = editor.getText();
            assertTrue("buffer shows rename header", bufText.startsWith("*rename*"));
            String content = readFile(dir, "Hello.java");
            assertTrue("file was renamed", content.contains("World") && !content.contains("Hello"));
        } finally {
            System.setProperty("user.dir", origUserDir);
        }
        passed("testRenameCommandBasic");
    }

    static void testRenameCommandNoArgs() {
        ModalEditor editor = new ModalEditor("hello");
        sendCommand(editor, "rename");
        String status = editor.getStatusMessage();
        assertTrue("error for no args", status.startsWith("E:"));
        passed("testRenameCommandNoArgs");
    }

    static void testRenameCommandOnlyOneArg() {
        ModalEditor editor = new ModalEditor("hello");
        sendCommand(editor, "rename OnlyOldName");
        String status = editor.getStatusMessage();
        assertTrue("error for missing newName", status.startsWith("E:"));
        passed("testRenameCommandOnlyOneArg");
    }

    static void testRenameCommandNoMatches() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "file.java", "class Foo {}\n");
        String origUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());
        try {
            ModalEditor editor = new ModalEditor("hello");
            sendCommand(editor, "rename NonExistent Whatever");
            String status = editor.getStatusMessage();
            assertTrue("status says not found", status.contains("no occurrences"));
        } finally {
            System.setProperty("user.dir", origUserDir);
        }
        passed("testRenameCommandNoMatches");
    }

    static void testRenameCommandDisplayBuffer() throws Exception {
        Path dir = createTempDir();
        writeFile(dir, "Alpha.java", "class Alpha { Alpha() {} }\n");
        writeFile(dir, "Beta.java",  "import Alpha;\nAlpha a;\n");
        String origUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", dir.toString());
        try {
            ModalEditor editor = new ModalEditor("// current");
            sendCommand(editor, "rename Alpha Gamma");
            String buf = editor.getText();
            assertTrue("buffer header", buf.contains("*rename*"));
            assertTrue("buffer shows Alpha→Gamma", buf.contains("Alpha") && buf.contains("Gamma"));
            assertTrue("both files listed", buf.contains("Alpha.java") && buf.contains("Beta.java"));
        } finally {
            System.setProperty("user.dir", origUserDir);
        }
        passed("testRenameCommandDisplayBuffer");
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private static void sendCommand(ModalEditor editor, String cmd) {
        // ESC で NORMAL モードに
        editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        // ':' でコマンドモードへ
        editor.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        // コマンド文字を1文字ずつ送信
        for (char c : cmd.toCharArray()) {
            editor.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        }
        // Enter で実行
        editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
    }

    private static Path createTempDir() throws IOException {
        return Files.createTempDirectory("refactor_test_");
    }

    private static void writeFile(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    private static String readFile(Path dir, String name) throws IOException {
        return Files.readString(dir.resolve(name));
    }

    private static void assertEquals(String label, int expected, int actual) {
        if (expected == actual) return;
        System.err.println("  FAIL [" + label + "] expected=" + expected + " actual=" + actual);
        failed++;
        throw new AssertionError(label);
    }

    private static void assertEquals(String label, String expected, String actual) {
        if (expected.equals(actual)) return;
        System.err.println("  FAIL [" + label + "] expected='" + expected + "' actual='" + actual + "'");
        failed++;
        throw new AssertionError(label);
    }

    private static void assertTrue(String label, boolean condition) {
        if (condition) return;
        System.err.println("  FAIL [" + label + "] was false");
        failed++;
        throw new AssertionError(label);
    }

    private static void passed(String name) {
        passed++;
        System.out.println("  PASS " + name);
    }

    private static void failed(String msg) {
        failed++;
        System.err.println("  FAIL " + msg);
    }
}
