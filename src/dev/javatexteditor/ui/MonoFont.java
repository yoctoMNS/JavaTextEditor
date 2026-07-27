package dev.javatexteditor.ui;

import java.awt.image.BufferedImage;

/**
 * 半角ASCII(0x20-0x7E)を描画するフォント実装の共通契約。
 * {@code MiscFixedBold9x15}（ビットマップ・ニアレストネイバー拡縮）と
 * {@code IbmPlexMonoFont}（TTFベクター・アンチエイリアス拡縮）の両方がこれを実装し、
 * EditorCanvas が :font コマンドで実行時に切り替えられるようにする。
 */
public interface MonoFont {

    /** ASCII 範囲内（描画対象）かどうかを返す。 */
    boolean isSupported(int codePoint);

    /** セル高さ cellH における、セル底辺からベースラインまでの距離（px）。 */
    int descentPixels(int cellH);

    /** codePoint のグリフを cellW×cellH の BufferedImage に描画して返す。 */
    BufferedImage renderGlyph(int codePoint, int cellW, int cellH, int fgRgb);
}
