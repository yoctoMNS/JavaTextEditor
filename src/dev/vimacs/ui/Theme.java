package dev.vimacs.ui;

import java.awt.Color;

/**
 * テーマごとの配色定義。
 * 純粋な黒(#000000)・純粋な白(#FFFFFF)を使わない理由:
 * コントラストが強すぎると目が疲れやすいため、わずかに調整した色を使う。
 */
public enum Theme {
    LIGHT_MODE(
        new Color(0xF5, 0xF0, 0xE6),  // ベージュ背景
        new Color(0x33, 0x33, 0x33),  // 薄い黒文字
        new Color(0x99, 0x99, 0x99)   // ステータス行区切り等に使う中間色
    ),
    DARK_MODE(
        new Color(0x1A, 0x1A, 0x1A),  // 黒背景（純黒より少し柔らかい）
        new Color(0xD4, 0xD4, 0xD4),  // 薄いグレー寄りの白文字
        new Color(0x66, 0x66, 0x66)
    );

    public final Color background;
    public final Color foreground;
    public final Color accent;

    Theme(Color background, Color foreground, Color accent) {
        this.background = background;
        this.foreground = foreground;
        this.accent = accent;
    }
}
