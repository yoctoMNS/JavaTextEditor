package dev.javatexteditor.analysis;

import java.nio.file.*;
import java.util.List;

public class SourceAnalyzerTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // --- import 収集テスト ---
        test_normalImports();
        test_staticImport();
        test_wildcardImport();
        test_multipleImports();

        // --- シンボル収集テスト ---
        test_topLevelClassName();
        test_methodSymbols();
        test_fieldSymbols();
        test_constructorSymbol();
        test_interfaceKind();
        test_enumKind();

        // --- エラー耐性テスト ---
        test_syntaxErrorPartialParse();
        test_emptySource();

        // --- バッファ文字列解析テスト ---
        test_analyzeTextNoFile();

        // --- ファイル解析テスト ---
        test_analyzeFile();

        // --- ネストしたクラスは収集しない ---
        test_nestedClassNotIncluded();

        // --- 行番号テスト ---
        test_lineNumbers();

        System.out.println();
        System.out.println("Results: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ── helpers ──────────────────────────────────────────────────

    static void assertTrue(String name, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + name);
            passed++;
        } else {
            System.out.println("  FAIL: " + name);
            failed++;
        }
    }

    static void assertEquals(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            System.out.println("  PASS: " + name);
            passed++;
        } else {
            System.out.println("  FAIL: " + name + " expected=" + expected + " actual=" + actual);
            failed++;
        }
    }

    static SourceIndex parse(String src) {
        try {
            return new SourceAnalyzer().analyzeText(src);
        } catch (AnalysisException e) {
            throw new RuntimeException(e);
        }
    }

    // ── test methods ─────────────────────────────────────────────

    static void test_normalImports() {
        System.out.println("test_normalImports");
        String src = """
            package com.example;
            import java.util.List;
            import java.util.Map;
            public class Foo {}
            """;
        SourceIndex idx = parse(src);
        assertEquals("import count", 2, idx.imports().size());
        assertEquals("first import fqn", "java.util.List", idx.imports().get(0).fullyQualifiedName());
        assertTrue("first import not static", !idx.imports().get(0).isStatic());
        assertTrue("first import not wildcard", !idx.imports().get(0).isWildcard());
        assertEquals("second import fqn", "java.util.Map", idx.imports().get(1).fullyQualifiedName());
    }

    static void test_staticImport() {
        System.out.println("test_staticImport");
        String src = """
            import static java.lang.Math.abs;
            public class Bar {}
            """;
        SourceIndex idx = parse(src);
        assertEquals("import count", 1, idx.imports().size());
        assertTrue("is static", idx.imports().get(0).isStatic());
        assertTrue("not wildcard", !idx.imports().get(0).isWildcard());
        assertEquals("fqn", "java.lang.Math.abs", idx.imports().get(0).fullyQualifiedName());
    }

    static void test_wildcardImport() {
        System.out.println("test_wildcardImport");
        String src = """
            import java.util.*;
            public class Baz {}
            """;
        SourceIndex idx = parse(src);
        assertEquals("import count", 1, idx.imports().size());
        assertTrue("is wildcard", idx.imports().get(0).isWildcard());
        assertEquals("fqn without .*", "java.util", idx.imports().get(0).fullyQualifiedName());
    }

    static void test_multipleImports() {
        System.out.println("test_multipleImports");
        String src = """
            import java.io.File;
            import static java.lang.System.out;
            import java.util.*;
            class Multi {}
            """;
        SourceIndex idx = parse(src);
        assertEquals("import count", 3, idx.imports().size());
        assertTrue("second is static", idx.imports().get(1).isStatic());
        assertTrue("third is wildcard", idx.imports().get(2).isWildcard());
    }

    static void test_topLevelClassName() {
        System.out.println("test_topLevelClassName");
        String src = """
            public class MyClass {}
            """;
        SourceIndex idx = parse(src);
        List<SymbolEntry> classes = idx.symbols().stream()
            .filter(s -> s.kind() == SymbolKind.CLASS).toList();
        assertEquals("class count", 1, classes.size());
        assertEquals("class name", "MyClass", classes.get(0).name());
    }

    static void test_methodSymbols() {
        System.out.println("test_methodSymbols");
        String src = """
            public class Calc {
                public int add(int a, int b) { return a + b; }
                private void reset() {}
            }
            """;
        SourceIndex idx = parse(src);
        List<SymbolEntry> methods = idx.symbols().stream()
            .filter(s -> s.kind() == SymbolKind.METHOD).toList();
        assertEquals("method count", 2, methods.size());
        List<String> names = methods.stream().map(SymbolEntry::name).toList();
        assertTrue("has add", names.contains("add"));
        assertTrue("has reset", names.contains("reset"));
    }

    static void test_fieldSymbols() {
        System.out.println("test_fieldSymbols");
        String src = """
            public class Counter {
                private int count = 0;
                public static final String NAME = "counter";
            }
            """;
        SourceIndex idx = parse(src);
        List<SymbolEntry> fields = idx.symbols().stream()
            .filter(s -> s.kind() == SymbolKind.FIELD).toList();
        assertEquals("field count", 2, fields.size());
        List<String> names = fields.stream().map(SymbolEntry::name).toList();
        assertTrue("has count", names.contains("count"));
        assertTrue("has NAME", names.contains("NAME"));
    }

    static void test_constructorSymbol() {
        System.out.println("test_constructorSymbol");
        String src = """
            public class Point {
                public Point(int x, int y) {}
            }
            """;
        SourceIndex idx = parse(src);
        List<SymbolEntry> ctors = idx.symbols().stream()
            .filter(s -> s.kind() == SymbolKind.CONSTRUCTOR).toList();
        assertEquals("constructor count", 1, ctors.size());
        assertEquals("constructor name matches class", "Point", ctors.get(0).name());
    }

    static void test_interfaceKind() {
        System.out.println("test_interfaceKind");
        String src = "public interface Runnable {}";
        SourceIndex idx = parse(src);
        List<SymbolEntry> ifaces = idx.symbols().stream()
            .filter(s -> s.kind() == SymbolKind.INTERFACE).toList();
        assertEquals("interface count", 1, ifaces.size());
        assertEquals("interface name", "Runnable", ifaces.get(0).name());
    }

    static void test_enumKind() {
        System.out.println("test_enumKind");
        String src = "public enum Color { RED, GREEN, BLUE }";
        SourceIndex idx = parse(src);
        List<SymbolEntry> enums = idx.symbols().stream()
            .filter(s -> s.kind() == SymbolKind.ENUM).toList();
        assertEquals("enum count", 1, enums.size());
        assertEquals("enum name", "Color", enums.get(0).name());
    }

    static void test_syntaxErrorPartialParse() {
        System.out.println("test_syntaxErrorPartialParse");
        // 構文エラーがあっても import は収集できる
        String src = """
            import java.util.List;
            public class Broken {
                void oops( { // 構文エラー
            }
            """;
        SourceIndex idx = parse(src);
        assertTrue("hasParseError is true", idx.hasParseError());
        assertTrue("imports collected despite error", !idx.imports().isEmpty());
        assertEquals("import fqn", "java.util.List", idx.imports().get(0).fullyQualifiedName());
    }

    static void test_emptySource() {
        System.out.println("test_emptySource");
        SourceIndex idx = parse("");
        assertTrue("no exception on empty", true);
        assertEquals("no imports", 0, idx.imports().size());
        assertEquals("no symbols", 0, idx.symbols().size());
    }

    static void test_analyzeTextNoFile() {
        System.out.println("test_analyzeTextNoFile");
        String src = "class BufferOnly { int x; }";
        SourceIndex idx = parse(src);
        assertEquals("filePath is <buffer>", "<buffer>", idx.filePath());
        assertTrue("has class symbol", idx.symbols().stream()
            .anyMatch(s -> s.name().equals("BufferOnly") && s.kind() == SymbolKind.CLASS));
    }

    static void test_analyzeFile() throws Exception {
        System.out.println("test_analyzeFile");
        Path tmp = Files.createTempFile("jte-test-", ".java");
        try {
            Files.writeString(tmp, """
                import java.util.List;
                public class TempClass {
                    private String name;
                    public TempClass(String name) { this.name = name; }
                    public String getName() { return name; }
                }
                """);
            SourceIndex idx = new SourceAnalyzer().analyzeFile(tmp);
            assertEquals("filePath is tmp", tmp.toString(), idx.filePath());
            assertEquals("import count", 1, idx.imports().size());
            assertTrue("has class", idx.symbols().stream()
                .anyMatch(s -> s.kind() == SymbolKind.CLASS));
            assertTrue("has field", idx.symbols().stream()
                .anyMatch(s -> s.kind() == SymbolKind.FIELD && s.name().equals("name")));
            assertTrue("has constructor", idx.symbols().stream()
                .anyMatch(s -> s.kind() == SymbolKind.CONSTRUCTOR));
            assertTrue("has method", idx.symbols().stream()
                .anyMatch(s -> s.kind() == SymbolKind.METHOD && s.name().equals("getName")));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static void test_nestedClassNotIncluded() {
        System.out.println("test_nestedClassNotIncluded");
        String src = """
            public class Outer {
                class Inner {}
                void method() {}
            }
            """;
        SourceIndex idx = parse(src);
        List<SymbolEntry> classes = idx.symbols().stream()
            .filter(s -> s.kind() == SymbolKind.CLASS).toList();
        // Outer は収集、Inner はネストしているので収集しない
        assertEquals("only top-level class", 1, classes.size());
        assertEquals("class is Outer", "Outer", classes.get(0).name());
    }

    static void test_lineNumbers() {
        System.out.println("test_lineNumbers");
        String src = """
            import java.util.List;
            import java.util.Map;
            public class LineTest {
                int field;
                void method() {}
            }
            """;
        SourceIndex idx = parse(src);
        // import は0始まり行番号
        assertEquals("first import line", 0, idx.imports().get(0).lineNumber());
        assertEquals("second import line", 1, idx.imports().get(1).lineNumber());

        SymbolEntry cls = idx.symbols().stream()
            .filter(s -> s.kind() == SymbolKind.CLASS).findFirst().orElseThrow();
        assertEquals("class line", 2, cls.lineNumber());

        SymbolEntry field = idx.symbols().stream()
            .filter(s -> s.kind() == SymbolKind.FIELD).findFirst().orElseThrow();
        assertEquals("field line", 3, field.lineNumber());
    }
}
