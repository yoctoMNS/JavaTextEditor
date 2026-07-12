package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;

/**
 * NORMALモード r コマンド（1文字置換）のテストハーネス（mainメソッド形式・JUnit不使用）。
 */
public class ReplaceCharTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testReplaceSingleChar();
        testReplaceWithCount();
        testReplaceCountExceedsLineEnd();
        testReplaceEscCancel();
        testReplaceCursorPosition();
        testReplaceStaysInNormalMode();
        testReplaceUndo();

        System.out.printf("%nPASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
        if (fail > 0) System.exit(1);
    }

    // 通常のr置換（カウントなし）
    static void testReplaceSingleChar() {
        System.out.println("[r: カウントなしの通常置換]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'r');
        pressKey(ed, 'Z');
        check("カーソル位置の1文字が置換される", ed.getText().equals("Zbcdef"));
    }

    // カウント付きr置換（行内に十分な文字数がある場合）
    static void testReplaceWithCount() {
        System.out.println("[3r: カウント付き置換]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, '3');
        pressKey(ed, 'r');
        pressKey(ed, 'a');
        check("カウント分だけ同じ文字に置換される", ed.getText().equals("aaadef"));
    }

    // カウントが行末を超える場合（変更されないこと）
    static void testReplaceCountExceedsLineEnd() {
        System.out.println("[10r: カウントが行末を超える場合は変更しない]");
        ModalEditor ed = new ModalEditor("abc");
        pressKey(ed, '9');
        pressKey(ed, 'r');
        pressKey(ed, 'z');
        check("行末を超えるカウントでは変更されない", ed.getText().equals("abc"));
        check("カーソル位置も変わらない", ed.getCursorCol() == 0);
    }

    // r入力後のEscキャンセル
    static void testReplaceEscCancel() {
        System.out.println("[r 後の Esc キャンセル]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'r');
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        check("Escでキャンセルすると何も置換されない", ed.getText().equals("abcdef"));
        check("キャンセル後もNORMALモードのまま", ed.isNormalMode());
        // キャンセル後、通常の r 入力が正しく機能することも確認する
        pressKey(ed, 'r');
        pressKey(ed, 'Z');
        check("キャンセル後の次の r は正常に動作する", ed.getText().equals("Zbcdef"));
    }

    // 置換後のカーソル位置確認
    static void testReplaceCursorPosition() {
        System.out.println("[r: 置換後のカーソル位置]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'l');
        pressKey(ed, 'l'); // col=2
        pressKey(ed, '3');
        pressKey(ed, 'r');
        pressKey(ed, 'x');
        check("カウント付き置換後、カーソルは置換した最後の文字位置に残る",
              ed.getCursorCol() == 4);
        check("テキストが正しく置換される", ed.getText().equals("abxxxf"));
    }

    // 置換後もINSERTモードへ遷移しないこと
    static void testReplaceStaysInNormalMode() {
        System.out.println("[r: 置換後もNORMALモードに留まる]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'r');
        pressKey(ed, 'Z');
        check("置換後もNORMALモードのまま", ed.isNormalMode());
    }

    // undo確認（既存のtoggleCaseUnderCursor等と同じdelete+insertパターンのため2回のuが必要）
    static void testReplaceUndo() {
        System.out.println("[r: undoで元に戻る]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'r');
        pressKey(ed, 'Z');
        pressKey(ed, 'u');
        pressKey(ed, 'u');
        check("undoで置換前のテキストに戻る", ed.getText().equals("abcdef"));
    }

    // ユーティリティ
    // -------------------------------------------------------------------------

    static void pressKey(ModalEditor ed, char keyChar) {
        ed.processKey(0, keyChar, 0);
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
