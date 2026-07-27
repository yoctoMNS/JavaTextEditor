package dev.javatexteditor.ui;

import java.util.List;

/**
 * 入力補完ポップアップ（Ctrl+Space / Alt+/）を描くために {@link EditorCanvas} が必要とする情報一式。
 *
 * <p>「開いているか・何を並べるか・何番目を選んでいるか・どのセルの下に出すか」がひとまとまりで
 * 意味を持つため、6個のフィールドをバラバラに受け渡すのではなく1つの値として扱う。
 * これにより、描画側が「一部だけ更新されて辻褄が合っていない状態」を見ることがなくなる。
 *
 * <p>候補を集める側の状態（{@code ModalEditor} の {@code CompletionPopupState}）とは別物である。
 * あちらは「どちらの索引を引き直すか」まで持つ編集側の状態、こちらは描画に必要な情報だけを写したもの。
 *
 * @param labels     候補の表示文字列
 * @param kinds      候補の種別（"cls"/"mth"/"fld"/"wd"）。{@code labels} と同じ並び
 * @param anchorRow  ポップアップを出す基準セルの行（入力中の識別子の先頭）
 * @param anchorCol  ポップアップを出す基準セルの列
 */
public record CompletionView(boolean active, List<String> labels, List<String> kinds,
                             int selectedIdx, int anchorRow, int anchorCol) {

    /** null を渡されても空リストとして扱い、リストは変更不能な写しにする。 */
    public CompletionView {
        labels = (labels != null) ? List.copyOf(labels) : List.of();
        kinds  = (kinds  != null) ? List.copyOf(kinds)  : List.of();
    }

    /** ポップアップを出さない状態。 */
    public static CompletionView hidden() {
        return new CompletionView(false, List.of(), List.of(), 0, 0, 0);
    }

    /** 実際に描くものがあるか（開いていて、かつ候補が1件以上ある）。 */
    public boolean hasVisibleItems() {
        return active && !labels.isEmpty();
    }

    /** {@code i} 番目の候補の種別。範囲外なら空文字列。 */
    public String kindAt(int i) {
        return (i < kinds.size()) ? kinds.get(i) : "";
    }
}
