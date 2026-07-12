package dev.javatexteditor.ui;

import java.awt.Color;
import java.awt.Graphics2D;

/**
 * bisqwit/that_terminal の persondata を移植した 2 フレーム歩行アニメーションスプライト。
 *
 * スプライトシートは 16×16 ピクセル × 2 フレーム（左 16 列 = frame 0、右 16 列 = frame 1）。
 * キャラクターの外見に関する固有名詞は使用せず "Person" / "Character" などの
 * 抽象的な命名規則に従う。
 *
 * ピクセル文字の意味:
 *   '#' → 不透明（前景色そのまま）
 *   ''' → 半透明シェーディング（前景色 約 70% alpha）
 *   '.' → 薄いシェーディング（前景色 約 40% alpha）
 *   ' ' → 透明（描画スキップ）
 */
public final class WalkingPersonSprite {

    // that_terminal rendering/person.cc の persondata をそのまま移植
    // 各行 32 文字: 左 16 = フレーム 0、右 16 = フレーム 1
    private static final String[] PERSON_DATA = {
        "                      #####     ",
        "      ######         #'''''###  ",
        "     #''''''##      #'''''''''# ",
        "    #'''''''''#     ###'.#.###  ",
        "    ###..#.###     #..##.#....# ",
        "   #..##.#....#    #..##..#...# ",
        "   #..##..#...#     ##...#####  ",
        "    ##...#####      ###.....#   ",
        "     ##.....#     ##'''##''###  ",
        "    #''##''#     #..''''##''#'# ",
        "   #''''##''#    #..'''######'.#",
        "   #''''#####     #..####.##.#.#",
        "    #...##.##     .#########''# ",
        "    #..'''###     #''######'''# ",
        "     #'''''#      #'''#  #'''#  ",
        "      #####        ###    ###   ",
    };

    /** 1 フレームあたりのピクセル幅 */
    public static final int PERSON_W = 16;
    /** スプライトのピクセル高さ */
    public static final int PERSON_H = 16;
    /** フレーム切り替え速度（フレーム/秒） */
    public static final double FRAME_RATE  = 6.0;
    /** 水平移動速度（スプライトピクセル/秒） */
    public static final double WALK_SPEED  = 64.0;

    /**
     * 経過秒数からアニメーションフレーム番号（0 or 1）を計算する。
     */
    public static int calcFrame(double elapsedSec) {
        return (int)(elapsedSec * FRAME_RATE) % 2;
    }

    /**
     * 経過秒数とキャンバス幅からスプライトの X 座標（キャンバスピクセル基準）を計算する。
     * 戻り値は [-spriteW, canvasWidth) の範囲でループし、左から右へ走り抜ける。
     *
     * @param elapsedSec  経過秒数
     * @param canvasWidth キャンバス幅（ピクセル）
     * @param scale       拡大率（スプライト 1px → canvas scale px）。ステータス行の文字高さと
     *                    常に一致させるため小数の拡大率を受け付ける（{@link #heightScale(int)}参照）。
     */
    public static int calcX(double elapsedSec, int canvasWidth, double scale) {
        int spriteW = (int) Math.round(PERSON_W * scale);
        int totalW  = canvasWidth + spriteW;
        return (int)(elapsedSec * WALK_SPEED * scale) % totalW - spriteW;
    }

    /**
     * スプライトの高さ（{@link #PERSON_H}px）を targetHeight px にちょうど一致させるための
     * 拡大率を返す。整数除算（{@code lineHeight / PERSON_H}）による切り捨てだと、
     * フォントサイズを変更（Ctrl+Shift+矢印）してもスプライトの高さが飛び飛びにしか
     * 変わらず、文字の高さ（cellH）と常に揃わなくなる問題があったため、小数の拡大率を
     * 返すようにした。呼び出し側は {@code Math.round(PERSON_H * scale)} が targetHeight と
     * 一致することを前提にできる。
     */
    public static double heightScale(int targetHeight) {
        return Math.max(0.0625, (double) targetHeight / PERSON_H);
    }

    /**
     * 指定フレームのスプライトを Graphics2D に描画する。
     *
     * @param g2        描画先（呼び出し元は必要に応じて g2.create() で切り出すこと）
     * @param frame     フレーム番号（0 or 1）
     * @param x         スプライト左端の X 座標
     * @param y         スプライト上端の Y 座標
     * @param scale     拡大率（小数可。フォントの文字高さと常に一致させるために使う）
     * @param baseColor '#' ピクセルに使う色（テーマの前景色または背景色を推奨）
     */
    public static void drawFrame(Graphics2D g2, int frame, int x, int y,
                                  double scale, Color baseColor) {
        int frameOffset = frame * PERSON_W;
        int br = baseColor.getRed();
        int bg = baseColor.getGreen();
        int bb = baseColor.getBlue();

        Graphics2D scaled = (Graphics2D) g2.create();
        try {
            scaled.translate(x, y);
            scaled.scale(scale, scale);
            for (int row = 0; row < PERSON_H; row++) {
                String rowData = PERSON_DATA[row];
                for (int col = 0; col < PERSON_W; col++) {
                    int dataIdx = frameOffset + col;
                    if (dataIdx >= rowData.length()) continue;
                    char ch = rowData.charAt(dataIdx);
                    if (ch == ' ') continue;

                    Color c = switch (ch) {
                        case '#'  -> baseColor;
                        case '\'' -> new Color(br, bg, bb, 178); // ~70% alpha
                        case '.'  -> new Color(br, bg, bb, 102); // ~40% alpha
                        default   -> baseColor;
                    };
                    scaled.setColor(c);
                    scaled.fillRect(col, row, 1, 1);
                }
            }
        } finally {
            scaled.dispose();
        }
    }

    private WalkingPersonSprite() {}
}
