package dev.javatexteditor.analysis;

import dev.javatexteditor.search.FileNameSearcher;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 *   <li>カーソルが識別子の上にある場合 → プロジェクト配下の {@code .c}/{@code .h} を走査し、
 *       関数定義（本体 {@code &#123;}）・マクロ（{@code #define}）・型（{@code typedef}/{@code struct}/
 *       {@code enum}/{@code union}）・関数プロトタイプ（{@code ;} で終わる宣言）の順で最も優先度の
 *       高い定義箇所へジャンプする。ヘッダにプロトタイプしか無い関数でも、実装（{@code .c} の関数
 *       定義）が見つかればそちらを優先するため「ヘッダ→実装」をたどれる。</li>
 * </ol>
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

    /** 山括弧 include がプロジェクトに無い場合に直接解決を試みる標準インクルードディレクトリ。 */
    private static final List<Path> SYSTEM_INCLUDE_DIRS = List.of(
        Path.of("/usr/include"),
        Path.of("/usr/local/include"));

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
        return resolveSymbol(word, currentFile, projectRoot);
    }

    // ---- #include の解決 --------------------------------------------------

    private Location resolveInclude(String header, boolean quoted, Path currentFile, Path projectRoot) {
        // 引用符形式: まず編集中ファイルと同じディレクトリからの相対で探す。
        if (quoted && currentFile != null) {
            Path parent = currentFile.toAbsolutePath().getParent();
            if (parent != null) {
                Path direct = parent.resolve(header).normalize();
                if (Files.isRegularFile(direct)) return new Location(direct, 0, "header");
            }
        }
        // プロジェクト全体からファイル名（またはパス末尾）一致で探す。
        Path found = findFileInProject(header, projectRoot);
        if (found != null) return new Location(found, 0, "header");

        // 山括弧形式（またはプロジェクトに無い場合）: 標準インクルードディレクトリを直接解決。
        for (Path base : SYSTEM_INCLUDE_DIRS) {
            Path candidate = base.resolve(header).normalize();
            if (Files.isRegularFile(candidate)) return new Location(candidate, 0, "system header");
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

    private Location resolveSymbol(String word, Path currentFile, Path projectRoot) {
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
                lines = Files.readAllLines(file);
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
        return null;
    }

    private static String labelFor(int pri) {
        return switch (pri) {
            case PRI_FUNCTION_DEF -> "definition";
            case PRI_MACRO        -> "macro";
            case PRI_TYPE         -> "type";
            default               -> "declaration";
        };
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
