package dev.javatexteditor.substitute;

import java.util.regex.Pattern;

/**
 * VimRegexTranslator（Vim magicモード正規表現 -> Java正規表現 変換）の単体テスト。
 * 自作テストハーネス（mainメソッド形式・JUnit不使用）。
 */
public class VimRegexTranslatorTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testGroupingEscape();
        testUnescapedParenIsLiteral();
        testPlusQuantifier();
        testQuestionAndEqualsQuantifier();
        testBraceQuantifier();
        testAlternation();
        testUnescapedBraceAndPipeAreLiteral();
        testWordBoundary();
        testCharacterClasses();
        testShorthandClassesPassThrough();
        testAnchorsPassThrough();
        testEscapedMetacharactersPassThrough();
        testUnknownEscapeBecomesLiteral();

        System.out.println();
        System.out.println("=== VimRegexTranslator: " + pass + " passed, " + fail + " failed ===");
        if (fail > 0) System.exit(1);
    }

    static void testGroupingEscape() {
        System.out.println("[\\( \\) はグループ化]");
        check("\\(a\\)", "(a)");
        matches("\\(ab\\)\\+", "abab");
    }

    static void testUnescapedParenIsLiteral() {
        System.out.println("[無エスケープの ( ) はリテラル]");
        check("f(x)", "f\\(x\\)");
        matches("f(x)", "f(x)");
    }

    static void testPlusQuantifier() {
        System.out.println("[\\+ は1回以上]");
        check("a\\+", "a+");
        matches("a\\+", "aaa");
    }

    static void testQuestionAndEqualsQuantifier() {
        System.out.println("[\\? \\= は0または1回]");
        check("a\\?", "a?");
        check("a\\=", "a?");
        matches("colou\\?r", "color");
        matches("colou\\?r", "colour");
    }

    static void testBraceQuantifier() {
        System.out.println("[\\{n,m} は繰り返し回数指定]");
        check("a\\{2,3}", "a{2,3}");
        matches("a\\{2,3}", "aa");
        matches("a\\{2,3}", "aaa");
    }

    static void testAlternation() {
        System.out.println("[\\| はOR]");
        check("foo\\|bar", "foo|bar");
        matches("foo\\|bar", "bar");
    }

    static void testUnescapedBraceAndPipeAreLiteral() {
        System.out.println("[無エスケープの { | はリテラル]");
        check("a{1}", "a\\{1}");
        check("a|b", "a\\|b");
        matches("a|b", "a|b");
    }

    static void testWordBoundary() {
        System.out.println("[\\< \\> は単語境界]");
        check("\\<foo\\>", "\\bfoo\\b");
        matches("\\<foo\\>", "foo");
    }

    static void testCharacterClasses() {
        System.out.println("[[...] の中身はそのまま通す（外側の \\+ は量指定子として変換される）]");
        check("[abc]\\+", "[abc]+");
        matches("[abc]\\+", "cab");
    }

    static void testShorthandClassesPassThrough() {
        System.out.println("[\\d \\w \\s は同じ意味のまま]");
        check("\\d\\w\\s", "\\d\\w\\s");
        matches("\\d\\+", "123");
    }

    static void testAnchorsPassThrough() {
        System.out.println("[^ $ はそのまま行頭・行末]");
        check("^foo$", "^foo$");
        matches("^foo$", "foo");
    }

    static void testEscapedMetacharactersPassThrough() {
        System.out.println("[\\. \\* \\^ \\$ はリテラルエスケープのまま]");
        check("a\\.b", "a\\.b");
        matches("a\\.b", "a.b");
    }

    static void testUnknownEscapeBecomesLiteral() {
        System.out.println("[未知の \\X はリテラルのXとして扱う]");
        check("\\z", "z");
    }

    // -------------------------------------------------------------------------

    static void check(String vimPattern, String expectedJavaRegex) {
        String actual = VimRegexTranslator.translate(vimPattern);
        boolean ok = actual.equals(expectedJavaRegex);
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + vimPattern + " -> " + actual
                + (ok ? "" : " (expected " + expectedJavaRegex + ")"));
        if (ok) pass++; else fail++;
    }

    static void matches(String vimPattern, String input) {
        String javaRegex = VimRegexTranslator.translate(vimPattern);
        boolean ok = Pattern.compile(javaRegex).matcher(input).find();
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": \"" + input + "\" が " + javaRegex + " に一致する");
        if (ok) pass++; else fail++;
    }
}
