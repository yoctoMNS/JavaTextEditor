package dev.javatexteditor.buffer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class UndoablePieceTable extends PieceTable {

    private final Deque<List<Piece>> undoStack = new ArrayDeque<>();
    private final Deque<List<Piece>> redoStack = new ArrayDeque<>();
    // :wa/:qa（Vim互換の全保存・全終了）の判定に使う「最後の保存以降に変更があったか」フラグ。
    // undo/redoで保存時点のテキストと文字列として一致する状態に戻っても modified は false に戻らない
    // （厳密な内容比較はせず「編集操作が行われたか」だけを見る単純な近似。既知の制約）。
    private boolean modified = false;

    public UndoablePieceTable(String initialText) {
        super(initialText);
    }

    private void snapshotBeforeEdit() {
        undoStack.push(getPieces());
        redoStack.clear();
    }

    @Override
    public void insert(int offset, String text) {
        snapshotBeforeEdit();
        super.insert(offset, text);
        modified = true;
    }

    @Override
    public void delete(int offset, int length) {
        snapshotBeforeEdit();
        super.delete(offset, length);
        modified = true;
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(getPieces());
        restorePieces(undoStack.pop());
        modified = true;
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(getPieces());
        restorePieces(redoStack.pop());
        modified = true;
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    /** :wa/:qa 用。最後の保存（{@link #markSaved()}）以降に編集操作が行われたか。 */
    public boolean isModified() { return modified; }

    /** 保存成功後に呼び、以降の isModified() を false に戻す。 */
    public void markSaved() { modified = false; }
}
