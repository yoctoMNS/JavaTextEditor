import java.util.ArrayList;
import java.util.List;

record Piece(Source source, int start, int length) {
    enum Source { ORIGINAL, ADD }
}

class PieceTable {
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

public class PieceTableTest {
    public static void main(String[] args) {
        int pass = 0, fail = 0;

        // Test 1: 末尾への挿入
        PieceTable t1 = new PieceTable("Hello");
        t1.insert(5, " World");
        pass += check("末尾挿入", "Hello World", t1.getText());

        // Test 2: 先頭への挿入
        PieceTable t2 = new PieceTable("World");
        t2.insert(0, "Hello ");
        pass += check("先頭挿入", "Hello World", t2.getText());

        // Test 3: 中間への挿入（ピース分割が発生する）
        PieceTable t3 = new PieceTable("Helo");
        t3.insert(2, "l"); // "He" + "l" + "lo" -> Hello
        pass += check("中間挿入(分割)", "Hello", t3.getText());

        // Test 4: 複数回の挿入を経た後の、さらに中間挿入
        PieceTable t4 = new PieceTable("AC");
        t4.insert(1, "B");      // ABC
        t4.insert(3, "D");      // ABCD
        t4.insert(2, "X");      // ABXCD
        pass += check("複数回挿入後の中間挿入", "ABXCD", t4.getText());

        // Test 5: 単純な削除
        PieceTable t5 = new PieceTable("Hello World");
        t5.delete(5, 6); // " World" を削除
        pass += check("末尾範囲削除", "Hello", t5.getText());

        // Test 6: 挿入によって分割されたピース群をまたぐ削除
        PieceTable t6 = new PieceTable("AC");
        t6.insert(1, "B"); // ABC (pieces: [A][B][C])
        t6.delete(0, 3);   // 全削除
        pass += check("複数ピースをまたぐ全削除", "", t6.getText());

        // Test 7: ピースの一部だけ重なる削除（ピース分割を伴うdelete）
        PieceTable t7 = new PieceTable("0123456789");
        t7.delete(2, 4); // "2345"を削除 -> "0123456789" minus index2..5 -> "016789"
        pass += check("ピース内部の部分削除", "016789", t7.getText());

        // Test 8: 空文字列からの構築・挿入
        PieceTable t8 = new PieceTable("");
        t8.insert(0, "X");
        pass += check("空文書への挿入", "X", t8.getText());

        int total = 8;
        fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) {
            System.exit(1); // 失敗があればシェルスクリプト側でエラーとして検知できるようにする
        }
    }

    static int check(String name, String expected, String actual) {
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name + " -> expected=\"" + expected + "\" actual=\"" + actual + "\"");
        return ok ? 1 : 0;
    }
}
