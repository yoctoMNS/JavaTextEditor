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
import java.awt.Color;
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
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

public class Main {

    /** アクティブなペインを示すボーダー色 */
    private static final Color ACTIVE_BORDER_COLOR = new Color(0x88, 0x88, 0xFF);

    /** ウィンドウの初期サイズ */
    private static final int WINDOW_WIDTH  = 1200;
    private static final int WINDOW_HEIGHT = 750;

    /** CompileAnalyzer はスレッドセーフなので全ペインで共有する */
    private static final CompileAnalyzer COMPILE_ANALYZER = new CompileAnalyzer();

    /** JDK クラスインデックス（起動時にバックグラウンドで構築） */
    private static final JdkClassIndex JDK_INDEX = JdkClassIndex.build();

    /** auto-import で使う SourceAnalyzer / ImportSuggester / AutoImportHandler */
    private static final SourceAnalyzer SOURCE_ANALYZER = new SourceAnalyzer();
    private static final ImportSuggester IMPORT_SUGGESTER = new ImportSuggester(JDK_INDEX);
    private static final AutoImportHandler AUTO_IMPORT_HANDLER =
        new AutoImportHandler(IMPORT_SUGGESTER, SOURCE_ANALYZER);

    /**
     * マウスカーソルがあるディスプレイの GraphicsConfiguration を返す。
     * 取得できない場合はプライマリモニターの設定を返す。
     */
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

    /**
     * フレームを指定した GraphicsConfiguration のモニター中央に配置する。
     */
    private static void centerOnScreen(JFrame frame, GraphicsConfiguration gc) {
        Rectangle bounds = gc.getBounds();
        int x = bounds.x + (bounds.width  - frame.getWidth())  / 2;
        int y = bounds.y + (bounds.height - frame.getHeight()) / 2;
        frame.setLocation(x, y);
    }

    /**
     * editor と canvas を接続し、INSERT→NORMAL 復帰時・保存時に
     * バックグラウンドでコンパイル解析を実行してガター表示を更新する。
     */
    private static void setupCompileAnalysis(ModalEditor editor, EditorCanvas canvas) {
        Runnable trigger = () -> {
            String filePath = editor.getCurrentFilePath();
            String source   = editor.getText();
            Thread.ofVirtual().start(() -> {
                try {
                    List<CompileDiagnostic> diags = (filePath != null)
                        ? COMPILE_ANALYZER.analyzeFile(Path.of(filePath))
                        : COMPILE_ANALYZER.analyze(source);
                    SwingUtilities.invokeLater(() -> {
                        canvas.setDiagnostics(diags);
                        editor.handleAutoImport(diags);
                    });
                } catch (AnalysisException e) {
                    SwingUtilities.invokeLater(() -> canvas.setDiagnostics(List.of()));
                }
            });
        };
        editor.setOnReturnToNormal(trigger);
        editor.setOnSave(trigger);
    }

    public static void main(String[] args) {
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

        // マウス位置の検出は AWT スレッド外でも安全
        final GraphicsConfiguration targetScreen = detectMouseScreen();

        final String text = initialText;
        final String path = initialPath;
        final boolean splash = (initialPath == null);

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Java Text Editor", targetScreen);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
            centerOnScreen(frame, targetScreen);

            // --- 単一ペイン（起動時は1画面表示）---
            EditorCanvas mainCanvas = new EditorCanvas();
            mainCanvas.setTheme(Theme.DARK_MODE);
            if (splash) mainCanvas.setShowSplash(true);
            ModalEditor mainEditor = new ModalEditor(text, path, mainCanvas);
            setupCompileAnalysis(mainEditor, mainCanvas);
            mainEditor.setJdkClassIndex(JDK_INDEX);
            mainEditor.setAutoImportHandler(AUTO_IMPORT_HANDLER);

            // --- 右ペイン（Ctrl+W で分割時に使用）---
            EditorCanvas splitCanvas = new EditorCanvas();
            splitCanvas.setTheme(Theme.DARK_MODE);
            ModalEditor splitEditor = new ModalEditor(text, path, splitCanvas);
            setupCompileAnalysis(splitEditor, splitCanvas);
            splitEditor.setJdkClassIndex(JDK_INDEX);
            splitEditor.setAutoImportHandler(AUTO_IMPORT_HANDLER);

            // アクティブペイン管理
            int[] activePaneIdx = {0};
            EditorCanvas[] canvases = {mainCanvas, splitCanvas};
            ModalEditor[] editors   = {mainEditor, splitEditor};
            boolean[] splitMode = {false};

            // 初期ボーダー（単一ペインでもアクティブ色を付ける）
            mainCanvas.setBorder(BorderFactory.createLineBorder(ACTIVE_BORDER_COLOR, 2));
            splitCanvas.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 1));

            // 単一ペインで起動
            frame.add(mainCanvas);

            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(e -> {
                    if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                    boolean ctrl = (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0;

                    // Ctrl+W: 単一⇔分割を切り替える
                    if (ctrl && e.getKeyCode() == KeyEvent.VK_W) {
                        if (!splitMode[0]) {
                            // 単一 → 分割
                            splitMode[0] = true;
                            frame.remove(mainCanvas);
                            JSplitPane splitPane = new JSplitPane(
                                JSplitPane.HORIZONTAL_SPLIT, mainCanvas, splitCanvas);
                            splitPane.setResizeWeight(0.5);
                            splitPane.setDividerSize(4);
                            splitPane.setBorder(null);
                            frame.add(splitPane);
                            frame.revalidate();
                            SwingUtilities.invokeLater(() ->
                                splitPane.setDividerLocation(frame.getWidth() / 2));
                        } else {
                            // 分割中はアクティブペインを切り替える
                            activePaneIdx[0] = 1 - activePaneIdx[0];
                            canvases[activePaneIdx[0]].setBorder(
                                BorderFactory.createLineBorder(ACTIVE_BORDER_COLOR, 2));
                            canvases[1 - activePaneIdx[0]].setBorder(
                                BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
                        }
                        return true;
                    }

                    editors[activePaneIdx[0]].processKey(
                        e.getKeyCode(), e.getKeyChar(), e.getModifiersEx());
                    return true;
                });

            frame.setVisible(true);
        });
    }
}
