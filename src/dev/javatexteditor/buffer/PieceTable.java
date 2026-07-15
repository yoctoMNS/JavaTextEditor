package dev.javatexteditor.buffer;

import java.util.ArrayList;
import java.util.List;

public class PieceTable {
    private final String original;
    private final StringBuilder addBuffer;
    private final List<Piece> pieces;
    // length() のキャッシュ。以前は呼ばれるたびに全ピースを stream().sum() しており
    // ピース数に比例するコストがかかっていた（軽量化リファクタリング Phase 1）。
    // insert()/delete()/restorePieces() だけが更新する。
    private int totalLength;

    public PieceTable(String originalText) {
        this.original = originalText;
        this.addBuffer = new StringBuilder();
        this.pieces = new ArrayList<>();
        if (!originalText.isEmpty()) {
            pieces.add(new Piece(Piece.Source.ORIGINAL, 0, originalText.length()));
        }
        this.totalLength = originalText.length();
    }

    public void insert(int offset, String text) {
        if (text.isEmpty()) return;
        int addStart = addBuffer.length();
        addBuffer.append(text);
        totalLength += text.length();

        int runningOffset = 0;
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            int pieceEnd = runningOffset + p.length();
            if (offset < pieceEnd) {
                // ピース内部への挿入: p を分割して新ピースを挟む
                int splitPoint = offset - runningOffset;
                pieces.remove(i);
                int insertAt = i;
                if (splitPoint > 0) {
                    pieces.add(insertAt++, new Piece(p.source(), p.start(), splitPoint));
                }
                pieces.add(insertAt++, new Piece(Piece.Source.ADD, addStart, text.length()));
                // offset < pieceEnd により splitPoint < p.length() が保証されるため後半は常に非空
                pieces.add(insertAt, new Piece(p.source(), p.start() + splitPoint, p.length() - splitPoint));
                return;
            }
            if (offset == pieceEnd) {
                // ピース境界（p の直後）への挿入。
                // p が追加バッファの末尾（今回 append する直前の終端 addStart）をちょうど指している
                // 場合は、新ピースを作らず p を伸長する（連続タイピングの結合）。
                // この結合が無いと1キー入力ごとにピースが1個ずつ増え続け、insert/getText が
                // 編集回数に比例して遅くなる（セッション累計で O(K^2)）。
                // 条件を「addBuffer 末尾の所有者」に限定しているのは、削除等でピース末尾と
                // addBuffer 末尾がズレた後に誤って結合し、削除済みの文字が復活するのを防ぐため。
                if (p.source() == Piece.Source.ADD && p.start() + p.length() == addStart) {
                    pieces.set(i, new Piece(Piece.Source.ADD, p.start(), p.length() + text.length()));
                } else {
                    pieces.add(i + 1, new Piece(Piece.Source.ADD, addStart, text.length()));
                }
                return;
            }
            runningOffset = pieceEnd;
        }
        // 空文書（pieces が空）への挿入、または文書末尾を超えるオフセット（従来仕様どおり末尾扱い）
        pieces.add(new Piece(Piece.Source.ADD, addStart, text.length()));
    }

    public void delete(int offset, int length) {
        if (length <= 0) return;
        int deleteEnd = offset + length;
        List<Piece> result = new ArrayList<>();
        int runningOffset = 0;
        int removed = 0;

        for (Piece p : pieces) {
            int pieceStart = runningOffset;
            int pieceEnd = runningOffset + p.length();
            runningOffset = pieceEnd;

            boolean noOverlap = (pieceEnd <= offset) || (pieceStart >= deleteEnd);
            if (noOverlap) {
                result.add(p);
                continue;
            }
            int keepBeforeLen = Math.max(0, offset - pieceStart);
            int keepAfterStart = Math.max(pieceStart, deleteEnd);
            int keepAfterLen = pieceEnd - keepAfterStart;

            if (keepBeforeLen > 0) {
                result.add(new Piece(p.source(), p.start(), keepBeforeLen));
            }
            if (keepAfterLen > 0) {
                result.add(new Piece(p.source(), p.start() + (keepAfterStart - pieceStart), keepAfterLen));
            }
            removed += p.length() - keepBeforeLen - Math.max(0, keepAfterLen);
        }
        pieces.clear();
        pieces.addAll(result);
        totalLength -= removed;
    }

    public int length() {
        return totalLength;
    }

    public String getText() {
        StringBuilder result = new StringBuilder(totalLength);
        for (Piece p : pieces) {
            // addBuffer.toString() を使わず CharSequence として範囲 append する。
            // 以前は ADD ピースごとに追加バッファ全体を String へコピーしており、
            // 長い編集セッション後の getText() が「ADDピース数×追加バッファ長」の
            // 無駄なアロケーションを発生させていた（軽量化リファクタリング Phase 1）。
            if (p.source() == Piece.Source.ORIGINAL) {
                result.append(original, p.start(), p.start() + p.length());
            } else {
                result.append(addBuffer, p.start(), p.start() + p.length());
            }
        }
        return result.toString();
    }

    /**
     * 文書全体ではなく指定オフセット範囲だけを返す。
     * 画面に表示する数十行分だけを取り出すことで getText() の全文字列構築コストを避けられる。
     */
    public String getTextInRange(int startOffset, int endOffset) {
        StringBuilder result = new StringBuilder(Math.max(0, endOffset - startOffset));
        int runningOffset = 0;
        for (Piece p : pieces) {
            int pieceEnd = runningOffset + p.length();
            if (pieceEnd > startOffset && runningOffset < endOffset) {
                int from = Math.max(0, startOffset - runningOffset);
                int to = Math.min(p.length(), endOffset - runningOffset);
                if (p.source() == Piece.Source.ORIGINAL) {
                    result.append(original, p.start() + from, p.start() + to);
                } else {
                    result.append(addBuffer, p.start() + from, p.start() + to);
                }
            }
            runningOffset = pieceEnd;
            if (runningOffset >= endOffset) break;
        }
        return result.toString();
    }

    /**
     * N行目が何文字目（0-based オフセット）から始まるかを返す。
     * ピースを直接走査するため getText() による全文再構築・アロケーションを伴わない
     * （軽量化リファクタリング Phase 1。従来は毎回全文 String を構築していた）。
     */
    public int offsetOfLine(int lineNumber) {
        if (lineNumber == 0) return 0;
        int currentLine = 0;
        int runningOffset = 0;
        for (Piece p : pieces) {
            CharSequence src = (p.source() == Piece.Source.ORIGINAL) ? original : addBuffer;
            int end = p.start() + p.length();
            for (int i = p.start(); i < end; i++) {
                if (src.charAt(i) == '\n') {
                    currentLine++;
                    if (currentLine == lineNumber) {
                        return runningOffset + (i - p.start()) + 1;
                    }
                }
            }
            runningOffset += p.length();
        }
        return totalLength;
    }

    protected List<Piece> getPieces() {
        return List.copyOf(pieces);
    }

    protected void restorePieces(List<Piece> snapshot) {
        pieces.clear();
        pieces.addAll(snapshot);
        // undo/redo でピースリストが丸ごと差し替わるため、キャッシュを再集計する。
        // スナップショットのピース数は結合により小さく保たれるので O(P) でも実質定数。
        int sum = 0;
        for (Piece p : snapshot) sum += p.length();
        totalLength = sum;
    }
}
