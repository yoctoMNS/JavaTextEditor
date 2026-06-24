package dev.vimacs;

import dev.vimacs.ui.EditorCanvas;
import dev.vimacs.ui.Theme;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Vimacs Editor");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(800, 600);

            EditorCanvas canvas = new EditorCanvas();
            canvas.setText("Hello, World!\nLine 2: abc def ghi\n日本語テスト\nLine 4: end of sample");
            canvas.setTheme(Theme.DARK_MODE);
            canvas.setCursor(0, 0);

            frame.add(canvas);
            frame.setVisible(true);
        });
    }
}
