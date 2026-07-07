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
 * IBM Plex Mono Regular (TTF, SIL OFL 1.1) を使って半角ASCII (0x20-0x7E) を
 * 描画するフォント。misc-fixed 系ビットマップフォント（BitmapFont5x7〜10x20・
 * FixedBitmapFont・FixedFontCatalog）を置き換えるもので、事前生成した固定サイズの
 * ビットマップ集合を持たず、任意の cellW×cellH に対してベクターアウトラインを
 * 非等方向（縦横別々）にスケールしてラスタライズする。これにより
 * Ctrl+Shift+矢印 でセル幅・高さを個別に伸縮する既存の可変仕様（横縦比率が
 * ネイティブ比率からずれても破綻しない）を、複数の固定サイズを切り替える仕組み
 * 無しにそのまま満たす。
 *
 * TTF実体は lib/fonts/IBMPlexMono-Regular.ttf に配置される（scripts/setup.sh が
 * ダウンロードする外部リソース。lib/ は .gitignore 対象のためリポジトリには
 * 含まれない）。見つからない場合は Font.MONOSPACED にフォールバックする。
 */
public final class TtfMonoFont {

    public static final int BASE_CELL_W = 10;
    public static final int BASE_CELL_H = 20;
    public static final int FIRST_CHAR  = 0x20;
    public static final int LAST_CHAR   = 0x7E;

    public static final TtfMonoFont INSTANCE = new TtfMonoFont();

    // 参照サイズでレンダリングして得たフォント固有の縦横比率を、実際のセルサイズへの
    // 非等方向スケール係数の算出に使う。参照サイズ自体の絶対値に意味はない。
    private static final float REF_SIZE = 100f;

    private final Font refFont;
    private final int refAscent;
    private final int refDescent;
    private final int refAdvance;
    private final int refCellH;

    private TtfMonoFont() {
        Font base = loadFont();
        this.refFont = base.deriveFont(REF_SIZE);
        FontMetrics fm = referenceMetrics(refFont);
        this.refAscent  = fm.getAscent();
        this.refDescent = fm.getDescent();
        this.refAdvance = fm.charWidth('M');
        this.refCellH   = refAscent + refDescent;
    }

    private static FontMetrics referenceMetrics(Font font) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = probe.createGraphics();
        try {
            return g2.getFontMetrics(font);
        } finally {
            g2.dispose();
        }
    }

    private static Font loadFont() {
        try {
            Path ttf = findTtf();
            if (ttf != null) {
                try (InputStream in = Files.newInputStream(ttf)) {
                    return Font.createFont(Font.TRUETYPE_FONT, in);
                }
            }
        } catch (Exception ignored) {
            // フォールバックへ
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, 12);
    }

    private static Path findTtf() throws IOException {
        var found = CodeSourceLocator.findUpward(
            TtfMonoFont.class, "lib/fonts/IBMPlexMono-Regular.ttf", 4, Files::exists);
        if (found.isPresent()) return found.get();
        Path fromCwd = Path.of("lib", "fonts", "IBMPlexMono-Regular.ttf");
        if (Files.exists(fromCwd)) return fromCwd.toAbsolutePath();
        return null;
    }

    /** ASCII 範囲内かどうかを返す。 */
    public boolean isSupported(int codePoint) {
        return codePoint >= FIRST_CHAR && codePoint <= LAST_CHAR;
    }

    /**
     * セル高さ cellH における、セル底辺からベースラインまでの距離（px）。
     * Swing フォールバックフォント（全角文字等）の drawString 呼び出し時に、
     * この値だけ y 座標を上げることで ASCII と非ASCII のベースラインを揃える。
     */
    public int descentPixels(int cellH) {
        return Math.round(cellH * (float) refDescent / refCellH);
    }

    /**
     * codePoint のグリフを cellW×cellH の BufferedImage に描画して返す。
     * アウトラインを縦横別々のスケール（sx=cellW/参照アドバンス幅、
     * sy=cellH/参照アセント+ディセント）で変換してから描画するため、
     * cellW/cellH の比率がフォント本来の比率からずれても
     * セル全体を過不足なく埋める（misc-fixed 版の独立軸ニアレストネイバー
     * 拡縮と同じ「セルに合わせて歪める」挙動を、アンチエイリアス付きの
     * ベクター描画で実現したもの）。
     */
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
