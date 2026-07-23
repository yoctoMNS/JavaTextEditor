package dev.javatexteditor.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C バッファ向けの {@code #include} 自動挿入・整理を担う純粋ロジック（Swing・サブプロセス非依存）。
 * Java 側の auto-import（{@link AutoImportHandler} / {@link ImportSuggester}）の C 版に相当し、
 * 標準ライブラリのシンボル（{@code printf} / {@code malloc} / {@code size_t} など）を使っているのに
 * 対応するヘッダを {@code #include} していない場合に、必要なヘッダ行を追加する。
 *
 * <p>2つの入口を想定する:
 * <ul>
 *   <li>診断ベース（INSERT→NORMAL 時の自動挿入）: gcc の "implicit declaration of function 'X'" /
 *       "unknown type name 'X'" / "'X' undeclared" 等のメッセージからシンボル名を抽出
 *       （{@link #extractSymbolFromMessage}）し、対応ヘッダを追加する。</li>
 *   <li>ソース走査ベース（{@code :oi} / {@code SPC+i+o} 相当の明示的整理）: ソース中に現れる既知の
 *       標準シンボルを走査（{@link #usedKnownSymbols}）し、未 include のヘッダをまとめて追加する。</li>
 * </ul>
 */
public final class CIncludeManager {

    private CIncludeManager() {}

    /** 既知の標準ライブラリシンボル → 標準ヘッダ名の対応表。 */
    private static final Map<String, String> SYMBOL_TO_HEADER = buildSymbolMap();

    private static Map<String, String> buildSymbolMap() {
        Map<String, String> m = new java.util.HashMap<>();
        // <stdio.h>
        for (String s : new String[]{"printf", "fprintf", "sprintf", "snprintf", "scanf", "sscanf",
                "fscanf", "puts", "putchar", "getchar", "fgets", "fputs", "fgetc", "fputc",
                "fopen", "fclose", "fread", "fwrite", "fseek", "ftell", "rewind", "fflush",
                "feof", "ferror", "perror", "remove", "rename", "getline", "FILE",
                "stdin", "stdout", "stderr", "EOF"}) {
            m.put(s, "stdio.h");
        }
        // <stdlib.h>
        for (String s : new String[]{"malloc", "calloc", "realloc", "free", "exit", "abort",
                "atoi", "atol", "atoll", "atof", "strtol", "strtoul", "strtod", "abs", "labs",
                "rand", "srand", "qsort", "bsearch", "getenv", "setenv", "system",
                "EXIT_SUCCESS", "EXIT_FAILURE", "RAND_MAX"}) {
            m.put(s, "stdlib.h");
        }
        // <string.h>
        for (String s : new String[]{"strlen", "strcpy", "strncpy", "strcat", "strncat",
                "strcmp", "strncmp", "strchr", "strrchr", "strstr", "strtok", "strdup",
                "strerror", "memcpy", "memmove", "memset", "memcmp", "memchr"}) {
            m.put(s, "string.h");
        }
        // <math.h>
        for (String s : new String[]{"sqrt", "cbrt", "pow", "exp", "log", "log2", "log10",
                "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sinh", "cosh", "tanh",
                "floor", "ceil", "round", "trunc", "fabs", "fmod", "hypot", "M_PI", "M_E", "NAN", "INFINITY"}) {
            m.put(s, "math.h");
        }
        // <ctype.h>
        for (String s : new String[]{"isalpha", "isdigit", "isalnum", "isspace", "isupper",
                "islower", "ispunct", "iscntrl", "isprint", "isgraph", "isxdigit", "toupper", "tolower"}) {
            m.put(s, "ctype.h");
        }
        // <time.h>
        for (String s : new String[]{"time", "clock", "difftime", "mktime", "localtime",
                "gmtime", "asctime", "ctime", "strftime", "time_t", "clock_t", "CLOCKS_PER_SEC"}) {
            m.put(s, "time.h");
        }
        // <stdbool.h>
        for (String s : new String[]{"bool", "true", "false"}) m.put(s, "stdbool.h");
        // <stddef.h>
        for (String s : new String[]{"size_t", "ptrdiff_t", "wchar_t", "offsetof", "NULL"}) {
            m.put(s, "stddef.h");
        }
        // <stdint.h>
        for (String s : new String[]{"int8_t", "uint8_t", "int16_t", "uint16_t", "int32_t",
                "uint32_t", "int64_t", "uint64_t", "intptr_t", "uintptr_t", "intmax_t", "uintmax_t"}) {
            m.put(s, "stdint.h");
        }
        // <assert.h> / <errno.h>
        m.put("assert", "assert.h");
        m.put("errno", "errno.h");
        // POSIX <unistd.h>
        for (String s : new String[]{"read", "write", "close", "unlink", "fork", "getpid",
                "sleep", "usleep", "pipe", "dup", "dup2", "access", "chdir", "getcwd"}) {
            m.put(s, "unistd.h");
        }
        return Map.copyOf(m);
    }

    // #include <foo.h> / #include "foo.h"
    private static final Pattern INCLUDE_PATTERN =
        Pattern.compile("^\\s*#\\s*include\\s+[<\"]([^>\"]+)[>\"]", Pattern.MULTILINE);

    // gcc/clang の未定義シンボル系メッセージからシンボル名を取り出す。
    private static final Pattern[] SYMBOL_MESSAGE_PATTERNS = {
        Pattern.compile("implicit declaration of function '([A-Za-z_][A-Za-z0-9_]*)'"),
        Pattern.compile("unknown type name '([A-Za-z_][A-Za-z0-9_]*)'"),
        Pattern.compile("'([A-Za-z_][A-Za-z0-9_]*)' undeclared"),
        Pattern.compile("use of undeclared identifier '([A-Za-z_][A-Za-z0-9_]*)'"),
        Pattern.compile("unknown type name \"([A-Za-z_][A-Za-z0-9_]*)\""),
    };

    /** そのシンボルに対応する標準ヘッダ（無ければ null）。 */
    public static String headerFor(String symbol) {
        return SYMBOL_TO_HEADER.get(symbol);
    }

    /** gcc/clang の診断メッセージ1件から未定義シンボル名を抽出する（該当しなければ null）。 */
    public static String extractSymbolFromMessage(String message) {
        if (message == null) return null;
        for (Pattern p : SYMBOL_MESSAGE_PATTERNS) {
            Matcher m = p.matcher(message);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** ソース中に既に現れている {@code #include} のヘッダ名（{@code <>}/{@code ""} 両方）の集合。 */
    public static Set<String> existingIncludes(String source) {
        Set<String> result = new LinkedHashSet<>();
        Matcher m = INCLUDE_PATTERN.matcher(source);
        while (m.find()) result.add(m.group(1));
        return result;
    }

    /**
     * ソース中に現れる既知の標準シンボルに対応するヘッダのうち、まだ include していないものを
     * アルファベット順で返す（{@code :oi} / {@code SPC+i+o} 相当の明示整理用）。
     */
    public static List<String> missingHeadersForSource(String source) {
        Set<String> already = existingIncludes(source);
        Set<String> needed = new TreeSet<>();
        for (String sym : usedKnownSymbols(source)) {
            String h = SYMBOL_TO_HEADER.get(sym);
            if (h != null && !already.contains(h)) needed.add(h);
        }
        return new ArrayList<>(needed);
    }

    /**
     * 与えられたシンボル集合（診断から抽出したもの等）に対応するヘッダのうち、まだ include して
     * いないものをアルファベット順で返す（診断ベースの自動挿入用）。
     */
    public static List<String> missingHeadersForSymbols(String source, Collection<String> symbols) {
        Set<String> already = existingIncludes(source);
        Set<String> needed = new TreeSet<>();
        for (String sym : symbols) {
            String h = SYMBOL_TO_HEADER.get(sym);
            if (h != null && !already.contains(h)) needed.add(h);
        }
        return new ArrayList<>(needed);
    }

    /** ソース中に現れる既知の標準シンボル（語境界一致）の集合。 */
    public static Set<String> usedKnownSymbols(String source) {
        Set<String> result = new LinkedHashSet<>();
        Matcher m = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(source);
        while (m.find()) {
            String token = m.group();
            if (SYMBOL_TO_HEADER.containsKey(token)) result.add(token);
        }
        return result;
    }

    /**
     * headers（{@code <>} 形式の標準ヘッダ名）をソースへ挿入した新しいソース文字列を返す。
     * 既存の最後の {@code #include} 行の直後に挿入する。既存の include が無ければ、先頭の
     * ブロック/行コメント群の直後（無ければ先頭）に挿入する。headers が空なら source をそのまま返す。
     * 追加行同士はアルファベット順に整列させる。
     */
    public static String addIncludes(String source, List<String> headers) {
        if (headers.isEmpty()) return source;
        int offset = insertOffset(source);
        String block = formatIncludeBlock(headers);
        return source.substring(0, offset) + block + source.substring(offset);
    }

    /**
     * headers を1行1件の {@code #include <...>\n} ブロックに整形する（アルファベット順・重複除去）。
     * headers が空なら空文字列。
     */
    public static String formatIncludeBlock(List<String> headers) {
        if (headers.isEmpty()) return "";
        StringBuilder block = new StringBuilder();
        for (String h : new TreeSet<>(headers)) {
            block.append("#include <").append(h).append(">\n");
        }
        return block.toString();
    }

    /**
     * {@link #formatIncludeBlock} で作ったブロックを挿入すべき文字オフセット。
     * 既存の最後の {@code #include} 行の直後（無ければ先頭のコメント群の直後、それも無ければ先頭）。
     */
    public static int insertOffset(String source) {
        String[] lines = source.split("\n", -1);
        int insertLine = lastIncludeLine(lines);
        insertLine = (insertLine >= 0) ? insertLine + 1 : firstCodeLineAfterLeadingComments(lines);
        // 行番号 → 文字オフセット
        int offset = 0;
        for (int i = 0; i < insertLine && i < lines.length; i++) {
            offset += lines[i].length() + 1; // +1 は '\n'
        }
        return Math.min(offset, source.length());
    }

    private static int lastIncludeLine(String[] lines) {
        int last = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().toLowerCase(Locale.ROOT).matches("#\\s*include\\s+[<\"].*")) {
                last = i;
            }
        }
        return last;
    }

    /** 先頭の空行・行コメント・ブロックコメントを飛ばした最初のコード行の行番号。 */
    private static int firstCodeLineAfterLeadingComments(String[] lines) {
        int i = 0;
        boolean inBlock = false;
        for (; i < lines.length; i++) {
            String t = lines[i].trim();
            if (inBlock) {
                if (t.contains("*/")) inBlock = false;
                continue;
            }
            if (t.isEmpty() || t.startsWith("//")) continue;
            if (t.startsWith("/*")) {
                if (!t.contains("*/")) inBlock = true;
                continue;
            }
            break;
        }
        return i;
    }
}
