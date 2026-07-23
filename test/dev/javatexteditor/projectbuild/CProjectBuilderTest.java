package dev.javatexteditor.projectbuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * F10/F11（C版）のコンパイル・実行ファイル解決ロジックを検証する（mainメソッド形式のハーネス）。
 * gcc/clang/cc が PATH に無い環境では、コンパイルを伴うテストは skip 扱いにして PASS とする
 * （実際の子プロセス起動を伴うため、CI 環境の C コンパイラ有無に依存しないようにする）。
 */
public class CProjectBuilderTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testParseDiagnosticError();
        testParseDiagnosticWarning();
        testParseDiagnosticNoteSkipped();
        testParseDiagnosticNonDiagnosticLine();
        testBinDirForClimbsToSrcAncestor();
        testExecutableForUsesBinDir();
        testHasExecutableFalseBeforeCompile();

        // 以下は実際に gcc を起動する（無ければ skip）
        testCompileSuccessProducesExecutable();
        testCompileFailureReportsErrorDiagnostics();
        testCompileWithNoCFiles();
        testHasExecutableTrueAfterCompile();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void assertTrue(String name, boolean condition) {
        if (condition) { System.out.println("  PASS: " + name); pass++; }
        else { System.out.println("  FAIL: " + name); fail++; }
    }

    static void assertEquals(String name, Object expected, Object actual) {
        assertTrue(name + " (expected=" + expected + ", actual=" + actual + ")",
            expected == null ? actual == null : expected.equals(actual));
    }

    static boolean hasCompiler() {
        return new CProjectBuilder().findCompiler() != null;
    }

    static void skip(String name) {
        System.out.println("  SKIP (no C compiler): " + name);
        pass++;
    }

    static void testParseDiagnosticError() {
        CProjectBuilder b = new CProjectBuilder();
        BuildDiagnostic d = b.parseDiagnostic("/tmp/t.c:3:5: error: expected ';' before '}' token");
        assertTrue("parse error not null", d != null);
        assertTrue("parse error isError", d != null && d.isError());
        assertEquals("parse error line (0-indexed)", 2, d == null ? null : d.lineNumber());
        assertEquals("parse error col (0-indexed)", 4, d == null ? null : d.column());
        assertEquals("parse error path", "/tmp/t.c", d == null ? null : d.filePath());
    }

    static void testParseDiagnosticWarning() {
        CProjectBuilder b = new CProjectBuilder();
        BuildDiagnostic d = b.parseDiagnostic(
            "/tmp/t.c:2:2: warning: implicit declaration of function 'printf'");
        assertTrue("parse warning not null", d != null);
        assertTrue("parse warning not error", d != null && !d.isError());
    }

    static void testParseDiagnosticNoteSkipped() {
        CProjectBuilder b = new CProjectBuilder();
        BuildDiagnostic d = b.parseDiagnostic("/tmp/t.c:1:1: note: include '<stdio.h>'");
        assertEquals("note lines skipped", null, d);
    }

    static void testParseDiagnosticNonDiagnosticLine() {
        CProjectBuilder b = new CProjectBuilder();
        assertEquals("in-function line skipped", null,
            b.parseDiagnostic("/tmp/t.c: In function 'main':"));
    }

    static void testBinDirForClimbsToSrcAncestor() throws IOException {
        Path root = Files.createTempDirectory("cpb-src");
        Files.createDirectories(root.resolve("src/pkg"));
        CProjectBuilder b = new CProjectBuilder();
        Path binFromDeep = b.binDirFor(root.resolve("src/pkg"));
        assertEquals("bin dir climbs to src ancestor",
            root.resolve("bin").toAbsolutePath().normalize(), binFromDeep);
    }

    static void testExecutableForUsesBinDir() throws IOException {
        Path root = Files.createTempDirectory("cpb-exe");
        CProjectBuilder b = new CProjectBuilder();
        assertEquals("executable name",
            root.resolve("bin").resolve(CProjectBuilder.EXECUTABLE_NAME).toAbsolutePath().normalize(),
            b.executableFor(root).toAbsolutePath().normalize());
    }

    static void testHasExecutableFalseBeforeCompile() throws IOException {
        Path root = Files.createTempDirectory("cpb-noexe");
        assertTrue("no executable before compile", !new CProjectBuilder().hasExecutable(root));
    }

    static void testCompileSuccessProducesExecutable() throws IOException {
        if (!hasCompiler()) { skip("compile success"); return; }
        Path root = Files.createTempDirectory("cpb-ok");
        Files.writeString(root.resolve("main.c"),
            "#include <stdio.h>\nint main(void){ printf(\"hi\\n\"); return 0; }\n");
        CProjectBuilder b = new CProjectBuilder();
        BuildResult r = b.compile(root);
        assertTrue("compile success", r.success());
        assertEquals("file count", 1, r.fileCount());
        assertTrue("executable exists", b.hasExecutable(root));
        assertTrue("command starts with compiler", r.command().matches("^(gcc|clang|cc)\\b.*"));
    }

    static void testCompileFailureReportsErrorDiagnostics() throws IOException {
        if (!hasCompiler()) { skip("compile failure"); return; }
        Path root = Files.createTempDirectory("cpb-fail");
        Files.writeString(root.resolve("bad.c"),
            "int main(void){ return 0 }\n"); // missing semicolon
        BuildResult r = new CProjectBuilder().compile(root);
        assertTrue("compile failed", !r.success());
        assertTrue("has error diagnostic",
            r.diagnostics().stream().anyMatch(BuildDiagnostic::isError));
    }

    static void testCompileWithNoCFiles() throws IOException {
        Path root = Files.createTempDirectory("cpb-empty");
        BuildResult r = new CProjectBuilder().compile(root);
        assertTrue("no c files -> failure", !r.success());
        assertTrue("no c files -> error message", r.errorMessage() != null);
    }

    static void testHasExecutableTrueAfterCompile() throws IOException {
        if (!hasCompiler()) { skip("has executable after compile"); return; }
        Path root = Files.createTempDirectory("cpb-after");
        Files.writeString(root.resolve("main.c"), "int main(void){ return 0; }\n");
        CProjectBuilder b = new CProjectBuilder();
        b.compile(root);
        assertTrue("executable exists after compile", b.hasExecutable(root));
    }
}
