package dev.vimacs;

import dev.vimacs.analysis.AnalysisException;
import dev.vimacs.analysis.CompileAnalyzer;
import dev.vimacs.analysis.CompileDiagnostic;
import dev.vimacs.analysis.JdkClassIndex;
import dev.vimacs.editor.ModalEditor;
import dev.vimacs.ui.EditorCanvas;
import dev.vimacs.ui.Theme;
import java.awt.Color;
import java.awt.KeyboardFocusManager;
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

    /** CompileAnalyzer はスレッドセーフなので全ペインで共有する */
    private static final CompileAnalyzer COMPILE_ANALYZER = new CompileAnalyzer();

    /** JDK クラスインデックス（起動時にバックグラウンドで構築） */
    private static final JdkClassIndex JDK_INDEX = JdkClassIndex.build();

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
                    SwingUtilities.invokeLater(() -> canvas.setDiagnostics(diags));
                } catch (AnalysisException e) {
                    // コンパイラが使えない環境でも静かに失敗する
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
            StringBuilder demoText = new StringBuilder();
            demoText.append("=== Vimacs Editor Demo ===\n");
            demoText.append("j/k: 上下移動  h/l: 左右移動  i: INSERTへ  Esc: NORMALへ\n");
            demoText.append(": でコマンドモードへ。:w <path> で保存、:e <path> でファイルを開く\n");
            demoText.append("Ctrl+W: 左右ペイン切り替え（アクティブ=青枠）\n");
            demoText.append("日本語テスト行: ひらがな・カタカナ・漢字が混在しても動作する\n");
            demoText.append("---\n");
            for (int i = 6; i <= 110; i++) {
                demoText.append("Line ").append(i).append(": ")
                    .append("The quick brown fox jumps over the lazy dog. (行番号=").append(i).append(")\n");
            }
            demoText.append("=== End of Demo ===\n");
            initialText = demoText.toString();
        }

        final String text = initialText;
        final String path = initialPath;
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Vimacs Editor");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(1200, 700);

            // --- 左ペイン ---
            EditorCanvas leftCanvas = new EditorCanvas();
            leftCanvas.setTheme(Theme.DARK_MODE);
            ModalEditor leftEditor = new ModalEditor(text, path, leftCanvas);
            setupCompileAnalysis(leftEditor, leftCanvas);
            leftEditor.setJdkClassIndex(JDK_INDEX);

            // --- 右ペイン ---
            EditorCanvas rightCanvas = new EditorCanvas();
            rightCanvas.setTheme(Theme.DARK_MODE);
            ModalEditor rightEditor = new ModalEditor(text, path, rightCanvas);
            setupCompileAnalysis(rightEditor, rightCanvas);
            rightEditor.setJdkClassIndex(JDK_INDEX);

            // JSplitPane で左右に並べる
            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftCanvas, rightCanvas);
            splitPane.setResizeWeight(0.5);
            splitPane.setDividerSize(4);
            splitPane.setBorder(null);

            // アクティブペイン管理（0=左, 1=右）
            int[] activePaneIdx = {0};
            EditorCanvas[] canvases = {leftCanvas, rightCanvas};
            ModalEditor[] editors = {leftEditor, rightEditor};

            // 初期ボーダー（左がアクティブ）
            leftCanvas.setBorder(BorderFactory.createLineBorder(ACTIVE_BORDER_COLOR, 2));
            rightCanvas.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 1));

            // KeyboardFocusManager でフォーカスに関係なく全キーを捕捉する
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(e -> {
                    if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                    boolean ctrl = (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0;

                    // Ctrl+W: アクティブペインを切り替える
                    if (ctrl && e.getKeyCode() == KeyEvent.VK_W) {
                        activePaneIdx[0] = 1 - activePaneIdx[0];
                        canvases[activePaneIdx[0]].setBorder(
                            BorderFactory.createLineBorder(ACTIVE_BORDER_COLOR, 2));
                        canvases[1 - activePaneIdx[0]].setBorder(
                            BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
                        return true;
                    }

                    editors[activePaneIdx[0]].processKey(
                        e.getKeyCode(), e.getKeyChar(), e.getModifiersEx());
                    return true;
                });

            frame.add(splitPane);
            frame.setVisible(true);

            // 初期表示後に分割位置を 50:50 に設定する
            SwingUtilities.invokeLater(() ->
                splitPane.setDividerLocation(frame.getWidth() / 2));
        });
    }
}
