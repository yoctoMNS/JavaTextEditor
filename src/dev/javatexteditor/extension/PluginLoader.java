package dev.javatexteditor.extension;

import javax.tools.*;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Java ソースファイルを javax.tools.JavaCompiler で動的にコンパイルし、
 * URLClassLoader でロードするプラグイン管理クラス。
 *
 * 各プラグインは独立した URLClassLoader を持つ。
 * 再ロード時は旧 ClassLoader を close() して新しく作り直す。
 */
public class PluginLoader {

    private record LoadedPlugin(EditorPlugin plugin, URLClassLoader loader, Path classDir) {}

    private final Map<String, LoadedPlugin> loaded = new HashMap<>();
    private EditorContext defaultContext;

    public PluginLoader() {}

    public PluginLoader(EditorContext defaultContext) {
        this.defaultContext = defaultContext;
    }

    /**
     * @param sourceFile  .java ファイルのパス（ファイル名がクラス名になる）
     * @return ロードされたプラグインインスタンス
     */
    public EditorPlugin loadPlugin(Path sourceFile) throws PluginLoadException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new PluginLoadException(
                "JavaCompiler が見つかりません。JDK (JRE ではなく) で実行してください。");
        }

        String fileName = sourceFile.getFileName().toString();
        if (!fileName.endsWith(".java")) {
            throw new PluginLoadException("ソースファイルの拡張子が .java ではありません: " + fileName);
        }
        String className = fileName.substring(0, fileName.length() - 5);

        // 既にロード済みなら先にアンロード
        if (loaded.containsKey(className)) {
            unloadPlugin(className);
        }

        Path classDir;
        try {
            classDir = Files.createTempDirectory("jte-plugin-" + className + "-");
        } catch (IOException e) {
            throw new PluginLoadException("一時ディレクトリの作成に失敗しました", e);
        }

        // コンパイル
        java.io.ByteArrayOutputStream errStream = new java.io.ByteArrayOutputStream();
        String[] compileArgs = {
            "-classpath", System.getProperty("java.class.path"),
            "-d", classDir.toString(),
            sourceFile.toAbsolutePath().toString()
        };
        int result = compiler.run(null, null, errStream, compileArgs);
        if (result != 0) {
            deleteDirQuietly(classDir);
            throw new PluginLoadException("コンパイルエラー:\n" + errStream.toString().trim());
        }

        // ロード
        URLClassLoader loader;
        try {
            loader = new URLClassLoader(
                new URL[]{ classDir.toUri().toURL() },
                Thread.currentThread().getContextClassLoader()
            );
        } catch (Exception e) {
            deleteDirQuietly(classDir);
            throw new PluginLoadException("ClassLoader の作成に失敗しました", e);
        }

        EditorPlugin plugin;
        try {
            Class<?> clazz = Class.forName(className, true, loader);
            if (!EditorPlugin.class.isAssignableFrom(clazz)) {
                loader.close();
                deleteDirQuietly(classDir);
                throw new PluginLoadException(
                    className + " は EditorPlugin を実装していません");
            }
            plugin = (EditorPlugin) clazz.getDeclaredConstructor().newInstance();
        } catch (PluginLoadException e) {
            throw e;
        } catch (Exception e) {
            try { loader.close(); } catch (IOException ignored) {}
            deleteDirQuietly(classDir);
            throw new PluginLoadException("プラグインのインスタンス化に失敗しました: " + e.getMessage(), e);
        }

        if (defaultContext != null) {
            plugin.onLoad(defaultContext);
        }

        loaded.put(plugin.getName(), new LoadedPlugin(plugin, loader, classDir));
        return plugin;
    }

    /**
     * 名前でプラグインを呼び出す。
     */
    public void executePlugin(String name, EditorContext ctx) throws PluginLoadException {
        LoadedPlugin lp = loaded.get(name);
        if (lp == null) {
            throw new PluginLoadException("プラグインが見つかりません: " + name);
        }
        lp.plugin().execute(ctx);
    }

    public void unloadPlugin(String name) {
        LoadedPlugin lp = loaded.remove(name);
        if (lp == null) return;
        lp.plugin().onUnload();
        try { lp.loader().close(); } catch (IOException ignored) {}
        deleteDirQuietly(lp.classDir());
    }

    public boolean isLoaded(String name) {
        return loaded.containsKey(name);
    }

    public java.util.Set<String> loadedPluginNames() {
        return java.util.Collections.unmodifiableSet(loaded.keySet());
    }

    public void unloadAll() {
        new HashMap<>(loaded).keySet().forEach(this::unloadPlugin);
    }

    private static void deleteDirQuietly(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) return;
            Files.walk(dir)
                 .sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
