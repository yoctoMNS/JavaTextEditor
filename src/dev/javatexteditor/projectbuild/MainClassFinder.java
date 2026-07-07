package dev.javatexteditor.projectbuild;

import dev.javatexteditor.search.FileNameSearcher;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F11: projectRoot 配下の .java ファイルを軽量な正規表現で走査し、
 * {@code public static void main(String[])} を持つクラスの完全修飾名(FQCN)を列挙する。
 * {@link dev.javatexteditor.analysis.SourceAnalyzer}（javac AST 解析）は使わない。
 * WordIndex と同じ理由（ファイル数に比例して重い AST 解析を避ける）で正規表現ベースにとどめている。
 */
public class MainClassFinder {

    private static final Pattern PACKAGE_PATTERN =
        Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern MAIN_METHOD_PATTERN = Pattern.compile(
        "(public\\s+static|static\\s+public)\\s+void\\s+main\\s*\\(\\s*String");

    public List<String> findMainClasses(Path projectRoot) {
        List<String> result = new ArrayList<>();
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(projectRoot) &&
                            (FileNameSearcher.SKIP_DIRS.contains(name) || name.equals(ProjectBuilder.OUTPUT_DIR_NAME))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!file.toString().endsWith(".java")) return FileVisitResult.CONTINUE;
                    String content;
                    try {
                        content = Files.readString(file);
                    } catch (IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (MAIN_METHOD_PATTERN.matcher(content).find()) {
                        String className = file.getFileName().toString().replace(".java", "");
                        Matcher pm = PACKAGE_PATTERN.matcher(content);
                        String fqcn = pm.find() ? pm.group(1) + "." + className : className;
                        result.add(fqcn);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 走査失敗はここまでに見つかった候補のみ返す（grep 系と同じ graceful degradation）
        }
        Collections.sort(result);
        return result;
    }
}
