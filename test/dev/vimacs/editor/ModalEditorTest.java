package dev.vimacs.editor;

import java.awt.event.KeyEvent;

/**
 * ModalEditor のテストハーネス（mainメソッド形式・JUnit不使用）。
 *
 * PieceTableに対してキーシーケンスを送り、getText()/getCursorRow()/getCursorCol() で検証する。
 */
public class ModalEditorTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testInitialState();
        testNormalCursorMovement();
        testNormalCursorBoundary();
        testEnterInsertModeWithI();
        testTypeTextInInsert();
        testBackspaceInInsert();
        testBackspaceAcrossLine();
        testEscapeToNormal();
        testEscapeClampsCursor();
        testAppendCommand();
        testOpenLineBelow();
        testCtrlMovementInInsert();
        testCtrlMovementBoundary();

        System.out.printf("%nPASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
        if (fail > 0) System.exit(1);
    }

    // -------------------------------------------------------------------------
    // 初期状態
    // -------------------------------------------------------------------------

    static void testInitialState() {
        System.out.println("[初期状態]");
        ModalEditor ed = new ModalEditor("hello");
        check("初期行=0", ed.getCursorRow() == 0);
        check("初期列=0", ed.getCursorCol() == 0);
        check("初期はNORMALモード", !ed.isInsertMode());
        check("テキスト保持", ed.getText().equals("hello"));
    }

    // -------------------------------------------------------------------------
    // NORMALモード: カーソル移動
    // -------------------------------------------------------------------------

    static void testNormalCursorMovement() {
        System.out.println("[NORMALモード: h/j/k/l移動]");
        ModalEditor ed = new ModalEditor("abc\ndef\nghi");

        pressKey(ed, 'l'); // col -> 1
        check("l: col=1", ed.getCursorCol() == 1);

        pressKey(ed, 'l'); // col -> 2
        check("l: col=2", ed.getCursorCol() == 2);

        pressKey(ed, 'h'); // col -> 1
        check("h: col=1", ed.getCursorCol() == 1);

        pressKey(ed, 'j'); // row -> 1
        check("j: row=1", ed.getCursorRow() == 1);

        pressKey(ed, 'k'); // row -> 0
        check("k: row=0", ed.getCursorRow() == 0);
    }

    static void testNormalCursorBoundary() {
        System.out.println("[NORMALモード: 境界クランプ]");
        ModalEditor ed = new ModalEditor("abc\ndef");

        // 左端を超えない
        pressKey(ed, 'h');
        check("h at col=0: col=0", ed.getCursorCol() == 0);

        // 右端を超えない ("abc" の最大col=2)
        pressKey(ed, 'l'); pressKey(ed, 'l'); pressKey(ed, 'l'); pressKey(ed, 'l');
        check("l*4 on 'abc': col=2", ed.getCursorCol() == 2);

        // 上端を超えない
        pressKey(ed, 'k');
        check("k at row=0: row=0", ed.getCursorRow() == 0);

        // 下端を超えない
        pressKey(ed, 'j'); pressKey(ed, 'j'); pressKey(ed, 'j');
        check("j*3 on 2-line: row=1", ed.getCursorRow() == 1);
    }

    // -------------------------------------------------------------------------
    // INSERTモード移行
    // -------------------------------------------------------------------------

    static void testEnterInsertModeWithI() {
        System.out.println("[INSERTモード移行: i]");
        ModalEditor ed = new ModalEditor("hello");
        pressKey(ed, 'i');
        check("i: INSERTモード", ed.isInsertMode());
        check("i: カーソル位置変わらず col=0", ed.getCursorCol() == 0);
    }

    // -------------------------------------------------------------------------
    // INSERTモード: 文字入力
    // -------------------------------------------------------------------------

    static void testTypeTextInInsert() {
        System.out.println("[INSERTモード: 文字入力]");
        ModalEditor ed = new ModalEditor("");
        pressKey(ed, 'i');
        typeString(ed, "hello");
        check("'hello'と入力: テキスト一致", ed.getText().equals("hello"));
        check("col=5", ed.getCursorCol() == 5);
    }

    // -------------------------------------------------------------------------
    // INSERTモード: Backspace
    // -------------------------------------------------------------------------

    static void testBackspaceInInsert() {
        System.out.println("[INSERTモード: Backspace（行内）]");
        ModalEditor ed = new ModalEditor("ab");
        pressKey(ed, 'i');
        // Ctrl+F で col=2 へ移動
        pressCtrl(ed, KeyEvent.VK_F, 'f');
        pressCtrl(ed, KeyEvent.VK_F, 'f');
        check("ctrl+f*2: col=2", ed.getCursorCol() == 2);

        ed.processKey(KeyEvent.VK_BACK_SPACE, '\b', 0);
        check("backspace: text='a'", ed.getText().equals("a"));
        check("backspace: col=1", ed.getCursorCol() == 1);
    }

    static void testBackspaceAcrossLine() {
        System.out.println("[INSERTモード: Backspace（行頭・行結合）]");
        ModalEditor ed = new ModalEditor("foo\nbar");
        pressKey(ed, 'j'); // NORMAL j -> row=1
        pressKey(ed, 'i'); // INSERT
        check("row=1 col=0", ed.getCursorRow() == 1 && ed.getCursorCol() == 0);

        ed.processKey(KeyEvent.VK_BACK_SPACE, '\b', 0);
        check("行頭backspace: テキスト結合", ed.getText().equals("foobar"));
        check("行頭backspace: row=0", ed.getCursorRow() == 0);
        check("行頭backspace: col=3 (fooの末尾)", ed.getCursorCol() == 3);
    }

    // -------------------------------------------------------------------------
    // ESCでNORMALモード復帰
    // -------------------------------------------------------------------------

    static void testEscapeToNormal() {
        System.out.println("[ESCでNORMALモード復帰]");
        ModalEditor ed = new ModalEditor("abc");
        pressKey(ed, 'i');
        check("INSERT中", ed.isInsertMode());
        ed.processKey(KeyEvent.VK_ESCAPE, (char) 27, 0);
        check("ESC: NORMALモード", !ed.isInsertMode());
    }

    static void testEscapeClampsCursor() {
        System.out.println("[ESC: カーソルクランプ（行末より1つ手前）]");
        ModalEditor ed = new ModalEditor("abc");
        pressKey(ed, 'i');
        typeString(ed, "xyz"); // cursor at col=3, text="xyzabc"
        // INSERT中は col=3 が有効
        check("INSERT col=3", ed.getCursorCol() == 3);
        ed.processKey(KeyEvent.VK_ESCAPE, (char) 27, 0);
        // "xyzabc" 長さ6, NORMAL最大col=5
        check("ESC後: col=3 (変化なし)", ed.getCursorCol() == 3);

        // 行末にカーソルがある場合のクランプ確認
        ModalEditor ed2 = new ModalEditor("hi");
        pressKey(ed2, 'i');
        pressCtrl(ed2, KeyEvent.VK_F, 'f');
        pressCtrl(ed2, KeyEvent.VK_F, 'f');
        check("INSERT col=2 (行末)", ed2.getCursorCol() == 2);
        ed2.processKey(KeyEvent.VK_ESCAPE, (char) 27, 0);
        check("ESC後: col=1 (len-1にクランプ)", ed2.getCursorCol() == 1);
    }

    // -------------------------------------------------------------------------
    // aコマンド（後挿入）
    // -------------------------------------------------------------------------

    static void testAppendCommand() {
        System.out.println("[aコマンド: カーソル後に挿入]");
        ModalEditor ed = new ModalEditor("xy");
        // col=0 で 'a' -> col=1, INSERT
        pressKey(ed, 'a');
        check("a: INSERTモード", ed.isInsertMode());
        check("a: col=1", ed.getCursorCol() == 1);

        typeString(ed, "Z");
        check("a+Z: text='xZy'", ed.getText().equals("xZy"));
        check("a+Z: col=2", ed.getCursorCol() == 2);
    }

    // -------------------------------------------------------------------------
    // oコマンド（下に新行を開く）
    // -------------------------------------------------------------------------

    static void testOpenLineBelow() {
        System.out.println("[oコマンド: 下に新行を開く]");
        ModalEditor ed = new ModalEditor("line1\nline2");
        pressKey(ed, 'o');
        check("o: INSERTモード", ed.isInsertMode());
        check("o: row=1", ed.getCursorRow() == 1);
        check("o: col=0", ed.getCursorCol() == 0);

        typeString(ed, "new");
        check("o+'new': text='line1\\nnew\\nline2'", ed.getText().equals("line1\nnew\nline2"));
    }

    // -------------------------------------------------------------------------
    // INSERTモード: Ctrl+F/B/N/P 移動
    // -------------------------------------------------------------------------

    static void testCtrlMovementInInsert() {
        System.out.println("[INSERTモード: Ctrl+F/B/N/P]");
        ModalEditor ed = new ModalEditor("abc\ndef");
        pressKey(ed, 'i');

        pressCtrl(ed, KeyEvent.VK_F, 'f');
        check("ctrl+f: col=1", ed.getCursorCol() == 1);

        pressCtrl(ed, KeyEvent.VK_B, 'b');
        check("ctrl+b: col=0", ed.getCursorCol() == 0);

        pressCtrl(ed, KeyEvent.VK_N, 'n');
        check("ctrl+n: row=1", ed.getCursorRow() == 1);

        pressCtrl(ed, KeyEvent.VK_P, 'p');
        check("ctrl+p: row=0", ed.getCursorRow() == 0);
    }

    static void testCtrlMovementBoundary() {
        System.out.println("[INSERTモード: Ctrl移動境界]");
        ModalEditor ed = new ModalEditor("ab\ncd");
        pressKey(ed, 'i');

        // 左端を超えない
        pressCtrl(ed, KeyEvent.VK_B, 'b');
        check("ctrl+b at col=0: col=0", ed.getCursorCol() == 0);

        // 右端（INSERT: lineLen まで）
        pressCtrl(ed, KeyEvent.VK_F, 'f');
        pressCtrl(ed, KeyEvent.VK_F, 'f');
        pressCtrl(ed, KeyEvent.VK_F, 'f');
        check("ctrl+f*3 on 'ab': col=2 (lineLen)", ed.getCursorCol() == 2);

        // 上端を超えない
        pressCtrl(ed, KeyEvent.VK_P, 'p');
        check("ctrl+p at row=0: row=0", ed.getCursorRow() == 0);

        // 下端を超えない
        pressCtrl(ed, KeyEvent.VK_N, 'n');
        pressCtrl(ed, KeyEvent.VK_N, 'n');
        check("ctrl+n*2 on 2-line: row=1", ed.getCursorRow() == 1);
    }

    // -------------------------------------------------------------------------
    // ユーティリティ
    // -------------------------------------------------------------------------

    /** NORMALモードのキー（keyCharのみ使用） */
    static void pressKey(ModalEditor ed, char keyChar) {
        ed.processKey(0, keyChar, 0);
    }

    /** Ctrl修飾付きキー */
    static void pressCtrl(ModalEditor ed, int keyCode, char keyChar) {
        ed.processKey(keyCode, keyChar, KeyEvent.CTRL_DOWN_MASK);
    }

    /** INSERT中に文字列を1文字ずつ入力する */
    static void typeString(ModalEditor ed, String s) {
        for (char c : s.toCharArray()) {
            ed.processKey(0, c, 0);
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
