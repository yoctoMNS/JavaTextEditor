package dev.javatexteditor.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link SingleInstanceGuard} の検証。同一プロセス内での二重取得だけでなく、
 * 実際のバグ報告（同一コマンドのプロセスが増殖する）を再現するため、
 * 自分自身を {@code --hold-lock} 引数付きで子プロセスとして起動し、
 * 「実行中のプロセスに対する2回目の起動がブロックされるか」
 * 「強制終了(kill -9相当)後にロックが解放され再起動できるか」を
 * 実プロセス間で確認する。
 */
public class SingleInstanceGuardTest {

    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) throws Exception {
        // 子プロセス役: 引数で指定されたディレクトリのロックを取得し、
        // 取得結果を1行出力してから常駐する（親プロセスに destroy されるまで）。
        if (args.length > 0 && args[0].equals("--hold-lock")) {
            Path dir = Path.of(args[1]);
            boolean acquired = SingleInstanceGuard.tryAcquire(dir);
            System.out.println(acquired ? "ACQUIRED" : "FAILED");
            System.out.flush();
            Thread.sleep(60_000);
            return;
        }
        // 子プロセス役（正常終了版）: ロックを取得して1行出力した直後に return し、
        // JVMを正常終了させる（シャットダウンフックによる解放を確認する）。
        if (args.length > 0 && args[0].equals("--hold-lock-then-exit")) {
            Path dir = Path.of(args[1]);
            boolean acquired = SingleInstanceGuard.tryAcquire(dir);
            System.out.println(acquired ? "ACQUIRED" : "FAILED");
            return;
        }

        testAcquireSucceedsOnFreshDir();
        testSecondAcquireInSameJvmFails();
        testDifferentProjectRootsAreIndependent();
        testSecondProcessBlockedWhileFirstIsRunning();
        testLockReleasedAfterGracefulExit();
        testLockReleasedAfterForcefulKill();

        int fail = total - pass;
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) System.exit(1);
    }

    static void check(String name, boolean expected, boolean actual) {
        total++;
        boolean ok = expected == actual;
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " (expected=" + expected + ", actual=" + actual + ")");
        if (ok) pass++;
    }

    static void check(String name, String expected, String actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " (expected=" + expected + ", actual=" + actual + ")");
        if (ok) pass++;
    }

    private static Path freshTempDir(String prefix) throws IOException {
        Path dir = Files.createTempDirectory(prefix);
        dir.toFile().deleteOnExit();
        return dir;
    }

    private static void testAcquireSucceedsOnFreshDir() throws IOException {
        Path dir = freshTempDir("sig-fresh-");
        boolean acquired = SingleInstanceGuard.tryAcquire(dir);
        check("新規プロジェクトディレクトリでの初回ロック取得は成功する", true, acquired);
    }

    private static void testSecondAcquireInSameJvmFails() throws IOException {
        // 同一JVM内での2回目のtryLock()はOverlappingFileLockExceptionになる
        // （JDKがJVMプロセス単位でロック領域の重複を検出するため、プロセスをまたがなくても再現できる）。
        Path dir = freshTempDir("sig-samejvm-");
        boolean first = SingleInstanceGuard.tryAcquire(dir);
        boolean second = SingleInstanceGuard.tryAcquire(dir);
        check("1回目の取得は成功する", true, first);
        check("同一JVM内での2回目の取得は失敗として扱われる", false, second);
    }

    private static void testDifferentProjectRootsAreIndependent() throws IOException {
        Path dirA = freshTempDir("sig-indep-a-");
        Path dirB = freshTempDir("sig-indep-b-");
        boolean okA = SingleInstanceGuard.tryAcquire(dirA);
        boolean okB = SingleInstanceGuard.tryAcquire(dirB);
        check("プロジェクトAのロック取得は成功する", true, okA);
        check("プロジェクトBは別ロックのため独立して成功する", true, okB);
    }

    /** 実際のバグ報告の再現: 起動中のプロセスに対する2回目の起動がブロックされること。 */
    private static void testSecondProcessBlockedWhileFirstIsRunning() throws Exception {
        Path dir = freshTempDir("sig-cross-block-");
        Process child = startChild("--hold-lock", dir);
        try {
            String line = readOneLine(child);
            check("子プロセスがロックを取得できている", "ACQUIRED", line);

            boolean blockedInParent = SingleInstanceGuard.tryAcquire(dir);
            check("子プロセスが起動中の間、親プロセスからの取得はブロックされる", false, blockedInParent);
        } finally {
            child.destroyForcibly();
            child.waitFor();
        }
    }

    /** 子プロセスが正常終了した後は、ロックが解放され再取得できること。 */
    private static void testLockReleasedAfterGracefulExit() throws Exception {
        Path dir = freshTempDir("sig-graceful-");
        Process child = startChild("--hold-lock-then-exit", dir);
        String line = readOneLine(child);
        child.waitFor();
        check("子プロセスがロックを取得できている（正常終了シナリオ）", "ACQUIRED", line);

        boolean reacquired = SingleInstanceGuard.tryAcquire(dir);
        check("子プロセスの正常終了後は同じディレクトリを再取得できる", true, reacquired);
    }

    /** kill -9 相当（destroyForcibly）で子プロセスを強制終了した場合も、OSがロックを自動解放すること。 */
    private static void testLockReleasedAfterForcefulKill() throws Exception {
        Path dir = freshTempDir("sig-killed-");
        Process child = startChild("--hold-lock", dir);
        String line = readOneLine(child);
        check("子プロセスがロックを取得できている（強制終了シナリオ）", "ACQUIRED", line);

        child.destroyForcibly();
        child.waitFor();

        boolean reacquired = SingleInstanceGuard.tryAcquire(dir);
        check("子プロセスをkill -9相当で強制終了した後は再取得できる（OSによる自動解放）", true, reacquired);
    }

    private static Process startChild(String mode, Path dir) throws IOException {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder pb = new ProcessBuilder(
            javaBin, "-cp", "build", "dev.javatexteditor.app.SingleInstanceGuardTest",
            mode, dir.toString());
        pb.redirectErrorStream(true);
        return pb.start();
    }

    /**
     * 子プロセスの出力から "ACQUIRED"/"FAILED" の行だけを読み取る。
     * 実行環境によっては JVM 起動時に "Picked up JAVA_TOOL_OPTIONS: ..." 等の
     * 診断バナーが標準出力/標準エラーに混入することがあるため、それらは読み飛ばす。
     */
    private static String readOneLine(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.equals("ACQUIRED") || line.equals("FAILED")) return line;
            }
            return "";
        }
    }
}
