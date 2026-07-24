package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ModalEditor 経由の C言語 Shift+K 定義ジャンプ統合テスト。
 * bindingLookupEnabled は既定で無効のため lookupCDefinition は withTimeout の同期経路を通り、
 * processKey 直後に同期 assert できる（editor-testing-strategy の同期契約に沿う）。
 */
public class CDefinitionJumpTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testJumpToFunctionDefinitionAcrossFiles();
        testJumpToHeaderMacro();
        testJumpOpensIncludedHeader();
        testNotFoundKeepsBuffer();
        testShiftJReturnsToOrigin();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void assertTrue(String name, boolean cond) {
        if (cond) { System.out.println("  PASS: " + name); pass++; }
        else { System.out.println("  FAIL: " + name); fail++; }
    }

    static void assertEquals(String name, Object expected, Object actual) {
        assertTrue(name + " (expected=" + expected + ", actual=" + actual + ")",
            expected == null ? actual == null : expected.equals(actual));
    }

    static void pressShiftK(ModalEditor ed) {
        ed.processKey(KeyEvent.VK_K, 'K', KeyEvent.SHIFT_DOWN_MASK);
    }

    static void testJumpToFunctionDefinitionAcrossFiles() throws Exception {
        Path dir = Files.createTempDirectory("cdefjump-fn");
        Files.writeString(dir.resolve("util.h"), "int add(int a, int b);\n");
        Files.writeString(dir.resolve("util.c"),
            "#include \"util.h\"\nint add(int a, int b) {\n  return a + b;\n}\n");
        String main = "#include \"util.h\"\nint main(void){ return add(1, 2); }\n";
        Path mainFile = dir.resolve("main.c");
        Files.writeString(mainFile, main);

        ModalEditor ed = new ModalEditor(main, mainFile.toString(), null);
        ed.setProjectRoot(dir);
        int col = main.split("\n", -1)[1].indexOf("add");
        ed.setCursor(1, col);

        pressShiftK(ed);
        assertTrue("ジャンプ先が util.c",
            ed.getCurrentFilePath() != null && ed.getCurrentFilePath().endsWith("util.c"));
        assertEquals("関数実装の行(1)へジャンプ", 1, ed.getCursorRow());
    }

    static void testJumpToHeaderMacro() throws Exception {
        Path dir = Files.createTempDirectory("cdefjump-macro");
        Files.writeString(dir.resolve("cfg.h"), "#ifndef CFG_H\n#define MAX_USERS 50\n#endif\n");
        String main = "#include \"cfg.h\"\nint arr[MAX_USERS];\n";
        Path mainFile = dir.resolve("main.c");
        Files.writeString(mainFile, main);

        ModalEditor ed = new ModalEditor(main, mainFile.toString(), null);
        ed.setProjectRoot(dir);
        int col = main.split("\n", -1)[1].indexOf("MAX_USERS");
        ed.setCursor(1, col);

        pressShiftK(ed);
        assertTrue("マクロ定義のあるヘッダ cfg.h へジャンプ",
            ed.getCurrentFilePath() != null && ed.getCurrentFilePath().endsWith("cfg.h"));
        assertEquals("#define の行(1)へジャンプ", 1, ed.getCursorRow());
    }

    static void testJumpOpensIncludedHeader() throws Exception {
        Path dir = Files.createTempDirectory("cdefjump-inc");
        Files.writeString(dir.resolve("helper.h"), "void help(void);\n");
        String main = "#include \"helper.h\"\nint main(void){ return 0; }\n";
        Path mainFile = dir.resolve("main.c");
        Files.writeString(mainFile, main);

        ModalEditor ed = new ModalEditor(main, mainFile.toString(), null);
        ed.setProjectRoot(dir);
        ed.setCursor(0, 3); // #include 行の上（列は問わない）

        pressShiftK(ed);
        assertTrue("#include 行の Shift+K で helper.h を開く",
            ed.getCurrentFilePath() != null && ed.getCurrentFilePath().endsWith("helper.h"));
    }

    static void testNotFoundKeepsBuffer() throws Exception {
        Path dir = Files.createTempDirectory("cdefjump-nf");
        String main = "int main(void){ return unknownThing; }\n";
        Path mainFile = dir.resolve("main.c");
        Files.writeString(mainFile, main);

        ModalEditor ed = new ModalEditor(main, mainFile.toString(), null);
        ed.setProjectRoot(dir);
        int col = main.indexOf("unknownThing");
        ed.setCursor(0, col);

        pressShiftK(ed);
        assertTrue("未解決でもファイルは変わらない",
            ed.getCurrentFilePath() != null && ed.getCurrentFilePath().endsWith("main.c"));
        assertTrue("ステータスにnot found表示",
            ed.getStatusMessage() != null && ed.getStatusMessage().contains("not found"));
    }

    static void testShiftJReturnsToOrigin() throws Exception {
        Path dir = Files.createTempDirectory("cdefjump-back");
        Files.writeString(dir.resolve("util.c"),
            "int square(int x) {\n  return x * x;\n}\n");
        String main = "#include \"util.c\"\nint main(void){ return square(3); }\n";
        Path mainFile = dir.resolve("main.c");
        Files.writeString(mainFile, main);

        ModalEditor ed = new ModalEditor(main, mainFile.toString(), null);
        ed.setProjectRoot(dir);
        int col = main.split("\n", -1)[1].indexOf("square");
        ed.setCursor(1, col);

        pressShiftK(ed);
        assertTrue("square の定義(util.c)へジャンプ",
            ed.getCurrentFilePath() != null && ed.getCurrentFilePath().endsWith("util.c"));

        ed.processKey(KeyEvent.VK_J, 'J', KeyEvent.SHIFT_DOWN_MASK);
        assertTrue("Shift+J で元ファイル(main.c)へ戻る",
            ed.getCurrentFilePath() != null && ed.getCurrentFilePath().endsWith("main.c"));
        assertEquals("元の行へ戻る", 1, ed.getCursorRow());
    }
}
