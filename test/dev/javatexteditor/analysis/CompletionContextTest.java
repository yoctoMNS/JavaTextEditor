package dev.javatexteditor.analysis;

/**
 * 補完文脈の判定（{@link CompletionContext}）のテスト。
 * 「いま何を補完しようとしているのか」を取り違えると候補集合ごと間違うため、
 * ドットの左側の式をどこまで遡るかを重点的に確認する。
 */
public class CompletionContextTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== CompletionContextTest ===");

        testPlainPrefix();
        testEmptyPrefixAtLineStart();
        testMemberWithoutPrefix();
        testMemberWithPrefix();
        testMemberOnMethodChain();
        testMemberOnNestedCall();
        testMemberOnArrayAccess();
        testMemberOnStringLiteral();
        testMemberOnQualifiedName();
        testThisReceiver();
        testNewContext();
        testNumericLiteralIsNotMember();
        testSimpleReceiverRejectsCompoundExpression();
        testCaretInsideIdentifier();

        System.out.println("=== " + passed + "/" + (passed + failed) + " PASSED ===");
        if (failed > 0) System.exit(1);
    }

    private static void testPlainPrefix() {
        CompletionContext ctx = CompletionContext.at("int val = coun", 14);
        assertEquals("修飾なしと判定", CompletionContext.Kind.PLAIN, ctx.kind());
        assertEquals("プレフィックスは coun", "coun", ctx.prefix());
        assertEquals("プレフィックス開始位置", 10, ctx.prefixStart());
    }

    private static void testEmptyPrefixAtLineStart() {
        CompletionContext ctx = CompletionContext.at("    ", 4);
        assertEquals("プレフィックスなし", "", ctx.prefix());
        assertTrue("メンバー補完ではない", !ctx.isMember());
    }

    private static void testMemberWithoutPrefix() {
        String text = "list.";
        CompletionContext ctx = CompletionContext.at(text, text.length());
        assertTrue("メンバー補完と判定", ctx.isMember());
        assertEquals("レシーバは list", "list", ctx.receiverText());
        assertEquals("プレフィックスは空", "", ctx.prefix());
    }

    private static void testMemberWithPrefix() {
        String text = "list.ad";
        CompletionContext ctx = CompletionContext.at(text, text.length());
        assertTrue("メンバー補完と判定", ctx.isMember());
        assertEquals("レシーバは list", "list", ctx.receiverText());
        assertEquals("プレフィックスは ad", "ad", ctx.prefix());
    }

    private static void testMemberOnMethodChain() {
        String text = "list.get(0).";
        CompletionContext ctx = CompletionContext.at(text, text.length());
        assertTrue("メンバー補完と判定", ctx.isMember());
        assertEquals("レシーバはチェーン全体", "list.get(0)", ctx.receiverText());
    }

    private static void testMemberOnNestedCall() {
        String text = "map.get(list.size()).";
        CompletionContext ctx = CompletionContext.at(text, text.length());
        assertTrue("メンバー補完と判定", ctx.isMember());
        assertEquals("入れ子の括弧を越えて先頭まで遡る", "map.get(list.size())", ctx.receiverText());
    }

    private static void testMemberOnArrayAccess() {
        String text = "args[0].";
        CompletionContext ctx = CompletionContext.at(text, text.length());
        assertTrue("メンバー補完と判定", ctx.isMember());
        assertEquals("レシーバは配列アクセス全体", "args[0]", ctx.receiverText());
    }

    private static void testMemberOnStringLiteral() {
        String text = "\"abc\".";
        CompletionContext ctx = CompletionContext.at(text, text.length());
        assertTrue("メンバー補完と判定", ctx.isMember());
        assertEquals("レシーバは文字列リテラル", "\"abc\"", ctx.receiverText());
    }

    private static void testMemberOnQualifiedName() {
        String text = "System.out.pri";
        CompletionContext ctx = CompletionContext.at(text, text.length());
        assertTrue("メンバー補完と判定", ctx.isMember());
        assertEquals("レシーバは System.out", "System.out", ctx.receiverText());
        assertEquals("プレフィックスは pri", "pri", ctx.prefix());
    }

    private static void testThisReceiver() {
        String text = "        this.na";
        CompletionContext ctx = CompletionContext.at(text, text.length());
        assertTrue("メンバー補完と判定", ctx.isMember());
        assertEquals("レシーバは this", "this", ctx.simpleReceiver());
    }

    private static void testNewContext() {
        String text = "Object o = new Str";
        CompletionContext ctx = CompletionContext.at(text, text.length());
        assertEquals("new の直後と判定", CompletionContext.Kind.NEW, ctx.kind());
        assertEquals("プレフィックスは Str", "Str", ctx.prefix());
    }

    private static void testNumericLiteralIsNotMember() {
        String text = "double d = 3.";
        CompletionContext ctx = CompletionContext.at(text, text.length());
        assertTrue("数値リテラルの小数点はメンバー補完にしない", !ctx.isMember());
    }

    private static void testSimpleReceiverRejectsCompoundExpression() {
        String text = "list.get(0).";
        CompletionContext ctx = CompletionContext.at(text, text.length());
        assertEquals("複合式は軽量解決の対象外", "", ctx.simpleReceiver());
    }

    private static void testCaretInsideIdentifier() {
        // "counter" の "coun" までにカーソルがある場合、後ろの "ter" はプレフィックスに含めない
        String text = "counter";
        CompletionContext ctx = CompletionContext.at(text, 4);
        assertEquals("カーソルまでがプレフィックス", "coun", ctx.prefix());
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

    private static void assertTrue(String msg, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + msg);
            passed++;
        } else {
            System.out.println("  FAIL: " + msg);
            failed++;
        }
    }
}
