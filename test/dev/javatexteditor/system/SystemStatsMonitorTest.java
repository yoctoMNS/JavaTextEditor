package dev.javatexteditor.system;

import java.util.Optional;

/**
 * SystemStatsMonitor（CPU使用率・GPU使用率・メモリ使用率）のテスト。
 * CI/コンテナ環境にはNVIDIA GPU（nvidia-smi）が存在しないことが多いため、
 * 具体的な使用率の値ではなく「値が取れる場合は妥当な範囲」「取れない場合は
 * ラベルからその項目が省略される」ことを検証する。
 * CPU使用率はJDK標準の com.sun.management.OperatingSystemMXBean 経由のため
 * Linux/Windows/macOSいずれでも通常は必ず値が返る。
 */
public class SystemStatsMonitorTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testMemoryUsageIsWithinValidRangeOrAbsent();
        testCpuUsageIsWithinValidRangeOrAbsent();
        testGpuUsageIsWithinValidRangeOrAbsent();
        testStatusLabelOmitsAbsentItems();
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

    static void testCpuUsageIsWithinValidRangeOrAbsent() {
        Optional<Integer> cpu = SystemStatsMonitor.INSTANCE.readCpuUsagePercent();
        // getCpuLoad()はJDK標準実装がLinux/Windows/macOS全てに持つため、通常は必ず値が返る。
        // 起動直後で値が未確定な場合のみ empty になりうる。
        assertTrue("cpu usage percent absent or within 0-100",
            cpu.isEmpty() || (cpu.get() >= 0 && cpu.get() <= 100));
    }

    static void testGpuUsageIsWithinValidRangeOrAbsent() {
        Optional<Integer> gpu = SystemStatsMonitor.INSTANCE.readGpuUsagePercent();
        // nvidia-smiが無い環境（このコンテナ含む）ではempty。あれば0-100%の範囲になっているはず。
        assertTrue("gpu usage percent absent or within 0-100",
            gpu.isEmpty() || (gpu.get() >= 0 && gpu.get() <= 100));
    }

    static void testStatusLabelOmitsAbsentItems() throws Exception {
        // バックグラウンドスレッドの初回refresh完了を少し待つ。
        Thread.sleep(200);
        String label = SystemStatsMonitor.INSTANCE.getStatusLabel();
        // "N/A" という文字列そのものがラベルに含まれてはいけない（取れない項目は丸ごと省略する仕様）。
        assertTrue("label never contains the literal N/A", !label.contains("N/A"));
        // CPU使用率はほぼ全環境で取れるため、通常はCPUセクションが含まれる。
        assertTrue("label contains CPU section", label.contains("CPU "));
        assertTrue("label contains MEM section", label.contains("MEM "));
        // GPUはnvidia-smiが無い環境（このコンテナ含む）では省略されているはず。
        boolean gpuPresent = SystemStatsMonitor.INSTANCE.readGpuUsagePercent().isPresent();
        assertTrue("label GPU section matches availability",
            gpuPresent == label.contains("GPU "));
    }

    static void testStatusLabelIsNonBlockingAfterConstruction() {
        long start = System.nanoTime();
        SystemStatsMonitor.INSTANCE.getStatusLabel();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("getStatusLabel returns immediately (non-blocking)", elapsedMs < 50);
    }
}
