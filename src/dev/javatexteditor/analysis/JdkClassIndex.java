package dev.javatexteditor.analysis;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * jrt:/ FileSystem を走査して クラス単純名 → FQN 候補リスト のインデックスを構築する。
 * インデックス構築はバックグラウンドスレッドで行い、完了前に lookup() を呼んだ場合は
 * 空リストを返す（isReady() で確認可能）。
 */
public class JdkClassIndex {

    private final Map<String, List<String>> simpleNameToFqns = new HashMap<>();
    private final AtomicBoolean ready = new AtomicBoolean(false);

    private JdkClassIndex() {}

    /**
     * バックグラウンドスレッドでインデックスを構築し、構築中のインスタンスを即座に返す。
     */
    public static JdkClassIndex build() {
        JdkClassIndex index = new JdkClassIndex();
        Thread.ofVirtual().start(() -> index.buildIndex());
        return index;
    }

    /**
     * 同期的にインデックスを構築して返す（テスト用）。
     */
    public static JdkClassIndex buildSync() {
        JdkClassIndex index = new JdkClassIndex();
        index.buildIndex();
        return index;
    }

    private void buildIndex() {
        try {
            FileSystem jrtFs = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path modulesRoot = jrtFs.getPath("/modules");
            try (Stream<Path> paths = Files.walk(modulesRoot)) {
                paths.filter(p -> p.toString().endsWith(".class"))
                     .forEach(p -> {
                         String fqn = pathToFqn(p.toString());
                         if (fqn != null) {
                             String simpleName = simpleName(fqn);
                             simpleNameToFqns.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(fqn);
                         }
                     });
            }
        } catch (IOException e) {
            // jrt:/ が使えない環境（JRE のみ等）では空のインデックスのまま
        }
        ready.set(true);
    }

    /** "/modules/java.base/java/util/List.class" → "java.util.List" */
    private static String pathToFqn(String pathStr) {
        // /modules/<module>/<pkg/...>/<Name>.class
        int modulesIdx = pathStr.indexOf("/modules/");
        if (modulesIdx < 0) return null;
        String afterModules = pathStr.substring(modulesIdx + "/modules/".length());
        int firstSlash = afterModules.indexOf('/');
        if (firstSlash < 0) return null;
        String classPath = afterModules.substring(firstSlash + 1); // e.g. "java/util/List.class"
        if (!classPath.endsWith(".class")) return null;
        String withoutExt = classPath.substring(0, classPath.length() - ".class".length());
        // 匿名クラス・内部クラス（$）はスキップ
        if (withoutExt.contains("$")) return null;
        return withoutExt.replace('/', '.');
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    /** インデックス構築が完了しているかどうか。 */
    public boolean isReady() {
        return ready.get();
    }

    /**
     * インデックス構築が完了するまでブロックする。
     * 仮想スレッドから呼ぶことを想定しており、EDT からは呼ばないこと。
     */
    public void awaitReady() throws InterruptedException {
        while (!ready.get()) {
            Thread.sleep(20);
        }
    }

    /**
     * 単純名（例: "List"）から FQN 候補リストを返す。
     * インデックス未完了の場合は空リストを返す。
     */
    public List<String> lookup(String simpleName) {
        if (!ready.get()) return Collections.emptyList();
        List<String> result = simpleNameToFqns.get(simpleName);
        return result != null ? Collections.unmodifiableList(result) : Collections.emptyList();
    }

    /**
     * FQN から Class<?> をロードする。
     * ロードできない場合（モジュールが封印されている等）は空を返す。
     */
    public Optional<Class<?>> loadClass(String fqn) {
        try {
            return Optional.of(Class.forName(fqn));
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** インデックスに登録された FQN の総数（テスト・デバッグ用）。 */
    public int totalClassCount() {
        return simpleNameToFqns.values().stream().mapToInt(List::size).sum();
    }
}
