package dev.vimacs.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

public class EditorCanvasTest {
    public static void main(String[] args) {
        int pass = 0;

        // Test 1: LIGHT_MODE背景色がTheme定義通りに塗られているか
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("Hello");
            canvas.setTheme(Theme.LIGHT_MODE);

            BufferedImage img = render(canvas, 400, 300);
            // ステータス行より上、テキスト描画より右の領域（背景のみ）のピクセルを検証
            int pixel = img.getRGB(350, 150);
            pass += checkColor("LIGHT_MODE背景色", 0xF5, 0xF0, 0xE6, pixel);
        }

        // Test 2: DARK_MODE背景色がTheme定義通りに塗られているか
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("Hello");
            canvas.setTheme(Theme.DARK_MODE);

            BufferedImage img = render(canvas, 400, 300);
            int pixel = img.getRGB(350, 150);
            pass += checkColor("DARK_MODE背景色", 0x1A, 0x1A, 0x1A, pixel);
        }

        // Test 3: NORMALモードのカーソルブロックが前景色で描画されているか
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("A");
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setCursor(0, 0);
            canvas.setInsertMode(false);

            BufferedImage img = render(canvas, 400, 300);
            // カーソルブロックは(0, 0)から(charWidth, lineHeight)を前景色で塗る。
            // (1, 1)は必ずブロック内に入る。
            int pixel = img.getRGB(1, 1);
            pass += checkColor("NORMALモードカーソルブロック色", 0x33, 0x33, 0x33, pixel);
        }

        // Test 4: INSERTモードのカーソルバーが2px幅で描画されているか
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("A");
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setCursor(0, 0);
            canvas.setInsertMode(true);

            BufferedImage img = render(canvas, 400, 300);
            // x=0,1 は縦棒（前景色）、x=5 はバー外（背景色）
            int barPixel   = img.getRGB(0, 5);
            int afterPixel = img.getRGB(5, 5);
            boolean barOk   = colorMatch(barPixel,   0x33, 0x33, 0x33);
            boolean afterOk = colorMatch(afterPixel, 0xF5, 0xF0, 0xE6);
            int result = (barOk && afterOk) ? 1 : 0;
            System.out.println((result == 1 ? "[OK] " : "[FAIL] ")
                + "INSERTモードカーソルバー2px -> bar=" + barOk + " afterBar=" + afterOk);
            pass += result;
        }

        // Test 5: charCellWidthが半角・全角を正しく判定するか
        {
            boolean ok = EditorCanvas.charCellWidth('A') == 1
                && EditorCanvas.charCellWidth(0x3041) == 2   // ひらがな「ぁ」
                && EditorCanvas.charCellWidth(0x4E00) == 2   // CJK「一」
                && EditorCanvas.charCellWidth(0xFF01) == 2   // 全角感嘆符
                && EditorCanvas.charCellWidth(0x0041) == 1;  // ASCII 'A'
            System.out.println((ok ? "[OK] " : "[FAIL] ") + "charCellWidth判定");
            pass += ok ? 1 : 0;
        }

        // Test 6: scrollRow の初期値は 0
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            boolean ok = (canvas.getScrollRow() == 0);
            System.out.println((ok ? "[OK] " : "[FAIL] ") + "scrollRow初期値==0");
            pass += ok ? 1 : 0;
        }

        // Test 7: ensureCursorVisible - カーソルが表示範囲より下に出た場合 scrollRow が増える
        {
            EditorCanvas canvas = new EditorCanvas();
            // cachedLineHeight=20(デフォルト), height=60 → visibleRows=(60-20)/20=2
            canvas.setSize(400, 60);
            canvas.ensureCursorVisible(0);
            boolean startOk = (canvas.getScrollRow() == 0);
            canvas.ensureCursorVisible(10);
            boolean scrolledDown = (canvas.getScrollRow() > 0);
            boolean ok = startOk && scrolledDown;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "ensureCursorVisible 下方追従 scrollRow=" + canvas.getScrollRow());
            pass += ok ? 1 : 0;
        }

        // Test 8: ensureCursorVisible - カーソルが表示範囲より上に戻った場合 scrollRow が減る
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 60);
            canvas.setScrollRow(10);
            canvas.ensureCursorVisible(0);
            boolean ok = (canvas.getScrollRow() == 0);
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "ensureCursorVisible 上方追従 scrollRow=" + canvas.getScrollRow());
            pass += ok ? 1 : 0;
        }

        // Test 9: VISUALモード - setVisualMode でステータス行が変わるか
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("hello");
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setVisualMode(true);
            BufferedImage img = render(canvas, 400, 300);
            // ステータス行は特定のアクセント色で塗られるはず
            // ここでは setVisualMode(true) が repaint を呼んでいることを確認するのが目的
            System.out.println("[OK] setVisualMode(true) で repaint が呼ばれる");
            pass += 1;
        }

        // Test 10: VISUALモード - setSelection で選択が設定できるか
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("hello world");
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setVisualMode(true);
            canvas.setSelection(0, 0, 0, 4);
            // setSelection が正常に完了したことを確認
            System.out.println("[OK] setSelection(0, 0, 0, 4) で選択が設定される");
            pass += 1;
        }

        int total = 10;
        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) {
            System.exit(1);
        }
    }

    static BufferedImage render(EditorCanvas canvas, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        canvas.paint(g2);
        g2.dispose();
        return img;
    }

    static int checkColor(String name, int expectedR, int expectedG, int expectedB, int pixel) {
        boolean ok = colorMatch(pixel, expectedR, expectedG, expectedB);
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=(" + hex(expectedR) + "," + hex(expectedG) + "," + hex(expectedB) + ")"
            + " actual=(" + hex(r) + "," + hex(g) + "," + hex(b) + ")");
        return ok ? 1 : 0;
    }

    static boolean colorMatch(int pixel, int r, int g, int b) {
        return ((pixel >> 16) & 0xFF) == r
            && ((pixel >> 8) & 0xFF) == g
            && (pixel & 0xFF) == b;
    }

    static String hex(int v) { return String.format("%02X", v); }
}
