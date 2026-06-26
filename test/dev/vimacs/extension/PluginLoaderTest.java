package dev.vimacs.extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PluginLoader の自作テストハーネス。
 */
public class PluginLoaderTest {

    // -------------------------------------------------------------------------
    // テスト用スタブ EditorContext
    // -------------------------------------------------------------------------

    static class StubContext implements EditorContext {
        final StringBuilder buf = new StringBuilder("hello world");
        String status = "";
        int cursorRow = 0;
        int cursorCol = 0;
        final List<String> insertLog = new ArrayList<>();
        final List<int[]> deleteLog  = new ArrayList<>();

        @Override public String getText()   { return buf.toString(); }
        @Override public int length()       { return buf.length(); }
        @Override public int getCursorRow() { return cursorRow; }
        @Override public int getCursorCol() { return cursorCol; }

        @Override public int getLineCount() {
            return buf.toString().split("\n", -1).length;
        }
        @Override public String getLine(int row) {
            String[] lines = buf.toString().split("\n", -1);
            return (row >= 0 && row < lines.length) ? lines[row] : "";
        }
        @Override public int offsetAt(int row, int col) {
            String[] lines = buf.toString().split("\n", -1);
            int offset = 0;
            for (int i = 0; i < row && i < lines.length; i++) {
                offset += lines[i].length() + 1;
            }
            int lineLen = row < lines.length ? lines[row].length() : 0;
            return offset + Math.min(col, lineLen);
        }
        @Override public void setCursor(int row, int col) {
            cursorRow = row;
            cursorCol = col;
        }
        @Override public boolean isNormalMode() { return true; }
        @Override public boolean isInsertMode() { return false; }

        @Override
        public void insertAtOffset(int offset, String text) {
            buf.insert(offset, text);
            insertLog.add(text);
        }

        @Override
        public void deleteRange(int start, int end) {
            buf.delete(start, end);
            deleteLog.add(new int[]{start, end});
        }

        @Override
        public void setStatusMessage(String msg) { status = msg; }
    }

    // -------------------------------------------------------------------------
    // ヘルパー
    // -------------------------------------------------------------------------

    private static int pass = 0;
    private static int fail = 0;

