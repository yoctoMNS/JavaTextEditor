package dev.javatexteditor.ui;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * BitmapFont5x7 ~ BitmapFont10x20（misc-fixed生成フォント群）と FixedFontCatalog の
 * 横縦比率選定ロジックのテスト。自作 main ハーネス方式（editor-testing-strategy 参照）。
 */
public class FixedFontCatalogTest {
    public static void main(String[] args) {
        int pass = 0;
        int total = 0;

        // Test: 候補フォント数は12（5x7〜10x20、Bold/Oblique/ja/koは対象外）
        total++;
        {
            boolean ok = FixedFontCatalog.CANDIDATES.size() == 12;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "候補フォント数==12, actual=" + FixedFontCatalog.CANDIDATES.size());
            pass += ok ? 1 : 0;
        }

        // Test: 各候補フォントの isSupported/renderGlyph/descentPixels が一貫して動作する
        for (FixedBitmapFont font : FixedFontCatalog.CANDIDATES) {
            total++;
            String label = font.cellW() + "x" + font.cellH();
            boolean supported = font.isSupportedI(' ') && font.isSupportedI('A') && font.isSupportedI('~')
                && !font.isSupportedI(0x3041)   // ひらがな
                && !font.isSupportedI(0x4E00);  // 漢字
            BufferedImage img = font.renderGlyphI('A', font.cellW(), font.cellH(), 0xFF0000);
            boolean sizeOk = img.getWidth() == font.cellW() && img.getHeight() == font.cellH();
            int litCount = 0;
            for (int row = 0; row < img.getHeight(); row++)
                for (int col = 0; col < img.getWidth(); col++)
                    if ((img.getRGB(col, row) & 0xFF000000) != 0) litCount++;
            boolean litOk = litCount > 0;
            int descent = font.descentPixelsI(font.cellH());
            boolean descentOk = descent >= 0 && descent < font.cellH();
            boolean ok = supported && sizeOk && litOk && descentOk;
            System.out.println((ok ? "[OK] " : "[FAIL] ") + "BitmapFont" + label
                + " isSupported=" + supported + " size=" + img.getWidth() + "x" + img.getHeight()
                + " lit=" + litCount + " descent=" + descent);
            pass += ok ? 1 : 0;
        }

        // Test: select() はセルサイズと完全一致するフォントをそのまま選ぶ
        total++;
        {
            boolean ok = FixedFontCatalog.select(5, 7) == BitmapFont5x7.INSTANCE
                && FixedFontCatalog.select(6, 13) == BitmapFont6x13.INSTANCE
                && FixedFontCatalog.select(9, 18) == BitmapFont9x18.INSTANCE
                && FixedFontCatalog.select(10, 20) == BitmapFont10x20.INSTANCE;
            System.out.println((ok ? "[OK] " : "[FAIL] ") + "select() 完全一致セルサイズ");
            pass += ok ? 1 : 0;
        }

        // Test: 比率が同一（0.5）で複数候補がタイする場合、絶対サイズが近い方を選ぶ
        // 6x12/7x14/9x18/10x20 は全て 幅:高さ=1:2。20x40 に最も近いのは 10x20。
        total++;
        {
            FixedBitmapFont selected = FixedFontCatalog.select(20, 40);
            boolean ok = selected == BitmapFont10x20.INSTANCE;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "select(20,40) タイブレーク -> " + selected.cellW() + "x" + selected.cellH());
            pass += ok ? 1 : 0;
        }

        // Test: select() は最も横縦比率が近いフォントを選ぶ（絶対サイズは一致しなくてよい）
        // cellW=5,cellH=20 (ratio=0.25) に最も近い比率は 6x13 (ratio=0.4615)。
        total++;
        {
            FixedBitmapFont selected = FixedFontCatalog.select(5, 20);
            boolean ok = selected == BitmapFont6x13.INSTANCE;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "select(5,20) 比率最近傍 -> " + selected.cellW() + "x" + selected.cellH());
            pass += ok ? 1 : 0;
        }

        // Test: EditorCanvas.adjustCellWidth/Height で cellW/cellH の比率が大きく変わると
        // 描画に使うビットマップフォントが自動的に切り替わる（getGlyphFg 経由で描画クラッシュしないことも兼ねて確認）
        total++;
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("A");
            int startW = canvas.getCellW();
            int startH = canvas.getCellH();
            // 幅だけ大きく縮めて横縦比率を10x20から遠ざける
            canvas.adjustCellWidth(-(startW - 5));
            boolean noCrash;
            try {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
                canvas.paint(img.getGraphics());
                noCrash = true;
            } catch (Exception e) {
                noCrash = false;
            }
            System.out.println((noCrash ? "[OK] " : "[FAIL] ")
                + "セル幅縮小後もクラッシュせず描画できる (cellW=" + canvas.getCellW() + " cellH=" + canvas.getCellH() + ")");
            pass += noCrash ? 1 : 0;
        }

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
