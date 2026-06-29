package dev.javatexteditor.ui;

import dev.javatexteditor.editor.ModalEditor;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * ヤンク・ペースト・削除機能の動作を画面キャプチャで実演するデモ
 */
public class YankPasteDemo {
    public static void main(String[] args) throws Exception {
        System.out.println("=== ヤンク・ペースト・削除機能の動作実演 ===\n");

        // シナリオ1: VISUALモードでヤンク
        {
            System.out.println("【シナリオ1】VISUALモードで \"Hello\" をヤンク");
            EditorCanvas canvas = new EditorCanvas();
            ModalEditor editor = new ModalEditor("Hello, World!\nLine 2: test", canvas);

            System.out.println("  初期状態: テキスト=\"Hello, World!\"");
            System.out.println("           カーソル=(0, 0)");
            renderScreen(canvas, "yank_01_initial.png");

            editor.processKey(0, 'v', 0);
            System.out.println("  ✓ Step1: 'v' を押す → VISUALモード進入");
            renderScreen(canvas, "yank_02_visual_mode.png");

            for (int i = 0; i < 4; i++) {
                editor.processKey(0, 'l', 0);
            }
            System.out.println("  ✓ Step2: 'l'×4 で \"Hello\" を選択（カーソル=0,4）");
            renderScreen(canvas, "yank_03_hello_selected.png");

            editor.processKey(0, 'y', 0);
            System.out.println("  ✓ Step3: 'y' でヤンク実行");
            System.out.println("           yankRegister=\"" + editor.getYankRegister() + "\"");
            System.out.println("           モード: NORMAL（自動で戻った）");
            renderScreen(canvas, "yank_04_yanked.png");
        }

        System.out.println();

        // シナリオ2: 1文字削除（x）
        {
            System.out.println("【シナリオ2】1文字削除（x）");
            EditorCanvas canvas = new EditorCanvas();
            ModalEditor editor = new ModalEditor("abcde", canvas);

            System.out.println("  初期状態: テキスト=\"abcde\"");
            System.out.println("           カーソル=(0, 0) = 'a' 上");
            renderScreen(canvas, "delete_01_initial.png");

            editor.processKey(0, 'x', 0);
            System.out.println("  ✓ 'x' で 'a' を削除");
            System.out.println("    結果テキスト=\"bcde\"");
            System.out.println("    yankRegister=\"a\"（削除した文字が保存）");
            renderScreen(canvas, "delete_02_after_delete.png");
        }

        System.out.println();

        // シナリオ3: ペースト（p: 後ろ）
        {
            System.out.println("【シナリオ3】ペースト（p: カーソル後）");
            EditorCanvas canvas = new EditorCanvas();
            ModalEditor editor = new ModalEditor("abcde", canvas);

            System.out.println("  初期状態: テキスト=\"abcde\"");
            System.out.println("           カーソル=(0, 1) = 'b' 上");
            editor.processKey(0, 'l', 0);
            renderScreen(canvas, "paste_p_01_initial.png");

            editor.processKey(0, 'x', 0);  // 'b' を削除してyankRegister=\"b\"にセット
            System.out.println("  準備: 'x' で 'b' を削除 → yankRegister=\"b\"");
            renderScreen(canvas, "paste_p_02_deleted.png");

            editor.processKey(0, 'p', 0);
            System.out.println("  ✓ 'p' でカーソル後に \"b\" をペースト");
            System.out.println("    結果テキスト=\"acbde\"");
            renderScreen(canvas, "paste_p_03_pasted.png");
        }

        System.out.println();

        // シナリオ4: ペースト（P: 前）
        {
            System.out.println("【シナリオ4】ペースト（P: カーソル前）");
            EditorCanvas canvas = new EditorCanvas();
            ModalEditor editor = new ModalEditor("abcde", canvas);

            System.out.println("  初期状態: テキスト=\"abcde\"");
            System.out.println("           yankRegister=\"X\"（別のヤンク結果）");
            System.out.println("           カーソル=(0, 2) = 'c' 上");
            editor.processKey(0, 'l', 0);
            editor.processKey(0, 'l', 0);
            renderScreen(canvas, "paste_P_01_initial.png");

            editor.processKey(0, 'x', 0);  // 'c' を削除してyankRegister=\"c\"
            System.out.println("  準備: 'x' で 'c' を削除 → yankRegister=\"c\"");
            renderScreen(canvas, "paste_P_02_deleted.png");

            editor.processKey(0, 'P', 0);
            System.out.println("  ✓ 'P' でカーソル前に \"c\" をペースト");
            System.out.println("    結果テキスト=\"abcde\"（元に戻った）");
            renderScreen(canvas, "paste_P_03_pasted.png");
        }

        System.out.println("\n✅ 画面キャプチャを生成しました:");
        System.out.println("   yank_*.png     : VISUALモード → ヤンク");
        System.out.println("   delete_*.png   : 1文字削除（x）");
        System.out.println("   paste_p_*.png  : ペースト p（後ろ）");
        System.out.println("   paste_P_*.png  : ペースト P（前）");
    }

    static void renderScreen(EditorCanvas canvas, String filename) throws Exception {
        BufferedImage img = new BufferedImage(640, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        canvas.paint(g2);
        g2.dispose();

        File out = new File("build/" + filename);
        ImageIO.write(img, "PNG", out);
        System.out.println("    📸 Saved: " + filename);
    }
}
