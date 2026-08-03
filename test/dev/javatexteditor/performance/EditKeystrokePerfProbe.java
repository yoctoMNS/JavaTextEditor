package dev.javatexteditor.performance;

import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;

/**
 * 調査用の使い捨て計測ハーネス（VisualPreview.java と同種。ファイル名を意図的に
 * *Test.java にしていないため scripts/test.sh の自動実行対象外）。
 * 約5万文字のJavaソースファイルを開いた状態で1文字入力してから画面再描画完了までを
 * System.nanoTime() で計測する。1キー入力(processKey/syncCanvas)と実際の
 * paintComponent(repaint)のどちらが支配的かを切り分ける目的で作成した。
 *
 * <p>2026-08 の調査でこの計測により判明した内訳: 支配的だったのは
 * refreshCanvasTextCache(buffer.getText()+split、O(n)だが1回あたりは軽い)でも
 * paintContent(既にビューポート分のみ描画でO(可視行数)、文書サイズに非依存)でもなく、
 * EditorCanvas.setText() 内の SyntaxHighlighter.computeBlockCommentStarts()
 * （構文ハイライトのブロックコメント状態を文書全体についてtokenizeLine()し直す処理）
 * だった。1181行の文書で2〜10ms、可視行数に関わらず1キー入力毎に走っていた。
 * SyntaxHighlighter.computeBlockCommentStartsIncremental() の追加によりこの内訳は
 * 数十〜数百usまで縮小した（詳細は同メソッドのJavadocおよび gui-rendering-pipeline
 * スキルの該当節を参照）。
 */
public class EditKeystrokePerfProbe {
    public static void main(String[] args) {
        String bigJavaSource = buildJavaSource(50_000);
        System.out.println("=== document length=" + bigJavaSource.length() + " chars ===");

        EditorCanvas canvas = new EditorCanvas();
        canvas.setSize(1200, 800);
        ModalEditor ed = new ModalEditor(bigJavaSource, "PerfSample.java", canvas);

        // INSERTモードに入り、文書末尾付近にカーソルを置く（末尾行への1文字挿入を想定）
        press(ed, 'G');
        press(ed, 'i');

        BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_ARGB);

        // ウォームアップ1回（初回キャッシュ生成・グリフキャッシュ充填は計測から除外）
        press(ed, 'x');
        renderOnce(canvas, image);

        System.out.println("--- 計測開始（5回分の1文字入力→再描画） ---");
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            press(ed, 'y');
            long t1 = System.nanoTime();
            renderOnce(canvas, image);
            long t2 = System.nanoTime();
            System.out.println("[PERF] keystroke#" + i
                + " processKey(syncCanvas含む)=" + (t1 - t0) / 1000 + "us"
                + " paint(repaint実体)=" + (t2 - t1) / 1000 + "us"
                + " total=" + (t2 - t0) / 1000 + "us");
        }
        // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
        // （EditorRenderPerfTest.java と同じ理由）。
        System.exit(0);
    }

    private static void renderOnce(EditorCanvas canvas, BufferedImage image) {
        Graphics2D g2 = image.createGraphics();
        canvas.paint(g2);
        g2.dispose();
    }

    private static String buildJavaSource(int targetChars) {
        StringBuilder sb = new StringBuilder(targetChars + 1000);
        sb.append("package dev.javatexteditor.perf;\n\n");
        sb.append("public class PerfSample {\n");
        int i = 0;
        while (sb.length() < targetChars) {
            sb.append("    // comment line ").append(i).append(" describing field below\n");
            sb.append("    private int field").append(i).append(" = ").append(i).append(";\n");
            sb.append("    public int getField").append(i).append("() { return field").append(i).append("; }\n");
            i++;
        }
        sb.append("}\n");
        return sb.toString();
    }

    static void press(ModalEditor ed, char c) {
        ed.processKey(KeyEvent.getExtendedKeyCodeForChar(c), c, 0);
    }
}
