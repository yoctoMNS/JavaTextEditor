package dev.javatexteditor.ui;

import dev.javatexteditor.editor.ModalEditor;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * :font / :fs のセルサイズ独立性バグ修正のエビデンス撮影用ツール（使い捨て、test.shには含まれない）。
 * ModalEditorへ実際のコマンド文字列を打鍵させ、EditorCanvasをヘッドレスでBufferedImageへ
 * レンダリングする（VisualPreview.javaと同じ方式）。ステータス行に実行したコマンドを表示する。
 */
public class FontCellSizeEvidencePreview {
    static final String SAMPLE =
        "public class OrderProcessor {\n" +
        "    private int total = 0;\n" +
        "    public void add(int price) {\n" +
        "        if (price > 0) {\n" +
        "            total += price;\n" +
        "        }\n" +
        "    }\n" +
        "}\n";

    public static void main(String[] args) throws Exception {
        // 1. :font 0 (MiscFixed) を既定サイズ(:fs 0, 9x15)で表示
        shot("01_miscfixed_default.png", ":font 0  :fs 0", (ed, canvas) -> {
            sendCommand(ed, "font 0");
            sendCommand(ed, "fs 0");
        });

        // 2. :font 1 (Plex Mono) に切り替えた直後、Ctrl+Shift+矢印を使わず本来のデフォルトサイズで表示
        shot("02_plexmono_default.png", ":font 1 (直後、リサイズ一切なし)", (ed, canvas) -> {
            sendCommand(ed, "font 1");
        });

        // 3a/3b/3c. Plex Mono選択中に :fs 0〜9 の最小・中間・最大
        shot("03_plexmono_fs0.png", ":font 1  :fs 0", (ed, canvas) -> {
            sendCommand(ed, "font 1");
            sendCommand(ed, "fs 0");
        });
        shot("03_plexmono_fs5.png", ":font 1  :fs 5", (ed, canvas) -> {
            sendCommand(ed, "font 1");
            sendCommand(ed, "fs 5");
        });
        shot("03_plexmono_fs9.png", ":font 1  :fs 9", (ed, canvas) -> {
            sendCommand(ed, "font 1");
            sendCommand(ed, "fs 9");
        });

        // 4a. Plex Mono選択中にCtrl+Shift+矢印相当でサイズ変更後、MiscFixedへ戻しても9x15のまま
        shot("04_crossfont_independence_plex_to_misc.png",
            ":font 1 + resize(28x60)  ->  :font 0", (ed, canvas) -> {
            sendCommand(ed, "font 1");
            canvas.setInitialCellSize(28, 60); // Ctrl+Shift+矢印相当の直接リサイズ
            sendCommand(ed, "font 0");
        });

        // 4b. 逆方向: MiscFixedを変更後、Plex Monoへ切り替えてもPlex Mono側は既定(7x15)のまま
        shot("04_crossfont_independence_misc_to_plex.png",
            ":font 0 + resize(45x75)  ->  :font 1", (ed, canvas) -> {
            sendCommand(ed, "font 0");
            canvas.setInitialCellSize(45, 75); // Ctrl+Shift+矢印相当の直接リサイズ
            sendCommand(ed, "font 1");
        });

        System.out.println("Done. See build/ for PNG files.");
    }

    interface Setup {
        void apply(ModalEditor ed, EditorCanvas canvas);
    }

    static void shot(String filename, String commandLabel, Setup setup) throws Exception {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setSize(900, 500);
        ModalEditor ed = new ModalEditor(SAMPLE, "Sample.java", canvas);
        setup.apply(ed, canvas);
        // 実行したコマンドが見えるよう、コマンドラインテキストとしてステータス行下に表示する。
        canvas.setCommandLineText(":" + commandLabel);

        BufferedImage img = new BufferedImage(900, 500, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        canvas.paint(g2);
        g2.dispose();

        File out = new File("build/" + filename);
        ImageIO.write(img, "PNG", out);
        System.out.println("Saved: " + out.getAbsolutePath()
            + "  (cellW=" + canvas.getCellW() + " cellH=" + canvas.getCellH() + ")");
    }

    static void sendCommand(ModalEditor editor, String cmd) {
        editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        editor.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : cmd.toCharArray()) {
            editor.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        }
        editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
    }
}
