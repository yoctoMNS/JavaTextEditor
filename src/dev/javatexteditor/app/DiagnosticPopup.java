package dev.javatexteditor.app;

import dev.javatexteditor.analysis.CompileDiagnostic;
import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.ui.EditorCanvas;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/**
 * F2 でカーソル行の診断（コンパイルエラー・警告）をモーダルダイアログに表示する。
 *
 * <p>{@code Main} のグローバルキーディスパッチャ内に直書きされていた56行を切り出した
 * （MAIN_DECOMPOSITION_PLAN.md 段階4）。
 *
 * <p><b>ダイアログの親は必ず {@code owner}（＝アプリの {@link JFrame}）にすること。</b>
 * {@code Main} のキーディスパッチャ冒頭には
 * 「フォーカスされているウィンドウがメインフレームでなければキー処理をスキップする」
 * というガード（{@code if (focused != frame) return false;}）があり、
 * この2つは対で機能している。親を変えたり {@code null} にしたりすると、
 * ダイアログ表示中のキー入力がエディタ本体にも流れ込む。
 *
 * <p><b>{@code canvas} 引数は実際に使用している</b>（{@code getDiagnostics()}）。
 * ビルド・実行系で削除した死んだ引数（MAIN_DECOMPOSITION_PLAN.md R-7）とは別件なので
 * 混同しないこと。
 */
public final class DiagnosticPopup {

    private DiagnosticPopup() {}

    /**
     * カーソル行に紐づく診断をモーダルダイアログで表示する。
     * 診断が無ければその旨のメッセージを出す（何も表示しないのではなく、明示的に伝える）。
     */
    public static void showForCursorRow(JFrame owner, ModalEditor editor, EditorCanvas canvas) {
        int row = editor.getCursorRow();
        List<CompileDiagnostic> diags = canvas.getDiagnostics();
        List<CompileDiagnostic> rowDiags = diags.stream()
            .filter(d -> d.lineNumber() == row)
            .toList();
        Font f2Font = computePopupFont(owner);
        if (rowDiags.isEmpty()) {
            JLabel f2Label = new JLabel("この行にエラー・警告はありません。");
            f2Label.setFont(f2Font);
            JOptionPane.showMessageDialog(owner,
                f2Label,
                "診断情報（行 " + (row + 1) + "）",
                JOptionPane.INFORMATION_MESSAGE);
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rowDiags.size(); i++) {
                CompileDiagnostic d = rowDiags.get(i);
                if (i > 0) sb.append("\n\n");
                String kindLabel = switch (d.kind()) {
                    case ERROR   -> "エラー";
                    case WARNING -> "警告";
                };
                sb.append("[").append(kindLabel).append("]");
                if (d.column() >= 0) {
                    sb.append("  列: ").append(d.column() + 1);
                }
                sb.append("\n").append(d.message());
            }
            int iconType = rowDiags.stream().anyMatch(
                d -> d.kind() == dev.javatexteditor.analysis.DiagnosticKind.ERROR)
                ? JOptionPane.ERROR_MESSAGE
                : JOptionPane.WARNING_MESSAGE;
            JTextArea f2Area = new JTextArea(sb.toString());
            f2Area.setFont(f2Font);
            f2Area.setEditable(false);
            f2Area.setLineWrap(true);
            f2Area.setWrapStyleWord(true);
            f2Area.setBackground(UIManager.getColor("OptionPane.background"));
            int screenW = owner.getGraphicsConfiguration().getBounds().width;
            int screenH = owner.getGraphicsConfiguration().getBounds().height;
            JScrollPane f2Scroll = new JScrollPane(f2Area);
            f2Scroll.setBorder(BorderFactory.createEmptyBorder());
            f2Scroll.setPreferredSize(new Dimension(
                Math.min(screenW * 3 / 5, 900),
                Math.min(screenH * 2 / 5, 500)));
            JOptionPane.showMessageDialog(owner,
                f2Scroll,
                "診断情報（行 " + (row + 1) + "）",
                iconType);
        }
    }

    /**
     * F2診断ポップアップの文字サイズを、フレームが乗っている画面の高さに比例して計算する。
     * 4Kディスプレイ等の高解像度画面でも既定のJOptionPaneフォント（画面によらず固定サイズ）が
     * 相対的に小さく読みにくくなる問題への対応。14〜28ptの範囲でクランプする。
     */
    private static Font computePopupFont(JFrame frame) {
        int screenHeight = frame.getGraphicsConfiguration().getBounds().height;
        int size = Math.max(14, Math.min(28, screenHeight / 45));
        Font base = UIManager.getFont("OptionPane.messageFont");
        String family = base != null ? base.getFamily() : Font.SANS_SERIF;
        return new Font(family, Font.PLAIN, size);
    }
}
