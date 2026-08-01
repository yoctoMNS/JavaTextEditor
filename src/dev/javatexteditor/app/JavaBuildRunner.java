package dev.javatexteditor.app;

import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.projectbuild.BuildResult;
import dev.javatexteditor.projectbuild.MainClassFinder;
import dev.javatexteditor.projectbuild.ProjectBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;

/**
 * F10/F11/F12 の Java 版。プロジェクト全体を {@code javax.tools.JavaCompiler} で
 * コンパイルし、main クラスを解決して別プロセスで実行する。
 *
 * <p>{@code Main} から切り出した（MAIN_DECOMPOSITION_PLAN.md 段階2）。
 * 切り出し前の各メソッドは {@code (ModalEditor, EditorCanvas)} を引数で受け取り
 * {@code root[]}/{@code active[]} に触れていなかったため、単独で移動できた。
 *
 * <p><b>{@link RunningProcessHolder} は {@link CBuildRunner} と共有すること。</b>
 * 理由は当該クラスの Javadoc を参照。
 *
 * <p><b>{@code EditorCanvas canvas} 引数を削除した経緯（MAIN_DECOMPOSITION_PLAN.md R-7）</b>:
 * 切り出し前は全メソッドが {@code canvas} を受け取っていたが、
 * このビルド・実行系では一度も使われていなかった（引数として引き回されるだけで
 * {@code canvas.} の呼び出しが1件も無かった）。段階2では「振る舞いを変えない」ことを
 * 優先していったん残し、段階3の完了後に削除した。
 * 進捗表示・出力はすべて {@code ModalEditor} の疑似バッファ経由で行われるため、
 * ここから {@code EditorCanvas} を触る必要はない。再び追加しないこと。
 */
public final class JavaBuildRunner {

    private final ProjectBuilder builder;
    private final MainClassFinder mainClassFinder;
    private final RunningProcessHolder running;

    /**
     * main クラスが複数見つかり telescope-picker で選択待ちになる場合に、
     * 入力済みの追加クラスパスを選択確定まで持ち越すための一時領域。
     *
     * <p>{@code ModalEditor.setOnRunMainClassSelected} はペイン生成時に1度だけ登録される
     * コールバックのため、選択確定時点では元のクロージャに追加クラスパスを持たせられない。
     * 切り出し前の {@code Main.pendingRunExtraClasspath} と同じ割り切り
     * （複数ペインで同時に F11 を使うケースはスコープ外）。
     */
    private List<Path> pendingRunExtraClasspath = List.of();

    public JavaBuildRunner(ProjectBuilder builder, MainClassFinder mainClassFinder,
            RunningProcessHolder running) {
        this.builder = builder;
        this.mainClassFinder = mainClassFinder;
        this.running = running;
    }

    /**
     * F10: 追加クラスパス（複数ディレクトリ、カンマ区切り）を尋ねてからプロジェクト全体を
     * コンパイルし、*compile* 疑似バッファに結果を表示する。Escなら追加クラスパスなしで続行する。
     */
    public void triggerCompile(ModalEditor editor) {
        editor.enterClasspathInput("F10",
            extraClasspath -> doCompile(editor, extraClasspath, null));
    }

    /** F11: bin/ に .class がなければ拒否し、あれば追加クラスパスを尋ねて main クラスを解決・実行する。 */
    public void triggerRun(ModalEditor editor) {
        Path projectRoot = editor.getBuildRoot();
        if (!builder.hasCompiledClasses(projectRoot)) {
            editor.setStatusMessage("run: no .class files in bin/. Compile with F10 first");
            return;
        }
        editor.enterClasspathInput("F11",
            extraClasspath -> resolveAndRunMainClass(editor, projectRoot, extraClasspath));
    }

