package dev.javatexteditor.system;

import java.util.Optional;

/**
 * SystemStatsMonitor（CPU温度・GPU温度・メモリ使用率）のテスト。
 * CI/コンテナ環境には温度センサーやnvidia-smiが存在しないことが多いため、
 * 具体的な温度値ではなく「値が取れる場合は妥当な範囲」「取れない場合は
 * N/A として graceful degradation する」ことを検証する。
 * CPU/GPU温度の取得コマンドはOSごとに異なる（Linux=/sys/class/thermal、
 * Windows=WMI経由のPowerShell、macOS=osx-cpu-temp）ため、このコンテナ
 * （Linux）では readCpuTempLinux() 相当のパスのみ実機検証できる。
 */
public class SystemStatsMonitorTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testMemoryUsageIsWithinValidRangeOrAbsent();
        testCpuTempIsWithinValidRangeOrAbsent();
        testGpuTempIsWithinValidRangeOrAbsent();
        testStatusLabelFormat();
        testStatusLabelIsNonBlockingAfterConstruction();

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

    static void testMemoryUsageIsWithinValidRangeOrAbsent() {
        Optional<Integer> mem = SystemStatsMonitor.INSTANCE.readMemoryUsagePercent();
        // com.sun.management.OperatingSystemMXBean はJDK標準で常に利用可能なため、通常は必ず値が返る。
        assertTrue("memory usage percent present", mem.isPresent());
        mem.ifPresent(p -> assertTrue("memory usage percent within 0-100", p >= 0 && p <= 100));
    }

    static void testCpuTempIsWithinValidRangeOrAbsent() {
        Optional<Integer> cpu = SystemStatsMonitor.INSTANCE.readCpuTempCelsius();
        // センサー/コマンドが無い環境ではempty、ある環境では現実的な温度範囲になっているはず。
        assertTrue("cpu temp absent or plausible",
            cpu.isEmpty() || (cpu.get() >= -40 && cpu.get() <= 150));
    }

    static void testGpuTempIsWithinValidRangeOrAbsent() {
        Optional<Integer> gpu = SystemStatsMonitor.INSTANCE.readGpuTempCelsius();
        // nvidia-smiが無い環境（このコンテナ含む）ではempty。あれば現実的な範囲になっているはず。
        assertTrue("gpu temp absent or plausible",
            gpu.isEmpty() || (gpu.get() >= -40 && gpu.get() <= 150));
    }

    static void testStatusLabelFormat() throws Exception {
        // バックグラウンドスレッドの初回refresh完了を少し待つ。
        Thread.sleep(200);
        String label = SystemStatsMonitor.INSTANCE.getStatusLabel();
        assertTrue("label starts with CPU", label.startsWith("CPU "));
        assertTrue("label contains GPU section", label.contains(" | GPU "));
        assertTrue("label contains MEM section", label.contains(" | MEM "));
    }

    static void testStatusLabelIsNonBlockingAfterConstruction() {
        long start = System.nanoTime();
        SystemStatsMonitor.INSTANCE.getStatusLabel();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("getStatusLabel returns immediately (non-blocking)", elapsedMs < 50);
    }
}
