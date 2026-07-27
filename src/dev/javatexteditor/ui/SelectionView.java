package dev.javatexteditor.ui;

/**
 * 選択範囲の描画状態。「どの種類の選択か」と「どこからどこまでか」を1つの値にしたもの。
 *
 * <p>{@link EditorCanvas} は内部では {@code visualMode} / {@code visualLineMode} /
 * {@code visualBlockMode} という3つの boolean で選択の種類を持っている。
 * この3つは本来 {@link Kind} という1つの4状態の値であり、バラバラに更新すると
 * 「VISUAL BLOCK なのに行選択のフラグも立っている」といった辻褄の合わない状態を作れてしまう。
 * このレコードを経由して渡すことで、3つのフラグと座標が必ず一貫した組で設定される。
 *
 * <p>内部表現を {@link Kind} そのものに置き換えていないのは意図的である。
 * 既存の {@code setVisualMode} / {@code setVisualLineMode} / {@code setVisualBlockMode} /
 * {@code clearSelection} は互いに独立した部分更新として呼ばれており、
 * {@code EditorCanvasTest} が実際に順序を入れ替えて呼んでいる。
 * これらを4状態の列挙へ写す変換は呼び出し順によって解釈が割れるうえ、
 * 当該テストは描画が例外なく完了することしか確認しないため誤った変換を検出できない。
 * そのため内部表現は据え置き、<b>新しい呼び出し側だけがこの型を使う</b>移行方式にしている。
 */
public record SelectionView(Kind kind, int anchorRow, int anchorCol, int cursorRow, int cursorCol) {

    /** 選択の種類。 */
    public enum Kind {
        /** 選択なし。 */
        NONE,
        /** v — 文字単位の選択。 */
        CHARACTER,
        /** V — 行単位の選択。 */
        LINE,
        /** Ctrl+V — 矩形選択。 */
        BLOCK
    }

    /** 選択していない状態。 */
    public static SelectionView none() {
        return new SelectionView(Kind.NONE, -1, -1, -1, -1);
    }

    /** 文字単位・矩形選択（列に意味がある選択）。 */
    public static SelectionView of(Kind kind, int anchorRow, int anchorCol, int cursorRow, int cursorCol) {
        return new SelectionView(kind, anchorRow, anchorCol, cursorRow, cursorCol);
    }

    /** 行単位の選択。行選択は列を持たないため、列は常に0として扱う。 */
    public static SelectionView ofLines(int anchorRow, int cursorRow) {
        return new SelectionView(Kind.LINE, anchorRow, 0, cursorRow, 0);
    }

    public boolean isActive() { return kind != Kind.NONE; }
    public boolean isLine()   { return kind == Kind.LINE; }
    public boolean isBlock()  { return kind == Kind.BLOCK; }
}
