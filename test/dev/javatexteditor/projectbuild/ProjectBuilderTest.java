package dev.javatexteditor.projectbuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * F10（プロジェクト全体コンパイル）と F11（main クラス検索）の純粋ロジック部分を検証する
 * （mainメソッド形式のテストハーネス。GUI/子プロセス起動を伴う実際の F11 実行部分は対象外）。
 */
public class ProjectBuilderTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testCompileSuccessCreatesClassFiles();
        testCompileFailureReportsErrorDiagnostics();
        testCompileWithNoJavaFiles();
        testHasCompiledClassesFalseBeforeCompile();
        testHasCompiledClassesTrueAfterCompile();
        testCompileSkipsBinDirectory();
        testBinDirForFallsBackWhenNoSrcAncestor();
        testBinDirForUsesProjectRootWhenSrcIsDirectChild();
        testBinDirForClimbsToSrcAncestorWhenCwdIsInsideSrc();
        testCompileAndRunUseSameBinDirWhenCwdIsInsideSrc();

        testFindMainClassWithPackage();
        testFindMainClassWithoutPackage();
        testFindMultipleMainClasses();
        testFindMainClassIgnoresNonMainClass();
        testFindMainClassSkipsBinDirectory();

        testCompileResultIncludesJavacCommand();
        testCompileResultCommandIncludesExtraClasspath();
        testCompileResultCommandEmptyWhenSourceScanFails();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void assertTrue(String name, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name);
            fail++;
        }
    }

    static void assertEquals(String name, Object expected, Object actual) {
        assertTrue(name + " (expected=" + expected + ", actual=" + actual + ")",
            expected == null ? actual == null : expected.equals(actual));
    }

    static void testCompileSuccessCreatesClassFiles() throws IOException {
        Path dir = Files.createTempDirectory("pb-success");
        Files.writeString(dir.resolve("Hello.java"),
            "public class Hello { public static void main(String[] a) { System.out.println(\"hi\"); } }");

        BuildResult result = new ProjectBuilder().compile(dir);
        assertTrue("compile success", result.success());
        assertEquals("fileCount", 1, result.fileCount());
        assertTrue("bin/Hello.class exists",
            Files.exists(dir.resolve("bin").resolve("Hello.class")));
    }

    static void testCompileFailureReportsErrorDiagnostics() throws IOException {
        Path dir = Files.createTempDirectory("pb-failure");
        Files.writeString(dir.resolve("Broken.java"),
            "public class Broken { this is not valid java }");

        BuildResult result = new ProjectBuilder().compile(dir);
        assertTrue("compile reports failure", !result.success());
        assertTrue("has at least one error diagnostic",
            result.diagnostics().stream().anyMatch(BuildDiagnostic::isError));
    }

    static void testCompileWithNoJavaFiles() throws IOException {
        Path dir = Files.createTempDirectory("pb-empty");
        BuildResult result = new ProjectBuilder().compile(dir);
        assertTrue("compile fails with no sources", !result.success());
        assertEquals("fileCount is 0", 0, result.fileCount());
        assertTrue("errorMessage set", result.errorMessage() != null);
    }

    static void testHasCompiledClassesFalseBeforeCompile() throws IOException {
        Path dir = Files.createTempDirectory("pb-no-bin");
        Files.writeString(dir.resolve("Hello.java"), "public class Hello {}");
        assertTrue("no bin/ yet", !new ProjectBuilder().hasCompiledClasses(dir));
    }

    static void testHasCompiledClassesTrueAfterCompile() throws IOException {
        Path dir = Files.createTempDirectory("pb-has-bin");
        Files.writeString(dir.resolve("Hello.java"), "public class Hello {}");
        ProjectBuilder builder = new ProjectBuilder();
        builder.compile(dir);
        assertTrue("bin/ has classes after compile", builder.hasCompiledClasses(dir));
    }

    static void testCompileSkipsBinDirectory() throws IOException {
        // bin/ に紛れ込んだ .java（通常は起こらないが）はソース走査対象から除外されることを確認
        Path dir = Files.createTempDirectory("pb-skip-bin");
        Files.writeString(dir.resolve("Hello.java"), "public class Hello {}");
        Path binDir = dir.resolve("bin");
        Files.createDirectories(binDir);
        Files.writeString(binDir.resolve("Stray.java"), "this is not valid java at all {{{");

        BuildResult result = new ProjectBuilder().compile(dir);
        assertTrue("only Hello.java compiled (bin/ skipped)", result.success());
        assertEquals("fileCount excludes bin/Stray.java", 1, result.fileCount());
    }

    static void testBinDirForFallsBackWhenNoSrcAncestor() throws IOException {
        // どの祖先ディレクトリにも src/ が存在しない場合は projectRoot/bin にフォールバックする
        Path dir = Files.createTempDirectory("pb-no-src-ancestor");
        Path expected = dir.resolve("bin");
        assertEquals("falls back to projectRoot/bin", expected, new ProjectBuilder().binDirFor(dir));
    }

    static void testBinDirForUsesProjectRootWhenSrcIsDirectChild() throws IOException {
        // projectRoot 自身が src/ の親ディレクトリなら、そのまま projectRoot/bin を使う
        Path dir = Files.createTempDirectory("pb-src-direct-child");
        Files.createDirectories(dir.resolve("src"));
        Path expected = dir.resolve("bin");
        assertEquals("uses projectRoot/bin directly", expected, new ProjectBuilder().binDirFor(dir));
    }

    static void testBinDirForClimbsToSrcAncestorWhenCwdIsInsideSrc() throws IOException {
        // :cd で src/ 配下の深い場所に移動していても、src/ の親ディレクトリまで遡って bin を置く
        Path projectDir = Files.createTempDirectory("pb-climb-to-src");
        Files.createDirectories(projectDir.resolve("src").resolve("dev").resolve("javatexteditor"));
        Path cwd = projectDir.resolve("src").resolve("dev").resolve("javatexteditor");

        Path expected = projectDir.resolve("bin");
        assertEquals("climbs up to the src/ parent directory", expected, new ProjectBuilder().binDirFor(cwd));
    }

    static void testCompileAndRunUseSameBinDirWhenCwdIsInsideSrc() throws IOException {
        // F10（コンパイル）とF11（実行）の両方が同じ bin/ を見ることを確認する
        Path projectDir = Files.createTempDirectory("pb-compile-from-inside-src");
        Path pkgDir = projectDir.resolve("src").resolve("dev").resolve("javatexteditor");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Hello.java"),
            "public class Hello { public static void main(String[] a) {} }");

        ProjectBuilder builder = new ProjectBuilder();
        BuildResult result = builder.compile(pkgDir);
        assertTrue("compile from inside src/ succeeds", result.success());
        assertTrue("bin/Hello.class exists at src/ sibling",
            Files.exists(projectDir.resolve("bin").resolve("Hello.class")));
        assertTrue("hasCompiledClasses true when queried from inside src/",
            builder.hasCompiledClasses(pkgDir));
    }

    static void testFindMainClassWithPackage() throws IOException {
        Path dir = Files.createTempDirectory("mcf-pkg");
        Path pkgDir = dir.resolve("com").resolve("example");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("App.java"),
            "package com.example;\npublic class App { public static void main(String[] args) {} }");

        List<String> found = new MainClassFinder().findMainClasses(dir);
        assertEquals("finds com.example.App", List.of("com.example.App"), found);
    }

    static void testFindMainClassWithoutPackage() throws IOException {
        Path dir = Files.createTempDirectory("mcf-nopkg");
        Files.writeString(dir.resolve("App.java"),
            "public class App { public static void main(String[] args) {} }");

        List<String> found = new MainClassFinder().findMainClasses(dir);
        assertEquals("finds App (no package)", List.of("App"), found);
    }

    static void testFindMultipleMainClasses() throws IOException {
        Path dir = Files.createTempDirectory("mcf-multi");
        Files.writeString(dir.resolve("Alpha.java"),
            "public class Alpha { public static void main(String[] args) {} }");
        Files.writeString(dir.resolve("Beta.java"),
            "public class Beta { public static void main(String[] args) {} }");

        List<String> found = new MainClassFinder().findMainClasses(dir);
        assertEquals("finds both classes sorted", List.of("Alpha", "Beta"), found);
    }

    static void testFindMainClassIgnoresNonMainClass() throws IOException {
        Path dir = Files.createTempDirectory("mcf-none");
        Files.writeString(dir.resolve("Util.java"),
            "public class Util { public static int add(int a, int b) { return a + b; } }");

        List<String> found = new MainClassFinder().findMainClasses(dir);
        assertTrue("no main class found", found.isEmpty());
    }

    static void testFindMainClassSkipsBinDirectory() throws IOException {
        Path dir = Files.createTempDirectory("mcf-skip-bin");
        Files.writeString(dir.resolve("App.java"),
            "public class App { public static void main(String[] args) {} }");
        Path binDir = dir.resolve("bin");
        Files.createDirectories(binDir);
        Files.writeString(binDir.resolve("Stray.java"),
            "public class Stray { public static void main(String[] args) {} }");

        List<String> found = new MainClassFinder().findMainClasses(dir);
        assertEquals("bin/ excluded from main class search", List.of("App"), found);
    }

    static void testCompileResultIncludesJavacCommand() throws IOException {
        Path dir = Files.createTempDirectory("pb-command");
        Files.writeString(dir.resolve("Hello.java"),
            "public class Hello { public static void main(String[] a) {} }");

        BuildResult result = new ProjectBuilder().compile(dir);
        assertTrue("command starts with javac", result.command().startsWith("javac "));
        assertTrue("command references bin output dir",
            result.command().contains(dir.resolve("bin").toString()));
        assertTrue("command references the source file",
            result.command().contains(dir.resolve("Hello.java").toString()));
    }

    static void testCompileResultCommandIncludesExtraClasspath() throws IOException {
        Path dir = Files.createTempDirectory("pb-command-cp");
        Files.writeString(dir.resolve("Hello.java"),
            "public class Hello { public static void main(String[] a) {} }");
        Path extra = Files.createTempDirectory("pb-command-cp-extra");

        BuildResult result = new ProjectBuilder().compile(dir, List.of(extra));
        assertTrue("command includes -cp", result.command().contains("-cp"));
        assertTrue("command includes extra classpath dir", result.command().contains(extra.toString()));
    }

    static void testCompileResultCommandEmptyWhenSourceScanFails() throws IOException {
        Path dir = Files.createTempDirectory("pb-command-empty");
        BuildResult result = new ProjectBuilder().compile(dir);
        assertTrue("command is empty when there are no sources to compile",
            result.command() != null && result.command().isEmpty());
    }
}
