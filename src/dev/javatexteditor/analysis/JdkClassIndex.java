package dev.javatexteditor.analysis;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        Map<String, Set<String>> exportedPackagesByModule = loadExportedPackagesByModule();
        try {
            FileSystem jrtFs = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path modulesRoot = jrtFs.getPath("/modules");
            try (Stream<Path> paths = Files.walk(modulesRoot)) {
                paths.filter(p -> p.toString().endsWith(".class"))
                     .forEach(p -> {
                         String pathStr = p.toString();
                         String fqn = pathToFqn(pathStr);
                         if (fqn != null
                                 && isExportedPackage(moduleName(pathStr), packageName(fqn), exportedPackagesByModule)) {
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

    /**
     * 各モジュールが無条件（非qualified）にエクスポートしているパッケージ集合を、
     * モジュール名をキーとして返す。{@code ModuleFinder.ofSystem()} はランタイムイメージに
     * 実在する全モジュールの {@code module-info} を返すため、{@code ModuleLayer.boot()} と異なり
     * 現在のJVM起動時に解決されなかった任意モジュール（例: jdk.hotspot.agent。jrt:/には
     * クラスファイルが存在するがboot layerには含まれない）も正しく判定できる。
     */
    private static Map<String, Set<String>> loadExportedPackagesByModule() {
        Map<String, Set<String>> result = new HashMap<>();
        try {
            for (ModuleReference ref : ModuleFinder.ofSystem().findAll()) {
                ModuleDescriptor descriptor = ref.descriptor();
                Set<String> exported = new HashSet<>();
                for (ModuleDescriptor.Exports e : descriptor.exports()) {
                    if (!e.isQualified()) exported.add(e.source());
                }
                result.put(descriptor.name(), exported);
            }
        } catch (RuntimeException e) {
            // 取得できない環境では空のまま（isExportedPackage は未知モジュール扱いで除外しない）
        }
        return result;
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

    /** "/modules/java.base/java/util/List.class" → "java.base" */
    private static String moduleName(String pathStr) {
        int modulesIdx = pathStr.indexOf("/modules/");
        if (modulesIdx < 0) return null;
        String afterModules = pathStr.substring(modulesIdx + "/modules/".length());
        int firstSlash = afterModules.indexOf('/');
        return firstSlash < 0 ? null : afterModules.substring(0, firstSlash);
    }

    /**
     * 指定モジュールの指定パッケージが、そのモジュールの外へ無条件にエクスポートされているか
     * どうかを判定する。{@code com.sun.org.apache.xpath.internal.operations} のような
     * 非公開の内部パッケージ（java.xmlモジュールでpublicなクラスとして実装されていても、
     * モジュールがエクスポートしていないため通常のコードからはimportできない）を索引から
     * 除外するために使用する。qualified export（特定モジュールにのみ公開）は、このエディタが
     * 生成する一般的なユーザーコード（unnamed module）からは利用できないため対象外とする。
     */
    private static boolean isExportedPackage(String moduleName, String pkg, Map<String, Set<String>> exportedPackagesByModule) {
        if (moduleName == null) return true; // 判定不能な場合は除外しない（安全側）
        Set<String> exported = exportedPackagesByModule.get(moduleName);
        if (exported == null) return true; // モジュール情報が取得できない場合は除外しない（安全側）
        return pkg.isEmpty() || exported.contains(pkg);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    /** "java.util.List" → "java.util"、パッケージなしの場合は "" */
    private static String packageName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(0, dot) : "";
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

    /**
     * インデックスに登録されている全クラス単純名のセットを返す。
     * インデックス未完了の場合は空セットを返す。
     * CompletionIndex が JDK クラス名を取得するために使用する。
     */
    public java.util.Set<String> allSimpleNames() {
        if (!ready.get()) return java.util.Set.of();
        return java.util.Collections.unmodifiableSet(simpleNameToFqns.keySet());
    }
}
