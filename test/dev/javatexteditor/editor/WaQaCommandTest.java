package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ":wa"（全保存）/ ":qa"（全終了・未保存拒否）/ ":qa!"（強制全終了）の統合テスト。
 * 既存の ":w" / ":q" / ":q!"（":q!" は本エディタに未実装）との衝突が無いことも併せて確認する。
 * mainメソッド形式のテストハーネス（JUnit不使用）。
 */
public class WaQaCommandTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testWaSavesModifiedSingleBuffer();
        testWaNoOpWhenNoChanges();
        testWaSavesOnlyModifiedAcrossMultipleEditors();
        testWaReportsFailureWhenNoFileName();

        testQaExitsWhenNoUnsavedChanges();
        testQaBlocksWhenUnsavedChangesExist();
        testQaBlockMessageListsFilePath();
        testQaAcrossMultipleEditorsBlocksIfAnyUnsaved();

        testQaBangForcesExitDespiteUnsavedChanges();

        testExistingWStillWorks();
        testExistingQStillExitsWithoutCheck();
        testQaDoesNotCollideWithQ();
        testQBangIsStillUnknownCommand();

        System.out.println("\n=== WaQaCommand: " + passed + "/" + (passed + failed) + " PASS ===");
        if (failed > 0) System.exit(1);
    }

    // -------------------------------------------------------------------
    // :wa
    // -------------------------------------------------------------------

    static void testWaSavesModifiedSingleBuffer() throws IOException {
        Path tmp = Files.createTempFile("waqa", ".txt");
        try {
            ModalEditor ed = new ModalEditor("", tmp.toString(), null);
            typeInsert(ed, "hello");
            assertTrue("insert marks modified", ed.isModified());
            sendCommand(ed, "wa");
            assertTrue("':wa' saved the file", Files.readString(tmp).equals("hello"));
            assertTrue("':wa' clears modified flag", !ed.isModified());
            assertTrue("status reports 1 file written", ed.getStatusMessage().contains("1 file"));
            passed("testWaSavesModifiedSingleBuffer");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static void testWaNoOpWhenNoChanges() throws IOException {
        Path tmp = Files.createTempFile("waqa", ".txt");
        try {
            ModalEditor ed = new ModalEditor("original", tmp.toString(), null);
            sendCommand(ed, "wa");
            assertTrue("':wa' with no changes reports no changes",
                ed.getStatusMessage().contains("no changes"));
            passed("testWaNoOpWhenNoChanges");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static void testWaSavesOnlyModifiedAcrossMultipleEditors() throws IOException {
        Path tmpA = Files.createTempFile("waqa-a", ".txt");
        Path tmpB = Files.createTempFile("waqa-b", ".txt");
        try {
            Files.writeString(tmpB, "original");
            ModalEditor edA = new ModalEditor("", tmpA.toString(), null);
            ModalEditor edB = new ModalEditor("original", tmpB.toString(), null);
            typeInsert(edA, "changed");
            List<ModalEditor> all = List.of(edA, edB);
            edA.setAllEditorsSupplier(() -> all);

            sendCommand(edA, "wa");

            assertTrue("modified editor A got saved", Files.readString(tmpA).equals("changed"));
            assertTrue("untouched editor B was left on disk unchanged",
                Files.readString(tmpB).equals("original"));
            assertTrue("only 1 file reported written", edA.getStatusMessage().contains("1 file"));
            passed("testWaSavesOnlyModifiedAcrossMultipleEditors");
        } finally {
            Files.deleteIfExists(tmpA);
            Files.deleteIfExists(tmpB);
        }
    }

    static void testWaReportsFailureWhenNoFileName() {
        ModalEditor ed = new ModalEditor("");
        typeInsert(ed, "x");
        sendCommand(ed, "wa");
        assertTrue("':wa' without a file name reports failure",
            ed.getStatusMessage().contains("failed to save"));
        passed("testWaReportsFailureWhenNoFileName");
    }

    // -------------------------------------------------------------------
    // :qa / :qa!
    // -------------------------------------------------------------------

    static void testQaExitsWhenNoUnsavedChanges() {
        ModalEditor ed = new ModalEditor("hello");
        boolean[] exited = {false};
        ed.setExitAllCallback(() -> exited[0] = true);
        sendCommand(ed, "qa");
        assertTrue("':qa' exits when nothing is unsaved", exited[0]);
        passed("testQaExitsWhenNoUnsavedChanges");
    }

    static void testQaBlocksWhenUnsavedChangesExist() {
        ModalEditor ed = new ModalEditor("");
        boolean[] exited = {false};
        ed.setExitAllCallback(() -> exited[0] = true);
        typeInsert(ed, "unsaved edit");
        sendCommand(ed, "qa");
        assertTrue("':qa' does not exit with unsaved changes", !exited[0]);
        assertTrue("':qa' reports E37-style message", ed.getStatusMessage().contains("No write since last change"));
        passed("testQaBlocksWhenUnsavedChangesExist");
    }

    static void testQaBlockMessageListsFilePath() {
        ModalEditor ed = new ModalEditor("", "/tmp/example.txt", null);
        typeInsert(ed, "x");
        sendCommand(ed, "qa");
        assertTrue("blocked ':qa' message names the file",
            ed.getStatusMessage().contains("/tmp/example.txt"));
        passed("testQaBlockMessageListsFilePath");
    }

    static void testQaAcrossMultipleEditorsBlocksIfAnyUnsaved() {
        ModalEditor edA = new ModalEditor("saved");
        ModalEditor edB = new ModalEditor("");
        typeInsert(edB, "dirty");
        List<ModalEditor> all = List.of(edA, edB);
        edA.setAllEditorsSupplier(() -> all);
        boolean[] exited = {false};
        edA.setExitAllCallback(() -> exited[0] = true);

        sendCommand(edA, "qa");

        assertTrue("':qa' blocked because a sibling editor has unsaved changes", !exited[0]);
        passed("testQaAcrossMultipleEditorsBlocksIfAnyUnsaved");
    }

    static void testQaBangForcesExitDespiteUnsavedChanges() {
        ModalEditor ed = new ModalEditor("");
        boolean[] exited = {false};
        ed.setExitAllCallback(() -> exited[0] = true);
        typeInsert(ed, "unsaved edit");
        sendCommand(ed, "qa!");
        assertTrue("':qa!' force-exits despite unsaved changes", exited[0]);
        passed("testQaBangForcesExitDespiteUnsavedChanges");
    }

    // -------------------------------------------------------------------
    // 既存 :w / :q との非衝突確認
    // -------------------------------------------------------------------

    static void testExistingWStillWorks() throws IOException {
        Path tmp = Files.createTempFile("waqa-w", ".txt");
        try {
            ModalEditor ed = new ModalEditor("", tmp.toString(), null);
            typeInsert(ed, "via-w");
            sendCommand(ed, "w");
            assertTrue("existing ':w' still saves the file",
                Files.readString(tmp).equals("via-w"));
            passed("testExistingWStillWorks");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static void testExistingQStillExitsWithoutCheck() {
        ModalEditor ed = new ModalEditor("");
        boolean[] exited = {false};
        ed.setExitCallback(() -> exited[0] = true);
        typeInsert(ed, "unsaved, but :q never checked this before");
        sendCommand(ed, "q");
        assertTrue("existing ':q' behavior (no unsaved check) is unchanged", exited[0]);
        passed("testExistingQStillExitsWithoutCheck");
    }

    static void testQaDoesNotCollideWithQ() {
        // ":q" と ":qa" は別コマンドとして扱われ、":q" が exitAllCallback を、
        // ":qa" が exitCallback を誤って呼ばないことを確認する。
        ModalEditor ed = new ModalEditor("");
        boolean[] exitCalled = {false};
        boolean[] exitAllCalled = {false};
        ed.setExitCallback(() -> exitCalled[0] = true);
        ed.setExitAllCallback(() -> exitAllCalled[0] = true);

        sendCommand(ed, "qa");
        assertTrue("':qa' calls exitAllCallback, not exitCallback",
            exitAllCalled[0] && !exitCalled[0]);
        passed("testQaDoesNotCollideWithQ (qa branch)");

        exitCalled[0] = false;
        exitAllCalled[0] = false;
        sendCommand(ed, "q");
        assertTrue("':q' calls exitCallback, not exitAllCallback",
            exitCalled[0] && !exitAllCalled[0]);
        passed("testQaDoesNotCollideWithQ (q branch)");
    }

    static void testQBangIsStillUnknownCommand() {
        // このエディタには ":q!" は元々実装されていない。今回の変更で誤って
        // ":qa!" 用の分岐が ":q!" にもマッチしてしまっていないかを確認する。
        ModalEditor ed = new ModalEditor("");
        sendCommand(ed, "q!");
        assertTrue("':q!' remains an unrecognized command (pre-existing behavior, unchanged)",
            ed.getStatusMessage().contains("unknown command"));
        passed("testQBangIsStillUnknownCommand");
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private static void typeInsert(ModalEditor editor, String text) {
        editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        editor.processKey(KeyEvent.VK_UNDEFINED, 'i', 0);
        for (char c : text.toCharArray()) {
            editor.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        }
        editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
    }

    private static void sendCommand(ModalEditor editor, String cmd) {
        editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        editor.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : cmd.toCharArray()) {
            editor.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        }
        editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
    }

    private static void assertTrue(String name, boolean condition) {
        if (!condition) fail(name);
    }

    private static void passed(String name) {
        passed++;
        System.out.println("[OK] " + name);
    }

    private static void fail(String name) {
        failed++;
        System.out.println("[FAIL] " + name);
    }
}
