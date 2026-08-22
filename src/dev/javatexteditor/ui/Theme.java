package dev.javatexteditor.ui;

import java.awt.Color;

/**
 * テーマごとの配色定義。:color コマンドで切り替える（詳細は ModalEditor.applyColorCommand()
 * 参照）。番号とenum定数の対応: :color 0 = DARK_MONO（既定）, :color 1 = BEIGE_MONO,
 * :color 2 = DARK_MODE, :color 3 = LIGHT_MODE。
 *
 * DARK_MONO/BEIGE_MONOは「予約語・クラス名」と「それ以外」の2色だけで構成する単色系テーマ
 * （2026-08 ユーザー要望により新規追加・既定テーマに設定）。DARK_MODE/LIGHT_MODEは
 * トークン種別ごとに色分けする従来の多色構文ハイライトテーマ。
 *
 * LIGHT_MODEは純粋な黒(#000000)・純粋な白(#FFFFFF)を使わない
 * （コントラストが強すぎると目が疲れやすいため、わずかに調整した色を使う）。
 * DARK_MODEの背景は2026-07のユーザー要望により純黒(#000000)に変更済み。
 * これら2テーマの配色は2026-08のDARK_MONO/BEIGE_MONO追加時にも変更していない
 * （DARK_MODEは「絶対変更しないでほしい」というユーザーの明示的な指示による。
 * LIGHT_MODEは2026-08にユーザー提供の配色へ全面差し替え済み）。
 *
 * syntaxKeyword はキーワード（if/static/return等）に加え、void/int/char/unsigned/bool
 * 等の基本型・ALL_CAPS識別子（マクロ・定数）も含む色。DARK_MODEでは純白(#FFFFFF)にして
 * 強調している。syntaxType はPascalCaseのクラス名（JDK API/自作プロジェクトのクラス）
 * 専用の色で、DARK_MODEでは薄い黄色にしている（基本型・定数とは意図的に区別する）。
 * syntaxSymbol は括弧・カンマ・セミコロン等の区切り記号、syntaxOperator は算術/比較/
 * 代入等の演算子の色。
 */
public enum Theme {
    DARK_MONO(
        false,
        new Color(0x00, 0x00, 0x00),  // 黒背景
        new Color(0xDB, 0xDB, 0xDB),  // 通常文字
        new Color(0x66, 0x66, 0x66),  // 選択ハイライト色（通常文字/キーワードのどちらとも被らない中間色）
        new Color(0xFF, 0xFF, 0xFF),  // キーワード（明るい白）
        new Color(0xFF, 0xFF, 0xFF),  // 型名/クラス名（明るい白）
        new Color(0xDB, 0xDB, 0xDB),  // 文字列
        new Color(0xDB, 0xDB, 0xDB),  // コメント
        new Color(0xDB, 0xDB, 0xDB),  // 数値
        new Color(0xDB, 0xDB, 0xDB),  // プリプロセッサ/マクロ
        new Color(0xDB, 0xDB, 0xDB),  // 記号
        new Color(0xDB, 0xDB, 0xDB)   // 演算子
    ),
    BEIGE_MONO(
        true,
        new Color(0xF5, 0xF0, 0xE6),  // ベージュ背景
        new Color(0x33, 0x33, 0x33),  // 通常文字（少し明るい黒）
        new Color(0x8A, 0x92, 0x9A),  // 選択ハイライト色（通常文字/キーワードのどちらとも被らない中間色）
        new Color(0x00, 0x00, 0x00),  // キーワード（黒）
        new Color(0x00, 0x00, 0x00),  // 型名/クラス名（黒）
        new Color(0x33, 0x33, 0x33),  // 文字列
        new Color(0x33, 0x33, 0x33),  // コメント
        new Color(0x33, 0x33, 0x33),  // 数値
        new Color(0x33, 0x33, 0x33),  // プリプロセッサ/マクロ
        new Color(0x33, 0x33, 0x33),  // 記号
        new Color(0x33, 0x33, 0x33)   // 演算子
    ),
    DARK_MODE(
        false,
        new Color(0x00, 0x00, 0x00),  // 純黒背景
        new Color(0xB8, 0xB8, 0xB8),  // 通常の文字（少し明るい灰色）
        new Color(0x66, 0x66, 0x66),
        new Color(0xFF, 0xFF, 0xFF),  // キーワード・基本型（純白）
        new Color(0xF0, 0xE6, 0x8C),  // 型名（Java API/自作クラスのみ・薄い黄色）
        new Color(0xC7, 0x5C, 0x8A),  // 文字列（暗いピンク）
        new Color(0xB0, 0x50, 0x50),  // コメント（赤系）
        new Color(0x9A, 0x7E, 0xD6),  // 数値（紫）
        new Color(0xC0, 0x60, 0xC8),  // プリプロセッサ/マクロ（マゼンタ）
        new Color(0x6A, 0xE6, 0x6A),  // 記号（括弧・カンマ・セミコロン等、明るい緑）
        new Color(0x3F, 0x9B, 0xB0)   // 演算子（少し暗い水色。型名の明るい水色より暗くする）
    ),
    LIGHT_MODE(
        true,
        new Color(0xF5, 0xF0, 0xE6),  // 背景: 温かみのあるウォームベージュ
        new Color(0x2D, 0x31, 0x42),  // 通常文字: 目に優しいダークチャコール（黒の眩しさを抑制）
        new Color(0x8A, 0x92, 0x9A),  // 区切り/中間色: ミュートグレー
        new Color(0x00, 0x52, 0xCC),  // キーワード: コバルトブルー（通常文字と完全分離）
        new Color(0x00, 0x7A, 0x87),  // 型名: ディープティール（青緑系）
        new Color(0xAD, 0x1A, 0x1A),  // 文字列: クリムゾンレッド（視認性の高い赤）
        new Color(0x65, 0x74, 0x6E),  // コメント: スレートグリーン（コードの邪魔をしない低彩度）
        new Color(0xB3, 0x59, 0x00),  // 数値: ダークアンバー/オレンジ（文字列・キーワードと干渉しない）
        new Color(0x7B, 0x1F, 0xA2),  // プリプロセッサ: ディープパープル
        new Color(0x55, 0x55, 0x55),  // 記号: ニュートラルダークグレー（構文ノイズの防止）
        new Color(0x9E, 0x2A, 0x2B)   // 演算子: ラスティレッド/暗いコラール（記号と差別化）
    );

    /** 背景が明るい系のテーマか（ファイル末尾を超えた領域の白/黒塗りの判定に使う）。 */
    public final boolean isLight;
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

    Theme(boolean isLight, Color background, Color foreground, Color accent,
          Color syntaxKeyword, Color syntaxType, Color syntaxString,
          Color syntaxComment, Color syntaxNumber, Color syntaxPreprocessor,
          Color syntaxSymbol, Color syntaxOperator) {
        this.isLight = isLight;
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
