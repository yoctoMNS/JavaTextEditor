package dev.javatexteditor.extension;

import dev.javatexteditor.editor.ModalEditor;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * EditorContext 公開 API の結合テスト。
 * SimpleEditorContext → ModalEditor の経路で各メソッドを検証する。
 */
public class EditorContextApiTest {

    static int pass = 0;
    static int fail = 0;

    // --- helpers ---------------------------------------------------------------

    /** 指定テキストで EditorContext を生成する。 */
    static EditorContext ctx(String text) {
        return new SimpleEditorContext(new ModalEditor(text));
    }

    static void check(String desc, boolean result) {
        if (result) {
            System.out.println("  PASS: " + desc);
            pass++;
        } else {
            System.out.println("  FAIL: " + desc);
            fail++;
        }
    }

    private static Path writeTempPlugin(String className, String body) throws Exception {
        Path dir = Files.createTempDirectory("jte-ctx-test-");
        Path src = dir.resolve(className + ".java");
        Files.writeString(src, body);
        return src;
    }

    // --- テストケース -----------------------------------------------------------

    static void testGetLineCount() {
        System.out.println("[getLineCount]");
        check("単一行: getLineCount() == 1", ctx("hello").getLineCount() == 1);
        check("2行: getLineCount() == 2",    ctx("line0\nline1").getLineCount() == 2);
        check("末尾改行あり: 3行",            ctx("a\nb\n").getLineCount() == 3);
        check("空テキスト: 1行",              ctx("").getLineCount() == 1);
    }

    static void testGetLine() {
        System.out.println("[getLine]");
        EditorContext c = ctx("alpha\nbeta\ngamma");
        check("getLine(0) == 'alpha'",  "alpha".equals(c.getLine(0)));
        check("getLine(1) == 'beta'",   "beta".equals(c.getLine(1)));
        check("getLine(2) == 'gamma'",  "gamma".equals(c.getLine(2)));
        check("getLine(-1) == '' (範囲外)", "".equals(c.getLine(-1)));
        check("getLine(99) == '' (範囲外)", "".equals(c.getLine(99)));
    }

    static void testOffsetAt() {
        System.out.println("[offsetAt]");
        EditorContext c = ctx("abc\nde\nf");
        //  offset: a=0, b=1, c=2, \n=3, d=4, e=5, \n=6, f=7
        check("offsetAt(0,0) == 0",  c.offsetAt(0, 0) == 0);
        check("offsetAt(0,3) == 3",  c.offsetAt(0, 3) == 3);
        check("offsetAt(1,0) == 4",  c.offsetAt(1, 0) == 4);
        check("offsetAt(1,2) == 6",  c.offsetAt(1, 2) == 6);
        check("offsetAt(2,0) == 7",  c.offsetAt(2, 0) == 7);
        // col がラインを超える場合は行末にクランプ
        check("offsetAt(0,99) clamp == 3", c.offsetAt(0, 99) == 3);
    }

    static void testSetCursor() {
        System.out.println("[setCursor]");
        EditorContext c = ctx("line0\nline1\nline2");
        check("初期 row=0", c.getCursorRow() == 0);
        check("初期 col=0", c.getCursorCol() == 0);

        c.setCursor(1, 3);
        check("setCursor(1,3): row=1", c.getCursorRow() == 1);
        check("setCursor(1,3): col=3", c.getCursorCol() == 3);

        c.setCursor(2, 0);
        check("setCursor(2,0): row=2", c.getCursorRow() == 2);
        check("setCursor(2,0): col=0", c.getCursorCol() == 0);
    }

    static void testSetCursorClamping() {
        System.out.println("[setCursor クランプ]");
        EditorContext c = ctx("abc\nde");
        // 行を超える場合
        c.setCursor(99, 0);
        check("row=99 → クランプして row=1 (最終行)", c.getCursorRow() == 1);
        // 行内でcolを超える場合
        c.setCursor(0, 99);
        check("col=99 on 'abc' → col=3 (行末)", c.getCursorCol() == 3);
        // 負数
        c.setCursor(-5, -3);
        check("row=-5 → row=0", c.getCursorRow() == 0);
        check("col=-3 → col=0", c.getCursorCol() == 0);
    }

