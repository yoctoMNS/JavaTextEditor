package dev.javatexteditor.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CDefinitionResolver（C の Shift+K 定義ジャンプ）の解決ロジックを検証する。
 * サブプロセス非依存（正規表現＋一時ディレクトリのファイル走査）のため常時実行できる。
 */
public class CDefinitionResolverTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        // classifyDefinition 単体
        testClassifyFunctionDefinition();
        testClassifyFunctionDefinitionBraceNextLine();
        testClassifyPrototype();
        testClassifyMacro();
        testClassifyTypedef();
        testClassifyStruct();
        testClassifyTypedefStructClose();
        testClassifyFunctionCallNotMatched();
        testClassifyAssignmentCallNotMatched();

        // wordAt 単体
        testWordAtMiddle();
        testWordAtEndOfWord();
        testWordAtNonIdentifier();

        // resolve（一時プロジェクト）
        testResolveFunctionPrefersDefinitionOverPrototype();
        testResolveMacroInHeader();
        testResolveIncludeQuotedSameDir();
        testResolveIncludeAngleInProject();
        testResolveIncludeCursorAnywhereOnLine();
        testResolveNotFound();
        testResolveSkipsKeyword();
        testResolveSameFileDefinition();

        // stripComments（コメント中の誤検出防止）
        testStripCommentsRemovesLineComment();
        testStripCommentsRemovesMultiLineBlockComment();
        testStripCommentsHandlesSameLineBlockComment();
        testResolveIgnoresSymbolMentionedOnlyInComment();

        // #include を辿ったシンボル探索（BFS、標準ヘッダ総当たりの代わり）
        testResolveSymbolViaTransitiveInclude();
        testResolveSymbolNotFoundWhenIncludedHeaderLacksIt();

        // parseIncludeSearchPaths（gcc/clang -E -v 出力の解析）
        testParseIncludeSearchPathsBasic();
        testParseIncludeSearchPathsStripsAnnotation();
        testParseIncludeSearchPathsIgnoresNonExistentDirs();
        testParseIncludeSearchPathsNoSectionReturnsEmpty();

        // 実コンパイラに依存する統合テスト（無い環境ではskip）
        testResolveStandardLibrarySymbolViaRealCompiler();
        testResolveIncludeAngleOpensRealSystemHeader();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void assertTrue(String name, boolean cond) {
        if (cond) { System.out.println("  PASS: " + name); pass++; }
        else { System.out.println("  FAIL: " + name); fail++; }
    }

    static void assertEquals(String name, Object expected, Object actual) {
        assertTrue(name + " (expected=" + expected + ", actual=" + actual + ")",
            expected == null ? actual == null : expected.equals(actual));
    }

    static final int DEF = 0, MACRO = 1, TYPE = 2, PROTO = 3;

    static void testClassifyFunctionDefinition() {
        assertEquals("function def with brace", DEF,
            new CDefinitionResolver().classifyDefinition("int add(int a, int b) {", "add"));
    }

    static void testClassifyFunctionDefinitionBraceNextLine() {
        assertEquals("function def brace on next line", DEF,
            new CDefinitionResolver().classifyDefinition("static void run(void)", "run"));
    }

    static void testClassifyPrototype() {
        assertEquals("prototype ends with ;", PROTO,
            new CDefinitionResolver().classifyDefinition("int add(int a, int b);", "add"));
    }

    static void testClassifyMacro() {
        assertEquals("macro define", MACRO,
            new CDefinitionResolver().classifyDefinition("#define MAX_LEN 100", "MAX_LEN"));
    }

    static void testClassifyTypedef() {
        assertEquals("typedef", TYPE,
            new CDefinitionResolver().classifyDefinition("typedef unsigned long ulong_t;", "ulong_t"));
    }

    static void testClassifyStruct() {
        assertEquals("struct definition", TYPE,
            new CDefinitionResolver().classifyDefinition("struct Point {", "Point"));
    }

    static void testClassifyTypedefStructClose() {
        assertEquals("} Name; close", TYPE,
            new CDefinitionResolver().classifyDefinition("} Node;", "Node"));
    }

    static void testClassifyFunctionCallNotMatched() {
        assertEquals("bare call not matched", -1,
            new CDefinitionResolver().classifyDefinition("    add(1, 2);", "add"));
    }

    static void testClassifyAssignmentCallNotMatched() {
        assertEquals("assignment call not matched", -1,
            new CDefinitionResolver().classifyDefinition("    int r = add(1, 2);", "add"));
    }

    static void testWordAtMiddle() {
        assertEquals("word at middle", "printf", CDefinitionResolver.wordAt("  printf(x);", 4));
    }

    static void testWordAtEndOfWord() {
        // カーソルが識別子直後（'(' の位置）でも左隣の語を拾う
        assertEquals("word at char after ident", "printf", CDefinitionResolver.wordAt("printf(x)", 6));
    }

    static void testWordAtNonIdentifier() {
        // 両隣が空白の位置（識別子に隣接しない）は null
        assertEquals("whitespace surrounded -> null", null, CDefinitionResolver.wordAt("a  b", 2));
    }

    // ---- resolve ----

    static Path tempProject(String name) throws IOException {
        return Files.createTempDirectory("cdef-" + name);
    }

    static void testResolveFunctionPrefersDefinitionOverPrototype() throws IOException {
        Path root = tempProject("fn");
        Files.writeString(root.resolve("util.h"), "int add(int a, int b);\n");
        Files.writeString(root.resolve("util.c"), "#include \"util.h\"\nint add(int a, int b) {\n  return a + b;\n}\n");
        String main = "#include \"util.h\"\nint main(void){ return add(1,2); }\n";
        Path mainFile = root.resolve("main.c");
        Files.writeString(mainFile, main);
        // カーソルを 2行目の "add" (add(1,2)) の上に置く
        int row = 1, col = main.split("\n")[1].indexOf("add");
        CDefinitionResolver.Location loc =
            new CDefinitionResolver().resolve(main, mainFile, row, col, root);
        assertTrue("resolved", loc != null);
        assertEquals("jumps to definition (util.c)", "util.c",
            loc == null ? null : loc.file().getFileName().toString());
        assertEquals("label definition", "definition", loc == null ? null : loc.label());
        assertEquals("line of definition (0-indexed)", 1, loc == null ? null : loc.line());
    }

    static void testResolveMacroInHeader() throws IOException {
        Path root = tempProject("macro");
        Files.writeString(root.resolve("cfg.h"), "#ifndef CFG_H\n#define MAX_USERS 50\n#endif\n");
        String main = "#include \"cfg.h\"\nint arr[MAX_USERS];\n";
        Path mainFile = root.resolve("main.c");
        Files.writeString(mainFile, main);
        int row = 1, col = main.split("\n")[1].indexOf("MAX_USERS");
        CDefinitionResolver.Location loc =
            new CDefinitionResolver().resolve(main, mainFile, row, col, root);
        assertTrue("macro resolved", loc != null);
        assertEquals("jumps to cfg.h", "cfg.h", loc == null ? null : loc.file().getFileName().toString());
        assertEquals("macro line", 1, loc == null ? null : loc.line());
    }

    static void testResolveIncludeQuotedSameDir() throws IOException {
        Path root = tempProject("incq");
        Path sub = Files.createDirectories(root.resolve("sub"));
        Files.writeString(sub.resolve("helper.h"), "void help(void);\n");
        String main = "#include \"helper.h\"\nint main(void){ return 0; }\n";
        Path mainFile = sub.resolve("main.c");
        Files.writeString(mainFile, main);
        CDefinitionResolver.Location loc =
            new CDefinitionResolver().resolve(main, mainFile, 0, 5, root);
        assertTrue("include resolved", loc != null);
        assertEquals("opens helper.h", "helper.h", loc == null ? null : loc.file().getFileName().toString());
        assertEquals("header label", "header", loc == null ? null : loc.label());
    }

    static void testResolveIncludeAngleInProject() throws IOException {
        Path root = tempProject("inca");
        Path inc = Files.createDirectories(root.resolve("include"));
        Files.writeString(inc.resolve("mylib.h"), "#define LIB 1\n");
        String main = "#include <mylib.h>\nint main(void){ return 0; }\n";
        Path mainFile = root.resolve("main.c");
        Files.writeString(mainFile, main);
        CDefinitionResolver.Location loc =
            new CDefinitionResolver().resolve(main, mainFile, 0, 12, root);
        assertTrue("angle include resolved from project", loc != null);
        assertEquals("opens mylib.h", "mylib.h", loc == null ? null : loc.file().getFileName().toString());
    }

    static void testResolveIncludeCursorAnywhereOnLine() throws IOException {
        Path root = tempProject("incany");
        Files.writeString(root.resolve("a.h"), "int x;\n");
        String main = "#include \"a.h\"\n";
        Path mainFile = root.resolve("main.c");
        Files.writeString(mainFile, main);
        // カーソルを行頭(col 0)に置いても include として解決される
        CDefinitionResolver.Location loc =
            new CDefinitionResolver().resolve(main, mainFile, 0, 0, root);
        assertTrue("include resolved from col 0", loc != null && "a.h".equals(loc.file().getFileName().toString()));
    }

    static void testResolveNotFound() throws IOException {
        Path root = tempProject("nf");
        String main = "int main(void){ return nonExistentSymbol; }\n";
        Path mainFile = root.resolve("main.c");
        Files.writeString(mainFile, main);
        int col = main.indexOf("nonExistentSymbol");
        CDefinitionResolver.Location loc =
            new CDefinitionResolver().resolve(main, mainFile, 0, col, root);
        assertEquals("unknown symbol -> null", null, loc);
    }

    static void testResolveSkipsKeyword() throws IOException {
        Path root = tempProject("kw");
        String main = "int main(void){ return 0; }\n";
        Path mainFile = root.resolve("main.c");
        Files.writeString(mainFile, main);
        int col = main.indexOf("return");
        CDefinitionResolver.Location loc =
            new CDefinitionResolver().resolve(main, mainFile, 0, col, root);
        assertEquals("keyword 'return' -> null", null, loc);
    }

    static void testResolveSameFileDefinition() throws IOException {
        Path root = tempProject("same");
        String main = "static int square(int x) {\n  return x * x;\n}\nint main(void){ return square(3); }\n";
        Path mainFile = root.resolve("main.c");
        Files.writeString(mainFile, main);
        int row = 3, col = main.split("\n")[3].indexOf("square");
        CDefinitionResolver.Location loc =
            new CDefinitionResolver().resolve(main, mainFile, row, col, root);
        assertTrue("same-file def resolved", loc != null);
        assertEquals("jumps within main.c", "main.c", loc == null ? null : loc.file().getFileName().toString());
        assertEquals("def at line 0", 0, loc == null ? null : loc.line());
    }

    // ---- stripComments ----

    static void testStripCommentsRemovesLineComment() {
        var out = CDefinitionResolver.stripComments(java.util.List.of("int x = 1; // printf here"));
        assertEquals("line comment removed", "int x = 1; ", out.get(0));
    }

    static void testStripCommentsRemovesMultiLineBlockComment() {
        var out = CDefinitionResolver.stripComments(java.util.List.of(
            "/* start",
            "   this mentions printf() in prose",
            "   more prose */",
            "int real(void) {"));
        assertEquals("line0 comment start stripped", "", out.get(0));
        assertEquals("line1 inside block stripped", "", out.get(1));
        assertEquals("line2 keeps only text after */", "", out.get(2));
        assertEquals("line3 untouched (after block closed)", "int real(void) {", out.get(3));
    }

    static void testStripCommentsHandlesSameLineBlockComment() {
        var out = CDefinitionResolver.stripComments(
            java.util.List.of("int x = /* comment printf() */ 5;"));
        assertEquals("same-line block comment removed, code kept",
            "int x =  5;", out.get(0));
    }

    static void testResolveIgnoresSymbolMentionedOnlyInComment() throws IOException {
        Path root = tempProject("cmt");
        String main = """
            /* Note: this file used to call printf() for debugging
               but that was removed. printf() is no longer used. */
            int real(void) {
              return 0;
            }
            int main(void) { return real(); }
            """;
        Path mainFile = root.resolve("main.c");
        Files.writeString(mainFile, main);
        int col = main.split("\n")[5].indexOf("real(");
        CDefinitionResolver.Location loc =
            new CDefinitionResolver().resolve(main, mainFile, 5, col, root);
        assertTrue("resolves to the real function, not the comment", loc != null);
        assertEquals("jumps to real()'s definition line", 2, loc == null ? null : loc.line());
    }

    // ---- #include を辿ったシンボル探索（BFS） ----

    static void testResolveSymbolViaTransitiveInclude() throws IOException {
        Path root = tempProject("transitive");
        // main.c -> #include "a.h" -> #include "b.h"（b.h に定義がある）
        Files.writeString(root.resolve("b.h"), "#define MAGIC 42\n");
        Files.writeString(root.resolve("a.h"), "#include \"b.h\"\nvoid helper(void);\n");
        String main = "#include \"a.h\"\nint main(void){ return MAGIC; }\n";
        Path mainFile = root.resolve("main.c");
        Files.writeString(mainFile, main);
        int col = main.split("\n")[1].indexOf("MAGIC");
        CDefinitionResolver.Location loc =
            new CDefinitionResolver().resolve(main, mainFile, 1, col, root);
        assertTrue("transitively-included header's macro resolved", loc != null);
        assertEquals("jumps to b.h", "b.h", loc == null ? null : loc.file().getFileName().toString());
    }

    static void testResolveSymbolNotFoundWhenIncludedHeaderLacksIt() throws IOException {
        Path root = tempProject("notfound-inc");
        Files.writeString(root.resolve("a.h"), "void helper(void);\n");
        String main = "#include \"a.h\"\nint main(void){ return TOTALLY_UNKNOWN_THING; }\n";
        Path mainFile = root.resolve("main.c");
        Files.writeString(mainFile, main);
        int col = main.split("\n")[1].indexOf("TOTALLY_UNKNOWN_THING");
        CDefinitionResolver.Location loc =
            new CDefinitionResolver().resolve(main, mainFile, 1, col, root);
        assertEquals("not found when included headers don't define it", null, loc);
    }

    // ---- parseIncludeSearchPaths ----

    static void testParseIncludeSearchPathsBasic() throws IOException {
        Path d1 = Files.createTempDirectory("inc1");
        Path d2 = Files.createTempDirectory("inc2");
        String output = """
            #include "..." search starts here:
            #include <...> search starts here:
             %s
             %s
            End of search list.
            """.formatted(d1, d2);
        java.util.List<Path> dirs = CDefinitionResolver.parseIncludeSearchPaths(output);
        assertEquals("parses 2 dirs", 2, dirs.size());
        assertTrue("contains d1", dirs.contains(d1));
        assertTrue("contains d2", dirs.contains(d2));
    }

    static void testParseIncludeSearchPathsStripsAnnotation() throws IOException {
        Path d1 = Files.createTempDirectory("framework");
        String output = """
            #include <...> search starts here:
             %s (framework directory)
            End of search list.
            """.formatted(d1);
        java.util.List<Path> dirs = CDefinitionResolver.parseIncludeSearchPaths(output);
        assertEquals("strips clang framework annotation", java.util.List.of(d1), dirs);
    }

    static void testParseIncludeSearchPathsIgnoresNonExistentDirs() {
        String output = """
            #include <...> search starts here:
             /this/path/does/not/exist/anywhere
            End of search list.
            """;
        assertEquals("nonexistent dir filtered out", 0,
            CDefinitionResolver.parseIncludeSearchPaths(output).size());
    }

    static void testParseIncludeSearchPathsNoSectionReturnsEmpty() {
        assertEquals("no search-list markers -> empty", 0,
            CDefinitionResolver.parseIncludeSearchPaths("just some unrelated compiler output\n").size());
    }

    // ---- 実コンパイラに依存する統合テスト（無い環境ではskip） ----

    static boolean hasCompiler() {
        for (String candidate : java.util.List.of("gcc", "clang", "cc")) {
            String pathEnv = System.getenv("PATH");
            if (pathEnv == null) continue;
            for (String dir : pathEnv.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (dir.isEmpty()) continue;
                if (Files.isRegularFile(Path.of(dir, candidate))
                        || Files.isRegularFile(Path.of(dir, candidate + ".exe"))) return true;
            }
        }
        return false;
    }

    static void skip(String name) {
        System.out.println("  SKIP (no C compiler): " + name);
        pass++;
    }

    static void testResolveStandardLibrarySymbolViaRealCompiler() throws IOException {
        if (!hasCompiler()) { skip("standard library symbol via real compiler"); return; }
        Path root = tempProject("realstd");
        String main = "#include <stdio.h>\nint main(void){ printf(\"hi\"); return 0; }\n";
        Path mainFile = root.resolve("main.c");
        Files.writeString(mainFile, main);
        int col = main.split("\n")[1].indexOf("printf");
        CDefinitionResolver.Location loc =
            new CDefinitionResolver().resolve(main, mainFile, 1, col, root);
        assertTrue("printf resolved via real installed compiler's headers", loc != null);
        assertTrue("label marks it as system header",
            loc != null && loc.label().contains("system header"));
    }

    static void testResolveIncludeAngleOpensRealSystemHeader() throws IOException {
        if (!hasCompiler()) { skip("angle include opens real system header"); return; }
        Path root = tempProject("realheader");
        String main = "#include <stdio.h>\nint main(void){ return 0; }\n";
        Path mainFile = root.resolve("main.c");
        Files.writeString(mainFile, main);
        CDefinitionResolver.Location loc =
            new CDefinitionResolver().resolve(main, mainFile, 0, 12, root);
        assertTrue("stdio.h resolved via real compiler's search path", loc != null);
        assertEquals("opens stdio.h", "stdio.h", loc == null ? null : loc.file().getFileName().toString());
        assertEquals("label is system header", "system header", loc == null ? null : loc.label());
    }
}
