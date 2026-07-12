package dev.javatexteditor.editor;

import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;

/**
 * VISUAL系モードのCtrl+Shift+C（システムクリップボードへコピー）と
 * NORMAL/INSERTモードのCtrl+Shift+V（システムクリップボードから貼り付け）のテストハーネス
 * （mainメソッド形式・JUnit不使用）。
 *
 * このコンテナはヘッドレス環境（DISPLAY未設定）のため、実際のOSクリップボードへの
 * アクセスは HeadlessException で失敗する。そのため本テストは「クラッシュせずモード遷移が
 * 正しく行われ、statusMessage にエラーが設定されること」を確認する（⑫openjdk-source-tracing
 * や⑳telescope-picker と同種の既知のテストギャップ。ヘッドフル環境での実クリップボード
 * 往復は手動確認が必要）。
 */
public class ClipboardTest {

    private static int pass = 0;
    private static int fail = 0;
    private static final boolean HEADLESS = GraphicsEnvironment.isHeadless();

    public static void main(String[] args) {
        testVisualCtrlShiftCReturnsToNormal();
        testVisualLineCtrlShiftCReturnsToNormal();
        testVisualBlockCtrlShiftCReturnsToNormal();
        testNormalCtrlShiftVDoesNotCrash();
        testInsertCtrlShiftVDoesNotCrash();
        testCtrlShiftVDoesNotConflictWithCtrlV();

        System.out.printf("%nPASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
        if (fail > 0) System.exit(1);
    }

    static void testVisualCtrlShiftCReturnsToNormal() {
        System.out.println("[VISUAL: Ctrl+Shift+C でNORMALへ戻りクラッシュしない]");
        ModalEditor ed = new ModalEditor("hello world");
        pressKey(ed, 'v');
        pressKey(ed, 'l');
        pressKey(ed, 'l');
        pressCtrlShift(ed, KeyEvent.VK_C);
        check("Ctrl+Shift+C 後はNORMALモードに戻る", ed.isNormalMode());
        check("statusMessage が設定される", ed.getStatusMessage() != null && !ed.getStatusMessage().isEmpty());
        if (HEADLESS) {
            check("ヘッドレス環境ではエラーメッセージになる", ed.getStatusMessage().startsWith("E: clipboard"));
        }
    }

    static void testVisualLineCtrlShiftCReturnsToNormal() {
        System.out.println("[VISUAL LINE: Ctrl+Shift+C でNORMALへ戻りクラッシュしない]");
        ModalEditor ed = new ModalEditor("line1\nline2\nline3");
        pressKey(ed, 'V');
        pressKey(ed, 'j');
        pressCtrlShift(ed, KeyEvent.VK_C);
        check("Ctrl+Shift+C 後はNORMALモードに戻る", ed.isNormalMode());
        check("カーソルは選択開始行に戻る", ed.getCursorRow() == 0);
    }

    static void testVisualBlockCtrlShiftCReturnsToNormal() {
        System.out.println("[VISUAL BLOCK: Ctrl+Shift+C でNORMALへ戻りクラッシュしない]");
        ModalEditor ed = new ModalEditor("abcdef\nghijkl\nmnopqr");
        ed.processKey(KeyEvent.VK_V, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK);
        pressKey(ed, 'j');
        pressKey(ed, 'l');
        pressCtrlShift(ed, KeyEvent.VK_C);
        check("Ctrl+Shift+C 後はNORMALモードに戻る", ed.isNormalMode());
    }

    static void testNormalCtrlShiftVDoesNotCrash() {
        System.out.println("[NORMAL: Ctrl+Shift+V でクラッシュせずNORMALのまま]");
        ModalEditor ed = new ModalEditor("hello");
        pressCtrlShift(ed, KeyEvent.VK_V);
        check("Ctrl+Shift+V 後もNORMALモードのまま", ed.isNormalMode());
        check("statusMessage が設定される", ed.getStatusMessage() != null && !ed.getStatusMessage().isEmpty());
        if (HEADLESS) {
            check("ヘッドレス環境ではテキストは変化しない", ed.getText().equals("hello"));
        }
    }

    static void testInsertCtrlShiftVDoesNotCrash() {
        System.out.println("[INSERT: Ctrl+Shift+V でクラッシュせずINSERTのまま]");
        ModalEditor ed = new ModalEditor("hello");
        pressKey(ed, 'i');
        pressCtrlShift(ed, KeyEvent.VK_V);
        check("Ctrl+Shift+V 後もINSERTモードのまま", !ed.isNormalMode());
    }

    // Ctrl+V（VISUAL BLOCK突入）とCtrl+Shift+V（クリップボード貼り付け）が別アクションとして
    // 解決されることを確認する（既存のenter.visual.blockバインドとの非衝突チェック）。
    static void testCtrlShiftVDoesNotConflictWithCtrlV() {
        System.out.println("[NORMAL: Ctrl+V は従来通り VISUAL BLOCK に入る]");
        ModalEditor ed = new ModalEditor("hello");
        ed.processKey(KeyEvent.VK_V, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK);
        check("Ctrl+VはVISUAL BLOCKへ遷移する（NORMALのままではない）", !ed.isNormalMode());
    }

    // ユーティリティ
    // -------------------------------------------------------------------------

    static void pressKey(ModalEditor ed, char keyChar) {
        ed.processKey(0, keyChar, 0);
    }

    static void pressCtrlShift(ModalEditor ed, int keyCode) {
        ed.processKey(keyCode, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
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
