package dev.javatexteditor.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdownソースを読みやすいプレーンテキストの「閲覧ビュー」に変換する（Swing非依存の純粋ロジック）。
 * このエディタの描画パイプラインは等幅ビットマップフォントのグリッド描画で、フォントスタイル
 * （太字/斜体）の切替や任意のUnicode記号の安全な幅計算はできない
 * (Following the lesson from telescope changing its selection marker from "▸" to ASCII ">", the emitted symbols are
 * すべてASCII印字可能文字(0x20-0x7E)の範囲に収める）。そのため色分けや文字装飾はせず、
 * 記法記号(#, **, - 等)を取り除き、見出しの下線・リストの正規化されたマーカー・
 * コードブロックのインデント・水平線などで構造だけを可読テキストとして再構成する。
 */
public final class MarkdownRenderer {

    /** 水平線（thematic break）を描画する際の文字数。 */
    public static final int RULE_WIDTH = 60;

    private static final Pattern ATX_HEADING = Pattern.compile("^ {0,3}(#{1,6})(?:\\s+(.*))?$");
    private static final Pattern TRAILING_HASHES = Pattern.compile("\\s+#+\\s*$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^ {0,3}([-*_])(?:\\s*\\1){2,}\\s*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})\\s*(.*)$");
    private static final Pattern BLOCKQUOTE_MARKER = Pattern.compile("^ {0,3}>( ?)(.*)$");
    private static final Pattern ORDERED_ITEM = Pattern.compile("^(\\s*)(\\d+)([.)])\\s+(.*)$");
    private static final Pattern UNORDERED_ITEM = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern TASK_ITEM = Pattern.compile("^\\[([ xX])\\]\\s+(.*)$");

    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]*)\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]*)\\)");
    private static final Pattern BOLD_STAR = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern BOLD_UNDERSCORE = Pattern.compile("__([^_]+)__");
    private static final Pattern ITALIC_STAR = Pattern.compile("\\*([^*]+)\\*");
    private static final Pattern ITALIC_UNDERSCORE = Pattern.compile("\\b_([^_]+)_\\b");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");

    private record Blockquote(int depth, String content) {}

    private MarkdownRenderer() {}

    /** *view* 疑似バッファの先頭に置くヘッダ行（既存のgrep結果・hexdump等の疑似バッファと同じ構成）。 */
    public static String header(String fileName) {
        return "*view* " + fileName + " — markdown preview (:mark to return to source view)";
    }

    /** ヘッダ行 + 空行 + 変換済み本文、を1つの文字列として返す。 */
    public static String render(String fileName, String source) {
        List<String> out = new ArrayList<>();
        out.add(header(fileName));
        out.add("");
        renderBody(source, out);
        return String.join("\n", out);
    }

    private static void renderBody(String source, List<String> out) {
        String[] lines = source.split("\n", -1);
        boolean inFence = false;
        char fenceChar = '`';
        int fenceLen = 0;
        for (String line : lines) {
            if (inFence) {
                Matcher close = FENCE.matcher(line);
                if (close.matches() && close.group(1).charAt(0) == fenceChar
                        && close.group(1).length() >= fenceLen && close.group(2).isBlank()) {
                    inFence = false;
                } else {
                    // コードブロック内はインライン記法(**/`` 等)を変換しない。4スペースインデントで区切る。
                    out.add("    " + line);
                }
                continue;
            }
            Matcher open = FENCE.matcher(line);
            if (open.matches()) {
                inFence = true;
                fenceChar = open.group(1).charAt(0);
                fenceLen = open.group(1).length();
                continue;
            }
            appendStructuredLine(line, out);
        }
    }

    private static void appendStructuredLine(String line, List<String> out) {
        Matcher heading = ATX_HEADING.matcher(line);
        if (heading.matches()) {
            appendHeading(heading.group(1).length(), heading.group(2), out);
            return;
        }
        if (HORIZONTAL_RULE.matcher(line).matches()) {
            out.add("-".repeat(RULE_WIDTH));
            return;
        }
        Blockquote bq = matchBlockquote(line);
        if (bq != null) {
            out.add("| ".repeat(bq.depth()) + applyInline(bq.content()));
            return;
        }
        Matcher unordered = UNORDERED_ITEM.matcher(line);
        if (unordered.matches()) {
            String indent = unordered.group(1);
            String content = unordered.group(2);
            Matcher task = TASK_ITEM.matcher(content);
            if (task.matches()) {
                String box = task.group(1).equalsIgnoreCase("x") ? "[x]" : "[ ]";
                out.add(indent + "- " + box + " " + applyInline(task.group(2)));
            } else {
                out.add(indent + "- " + applyInline(content));
            }
            return;
        }
        Matcher ordered = ORDERED_ITEM.matcher(line);
        if (ordered.matches()) {
            out.add(ordered.group(1) + ordered.group(2) + ordered.group(3) + " " + applyInline(ordered.group(4)));
            return;
        }
        out.add(applyInline(line));
    }

    /**
     * 見出しを描画する。H1/H2は本文タイトル行の下に"="/"-"の下線を引く（setext見出し風）。
     * H3-H6は元の"#"接頭辞をそのまま残す（フォントサイズ変更ができないため階層は
     * ハッシュの個数で示す。安全なASCIIのみで構成済みの表現をあえて変更しない）。
     */
    private static void appendHeading(int level, String rawText, List<String> out) {
        String text = (rawText == null) ? "" : rawText;
        text = TRAILING_HASHES.matcher(text).replaceAll("").strip();
        text = applyInline(text);
        if (level <= 2) {
            out.add(text);
            if (!text.isEmpty()) {
                out.add((level == 1 ? "=" : "-").repeat(visualWidth(text)));
            }
        } else {
            out.add("#".repeat(level) + (text.isEmpty() ? "" : " " + text));
        }
    }

    private static Blockquote matchBlockquote(String line) {
        int depth = 0;
        String rest = line;
        while (true) {
            Matcher m = BLOCKQUOTE_MARKER.matcher(rest);
            if (!m.matches()) break;
            depth++;
            rest = m.group(2);
        }
        return depth > 0 ? new Blockquote(depth, rest) : null;
    }

    // インラインコードの保護に使うプレースホルダの目印（Private Use Area。通常のMarkdown本文には
    // 現れないため、太字/斜体の正規表現と衝突しない）。
    private static final char CODE_MARK = '\uE000';
    private static final Pattern CODE_PLACEHOLDER = Pattern.compile(CODE_MARK + "(\\d+)" + CODE_MARK);

    /**
     * インライン記法（画像・リンク・太字・斜体・インラインコード）の記号を取り除く。
     * フェンスコードブロック内の行には呼び出し元(renderBody)から適用されない。
     * インラインコード（`code`）は他の変換より先に本文から退避させ、最後にそのまま復元する
     * （例: `__init__` のようなコード片が太字の正規表現に誤って巻き込まれてしまうのを防ぐ）。
     */
    private static String applyInline(String text) {
        List<String> codeSpans = new ArrayList<>();
        String s = replaceAll(text, INLINE_CODE, m -> {
            codeSpans.add(m.group(1));
            return "" + CODE_MARK + (codeSpans.size() - 1) + CODE_MARK;
        });
        s = replaceAll(s, IMAGE, m -> {
            String alt = m.group(1);
            return (alt.isEmpty() ? "[image]" : "[image: " + alt + "]") + " (" + m.group(2) + ")";
        });
        s = replaceAll(s, LINK, m -> m.group(1) + " (" + m.group(2) + ")");
        s = replaceAll(s, BOLD_STAR, m -> m.group(1));
        s = replaceAll(s, BOLD_UNDERSCORE, m -> m.group(1));
        s = replaceAll(s, ITALIC_STAR, m -> m.group(1));
        s = replaceAll(s, ITALIC_UNDERSCORE, m -> m.group(1));
        if (codeSpans.isEmpty()) return s;
        return replaceAll(s, CODE_PLACEHOLDER, m -> codeSpans.get(Integer.parseInt(m.group(1))));
    }

    /**
     * Matcher#replaceAll は置換文字列中の $/\ を特殊文字として解釈するため、キャプチャ内容を
     * そのままリテラルとして埋め込みたいここでは使わず、手動でStringBuilderへ組み立てる。
     */
    private static String replaceAll(String input, Pattern pattern, Function<Matcher, String> replacer) {
        Matcher m = pattern.matcher(input);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(input, last, m.start());
            out.append(replacer.apply(m));
            last = m.end();
        }
        out.append(input, last, input.length());
        return out.toString();
    }

    /**
     * 全角文字（ひらがな・カタカナ・CJK統合漢字・全角英数記号等）を2セル分として数える表示幅。
     * EditorCanvas.charCellWidthと同じ判定基準（見出し下線の長さ計算専用にこの1箇所だけ複製。
     * markdownパッケージはSwing非依存の純粋ロジックに保つ方針のためuiパッケージには依存しない）。
     */
    private static int visualWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            width += charCellWidth(cp);
            i += Character.charCount(cp);
        }
        return width;
    }

    private static int charCellWidth(int codePoint) {
        if (codePoint >= 0x3000 && codePoint <= 0x303F) return 2;
        if (codePoint >= 0x3040 && codePoint <= 0x30FF) return 2;
        if (codePoint >= 0x4E00 && codePoint <= 0x9FFF) return 2;
        if (codePoint >= 0xFF01 && codePoint <= 0xFF60) return 2;
        if (codePoint >= 0xFFE0 && codePoint <= 0xFFE6) return 2;
        if (codePoint >= 0x25A0 && codePoint <= 0x25FF) return 2;
        if (codePoint >= 0x2460 && codePoint <= 0x24FF) return 2;
        return 1;
    }
}
