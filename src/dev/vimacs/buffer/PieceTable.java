package dev.vimacs.buffer;

import java.util.ArrayList;
import java.util.List;

public class PieceTable {
    private final String original;
    private final StringBuilder addBuffer;
    private final List<Piece> pieces;

    public PieceTable(String originalText) {
        this.original = originalText;
        this.addBuffer = new StringBuilder();
        this.pieces = new ArrayList<>();
        if (!originalText.isEmpty()) {
            pieces.add(new Piece(Piece.Source.ORIGINAL, 0, originalText.length()));
        }
    }

    public void insert(int offset, String text) {
        if (text.isEmpty()) return;
        int addStart = addBuffer.length();
        addBuffer.append(text);
        Piece newPiece = new Piece(Piece.Source.ADD, addStart, text.length());

        int runningOffset = 0;
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            if (offset <= runningOffset + p.length()) {
                int splitPoint = offset - runningOffset;
                pieces.remove(i);
                int insertAt = i;
                if (splitPoint > 0) {
                    pieces.add(insertAt++, new Piece(p.source(), p.start(), splitPoint));
                }
                pieces.add(insertAt++, newPiece);
                if (splitPoint < p.length()) {
                    pieces.add(insertAt, new Piece(p.source(), p.start() + splitPoint, p.length() - splitPoint));
                }
                return;
            }
            runningOffset += p.length();
        }
        pieces.add(newPiece);
    }

    public void delete(int offset, int length) {
        if (length <= 0) return;
        int deleteEnd = offset + length;
        List<Piece> result = new ArrayList<>();
        int runningOffset = 0;

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
        }
        pieces.clear();
        pieces.addAll(result);
    }

    public int length() {
        return pieces.stream().mapToInt(Piece::length).sum();
    }

    public String getText() {
        StringBuilder result = new StringBuilder(length());
        for (Piece p : pieces) {
            String source = (p.source() == Piece.Source.ORIGINAL) ? original : addBuffer.toString();
            result.append(source, p.start(), p.start() + p.length());
        }
        return result.toString();
    }
}
