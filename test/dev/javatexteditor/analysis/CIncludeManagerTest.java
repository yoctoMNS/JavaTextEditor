package dev.javatexteditor.analysis;

import java.util.List;
import java.util.Set;

/**
 * CIncludeManager（C の #include 自動挿入・整理の純粋ロジック）を検証する。
 * サブプロセス非依存のため CI 環境の C コンパイラ有無に関係なく実行できる。
 */
public class CIncludeManagerTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testHeaderFor();
        testExtractSymbolImplicitDeclaration();
        testExtractSymbolUnknownType();
        testExtractSymbolUndeclared();
        testExtractSymbolClangUndeclared();
        testExtractSymbolNoMatch();
        testExistingIncludes();
        testUsedKnownSymbols();
        testMissingHeadersForSource();
        testMissingHeadersForSourceSkipsAlreadyIncluded();
        testMissingHeadersForSymbols();
        testAddIncludesAfterExistingInclude();
        testAddIncludesNoExistingIncludeAfterComment();
        testAddIncludesSortedAndDeduped();
        testAddIncludesEmptyNoChange();
        testInsertOffsetMatchesAddIncludes();

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

    static void testHeaderFor() {
        assertEquals("printf -> stdio.h", "stdio.h", CIncludeManager.headerFor("printf"));
        assertEquals("malloc -> stdlib.h", "stdlib.h", CIncludeManager.headerFor("malloc"));
        assertEquals("strlen -> string.h", "string.h", CIncludeManager.headerFor("strlen"));
        assertEquals("sqrt -> math.h", "math.h", CIncludeManager.headerFor("sqrt"));
        assertEquals("size_t -> stddef.h", "stddef.h", CIncludeManager.headerFor("size_t"));
        assertEquals("unknown -> null", null, CIncludeManager.headerFor("myCustomFunction"));
    }

    static void testExtractSymbolImplicitDeclaration() {
        assertEquals("implicit declaration", "printf", CIncludeManager.extractSymbolFromMessage(
            "implicit declaration of function 'printf' [-Wimplicit-function-declaration]"));
    }

    static void testExtractSymbolUnknownType() {
        assertEquals("unknown type name", "size_t", CIncludeManager.extractSymbolFromMessage(
            "unknown type name 'size_t'"));
    }

    static void testExtractSymbolUndeclared() {
        assertEquals("undeclared", "EOF", CIncludeManager.extractSymbolFromMessage(
            "'EOF' undeclared (first use in this function)"));
    }

    static void testExtractSymbolClangUndeclared() {
        assertEquals("clang undeclared identifier", "NULL", CIncludeManager.extractSymbolFromMessage(
            "use of undeclared identifier 'NULL'"));
    }

    static void testExtractSymbolNoMatch() {
        assertEquals("no symbol in message", null,
            CIncludeManager.extractSymbolFromMessage("expected ';' before '}' token"));
    }

    static void testExistingIncludes() {
        String src = "#include <stdio.h>\n#include \"local.h\"\n# include  <stdlib.h>\nint main(){}\n";
        Set<String> inc = CIncludeManager.existingIncludes(src);
        assertTrue("has stdio.h", inc.contains("stdio.h"));
        assertTrue("has local.h", inc.contains("local.h"));
        assertTrue("has stdlib.h (with spaces)", inc.contains("stdlib.h"));
    }

    static void testUsedKnownSymbols() {
        String src = "int main(){ printf(\"%d\", strlen(\"x\")); return 0; }";
        Set<String> used = CIncludeManager.usedKnownSymbols(src);
        assertTrue("uses printf", used.contains("printf"));
        assertTrue("uses strlen", used.contains("strlen"));
        assertTrue("does not include main as symbol", !used.contains("main"));
    }

    static void testMissingHeadersForSource() {
        String src = "int main(){ printf(\"%d\", strlen(\"x\")); double y = sqrt(4.0); return 0; }";
        List<String> h = CIncludeManager.missingHeadersForSource(src);
        assertTrue("needs math.h", h.contains("math.h"));
        assertTrue("needs stdio.h", h.contains("stdio.h"));
        assertTrue("needs string.h", h.contains("string.h"));
        // sorted alphabetically
        assertEquals("sorted order", List.of("math.h", "stdio.h", "string.h"), h);
    }

    static void testMissingHeadersForSourceSkipsAlreadyIncluded() {
        String src = "#include <stdio.h>\nint main(){ printf(\"hi\"); return 0; }";
        List<String> h = CIncludeManager.missingHeadersForSource(src);
        assertTrue("stdio.h already included -> not suggested", !h.contains("stdio.h"));
        assertTrue("nothing to add", h.isEmpty());
    }

    static void testMissingHeadersForSymbols() {
        String src = "int main(){ return 0; }";
        List<String> h = CIncludeManager.missingHeadersForSymbols(src, List.of("printf", "malloc", "unknownX"));
        assertEquals("resolves printf+malloc, ignores unknown",
            List.of("stdio.h", "stdlib.h"), h);
    }

    static void testAddIncludesAfterExistingInclude() {
        String src = "#include <stdio.h>\nint main(){ return 0; }\n";
        String out = CIncludeManager.addIncludes(src, List.of("stdlib.h"));
        assertEquals("inserted after existing include",
            "#include <stdio.h>\n#include <stdlib.h>\nint main(){ return 0; }\n", out);
    }

    static void testAddIncludesNoExistingIncludeAfterComment() {
        String src = "// header comment\nint main(){ return 0; }\n";
        String out = CIncludeManager.addIncludes(src, List.of("stdio.h"));
        assertEquals("inserted after leading comment",
            "// header comment\n#include <stdio.h>\nint main(){ return 0; }\n", out);
    }

    static void testAddIncludesSortedAndDeduped() {
        String src = "int main(){ return 0; }\n";
        String out = CIncludeManager.addIncludes(src, List.of("string.h", "stdio.h", "stdio.h"));
        assertEquals("sorted + deduped block at top",
            "#include <stdio.h>\n#include <string.h>\nint main(){ return 0; }\n", out);
    }

    static void testAddIncludesEmptyNoChange() {
        String src = "int main(){ return 0; }\n";
        assertEquals("empty headers -> unchanged", src, CIncludeManager.addIncludes(src, List.of()));
    }

    static void testInsertOffsetMatchesAddIncludes() {
        String src = "#include <stdio.h>\nint main(){}\n";
        int offset = CIncludeManager.insertOffset(src);
        String block = CIncludeManager.formatIncludeBlock(List.of("stdlib.h"));
        String manual = src.substring(0, offset) + block + src.substring(offset);
        assertEquals("offset+block equals addIncludes",
            CIncludeManager.addIncludes(src, List.of("stdlib.h")), manual);
    }
}
