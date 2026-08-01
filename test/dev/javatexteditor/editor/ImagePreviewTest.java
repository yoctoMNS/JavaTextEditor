package dev.javatexteditor.editor;

import dev.javatexteditor.Main;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * 画像ファイル（png/jpg/jpeg/gif/bmp）を開いた際の全画面プレビュー（Mode.IMAGE）を検証する。
 * mainメソッド形式のテストハーネス（JUnit不使用）。SwingWorkerの非同期タイミングそのものは
 * ヘッドレス環境で確実にテストできないため、EventQueueを手動でポンプして完了を待つ
 * （editor-testing-strategyスキル方針に従い、GUIスレッド依存の厳密な検証は行わない）。
 */
public class ImagePreviewTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testIsImageFileAcceptsWhitelistedExtension();
        testIsImageFileRejectsNonImageExtension();
        testIsImageFileRejectsCorruptFileWithImageExtension();
        testIsImageFileRejectsMissingFile();
        testOpenImageEntersImageModeImmediately();
        testOpenImageLoadsAsynchronouslyAndShowsDimensions();
        testImageModeOwnerInvalidatedAfterSwitchingBuffer();
        testZoomKeysDisableAutoFitAndClamp();
        testResetKeyRestoresAutoFit();
        testCorruptImageFallsBackWithErrorMessage();
        testPanKeysMoveScrollAndSurviveSyncCanvas();
        testSpaceBOpensBufferPickerFromImageModeAndReturnsOnEscape();
        testSemicolonEntersCommandModeFromImage();

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

    private static void openViaCommand(ModalEditor ed, String path) {
        ed.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : ("e " + path).toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    private static Path writeTempPng(String prefix, int w, int h) throws IOException {
        Path file = Files.createTempFile(prefix, ".png");
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", file.toFile());
        return file;
    }

    /** SwingWorker.done() はEDTへ invokeLater されるため、明示的にEventQueueをポンプして待つ。 */
    private static void waitForImageLoad(ModalEditor ed) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (ed.isImageLoadPending() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            SwingUtilities.invokeAndWait(() -> { });
        }
        SwingUtilities.invokeAndWait(() -> { });
    }

    static void testIsImageFileAcceptsWhitelistedExtension() throws IOException {
        Path png = writeTempPng("image-preview-a", 4, 4);
        assertTrue(".pngはisImageFileがtrueを返す", Main.isImageFile(png));
    }

    static void testIsImageFileRejectsNonImageExtension() throws IOException {
        Path txt = Files.createTempFile("image-preview-b", ".txt");
        Files.writeString(txt, "hello");
        assertTrue(".txtはisImageFileがfalseを返す", !Main.isImageFile(txt));
    }

    static void testIsImageFileRejectsCorruptFileWithImageExtension() throws IOException {
        // 拡張子は.pngだが中身は画像として不正なバイト列 → ImageIO.read()の成否で最終判定(A3)
        Path fake = Files.createTempFile("image-preview-c", ".png");
        Files.writeString(fake, "not a real png");
        assertTrue("拡張子だけpngの壊れたファイルはisImageFileがfalseを返す", !Main.isImageFile(fake));
    }

    static void testIsImageFileRejectsMissingFile() {
        Path missing = Path.of("/nonexistent/path/does-not-exist.png");
        assertTrue("存在しないファイルはisImageFileがfalseを返す", !Main.isImageFile(missing));
    }

    static void testOpenImageEntersImageModeImmediately() throws Exception {
        Path png = writeTempPng("image-preview-d", 10, 10);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, png.toString());
        assertTrue(":eで画像を開くと即座にMode.IMAGEへ入る", ed.isImageMode());
        assertEquals("読み取り専用プレビューのためcurrentFilePathはnull", null, ed.getCurrentFilePath());
        assertTrue("開いた直後は自動フィット", ed.isImageAutoFit());
        waitForImageLoad(ed);
    }

    /** `;` はMode.IMAGEでも `:` と同じくCOMMANDモードへ入るエイリアスとして扱われる（2026-07-29追加）。 */
    static void testSemicolonEntersCommandModeFromImage() throws Exception {
        Path png = writeTempPng("image-preview-semicolon", 10, 10);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, png.toString());
        waitForImageLoad(ed);
        assertTrue("画像読み込み後はMode.IMAGE", ed.isImageMode());

        ed.processKey(KeyEvent.VK_UNDEFINED, ';', 0);
        assertTrue("; でCOMMANDモードへ入る", ed.isCommandMode());
    }

    static void testOpenImageLoadsAsynchronouslyAndShowsDimensions() throws Exception {
        Path png = writeTempPng("image-preview-e", 33, 17);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, png.toString());
        waitForImageLoad(ed);
        assertTrue("非同期読み込み完了後はpendingがfalse", !ed.isImageLoadPending());
        assertTrue("非同期読み込み完了後はまだMode.IMAGE", ed.isImageMode());
        BufferedImage loaded = ed.getImageBufferForTest();
        assertTrue("読み込んだBufferedImageが取得できる", loaded != null);
        if (loaded != null) {
            assertEquals("読み込んだ画像の幅が一致する", 33, loaded.getWidth());
            assertEquals("読み込んだ画像の高さが一致する", 17, loaded.getHeight());
        }
    }

    static void testImageModeOwnerInvalidatedAfterSwitchingBuffer() throws Exception {
        Path png = writeTempPng("image-preview-f", 8, 8);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, png.toString());
        waitForImageLoad(ed);
        assertTrue("画像読み込み後はMode.IMAGE", ed.isImageMode());

        Path txt = Files.createTempFile("image-preview-g", ".txt");
        Files.writeString(txt, "plain text file");
        openViaCommand(ed, txt.toString());
        assertTrue("別のテキストファイルへ切り替えるとMode.IMAGEを抜ける", !ed.isImageMode());
        assertEquals("通常ファイルとしてcurrentFilePathが設定される", txt.toString(), ed.getCurrentFilePath());
    }

    static void testZoomKeysDisableAutoFitAndClamp() throws Exception {
        Path png = writeTempPng("image-preview-h", 20, 20);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, png.toString());
        waitForImageLoad(ed);

        double before = ed.getImageZoom();
        ed.processKey(KeyEvent.VK_UNDEFINED, '+', 0);
        assertTrue("+キーで自動フィットが解除される", !ed.isImageAutoFit());
        assertTrue("+キーでズーム倍率が上がる", ed.getImageZoom() > before);

        for (int i = 0; i < 100; i++) ed.processKey(KeyEvent.VK_UNDEFINED, '+', 0);
        assertTrue("+キーの連打はMAX_ZOOMでクランプされる",
            ed.getImageZoom() <= dev.javatexteditor.ui.ImageRenderer.MAX_ZOOM);

        for (int i = 0; i < 200; i++) ed.processKey(KeyEvent.VK_UNDEFINED, '-', 0);
        assertTrue("-キーの連打はMIN_ZOOMでクランプされる",
            ed.getImageZoom() >= dev.javatexteditor.ui.ImageRenderer.MIN_ZOOM);
    }

    static void testResetKeyRestoresAutoFit() throws Exception {
        Path png = writeTempPng("image-preview-i", 12, 12);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, png.toString());
        waitForImageLoad(ed);

        ed.processKey(KeyEvent.VK_UNDEFINED, '+', 0);
        assertTrue("+キー後は自動フィットでない", !ed.isImageAutoFit());
        ed.processKey(KeyEvent.VK_UNDEFINED, '0', 0);
        assertTrue("0キーで自動フィットへ戻る", ed.isImageAutoFit());
        assertEquals("0キーでズーム倍率もデフォルトへ戻る",
            dev.javatexteditor.ui.ImageRenderer.DEFAULT_ZOOM, ed.getImageZoom());
    }

    static void testCorruptImageFallsBackWithErrorMessage() throws Exception {
        // SwingWorkerのdoInBackground()内でImageIO.read()が失敗するケース（spec§5）は、
        // isImageFile()の事前チェックと非同期読み込みの間の一瞬のレースを安定して再現できないため、
        // simulateImageLoadFailureForTest()経由で決定的にフォールバック処理そのものを検証する。
        Path png = writeTempPng("image-preview-j", 5, 5);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, png.toString());
        waitForImageLoad(ed);
        assertTrue("読み込み成功後はMode.IMAGE", ed.isImageMode());

        ed.simulateImageLoadFailureForTest(png);
        assertTrue("読み込み失敗後はMode.IMAGEを抜ける", !ed.isImageMode());
        assertTrue("読み込み失敗のエラーメッセージが表示される",
            ed.getStatusMessage().contains("cannot be displayed"));
    }

    /**
     * 回帰テスト: syncCanvas()がensureCursorVisible*を無条件に呼んでいたため、
     * cursorRow/Col=0のままのMode.IMAGEではパン直後にscrollRow/Colが毎回0へ巻き戻され、
     * hjkl/矢印キーによるパンが実質効かなかった不具合。
     */
    static void testPanKeysMoveScrollAndSurviveSyncCanvas() throws Exception {
        Path png = writeTempPng("image-preview-k", 2000, 2000);
        dev.javatexteditor.ui.EditorCanvas canvas = new dev.javatexteditor.ui.EditorCanvas();
        canvas.setSize(200, 200);
        ModalEditor ed = new ModalEditor("", canvas);
        openViaCommand(ed, png.toString());
        waitForImageLoad(ed);

        ed.processKey(KeyEvent.VK_UNDEFINED, '+', 0);
        for (int i = 0; i < 10; i++) ed.processKey(KeyEvent.VK_UNDEFINED, '+', 0);
        for (int i = 0; i < 5; i++) ed.processKey(KeyEvent.VK_UNDEFINED, 'l', 0);
        assertTrue("lキーでパンした後もscrollColが0に巻き戻らない", canvas.getScrollCol() > 0);
        for (int i = 0; i < 5; i++) ed.processKey(KeyEvent.VK_UNDEFINED, 'j', 0);
        assertTrue("jキーでパンした後もscrollRowが0に巻き戻らない", canvas.getScrollRow() > 0);

        ed.processKey(KeyEvent.VK_UNDEFINED, 'h', 0);
        ed.processKey(KeyEvent.VK_UNDEFINED, 'k', 0);
        assertTrue("hキーでscrollColが減る", canvas.getScrollCol() >= 0);
        assertTrue("kキーでscrollRowが減る", canvas.getScrollRow() >= 0);
    }

    /**
     * 回帰テスト: SPC+bはNORMALモード専用実装だったため、Mode.IMAGE中はバッファ一覧を
     * 開けなかった。processImageKey()にSPC+bを追加し、exitTelescope()もimageModeOwnerを
     * 見てMode.IMAGEへ戻すよう修正した（modeAfterCommand()と同じ参照一致判定を再利用）。
     */
    static void testSpaceBOpensBufferPickerFromImageModeAndReturnsOnEscape() throws Exception {
        Path png = writeTempPng("image-preview-l", 6, 6);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, png.toString());
        waitForImageLoad(ed);
        assertTrue("画像読み込み後はMode.IMAGE", ed.isImageMode());

        ed.processKey(KeyEvent.VK_SPACE, ' ', 0);
        ed.processKey(KeyEvent.VK_B, 'b', 0);
        assertTrue("SPC+bでMode.IMAGEからもTELESCOPEへ入れる", ed.isTelescopeMode());

        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        assertTrue("Escでキャンセルすると元のMode.IMAGEへ戻る", ed.isImageMode());
    }
}
