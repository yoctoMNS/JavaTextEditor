package dev.javatexteditor.editor;

import java.util.List;
import java.util.regex.Pattern;

/**
 * BufferTextSearch（一致箇所の列挙と、カーソルから見た「次の一致」の選択）の単体テスト。
 * バッファにもカーソルにも依存しない純粋ロジックなので、文字列だけで検証できる。
 */
public class BufferTextSearchTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testFindAllReturnsMatchesInOrder();
        testFindAllReturnsEmptyWhenNoMatch();
        testFindAllTreatsZeroWidthMatchAsLengthOne();
        testSelectNearestForwardPicksFirstAfterCursor();
        testSelectNearestForwardWrapsToTop();
        testSelectNearestBackwardPicksLastBeforeCursor();
        testSelectNearestBackwardWrapsToBottom();
        testSelectNearestOnEmptyListReturnsMinusOne();
        testStepCyclesForwardAndBackward();
        testStepOnEmptyListReturnsMinusOne();

        System.out.println();
        System.out.println("PASS: " + passed + " / " + (passed + failed) + "  (FAIL: " + failed + ")");
        if (failed > 0) System.exit(1);
    }

    private static void testFindAllReturnsMatchesInOrder() {
        // "ab" は offset 0 / 4 / 8 の3箇所
        List<int[]> matches = BufferTextSearch.findAll("ab__ab__ab", Pattern.compile("ab"));
        check("3件見つかる", matches.size() == 3);
        check("オフセット昇順で返る",
                matches.get(0)[0] == 0 && matches.get(1)[0] == 4 && matches.get(2)[0] == 8);
        check("長さが記録される", matches.get(0)[1] == 2);
    }

    private static void testFindAllReturnsEmptyWhenNoMatch() {
        check("一致なしなら空リスト",
                BufferTextSearch.findAll("hello", Pattern.compile("zzz")).isEmpty());
    }

    private static void testFindAllTreatsZeroWidthMatchAsLengthOne() {
        // \b はゼロ幅。ハイライトできるよう長さ1に補正される
        List<int[]> matches = BufferTextSearch.findAll("ab cd", Pattern.compile("\\b"));
        check("ゼロ幅一致も1件以上見つかる", !matches.isEmpty());
        boolean allAtLeastOne = matches.stream().allMatch(m -> m[1] >= 1);
        check("ゼロ幅一致の長さは1に補正される", allAtLeastOne);
    }

    private static List<int[]> sampleMatches() {
        // オフセット 10 / 20 / 30 の3件
        return List.of(new int[]{10, 2}, new int[]{20, 2}, new int[]{30, 2});
    }

    private static void testSelectNearestForwardPicksFirstAfterCursor() {
        check("前方検索: カーソル15 → offset20（index 1）",
                BufferTextSearch.selectNearest(sampleMatches(), 15, true) == 1);
        check("前方検索: カーソルが一致位置ちょうど(20)なら次の一致へ",
                BufferTextSearch.selectNearest(sampleMatches(), 20, true) == 2);
    }

    private static void testSelectNearestForwardWrapsToTop() {
        check("前方検索: 最後の一致より後ろなら先頭へ折り返す",
                BufferTextSearch.selectNearest(sampleMatches(), 999, true) == 0);
    }

    private static void testSelectNearestBackwardPicksLastBeforeCursor() {
        check("後方検索: カーソル25 → offset20（index 1）",
                BufferTextSearch.selectNearest(sampleMatches(), 25, false) == 1);
    }

    private static void testSelectNearestBackwardWrapsToBottom() {
        check("後方検索: 最初の一致より前なら末尾へ折り返す",
                BufferTextSearch.selectNearest(sampleMatches(), 0, false) == 2);
    }

    private static void testSelectNearestOnEmptyListReturnsMinusOne() {
        check("一致が無ければ -1", BufferTextSearch.selectNearest(List.of(), 5, true) == -1);
    }

    private static void testStepCyclesForwardAndBackward() {
        List<int[]> m = sampleMatches();
        check("n: 0 → 1", BufferTextSearch.step(m, 0, true) == 1);
        check("n: 末尾(2) → 先頭(0) へ循環", BufferTextSearch.step(m, 2, true) == 0);
        check("N: 1 → 0", BufferTextSearch.step(m, 1, false) == 0);
        check("N: 先頭(0) → 末尾(2) へ循環", BufferTextSearch.step(m, 0, false) == 2);
    }

    private static void testStepOnEmptyListReturnsMinusOne() {
        check("一致が無ければ step も -1", BufferTextSearch.step(List.of(), 0, true) == -1);
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS: " + label);
        } else {
            failed++;
            System.out.println("FAIL [" + label + "]");
        }
    }
}
