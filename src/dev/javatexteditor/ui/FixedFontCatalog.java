package dev.javatexteditor.ui;

import java.util.List;

/**
 * MiscFixed 系ビットマップフォント（5x7 ~ 10x20）のカタログと、
 * 現在のセルサイズ（cellW × cellH）に最も適した横縦比率のフォントを選ぶロジック。
 *
 * Ctrl+Shift+矢印でセル幅・高さを別々に伸縮すると、常に10x20を伸縮する従来方式では
 * 元の横縦比率（10:20）から外れるほど独立軸ニアレストネイバー拡大の歪みが目立って汚くなる。
 * ここでは要求セルサイズの横縦比率に最も近いネイティブ比率を持つ misc-fixed フォントを選び、
 * そのフォントの GLYPHS を（元のサイズに近いぶん歪みが少ない状態で）伸縮して描画する。
 *
 * 選定候補は Bold/Oblique 変種・ja/ko（全角・多バイト）フォントを含まない。
 * これらは ASCII セルの横縦比率としては同サイズの Medium-R 版と同一比率になり、
 * 比率選定には寄与しないため対象外にしている（必要になれば別途スタイル選択の仕組みを追加する）。
 */
public final class FixedFontCatalog {

    public static final List<FixedBitmapFont> CANDIDATES = List.of(
        BitmapFont5x7.INSTANCE,
        BitmapFont5x8.INSTANCE,
        BitmapFont6x9.INSTANCE,
        BitmapFont6x10.INSTANCE,
        BitmapFont6x12.INSTANCE,
        BitmapFont6x13.INSTANCE,
        BitmapFont7x13.INSTANCE,
        BitmapFont7x14.INSTANCE,
        BitmapFont8x13.INSTANCE,
        BitmapFont9x15.INSTANCE,
        BitmapFont9x18.INSTANCE,
        BitmapFont10x20.INSTANCE
    );

    /**
     * cellW × cellH に対して、横縦比率が最も近いフォントを返す。
     * 比率が同じ場合は、絶対サイズがセルサイズに近い方を優先する
     * （scale factor が小さいほど拡大縮小による歪みが少ないため）。
     */
    public static FixedBitmapFont select(int cellW, int cellH) {
        double targetRatio = (double) cellW / cellH;
        FixedBitmapFont best = null;
        double bestRatioDiff = Double.MAX_VALUE;
        double bestSizeDiff = Double.MAX_VALUE;
        for (FixedBitmapFont f : CANDIDATES) {
            double ratio = (double) f.cellW() / f.cellH();
            double ratioDiff = Math.abs(ratio - targetRatio);
            double sizeDiff = Math.abs(f.cellW() - cellW) + Math.abs(f.cellH() - cellH);
            boolean better = ratioDiff < bestRatioDiff - 1e-9
                || (Math.abs(ratioDiff - bestRatioDiff) <= 1e-9 && sizeDiff < bestSizeDiff);
            if (better) {
                best = f;
                bestRatioDiff = ratioDiff;
                bestSizeDiff = sizeDiff;
            }
        }
        return best;
    }

    private FixedFontCatalog() {}
}
