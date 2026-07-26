package dev.javatexteditor.ui;

import java.awt.Color;

/**
 * テーマごとの配色定義。
 * 純粋な黒(#000000)・純粋な白(#FFFFFF)を使わない理由:
 * コントラストが強すぎると目が疲れやすいため、わずかに調整した色を使う。
 *
 * syntaxKeyword はキーワード（if/static/return等）の色。ダークモードの参考画像では
 * キーワードは通常の識別子と同じ地の色（本文フォントが元々Boldのため強調は不要）で
 * 描画されていたため、値は foreground と同じにしている（SyntaxKind自体は将来の
 * テーマ拡張のために区別して保持する）。
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
        new Color(0xAF, 0x00, 0xDB)   // プリプロセッサ/マクロ（紫）
    ),
    DARK_MODE(
        new Color(0x1A, 0x1A, 0x1A),  // 黒背景（純黒より少し柔らかい）
        new Color(0xD4, 0xD4, 0xD4),  // 薄いグレー寄りの白文字
        new Color(0x66, 0x66, 0x66),
        new Color(0xD4, 0xD4, 0xD4),  // キーワード（foregroundと同色）
        new Color(0x6E, 0xC0, 0xC8),  // 型名（シアン系）
        new Color(0xB5, 0xCE, 0x6B),  // 文字列（黄緑）
        new Color(0xB0, 0x50, 0x50),  // コメント（赤系）
        new Color(0x9A, 0x7E, 0xD6),  // 数値（紫）
        new Color(0xC0, 0x60, 0xC8)   // プリプロセッサ/マクロ（マゼンタ）
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

    Theme(Color background, Color foreground, Color accent,
          Color syntaxKeyword, Color syntaxType, Color syntaxString,
          Color syntaxComment, Color syntaxNumber, Color syntaxPreprocessor) {
        this.background = background;
        this.foreground = foreground;
        this.accent = accent;
        this.syntaxKeyword = syntaxKeyword;
        this.syntaxType = syntaxType;
        this.syntaxString = syntaxString;
        this.syntaxComment = syntaxComment;
        this.syntaxNumber = syntaxNumber;
        this.syntaxPreprocessor = syntaxPreprocessor;
    }
}
