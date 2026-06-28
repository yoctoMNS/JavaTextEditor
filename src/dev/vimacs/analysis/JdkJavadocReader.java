package dev.vimacs.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ローカル JDK 付属 Javadoc HTML からクラスのサマリ文を読み取る。
 *
 * 検索順:
 *  1. システムプロパティ vimacs.javadoc.path で指定されたディレクトリ
 *  2. $JAVA_HOME/../docs/api/
 *  3. /usr/share/doc/openjdk-<version>-doc/api/ （Debian/Ubuntu系）
 *
 * いずれも存在しない場合は Optional.empty() を返す（graceful degradation）。
 */
public class JdkJavadocReader {

    /** "java.util.ArrayList" → first sentence of the class description (or empty) */
    private final Map<String, Optional<String>> cache = new HashMap<>();

    private final Path apiRoot;  // null if no Javadoc found

    // <div class="block">...</div> の最初の1文を抜き出す正規表現
    // Modern Javadoc (JDK 17+) のHTML構造に対応
    private static final Pattern BLOCK_PAT =
        Pattern.compile("<div[^>]*class=\"block\"[^>]*>(.*?)</div>",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG_PAT = Pattern.compile("<[^>]+>");

    public JdkJavadocReader() {
        this.apiRoot = findApiRoot();
    }

    /** Javadoc が見つかったかどうか */
    public boolean isAvailable() {
        return apiRoot != null;
    }

    /**
     * FQN のクラスの Javadoc サマリ文を返す。
     * Javadoc が存在しない、または対象クラスが見つからない場合は Optional.empty()。
     */
    public Optional<String> readSummary(String fqn) {
        if (apiRoot == null) return Optional.empty();
        return cache.computeIfAbsent(fqn, this::loadSummary);
    }

    private Optional<String> loadSummary(String fqn) {
        // "java.util.ArrayList" → "java/util/ArrayList.html"
        String relativePath = fqn.replace('.', '/') + ".html";
        Path htmlFile = apiRoot.resolve(relativePath);
        if (!Files.exists(htmlFile)) return Optional.empty();

        try {
            String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
            return extractFirstSentence(html);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> extractFirstSentence(String html) {
        Matcher m = BLOCK_PAT.matcher(html);
        if (!m.find()) return Optional.empty();

        String content = m.group(1);
        // HTMLタグを除去し、エンティティを簡易変換
        String text = TAG_PAT.matcher(content).replaceAll("").trim();
        text = decodeEntities(text);

        if (text.isEmpty()) return Optional.empty();

        // 最初の文（ピリオド＋空白で区切る）
        int dot = indexOfSentenceEnd(text);
        String first = dot >= 0 ? text.substring(0, dot + 1).trim() : text;
        // 改行・連続空白を正規化
        first = first.replaceAll("\\s+", " ").trim();
        return first.isEmpty() ? Optional.empty() : Optional.of(first);
    }

    private static int indexOfSentenceEnd(String text) {
        for (int i = 0; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                char next = text.charAt(i + 1);
                if (next == ' ' || next == '\n' || next == '\r') return i;
            }
        }
        // 最後の文末記号
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') return i;
        }
        return -1;
    }

    private static String decodeEntities(String s) {
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }

    private static Path findApiRoot() {
        // 1. システムプロパティによる明示指定
        String prop = System.getProperty("vimacs.javadoc.path");
        if (prop != null) {
            Path p = Path.of(prop);
            if (Files.isDirectory(p)) return p;
        }

        // 2. $JAVA_HOME に基づく標準パス
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            // JDK では $JAVA_HOME/docs/api/ が存在することがある
            Path candidate = Path.of(javaHome, "docs", "api");
            if (Files.isDirectory(candidate)) return candidate;
            // $JAVA_HOME/../docs/api/ (旧レイアウト)
            candidate = Path.of(javaHome, "..", "docs", "api");
            if (Files.isDirectory(candidate)) return candidate;
        }

        // 3. Debian/Ubuntu 系: openjdk-<N>-doc パッケージ
        String version = Runtime.version().feature() + "";  // "21"
        Path debian = Path.of("/usr/share/doc/openjdk-" + version + "-doc/api");
        if (Files.isDirectory(debian)) return debian;

        return null;
    }
}
