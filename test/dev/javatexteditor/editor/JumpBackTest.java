package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shift+K（定義ジャンプ）→ Shift+J（一つ前の参照へ戻る）の往復動作を検証する。
 * mainメソッド形式のテストハーネス（JUnit不使用）。
 */
public class JumpBackTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testJumpBackSameFile();
        testJumpBackAcrossFiles();
        testJumpBackWithNoPriorJump();
        testJumpBackKeyBinding();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void assertTrue(String name, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name);
            fail++;
        }
    }

    static void assertEquals(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name + " expected=" + expected + " actual=" + actual);
            fail++;
        }
    }

    private static void pressShiftK(ModalEditor ed) {
        ed.processKey(KeyEvent.VK_K, 'K', KeyEvent.SHIFT_DOWN_MASK);
    }

    private static void pressShiftJ(ModalEditor ed) {
        ed.processKey(KeyEvent.VK_J, 'J', KeyEvent.SHIFT_DOWN_MASK);
    }

    static void testJumpBackSameFile() throws Exception {
        Path dir = Files.createTempDirectory("jumpback-test");
        Path file = dir.resolve("Sample.java");
        String content = """
            public class Sample {
                static int VALUE = 10;

                void use() {
                    int x = VALUE;
                }
            }
            """;
        Files.writeString(file, content);

        ModalEditor ed = new ModalEditor(content, file.toString(), null);
        ed.setProjectRoot(dir);

        // "VALUE" の使用箇所（4行目、0-indexed）にカーソルを置く
        int useLine = 4;
        int col = content.split("\n", -1)[useLine].indexOf("VALUE");
        ed.setCursor(useLine, col);

        pressShiftK(ed);
        assertEquals("Shift+K: 宣言行(1行目)へジャンプ", 1, ed.getCursorRow());

        pressShiftJ(ed);
        assertEquals("Shift+J: 呼び出し元の行へ戻る", useLine, ed.getCursorRow());
        assertEquals("Shift+J: 呼び出し元の列へ戻る", col, ed.getCursorCol());
    }

    static void testJumpBackAcrossFiles() throws Exception {
        Path dir = Files.createTempDirectory("jumpback-test-multi");
        Path callerFile = dir.resolve("Caller.java");
        Path targetFile = dir.resolve("Target.java");
        String callerContent = """
            public class Caller {
                void use() {
                    Target.helper();
                }
            }
            """;
        String targetContent = """
            public class Target {
                static void helper() {
                }
            }
            """;
        Files.writeString(callerFile, callerContent);
        Files.writeString(targetFile, targetContent);

        ModalEditor ed = new ModalEditor(callerContent, callerFile.toString(), null);
        ed.setProjectRoot(dir);

        int useLine = 2;
        int col = callerContent.split("\n", -1)[useLine].indexOf("helper");
        ed.setCursor(useLine, col);

        pressShiftK(ed);
        assertTrue("Shift+K: Target.java を開いた", ed.getCurrentFilePath().endsWith("Target.java"));

        pressShiftJ(ed);
        assertTrue("Shift+J: Caller.java へ戻った", ed.getCurrentFilePath().endsWith("Caller.java"));
        assertEquals("Shift+J: 呼び出し元の行へ戻る", useLine, ed.getCursorRow());
        assertEquals("Shift+J: 呼び出し元の列へ戻る", col, ed.getCursorCol());
    }

    static void testJumpBackWithNoPriorJump() {
        ModalEditor ed = new ModalEditor("hello world");
        int beforeRow = ed.getCursorRow();
        int beforeCol = ed.getCursorCol();
        pressShiftJ(ed);
        assertEquals("Shift+J: ジャンプ履歴が無い場合は何もしない(row)", beforeRow, ed.getCursorRow());
        assertEquals("Shift+J: ジャンプ履歴が無い場合は何もしない(col)", beforeCol, ed.getCursorCol());
        assertTrue("Shift+J: ステータスメッセージが表示される",
            !ed.getStatusMessage().isEmpty());
    }

    static void testJumpBackKeyBinding() {
        KeymapRegistry reg = new KeymapRegistry();
        String action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_J, 'J', KeyEvent.SHIFT_DOWN_MASK);
        assertEquals("Shift+J -> jump.back", "jump.back", action);
    }
}
