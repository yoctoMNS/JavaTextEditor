package dev.javatexteditor.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * v4 VISUALモードのプレビューを生成するツール
 */
public class VisualModePreview {
    public static void main(String[] args) throws Exception {
        // ライトテーマ・VISUALモード
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(640, 400);
            canvas.setText("Hello, World!\nLine 2: abcdefg\nLine 3: sample");
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setCursor(0, 7);  // "Hello, " のあとまで
            canvas.setInsertMode(false);

            // v4: VISUALモード・アンカーは(0,0)、カーソルは(0,7)
            canvas.setVisualMode(true);
            canvas.setSelection(0, 0, 0, 7);

            BufferedImage img = new BufferedImage(640, 400, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            canvas.paint(g2);
            g2.dispose();

            File out = new File("build/preview_v4_visual_light.png");
            ImageIO.write(img, "PNG", out);
            System.out.println("✅ Saved: " + out.getAbsolutePath());
        }

        // ダークテーマ・VISUALモード
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(640, 400);
            canvas.setText("Hello, World!\nLine 2: abcdefg\nLine 3: sample");
            canvas.setTheme(Theme.DARK_MODE);
            canvas.setCursor(0, 7);
            canvas.setInsertMode(false);

            // VISUALモード
            canvas.setVisualMode(true);
            canvas.setSelection(0, 0, 0, 7);

            BufferedImage img = new BufferedImage(640, 400, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            canvas.paint(g2);
            g2.dispose();

            File out = new File("build/preview_v4_visual_dark.png");
            ImageIO.write(img, "PNG", out);
            System.out.println("✅ Saved: " + out.getAbsolutePath());
        }

        // 複数行選択（行をまたぐ）
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(640, 400);
            canvas.setText("Hello, World!\nLine 2: abcdefg\nLine 3: sample\nLine 4");
            canvas.setTheme(Theme.DARK_MODE);
            canvas.setCursor(2, 5);  // Line 3の"sampl"まで
            canvas.setInsertMode(false);

            // VISUALモード・複数行選択: (1,5) から (2,5) まで
            canvas.setVisualMode(true);
            canvas.setSelection(1, 5, 2, 5);

            BufferedImage img = new BufferedImage(640, 400, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            canvas.paint(g2);
            g2.dispose();

            File out = new File("build/preview_v4_visual_multiline.png");
            ImageIO.write(img, "PNG", out);
            System.out.println("✅ Saved: " + out.getAbsolutePath());
        }

        System.out.println("\n✨ v4 VISUALモード プレビュー画像を生成しました:");
        System.out.println("   - build/preview_v4_visual_light.png (ライトテーマ・単行選択)");
        System.out.println("   - build/preview_v4_visual_dark.png (ダークテーマ・単行選択)");
        System.out.println("   - build/preview_v4_visual_multiline.png (複数行選択)");
    }
}
