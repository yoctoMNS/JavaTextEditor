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
        test_resolveMemberInType_walksSuperclassChain();
        test_resolveMemberInType_stopsAtUnknownSuperclass();
        test_resolveMemberInType_multiLevelInheritance();

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

    /**
     * インスタンスメソッド呼び出しの継承バグ再現: Derived型のレシーバでBaseのメソッドを
     * 呼んでも、無関係な同名メソッドを持つOtherへ誤ジャンプせず、正しくBase.javaが見つかる。
     */
    static void test_resolveMemberInType_walksSuperclassChain() throws Exception {
        Path dir = tempDir();
        writeFile(dir, "Base.java", """
            public class Base {
                void helper() {
                    System.out.println("base");
                }
            }
            """);
        writeFile(dir, "Derived.java", """
            public class Derived extends Base {
                void other() {}
            }
            """);
        writeFile(dir, "Other.java", """
            public class Other {
                void helper() {
                    System.out.println("wrong target");
                }
            }
            """);

        ProjectSymbolResolver resolver = new ProjectSymbolResolver();
        Optional<ProjectSymbolResolver.SymbolLocation> loc =
            resolver.resolveMemberInType(dir, null, null, "Derived", "helper");

        assertTrue("inherited helper() is found by walking to Base", loc.isPresent());
        assertTrue("resolved to Base.java, not Other.java",
            loc.get().filePath().endsWith("Base.java"));
    }

    static void test_resolveMemberInType_stopsAtUnknownSuperclass() throws Exception {
        Path dir = tempDir();
        // ArrayList は JDK クラス（プロジェクト内には存在しない）
        writeFile(dir, "MyList.java", """
            public class MyList extends java.util.ArrayList<String> {
            }
            """);

        ProjectSymbolResolver resolver = new ProjectSymbolResolver();
        Optional<ProjectSymbolResolver.SymbolLocation> loc =
            resolver.resolveMemberInType(dir, null, null, "MyList", "add");

        assertTrue("JDK superclass is not resolvable in project, returns empty", loc.isEmpty());
    }

    static void test_resolveMemberInType_multiLevelInheritance() throws Exception {
        Path dir = tempDir();
        writeFile(dir, "A.java", """
            public class A {
                void rootMethod() {
                }
            }
            """);
        writeFile(dir, "B.java", """
            public class B extends A {
            }
            """);
        writeFile(dir, "C.java", """
            public class C extends B {
            }
            """);

        ProjectSymbolResolver resolver = new ProjectSymbolResolver();
        Optional<ProjectSymbolResolver.SymbolLocation> loc =
            resolver.resolveMemberInType(dir, null, null, "C", "rootMethod");

        assertTrue("rootMethod found by walking two levels up (C -> B -> A)", loc.isPresent());
        assertTrue("resolved to A.java", loc.get().filePath().endsWith("A.java"));
    }
}
