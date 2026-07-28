package dev.javatexteditor;

import dev.javatexteditor.analysis.AutoImportHandler;
import dev.javatexteditor.analysis.CompileAnalyzer;
import dev.javatexteditor.analysis.ImportSuggester;
import dev.javatexteditor.analysis.JdkClassIndex;
import dev.javatexteditor.analysis.SourceAnalyzer;
import dev.javatexteditor.app.CBuildRunner;
import dev.javatexteditor.app.DiagnosticPopup;
import dev.javatexteditor.app.JavaBuildRunner;
import dev.javatexteditor.app.LiveDiagnostics;
import dev.javatexteditor.app.RunningProcessHolder;
import dev.javatexteditor.app.SetupBootstrap;
import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.ui.DisplayMetrics;
import dev.javatexteditor.ui.EditorCanvas;
import dev.javatexteditor.ui.FontChoice;
import dev.javatexteditor.ui.MiscFixedBold9x15;
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
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

public class Main {

    // -------------------------------------------------------------------------
    // エントリポイント
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        // セットアップ未完了なら自動実行（バックグラウンド）
        // Main.class を渡すのは、切り出し前と同じ基準でパスを解決するため（SetupBootstrap の Javadoc 参照）
        SetupBootstrap.runIfNeeded(Main.class);

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

            PaneTree.Leaf firstLeaf = createLeaf(text, path);
            if (splash) firstLeaf.canvas().setShowSplash(true);
            // 初期ファイルをバッファレジストリに登録
            if (path != null) {
                BUFFER_REGISTRY.register(new dev.javatexteditor.telescope.BufferPicker.BufferEntry(
                    Path.of(path).getFileName().toString(), path));
            }

            PaneTree.PaneNode[] root   = { firstLeaf };
            PaneTree.Leaf[]     active = { firstLeaf };

