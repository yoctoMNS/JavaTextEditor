package dev.javatexteditor.ui;

import java.awt.Color;

/**
 * テーマごとの配色定義。
 * LIGHT_MODEは純粋な黒(#000000)・純粋な白(#FFFFFF)を使わない
 * （コントラストが強すぎると目が疲れやすいため、わずかに調整した色を使う）。
 * DARK_MODEの背景は2026-07のユーザー要望により純黒(#000000)に変更済み。
 *
 * syntaxKeyword はキーワード（if/static/return等）に加え、void/int/char/unsigned/bool
 * 等の基本型・ALL_CAPS識別子（マクロ・定数）も含む色。DARK_MODEでは純白(#FFFFFF)にして
 * 強調している。syntaxType はPascalCaseのクラス名（JDK API/自作プロジェクトのクラス）
 * 専用の色で、DARK_MODEでは明るい水色にしている（基本型・定数とは意図的に区別する）。
 * syntaxSymbol は括弧・カンマ・セミコロン等の区切り記号、syntaxOperator は算術/比較/
 * 代入等の演算子の色。
 */
public enum Theme {
    LIGHT_MODE(
        new Color(0xF5, 0xF0, 0xE6),  // ベージュ背景
        new Color(0x33, 0x33, 0x33),  // 薄い黒文字
        new Color(0x99, 0x99, 0x99),  // ステータス行区切り等に使う中間色
        new Color(0x33, 0x33, 0x33),  // キーワード（foregroundと同色）
        new Color(0x26, 0x7F, 0x99),  // 型名（ティール系）
        new Color(0xA3, 0x15, 0x15),  // 文字列（暗い赤）
        new Color(0x3F, 0x7F, 0x5F),  // コメント（緑）
        new Color(0x17, 0x50, 0xEB),  // 数値（青）
        new Color(0xAF, 0x00, 0xDB),  // プリプロセッサ/マクロ（紫）
        new Color(0x2E, 0x8B, 0x57),  // 記号（括弧・カンマ・セミコロン等、緑）
        new Color(0x00, 0x6E, 0x8A)   // 演算子（暗めの水色）
    ),
    DARK_MODE(
        new Color(0x00, 0x00, 0x00),  // 純黒背景
        new Color(0xB8, 0xB8, 0xB8),  // 通常の文字（少し明るい灰色）
        new Color(0x66, 0x66, 0x66),
        new Color(0xFF, 0xFF, 0xFF),  // キーワード・基本型（純白）
        new Color(0x7F, 0xE0, 0xFF),  // 型名（Java API/自作クラスのみ・明るい水色）
        new Color(0xC7, 0x5C, 0x8A),  // 文字列（暗いピンク）
        new Color(0xB0, 0x50, 0x50),  // コメント（赤系）
        new Color(0x9A, 0x7E, 0xD6),  // 数値（紫）
        new Color(0xC0, 0x60, 0xC8),  // プリプロセッサ/マクロ（マゼンタ）
        new Color(0x6A, 0xE6, 0x6A),  // 記号（括弧・カンマ・セミコロン等、明るい緑）
        new Color(0x3F, 0x9B, 0xB0)   // 演算子（少し暗い水色。型名の明るい水色より暗くする）
    );

    public final Color background;
    public final Color foreground;
    public final Color accent;
    public final Color syntaxKeyword;
    public final Color syntaxType;
    public final Color syntaxString;
    public final Color syntaxComment;
    public final Color syntaxNumber;
    public final Color syntaxPreprocessor;
    public final Color syntaxSymbol;
    public final Color syntaxOperator;

    Theme(Color background, Color foreground, Color accent,
          Color syntaxKeyword, Color syntaxType, Color syntaxString,
          Color syntaxComment, Color syntaxNumber, Color syntaxPreprocessor,
          Color syntaxSymbol, Color syntaxOperator) {
        this.background = background;
        this.foreground = foreground;
        this.accent = accent;
        this.syntaxKeyword = syntaxKeyword;
        this.syntaxType = syntaxType;
        this.syntaxString = syntaxString;
        this.syntaxComment = syntaxComment;
        this.syntaxNumber = syntaxNumber;
        this.syntaxPreprocessor = syntaxPreprocessor;
        this.syntaxSymbol = syntaxSymbol;
        this.syntaxOperator = syntaxOperator;
    }
}
