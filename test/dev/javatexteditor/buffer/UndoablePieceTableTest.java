package dev.javatexteditor.buffer;

/**
 * UndoablePieceTable のテストハーネス（mainメソッド形式・JUnit不使用）。
 */
public class UndoablePieceTableTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testUndoBasic();
        testRedoBasic();
        testUndoSequential();
        testRedoInvalidation();
        testCanUndoCanRedo();

        System.out.printf("%nPASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
        if (fail > 0) System.exit(1);
    }

    // -------------------------------------------------------------------------
    // アンドゥ基本
    // -------------------------------------------------------------------------

    static void testUndoBasic() {
        System.out.println("[アンドゥ基本]");

        UndoablePieceTable t1 = new UndoablePieceTable("hello");
        t1.insert(5, " world");
        check("insert 後に undo() でテキストが元に戻る", () -> {
            t1.undo();
            return t1.getText().equals("hello");
        });

        UndoablePieceTable t2 = new UndoablePieceTable("hello world");
        t2.delete(5, 6);
        check("delete 後に undo() でテキストが元に戻る", () -> {
            t2.undo();
            return t2.getText().equals("hello world");
        });

        UndoablePieceTable t3 = new UndoablePieceTable("abc");
        check("undoStack が空のとき undo() を呼んでも例外なく何も起きない", () -> {
            t3.undo();
            return t3.getText().equals("abc");
        });
    }

    // -------------------------------------------------------------------------
    // リドゥ基本
    // -------------------------------------------------------------------------

    static void testRedoBasic() {
        System.out.println("[リドゥ基本]");

        UndoablePieceTable t1 = new UndoablePieceTable("hello");
        t1.insert(5, " world");
        t1.undo();
        check("undo() 後に redo() でテキストが再適用される", () -> {
            t1.redo();
            return t1.getText().equals("hello world");
        });

        UndoablePieceTable t2 = new UndoablePieceTable("abc");
        check("redoStack が空のとき redo() を呼んでも例外なく何も起きない", () -> {
            t2.redo();
            return t2.getText().equals("abc");
        });
    }

    // -------------------------------------------------------------------------
    // アンドゥ連続
    // -------------------------------------------------------------------------

    static void testUndoSequential() {
        System.out.println("[アンドゥ連続]");

        UndoablePieceTable t1 = new UndoablePieceTable("a");
        t1.insert(1, "b");
        t1.insert(2, "c");
        t1.insert(3, "d");
        t1.undo();
        t1.undo();
        t1.undo();
        check("3回 insert 後、3回 undo() で初期テキストに戻る",
              t1.getText().equals("a"));

        UndoablePieceTable t2 = new UndoablePieceTable("a");
        t2.insert(1, "b");
        t2.insert(2, "c");
        t2.insert(3, "d");
        t2.undo();
        t2.undo();
        t2.undo();
        t2.redo();
        t2.redo();
        t2.redo();
        check("3回 undo() 後に 3回 redo() で最終テキストに戻る",
              t2.getText().equals("abcd"));
    }

    // -------------------------------------------------------------------------
    // リドゥ無効化
    // -------------------------------------------------------------------------

    static void testRedoInvalidation() {
        System.out.println("[リドゥ無効化]");

        UndoablePieceTable t = new UndoablePieceTable("abc");
        t.insert(3, "d");
        t.undo();
        t.insert(3, "x");
        check("undo() 後に新しい insert() をすると canRedo()==false になる",
              !t.canRedo());
    }

    // -------------------------------------------------------------------------
    // canUndo / canRedo
    // -------------------------------------------------------------------------

    static void testCanUndoCanRedo() {
        System.out.println("[canUndo / canRedo]");

        UndoablePieceTable t = new UndoablePieceTable("abc");
        check("初期状態で canUndo()==false かつ canRedo()==false",
              !t.canUndo() && !t.canRedo());

        t.insert(3, "d");
        check("insert 後に canUndo()==true", t.canUndo());

        t.undo();
        check("undo 後に canRedo()==true", t.canRedo());
    }

    // -------------------------------------------------------------------------
    // ユーティリティ
    // -------------------------------------------------------------------------

    @FunctionalInterface
    interface ThrowingSupplier {
        boolean get() throws Exception;
    }

    static void check(String label, ThrowingSupplier condition) {
        try {
            if (condition.get()) {
                System.out.println("  PASS: " + label);
                pass++;
            } else {
                System.out.println("  FAIL: " + label);
                fail++;
            }
        } catch (Exception e) {
            System.out.println("  FAIL: " + label + " (exception: " + e.getMessage() + ")");
            fail++;
        }
    }

    static void check(String label, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + label);
            pass++;
        } else {
            System.out.println("  FAIL: " + label);
            fail++;
        }
    }
}
