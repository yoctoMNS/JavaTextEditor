package dev.javatexteditor.ui;

import dev.javatexteditor.analysis.CodeSourceLocator;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JetBrains Mono Regular (TTF, SIL OFL 1.1) を使って半角ASCII (0x20-0x7E) を
 * 描画するフォント。:font 2 で選択できる（既定は MiscFixedBold9x15、:font 0）。
 * 実装は {@link IbmPlexMonoFont} と同一の設計（事前生成した固定サイズのビットマップ集合を
 * 持たず、任意の cellW×cellH に対してベクターアウトラインを非等方向にスケールして
 * ラスタライズする）をそのまま踏襲している。
 *
 * TTF実体は lib/fonts/JetBrainsMono-Regular.ttf に配置される（scripts/setup.sh/setup.bat が
 * ダウンロードする外部リソース。lib/ は .gitignore 対象のためリポジトリには含まれない）。
 * 見つからない場合は Font.MONOSPACED にフォールバックする。
 *
 * <p>{@link #BASE_CELL_W}/{@link #BASE_CELL_H} は、IbmPlexMonoFont と同じ手順（REF_SIZE=100pt
 * でレンダリングした実測 FontMetrics から advance/(ascent+descent) 比率を求め、MiscFixedの
 * 既定高さ15pxに当てはめて幅を逆算）で求めた値。JetBrains Mono Regular の実測は
 * ascent=102・descent=30・advance('M')=60・比率≒0.4545 で、round(15*0.4545)=7 となり
 * IBM Plex Mono と同じ BASE_CELL_W=7 になった（偶然の一致で、独自に測定した値）。
 */
public final class JetBrainsMonoFont implements MonoFont {

    public static final int BASE_CELL_W = 7;
    public static final int BASE_CELL_H = 15;
    public static final int FIRST_CHAR  = 0x20;
    public static final int LAST_CHAR   = 0x7E;

    public static final JetBrainsMonoFont INSTANCE = new JetBrainsMonoFont();

    // 参照サイズでレンダリングして得たフォント固有の縦横比率を、実際のセルサイズへの
    // 非等方向スケール係数の算出に使う。参照サイズ自体の絶対値に意味はない。
    private static final float REF_SIZE = 100f;

    private final Font refFont;
    private final int refAscent;
    private final int refDescent;
    private final int refAdvance;
    private final int refCellH;
    private final boolean bundledFontLoaded;

    private JetBrainsMonoFont() {
        LoadResult lr = loadFontResult();
        this.bundledFontLoaded = lr.loaded();
        this.refFont = lr.font().deriveFont(REF_SIZE);
        FontMetrics fm = referenceMetrics(refFont);
        this.refAscent  = fm.getAscent();
        this.refDescent = fm.getDescent();
        this.refAdvance = fm.charWidth('M');
        this.refCellH   = refAscent + refDescent;
    }

    /**
     * lib/fonts/JetBrainsMono-Regular.ttf を実際に読み込めたかどうかを返す。
     * false の場合は Font.MONOSPACED へフォールバック済み（起動・描画自体は継続する）。
     * :font 2 実行時にステータスバーへ setup.sh/setup.bat の実行を促す表示を出すために使う
     * （ModalEditor.applyFontCommand参照）。
     */
    public boolean isBundledFontLoaded() { return bundledFontLoaded; }

    private static FontMetrics referenceMetrics(Font font) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = probe.createGraphics();
        try {
            return g2.getFontMetrics(font);
        } finally {
            g2.dispose();
        }
    }

    /** loadFont()の結果と、実際に同梱TTFを読み込めたか（false=Monospacedフォールバック）の組。 */
    private record LoadResult(Font font, boolean loaded) {}

    private static LoadResult loadFontResult() {
        try {
            Path ttf = findTtf();
            if (ttf != null) {
                try (InputStream in = Files.newInputStream(ttf)) {
                    return new LoadResult(Font.createFont(Font.TRUETYPE_FONT, in), true);
                }
            }
        } catch (Exception ignored) {
            // フォールバックへ
        }
        return new LoadResult(new Font(Font.MONOSPACED, Font.PLAIN, 12), false);
    }

    private static Path findTtf() throws IOException {
        var found = CodeSourceLocator.findUpward(
            JetBrainsMonoFont.class, "lib/fonts/JetBrainsMono-Regular.ttf", 4, Files::exists);
        if (found.isPresent()) return found.get();
        Path fromCwd = Path.of("lib", "fonts", "JetBrainsMono-Regular.ttf");
        if (Files.exists(fromCwd)) return fromCwd.toAbsolutePath();
        return null;
    }

    /** ASCII 範囲内かどうかを返す。 */
    @Override
    public boolean isSupported(int codePoint) {
        return codePoint >= FIRST_CHAR && codePoint <= LAST_CHAR;
    }

    /**
     * セル高さ cellH における、セル底辺からベースラインまでの距離（px）。
     * Swing フォールバックフォント（全角文字等）の drawString 呼び出し時に、
     * この値だけ y 座標を上げることで ASCII と非ASCII のベースラインを揃える。
     */
    @Override
    public int descentPixels(int cellH) {
        return Math.round(cellH * (float) refDescent / refCellH);
    }

    /**
     * codePoint のグリフを cellW×cellH の BufferedImage に描画して返す。
     * アウトラインを縦横別々のスケール（sx=cellW/参照アドバンス幅、
     * sy=cellH/参照アセント+ディセント）で変換してから描画するため、
     * cellW/cellH の比率がフォント本来の比率からずれても
     * セル全体を過不足なく埋める（IbmPlexMonoFontと同じ方式）。
     */
    @Override
    public BufferedImage renderGlyph(int codePoint, int cellW, int cellH, int fgRgb) {
        if (codePoint < FIRST_CHAR || codePoint > LAST_CHAR) codePoint = '?';
        BufferedImage img = new BufferedImage(cellW, cellH, BufferedImage.TYPE_INT_ARGB);
        if (codePoint == ' ') return img;

        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            double sx = (double) cellW / refAdvance;
            double sy = (double) cellH / refCellH;
            g2.setColor(new Color(fgRgb | 0xFF000000, true));
            g2.translate(0, refAscent * sy);
            g2.scale(sx, sy);
            g2.setFont(refFont);
            g2.drawString(String.valueOf((char) codePoint), 0, 0);
        } finally {
            g2.dispose();
        }
        return img;
    }
}
