package dev.javatexteditor.app;

import dev.javatexteditor.editor.ModalEditor;
import java.io.IOException;
import javax.swing.SwingUtilities;

/**
 * 実行中プロセスの出力ストリームを読み取り、{@code *run*} 疑似バッファへ
 * リアルタイムに流し込む。
 *
 * <p>{@code Main.startRunOutputReader} から切り出した（MAIN_DECOMPOSITION_PLAN.md 段階2）。
 * {@link JavaBuildRunner} と {@link CBuildRunner} の両方から使われる。
 */
public final class ProcessOutputPump {

    private ProcessOutputPump() {}

    /**
     * 実行中プロセスの標準出力/標準エラーを1行読むたび *run* 疑似バッファへリアルタイム反映する
     * 読み取り専用スレッドを起動する（isError=trueなら標準エラー由来として赤字表示される）。
     */
    public static Thread start(java.io.InputStream in, ModalEditor editor, boolean isError) {
        Thread t = Thread.ofVirtual().unstarted(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String finalLine = line;
                    SwingUtilities.invokeLater(() -> {
                        editor.appendRunOutputLine(finalLine, isError);
                        editor.syncCanvas();
                    });
                }
            } catch (IOException ignored) {
            }
        });
        t.start();
        return t;
    }
}