    /**
     * F12: 追加クラスパスを尋ねてからコンパイルし、成功した場合のみ同じ追加クラスパスで
     * main クラスを解決して実行する。
     */
    public void triggerCompileAndRun(ModalEditor editor) {
        editor.enterClasspathInput("F12", extraClasspath -> {
            Path projectRoot = editor.getBuildRoot();
            doCompile(editor, extraClasspath, result -> {
                if (result.success()) resolveAndRunMainClass(editor, projectRoot, extraClasspath);
            });
        });
    }

    /**
     * telescope-picker で main クラスが選択されたときに呼ぶ。
     * 追加クラスパスは {@link #pendingRunExtraClasspath} に持ち越したものを
     * <b>呼び出し時点で</b>読み出す（切り出し前も同じく、コールバック実行時に読んでいた）。
     */
    public void runSelectedMainClass(ModalEditor editor, Path projectRoot,
            String fqcn) {
        runJavaClass(editor, projectRoot, fqcn, pendingRunExtraClasspath);
    }

    /**
     * F10/F12共通のコンパイル実行部。onDone は完了後にEDT上で呼ばれる（null可）。
     * javacが診断を報告するたび *compile* 疑似バッファへリアルタイムに追記する。
     */
    private void doCompile(ModalEditor editor, List<Path> extraClasspath,
            java.util.function.Consumer<BuildResult> onDone) {
        editor.beginCompileOutput();
        editor.syncCanvas();
        Path projectRoot = editor.getBuildRoot();
        Thread.ofVirtual().start(() -> {
            BuildResult result =
                builder.compile(projectRoot, extraClasspath, diag ->
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
     * main メソッドを持つクラスを索引から探し、1件なら即実行、複数なら telescope-picker で選ばせる
     * （{@code ModalEditor#setOnRunMainClassSelected} 経由で選択結果が
     * {@link #runSelectedMainClass} に届く）。
     */
    private void resolveAndRunMainClass(
            ModalEditor editor, Path projectRoot, List<Path> extraClasspath) {
        editor.setStatusMessage("searching for main class...");
        Thread.ofVirtual().start(() -> {
            List<String> mainClasses = mainClassFinder.findMainClasses(projectRoot);
            SwingUtilities.invokeLater(() -> {
                if (mainClasses.isEmpty()) {
                    editor.setStatusMessage("run: no class with a main method was found");
                } else if (mainClasses.size() == 1) {
                    runJavaClass(editor, projectRoot, mainClasses.get(0), extraClasspath);
                } else {
                    pendingRunExtraClasspath = extraClasspath;
                    editor.enterMainClassPicker(mainClasses);
                }
            });
        });
    }

    /**
     * bin/（常にデフォルトで含まれる）＋ユーザー指定の追加クラスパスで別プロセスとして java を起動する。
     * 実行中プロセスがまだ生きていれば destroy() してから起動し直す（多重実行を避けるため）。
     * 標準出力/標準エラーは別々のスレッドで読み取り、*run* 疑似バッファへ1行ずつリアルタイムに
     * 追記する（標準エラー由来の行は赤字表示。EditorCanvas.setErrorLines参照）。
     */
    private void runJavaClass(ModalEditor editor, Path projectRoot, String fqcn,
            List<Path> extraClasspath) {
        running.terminateIfAlive();
        Path binDir = builder.binDirFor(projectRoot);
        StringBuilder classpath = new StringBuilder(binDir.toString());
        for (Path p : extraClasspath) {
            classpath.append(java.io.File.pathSeparatorChar).append(p);
        }
        String command = "java -cp " + classpath + " " + fqcn;
        editor.beginRunOutput(command, fqcn);
        editor.syncCanvas();
        Thread.ofVirtual().start(() -> {
            int exitCode;
            try {
                ProcessBuilder pb = new ProcessBuilder("java", "-cp", classpath.toString(), fqcn);
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
                    editor.setStatusMessage("run: failed to start process: " + e.getMessage()));
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            int finalExitCode = exitCode;
            SwingUtilities.invokeLater(() -> {
                editor.finishRunOutput(fqcn, finalExitCode);
                editor.syncCanvas();
            });
        });
    }
}
