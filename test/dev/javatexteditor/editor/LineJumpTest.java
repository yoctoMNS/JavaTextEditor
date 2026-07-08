package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;

/**
 * 行ジャンプコマンド(:X)のテストハーネス(mainメソッド形式・JUnit不使用)。
 * 設計判断は .claude/skills/line-number-jump/SKILL.md 参照。
 */
public class LineJumpTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testJumpToFirstLine();
        testJumpToMiddleLine();
        testJumpToLastLine();
        testZeroIsInvalid();
        testNegativeIsInvalid();
        testBeyondLastLineIsInvalid();
        testNonNumericIsUnknownCommand();
        testCursorColResetToZero();

        System.out.println();
        System.out.println("=== LineJump: " + pass + " passed, " + fail + " failed ===");
        if (fail > 0) System.exit(1);
    }

    static void testJumpToFirstLine() {
        System.out.println("[:1 は1行目(index0)へジャンプ]");
        ModalEditor ed = new ModalEditor("aaa\nbbb\nccc");
        pressKey(ed, 'j');
        pressKey(ed, 'j');
        sendCommand(ed, "1");
        check("カーソル行が0", ed.getCursorRow() == 0);
    }

    static void testJumpToMiddleLine() {
        System.out.println("[:2 は2行目(index1)へジャンプ]");
        ModalEditor ed = new ModalEditor("aaa\nbbb\nccc");
        sendCommand(ed, "2");
        check("カーソル行が1", ed.getCursorRow() == 1);
    }

    static void testJumpToLastLine() {
        System.out.println("[:総行数 は最終行へジャンプ]");
        ModalEditor ed = new ModalEditor("aaa\nbbb\nccc");
        sendCommand(ed, "3");
        check("カーソル行が2", ed.getCursorRow() == 2);
    }

    static void testZeroIsInvalid() {
        System.out.println("[:0 は無効(1未満)]");
        ModalEditor ed = new ModalEditor("aaa\nbbb\nccc");
        pressKey(ed, 'j');
        sendCommand(ed, "0");
        check("エラーメッセージ", ed.getStatusMessage().contains("invalid line number"));
        check("カーソル行は変わらない(1のまま)", ed.getCursorRow() == 1);
    }

    static void testNegativeIsInvalid() {
        System.out.println("[負の値は数字のみパターンにマッチせず unknown command]");
        ModalEditor ed = new ModalEditor("aaa\nbbb\nccc");
        sendCommand(ed, "-1");
        check("unknown commandエラー", ed.getStatusMessage().contains("unknown command"));
        check("カーソル行は変わらない(0のまま)", ed.getCursorRow() == 0);
    }

    static void testBeyondLastLineIsInvalid() {
        System.out.println("[総行数を超える行番号は無効]");
        ModalEditor ed = new ModalEditor("aaa\nbbb\nccc");
        sendCommand(ed, "4");
        check("エラーメッセージ", ed.getStatusMessage().contains("invalid line number"));
        check("カーソル行は変わらない(0のまま)", ed.getCursorRow() == 0);
    }

    static void testNonNumericIsUnknownCommand() {
        System.out.println("[数字以外が混ざる文字列はジャンプせずunknown command]");
        ModalEditor ed = new ModalEditor("aaa\nbbb\nccc");
        sendCommand(ed, "2x");
        check("unknown commandエラー", ed.getStatusMessage().contains("unknown command"));
        check("カーソル行は変わらない(0のまま)", ed.getCursorRow() == 0);
    }

    static void testCursorColResetToZero() {
        System.out.println("[ジャンプ後カーソル列は0にリセットされる]");
        ModalEditor ed = new ModalEditor("aaa\nbbb\nccc");
        pressKey(ed, 'l');
        pressKey(ed, 'l');
        sendCommand(ed, "3");
        check("カーソル列が0", ed.getCursorCol() == 0);
    }

    // -------------------------------------------------------------------------

    static void sendCommand(ModalEditor ed, String cmd) {
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        pressKey(ed, ':');
        typeString(ed, cmd);
        ed.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
    }

    static void pressKey(ModalEditor ed, char keyChar) {
        ed.processKey(0, keyChar, 0);
    }

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
