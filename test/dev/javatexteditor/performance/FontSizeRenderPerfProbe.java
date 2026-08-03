package dev.javatexteditor.performance;

import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.ui.EditorCanvas;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.event.KeyEvent;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * 調査用の使い捨て計測ハーネス（EditKeystrokePerfProbe.java と同種。ファイル名を意図的に
 * *Test.java にしていないため scripts/test.sh の自動実行対象外）。
 *
 * <p>「フォントサイズを大きくするとカーソル移動・スクロールの描画が滑らかでなくなる」
 * という症状について、文書サイズ・行数を固定したまま cellW/cellH（フォントセルサイズ）
 * のみを 12/24/48pt 相当（9x15 のスケール比に合わせて 9x15, 18x30, 36x60）に変えて
 * 以下3点を個別に System.nanoTime() で計測する。
 *
 * <p>a) EditorCanvas.xForCol(): 列番号→Xピクセル座標変換が列番号に比例して重くならないか
 *    （リフレクションで private static メソッドを直接叩き、paintの影響を除外する）
 * <p>b) 1文字あたりのグリフ描画コスト。ASCIIのみの文書と、日本語コメントを含む文書
 *    （本プロジェクト自身のソースが典型例）を比較する。ASCIIはビットマップフォントの
 *    キャッシュ経由、日本語はSwingフォントのdrawString直呼び（非キャッシュ）という
 *    実装上の違いがあるため、この2つを分けて計測しないとb)の支配要因を見誤る。
 * <p>c) カーソル移動・スクロール1回あたりの再描画で、実際に repaint 対象になる矩形が
 *    画面全体（引数なし repaint()）になっていないか（EditorCanvas.requestRepaint() の
 *    実装を確認する。ここでは呼び出し回数の観測はできないため、コード上の事実を
 *    コメントで記録し、a)b)の数値評価に専念する）。
 */
public class FontSizeRenderPerfProbe {

