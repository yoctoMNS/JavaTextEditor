package dev.javatexteditor.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * 2026-08 フォントサイズ別描画コスト調査で追加した非ASCII文字グリフキャッシュ
 * （EditorCanvas.getNonAsciiGlyph/nonAsciiGlyphCache）の正しさを検証するテスト。
 *
 * <p>調査で判明した内容: MiscFixedBold9x15が対応しない非ASCII文字（日本語コメント等）は
 * 以前 g2.drawString() を毎paintごとに直接呼んで再ラスタライズしていたが、これを
 * ASCII本文と同じ「BufferedImageへ1回だけ描画してキャッシュし、以降はdrawImage()で
 * 使い回す」方式に変更した（詳細は gui-rendering-pipeline スキル参照）。
 * このテストは「キャッシュ導入によって描画結果（色・位置・サイズ）が変わっていないか」
 * 「フォントサイズ変更・テーマ変更でキャッシュが正しく無効化されるか」を検証する。
 */
public class NonAsciiGlyphCacheTest {
    public static void main(String[] args) {
        int pass = 0;
        int total = 0;

        // Test 1: 日本語文字がLIGHT_MODEの前景色で描画される（キャッシュ経由でも色が正しい）
        {
            total++;
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(200, 100);
            canvas.setInitialCellSize(20, 30);
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setText("あ");
            canvas.setCursor(0, 5); // カーソルを文字と重ならない位置に置く

            BufferedImage img = render(canvas, 200, 100);
            boolean found = hasPixelColor(img, 0x33, 0x33, 0x33, 0, 0, 20, 30);
            pass += check("非ASCII文字がLIGHT_MODE前景色(0x333333)で描画される", found);
        }

        // Test 2: テーマ変更後、非ASCII文字の色がDARK_MODEの前景色に切り替わる
        //         （invalidateGlyphCache()がnonAsciiGlyphCacheもクリアしていることの確認）
        {
            total++;
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(200, 100);
            canvas.setInitialCellSize(20, 30);
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setText("あ");
            canvas.setCursor(0, 5);
            render(canvas, 200, 100); // 1回描画してLIGHT_MODEのグリフをキャッシュさせる

            canvas.setTheme(Theme.DARK_MODE);
            BufferedImage img = render(canvas, 200, 100);
            // LIGHT_MODEは明るい背景に暗い文字、DARK_MODEは暗い背景に明るい文字なので、
            // 「最も暗い画素」と「最も明るい画素」の明度で判定する（アンチエイリアスの
            // 縁は前景・背景の中間色になり単純な色距離では偶然一致し得るため、
            // 中間色を含む厳密色一致ではなくストローク中心の明度方向で見る）。
            int maxBrightnessDark = maxBrightness(img, 0, 0, 20, 30);
            pass += check("DARK_MODEでは明るい前景色(0xD4D4D4相当)の画素がLIGHT_MODEの"
                + "暗い前景色(0x333333=明度51)より明るく描画される maxBrightness=" + maxBrightnessDark,
                maxBrightnessDark > 150);
        }

        // Test 3: フォントサイズ変更後、非ASCII文字の描画結果がサイズに応じて変わる
        //         （古いセルサイズのキャッシュ画像を使い回していないことの確認）
        {
            total++;
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(200, 200);
            canvas.setInitialCellSize(10, 15);
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setText("あ");
            canvas.setCursor(0, 5);
            BufferedImage small = render(canvas, 200, 200);
            int smallNonBg = countNonBackgroundPixels(small, 0, 0, 10, 15, 0xF5, 0xF0, 0xE6);

            canvas.setInitialCellSize(40, 60);
            canvas.setCursor(0, 5);
            BufferedImage large = render(canvas, 200, 200);
            int largeNonBg = countNonBackgroundPixels(large, 0, 0, 40, 60, 0xF5, 0xF0, 0xE6);

            // 拡大後のセルは面積が16倍(4x4)になるため、文字の塗り面積も明確に増えるはず。
            boolean grew = largeNonBg > smallNonBg * 2;
            pass += check("フォントサイズ拡大で非ASCII文字の描画サイズも拡大する(旧サイズキャッシュ流用なし)"
                + " small=" + smallNonBg + " large=" + largeNonBg, grew);
        }

        // Test 4: 同一の非ASCII文字を複数回描画しても結果ピクセルが同一
        //         （キャッシュされた同一BufferedImageを再利用しており、毎回異なるラスタライズ結果に
        //          なる非決定的な描画になっていないことの確認）
        {
            total++;
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(200, 100);
            canvas.setInitialCellSize(20, 30);
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setText("漢");
            canvas.setCursor(0, 5);

            BufferedImage first = render(canvas, 200, 100);
            BufferedImage second = render(canvas, 200, 100);
            boolean identical = imagesEqual(first, second, 0, 0, 20, 30);
            pass += check("同一非ASCII文字の連続描画結果が完全一致する（キャッシュ再利用の一貫性）", identical);
        }

        // Test 5: 全角文字（2セル幅）のXピクセル位置が、フォントサイズを変えても
        //         「列番号×セル幅×2」という等幅の理屈通りになる（xForColとの整合性確認）
        {
            total++;
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 100);
            canvas.setInitialCellSize(12, 20);
            canvas.setTheme(Theme.LIGHT_MODE);
            // "AA" の後に全角文字「あ」を置く。カーソルを「あ」の直後(col=3)に置き、
            // カーソルブロックのX座標が (半角2文字 + 全角1文字=2セル) * cellW = 4*12 = 48 になるはず。
            canvas.setText("AAあB");
            canvas.setCursor(0, 3);
            canvas.setInsertMode(false);

            BufferedImage img = render(canvas, 400, 100);
            int cursorX = 3 * 12 + 12; // AA(2セル)+あ(2セル)=4セル分 → 48px から開始
            boolean cursorAtExpectedX = hasPixelColor(img, 0x33, 0x33, 0x33, cursorX, 0, 12, 20);
            pass += check("全角文字を含む行でカーソルXピクセル座標が等幅計算(列×セル幅)と一致する"
                + " expectedX=" + cursorX, cursorAtExpectedX);
        }

