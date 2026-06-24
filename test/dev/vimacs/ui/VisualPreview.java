package dev.vimacs.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * EditorCanvasの描画結果をPNGに保存する目視確認ツール。
 * ウィンドウを開かずにヘッドレス環境でも動作する。
 */
public class VisualPreview {
    public static void main(String[] args) throws Exception {
        render(Theme.LIGHT_MODE, false, 0, 0, "preview_light_normal.png");
        render(Theme.LIGHT_MODE, true,  0, 0, "preview_light_insert.png");
        render(Theme.DARK_MODE,  false, 0, 0, "preview_dark_normal.png");
        render(Theme.DARK_MODE,  true,  0, 0, "preview_dark_insert.png");
        // 日本語テキストの全角幅確認用
        render(Theme.DARK_MODE, false, 2, 0, "preview_dark_japanese.png");
        System.out.println("プレビュー画像をbuild/に保存しました。");
    }

    static void render(Theme theme, boolean insertMode, int cursorRow, int cursorCol, String filename) throws Exception {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setSize(640, 400);
        canvas.setText("Hello, World!\nLine 2: abc def ghi\n日本語テスト（全角）\nLine 4: end of sample");
        canvas.setTheme(theme);
        canvas.setCursor(cursorRow, cursorCol);
        canvas.setInsertMode(insertMode);

        BufferedImage img = new BufferedImage(640, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        canvas.paint(g2);
        g2.dispose();

        File out = new File("build/" + filename);
        ImageIO.write(img, "PNG", out);
        System.out.println("Saved: " + out.getAbsolutePath());
    }
}
