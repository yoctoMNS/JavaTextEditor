package dev.javatexteditor.analysis;

/**
 * 入力補完候補のスコアリング。
 *
 * IntelliJ IDEA / VS Code 相当の4段階評価:
 *   Tier 1 (>=900): 完全一致
 *   Tier 2 (>=600): 大文字小文字区別ありプレフィックス
 *   Tier 3 (>=400): 大文字小文字区別なしプレフィックス
 *   Tier 4 (>=200): CamelCase 頭文字一致 (例: "AL" → "ArrayList")
 *   Tier 5 (  >0 ): ファジー部分列一致 (subsequence)
 *
 * 戻り値が 0 以下のときはマッチなし（候補から除外する）。
 */
public final class CompletionScorer {

    private CompletionScorer() {}

    /**
     * query が label にどれだけマッチするかをスコアで返す。
     * 0 以下 = マッチしない。
     */
    public static int score(String query, String label) {
        if (query.isEmpty() || label.isEmpty()) return 0;

        // Tier 1: 完全一致（大文字小文字区別あり）
        if (label.equals(query)) return 1000;

        // Tier 2: プレフィックス一致（大文字小文字区別あり）
        if (label.startsWith(query)) {
            // プレフィックスが label 全体に占める割合が大きいほど高スコア
            return 800 + query.length() * 100 / label.length();
        }

        // Tier 3: プレフィックス一致（大文字小文字区別なし）
        if (startsWithIgnoreCase(label, query)) {
            return 600 + query.length() * 100 / label.length();
        }

        // Tier 4: CamelCase 頭文字一致
        int camelScore = matchCamelCase(query, label);
        if (camelScore > 0) return 200 + camelScore;

        // Tier 5: ファジー部分列一致
        int fuzzyScore = matchFuzzy(query, label);
        if (fuzzyScore > 0) return fuzzyScore;

        return 0;
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private static boolean startsWithIgnoreCase(String label, String query) {
        if (query.length() > label.length()) return false;
        return label.regionMatches(true, 0, query, 0, query.length());
    }

    /**
     * CamelCase 境界一致。
     * query の各文字を label の "単語先頭" に順番に照合する。
     *
     * 例:
     *   query="AL", label="ArrayList"  → 'A' @ 0, 'L' @ 5 → マッチ (score 2)
     *   query="hm",  label="HashMap"   → 'h' @ 0, 'm' @ 4 → マッチ (score 2)
     *   query="ISE",  label="IllegalStateException" → マッチ (score 3)
     *
     * @return マッチした単語先頭の数（= query.length()）のとき成功; それ以外 0
     */
    private static int matchCamelCase(String query, String label) {
        String ql = query.toLowerCase();
        int qi = 0;

        // 単語境界位置を順に探しながらクエリ文字と照合
        for (int li = 0; li < label.length() && qi < ql.length(); li++) {
            if (isBoundaryStart(label, li)) {
                if (Character.toLowerCase(label.charAt(li)) == ql.charAt(qi)) {
                    qi++;
                }
            }
        }

        if (qi < ql.length()) return 0;
        // 消費した境界が少ない（=頭文字の密度が高い）ほど高スコア
        return ql.length() * 10;
    }

    /**
     * 位置 i が CamelCase の単語先頭かどうかを判定する。
     * 先頭文字、アンダースコア直後、大文字の開始（前が小文字）が該当する。
     */
    private static boolean isBoundaryStart(String label, int i) {
        if (i == 0) return true;
        char prev = label.charAt(i - 1);
        char cur  = label.charAt(i);
        if (prev == '_' || prev == '-') return true;
        if (Character.isUpperCase(cur) && !Character.isUpperCase(prev)) return true;
        return false;
    }

    /**
     * ファジー部分列一致（fzy アルゴリズム簡略版）。
     * query の全文字が label に順番通りに存在すれば正のスコアを返す。
     * 連続一致・単語境界でボーナス加算、ギャップにはペナルティを与える。
     */
    private static int matchFuzzy(String query, String label) {
        String ql = query.toLowerCase();
        String ll = label.toLowerCase();
        int qi = 0, score = 0, lastMatch = -2;

        for (int li = 0; li < ll.length() && qi < ql.length(); li++) {
            if (ll.charAt(li) == ql.charAt(qi)) {
                if (lastMatch == li - 1) {
                    score += 3;           // 連続一致ボーナス
                } else if (isBoundaryStart(label, li)) {
                    score += 2;           // 単語境界ボーナス
                } else {
                    score += 1;
                }
                if (lastMatch >= 0) score -= (li - lastMatch - 1); // ギャップペナルティ
                lastMatch = li;
                qi++;
            }
        }
        return (qi == ql.length()) ? Math.max(1, score) : 0;
    }
}