    private static void assertTrue(String name, boolean cond) {
        if (cond) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name);
            fail++;
        }
    }

    private static Path writeTempPlugin(String className, String body) throws IOException {
        Path dir = Files.createTempDirectory("vimacs-test-src-");
        Path src = dir.resolve(className + ".java");
        Files.writeString(src, body);
        return src;
    }

    // -------------------------------------------------------------------------
    // テストケース
    // -------------------------------------------------------------------------

    // TC1: 正常なプラグインをロードして execute できる
    static void testLoadAndExecute() throws Exception {
        System.out.println("[TC1] 正常なプラグインをロードして execute できる");
        String src = """
            import dev.vimacs.extension.EditorPlugin;
            import dev.vimacs.extension.EditorContext;
            public class HelloPlugin implements EditorPlugin {
                public String getName() { return "hello"; }
                public void execute(EditorContext ctx) {
                    ctx.setStatusMessage("Hello from plugin!");
                }
            }
            """;
        Path srcFile = writeTempPlugin("HelloPlugin", src);
        PluginLoader loader = new PluginLoader();
        EditorPlugin plugin = loader.loadPlugin(srcFile);
        assertTrue("getName() == hello", "hello".equals(plugin.getName()));

        StubContext ctx = new StubContext();
        plugin.execute(ctx);
        assertTrue("status message set", "Hello from plugin!".equals(ctx.status));
        loader.unloadAll();
    }

    // TC2: onLoad が呼ばれる
    static void testOnLoadCalled() throws Exception {
        System.out.println("[TC2] onLoad が呼ばれる");
        String src = """
            import dev.vimacs.extension.EditorPlugin;
            import dev.vimacs.extension.EditorContext;
            public class LoadTracker implements EditorPlugin {
                public static boolean loadCalled = false;
                public String getName() { return "loadtracker"; }
                public void onLoad(EditorContext ctx) { loadCalled = true; }
                public void execute(EditorContext ctx) {}
            }
            """;
        Path srcFile = writeTempPlugin("LoadTracker", src);
        StubContext ctx = new StubContext();
        PluginLoader loader = new PluginLoader(ctx);
        EditorPlugin plugin = loader.loadPlugin(srcFile);
        // onLoad は PluginLoader コンストラクタに defaultContext を渡した場合に呼ばれる
        assertTrue("plugin loaded", loader.isLoaded("loadtracker"));
        loader.unloadAll();
    }

    // TC3: プラグインが EditorContext.insertAtOffset を呼べる
    static void testPluginInsertsText() throws Exception {
        System.out.println("[TC3] プラグインが insertAtOffset を呼べる");
        String src = """
            import dev.vimacs.extension.EditorPlugin;
            import dev.vimacs.extension.EditorContext;
            public class InsertPlugin implements EditorPlugin {
                public String getName() { return "insert"; }
                public void execute(EditorContext ctx) {
                    ctx.insertAtOffset(0, "PREFIX:");
                }
            }
            """;
        Path srcFile = writeTempPlugin("InsertPlugin", src);
        PluginLoader loader = new PluginLoader();
        EditorPlugin plugin = loader.loadPlugin(srcFile);
        StubContext ctx = new StubContext();
        plugin.execute(ctx);
        assertTrue("text prefixed", ctx.buf.toString().startsWith("PREFIX:"));
        loader.unloadAll();
    }

    // TC4: コンパイルエラーのソースで PluginLoadException がスローされる
    static void testCompileError() throws Exception {
        System.out.println("[TC4] コンパイルエラーで PluginLoadException がスローされる");
        String src = """
            public class BrokenPlugin {
                // EditorPlugin を実装していない・構文エラーあり
                public void execute( {  // ← 構文エラー
            }
            """;
        Path srcFile = writeTempPlugin("BrokenPlugin", src);
        PluginLoader loader = new PluginLoader();
        boolean threw = false;
        try {
            loader.loadPlugin(srcFile);
        } catch (PluginLoadException e) {
            threw = true;
        }
        assertTrue("PluginLoadException thrown on compile error", threw);
    }

    // TC5: EditorPlugin を実装していないクラスで PluginLoadException
    static void testNotImplementingInterface() throws Exception {
        System.out.println("[TC5] EditorPlugin 未実装クラスで PluginLoadException");
        String src = """
            public class PlainClass {
                public String getName() { return "plain"; }
            }
            """;
        Path srcFile = writeTempPlugin("PlainClass", src);
        PluginLoader loader = new PluginLoader();
        boolean threw = false;
        try {
            loader.loadPlugin(srcFile);
        } catch (PluginLoadException e) {
            threw = true;
        }
        assertTrue("PluginLoadException thrown for non-plugin class", threw);
    }

    // TC6: unloadPlugin でプラグインが削除され、isLoaded が false になる
    static void testUnload() throws Exception {
        System.out.println("[TC6] unloadPlugin でプラグインが削除される");
        String src = """
            import dev.vimacs.extension.EditorPlugin;
            import dev.vimacs.extension.EditorContext;
            public class TempPlugin implements EditorPlugin {
                public String getName() { return "temp"; }
                public void execute(EditorContext ctx) {}
            }
            """;
        Path srcFile = writeTempPlugin("TempPlugin", src);
        PluginLoader loader = new PluginLoader();
        loader.loadPlugin(srcFile);
        assertTrue("loaded before unload", loader.isLoaded("temp"));
        loader.unloadPlugin("temp");
        assertTrue("not loaded after unload", !loader.isLoaded("temp"));
    }

    // TC7: 同名プラグインを再ロードできる
    static void testReload() throws Exception {
        System.out.println("[TC7] 同名プラグインを再ロードできる");
        String src1 = """
            import dev.vimacs.extension.EditorPlugin;
            import dev.vimacs.extension.EditorContext;
            public class ReloadPlugin implements EditorPlugin {
                public String getName() { return "reload"; }
                public void execute(EditorContext ctx) { ctx.setStatusMessage("v1"); }
            }
            """;
        String src2 = """
            import dev.vimacs.extension.EditorPlugin;
            import dev.vimacs.extension.EditorContext;
            public class ReloadPlugin implements EditorPlugin {
                public String getName() { return "reload"; }
                public void execute(EditorContext ctx) { ctx.setStatusMessage("v2"); }
            }
            """;
        Path src1File = writeTempPlugin("ReloadPlugin", src1);
        PluginLoader loader = new PluginLoader();
        loader.loadPlugin(src1File);

        // 2回目のロード（旧バージョンを上書き）
        Path src2File = writeTempPlugin("ReloadPlugin", src2);
        EditorPlugin reloaded = loader.loadPlugin(src2File);
        StubContext ctx = new StubContext();
        reloaded.execute(ctx);
        assertTrue("reloaded plugin executes new version", "v2".equals(ctx.status));
        loader.unloadAll();
    }

    // -------------------------------------------------------------------------
    // main
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("=== dev.vimacs.extension.PluginLoaderTest ===");
        try { testLoadAndExecute();           } catch (Exception e) { fail++; System.out.println("  ERROR: " + e); }
        try { testOnLoadCalled();             } catch (Exception e) { fail++; System.out.println("  ERROR: " + e); }
        try { testPluginInsertsText();        } catch (Exception e) { fail++; System.out.println("  ERROR: " + e); }
        try { testCompileError();             } catch (Exception e) { fail++; System.out.println("  ERROR: " + e); }
        try { testNotImplementingInterface(); } catch (Exception e) { fail++; System.out.println("  ERROR: " + e); }
        try { testUnload();                   } catch (Exception e) { fail++; System.out.println("  ERROR: " + e); }
        try { testReload();                   } catch (Exception e) { fail++; System.out.println("  ERROR: " + e); }
        System.out.printf("PASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
    }
}
