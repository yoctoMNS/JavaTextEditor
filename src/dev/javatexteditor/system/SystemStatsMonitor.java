package dev.javatexteditor.system;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CPU使用率・GPU使用率・メモリ使用率をバックグラウンドスレッドで定期的に取得し、
 * EDT（drawStatusLine）からは非ブロッキングでキャッシュ済みラベルを読めるようにする。
 * センサー/コマンドが利用できない環境（コンテナ・GPU非搭載・非NVIDIA GPU等）では、
 * その項目を "N/A" と表示するのではなく、ラベルから丸ごと省略する
 * （例: GPU非搭載ノートPCでは "CPU 12% | MEM 62%" のようにGPU部分が消える）。
 *
 * CPU使用率は com.sun.management.OperatingSystemMXBean#getCpuLoad() を使う。これはJDK標準の
 * 実装がLinux/Windows/macOSいずれにも同梱しているシステム全体のCPU使用率取得APIのため、
 * OS別のファイル/コマンドに依存せず全プラットフォームで動作する。
 *
 * メモリ使用率はLinuxでは /proc/meminfo の MemAvailable を優先して使う（詳細は
 * readMemoryUsagePercent() のJavadoc参照）。それ以外のOS・procfsが読めない環境では
 * 従来どおり OperatingSystemMXBean#getFreeMemorySize() ベースの計算にフォールバックする。
 */
public final class SystemStatsMonitor {

    public static final SystemStatsMonitor INSTANCE = new SystemStatsMonitor();

    private static final long REFRESH_INTERVAL_SECONDS = 2;
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
            List<String> parts = new ArrayList<>();
            readCpuUsagePercent().ifPresent(p -> parts.add("CPU " + p + "%"));
            readGpuUsagePercent().ifPresent(p -> parts.add("GPU " + p + "%"));
            readMemoryUsagePercent().ifPresent(p -> parts.add("MEM " + p + "%"));
            cachedLabel = String.join(" | ", parts);
        } catch (RuntimeException e) {
            cachedLabel = "";
        }
    }

    /**
     * JDK標準の com.sun.management.OperatingSystemMXBean からシステム全体のCPU使用率(%)を読む。
     * Linux/Windows/macOSいずれのJDK標準実装にも存在するため全プラットフォームで動作する。
     */
    Optional<Integer> readCpuUsagePercent() {
        var osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            double load = sunBean.getCpuLoad();
            if (load < 0) return Optional.empty(); // 起動直後等、値がまだ利用不可の場合は -1
            return Optional.of((int) Math.round(load * 100.0));
        }
        return Optional.empty();
    }

    /**
     * nvidia-smi があればGPU使用率(%)を読む。無ければ empty（GPU非搭載・他ベンダGPU・コンテナ環境等）。
     * nvidia-smi はNVIDIAドライバに同梱されLinux/Windows双方のPATHに追加されるため、
     * OS判定なしで共通に試すだけでよい（macOSはNVIDIAドライバが提供されないため自然にempty）。
     */
    Optional<Integer> readGpuUsagePercent() {
        String output = runCommand(
            "nvidia-smi", "--query-gpu=utilization.gpu", "--format=csv,noheader,nounits");
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

    private static final boolean IS_LINUX =
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("nux");
    private static final Path MEMINFO_PATH = Path.of("/proc/meminfo");

    /**
     * メモリ使用率(%)を読む。Linuxでは /proc/meminfo の MemAvailable を使い、
     * それ以外（取得失敗時含む）は com.sun.management.OperatingSystemMXBean にフォールバックする。
     *
     * getFreeMemorySize()（= Linuxの MemFree 相当）は再利用可能なディスクキャッシュ/バッファを
     * 「使用中」に含めてしまうため、free/htop/GNOME System Monitor 等の実際のシステムモニタが
     * 表示する使用率（MemAvailable基準）と大きく乖離する（アイドル時は数%の差だが、ディスクキャッシュが
     * 積み上がった状態では数十%の差になり得る）。MemAvailable はキャッシュ分を「回収可能な空き」として
     * 差し引き済みの値のため、これを使うことで実際のシステムモニタの表示と一致する。
     */
    Optional<Integer> readMemoryUsagePercent() {
        if (IS_LINUX) {
            Optional<Integer> fromMeminfo = readMemoryUsagePercentFromMeminfo();
            if (fromMeminfo.isPresent()) return fromMeminfo;
        }
        return readMemoryUsagePercentFromMxBean();
    }

    /** Linux専用: /proc/meminfo の MemTotal/MemAvailable からメモリ使用率(%)を読む。 */
    private Optional<Integer> readMemoryUsagePercentFromMeminfo() {
        try {
            long total = -1;
            long available = -1;
            for (String line : Files.readAllLines(MEMINFO_PATH)) {
                if (line.startsWith("MemTotal:")) {
                    total = parseMeminfoKb(line);
                } else if (line.startsWith("MemAvailable:")) {
                    available = parseMeminfoKb(line);
                }
                if (total >= 0 && available >= 0) break;
            }
            if (total <= 0 || available < 0) return Optional.empty();
            long used = total - available;
            return Optional.of((int) Math.round(used * 100.0 / total));
        } catch (IOException | RuntimeException e) {
            // 古いカーネル（MemAvailable未対応）・コンテナ環境でprocfsが読めない場合等はフォールバック。
            return Optional.empty();
        }
    }

    /** "MemAvailable:   15866900 kB" のようなkB単位の行から数値部分を読む。 */
    private static long parseMeminfoKb(String line) {
        String digits = line.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? -1 : Long.parseLong(digits);
    }

    /** JDK標準の com.sun.management.OperatingSystemMXBean からメモリ使用率(%)を読む（非Linux向けフォールバック）。 */
    private Optional<Integer> readMemoryUsagePercentFromMxBean() {
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