            // 作業ディレクトリ変更時: 全エディタと JFrame タイトルを更新
            WD_MANAGER.addChangeListener(wd -> {
                for (PaneTree.Leaf l : PaneTree.allLeaves(root[0])) {
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
                            DiagnosticPopup.showForCursorRow(
                                frame, active[0].editor(), active[0].canvas());
                            pressedHandled[0] = true;
                            return true;
                        }

                        // F10/F11/F12: プロジェクト全体のコンパイル・実行（NORMALモードのみ）
                        if (e.getKeyCode() == KeyEvent.VK_F10
                                || e.getKeyCode() == KeyEvent.VK_F11
                                || e.getKeyCode() == KeyEvent.VK_F12) {
                            dev.javatexteditor.editor.ModalEditor edBuild = active[0].editor();
                            if (edBuild.isNormalMode()) {
                                boolean c = LiveDiagnostics.isCBuffer(edBuild);
                                switch (e.getKeyCode()) {
                                    case KeyEvent.VK_F10 -> { if (c) C_BUILD_RUNNER.triggerCompile(edBuild); else JAVA_BUILD_RUNNER.triggerCompile(edBuild); }
                                    case KeyEvent.VK_F11 -> { if (c) C_BUILD_RUNNER.triggerRun(edBuild); else JAVA_BUILD_RUNNER.triggerRun(edBuild); }
                                    case KeyEvent.VK_F12 -> { if (c) C_BUILD_RUNNER.triggerCompileAndRun(edBuild); else JAVA_BUILD_RUNNER.triggerCompileAndRun(edBuild); }
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
                        updateBorders(PaneTree.allLeaves(root[0]), active[0]);
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
                            updateBorders(PaneTree.allLeaves(root[0]), active[0]);
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
                    for (PaneTree.Leaf l : PaneTree.allLeaves(root[0])) {
                        if (l.canvas() == clicked) {
                            active[0] = l;
                            updateBorders(PaneTree.allLeaves(root[0]), active[0]);
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

    private static final Color ACTIVE_BORDER_COLOR = new Color(0x88, 0x88, 0xFF);
    private static final int WINDOW_WIDTH  = 1200;
    private static final int WINDOW_HEIGHT = 750;

    // Ctrl+Alt+矢印: アクティブペインのリサイズ量・最小ペインサイズ（ピクセル）
    private static final int PANE_RESIZE_STEP_PX = 20;
    private static final int PANE_RESIZE_MIN_PX   = 60;

    // 作業ディレクトリの中央管理（main() で初期化）
    private static WorkingDirectoryManager WD_MANAGER;

    private static final JdkClassIndex JDK_INDEX = JdkClassIndex.build();
    // 編集中バッファのインライン診断（ガター/波下線）と auto-import / auto-#include。
    // 作業ディレクトリは Supplier で渡す（:cd 後も解析時点の値を読むため。LiveDiagnostics の Javadoc 参照）。
    private static final LiveDiagnostics LIVE_DIAGNOSTICS = new LiveDiagnostics(
        new CompileAnalyzer(),
        new dev.javatexteditor.analysis.CCompileAnalyzer(),
        JDK_INDEX,
        () -> WD_MANAGER.getWorkingDirectory());
    private static final SourceAnalyzer SOURCE_ANALYZER = new SourceAnalyzer();
    private static final ImportSuggester IMPORT_SUGGESTER = new ImportSuggester(JDK_INDEX);
    private static final AutoImportHandler AUTO_IMPORT_HANDLER =
        new AutoImportHandler(IMPORT_SUGGESTER, SOURCE_ANALYZER);
    private static dev.javatexteditor.analysis.CompletionIndex COMPLETION_INDEX = null;
    private static dev.javatexteditor.analysis.WordIndex WORD_INDEX = null;

    // -------------------------------------------------------------------------
    // F10/F11/F12: プロジェクト全体のコンパイル・実行
    // -------------------------------------------------------------------------
    // F11/F12 で起動した直近の子プロセス。もう一度実行されたら前回分を destroy() してから起動し直す。
    // Java版・C版が1つを共有する（言語をまたいだ多重実行防止。RunningProcessHolder の Javadoc 参照）。
    private static final RunningProcessHolder RUNNING_PROCESS = new RunningProcessHolder();
    private static final JavaBuildRunner JAVA_BUILD_RUNNER = new JavaBuildRunner(
        new dev.javatexteditor.projectbuild.ProjectBuilder(),
        new dev.javatexteditor.projectbuild.MainClassFinder(),
        RUNNING_PROCESS);
    // C版のプロジェクトビルダ（gcc/clang/cc を外部起動。詳細は CProjectBuilder 参照）。
    private static final CBuildRunner C_BUILD_RUNNER = new CBuildRunner(
        new dev.javatexteditor.projectbuild.CProjectBuilder(),
        RUNNING_PROCESS);

    // -------------------------------------------------------------------------
    // グローバルバッファレジストリ（SPC+b で表示される開いたバッファの一覧）
    // -------------------------------------------------------------------------
    private static final BufferRegistry BUFFER_REGISTRY = new BufferRegistry();

    // -------------------------------------------------------------------------
    // ペインツリー
    // -------------------------------------------------------------------------

    /** ツリーを Swing コンポーネントに変換する。 */
    private static Component buildComponent(PaneTree.PaneNode node) {
        return switch (node) {
            case PaneTree.Leaf l -> l.canvas();
            case PaneTree.Split s -> {
                JSplitPane sp = new JSplitPane(s.orientation,
                    buildComponent(s.left), buildComponent(s.right));
                sp.setResizeWeight(0.5);
                sp.setDividerSize(4);
                sp.setBorder(null);
                yield sp;
            }
        };
    }

    /**
     * Vim方式の共有バッファ: absolutePath を現在の currentFilePath として持つ生きたペインが
     * あれば、そのペインが参照する UndoablePieceTable をそのまま返す（無ければ null）。
     * ModalEditor.acquireBufferForOpen() から liveBufferLookup 経由で呼ばれる。
     */
    private static dev.javatexteditor.buffer.UndoablePieceTable findLiveBuffer(
            PaneTree.PaneNode root, String absolutePath) {
        if (absolutePath == null) return null;
        for (PaneTree.Leaf l : PaneTree.allLeaves(root)) {
            if (absolutePath.equals(l.editor().getCurrentFilePath())) {
                return l.editor().getBuffer();
            }
        }
        return null;
    }

    /**
     * source と同じバッファ参照（Vim方式の共有バッファ）を表示している他ペインへ、
     * カーソル位置を現在の値で再クランプしつつ画面を再描画させる。setCursor() が
     * getLines()（＝共有バッファの最新内容）を基準に行/列をクランプしたうえで
     * syncCanvas() まで行うため、他ペインでの削除等でカーソルが範囲外になっていても安全。
     */
    private static void syncSiblingBuffers(PaneTree.PaneNode root, PaneTree.Leaf source) {
        dev.javatexteditor.buffer.UndoablePieceTable buf = source.editor().getBuffer();
        for (PaneTree.Leaf l : PaneTree.allLeaves(root)) {
            if (l != source && l.editor().getBuffer() == buf) {
                l.editor().setCursor(l.editor().getCursorRow(), l.editor().getCursorCol());
            }
        }
    }

    // -------------------------------------------------------------------------
    // 画面操作
    // -------------------------------------------------------------------------

    // 起動時にマウスカーソルのあるディスプレイの解像度から算出する初期フォントセルサイズ
    // （4K等の高解像度ディスプレイでデフォルトフォントが小さすぎるのを防ぐ）。
    // 以後はユーザーが Ctrl+Shift+矢印で自由に変更できる。
    private static int initialCellW = MiscFixedBold9x15.BASE_CELL_W;
    private static int initialCellH = MiscFixedBold9x15.BASE_CELL_H;

    /**
     * 指定ディスプレイの物理解像度（OSのHiDPIスケーリングも加味）を調べ、拡大率を求める。
     * 実際の倍率計算は {@link DisplayMetrics#scaleForWidth} が行い、ここは画面情報の取得だけを担う。
     */
    private static double computeDisplayScale(GraphicsConfiguration gc) {
        double scaleX;
        try {
            scaleX = gc.getDefaultTransform().getScaleX();
        } catch (Exception e) {
            scaleX = 1.0;
        }
        return DisplayMetrics.scaleForWidth(gc.getBounds().width * scaleX);
    }

    private static int[] computeInitialCellSize(double scale) {
        return DisplayMetrics.cellSize(scale);
    }

    /**
     * フォントセルサイズと同じ倍率でウィンドウサイズも拡大する。
     * ここでは画面の利用可能領域（タスクバー等を除く）を調べ、クランプ計算は
     * {@link DisplayMetrics#windowSize} に任せる。
     */
    private static int[] computeInitialWindowSize(GraphicsConfiguration gc, double scale) {
        Rectangle screen = gc.getBounds();
        java.awt.Insets insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return DisplayMetrics.windowSize(WINDOW_WIDTH, WINDOW_HEIGHT, scale,
            screen.width  - insets.left - insets.right,
            screen.height - insets.top  - insets.bottom);
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

    /** リーフの分割コールバックを設定する（splitLeaf 後に呼ぶ）。 */
    private static void setupSplitCallbacks(
            JFrame frame, PaneTree.PaneNode[] root, PaneTree.Leaf[] active, PaneTree.Leaf leaf) {
        leaf.editor().setSplitHorizontalCallback(() -> {
            PaneTree.Leaf cur     = active[0];
            PaneTree.Leaf newLeaf = createLeaf(cur.editor().getText(),
                                      cur.editor().getCurrentFilePath(),
                                      cur.canvas().getCellW(), cur.canvas().getCellH(),
                                      cur.editor().getTheme(), cur.editor().getFontChoice());
            shareBufferWithSplit(cur, newLeaf);
            root[0]   = PaneTree.splitLeaf(root[0], cur, newLeaf, JSplitPane.HORIZONTAL_SPLIT);
            active[0] = newLeaf;
            rebuildLayout(frame, root[0], active[0]);
            refreshCallbacks(frame, root, active);
            active[0].canvas().requestFocusInWindow();
        });
        leaf.editor().setSplitVerticalCallback(() -> {
            PaneTree.Leaf cur     = active[0];
            PaneTree.Leaf newLeaf = createLeaf(cur.editor().getText(),
                                      cur.editor().getCurrentFilePath(),
                                      cur.canvas().getCellW(), cur.canvas().getCellH(),
                                      cur.editor().getTheme(), cur.editor().getFontChoice());
            shareBufferWithSplit(cur, newLeaf);
            root[0]   = PaneTree.splitLeaf(root[0], cur, newLeaf, JSplitPane.VERTICAL_SPLIT);
            active[0] = newLeaf;
            rebuildLayout(frame, root[0], active[0]);
            refreshCallbacks(frame, root, active);
            active[0].canvas().requestFocusInWindow();
        });
    }

    /**
     * :split/:vsplit: 本家Vimと同じく、分割で作った新ペインは分割元と同じバッファを共有する
     * （新ペインを作った瞬間から真に同一の UndoablePieceTable インスタンスを指すため、
     * createLeaf() が内部で一旦構築した独自バッファを捨てて置き換える。カーソル位置も
     * 分割元に揃える。以後は liveBufferLookup を経由せずとも参照が同一のまま保たれる）。
     */
    private static void shareBufferWithSplit(PaneTree.Leaf source, PaneTree.Leaf newLeaf) {
        newLeaf.editor().setBuffer(source.editor().getBuffer());
        newLeaf.editor().setCursor(source.editor().getCursorRow(), source.editor().getCursorCol());
    }

    /** 新しいリーフを生成してコールバックを設定する（既定のフォントセルサイズ・テーマ・フォントを使用）。 */
    private static PaneTree.Leaf createLeaf(String text, String path) {
        return createLeaf(text, path, initialCellW, initialCellH, Theme.DARK_MODE, FontChoice.MISC_FIXED);
    }

    /**
     * 新しいリーフを生成してコールバックを設定する。分割元ペインのフォントセルサイズを
     * 引き継ぐために cellW/cellH を明示指定できる（分割後は Ctrl+Shift+矢印で他ペインとは
     * 独立に変更可能。あくまで「分割直後の初期値」を揃えるだけ）。
     */
    private static PaneTree.Leaf createLeaf(String text, String path, int cellW, int cellH) {
        return createLeaf(text, path, cellW, cellH, Theme.DARK_MODE, FontChoice.MISC_FIXED);
    }

    /**
     * 新しいリーフを生成してコールバックを設定する。:split/:vsplit時は分割元ペインの
     * カラーテーマ・フォント（:color/:font コマンドで変更済みの値）も引き継ぐ
     * （cellW/cellHの引き継ぎと同じ「分割直後の初期値を揃える」考え方。以後は各ペインで
     * 独立に :color/:font を実行できる）。
     */
    private static PaneTree.Leaf createLeaf(String text, String path, int cellW, int cellH,
                                    Theme theme, FontChoice fontChoice) {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(cellW, cellH);
        canvas.setTheme(theme);
        canvas.setFontChoice(fontChoice);
        ModalEditor editor = new ModalEditor(text, path, canvas);
        editor.setTheme(theme);
        editor.setFontChoice(fontChoice);
        LIVE_DIAGNOSTICS.install(editor, canvas);
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
        // Shift+K の最優先段（Eclipse JDT 流バインディング解決）を有効化する。
        // javac の属性付けはプロジェクト規模に比例して重いため EDT では実行せず、
        // 仮想スレッドで解析して invokeLater で結果を反映する（完全非同期）。
        editor.enableBindingDefinitionLookup(
            task -> Thread.ofVirtual().name("binding-definition-lookup").start(task),
            SwingUtilities::invokeLater);
        editor.setAutoImportHandler(AUTO_IMPORT_HANDLER);
        editor.setBufferListSupplier(BUFFER_REGISTRY::entries);
        editor.setOnFileOpened(BUFFER_REGISTRY::register);
        editor.setOnBufferDelete(BUFFER_REGISTRY::unregister);
        editor.setOnRunMainClassSelected(
            fqcn -> JAVA_BUILD_RUNNER.runSelectedMainClass(editor, editor.getBuildRoot(), fqcn));
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
        return new PaneTree.Leaf(canvas, editor);
    }

    /**
     * 全リーフの exitCallback を再設定する。
     * :q 時、ペインが1つなら終了、複数なら現在のリーフを閉じる。
     */
    private static void refreshCallbacks(
            JFrame frame, PaneTree.PaneNode[] root, PaneTree.Leaf[] active) {
        for (PaneTree.Leaf leaf : PaneTree.allLeaves(root[0])) {
            setupSplitCallbacks(frame, root, active, leaf);
            // :wa/:qa/:qa! の対象を現在の全ペインにする（分割構成は :split/:vsplit のたびに変わるため、
            // 固定リストではなく毎回 PaneTree.allLeaves(root[0]) を再評価するSupplierを渡す）。
            leaf.editor().setAllEditorsSupplier(
                    () -> PaneTree.allLeaves(root[0]).stream().map(PaneTree.Leaf::editor).toList());
            // Vim方式の共有バッファ: ファイルを開く際、同じ絶対パスを他ペインが既に開いていれば
            // その生きたバッファ参照を再利用させる（:e/telescope/FILER/gr/Ctrl+U/Ctrl+P等すべて経由）。
            leaf.editor().setLiveBufferLookup(path -> findLiveBuffer(root[0], path));
            // 共有バッファの内容が変化した直後、同じ参照を持つ他ペインの画面へ即座に反映する。
            leaf.editor().setOnSharedBufferSync(() -> syncSiblingBuffers(root[0], leaf));
            leaf.editor().setMovePanePrevCallback(() -> {
                List<PaneTree.Leaf> leaves = PaneTree.allLeaves(root[0]);
                if (leaves.size() <= 1) return;
                int idx = leaves.indexOf(active[0]);
                active[0] = leaves.get((idx - 1 + leaves.size()) % leaves.size());
                updateBorders(leaves, active[0]);
                active[0].canvas().requestFocusInWindow();
            });
            leaf.editor().setMovePaneNextCallback(() -> {
                List<PaneTree.Leaf> leaves = PaneTree.allLeaves(root[0]);
                if (leaves.size() <= 1) return;
                int idx = leaves.indexOf(active[0]);
                active[0] = leaves.get((idx + 1) % leaves.size());
                updateBorders(leaves, active[0]);
                active[0].canvas().requestFocusInWindow();
            });
            leaf.editor().setExitCallback(() -> {
                List<PaneTree.Leaf> leaves = PaneTree.allLeaves(root[0]);
                if (leaves.size() <= 1) {
                    System.exit(0);
                    return;
                }
                // アクティブを閉じる
                PaneTree.Leaf closing = active[0];
                PaneTree.PaneNode newRoot = PaneTree.removeLeaf(root[0], closing);
                root[0] = newRoot;

                // 次のアクティブは閉じたリーフの直前 or 先頭
                List<PaneTree.Leaf> remaining = PaneTree.allLeaves(root[0]);
                int idx = leaves.indexOf(closing);
                active[0] = remaining.get(Math.min(idx, remaining.size() - 1));

                rebuildLayout(frame, root[0], active[0]);
                refreshCallbacks(frame, root, active);
                active[0].canvas().requestFocusInWindow();
            });
        }
    }

    /** フレームのコンテンツを再構築してボーダーを更新する。 */
    private static void rebuildLayout(JFrame frame, PaneTree.PaneNode root, PaneTree.Leaf active) {
        frame.getContentPane().removeAll();
        frame.getContentPane().add(buildComponent(root));
        frame.revalidate();
        frame.repaint();
        updateBorders(PaneTree.allLeaves(root), active);
    }

    /**
     * Ctrl+Alt+矢印: アクティブペインを囲む祖先のうち、キーの方向に対応するorientationを持つ
     * 最初のJSplitPaneだけを調整し、現在ペインを伸縮する。対応する分割が見つからなければ何もしない。
     * PaneTree.PaneNode/Splitツリーではなく、実際に画面に貼られたSwingコンポーネント階層を直接辿る
     * （buildComponentがリーフのEditorCanvasを中間ラッパーなしでJSplitPaneの子にするため辿れる）。
     */
    private static void resizeActivePane(PaneTree.Leaf active, int keyCode) {
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

    private static void updateBorders(List<PaneTree.Leaf> leaves, PaneTree.Leaf active) {
        for (PaneTree.Leaf l : leaves) {
            boolean isActive = l == active;
            l.canvas().setBorder(isActive
                ? BorderFactory.createLineBorder(ACTIVE_BORDER_COLOR, 2)
                : BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
            l.canvas().setActivePane(isActive);
        }
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
}
