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

    private static final Color ACTIVE_BORDER_COLOR = new Color(0x88, 0x88, 0xFF);
    private static final int WINDOW_WIDTH  = 1200;
    private static final int WINDOW_HEIGHT = 750;

    private static final CompileAnalyzer COMPILE_ANALYZER = new CompileAnalyzer();
    private static final JdkClassIndex JDK_INDEX = JdkClassIndex.build();
    private static final SourceAnalyzer SOURCE_ANALYZER = new SourceAnalyzer();
    private static final ImportSuggester IMPORT_SUGGESTER = new ImportSuggester(JDK_INDEX);
    private static final AutoImportHandler AUTO_IMPORT_HANDLER =
        new AutoImportHandler(IMPORT_SUGGESTER, SOURCE_ANALYZER);

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
        // INSERT→NORMAL 遷移時: IMEを半角英数字に切り替えてからコンパイル解析を実行する
        editor.setOnReturnToNormal(() -> {
            canvas.switchToHalfWidth();
            trigger.run();
        });
        editor.setOnSave(trigger);
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
        canvas.setTheme(Theme.DARK_MODE);
        ModalEditor editor = new ModalEditor(text, path, canvas);
        setupCompileAnalysis(editor, canvas);
        editor.setJdkClassIndex(JDK_INDEX);
        editor.setAutoImportHandler(AUTO_IMPORT_HANDLER);
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

            Leaf firstLeaf = createLeaf(text, path);
            if (splash) firstLeaf.canvas().setShowSplash(true);

            PaneNode[] root   = { firstLeaf };
            Leaf[]     active = { firstLeaf };

            refreshCallbacks(frame, root, active);
            updateBorders(List.of(firstLeaf), firstLeaf);
            frame.add(firstLeaf.canvas());

            // KEY_PRESSEDで processKey を呼んだキーは KEY_TYPED でも届くため、
            // 二重処理を防ぐためにフラグで管理する。
            boolean[] pressedHandled = { false };

            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(e -> {
                    if (e.getID() == KeyEvent.KEY_PRESSED) {
                        pressedHandled[0] = false;

                        // Ctrl+Shift+矢印: ビットマップフォントのセルサイズを全ペイン一括変更
                        boolean ctrl  = (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK)  != 0;
                        boolean shift = (e.getModifiersEx() & KeyEvent.SHIFT_DOWN_MASK) != 0;
                        if (ctrl && shift) {
                            int kc = e.getKeyCode();
                            if (kc == KeyEvent.VK_RIGHT) {
                                allLeaves(root[0]).forEach(l -> l.canvas().adjustCellWidth(+1));
                                pressedHandled[0] = true; return true;
                            } else if (kc == KeyEvent.VK_LEFT) {
                                allLeaves(root[0]).forEach(l -> l.canvas().adjustCellWidth(-1));
                                pressedHandled[0] = true; return true;
                            } else if (kc == KeyEvent.VK_DOWN) {
                                allLeaves(root[0]).forEach(l -> l.canvas().adjustCellHeight(+1));
                                pressedHandled[0] = true; return true;
                            } else if (kc == KeyEvent.VK_UP) {
                                allLeaves(root[0]).forEach(l -> l.canvas().adjustCellHeight(-1));
                                pressedHandled[0] = true; return true;
                            }
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
}
