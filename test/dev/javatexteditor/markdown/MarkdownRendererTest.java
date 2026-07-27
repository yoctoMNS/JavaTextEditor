package dev.javatexteditor.markdown;

import java.util.Arrays;

/**
 * {@link MarkdownRenderer}（Markdownソースを読みやすいプレーンテキストの閲覧ビューへ変換する
 * 純粋ロジック）のテストハーネス（mainメソッド形式・JUnit不使用）。
 */
public class MarkdownRendererTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testHeaderLine();
        testHeadingH1();
        testHeadingH2();
        testHeadingJapaneseUnderlineWidth();
        testHeadingH3ToH6KeepHashPrefix();
        testHeadingTrailingHashesStripped();
        testHeadingWithInlineFormatting();
        testEmptyHeadingNoUnderlineNoCrash();

        testHorizontalRuleVariants();
        testHorizontalRuleNotConfusedWithShortDashes();

        testFencedCodeBlockBacktick();
        testFencedCodeBlockTilde();
        testFencedCodeBlockWithInfoString();
        testFencedCodeBlockUnclosedExtendsToEndOfDocument();
        testFencedCodeBlockClosingMustBeAtLeastAsLongAsOpening();

        testBlockquoteSingleLevel();
        testBlockquoteNested();
        testBlockquoteNoSpaceAfterMarker();
        testBlockquoteAppliesInlineFormatting();

        testUnorderedListMarkersNormalizedToDash();
        testUnorderedListIndentPreserved();
        testTaskListItems();

        testOrderedListPreservedWithInlineFormatting();
        testOrderedListNotFalsePositiveMidSentence();

        testBoldStarAndUnderscore();
        testItalicStarAndUnderscore();
        testItalicDoesNotBreakSnakeCaseIdentifiers();
        testTripleAsteriskBoldItalicCombined();
        testInlineCodeStripsBackticks();
        testInlineCodeProtectsDunderFromBoldRegex();
        testMultipleInlineCodeSpansOnSameLine();
        testLinkRendersTextAndUrl();
        testImageWithAlt();
        testImageWithoutAlt();
        testImageNotDoubleProcessedByLinkRegex();

        testEmptySourceDoesNotCrash();
        testPlainParagraphPassthrough();
        testBlankLinesPreservedBetweenParagraphs();
        testTrailingNewlineProducesTrailingEmptyLine();

        testMixedDocumentIntegration();

        System.out.printf("%nPASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
        if (fail > 0) System.exit(1);
    }

    // ---------------------------------------------------------------
    // header / render の全体構成
    // ---------------------------------------------------------------

    static void testHeaderLine() {
        System.out.println("[header/render: ヘッダ行の形式]");
        String header = MarkdownRenderer.header("notes.md");
        check("ファイル名を含む", header.contains("notes.md"));
        check("*view* で始まる", header.startsWith("*view* "));
        check(":mark の案内を含む", header.contains(":mark"));

        String rendered = MarkdownRenderer.render("notes.md", "# Title");
        String[] lines = rendered.split("\n", -1);
        check("1行目はヘッダそのもの", lines[0].equals(header));
        check("2行目は空行（ヘッダと本文の区切り）", lines[1].isEmpty());
        check("3行目から本文が始まる", lines[2].equals("Title"));
    }

    // ---------------------------------------------------------------
    // 見出し（ATX heading）
    // ---------------------------------------------------------------

    static void testHeadingH1() {
        System.out.println("[見出し: H1はタイトル行+\"=\"下線]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "# Hello"));
        String[] lines = body.split("\n", -1);
        check("1行目はテキストそのまま", lines[0].equals("Hello"));
        check("2行目はテキストと同じ長さの=下線", lines[1].equals("=".repeat("Hello".length())));
    }

    static void testHeadingH2() {
        System.out.println("[見出し: H2はタイトル行+\"-\"下線]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "## World"));
        String[] lines = body.split("\n", -1);
        check("1行目はテキストそのまま", lines[0].equals("World"));
        check("2行目はテキストと同じ長さの-下線", lines[1].equals("-".repeat("World".length())));
    }

    static void testHeadingJapaneseUnderlineWidth() {
        System.out.println("[見出し: 全角文字は下線幅も2セル分としてカウントする]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "# こんにちは"));
        String[] lines = body.split("\n", -1);
        check("1行目はテキストそのまま", lines[0].equals("こんにちは"));
        // ひらがな5文字 × 2セル = 10
        check("下線幅は全角換算で10文字", lines[1].equals("=".repeat(10)));
    }

    static void testHeadingH3ToH6KeepHashPrefix() {
        System.out.println("[見出し: H3-H6は#接頭辞を保持する（フォントサイズ変更ができないため）]");
        String body = bodyOf(MarkdownRenderer.render("f.md",
            "### Sub\n#### Sub2\n##### Sub3\n###### Sub4"));
        String[] lines = body.split("\n", -1);
        check("H3はそのまま", lines[0].equals("### Sub"));
        check("H4はそのまま", lines[1].equals("#### Sub2"));
        check("H5はそのまま", lines[2].equals("##### Sub3"));
        check("H6はそのまま", lines[3].equals("###### Sub4"));
    }

    static void testHeadingTrailingHashesStripped() {
        System.out.println("[見出し: 閉じ#（例: \"## Done ##\"）は除去される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "## Done ##"));
        check("閉じ#が除去され下線が付く", body.equals("Done\n----"));
    }

    static void testHeadingWithInlineFormatting() {
        System.out.println("[見出し: 見出しテキスト内のインライン記法も変換される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "## **Bold** Heading"));
        String[] lines = body.split("\n", -1);
        check("太字記号が除去される", lines[0].equals("Bold Heading"));
        check("下線は変換後の長さに合わせる", lines[1].equals("-".repeat("Bold Heading".length())));
    }

    static void testEmptyHeadingNoUnderlineNoCrash() {
        System.out.println("[見出し: 空の見出し(\"#\"のみ)はクラッシュせず下線も付かない]");
        String rendered = MarkdownRenderer.render("f.md", "#");
        check("nullを返さない", rendered != null);
        check("空行のみで下線は付かない", bodyOf(rendered).equals(""));
    }

    // ---------------------------------------------------------------
    // 水平線（thematic break）
    // ---------------------------------------------------------------

    static void testHorizontalRuleVariants() {
        System.out.println("[水平線: ---/***/___/スペース区切りが全て検出される]");
        String expected = "-".repeat(MarkdownRenderer.RULE_WIDTH);
        check("---", bodyOf(MarkdownRenderer.render("f.md", "---")).equals(expected));
        check("***", bodyOf(MarkdownRenderer.render("f.md", "***")).equals(expected));
        check("___", bodyOf(MarkdownRenderer.render("f.md", "___")).equals(expected));
        check("- - -", bodyOf(MarkdownRenderer.render("f.md", "- - -")).equals(expected));
    }

    static void testHorizontalRuleNotConfusedWithShortDashes() {
        System.out.println("[水平線: 2個以下のダッシュは水平線扱いしない]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "--"));
        check("--はそのまま残る", body.equals("--"));
    }

    // ---------------------------------------------------------------
    // フェンスコードブロック
    // ---------------------------------------------------------------

    static void testFencedCodeBlockBacktick() {
        System.out.println("[コードブロック: バッククォート3つで開始・終了し内容は4スペースインデント]");
        String source = "```\nint x = 1;\n**not bold**\n```";
        String body = bodyOf(MarkdownRenderer.render("f.md", source));
        String[] lines = body.split("\n", -1);
        check("フェンス行自体は出力されず中身2行のみ", lines.length == 2);
        check("1行目はインデントされた内容", lines[0].equals("    int x = 1;"));
        check("コードブロック内はインライン変換されない", lines[1].equals("    **not bold**"));
    }

    static void testFencedCodeBlockTilde() {
        System.out.println("[コードブロック: チルダ(~~~)でも開始・終了できる]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "~~~\nhello\n~~~"));
        check("中身がインデントされフェンス行は消える", body.equals("    hello"));
    }

    static void testFencedCodeBlockWithInfoString() {
        System.out.println("[コードブロック: 開始フェンスの言語指定(```java)は無視される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "```java\nSystem.out.println(1);\n```"));
        check("言語指定行は消え中身のみインデントされる", body.equals("    System.out.println(1);"));
    }

    static void testFencedCodeBlockUnclosedExtendsToEndOfDocument() {
        System.out.println("[コードブロック: 閉じフェンスが無ければ文書末尾まで全てコード扱い]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "```\nline1\nline2"));
        check("残り全行がインデントされる", body.equals("    line1\n    line2"));
    }

    static void testFencedCodeBlockClosingMustBeAtLeastAsLongAsOpening() {
        System.out.println("[コードブロック: 閉じフェンスは開始フェンス以上の長さが必要（CommonMark仕様）]");
        // 4バッククォートで開始したので、途中の3個の```は閉じフェンスとして扱われずコード内容のまま
        String source = "````\ncode\n```\nstill code\n````";
        String body = bodyOf(MarkdownRenderer.render("f.md", source));
        String[] lines = body.split("\n", -1);
        check("短い閉じ記号はコード内容として扱われ最終行の4個だけが閉じる",
            lines.length == 3
                && lines[0].equals("    code")
                && lines[1].equals("    ```")
                && lines[2].equals("    still code"));
    }

    // ---------------------------------------------------------------
    // 引用（blockquote）
    // ---------------------------------------------------------------

    static void testBlockquoteSingleLevel() {
        System.out.println("[引用: 単一階層の > マーカーは \"| \" に変換される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "> quoted text"));
        check("| に変換されテキストは保持される", body.equals("| quoted text"));
    }

    static void testBlockquoteNested() {
        System.out.println("[引用: ネストした > > は階層分の \"| | \" になる]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "> > deep quote"));
        check("2階層は\"| | \"接頭辞になる", body.equals("| | deep quote"));
    }

    static void testBlockquoteNoSpaceAfterMarker() {
        System.out.println("[引用: >の直後にスペースが無くても認識される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", ">no space"));
        check("スペース無しでも| に変換される", body.equals("| no space"));
    }

    static void testBlockquoteAppliesInlineFormatting() {
        System.out.println("[引用: 引用内のインライン記法も変換される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "> **bold** quote"));
        check("引用内の太字記号も除去される", body.equals("| bold quote"));
    }

    // ---------------------------------------------------------------
    // リスト（unordered / ordered / task list）
    // ---------------------------------------------------------------

    static void testUnorderedListMarkersNormalizedToDash() {
        System.out.println("[箇条書き: -/*/+ はすべて \"- \" に正規化される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "- dash\n* star\n+ plus"));
        check("すべて\"- \"で統一される", body.equals("- dash\n- star\n- plus"));
    }

    static void testUnorderedListIndentPreserved() {
        System.out.println("[箇条書き: ネストのインデントは保持される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "- top\n  - nested"));
        check("ネスト行の先頭空白がそのまま残る", body.equals("- top\n  - nested"));
    }

    static void testTaskListItems() {
        System.out.println("[箇条書き: タスクリスト [ ]/[x]/[X] のチェックボックス表示]");
        String body = bodyOf(MarkdownRenderer.render("f.md",
            "- [ ] todo\n- [x] done\n- [X] also done"));
        check("未完了は[ ]のまま", body.contains("- [ ] todo"));
        check("小文字xは[x]のまま", body.contains("- [x] done"));
        check("大文字Xも[x]として扱われる", body.contains("- [x] also done"));
    }

    static void testOrderedListPreservedWithInlineFormatting() {
        System.out.println("[番号リスト: 番号+マーカーはそのまま・内容のインライン記法は変換される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "1. **first**\n2) second"));
        check("番号はそのまま残り太字記号は除去される", body.equals("1. first\n2) second"));
    }

    static void testOrderedListNotFalsePositiveMidSentence() {
        System.out.println("[番号リスト: 文中に現れる数字はリスト扱いされない]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "Note: see item 5. for details"));
        check("行頭でない数字はそのまま", body.equals("Note: see item 5. for details"));
    }

    // ---------------------------------------------------------------
    // インライン記法（太字・斜体・コード・リンク・画像）
    // ---------------------------------------------------------------

    static void testBoldStarAndUnderscore() {
        System.out.println("[インライン: **bold**/__bold__ の記号が除去される]");
        check("**bold**", bodyOf(MarkdownRenderer.render("f.md", "**bold text**")).equals("bold text"));
        check("__bold__", bodyOf(MarkdownRenderer.render("f.md", "__bold text__")).equals("bold text"));
    }

    static void testItalicStarAndUnderscore() {
        System.out.println("[インライン: *italic*/_italic_ の記号が除去される]");
        check("*italic*", bodyOf(MarkdownRenderer.render("f.md", "*italic text*")).equals("italic text"));
        check("_italic_", bodyOf(MarkdownRenderer.render("f.md", "_italic text_")).equals("italic text"));
    }

    static void testItalicDoesNotBreakSnakeCaseIdentifiers() {
        System.out.println("[インライン: snake_case_variable のようなアンダースコアは斜体扱いされない]");
        String text = "use snake_case_variable here";
        check("アンダースコア識別子が変化しない", bodyOf(MarkdownRenderer.render("f.md", text)).equals(text));
    }

    static void testTripleAsteriskBoldItalicCombined() {
        System.out.println("[インライン: ***text*** は太字/斜体まとめて除去される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "***very important***"));
        check("3重アスタリスクも除去される", body.equals("very important"));
    }

    static void testInlineCodeStripsBackticks() {
        System.out.println("[インライン: `code` のバッククォートが除去される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "call `foo()` now"));
        check("バッククォートが除去される", body.equals("call foo() now"));
    }

    static void testInlineCodeProtectsDunderFromBoldRegex() {
        System.out.println("[インライン: `__init__` のようなコード片が太字記号として誤変換されない]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "call `__init__` in Python"));
        check("アンダースコア2連続のコードが壊れず保持される", body.equals("call __init__ in Python"));
    }

    static void testMultipleInlineCodeSpansOnSameLine() {
        System.out.println("[インライン: 同じ行の複数のコード片がそれぞれ正しく復元される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "`a` and `b` and `c`"));
        check("3つとも個別に正しく復元される", body.equals("a and b and c"));
    }

    static void testLinkRendersTextAndUrl() {
        System.out.println("[インライン: [text](url) は \"text (url)\" に変換される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "see [the docs](https://example.com/docs)"));
        check("リンクがtext (url)形式になる", body.equals("see the docs (https://example.com/docs)"));
    }

    static void testImageWithAlt() {
        System.out.println("[インライン: ![alt](url) は \"[image: alt] (url)\" に変換される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "![a cat](cat.png)"));
        check("altテキスト付き画像の変換", body.equals("[image: a cat] (cat.png)"));
    }

    static void testImageWithoutAlt() {
        System.out.println("[インライン: alt無し画像は \"[image] (url)\" に変換される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "![](cat.png)"));
        check("alt無し画像の変換", body.equals("[image] (cat.png)"));
    }

    static void testImageNotDoubleProcessedByLinkRegex() {
        System.out.println("[インライン: 画像変換後の文字列がリンク正規表現で二重変換されない]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "![a cat](cat.png) and [a link](url)"));
        check("画像とリンクがそれぞれ独立して正しく変換される",
            body.equals("[image: a cat] (cat.png) and a link (url)"));
    }

    // ---------------------------------------------------------------
    // 境界値
    // ---------------------------------------------------------------

    static void testEmptySourceDoesNotCrash() {
        System.out.println("[境界値: 空文字列のソースでもクラッシュしない]");
        String rendered = MarkdownRenderer.render("empty.md", "");
        check("nullを返さずヘッダを含む",
            rendered != null && rendered.startsWith(MarkdownRenderer.header("empty.md")));
    }

    static void testPlainParagraphPassthrough() {
        System.out.println("[境界値: 記法を含まない段落はそのまま出力される]");
        String text = "This is just a plain sentence with no markdown at all.";
        check("完全に同一のまま出力される", bodyOf(MarkdownRenderer.render("f.md", text)).equals(text));
    }

    static void testBlankLinesPreservedBetweenParagraphs() {
        System.out.println("[境界値: 段落間の空行は保持される]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "para one\n\npara two"));
        check("空行を挟んで2段落が保持される", body.equals("para one\n\npara two"));
    }

    static void testTrailingNewlineProducesTrailingEmptyLine() {
        System.out.println("[境界値: 末尾改行はsplit(\"\\n\", -1)規約どおり末尾空行になる]");
        String body = bodyOf(MarkdownRenderer.render("f.md", "only line\n"));
        check("末尾に空行が1つ付く", body.equals("only line\n"));
    }

    // ---------------------------------------------------------------
    // 統合テスト
    // ---------------------------------------------------------------

    static void testMixedDocumentIntegration() {
        System.out.println("[統合: 複数の構造が混在する文書を通しで変換する]");
        String source = String.join("\n",
            "# Title",
            "",
            "Some **bold** and _italic_ text with a [link](https://x.test).",
            "",
            "- item one",
            "- item two",
            "",
            "```",
            "code **not bold**",
            "```",
            "",
            "> a quote"
        );
        String body = bodyOf(MarkdownRenderer.render("f.md", source));
        String[] lines = body.split("\n", -1);
        check("H1タイトル行", lines[0].equals("Title"));
        check("H1下線", lines[1].equals("=".repeat("Title".length())));
        check("空行保持(1)", lines[2].isEmpty());
        check("太字/斜体/リンクがまとめて変換される",
            lines[3].equals("Some bold and italic text with a link (https://x.test)."));
        check("空行保持(2)", lines[4].isEmpty());
        check("箇条書き1", lines[5].equals("- item one"));
        check("箇条書き2", lines[6].equals("- item two"));
        check("空行保持(3)", lines[7].isEmpty());
        check("コードブロック内は変換されずインデントのみ", lines[8].equals("    code **not bold**"));
        check("空行保持(4)", lines[9].isEmpty());
        check("引用", lines[10].equals("| a quote"));
    }

    // ---------------------------------------------------------------
    // ヘルパー
    // ---------------------------------------------------------------

    /** render()の出力からヘッダ行+区切り空行を取り除いた本文部分だけを返す。 */
    private static String bodyOf(String rendered) {
        String[] lines = rendered.split("\n", -1);
        return String.join("\n", Arrays.copyOfRange(lines, 2, lines.length));
    }

    static void check(String label, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + label);
            pass++;
        } else {
            System.out.println("  FAIL: " + label);
            fail++;
        }
    }
}
