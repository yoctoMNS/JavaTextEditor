package dev.javatexteditor.editor;

import java.util.Map;
import java.util.OptionalInt;

/**
 * Vim の {@code %}（対応する括弧へのジャンプ）が使う括弧ペア探索ロジック。
 * ペア定義を Map で持たせているのは、将来 {@code matchpairs} オプション相当の
 * ユーザー定義ペア（例: "&lt;"/"&gt;"）を追加できるよう、ハードコードした
 * if/else 分岐にしないため。
 */
public final class MatchPairs {

    private MatchPairs() {}

    private static final Map<Character, Character> OPEN_TO_CLOSE = Map.of(
            '(', ')',
            '[', ']',
            '{', '}'
    );
    private static final Map<Character, Character> CLOSE_TO_OPEN = Map.of(
            ')', '(',
            ']', '[',
            '}', '{'
    );

    /** offset位置の文字が括弧かどうか（開き・閉じ問わず）。 */
    public static boolean isBracket(char c) {
        return OPEN_TO_CLOSE.containsKey(c) || CLOSE_TO_OPEN.containsKey(c);
    }

    /**
     * text 中の offset 位置の文字が開き括弧または閉じ括弧であれば、対応する相手の
     * offset を返す。括弧でない場合、または対応相手が見つからない場合（不正な
     * ネスト等）は empty を返す。
     *
     * ネストの解決は「最も近い相手」を単純に探すのではなく、深さカウント
     * （スタック相当）で行う。同じ種類の括弧が探索方向に現れるたびに深さを
     * 増やし、逆方向の括弧が現れるたびに減らして、深さが 0 に戻った位置を
     * 対応相手とする。
     */
    public static OptionalInt findMatch(String text, int offset) {
        if (text == null || offset < 0 || offset >= text.length()) return OptionalInt.empty();
        char c = text.charAt(offset);

        Character close = OPEN_TO_CLOSE.get(c);
        if (close != null) {
            int depth = 0;
            for (int i = offset; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == c) depth++;
                else if (ch == close) {
                    depth--;
                    if (depth == 0) return OptionalInt.of(i);
                }
            }
            return OptionalInt.empty();
        }

        Character open = CLOSE_TO_OPEN.get(c);
        if (open != null) {
            int depth = 0;
            for (int i = offset; i >= 0; i--) {
                char ch = text.charAt(i);
                if (ch == c) depth++;
                else if (ch == open) {
                    depth--;
                    if (depth == 0) return OptionalInt.of(i);
                }
            }
            return OptionalInt.empty();
        }

        return OptionalInt.empty();
    }
}
