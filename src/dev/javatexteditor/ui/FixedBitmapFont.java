package dev.javatexteditor.ui;

import java.awt.image.BufferedImage;

/**
 * MiscFixed 系ビットマップフォント（BitmapFont5x7 ~ BitmapFont10x20）の共通インタフェース。
 * FixedFontCatalog がセルサイズの横縦比率に最も近いフォントを選ぶために使う。
 * 各実装クラスは同名の static メソッド（renderGlyph/isSupported/descentPixels）も
 * 従来通り公開し続け、既存の直接呼び出し（EditorCanvasTest 等）との互換性を保つ。
 */
public interface FixedBitmapFont {
    int cellW();
    int cellH();
    BufferedImage renderGlyphI(int codePoint, int cellW, int cellH, int fgRgb);
    boolean isSupportedI(int codePoint);
    int descentPixelsI(int cellH);
}
