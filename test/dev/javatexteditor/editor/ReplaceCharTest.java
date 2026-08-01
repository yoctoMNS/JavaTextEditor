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
        testReplaceUppercaseChar();
        testReplaceUppercaseWithRealKeyEvent();
        testReplaceUppercaseWithShiftKeyPressedFirst();

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

    // r 後の大文字置換（A-Z）が弾かれないことの回帰テスト。
    // pressKey() 経由（keyCode=VK_UNDEFINED, modifiers=0）での確認。
    static void testReplaceUppercaseChar() {
        System.out.println("[r: 大文字での置換]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'r');
        pressKey(ed, 'A');
        check("大文字1文字で置換される", ed.getText().equals("Abcdef"));

        ModalEditor ed2 = new ModalEditor("abcdef");
        pressKey(ed2, '3');
        pressKey(ed2, 'r');
        pressKey(ed2, 'Q');
        check("カウント付きでも大文字で置換される", ed2.getText().equals("QQQdef"));
    }

    // GlobalKeyDispatcher が KEY_PRESSED で実際に渡す形（keyCode=VK_x, keyChar=大文字,
    // modifiers=SHIFT_DOWN_MASK）を直接再現した確認。pressKey() のような keyCode=VK_UNDEFINED
    // の簡易呼び出しだけでは、実キー入力時の keyCode 経由の分岐（matches() 等）を通さないため、
    // 実際のキーイベントに近い形でも別途確認する。
    static void testReplaceUppercaseWithRealKeyEvent() {
        System.out.println("[r: 実キーイベント相当（keyCode+SHIFT_DOWN_MASK）での大文字置換]");
        ModalEditor ed = new ModalEditor("hello world");
        ed.processKey(KeyEvent.VK_R, 'r', 0);
        ed.processKey(KeyEvent.VK_Z, 'Z', KeyEvent.SHIFT_DOWN_MASK);
        check("keyCode+SHIFT_DOWN_MASKで渡された大文字でも置換される", ed.getText().equals("Zello world"));
    }

    // GlobalKeyDispatcherが実際にOSから受け取る順序（Shift単体のkeyPressedが先に届き、
    // その後Shift適用済みのkeyPressed(VK_A, 'A')が届く）を再現した回帰テスト。
    // 修正前はShift単体のkeyPressedがpendingSequenceを空文字にリセットしてしまい、
    // 後続の'A'が「r未入力時の通常入力」として扱われ、置換が発生しなかった。
    static void testReplaceUppercaseWithShiftKeyPressedFirst() {
        System.out.println("[r: Shift単体のkeyPressedが先に届いても大文字置換が成立する]");
        ModalEditor ed = new ModalEditor("hello world");
        ed.processKey(KeyEvent.VK_R, 'r', 0);
        // Shiftキー単体の押下（keyChar=CHAR_UNDEFINED）。これが pendingSequence="r" を破棄してはならない。
        ed.processKey(KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.SHIFT_DOWN_MASK);
        ed.processKey(KeyEvent.VK_A, 'A', KeyEvent.SHIFT_DOWN_MASK);
        check("Shift単体のkeyPressedを挟んでも大文字で置換される", ed.getText().equals("Aello world"));
        check("置換後もNORMALモードのまま", ed.isNormalMode());
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
