package dev.vimacs;

import dev.vimacs.editor.ModalEditor;
import dev.vimacs.ui.EditorCanvas;
import dev.vimacs.ui.Theme;
import java.awt.event.KeyAdapter;
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
            canvas.setFocusable(true);

            ModalEditor editor = new ModalEditor(
                "Hello, World!\nLine 2: abc def ghi\n日本語テスト\nLine 4: end of sample",
                canvas
            );

            canvas.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    editor.processKey(e.getKeyCode(), e.getKeyChar(), e.getModifiersEx());
                    e.consume();
                }
            });

            frame.add(canvas);
            frame.setVisible(true);
            canvas.requestFocusInWindow();
        });
    }
}
