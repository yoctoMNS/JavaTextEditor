package dev.javatexteditor.analysis;

import dev.javatexteditor.search.FileNameSearcher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 作業ディレクトリ配下のテキストファイルから識別子（単語）を抜き出し、
 * Alt+/（Vim の i_CTRL-N 相当のキーワード補完）に使うインデックス。
 *
 * CompletionIndex（クラス/メソッド/フィールド名。javac の AST 解析が必要）とは異なり、
 * 正規表現でトークンを抜き出すだけなのでビルドが高速。ローカル変数・定数・
 * Java 以外のファイルの単語も拾える。
 *
 * TreeSet の subSet(prefix, prefix+MAX_VALUE) による O(log n + k) のプレフィックス
 * 検索を使う（k = 一致件数のみを走査すればよく、全件スキャンしない）。
 * ビルド完了後の TreeSet インスタンスは不変として扱い、参照の差し替え（volatile）だけで
 * スレッド間の可視性を保証するため、通常の読み取りにロックは不要。
 */
public final class WordIndex {

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    // ディレクトリ探索・ファイル名検索など他機能と同じスキップ対象（project-wide-search 系と共通の慣例）
    private static final Set<String> SKIP_DIRS = FileNameSearcher.SKIP_DIRS;  // 実体は search 側の1定義

    // バイナリ・巨大ファイルの走査に時間を取られないための上限
    private static final long MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024; // 2MB

    // 単語抽出の対象とする拡張子（バイナリファイルは開かない）
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        "java", "kt", "py", "js", "jsx", "ts", "tsx", "c", "h", "cpp", "hpp", "cc",
        "go", "rs", "rb", "php", "cs", "swift", "scala", "sh", "bash", "sql",
        "md", "txt", "json", "yaml", "yml", "xml", "properties", "gradle", "toml",
        "html", "css", "scss", "vue"
    );

    private volatile TreeSet<String> words = new TreeSet<>();
    private volatile boolean ready = false;

    private WordIndex() {}

    /** バックグラウンド仮想スレッドでインデックスを構築し、構築中のインスタンスを即座に返す。 */
    public static WordIndex build(Path root) {
        WordIndex idx = new WordIndex();
        Thread.ofVirtual().start(() -> idx.scanAndPublish(root));
        return idx;
    }

    /** テスト用: 同期的に構築して返す。 */
    public static WordIndex buildSync(Path root) {
        WordIndex idx = new WordIndex();
        idx.scanAndPublish(root);
        return idx;
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * プレフィックスに前方一致（大文字小文字区別あり）する単語を辞書順に最大 maxResults 件返す。
     * extraWords（例: 現在編集中バッファの単語。まだディスクに保存されていない可能性がある）も
     * 候補にマージする。
     *
     * @param prefix     検索文字列（空なら空リスト）
     * @param maxResults 返却する最大件数
     * @param extraWords 追加でマージする単語集合（null 可）
     */
    public List<String> query(String prefix, int maxResults, Collection<String> extraWords) {
        if (prefix == null || prefix.isEmpty() || maxResults <= 0) return List.of();

        TreeSet<String> matches = new TreeSet<>();
        if (ready) {
            String hi = prefix + Character.MAX_VALUE;
            matches.addAll(words.subSet(prefix, hi)); // O(log n + k)：一致件数分だけコピー
        }
        if (extraWords != null) {
            for (String w : extraWords) {
                if (w.startsWith(prefix)) matches.add(w);
            }
        }

        List<String> result = new java.util.ArrayList<>(Math.min(maxResults, matches.size()));
        for (String w : matches) {
            if (result.size() >= maxResults) break;
            result.add(w);
        }
        return result;
    }

    /** 任意のテキストから識別子トークンを重複なく抽出する（現在編集中バッファ用）。 */
    public static Set<String> extractWords(String text) {
        Set<String> result = new TreeSet<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            result.add(m.group());
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void scanAndPublish(Path root) {
        TreeSet<String> collected = new TreeSet<>();
        if (root != null) {
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        return SKIP_DIRS.contains(name) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.size() <= MAX_FILE_SIZE_BYTES && hasTextExtension(file)) {
                            indexFile(file, collected);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException | UncheckedIOException ignored) {
                // アクセス不可なディレクトリ等は無視して、それまでに集めた単語で公開する
            }
        }
        this.words = collected; // 参照の差し替え（volatile）でスレッド間可視性を確保
        this.ready = true;
    }

    private static boolean hasTextExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }

    private static void indexFile(Path file, TreeSet<String> collected) {
        try {
            String content = Files.readString(file);
            Matcher m = WORD.matcher(content);
            while (m.find()) {
                collected.add(m.group());
            }
        } catch (MalformedInputException ignored) {
            // UTF-8 として読めない（バイナリ寄り）ファイルはスキップ
        } catch (IOException ignored) {
            // 読み取り不可（権限等）はスキップ
        }
    }
}
