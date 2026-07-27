package dev.javatexteditor.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 起動時のセットアップスクリプト（{@code scripts/setup.sh} / {@code scripts/setup.bat}）を
 * 必要に応じてバックグラウンド実行する。
 *
 * <p>{@code Main} から切り出した（MAIN_DECOMPOSITION_PLAN.md 段階1）。
 * このクラスは {@code Main} の他のフィールド・メソッドに一切依存しない独立したブロックである。
 *
 * <p><b>基準クラス（anchor）について</b>: パスの解決は
 * {@code Class#getProtectionDomain().getCodeSource().getLocation()} を起点に行うため、
 * 「どのクラスを基準にするか」で結果が変わりうる。切り出し前は {@code Main.class} を
 * 基準にしていたので、その挙動をそのまま保つために基準クラスを引数で受け取る。
 * {@code SetupBootstrap.class} をハードコードしてはならない
 * （現在の構成ではクラスパスのルートが同じため同じ値になるが、
 * 将来クラスの出力先を分けた場合に静かに壊れる）。
 */
public final class SetupBootstrap {

    private SetupBootstrap() {}

    /**
     * lib/src.zip または lib/openjdk-native/ が存在しない場合、
     * セットアップスクリプトをバックグラウンドスレッドで自動実行する。
     * エディタの起動は待たずに続行する。
     *
     * @param anchor パス解決の基準にするクラス（呼び出し側は {@code Main.class} を渡す）
     */
    public static void runIfNeeded(Class<?> anchor) {
        Path libDir = resolveLibDir(anchor);
        boolean hasSrcZip    = Files.exists(libDir.resolve("src.zip"));
        boolean hasNativeSrc = Files.isDirectory(libDir.resolve("openjdk-native"));
        if (hasSrcZip && hasNativeSrc) return;

        Thread.ofVirtual().name("setup-auto").start(() -> {
            String os = System.getProperty("os.name", "").toLowerCase();
            boolean isWindows = os.contains("win");
            Path scriptDir = resolveScriptDir(anchor);
            Path script = isWindows
                ? scriptDir.resolve("setup.bat")
                : scriptDir.resolve("setup.sh");

            if (!Files.exists(script)) {
                System.err.println("[setup] Script not found: " + script);
                return;
            }

            System.out.println("[setup] Running " + script.getFileName() + " in background...");
            try {
                ProcessBuilder pb = isWindows
                    ? new ProcessBuilder("cmd.exe", "/c", script.toString())
                    : new ProcessBuilder("bash", script.toString());
                pb.directory(scriptDir.getParent().toFile());
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                // 子プロセス（cmd.exe/xcopy/git 等）の出力はOSのネイティブエンコーディング
                // （Windowsではコンソールのコードページ、日本語版なら通常 CP932）でバイト列化される。
                // JDK 18+ の既定文字セットは JEP 400 により常に UTF-8 になっているため、
                // InputStreamReader をそのまま使うと非ASCII文字（日本語のシステムメッセージ等）が
                // 文字化けする。native.encoding（無ければ sun.jnu.encoding）で明示的にデコードする。
                String nativeEncodingName = System.getProperty("native.encoding",
                    System.getProperty("sun.jnu.encoding", "UTF-8"));
                java.nio.charset.Charset nativeEncoding;
                try {
                    nativeEncoding = java.nio.charset.Charset.forName(nativeEncodingName);
                } catch (Exception e) {
                    nativeEncoding = java.nio.charset.Charset.defaultCharset();
                }
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream(), nativeEncoding))) {
                    reader.lines().forEach(line -> System.out.println("[setup] " + line));
                }
                int exit = proc.waitFor();
                if (exit == 0) {
                    System.out.println("[setup] Done.");
                } else {
                    System.err.println("[setup] Exited with code " + exit);
                }
            } catch (Exception e) {
                System.err.println("[setup] Failed: " + e.getMessage());
            }
        });
    }

    /**
     * lib ディレクトリを解決する。
     *
     * <p>注記（MAIN_DECOMPOSITION_PLAN.md 段階1 の「気づき」）: この探索は
     * {@code analysis.CodeSourceLocator#findUpward} とほぼ同じ処理を手書きで再実装したものである
     * （{@link #resolveScriptDir} は既に {@code CodeSourceLocator} を使っている）。
     * 共通化の余地があるが、段階1は「振る舞いを変えない」ことを条件としているため
     * 本文には手を入れず、切り出しのみを行った。統合するかどうかは別途判断する。
     */
    private static Path resolveLibDir(Class<?> anchor) {
        try {
            var url = anchor.getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                Path code = Paths.get(url.toURI());
                Path dir = Files.isDirectory(code) ? code : code.getParent();
                for (int i = 0; i < 4; i++) {
                    if (dir == null) break;
                    Path candidate = dir.resolve("lib");
                    if (Files.isDirectory(candidate)) return candidate;
                    // lib がなくても返す（初回は存在しないのが普通）
                    if (Files.isDirectory(dir.resolve("scripts"))) return dir.resolve("lib");
                    dir = dir.getParent();
                }
            }
        } catch (Exception ignored) {}
        return Path.of("lib");
    }

    private static Path resolveScriptDir(Class<?> anchor) {
        return dev.javatexteditor.analysis.CodeSourceLocator
                .findUpward(anchor, "scripts", 4, Files::isDirectory)
                .orElse(Path.of("scripts"));
    }
}
