package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * :e で存在しないファイルを指定した場合、y/n の確認を経てから新規作成する（y=作成・n=何もしない）
 * ことを検証する。mainメソッド形式のテストハーネス（JUnit不使用）。
 */
public class ConfirmNewFileTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testYConfirmsCreation();
        testNConfirmsNoCreation();
        testEscConfirmsNoCreation();
        testExistingFileOpensWithoutPrompt();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
        System.exit(0);
    }

    private static void colonCommand(ModalEditor ed, String cmd) {
        ed.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : cmd.toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    static void testYConfirmsCreation() throws IOException {
        Path dir = Files.createTempDirectory("confirm-newfile-y");
        Path target = dir.resolve("brandnew.txt");
        ModalEditor ed = new ModalEditor("original");
        ed.setProjectRoot(dir);

        colonCommand(ed, "e " + target);
        assertEquals("file not yet created before confirmation", false, Files.exists(target));
        assertEquals("currentFilePath unchanged before confirmation", "original", ed.getText());

        ed.processKey(KeyEvent.VK_UNDEFINED, 'y', 0);
        assertEquals("y confirms creation: currentFilePath is the new path",
                target.toString(), ed.getCurrentFilePath());
        assertEquals("y confirms creation: buffer text is empty", "", ed.getText());
    }

    static void testNConfirmsNoCreation() throws IOException {
        Path dir = Files.createTempDirectory("confirm-newfile-n");
        Path target = dir.resolve("shouldnotexist.txt");
        ModalEditor ed = new ModalEditor("original");
        ed.setProjectRoot(dir);

        colonCommand(ed, "e " + target);
        ed.processKey(KeyEvent.VK_UNDEFINED, 'n', 0);

        assertEquals("n cancels: file not created on disk", false, Files.exists(target));
        assertEquals("n cancels: original buffer text is kept", "original", ed.getText());
        assertEquals("n cancels: currentFilePath unchanged", null, ed.getCurrentFilePath());
    }

    static void testEscConfirmsNoCreation() throws IOException {
        Path dir = Files.createTempDirectory("confirm-newfile-esc");
        Path target = dir.resolve("shouldnotexist2.txt");
        ModalEditor ed = new ModalEditor("original");
        ed.setProjectRoot(dir);

        colonCommand(ed, "e " + target);
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);

        assertEquals("Esc cancels: file not created on disk", false, Files.exists(target));
        assertEquals("Esc cancels: original buffer text is kept", "original", ed.getText());
    }

    static void testExistingFileOpensWithoutPrompt() throws IOException {
        Path dir = Files.createTempDirectory("confirm-newfile-existing");
        Path target = dir.resolve("already.txt");
        Files.writeString(target, "existing content");
        ModalEditor ed = new ModalEditor("original");
        ed.setProjectRoot(dir);

        colonCommand(ed, "e " + target);

        assertEquals("existing file opens immediately without confirmation",
                "existing content", ed.getText());
        assertEquals("currentFilePath set to existing file", target.toString(), ed.getCurrentFilePath());
    }

    static void assertEquals(String name, Object expected, Object actual) {
        if (java.util.Objects.equals(expected, actual)) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name + " (expected=" + expected + ", actual=" + actual + ")");
            fail++;
        }
    }
}
