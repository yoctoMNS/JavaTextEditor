package dev.javatexteditor.analysis;

/**
 * カーソル直前のテキストを見て「いま何を補完しようとしているのか」を判定した結果。
 *
 * <p>IntelliJ IDEA の補完が最初に行うのも同じ判定である。{@code list.} の後ろでは
 * list の型のメンバーだけを、{@code new } の後ろでは型名だけを候補にする、という具合に、
 * 文脈によって候補の集合そのものを切り替えるための土台になる。
 *
 * <p>Swing にもバッファ実装にも依存しない純粋な文字列解析なので、単体でテストできる。
 *
 * @param kind          補完しようとしている対象の種類
 * @param prefix        入力中の識別子（カーソル直前まで。まだ何も打っていなければ空文字列）
 * @param prefixStart   prefix の開始オフセット（候補確定時に置き換える範囲の先頭）
 * @param receiverText  {@link Kind#MEMBER} のときの {@code '.'} の左側の式。それ以外では空文字列
 * @param receiverStart receiverText の開始オフセット。{@link Kind#MEMBER} 以外では -1
 */
public record CompletionContext(Kind kind, String prefix, int prefixStart,
                                String receiverText, int receiverStart) {

    public enum Kind {
        /** 修飾なしの位置。ローカル変数・自クラスのメンバー・クラス名・キーワードが候補になる。 */
        PLAIN,
        /** {@code expr.} の直後。expr の型のメンバーだけが候補になる。 */
        MEMBER,
        /** {@code new } の直後。型名だけが候補になる。 */
        NEW
    }

    /** メンバー補完の文脈か。 */
    public boolean isMember() {
        return kind == Kind.MEMBER;
    }

    /**
     * receiverText が単一の識別子（{@code list}・{@code this} 等）ならそれを返す。
     * {@code a.b()} のような複合式なら空文字列を返す（軽量解決では型を決められないため）。
     */
    public String simpleReceiver() {
        if (receiverText.isEmpty()) return "";
        for (int i = 0; i < receiverText.length(); i++) {
            if (!isIdentifierPart(receiverText.charAt(i))) return "";
        }
        return receiverText;
    }

    /**
     * text の caretOffset 位置における補完文脈を判定する。
     *
     * @param text        バッファ全文
     * @param caretOffset カーソル位置（0..text.length()）
     */
    public static CompletionContext at(String text, int caretOffset) {
        if (text == null) return empty(0);
        int caret = Math.max(0, Math.min(caretOffset, text.length()));

        int prefixStart = caret;
        while (prefixStart > 0 && isIdentifierPart(text.charAt(prefixStart - 1))) {
            prefixStart--;
        }
        String prefix = text.substring(prefixStart, caret);

        // 数値リテラルの途中（"3.14" の "14" 等）を識別子と誤認しないよう、先頭が数字なら補完しない
        if (!prefix.isEmpty() && Character.isDigit(prefix.charAt(0))) {
            return empty(prefixStart);
        }

        int beforePrefix = prefixStart - 1;
        if (beforePrefix >= 0 && text.charAt(beforePrefix) == '.') {
            // "..", "1." のような誤検出を避けるため、ドットの左に式があることを確認する
            int receiverStart = scanReceiverStart(text, beforePrefix);
            if (receiverStart >= 0 && receiverStart < beforePrefix) {
                String receiver = text.substring(receiverStart, beforePrefix).trim();
                if (!receiver.isEmpty() && !isNumericLiteral(receiver)) {
                    return new CompletionContext(Kind.MEMBER, prefix, prefixStart,
                        receiver, receiverStart);
                }
            }
            return empty(prefixStart);
        }

        if (precededByKeyword(text, prefixStart, "new")) {
            return new CompletionContext(Kind.NEW, prefix, prefixStart, "", -1);
        }
        return new CompletionContext(Kind.PLAIN, prefix, prefixStart, "", -1);
    }

    private static CompletionContext empty(int prefixStart) {
        return new CompletionContext(Kind.PLAIN, "", prefixStart, "", -1);
    }

    /**
     * dotIndex にあるドットの左側の式の開始位置を返す。式が見つからなければ -1。
     *
     * <p>{@code a}、{@code a.b}、{@code a.b()}、{@code arr[0]}、{@code new Foo()}、
     * {@code "str"} を1つの式として遡る。括弧・角括弧は対応を取って丸ごと読み飛ばすため、
     * {@code map.get(list.size()).} のような入れ子でも正しく先頭に到達できる。
     */
    private static int scanReceiverStart(String text, int dotIndex) {
        int pos = skipWhitespaceBackward(text, dotIndex - 1);
        if (pos < 0) return -1;

        int earliest = -1; // これまでに読み終えた式の先頭
        while (pos >= 0) {
            char c = text.charAt(pos);
            if (c == ')' || c == ']') {
                int open = matchOpenBracket(text, pos);
                if (open < 0) return -1;
                earliest = open;
                int before = skipWhitespaceBackward(text, open - 1);
                if (before >= 0 && (isIdentifierPart(text.charAt(before))
                        || text.charAt(before) == ')' || text.charAt(before) == ']')) {
                    pos = before; // メソッド呼び出し・配列アクセスの本体を続けて読む
                    continue;
                }
                return earliest; // "(a + b)." のような括弧式そのものが起点
            }
            if (c == '"') {
                return scanStringLiteralStart(text, pos);
            }
            if (isIdentifierPart(c)) {
                int start = pos;
                while (start > 0 && isIdentifierPart(text.charAt(start - 1))) start--;
                earliest = start;
                int before = skipWhitespaceBackward(text, start - 1);
                if (before >= 0 && text.charAt(before) == '.') {
                    pos = skipWhitespaceBackward(text, before - 1);
                    if (pos < 0) return earliest;
                    continue;
                }
                // "new Foo()." の new は式に含めない（型名 Foo から始める）
                return earliest;
            }
            // それ以外の記号（演算子・区切り）に当たったらそこまで
            return earliest;
        }
        return earliest;
    }

    private static int skipWhitespaceBackward(String text, int from) {
        int i = from;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) i--;
        return i;
    }

    /** closeIndex にある閉じ括弧に対応する開き括弧の位置。見つからなければ -1。 */
    private static int matchOpenBracket(String text, int closeIndex) {
        char close = text.charAt(closeIndex);
        char open = (close == ')') ? '(' : '[';
        int depth = 0;
        for (int i = closeIndex; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == close) depth++;
            else if (c == open) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** endQuoteIndex にある閉じ引用符に対応する文字列リテラルの開始位置。見つからなければ -1。 */
    private static int scanStringLiteralStart(String text, int endQuoteIndex) {
        for (int i = endQuoteIndex - 1; i >= 0; i--) {
            if (text.charAt(i) == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                return i;
            }
            if (text.charAt(i) == '\n') return -1;
        }
        return -1;
    }

    /** prefixStart の直前にある単語が keyword かどうか（間の空白は無視する）。 */
    private static boolean precededByKeyword(String text, int prefixStart, String keyword) {
        int i = skipWhitespaceBackward(text, prefixStart - 1);
        if (i < 0 || i == prefixStart - 1) return false; // 空白で区切られていること
        int end = i + 1;
        int start = end;
        while (start > 0 && isIdentifierPart(text.charAt(start - 1))) start--;
        return text.substring(start, end).equals(keyword);
    }

    /** "3." のような数値リテラルの小数点をメンバーアクセスと誤認しないための判定。 */
    private static boolean isNumericLiteral(String receiver) {
        for (int i = 0; i < receiver.length(); i++) {
            if (!Character.isDigit(receiver.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
