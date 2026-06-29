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

    public OpenjdkSourceTracer() {
        this.srcZip = findSrcZip();
    }

    /** テスト用コンストラクタ: src.zip パスを直接指定。 */
    public OpenjdkSourceTracer(Path srcZipPath) {
        this.srcZip = (srcZipPath != null && Files.exists(srcZipPath))
            ? Optional.of(srcZipPath)
            : Optional.empty();
    }

    /**
     * 指定クラスの指定メソッドが native かどうか調べ、native なら JNI 情報を返す。
     * native でなければ isNative=false の結果を返す。
     */
    public TracingResult trace(Class<?> cls, String methodName) {
        Method nativeMethod = findNativeMethod(cls, methodName);
        if (nativeMethod == null) {
            return new TracingResult(false, "", Optional.empty(), Optional.empty());
        }
        String jniName = computeJniName(cls, methodName);
        if (srcZip.isEmpty()) {
            return new TracingResult(true, jniName, Optional.empty(), Optional.empty());
        }
        return searchInSrcZip(jniName, cls, methodName, nativeMethod);
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
