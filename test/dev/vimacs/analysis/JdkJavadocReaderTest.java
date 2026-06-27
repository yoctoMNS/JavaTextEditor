package dev.vimacs.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * JdkJavadocReader のテスト。
 * Javadoc が存在しない環境でも graceful degradation で動作することを検証する。
 */
public class JdkJavadocReaderTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== JdkJavadocReaderTest ===");

        testIsAvailableReturnsBooleanWithoutException();
        testReadSummaryReturnsEmptyWhenNoJavadoc();
        testReadSummaryDoesNotThrowForUnknownFqn();
        testReadSummaryWithFakeHtml();
        testReadSummaryWithMultipleBlockDivs();
        testReadSummaryEntityDecoding();
        testReadSummaryStripsHtmlTags();
        testReadSummaryNormalizesWhitespace();
        testReadSummaryCachesResult();
        testReadSummaryWithAbsentHtmlFile();

        // Javadoc が利用可能な場合のみ追加テストを実行
        JdkJavadocReader reader = new JdkJavadocReader();
        if (reader.isAvailable()) {
            System.out.println("[INFO] Javadoc found — running live tests");
            testLiveArrayListSummary(reader);
            testLiveStringSummary(reader);
        } else {
            System.out.println("[INFO] Javadoc not installed — skipping live tests (graceful degradation OK)");
        }

        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // -----------------------------------------------------------------------
    // 単体テスト（Javadoc 不要）
    // -----------------------------------------------------------------------

    private static void testIsAvailableReturnsBooleanWithoutException() {
        try {
            JdkJavadocReader r = new JdkJavadocReader();
            boolean available = r.isAvailable();
            // isAvailable() が例外を投げずに真偽値を返すことだけを確認
            pass("isAvailable() returns " + available + " without exception");
        } catch (Exception e) {
            fail("isAvailable() threw: " + e);
        }
    }

    private static void testReadSummaryReturnsEmptyWhenNoJavadoc() {
        JdkJavadocReader r = new JdkJavadocReader();
        if (r.isAvailable()) {
            pass("readSummaryReturnsEmpty: Javadoc available, skip");
            return;
        }
        Optional<String> result = r.readSummary("java.util.ArrayList");
        assertTrue("readSummary returns empty when no Javadoc", result.isEmpty());
    }

    private static void testReadSummaryDoesNotThrowForUnknownFqn() {
        try {
            JdkJavadocReader r = new JdkJavadocReader();
            r.readSummary("com.nonexistent.ClassName");
            pass("readSummary does not throw for unknown FQN");
        } catch (Exception e) {
            fail("readSummary threw for unknown FQN: " + e);
        }
    }

    private static void testReadSummaryWithFakeHtml() throws Exception {
        // 一時ディレクトリに偽の Javadoc HTML を作成してテスト
        Path tmpDir = Files.createTempDirectory("javadoc-test");
        Path pkgDir = tmpDir.resolve("java/util");
        Files.createDirectories(pkgDir);
        String html = """
            <html><body>
            <div class="block">Resizable-array implementation of the List interface. This is a fake entry.</div>
            </body></html>
            """;
        Files.writeString(pkgDir.resolve("ArrayList.html"), html);

        System.setProperty("vimacs.javadoc.path", tmpDir.toString());
        try {
            JdkJavadocReader r = new JdkJavadocReader();
            Optional<String> result = r.readSummary("java.util.ArrayList");
            assertTrue("readSummary finds summary from fake HTML", result.isPresent());
            assertTrue("summary starts with 'Resizable'", result.get().startsWith("Resizable"));
        } finally {
            System.clearProperty("vimacs.javadoc.path");
            deleteTree(tmpDir);
        }
    }

    private static void testReadSummaryWithMultipleBlockDivs() throws Exception {
        Path tmpDir = Files.createTempDirectory("javadoc-test2");
        Path pkgDir = tmpDir.resolve("java/lang");
        Files.createDirectories(pkgDir);
        // 複数の <div class="block"> が存在する場合、最初の1個だけ使う
        String html = """
            <html><body>
            <div class="block">First block content. More details.</div>
            <div class="block">Second block content.</div>
            </body></html>
            """;
        Files.writeString(pkgDir.resolve("String.html"), html);

        System.setProperty("vimacs.javadoc.path", tmpDir.toString());
        try {
            JdkJavadocReader r = new JdkJavadocReader();
            Optional<String> result = r.readSummary("java.lang.String");
            assertTrue("readSummary returns first block only", result.isPresent());
            assertTrue("summary is first sentence only",
                       result.get().equals("First block content."));
        } finally {
            System.clearProperty("vimacs.javadoc.path");
            deleteTree(tmpDir);
        }
    }

    private static void testReadSummaryEntityDecoding() throws Exception {
        Path tmpDir = Files.createTempDirectory("javadoc-test3");
        Path pkgDir = tmpDir.resolve("java/util");
        Files.createDirectories(pkgDir);
        String html = """
            <html><body>
            <div class="block">A &lt;K,V&gt; map &amp; collection.</div>
            </body></html>
            """;
        Files.writeString(pkgDir.resolve("HashMap.html"), html);

        System.setProperty("vimacs.javadoc.path", tmpDir.toString());
        try {
            JdkJavadocReader r = new JdkJavadocReader();
            Optional<String> result = r.readSummary("java.util.HashMap");
            assertTrue("entity decoding works", result.isPresent());
            assertTrue("entities decoded: " + result.get(),
                       result.get().contains("<K,V>") && result.get().contains("&"));
        } finally {
            System.clearProperty("vimacs.javadoc.path");
            deleteTree(tmpDir);
        }
    }

    private static void testReadSummaryStripsHtmlTags() throws Exception {
        Path tmpDir = Files.createTempDirectory("javadoc-test4");
        Path pkgDir = tmpDir.resolve("java/io");
        Files.createDirectories(pkgDir);
        String html = """
            <html><body>
            <div class="block">Reads <code>char</code> values from a stream.</div>
            </body></html>
            """;
        Files.writeString(pkgDir.resolve("Reader.html"), html);

        System.setProperty("vimacs.javadoc.path", tmpDir.toString());
        try {
            JdkJavadocReader r = new JdkJavadocReader();
            Optional<String> result = r.readSummary("java.io.Reader");
            assertTrue("HTML tags stripped", result.isPresent());
            assertTrue("no <code> tag remains: " + result.get(),
                       !result.get().contains("<code>") && result.get().contains("char"));
        } finally {
            System.clearProperty("vimacs.javadoc.path");
            deleteTree(tmpDir);
        }
    }

    private static void testReadSummaryNormalizesWhitespace() throws Exception {
        Path tmpDir = Files.createTempDirectory("javadoc-test5");
        Path pkgDir = tmpDir.resolve("java/lang");
        Files.createDirectories(pkgDir);
        String html = """
            <html><body>
            <div class="block">  A   simple   description.\n  More  text.  </div>
            </body></html>
            """;
        Files.writeString(pkgDir.resolve("Object.html"), html);

        System.setProperty("vimacs.javadoc.path", tmpDir.toString());
        try {
            JdkJavadocReader r = new JdkJavadocReader();
            Optional<String> result = r.readSummary("java.lang.Object");
            assertTrue("whitespace normalized", result.isPresent());
            assertTrue("no double spaces: '" + result.get() + "'",
                       !result.get().contains("  "));
        } finally {
            System.clearProperty("vimacs.javadoc.path");
            deleteTree(tmpDir);
        }
    }

    private static void testReadSummaryCachesResult() throws Exception {
        Path tmpDir = Files.createTempDirectory("javadoc-test6");
        Path pkgDir = tmpDir.resolve("java/util");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("List.html"),
            "<html><body><div class=\"block\">A list interface.</div></body></html>");

        System.setProperty("vimacs.javadoc.path", tmpDir.toString());
        try {
            JdkJavadocReader r = new JdkJavadocReader();
            Optional<String> first  = r.readSummary("java.util.List");
            Optional<String> second = r.readSummary("java.util.List");
            assertTrue("cache: both calls return same value",
                       first.equals(second));
        } finally {
            System.clearProperty("vimacs.javadoc.path");
            deleteTree(tmpDir);
        }
    }

    private static void testReadSummaryWithAbsentHtmlFile() throws Exception {
        Path tmpDir = Files.createTempDirectory("javadoc-test7");
        System.setProperty("vimacs.javadoc.path", tmpDir.toString());
        try {
            JdkJavadocReader r = new JdkJavadocReader();
            Optional<String> result = r.readSummary("com.missing.Class");
            assertTrue("absent HTML → empty", result.isEmpty());
        } finally {
            System.clearProperty("vimacs.javadoc.path");
            deleteTree(tmpDir);
        }
    }

    // -----------------------------------------------------------------------
    // ライブテスト（Javadoc インストール済みの場合のみ）
    // -----------------------------------------------------------------------

    private static void testLiveArrayListSummary(JdkJavadocReader reader) {
        Optional<String> s = reader.readSummary("java.util.ArrayList");
        assertTrue("live: ArrayList summary present", s.isPresent());
        assertTrue("live: ArrayList summary non-empty", !s.get().isEmpty());
        System.out.println("  ArrayList summary: " + s.get());
    }

    private static void testLiveStringSummary(JdkJavadocReader reader) {
        Optional<String> s = reader.readSummary("java.lang.String");
        assertTrue("live: String summary present", s.isPresent());
        System.out.println("  String summary: " + s.get());
    }

    // -----------------------------------------------------------------------
    // ヘルパー
    // -----------------------------------------------------------------------

    private static void assertTrue(String name, boolean cond) {
        if (cond) pass(name); else fail(name);
    }

    private static void pass(String name) {
        System.out.println("  PASS: " + name);
        passed++;
    }

    private static void fail(String name) {
        System.out.println("  FAIL: " + name);
        failed++;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var s = Files.walk(root)) {
            s.sorted(java.util.Comparator.reverseOrder())
             .forEach(p -> { try { Files.delete(p); } catch (Exception e) {} });
        }
    }
}
