package dev.javatexteditor.buffer;

public class PieceTableEdgeCaseTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) {
        // --- 空バッファの境界値 ---
        testEmptyBuffer();

        // --- 1文字バッファ ---
        testSingleCharBuffer();

        // --- 境界削除（先頭・末尾・全体） ---
        testBoundaryDelete();

        // --- 連続挿入が先頭・末尾に集中するケース ---
        testRepeatedInsertAtSamePosition();

        // --- ゼロ長削除・ゼロ長挿入は無視される ---
        testNoOpEdits();

        // --- getTextInRange の境界値 ---
        testGetTextInRangeBoundary();

        // --- offsetOfLine の境界値 ---
        testOffsetOfLineBoundary();

        // --- 多数の小さな挿入によるピース爆発後の getText 整合性 ---
        testManySmallInserts();

        // --- 多数の削除後の getText 整合性 ---
        testManyDeletes();

        // --- 改行のみの文書 ---
        testNewlineOnlyDocument();

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) System.exit(1);
    }

    // =========================================================================
    // 空バッファ
    // =========================================================================
    static void testEmptyBuffer() {
        PieceTable t = new PieceTable("");
        check("空バッファ.getText()==\"\"", "", t.getText());
        check("空バッファ.length()==0", 0, t.length());
        check("空バッファ.getTextInRange(0,0)==\"\"", "", t.getTextInRange(0, 0));
        check("空バッファ.offsetOfLine(0)==0", 0, t.offsetOfLine(0));

        // 空バッファに挿入してから削除して戻す
        t.insert(0, "A");
        t.delete(0, 1);
        check("空バッファ挿入→削除後getText()==\"\"", "", t.getText());
        check("空バッファ挿入→削除後length()==0", 0, t.length());
    }

    // =========================================================================
    // 1文字バッファ
    // =========================================================================
    static void testSingleCharBuffer() {
        PieceTable t = new PieceTable("X");
        check("1文字.getText()", "X", t.getText());
        check("1文字.length()", 1, t.length());
        check("1文字.getTextInRange(0,1)", "X", t.getTextInRange(0, 1));
        check("1文字.offsetOfLine(0)", 0, t.offsetOfLine(0));

        t.delete(0, 1);
        check("1文字削除後.getText()==\"\"", "", t.getText());
    }

    // =========================================================================
    // 境界削除
    // =========================================================================
    static void testBoundaryDelete() {
        // 先頭1文字削除
        PieceTable t1 = new PieceTable("ABC");
        t1.delete(0, 1);
        check("先頭1文字削除", "BC", t1.getText());

        // 末尾1文字削除
        PieceTable t2 = new PieceTable("ABC");
        t2.delete(2, 1);
        check("末尾1文字削除", "AB", t2.getText());

        // 全体削除
        PieceTable t3 = new PieceTable("ABC");
        t3.delete(0, 3);
        check("全体削除", "", t3.getText());
        check("全体削除後length==0", 0, t3.length());

        // 削除後に再挿入
        t3.insert(0, "Z");
        check("全体削除→再挿入", "Z", t3.getText());

        // 改行を含む先頭行削除
        PieceTable t4 = new PieceTable("line0\nline1\nline2");
        t4.delete(0, 6); // "line0\n" を削除
        check("先頭行削除", "line1\nline2", t4.getText());
    }

    // =========================================================================
    // 同一位置への連続挿入
    // =========================================================================
    static void testRepeatedInsertAtSamePosition() {
        // 末尾への連続追記
        PieceTable t = new PieceTable("");
        for (int i = 0; i < 10; i++) {
            t.insert(t.length(), String.valueOf((char)('A' + i)));
        }
        check("末尾連続追記10回", "ABCDEFGHIJ", t.getText());

        // 先頭への連続挿入（逆順になる）
        PieceTable t2 = new PieceTable("");
        for (int i = 0; i < 5; i++) {
            t2.insert(0, String.valueOf((char)('A' + i)));
        }
        check("先頭連続挿入5回（逆順）", "EDCBA", t2.getText());
    }

    // =========================================================================
    // ゼロ長操作はno-op
    // =========================================================================
    static void testNoOpEdits() {
        PieceTable t = new PieceTable("Hello");
        t.insert(2, ""); // 空文字挿入
        check("空文字挿入はno-op", "Hello", t.getText());

        t.delete(2, 0); // 長さ0の削除
        check("長さ0の削除はno-op", "Hello", t.getText());
    }

    // =========================================================================
    // getTextInRange の境界値
    // =========================================================================
    static void testGetTextInRangeBoundary() {
        PieceTable t = new PieceTable("ABCDE");

        // 全体取得
        check("getTextInRange全体", "ABCDE", t.getTextInRange(0, 5));

        // 空範囲
        check("getTextInRange空範囲(2,2)", "", t.getTextInRange(2, 2));

        // 先頭1文字
        check("getTextInRange先頭1文字", "A", t.getTextInRange(0, 1));

        // 末尾1文字
        check("getTextInRange末尾1文字", "E", t.getTextInRange(4, 5));

        // 挿入後にまたがる範囲取得
        PieceTable t2 = new PieceTable("AC");
        t2.insert(1, "B");
        // ピースは [A][B][C] の3つ
        check("getTextInRange挿入後全体", "ABC", t2.getTextInRange(0, 3));
        check("getTextInRange挿入後中間から", "BC", t2.getTextInRange(1, 3));
        check("getTextInRange挿入後先頭まで", "AB", t2.getTextInRange(0, 2));
    }

    // =========================================================================
    // offsetOfLine の境界値
    // =========================================================================
    static void testOffsetOfLineBoundary() {
        // 改行なし（1行のみ）
        PieceTable t1 = new PieceTable("Hello");
        check("offsetOfLine(0) 改行なし", 0, t1.offsetOfLine(0));
        // 存在しない行番号 → テキスト末尾を返す
        check("offsetOfLine(1) 改行なし→末尾", 5, t1.offsetOfLine(1));

        // 末尾が改行で終わる文書
        PieceTable t2 = new PieceTable("A\nB\n");
        check("offsetOfLine(0) 末尾改行あり", 0, t2.offsetOfLine(0));
        check("offsetOfLine(1) 末尾改行あり", 2, t2.offsetOfLine(1));
        check("offsetOfLine(2) 末尾改行あり（空行）", 4, t2.offsetOfLine(2));

        // 空バッファ
        PieceTable t3 = new PieceTable("");
        check("offsetOfLine(0) 空バッファ", 0, t3.offsetOfLine(0));
    }

    // =========================================================================
    // 多数の小さな挿入（ピース爆発）後の整合性
    // =========================================================================
    static void testManySmallInserts() {
        PieceTable t = new PieceTable("X");
        // 中間に 1000 回挿入
        for (int i = 0; i < 1000; i++) {
            t.insert(1, "A");
        }
        String text = t.getText();
        check("多数挿入後length==1001", 1001, t.length());
        check("多数挿入後先頭文字=='X'", 'X', text.charAt(0));
        check("多数挿入後末尾文字=='A'", 'A', text.charAt(1000));
        // 最初の1001文字すべてが 'X' または 'A' であること
        boolean allValid = true;
        for (char c : text.toCharArray()) {
            if (c != 'X' && c != 'A') { allValid = false; break; }
        }
        check("多数挿入後全文字がXかA", true, allValid);
    }

    // =========================================================================
    // 多数の削除後の整合性
    // =========================================================================
    static void testManyDeletes() {
        // "ABABAB..." (2000文字) から A を1文字ずつ削除
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) sb.append("AB");
        PieceTable t = new PieceTable(sb.toString());

        // 末尾から 1000 文字削除 (500回 × 2文字)
        for (int i = 0; i < 500; i++) {
            t.delete(t.length() - 2, 2);
        }
        check("多数削除後length==1000", 1000, t.length());
        String result = t.getText();
        boolean allAB = true;
        for (int i = 0; i < result.length(); i += 2) {
            if (result.charAt(i) != 'A') { allAB = false; break; }
            if (i + 1 < result.length() && result.charAt(i + 1) != 'B') { allAB = false; break; }
        }
        check("多数削除後残存テキストが全てAB", true, allAB);
    }

    // =========================================================================
    // 改行のみの文書
    // =========================================================================
    static void testNewlineOnlyDocument() {
        PieceTable t = new PieceTable("\n\n\n");
        check("改行のみ.length()==3", 3, t.length());
        check("改行のみ.getText()", "\n\n\n", t.getText());
        check("offsetOfLine(0)==0", 0, t.offsetOfLine(0));
        check("offsetOfLine(1)==1", 1, t.offsetOfLine(1));
        check("offsetOfLine(2)==2", 2, t.offsetOfLine(2));
        check("offsetOfLine(3)==3 (末尾空行)", 3, t.offsetOfLine(3));
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================
    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
