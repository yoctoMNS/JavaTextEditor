package dev.javatexteditor.buffer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class UndoablePieceTable extends PieceTable {

    private final Deque<List<Piece>> undoStack = new ArrayDeque<>();
    private final Deque<List<Piece>> redoStack = new ArrayDeque<>();

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
    }

    @Override
    public void delete(int offset, int length) {
        snapshotBeforeEdit();
        super.delete(offset, length);
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(getPieces());
        restorePieces(undoStack.pop());
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(getPieces());
        restorePieces(redoStack.pop());
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }
}
