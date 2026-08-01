package dev.javatexteditor.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 画像プレビューの描画・拡縮ロジックを {@link EditorCanvas} から切り出したヘルパー。
 * 画像ファイル（png/jpg/jpeg/gif/bmp）を開いた際の全画面プレビュー（C2方式、CardLayout不採用、
 * JScrollPane不採用）の実処理を担う。詳細な設計判断は
 * {@code .claude/skills/image-preview/SKILL.md} を参照。
 *
 * <p>拡縮の計算部分（{@link #computeFitSize}/{@link #computeZoomSize}/{@link #clampOffset}）は
 * Swing 非依存の純粋ロジックとして切り出してあり、テストハーネスから直接検証できる。
 */
public final class ImageRenderer {

    private ImageRenderer() {}

    public static final double MIN_ZOOM = 0.1;
    public static final double MAX_ZOOM = 10.0;
    public static final double ZOOM_STEP = 1.25;
    public static final double DEFAULT_ZOOM = 1.0;

    /** アスペクト比を保ったまま計算した描画サイズ（px）。 */
    public record FitSize(int width, int height) {}

    /**
     * アスペクト比を維持したままビューポートに収まる最大サイズを計算する（自動フィット）。
     * 縮小・拡大のどちらの方向にも対応する（B1、幅・高さそれぞれの倍率のうち小さい方を採用）。
     */
    public static FitSize computeFitSize(int imgW, int imgH, int viewportW, int viewportH) {
        if (imgW <= 0 || imgH <= 0 || viewportW <= 0 || viewportH <= 0) {
            return new FitSize(0, 0);
        }
        double scale = Math.min((double) viewportW / imgW, (double) viewportH / imgH);
        int w = Math.max(1, (int) Math.round(imgW * scale));
        int h = Math.max(1, (int) Math.round(imgH * scale));
        return new FitSize(w, h);
    }

    /** 手動ズーム倍率を適用したサイズを計算する。 */
    public static FitSize computeZoomSize(int imgW, int imgH, double zoom) {
        if (imgW <= 0 || imgH <= 0) return new FitSize(0, 0);
        int w = Math.max(1, (int) Math.round(imgW * zoom));
        int h = Math.max(1, (int) Math.round(imgH * zoom));
        return new FitSize(w, h);
    }

    /** `+` キー相当: ズームイン。MAX_ZOOM でクランプする。 */
    public static double zoomIn(double zoom) {
        return Math.min(MAX_ZOOM, zoom * ZOOM_STEP);
    }

    /** `-` キー相当: ズームアウト。MIN_ZOOM でクランプする。 */
    public static double zoomOut(double zoom) {
        return Math.max(MIN_ZOOM, zoom / ZOOM_STEP);
    }

    /**
     * パン用オフセット（px）を、画像がビューポートから完全にはみ出さないようクランプする。
     * contentSize が viewportSize 以下（画像がビューポートより小さい）の場合は常に 0 を返す
     * （その場合は中央寄せで描画されるため、パンの必要がない）。
     */
    public static int clampOffset(int offset, int contentSize, int viewportSize) {
        int maxOffset = Math.max(0, contentSize - viewportSize);
        return Math.max(0, Math.min(offset, maxOffset));
    }

    /**
     * 画像を計算済みサイズ・パンオフセットでビューポートへ描画する。
     * 自動フィット時（autoFit=true）は毎回 {@link #computeFitSize} を再計算する（A1、paintComponent毎）。
     * 手動ズーム時（autoFit=false）は zoom をそのまま使う。
     *
     * @param panXCells 横方向パンオフセット（セル単位。EditorCanvas の scrollCol をそのまま流用する）
     * @param panYCells 縦方向パンオフセット（セル単位。EditorCanvas の scrollRow をそのまま流用する）
     */
    public static void paint(Graphics2D g2, BufferedImage img, int viewportX, int viewportY,
            int viewportW, int viewportH, boolean autoFit, double zoom,
            int panXCells, int panYCells, int cellW, int cellH) {
        if (img == null || viewportW <= 0 || viewportH <= 0) return;

        FitSize size = autoFit
            ? computeFitSize(img.getWidth(), img.getHeight(), viewportW, viewportH)
            : computeZoomSize(img.getWidth(), img.getHeight(), zoom);
        if (size.width() <= 0 || size.height() <= 0) return;

        int panX = clampOffset(panXCells * Math.max(1, cellW), size.width(), viewportW);
        int panY = clampOffset(panYCells * Math.max(1, cellH), size.height(), viewportH);

        // 画像がビューポートより小さい方向は中央寄せ、大きい方向はパンオフセット分だけずらす。
        int drawX = viewportX + (size.width() <= viewportW ? (viewportW - size.width()) / 2 : -panX);
        int drawY = viewportY + (size.height() <= viewportH ? (viewportH - size.height()) / 2 : -panY);

        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(img, drawX, drawY, size.width(), size.height(), null);
    }

    /** 読み込み中インジケーターの表示に使う簡易文言。 */
    public static final String LOADING_TEXT = "Loading...";

    /** ビューポート中央に文言を描画する（読み込み中表示用の下請け）。 */
    public static void paintCenteredMessage(Graphics2D g2, String message, int viewportX, int viewportY,
            int viewportW, int viewportH, Color color) {
        g2.setColor(color);
        java.awt.FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(message);
        int x = viewportX + Math.max(0, (viewportW - tw) / 2);
        int y = viewportY + viewportH / 2;
        g2.drawString(message, x, y);
    }
}
