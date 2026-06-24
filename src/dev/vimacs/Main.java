package dev.vimacs;

import dev.vimacs.editor.ModalEditor;
import dev.vimacs.ui.EditorCanvas;
import dev.vimacs.ui.Theme;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Vimacs Editor");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(800, 600);

            EditorCanvas canvas = new EditorCanvas();
            canvas.setTheme(Theme.DARK_MODE);

            ModalEditor editor = new ModalEditor(
                "Hello, World!\nLine 2: abc def ghi\n日本語テスト\nLine 4: end of sample",
                canvas
            );

            // KeyboardFocusManager はフォーカス状態に関係なく全キーを捕捉する
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(e -> {
                    if (e.getID() == KeyEvent.KEY_PRESSED) {
                        editor.processKey(e.getKeyCode(), e.getKeyChar(), e.getModifiersEx());
                        return true;
                    }
                    return false;
                });

            frame.add(canvas);
            frame.setVisible(true);
        });
    }
}
