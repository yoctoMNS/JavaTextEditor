package dev.javatexteditor.analysis;

import java.util.List;

/**
 * CCompileAnalyzer（gcc -fsyntax-only による単一Cファイル診断）を検証する。
 * gcc/clang/cc が無い環境では実コンパイルを伴うテストを skip する。診断行のパース単体テストは
 * サブプロセス非依存なので常に実行する。
 */
public class CCompileAnalyzerTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testParseErrorLine();
        testParseWarningLine();
        testParseNoteSkipped();
        testParseFiltersByPath();
        testParseNonMatchingLine();

        testAnalyzeCleanSource();
        testAnalyzeSyntaxError();

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
        return new CCompileAnalyzer().findCompiler() != null;
    }

    static void skip(String name) {
        System.out.println("  SKIP (no C compiler): " + name);
        pass++;
    }

    static void testParseErrorLine() {
        CCompileAnalyzer a = new CCompileAnalyzer();
        CompileDiagnostic d = a.parseDiagnostic("/tmp/x.c:5:9: error: expected ';'", null);
        assertTrue("error parsed", d != null);
        assertEquals("line 0-indexed", 4, d == null ? null : d.lineNumber());
        assertEquals("col 0-indexed", 8, d == null ? null : d.column());
        assertEquals("kind ERROR", DiagnosticKind.ERROR, d == null ? null : d.kind());
    }

    static void testParseWarningLine() {
        CCompileAnalyzer a = new CCompileAnalyzer();
        CompileDiagnostic d = a.parseDiagnostic(
            "/tmp/x.c:2:2: warning: implicit declaration of function 'printf'", null);
        assertEquals("kind WARNING", DiagnosticKind.WARNING, d == null ? null : d.kind());
    }

    static void testParseNoteSkipped() {
        CCompileAnalyzer a = new CCompileAnalyzer();
        assertEquals("note skipped", null,
            a.parseDiagnostic("/tmp/x.c:1:1: note: include '<stdio.h>'", null));
    }

    static void testParseFiltersByPath() {
        CCompileAnalyzer a = new CCompileAnalyzer();
        assertEquals("other-file diagnostic filtered out", null,
            a.parseDiagnostic("/usr/include/stdio.h:1:1: error: boom", "/tmp/x.c"));
        assertTrue("matching-path diagnostic kept",
            a.parseDiagnostic("/tmp/x.c:1:1: error: boom", "/tmp/x.c") != null);
    }

    static void testParseNonMatchingLine() {
        CCompileAnalyzer a = new CCompileAnalyzer();
        assertEquals("non-diagnostic line skipped", null,
            a.parseDiagnostic("some random text", null));
    }

    static void testAnalyzeCleanSource() throws Exception {
        if (!hasCompiler()) { skip("analyze clean source"); return; }
        List<CompileDiagnostic> d = new CCompileAnalyzer()
            .analyze("#include <stdio.h>\nint main(void){ printf(\"ok\\n\"); return 0; }\n");
        assertTrue("clean source has no errors",
            d.stream().noneMatch(x -> x.kind() == DiagnosticKind.ERROR));
    }

    static void testAnalyzeSyntaxError() throws Exception {
        if (!hasCompiler()) { skip("analyze syntax error"); return; }
        List<CompileDiagnostic> d = new CCompileAnalyzer()
            .analyze("int main(void){ return 0 }\n"); // missing semicolon
        assertTrue("syntax error detected",
            d.stream().anyMatch(x -> x.kind() == DiagnosticKind.ERROR));
    }
}
