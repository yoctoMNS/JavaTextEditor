package dev.javatexteditor.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 構文ハイライトの配色を目視確認するためのプレビュー生成ツール（VisualPreview.javaの
 * 構文ハイライト版）。*Test.javaという命名ではないため test.sh からは自動実行されない。
 * 構文ハイライトの色・分類変更を行うたびに、このクラスを実行してPNGを再生成し、
 * ユーザーへ確認のため提示すること（手順の詳細は本パッケージのSKILL.md
 * 「構文ハイライトの色・分類変更ワークフロー」節を参照）。
 *
 * サンプルコードはクラス・フィールド・メソッド・複雑な制御構文（for/if-else/switch式/
 * try-catch-finally/while）をすべて含むよう構成している。単純な1関数のサンプルだけでは
 * クラス名(TYPE)・定数(KEYWORD)・文字列(STRING)・記号(SYMBOL)・演算子(OPERATOR)等の
 * 分類が偏って見えず、配色の妥当性を判断しづらいため。
 */
public class SyntaxHighlightPreview {
    private static final String JAVA_SAMPLE = String.join("\n",
        "package dev.javatexteditor.sample;",
        "",
        "import java.util.List;",
        "import java.util.ArrayList;",
        "",
        "/**",
        " * サンプルクラス（構文ハイライトの目視確認用）",
        " */",
        "public class OrderProcessor {",
        "    private static final int MAX_RETRY = 3;",
        "    private final List<String> pendingOrders = new ArrayList<>();",
        "    private String status = \"idle\";",
        "",
        "    public boolean processAll(int[] ids) {",
        "        for (int i = 0; i < ids.length; i++) {",
        "            int id = ids[i];",
        "            if (id <= 0) {",
        "                continue;",
        "            } else if (id > MAX_RETRY * 100) {",
        "                status = \"skipped\";",
        "                break;",
        "            }",
        "            try {",
        "                switch (id % 3) {",
        "                    case 0 -> pendingOrders.add(\"order-\" + id);",
        "                    case 1 -> retry(id);",
        "                    default -> throw new IllegalStateException(\"bad id: \" + id);",
        "                }",
        "            } catch (IllegalStateException e) {",
        "                System.out.println(\"error: \" + e.getMessage());",
        "            } finally {",
        "                status = \"done\";",
        "            }",
        "        }",
        "        return !pendingOrders.isEmpty();",
        "    }",
        "",
        "    private void retry(int id) {",
        "        int attempts = 0;",
        "        while (attempts < MAX_RETRY) {",
        "            attempts++;",
        "        }",
        "    }",
        "}"
    );

    private static final String C_SAMPLE = String.join("\n",
        "// comment line",
        "#include <stdio.h>",
        "static void RenderSignal(unsigned* texture, unsigned stride, unsigned offset)",
        "{",
        "    /* block",
        "       comment */",
        "    int x = 0x1F + 42;",
        "    char c = 'A';",
        "    printf(\"hello %d\\n\", x);",
        "    bool ok = SDLK_LSHIFT;",
        "}"
    );

    public static void main(String[] args) throws Exception {
        render(Theme.DARK_MODE, SourceLanguage.JAVA, JAVA_SAMPLE, 980, 620, "preview_syntax_java_dark.png");
        render(Theme.LIGHT_MODE, SourceLanguage.JAVA, JAVA_SAMPLE, 980, 620, "preview_syntax_java_light.png");
        render(Theme.DARK_MODE, SourceLanguage.C, C_SAMPLE, 900, 300, "preview_syntax_c_dark.png");
        render(Theme.LIGHT_MODE, SourceLanguage.C, C_SAMPLE, 900, 300, "preview_syntax_c_light.png");
        render(Theme.DARK_MONO, SourceLanguage.JAVA, JAVA_SAMPLE, 980, 620, "preview_syntax_java_darkmono.png");
        render(Theme.BEIGE_MONO, SourceLanguage.JAVA, JAVA_SAMPLE, 980, 620, "preview_syntax_java_beigemono.png");

        System.out.println("✅ 構文ハイライトのプレビュー画像をbuild/に保存しました。");
        System.exit(0); // Swing Timer等の非daemonスレッドでJVMが終了しないため明示的に終了する
    }

    static void render(Theme theme, SourceLanguage language, String text,
                        int width, int height, String filename) throws Exception {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setSize(width, height);
        canvas.setTheme(theme);
        canvas.setLanguage(language);
        canvas.setText(text);

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        canvas.paint(g2);
        g2.dispose();

        File out = new File("build/" + filename);
        ImageIO.write(img, "PNG", out);
        System.out.println("Saved: " + out.getAbsolutePath());
    }
}
