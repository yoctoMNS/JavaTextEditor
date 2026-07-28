package dev.javatexteditor;

import dev.javatexteditor.analysis.CompileAnalyzer;
import dev.javatexteditor.app.AnalysisServices;
import dev.javatexteditor.app.CBuildRunner;
import dev.javatexteditor.app.GlobalKeyDispatcher;
import dev.javatexteditor.app.JavaBuildRunner;
import dev.javatexteditor.app.LiveDiagnostics;
import dev.javatexteditor.app.PaneManager;
import dev.javatexteditor.app.RunningProcessHolder;
import dev.javatexteditor.app.SetupBootstrap;
import dev.javatexteditor.ui.DisplayMetrics;
import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JFrame;
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

        // 作業ディレクトリに依存する索引（補完・単語）の構築を開始する。
        // ★必ず SwingUtilities.invokeLater より前で呼ぶこと（構築開始が遅れると
        //   起動直後の Ctrl+Space / Alt+/ が空振りする）。
        SERVICES.startProjectIndexing(projectRoot);

        final GraphicsConfiguration targetScreen = detectMouseScreen();
        double displayScale = computeDisplayScale(targetScreen);
        int[] cellSize = computeInitialCellSize(displayScale);
        final int initialCellW = cellSize[0];
        final int initialCellH = cellSize[1];
        int[] windowSize = computeInitialWindowSize(targetScreen, displayScale);
        final String text = initialText;
        final String path = initialPath;
        final boolean splash = (initialPath == null);

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame(buildTitle(WD_MANAGER.getWorkingDirectory()), targetScreen);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(windowSize[0], windowSize[1]);
            centerOnScreen(frame, targetScreen);

            PaneManager panes = new PaneManager(frame, text, path, initialCellW, initialCellH,
                LIVE_DIAGNOSTICS, SERVICES, BUFFER_REGISTRY, JAVA_BUILD_RUNNER, WD_MANAGER);
            if (splash) panes.active().canvas().setShowSplash(true);
            // 初期ファイルをバッファレジストリに登録
            if (path != null) {
                BUFFER_REGISTRY.register(new dev.javatexteditor.telescope.BufferPicker.BufferEntry(
                    Path.of(path).getFileName().toString(), path));
            }

            // 作業ディレクトリ変更時: 全エディタと JFrame タイトルを更新
            WD_MANAGER.addChangeListener(wd -> {
                for (PaneTree.Leaf l : panes.allLeaves()) {
                    l.editor().setProjectRoot(wd);
                }
                frame.setTitle(buildTitle(wd));
            });

            frame.add(panes.active().canvas());

            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(new GlobalKeyDispatcher(
                    frame, panes, JAVA_BUILD_RUNNER, C_BUILD_RUNNER));

            // マウスクリックでアクティブペインを切り替える
            frame.getContentPane().addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent ev) {
                    Component clicked = frame.getContentPane().findComponentAt(ev.getPoint());
                    for (PaneTree.Leaf l : panes.allLeaves()) {
                        if (l.canvas() == clicked) {
                            panes.setActive(l);
                            panes.active().canvas().requestFocusInWindow();
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
            panes.active().canvas().requestFocusInWindow();
        });
    }

    private static final int WINDOW_WIDTH  = 1200;
    private static final int WINDOW_HEIGHT = 750;

    // 作業ディレクトリの中央管理（main() で初期化）
    private static WorkingDirectoryManager WD_MANAGER;

    // 各ペインに配線する解析サービス一式。
    // ★この宣言はクラスロード時に評価され、その時点で JDK クラス索引の構築が始まる。
    //   invokeLater の中へ移すと構築開始が遅れ、起動直後の Ctrl+Space が空振りする
    //   （AnalysisServices の Javadoc「構築開始のタイミング」参照）。
    // ★LIVE_DIAGNOSTICS が jdkClassIndex() を使うため、必ずその宣言より前に置くこと
    //   （static フィールドはソース順に初期化される）。
    private static final AnalysisServices SERVICES = AnalysisServices.createAndStartJdkIndexing();
    // 編集中バッファのインライン診断（ガター/波下線）と auto-import / auto-#include。
    // 作業ディレクトリは Supplier で渡す（:cd 後も解析時点の値を読むため。LiveDiagnostics の Javadoc 参照）。
    private static final LiveDiagnostics LIVE_DIAGNOSTICS = new LiveDiagnostics(
        new CompileAnalyzer(),
        new dev.javatexteditor.analysis.CCompileAnalyzer(),
        SERVICES.jdkClassIndex(),
        () -> WD_MANAGER.getWorkingDirectory());

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
    // 画面操作
    // -------------------------------------------------------------------------

    // 起動時にマウスカーソルのあるディスプレイの解像度から算出する初期フォントセルサイズ
    // （4K等の高解像度ディスプレイでデフォルトフォントが小さすぎるのを防ぐ）。
    // 以後はユーザーが Ctrl+Shift+矢印で自由に変更できる（フォントセルサイズ自体の既定値は
    // MiscFixedBold9x15.BASE_CELL_W/H。main() ローカル変数として算出し PaneManager へ渡す）。

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
