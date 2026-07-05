package dev.javatexteditor.completion2;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import javax.swing.undo.UndoManager;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Alt+/ 単語補完を実際にウィンドウで目視確認するための手動デモ。
 * 自動テストではないため test.sh からは実行されない（VisualPreview.java と同様の位置づけ）。
 * ディスプレイのある環境で以下のように実行する:
 *
 *   ./scripts/build.sh
 *   javac -encoding UTF-8 -cp build -d build test/dev/javatexteditor/completion2/CompletionPopupDemo.java
 *   java -cp build dev.javatexteditor.completion2.CompletionPopupDemo
 *
 * Ctrl+Z / Ctrl+Y でUndo/Redoの挙動も確認できる。
 */
public final class CompletionPopupDemo {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(CompletionPopupDemo::createAndShow);
    }

    private static void createAndShow() {
        JTextArea textArea = new JTextArea(
            "userName userAge userNameHistory\n"
            + "int totalCount = 0;\n"
            + "// ここでカーソルを 'user' や 'total' の続きに置いて Alt+/ を押す\n");
        textArea.setCaretPosition(textArea.getText().length());

        UndoManager undoManager = new UndoManager();
        Document doc = textArea.getDocument();
        doc.addUndoableEditListener(undoManager);

        registerUndoRedoKeys(textArea, undoManager);

        CompletionController controller = new CompletionController(textArea, new CompletionEngine(), undoManager);
        EditorKeyHandler.install(textArea, controller);

        JFrame frame = new JFrame("Alt+/ Completion Demo");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().add(new JScrollPane(textArea));
        frame.setSize(640, 400);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void registerUndoRedoKeys(JTextArea textArea, UndoManager undoManager) {
        textArea.getInputMap().put(
            javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "demo-undo");
        textArea.getActionMap().put("demo-undo", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canUndo()) {
                    undoManager.undo();
                }
            }
        });
        textArea.getInputMap().put(
            javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "demo-redo");
        textArea.getActionMap().put("demo-redo", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (undoManager.canRedo()) {
                    undoManager.redo();
                }
            }
        });
    }
}
