package dev.javatexteditor.ui;

import java.util.List;

public class SyntaxHighlighterTest {
    public static void main(String[] args) {
        int pass = 0;
        int total = 0;

        // --- Java: キーワード・型・数値・文字列・行コメント ---
        String l1 = "public static int x = 42; // note";
        SyntaxHighlighter.LineResult r1 = SyntaxHighlighter.tokenizeLine(l1, SourceLanguage.JAVA, false);
        total++; pass += check("Java: publicはKEYWORD", kindOf(l1, r1.tokens(), "public") == SyntaxKind.KEYWORD);
        total++; pass += check("Java: staticはKEYWORD", kindOf(l1, r1.tokens(), "static") == SyntaxKind.KEYWORD);
        total++; pass += check("Java: int(基本型)はKEYWORD", kindOf(l1, r1.tokens(), "int") == SyntaxKind.KEYWORD);
        total++; pass += check("Java: 42はNUMBER", kindOf(l1, r1.tokens(), "42") == SyntaxKind.NUMBER);
        total++; pass += check("Java: 行コメントはCOMMENT", kindOf(l1, r1.tokens(), "note") == SyntaxKind.COMMENT);
        total++; pass += check("Java: 行末は複数行コメントを継続しない", !r1.endsInBlockComment());

        // --- Java: 文字列・PascalCase・ALL_CAPS ---
        String l2 = "String s = \"hello\"; MAX_COUNT = Foo.bar();";
        SyntaxHighlighter.LineResult r2 = SyntaxHighlighter.tokenizeLine(l2, SourceLanguage.JAVA, false);
        total++; pass += check("Java: StringはPascalCaseでTYPE", kindOf(l2, r2.tokens(), "String") == SyntaxKind.TYPE);
        total++; pass += check("Java: 文字列リテラルはSTRING", kindOf(l2, r2.tokens(), "hello") == SyntaxKind.STRING);
        total++; pass += check("Java: ALL_CAPS識別子(定数/マクロ)はKEYWORD", kindOf(l2, r2.tokens(), "MAX_COUNT") == SyntaxKind.KEYWORD);
        total++; pass += check("Java: PascalCaseクラス名はTYPE", kindOf(l2, r2.tokens(), "Foo") == SyntaxKind.TYPE);
        total++; pass += check("Java: 小文字識別子はDEFAULT", kindOf(l2, r2.tokens(), "bar") == SyntaxKind.DEFAULT);

        // --- Java: 複数行ブロックコメント ---
        String l3 = "/* start of comment";
        SyntaxHighlighter.LineResult r3 = SyntaxHighlighter.tokenizeLine(l3, SourceLanguage.JAVA, false);
        total++; pass += check("ブロックコメント開始行はCOMMENT", kindOf(l3, r3.tokens(), "start") == SyntaxKind.COMMENT);
        total++; pass += check("閉じられていないブロックコメントは継続する", r3.endsInBlockComment());

        String l4 = "still comment */ int y = 1;";
        SyntaxHighlighter.LineResult r4 = SyntaxHighlighter.tokenizeLine(l4, SourceLanguage.JAVA, true);
        total++; pass += check("継続中のブロックコメントの残りはCOMMENT", kindOf(l4, r4.tokens(), "still") == SyntaxKind.COMMENT);
        total++; pass += check("*/後のintはKEYWORDに戻る", kindOf(l4, r4.tokens(), "int") == SyntaxKind.KEYWORD);
        total++; pass += check("閉じたのでブロックコメントは継続しない", !r4.endsInBlockComment());

        boolean[] starts = SyntaxHighlighter.computeBlockCommentStarts(
            new String[]{"/* start", "middle", "end */ int z;"}, SourceLanguage.JAVA);
        total++; pass += check("computeBlockCommentStarts: 1行目は非継続", !starts[0]);
        total++; pass += check("computeBlockCommentStarts: 2行目はブロックコメント継続", starts[1]);
        total++; pass += check("computeBlockCommentStarts: 3行目もブロックコメント継続", starts[2]);

        // --- C: 型・キーワード・プリプロセッサ・16進数 ---
        String l5 = "unsigned char c = 0x1F; if (c) return;";
        SyntaxHighlighter.LineResult r5 = SyntaxHighlighter.tokenizeLine(l5, SourceLanguage.C, false);
        total++; pass += check("C: unsigned(基本型)はKEYWORD", kindOf(l5, r5.tokens(), "unsigned") == SyntaxKind.KEYWORD);
        total++; pass += check("C: char(基本型)はKEYWORD", kindOf(l5, r5.tokens(), "char") == SyntaxKind.KEYWORD);
        total++; pass += check("C: ifはKEYWORD", kindOf(l5, r5.tokens(), "if") == SyntaxKind.KEYWORD);
        total++; pass += check("C: returnはKEYWORD", kindOf(l5, r5.tokens(), "return") == SyntaxKind.KEYWORD);
        total++; pass += check("C: 0x1Fは16進数としてNUMBER", kindOf(l5, r5.tokens(), "0x1F") == SyntaxKind.NUMBER);

        SyntaxHighlighter.LineResult r6 = SyntaxHighlighter.tokenizeLine(
            "#include <stdio.h>", SourceLanguage.C, false);
        total++; pass += check("C: #includeはプリプロセッサ行全体がPREPROCESSOR",
            r6.tokens().size() == 1 && r6.tokens().get(0).kind() == SyntaxKind.PREPROCESSOR);

        // --- C: bool(基本型)とSDLK_LSHIFT(ALL_CAPSマクロ定数)はいずれもKEYWORD ---
        String l9 = "bool ok = SDLK_LSHIFT;";
        SyntaxHighlighter.LineResult r9 = SyntaxHighlighter.tokenizeLine(l9, SourceLanguage.C, false);
        total++; pass += check("C: bool(基本型)はKEYWORD", kindOf(l9, r9.tokens(), "bool") == SyntaxKind.KEYWORD);
        total++; pass += check("C: SDLK_LSHIFT(ALL_CAPS)はKEYWORD", kindOf(l9, r9.tokens(), "SDLK_LSHIFT") == SyntaxKind.KEYWORD);

        // --- 記号(SYMBOL)・演算子(OPERATOR) ---
        String l8 = "int x = (a + b) * 2;";
        SyntaxHighlighter.LineResult r8 = SyntaxHighlighter.tokenizeLine(l8, SourceLanguage.C, false);
        total++; pass += check("(はSYMBOL", kindOf(l8, r8.tokens(), "(") == SyntaxKind.SYMBOL);
        total++; pass += check(")はSYMBOL", kindOf(l8, r8.tokens(), ")") == SyntaxKind.SYMBOL);
        total++; pass += check(";はSYMBOL", kindOf(l8, r8.tokens(), ";") == SyntaxKind.SYMBOL);
        total++; pass += check("=はOPERATOR", kindOf(l8, r8.tokens(), "=") == SyntaxKind.OPERATOR);
        total++; pass += check("+はOPERATOR", kindOf(l8, r8.tokens(), "+") == SyntaxKind.OPERATOR);
        total++; pass += check("*はOPERATOR", kindOf(l8, r8.tokens(), "*") == SyntaxKind.OPERATOR);

        // --- 未対応言語は全体をDEFAULT ---
        SyntaxHighlighter.LineResult r7 = SyntaxHighlighter.tokenizeLine(
            "plain text", SourceLanguage.NONE, false);
        total++; pass += check("SourceLanguage.NONEは全体DEFAULT",
            r7.tokens().size() == 1 && r7.tokens().get(0).kind() == SyntaxKind.DEFAULT);

        // --- SourceLanguage.detect ---
        total++; pass += check("detect: .javaはJAVA", SourceLanguage.detect("Foo.java") == SourceLanguage.JAVA);
        total++; pass += check("detect: .cはC", SourceLanguage.detect("main.c") == SourceLanguage.C);
        total++; pass += check("detect: .hppはC", SourceLanguage.detect("foo.hpp") == SourceLanguage.C);
        total++; pass += check("detect: .txtはNONE", SourceLanguage.detect("readme.txt") == SourceLanguage.NONE);
        total++; pass += check("detect: nullパスはNONE", SourceLanguage.detect(null) == SourceLanguage.NONE);

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) {
            System.exit(1);
        }
    }

    static int check(String name, boolean condition) {
        System.out.println((condition ? "[OK] " : "[FAIL] ") + name);
        return condition ? 1 : 0;
    }

    /** line中のneedleの開始位置を含むトークンのkindを返す。 */
    private static SyntaxKind kindOf(String line, List<SyntaxToken> tokens, String needle) {
        int idx = line.indexOf(needle);
        if (idx < 0) throw new IllegalArgumentException("needle not found: " + needle);
        for (SyntaxToken t : tokens) {
            if (idx >= t.start() && idx < t.end()) return t.kind();
        }
        throw new IllegalStateException("no token covers offset " + idx);
    }
}
