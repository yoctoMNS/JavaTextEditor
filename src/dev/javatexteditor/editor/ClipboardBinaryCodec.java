package dev.javatexteditor.editor;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * システムクリップボードから読み取った非文字列データ（ファイル一覧・画像）を
 * バッファへ挿入可能な形へ変換する純粋ロジック。java.awt.datatransfer.Transferable/
 * Clipboardには依存しないため、実クリップボードなしでテスト可能。
 */
final class ClipboardBinaryCodec {

    private ClipboardBinaryCodec() {}

    /** ファイル一覧を、絶対パスを改行区切りにした文字列へ変換する。空リストなら空文字列。 */
    static String joinFilePaths(List<File> files) {
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(f.getAbsolutePath());
        }
        return sb.toString();
    }

    /** java.awt.Image をPNGバイト列へエンコードする。BufferedImage以外は描画してBufferedImage化する。 */
    static byte[] encodeImageAsPng(Image image) throws IOException {
        BufferedImage buffered = (image instanceof BufferedImage bi) ? bi : toBufferedImage(image);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(buffered, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage toBufferedImage(Image image) {
        int width = Math.max(1, image.getWidth(null));
        int height = Math.max(1, image.getHeight(null));
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = buffered.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return buffered;
    }
}
