package dev.javatexteditor.system;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
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
