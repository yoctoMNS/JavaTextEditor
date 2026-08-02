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
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 作業ディレクトリ配下のテキストファイルから識別子（単語）を抜き出し、
 * Alt+/（Vim の i_CTRL-N 相当のキーワード補完）に使うインデックス。
 *
 * CompletionIndex（JDK クラス名。javac の AST 解析が必要）とは異なり、
 * 正規表現でトークンを抜き出すだけなのでビルドが高速。ローカル変数・定数・
 * Java 以外のファイルの単語も拾える。
 *
 * プレフィックス検索は大文字小文字を区別しない。{@link ConcurrentSkipListMap} のキーを
 * word.toLowerCase() に正規化し、subMap(prefix, prefix+MAX_VALUE) による O(log n + k) の
 * 検索を使う（k = 一致件数のみを走査すればよく、全件スキャンしない）。値には元の大文字小文字
 * 表記を複数保持できる（例: "Apple" と "apple" は別キー "apple" の下に両方残る）。
 *
 * <p><b>ファイル単位の差分更新（{@link #updateFile(Path)}）</b>: 新規ファイル作成・既存ファイル
 * 編集の保存を検知した際、プロジェクト全体を再スキャンするとファイル数が多いプロジェクトで
 * 重くなる。そのため、保存されたファイル1つだけを再解析し、そのファイルが寄与していた単語
 * だけを削除・追加する。どの単語がどのファイルに由来するかを {@code fileWords}
 * （ファイル→そのファイルが持つ単語集合）と {@code wordFiles}（単語→参照しているファイル集合）
 * の相互参照で追跡し、ある単語を参照するファイルが0件になった時点でのみ {@code words} から
 * 削除する（複数ファイルに同じ単語がある場合に、片方を更新しただけで消えないようにするため）。
 * {@link ConcurrentSkipListMap}/{@link ConcurrentHashMap} を使うことで、この差分更新と
 * 通常の {@link #query} を排他ロックなしで安全に並行実行できる（弱一貫性のイテレーションで十分）。
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
    // 大文字小文字を区別しないプレフィックス検索のため、キーを小文字に正規化する。
    // 差分更新（updateFile）と通常の読み取り（query）を同時に扱えるよう、並行対応の
    // NavigableMap を使う（TreeMap + volatile 参照差し替えだと、1ファイル分の差分更新のたびに
    // マップ全体をコピーすることになり、プロジェクトが大きいほど保存のたびに重くなる）。
    private final ConcurrentSkipListMap<String, Set<String>> words = new ConcurrentSkipListMap<>();

    // 単語（元の大文字小文字表記）→ その単語を含むファイルの集合。updateFile() で、
    // あるファイルからその単語が消えたときに「他のファイルにもう存在しないなら words からも消す」
    // 判定をするために使う。
    private final ConcurrentHashMap<String, Set<Path>> wordFiles = new ConcurrentHashMap<>();

    // ファイル → そのファイルが最後に寄与した単語集合。updateFile() で旧内容との差分を取るために使う。
    private final ConcurrentHashMap<Path, Set<String>> fileWords = new ConcurrentHashMap<>();

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
            for (Set<String> originals : words.subMap(lowerPrefix, hi).values()) {
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

    /**
     * {@link #extractWordsByProximity} と同じ探索順序（カーソル位置から末尾へ、末尾に達したら
     * 先頭へ折り返す）で走査しつつ、前方一致ではなく {@link CompletionScorer} のマッチ判定を使う版。
     *
     * <p>IntelliJ IDEA の補完は前方一致だけでなく CamelCase 頭文字（{@code sb} → {@code stringBuilder}）や
     * 単語境界からの部分一致（{@code Builder} → {@code stringBuilder}）でも候補を拾う。
     * 編集中のバッファは走査が1ファイル分で済むため、そこだけはディスク索引（TreeMap の
     * 前方一致検索）と違って全語をマッチャに掛けられる。
     *
     * <p>戻り値の順序は「カーソルに近い順」のままで、スコア順への並べ替えは行わない
     * （並べ替えは {@link CompletionRanker} が担当し、同スコアの候補は入力順＝近接順を保つ）。
     *
     * @param maxResults 収集する語数の上限。巨大ファイルで候補が膨らみすぎないための歯止め
     */
    public static List<String> matchWordsByProximity(String text, int cursorOffset,
                                                     String prefix, int maxResults) {
        if (prefix == null || prefix.isEmpty() || text == null || maxResults <= 0) return List.of();

        List<String> result = new java.util.ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        Matcher m = WORD.matcher(text);

        // 1周目: カーソル位置以降〜本文末尾（カーソル位置そのものの語は除く）
        while (m.find() && result.size() < maxResults) {
            if (m.start() <= cursorOffset) continue;
            String word = m.group();
            if (CompletionScorer.match(prefix, word) != null && seen.add(word)) result.add(word);
        }
        // 2周目: 本文先頭〜カーソル位置（末尾から折り返した続き）
        m.reset();
        while (m.find() && m.start() < cursorOffset && result.size() < maxResults) {
            String word = m.group();
            if (CompletionScorer.match(prefix, word) != null && seen.add(word)) result.add(word);
        }
        return result;
    }

    /**
     * 指定した1ファイルだけを再解析し、そのファイルが寄与する単語を差分更新する。
     * プロジェクト全体を再スキャンしないため、ファイル数の多いプロジェクトでも
     * 保存のたびに重くならない（差分は「変更されたファイル1つ分」の単語数に比例する）。
     *
     * <p>次のいずれの経路でも安全に呼べる（同一の意味になるよう設計してある）:
     * <ul>
     *   <li>新規ファイルの保存（初回） — {@code fileWords} に旧エントリが無いため全単語が「追加」扱い</li>
     *   <li>既存ファイルの上書き保存 — 旧単語との差分だけを追加・削除する</li>
     *   <li>ファイルの削除・対象外拡張子への変更 — 読み取れない/対象外のときは新しい単語集合を
     *       空として扱うため、そのファイル由来の単語がすべて削除される（＝簡易的な削除検知を兼ねる）</li>
     * </ul>
     *
     * <p>{@code null} は無視する。呼び出し側の状態に関わらず安全に呼べるようにするため
     * （例: currentFilePath が未設定の疑似バッファで保存フックが呼ばれても何もしない）。
     */
    public synchronized void updateFile(Path file) {
        if (file == null) return;
        Set<String> newWords = readWords(file);
        Set<String> oldWords = fileWords.getOrDefault(file, Set.of());

        for (String word : oldWords) {
            if (!newWords.contains(word)) unlinkWord(file, word);
        }
        for (String word : newWords) {
            if (!oldWords.contains(word)) linkWord(file, word);
        }

        if (newWords.isEmpty()) {
            fileWords.remove(file);
        } else {
            fileWords.put(file, newWords);
        }
        // 初回保存（buildSync/build を経ていない単体利用等）でも query() が結果を返せるようにする
        this.ready = true;
    }

    /** 単語1つを、指定ファイルが参照している状態として登録する。 */
    private void linkWord(Path file, String word) {
        wordFiles.computeIfAbsent(word, w -> ConcurrentHashMap.newKeySet()).add(file);
        words.computeIfAbsent(word.toLowerCase(Locale.ROOT), k -> ConcurrentHashMap.newKeySet()).add(word);
    }

    /** 単語1つについて、指定ファイルからの参照を外す。他に参照するファイルが無くなった場合のみ words から消す。 */
    private void unlinkWord(Path file, String word) {
        Set<Path> files = wordFiles.get(word);
        if (files == null) return;
        files.remove(file);
        if (!files.isEmpty()) return;
        wordFiles.remove(word);
        String key = word.toLowerCase(Locale.ROOT);
        Set<String> originals = words.get(key);
        if (originals == null) return;
        originals.remove(word);
        if (originals.isEmpty()) words.remove(key);
    }

    /** ファイル1つ分の単語集合を読み取る。対象外拡張子・巨大ファイル・読み取り不可なら空集合。 */
    private static Set<String> readWords(Path file) {
        if (!hasTextExtension(file) || !Files.isRegularFile(file)) return Set.of();
        try {
            if (Files.size(file) > MAX_FILE_SIZE_BYTES) return Set.of();
            String content = Files.readString(file);
            return extractWords(content);
        } catch (MalformedInputException ignored) {
            return Set.of(); // UTF-8 として読めない（バイナリ寄り）ファイル
        } catch (IOException ignored) {
            return Set.of(); // 読み取り不可（権限等）・削除済み
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void scanAndPublish(Path root) {
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
                            indexFile(file);
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
        this.ready = true;
    }

    private static boolean hasTextExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }

    /** 起動時の全体スキャン専用。updateFile() と違い、この時点ではファイル間の重複判定は不要
     *  （初回スキャンでは同一ファイルを2回見ることはない）ため、直接 words/wordFiles/fileWords に積む。 */
    private void indexFile(Path file) {
        try {
            String content = Files.readString(file);
            Set<String> fileWordSet = extractWords(content);
            for (String word : fileWordSet) {
                linkWord(file, word);
            }
            if (!fileWordSet.isEmpty()) fileWords.put(file, fileWordSet);
        } catch (MalformedInputException ignored) {
            // UTF-8 として読めない（バイナリ寄り）ファイルはスキップ
        } catch (IOException ignored) {
            // 読み取り不可（権限等）はスキップ
        }
    }
}
