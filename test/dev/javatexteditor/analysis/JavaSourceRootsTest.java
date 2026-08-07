package dev.javatexteditor.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * {@link JavaSourceRoots} と、それを使うようになった
 * {@link CompileAnalyzer#analyzeWithProject} のクロスファイル解決を検証する。
 *
 * <p>背景（2026-08 メモリ肥大化の修正）: {@code analyzeWithProject} は以前、作業ディレクトリ配下の
 * 全 {@code .java} を読み込んで毎回まるごとコンパイルしていた。編集のたびに数百MBを割り当てて
 * ヒープを膨張させる原因だったため、「編集中のバッファ1件だけをコンパイル対象にし、他は
 * {@code -sourcepath} 経由で必要な分だけ javac に読ませる」方式へ変更した。
 * 本テストは、この変更で <b>クロスパッケージのシンボル解決能力が落ちていないこと</b>
 * （＝誤検知の "cannot find symbol" が出ないこと）と、
 * <b>未解決シンボルの検出は従来どおり効くこと</b>（auto-import の入力になるため）を担保する。
 */
public class JavaSourceRootsTest {

    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) throws Exception {
        testSourcePathFromPackageDeclaration();
        testSourcePathForDefaultPackage();
        testSourcePathForNestedSourceRoot();
        testSourcePathForUnsavedBuffer();
        testCrossPackageSymbolResolves();
        testMissingImportStillReported();
        testUnrelatedProjectFilesAreNotCompiled();
        testNoFalsePositivesOnThisProject();

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        System.exit(fail > 0 ? 1 : 0);
    }

    // -------------------------------------------------------------------------
    // sourcePathFor の導出規則
    // -------------------------------------------------------------------------

    /** package 宣言のぶんだけ親へ遡ったディレクトリが sourcepath に含まれる。 */
    static void testSourcePathFromPackageDeclaration() throws IOException {
        Path root = createTempProject();
        try {
            Path file = root.resolve("src/com/example/app/Main.java");
            writeFile(file, "package com.example.app;\npublic class Main {}\n");
            String sp = JavaSourceRoots.sourcePathFor(root, file.toString(),
                "package com.example.app;\npublic class Main {}\n");
            check("package宣言からソースルートを導出する",
                true, containsPath(sp, root.resolve("src")));
        } finally {
            deleteRecursively(root);
        }
    }

    /** package 宣言が無いファイルは、そのファイルが置かれたディレクトリ自体がソースルートになる。 */
    static void testSourcePathForDefaultPackage() throws IOException {
        Path root = createTempProject();
        try {
            Path file = root.resolve("Source.java");
            writeFile(file, "class Source {}\n");
            String sp = JavaSourceRoots.sourcePathFor(root, file.toString(), "class Source {}\n");
            check("デフォルトパッケージではファイルのあるディレクトリがルートになる",
                true, containsPath(sp, root));
        } finally {
            deleteRecursively(root);
        }
    }

    /** src/main/java のような入れ子のレイアウトでも正しいルートが求まる。 */
    static void testSourcePathForNestedSourceRoot() throws IOException {
        Path root = createTempProject();
        try {
            String src = "package org.demo;\npublic class Deep {}\n";
            Path file = root.resolve("src/main/java/org/demo/Deep.java");
            writeFile(file, src);
            String sp = JavaSourceRoots.sourcePathFor(root, file.toString(), src);
            check("src/main/java レイアウトのソースルートを導出する",
                true, containsPath(sp, root.resolve("src/main/java")));
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * 未保存バッファ（{@code "<buffer>"}）でも例外にならず、プロジェクト走査で得た
     * ソースルートだけが返る。
     */
    static void testSourcePathForUnsavedBuffer() throws IOException {
        Path root = createTempProject();
        try {
            writeFile(root.resolve("src/com/example/Existing.java"),
                "package com.example;\npublic class Existing {}\n");
            JavaSourceRoots.clearCache();
            String sp = JavaSourceRoots.sourcePathFor(root, "<buffer>", "class Tmp {}\n");
            check("未保存バッファでもプロジェクト走査でルートが得られる",
                true, containsPath(sp, root.resolve("src")));
        } finally {
            deleteRecursively(root);
        }
    }

    // -------------------------------------------------------------------------
    // analyzeWithProject のクロスファイル解決（本修正で壊してはならない挙動）
    // -------------------------------------------------------------------------

    /** 別パッケージのクラス・メソッドが sourcepath 経由で解決され、誤ったエラーが出ない。 */
    static void testCrossPackageSymbolResolves() throws Exception {
        Path root = createTempProject();
        try {
            writeFile(root.resolve("src/lib/Helper.java"),
                "package lib;\npublic class Helper {\n    public static int answer() { return 42; }\n}\n");
            String appSource =
                "package app;\n"
                + "import lib.Helper;\n"
                + "public class App {\n"
                + "    int value = Helper.answer();\n"
                + "}\n";
            Path appFile = root.resolve("src/app/App.java");
            writeFile(appFile, appSource);

            JavaSourceRoots.clearCache();
            List<CompileDiagnostic> diags = new CompileAnalyzer()
                .analyzeWithProject(appFile.toString(), appSource, root);
            check("別パッケージのクラスがエラーなしで解決される (diags=" + diags + ")",
                0L, diags.stream().filter(d -> d.kind() == DiagnosticKind.ERROR).count());
        } finally {
            deleteRecursively(root);
        }
    }

    /** import が欠けている場合は従来どおり "cannot find symbol" が返る（auto-import の入力）。 */
    static void testMissingImportStillReported() throws Exception {
        Path root = createTempProject();
        try {
            writeFile(root.resolve("src/lib/Helper.java"),
                "package lib;\npublic class Helper {\n    public static int answer() { return 42; }\n}\n");
            String appSource =
                "package app;\n"
                + "public class App {\n"
                + "    int value = Helper.answer();\n"   // import が無い
                + "}\n";
            Path appFile = root.resolve("src/app/App.java");
            writeFile(appFile, appSource);

            JavaSourceRoots.clearCache();
            List<CompileDiagnostic> diags = new CompileAnalyzer()
                .analyzeWithProject(appFile.toString(), appSource, root);
            boolean reported = diags.stream()
                .anyMatch(d -> d.kind() == DiagnosticKind.ERROR
                    && d.message().contains("cannot find symbol"));
            check("import 欠落は cannot find symbol として検出される", true, reported);
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * 編集中のファイルが参照していないプロジェクト内のファイルは解析対象にならない。
     *
     * <p>旧実装は「作業ディレクトリ配下の全 {@code .java} を毎回コンパイルする」方式だったため、
     * 無関係なファイルの構文エラーでもコンパイル全体が巻き添えになり、ファイル数に比例して
     * 時間とメモリを消費していた。ここでは <b>意図的に壊れたファイルを大量に置いても</b>
     * 対象ファイルの診断が空のままであることを確認する。壊れたファイルが解析されていれば
     * javac は先へ進めず、この検証は失敗する。
     */
    static void testUnrelatedProjectFilesAreNotCompiled() throws Exception {
        Path root = createTempProject();
        try {
            for (int i = 0; i < 50; i++) {
                writeFile(root.resolve("src/junk/Broken" + i + ".java"),
                    "package junk;\nclass Broken" + i + " { this is not java at all ###\n");
            }
            String appSource = "package app;\npublic class App {\n    int value = 1;\n}\n";
            Path appFile = root.resolve("src/app/App.java");
            writeFile(appFile, appSource);

            JavaSourceRoots.clearCache();
            List<CompileDiagnostic> diags = new CompileAnalyzer()
                .analyzeWithProject(appFile.toString(), appSource, root);
            check("参照していない壊れたファイルは解析対象にならない (diags=" + diags + ")",
                0, diags.size());
        } finally {
            deleteRecursively(root);
        }
    }

    /**
     * このプロジェクト自身のソース（コンパイルが通っている＝診断は0件が正解）を実際に解析し、
     * sourcepath 方式が誤検知を出さないことを確認する。
     *
     * <p>ソースルートの導出を誤ると「他のパッケージのクラスが見つからない」という誤った
     * {@code cannot find symbol} が大量に出るため、この確認は本方式の要になる。
     * 全ファイル（154件・約4.7秒）だと遅いので、パッケージが散らばるようサブディレクトリごとに
     * 先頭1ファイルだけを抜き出して検証する。プロジェクトのレイアウトが分からない環境
     * （{@code src} が無い等）では黙ってスキップする。
     */
    static void testNoFalsePositivesOnThisProject() throws Exception {
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path src = root.resolve("src");
        if (!Files.isDirectory(src)) {
            System.out.println("[SKIP] src/ が無いため誤検知チェックは省略");
            return;
        }
        List<Path> samples = new java.util.ArrayList<>();
        java.util.Set<Path> seenDirs = new java.util.LinkedHashSet<>();
        try (var paths = Files.walk(src)) {
            for (Path p : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                if (seenDirs.add(p.getParent())) samples.add(p);
            }
        }
        JavaSourceRoots.clearCache();
        CompileAnalyzer analyzer = new CompileAnalyzer();
        List<String> unexpected = new java.util.ArrayList<>();
        for (Path file : samples) {
            List<CompileDiagnostic> diags =
                analyzer.analyzeWithProject(file.toString(), Files.readString(file), root);
            if (!diags.isEmpty()) unexpected.add(root.relativize(file) + " -> " + diags);
        }
        check("プロジェクト自身の各パッケージ" + samples.size() + "ファイルで誤検知が出ない"
            + (unexpected.isEmpty() ? "" : " " + unexpected), 0, unexpected.size());
    }

    // -------------------------------------------------------------------------
    // ヘルパー
    // -------------------------------------------------------------------------

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + (ok ? "" : " -> expected=" + expected + " actual=" + actual));
        if (ok) pass++;
    }

    static Path createTempProject() throws IOException {
        return Files.createTempDirectory("sourceroots");
    }

    static void writeFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** sourcepath 文字列（File.pathSeparator 区切り）に指定ディレクトリが含まれるか。 */
    static boolean containsPath(String sourcePath, Path expected) {
        String normalized = expected.toAbsolutePath().normalize().toString();
        for (String entry : sourcePath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (entry.equals(normalized)) return true;
        }
        return false;
    }

    static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
