package dev.vimacs.buffer;

public class UndoRedoDeepTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) {
        testDeepUndoChain();
        testUndoRedoInterleaved();
        testUndoOnEmptyStack();
        testRedoInvalidatedByNewEdit();
        testUndoAllThenRedo();
        testBoundaryUndoAfterDeleteAll();

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) System.exit(1);
    }

    // 50回編集 → 50回アンドゥ → 元に戻っているか
    static void testDeepUndoChain() {
        UndoablePieceTable t = new UndoablePieceTable("");
        for (int i = 0; i < 50; i++) {
            t.insert(t.length(), String.valueOf((char)('A' + (i % 26))));
        }
        check("deep_undo: 50挿入後length==50", 50, t.length());
        check("deep_undo: canUndo()==true", true, t.canUndo());

        for (int i = 0; i < 50; i++) {
            t.undo();
        }
        check("deep_undo: 50回アンドゥ後getText()==\"\"", "", t.getText());
        check("deep_undo: 50回アンドゥ後canUndo()==false", false, t.canUndo());
        check("deep_undo: 50回アンドゥ後canRedo()==true", true, t.canRedo());
    }

    // アンドゥ・リドゥを交互に繰り返す整合性確認
    static void testUndoRedoInterleaved() {
        UndoablePieceTable t = new UndoablePieceTable("base");
        t.insert(4, "X");  // "baseX"
        t.insert(5, "Y");  // "baseXY"

        t.undo(); // "baseX"
        check("interleaved: undo→baseX", "baseX", t.getText());
        t.redo(); // "baseXY"
        check("interleaved: redo→baseXY", "baseXY", t.getText());
        t.undo(); // "baseX"
        t.undo(); // "base"
        check("interleaved: 2xundo→base", "base", t.getText());
        t.redo(); // "baseX"
        check("interleaved: redo→baseX", "baseX", t.getText());
        t.redo(); // "baseXY"
        check("interleaved: redo→baseXY", "baseXY", t.getText());
        t.redo(); // スタック空 → 変化なし
        check("interleaved: 空リドゥスタック→変化なし", "baseXY", t.getText());
    }

    // アンドゥスタックが空のときのアンドゥは no-op
    static void testUndoOnEmptyStack() {
        UndoablePieceTable t = new UndoablePieceTable("Hello");
        check("empty_undo: canUndo()==false", false, t.canUndo());
        t.undo(); // no-op
        check("empty_undo: アンドゥ後も変化なし", "Hello", t.getText());
    }

    // 新規編集でリドゥスタックがクリアされる
    static void testRedoInvalidatedByNewEdit() {
        UndoablePieceTable t = new UndoablePieceTable("");
        t.insert(0, "A");
        t.insert(1, "B");
        t.undo(); // "A"
        check("redo_invalidated: アンドゥ後canRedo()==true", true, t.canRedo());
        t.insert(1, "C"); // "AC" — リドゥスタックがクリアされるはず
        check("redo_invalidated: 新規編集後canRedo()==false", false, t.canRedo());
        check("redo_invalidated: getText()==\"AC\"", "AC", t.getText());
    }

    // 全アンドゥ → 全リドゥで元の文書に戻る（複雑な編集シーケンス）
    static void testUndoAllThenRedo() {
        UndoablePieceTable t = new UndoablePieceTable("start");
        String[] ops = {"X", "YZ", "W", "123"};
        int[] positions = {0, 5, 2, 3};

        for (int i = 0; i < ops.length; i++) {
            t.insert(positions[i], ops[i]);
        }
        String afterAllEdits = t.getText();

        // 全アンドゥ
        while (t.canUndo()) t.undo();
        check("all_undo: 全アンドゥ後getText()==start", "start", t.getText());

        // 全リドゥ
        while (t.canRedo()) t.redo();
        check("all_undo: 全リドゥ後元の文書に復元", afterAllEdits, t.getText());
    }

    // 全体削除→アンドゥで復元
    static void testBoundaryUndoAfterDeleteAll() {
        UndoablePieceTable t = new UndoablePieceTable("Hello World");
        t.delete(0, t.length()); // 全消し
        check("boundary_undo: 全削除後getText()==\"\"", "", t.getText());
        t.undo();
        check("boundary_undo: アンドゥ後復元", "Hello World", t.getText());
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
