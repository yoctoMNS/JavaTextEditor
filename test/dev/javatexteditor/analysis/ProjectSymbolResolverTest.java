package dev.javatexteditor.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class ProjectSymbolResolverTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        test_findFieldInSingleFile();
        test_findMethodInSingleFile();
        test_findAcrossMultipleFiles();
        test_currentBufferTakesPriorityOverDisk();
        test_unknownSymbolReturnsEmpty();
        test_findClassDeclaration();
        test_resolveMemberInType_disambiguatesSameNamedMethod();
        test_resolveMemberInType_unknownTypeReturnsEmpty();
        test_resolveMemberInType_memberNotInThatTypeReturnsEmpty();

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

    static Path tempDir() throws IOException {
        return Files.createTempDirectory("psr-test");
    }

    static void writeFile(Path dir, String relName, String content) throws IOException {
        Path p = dir.resolve(relName);
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    // ── tests ──────────────────────────────────────────────────

    static void test_findFieldInSingleFile() throws Exception {
        Path dir = tempDir();
        writeFile(dir, "Foo.java", """
            public class Foo {
                private int count;
                public static final int MAX = 10;
            }
            """);

        ProjectSymbolResolver resolver = new ProjectSymbolResolver();
        Optional<ProjectSymbolResolver.SymbolLocation> loc =
            resolver.resolve(dir, null, null, "MAX");

        assertTrue("field MAX is found", loc.isPresent());
        assertEquals("field kind is FIELD", SymbolKind.FIELD, loc.get().kind());
        assertEquals("field declaration line", 2, loc.get().lineNumber());
    }

    static void test_findMethodInSingleFile() throws Exception {
        Path dir = tempDir();
        writeFile(dir, "Bar.java", """
            public class Bar {
                public void doWork() {
                    System.out.println("hi");
                }
            }
            """);

        ProjectSymbolResolver resolver = new ProjectSymbolResolver();
        Optional<ProjectSymbolResolver.SymbolLocation> loc =
            resolver.resolve(dir, null, null, "doWork");

        assertTrue("method doWork is found", loc.isPresent());
        assertEquals("method kind is METHOD", SymbolKind.METHOD, loc.get().kind());
        assertEquals("method declaration line", 1, loc.get().lineNumber());
    }

    static void test_findAcrossMultipleFiles() throws Exception {
        Path dir = tempDir();
        writeFile(dir, "Caller.java", """
            public class Caller {
                void use() {
                    Target.helper();
                }
            }
            """);
        writeFile(dir, "Target.java", """
            public class Target {
                static void helper() {
                }
            }
            """);

        ProjectSymbolResolver resolver = new ProjectSymbolResolver();
        Optional<ProjectSymbolResolver.SymbolLocation> loc =
            resolver.resolve(dir, null, null, "helper");

        assertTrue("helper() found across files", loc.isPresent());
        assertTrue("found in Target.java, not Caller.java",
            loc.get().filePath().endsWith("Target.java"));
    }

    static void test_currentBufferTakesPriorityOverDisk() throws Exception {
        Path dir = tempDir();
        Path onDisk = dir.resolve("Live.java");
        writeFile(dir, "Live.java", """
            public class Live {
                int oldField;
            }
            """);

        // 未保存の内容には別のフィールドが追加されている
        String bufferText = """
            public class Live {
                int oldField;
                int newField;
            }
            """;

        ProjectSymbolResolver resolver = new ProjectSymbolResolver();
        Optional<ProjectSymbolResolver.SymbolLocation> loc =
            resolver.resolve(dir, onDisk.toString(), bufferText, "newField");

        assertTrue("unsaved field newField is found via buffer text", loc.isPresent());
        assertEquals("resolved to current file path", onDisk.toString(), loc.get().filePath());
    }

    static void test_unknownSymbolReturnsEmpty() throws Exception {
        Path dir = tempDir();
        writeFile(dir, "Empty.java", """
            public class Empty {
            }
            """);

        ProjectSymbolResolver resolver = new ProjectSymbolResolver();
        Optional<ProjectSymbolResolver.SymbolLocation> loc =
            resolver.resolve(dir, null, null, "doesNotExist");

        assertTrue("unknown symbol returns empty", loc.isEmpty());
    }

    static void test_findClassDeclaration() throws Exception {
        Path dir = tempDir();
        writeFile(dir, "Widget.java", """
            public class Widget {
            }
            """);

        ProjectSymbolResolver resolver = new ProjectSymbolResolver();
        Optional<ProjectSymbolResolver.SymbolLocation> loc =
            resolver.resolve(dir, null, null, "Widget");

        assertTrue("class Widget is found", loc.isPresent());
        assertEquals("class kind is CLASS", SymbolKind.CLASS, loc.get().kind());
    }

    /**
     * "instanceVar.member" のバグ再現: 同名メソッドが複数クラスに存在する場合、
     * 型を指定した resolveMemberInType() は正しいクラスのファイルだけを見る。
     */
    static void test_resolveMemberInType_disambiguatesSameNamedMethod() throws Exception {
        Path dir = tempDir();
        writeFile(dir, "Dog.java", """
            public class Dog {
                void speak() {
                    System.out.println("Woof");
                }
            }
            """);
        writeFile(dir, "Cat.java", """
            public class Cat {
                void speak() {
                    System.out.println("Meow");
                }
            }
            """);

        ProjectSymbolResolver resolver = new ProjectSymbolResolver();
        Optional<ProjectSymbolResolver.SymbolLocation> loc =
            resolver.resolveMemberInType(dir, null, null, "Cat", "speak");

        assertTrue("speak() resolved via Cat type is found", loc.isPresent());
        assertTrue("resolved to Cat.java, not Dog.java",
            loc.get().filePath().endsWith("Cat.java"));
    }

    static void test_resolveMemberInType_unknownTypeReturnsEmpty() throws Exception {
        Path dir = tempDir();
        writeFile(dir, "Cat.java", """
            public class Cat {
                void speak() {}
            }
            """);

        ProjectSymbolResolver resolver = new ProjectSymbolResolver();
        Optional<ProjectSymbolResolver.SymbolLocation> loc =
            resolver.resolveMemberInType(dir, null, null, "List", "speak");

        assertTrue("unknown (JDK) type returns empty so caller can try JDK", loc.isEmpty());
    }

    static void test_resolveMemberInType_memberNotInThatTypeReturnsEmpty() throws Exception {
        Path dir = tempDir();
        writeFile(dir, "Cat.java", """
            public class Cat {
                void speak() {}
            }
            """);

        ProjectSymbolResolver resolver = new ProjectSymbolResolver();
        Optional<ProjectSymbolResolver.SymbolLocation> loc =
            resolver.resolveMemberInType(dir, null, null, "Cat", "bark");

        assertTrue("member absent from resolved type returns empty (no cross-class fallback)",
            loc.isEmpty());
    }
}
