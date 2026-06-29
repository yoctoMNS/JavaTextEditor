package dev.javatexteditor.telescope;

/**
 * 部分列ファジーマッチング（fzy アルゴリズム簡略版）。
 * クエリの全文字がターゲットに順序通りに出現するか（subsequence）を判定し、
 * 連続一致・単語境界一致でスコアを加算する。
 */
public class FuzzyMatcher {

    private FuzzyMatcher() {}

    public record MatchResult(boolean matched, int score, int[] positions) {}

    /**
     * クエリがターゲットにファジーマッチするか判定し、スコアを返す。
     * 大文字小文字は区別しない。
     */
    public static MatchResult match(String query, String target) {
        if (query.isEmpty()) return new MatchResult(true, 0, new int[0]);

        String q = query.toLowerCase();
        String t = target.toLowerCase();
        int[] positions = new int[q.length()];
        int qi = 0, score = 0, lastMatch = -2;

        for (int ti = 0; ti < t.length() && qi < q.length(); ti++) {
            if (t.charAt(ti) == q.charAt(qi)) {
                positions[qi] = ti;
                if (lastMatch == ti - 1) {
                    // 連続一致ボーナス
                    score += 3;
                } else if (ti == 0 || isBoundary(target, ti)) {
                    // 単語境界ボーナス（/  .  _  -  大文字先頭）
                    score += 2;
                } else {
                    score += 1;
                }
                // ギャップペナルティ
                if (lastMatch >= 0) score -= (ti - lastMatch - 1);
                lastMatch = ti;
                qi++;
            }
        }
        return new MatchResult(qi == q.length(), score, positions);
    }

    /** ターゲットの位置 ti が単語境界（セパレータの直後 or 大文字先頭）かどうか。 */
    private static boolean isBoundary(String target, int ti) {
        char prev = target.charAt(ti - 1);
        char cur  = target.charAt(ti);
        if (prev == '/' || prev == '\\' || prev == '.' || prev == '_' || prev == '-') return true;
        if (Character.isUpperCase(cur) && Character.isLowerCase(prev)) return true;
        return false;
    }
}
