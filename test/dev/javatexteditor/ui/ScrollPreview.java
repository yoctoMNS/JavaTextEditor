package dev.javatexteditor.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * EditorCanvasのスクロール動作を確認するため、
 * scrollRow を変えながら複数の PNG を生成する。
 */
public class ScrollPreview {
    public static void main(String[] args) throws Exception {
        // 110行以上のテキスト（Main.java と同じ）
        StringBuilder demoText = new StringBuilder();
        demoText.append("=== Java Text Editor Demo ===\n");
        demoText.append("j/k: 上下移動  h/l: 左右移動  i: INSERTへ  Esc: NORMALへ\n");
        demoText.append("日本語テスト行: ひらがな・カタカナ・漢字が混在しても動作する\n");
        demoText.append("---\n");
        for (int i = 5; i <= 110; i++) {
            demoText.append("Line ").append(i).append(": ")
                .append("The quick brown fox jumps over the lazy dog. (行番号=").append(i).append(")\n");
        }
        demoText.append("=== End of Demo ===\n");

        // スクロールなし（最初の行）
        renderWithScroll(demoText.toString(), 0, "scroll_top.png", "最初（行0）");
        // スクロール中（行20）
        renderWithScroll(demoText.toString(), 20, "scroll_middle.png", "中間（行20）");
        // スクロール下部（行100）
        renderWithScroll(demoText.toString(), 100, "scroll_bottom.png", "下部（行100）");

        System.out.println("スクロール確認用PNG をbuild/に保存しました。");
    }

    static void renderWithScroll(String text, int scrollRow, String filename, String label) throws Exception {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setSize(800, 400);
        canvas.setText(text);
        canvas.setTheme(Theme.DARK_MODE);
        canvas.setScrollRow(scrollRow);
        canvas.setCursor(scrollRow + 5, 0); // スクロール範囲内のカーソル位置
        canvas.setInsertMode(false);

        BufferedImage img = new BufferedImage(800, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        canvas.paint(g2);
        g2.dispose();

        File out = new File("build/" + filename);
        ImageIO.write(img, "PNG", out);
        System.out.println("✓ " + label + " -> " + out.getAbsolutePath());
    }
}
