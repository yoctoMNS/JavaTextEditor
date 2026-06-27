package dev.vimacs.analysis;

import dev.vimacs.buffer.PieceTable;
import java.util.List;
import java.util.Map;

/**
 * AutoImportHandler / ImportSuggester のテスト。
 * JDK インデックスを buildSync() で同期構築してテストする。
 */
public class AutoImportHandlerTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // JDK インデックスを同期構築（テスト環境で確実に使えるようにする）
        JdkClassIndex index;
        try {
            index = JdkClassIndex.buildSync();
        } catch (Exception e) {
            System.out.println("SKIP: JDK index unavailable (" + e.getMessage() + ")");
            return;
        }

        ImportSuggester suggester = new ImportSuggester(index);
        SourceAnalyzer sourceAnalyzer = new SourceAnalyzer();
        AutoImportHandler handler = new AutoImportHandler(suggester, sourceAnalyzer);

        testFindMissingSymbols(handler);
        testFindImportInsertOffset(handler);
        testApplyImportNoImports(handler);
        testApplyImportAfterExistingImport(handler);
        testApplyImportDuplicateSkipped(handler);
        testApplyImports(handler);
        testResolveCandidates(handler, index);
        testImportSuggesterSuggestNew(suggester, sourceAnalyzer);

        System.out.println("\n=== AutoImportHandlerTest: " + passed + "/" + (passed + failed) + " passed ===");
        if (failed > 0) System.exit(1);
    }

    // ----- findMissingSymbols -----

    private static void testFindMissingSymbols(AutoImportHandler handler) {
        // エラーなし → 空
        List<CompileDiagnostic> noErrors = List.of();
        assertEqual("findMissingSymbols empty", List.of(), handler.findMissingSymbols(noErrors));

        // cannot find symbol: class List
        CompileDiagnostic d1 = new CompileDiagnostic(5, 4,
            "error: cannot find symbol\n  symbol:   class List", DiagnosticKind.ERROR);
        List<String> result1 = handler.findMissingSymbols(List.of(d1));
        assertEqual("findMissingSymbols class", List.of("List"), result1);

        // interface
        CompileDiagnostic d2 = new CompileDiagnostic(6, 4,
            "error: cannot find symbol\n  symbol:   interface Runnable", DiagnosticKind.ERROR);
        List<String> result2 = handler.findMissingSymbols(List.of(d2));
        assertEqual("findMissingSymbols interface", List.of("Runnable"), result2);

        // 重複排除
        CompileDiagnostic d3 = new CompileDiagnostic(7, 4,
            "error: cannot find symbol\n  symbol:   class List", DiagnosticKind.ERROR);
        List<String> result3 = handler.findMissingSymbols(List.of(d1, d3));
        assertEqual("findMissingSymbols dedup", List.of("List"), result3);

        // WARNING は無視
        CompileDiagnostic w1 = new CompileDiagnostic(3, 2,
            "warning: symbol:   class Foo", DiagnosticKind.WARNING);
        assertEqual("findMissingSymbols warning ignored", List.of(), handler.findMissingSymbols(List.of(w1)));

        // enum
        CompileDiagnostic d4 = new CompileDiagnostic(8, 4,
            "error: cannot find symbol\n  symbol:   enum TimeUnit", DiagnosticKind.ERROR);
        List<String> result4 = handler.findMissingSymbols(List.of(d4));
        assertEqual("findMissingSymbols enum", List.of("TimeUnit"), result4);
    }

    // ----- findImportInsertOffset -----

    private static void testFindImportInsertOffset(AutoImportHandler handler) {
        // package + imports
        String src1 = "package foo.bar;\nimport java.util.List;\nclass X {}";
        int off1 = handler.findImportInsertOffset(src1);
        // 末尾 import 行（行1）の次 = "package foo.bar;\n".length() + "import java.util.List;\n".length()
        int expected1 = "package foo.bar;\n".length() + "import java.util.List;\n".length();
        assertEqual("insertOffset after import", expected1, off1);

        // package のみ
        String src2 = "package foo.bar;\nclass X {}";
        int off2 = handler.findImportInsertOffset(src2);
        int expected2 = "package foo.bar;\n".length();
        assertEqual("insertOffset after package", expected2, off2);

        // 何もなし
        String src3 = "class X {}";
        int off3 = handler.findImportInsertOffset(src3);
        assertEqual("insertOffset empty", 0, off3);

        // 複数 import
        String src4 = "package p;\nimport java.util.List;\nimport java.util.Map;\nclass X {}";
        int off4 = handler.findImportInsertOffset(src4);
        int expected4 = "package p;\n".length() + "import java.util.List;\n".length() + "import java.util.Map;\n".length();
        assertEqual("insertOffset multiple imports", expected4, off4);
    }

    // ----- applyImport -----

    private static void testApplyImportNoImports(AutoImportHandler handler) {
        String src = "package foo;\nclass X { List x; }";
        PieceTable buf = new PieceTable(src);
        boolean inserted = handler.applyImport("java.util.List", buf);
        assertTrue("applyImport returns true", inserted);
        String result = buf.getText();
        assertTrue("import line present", result.contains("import java.util.List;\n"));
        // 挿入位置: package 行の次
        int pkgEnd = src.indexOf('\n') + 1;
        assertTrue("import after package", result.startsWith("package foo;\nimport java.util.List;"));
    }

    private static void testApplyImportAfterExistingImport(AutoImportHandler handler) {
        String src = "package foo;\nimport java.util.Map;\nclass X {}";
        PieceTable buf = new PieceTable(src);
        handler.applyImport("java.util.List", buf);
        String result = buf.getText();
        // 新しい import は最後の import の後
        int mapIdx = result.indexOf("import java.util.Map;");
        int listIdx = result.indexOf("import java.util.List;");
        assertTrue("List import present", listIdx >= 0);
        assertTrue("List after Map", listIdx > mapIdx);
    }

    private static void testApplyImportDuplicateSkipped(AutoImportHandler handler) {
        String src = "package foo;\nimport java.util.List;\nclass X {}";
        PieceTable buf = new PieceTable(src);
        boolean inserted = handler.applyImport("java.util.List", buf);
        assertFalse("duplicate not inserted", inserted);
        // テキストは変わらない
        assertEqual("text unchanged", src, buf.getText());
    }

    private static void testApplyImports(AutoImportHandler handler) {
        String src = "package foo;\nclass X {}";
        PieceTable buf = new PieceTable(src);
        List<String> inserted = handler.applyImports(
            List.of("java.util.List", "java.util.Map"), buf);
        assertEqual("applyImports count", 2, inserted.size());
        String result = buf.getText();
        assertTrue("List present", result.contains("import java.util.List;"));
        assertTrue("Map present", result.contains("import java.util.Map;"));
    }

    // ----- resolveCandidates -----

    private static void testResolveCandidates(AutoImportHandler handler, JdkClassIndex index) {
        // エラーなし → 空マップ
        Map<String, List<String>> c0 = handler.resolveCandidates(List.of(), "class X {}");
        assertTrue("resolveCandidates empty", c0.isEmpty());

        // 既にインポート済みなら候補から除外
        if (!index.lookup("List").isEmpty()) {
            String src = "package foo;\nimport java.util.List;\nclass X {}";
            CompileDiagnostic d = new CompileDiagnostic(3, 0,
                "error: cannot find symbol\n  symbol:   class List", DiagnosticKind.ERROR);
            Map<String, List<String>> c1 = handler.resolveCandidates(List.of(d), src);
            // java.util.List は既インポートなので除外されているはず
            if (c1.containsKey("List")) {
                assertFalse("already-imported excluded",
                    c1.get("List").contains("java.util.List"));
            } else {
                passed++;
                System.out.println("PASS: resolveCandidates already-imported excluded");
            }
        }

        // 存在しないシンボル → 候補なし → マップにエントリなし
        CompileDiagnostic dUnknown = new CompileDiagnostic(1, 0,
            "error: cannot find symbol\n  symbol:   class ZzZzUnknownXxX", DiagnosticKind.ERROR);
        Map<String, List<String>> c2 =
            handler.resolveCandidates(List.of(dUnknown), "class X {}");
        assertFalse("unknown symbol not in map", c2.containsKey("ZzZzUnknownXxX"));
    }

    // ----- ImportSuggester.suggestNew -----

    private static void testImportSuggesterSuggestNew(
            ImportSuggester suggester, SourceAnalyzer sourceAnalyzer) {
        // すでに import 済みのものは除外
        if (!suggester.suggest("List").isEmpty()) {
            String src = "import java.util.List;\nclass X {}";
            SourceIndex idx;
            try {
                idx = sourceAnalyzer.analyzeText(src);
            } catch (AnalysisException e) {
                System.out.println("SKIP: SourceAnalyzer unavailable");
                return;
            }
            List<String> candidates = suggester.suggestNew("List", idx);
            assertFalse("suggestNew excludes existing", candidates.contains("java.util.List"));
            assertTrue("alreadyImported true",
                suggester.alreadyImported("java.util.List", idx));
            assertFalse("alreadyImported false for new",
                suggester.alreadyImported("java.util.Map", idx));
        }
    }

    // ----- assertion helpers -----

    private static void assertTrue(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS: " + name);
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    private static void assertFalse(String name, boolean condition) {
        assertTrue(name, !condition);
    }

    private static <T> void assertEqual(String name, T expected, T actual) {
        if (expected.equals(actual)) {
            passed++;
            System.out.println("PASS: " + name);
        } else {
            failed++;
            System.out.println("FAIL: " + name + " expected=" + expected + " actual=" + actual);
        }
    }
}
