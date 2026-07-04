package dev.javatexteditor;

import dev.javatexteditor.analysis.AnalysisException;
import dev.javatexteditor.analysis.AutoImportHandler;
import dev.javatexteditor.analysis.CompileAnalyzer;
import dev.javatexteditor.analysis.CompileDiagnostic;
import dev.javatexteditor.analysis.ImportSuggester;
import dev.javatexteditor.analysis.JdkClassIndex;
import dev.javatexteditor.analysis.SourceAnalyzer;
import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.ui.BitmapFont10x20;
import dev.javatexteditor.ui.EditorCanvas;
import dev.javatexteditor.ui.Theme;
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
    private static int initialCellW = BitmapFont10x20.BASE_CELL_W;
    private static int initialCellH = BitmapFont10x20.BASE_CELL_H;

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
        int w = (int) Math.round(BitmapFont10x20.BASE_CELL_W * scale);
        int h = (int) Math.round(BitmapFont10x20.BASE_CELL_H * scale);
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
            String source = editor.getText();
            String snapshotPath = editor.getCurrentFilePath();
            editor.setStatusMessage("auto-import: 解析中...");
            Thread.ofVirtual().start(() -> {
                try {
                    // クラス索引が未完了なら完了まで待つ（起動直後の INSERT→NORMAL 対策）
                    JDK_INDEX.awaitReady();
                    // ファイルが保存済みの場合は実パスを URI に渡す（public class 名不一致エラーを防ぐ）
                    List<CompileDiagnostic> diags = (snapshotPath != null)
                        ? COMPILE_ANALYZER.analyzeWithPath(snapshotPath, source)
                        : COMPILE_ANALYZER.analyze(source);
                    SwingUtilities.invokeLater(() -> {
                        canvas.setDiagnostics(diags);
                        editor.setOnImportComplete(editor::organizeImportsRemoveUnused);
                        editor.handleAutoImport(diags);
                    });
                } catch (AnalysisException e) {
                    SwingUtilities.invokeLater(() -> {
                        canvas.setDiagnostics(List.of());
                        editor.setStatusMessage("auto-import: 解析失敗");
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        };
        // INSERT→NORMAL 遷移時: IMEを半角英数字に切り替えてからコンパイル解析を実行する
        editor.setOnReturnToNormal(() -> {
            canvas.switchToHalfWidth();
            trigger.run();
        });
        editor.setOnSave(trigger);
        // Ctrl+Shift+O: コンパイル→未定義シンボルへの import 挿入→未使用 import 削除
        editor.setOnOrganizeImports(() -> {
            String filePath = editor.getCurrentFilePath();
            String source   = editor.getText();
            editor.setStatusMessage("import 整理中...");
            Thread.ofVirtual().start(() -> {
                try {
                    JDK_INDEX.awaitReady();
                    List<CompileDiagnostic> diags = COMPILE_ANALYZER.analyze(source);
                    SwingUtilities.invokeLater(() -> {
                        canvas.setDiagnostics(diags);
                        // 未使用削除は handleAutoImport の全候補処理完了後に実行
                        editor.setOnImportComplete(editor::organizeImportsRemoveUnused);
                        editor.handleAutoImport(diags);
                    });
                } catch (AnalysisException e) {
                    SwingUtilities.invokeLater(() -> {
                        canvas.setDiagnostics(List.of());
                        editor.setStatusMessage("E: コンパイル解析失敗");
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });
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
        });
        leaf.editor().setSplitVerticalCallback(() -> {
            Leaf cur     = active[0];
            Leaf newLeaf = createLeaf(cur.editor().getText(),
                                      cur.editor().getCurrentFilePath());
            root[0]   = splitLeaf(root[0], cur, newLeaf, JSplitPane.VERTICAL_SPLIT);
            active[0] = newLeaf;
            rebuildLayout(frame, root[0], active[0]);
            refreshCallbacks(frame, root, active);
        });
    }

    /** 新しいリーフを生成してコールバックを設定する。 */
    private static Leaf createLeaf(String text, String path) {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(initialCellW, initialCellH);
        canvas.setTheme(Theme.LIGHT_MODE);
        ModalEditor editor = new ModalEditor(text, path, canvas);
        setupCompileAnalysis(editor, canvas);
        editor.setJdkClassIndex(JDK_INDEX);
        editor.setAutoImportHandler(AUTO_IMPORT_HANDLER);
        editor.setBufferListSupplier(Main::getBufferRegistry);
        editor.setOnFileOpened(Main::registerBuffer);
        editor.setOnBufferDelete(Main::unregisterBuffer);
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
            canvas.setWorkingDirectory(wd);
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

    private static void updateBorders(List<Leaf> leaves, Leaf active) {
        for (Leaf l : leaves) {
            l.canvas().setBorder(l == active
                ? BorderFactory.createLineBorder(ACTIVE_BORDER_COLOR, 2)
                : BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
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

        // 補完インデックスをバックグラウンドで構築
        COMPLETION_INDEX = dev.javatexteditor.analysis.CompletionIndex.build(
            JDK_INDEX, projectRoot, SOURCE_ANALYZER);
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
                    l.canvas().setWorkingDirectory(wd);
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
                            break;
                        }
                    }
                }
            });

            frame.setVisible(true);
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
        try {
            var url = Main.class.getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                Path code = Paths.get(url.toURI());
                Path dir = Files.isDirectory(code) ? code : code.getParent();
                for (int i = 0; i < 4; i++) {
                    if (dir == null) break;
                    Path candidate = dir.resolve("scripts");
                    if (Files.isDirectory(candidate)) return candidate;
                    dir = dir.getParent();
                }
            }
        } catch (Exception ignored) {}
        return Path.of("scripts");
    }
}
