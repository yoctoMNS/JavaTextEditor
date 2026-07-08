package dev.javatexteditor.system;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CPU温度・GPU温度・メモリ使用率をバックグラウンドスレッドで定期的に取得し、
 * EDT（drawStatusLine）からは非ブロッキングでキャッシュ済みラベルを読めるようにする。
 * センサー/コマンドが利用できない環境（コンテナ・非NVIDIA GPU・ACPI非対応機等）では
 * 該当項目を "N/A" として graceful degradation する（K/gr 等の既存パターンと同じ方針）。
 *
 * JDK標準APIには温度を返すクロスプラットフォームなAPIが存在しないため、
 * CPU/GPU温度はいずれもOS判定（{@code os.name}）に応じてコマンドを切り替える方式を取る。
 * どのOSでも「値が取れなければ空を返す」契約は共通なので、呼び出し側（refresh）は
 * OSを意識せず readCpuTempCelsius()/readGpuTempCelsius() を呼ぶだけでよい。
 */
public final class SystemStatsMonitor {

    public static final SystemStatsMonitor INSTANCE = new SystemStatsMonitor();

    private static final long REFRESH_INTERVAL_SECONDS = 2;
    private static final String NOT_AVAILABLE = "N/A";
    private static final Path THERMAL_ROOT = Path.of("/sys/class/thermal");
    private static final long COMMAND_TIMEOUT_MS = 1500;

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

    /** OS判定に応じてCPU温度（摂氏）を読む。取得できない環境なら empty。 */
    Optional<Integer> readCpuTempCelsius() {
        return switch (currentOs()) {
            case WINDOWS -> readCpuTempWindows();
            case MAC -> readCpuTempMac();
            case LINUX, OTHER -> readCpuTempLinux();
        };
    }

    /** Linux の /sys/class/thermal からCPU温度（摂氏）を読む。センサー無しなら empty。 */
    Optional<Integer> readCpuTempLinux() {
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
                    String type = Files.readString(typeFile).trim().toLowerCase(Locale.ROOT);
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

    /**
     * Windows の WMI（root/wmi 名前空間の MSAcpi_ThermalZoneTemperature）を PowerShell 経由で読む。
     * この値は10分の1ケルビン単位で返るため摂氏に変換する。ACPIが温度ゾーンを公開していない
     * 機種（多くのノートPC等）では該当インスタンスが存在せず empty になる（既知の制約）。
     */
    Optional<Integer> readCpuTempWindows() {
        String output = runCommand(
            "powershell", "-NoProfile", "-NonInteractive", "-Command",
            "(Get-CimInstance -Namespace root/wmi -ClassName MSAcpi_ThermalZoneTemperature "
                + "-ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty CurrentTemperature)");
        if (output == null) return Optional.empty();
        String firstLine = output.lines().findFirst().orElse("").trim();
        if (firstLine.isEmpty()) return Optional.empty();
        try {
            double tenthsKelvin = Double.parseDouble(firstLine);
            double celsius = tenthsKelvin / 10.0 - 273.15;
            return Optional.of((int) Math.round(celsius));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * macOS では標準コマンド・JDK標準APIのみでCPU温度を取得する手段が無いため、
     * Homebrewで導入可能な {@code osx-cpu-temp} コマンドを試す（未導入なら empty）。
     */
    Optional<Integer> readCpuTempMac() {
        String output = runCommand("osx-cpu-temp");
        if (output == null) return Optional.empty();
        return parseLeadingDecimalCelsius(output);
    }

    /**
     * nvidia-smi があればGPU温度（摂氏）を読む。無ければ empty（他ベンダGPU/コンテナ環境等）。
     * nvidia-smi はNVIDIAドライバに同梱されLinux/Windows双方でPATHに登録されるため、
     * OS判定なしで共通に試すだけでよい（macOSはNVIDIAドライバが提供されないため自然にempty）。
     */
    Optional<Integer> readGpuTempCelsius() {
        String output = runCommand(
            "nvidia-smi", "--query-gpu=temperature.gpu", "--format=csv,noheader,nounits");
        if (output == null) return Optional.empty();
        String firstLine = output.lines().findFirst().orElse("").trim();
        try {
            return Optional.of(Integer.parseInt(firstLine));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * 外部コマンドを実行し、標準出力（native.encodingでデコード）をtrimして返す。
     * 起動失敗・タイムアウト・非0終了はすべて null（=呼び出し側で empty 扱い）に統一する。
     */
    private static String runCommand(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            boolean finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), nativeEncoding()).trim();
            }
            if (process.exitValue() != 0 || output.isEmpty()) {
                return null;
            }
            return output;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** "55.6°C" のような出力から先頭の小数を摂氏として取り出す。 */
    private static Optional<Integer> parseLeadingDecimalCelsius(String output) {
        String firstLine = output.lines().findFirst().orElse("").trim();
        String digits = firstLine.replaceAll("[^0-9.].*$", "");
        if (digits.isEmpty()) return Optional.empty();
        try {
            return Optional.of((int) Math.round(Double.parseDouble(digits)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** サブプロセス出力読み取り用のOSネイティブエンコーディング（windows-batch-and-subprocessスキル参照）。 */
    private static Charset nativeEncoding() {
        String name = System.getProperty("native.encoding",
            System.getProperty("sun.jnu.encoding", "UTF-8"));
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return Charset.defaultCharset();
        }
    }

    private enum Os { LINUX, WINDOWS, MAC, OTHER }

    private static Os currentOs() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return Os.WINDOWS;
        if (name.contains("mac") || name.contains("darwin")) return Os.MAC;
        if (name.contains("linux")) return Os.LINUX;
        return Os.OTHER;
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