        System.out.println();
        System.out.println("=== NonAsciiGlyphCacheTest: " + pass + "/" + total + " passed ===");
        System.exit(pass == total ? 0 : 1);
    }

    private static int check(String label, boolean ok) {
        System.out.println((ok ? "[OK] " : "[FAIL] ") + label);
        return ok ? 1 : 0;
    }

    private static BufferedImage render(EditorCanvas canvas, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        canvas.paint(g2);
        g2.dispose();
        return img;
    }

    private static boolean hasPixelColor(BufferedImage img, int r, int g, int b,
            int x0, int y0, int w, int h) {
        for (int y = y0; y < y0 + h && y < img.getHeight(); y++) {
            for (int x = x0; x < x0 + w && x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int pr = (rgb >> 16) & 0xFF, pg = (rgb >> 8) & 0xFF, pb = rgb & 0xFF;
                if (pr == r && pg == g && pb == b) return true;
            }
        }
        return false;
    }

    /** 指定領域内での最大明度（R+G+B）/3を返す。 */
    private static int maxBrightness(BufferedImage img, int x0, int y0, int w, int h) {
        int best = 0;
        for (int y = y0; y < y0 + h && y < img.getHeight(); y++) {
            for (int x = x0; x < x0 + w && x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int pr = (rgb >> 16) & 0xFF, pg = (rgb >> 8) & 0xFF, pb = rgb & 0xFF;
                int brightness = (pr + pg + pb) / 3;
                if (brightness > best) best = brightness;
            }
        }
        return best;
    }

    private static int countNonBackgroundPixels(BufferedImage img, int x0, int y0, int w, int h,
            int bgR, int bgG, int bgB) {
        int count = 0;
        for (int y = y0; y < y0 + h && y < img.getHeight(); y++) {
            for (int x = x0; x < x0 + w && x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int pr = (rgb >> 16) & 0xFF, pg = (rgb >> 8) & 0xFF, pb = rgb & 0xFF;
                if (pr != bgR || pg != bgG || pb != bgB) count++;
            }
        }
        return count;
    }

    private static boolean imagesEqual(BufferedImage a, BufferedImage b, int x0, int y0, int w, int h) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
            }
        }
        return true;
    }
}
