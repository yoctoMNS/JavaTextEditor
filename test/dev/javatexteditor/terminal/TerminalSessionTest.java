package dev.javatexteditor.terminal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TerminalSession は子プロセス起動のみでGUIに依存しないため、ProjectBuilder同様
 * 実際にサブプロセスを起動して検証する（F10/F11のjava実行プロセスと違い、GUIイベントディスパッチ
 * やSwingUtilities.invokeLaterへの依存がないため自動テストが可能）。
 */
public class TerminalSessionTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testResolveShellCommandNonEmpty();
        testResolveShellCommandWindowsIsCmdExe();
        testStartAndEchoOutput();
        testWriteSendsInputToShell();
        testDestroyForciblyStopsProcess();
        testIsAliveFalseBeforeStart();
        testOnExitCalledAfterProcessExits();

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

    static void testResolveShellCommandNonEmpty() {
        List<String> cmd = TerminalSession.resolveShellCommand();
        assertTrue("resolveShellCommand is non-empty", !cmd.isEmpty());
    }

    static void testResolveShellCommandWindowsIsCmdExe() {
        // 実行OSに関わらずロジックだけ検証したいが、os.nameを差し替えるAPIはJDK標準に無いため、
        // 現在のOSでの解決結果が「Windowsならcmd.exe単体」「それ以外ならshell + -i の2要素」という
        // 契約を満たしていることだけを確認する。
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> cmd = TerminalSession.resolveShellCommand();
        if (os.contains("win")) {
            assertTrue("windows resolves to cmd.exe", cmd.equals(List.of("cmd.exe")));
        } else {
            assertTrue("non-windows resolves to [shell, -i]", cmd.size() == 2 && cmd.get(1).equals("-i"));
        }
    }

    static void testIsAliveFalseBeforeStart() {
        TerminalSession session = new TerminalSession();
        assertTrue("isAlive false before start", !session.isAlive());
    }

    static void testStartAndEchoOutput() throws Exception {
        if (isWindows()) { skip("testStartAndEchoOutput (Windows未検証)"); return; }
        TerminalSession session = new TerminalSession();
        StringBuilder collected = new StringBuilder();
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        Path workDir = Files.createTempDirectory("terminal-session-test");
        session.start(workDir,
            chunk -> { synchronized (collected) { collected.append(chunk); } },
            chunk -> { synchronized (collected) { collected.append(chunk); } },
            code -> exitCode.set(code));
        assertTrue("isAlive true after start", session.isAlive());
        session.write("echo TERMINAL_SESSION_TEST_MARKER\n");
        boolean found = waitUntil(() -> {
            synchronized (collected) { return collected.toString().contains("TERMINAL_SESSION_TEST_MARKER"); }
        }, 5000);
        assertTrue("echoed output observed", found);
        session.write("exit\n");
        boolean exited = waitUntil(() -> !session.isAlive(), 3000);
        assertTrue("session exits after 'exit'", exited);
    }

    static void testWriteSendsInputToShell() throws Exception {
        if (isWindows()) { skip("testWriteSendsInputToShell (Windows未検証)"); return; }
        TerminalSession session = new TerminalSession();
        StringBuilder collected = new StringBuilder();
        Path workDir = Files.createTempDirectory("terminal-session-test");
        session.start(workDir,
            chunk -> { synchronized (collected) { collected.append(chunk); } },
            chunk -> { synchronized (collected) { collected.append(chunk); } },
            code -> {});
        session.write("expr 40 + 2\n");
        boolean found = waitUntil(() -> {
            synchronized (collected) { return collected.toString().contains("42"); }
        }, 5000);
        assertTrue("computed shell output observed", found);
        session.destroyForcibly();
    }

    static void testDestroyForciblyStopsProcess() throws Exception {
        if (isWindows()) { skip("testDestroyForciblyStopsProcess (Windows未検証)"); return; }
        TerminalSession session = new TerminalSession();
        AtomicReference<Integer> exitCode = new AtomicReference<>();
        Path workDir = Files.createTempDirectory("terminal-session-test");
        session.start(workDir, chunk -> {}, chunk -> {}, exitCode::set);
        assertTrue("alive before destroy", session.isAlive());
        session.destroyForcibly();
        boolean stopped = waitUntil(() -> !session.isAlive(), 3000);
        assertTrue("not alive after destroyForcibly", stopped);
    }

    static void testOnExitCalledAfterProcessExits() throws Exception {
        if (isWindows()) { skip("testOnExitCalledAfterProcessExits (Windows未検証)"); return; }
        TerminalSession session = new TerminalSession();
        AtomicReference<Integer> exitCode = new AtomicReference<>();
        Path workDir = Files.createTempDirectory("terminal-session-test");
        session.start(workDir, chunk -> {}, chunk -> {}, exitCode::set);
        session.write("exit 7\n");
        boolean called = waitUntil(() -> exitCode.get() != null, 5000);
        assertTrue("onExit callback invoked", called);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void skip(String name) {
        System.out.println("  SKIP: " + name);
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
