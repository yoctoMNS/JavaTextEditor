package dev.javatexteditor.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * javac の意味解析によるメンバー補完（{@link JavacCompletionAnalyzer}）のテスト。
 *
 * <p>ここで確認するのは「軽量解決（正規表現 + リフレクション）では出せない候補が出るか」である。
 * メソッドチェーン・ジェネリクスの要素型・{@code var} の推論型がその代表。
 */
public class JavacCompletionAnalyzerTest {

    private static int passed = 0;
    private static int failed = 0;

    private static final JavacCompletionAnalyzer ANALYZER = new JavacCompletionAnalyzer();

    public static void main(String[] args) throws Exception {
        System.out.println("=== JavacCompletionAnalyzerTest ===");

        testLocalVariableMembers();
        testMethodSignatureIsShown();
        testGenericElementType();
        testMethodChain();
        testVarInferredType();
        testStaticAccessShowsOnlyStaticMembers();
        testInstanceAccessHidesStaticMembers();
        testArrayHasLengthField();
        testThisMembersIncludePrivate();
        testProjectClassInAnotherFile();
        testUnresolvableReceiverReturnsEmpty();
        testCaretWithPrefixStillResolves();

        System.out.println("=== " + passed + "/" + (passed + failed) + " PASSED ===");
        if (failed > 0) System.exit(1);
    }

    /** {@code // HERE} を含む行の末尾にカーソルがある想定で候補を求める。 */
    private static List<CompletionItem> completeAt(String source, Path projectRoot) {
        int caret = source.indexOf("|CARET|");
        String text = source.replace("|CARET|", "");
        CompletionContext ctx = CompletionContext.at(text, caret);
        String filePath = (projectRoot != null)
            ? projectRoot.resolve("Sample.java").toString() : null;
        return ANALYZER.resolveMembers(text, filePath, ctx, projectRoot);
    }

    private static boolean hasLabel(List<CompletionItem> items, String label) {
        return items.stream().anyMatch(i -> i.label().equals(label));
    }

    private static void testLocalVariableMembers() {
        String src = """
            public class Sample {
                void run() {
                    String text = "hello";
                    text.|CARET|
                }
            }
            """;
        List<CompletionItem> items = completeAt(src, null);
        assertTrue("String のメソッドが出る (isEmpty)", hasLabel(items, "isEmpty"));
        assertTrue("継承した Object のメソッドも出る (hashCode)", hasLabel(items, "hashCode"));
    }

    private static void testMethodSignatureIsShown() {
        String src = """
            public class Sample {
                void run() {
                    String text = "hello";
                    text.|CARET|
                }
            }
            """;
        CompletionItem substring = completeAt(src, null).stream()
            .filter(i -> i.label().equals("substring") && i.tailText().contains(","))
            .findFirst().orElse(null);
        assertTrue("substring(int, int) が見つかる", substring != null);
        if (substring == null) return;
        // JDK のクラスファイルには引数名が残らないため arg0/arg1 になる（型は正しく出る）。
        // ソースから読めるクラスの引数名は testProjectClassInAnotherFile で確認する。
        assertTrue("引数の型が2つ表示される",
            substring.tailText().startsWith("(int ") && substring.tailText().contains(", int "));
        assertEquals("戻り値型が表示される", "String", substring.typeText());
        assertEquals("挿入テキストは括弧つき", "substring()", substring.insertText());
        assertEquals("カーソルは括弧の内側へ", 1, substring.caretBackOffset());
    }

    private static void testGenericElementType() {
        String src = """
            import java.util.List;
            public class Sample {
                void run(List<String> names) {
                    names.get(0).|CARET|
                }
            }
            """;
        List<CompletionItem> items = completeAt(src, null);
        assertTrue("List<String> の要素は String と解決される", hasLabel(items, "toUpperCase"));
    }

    private static void testMethodChain() {
        String src = """
            public class Sample {
                void run() {
                    StringBuilder sb = new StringBuilder();
                    sb.append("x").|CARET|
                }
            }
            """;
        List<CompletionItem> items = completeAt(src, null);
        assertTrue("チェーンの戻り値型のメンバーが出る", hasLabel(items, "reverse"));
    }

