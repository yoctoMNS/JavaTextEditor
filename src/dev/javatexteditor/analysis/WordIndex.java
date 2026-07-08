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
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
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
 * プレフィックス検索は大文字小文字を区別しない。TreeMap のキーを word.toLowerCase() に
 * 正規化し、subMap(prefix, prefix+MAX_VALUE) による O(log n + k) の検索を使う
 * （k = 一致件数のみを走査すればよく、全件スキャンしない）。値には元の大文字小文字表記を
 * 複数保持できる（例: "Apple" と "apple" は別キー "apple" の下に両方残る）。
 * ビルド完了後の TreeMap インスタンスは不変として扱い、参照の差し替え（volatile）だけで
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

    // key = word.toLowerCase()、value = 出現した元の大文字小文字表記（重複除去）。
    // 大文字小文字を区別しないプレフィックス検索のため、TreeMap のキーを小文字に正規化する。
    private volatile TreeMap<String, TreeSet<String>> words = new TreeMap<>();
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
     * プレフィックスに前方一致（大文字小文字区別なし）する単語を最大 maxResults 件返す。
     *
     * Vim の i_CTRL-N（'complete' 既定値 ".,w,b,u,t,i"）がカレントバッファを最優先ソースとして
     * 扱うのに倣い、extraWords（呼び出し側が並べた順序をそのまま尊重する。例:
     * {@link #extractWordsByProximity} でカーソル近接順に並べた現在編集中バッファの単語）を
     * 最優先で詰め、埋まらなかった残り枠だけをディスク索引（辞書順）から補う。
     * 二者間の重複は先に採用された方（＝ extraWords 側）を残す。
     *
     * @param prefix     検索文字列（空なら空リスト）
     * @param maxResults 返却する最大件数
     * @param extraWords 優先的に採用する単語（呼び出し側の順序を維持する。null 可）
     */
    public List<String> query(String prefix, int maxResults, Collection<String> extraWords) {
        if (prefix == null || prefix.isEmpty() || maxResults <= 0) return List.of();

        List<String> result = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);

        if (extraWords != null) {
            for (String w : extraWords) {
                if (result.size() >= maxResults) break;
                if (w.toLowerCase(Locale.ROOT).startsWith(lowerPrefix) && seen.add(w)) result.add(w);
            }
        }
        if (ready && result.size() < maxResults) {
            String hi = lowerPrefix + Character.MAX_VALUE;
            // O(log n + k)：小文字キーの一致件数分だけ走査
            for (TreeSet<String> originals : words.subMap(lowerPrefix, hi).values()) {
                for (String w : originals) {
                    if (result.size() >= maxResults) break;
                    if (seen.add(w)) result.add(w);
                }
                if (result.size() >= maxResults) break;
            }
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

    /**
     * Vim の i_CTRL-N と同じ探索順序（カーソル位置から本文末尾へ向かって前方探索し、
     * 末尾に達したら先頭へ折り返してカーソル位置の手前まで続ける）で、prefix に前方一致する
     * 識別子を重複なく列挙する。同一語が複数箇所にある場合は最初に見つかった出現
     * （＝カーソルに近い方）を残す。カーソル位置そのものの語（入力中の未確定なプレフィックス）は除く。
     *
     * @param text         走査対象のテキスト全文（現在編集中バッファ）
     * @param cursorOffset 探索の起点となるオフセット（入力中プレフィックスの先頭位置）
     * @param prefix       前方一致させる文字列
     */
    public static List<String> extractWordsByProximity(String text, int cursorOffset, String prefix) {
        if (prefix == null || prefix.isEmpty() || text == null) return List.of();

        List<String> result = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        Matcher m = WORD.matcher(text);
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);

        // 1周目: カーソル位置以降〜本文末尾（カーソル位置そのものの語は除く）
        while (m.find()) {
            int start = m.start();
            if (start < cursorOffset || start == cursorOffset) continue;
            String word = m.group();
            if (word.toLowerCase(Locale.ROOT).startsWith(lowerPrefix) && seen.add(word)) result.add(word);
        }
        // 2周目: 本文先頭〜カーソル位置（末尾から折り返した続き）。マッチは常に位置の昇順で
        // 見つかるため、cursorOffset 以降に達した時点で走査を打ち切ってよい。
        m.reset();
        while (m.find() && m.start() < cursorOffset) {
            String word = m.group();
            if (word.toLowerCase(Locale.ROOT).startsWith(lowerPrefix) && seen.add(word)) result.add(word);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void scanAndPublish(Path root) {
        TreeMap<String, TreeSet<String>> collected = new TreeMap<>();
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

    private static void indexFile(Path file, TreeMap<String, TreeSet<String>> collected) {
        try {
            String content = Files.readString(file);
            Matcher m = WORD.matcher(content);
            while (m.find()) {
                String word = m.group();
                collected.computeIfAbsent(word.toLowerCase(Locale.ROOT), k -> new TreeSet<>()).add(word);
            }
        } catch (MalformedInputException ignored) {
            // UTF-8 として読めない（バイナリ寄り）ファイルはスキップ
        } catch (IOException ignored) {
            // 読み取り不可（権限等）はスキップ
        }
    }
}
