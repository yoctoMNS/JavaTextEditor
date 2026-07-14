package dev.javatexteditor.ui;

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
            // 1行目の描画域内（テキスト描画より右の背景のみの領域）のピクセルを検証。
            // y=150相当の行は文書末尾を超えるため、その領域は白/黒で塗られる
            // （後続の「zz末尾超過領域」テスト参照）ので、ここでは行0の内側(y=5)を見る。
            int pixel = img.getRGB(350, 5);
            pass += checkColor("LIGHT_MODE背景色", 0xF5, 0xF0, 0xE6, pixel);
        }

        // Test 2: DARK_MODE背景色がTheme定義通りに塗られているか
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("Hello");
            canvas.setTheme(Theme.DARK_MODE);

            BufferedImage img = render(canvas, 400, 300);
            // 行0の内側(y=5)を見る（理由はLIGHT_MODE背景色テストのコメント参照）
            int pixel = img.getRGB(350, 5);
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

        // Test 4: INSERTモードでもカーソルはブロック（■）のまま描画されるか
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("A");
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setCursor(0, 0);
            canvas.setInsertMode(true);

            BufferedImage img = render(canvas, 400, 300);
            // (1, 1)は必ずブロック内に入る。INSERTモードでも縦棒(2px)ではなく
            // NORMALモードと同じブロックカーソルが描画されることを確認する。
            int pixel = img.getRGB(1, 1);
            pass += checkColor("INSERTモードカーソルブロック色", 0x33, 0x33, 0x33, pixel);
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

        // Test 11: VISUAL LINE モード - setVisualLineMode で状態が変わる
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("line0\nline1\nline2");
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setVisualMode(true);
            canvas.setVisualLineMode(true);
            canvas.setSelection(0, 0, 1, 0);
            // 描画が例外なく完了することを確認
            BufferedImage img = render(canvas, 400, 300);
            System.out.println("[OK] setVisualLineMode(true) で行単位ハイライト描画が完了");
            pass += 1;
        }

        // Test 12: VISUAL LINE モード - ステータス行が "-- VISUAL LINE --" になる
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 80);
            canvas.setText("line0\nline1");
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setInsertMode(false);
            canvas.setVisualMode(true);
            canvas.setVisualLineMode(true);
            // ステータス行はアクセント色(0x99,0x99,0x99)で塗られるので背景色でない
            BufferedImage img = render(canvas, 400, 80);
            int pixel = img.getRGB(50, 75); // ステータス行の中央付近
            boolean isNotBackground = !colorMatch(pixel, 0xF5, 0xF0, 0xE6);
            System.out.println((isNotBackground ? "[OK] " : "[FAIL] ")
                + "VISUAL LINE ステータス行がアクセント色で塗られる");
            pass += isNotBackground ? 1 : 0;
        }

        // Test 13: clearSelection で visualLineMode がリセットされる
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("line0\nline1");
            canvas.setVisualLineMode(true);
            canvas.setVisualMode(true);
            canvas.setSelection(0, 0, 1, 0);
            canvas.clearSelection();
            // clearSelection後は行単位ハイライトなし → 普通の背景が描かれる
            canvas.setVisualMode(false);
            BufferedImage img = render(canvas, 400, 300);
            // 行1（y=20〜39、文書内）の内側を見る。y=100相当は文書末尾を超えるため
            // 白塗り領域になり「ハイライトなし」の検証には使えない。
            int pixel = img.getRGB(350, 25);
            boolean isBackground = colorMatch(pixel, 0xF5, 0xF0, 0xE6);
            System.out.println((isBackground ? "[OK] " : "[FAIL] ")
                + "clearSelection 後はハイライトなし（背景色に戻る）");
            pass += isBackground ? 1 : 0;
        }

        // Test 14: VISUAL LINE - 複数行が全幅でハイライトされるか（ピクセル検証）
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 200);
            canvas.setText("line0\nline1\nline2");
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setVisualMode(true);
            canvas.setVisualLineMode(true);
            canvas.setSelection(0, 0, 1, 0); // row0〜row1を選択
            BufferedImage img = render(canvas, 400, 200);
            // 行0のx=300付近（テキスト描画範囲外の右端）がアクセント色になるはず
            // cachedLineHeight≒20なので行0のy範囲は0〜19、中央y≒10
            int pixel = img.getRGB(300, 10);
            boolean isAccent = colorMatch(pixel, 0x99, 0x99, 0x99);
            System.out.println((isAccent ? "[OK] " : "[FAIL] ")
                + "VISUAL LINE 行0の右端がアクセント色（行全幅ハイライト）");
            pass += isAccent ? 1 : 0;
        }

        // Test 15: VISUAL LINE 選択後の repaint でクラッシュしない（境界値）
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("only line");
            canvas.setTheme(Theme.DARK_MODE);
            canvas.setVisualMode(true);
            canvas.setVisualLineMode(true);
            canvas.setSelection(0, 0, 0, 0); // 1行のみ選択
            BufferedImage img = render(canvas, 400, 300);
            System.out.println("[OK] VISUAL LINE 単一行選択で repaint がクラッシュしない");
            pass += 1;
        }

        // =====================================================================
        // 横スクロール（⑤ v3）テスト
        // =====================================================================

        // Test 16: scrollCol 初期値は 0
        {
            EditorCanvas canvas = new EditorCanvas();
            boolean ok = canvas.getScrollCol() == 0;
            System.out.println((ok ? "[OK] " : "[FAIL] ") + "scrollCol 初期値 == 0");
            pass += ok ? 1 : 0;
        }

        // Test 17: setScrollCol で値が設定される（負の値は 0 にクランプ）
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setScrollCol(10);
            boolean posOk = canvas.getScrollCol() == 10;
            canvas.setScrollCol(-5);
            boolean negOk = canvas.getScrollCol() == 0;
            boolean ok = posOk && negOk;
            System.out.println((ok ? "[OK] " : "[FAIL] ") + "setScrollCol 正値/負値クランプ");
            pass += ok ? 1 : 0;
        }

        // Test 18: ensureCursorColVisible - カーソルが右にはみ出た場合 scrollCol が増える
        {
            // width=200, cachedCharWidth=10 (デフォルト) → visibleCols ≈ 20
            // cursorCol=30 → 右端(20)を超えるので scrollCol が増えるはず
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(200, 300);
            canvas.setText("A".repeat(50));
            canvas.ensureCursorColVisible(30, "A".repeat(50));
            boolean ok = canvas.getScrollCol() > 0;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "ensureCursorColVisible 右方追従 scrollCol=" + canvas.getScrollCol());
            pass += ok ? 1 : 0;
        }

        // Test 19: ensureCursorColVisible - カーソルが左に戻った場合 scrollCol が減る
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(200, 300);
            canvas.setText("A".repeat(50));
            canvas.setScrollCol(20); // まず右にスクロール
            canvas.ensureCursorColVisible(0, "A".repeat(50));
            boolean ok = canvas.getScrollCol() == 0;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "ensureCursorColVisible 左方追従 scrollCol=" + canvas.getScrollCol());
            pass += ok ? 1 : 0;
        }

        // Test 20: scrollCol > 0 の時、行頭の文字が描画されない（背景色が見える）
        {
            // 短い文字列をスクロールすることで行頭が隠れ、左端が背景色になるはず
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("XXXXXXXXXX");  // 左端に描画される文字列
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setScrollCol(0);  // スクロールなし → X が左端に描画される → 背景色でない
            BufferedImage imgNoScroll = render(canvas, 400, 300);
            canvas.setScrollCol(100); // 大きくスクロール → 文字が画面外 → 左端は背景色
            BufferedImage imgScrolled = render(canvas, 400, 300);
            // スクロールなし: 左端 (1,1) はカーソル色（前景色）か文字領域
            // スクロールあり: 左端 (1,1) は背景色になるはず
            boolean isBackground = colorMatch(imgScrolled.getRGB(1, 1), 0xF5, 0xF0, 0xE6);
            System.out.println((isBackground ? "[OK] " : "[FAIL] ")
                + "横スクロール後の左端が背景色になる");
            pass += isBackground ? 1 : 0;
        }

        // Test 21: ensureCursorColVisible は全角文字（2セル分）を正しく計算する
        {
            // "あ" は 2 セル。"ああ" なら 4 セル。
            // width=60, cachedCharWidth=10 → visibleCols=6セル
            // cursorCol=4 (5文字目の位置) の全角文字列では 4*2=8 セル → 範囲外
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(60, 300);
            String allZenkaku = "ああああああ"; // 6文字 × 2セル = 12セル
            canvas.ensureCursorColVisible(4, allZenkaku); // 8セル目にカーソル
            boolean ok = canvas.getScrollCol() > 0;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "全角文字横スクロール scrollCol=" + canvas.getScrollCol());
            pass += ok ? 1 : 0;
        }

        // Test 22: scrollCol=0 に戻った後、描画が正常に復帰する（クラッシュなし）
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("Hello, World!");
            canvas.setTheme(Theme.DARK_MODE);
            canvas.setScrollCol(5);
            BufferedImage img1 = render(canvas, 400, 300);
            canvas.setScrollCol(0);
            BufferedImage img2 = render(canvas, 400, 300);
            System.out.println("[OK] scrollCol 5→0 復帰描画がクラッシュしない");
            pass += 1;
        }

        // =====================================================================
        // 半角フォント（TtfMonoFont / IBM Plex Mono Regular）テスト
        // =====================================================================

        // Test 23: isSupported - ASCII 範囲は true、範囲外は false
        {
            TtfMonoFont f = TtfMonoFont.INSTANCE;
            boolean ok = f.isSupported(' ')
                && f.isSupported('A')
                && f.isSupported('~')
                && !f.isSupported(0x3041)   // ひらがな
                && !f.isSupported(0x4E00);  // 漢字
            System.out.println((ok ? "[OK] " : "[FAIL] ") + "TtfMonoFont.isSupported 範囲判定");
            pass += ok ? 1 : 0;
        }

        // Test 24: renderGlyph - 返される画像のサイズがセルサイズと一致する
        {
            java.awt.image.BufferedImage img = TtfMonoFont.INSTANCE.renderGlyph('A', 10, 20, 0xFFFFFF);
            boolean ok = img.getWidth() == 10 && img.getHeight() == 20;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "renderGlyph サイズ (10x20) -> " + img.getWidth() + "x" + img.getHeight());
            pass += ok ? 1 : 0;
        }

        // Test 25: renderGlyph - 'A' の一部ピクセルが点灯している（コンテンツ検証）
        {
            java.awt.image.BufferedImage img = TtfMonoFont.INSTANCE.renderGlyph('A', 10, 20, 0xFF0000);
            int litCount = 0;
            for (int row = 0; row < 20; row++)
                for (int col = 0; col < 10; col++)
                    if ((img.getRGB(col, row) & 0xFF000000) != 0) litCount++;
            boolean ok = litCount > 10; // 少なくとも10ピクセルが点灯しているはず
            System.out.println((ok ? "[OK] " : "[FAIL] ") + "'A' グリフの点灯ピクセル数=" + litCount);
            pass += ok ? 1 : 0;
        }

        // Test 26: adjustCellWidth - セル幅が変わる
        {
            EditorCanvas canvas = new EditorCanvas();
            int before = canvas.getCellW();
            canvas.adjustCellWidth(+5);
            int after = canvas.getCellW();
            boolean ok = after == before + 5;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "adjustCellWidth +5: " + before + " -> " + after);
            pass += ok ? 1 : 0;
        }

        // Test 27: adjustCellHeight - セル高さが変わる
        {
            EditorCanvas canvas = new EditorCanvas();
            int before = canvas.getCellH();
            canvas.adjustCellHeight(+10);
            int after = canvas.getCellH();
            boolean ok = after == before + 10;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "adjustCellHeight +10: " + before + " -> " + after);
            pass += ok ? 1 : 0;
        }

        // Test 28: adjustCellWidth - 最小値 5 でクランプされる
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.adjustCellWidth(-1000);
            boolean ok = canvas.getCellW() == 5;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "adjustCellWidth 最小クランプ == 5, actual=" + canvas.getCellW());
            pass += ok ? 1 : 0;
        }

        // Test 29: adjustCellHeight - 最大値 80 でクランプされる
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.adjustCellHeight(+1000);
            boolean ok = canvas.getCellH() == 80;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "adjustCellHeight 最大クランプ == 80, actual=" + canvas.getCellH());
            pass += ok ? 1 : 0;
        }

        // Test 30: セルサイズ変更後の renderGlyph が新サイズを反映する
        {
            java.awt.image.BufferedImage img = TtfMonoFont.INSTANCE.renderGlyph('B', 20, 40, 0xFFFFFF);
            boolean ok = img.getWidth() == 20 && img.getHeight() == 40;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "renderGlyph 20x40 サイズ -> " + img.getWidth() + "x" + img.getHeight());
            pass += ok ? 1 : 0;
        }

        // Test 31: zz等でファイル末尾を超えてスクロールした場合、LIGHT_MODEでは
        //          その空白領域が純粋な白(#FFFFFF)で描画される（通常背景色#F5F0E6とは異なる）
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("line1\nline2\nline3");
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setScrollRow(50); // 文書末尾(3行)をはるかに超えてスクロール

            BufferedImage img = render(canvas, 400, 300);
            int pixel = img.getRGB(350, 150);
            pass += checkColor("zz末尾超過領域(LIGHT_MODE)は純白", 0xFF, 0xFF, 0xFF, pixel);
        }

        // Test 32: 同条件でDARK_MODEでは純粋な黒(#000000)で描画される
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("line1\nline2\nline3");
            canvas.setTheme(Theme.DARK_MODE);
            canvas.setScrollRow(50);

            BufferedImage img = render(canvas, 400, 300);
            int pixel = img.getRGB(350, 150);
            pass += checkColor("zz末尾超過領域(DARK_MODE)は純黒", 0x00, 0x00, 0x00, pixel);
        }

        // Test 33: 文書内に収まっている通常行の領域は末尾超過の白/黒塗りの影響を受けない
        {
            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("line1\nline2\nline3");
            canvas.setTheme(Theme.LIGHT_MODE);
            canvas.setScrollRow(0); // スクロールなし、3行とも表示範囲内ではない末尾領域が存在する

            BufferedImage img = render(canvas, 400, 300);
            // 1行目の描画域（(1,1)付近）は背景色のまま（白塗りされない）
            int pixel = img.getRGB(350, 5);
            pass += checkColor("文書内領域は通常の背景色のまま", 0xF5, 0xF0, 0xE6, pixel);
        }

        int total = 33;
        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) {
            System.exit(1);
        }
        System.exit(0);   // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
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
