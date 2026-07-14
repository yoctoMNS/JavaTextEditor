package dev.javatexteditor;

import dev.javatexteditor.analysis.AnalysisException;
import dev.javatexteditor.analysis.AutoImportHandler;
import dev.javatexteditor.analysis.CompileAnalyzer;
import dev.javatexteditor.analysis.CompileDiagnostic;
import dev.javatexteditor.analysis.ImportSuggester;
import dev.javatexteditor.analysis.JdkClassIndex;
import dev.javatexteditor.analysis.SourceAnalyzer;
import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.ui.EditorCanvas;
import dev.javatexteditor.ui.Theme;
import dev.javatexteditor.ui.TtfMonoFont;
import java.awt.Color;
import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

public class Main {

    private static final Color ACTIVE_BORDER_COLOR = new Color(0x88, 0x88, 0xFF);
    private static final int WINDOW_WIDTH  = 1200;
    private static final int WINDOW_HEIGHT = 750;

    // Ctrl+Alt+矢印: アクティブペインのリサイズ量・最小ペインサイズ（ピクセル）
    private static final int PANE_RESIZE_STEP_PX = 20;
    private static final int PANE_RESIZE_MIN_PX   = 60;

    // 作業ディレクトリの中央管理（main() で初期化）
    private static WorkingDirectoryManager WD_MANAGER;

    private static final CompileAnalyzer COMPILE_ANALYZER = new CompileAnalyzer();
    private static final JdkClassIndex JDK_INDEX = JdkClassIndex.build();
    private static final SourceAnalyzer SOURCE_ANALYZER = new SourceAnalyzer();
    private static final ImportSuggester IMPORT_SUGGESTER = new ImportSuggester(JDK_INDEX);
    private static final AutoImportHandler AUTO_IMPORT_HANDLER =
        new AutoImportHandler(IMPORT_SUGGESTER, SOURCE_ANALYZER);
    private static dev.javatexteditor.analysis.CompletionIndex COMPLETION_INDEX = null;
    private static dev.javatexteditor.analysis.WordIndex WORD_INDEX = null;

    // -------------------------------------------------------------------------
    // F10/F11/F12: プロジェクト全体のコンパイル・実行
    // -------------------------------------------------------------------------
    private static final dev.javatexteditor.projectbuild.ProjectBuilder PROJECT_BUILDER =
        new dev.javatexteditor.projectbuild.ProjectBuilder();
    private static final dev.javatexteditor.projectbuild.MainClassFinder MAIN_CLASS_FINDER =
        new dev.javatexteditor.projectbuild.MainClassFinder();
    // F11/F12 で起動した直近の子プロセス。もう一度実行されたら前回分を destroy() してから起動し直す。
    private static Process runningProcess = null;
    // F11でmainクラスが複数見つかりtelescope選択待ちの間、選択確定後の実行まで
    // ユーザーが入力した追加クラスパスを持ち越すための一時保存（onRunMainClassSelectedコールバックは
    // createLeaf内で固定で1回だけ登録されるため、選択待ちの間はここに置くしかない）。
    private static List<Path> pendingRunExtraClasspath = List.of();

    // -------------------------------------------------------------------------
    // グローバルバッファレジストリ（SPC+b で表示される開いたバッファの一覧）
    // -------------------------------------------------------------------------
    private static final List<dev.javatexteditor.telescope.BufferPicker.BufferEntry> BUFFER_REGISTRY =
        new ArrayList<>();

    private static synchronized void registerBuffer(dev.javatexteditor.telescope.BufferPicker.BufferEntry entry) {
        if (entry.filePath() == null) return;
        // 同じパスが既にあれば重複登録しない
        for (var e : BUFFER_REGISTRY) {
            if (entry.filePath().equals(e.filePath())) return;
        }
        BUFFER_REGISTRY.add(entry);
    }

    private static synchronized void unregisterBuffer(dev.javatexteditor.telescope.BufferPicker.BufferEntry entry) {
        if (entry.filePath() == null) return;
        BUFFER_REGISTRY.removeIf(e -> entry.filePath().equals(e.filePath()));
    }

    private static synchronized List<dev.javatexteditor.telescope.BufferPicker.BufferEntry> getBufferRegistry() {
        return new ArrayList<>(BUFFER_REGISTRY);
    }

    // -------------------------------------------------------------------------
    // ペインツリー
    // -------------------------------------------------------------------------

    sealed interface PaneNode permits Leaf, Split {}

    record Leaf(EditorCanvas canvas, ModalEditor editor) implements PaneNode {}

    static final class Split implements PaneNode {
        final int orientation; // JSplitPane.HORIZONTAL_SPLIT or VERTICAL_SPLIT
        PaneNode left, right;
        Split(int orientation, PaneNode left, PaneNode right) {
            this.orientation = orientation;
            this.left  = left;
            this.right = right;
        }
    }

