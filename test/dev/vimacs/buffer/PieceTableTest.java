package dev.vimacs.buffer;

public class PieceTableTest {
    public static void main(String[] args) {
        int pass = 0;

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
        t3.insert(2, "l");
        pass += check("中間挿入(分割)", "Hello", t3.getText());

        // Test 4: 複数回の挿入を経た後の、さらに中間挿入
        PieceTable t4 = new PieceTable("AC");
        t4.insert(1, "B");
        t4.insert(3, "D");
        t4.insert(2, "X");
        pass += check("複数回挿入後の中間挿入", "ABXCD", t4.getText());

        // Test 5: 単純な削除
        PieceTable t5 = new PieceTable("Hello World");
        t5.delete(5, 6);
        pass += check("末尾範囲削除", "Hello", t5.getText());

        // Test 6: 挿入によって分割されたピース群をまたぐ削除
        PieceTable t6 = new PieceTable("AC");
        t6.insert(1, "B");
        t6.delete(0, 3);
        pass += check("複数ピースをまたぐ全削除", "", t6.getText());

        // Test 7: ピースの一部だけ重なる削除（ピース分割を伴うdelete）
        PieceTable t7 = new PieceTable("0123456789");
        t7.delete(2, 4);
        pass += check("ピース内部の部分削除", "016789", t7.getText());

        // Test 8: 空文字列からの構築・挿入
        PieceTable t8 = new PieceTable("");
        t8.insert(0, "X");
        pass += check("空文書への挿入", "X", t8.getText());

        int total = 8;
        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) {
            System.exit(1);
        }
    }

    static int check(String name, String expected, String actual) {
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=\"" + expected + "\" actual=\"" + actual + "\"");
        return ok ? 1 : 0;
    }
}
