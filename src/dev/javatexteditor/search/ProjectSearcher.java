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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 指定ディレクトリ配下のファイルを正規表現で全文検索する。
 * Java SE 標準の Files.walkFileTree() と java.util.regex を使用。
 * バイナリファイルや読み取れないファイルは静かにスキップする。
 * マッチは大文字小文字を区別しない（CASE_INSENSITIVE。FileNameSearcher と同じ方針）。
 *
 * 軽量化リファクタリング Phase 3:
 * 「①逐次 walk でパス収集 → ②仮想スレッドでファイルごとに並列 grep → ③submit 順に連結」
 * の2段階構成。結果順序は従来の逐次実装（walk 順・ファイル内は行昇順）と同一。
 * 呼び出し元から見た同期的なブロッキング契約（processKey 直後に結果を assert できる）は
 * 変更していない。walk と各 grep タスクは割り込みを検査するため、呼び出し側
 * （ModalEditor.withTimeout）がタイムアウトで future.cancel(true) すると協調的に停止する
 *（従来はタイムアウト後も walkFileTree が走り続けるスレッド残留が既知の残課題だった）。
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
        Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);

        if (!Files.isDirectory(baseDir)) {
            return new ArrayList<>();
        }

        List<Path> candidates = collectCandidateFiles(baseDir, fullScan);
        return grepFilesInParallel(candidates, regex, baseDir);
    }

    /**
     * 第1段階: 対象ファイルのパスだけを逐次 walk で収集する（メタデータのみ・内容は読まない）。
     * スキップ規則・2MB上限は従来の visitFile 内判定と同一。
     */
    private List<Path> collectCandidateFiles(Path baseDir, boolean fullScan) {
        List<Path> candidates = new ArrayList<>();
        try {
            Files.walkFileTree(baseDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Thread.currentThread().isInterrupted()) {
                        return FileVisitResult.TERMINATE; // タイムアウトによる協調キャンセル
                    }
                    if (attrs.size() <= MAX_FILE_SIZE_BYTES) {
                        candidates.add(file);
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
                    if (Thread.currentThread().isInterrupted()) {
                        return FileVisitResult.TERMINATE; // タイムアウトによる協調キャンセル
                    }
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
        return candidates;
    }

    /**
     * 第2段階: 候補ファイルを仮想スレッドで並列に grep する。
     * Future を submit 順に get して連結するため、結果順序は逐次実装（walk 順）と同一。
     * ファイル I/O 主体の処理のためファイル数ぶんの仮想スレッドを一括生成してよい
     *（実際の同時 I/O はキャリアスレッド数に律速され、FD を使い果たすことはない）。
     */
    private List<SearchResult> grepFilesInParallel(List<Path> files, Pattern regex, Path baseDir) {
        List<SearchResult> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<SearchResult>>> futures = new ArrayList<>(files.size());
            for (Path file : files) {
                futures.add(executor.submit(() -> searchFile(file, regex, baseDir)));
            }
            for (Future<List<SearchResult>> future : futures) {
                results.addAll(future.get());
            }
        } catch (InterruptedException e) {
            // withTimeout 側の future.cancel(true)（タイムアウト）による割り込み。
            // 割り込みフラグを立て直すことで try-with-resources の close() が
            // shutdownNow() 相当の即時停止に切り替わり、残タスクは searchFile 冒頭の
            // 割り込みチェックで速やかに空リストを返して終了する。
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // searchFile は IOException 等を内部で握りつぶして空リストを返すため通常到達しない
        }
        return results;
    }

    /** 1ファイルを grep してそのファイル内の一致（行昇順）を返す。共有状態は持たない（並列実行のため）。 */
    private List<SearchResult> searchFile(Path file, Pattern regex, Path baseDir) {
        List<SearchResult> results = new ArrayList<>();
        if (Thread.currentThread().isInterrupted()) {
            return results; // タイムアウト後の残タスクは読み込みを始めず即終了する
        }
        // バイナリファイルのクイックチェック（先頭 8KB を読んで NUL バイトがあればスキップ）
        try {
            byte[] head = readHead(file, 8192);
            for (byte b : head) {
                if (b == NUL) return results;
            }
        } catch (IOException e) {
            return results;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            // UTF-8 でデコードできないファイル（バイナリ等）はスキップ
            return results;
        } catch (IOException e) {
            return results;
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
        return results;
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