    /** ツリーを Swing コンポーネントに変換する。 */
    private static Component buildComponent(PaneNode node) {
        return switch (node) {
            case Leaf l -> l.canvas();
            case Split s -> {
                JSplitPane sp = new JSplitPane(s.orientation,
                    buildComponent(s.left), buildComponent(s.right));
                sp.setResizeWeight(0.5);
                sp.setDividerSize(4);
                sp.setBorder(null);
                yield sp;
            }
        };
    }

    /** ツリー内のすべてのリーフを収集する。 */
    private static List<Leaf> allLeaves(PaneNode node) {
        List<Leaf> result = new ArrayList<>();
        collectLeaves(node, result);
        return result;
    }

    private static void collectLeaves(PaneNode node, List<Leaf> out) {
        switch (node) {
            case Leaf l    -> out.add(l);
            case Split s   -> { collectLeaves(s.left, out); collectLeaves(s.right, out); }
        }
    }

    /**
     * target リーフを指定の向きで分割し、右/下に新リーフを挿入した新ツリーを返す。
     * root が target 自身の場合は Split を直接返す。
     */
    private static PaneNode splitLeaf(PaneNode node, Leaf target, Leaf newLeaf, int orientation) {
        return switch (node) {
            case Leaf l -> (l == target)
                ? new Split(orientation, l, newLeaf)
                : l;
            case Split s -> {
                s.left  = splitLeaf(s.left,  target, newLeaf, orientation);
                s.right = splitLeaf(s.right, target, newLeaf, orientation);
                yield s;
            }
        };
    }

    /**
     * target リーフを取り除いたツリーを返す。
     * 親 Split は兄弟ノードに置き換わる。
     * ルートが target の場合は null を返す（最後の1ペイン）。
     */
    private static PaneNode removeLeaf(PaneNode node, Leaf target) {
        return switch (node) {
            case Leaf l -> (l == target) ? null : l;
            case Split s -> {
                PaneNode newLeft  = removeLeaf(s.left,  target);
                PaneNode newRight = removeLeaf(s.right, target);
                if (newLeft  == null) yield newRight;
                if (newRight == null) yield newLeft;
                s.left  = newLeft;
                s.right = newRight;
                yield s;
            }
        };
    }

    // -------------------------------------------------------------------------
    // 画面操作
    // -------------------------------------------------------------------------

    // 起動時にマウスカーソルのあるディスプレイの解像度から算出する初期フォントセルサイズ
    // （4K等の高解像度ディスプレイでデフォルトフォントが小さすぎるのを防ぐ）。
    // 以後はユーザーが Ctrl+Shift+矢印で自由に変更できる。
    private static int initialCellW = TtfMonoFont.BASE_CELL_W;
    private static int initialCellH = TtfMonoFont.BASE_CELL_H;

    /** design baseline: フルHD(1920px幅)でBASE_CELL_W/Hがちょうど良い大きさになる想定 */
    private static final double BASELINE_SCREEN_WIDTH_PX = 1920.0;

    /**
     * 指定ディスプレイの物理解像度（OSのHiDPIスケーリングも加味）に応じて、
     * ベースラインからの拡大率を算出する。縮小はしない（下限は等倍）。
     */
    private static double computeDisplayScale(GraphicsConfiguration gc) {
        double scaleX;
        try {
            scaleX = gc.getDefaultTransform().getScaleX();
        } catch (Exception e) {
            scaleX = 1.0;
        }
        double physicalWidthPx = gc.getBounds().width * scaleX;
        double scale = physicalWidthPx / BASELINE_SCREEN_WIDTH_PX;
        return Math.max(1.0, Math.min(2.5, scale));
    }

    private static int[] computeInitialCellSize(double scale) {
        int w = (int) Math.round(TtfMonoFont.BASE_CELL_W * scale);
        int h = (int) Math.round(TtfMonoFont.BASE_CELL_H * scale);
        return new int[] { w, h };
    }

