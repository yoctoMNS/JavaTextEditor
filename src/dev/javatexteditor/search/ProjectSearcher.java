package dev.javatexteditor.search;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 指定ディレクトリ配下のファイルを正規表現で全文検索する。
 * Java SE 標準の Files.walkFileTree() と java.util.regex を使用。
 * バイナリファイルや読み取れないファイルは静かにスキップする。
 */
public class ProjectSearcher {

    /** バイナリ判定: NUL バイトを含む場合はバイナリとみなしてスキップ */
    private static final int NUL = 0;

    /** 巨大ファイル（ログ・ダンプ等）の全文読み込みに時間を取られないための上限。
     *  WordIndex と同じ 2MB を採用（analysis/WordIndex.java 参照）。
     *  この上限がないと、K（jdk.doc）/ gr / :grep はプロジェクトルート配下を
     *  同期的（EDT上）に全文検索するため、巨大ファイルが1つあるだけで
     *  UI がフリーズしたように見える不具合があった。 */
    private static final long MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024; // 2MB

    /**
     * grep のデフォルトスキップ対象ディレクトリ。{@link FileNameSearcher#SKIP_DIRS} と共通。
     * 以前は「意図的に .git/build/target のみをスキップする」設計だったが、作業ディレクトリの
     * 既定値がホームディレクトリになりうるため、node_modules 等（数万ファイル規模になりうる）を
     * 素通しすると Shift+K/gr/:grep が容易にタイムアウトする問題が実測で確認された。
     * ユーザーからの明示的な要望（gR / :grep! / \g! / \f! の「全ファイル走査」指定）に応じて
     * デフォルトはこのスキップ対象を適用し、bang（!）付きの呼び出しでのみスキップを無効化する。
     */
    private static final java.util.Set<String> DEFAULT_SKIP_DIRS = FileNameSearcher.SKIP_DIRS;

    /**
     * baseDir 配下のテキストファイルを再帰的に走査し、
     * pattern に一致する行を SearchResult のリストで返す。
     * {@link #DEFAULT_SKIP_DIRS} を適用する（{@code node_modules}等をスキップ）。
     *
     * @param baseDir  検索の起点ディレクトリ
     * @param pattern  java.util.regex.Pattern 形式の正規表現
     * @return 一致結果のリスト（発見順）
     * @throws PatternSyntaxException 正規表現が不正な場合
     */
    public List<SearchResult> search(Path baseDir, String pattern) {
        return search(baseDir, pattern, false);
    }

    /**
     * baseDir 配下のテキストファイルを再帰的に走査し、
     * pattern に一致する行を SearchResult のリストで返す。
     *
     * @param baseDir  検索の起点ディレクトリ
     * @param pattern  java.util.regex.Pattern 形式の正規表現
     * @param fullScan true の場合 {@link #DEFAULT_SKIP_DIRS} を無視し、全ファイルを走査する
     *                 （gR / :grep! / \g! / \f! 等「bang」付き呼び出し用）
     * @return 一致結果のリスト（発見順）
     * @throws PatternSyntaxException 正規表現が不正な場合
     */
    public List<SearchResult> search(Path baseDir, String pattern, boolean fullScan) {
        Pattern regex = Pattern.compile(pattern);
        List<SearchResult> results = new ArrayList<>();

        if (!Files.isDirectory(baseDir)) {
            return results;
        }

        try {
            Files.walkFileTree(baseDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.size() <= MAX_FILE_SIZE_BYTES) {
                        searchFile(file, regex, baseDir, results);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // アクセス権なし等は静かにスキップ
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (fullScan) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (DEFAULT_SKIP_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // walkFileTree 自体は通常 IOException を投げないが念のため
        }

        return results;
    }

    private void searchFile(Path file, Pattern regex, Path baseDir, List<SearchResult> results) {
        // バイナリファイルのクイックチェック（先頭 8KB を読んで NUL バイトがあればスキップ）
        try {
            byte[] head = readHead(file, 8192);
            for (byte b : head) {
                if (b == NUL) return;
            }
        } catch (IOException e) {
            return;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            // UTF-8 でデコードできないファイル（バイナリ等）はスキップ
            return;
        } catch (IOException e) {
            return;
        }

        String relativePath = baseDir.relativize(file).toString();
        // OS に依らず / で表示
        relativePath = relativePath.replace('\\', '/');

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = regex.matcher(line);
            if (m.find()) {
                results.add(new SearchResult(relativePath, i + 1, line));
            }
        }
    }

    private byte[] readHead(Path file, int maxBytes) throws IOException {
        try (var is = Files.newInputStream(file)) {
            byte[] buf = new byte[maxBytes];
            int read = is.read(buf, 0, maxBytes);
            if (read <= 0) return new byte[0];
            if (read < maxBytes) {
                byte[] trimmed = new byte[read];
                System.arraycopy(buf, 0, trimmed, 0, read);
                return trimmed;
            }
            return buf;
        }
    }
}