    static void testModeQueries() {
        System.out.println("[isNormalMode / isInsertMode]");
        ModalEditor editor = new ModalEditor("hello");
        EditorContext c = new SimpleEditorContext(editor);

        check("初期: isNormalMode() == true",  c.isNormalMode());
        check("初期: isInsertMode() == false", !c.isInsertMode());

        // 'i' を送ってINSERTモードへ
        editor.processKey(0, 'i', 0);
        check("i 後: isInsertMode() == true",  c.isInsertMode());
        check("i 後: isNormalMode() == false", !c.isNormalMode());

        // ESC で NORMAL に戻る
        editor.processKey(java.awt.event.KeyEvent.VK_ESCAPE, (char)27, 0);
        check("ESC 後: isNormalMode() == true",  c.isNormalMode());
        check("ESC 後: isInsertMode() == false", !c.isInsertMode());
    }

    static void testPluginUsesGetLine() throws Exception {
        System.out.println("[E2E: Plugin が getLine で行内容を読む]");
        String src = """
            import dev.javatexteditor.extension.EditorPlugin;
            import dev.javatexteditor.extension.EditorContext;
            public class ReadLinePlugin implements EditorPlugin {
                public String getName() { return "readline"; }
                public void execute(EditorContext ctx) {
                    String line = ctx.getLine(1);
                    ctx.setStatusMessage("line1=" + line);
                }
            }
            """;
        Path srcFile = writeTempPlugin("ReadLinePlugin", src);
        PluginLoader loader = new PluginLoader();
        EditorPlugin plugin = loader.loadPlugin(srcFile);

        ModalEditor editor = new ModalEditor("first\nsecond\nthird");
        EditorContext c = new SimpleEditorContext(editor);
        plugin.execute(c);
        check("plugin が getLine(1) で 'second' を読んだ",
              "line1=second".equals(editor.getStatusMessage()));
        loader.unloadAll();
    }

    static void testPluginUsesSetCursor() throws Exception {
        System.out.println("[E2E: Plugin が setCursor でカーソルを移動する]");
        String src = """
            import dev.javatexteditor.extension.EditorPlugin;
            import dev.javatexteditor.extension.EditorContext;
            public class JumpPlugin implements EditorPlugin {
                public String getName() { return "jump"; }
                public void execute(EditorContext ctx) {
                    ctx.setCursor(2, 3);
                }
            }
            """;
        Path srcFile = writeTempPlugin("JumpPlugin", src);
        PluginLoader loader = new PluginLoader();
        EditorPlugin plugin = loader.loadPlugin(srcFile);

        ModalEditor editor = new ModalEditor("aaa\nbbb\nccc");
        EditorContext c = new SimpleEditorContext(editor);
        plugin.execute(c);
        check("plugin setCursor(2,3) → row=2", editor.getCursorRow() == 2);
        check("plugin setCursor(2,3) → col=3", editor.getCursorCol() == 3);
        loader.unloadAll();
    }

    static void testGetKeymap() {
        System.out.println("[getKeymap]");
        EditorContext c = ctx("hello");
        check("getKeymap() != null", c.getKeymap() != null);
        // 同じインスタンスが返る
        check("getKeymap() は同じインスタンスを返す", c.getKeymap() == c.getKeymap());
    }

    static void testPluginRebindsKey() throws Exception {
        System.out.println("[E2E: Plugin が getKeymap でキーを再バインドする]");
        String src = """
            import dev.javatexteditor.extension.EditorPlugin;
            import dev.javatexteditor.extension.EditorContext;
            import dev.javatexteditor.editor.KeymapRegistry;
            import dev.javatexteditor.editor.KeyBinding;
            public class RebindPlugin implements EditorPlugin {
                public String getName() { return "rebind"; }
                public void execute(EditorContext ctx) {
                    // 'z' -> undo に割り当て
                    ctx.getKeymap().bind(KeymapRegistry.Mode.NORMAL,
                        KeyBinding.ofChar('z', "undo"), "undo");
                }
            }
            """;
        Path srcFile = writeTempPlugin("RebindPlugin", src);
        PluginLoader loader = new PluginLoader();
        EditorPlugin plugin = loader.loadPlugin(srcFile);

        dev.javatexteditor.editor.ModalEditor editor = new dev.javatexteditor.editor.ModalEditor("hello");
        EditorContext c = new SimpleEditorContext(editor);

        // INSERT モードで 'X' を入力してからNORMALに戻す
        editor.processKey(0, 'i', 0);
        editor.processKey(0, 'X', 0);
        editor.processKey(java.awt.event.KeyEvent.VK_ESCAPE, (char)27, 0);
        String afterInsert = editor.getText();

        // プラグインで 'z' を undo に再バインド
        plugin.execute(c);

        // 'z' を押すとアンドゥが実行される
        editor.processKey(0, 'z', 0);
        check("'z' 押下後アンドゥが実行された (テキストが復元)", editor.getText().equals("hello"));
        loader.unloadAll();
    }

