package dev.javatexteditor.terminal;

public class AnsiEscapeFilterTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testPlainTextPassesThrough();
        testStripsColorCode();
        testStripsCursorMovement();
        testStripsMultipleSequencesInOneChunk();
        testHandlesSequenceSplitAcrossChunks();
        testHandlesEscSplitAcrossChunks();
        testNonCsiEscapeSequence();
        testEmptyInput();
        testConsecutivePlainChunksAfterSequence();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void assertEquals(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        System.out.println((ok ? "  PASS: " : "  FAIL: ") + name
            + " (expected=" + show(expected) + ", actual=" + show(actual) + ")");
        if (ok) pass++; else fail++;
    }

    private static String show(Object o) {
        return o == null ? "null" : "\"" + o.toString().replace("\u001B", "<ESC>") + "\"";
    }

    static void testPlainTextPassesThrough() {
        assertEquals("plain text", "hello world", new AnsiEscapeFilter().filter("hello world"));
    }

    static void testStripsColorCode() {
        String input = "\u001B[31mred text\u001B[0m";
        assertEquals("color code stripped", "red text", new AnsiEscapeFilter().filter(input));
    }

    static void testStripsCursorMovement() {
        String input = "before\u001B[2Kafter";
        assertEquals("cursor movement stripped", "beforeafter", new AnsiEscapeFilter().filter(input));
    }

    static void testStripsMultipleSequencesInOneChunk() {
        String input = "\u001B[1ma\u001B[0mb\u001B[32mc\u001B[0m";
        assertEquals("multiple sequences stripped", "abc", new AnsiEscapeFilter().filter(input));
    }

    static void testHandlesSequenceSplitAcrossChunks() {
        AnsiEscapeFilter filter = new AnsiEscapeFilter();
        String out1 = filter.filter("before\u001B[3");
        String out2 = filter.filter("1mred\u001B[0mafter");
        assertEquals("split sequence part1", "before", out1);
        assertEquals("split sequence part2", "redafter", out2);
    }

    static void testHandlesEscSplitAcrossChunks() {
        AnsiEscapeFilter filter = new AnsiEscapeFilter();
        String out1 = filter.filter("x\u001B");
        String out2 = filter.filter("[31my");
        assertEquals("esc byte alone part1", "x", out1);
        assertEquals("esc byte alone part2", "y", out2);
    }

    static void testNonCsiEscapeSequence() {
        // ESC + 'M'（非CSI）は簡易的に2文字で終端とみなす。
        String input = "a\u001BMb";
        assertEquals("non-CSI escape stripped", "ab", new AnsiEscapeFilter().filter(input));
    }

    static void testEmptyInput() {
        assertEquals("empty input", "", new AnsiEscapeFilter().filter(""));
    }

    static void testConsecutivePlainChunksAfterSequence() {
        AnsiEscapeFilter filter = new AnsiEscapeFilter();
        filter.filter("\u001B[31m");
        String out = filter.filter("plain after color reset state");
        assertEquals("state resets to NORMAL after CSI terminator", "plain after color reset state", out);
    }
}
