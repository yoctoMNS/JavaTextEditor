package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;

/**
 * NORMALモード D コマンド（カーソル位置から行末まで削除）のテストハーネス（mainメソッド形式・JUnit不使用）。
 */
public class DeleteToEndOfLineTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testDeleteFromMiddleOfLine();
        testDeleteFromLineStart();
        testDeleteAtLineEndIsNoOp();
        testDeleteYanksIntoRegisterAsChar();
        testDeleteDoesNotAffectOtherLines();
        testDeleteStaysInNormalMode();
        testDeleteUndo();

        System.out.printf("%nPASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
        if (fail > 0) System.exit(1);
    }

    // カーソルが行の途中にある場合、そこから行末までが削除される
    static void testDeleteFromMiddleOfLine() {
        System.out.println("[D: 行の途中から行末まで削除]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'l');
        pressKey(ed, 'l'); // col=2
        pressKey(ed, 'D');
        check("カーソル位置から行末までが削除される", ed.getText().equals("ab"));
        check("カーソルは削除後の行末（最後の文字）に残る", ed.getCursorCol() == 1);
    }

    // 行頭からDを押すと行全体が削除され空行になる
    static void testDeleteFromLineStart() {
        System.out.println("[D: 行頭からは行全体が削除される]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'D');
        check("行頭からは行全体が削除される", ed.getText().equals(""));
        check("空行になった場合カーソルは列0", ed.getCursorCol() == 0);
    }

    // 行末（既に文字がない位置）でDを押しても何も起きない
    static void testDeleteAtLineEndIsNoOp() {
        System.out.println("[D: 行末では何も削除されない]");
        ModalEditor ed = new ModalEditor("abc");
        pressKey(ed, '$'); // col=2 (最後の文字)
        pressKey(ed, 'D');
        check("最後の文字を含めて削除される", ed.getText().equals("ab"));
    }

    // 削除内容がヤンクレジスタにchar単位で保存されること
    static void testDeleteYanksIntoRegisterAsChar() {
        System.out.println("[D: 削除内容がヤンクレジスタに保存される]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'l'); // col=1
        pressKey(ed, 'D');
        check("削除された文字列がヤンクレジスタに入る", ed.getYankRegister().equals("bcdef"));
        check("ヤンク種別はchar", ed.getYankType().equals("char"));
    }

    // 複数行のうち現在行以外には影響しないこと
    static void testDeleteDoesNotAffectOtherLines() {
        System.out.println("[D: 他の行には影響しない]");
        ModalEditor ed = new ModalEditor("abc\ndefgh\nij");
        pressKey(ed, 'j'); // row=1
        pressKey(ed, 'l'); // col=1
        pressKey(ed, 'D');
        check("現在行のみカーソル位置から行末までが削除される", ed.getText().equals("abc\nd\nij"));
    }

    // Dの後もNORMALモードに留まること
    static void testDeleteStaysInNormalMode() {
        System.out.println("[D: 実行後もNORMALモードに留まる]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'D');
        check("実行後もNORMALモードのまま", ed.isNormalMode());
    }

    // undoで元に戻ること
    static void testDeleteUndo() {
        System.out.println("[D: undoで元に戻る]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'l');
        pressKey(ed, 'D');
        pressKey(ed, 'u');
        check("undoで削除前のテキストに戻る", ed.getText().equals("abcdef"));
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
