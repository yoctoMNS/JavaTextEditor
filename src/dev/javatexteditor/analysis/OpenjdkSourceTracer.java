package dev.javatexteditor.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * native メソッドの JNI 実装を OpenJDK ソース（src.zip）から探索する。
 */
public class OpenjdkSourceTracer {

    public record TracingResult(
        boolean isNative,
        String jniMangledName,
        Optional<String> sourceFile,
        Optional<String> snippet
    ) {
        public String toStatusLine() {
            if (!isNative) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("[native] ").append(jniMangledName);
            if (sourceFile.isPresent()) {
                sb.append(" → ").append(sourceFile.get());
            } else {
                sb.append(" (no JDK source available)");
            }
            return sb.toString();
        }
    }

    private final Optional<Path> srcZip;
    private final Optional<Path> nativeSrcDir; // lib/openjdk-native/

    public OpenjdkSourceTracer() {
        this.srcZip = findSrcZip();
        this.nativeSrcDir = findNativeSrcDir();
    }

    /** テスト用コンストラクタ: src.zip パスを直接指定。 */
    public OpenjdkSourceTracer(Path srcZipPath) {
        this.srcZip = (srcZipPath != null && Files.exists(srcZipPath))
            ? Optional.of(srcZipPath)
            : Optional.empty();
        this.nativeSrcDir = findNativeSrcDir();
    }

    /** テスト用コンストラクタ: src.zip と native ソースディレクトリの両方を直接指定。 */
    public OpenjdkSourceTracer(Path srcZipPath, Path nativeSrcDirOverride) {
        this.srcZip = (srcZipPath != null && Files.exists(srcZipPath))
            ? Optional.of(srcZipPath)
            : Optional.empty();
        this.nativeSrcDir = (nativeSrcDirOverride != null && Files.isDirectory(nativeSrcDirOverride))
            ? Optional.of(nativeSrcDirOverride)
            : Optional.empty();
    }

    /** src.zip が利用可能かどうかを返す。 */
    public boolean hasSrcZip() {
        return srcZip.isPresent();
    }