    private static void testVarInferredType() {
        String src = """
            public class Sample {
                void run() {
                    var builder = new StringBuilder();
                    builder.|CARET|
                }
            }
            """;
        List<CompletionItem> items = completeAt(src, null);
        assertTrue("var の推論型が解決される", hasLabel(items, "append"));
    }

    private static void testStaticAccessShowsOnlyStaticMembers() {
        String src = """
            public class Sample {
                void run() {
                    Integer.|CARET|
                }
            }
            """;
        List<CompletionItem> items = completeAt(src, null);
        assertTrue("static メソッドが出る (parseInt)", hasLabel(items, "parseInt"));
        assertTrue("インスタンスメソッドは出ない (intValue)", !hasLabel(items, "intValue"));
    }

    private static void testInstanceAccessHidesStaticMembers() {
        String src = """
            public class Sample {
                void run() {
                    Integer boxed = 1;
                    boxed.|CARET|
                }
            }
            """;
        List<CompletionItem> items = completeAt(src, null);
        assertTrue("インスタンスメソッドが出る (intValue)", hasLabel(items, "intValue"));
        assertTrue("static メソッドは出ない (parseInt)", !hasLabel(items, "parseInt"));
    }

    private static void testArrayHasLengthField() {
        String src = """
            public class Sample {
                void run(String[] items) {
                    items.|CARET|
                }
            }
            """;
        List<CompletionItem> items = completeAt(src, null);
        assertTrue("配列は length を持つ", hasLabel(items, "length"));
        assertTrue("配列でも Object のメソッドは出る", hasLabel(items, "equals"));
    }

    private static void testThisMembersIncludePrivate() {
        String src = """
            public class Sample {
                private int secretValue = 1;
                void run() {
                    this.|CARET|
                }
            }
            """;
        List<CompletionItem> items = completeAt(src, null);
        assertTrue("自クラスの private フィールドは見える", hasLabel(items, "secretValue"));
    }

    private static void testProjectClassInAnotherFile() throws IOException {
        Path root = Files.createTempDirectory("javaccomp_project_");
        try {
            Files.writeString(root.resolve("Helper.java"), """
                public class Helper {
                    public int compute(int base) { return base; }
                    private int hidden() { return 0; }
                }
                """);
            String src = """
                public class Sample {
                    void run() {
                        Helper helper = new Helper();
                        helper.|CARET|
                    }
                }
                """;
            List<CompletionItem> items = completeAt(src, root);
            assertTrue("別ファイルのクラスのメソッドが出る", hasLabel(items, "compute"));
            assertTrue("他クラスの private メソッドは出ない", !hasLabel(items, "hidden"));
            CompletionItem compute = items.stream()
                .filter(i -> i.label().equals("compute")).findFirst().orElseThrow();
            assertEquals("ソースから読めるクラスは実際の引数名が出る", "(int base)", compute.tailText());
            assertEquals("戻り値型が出る", "int", compute.typeText());
        } finally {
            deleteDir(root);
        }
    }

    private static void testUnresolvableReceiverReturnsEmpty() {
        String src = """
            public class Sample {
                void run() {
                    unknownThing.|CARET|
                }
            }
            """;
        assertEquals("解決できないレシーバは候補なし", 0, completeAt(src, null).size());
    }

    private static void testCaretWithPrefixStillResolves() {
        String src = """
            public class Sample {
                void run() {
                    String text = "hello";
                    text.sub|CARET|
                }
            }
            """;
        List<CompletionItem> items = completeAt(src, null);
        assertTrue("入力中のプレフィックスがあっても型解決できる", hasLabel(items, "substring"));
        assertTrue("プレフィックスによる絞り込みはここでは行わない", hasLabel(items, "isEmpty"));
    }

    // -------------------------------------------------------------------------

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void assertEquals(String msg, Object expected, Object actual) {
        if (expected.equals(actual)) {
            System.out.println("  PASS: " + msg);
            passed++;
        } else {
            System.out.println("  FAIL: " + msg + " expected=" + expected + " actual=" + actual);
            failed++;
        }
    }

    private static void assertTrue(String msg, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + msg);
            passed++;
        } else {
            System.out.println("  FAIL: " + msg);
            failed++;
        }
    }
}