    /**
     * フォントセルサイズと同じ倍率でウィンドウサイズも拡大する。
     * これをしないと、文字は大きくなるのに表示行数・桁数の見た目上の割合が変わり、
     * スプラッシュ画面やステータスラインがウィンドウ下端からはみ出す。
     * 画面の利用可能領域（タスクバー等を除く）を超えないようクランプする。
     */
    private static int[] computeInitialWindowSize(GraphicsConfiguration gc, double scale) {
        int w = (int) Math.round(WINDOW_WIDTH * scale);
        int h = (int) Math.round(WINDOW_HEIGHT * scale);
        Rectangle screen = gc.getBounds();
        java.awt.Insets insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc);
        int maxW = screen.width  - insets.left - insets.right;
        int maxH = screen.height - insets.top  - insets.bottom;
        return new int[] { Math.min(w, maxW), Math.min(h, maxH) };
    }

    private static GraphicsConfiguration detectMouseScreen() {
        try {
            Point mouse = MouseInfo.getPointerInfo().getLocation();
            for (GraphicsDevice gd : GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getScreenDevices()) {
                GraphicsConfiguration gc = gd.getDefaultConfiguration();
                if (gc.getBounds().contains(mouse)) return gc;
            }
        } catch (Exception ignored) {}
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
    }

    private static void centerOnScreen(JFrame frame, GraphicsConfiguration gc) {
        Rectangle bounds = gc.getBounds();
        int x = bounds.x + (bounds.width  - frame.getWidth())  / 2;
        int y = bounds.y + (bounds.height - frame.getHeight()) / 2;
        frame.setLocation(x, y);
    }

    private static void setupCompileAnalysis(ModalEditor editor, EditorCanvas canvas) {
        Runnable trigger = () -> {
            if (!isJavaBuffer(editor)) return;
            editor.setStatusMessage("auto-import: 解析中...");
            runCompileAnalysis(editor, canvas, true, "auto-import: 解析失敗");
        };
        // INSERT→NORMAL 遷移時: IMEを半角英数字に切り替えてからコンパイル解析を実行する
        editor.setOnReturnToNormal(() -> {
            canvas.switchToHalfWidth();
            trigger.run();
        });
        editor.setOnSave(trigger);
        // Ctrl+Shift+O: コンパイル→未定義シンボルへの import 挿入→未使用 import 削除
        editor.setOnOrganizeImports(() -> {
            if (!isJavaBuffer(editor)) {
                editor.setStatusMessage("E: Javaファイルではありません");
                return;
            }
            editor.setStatusMessage("import 整理中...");
            runCompileAnalysis(editor, canvas, false, "E: コンパイル解析失敗");
        });
        // dd/p/u/Ctrl+R等、INSERT離脱・保存を経由しないバッファ変更操作は上記2フックの対象外で、
        // 行が増減しても診断（ガターの赤線）が古い行番号のまま残り、保存するまで直らない不具合が
        // あった。バッファのversionが変わるたびに再解析するが、INSERT中は入力途中の構文を
        // 都度解析しても無駄なため対象外にする（onReturnToNormalが離脱時に既に解析する）。
        // 連続編集での解析多発を避けるためデバウンスする。
        javax.swing.Timer debounceTimer = new javax.swing.Timer(400, e -> trigger.run());
        debounceTimer.setRepeats(false);
        editor.setOnBufferChanged(() -> {
            if (!editor.isInsertMode()) {
                debounceTimer.restart();
            }
        });
    }

    /**
     * currentFilePath が設定されておりかつ拡張子が ".java" でない場合は
     * Javaファイルではないと判定する（コンパイル解析が無意味なため）。
     * ファイルパス未設定（:enew 等の疑似バッファ）は従来どおり解析対象に含める。
     */
    private static boolean isJavaBuffer(ModalEditor editor) {
        String path = editor.getCurrentFilePath();
        return path == null || path.toLowerCase(java.util.Locale.ROOT).endsWith(".java");
    }

    /** バックグラウンド仮想スレッドでコンパイル解析し、EDT で診断反映と auto-import を行う。
     *  @param useRealPathIfSaved true のとき、保存済みファイルなら analyzeWithProject を使う
     *                            （INSERT→NORMAL / 保存トリガ用。public class 名不一致エラーを防ぐ）。
     *                            false のとき常に analyzeWithProject を使う（Ctrl+Shift+O 用。複数ファイル対応）。
     *  @param failureMessage 解析失敗時にステータス行へ出す文言 */
    private static void runCompileAnalysis(ModalEditor editor, EditorCanvas canvas,
            boolean useRealPathIfSaved, String failureMessage) {
        String source = editor.getText();
        String snapshotPath = editor.getCurrentFilePath();
        Thread.ofVirtual().start(() -> {
            try {
                // クラス索引が未完了なら完了まで待つ（起動直後の INSERT→NORMAL 対策）
                JDK_INDEX.awaitReady();
                Path projectRoot = WD_MANAGER.getWorkingDirectory();
                List<CompileDiagnostic> diags = (useRealPathIfSaved && snapshotPath != null)
                    ? COMPILE_ANALYZER.analyzeWithProject(snapshotPath, source, projectRoot)
                    : COMPILE_ANALYZER.analyzeWithProject("<buffer>", source, projectRoot);
                SwingUtilities.invokeLater(() -> {
                    canvas.setDiagnostics(diags);
                    // 未使用削除は handleAutoImport の全候補処理完了後に実行
                    editor.setOnImportComplete(editor::organizeImportsRemoveUnused);
                    editor.handleAutoImport(diags);
                });
            } catch (AnalysisException e) {
                SwingUtilities.invokeLater(() -> {
                    canvas.setDiagnostics(List.of());
                    editor.setStatusMessage(failureMessage);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * F10: 追加クラスパス（複数ディレクトリ、カンマ区切り）を尋ねてからプロジェクト全体を
     * コンパイルし、*compile* 疑似バッファに結果を表示する。Escなら追加クラスパスなしで続行する。
     */
    private static void triggerCompile(ModalEditor editor, EditorCanvas canvas) {
        editor.enterClasspathInput("F10",
            extraClasspath -> doCompile(editor, canvas, extraClasspath, null));
    }

    /** F11: bin/ に .class がなければ拒否し、あれば追加クラスパスを尋ねて main クラスを解決・実行する。 */
    private static void triggerRun(ModalEditor editor, EditorCanvas canvas) {
        Path projectRoot = editor.getProjectRoot();
        if (!PROJECT_BUILDER.hasCompiledClasses(projectRoot)) {
            editor.setStatusMessage("run: bin/ に.classファイルがありません。先にF10でコンパイルしてください");
            return;
        }
        editor.enterClasspathInput("F11",
            extraClasspath -> resolveAndRunMainClass(editor, canvas, projectRoot, extraClasspath));
    }

    /**
     * F12: 追加クラスパスを尋ねてからコンパイルし、成功した場合のみ同じ追加クラスパスで
     * main クラスを解決して実行する。
     */
    private static void triggerCompileAndRun(ModalEditor editor, EditorCanvas canvas) {
        editor.enterClasspathInput("F12", extraClasspath -> {
            Path projectRoot = editor.getProjectRoot();
            doCompile(editor, canvas, extraClasspath, result -> {
                if (result.success()) resolveAndRunMainClass(editor, canvas, projectRoot, extraClasspath);
            });
        });
    }

    /**
     * F10/F12共通のコンパイル実行部。onDone は完了後にEDT上で呼ばれる（null可）。
     * javacが診断を報告するたび *compile* 疑似バッファへリアルタイムに追記する。
     */
    private static void doCompile(ModalEditor editor, EditorCanvas canvas, List<Path> extraClasspath,
            java.util.function.Consumer<dev.javatexteditor.projectbuild.BuildResult> onDone) {
        editor.beginCompileOutput();
        editor.syncCanvas();
        Path projectRoot = editor.getProjectRoot();
        Thread.ofVirtual().start(() -> {
            dev.javatexteditor.projectbuild.BuildResult result =
                PROJECT_BUILDER.compile(projectRoot, extraClasspath, diag ->
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
     * （{@link ModalEditor#setOnRunMainClassSelected} 経由で選択結果が {@link #runJavaClass} に届く）。
     */
    private static void resolveAndRunMainClass(
            ModalEditor editor, EditorCanvas canvas, Path projectRoot, List<Path> extraClasspath) {
        editor.setStatusMessage("mainクラスを検索中...");
        Thread.ofVirtual().start(() -> {
            List<String> mainClasses = MAIN_CLASS_FINDER.findMainClasses(projectRoot);
            SwingUtilities.invokeLater(() -> {
                if (mainClasses.isEmpty()) {
                    editor.setStatusMessage("run: mainメソッドを持つクラスが見つかりません");
                } else if (mainClasses.size() == 1) {
                    runJavaClass(editor, canvas, projectRoot, mainClasses.get(0), extraClasspath);
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
    private static void runJavaClass(ModalEditor editor, EditorCanvas canvas, Path projectRoot, String fqcn,
            List<Path> extraClasspath) {
        if (runningProcess != null && runningProcess.isAlive()) {
            runningProcess.destroy();
        }
        Path binDir = PROJECT_BUILDER.binDirFor(projectRoot);
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
                runningProcess = process;
                Thread stdoutReader = startRunOutputReader(process.getInputStream(), editor, false);
                Thread stderrReader = startRunOutputReader(process.getErrorStream(), editor, true);
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
                editor.finishRunOutput(fqcn, finalExitCode);
                editor.syncCanvas();
            });
        });
    }

    /**
     * 実行中プロセスの標準出力/標準エラーを1行読むたび *run* 疑似バッファへリアルタイム反映する
     * 読み取り専用スレッドを起動する（isError=trueなら標準エラー由来として赤字表示される）。
     */
    private static Thread startRunOutputReader(java.io.InputStream in, ModalEditor editor, boolean isError) {
        Thread t = Thread.ofVirtual().unstarted(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String finalLine = line;
                    SwingUtilities.invokeLater(() -> {
                        editor.appendRunOutputLine(finalLine, isError);
                        editor.syncCanvas();
                    });
                }
            } catch (IOException ignored) {
            }
        });
        t.start();
        return t;
    }

    /** リーフの分割コールバックを設定する（splitLeaf 後に呼ぶ）。 */
    private static void setupSplitCallbacks(
            JFrame frame, PaneNode[] root, Leaf[] active, Leaf leaf) {
        leaf.editor().setSplitHorizontalCallback(() -> {
            Leaf cur     = active[0];
            Leaf newLeaf = createLeaf(cur.editor().getText(),
                                      cur.editor().getCurrentFilePath());
            root[0]   = splitLeaf(root[0], cur, newLeaf, JSplitPane.HORIZONTAL_SPLIT);
            active[0] = newLeaf;
            rebuildLayout(frame, root[0], active[0]);
            refreshCallbacks(frame, root, active);
            active[0].canvas().requestFocusInWindow();
        });
        leaf.editor().setSplitVerticalCallback(() -> {
            Leaf cur     = active[0];
            Leaf newLeaf = createLeaf(cur.editor().getText(),
                                      cur.editor().getCurrentFilePath());
            root[0]   = splitLeaf(root[0], cur, newLeaf, JSplitPane.VERTICAL_SPLIT);
            active[0] = newLeaf;
            rebuildLayout(frame, root[0], active[0]);
            refreshCallbacks(frame, root, active);
            active[0].canvas().requestFocusInWindow();
        });
    }

    /** 新しいリーフを生成してコールバックを設定する。 */
    private static Leaf createLeaf(String text, String path) {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(initialCellW, initialCellH);
        canvas.setTheme(Theme.LIGHT_MODE);
        ModalEditor editor = new ModalEditor(text, path, canvas);
        setupCompileAnalysis(editor, canvas);
        // IME（日本語入力等）が確定した文字列を、KEY_TYPEDの1文字コミットと同じ経路で挿入する。
        // 変換中の未確定文字列自体は EditorCanvas 側でカーソル位置にオーバーレイ表示される。
        canvas.setImeCommitHandler(committed -> {
            if (!editor.isInsertMode() && !editor.isCommandMode()) return;
            for (int i = 0; i < committed.length(); ) {
                int cp = committed.codePointAt(i);
                for (char ch : Character.toChars(cp)) {
                    editor.processKey(0, ch, 0);
                }
                i += Character.charCount(cp);
            }
        });
        editor.setJdkClassIndex(JDK_INDEX);
        editor.setAutoImportHandler(AUTO_IMPORT_HANDLER);
        editor.setBufferListSupplier(Main::getBufferRegistry);
        editor.setOnFileOpened(Main::registerBuffer);
        editor.setOnBufferDelete(Main::unregisterBuffer);
        editor.setOnRunMainClassSelected(
            fqcn -> runJavaClass(editor, canvas, editor.getProjectRoot(), fqcn, pendingRunExtraClasspath));
        if (COMPLETION_INDEX != null) {
            editor.setCompletionIndex(COMPLETION_INDEX);
        }
        if (WORD_INDEX != null) {
            editor.setWordIndex(WORD_INDEX);
        }
        // 作業ディレクトリを反映
        if (WD_MANAGER != null) {
            Path wd = WD_MANAGER.getWorkingDirectory();
            editor.setProjectRoot(wd);
            editor.setChangeWorkingDirectoryCallback(p -> WD_MANAGER.setWorkingDirectory(p));
        }
        return new Leaf(canvas, editor);
    }

    /**
     * 全リーフの exitCallback を再設定する。
     * :q 時、ペインが1つなら終了、複数なら現在のリーフを閉じる。
     */
    private static void refreshCallbacks(
            JFrame frame, PaneNode[] root, Leaf[] active) {
        for (Leaf leaf : allLeaves(root[0])) {
            setupSplitCallbacks(frame, root, active, leaf);
            // :wa/:qa/:qa! の対象を現在の全ペインにする（分割構成は :split/:vsplit のたびに変わるため、
            // 固定リストではなく毎回 allLeaves(root[0]) を再評価するSupplierを渡す）。
            leaf.editor().setAllEditorsSupplier(
                    () -> allLeaves(root[0]).stream().map(Leaf::editor).toList());
            leaf.editor().setMovePanePrevCallback(() -> {
                List<Leaf> leaves = allLeaves(root[0]);
                if (leaves.size() <= 1) return;
                int idx = leaves.indexOf(active[0]);
                active[0] = leaves.get((idx - 1 + leaves.size()) % leaves.size());
                updateBorders(leaves, active[0]);
                active[0].canvas().requestFocusInWindow();
            });
            leaf.editor().setMovePaneNextCallback(() -> {
                List<Leaf> leaves = allLeaves(root[0]);
                if (leaves.size() <= 1) return;
                int idx = leaves.indexOf(active[0]);
                active[0] = leaves.get((idx + 1) % leaves.size());
                updateBorders(leaves, active[0]);
                active[0].canvas().requestFocusInWindow();
            });
            leaf.editor().setExitCallback(() -> {
                List<Leaf> leaves = allLeaves(root[0]);
                if (leaves.size() <= 1) {
                    System.exit(0);
                    return;
                }
                // アクティブを閉じる
                Leaf closing = active[0];
                PaneNode newRoot = removeLeaf(root[0], closing);
                root[0] = newRoot;

                // 次のアクティブは閉じたリーフの直前 or 先頭
                List<Leaf> remaining = allLeaves(root[0]);
                int idx = leaves.indexOf(closing);
                active[0] = remaining.get(Math.min(idx, remaining.size() - 1));

                rebuildLayout(frame, root[0], active[0]);
                refreshCallbacks(frame, root, active);
                active[0].canvas().requestFocusInWindow();
            });
        }
    }

    /** フレームのコンテンツを再構築してボーダーを更新する。 */
    private static void rebuildLayout(JFrame frame, PaneNode root, Leaf active) {
        frame.getContentPane().removeAll();
        frame.getContentPane().add(buildComponent(root));
        frame.revalidate();
        frame.repaint();
        updateBorders(allLeaves(root), active);
    }

    /**
     * Ctrl+Alt+矢印: アクティブペインを囲む祖先のうち、キーの方向に対応するorientationを持つ
     * 最初のJSplitPaneだけを調整し、現在ペインを伸縮する。対応する分割が見つからなければ何もしない。
     * PaneNode/Splitツリーではなく、実際に画面に貼られたSwingコンポーネント階層を直接辿る
     * （buildComponentがリーフのEditorCanvasを中間ラッパーなしでJSplitPaneの子にするため辿れる）。
     */
    private static void resizeActivePane(Leaf active, int keyCode) {
        boolean horizontal = (keyCode == KeyEvent.VK_LEFT || keyCode == KeyEvent.VK_RIGHT);
        int neededOrientation = horizontal ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT;
        boolean grow = (keyCode == KeyEvent.VK_RIGHT || keyCode == KeyEvent.VK_DOWN);

        Component prev = active.canvas();
        Component cur  = prev.getParent();
        while (cur != null) {
            if (cur instanceof JSplitPane sp && sp.getOrientation() == neededOrientation) {
                boolean isFirstChildActive = (sp.getLeftComponent() == prev);
                int totalSpan = horizontal ? sp.getWidth() : sp.getHeight();
                int newLoc = dev.javatexteditor.ui.PaneResizeCalculator.computeNewDividerLocation(
                    sp.getDividerLocation(), totalSpan, sp.getDividerSize(),
                    isFirstChildActive, grow, PANE_RESIZE_STEP_PX, PANE_RESIZE_MIN_PX);
                sp.setDividerLocation(newLoc);
                sp.revalidate();
                sp.repaint();
                return;
            }
            prev = cur;
            cur = cur.getParent();
        }
        // 対応方向の分割祖先が見つからない場合は何もしない（単一ペイン・非対応方向のみの入れ子等）
    }

    private static void updateBorders(List<Leaf> leaves, Leaf active) {
        for (Leaf l : leaves) {
            boolean isActive = l == active;
            l.canvas().setBorder(isActive
                ? BorderFactory.createLineBorder(ACTIVE_BORDER_COLOR, 2)
                : BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
            l.canvas().setActivePane(isActive);
        }
    }

    // -------------------------------------------------------------------------
    // エントリポイント
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        // セットアップ未完了なら自動実行（バックグラウンド）
        runSetupIfNeeded();

        // プロジェクトルートを引数のファイルの親ディレクトリか user.dir から決定
        String initialPath = (args.length > 0) ? args[0] : null;
        String initialText;
        if (initialPath != null) {
            try {
                initialText = Files.readString(Path.of(initialPath)).replace("\r\n", "\n");
            } catch (IOException e) {
                System.err.println("Error opening file: " + e.getMessage());
                return;
            }
        } else {
            initialText = "";
        }

        // 作業ディレクトリマネージャを初期化（引数ファイルの親を hint として渡す）
        Path initialHint = (initialPath != null)
            ? Path.of(initialPath).toAbsolutePath().getParent()
            : null;
        WD_MANAGER = new WorkingDirectoryManager(initialHint);
        Path projectRoot = WD_MANAGER.getWorkingDirectory();

        // 補完インデックス（JDK クラス名のみ）をバックグラウンドで構築
        COMPLETION_INDEX = dev.javatexteditor.analysis.CompletionIndex.build(JDK_INDEX);
        // Alt+/ 単語補完インデックス（作業ディレクトリ配下の単語）もバックグラウンドで構築
        WORD_INDEX = dev.javatexteditor.analysis.WordIndex.build(projectRoot);

        final GraphicsConfiguration targetScreen = detectMouseScreen();
        double displayScale = computeDisplayScale(targetScreen);
        int[] cellSize = computeInitialCellSize(displayScale);
        initialCellW = cellSize[0];
        initialCellH = cellSize[1];
        int[] windowSize = computeInitialWindowSize(targetScreen, displayScale);
        final String text = initialText;
        final String path = initialPath;
        final boolean splash = (initialPath == null);

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame(buildTitle(WD_MANAGER.getWorkingDirectory()), targetScreen);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(windowSize[0], windowSize[1]);
            centerOnScreen(frame, targetScreen);

            Leaf firstLeaf = createLeaf(text, path);
            if (splash) firstLeaf.canvas().setShowSplash(true);
            // 初期ファイルをバッファレジストリに登録
            if (path != null) {
                registerBuffer(new dev.javatexteditor.telescope.BufferPicker.BufferEntry(
                    Path.of(path).getFileName().toString(), path));
            }

            PaneNode[] root   = { firstLeaf };
            Leaf[]     active = { firstLeaf };

            // 作業ディレクトリ変更時: 全エディタと JFrame タイトルを更新
            WD_MANAGER.addChangeListener(wd -> {
                for (Leaf l : allLeaves(root[0])) {
                    l.editor().setProjectRoot(wd);
                }
                frame.setTitle(buildTitle(wd));
            });

            refreshCallbacks(frame, root, active);
            updateBorders(List.of(firstLeaf), firstLeaf);
            frame.add(firstLeaf.canvas());

            // KEY_PRESSEDで processKey を呼んだキーは KEY_TYPED でも届くため、
            // 二重処理を防ぐためにフラグで管理する。
            boolean[] pressedHandled = { false };

            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(e -> {
                    // モーダルダイアログが前面にある場合はエディタのキー処理をスキップする
                    java.awt.Window focused = KeyboardFocusManager
                        .getCurrentKeyboardFocusManager().getFocusedWindow();
                    if (focused != frame) return false;

                    if (e.getID() == KeyEvent.KEY_PRESSED) {
                        pressedHandled[0] = false;

                        // Ctrl+Shift+矢印: アクティブペインのビットマップフォントセルサイズを変更
                        boolean ctrl  = (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK)  != 0;
                        boolean shift = (e.getModifiersEx() & KeyEvent.SHIFT_DOWN_MASK) != 0;
                        if (ctrl && shift) {
                            int kc = e.getKeyCode();
                            if (kc == KeyEvent.VK_RIGHT) {
                                active[0].canvas().adjustCellWidth(+1);
                                pressedHandled[0] = true; return true;
                            } else if (kc == KeyEvent.VK_LEFT) {
                                active[0].canvas().adjustCellWidth(-1);
                                pressedHandled[0] = true; return true;
                            } else if (kc == KeyEvent.VK_DOWN) {
                                active[0].canvas().adjustCellHeight(+1);
                                pressedHandled[0] = true; return true;
                            } else if (kc == KeyEvent.VK_UP) {
                                active[0].canvas().adjustCellHeight(-1);
                                pressedHandled[0] = true; return true;
                            }
                        }

                        // Ctrl+Alt+矢印: 画面分割中、アクティブペインの縦横幅を伸縮する
                        boolean alt = (e.getModifiersEx() & KeyEvent.ALT_DOWN_MASK) != 0;
                        if (ctrl && alt && !shift) {
                            int kc = e.getKeyCode();
                            if (kc == KeyEvent.VK_LEFT || kc == KeyEvent.VK_RIGHT
                                    || kc == KeyEvent.VK_UP || kc == KeyEvent.VK_DOWN) {
                                resizeActivePane(active[0], kc);
                                pressedHandled[0] = true; return true;
                            }
                        }

                        // F2: カーソル行の診断をモーダルダイアログで表示
                        if (e.getKeyCode() == KeyEvent.VK_F2) {
                            dev.javatexteditor.editor.ModalEditor edF2 = active[0].editor();
                            int row = edF2.getCursorRow();
                            List<CompileDiagnostic> diags = active[0].canvas().getDiagnostics();
                            List<CompileDiagnostic> rowDiags = diags.stream()
                                .filter(d -> d.lineNumber() == row)
                                .toList();
                            if (rowDiags.isEmpty()) {
                                JOptionPane.showMessageDialog(frame,
                                    "この行にエラー・警告はありません。",
                                    "診断情報（行 " + (row + 1) + "）",
                                    JOptionPane.INFORMATION_MESSAGE);
                            } else {
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < rowDiags.size(); i++) {
                                    CompileDiagnostic d = rowDiags.get(i);
                                    if (i > 0) sb.append("\n\n");
                                    String kindLabel = switch (d.kind()) {
                                        case ERROR   -> "エラー";
                                        case WARNING -> "警告";
                                    };
                                    sb.append("[").append(kindLabel).append("]");
                                    if (d.column() >= 0) {
                                        sb.append("  列: ").append(d.column() + 1);
                                    }
                                    sb.append("\n").append(d.message());
                                }
                                int iconType = rowDiags.stream().anyMatch(
                                    d -> d.kind() == dev.javatexteditor.analysis.DiagnosticKind.ERROR)
                                    ? JOptionPane.ERROR_MESSAGE
                                    : JOptionPane.WARNING_MESSAGE;
                                JOptionPane.showMessageDialog(frame,
                                    sb.toString(),
                                    "診断情報（行 " + (row + 1) + "）",
                                    iconType);
                            }
                            pressedHandled[0] = true;
                            return true;
                        }

                        // F10/F11/F12: プロジェクト全体のコンパイル・実行（NORMALモードのみ）
                        if (e.getKeyCode() == KeyEvent.VK_F10
                                || e.getKeyCode() == KeyEvent.VK_F11
                                || e.getKeyCode() == KeyEvent.VK_F12) {
                            dev.javatexteditor.editor.ModalEditor edBuild = active[0].editor();
                            EditorCanvas canvasBuild = active[0].canvas();
                            if (edBuild.isNormalMode()) {
                                switch (e.getKeyCode()) {
                                    case KeyEvent.VK_F10 -> triggerCompile(edBuild, canvasBuild);
                                    case KeyEvent.VK_F11 -> triggerRun(edBuild, canvasBuild);
                                    case KeyEvent.VK_F12 -> triggerCompileAndRun(edBuild, canvasBuild);
                                }
                            }
                            pressedHandled[0] = true;
                            return true;
                        }

                        // INSERT/COMMANDモードで印字可能文字（Ctrl/Altなし）はIMEに委譲する。
                        // IMEがコミットした文字は KEY_TYPED で受け取る。
                        boolean noCtrlAlt = (e.getModifiersEx() &
                            (KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK)) == 0;
                        char kc2 = e.getKeyChar();
                        boolean isPrintable = kc2 != KeyEvent.CHAR_UNDEFINED && kc2 >= ' ';
                        dev.javatexteditor.editor.ModalEditor ed = active[0].editor();
                        if (noCtrlAlt && isPrintable &&
                                (ed.isInsertMode() || ed.isCommandMode())) {
                            return false; // IMEに委譲（pressedHandled は false のまま）
                        }

                        ed.processKey(e.getKeyCode(), e.getKeyChar(), e.getModifiersEx());
                        updateBorders(allLeaves(root[0]), active[0]);
                        pressedHandled[0] = true; // KEY_TYPED で二重処理しないようにマーク
                        return true;
                    }

                    // KEY_TYPED: IMEがコミットした文字（日本語など）をINSERT/COMMANDモードで処理する。
                    // KEY_PRESSEDで既に処理したキーは無視する（';'→COMMMANDモードへの遷移後に
                    // KEY_TYPED の';'がコマンドバッファに追記される問題を防ぐ）。
                    if (e.getID() == KeyEvent.KEY_TYPED) {
                        if (pressedHandled[0]) {
                            pressedHandled[0] = false;
                            return false;
                        }
                        char ch = e.getKeyChar();
                        dev.javatexteditor.editor.ModalEditor ed = active[0].editor();
                        if (ch != KeyEvent.CHAR_UNDEFINED && ch >= ' ' &&
                                (ed.isInsertMode() || ed.isCommandMode())) {
                            ed.processKey(0, ch, 0);
                            updateBorders(allLeaves(root[0]), active[0]);
                            return true;
                        }
                    }

                    return false;
                });

            // マウスクリックでアクティブペインを切り替える
            frame.getContentPane().addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent ev) {
                    Component clicked = frame.getContentPane().findComponentAt(ev.getPoint());
                    for (Leaf l : allLeaves(root[0])) {
                        if (l.canvas() == clicked) {
                            active[0] = l;
                            updateBorders(allLeaves(root[0]), active[0]);
                            active[0].canvas().requestFocusInWindow();
                            break;
                        }
                    }
                }
            });

            frame.setVisible(true);
            // canvasは既定でフォーカスを持たない(JPanel)ため、表示直後に明示的に
            // フォーカスを与える。IME(InputContext)は実際のフォーカスオーナーである
            // コンポーネントにしか関連付けられないため、これが無いと変換中文字列の
            // オーバーレイ表示（EditorCanvas.inputMethodTextChanged）が呼ばれない。
            active[0].canvas().requestFocusInWindow();
        });
    }

    /** JFrame タイトル文字列を構築する（ホームディレクトリは ~ に置換）。 */
    private static String buildTitle(Path wd) {
        try {
            Path home = Path.of(System.getProperty("user.home", ""));
            Path rel  = home.relativize(wd);
            return "Java Text Editor — ~/" + rel.toString().replace('\\', '/');
        } catch (IllegalArgumentException ignored) {}
        return "Java Text Editor — " + wd;
    }

    /**
     * lib/src.zip または lib/openjdk-native/ が存在しない場合、
     * セットアップスクリプトをバックグラウンドスレッドで自動実行する。
     * エディタの起動は待たずに続行する。
     */
    private static void runSetupIfNeeded() {
        Path libDir = resolveLibDir();
        boolean hasSrcZip    = Files.exists(libDir.resolve("src.zip"));
        boolean hasNativeSrc = Files.isDirectory(libDir.resolve("openjdk-native"));
        if (hasSrcZip && hasNativeSrc) return;

        Thread.ofVirtual().name("setup-auto").start(() -> {
            String os = System.getProperty("os.name", "").toLowerCase();
            boolean isWindows = os.contains("win");
            Path scriptDir = resolveScriptDir();
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

    private static Path resolveLibDir() {
        try {
            var url = Main.class.getProtectionDomain().getCodeSource().getLocation();
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

    private static Path resolveScriptDir() {
        return dev.javatexteditor.analysis.CodeSourceLocator
                .findUpward(Main.class, "scripts", 4, Files::isDirectory)
                .orElse(Path.of("scripts"));
    }
}
