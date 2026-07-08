package dev.javatexteditor.ui;

/**
 * JSplitPaneのdividerLocationを「現在ペインを伸縮する」意味づけで再計算する純粋ロジック。
 * Swingコンポーネントに依存しないため、実際のJSplitPaneを表示せずにテストできる。
 */
public final class PaneResizeCalculator {

    private PaneResizeCalculator() {}

    /**
     * @param currentDividerLocation 現在のdividerLocation（ピクセル）
     * @param totalSpan JSplitPaneの合計幅（HORIZONTAL_SPLIT）または高さ（VERTICAL_SPLIT）
     * @param dividerSize JSplitPaneのdivider自体の太さ
     * @param isFirstChildActive アクティブペインがsp.getLeftComponent()側
     *                            （横分割なら左、縦分割なら上）か
     * @param grow true=現在ペインを拡大（Right/Down）、false=縮小（Left/Up）
     * @param stepPx 1回の操作で動かすピクセル数
     * @param minPanePx 分割された各ペインが下回ってはいけない最小ピクセル数
     * @return クランプ後の新しいdividerLocation
     */
    public static int computeNewDividerLocation(
            int currentDividerLocation, int totalSpan, int dividerSize,
            boolean isFirstChildActive, boolean grow, int stepPx, int minPanePx) {
        int delta = (grow == isFirstChildActive) ? stepPx : -stepPx;
        int maxLoc = Math.max(minPanePx, totalSpan - dividerSize - minPanePx);
        return Math.clamp(currentDividerLocation + delta, minPanePx, maxLoc);
    }
}