    static void testPluginRegistersCustomKey() throws Exception {
        System.out.println("[E2E: Plugin がカスタムキーとアクションを登録する]");
        String src = """
            import dev.javatexteditor.extension.EditorPlugin;
            import dev.javatexteditor.extension.EditorContext;
            import dev.javatexteditor.editor.KeymapRegistry;
            import dev.javatexteditor.editor.KeyBinding;
            public class CustomKeyPlugin implements EditorPlugin {
                public String getName() { return "customkey"; }
                public void execute(EditorContext ctx) {
                    ctx.getKeymap().registerAction("custom.greet",
                        () -> ctx.setStatusMessage("hello from custom key"));
                    ctx.getKeymap().bind(KeymapRegistry.Mode.NORMAL,
                        KeyBinding.ofChar('Q', "custom.greet"), "custom.greet");
                }
            }
            """;
        Path srcFile = writeTempPlugin("CustomKeyPlugin", src);
        PluginLoader loader = new PluginLoader();
        EditorPlugin plugin = loader.loadPlugin(srcFile);

        dev.javatexteditor.editor.ModalEditor editor = new dev.javatexteditor.editor.ModalEditor("hello");
        EditorContext c = new SimpleEditorContext(editor);
        plugin.execute(c);

        // 'Q' を押すとカスタムアクションが実行される
        editor.processKey(0, 'Q', 0);
        check("'Q' 押下でカスタムアクションが実行された",
              "hello from custom key".equals(editor.getStatusMessage()));
        loader.unloadAll();
    }

    static void testPluginUsesOffsetAt() throws Exception {
        System.out.println("[E2E: Plugin が offsetAt + insertAtOffset で行頭挿入する]");
        String src = """
            import dev.javatexteditor.extension.EditorPlugin;
            import dev.javatexteditor.extension.EditorContext;
            public class LineHeadInsertPlugin implements EditorPlugin {
                public String getName() { return "lineheadinsert"; }
                public void execute(EditorContext ctx) {
                    // 1行目の先頭に ">> " を挿入
                    int offset = ctx.offsetAt(1, 0);
                    ctx.insertAtOffset(offset, ">> ");
                }
            }
            """;
        Path srcFile = writeTempPlugin("LineHeadInsertPlugin", src);
        PluginLoader loader = new PluginLoader();
        EditorPlugin plugin = loader.loadPlugin(srcFile);

        ModalEditor editor = new ModalEditor("alpha\nbeta\ngamma");
        EditorContext c = new SimpleEditorContext(editor);
        plugin.execute(c);
        check("offsetAt+insert: 1行目が '>> beta' になる",
              editor.getLine(1).equals(">> beta"));
        loader.unloadAll();
    }

    // --- main ------------------------------------------------------------------

    public static void main(String[] args) {
        testGetLineCount();
        testGetLine();
        testOffsetAt();
        testSetCursor();
        testSetCursorClamping();
        testModeQueries();
        testGetKeymap();
        try { testPluginUsesGetLine();           } catch (Exception e) { fail++; System.out.println("  ERROR: " + e); }
        try { testPluginUsesSetCursor();         } catch (Exception e) { fail++; System.out.println("  ERROR: " + e); }
        try { testPluginUsesOffsetAt();          } catch (Exception e) { fail++; System.out.println("  ERROR: " + e); }
        try { testPluginRebindsKey();            } catch (Exception e) { fail++; System.out.println("  ERROR: " + e); }
        try { testPluginRegistersCustomKey();    } catch (Exception e) { fail++; System.out.println("  ERROR: " + e); }

        System.out.printf("%nPASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
        if (fail > 0) System.exit(1);
    }
}
