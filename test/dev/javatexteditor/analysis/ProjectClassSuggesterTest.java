package dev.javatexteditor.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ProjectClassSuggester のテスト。
 * 一時ディレクトリに複数パッケージの .java ファイルを作り、
 * 単純名からのFQN解決・別パッケージ検出・ファイル名不一致の除外を検証する。
 */
public class ProjectClassSuggesterTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws IOException {
        Path tmp = Files.createTempDirectory("pcs-test");
        try {
            Path pkgA = tmp.resolve("src/com/example/foo");
            Path pkgB = tmp.resolve("src/com/example/bar");
            Files.createDirectories(pkgA);
            Files.createDirectories(pkgB);

            Files.writeString(pkgA.resolve("Helper.java"),
                "package com.example.foo;\n\npublic class Helper {\n}\n");
            Files.writeString(pkgB.resolve("Widget.java"),
                "package com.example.bar;\n\npublic class Widget {\n}\n");
            // ファイル名とクラス名が一致しない場合は候補に含めない（内部クラス誤検出防止）
            Files.writeString(pkgB.resolve("Container.java"),
                "package com.example.bar;\n\npublic class Container {\n  class Nested {}\n}\n");

            ProjectClassSuggester suggester = new ProjectClassSuggester();

            List<String> helperCandidates = suggester.suggest(tmp, "Helper");
            assertTrue("別パッケージのクラスが候補に出る",
                helperCandidates.contains("com.example.foo.Helper"));

            List<String> widgetCandidates = suggester.suggest(tmp, "Widget");
            assertTrue("Widgetクラスが候補に出る",
                widgetCandidates.contains("com.example.bar.Widget"));

            List<String> nestedCandidates = suggester.suggest(tmp, "Nested");
            assertTrue("ファイル名と不一致の内部クラスは候補に出ない",
                nestedCandidates.isEmpty());

            List<String> unknownCandidates = suggester.suggest(tmp, "NoSuchClassXyz");
            assertTrue("存在しないクラス名は候補なし", unknownCandidates.isEmpty());

            List<String> nullBaseDir = suggester.suggest(null, "Helper");
            assertTrue("baseDirがnullなら空", nullBaseDir.isEmpty());

            System.out.println("\n=== ProjectClassSuggesterTest: " + passed + "/" + (passed + failed) + " passed ===");
            if (failed > 0) System.exit(1);
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }

    private static void assertTrue(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS: " + label);
        } else {
            failed++;
            System.out.println("  FAIL: " + label);
        }
    }
}
