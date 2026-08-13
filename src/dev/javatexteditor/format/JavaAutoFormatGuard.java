package dev.javatexteditor.format;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 保存時のメンバー自動並び替え（{@link JavaMemberFormatter}）を発動してよいかどうかの
 * フェイルセーフ判定。ここでの判定はすべてパス・バッファ状態ベースの高速なもので、
 * AST由来の最終防衛線（java./javax./jdk./sun.パッケージ宣言の除外）は
 * {@link JavaMemberFormatter#format(String)} 側で二重に行っている。
 */
public final class JavaAutoFormatGuard {

    private JavaAutoFormatGuard() {}

    /**
     * @param targetPath        保存先の絶対パス
     * @param currentFilePath   エディタが認識している現在のバッファのファイルパス
     *                          （疑似バッファは {@code null} または {@code *...*} 形式）
     * @param inJdkSourceBuffer jdk-source疑似バッファ（Shift+Kのジャンプ先）内かどうか
     */
    public static boolean isEligible(Path targetPath, String currentFilePath, boolean inJdkSourceBuffer) {
        if (inJdkSourceBuffer) return false;
        if (currentFilePath == null || currentFilePath.startsWith("*")) return false;
        String lowerCurrent = currentFilePath.toLowerCase(Locale.ROOT);
        if (!lowerCurrent.endsWith(".java")) return false;
        if (targetPath == null) return false;

        String pathStr = targetPath.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (pathStr.contains("src.zip") || pathStr.contains("/openjdk-native/")) return false;

        if (isUnderJavaHome(targetPath)) return false;

        if (Files.exists(targetPath) && !Files.isWritable(targetPath)) return false;

        return true;
    }

    /** JDKインストールディレクトリ（{@code java.home}）配下のファイルを保険として除外する。 */
    private static boolean isUnderJavaHome(Path targetPath) {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) return false;
        try {
            Path home = Path.of(javaHome).toAbsolutePath().normalize();
            Path target = targetPath.toAbsolutePath().normalize();
            return target.startsWith(home);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
