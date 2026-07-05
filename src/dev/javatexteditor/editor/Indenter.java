package dev.javatexteditor.editor;

/**
 * Vim の {@code >}/{@code <}（インデントシフト）の純粋ロジック。
 * ModalEditor から呼ばれる static ヘルパー群。バッファ操作(offset計算・delete/insert)は
 * 呼び出し側（ModalEditor）が担い、本クラスは「1行の新しいテキストを計算する」
 * 「幅Nのインデント文字列を組み立てる」という文字列レベルの計算のみを行う。
 */
public final class Indenter {

    private Indenter() {}

    /** 行頭の空白（スペース・タブ）の文字数（表示幅ではなく文字数）。 */
    public static int leadingWhitespaceLength(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return i;
    }

    /** 行頭の空白を tabstop で展開した表示幅。 */
    public static int leadingIndentWidth(String line, int tabstop) {
        int width = 0;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == ' ') {
                width++;
                i++;
            } else if (c == '\t') {
                width += tabstop - (width % tabstop);
                i++;
            } else {
                break;
            }
        }
        return width;
    }

    /** 表示幅 width 分のインデント文字列を組み立てる（expandtab に従う）。 */
    public static String buildIndent(int width, IndentSettings s) {
        if (width <= 0) return "";
        if (s.isExpandtab()) {
            return " ".repeat(width);
        }
        int tabstop = s.getTabstop();
        int tabs = width / tabstop;
        int spaces = width % tabstop;
        return "\t".repeat(tabs) + " ".repeat(spaces);
    }

    /**
     * 1行を count * shiftwidth 分だけ右(left=false)/左(left=true)にシフトした
     * 新しい行テキストを返す。行頭の空白部分だけを置き換え、それ以降の内容は
     * そのまま保持する。
     *
     * shiftround の丸めは Vim 本家の shift_line() と同じ式を採用する:
     * 「まず現在の表示幅を shiftwidth の倍数に切り下げてから shiftwidth*count を
     * 加減算する」。丸めなしの場合は単純に加減算するだけ。
     *
     * 右シフトは空行（空白のみ・完全に空も含む）を変更しない。
     * 左シフトは表示幅が 0 未満にならないようクランプする。
     */
    public static String shiftLine(String line, int count, boolean left, IndentSettings s) {
        if (!left && line.isBlank()) {
            return line; // 空行は右シフトで変更しない
        }

        int tabstop = s.getTabstop();
        int shiftwidth = s.getShiftwidth();
        int wsLen = leadingWhitespaceLength(line);
        int width = leadingIndentWidth(line, tabstop);

        int newWidth;
        if (s.isShiftround()) {
            int rounded = width - (width % shiftwidth);
            newWidth = left ? rounded - shiftwidth * count : rounded + shiftwidth * count;
        } else {
            newWidth = left ? width - shiftwidth * count : width + shiftwidth * count;
        }
        if (newWidth < 0) newWidth = 0;

        String newIndent = buildIndent(newWidth, s);
        return newIndent + line.substring(wsLen);
    }
}
