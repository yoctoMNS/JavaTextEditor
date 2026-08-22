package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.util.List;

/**
 * ":restart"（アプリケーション再起動・未保存拒否）/ ":restart!"（強制再起動）の統合テスト。
 * 実プロセス起動（{@link ModalEditor#setRestartCallback}未使用時のデフォルト実装、
 * {@code performRestart()}）はテストから直接検証しない（新JVMプロセスを実際に起動してしまうため）。
 * :qa/:qa! と同じ未保存変更ガードのロジックを共有していること、":qa"/":restart" が互いに
 * 誤って衝突しないことをmainメソッド形式のテストハーネス（JUnit不使用）で確認する。
 */
public class RestartCommandTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testRestartCallsCallbackWhenNoUnsavedChanges();
        testRestartBlocksWhenUnsavedChangesExist();
        testRestartBlockMessageListsFilePath();
        testRestartAcrossMultipleEditorsBlocksIfAnyUnsaved();
        testRestartBangForcesRestartDespiteUnsavedChanges();
        testRestartDoesNotCollideWithQa();

        System.out.println("\n=== RestartCommand: " + passed + "/" + (passed + failed) + " PASS ===");
        System.exit(failed > 0 ? 1 : 0);
    }

    static void testRestartCallsCallbackWhenNoUnsavedChanges() {
        ModalEditor ed = new ModalEditor("hello");
        boolean[] restarted = {false};
        ed.setRestartCallback(() -> restarted[0] = true);
        sendCommand(ed, "restart");
        assertTrue("':restart' invokes the restart callback when nothing is unsaved", restarted[0]);
        passed("testRestartCallsCallbackWhenNoUnsavedChanges");
    }

    static void testRestartBlocksWhenUnsavedChangesExist() {
        ModalEditor ed = new ModalEditor("");
        boolean[] restarted = {false};
        ed.setRestartCallback(() -> restarted[0] = true);
        typeInsert(ed, "unsaved edit");
        sendCommand(ed, "restart");
        assertTrue("':restart' does not restart with unsaved changes", !restarted[0]);
        assertTrue("':restart' reports E37-style message",
            ed.getStatusMessage().contains("No write since last change"));
        passed("testRestartBlocksWhenUnsavedChangesExist");
    }

    static void testRestartBlockMessageListsFilePath() {
        ModalEditor ed = new ModalEditor("", "/tmp/example.txt", null);
        typeInsert(ed, "x");
        sendCommand(ed, "restart");
        assertTrue("blocked ':restart' message names the file",
            ed.getStatusMessage().contains("/tmp/example.txt"));
        passed("testRestartBlockMessageListsFilePath");
    }

    static void testRestartAcrossMultipleEditorsBlocksIfAnyUnsaved() {
        ModalEditor edA = new ModalEditor("saved");
        ModalEditor edB = new ModalEditor("");
        typeInsert(edB, "dirty");
        List<ModalEditor> all = List.of(edA, edB);
        edA.setAllEditorsSupplier(() -> all);
        boolean[] restarted = {false};
        edA.setRestartCallback(() -> restarted[0] = true);

        sendCommand(edA, "restart");

        assertTrue("':restart' blocked because a sibling editor has unsaved changes", !restarted[0]);
        passed("testRestartAcrossMultipleEditorsBlocksIfAnyUnsaved");
    }

    static void testRestartBangForcesRestartDespiteUnsavedChanges() {
        ModalEditor ed = new ModalEditor("");
        boolean[] restarted = {false};
        ed.setRestartCallback(() -> restarted[0] = true);
        typeInsert(ed, "unsaved edit");
        sendCommand(ed, "restart!");
        assertTrue("':restart!' force-restarts despite unsaved changes", restarted[0]);
        passed("testRestartBangForcesRestartDespiteUnsavedChanges");
    }

    static void testRestartDoesNotCollideWithQa() {
        // ":restart" と ":qa" は別コマンドとして扱われ、互いのコールバックを誤って呼ばないことを確認する。
        ModalEditor ed = new ModalEditor("");
        boolean[] restartCalled = {false};
        boolean[] exitAllCalled = {false};
        ed.setRestartCallback(() -> restartCalled[0] = true);
        ed.setExitAllCallback(() -> exitAllCalled[0] = true);

        sendCommand(ed, "restart");
        assertTrue("':restart' calls restartCallback, not exitAllCallback",
            restartCalled[0] && !exitAllCalled[0]);
        passed("testRestartDoesNotCollideWithQa (restart branch)");

        restartCalled[0] = false;
        exitAllCalled[0] = false;
        sendCommand(ed, "qa");
        assertTrue("':qa' calls exitAllCallback, not restartCallback",
            exitAllCalled[0] && !restartCalled[0]);
        passed("testRestartDoesNotCollideWithQa (qa branch)");
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
