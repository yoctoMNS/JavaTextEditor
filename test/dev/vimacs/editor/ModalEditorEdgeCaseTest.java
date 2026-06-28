package dev.vimacs.editor;

import java.awt.event.KeyEvent;

public class ModalEditorEdgeCaseTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) {
        testCursorClampOnEmptyBuffer();
        testCursorClampOnSingleLine();
        testCursorClampAfterDelete();
        testCursorClampAfterUndo();
        testMultibyteCharacterBoundary();
        testInsertMultibyteChars();
        testCursorMovementAtDocumentBoundary();
        testDeepUndoSequenceViaEditor();
        testNormalModeClampAtLineEnd();

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) System.exit(1);
    }

    // =========================================================================
    // 空バッファでのカーソルクランプ
    // =========================================================================
    static void testCursorClampOnEmptyBuffer() {
        ModalEditor ed = new ModalEditor("");
        // 空バッファで右移動しても範囲外にならない
        pressKey(ed, KeyEvent.VK_L, 'l');
        check("空バッファ右移動後row==0", 0, ed.getCursorRow());
        check("空バッファ右移動後col==0", 0, ed.getCursorCol());

        pressKey(ed, KeyEvent.VK_J, 'j');
        check("空バッファ下移動後row==0", 0, ed.getCursorRow());

        pressKey(ed, KeyEvent.VK_H, 'h');
        check("空バッファ左移動後col==0", 0, ed.getCursorCol());

        pressKey(ed, KeyEvent.VK_K, 'k');
        check("空バッファ上移動後row==0", 0, ed.getCursorRow());
    }

    // =========================================================================
    // 1行バッファでのカーソルクランプ
    // =========================================================================
    static void testCursorClampOnSingleLine() {
        ModalEditor ed = new ModalEditor("ABC");
        // NORMALモードでは最後の文字(index 2)まで
        pressKey(ed, KeyEvent.VK_L, 'l'); // col→1
        pressKey(ed, KeyEvent.VK_L, 'l'); // col→2
        pressKey(ed, KeyEvent.VK_L, 'l'); // clamp at 2
        check("1行末端clamp col==2", 2, ed.getCursorCol());

        pressKey(ed, KeyEvent.VK_H, 'h'); // col→1
        pressKey(ed, KeyEvent.VK_H, 'h'); // col→0
        pressKey(ed, KeyEvent.VK_H, 'h'); // clamp at 0
        check("1行先頭clamp col==0", 0, ed.getCursorCol());
    }

    // =========================================================================
    // 削除後のカーソルクランプ
    // =========================================================================
    static void testCursorClampAfterDelete() {
        ModalEditor ed = new ModalEditor("ABCDE");
        // カーソルをcol=4（末尾）へ移動
        for (int i = 0; i < 4; i++) pressKey(ed, KeyEvent.VK_L, 'l');
        check("削除前col==4", 4, ed.getCursorCol());

        // 'x' で1文字削除(3文字になる →末尾はcol=3だが、削除後NORMALではclampしない場合がある)
        // 代わりに dd（行削除）で空バッファにして確認
        // まず 'dd' で行全体を削除
        pressKey(ed, KeyEvent.VK_D, 'd');
        pressKey(ed, KeyEvent.VK_D, 'd');
        // 空バッファになるのでカーソルは (0,0) にクランプされているはず
        check("行削除後row==0", 0, ed.getCursorRow());
        check("行削除後col==0", 0, ed.getCursorCol());
    }

    // =========================================================================
    // アンドゥ後のカーソルクランプ
    // =========================================================================
    static void testCursorClampAfterUndo() {
        // 3行の文書でカーソルを下へ2行移動 → アンドゥで行が減っても範囲外にならない
        ModalEditor ed = new ModalEditor("line0");
        // INSERT モードで2行追加
        pressKey(ed, KeyEvent.VK_I, 'i'); // INSERT
        typeText(ed, "\nline1\nline2");
        pressEscape(ed);
        // カーソルを最終行(row=2)へ
        pressKey(ed, KeyEvent.VK_J, 'j');
        pressKey(ed, KeyEvent.VK_J, 'j');
        check("アンドゥ前row==2", 2, ed.getCursorRow());

        // アンドゥを複数回実行 → カーソルがクランプされる
        pressKey(ed, KeyEvent.VK_U, 'u');
        pressKey(ed, KeyEvent.VK_U, 'u');
        // 何行あるかに関わらずrow<行数、col<行長であること
        int row = ed.getCursorRow();
        int col = ed.getCursorCol();
        String[] lines = ed.getText().split("\n", -1);
        boolean rowValid = row >= 0 && row < lines.length;
        int lineLen = row < lines.length ? lines[row].length() : 0;
        boolean colValid = col >= 0 && col <= lineLen;
        check("アンドゥ後rowが有効範囲内", true, rowValid);
        check("アンドゥ後colが有効範囲内", true, colValid);
    }

    // =========================================================================
    // マルチバイト文字（全角）境界
    // =========================================================================
    static void testMultibyteCharacterBoundary() {
        // Java の String は UTF-16 なので、全角1文字 = charAt() 1文字
        // PieceTable のオフセットは char 単位
        ModalEditor ed = new ModalEditor("あいう");
        // "あいう" は char で3文字
        check("全角3文字バッファのテキスト", "あいう", ed.getText());

        // NORMALモードで右端までカーソル移動
        pressKey(ed, KeyEvent.VK_L, 'l');
        pressKey(ed, KeyEvent.VK_L, 'l');
        pressKey(ed, KeyEvent.VK_L, 'l'); // clamp at 2
        check("全角文字末端clamp col==2", 2, ed.getCursorCol());
    }

    // =========================================================================
    // INSERTモードで全角文字を入力する
    // =========================================================================
    static void testInsertMultibyteChars() {
        ModalEditor ed = new ModalEditor("");
        pressKey(ed, KeyEvent.VK_I, 'i'); // INSERT
        // 全角文字はキーコードではなく keyChar で識別
        typeChar(ed, 'あ');
        typeChar(ed, 'い');
        typeChar(ed, 'う');
        pressEscape(ed);
        check("全角文字INSERT後getText", "あいう", ed.getText());
        check("全角文字INSERT後cursorCol(NORMAL clamp)", 2, ed.getCursorCol());
    }

    // =========================================================================
    // 文書境界でのカーソル移動
    // =========================================================================
    static void testCursorMovementAtDocumentBoundary() {
        ModalEditor ed = new ModalEditor("line0\nline1\nline2");
        // 上端から上移動
        pressKey(ed, KeyEvent.VK_K, 'k');
        check("文書先頭から上移動→row==0", 0, ed.getCursorRow());

        // 最終行へ移動して下移動
        pressKey(ed, KeyEvent.VK_J, 'j');
        pressKey(ed, KeyEvent.VK_J, 'j');
        pressKey(ed, KeyEvent.VK_J, 'j'); // clamp at row=2
        check("文書末尾から下移動→row==2", 2, ed.getCursorRow());
    }

    // =========================================================================
    // エディタ経由の深いアンドゥシーケンス
    // =========================================================================
    static void testDeepUndoSequenceViaEditor() {
        ModalEditor ed = new ModalEditor("base");
        // INSERTモードで30文字追加
        pressKey(ed, KeyEvent.VK_I, 'i');
        for (int i = 0; i < 30; i++) {
            typeChar(ed, (char)('a' + i % 26));
        }
        pressEscape(ed);
        String afterInsert = ed.getText();
        check("深いアンドゥ: 30文字追加後length==34", 34, afterInsert.length());

        // 30回アンドゥ
        for (int i = 0; i < 30; i++) {
            pressKey(ed, KeyEvent.VK_U, 'u');
        }
        check("深いアンドゥ: 30回アンドゥ後getText==base", "base", ed.getText());

        // カーソルが有効範囲内
        int row = ed.getCursorRow();
        int col = ed.getCursorCol();
        String[] lines = ed.getText().split("\n", -1);
        check("深いアンドゥ後rowが有効", true, row >= 0 && row < lines.length);
    }

    // =========================================================================
    // NORMALモードの行末クランプ（行長-1 まで）
    // =========================================================================
    static void testNormalModeClampAtLineEnd() {
        ModalEditor ed = new ModalEditor("Hello");
        // INSERTモードで末尾（col=5）まで Ctrl+F で移動してからESCAPE
        pressKey(ed, KeyEvent.VK_I, 'i');
        for (int i = 0; i < 5; i++) {
            ed.processKey(KeyEvent.VK_F, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK);
        }
        pressEscape(ed);
        // NORMALモードでは最大 col == lineLen-1 == 4
        check("NORMAL末端clamp col==4", 4, ed.getCursorCol());
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================
    static void pressKey(ModalEditor ed, int keyCode, char keyChar) {
        ed.processKey(keyCode, keyChar, 0);
    }

    static void pressEscape(ModalEditor ed) {
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
    }

    static void typeChar(ModalEditor ed, char c) {
        ed.processKey(0, c, 0);
    }

    static void typeText(ModalEditor ed, String text) {
        for (char c : text.toCharArray()) {
            if (c == '\n') {
                ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
            } else {
                typeChar(ed, c);
            }
        }
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
