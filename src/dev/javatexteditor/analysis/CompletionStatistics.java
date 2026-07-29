package dev.javatexteditor.analysis;

import java.util.HashMap;
import java.util.Map;

/**
 * 「以前どの候補を選んだか」を数え、次回以降その候補を上に出すための記録。
 * IntelliJ IDEA の statistician（補完候補の使用頻度学習）に相当する。
 *
 * <p>記録はエディタのセッション中のみ有効で、ファイルへは保存しない。
 * 設定ファイル形式を新設すると、その読み書き・破損時の扱い・バージョン間の互換性まで
 * 面倒を見ることになり、得られる利便性に見合わないため
 * （必要になった時点で {@code TextEditorSettings} 側の仕組みに乗せる方が筋が良い）。
 *
 * <p>スレッド安全ではない。EDT からのみ更新・参照すること。
 */
public final class CompletionStatistics {

    /** 記録を持たない共有インスタンス（null チェックを不要にするため）。 */
    public static final CompletionStatistics EMPTY = new CompletionStatistics();

    /** 1つの候補が偏りすぎて他を押しのけないための上限。 */
    private static final int MAX_COUNT = 50;

    private final Map<String, Integer> counts = new HashMap<>();

    /** 候補が確定されたことを記録する。 */
    public void recordAccepted(String label) {
        if (this == EMPTY) return; // 共有インスタンスは常に空のまま
        if (label == null || label.isEmpty()) return;
        counts.merge(label, 1, (a, b) -> Math.min(MAX_COUNT, a + b));
    }

    /** label がこれまでに確定された回数。 */
    public int timesAccepted(String label) {
        return counts.getOrDefault(label, 0);
    }

    /** 記録をすべて消す（テスト用）。 */
    public void clear() {
        counts.clear();
    }
}