    /**
     * src.zip から指定クラスの Java ソースを取り出して返す。
     * 見つからない場合は Optional.empty()。
     */
    public Optional<String> readJavaSource(Class<?> cls) {
        if (srcZip.isEmpty()) return Optional.empty();
        try (ZipFile zip = new ZipFile(srcZip.get().toFile())) {
            return findJavaSource(zip, cls);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** lib/openjdk-native/ が利用可能かどうかを返す。 */
    public boolean hasNativeSrcDir() {
        return nativeSrcDir.isPresent();
    }

    /** lib/openjdk-native/ のパスを返す（grep 検索の起点に使う）。 */
    public Optional<Path> getNativeSrcDir() {
        return nativeSrcDir;
    }

    /**
     * C/C++ シンボル（関数名・マクロ名）の定義箇所を native ソースから検索する。
     * 定義行とは「行頭付近でシンボル名に '(' が続く行」と判定する。
     * 見つかった場合は CSymbolLocation を返す。見つからなければ empty。
     */
    public Optional<CSymbolLocation> findCSymbol(String symbol) {
        if (nativeSrcDir.isEmpty() || symbol.isBlank()) return Optional.empty();
        try {
            return Files.walk(nativeSrcDir.get())
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".h") || n.endsWith(".hpp");
                })
                .flatMap(p -> {
                    try {
                        String content = Files.readString(p, StandardCharsets.UTF_8);
                        int lineNo = findDefinitionLine(content, symbol);
                        if (lineNo >= 0) {
                            String rel = nativeSrcDir.get().getParent()
                                .relativize(p).toString().replace('\\', '/');
                            return java.util.stream.Stream.of(
                                new CSymbolLocation(rel, p, lineNo, extractLines(content, lineNo, 8)));
                        }
                    } catch (IOException ignored) {}
                    return java.util.stream.Stream.empty();
                })
                .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** C シンボルの定義場所を表すレコード。 */
    public record CSymbolLocation(
        String relativePath,   // openjdk-native/src/... 形式の相対パス
        Path absolutePath,     // フルファイルを開くための絶対パス
        int lineNumber,        // 0-indexed 定義行
        String snippet         // 定義前後数行のスニペット
    ) {}

    /**
     * ソーステキストから C 関数定義行を探す（0-indexed 行番号を返す）。
     * 定義行の条件:
     *   - symbol に単語境界で一致し、直後（空白を挟んでもよい）に "(" が続く
     *     （例: "argc(" のように symbol が他の識別子の部分文字列であるケースを誤マッチしない）
     *   - 行頭が空白でない（インデントされた関数呼び出しを除外）
     *   - "//" や "*" で始まるコメント行でない
     * 見つからなければ -1。
     */
    private static int findDefinitionLine(String content, String symbol) {
        String[] lines = content.split("\n", -1);
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
            "\\b" + java.util.regex.Pattern.quote(symbol) + "\\s*\\(");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!pat.matcher(line).find()) continue;
            String trimmed = line.stripLeading();
            // コメント行を除外
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
            // 行頭から始まる定義（returnType や JNIEXPORT など）を優先
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) return i;
        }
        // 行頭条件を緩めてもう一度探す（マクロや static inline など）
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            java.util.regex.Matcher m = pat.matcher(line);
            if (!m.find()) continue;
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
            // = が直前にない（代入や関数ポインタ呼び出しを除外）
            int idx = m.start();
            if (idx > 0 && line.charAt(idx - 1) == '=') continue;
            return i;
        }
        return -1;
    }

    /** content の lineNo 行目を中心に前後 context 行を取り出す。 */
    private static String extractLines(String content, int lineNo, int context) {
        String[] lines = content.split("\n", -1);
        int from = Math.max(0, lineNo);
        int to   = Math.min(lines.length, lineNo + context);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) sb.append(lines[i]).append('\n');
        return sb.toString().stripTrailing();
    }

    /**
     * 指定クラスの指定メソッドが native かどうか調べ、native なら JNI 情報を返す。
     * native でなければ isNative=false の結果を返す。
     * lib/openjdk-native/ があればそちらを優先、なければ src.zip にフォールバック。
     */
    public TracingResult trace(Class<?> cls, String methodName) {
        Method nativeMethod = findNativeMethod(cls, methodName);
        if (nativeMethod == null) {
            return new TracingResult(false, "", Optional.empty(), Optional.empty());
        }
        String jniName = computeJniName(cls, methodName);
        // lib/openjdk-native/ を優先検索
        if (nativeSrcDir.isPresent()) {
            TracingResult r = searchInNativeSrcDir(jniName, cls, methodName);
            if (r.sourceFile().isPresent()) return r;
        }
        // フォールバック: src.zip 内を検索
        if (srcZip.isPresent()) {
            return searchInSrcZip(jniName, cls, methodName, nativeMethod);
        }
        return new TracingResult(true, jniName, Optional.empty(), Optional.empty());
    }

    /**
     * クラスに native メソッドが1つでもあるか調べる（クラスレベルの判定）。
     */
    public boolean hasNativeMethod(Class<?> cls) {
        try {
            for (Method m : cls.getMethods()) {
                if (Modifier.isNative(m.getModifiers())) return true;
            }
            for (Method m : cls.getDeclaredMethods()) {
                if (Modifier.isNative(m.getModifiers())) return true;
            }
        } catch (Exception | Error ignored) {}
        return false;
    }

    /**
     * JNI マングル名を計算する。
     * 規則: Java_<package_with_underscores>_<ClassName>_<methodName>
     * アンダースコアは "_1" にエスケープ、パッケージ区切り "." は "_"。
     */
    public String computeJniName(Class<?> cls, String methodName) {
        String pkg = cls.getPackageName().replace("_", "_1").replace('.', '_');
        String simpleName = cls.getSimpleName().replace("_", "_1");
        String mName = methodName.replace("_", "_1");
        if (pkg.isEmpty()) {
            return "Java_" + simpleName + "_" + mName;
        }
        return "Java_" + pkg + "_" + simpleName + "_" + mName;
    }

    /**
     * lib/openjdk-native/ ディレクトリから JNI 関数名を含む C/C++ ファイルを検索。
     * ファイルシステムを直接 walk するので src.zip より高速で確実。
     */
    private TracingResult searchInNativeSrcDir(String jniName, Class<?> cls, String methodName) {
        try {
            List<CandidateMatch> candidates = new ArrayList<>();
            Files.walk(nativeSrcDir.get())
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith(".c") || n.endsWith(".cpp");
                })
                .forEach(p -> {
                    try {
                        String content = Files.readString(p, StandardCharsets.UTF_8);
                        if (content.contains(jniName)) {
                            String relPath = nativeSrcDir.get().getParent().relativize(p).toString()
                                .replace('\\', '/');
                            String snippet = extractSnippet(content, jniName);
                            candidates.add(new CandidateMatch(relPath, snippet));
                        }
                    } catch (IOException ignored) {}
                });
            if (!candidates.isEmpty()) {
                CandidateMatch best = candidates.get(0);
                return new TracingResult(true, jniName,
                    Optional.of(best.path()),
                    best.snippet().isEmpty() ? Optional.empty() : Optional.of(best.snippet()));
            }
        } catch (IOException ignored) {}
        return new TracingResult(true, jniName, Optional.empty(), Optional.empty());
    }

    /** src.zip から JNI 関数名を含む C/C++ ソースを検索。 */
    private TracingResult searchInSrcZip(String jniName, Class<?> cls, String methodName, Method m) {
        try (ZipFile zip = new ZipFile(srcZip.get().toFile())) {
            // まず Java ソースでメソッドが native であることを確認済みなのでスキップ
            // C/C++ ファイル (.c, .cpp) を探す
            List<CandidateMatch> candidates = new ArrayList<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".c") && !name.endsWith(".cpp")) continue;
                String content = readEntry(zip, entry);
                if (content.contains(jniName)) {
                    String snippet = extractSnippet(content, jniName);
                    candidates.add(new CandidateMatch(name, snippet));
                }
            }
            if (candidates.isEmpty()) {
                // C/C++ ファイルが見つからなかった場合、Javaソースで native 宣言の行を探す
                Optional<String> javaSource = findJavaSource(zip, cls);
                if (javaSource.isPresent()) {
                    String snippet = extractNativeDeclaration(javaSource.get(), methodName);
                    String javaPath = cls.getName().replace('.', '/') + ".java";
                    return new TracingResult(true, jniName, Optional.of(javaPath + " (Java declaration)"),
                                            snippet.isEmpty() ? Optional.empty() : Optional.of(snippet));
                }
                return new TracingResult(true, jniName, Optional.empty(), Optional.empty());
            }
            CandidateMatch best = candidates.get(0);
            return new TracingResult(true, jniName, Optional.of(best.path()),
                                     best.snippet().isEmpty() ? Optional.empty() : Optional.of(best.snippet()));
        } catch (IOException e) {
            return new TracingResult(true, jniName, Optional.empty(), Optional.empty());
        }
    }

    private record CandidateMatch(String path, String snippet) {}

    private Optional<String> findJavaSource(ZipFile zip, Class<?> cls) {
        // src.zip には module/pkg/Class.java 形式で格納されている場合がある
        String path = cls.getName().replace('.', '/') + ".java";
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            // モジュール名プレフィックス付きを試みる
            String moduleName = cls.getModule().getName();
            if (moduleName != null) {
                entry = zip.getEntry(moduleName + "/" + path);
            }
        }
        if (entry == null) return Optional.empty();
        return Optional.of(readEntry(zip, entry));
    }

    private String extractSnippet(String content, String jniName) {
        int idx = content.indexOf(jniName);
        if (idx < 0) return "";
        int lineStart = content.lastIndexOf('\n', idx) + 1;
        int end = Math.min(content.length(), idx + 200);
        int lineEnd = content.indexOf('\n', end);
        if (lineEnd < 0) lineEnd = end;
        return content.substring(lineStart, Math.min(lineEnd, lineStart + 300)).trim();
    }

    private String extractNativeDeclaration(String source, String methodName) {
        String[] lines = source.split("\n");
        for (String line : lines) {
            if (line.contains("native") && line.contains(methodName + "(")) {
                return line.trim();
            }
        }
        return "";
    }

    private String readEntry(ZipFile zip, ZipEntry entry) {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private Method findNativeMethod(Class<?> cls, String methodName) {
        try {
            for (Method m : cls.getMethods()) {
                if (m.getName().equals(methodName) && Modifier.isNative(m.getModifiers())) {
                    return m;
                }
            }
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && Modifier.isNative(m.getModifiers())) {
                    return m;
                }
            }
        } catch (Exception | Error ignored) {}
        return null;
    }

    /** プロジェクト同梱 src.zip または JDK インストール内の src.zip を探す。 */
    private static Optional<Path> findSrcZip() {
        // 1. プロジェクト同梱 lib/src.zip を最優先（scripts/setup.sh で配置）
        Optional<Path> bundled = findBundledSrcZip();
        if (bundled.isPresent()) return bundled;

        // 2. java.home プロパティから辿る
        String javaHome = System.getProperty("java.home", "");
        if (!javaHome.isEmpty()) {
            Path home = Paths.get(javaHome);
            Path[] candidates = {
                home.resolve("lib/src.zip"),
                home.resolve("src.zip"),
                home.getParent() != null ? home.getParent().resolve("lib/src.zip") : home.resolve("lib/src.zip"),
                home.getParent() != null && home.getParent().getParent() != null
                    ? home.getParent().getParent().resolve("lib/src.zip")
                    : home.resolve("lib/src.zip"),
            };
            for (Path p : candidates) {
                if (Files.exists(p) && !Files.isSymbolicLink(p)) return Optional.of(p);
            }
            // シンボリックリンクでも中身があれば使う
            for (Path p : candidates) {
                if (Files.exists(p)) return Optional.of(p);
            }
        }

        // 3. JAVA_HOME 環境変数
        String envJavaHome = System.getenv("JAVA_HOME");
        if (envJavaHome != null && !envJavaHome.isEmpty()) {
            Path p = Paths.get(envJavaHome, "lib", "src.zip");
            if (Files.exists(p)) return Optional.of(p);
        }

        return Optional.empty();
    }

    /** lib/openjdk-native/ ディレクトリを探す（findBundledSrcZip と同じ探索ロジック）。 */
    private static Optional<Path> findNativeSrcDir() {
        try {
            var url = OpenjdkSourceTracer.class.getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                Path codeLocation = Paths.get(url.toURI());
                Path dir = Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent();
                for (int i = 0; i < 4; i++) {
                    if (dir == null) break;
                    Path candidate = dir.resolve("lib/openjdk-native");
                    if (Files.isDirectory(candidate)) return Optional.of(candidate);
                    dir = dir.getParent();
                }
            }
        } catch (Exception ignored) {}
        Path fromCwd = Paths.get("lib", "openjdk-native");
        if (Files.isDirectory(fromCwd)) return Optional.of(fromCwd.toAbsolutePath());
        return Optional.empty();
    }

    /**
     * クラスファイルの場所から遡って lib/src.zip を探す。
     * 実行形態（クラスパス直接 / jar）に関わらず動作する。
     */
    private static Optional<Path> findBundledSrcZip() {
        try {
            // クラスファイルの URL から場所を特定
            var url = OpenjdkSourceTracer.class.getProtectionDomain().getCodeSource().getLocation();
            if (url == null) return Optional.empty();
            Path codeLocation = Paths.get(url.toURI());
            // build/ や .jar が起点 → 親を遡って lib/src.zip を探す
            Path dir = Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent();
            for (int i = 0; i < 4; i++) {
                if (dir == null) break;
                Path candidate = dir.resolve("lib/src.zip");
                if (Files.exists(candidate)) return Optional.of(candidate);
                dir = dir.getParent();
            }
        } catch (Exception ignored) {}
        // カレントディレクトリからも試す
        Path fromCwd = Paths.get("lib", "src.zip");
        if (Files.exists(fromCwd)) return Optional.of(fromCwd.toAbsolutePath());
        return Optional.empty();
    }
}
