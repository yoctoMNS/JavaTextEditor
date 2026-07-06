package dev.javatexteditor.ui;

import java.awt.image.BufferedImage;

/**
 * BitmapFont5x7 ~ BitmapFont10x20 共通のグリフ描画ロジック。
 * 1グリフは baseH 行 × bytesPerRow バイトで、各行は MSBit=左端ピクセルの
 * bytesPerRow*8 ビット幅のビットマスク（右側の余りビットは常に0）として格納されている。
 * BitmapFont10x20 の renderGlyph() にあった実装をフォント幅に依存しない形に一般化したもの。
 */
final class FixedFontRenderer {

    static BufferedImage renderGlyph(byte[] glyphs, int baseW, int baseH, int bytesPerRow,
                                      int firstChar, int lastChar,
                                      int codePoint, int cellW, int cellH, int fgRgb) {
        if (codePoint < firstChar || codePoint > lastChar) codePoint = '?';
        int glyphBytes = bytesPerRow * baseH;
        int base = (codePoint - firstChar) * glyphBytes;
        BufferedImage img = new BufferedImage(cellW, cellH, BufferedImage.TYPE_INT_ARGB);
        int opaqueRgb = fgRgb | 0xFF000000;
        int totalBits = bytesPerRow * 8;
        for (int row = 0; row < cellH; row++) {
            int srcRow = row * baseH / cellH;
            int offset = base + srcRow * bytesPerRow;
            int combined = 0;
            for (int k = 0; k < bytesPerRow; k++) {
                combined = (combined << 8) | (glyphs[offset + k] & 0xFF);
            }
            for (int col = 0; col < cellW; col++) {
                int srcCol = col * baseW / cellW;
                if (((combined >> (totalBits - 1 - srcCol)) & 1) == 1) {
                    img.setRGB(col, row, opaqueRgb);
                }
            }
        }
        return img;
    }

    private FixedFontRenderer() {}
}
