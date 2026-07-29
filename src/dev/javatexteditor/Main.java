package dev.javatexteditor;

import dev.javatexteditor.app.EditorApplication;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * エントリポイント。組み立て（起動引数の解析・サービス生成・GUI構築）は
 * すべて {@link EditorApplication} が担う（MAIN_DECOMPOSITION_PLAN.md 段階7、
 * docs/STAGE7_PLAN.md 7-2）。
 */
public class Main {

    /** 画像プレビュー対応の拡張子ホワイトリスト（A1、image-preview-spec-2026-07-29.md §1）。 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp");

    /**
     * path が画像ファイルとして開けるかどうかを判定する。まず拡張子ホワイトリストで事前フィルタし
     * （A1）、通過したものだけ実際に {@link ImageIO#read} を試して成否で最終判定する（A3）。
     * マジックナンバー確認は行わない（仕様確定済み、docs/archive/image-preview-spec-2026-07-29.md）。
     */
    public static boolean isImageFile(Path path) {
        if (path == null) return false;
        String name = path.getFileName() != null ? path.getFileName().toString() : "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!IMAGE_EXTENSIONS.contains(ext)) return false;
        try {
            return ImageIO.read(path.toFile()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static void main(String[] args) {
        EditorApplication.launch(args);
    }
}
