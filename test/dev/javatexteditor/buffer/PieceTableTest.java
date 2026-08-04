package dev.javatexteditor.buffer;

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

        // Test 9: getTextInRange - オリジナルバッファ内の部分取得
        PieceTable t9 = new PieceTable("Hello World");
        pass += check("getTextInRange 先頭5文字", "Hello", t9.getTextInRange(0, 5));
        pass += check("getTextInRange 後半5文字", "World", t9.getTextInRange(6, 11));

        // Test 10: getTextInRange - 挿入後の複数ピースをまたぐ取得
        PieceTable t10 = new PieceTable("AC");
        t10.insert(1, "B");
        pass += check("getTextInRange ピースまたぎ", "ABC", t10.getTextInRange(0, 3));
        pass += check("getTextInRange ピースまたぎ中間", "BC", t10.getTextInRange(1, 3));

        // Test 11: offsetOfLine - 行ごとの開始オフセット
        PieceTable t11 = new PieceTable("line0\nline1\nline2");
        pass += check("offsetOfLine(0)==0", "0", String.valueOf(t11.offsetOfLine(0)));
        pass += check("offsetOfLine(1)==6", "6", String.valueOf(t11.offsetOfLine(1)));
        pass += check("offsetOfLine(2)==12", "12", String.valueOf(t11.offsetOfLine(2)));

        // Test 12: 連続タイピングのピース結合（Phase 1）: 連続insertでピースが増えない
        PieceTable t12 = new PieceTable("");
        t12.insert(0, "a");
        t12.insert(1, "b");
        t12.insert(2, "c");
        pass += check("連続挿入の結合: テキスト", "abc", t12.getText());
        pass += check("連続挿入の結合: ピース数1", "1", String.valueOf(t12.getPieces().size()));

        // Test 13: 文書中間での連続タイピングも結合される
        PieceTable t13 = new PieceTable("AB");
        t13.insert(1, "x");
        t13.insert(2, "y");
        t13.insert(3, "z");
        pass += check("中間連続挿入: テキスト", "AxyzB", t13.getText());
        pass += check("中間連続挿入: ピース数3", "3", String.valueOf(t13.getPieces().size()));

        // Test 14: 離れた位置への挿入は結合されない（正しさ優先）
        PieceTable t14 = new PieceTable("abcdef");
        t14.insert(1, "X");
        t14.insert(4, "Y");
        pass += check("離れた挿入: テキスト", "aXbcYdef", t14.getText());

        // Test 15: 削除で追加バッファ末尾の所有が切れた後の挿入は結合しない（誤結合防止）
        PieceTable t15 = new PieceTable("");
        t15.insert(0, "abc");
        t15.delete(2, 1);       // "ab"（ピース末尾と addBuffer 末尾がズレる）
        t15.insert(2, "d");     // 誤って結合すると "abc"+"d" の断片になり壊れる
        pass += check("削除後の挿入: テキスト", "abd", t15.getText());

        // Test 16: length() キャッシュの整合性（挿入・削除・範囲外にはみ出す削除）
        PieceTable t16 = new PieceTable("hello");
        t16.insert(5, " world");
        t16.delete(0, 6);
        pass += check("length==getText().length()",
            String.valueOf(t16.getText().length()), String.valueOf(t16.length()));
        t16.delete(3, 100);     // 実在部分だけ消える既存仕様
        pass += check("範囲外削除後のlength整合",
            String.valueOf(t16.getText().length()), String.valueOf(t16.length()));

        // Test 17: 結合された連続挿入でも undo 粒度は1操作ずつ（スナップショット互換）
        UndoablePieceTable t17 = new UndoablePieceTable("");
        t17.insert(0, "a");
        t17.insert(1, "b");
        t17.insert(2, "c");
        t17.undo();
        pass += check("結合後undo1回目", "ab", t17.getText());
        t17.undo();
        pass += check("結合後undo2回目", "a", t17.getText());
        t17.redo();
        pass += check("結合後redo", "ab", t17.getText());

        // Test 18: delete()のピース統合(Phase 2) - 挿入で分割されたピースが
        // 削除で元通り連続範囲に戻ったら1本に統合される(典型的な「打っては消す」パターン)
        PieceTable t18 = new PieceTable("ABCD");
        t18.insert(2, "X");     // [ORIGINAL(0,2), ADD(0,1), ORIGINAL(2,2)] の3ピース
        t18.delete(2, 1);       // Xを削除 -> ORIGINAL側の前後が再び連続するので1本に統合される
        pass += check("挿入分割後の削除で再統合: テキスト", "ABCD", t18.getText());
        pass += check("挿入分割後の削除で再統合: ピース数1", "1", String.valueOf(t18.getPieces().size()));

        // Test 19: 「1文字挿入->1文字削除」を1000回繰り返してもピース数がほぼ一定範囲に収まる
        // (統合が無いと1000回の反復でピースが際限なく増え続ける)
        PieceTable t19 = new PieceTable("hello world");
        for (int i = 0; i < 1000; i++) {
            t19.insert(5, "Z");
            t19.delete(5, 1);
        }
        pass += check("挿入/削除1000回反復後のテキスト整合", "hello world", t19.getText());
        boolean pieceCountBounded = t19.getPieces().size() <= 5;
        System.out.println((pieceCountBounded ? "[OK] " : "[FAIL] ")
            + "挿入/削除1000回反復後もピース数が小さいまま(実測=" + t19.getPieces().size() + ")");
        pass += pieceCountBounded ? 1 : 0;

        // Test 20: 統合の境界条件 - 文書先頭・末尾での削除後の統合
        PieceTable t20 = new PieceTable("0123456789");
        t20.insert(0, "X");     // [ADD(0,1), ORIGINAL(0,10)]
        t20.delete(0, 1);       // X削除 -> ORIGINALの1ピースのみに戻る
        pass += check("先頭挿入分割後の削除: テキスト", "0123456789", t20.getText());
        pass += check("先頭挿入分割後の削除: ピース数1", "1", String.valueOf(t20.getPieces().size()));

        PieceTable t21 = new PieceTable("0123456789");
        t21.insert(10, "X");    // [ORIGINAL(0,10), ADD(0,1)]
        t21.delete(10, 1);      // X削除 -> ORIGINALの1ピースのみに戻る
        pass += check("末尾挿入分割後の削除: テキスト", "0123456789", t21.getText());
        pass += check("末尾挿入分割後の削除: ピース数1", "1", String.valueOf(t21.getPieces().size()));

        // Test 22: 複数ピースにまたがる削除でも前後が同一ソース連続なら統合される
        PieceTable t22 = new PieceTable("AC");
        t22.insert(1, "BXY");   // [ORIGINAL(0,1), ADD(0,3), ORIGINAL(1,1)]
        t22.delete(2, 2);       // "XY"の2文字を削除(ADDピースの後半+ORIGINAL側の"C"の前まで)
        pass += check("複数ピースまたぎ削除後のテキスト", "ABC", t22.getText());

        // Test 23: 異なるソース同士は誤って統合されない(正しさ優先)
        PieceTable t23 = new PieceTable("AB");
        t23.insert(1, "X");     // [ORIGINAL(0,1), ADD(0,1), ORIGINAL(1,1)]
        // 何も削除せず、ORIGINAL(0,1)とADD(0,1)が隣接していても異なるソースなので統合されないこと
        pass += check("異なるソース隣接は統合されない: ピース数3", "3", String.valueOf(t23.getPieces().size()));

        int total = 36;
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
