package dev.javatexteditor.analysis;

import dev.javatexteditor.search.FileNameSearcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C言語（{@code .c}/{@code .h}）の Shift+K（定義ジャンプ）の解決ロジック。Swing 非依存の純粋ロジック＋
 * ディスク走査で、カーソル位置に応じて次のいずれかの飛び先を返す:
 *
 * <ol>
 *   <li>カーソルが {@code #include "foo.h"} / {@code #include <foo.h>} の行にある場合 → その
 *       ヘッダファイルを開く（引用符形式はまず編集中ファイルと同じディレクトリ、次にプロジェクト全体、
 *       山括弧形式はプロジェクト全体→標準インクルードディレクトリの順に探す）。</li>
 *   <li>カーソルが識別子の上にある場合 → まずプロジェクト配下の {@code .c}/{@code .h} を走査し、
 *       関数定義（本体 {@code &#123;}）・マクロ（{@code #define}）・型（{@code typedef}/{@code struct}/
 *       {@code enum}/{@code union}）・関数プロトタイプ（{@code ;} で終わる宣言）の順で最も優先度の
 *       高い定義箇所へジャンプする。ヘッダにプロトタイプしか無い関数でも、実装（{@code .c} の関数
 *       定義）が見つかればそちらを優先するため「ヘッダ→実装」をたどれる。プロジェクト内に見つから
 *       なければ、現在のファイルが実際に {@code #include} しているヘッダ（プロジェクト内／標準ヘッダを
 *       問わず、そのヘッダがさらに {@code #include} するヘッダも幅優先で追う）から
 *       {@code printf}/{@code NULL}/{@code size_t} のような標準ライブラリの宣言を探す。</li>
 * </ol>
 *
 * <p><b>標準ヘッダの探索範囲を「現在のファイルが実際に #include しているものだけ」に限定している
 * 理由</b>: 当初は標準インクルードディレクトリ（{@code /usr/include} 等）配下を丸ごと総当たりする
 * 実装だったが、実機検証で2つの問題が判明した。①ディレクトリ配下には無関係な大量のライブラリ
 * （openssl・X11・valgrind 等）が同居しており、コメント中に偶然シンボル名が現れるだけの行を
 * 誤検出する事故が実際に発生した。②総ファイル数が数千に達し、1回の検索に数秒かかり
 * {@code ModalEditor} 側の1500msタイムアウトを恒常的に超過した。このため、現在のバッファが
 * 実際に {@code #include} している（かつそこから辿れる）ヘッダだけを対象にする幅優先探索に
 * 変更した。これは実際のCコンパイラのシンボル解決規則（インクルードしていないヘッダの宣言は
 * そもそも見えない）とも一致しており、正しさと速さを両立する。
 *
 * <p><b>標準インクルードディレクトリの解決</b>: OS別のパスをハードコードするのではなく、実際に
 * インストールされている C コンパイラ（{@code gcc}→{@code clang}→{@code cc}。{@code CProjectBuilder}/
 * {@code CCompileAnalyzer} と同じ検出順）に {@code <compiler> -E -v} で問い合わせ、コンパイラ自身が
 * 報告する検索パス（{@code #include <...> search starts here:} 〜 {@code End of search list.}）を使う。
 * これにより Linux（glibc）でも Windows（MinGW-w64/MSYS2 等）でも、その環境に実際に入っている
 * ツールチェーンの標準ヘッダへ正しくジャンプできる（OS別パスのハードコードでは Windows で機能しない）。
 * 検出結果はプロセス起動を伴うため JVM 内で1回だけ計算しキャッシュする。
 *
 * Java の {@link BindingDefinitionResolver}（javac 属性付け）と異なり、C にはインプロセスの型解決 API が
 * 無いため、正規表現ベースの ctags 風ヒューリスティックで実装する（{@code gr}/{@code :grep} と同じ
 * 割り切り）。Windows/Linux 双方で動くよう {@link Path}/{@link Files}/正規表現のみを使う。
 */
public final class CDefinitionResolver {

    /** 定義箇所（0-indexed 行番号）。header==true はヘッダを開くだけの飛び先。 */
    public record Location(Path file, int line, String label) {}

    /** 走査対象とする C 系拡張子。 */
    private static final Set<String> C_EXTENSIONS =
        Set.of("c", "h", "cc", "cpp", "cxx", "hpp", "hh", "hxx");

    /** 2MB 超のファイルは読み飛ばす（WordIndex/ProjectSearcher と同じ上限）。 */
    private static final long MAX_FILE_SIZE = 2L * 1024 * 1024;

    /**
     * 検出を試みる C コンパイラ（{@link dev.javatexteditor.projectbuild.CProjectBuilder}/
     * {@link CCompileAnalyzer} と同じ検出順・同じ検出ロジック。3クラス目の重複だが、CLAUDE.md の
     * 「3行の重複は早すぎる抽象化よりよい」という既存方針を踏襲しあえて共通化しなかった）。
     */
    private static final List<String> COMPILER_CANDIDATES = List.of("gcc", "clang", "cc");

    // 標準インクルードディレクトリ一覧の遅延キャッシュ。コンパイラへの問い合わせはプロセス起動を
    // 伴うため、JVM内（＝エディタのセッション中）で1回だけ計算する。インストール済みツールチェーンは
    // セッション中に変わらない前提。
    private static volatile List<Path> systemIncludeDirsCache = null;
    private static volatile boolean systemIncludeDirsAttempted = false;

    private static final Pattern INCLUDE_PATTERN =
        Pattern.compile("^\\s*#\\s*include\\s*([<\"])([^>\"]+)[>\"]");

    // 定義の種類。数値が小さいほど優先度が高い。
    private static final int PRI_FUNCTION_DEF = 0;
    private static final int PRI_MACRO        = 1;
    private static final int PRI_TYPE         = 2;
    private static final int PRI_PROTOTYPE    = 3;

    // C のキーワード（識別子として定義探索の対象にしない）。
    private static final Set<String> KEYWORDS = Set.of(
        "if", "else", "for", "while", "do", "switch", "case", "default", "break",
        "continue", "return", "goto", "sizeof", "typedef", "struct", "enum", "union",
        "const", "static", "extern", "void", "int", "char", "short", "long", "float",
        "double", "unsigned", "signed", "register", "volatile", "auto", "inline");

    /**
     * カーソル位置に応じた C の定義ジャンプ先を解決する。見つからなければ null。
     *
     * @param source      現在バッファ全文
     * @param currentFile 編集中ファイル（{@code #include "..."} の相対解決の基点。null 可）
     * @param cursorRow   カーソル行（0-indexed）
     * @param cursorCol   カーソル列（0-indexed）
     * @param projectRoot 走査基点ディレクトリ
     */
    public Location resolve(String source, Path currentFile, int cursorRow, int cursorCol, Path projectRoot) {
        String[] lines = source.split("\n", -1);
        if (cursorRow < 0 || cursorRow >= lines.length) return null;
        String line = lines[cursorRow];

        // 1) #include 行なら該当ヘッダを開く（カーソル列は問わない）。
        Matcher inc = INCLUDE_PATTERN.matcher(line);
        if (inc.find()) {
            boolean quoted = inc.group(1).equals("\"");
            return resolveInclude(inc.group(2), quoted, currentFile, projectRoot);
        }

        // 2) カーソル位置の識別子を定義探索する。
        String word = wordAt(line, cursorCol);
        if (word == null || word.isEmpty() || KEYWORDS.contains(word)) return null;
        return resolveSymbol(word, source, currentFile, projectRoot);
    }

    // ---- #include の解決 --------------------------------------------------

    /** header の実ファイルパスの解決結果。system==true なら標準インクルードディレクトリ側で見つかった。 */
    private record ResolvedHeader(Path path, boolean system) {}

    private Location resolveInclude(String header, boolean quoted, Path currentFile, Path projectRoot) {
        ResolvedHeader rh = resolveIncludePath(header, quoted, currentFile, projectRoot);
        if (rh == null) return null;
        return new Location(rh.path(), 0, rh.system() ? "system header" : "header");
    }

    /**
     * header の実ファイルパスを解決する（#include ジャンプ・標準ヘッダ経由のシンボル探索の
     * 両方から使う共通ロジック）。引用符形式はまず編集中ファイルと同じディレクトリ、次にプロジェクト
     * 全体、山括弧形式はプロジェクト全体→標準インクルードディレクトリの順に探す。見つからなければ null。
     */
    private ResolvedHeader resolveIncludePath(String header, boolean quoted, Path currentFile, Path projectRoot) {
        if (quoted && currentFile != null) {
            Path parent = currentFile.toAbsolutePath().getParent();
            if (parent != null) {
                Path direct = parent.resolve(header).normalize();
                if (Files.isRegularFile(direct)) return new ResolvedHeader(direct, false);
            }
        }
        Path found = findFileInProject(header, projectRoot);
        if (found != null) return new ResolvedHeader(found, false);

        for (Path base : systemIncludeDirs()) {
            Path candidate = base.resolve(header).normalize();
            if (Files.isRegularFile(candidate)) return new ResolvedHeader(candidate, true);
        }
        return null;
    }

    /** projectRoot 配下でパス末尾が header に一致する最初のファイルを返す（無ければ null）。 */
    private Path findFileInProject(String header, Path projectRoot) {
        String normalized = header.replace('\\', '/');
        List<Path> exact = new ArrayList<>();
        List<Path> byName = new ArrayList<>();
        String baseName = normalized.substring(normalized.lastIndexOf('/') + 1);
        walkCFiles(projectRoot, (file, attrs) -> {
            String p = file.toString().replace('\\', '/');
            if (p.endsWith("/" + normalized) || p.equals(normalized)) exact.add(file);
            else if (file.getFileName().toString().equals(baseName)) byName.add(file);
        }, /*restrictToCExtensions=*/false, normalized);
        exact.sort(null);
        byName.sort(null);
        if (!exact.isEmpty()) return exact.get(0);
        if (!byName.isEmpty()) return byName.get(0);
        return null;
    }

    // ---- 識別子の定義探索 -------------------------------------------------

    private Location resolveSymbol(String word, String source, Path currentFile, Path projectRoot) {
        List<Path> files = new ArrayList<>();
        walkCFiles(projectRoot, (file, attrs) -> files.add(file), true, null);
        // 編集中ファイルを先に見る（同一ファイル内の定義を優先）。残りはパス順で決定的に。
        files.sort(null);
        if (currentFile != null) {
            Path cf = currentFile.toAbsolutePath().normalize();
            files.sort((a, b) -> {
                boolean aCur = a.toAbsolutePath().normalize().equals(cf);
                boolean bCur = b.toAbsolutePath().normalize().equals(cf);
                if (aCur != bCur) return aCur ? -1 : 1;
                return a.compareTo(b);
            });
        }

        Location[] best = new Location[PRI_PROTOTYPE + 1]; // 種類ごとの最初のヒット
        for (Path file : files) {
            List<String> lines;
            try {
                lines = stripComments(Files.readAllLines(file));
            } catch (IOException e) {
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                int pri = classifyDefinition(lines.get(i), word);
                if (pri >= 0 && best[pri] == null) {
                    best[pri] = new Location(file, i, labelFor(pri));
                    if (pri == PRI_FUNCTION_DEF) return best[pri]; // 実装が最優先。即決定
                }
            }
        }
        for (int pri = 0; pri <= PRI_PROTOTYPE; pri++) {
            if (best[pri] != null) return best[pri];
        }
        // プロジェクト内に見つからなければ、現在のファイルが実際に #include しているヘッダ
        // （そこからさらに #include されるヘッダも含む）から標準ライブラリの宣言を探す
        // （printf/NULL/size_t 等。#include 行以外の使用箇所での K でもここに到達する）。
        return resolveSymbolInIncludedHeaders(word, source, currentFile, projectRoot);
    }

    /** 1回の探索で辿るヘッダ数の上限（無関係な巨大ツリーへ広がりすぎるのを防ぐ安全弁）。 */
    private static final int MAX_HEADER_SCAN = 300;

    /**
     * 現在のファイルが実際に {@code #include} しているヘッダ群から word の定義を幅優先で探す
     * （{@link #resolveSymbol} からプロジェクト内に見つからなかった場合のフォールバック）。
     * 標準インクルードディレクトリを丸ごと総当たりするのではなく、実際にこのファイルから見える
     * ヘッダ（そのヘッダがさらに #include するヘッダも含む）だけを対象にすることで、無関係な
     * ライブラリのコメント等からの誤検出を避けつつ、数千ファイル規模の走査を避けて高速に保つ
     * （クラス Javadoc の「標準ヘッダの探索範囲を…」参照）。標準ヘッダ由来のヒットはラベルに
     * "(system header)" を付け、標準ライブラリ側へジャンプしたことをステータスバーで分かるようにする。
     */
    private Location resolveSymbolInIncludedHeaders(
            String word, String source, Path currentFile, Path projectRoot) {
        Set<Path> visited = new HashSet<>();
        Deque<ResolvedHeader> queue = new ArrayDeque<>();
        enqueueIncludes(source, currentFile, projectRoot, visited, queue);

        Location[] best = new Location[PRI_PROTOTYPE + 1];
        int scanned = 0;
        while (!queue.isEmpty() && scanned < MAX_HEADER_SCAN) {
            if (Thread.currentThread().isInterrupted()) break;
            ResolvedHeader rh = queue.poll();
            scanned++;
            List<String> rawLines;
            try {
                rawLines = Files.readAllLines(rh.path());
            } catch (IOException e) {
                continue;
            }
            List<String> lines = stripComments(rawLines);
            for (int i = 0; i < lines.size(); i++) {
                int pri = classifyDefinition(lines.get(i), word);
                if (pri >= 0 && best[pri] == null) {
                    String suffix = rh.system() ? " (system header)" : "";
                    best[pri] = new Location(rh.path(), i, labelFor(pri) + suffix);
                    if (pri == PRI_FUNCTION_DEF) return best[pri];
                }
            }
            // このヘッダがさらに #include しているヘッダも探索対象に加える
            // （size_t 等、間接的に標準ヘッダを経由する宣言をたどるため）。
            enqueueIncludes(String.join("\n", rawLines), rh.path(), projectRoot, visited, queue);
        }
        for (int pri = 0; pri <= PRI_PROTOTYPE; pri++) {
            if (best[pri] != null) return best[pri];
        }
        return null;
    }

    /** source 中の #include 行を解決し、未訪問のものだけを queue に追加する。 */
    private void enqueueIncludes(String source, Path fromFile, Path projectRoot,
            Set<Path> visited, Deque<ResolvedHeader> queue) {
        for (String line : source.split("\n", -1)) {
            Matcher m = INCLUDE_PATTERN.matcher(line);
            if (!m.find()) continue;
            boolean quoted = m.group(1).equals("\"");
            ResolvedHeader rh = resolveIncludePath(m.group(2), quoted, fromFile, projectRoot);
            if (rh != null && visited.add(rh.path())) queue.add(rh);
        }
    }

    /**
     * ブロックコメント（{@code /* ... *&#47;}）・行コメント（{@code //}）を大まかに除去した各行の
     * リストを返す。行コメントは行頭からの出現位置を見るだけの単純な判定のため、文字列リテラル内の
     * {@code //}/{@code /*} は考慮しない（例: {@code "http://example.com"}）。標準ヘッダ・プロジェクト
     * コードのコメント中に識別子名が偶然現れて誤って定義行と判定される事故を防ぐための簡易実装で、
     * 完全な字句解析ではない（{@code gr}/{@code :grep} と同じ正規表現ヒューリスティックの延長）。
     * ブロックコメントの開閉状態はファイル全体を通して1回のループで引き継ぐ。
     */
    static List<String> stripComments(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        boolean inBlock = false;
        for (String line : lines) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < line.length()) {
                if (inBlock) {
                    int end = line.indexOf("*/", i);
                    if (end < 0) { i = line.length(); break; }
                    i = end + 2;
                    inBlock = false;
                    continue;
                }
                int lineCommentIdx = line.indexOf("//", i);
                int blockCommentIdx = line.indexOf("/*", i);
                if (lineCommentIdx >= 0 && (blockCommentIdx < 0 || lineCommentIdx < blockCommentIdx)) {
                    sb.append(line, i, lineCommentIdx);
                    break;
                }
                if (blockCommentIdx >= 0) {
                    sb.append(line, i, blockCommentIdx);
                    int end = line.indexOf("*/", blockCommentIdx + 2);
                    if (end < 0) {
                        inBlock = true;
                        break;
                    }
                    i = end + 2;
                    continue;
                }
                sb.append(line, i, line.length());
                break;
            }
            result.add(sb.toString());
        }
        return result;
    }

    private static String labelFor(int pri) {
        return switch (pri) {
            case PRI_FUNCTION_DEF -> "definition";
            case PRI_MACRO        -> "macro";
            case PRI_TYPE         -> "type";
            default               -> "declaration";
        };
    }

    // ---- 標準インクルードディレクトリの動的検出 ----------------------------

    /**
     * インストールされている C コンパイラ（gcc→clang→cc）の標準インクルードディレクトリ一覧
     * （JVM内で1回だけ計算・キャッシュ）。コンパイラが見つからない・問い合わせに失敗した場合は
     * 空リスト（以後の呼び出しでも再試行しない。頻繁に呼ばれる K のたびにプロセス起動を
     * 繰り返さないため）。
     *
     * <p>ModalEditor 側の {@code withTimeout()}（1500ms）に途中で割り込まれた場合は、不完全な
     * 結果を「検出失敗」として永続キャッシュしない（次回の K で再試行させる）。割り込みを永続化
     * すると、たまたま最初の K 押下時にコンパイラの初回起動が遅かっただけで、以後のセッション
     * 全体で標準ヘッダへのジャンプが機能しなくなるため。
     */
    private static synchronized List<Path> systemIncludeDirs() {
        if (systemIncludeDirsAttempted) return systemIncludeDirsCache;
        List<Path> result = discoverSystemIncludeDirs();
        if (Thread.currentThread().isInterrupted()) {
            return result; // 中断された結果はキャッシュしない
        }
        systemIncludeDirsCache = result;
        systemIncludeDirsAttempted = true;
        return systemIncludeDirsCache;
    }

    private static List<Path> discoverSystemIncludeDirs() {
        String compiler = findCompiler();
        if (compiler == null) return List.of();
        Path tempSrc;
        try {
            tempSrc = Files.createTempFile("jte-cinc-", ".c");
        } catch (IOException e) {
            return List.of();
        }
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(compiler, "-E", "-v", tempSrc.toString());
            pb.redirectErrorStream(true); // -v の検索パス一覧は stderr に出るため合流させて読む
            process = pb.start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            process.waitFor(5, TimeUnit.SECONDS);
            return parseIncludeSearchPaths(output);
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            // 割り込み等でまだ生きていれば、子プロセスが残留しないよう強制終了する。
            if (process != null && process.isAlive()) process.destroyForcibly();
            try {
                Files.deleteIfExists(tempSrc);
            } catch (IOException ignored) {
                // 一時ファイル削除失敗は無視
            }
        }
    }

    /**
     * gcc/clang の {@code -E -v} 出力から、{@code #include <...> search starts here:} と
     * {@code End of search list.} の間に列挙されるディレクトリ一覧を抽出する。両コンパイラとも
     * 同じ書式（ビルドツールがコンパイラのデフォルト検索パスを検出するのに使う標準的な慣習）で
     * 出力するため、gcc/clang 共通のパーサで扱える。実在しないディレクトリ（出力の解釈ミス等）は
     * 除外する。
     */
    static List<Path> parseIncludeSearchPaths(String verboseOutput) {
        List<Path> dirs = new ArrayList<>();
        boolean inSection = false;
        for (String line : verboseOutput.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#include") && trimmed.contains("search starts here")) {
                inSection = true;
                continue;
            }
            if (trimmed.equals("End of search list.")) {
                inSection = false;
                continue;
            }
            if (inSection && !trimmed.isEmpty()) {
                // clang は "(framework directory)" 等の注記を付けることがあるため取り除く。
                String pathStr = trimmed.replaceAll("\\s*\\(.*\\)\\s*$", "").strip();
                if (pathStr.isEmpty()) continue;
                Path p = Path.of(pathStr);
                if (Files.isDirectory(p)) dirs.add(p);
            }
        }
        return List.copyOf(dirs);
    }

    /** PATH 上で最初に見つかった C コンパイラ名を返す（無ければ null）。 */
    private static String findCompiler() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String candidate : COMPILER_CANDIDATES) {
            for (String dir : pathEnv.split(Pattern.quote(java.io.File.pathSeparator))) {
                if (dir.isEmpty()) continue;
                Path p = Path.of(dir, candidate);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) return candidate;
                if (Files.isRegularFile(Path.of(dir, candidate + ".exe"))) return candidate;
            }
        }
        return null;
    }

    /**
     * 1行を見て、word の定義であればその種類（PRI_*）を、そうでなければ -1 を返す。
     * 関数定義/プロトタイプは「行が『戻り値の型 word(』の形で始まる」ことを要求し、
     * 関数呼び出し（{@code x = foo(...)} 等）を誤検出しないようにする。
     */
    int classifyDefinition(String line, String word) {
        String q = Pattern.quote(word);

        // マクロ: #define word （オブジェクト形式・関数形式どちらも）
        if (Pattern.compile("^\\s*#\\s*define\\s+" + q + "\\b").matcher(line).find()) {
            return PRI_MACRO;
        }
        // 型: struct/enum/union word / typedef ... word; / } word;
        if (Pattern.compile("^\\s*(typedef\\s+)?(struct|enum|union)\\s+" + q + "\\b").matcher(line).find()
            || Pattern.compile("^\\s*typedef\\b.*\\b" + q + "\\s*;").matcher(line).find()
            || Pattern.compile("\\}\\s*" + q + "\\s*;").matcher(line).find()) {
            return PRI_TYPE;
        }

        // 関数定義/プロトタイプ: 行頭から「型トークン列 + word(」で始まる。
        // beforeWord に戻り値の型（英字トークン + 空白）が必須 → 呼び出しを除外する。
        Matcher fn = Pattern.compile(
            "^\\s*[A-Za-z_][A-Za-z0-9_\\s\\*]*?\\b" + q + "\\s*\\(").matcher(line);
        if (fn.find()) {
            String beforeWord = line.substring(0, line.indexOf(word, fn.start()));
            String bt = beforeWord.strip();
            // 戻り値の型（少なくとも1トークン）が word の前にあること。代入・return は除外。
            boolean hasReturnType = bt.matches("[A-Za-z_][A-Za-z0-9_\\s\\*]*\\S");
            boolean looksLikeCall = beforeWord.contains("=") || beforeWord.contains("return");
            if (hasReturnType && !looksLikeCall) {
                String trimmed = line.strip();
                if (trimmed.endsWith(";")) return PRI_PROTOTYPE;
                if (trimmed.endsWith("{") || trimmed.endsWith(")") || trimmed.endsWith(",")) {
                    return PRI_FUNCTION_DEF;
                }
                // 上記以外（複数行シグネチャの途中等）は定義寄りに倒す。
                return PRI_FUNCTION_DEF;
            }
        }
        return -1;
    }

    // ---- 共通のファイル走査 -----------------------------------------------

    private interface FileConsumer {
        void accept(Path file, BasicFileAttributes attrs);
    }

    /**
     * projectRoot 配下を走査し、条件に合うファイルへ consumer を呼ぶ。SKIP_DIRS・2MB 上限を適用する。
     * restrictToCExtensions=true のとき C 系拡張子のみ。false のときは includeNeedle（パス末尾一致対象の
     * ヘッダ名。null 可）で早期に絞るためのヒントとして basename を見る。
     */
    private void walkCFiles(Path projectRoot, FileConsumer consumer,
            boolean restrictToCExtensions, String includeNeedle) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return;
        String needleBase = includeNeedle == null ? null
            : includeNeedle.substring(includeNeedle.lastIndexOf('/') + 1);
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(projectRoot) && FileNameSearcher.SKIP_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                    if (attrs.size() > MAX_FILE_SIZE) return FileVisitResult.CONTINUE;
                    if (restrictToCExtensions) {
                        if (!hasCExtension(file)) return FileVisitResult.CONTINUE;
                    } else if (needleBase != null
                            && !file.getFileName().toString().equals(needleBase)) {
                        return FileVisitResult.CONTINUE;
                    }
                    consumer.accept(file, attrs);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 走査失敗はここまでの結果で処理する（grep 系と同じ graceful degradation）。
        }
    }

    private static boolean hasCExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return C_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** line の col 位置にある C 識別子（見つからなければ null）。 */
    static String wordAt(String line, int col) {
        if (col < 0 || col > line.length()) return null;
        // カーソルが行末（col==length）や識別子でない位置なら、左隣も試す。
        int probe = col;
        if (probe >= line.length() || !isIdentPart(line.charAt(probe))) {
            if (probe > 0 && isIdentPart(line.charAt(probe - 1))) probe--;
            else return null;
        }
        int start = probe;
        while (start > 0 && isIdentPart(line.charAt(start - 1))) start--;
        int end = probe;
        while (end < line.length() && isIdentPart(line.charAt(end))) end++;
        return line.substring(start, end);
    }

    private static boolean isIdentPart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }
}
