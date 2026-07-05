package dev.javatexteditor.completion2;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java識別子相当のトークン（[A-Za-z0-9_$]+）を走査するための純粋ロジック。
 * Swing等のUI依存を一切持たない。
 */
public final class TokenScanner {

    /** Java識別子の基本トークンパターン。 */
    public static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_$]+");

    private TokenScanner() {
    }

    /** 文書全体からトークンを出現順にすべて抽出する。 */
    public static List<String> extractAllTokens(CharSequence text) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN_PATTERN.matcher(text);
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }

    /** caretOffset から後方にトークン文字が続く限り遡り、トークンの開始オフセットを返す。 */
    public static int findTokenStart(CharSequence text, int caretOffset) {
        int i = caretOffset;
        while (i > 0 && isTokenChar(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /** カーソル直前の未確定プレフィックス文字列を返す。 */
    public static String prefixBeforeCaret(CharSequence text, int caretOffset) {
        int start = findTokenStart(text, caretOffset);
        return text.subSequence(start, caretOffset).toString();
    }

    public static boolean isTokenChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '$';
    }
}
