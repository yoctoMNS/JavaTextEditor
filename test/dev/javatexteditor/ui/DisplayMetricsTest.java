package dev.javatexteditor.ui;

/**
 * DisplayMetrics（起動時のフォントセル・ウィンドウサイズの算出）の単体テスト。
 * 画面情報の取得を含まない純粋な計算なので、実ディスプレイなしで検証できる。
 */
public class DisplayMetricsTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testFullHdIsBaseline();
        testSmallerScreenIsNotShrunk();
        testFourKIsScaledUp();
        testScaleIsCappedAtMax();
        testCellSizeScalesFromBase();
        testCellSizeAtBaselineEqualsBaseCell();
        testWindowSizeScales();
        testWindowSizeIsClampedToScreen();

        System.out.println();
        System.out.println("PASS: " + passed + " / " + (passed + failed) + "  (FAIL: " + failed + ")");
        if (failed > 0) System.exit(1);
    }

    private static void testFullHdIsBaseline() {
        check("フルHD(1920px)はちょうど等倍",
                DisplayMetrics.scaleForWidth(1920.0) == 1.0);
    }

    private static void testSmallerScreenIsNotShrunk() {
        check("1920pxより狭い画面でも縮小はしない（下限は等倍）",
                DisplayMetrics.scaleForWidth(1280.0) == 1.0);
        check("極端に狭くても等倍を下回らない",
                DisplayMetrics.scaleForWidth(640.0) == 1.0);
    }

    private static void testFourKIsScaledUp() {
        // 3840 / 1920 = 2.0
        check("4K(3840px)では2倍になる",
                Math.abs(DisplayMetrics.scaleForWidth(3840.0) - 2.0) < 1e-9);
    }

    private static void testScaleIsCappedAtMax() {
        check("上限(2.5倍)を超えない",
                DisplayMetrics.scaleForWidth(7680.0) == DisplayMetrics.MAX_SCALE);
    }

    private static void testCellSizeAtBaselineEqualsBaseCell() {
        int[] cell = DisplayMetrics.cellSize(1.0);
        check("等倍ではフォントの基準セルサイズそのもの",
                cell[0] == MiscFixedBold9x15.BASE_CELL_W
             && cell[1] == MiscFixedBold9x15.BASE_CELL_H);
    }

    private static void testCellSizeScalesFromBase() {
        int[] cell = DisplayMetrics.cellSize(2.0);
        check("2倍では基準セルサイズの2倍になる",
                cell[0] == MiscFixedBold9x15.BASE_CELL_W * 2
             && cell[1] == MiscFixedBold9x15.BASE_CELL_H * 2);
    }

    private static void testWindowSizeScales() {
        int[] win = DisplayMetrics.windowSize(1200, 750, 2.0, 10000, 10000);
        check("画面に余裕があれば倍率どおりに拡大する",
                win[0] == 2400 && win[1] == 1500);
    }

    private static void testWindowSizeIsClampedToScreen() {
        int[] win = DisplayMetrics.windowSize(1200, 750, 2.0, 1600, 900);
        check("画面の利用可能領域を超えないようクランプされる",
                win[0] == 1600 && win[1] == 900);

        int[] partial = DisplayMetrics.windowSize(1200, 750, 1.0, 1600, 400);
        check("幅は収まり高さだけ超える場合、高さのみクランプされる",
                partial[0] == 1200 && partial[1] == 400);
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS: " + label);
        } else {
            failed++;
            System.out.println("FAIL [" + label + "]");
        }
    }
}
