package dev.javatexteditor.ui;

/**
 * 画像プレビューの拡縮・パン計算ロジック（{@link ImageRenderer}、Swing非依存の純粋部分）を検証する。
 * mainメソッド形式のテストハーネス（JUnit不使用）。
 */
public class ImageRendererTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testFitSizeLandscapeIntoWideViewport();
        testFitSizeLandscapeIntoTallViewport();
        testFitSizeUpscalesSmallImage();
        testFitSizeExactMatch();
        testFitSizeInvalidInputsReturnZero();
        testZoomSizeScalesProportionally();
        testZoomInIncreasesAndClampsAtMax();
        testZoomOutDecreasesAndClampsAtMin();
        testZoomRoundTripStaysWithinBounds();
        testClampOffsetWithinRange();
        testClampOffsetNegativeClampsToZero();
        testClampOffsetBeyondMaxClamps();
        testClampOffsetSmallerThanViewportAlwaysZero();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
        System.exit(0);
    }

    static void assertTrue(String name, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name);
            fail++;
        }
    }

    static void assertEquals(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name + " expected=" + expected + " actual=" + actual);
            fail++;
        }
    }

    private static void testFitSizeLandscapeIntoWideViewport() {
        // 1000x500 (2:1) を 400x400 のビューポートへ: 幅基準ではみ出るので高さ基準で400x200
        ImageRenderer.FitSize s = ImageRenderer.computeFitSize(1000, 500, 400, 400);
        assertEquals("fit landscape width", 400, s.width());
        assertEquals("fit landscape height", 200, s.height());
    }

    private static void testFitSizeLandscapeIntoTallViewport() {
        // 400x800 (1:2、縦長画像) を 1000x400 のビューポートへ: 高さ基準で200x400
        ImageRenderer.FitSize s = ImageRenderer.computeFitSize(400, 800, 1000, 400);
        assertEquals("fit portrait width", 200, s.width());
        assertEquals("fit portrait height", 400, s.height());
    }

    private static void testFitSizeUpscalesSmallImage() {
        // 小さい画像はビューポートに合わせて拡大される（自動フィットは縮小・拡大どちらも行う）
        ImageRenderer.FitSize s = ImageRenderer.computeFitSize(10, 10, 100, 100);
        assertEquals("fit upscale width", 100, s.width());
        assertEquals("fit upscale height", 100, s.height());
    }

    private static void testFitSizeExactMatch() {
        ImageRenderer.FitSize s = ImageRenderer.computeFitSize(200, 100, 200, 100);
        assertEquals("fit exact width", 200, s.width());
        assertEquals("fit exact height", 100, s.height());
    }

    private static void testFitSizeInvalidInputsReturnZero() {
        assertEquals("fit zero image width", 0, ImageRenderer.computeFitSize(0, 100, 200, 200).width());
        assertEquals("fit zero viewport height", 0, ImageRenderer.computeFitSize(100, 100, 200, 0).height());
        assertEquals("fit negative width", 0, ImageRenderer.computeFitSize(-5, 100, 200, 200).width());
    }

    private static void testZoomSizeScalesProportionally() {
        ImageRenderer.FitSize s = ImageRenderer.computeZoomSize(200, 100, 2.0);
        assertEquals("zoom size width", 400, s.width());
        assertEquals("zoom size height", 200, s.height());
    }

    private static void testZoomInIncreasesAndClampsAtMax() {
        double z = 1.0;
        double prev = z;
        for (int i = 0; i < 100; i++) {
            z = ImageRenderer.zoomIn(z);
            assertTrue("zoom in never decreases (" + i + ")", z >= prev);
            prev = z;
        }
        assertTrue("zoom in clamps at MAX_ZOOM", z <= ImageRenderer.MAX_ZOOM);
        assertEquals("zoom in reaches MAX_ZOOM", ImageRenderer.MAX_ZOOM, z);
    }

    private static void testZoomOutDecreasesAndClampsAtMin() {
        double z = 1.0;
        double prev = z;
        for (int i = 0; i < 100; i++) {
            z = ImageRenderer.zoomOut(z);
            assertTrue("zoom out never increases (" + i + ")", z <= prev);
            prev = z;
        }
        assertTrue("zoom out clamps at MIN_ZOOM", z >= ImageRenderer.MIN_ZOOM);
        assertEquals("zoom out reaches MIN_ZOOM", ImageRenderer.MIN_ZOOM, z);
    }

    private static void testZoomRoundTripStaysWithinBounds() {
        double z = ImageRenderer.DEFAULT_ZOOM;
        z = ImageRenderer.zoomIn(z);
        z = ImageRenderer.zoomOut(z);
        assertTrue("zoom in then out is close to original",
            Math.abs(z - ImageRenderer.DEFAULT_ZOOM) < 1e-9);
    }

    private static void testClampOffsetWithinRange() {
        assertEquals("clamp within range unchanged", 50, ImageRenderer.clampOffset(50, 500, 200));
    }

    private static void testClampOffsetNegativeClampsToZero() {
        assertEquals("clamp negative to zero", 0, ImageRenderer.clampOffset(-30, 500, 200));
    }

    private static void testClampOffsetBeyondMaxClamps() {
        // contentSize=500, viewportSize=200 → maxOffset=300
        assertEquals("clamp beyond max", 300, ImageRenderer.clampOffset(9999, 500, 200));
    }

    private static void testClampOffsetSmallerThanViewportAlwaysZero() {
        // 画像がビューポートより小さい場合はどんなオフセットを渡しても0（中央寄せのため）
        assertEquals("clamp smaller-than-viewport content", 0, ImageRenderer.clampOffset(40, 100, 300));
    }
}
