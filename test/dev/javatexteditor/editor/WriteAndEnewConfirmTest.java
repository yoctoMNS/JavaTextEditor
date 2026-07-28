package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * :w / :enew で存在しないファイルを対象にした場合も :e と同じく y/n の確認を挟むこと、
 * および :e/:enew/:w のいずれもディレクトリ・ファイル名を TAB キーで補完できることを
 * 検証する。mainメソッド形式のテストハーネス（JUnit不使用）。
 */
public class WriteAndEnewConfirmTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testWToNewPathAsksConfirmationAndYCreates();
        testWToNewPathNCancelsWithoutWriting();
        testWToExistingPathSavesWithoutPrompt();
        testBareWOnNeverSavedPathAsksConfirmation();

        testEnewWithNewPathAsksConfirmationAndYCreates();
        testEnewWithNewPathNCancels();
        testEnewWithExistingPathOpensWithoutPrompt();

        testTabCompletionForW();
        testTabCompletionForEnew();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
        System.exit(0);
    }

    private static void colon(ModalEditor ed, String cmd) {
        ed.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : cmd.toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
    }

    private static void enter(ModalEditor ed) {
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    private static void tab(ModalEditor ed) {
        ed.processKey(KeyEvent.VK_TAB, '\t', 0);
    }

    // -------------------------------------------------------------------------
    // :w
    // -------------------------------------------------------------------------

    static void testWToNewPathAsksConfirmationAndYCreates() throws IOException {
        Path dir = Files.createTempDirectory("w-confirm-y");
        Path target = dir.resolve("out.txt");
        ModalEditor ed = new ModalEditor("hello");
        ed.setProjectRoot(dir);

        colon(ed, "w " + target);
        enter(ed);
        assertEquals("file not yet written before confirmation", false, Files.exists(target));

        ed.processKey(KeyEvent.VK_UNDEFINED, 'y', 0);
        assertEquals("y confirms: file now exists on disk", true, Files.exists(target));
        assertEquals("y confirms: file content matches buffer", "hello", Files.readString(target));
        assertEquals("y confirms: currentFilePath updated", target.toString(), ed.getCurrentFilePath());
    }

    static void testWToNewPathNCancelsWithoutWriting() throws IOException {
        Path dir = Files.createTempDirectory("w-confirm-n");
        Path target = dir.resolve("out.txt");
        ModalEditor ed = new ModalEditor("hello");
        ed.setProjectRoot(dir);

        colon(ed, "w " + target);
        enter(ed);
        ed.processKey(KeyEvent.VK_UNDEFINED, 'n', 0);

        assertEquals("n cancels: file not written to disk", false, Files.exists(target));
        assertEquals("n cancels: currentFilePath unchanged", null, ed.getCurrentFilePath());
    }

    static void testWToExistingPathSavesWithoutPrompt() throws IOException {
        Path dir = Files.createTempDirectory("w-confirm-existing");
        Path target = dir.resolve("out.txt");
        Files.writeString(target, "old content");
        ModalEditor ed = new ModalEditor("new content");
        ed.setProjectRoot(dir);

        colon(ed, "w " + target);
        enter(ed);

        assertEquals("existing file overwritten without confirmation",
                "new content", Files.readString(target));
    }

    static void testBareWOnNeverSavedPathAsksConfirmation() throws IOException {
        // :e で作った直後の新規ファイル（まだディスクに存在しない）に対する bare ':w' も確認する。
        Path dir = Files.createTempDirectory("w-confirm-bare");
        Path target = dir.resolve("brandnew.txt");
        ModalEditor ed = new ModalEditor("original");
        ed.setProjectRoot(dir);

        colon(ed, "e " + target);
        enter(ed);
        ed.processKey(KeyEvent.VK_UNDEFINED, 'y', 0); // :e 自体の確認
        assertEquals(":e created empty buffer for new path", target.toString(), ed.getCurrentFilePath());

        ed.processKey(KeyEvent.VK_UNDEFINED, 'i', 0);
        for (char c : "typed".toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);

        colon(ed, "w");
        enter(ed);
        assertEquals("bare :w on not-yet-existing file asks for confirmation too",
                false, Files.exists(target));

        ed.processKey(KeyEvent.VK_UNDEFINED, 'y', 0);
        assertEquals("bare :w confirmed: file now exists", true, Files.exists(target));
        assertEquals("bare :w confirmed: content matches", "typed", Files.readString(target));
    }

    // -------------------------------------------------------------------------
    // :enew
    // -------------------------------------------------------------------------

    static void testEnewWithNewPathAsksConfirmationAndYCreates() throws IOException {
        Path dir = Files.createTempDirectory("enew-confirm-y");
        Path target = dir.resolve("fresh.txt");
        ModalEditor ed = new ModalEditor("original");
        ed.setProjectRoot(dir);

        colon(ed, "enew " + target);
        enter(ed);
        assertEquals("file not yet created before confirmation", false, Files.exists(target));

        ed.processKey(KeyEvent.VK_UNDEFINED, 'y', 0);
        assertEquals("y confirms: currentFilePath is the new path",
                target.toString(), ed.getCurrentFilePath());
        assertEquals("y confirms: buffer text is empty", "", ed.getText());
    }

    static void testEnewWithNewPathNCancels() throws IOException {
        Path dir = Files.createTempDirectory("enew-confirm-n");
        Path target = dir.resolve("fresh.txt");
        ModalEditor ed = new ModalEditor("original");
        ed.setProjectRoot(dir);

        colon(ed, "enew " + target);
        enter(ed);
        ed.processKey(KeyEvent.VK_UNDEFINED, 'n', 0);

        assertEquals("n cancels: file not created", false, Files.exists(target));
        assertEquals("n cancels: original buffer text kept", "original", ed.getText());
    }

    static void testEnewWithExistingPathOpensWithoutPrompt() throws IOException {
        Path dir = Files.createTempDirectory("enew-confirm-existing");
        Path target = dir.resolve("already.txt");
        Files.writeString(target, "existing content");
        ModalEditor ed = new ModalEditor("original");
        ed.setProjectRoot(dir);

        colon(ed, "enew " + target);
        enter(ed);

        assertEquals("existing file opens immediately without confirmation",
                "existing content", ed.getText());
    }

    // -------------------------------------------------------------------------
    // TAB 補完
    // -------------------------------------------------------------------------

    static void testTabCompletionForW() throws IOException {
        Path dir = Files.createTempDirectory("w-tab-complete");
        Files.writeString(dir.resolve("uniquefile.txt"), "x");
        ModalEditor ed = new ModalEditor("hello");
        ed.setProjectRoot(dir);

        colon(ed, "w uniq");
        tab(ed);

        assertEquals("single candidate completes in place",
                "w uniquefile.txt", ed.getCommandBuffer());
    }

    static void testTabCompletionForEnew() throws IOException {
        Path dir = Files.createTempDirectory("enew-tab-complete");
        Files.writeString(dir.resolve("uniquefile2.txt"), "x");
        ModalEditor ed = new ModalEditor("hello");
        ed.setProjectRoot(dir);

        colon(ed, "enew uniq");
        tab(ed);

        assertEquals("single candidate completes in place",
                "enew uniquefile2.txt", ed.getCommandBuffer());
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
