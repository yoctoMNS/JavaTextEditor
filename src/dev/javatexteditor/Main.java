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
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

public class Main {

    private static final Color ACTIVE_BORDER_COLOR = new Color(0x88, 0x88, 0xFF);
    private static final int WINDOW_WIDTH  = 1200;
    private static final int WINDOW_HEIGHT = 750;

    private static final CompileAnalyzer COMPILE_ANALYZER = new CompileAnalyzer();
    private static final JdkClassIndex JDK_INDEX = JdkClassIndex.build();
    private static final SourceAnalyzer SOURCE_ANALYZER = new SourceAnalyzer();
    private static final ImportSuggester IMPORT_SUGGESTER = new ImportSuggester(JDK_INDEX);
    private static final AutoImportHandler AUTO_IMPORT_HANDLER =
        new AutoImportHandler(IMPORT_SUGGESTER, SOURCE_ANALYZER);

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

    /** ペインのリストから JSplitPane ツリーを再帰的に構築する。 */
    private static java.awt.Component buildLayout(List<EditorCanvas> canvases, int from, int to) {
        if (to - from == 1) return canvases.get(from);
        int mid = (from + to) / 2;
        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            buildLayout(canvases, from, mid),
            buildLayout(canvases, mid, to));
        sp.setResizeWeight(0.5);
        sp.setDividerSize(4);
        sp.setBorder(null);
        return sp;
    }

    /** フレームのコンテンツペインを現在のペインリストで再構築する。 */
    private static void rebuildLayout(JFrame frame, List<EditorCanvas> canvases, int activeIdx) {
        frame.getContentPane().removeAll();
        java.awt.Component layout = buildLayout(canvases, 0, canvases.size());
        frame.getContentPane().add(layout);
        frame.revalidate();
        frame.repaint();
        // 分割位置を均等に設定（次の EDT サイクルで確定させる）
        SwingUtilities.invokeLater(() -> setDividerLocations(layout, frame.getWidth(), canvases.size()));
        updateBorders(canvases, activeIdx);
    }

    /** JSplitPane ツリーを幅均等になるよう再帰的に分割位置を設定する。 */
    private static void setDividerLocations(java.awt.Component comp, int totalWidth, int paneCount) {
        if (!(comp instanceof JSplitPane sp)) return;
        // 左側に占めるペイン数を推定
        int leftPanes = paneCount / 2;
        int divLoc = (int)((double) totalWidth * leftPanes / paneCount);
        sp.setDividerLocation(divLoc);
        setDividerLocations(sp.getLeftComponent(),  divLoc, leftPanes);
        setDividerLocations(sp.getRightComponent(), totalWidth - divLoc - sp.getDividerSize(), paneCount - leftPanes);
    }

    private static void updateBorders(List<EditorCanvas> canvases, int activeIdx) {
        for (int i = 0; i < canvases.size(); i++) {
            canvases.get(i).setBorder(i == activeIdx
                ? BorderFactory.createLineBorder(ACTIVE_BORDER_COLOR, 2)
                : BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
        }
    }

    /** 新しいペインを作成してリストへ追加し、各コールバックを設定する。 */
    private static void addPane(
            JFrame frame,
            List<EditorCanvas> canvases,
            List<ModalEditor> editors,
            int[] activePaneIdx,
            String text, String path) {

        EditorCanvas canvas = new EditorCanvas();
        canvas.setTheme(Theme.DARK_MODE);
        ModalEditor editor = new ModalEditor(text, path, canvas);
        setupCompileAnalysis(editor, canvas);
        editor.setJdkClassIndex(JDK_INDEX);
        editor.setAutoImportHandler(AUTO_IMPORT_HANDLER);

        // アクティブの右隣に挿入
        int insertAt = activePaneIdx[0] + 1;
        canvases.add(insertAt, canvas);
        editors.add(insertAt, editor);

        // 新しいペインをアクティブにする
        activePaneIdx[0] = insertAt;

        setupPaneCallbacks(frame, canvases, editors, activePaneIdx, editor);
        rebuildLayout(frame, canvases, activePaneIdx[0]);
    }

    /**
     * エディタの :q / exitCallback / closeBlockedCallback を設定する。
     * ペインリストの状態に依存するため、ペイン追加・削除のたびに全ペインへ再設定する。
     */
    private static void setupPaneCallbacks(
            JFrame frame,
            List<EditorCanvas> canvases,
            List<ModalEditor> editors,
            int[] activePaneIdx,
            ModalEditor editor) {

        editor.setExitCallback(() -> {
            if (canvases.size() == 1) {
                // 最後の1ペイン → アプリ終了
                System.exit(0);
            }
            closeActivePane(frame, canvases, editors, activePaneIdx);
        });

        editor.setCloseBlockedCallback(null); // 1ペイン以上なら常に閉じられる

        // 最後の1ペインになったとき用に全ペインのコールバックを同期させる
        // （実際の制御は setExitCallback 内の canvases.size() チェックで行う）
    }

    /** アクティブペインを閉じてレイアウトを再構築する。 */
    private static void closeActivePane(
            JFrame frame,
            List<EditorCanvas> canvases,
            List<ModalEditor> editors,
            int[] activePaneIdx) {

        if (canvases.size() <= 1) return; // 1ペイン以下は閉じない

        int idx = activePaneIdx[0];
        canvases.remove(idx);
        editors.remove(idx);

        // 閉じた後のアクティブインデックスを決定
        activePaneIdx[0] = Math.min(idx, canvases.size() - 1);

        rebuildLayout(frame, canvases, activePaneIdx[0]);
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

        final GraphicsConfiguration targetScreen = detectMouseScreen();
        final String text = initialText;
        final String path = initialPath;
        final boolean splash = (initialPath == null);

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Java Text Editor", targetScreen);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
            centerOnScreen(frame, targetScreen);

            // ペイン管理リスト
            List<EditorCanvas> canvases = new ArrayList<>();
            List<ModalEditor> editors   = new ArrayList<>();
            int[] activePaneIdx = {0};

            // 初期ペインを作成
            EditorCanvas firstCanvas = new EditorCanvas();
            firstCanvas.setTheme(Theme.DARK_MODE);
            if (splash) firstCanvas.setShowSplash(true);
            ModalEditor firstEditor = new ModalEditor(text, path, firstCanvas);
            setupCompileAnalysis(firstEditor, firstCanvas);
            firstEditor.setJdkClassIndex(JDK_INDEX);
            firstEditor.setAutoImportHandler(AUTO_IMPORT_HANDLER);

            canvases.add(firstCanvas);
            editors.add(firstEditor);
            setupPaneCallbacks(frame, canvases, editors, activePaneIdx, firstEditor);
            updateBorders(canvases, 0);
            frame.add(firstCanvas);

            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(e -> {
                    if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                    boolean ctrl = (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0;

                    if (ctrl && e.getKeyCode() == KeyEvent.VK_W) {
                        // アクティブペインを右に分割（新ペインを追加）
                        int idx = activePaneIdx[0];
                        String t = editors.get(idx).getText();
                        String p = editors.get(idx).getCurrentFilePath();
                        addPane(frame, canvases, editors, activePaneIdx, t, p);
                        // 追加後は全ペインのコールバックを再設定
                        for (ModalEditor ed : editors) {
                            setupPaneCallbacks(frame, canvases, editors, activePaneIdx, ed);
                        }
                        return true;
                    }

                    editors.get(activePaneIdx[0]).processKey(
                        e.getKeyCode(), e.getKeyChar(), e.getModifiersEx());

                    // アクティブペインが変わった可能性があるのでボーダーを同期
                    updateBorders(canvases, activePaneIdx[0]);
                    return true;
                });

            frame.setVisible(true);
        });
    }
}
