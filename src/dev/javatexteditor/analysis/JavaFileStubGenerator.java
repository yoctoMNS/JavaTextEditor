package dev.javatexteditor.analysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.SourceVersion;

/**
 * 新規 .java ファイル作成時に自動挿入する package 文・クラス定義のひな形を生成する。
 * パッケージ名は、新規ファイルの位置から祖先ディレクトリを遡って「src」という名前の
 * ディレクトリを探索して決定する（Maven/Gradleの src/main/java 等、src と実際の
 * パッケージ階層の間にある慣例的な中間ディレクトリはパッケージ名から除外する）。
 * src が見つからない場合は（bin フォルダの有無に関わらず）package 文を挿入せず、
 * クラス定義のみ挿入する。
 */
public final class JavaFileStubGenerator {

    /** Maven/Gradle等の慣例的な中間ディレクトリ名。パッケージ名の構成要素からは除外する。 */
    private static final Set<String> NON_PACKAGE_SEGMENTS =
        Set.of("main", "test", "java", "kotlin", "groovy", "scala", "resources", "testFixtures");

    public record Stub(String text, int cursorRow, int cursorCol) {}

    private JavaFileStubGenerator() {}

    /**
     * filePath 用のひな形を生成する。拡張子が .java でない場合や、ファイル名が
     * クラス名として不正（識別子として不正／Java予約語）な場合は null を返し、
     * 呼び出し側は空文字列など従来通りの挙動にフォールバックすべきことを示す。
     */
    public static Stub generate(Path filePath) {
        if (filePath == null || filePath.getFileName() == null) return null;
        String fileName = filePath.getFileName().toString();
        if (!fileName.endsWith(".java")) return null;
        String className = fileName.substring(0, fileName.length() - ".java".length());
        if (!isValidClassName(className)) return null;

        String packageName = resolvePackageName(filePath);
        StringBuilder sb = new StringBuilder();
        int cursorRow;
        if (packageName != null) {
            sb.append("package ").append(packageName).append(";\n\n");
            sb.append("public class ").append(className).append(" {\n\n}\n");
            cursorRow = 3;
        } else {
            sb.append("public class ").append(className).append(" {\n\n}\n");
            cursorRow = 1;
        }
        return new Stub(sb.toString(), cursorRow, 0);
    }

    /**
     * filePath の祖先ディレクトリを遡って「src」ディレクトリを探し、そこから filePath の
     * 親ディレクトリまでの相対パスをパッケージ名として組み立てる。src が見つからない場合や
     * src 直下（デフォルトパッケージ）の場合は null を返す。
     */
    static String resolvePackageName(Path filePath) {
        Path dir = filePath.toAbsolutePath().normalize().getParent();
        if (dir == null) return null;
        Path srcDir = findSrcAncestor(dir);
        if (srcDir == null) return null;

        List<String> segments = new ArrayList<>();
        for (Path seg : srcDir.relativize(dir)) {
            String name = seg.toString();
            if (name.isEmpty() || NON_PACKAGE_SEGMENTS.contains(name)) continue;
            segments.add(name);
        }
        return segments.isEmpty() ? null : String.join(".", segments);
    }

    private static Path findSrcAncestor(Path dir) {
        for (Path cur = dir; cur != null; cur = cur.getParent()) {
            Path name = cur.getFileName();
            if (name != null && name.toString().equals("src")) {
                return cur;
            }
        }
        return null;
    }

    private static boolean isValidClassName(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return !SourceVersion.isKeyword(s);
    }
}