    private static final int[] FONT_SIZES_W = {9, 18, 36};
    private static final int[] FONT_SIZES_H = {15, 30, 60};

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));

        System.out.println("=== a) xForCol: 列番号に比例して重くならないか ===");
        measureXForCol();

        System.out.println();
        System.out.println("=== b-1) paintContent全体（可視セル数がフォントサイズで変動するため参考値） ===");
        measureGlyphPaintCost("ASCIIのみの文書", buildAsciiJavaSource(2000));
        measureGlyphPaintCost("日本語コメントを含む文書（本プロジェクト相当）", buildJapaneseJavaSource(2000));

        System.out.println();
        System.out.println("=== b-2) 1グリフあたりの純粋な描画コスト（可視セル数を固定して比較） ===");
        measureRawGlyphCost();

        System.out.println();
        System.out.println("=== c) カーソル移動時の repaint 呼び出し形態（コード上の事実） ===");
        System.out.println("EditorCanvas.requestRepaint() は常に引数なし repaint() を呼ぶ実装であり、"
            + "カーソル移動・スクロールの度に「画面全体（=ビューポート全体)」が再描画対象になる。"
            + "ただし paintContent 自体が既にビューポート限定描画のため、この「全体」は文書サイズに"
            + "依存しない。b)の計測結果と合わせて、repaint矩形の広さ自体よりも1文字あたりの描画コストが"
            + "支配的かどうかを判断する。");

        System.exit(0);
    }

    // ------------------------------------------------------------------
    // a) xForCol
    // ------------------------------------------------------------------
    private static void measureXForCol() throws Exception {
        Method xForCol = EditorCanvas.class.getDeclaredMethod("xForCol", String.class, int.class, int.class);
        xForCol.setAccessible(true);

        String longLine = "a".repeat(20_000);
        int[] cols = {10, 1000, 10_000, 19_999};

        for (int cw : new int[]{9, 18, 36}) {
            System.out.println("-- charWidth=" + cw + "px --");
            for (int col : cols) {
                // ウォームアップ
                for (int i = 0; i < 100; i++) xForCol.invoke(null, longLine, col, cw);

                long t0 = System.nanoTime();
                int iterations = 2000;
                for (int i = 0; i < iterations; i++) {
                    xForCol.invoke(null, longLine, col, cw);
                }
                long t1 = System.nanoTime();
                double perCallUs = (t1 - t0) / 1000.0 / iterations;
                System.out.println("  col=" + col + " -> " + String.format("%.3f", perCallUs) + "us/call");
            }
        }
        System.out.println("[判定] charWidth(フォントサイズ)を変えても同じcolに対する時間はほぼ一定、"
            + "colが大きいほど線形に増加していれば、xForColは「列番号に比例」であり"
            + "「フォントサイズに比例」ではないことが確認できる。");
    }

    // ------------------------------------------------------------------
    // b) グリフ描画コスト
    // ------------------------------------------------------------------
    private static void measureGlyphPaintCost(String label, String source) {
        System.out.println("-- " + label + " --");
        for (int i = 0; i < FONT_SIZES_W.length; i++) {
            int cw = FONT_SIZES_W[i];
            int ch = FONT_SIZES_H[i];

            EditorCanvas canvas = new EditorCanvas();
            canvas.setSize(1400, 900);
            canvas.setInitialCellSize(cw, ch);
            ModalEditor ed = new ModalEditor(source, "PerfSample.java", canvas);

            BufferedImage image = new BufferedImage(1400, 900, BufferedImage.TYPE_INT_ARGB);

            // ウォームアップ（グリフキャッシュ充填はここで済ませ、計測から除外する）
            renderOnce(canvas, image);
            renderOnce(canvas, image);

            int iterations = 30;
            long total = 0;
            for (int r = 0; r < iterations; r++) {
                // カーソルを動かして描画内容を毎回変える（キャッシュに対して不利にしない程度）
                press(ed, 'j');
                long t0 = System.nanoTime();
                renderOnce(canvas, image);
                long t1 = System.nanoTime();
                total += (t1 - t0);
            }
            double avgUs = total / 1000.0 / iterations;
            System.out.println("  cellW=" + cw + " cellH=" + ch
                + " -> paintContent平均 " + String.format("%.1f", avgUs) + "us/回 (" + iterations + "回平均)");
        }
    }

    /**
     * paintContent全体だと「フォントサイズが大きいほど画面に入る文字数が減る」効果と
     * 「1グリフあたりの描画コストが増える」効果が相殺しあって観測しづらいため、
     * 「固定個数のグリフをdrawString/drawImageで描画する」という条件を揃えた
     * マイクロベンチマークで1グリフあたりのコストだけを取り出す。
     */
    private static void measureRawGlyphCost() {
        int glyphCount = 2000; // フォントサイズによらず同じ枚数を描く
        BufferedImage image = new BufferedImage(2000, 2000, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
            java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
            java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        System.out.println("-- 非ASCII文字(日本語)を drawString で直接描画（本文の Swing フォールバック経路相当） --");
        for (int i = 0; i < FONT_SIZES_H.length; i++) {
            int ch = FONT_SIZES_H[i];
            Font f = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(8, (int) (ch * 0.75)));
            g2.setFont(f);
            // ウォームアップ
            for (int i2 = 0; i2 < 100; i2++) g2.drawString("あ", 10, 100);

            long t0 = System.nanoTime();
            for (int i2 = 0; i2 < glyphCount; i2++) {
                g2.drawString("あ", (i2 % 100) * 4, 100 + (i2 / 100));
            }
            long t1 = System.nanoTime();
            double perGlyphUs = (t1 - t0) / 1000.0 / glyphCount;
            System.out.println("  fontSize(px)=" + f.getSize()
                + " -> " + String.format("%.3f", perGlyphUs) + "us/文字 (" + glyphCount + "文字)");
        }

        System.out.println("-- ASCII文字をキャッシュ済みBufferedImageのdrawImageで描画（本文のビットマップ経路相当） --");
        for (int i = 0; i < FONT_SIZES_W.length; i++) {
            int cw = FONT_SIZES_W[i];
            int ch = FONT_SIZES_H[i];
            BufferedImage glyph = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
            // ウォームアップ
            for (int i2 = 0; i2 < 100; i2++) g2.drawImage(glyph, 10, 100, null);

            long t0 = System.nanoTime();
            for (int i2 = 0; i2 < glyphCount; i2++) {
                g2.drawImage(glyph, (i2 % 100) * cw, 100 + (i2 / 100), null);
            }
            long t1 = System.nanoTime();
            double perGlyphUs = (t1 - t0) / 1000.0 / glyphCount;
            System.out.println("  cellW=" + cw + " cellH=" + ch
                + " -> " + String.format("%.3f", perGlyphUs) + "us/文字 (" + glyphCount + "文字)");
        }
        g2.dispose();
    }

    private static void renderOnce(EditorCanvas canvas, BufferedImage image) {
        Graphics2D g2 = image.createGraphics();
        canvas.paint(g2);
        g2.dispose();
    }

    private static void press(ModalEditor ed, char c) {
        ed.processKey(KeyEvent.getExtendedKeyCodeForChar(c), c, 0);
    }

    private static String buildAsciiJavaSource(int lines) {
        StringBuilder sb = new StringBuilder(lines * 40);
        sb.append("package dev.javatexteditor.perf;\n\npublic class PerfSample {\n");
        for (int i = 0; i < lines; i++) {
            sb.append("    // comment line ").append(i).append(" describing field below\n");
            sb.append("    private int field").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String buildJapaneseJavaSource(int lines) {
        StringBuilder sb = new StringBuilder(lines * 60);
        sb.append("package dev.javatexteditor.perf;\n\npublic class PerfSample {\n");
        for (int i = 0; i < lines; i++) {
            sb.append("    // ").append(i).append("番目のフィールドを説明する日本語のコメント行です\n");
            sb.append("    private int field").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}
