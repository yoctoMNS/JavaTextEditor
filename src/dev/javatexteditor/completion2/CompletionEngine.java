package dev.javatexteditor.completion2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * 文書全体のテキストとカーソル位置・プレフィックスから補完候補一覧を計算する純粋ロジック。
 * UI（Swing）には一切依存しない。
 *
 * 候補の順序は「カーソルに近い出現ほど優先」「同距離ならアルファベット順」とする。
 * プレフィックスと完全一致するだけのトークン（補完しても変化がないもの）は除外する。
 */
public final class CompletionEngine {

    public List<CompletionCandidate> collectCandidates(CharSequence documentText, int caretOffset, String prefix) {
        Map<String, Integer> bestDistanceByToken = new LinkedHashMap<>();
        Matcher m = TokenScanner.TOKEN_PATTERN.matcher(documentText);
        while (m.find()) {
            String token = m.group();
            if (token.equals(prefix)) {
                continue;
            }
            if (!prefix.isEmpty() && !token.startsWith(prefix)) {
                continue;
            }
            int distance = distanceFromCaret(m.start(), m.end(), caretOffset);
            bestDistanceByToken.merge(token, distance, Math::min);
        }

        List<CompletionCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : bestDistanceByToken.entrySet()) {
            candidates.add(new CompletionCandidate(entry.getKey(), -entry.getValue()));
        }
        candidates.sort(null);
        return candidates;
    }

    private static int distanceFromCaret(int tokenStart, int tokenEnd, int caretOffset) {
        if (caretOffset <= tokenStart) {
            return tokenStart - caretOffset;
        }
        if (caretOffset >= tokenEnd) {
            return caretOffset - tokenEnd;
        }
        return 0;
    }
}
