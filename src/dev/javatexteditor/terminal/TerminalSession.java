package dev.javatexteditor.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * OS標準シェル（Windows: cmd.exe / それ以外: $SHELL または /bin/sh）を子プロセスとして起動し、
 * 標準入出力をやり取りする対話型ターミナルセッション。Swing/AWTに一切依存しない純粋な
 * プロセスライフサイクル管理クラス（ProjectBuilder/MainClassFinderと同じ位置づけ）。
 *
 * このプロジェクトは真のPTY（疑似端末）を実装できない（CLAUDE.mdの「外部ライブラリ一切不使用・
 * javac直接呼び出し」方針上、PTY操作にはJNI/ネイティブコードが必要になるため採用不可。
 * SystemStatsMonitorのCPU温度取得でnative実装を見送った判断と同じ理由）。そのため:
 * - vim/less/top等フルスクリーンで画面を書き換えるプログラムは正しく描画されない（raw modeがない）。
 * - Ctrl+Cは本物のSIGINT転送ができないため、呼び出し側は destroyForcibly() で代替する
 *   （プロセスを強制終了し、以後は新しいセッションとして再起動する運用になる）。
 * - シェル側の readline（行編集・補完・履歴）はttyが無いと無効化されるため、ユーザーが入力した
 *   文字はシェルからエコーバックされない。呼び出し側（ModalEditor）でローカルエコーし、
 *   Enterで1行分をまとめて {@link #write} する「行ベース」の運用を前提とする。
 */
public final class TerminalSession {

    private final Charset nativeEncoding;
    private Process process;
    private OutputStream stdin;
    private volatile boolean alive = false;

    public TerminalSession() {
        this.nativeEncoding = resolveNativeEncoding();
    }

    /** OSに応じた対話型シェルの起動コマンドを決定する。 */
    public static List<String> resolveShellCommand() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("cmd.exe");
        }
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            shell = "/bin/sh";
        }
        // -i: tty が無い状態でも対話的シェルとして起動する（プロンプト・エイリアス等を有効にする）。
        return List.of(shell, "-i");
    }

    public boolean isAlive() {
        return alive && process != null && process.isAlive();
    }

    /**
     * シェルを起動し、標準出力/標準エラーをそれぞれ別の仮想スレッドで読み取る。
     * 1回のread()ごとにANSIエスケープシーケンスを除去したチャンクを onStdout/onStderr へ渡す
     * （行区切りを待たない。シェルのプロンプトは末尾に改行を含まないため、行単位の読み取りだと
     * 表示されなくなってしまう）。呼び出し側（Main.java）はこれらのコールバック内で
     * SwingUtilities.invokeLater 等によるUIスレッドへのディスパッチを行う責務を持つ
     * （本クラス自体はSwingに依存しない）。
     */
    public void start(Path workingDir, Consumer<String> onStdout, Consumer<String> onStderr,
            IntConsumer onExit) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(resolveShellCommand());
        pb.directory(workingDir.toFile());
        // TERM=dumb: readline系の行編集機能を無効化し、多くのCLIツールに色/カーソル移動系の
        // エスケープシーケンス出力を抑制させる（AnsiEscapeFilterはあくまで安全網）。
        pb.environment().put("TERM", "dumb");
        process = pb.start();
        stdin = process.getOutputStream();
        alive = true;
        startReader(process.getInputStream(), onStdout);
        startReader(process.getErrorStream(), onStderr);
        Thread.ofVirtual().start(() -> {
            try {
                int code = process.waitFor();
                alive = false;
                onExit.accept(code);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void startReader(InputStream in, Consumer<String> onChunk) {
        Thread.ofVirtual().start(() -> {
            AnsiEscapeFilter filter = new AnsiEscapeFilter();
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    String decoded = new String(buf, 0, n, nativeEncoding);
                    String cleaned = filter.filter(decoded);
                    if (!cleaned.isEmpty()) {
                        onChunk.accept(cleaned);
                    }
                }
            } catch (IOException ignored) {
                // プロセス終了に伴うストリームクローズは正常系として無視する
            }
        });
    }

    /** 確定した1行（末尾の改行込み）をシェルの標準入力へ書き込む。セッション未起動時は無視する。 */
    public void write(String text) {
        if (stdin == null) return;
        try {
            stdin.write(text.getBytes(nativeEncoding));
            stdin.flush();
        } catch (IOException ignored) {
            // 書き込み失敗＝プロセスが既に終了している等。onExitコールバック側で検知されるため無視する。
        }
    }

    /** Ctrl+C相当。真のSIGINT転送はできないため、プロセスを強制終了する（呼び出し側で再起動が必要）。 */
    public void destroyForcibly() {
        if (process != null) {
            process.destroyForcibly();
        }
        alive = false;
    }

    /** windows-batch-and-subprocess スキルのルール3準拠: サブプロセスの実出力はOSネイティブエンコーディング。 */
    private static Charset resolveNativeEncoding() {
        String name = System.getProperty("native.encoding",
            System.getProperty("sun.jnu.encoding", "UTF-8"));
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}
