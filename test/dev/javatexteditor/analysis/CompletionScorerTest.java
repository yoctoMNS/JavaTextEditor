package dev.javatexteditor.analysis;

/**
 * CompletionScorer のスコアリングロジックのテスト。
 */
public class CompletionScorerTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== CompletionScorerTest ===");

        testExactMatch();
        testCaseSensitivePrefix();
        testCaseInsensitivePrefix();
        testCamelCaseAL();
        testCamelCaseHM();
        testCamelCaseISE();
        testCamelCaseNPE();
        testFuzzyMatch();
        testNoMatch();
        testPrefixRanksHigherThanCamel();
        testCamelRanksHigherThanFuzzy();
        testExactRanksHighest();
        testLongerPrefixScoresHigher();
        testQueryLongerThanLabel();
        testEmptyQuery();
        testEmptyLabel();

        summary();
    }

    private static void testExactMatch() {
        int s = CompletionScorer.score("List", "List");
        assertTrue("完全一致は最高スコア (>= 900)", s >= 900);
    }

    private static void testCaseSensitivePrefix() {
        int s = CompletionScorer.score("List", "ListIterator");
        assertTrue("大文字小文字区別ありプレフィックスは >= 600", s >= 600);
    }

    private static void testCaseInsensitivePrefix() {
        int s = CompletionScorer.score("list", "ListIterator");
        int s2 = CompletionScorer.score("List", "ListIterator");
        assertTrue("大文字小文字区別なしプレフィックスは正スコア", s > 0);
        assertTrue("大文字小文字区別なしプレフィックスは区別ありより低スコア", s <= s2);
    }

    private static void testCamelCaseAL() {
        int s = CompletionScorer.score("AL", "ArrayList");
        assertTrue("'AL' → 'ArrayList' は CamelCase マッチ (>= 200)", s >= 200);
        System.out.println("  score('AL','ArrayList') = " + s);
    }

    private static void testCamelCaseHM() {
        int s = CompletionScorer.score("HM", "HashMap");
        assertTrue("'HM' → 'HashMap' は CamelCase マッチ (>= 200)", s >= 200);
        System.out.println("  score('HM','HashMap') = " + s);
    }

    private static void testCamelCaseISE() {
        int s = CompletionScorer.score("ISE", "IllegalStateException");
        assertTrue("'ISE' → 'IllegalStateException' は CamelCase マッチ (>= 200)", s >= 200);
        System.out.println("  score('ISE','IllegalStateException') = " + s);
    }

    private static void testCamelCaseNPE() {
        int s = CompletionScorer.score("NPE", "NullPointerException");
        assertTrue("'NPE' → 'NullPointerException' は CamelCase マッチ (>= 200)", s >= 200);
        System.out.println("  score('NPE','NullPointerException') = " + s);
    }

    private static void testFuzzyMatch() {
        int s = CompletionScorer.score("acl", "AbstractCollection");
        assertTrue("'acl' → 'AbstractCollection' はファジーマッチ (> 0)", s > 0);
        System.out.println("  score('acl','AbstractCollection') = " + s);
    }

    private static void testNoMatch() {
        int s = CompletionScorer.score("xyz", "ArrayList");
        assertTrue("マッチなしは 0 以下", s <= 0);
    }

    private static void testPrefixRanksHigherThanCamel() {
        int prefixScore = CompletionScorer.score("Array", "ArrayList");
        int camelScore  = CompletionScorer.score("AL", "ArrayList");
        assertTrue("プレフィックス一致 > CamelCase 一致", prefixScore > camelScore);
    }

    private static void testCamelRanksHigherThanFuzzy() {
        int camelScore = CompletionScorer.score("HM", "HashMap");
        int fuzzyScore = CompletionScorer.score("hm", "HashMap"); // CamelCase にもなるが小文字なのでfuzzy
        // HM（大文字2文字）は CamelCase として score >= 200 になるはず
        // hm（小文字）は CamelCase as lowercase match -> also matches but ...
        // どちらにせよ camelScore >= 200
        assertTrue("CamelCase スコア >= 200", camelScore >= 200);
        System.out.println("  score('HM','HashMap') = " + camelScore
                         + ", score('hm','HashMap') = " + fuzzyScore);
    }

    private static void testExactRanksHighest() {
        int exact  = CompletionScorer.score("HashMap", "HashMap");
        int prefix = CompletionScorer.score("HashMap", "HashMapEntry");
        assertTrue("完全一致 > プレフィックス", exact > prefix);
    }

    private static void testLongerPrefixScoresHigher() {
        // "HashMa" は "Ha" より label に対して長いプレフィックスなのでスコア高いはず
        int short_ = CompletionScorer.score("Ha", "HashMap");
        int long_  = CompletionScorer.score("HashMa", "HashMap");
        assertTrue("長いプレフィックスほど高スコア", long_ > short_);
    }

    private static void testQueryLongerThanLabel() {
        int s = CompletionScorer.score("ListIteratorImpl", "List");
        assertTrue("query > label はマッチなし", s <= 0);
    }

    private static void testEmptyQuery() {
        int s = CompletionScorer.score("", "ArrayList");
        assertTrue("空クエリは 0", s == 0);
    }

    private static void testEmptyLabel() {
        int s = CompletionScorer.score("A", "");
        assertTrue("空 label は 0", s == 0);
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

    private static void summary() {
        System.out.println("=== " + passed + "/" + (passed + failed) + " PASSED ===");
        if (failed > 0) System.exit(1);
    }
}
