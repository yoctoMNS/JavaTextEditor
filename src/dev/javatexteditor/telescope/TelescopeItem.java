package dev.javatexteditor.telescope;

/**
 * telescope の1候補。
 *
 * @param display    結果リストに表示する文字列
 * @param filePath   ファイルパス（null なら開けない候補）
 * @param lineNumber 該当行（0-indexed、-1 なら行指定なし）
 * @param score      ファジーマッチスコア（高いほど上位）
 */
public record TelescopeItem(String display, String filePath, int lineNumber, int score) {

    public TelescopeItem withScore(int newScore) {
        return new TelescopeItem(display, filePath, lineNumber, newScore);
    }
}
