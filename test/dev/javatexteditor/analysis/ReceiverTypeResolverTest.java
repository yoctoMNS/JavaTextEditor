package dev.javatexteditor.analysis;

import java.util.Optional;

public class ReceiverTypeResolverTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        test_localVariableDeclaration();
        test_methodParameter();
        test_fieldDeclaration();
        test_enhancedForLoop();
        test_genericTypeStripsTypeArguments();
        test_arrayTypeStripsBrackets();
        test_nearestDeclarationWins();
        test_keywordIsNotMistakenForType();
        test_unknownVariableReturnsEmpty();

        System.out.println();
        System.out.println("Results: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

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

    static void test_localVariableDeclaration() {
        String[] lines = {
            "void use() {",
            "    Cat obj = new Cat();",
            "    obj.speak();",
            "}"
        };
        Optional<String> type = new ReceiverTypeResolver().resolveType(lines, 2, "obj");
        assertEquals("local variable declaration resolves to Cat", "Cat", type.orElse(null));
    }

    static void test_methodParameter() {
        String[] lines = {
            "void use(Cat obj) {",
            "    obj.speak();",
            "}"
        };
        Optional<String> type = new ReceiverTypeResolver().resolveType(lines, 1, "obj");
        assertEquals("method parameter resolves to Cat", "Cat", type.orElse(null));
    }

    static void test_fieldDeclaration() {
        String[] lines = {
            "class Owner {",
            "    private Cat obj;",
            "    void use() {",
            "        this.obj.speak();",
            "    }",
            "}"
        };
        Optional<String> type = new ReceiverTypeResolver().resolveType(lines, 3, "obj");
        assertEquals("field declaration resolves to Cat", "Cat", type.orElse(null));
    }

    static void test_enhancedForLoop() {
        String[] lines = {
            "void use(List<Cat> cats) {",
            "    for (Cat obj : cats) {",
            "        obj.speak();",
            "    }",
            "}"
        };
        Optional<String> type = new ReceiverTypeResolver().resolveType(lines, 2, "obj");
        assertEquals("enhanced-for variable resolves to Cat", "Cat", type.orElse(null));
    }

    static void test_genericTypeStripsTypeArguments() {
        String[] lines = {
            "void use() {",
            "    List<String> list = new ArrayList<>();",
            "    list.add(\"x\");",
            "}"
        };
        Optional<String> type = new ReceiverTypeResolver().resolveType(lines, 2, "list");
        assertEquals("generic type is stripped to base List", "List", type.orElse(null));
    }

    static void test_arrayTypeStripsBrackets() {
        String[] lines = {
            "void use() {",
            "    Cat[] cats = new Cat[0];",
            "    cats.clone();",
            "}"
        };
        Optional<String> type = new ReceiverTypeResolver().resolveType(lines, 2, "cats");
        assertEquals("array type is stripped to base Cat", "Cat", type.orElse(null));
    }

    static void test_nearestDeclarationWins() {
        String[] lines = {
            "void use() {",
            "    Cat obj = new Cat();",
            "    {",
            "        Dog obj2 = new Dog();",
            "        obj2.speak();",
            "    }",
            "}"
        };
        // obj2 should resolve to Dog (declared closer above), not skip to unrelated lines
        Optional<String> type = new ReceiverTypeResolver().resolveType(lines, 4, "obj2");
        assertEquals("nearest matching declaration wins", "Dog", type.orElse(null));
    }

    static void test_keywordIsNotMistakenForType() {
        String[] lines = {
            "Cat obj() {",
            "    return obj;",
            "}"
        };
        // "return obj;" must not be parsed as "Type=return, var=obj"
        Optional<String> type = new ReceiverTypeResolver().resolveType(lines, 1, "obj");
        assertTrue("'return obj;' does not resolve 'return' as a type", type.isEmpty());
    }

    static void test_unknownVariableReturnsEmpty() {
        String[] lines = {
            "void use() {",
            "    doSomething();",
            "}"
        };
        Optional<String> type = new ReceiverTypeResolver().resolveType(lines, 1, "obj");
        assertTrue("undeclared variable returns empty", type.isEmpty());
    }
}
