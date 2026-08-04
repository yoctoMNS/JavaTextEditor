package dev.javatexteditor.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * {@link RunningProcessHolder#terminateAndStart} の検証。
 * 単発呼び出しの基本動作に加え、F11連打・マクロ連打を模した「複数スレッドからの
 * 同時呼び出し」でも孤児プロセス（destroy()されずに残るプロセス）が発生しないことを
 * 実プロセスで確認する。
 */
public class RunningProcessHolderTest {

    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) throws Exception {
        testSingleStartLeavesOneAliveProcess();
        testSecondStartTerminatesThePreviousOne();
        testConcurrentCallsLeaveExactlyOneAliveProcess();

        int fail = total - pass;
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) System.exit(1);
    }

    static void check(String name, long expected, long actual) {
        total++;
        boolean ok = expected == actual;
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " (expected=" + expected + ", actual=" + actual + ")");
        if (ok) pass++;
    }

    static void check(String name, boolean expected, boolean actual) {
        total++;
        boolean ok = expected == actual;
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " (expected=" + expected + ", actual=" + actual + ")");
        if (ok) pass++;
    }

    private static void testSingleStartLeavesOneAliveProcess() throws Exception {
        RunningProcessHolder holder = new RunningProcessHolder();
        Process p = holder.terminateAndStart(() -> startSleepyChild(5000));
        try {
            check("単発起動: 起動直後は生存している", true, p.isAlive());
        } finally {
            p.destroyForcibly();
            p.waitFor();
        }
    }

    private static void testSecondStartTerminatesThePreviousOne() throws Exception {
        RunningProcessHolder holder = new RunningProcessHolder();
        Process first = holder.terminateAndStart(() -> startSleepyChild(5000));
        Process second = holder.terminateAndStart(() -> startSleepyChild(5000));
        // destroy()はOSへのシグナル送信であり非同期のため、反映されるまで少し待つ。
        boolean firstTerminated = first.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        check("2回目の起動で1回目のプロセスが終了させられる", true, firstTerminated);
        check("2回目のプロセスは生存している", true, second.isAlive());
        second.destroyForcibly();
        second.waitFor();
    }

    /** F11連打・マクロ連打の再現: 複数スレッドから同時に terminateAndStart を呼んでも、最終的に生きているのは1つだけ。 */
    private static void testConcurrentCallsLeaveExactlyOneAliveProcess() throws Exception {
        RunningProcessHolder holder = new RunningProcessHolder();
        int concurrency = 20;
        List<Process> started = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();
                    Process p = holder.terminateAndStart(() -> startSleepyChild(8000));
                    started.add(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        startGate.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        // destroy()の非同期反映を待つ（生存プロセス数が1に収束するまで最大3秒ポーリング）。
        long aliveCount = -1;
        for (int i = 0; i < 30; i++) {
            aliveCount = started.stream().filter(Process::isAlive).count();
            if (aliveCount <= 1) break;
            Thread.sleep(100);
        }

        check("20回同時に連打しても起動されたプロセス数はconcurrency分だけ存在する",
            concurrency, started.size());
        check("同時連打しても、最終的に生存しているプロセスはちょうど1つ（孤児プロセスなし）",
            1, aliveCount);

        started.forEach(Process::destroyForcibly);
        for (Process p : started) p.waitFor();
    }

    private static Process startSleepyChild(long sleepMs) throws java.io.IOException {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder pb = new ProcessBuilder(
            javaBin, "-cp", "build", "dev.javatexteditor.app.SleepyMain", String.valueOf(sleepMs));
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectErrorStream(true);
        return pb.start();
    }
}
