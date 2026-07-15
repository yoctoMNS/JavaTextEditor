package dev.javatexteditor.editor;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * {@link ClipboardBinaryCodec}（システムクリップボードのファイル一覧・画像を
 * バッファ挿入用の文字列/バイト列へ変換する純粋ロジック）のテストハーネス
 * （mainメソッド形式・JUnit不使用）。
 *
 * 実クリップボードには一切依存しないため、ヘッドレス環境でも完全に検証できる
 * （ClipboardTestが持つ「実クリップボード往復は手動確認が必要」という既知の
 * テストギャップを、ここで扱う変換ロジック部分に限っては解消している）。
 */
public class ClipboardBinaryCodecTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testJoinFilePathsSingle();
        testJoinFilePathsMultiple();
        testJoinFilePathsEmpty();
        testEncodeImageAsPngRoundTrip();
        testEncodeImageAsPngPreservesPixels();

        System.out.printf("%nPASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
        if (fail > 0) System.exit(1);
    }

    static void testJoinFilePathsSingle() {
        System.out.println("[joinFilePaths: 1件はそのままパス]");
        String result = ClipboardBinaryCodec.joinFilePaths(List.of(new File("/tmp/a.txt")));
        check("パスが一致する", result.equals(new File("/tmp/a.txt").getAbsolutePath()));
    }

    static void testJoinFilePathsMultiple() {
        System.out.println("[joinFilePaths: 複数件は改行区切り]");
        List<File> files = Arrays.asList(new File("/tmp/a.txt"), new File("/tmp/b.txt"));
        String result = ClipboardBinaryCodec.joinFilePaths(files);
        String expected = new File("/tmp/a.txt").getAbsolutePath() + "\n" + new File("/tmp/b.txt").getAbsolutePath();
        check("2件が改行区切りで連結される", result.equals(expected));
    }

    static void testJoinFilePathsEmpty() {
        System.out.println("[joinFilePaths: 空リストは空文字列]");
        String result = ClipboardBinaryCodec.joinFilePaths(List.of());
        check("空文字列になる", result.isEmpty());
    }

    static void testEncodeImageAsPngRoundTrip() throws Exception {
        System.out.println("[encodeImageAsPng: ISO-8859-1経由のバイト往復が一致する]");
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) img.setRGB(x, y, 0xFF00FF00);
        }
        byte[] pngBytes = ClipboardBinaryCodec.encodeImageAsPng(img);
        // ModalEditor.pasteFromSystemClipboard() と同じISO-8859-1往復
        String text = new String(pngBytes, StandardCharsets.ISO_8859_1);
        byte[] restored = text.getBytes(StandardCharsets.ISO_8859_1);
        check("PNGバイト列がISO-8859-1往復で完全に一致する", Arrays.equals(pngBytes, restored));
    }

    static void testEncodeImageAsPngPreservesPixels() throws Exception {
        System.out.println("[encodeImageAsPng: デコードすると元のピクセルが復元できる]");
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFFAABBCC);
        byte[] pngBytes = ClipboardBinaryCodec.encodeImageAsPng(img);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(pngBytes));
        check("デコード後のサイズが一致する", decoded.getWidth() == 2 && decoded.getHeight() == 2);
        check("デコード後のピクセルが一致する", decoded.getRGB(0, 0) == 0xFFAABBCC);
    }

    static void check(String label, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + label);
            pass++;
        } else {
            System.out.println("  FAIL: " + label);
            fail++;
        }
    }
}
