package dev.javatexteditor.substitute;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VimReplacementBuilder（\1 & バックスラッシュ+u/U/l/L/e/E の展開）の単体テスト。
 * 自作テストハーネス（mainメソッド形式・JUnit不使用）。
 */
public class VimReplacementBuilderTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testBackreference();
        testAmpersandWholeMatch();
        testEscapedAmpersandIsLiteral();
        testEscapedBackslashIsLiteral();
        testUpperOneShot();
        testLowerOneShot();
        testUpperRangeUntilEnd();
        testLowerRangeThenEscapeStopsIt();
        testOneShotOverridesRangeForSingleChar();
        testCombinationOfBackreferenceAndCase();
        testPlainTextPassThrough();
        testOptionalGroupNotMatchedBecomesEmpty();

        System.out.println();
        System.out.println("=== VimReplacementBuilder: " + pass + " passed, " + fail + " failed ===");
        if (fail > 0) System.exit(1);
    }

    static void testBackreference() {
        System.out.println("[\\1 \\2 は後方参照]");
        check("(hello) (world)", "hello world", "\\2 \\1", "world hello");
    }

    static void testAmpersandWholeMatch() {
        System.out.println("[& はマッチ全体]");
        check("foo", "foo", "[&]", "[foo]");
    }

    static void testEscapedAmpersandIsLiteral() {
        System.out.println("[\\& はリテラルの&]");
        check("foo", "foo", "\\&", "&");
    }

    static void testEscapedBackslashIsLiteral() {
        System.out.println("[\\\\ はリテラルの\\]");
        check("foo", "foo", "\\\\", "\\");
    }

    static void testUpperOneShot() {
        System.out.println("[\\u は直後の1文字のみ大文字化]");
        check("(\\w+)", "hello", "\\u\\1", "Hello");
    }

    static void testLowerOneShot() {
        System.out.println("[\\l は直後の1文字のみ小文字化]");
        check("(\\w+)", "HELLO", "\\l\\1", "hELLO");
    }

    static void testUpperRangeUntilEnd() {
        System.out.println("[\\U は\\e/\\Eまたは末尾まで大文字化し続ける]");
        check("(\\w+) (\\w+)", "hello world", "\\U\\1 \\2", "HELLO WORLD");
    }

    static void testLowerRangeThenEscapeStopsIt() {
        System.out.println("[\\L ... \\E で範囲変換を終了できる]");
        check("(\\w+) (\\w+)", "HELLO WORLD", "\\L\\1\\E \\2", "hello WORLD");
    }

    static void testOneShotOverridesRangeForSingleChar() {
        System.out.println("[\\U 中でも \\u/\\l は直後の1文字だけ上書きする]");
        check("(\\w+) (\\w+)", "hello world", "\\U\\1 \\l\\2", "HELLO wORLD");
    }

    static void testCombinationOfBackreferenceAndCase() {
        System.out.println("[\\u\\1 と \\U\\2 を同時に使う組み合わせ]");
        check("(\\w+) (\\w+)", "hello world", "\\u\\1 \\U\\2", "Hello WORLD");
    }

    static void testPlainTextPassThrough() {
        System.out.println("[置換文字列にVim特殊記法が無ければそのまま]");
        check("foo", "foo", "bar", "bar");
    }

    static void testOptionalGroupNotMatchedBecomesEmpty() {
        System.out.println("[マッチしなかった省略可能グループは空文字列扱い]");
        check("(a)(b)?", "a", "[\\1][\\2]", "[a][]");
    }

    // -------------------------------------------------------------------------

    static void check(String javaRegex, String input, String vimReplacement, String expected) {
        Matcher m = Pattern.compile(javaRegex).matcher(input);
        if (!m.find()) {
            System.out.println("  FAIL: パターンが一致しない: " + javaRegex + " / " + input);
            fail++;
            return;
        }
        String actual = VimReplacementBuilder.build(m, vimReplacement);
        boolean ok = actual.equals(expected);
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + vimReplacement + " -> " + actual
                + (ok ? "" : " (expected " + expected + ")"));
        if (ok) pass++; else fail++;
    }
}
