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
        if (gpuCommandMissing) return Optional.empty();
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
     * 外部コマンドの実行ファイルが見つからないことが判明したら true になる（GPU項目を恒久的に省く）。
     *
     * <p>2026-08のメモリ/CPU調査で追加した。nvidia-smi が無い環境（GPU非搭載・他ベンダGPU・
     * コンテナ等）でも {@link #REFRESH_INTERVAL_SECONDS} 秒ごとに永久にプロセス起動を試み続けており、
     * 起動失敗のたびに {@link IOException} の生成（スタックトレース込み）とプロセス起動処理のコストを
     * 払っていた。実行ファイルが存在しないことは実行中に変わらないため、1度分かったら以後は試さない。
     * 起動はできたが失敗した場合（タイムアウト・非0終了）は一時的な事象でありうるので、
     * 従来どおり次回も試す。
     */
    private volatile boolean gpuCommandMissing = false;

    /**
     * 外部コマンドを実行し、標準出力（native.encodingでデコード）をtrimして返す。
     * 起動失敗・タイムアウト・非0終了はすべて null（=呼び出し側で empty 扱い）に統一する。
     */
    private String runCommand(String... command) {
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
            // ProcessBuilder.start() の IOException は実質「実行ファイルが無い」を意味する。
            gpuCommandMissing = true;
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

    /**
     * /proc/meminfo を読み込むための使い回しバッファ。全体でも 2KB 程度のファイルで、
     * 必要な MemTotal / MemAvailable は先頭数行に現れる。
     *
     * <p>{@link #refresh()} を実行する単一スレッドからしか触らないため同期は不要。
     */
    private final byte[] meminfoBuffer = new byte[8192];

    /**
     * Linux専用: /proc/meminfo の MemTotal/MemAvailable からメモリ使用率(%)を読む。
     *
     * <p>{@code Files.readAllLines} + 行ごとの {@code String.replaceAll} をやめてバイト列を
     * 直接走査しているのは、この処理が {@link #REFRESH_INTERVAL_SECONDS} 秒ごとに永久に走り続ける
     * ためである（2026-08 メモリ調査）。旧実装は1回ごとに 8KB の文字バッファ・全行ぶんの
     * {@link String}・行ごとの正規表現 {@link java.util.regex.Matcher} を生成しており、
     * アイドル時のアロケーションプロファイルに現れる程度には無視できない量になっていた。
     */
    private Optional<Integer> readMemoryUsagePercentFromMeminfo() {
        try (var in = Files.newInputStream(MEMINFO_PATH)) {
            int length = in.readNBytes(meminfoBuffer, 0, meminfoBuffer.length);
            long total = findMeminfoKb(meminfoBuffer, length, "MemTotal:");
            long available = findMeminfoKb(meminfoBuffer, length, "MemAvailable:");
            if (total <= 0 || available < 0) return Optional.empty();
            long used = total - available;
            return Optional.of((int) Math.round(used * 100.0 / total));
        } catch (IOException | RuntimeException e) {
            // 古いカーネル（MemAvailable未対応）・コンテナ環境でprocfsが読めない場合等はフォールバック。
            return Optional.empty();
        }
    }

    /**
     * "MemAvailable:   15866900 kB" のような行を行頭のラベルで探し、kB単位の数値を返す。
     * 見つからなければ -1。/proc/meminfo は ASCII のみなのでバイト単位で比較してよい。
     */
    private static long findMeminfoKb(byte[] buffer, int length, String label) {
        int lineStart = 0;
        while (lineStart < length) {
            int lineEnd = lineStart;
            while (lineEnd < length && buffer[lineEnd] != '\n') lineEnd++;
            if (startsWith(buffer, lineStart, lineEnd, label)) {
                return parseFirstNumber(buffer, lineStart + label.length(), lineEnd);
            }
            lineStart = lineEnd + 1;
        }
        return -1;
    }

    private static boolean startsWith(byte[] buffer, int from, int to, String label) {
        if (to - from < label.length()) return false;
        for (int i = 0; i < label.length(); i++) {
            if (buffer[from + i] != (byte) label.charAt(i)) return false;
        }
        return true;
    }

    /** [from, to) の範囲に現れる最初の10進数を返す。数字が無ければ -1。 */
    private static long parseFirstNumber(byte[] buffer, int from, int to) {
        int i = from;
        while (i < to && (buffer[i] < '0' || buffer[i] > '9')) i++;
        if (i >= to) return -1;
        long value = 0;
        while (i < to && buffer[i] >= '0' && buffer[i] <= '9') {
            value = value * 10 + (buffer[i] - '0');
            i++;
        }
        return value;
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
