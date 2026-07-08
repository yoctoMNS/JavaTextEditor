package dev.javatexteditor.ui;

public class PaneResizeCalculatorTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testGrowFirstChildActive();
        testGrowSecondChildActive();
        testShrinkFirstChildActive();
        testShrinkSecondChildActive();
        testClampToMinimum();
        testClampToMaximum();
        testClampWhenActiveIsSecondChildNearMinimum();
        testClampWhenActiveIsFirstChildNearMaximum();
        testTinyTotalSpanStillReturnsMinPane();

        System.out.println("\n=== PaneResizeCalculatorTest: " + passed + "/" + (passed + failed) + " PASS ===");
        if (failed > 0) System.exit(1);
    }

    // ── テストケース ──────────────────────────────────────────────────────────

    static void testGrowFirstChildActive() {
        // 横分割・左側(first child)がアクティブ・Right押下(grow) → dividerLocationは増える
        int result = PaneResizeCalculator.computeNewDividerLocation(
            300, 800, 4, true, true, 20, 60);
        assertEquals("grow + firstChildActive => +step", 320, result);
    }

    static void testGrowSecondChildActive() {
        // 横分割・右側(second child)がアクティブ・Right押下(grow) => dividerLocationは減る
        int result = PaneResizeCalculator.computeNewDividerLocation(
            300, 800, 4, false, true, 20, 60);
        assertEquals("grow + secondChildActive => -step", 280, result);
    }

    static void testShrinkFirstChildActive() {
        // 左側がアクティブ・Left押下(shrink) => dividerLocationは減る
        int result = PaneResizeCalculator.computeNewDividerLocation(
            300, 800, 4, true, false, 20, 60);
        assertEquals("shrink + firstChildActive => -step", 280, result);
    }

    static void testShrinkSecondChildActive() {
        // 右側がアクティブ・Left押下(shrink) => dividerLocationは増える
        int result = PaneResizeCalculator.computeNewDividerLocation(
            300, 800, 4, false, false, 20, 60);
        assertEquals("shrink + secondChildActive => +step", 320, result);
    }

    static void testClampToMinimum() {
        // dividerLocationが既に最小値付近で、さらに縮める操作 => minPanePxでクランプ
        int result = PaneResizeCalculator.computeNewDividerLocation(
            65, 800, 4, true, false, 20, 60);
        assertEquals("clamp to min", 60, result);
    }

    static void testClampToMaximum() {
        // dividerLocationが最大値付近で、さらに広げる操作 => totalSpan - dividerSize - minPanePx でクランプ
        int result = PaneResizeCalculator.computeNewDividerLocation(
            730, 800, 4, true, true, 20, 60);
        int expectedMax = 800 - 4 - 60; // 736
        assertEquals("clamp to max", expectedMax, result);
    }

    static void testClampWhenActiveIsSecondChildNearMinimum() {
        // 右側(second child)がアクティブで、右側を縮める(Left) => dividerLocationは増加方向だが
        // 総幅の制約で最大値を超えないようクランプされる
        int result = PaneResizeCalculator.computeNewDividerLocation(
            730, 800, 4, false, false, 20, 60);
        int expectedMax = 800 - 4 - 60; // 736
        assertEquals("clamp to max when shrinking second child", expectedMax, result);
    }

    static void testClampWhenActiveIsFirstChildNearMaximum() {
        // 左側(first child)がアクティブで、右側の最小サイズを侵害しないようクランプされる
        int result = PaneResizeCalculator.computeNewDividerLocation(
            60, 800, 4, false, true, 20, 60);
        // secondChildActive + grow => delta = -stepPx => 60 - 20 = 40 => clamp to min(60)
        assertEquals("clamp to min when growing second child near start", 60, result);
    }

    static void testTinyTotalSpanStillReturnsMinPane() {
        // 分割の合計幅がminPanePxの2倍未満でも、少なくともminPanePxは返す
        // （maxLocはMath.max(minPanePx, ...)でminPanePxを下回らないため矛盾しない）
        int result = PaneResizeCalculator.computeNewDividerLocation(
            50, 100, 4, true, true, 20, 60);
        assertEquals("tiny total span clamps to minPanePx", 60, result);
    }

    // ── アサーション ────────────────────────────────────────────────────────────

    static void assertEquals(String name, int expected, int actual) {
        if (expected == actual) {
            System.out.println("  PASS " + name);
            passed++;
        } else {
            System.out.println("  FAIL " + name + " (expected=" + expected + ", actual=" + actual + ")");
            failed++;
        }
    }
}
