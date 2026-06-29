package dev.javatexteditor.search;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 指定ディレクトリ配下のファイル名を正規表現で検索する。
 * ファイル内容は読まず、ファイル名（パスの最後の要素）だけを照合する。
 * マッチは大文字小文字を区別しない（CASE_INSENSITIVE）。
 */
public class FileNameSearcher {

    private static final Set<String> SKIP_DIRS =
        Set.of(".git", "build", "target", ".gradle", "node_modules", ".idea", ".vscode");

    /**
     * baseDir 配下のファイルをファイル名パターンで検索する。
     *
     * @param baseDir  検索の起点ディレクトリ
     * @param pattern  java.util.regex.Pattern 形式の正規表現（大文字小文字無視）
     * @return baseDir からの相対パスのリスト（発見順）
     * @throws PatternSyntaxException 正規表現が不正な場合
     */
    public List<Path> search(Path baseDir, String pattern) throws PatternSyntaxException {
        Pattern p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        List<Path> results = new ArrayList<>();

        if (!Files.isDirectory(baseDir)) {
            return results;
        }

        try {
            Files.walkFileTree(baseDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (SKIP_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (p.matcher(name).find()) {
                        results.add(baseDir.relativize(file));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // ルートへのアクセス失敗は無視
        }

        return results;
    }
}
