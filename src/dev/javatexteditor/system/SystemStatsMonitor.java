package dev.javatexteditor.system;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CPU温度・GPU温度・メモリ使用率をバックグラウンドスレッドで定期的に取得し、
 * EDT（drawStatusLine）からは非ブロッキングでキャッシュ済みラベルを読めるようにする。
 * センサー/コマンドが利用できない環境（コンテナ等）では該当項目を "N/A" として
 * graceful degradation する（K/gr 等の既存パターンと同じ方針）。
 */
public final class SystemStatsMonitor {

    public static final SystemStatsMonitor INSTANCE = new SystemStatsMonitor();

    private static final long REFRESH_INTERVAL_SECONDS = 2;
    private static final String NOT_AVAILABLE = "N/A";
    private static final Path THERMAL_ROOT = Path.of("/sys/class/thermal");
    private static final long GPU_QUERY_TIMEOUT_MS = 1500;

    private volatile String cachedLabel = "";

    private SystemStatsMonitor() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "system-stats-monitor");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::refresh, 0, REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** ステータスライン描画用のラベルを返す（非ブロッキング）。 */
    public String getStatusLabel() {
        return cachedLabel;
    }

    private void refresh() {
        try {
            String cpu = readCpuTempCelsius().map(t -> t + "°C").orElse(NOT_AVAILABLE);
            String gpu = readGpuTempCelsius().map(t -> t + "°C").orElse(NOT_AVAILABLE);
            String mem = readMemoryUsagePercent().map(p -> p + "%").orElse(NOT_AVAILABLE);
            cachedLabel = "CPU " + cpu + " | GPU " + gpu + " | MEM " + mem;
        } catch (RuntimeException e) {
            cachedLabel = "";
        }
    }

    /** Linux の /sys/class/thermal からCPU温度（摂氏）を読む。他OS・センサー無しなら empty。 */
    Optional<Integer> readCpuTempCelsius() {
        if (!Files.isDirectory(THERMAL_ROOT)) {
            return Optional.empty();
        }
        try (var zones = Files.list(THERMAL_ROOT)) {
            List<Path> zoneDirs = zones
                .filter(p -> p.getFileName().toString().startsWith("thermal_zone"))
                .sorted()
                .toList();
            Path preferred = null;
            Path fallback = null;
            for (Path zone : zoneDirs) {
                Path tempFile = zone.resolve("temp");
                if (!Files.isReadable(tempFile)) continue;
                if (fallback == null) fallback = zone;
                Path typeFile = zone.resolve("type");
                if (Files.isReadable(typeFile)) {
                    String type = Files.readString(typeFile).trim().toLowerCase();
                    if (type.contains("cpu") || type.contains("x86_pkg_temp")) {
                        preferred = zone;
                        break;
                    }
                }
            }
            Path chosen = (preferred != null) ? preferred : fallback;
            if (chosen == null) return Optional.empty();
            String raw = Files.readString(chosen.resolve("temp")).trim();
            int milliDegrees = Integer.parseInt(raw);
            return Optional.of(Math.round(milliDegrees / 1000.0f));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** nvidia-smi があればGPU温度（摂氏）を読む。無ければ empty（他ベンダGPU/コンテナ環境等）。 */
    Optional<Integer> readGpuTempCelsius() {
        try {
            Process process = new ProcessBuilder(
                "nvidia-smi", "--query-gpu=temperature.gpu", "--format=csv,noheader,nounits")
                .redirectErrorStream(true)
                .start();
            boolean finished = process.waitFor(GPU_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Optional.empty();
            }
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes()).trim();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            String firstLine = output.lines().findFirst().orElse("").trim();
            return Optional.of(Integer.parseInt(firstLine));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** JDK標準の com.sun.management.OperatingSystemMXBean からメモリ使用率(%)を読む。 */
    Optional<Integer> readMemoryUsagePercent() {
        var osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            long total = sunBean.getTotalMemorySize();
            long free = sunBean.getFreeMemorySize();
            if (total <= 0) return Optional.empty();
            long used = total - free;
            int percent = (int) Math.round(used * 100.0 / total);
            return Optional.of(percent);
        }
        return Optional.empty();
    }
}
