package dev.javatexteditor.analysis;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「編集中のバッファ ＋ プロジェクト配下の .java ファイル」を javac の compilation unit として
 * 集める処理。javac に意味解析（型解決）をさせる機能が共通で必要とする前段である。
 *
 * <p>{@link BindingDefinitionResolver}（Shift+K の定義ジャンプ）と
 * {@link JavacCompletionAnalyzer}（メンバー補完の型解決）の両方から使う。
 * 収集条件（スキップするディレクトリ・ファイル数上限・編集中ファイルの二重登録防止）が
 * 二箇所に分かれていると、片方だけ直して挙動がずれる事故が起きるため1箇所にまとめている。
 */
public final class JavaSourceCollector {

    /**
     * projectRoot 配下から収集する .java ファイル数の上限。これを超える場合は収集を諦める。
     * 作業ディレクトリの既定値はホームディレクトリになりうるため（WorkingDirectoryManager 参照）、
     * 無制限に収集すると javac の属性付けが数十秒〜数分かかる恐れがある。
     */
    public static final int MAX_SOURCE_FILES = 2000;

    /**
     * 走査時にスキップするディレクトリ名。FileNameSearcher.SKIP_DIRS と同じ集合
     * （search パッケージへの依存を避けるためここに複製。値を変える場合は両方を揃えること）。
     */
    private static final Set<String> SKIP_DIRS =
        Set.of(".git", "build", "target", ".gradle", "node_modules", ".idea", ".vscode");

    private JavaSourceCollector() {}

    /**
     * 編集中バッファ + projectRoot 配下の全 .java を収集する。
     * {@link #MAX_SOURCE_FILES} を超えた場合は null を返す（解析断念）。
     *
     * @param mainFileObj     編集中バッファ（未保存の変更を含む）を表す JavaFileObject
     * @param currentFilePath 編集中ファイルの絶対パス。ディスク上の古い内容を二重登録しないために使う
     * @param projectRoot     走査するプロジェクトルート（null や非ディレクトリならバッファのみ）
     * @param realPathByUri   収集した各ファイルの URI → 実パスの対応を書き込む先（不要なら null）
     */
    public static List<JavaFileObject> collect(StringJavaFileObject mainFileObj,
                                               String currentFilePath, Path projectRoot,
                                               Map<URI, String> realPathByUri) {
        List<JavaFileObject> sources = new ArrayList<>();
        sources.add(mainFileObj);
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return sources;
        }
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    if (name != null && SKIP_DIRS.contains(name.toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!file.toString().endsWith(".java")) {
                        return FileVisitResult.CONTINUE;
                    }
                    // 現在編集中のファイルはバッファ内容（未保存の変更を含む）を既に
                    // 追加済みなので、ディスク上の古い内容と二重にしない
                    if (file.toString().equals(currentFilePath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (sources.size() >= MAX_SOURCE_FILES + 1) {
                        return FileVisitResult.TERMINATE;
                    }
                    try {
                        String content = Files.readString(file);
                        StringJavaFileObject obj = new StringJavaFileObject(file.toString(), content);
                        if (realPathByUri != null) {
                            realPathByUri.put(obj.toUri(), file.toString());
                        }
                        sources.add(obj);
                    } catch (IOException ignored) {
                        // 非UTF-8等の読めないファイルは解析対象外として無視
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return sources; // 走査途中の失敗: 収集できた分だけで解析を試みる
        }
        if (sources.size() > MAX_SOURCE_FILES) {
            return null;
        }
        return sources;
    }
}
