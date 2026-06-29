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
     * @param scale       拡大率（スプライト 1px → canvas scale px）
     */
    public static int calcX(double elapsedSec, int canvasWidth, int scale) {
        int spriteW = PERSON_W * scale;
        int totalW  = canvasWidth + spriteW;
        return (int)(elapsedSec * WALK_SPEED * scale) % totalW - spriteW;
    }

    /**
     * 指定フレームのスプライトを Graphics2D に描画する。
     *
     * @param g2        描画先（呼び出し元は必要に応じて g2.create() で切り出すこと）
     * @param frame     フレーム番号（0 or 1）
     * @param x         スプライト左端の X 座標
     * @param y         スプライト上端の Y 座標
     * @param scale     拡大率
     * @param baseColor '#' ピクセルに使う色（テーマの前景色または背景色を推奨）
     */
    public static void drawFrame(Graphics2D g2, int frame, int x, int y,
                                  int scale, Color baseColor) {
        int frameOffset = frame * PERSON_W;
        int br = baseColor.getRed();
        int bg = baseColor.getGreen();
        int bb = baseColor.getBlue();

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
                g2.setColor(c);
                g2.fillRect(x + col * scale, y + row * scale, scale, scale);
            }
        }
    }

    private WalkingPersonSprite() {}
}
