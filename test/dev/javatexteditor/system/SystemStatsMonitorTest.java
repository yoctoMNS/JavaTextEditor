package dev.javatexteditor.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * SystemStatsMonitor（CPU温度・GPU温度・メモリ使用率）のテスト。
 * CI/コンテナ環境には温度センサーやnvidia-smiが存在しないことが多いため、
 * 具体的な温度値ではなく「値が取れる場合は妥当な範囲」「取れない場合は
 * N/A として graceful degradation する」ことを検証する。
 * hwmon/thermal_zone経由の読み取りロジックは、偽装したsysfsツリーを
 * 一時ディレクトリに作って直接検証する。
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
        testHwmonCoretempIsPreferredOverGenericThermalZone();
        testHwmonPrefersPackageLabelOverOtherSensors();
        testFallsBackToThermalZoneWhenHwmonAbsent();
        testEmptyWhenNeitherHwmonNorThermalZoneHasCpuData();

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
        // センサーが無い環境ではempty、ある環境では現実的な温度範囲になっているはず。
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

    /**
     * Fedora等で実際に起きる状況の再現: /sys/class/thermal には "acpitz"（CPU以外の周辺温度）
     * しかなく、実際のCPUダイ温度は /sys/class/hwmon の coretemp からしか取れない。
     * hwmon を優先して読み、thermal_zone側の acpitz を誤って採用しないことを確認する。
     */
    static void testHwmonCoretempIsPreferredOverGenericThermalZone() throws IOException {
        Path root = Files.createTempDirectory("sysstats-fedora");
        Path hwmon = createHwmonDevice(root, "hwmon0", "coretemp");
        Files.writeString(hwmon.resolve("temp1_input"), "52000\n");

        Path thermal = root.resolve("thermal");
        Path zone = thermal.resolve("thermal_zone0");
        Files.createDirectories(zone);
        Files.writeString(zone.resolve("type"), "acpitz\n");
        Files.writeString(zone.resolve("temp"), "30000\n");

        Optional<Integer> result = SystemStatsMonitor.INSTANCE.readCpuTempCelsius(root.resolve("hwmon"), thermal);
        assertTrue("hwmon coretemp value used", result.equals(Optional.of(52)));
    }

    static void testHwmonPrefersPackageLabelOverOtherSensors() throws IOException {
        Path root = Files.createTempDirectory("sysstats-hwmon-label");
        Path hwmon = createHwmonDevice(root, "hwmon0", "coretemp");
        Files.writeString(hwmon.resolve("temp1_input"), "40000\n");
        Files.writeString(hwmon.resolve("temp1_label"), "Core 0\n");
        Files.writeString(hwmon.resolve("temp2_input"), "48000\n");
        Files.writeString(hwmon.resolve("temp2_label"), "Package id 0\n");

        Optional<Integer> result = SystemStatsMonitor.INSTANCE.readCpuTempCelsius(
            root.resolve("hwmon"), root.resolve("thermal-does-not-exist"));
        assertTrue("Package-labeled sensor preferred", result.equals(Optional.of(48)));
    }

    static void testFallsBackToThermalZoneWhenHwmonAbsent() throws IOException {
        Path root = Files.createTempDirectory("sysstats-thermal-only");
        Path zone = root.resolve("thermal/thermal_zone0");
        Files.createDirectories(zone);
        Files.writeString(zone.resolve("type"), "x86_pkg_temp\n");
        Files.writeString(zone.resolve("temp"), "55000\n");

        Optional<Integer> result = SystemStatsMonitor.INSTANCE.readCpuTempCelsius(
            root.resolve("hwmon-does-not-exist"), root.resolve("thermal"));
        assertTrue("falls back to thermal_zone when no hwmon", result.equals(Optional.of(55)));
    }

    static void testEmptyWhenNeitherHwmonNorThermalZoneHasCpuData() throws IOException {
        Path root = Files.createTempDirectory("sysstats-none");
        Optional<Integer> result = SystemStatsMonitor.INSTANCE.readCpuTempCelsius(
            root.resolve("hwmon-does-not-exist"), root.resolve("thermal-does-not-exist"));
        assertTrue("empty when no sensor sources exist", result.isEmpty());
    }

    private static Path createHwmonDevice(Path root, String deviceName, String driverName) throws IOException {
        Path device = root.resolve("hwmon").resolve(deviceName);
        Files.createDirectories(device);
        Files.writeString(device.resolve("name"), driverName + "\n");
        return device;
    }
}
