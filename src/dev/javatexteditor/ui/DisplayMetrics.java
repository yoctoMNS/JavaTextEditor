package dev.javatexteditor.ui;

/**
 * 起動時のフォントセルサイズとウィンドウサイズを、ディスプレイの解像度から決める計算。
 *
 * <p>4K 等の高解像度ディスプレイで既定フォントが小さすぎるのを防ぐのが目的で、
 * フルHD（幅1920px）を基準に、それより広い画面では同じ倍率で文字とウィンドウの両方を拡大する。
 * 文字だけを大きくするとスプラッシュ画面やステータス行がウィンドウ下端からはみ出すため、
 * 必ず同じ倍率を使う。
 *
 * <p>ここには <b>{@code GraphicsConfiguration} も {@code Toolkit} も出てこない</b>。
 * 画面情報の取得（実際のディスプレイを調べる部分）は {@code Main} 側に残し、
 * このクラスは取得済みの数値だけを受け取って計算する。そのおかげでそのまま単体テストできる。
 */
public final class DisplayMetrics {

    private DisplayMetrics() {}

    /** design baseline: フルHD(1920px幅)で BASE_CELL_W/H がちょうど良い大きさになる想定。 */
    public static final double BASELINE_SCREEN_WIDTH_PX = 1920.0;

    /** 拡大率の下限（縮小はしない）。 */
    public static final double MIN_SCALE = 1.0;

    /** 拡大率の上限（大きくしすぎると1画面に収まる行数が減りすぎる）。 */
    public static final double MAX_SCALE = 2.5;

    /**
     * 画面の物理幅から拡大率を求める。
     *
     * @param physicalWidthPx OS の HiDPI スケーリングも加味した実ピクセル幅
     * @return {@value #MIN_SCALE} 〜 {@value #MAX_SCALE} に収めた拡大率
     */
    public static double scaleForWidth(double physicalWidthPx) {
        double scale = physicalWidthPx / BASELINE_SCREEN_WIDTH_PX;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    /** 拡大率に応じたフォントセルサイズ {幅, 高さ}。 */
    public static int[] cellSize(double scale) {
        return new int[] {
            (int) Math.round(MiscFixedBold9x15.BASE_CELL_W * scale),
            (int) Math.round(MiscFixedBold9x15.BASE_CELL_H * scale)
        };
    }

    /**
     * 拡大率に応じたウィンドウサイズ {幅, 高さ}。画面の利用可能領域を超えないようクランプする。
     *
     * @param baseWidth  等倍時のウィンドウ幅
     * @param baseHeight 等倍時のウィンドウ高さ
     * @param maxWidth   画面の利用可能幅（タスクバー等を除いた値）
     * @param maxHeight  画面の利用可能高さ
     */
    public static int[] windowSize(int baseWidth, int baseHeight, double scale,
                                   int maxWidth, int maxHeight) {
        int w = (int) Math.round(baseWidth  * scale);
        int h = (int) Math.round(baseHeight * scale);
        return new int[] { Math.min(w, maxWidth), Math.min(h, maxHeight) };
    }
}
