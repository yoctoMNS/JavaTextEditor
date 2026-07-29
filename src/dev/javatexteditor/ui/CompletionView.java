package dev.javatexteditor.ui;

import java.util.List;

/**
 * 入力補完ポップアップ（Ctrl+Space / Alt+/）を描くために {@link EditorCanvas} が必要とする情報一式。
 *
 * <p>「開いているか・何を並べるか・何番目を選んでいるか・どのセルの下に出すか」がひとまとまりで
 * 意味を持つため、バラバラに受け渡すのではなく1つの値として扱う。
 * これにより、描画側が「一部だけ更新されて辻褄が合っていない状態」を見ることがなくなる。
 *
 * <p>候補を集める側の状態（{@code ModalEditor} の {@code CompletionPopupState}）とは別物である。
 * あちらは「どちらの索引を引き直すか」まで持つ編集側の状態、こちらは描画に必要な情報だけを写したもの。
 *
 * @param rows       候補1件ずつの表示内容
 * @param anchorRow  ポップアップを出す基準セルの行（入力中の識別子の先頭）
 * @param anchorCol  ポップアップを出す基準セルの列
 */
public record CompletionView(boolean active, List<Row> rows,
                             int selectedIdx, int anchorRow, int anchorCol) {

    /**
     * 候補1件の表示内容。IntelliJ IDEA の候補行と同じ3要素で構成する。
     *
     * @param label      主表示（メソッド名・クラス名・単語）
     * @param kind       種別タグ（"cls"/"mth"/"fld"/"var"/"kw"/"wd"）
     * @param tailText   label の直後に淡色で続く補足（メソッドの引数リスト等）
     * @param typeText   行の右端に表示する型情報
     * @param highlights label 内で入力に一致した文字の位置（強調表示に使う）
     */
    public record Row(String label, String kind, String tailText, String typeText, int[] highlights) {

        public Row {
            label = (label != null) ? label : "";
            kind = (kind != null) ? kind : "";
            tailText = (tailText != null) ? tailText : "";
            typeText = (typeText != null) ? typeText : "";
            // 配列は共有すると呼び出し側の変更が描画に漏れるため写しを持つ
            highlights = (highlights != null) ? highlights.clone() : new int[0];
        }

        /** i 文字目が入力と一致した文字かどうか。 */
        public boolean isHighlighted(int i) {
            for (int pos : highlights) {
                if (pos == i) return true;
            }
            return false;
        }
    }

    /** null を渡されても空リストとして扱い、リストは変更不能な写しにする。 */
    public CompletionView {
        rows = (rows != null) ? List.copyOf(rows) : List.of();
    }

    /** ポップアップを出さない状態。 */
    public static CompletionView hidden() {
        return new CompletionView(false, List.of(), 0, 0, 0);
    }

    /** 実際に描くものがあるか（開いていて、かつ候補が1件以上ある）。 */
    public boolean hasVisibleItems() {
        return active && !rows.isEmpty();
    }
}
