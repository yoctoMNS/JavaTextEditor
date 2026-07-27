package dev.javatexteditor.app;

import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.projectbuild.BuildResult;
import dev.javatexteditor.projectbuild.CProjectBuilder;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.SwingUtilities;

/**
 * F10/F11/F12 の C 版（gcc/clang/cc を外部起動）。Java 版と同じ {@code *compile*} /
 * {@code *run*} 疑似バッファ・ストリーミング表示・多重実行防止（{@link RunningProcessHolder}）を
 * 再利用する。
 * Java 版と異なりクラスパス入力プロンプト（enterClasspathInput）は挟まず直接コンパイルする
 * （C にはクラスパスの概念がないため）。
 *
 * <p>{@code Main} から切り出した（MAIN_DECOMPOSITION_PLAN.md 段階2）。
 *
 * <p><b>{@link RunningProcessHolder} は {@link JavaBuildRunner} と共有すること。</b>
 * 理由は当該クラスの Javadoc を参照。
 *
 * <p><b>{@code EditorCanvas canvas} 引数は削除済み</b>（MAIN_DECOMPOSITION_PLAN.md R-7）。
 * 経緯は {@link JavaBuildRunner} の Javadoc を参照。再び追加しないこと。
 */
public final class CBuildRunner {

    private final CProjectBuilder builder;
    private final RunningProcessHolder running;

    public CBuildRunner(CProjectBuilder builder, RunningProcessHolder running) {
        this.builder = builder;
        this.running = running;
    }

    /** F10（C）: projectRoot 配下の全 .c を gcc で1実行ファイルにコンパイルし *compile* に表示する。 */
    public void triggerCompile(ModalEditor editor) {
        doCompile(editor, null);
    }

    /** F11（C）: 実行ファイルが無ければ拒否し、あれば実行する。 */
    public void triggerRun(ModalEditor editor) {
        Path projectRoot = editor.getBuildRoot();
        if (!builder.hasExecutable(projectRoot)) {
            editor.setStatusMessage("run: 実行ファイルがありません。先にF10でコンパイルしてください");
            return;
        }
        runCExecutable(editor, projectRoot);
    }

    /** F12（C）: コンパイル→成功時のみ実行。 */
    public void triggerCompileAndRun(ModalEditor editor) {
        doCompile(editor, result -> {
            if (result.success()) runCExecutable(editor, editor.getBuildRoot());
        });
    }

    /** F10/F12（C）共通のコンパイル実行部。diagnostic をリアルタイムに *compile* へ追記する。 */
    private void doCompile(ModalEditor editor,
            java.util.function.Consumer<BuildResult> onDone) {
        editor.beginCompileOutput();
        editor.syncCanvas();
        Path projectRoot = editor.getBuildRoot();
        Thread.ofVirtual().start(() -> {
            BuildResult result =
                builder.compile(projectRoot, diag ->
                    SwingUtilities.invokeLater(() -> {
                        editor.appendCompileDiagnostic(diag);
                        editor.syncCanvas();
                    }));
            SwingUtilities.invokeLater(() -> {
                editor.finishCompileOutput(result);
                editor.syncCanvas();
                if (onDone != null) onDone.accept(result);
            });
        });
    }

    /**
     * F11（C）: コンパイル済みの実行ファイルを別プロセスとして起動し、標準出力/標準エラーを
     * *run* 疑似バッファへリアルタイム表示する（JavaBuildRunner の runJavaClass の C 版）。
     */
    private void runCExecutable(ModalEditor editor, Path projectRoot) {
        running.terminateIfAlive();
        Path executable = builder.executableFor(projectRoot);
        String command = executable.toString();
        editor.beginRunOutput(command, executable.getFileName().toString());
        editor.syncCanvas();
        Thread.ofVirtual().start(() -> {
            int exitCode;
            try {
                ProcessBuilder pb = new ProcessBuilder(executable.toString());
                pb.directory(projectRoot.toFile());
                Process process = pb.start();
                running.set(process);
                Thread stdoutReader = ProcessOutputPump.start(process.getInputStream(), editor, false);
                Thread stderrReader = ProcessOutputPump.start(process.getErrorStream(), editor, true);
                exitCode = process.waitFor();
                stdoutReader.join();
                stderrReader.join();
            } catch (IOException e) {
                SwingUtilities.invokeLater(() ->
                    editor.setStatusMessage("run: プロセス起動に失敗しました: " + e.getMessage()));
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            int finalExitCode = exitCode;
            SwingUtilities.invokeLater(() -> {
                editor.finishRunOutput(executable.getFileName().toString(), finalExitCode);
                editor.syncCanvas();
            });
        });
    }
}
