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
        // v3 までのプレビュー
        render(Theme.LIGHT_MODE, false, false, 0, 0, -1, -1, -1, -1, "preview_light_normal.png");
        render(Theme.LIGHT_MODE, true,  false, 0, 0, -1, -1, -1, -1, "preview_light_insert.png");
        render(Theme.DARK_MODE,  false, false, 0, 0, -1, -1, -1, -1, "preview_dark_normal.png");
        render(Theme.DARK_MODE,  true,  false, 0, 0, -1, -1, -1, -1, "preview_dark_insert.png");

        // v4: VISUALモードプレビュー（ハイライト表示）
        render(Theme.LIGHT_MODE, false, true, 0, 0, 0, 0, 0, 7, "preview_v4_visual_light.png");
        render(Theme.DARK_MODE,  false, true, 0, 0, 0, 0, 0, 7, "preview_v4_visual_dark.png");

        // 日本語テキストの全角幅確認用
        render(Theme.DARK_MODE, false, false, 2, 0, -1, -1, -1, -1, "preview_dark_japanese.png");

        System.out.println("✅ プレビュー画像をbuild/に保存しました。");
        System.out.println("   - preview_v4_visual_light.png (VISUALモード・ライトテーマ)");
        System.out.println("   - preview_v4_visual_dark.png (VISUALモード・ダークテーマ)");
    }

    static void render(Theme theme, boolean insertMode, boolean visualMode,
                       int cursorRow, int cursorCol,
                       int anchorRow, int anchorCol, int selCursorRow, int selCursorCol,
                       String filename) throws Exception {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setSize(640, 400);
        canvas.setText("Hello, World!\nLine 2: abcdefg\n日本語テスト（全角）\nLine 4: VISUAL demo");
        canvas.setTheme(theme);
        canvas.setCursor(cursorRow, cursorCol);
        canvas.setInsertMode(insertMode);

        // v4: VISUALモード対応
        if (visualMode && anchorRow >= 0) {
            canvas.setVisualMode(true);
            canvas.setSelection(anchorRow, anchorCol, selCursorRow, selCursorCol);
        }

        BufferedImage img = new BufferedImage(640, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        canvas.paint(g2);
        g2.dispose();

        File out = new File("build/" + filename);
        ImageIO.write(img, "PNG", out);
        System.out.println("Saved: " + out.getAbsolutePath());
    }
}
