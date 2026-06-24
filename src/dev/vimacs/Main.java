package dev.vimacs;

import dev.vimacs.editor.ModalEditor;
import dev.vimacs.ui.EditorCanvas;
import dev.vimacs.ui.Theme;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public class Main {
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
            demoText.append("日本語テスト行: ひらがな・カタカナ・漢字が混在しても動作する\n");
            demoText.append("---\n");
            for (int i = 5; i <= 110; i++) {
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
            frame.setSize(800, 600);

            EditorCanvas canvas = new EditorCanvas();
            canvas.setTheme(Theme.DARK_MODE);

            ModalEditor editor = new ModalEditor(text, path, canvas);

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
