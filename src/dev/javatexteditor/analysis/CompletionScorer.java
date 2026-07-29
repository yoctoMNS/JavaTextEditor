package dev.javatexteditor.analysis;

/**
 * 入力補完候補のマッチ判定とスコアリング。
 *
 * IntelliJ IDEA の PrefixMatcher（CamelHumpMatcher）と同じ段階評価を行う:
 *   Tier 1 (>=900): 完全一致
 *   Tier 2 (>=600): 大文字小文字区別ありプレフィックス
 *   Tier 3 (>=400): 大文字小文字区別なしプレフィックス
 *   Tier 4 (>=200): CamelCase 頭文字一致 (例: "AL" → "ArrayList")
 *   Tier 5 (>=100): 単語境界からの部分一致（IntelliJ の "middle matching"。
 *                   例: "Builder" → "StringBuilder"、"list" → "ArrayList"）
 *   Tier 6 (  >0 ): ファジー部分列一致 (subsequence)
 *
 * 戻り値が 0 以下のときはマッチなし（候補から除外する）。
 *
 * <p>{@link #match(String, String)} はスコアに加えて「label のどの文字がクエリに対応したか」の
 * 位置配列も返す。IntelliJ が候補一覧で一致部分だけを強調表示するのと同じ描画を行うために使う。
 * スコアだけが必要な場合は従来どおり {@link #score(String, String)} を使えばよい。
 */
public final class CompletionScorer {

    private CompletionScorer() {}

    /**
     * マッチ結果。
     *
     * @param score     スコア（大きいほど良い一致）
     * @param positions label 内で一致した文字の位置（昇順）。強調表示に使う
     */
    public record Match(int score, int[] positions) {}

    /**
     * query が label にどれだけマッチするかをスコアで返す。
     * 0 以下 = マッチしない。
     */
    public static int score(String query, String label) {
        Match m = match(query, label);
        return (m == null) ? 0 : m.score();
    }

    /**
     * query が label にマッチするかを判定し、スコアと一致位置を返す。
     * マッチしない場合は null。
     */
    public static Match match(String query, String label) {
        if (query == null || label == null || query.isEmpty() || label.isEmpty()) return null;

        // Tier 1: 完全一致（大文字小文字区別あり）
        if (label.equals(query)) {
            return new Match(1000, rangePositions(0, label.length()));
        }

        // Tier 2: プレフィックス一致（大文字小文字区別あり）
        if (label.startsWith(query)) {
            // プレフィックスが label 全体に占める割合が大きいほど高スコア
            return new Match(800 + query.length() * 100 / label.length(),
                rangePositions(0, query.length()));
        }

        // Tier 3: プレフィックス一致（大文字小文字区別なし）
        if (startsWithIgnoreCase(label, query)) {
            return new Match(600 + query.length() * 100 / label.length(),
                rangePositions(0, query.length()));
        }

        // Tier 4: CamelCase 頭文字一致
        Match camel = matchCamelCase(query, label);
        if (camel != null) return camel;

        // Tier 5: 単語境界からの部分一致（middle matching）
        Match middle = matchWordBoundary(query, label);
        if (middle != null) return middle;

        // Tier 6: ファジー部分列一致
        return matchFuzzy(query, label);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private static boolean startsWithIgnoreCase(String label, String query) {
        if (query.length() > label.length()) return false;
        return label.regionMatches(true, 0, query, 0, query.length());
    }

    private static int[] rangePositions(int from, int toExclusive) {
        int[] pos = new int[Math.max(0, toExclusive - from)];
        for (int i = 0; i < pos.length; i++) pos[i] = from + i;
        return pos;
    }

    /**
     * CamelCase 境界一致。
     * query の各文字を label の "単語先頭" に順番に照合する。
     *
     * 例:
     *   query="AL", label="ArrayList"  → 'A' @ 0, 'L' @ 5 → マッチ (score 2)
     *   query="hm",  label="HashMap"   → 'h' @ 0, 'm' @ 4 → マッチ (score 2)
     *   query="ISE",  label="IllegalStateException" → マッチ (score 3)
     */
    private static Match matchCamelCase(String query, String label) {
        String ql = query.toLowerCase();
        int qi = 0;
        int[] positions = new int[ql.length()];

        // 単語境界位置を順に探しながらクエリ文字と照合
        for (int li = 0; li < label.length() && qi < ql.length(); li++) {
            if (isBoundaryStart(label, li)) {
                if (Character.toLowerCase(label.charAt(li)) == ql.charAt(qi)) {
                    positions[qi] = li;
                    qi++;
                }
            }
        }

        if (qi < ql.length()) return null;
        // 消費した境界が少ない（=頭文字の密度が高い）ほど高スコア
        return new Match(200 + ql.length() * 10, positions);
    }

    /**
     * 単語境界からの部分一致（IntelliJ の "middle matching"）。
     * label の途中にある単語の先頭から query が連続して一致する場合にマッチとする。
     *
     * 例:
     *   query="Builder", label="StringBuilder" → 'B' @ 6 から連続一致
     *   query="list",    label="ArrayList"     → 'L' @ 5 から連続一致（大小無視）
     *
     * 単語の途中から始まる一致（"tring" → "String"）は、IntelliJ 同様に採用しない。
     * 無関係な候補が大量に混じり、一覧の先頭が信用できなくなるため。
     */
    private static Match matchWordBoundary(String query, String label) {
        if (query.length() > label.length()) return null;
        for (int li = 1; li + query.length() <= label.length(); li++) {
            if (!isBoundaryStart(label, li)) continue;
            if (label.regionMatches(true, li, query, 0, query.length())) {
                // 前方（先頭に近い位置）で一致したものほど高スコア
                int positionBonus = Math.max(0, 40 - li);
                int coverage = query.length() * 40 / label.length();
                return new Match(100 + positionBonus + coverage,
                    rangePositions(li, li + query.length()));
            }
        }
        return null;
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
     *
     * <p>Tier 5 までのスコア下限（100）を越えないよう上限を設けている。
     * 上位 Tier との序列が入れ替わると「なぜこの候補が一番上なのか」が説明できなくなるため。
     */
    private static Match matchFuzzy(String query, String label) {
        String ql = query.toLowerCase();
        String ll = label.toLowerCase();
        int qi = 0, score = 0, lastMatch = -2;
        int[] positions = new int[ql.length()];

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
                positions[qi] = li;
                qi++;
            }
        }
        if (qi < ql.length()) return null;
        return new Match(Math.min(99, Math.max(1, score)), positions);
    }
}
