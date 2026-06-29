package dev.javatexteditor.analysis;

import java.nio.file.Path;
import java.util.List;

/**
 * CompletionIndex のテスト。
 * buildSync() を使って同期的にインデックスを構築し検証する。
 */
public class CompletionIndexTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== CompletionIndexTest ===");

        // JdkClassIndex を先に同期構築
        JdkClassIndex jdkIndex = JdkClassIndex.buildSync();
        SourceAnalyzer analyzer = new SourceAnalyzer();

        // プロジェクトディレクトリ（src/）を補完対象に含める
        Path projectRoot = Path.of("src");

        long start = System.currentTimeMillis();
        CompletionIndex ci = CompletionIndex.buildSync(jdkIndex, projectRoot, analyzer);
        long elapsed = System.currentTimeMillis() - start;

        testBuildTime(elapsed);
        testIsReady(ci);
        testQueryJdkClass(ci);
        testQueryCaseExact(ci);
        testQueryPrefix(ci);
        testQueryEmpty(ci);
        testQueryMaxResults(ci);
        testQueryNoMatch(ci);
        testQueryProjectClass(ci);
        testQueryProjectMethod(ci);
        testKindField(ci);

        // allSimpleNames テスト
        testAllSimpleNames(jdkIndex);

        summary();
    }

    // -------------------------------------------------------------------------
    // テストケース
    // -------------------------------------------------------------------------

    private static void testBuildTime(long elapsed) {
        // インデックス構築は JDK 走査を含むが 30 秒以内に終わるはず
        assertTrue("buildSync() は 30 秒以内に完了する", elapsed < 30_000);
        System.out.println("  buildSync() elapsed: " + elapsed + " ms");
    }

    private static void testIsReady(CompletionIndex ci) {
        assertTrue("buildSync() 後は isReady() == true", ci.isReady());
    }

    private static void testQueryJdkClass(CompletionIndex ci) {
        // "List" は java.util.List の単純名として登録されるはず
        List<CompletionItem> results = ci.query("List", 10);
        assertTrue("'List' で JDK クラスが返る", !results.isEmpty());
        boolean found = results.stream().anyMatch(it -> it.label().equals("List"));
        assertTrue("'List' という label が含まれる", found);
    }

    private static void testQueryCaseExact(CompletionIndex ci) {
        // 大文字始まりのプレフィックス
        List<CompletionItem> upper = ci.query("Array", 10);
        assertTrue("'Array' で候補が返る", !upper.isEmpty());
        // 小文字では一致しない（Java 識別子は大文字小文字区別あり）
        List<CompletionItem> lower = ci.query("array", 10);
        // JDK には toLowerCase の "array" から始まるクラスが存在しないので空のはず
        // ただしプロジェクトに "array" で始まる識別子があれば返ることがある – そこはスキップ
        System.out.println("  'array' results: " + lower.size() + " (case-sensitive check)");
    }

    private static void testQueryPrefix(CompletionIndex ci) {
        // "String" プレフィックスで StringBuffer, StringBuilder, String 等が返るはず
        List<CompletionItem> results = ci.query("String", 20);
        assertTrue("'String' プレフィックスで複数候補", results.size() > 1);
        for (CompletionItem it : results) {
            assertTrue("全結果が 'String' で始まる", it.label().startsWith("String"));
        }
    }

    private static void testQueryEmpty(CompletionIndex ci) {
        List<CompletionItem> results = ci.query("", 10);
        assertTrue("空文字列クエリは空リストを返す", results.isEmpty());
    }

    private static void testQueryMaxResults(CompletionIndex ci) {
        // "S" は大量にヒットするはず
        List<CompletionItem> results = ci.query("S", 5);
        assertTrue("maxResults=5 で結果が 5 件以下", results.size() <= 5);
    }

    private static void testQueryNoMatch(CompletionIndex ci) {
        List<CompletionItem> results = ci.query("ZzZzZqQqQ", 10);
        assertTrue("マッチしないプレフィックスは空リスト", results.isEmpty());
    }

    private static void testQueryProjectClass(CompletionIndex ci) {
        // このプロジェクト自身の "PieceTable" クラスが返るはず
        List<CompletionItem> results = ci.query("PieceTable", 10);
        assertTrue("プロジェクトクラス 'PieceTable' が補完候補に含まれる", !results.isEmpty());
        boolean found = results.stream().anyMatch(it -> it.label().equals("PieceTable"));
        assertTrue("'PieceTable' という label が返る", found);
        // kind は "cls" のはず
        CompletionItem item = results.stream()
            .filter(it -> it.label().equals("PieceTable")).findFirst().orElseThrow();
        assertEquals("PieceTable の kind は 'cls'", "cls", item.kind());
    }

    private static void testQueryProjectMethod(CompletionIndex ci) {
        // SourceAnalyzer が analyzeFile() というメソッドを持つはず
        List<CompletionItem> results = ci.query("analyzeFile", 10);
        assertTrue("プロジェクトメソッド 'analyzeFile' が補完候補に含まれる", !results.isEmpty());
        boolean found = results.stream().anyMatch(it -> it.label().equals("analyzeFile"));
        assertTrue("'analyzeFile' という label が返る", found);
    }

    private static void testKindField(CompletionIndex ci) {
        // kind の値が "cls"/"mth"/"fld" のいずれかであることを確認
        List<CompletionItem> results = ci.query("List", 10);
        for (CompletionItem it : results) {
            assertTrue("kind は cls/mth/fld のいずれか",
                it.kind().equals("cls") || it.kind().equals("mth") || it.kind().equals("fld"));
        }
    }

    private static void testAllSimpleNames(JdkClassIndex jdkIndex) {
        java.util.Set<String> names = jdkIndex.allSimpleNames();
        assertTrue("allSimpleNames() は空でない", !names.isEmpty());
        assertTrue("allSimpleNames() に 'List' が含まれる", names.contains("List"));
        assertTrue("allSimpleNames() に 'String' が含まれる", names.contains("String"));
        assertTrue("allSimpleNames() に 'HashMap' が含まれる", names.contains("HashMap"));
        System.out.println("  allSimpleNames() size: " + names.size());
    }

    // -------------------------------------------------------------------------
    // ユーティリティ
    // -------------------------------------------------------------------------

    private static void assertTrue(String msg, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + msg);
            passed++;
        } else {
            System.out.println("  FAIL: " + msg);
            failed++;
        }
    }

    private static void assertEquals(String msg, String expected, String actual) {
        if (expected.equals(actual)) {
            System.out.println("  PASS: " + msg);
            passed++;
        } else {
            System.out.println("  FAIL: " + msg + " (expected=" + expected + ", actual=" + actual + ")");
            failed++;
        }
    }

    private static void summary() {
        System.out.println("=== " + passed + "/" + (passed + failed) + " PASSED ===");
        if (failed > 0) System.exit(1);
    }
}
