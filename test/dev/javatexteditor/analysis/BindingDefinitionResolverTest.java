package dev.javatexteditor.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * BindingDefinitionResolver（Eclipse JDT 流バインディング解決）の純粋ロジックテスト。
 * mainメソッド形式のテストハーネス（JUnit不使用）。
 *
 * 既存の正規表現ヒューリスティック（ProjectSymbolResolver/ReceiverTypeResolver）では
 * 原理的に不可能だった解決（オーバーロード区別・ブロックスコープ・implements 経由の
 * 継承メンバー・JDKシンボルの正確な FQCN 特定）を中心に検証する。
 */
public class BindingDefinitionResolverTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        test_overloadDistinction();
        test_localVariableShadowsField();
        test_crossFileResolution();
        test_interfaceDefaultMethodAcrossFiles();
        test_jdkMemberResolution();
        test_jdkClassResolution();
        test_nestedJdkClassResolvesToOuterFile();
        test_unnamedBufferLocalVariable();
        test_syntaxErrorReturnsNotFound();
        test_cursorOnWhitespaceReturnsNotFound();

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
        return Files.createTempDirectory("bdr-test");
    }

    static Path writeFile(Path dir, String relName, String content) throws IOException {
        Path p = dir.resolve(relName);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    /** text 内で marker が最初に現れる位置の文字オフセットを返す。 */
    static int offsetOf(String text, String marker) {
        int idx = text.indexOf(marker);
        if (idx < 0) throw new IllegalArgumentException("marker not found: " + marker);
        return idx;
    }

    /** text 内で marker が最初に現れる行番号（0-indexed）を返す。 */
    static int lineOf(String text, String marker) {
        int idx = offsetOf(text, marker);
        int line = 0;
        for (int i = 0; i < idx; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    // ── tests ──────────────────────────────────────────────────

    /**
     * オーバーロードの区別: 名前ベース検索（ProjectSymbolResolver）は「同名の最初の宣言」
     * にしかジャンプできないが、バインディング解決は実引数の型から正しいオーバーロードを選ぶ。
     */
    static void test_overloadDistinction() throws Exception {
        Path dir = tempDir();
        String text = """
            public class Overloads {
                void run(int x) {
                }
                void run(String s) {
                }
                void caller() {
                    run("hi");
                }
            }
            """;
        BindingDefinitionResolver resolver = new BindingDefinitionResolver();
        BindingDefinitionResolver.Resolution r =
            resolver.resolve(text, null, offsetOf(text, "run(\"hi\")"), dir);

        assertTrue("overload: ProjectLocation が返る",
            r instanceof BindingDefinitionResolver.ProjectLocation);
        if (r instanceof BindingDefinitionResolver.ProjectLocation loc) {
            assertEquals("overload: String版の宣言行へ解決される",
                lineOf(text, "void run(String s)"), loc.lineNumber());
        }
    }

    /** ブロックスコープ: 同名のフィールドが存在しても、メソッド内ではローカル変数が優先される。 */
    static void test_localVariableShadowsField() throws Exception {
        Path dir = tempDir();
        String text = """
            public class Shadow {
                int value = 1;
                void f() {
                    String value = "local";
                    System.out.println(value);
                }
            }
            """;
        BindingDefinitionResolver resolver = new BindingDefinitionResolver();
        BindingDefinitionResolver.Resolution r =
            resolver.resolve(text, null, offsetOf(text, "value);"), dir);

        assertTrue("shadow: ProjectLocation が返る",
            r instanceof BindingDefinitionResolver.ProjectLocation);
        if (r instanceof BindingDefinitionResolver.ProjectLocation loc) {
            assertEquals("shadow: ローカル変数の宣言行（フィールドではなく）へ解決される",
                lineOf(text, "String value"), loc.lineNumber());
        }
    }

    /** プロジェクト内の別ファイルで宣言されたメソッドへの解決。 */
    static void test_crossFileResolution() throws Exception {
        Path dir = tempDir();
        String helper = """
            public class Helper {
                void doWork() {
                }
            }
            """;
        Path helperFile = writeFile(dir, "Helper.java", helper);
        String text = """
            public class Client {
                void f() {
                    Helper h = new Helper();
                    h.doWork();
                }
            }
            """;
        BindingDefinitionResolver resolver = new BindingDefinitionResolver();
        BindingDefinitionResolver.Resolution r =
            resolver.resolve(text, null, offsetOf(text, "doWork();"), dir);

        assertTrue("cross-file: ProjectLocation が返る",
            r instanceof BindingDefinitionResolver.ProjectLocation);
        if (r instanceof BindingDefinitionResolver.ProjectLocation loc) {
            assertEquals("cross-file: Helper.java のパスが返る",
                helperFile.toString(), loc.filePath());
            assertEquals("cross-file: doWork の宣言行へ解決される",
                lineOf(helper, "void doWork()"), loc.lineNumber());
        }
    }

    /**
     * implements 経由の継承メンバー解決: 既存ヒューリスティックは extends チェーンしか
     * 辿れない（インタフェースの default メソッドはスコープ外）が、バインディング解決は
     * javac の意味解析でそのまま正しい宣言に到達できる。
     */
    static void test_interfaceDefaultMethodAcrossFiles() throws Exception {
        Path dir = tempDir();
        String greeter = """
            public interface Greeter {
                default void greet() {
                }
            }
            """;
        Path greeterFile = writeFile(dir, "Greeter.java", greeter);
        writeFile(dir, "Impl.java", """
            public class Impl implements Greeter {
            }
            """);
        String text = """
            public class UseGreeter {
                void f() {
                    Impl i = new Impl();
                    i.greet();
                }
            }
            """;
        BindingDefinitionResolver resolver = new BindingDefinitionResolver();
        BindingDefinitionResolver.Resolution r =
            resolver.resolve(text, null, offsetOf(text, "greet();"), dir);

        assertTrue("default method: ProjectLocation が返る",
            r instanceof BindingDefinitionResolver.ProjectLocation);
        if (r instanceof BindingDefinitionResolver.ProjectLocation loc) {
            assertEquals("default method: Greeter.java のパスが返る",
                greeterFile.toString(), loc.filePath());
            assertEquals("default method: greet の宣言行へ解決される",
                lineOf(greeter, "default void greet()"), loc.lineNumber());
        }
    }

    /** JDK メンバー: List.add は java.base モジュールの java.util.List のメンバーとして返る。 */
    static void test_jdkMemberResolution() throws Exception {
        Path dir = tempDir();
        String text = """
            import java.util.List;
            public class UseList {
                void f(List<String> l) {
                    l.add("x");
                }
            }
            """;
        BindingDefinitionResolver resolver = new BindingDefinitionResolver();
        BindingDefinitionResolver.Resolution r =
            resolver.resolve(text, null, offsetOf(text, "add(\"x\")"), dir);

        assertTrue("JDK member: JdkElementLocation が返る",
            r instanceof BindingDefinitionResolver.JdkElementLocation);
        if (r instanceof BindingDefinitionResolver.JdkElementLocation jdk) {
            assertEquals("JDK member: FQCN は java.util.List", "java.util.List", jdk.fqcn());
            assertEquals("JDK member: メンバー名は add", "add", jdk.memberName());
            assertEquals("JDK member: モジュールは java.base", "java.base", jdk.moduleName());
        }
    }

    /** JDK クラス名そのもの: member は null（クラス自体）として返る。 */
    static void test_jdkClassResolution() throws Exception {
        Path dir = tempDir();
        String text = """
            public class UseString {
                String name;
            }
            """;
        BindingDefinitionResolver resolver = new BindingDefinitionResolver();
        BindingDefinitionResolver.Resolution r =
            resolver.resolve(text, null, offsetOf(text, "String name"), dir);

        assertTrue("JDK class: JdkElementLocation が返る",
            r instanceof BindingDefinitionResolver.JdkElementLocation);
        if (r instanceof BindingDefinitionResolver.JdkElementLocation jdk) {
            assertEquals("JDK class: FQCN は java.lang.String", "java.lang.String", jdk.fqcn());
            assertEquals("JDK class: member は null（クラス自体）", null, jdk.memberName());
        }
    }

    /**
     * ネストした JDK クラス（Map.Entry）: src.zip のエントリはトップレベルクラス単位のため、
     * FQCN は最外殻（java.util.Map）まで遡り、ネストクラス名はメンバー名として返す。
     */
    static void test_nestedJdkClassResolvesToOuterFile() throws Exception {
        Path dir = tempDir();
        String text = """
            import java.util.Map;
            public class UseEntry {
                Map.Entry<String, String> e;
            }
            """;
        BindingDefinitionResolver resolver = new BindingDefinitionResolver();
        BindingDefinitionResolver.Resolution r =
            resolver.resolve(text, null, offsetOf(text, "Entry<String"), dir);

        assertTrue("nested JDK class: JdkElementLocation が返る",
            r instanceof BindingDefinitionResolver.JdkElementLocation);
        if (r instanceof BindingDefinitionResolver.JdkElementLocation jdk) {
            assertEquals("nested JDK class: FQCN は最外殻の java.util.Map",
                "java.util.Map", jdk.fqcn());
            assertEquals("nested JDK class: member はネストクラス名 Entry",
                "Entry", jdk.memberName());
        }
    }

    /** 無名バッファ（currentFilePath == null）でも解決でき、filePath は null で返る。 */
    static void test_unnamedBufferLocalVariable() throws Exception {
        Path dir = tempDir();
        String text = """
            public class Unnamed {
                void f() {
                    int counter = 0;
                    counter++;
                }
            }
            """;
        BindingDefinitionResolver resolver = new BindingDefinitionResolver();
        BindingDefinitionResolver.Resolution r =
            resolver.resolve(text, null, offsetOf(text, "counter++"), dir);

        assertTrue("unnamed buffer: ProjectLocation が返る",
            r instanceof BindingDefinitionResolver.ProjectLocation);
        if (r instanceof BindingDefinitionResolver.ProjectLocation loc) {
            assertEquals("unnamed buffer: filePath は null（現在バッファ内）", null, loc.filePath());
            assertEquals("unnamed buffer: ローカル変数の宣言行へ解決される",
                lineOf(text, "int counter"), loc.lineNumber());
        }
    }

    /** 解析不能なテキストは例外を投げず NotFound を返す（graceful degradation）。 */
    static void test_syntaxErrorReturnsNotFound() throws Exception {
        Path dir = tempDir();
        String text = "public class {{{ broken !!";
        BindingDefinitionResolver resolver = new BindingDefinitionResolver();
        BindingDefinitionResolver.Resolution r =
            resolver.resolve(text, null, offsetOf(text, "broken"), dir);

        assertTrue("syntax error: NotFound が返る（クラッシュしない）",
            r instanceof BindingDefinitionResolver.NotFound);
    }

    /** カーソルが識別子の上にない場合は NotFound（呼び出し側がフォールバックする）。 */
    static void test_cursorOnWhitespaceReturnsNotFound() throws Exception {
        Path dir = tempDir();
        String text = """
            public class Blank {
                void f() {
                }
            }
            """;
        BindingDefinitionResolver resolver = new BindingDefinitionResolver();
        // "void f() {" の直後の改行位置（どの識別子でもない）
        int offset = offsetOf(text, "{\n    }");
        BindingDefinitionResolver.Resolution r = resolver.resolve(text, null, offset, dir);

        assertTrue("whitespace: NotFound が返る",
            r instanceof BindingDefinitionResolver.NotFound);
    }
}
