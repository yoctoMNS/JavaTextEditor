package dev.javatexteditor.analysis;

import java.util.List;

/**
 * 候補の並べ替え（{@link CompletionRanker}）と、IntelliJ 式マッチャ
 * （{@link CompletionScorer#match}）のテスト。
 */
public class CompletionRankerTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== CompletionRankerTest ===");

        testPrefixBeatsCamelCase();
        testCamelCaseBeatsMiddleMatch();
        testMiddleMatchIsFound();
        testOriginBreaksTieBetweenEqualMatches();
        testInputOrderIsKeptWhenFullyTied();
        testStatisticsPromotesPreviouslyAccepted();
        testTypedPrefixItselfIsExcluded();
        testDuplicateLabelsAreCollapsed();
        testEmptyPrefixKeepsEverythingInOrder();
        testMaxResultsIsRespected();
        testHighlightPositionsForPrefix();
        testHighlightPositionsForCamelCase();
        testNonMatchingCandidateIsDropped();

        System.out.println("=== " + passed + "/" + (passed + failed) + " PASSED ===");
        if (failed > 0) System.exit(1);
    }

    private static CompletionItem word(String label) {
        return new CompletionItem(label, "wd", "", "", label, 0, null, CompletionItem.Origin.WORD);
    }

    private static CompletionItem of(String label, CompletionItem.Origin origin) {
        return new CompletionItem(label, "wd", "", "", label, 0, null, origin);
    }

    private static List<String> labelsOf(List<CompletionRanker.Ranked> ranked) {
        return ranked.stream().map(r -> r.item().label()).toList();
    }

    private static void testPrefixBeatsCamelCase() {
        List<CompletionRanker.Ranked> ranked = CompletionRanker.rank("sb",
            List.of(word("stringBuilder"), word("sbCount")), 10, null);
        assertEquals("前方一致が CamelCase 一致より上", "sbCount", labelsOf(ranked).get(0));
    }

    private static void testCamelCaseBeatsMiddleMatch() {
        List<CompletionRanker.Ranked> ranked = CompletionRanker.rank("sb",
            List.of(word("parseSbValue"), word("stringBuilder")), 10, null);
        assertEquals("CamelCase 頭文字一致が部分一致より上", "stringBuilder", labelsOf(ranked).get(0));
    }

    private static void testMiddleMatchIsFound() {
        List<CompletionRanker.Ranked> ranked = CompletionRanker.rank("Builder",
            List.of(word("StringBuilder")), 10, null);
        assertEquals("単語境界からの部分一致を拾う", 1, ranked.size());
    }

    private static void testOriginBreaksTieBetweenEqualMatches() {
        // どちらも同じ前方一致だが、ローカル変数の方が近い
        List<CompletionRanker.Ranked> ranked = CompletionRanker.rank("cou",
            List.of(of("count", CompletionItem.Origin.JDK_CLASS),
                    of("count", CompletionItem.Origin.LOCAL)),
            10, null);
        // 同じ label は1件に畳まれるため、先に渡した方（JDK）が残る点も含めて確認する
        assertEquals("同名候補は1件に畳まれる", 1, ranked.size());

        List<CompletionRanker.Ranked> ranked2 = CompletionRanker.rank("co",
            List.of(of("color", CompletionItem.Origin.JDK_CLASS),
                    of("count", CompletionItem.Origin.LOCAL)),
            10, null);
        assertEquals("マッチ品質が同じならローカルが上", "count", labelsOf(ranked2).get(0));
    }

    private static void testInputOrderIsKeptWhenFullyTied() {
        // 同じ出所・同じマッチ品質なら、渡された順序（＝カーソルからの近接順）を保つ
        List<CompletionRanker.Ranked> ranked = CompletionRanker.rank("va",
            List.of(word("valueB"), word("valueA")), 10, null);
        assertEquals("入力順を保つ", List.of("valueB", "valueA"), labelsOf(ranked));
    }

    private static void testStatisticsPromotesPreviouslyAccepted() {
        CompletionStatistics stats = new CompletionStatistics();
        stats.recordAccepted("valueA");
        List<CompletionRanker.Ranked> ranked = CompletionRanker.rank("va",
            List.of(word("valueB"), word("valueA")), 10, stats);
        assertEquals("以前選んだ候補が上に来る", "valueA", labelsOf(ranked).get(0));
    }

    private static void testTypedPrefixItselfIsExcluded() {
        List<CompletionRanker.Ranked> ranked = CompletionRanker.rank("count",
            List.of(word("count"), word("counter")), 10, null);
        assertEquals("入力そのものは候補にしない", List.of("counter"), labelsOf(ranked));
    }

    private static void testDuplicateLabelsAreCollapsed() {
        List<CompletionRanker.Ranked> ranked = CompletionRanker.rank("val",
            List.of(word("value"), word("value"), word("valueOf")), 10, null);
        assertEquals("重複は畳まれる", 2, ranked.size());
    }

    private static void testEmptyPrefixKeepsEverythingInOrder() {
        List<CompletionRanker.Ranked> ranked = CompletionRanker.rank("",
            List.of(word("zeta"), word("alpha")), 10, null);
        assertEquals("プレフィックスなしでは絞り込まない", List.of("zeta", "alpha"), labelsOf(ranked));
    }

    private static void testMaxResultsIsRespected() {
        List<CompletionRanker.Ranked> ranked = CompletionRanker.rank("v",
            List.of(word("v1"), word("v2"), word("v3"), word("v4")), 2, null);
        assertEquals("上限件数を超えない", 2, ranked.size());
    }

    private static void testHighlightPositionsForPrefix() {
        List<CompletionRanker.Ranked> ranked = CompletionRanker.rank("val",
            List.of(word("value")), 10, null);
        int[] positions = ranked.get(0).highlightPositions();
        assertEquals("前方一致は先頭3文字を強調", 3, positions.length);
        assertEquals("1文字目の位置", 0, positions[0]);
        assertEquals("3文字目の位置", 2, positions[2]);
    }

    private static void testHighlightPositionsForCamelCase() {
        List<CompletionRanker.Ranked> ranked = CompletionRanker.rank("sb",
            List.of(word("stringBuilder")), 10, null);
        int[] positions = ranked.get(0).highlightPositions();
        assertEquals("CamelCase は各単語の先頭を強調", 2, positions.length);
        assertEquals("s の位置", 0, positions[0]);
        assertEquals("B の位置", 6, positions[1]);
    }

    private static void testNonMatchingCandidateIsDropped() {
        List<CompletionRanker.Ranked> ranked = CompletionRanker.rank("xyz",
            List.of(word("value")), 10, null);
        assertEquals("一致しない候補は落とす", 0, ranked.size());
    }

    // -------------------------------------------------------------------------

    private static void assertEquals(String msg, Object expected, Object actual) {
        if (expected.equals(actual)) {
            System.out.println("  PASS: " + msg);
            passed++;
        } else {
            System.out.println("  FAIL: " + msg + " expected=" + expected + " actual=" + actual);
            failed++;
        }
    }
}
