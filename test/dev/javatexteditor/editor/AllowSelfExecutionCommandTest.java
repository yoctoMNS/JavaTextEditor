package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;

/**
 * {@code :set allowselfexecution} コマンドのテストハーネス（mainメソッド形式・JUnit不使用）。
 * F11/F12自体の実行ブロック判定は {@code dev.javatexteditor.app.SelfExecutionBlockTest} が
 * 検証する。本テストは、COMMANDモードの文字列 {@code "set allowselfexecution"} が
 * {@link ModalEditor#setAllowSelfExecutionCallback} で登録したコールバックを正しく
 * 発火させることだけを検証する（CommandRegistryへの配線の回帰防止）。
 */
public class AllowSelfExecutionCommandTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testCommandFiresCallback();
        testCommandShowsConfirmationMessage();
        testCommandWithoutCallbackDoesNotThrow();

        System.out.printf("%nPASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
        if (fail > 0) System.exit(1);
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

    static void typeCommand(ModalEditor ed, String cmd) {
        ed.processKey(0, ';', 0); // ; → COMMAND（: の別名）
        for (char c : cmd.toCharArray()) ed.processKey(0, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    static void testCommandFiresCallback() {
        System.out.println("[:set allowselfexecution: コールバックが1回発火する]");
        ModalEditor ed = new ModalEditor("abc");
        int[] count = {0};
        ed.setAllowSelfExecutionCallback(() -> count[0]++);

        typeCommand(ed, "set allowselfexecution");

        check("コールバックが1回発火する", count[0] == 1);
        check("NORMALモードに戻る", ed.isNormalMode());
    }

    static void testCommandShowsConfirmationMessage() {
        System.out.println("[:set allowselfexecution: statusMessageで確認できる]");
        ModalEditor ed = new ModalEditor("abc");
        ed.setAllowSelfExecutionCallback(() -> {});

        typeCommand(ed, "set allowselfexecution");

        check("許可した旨のstatusMessageが表示される",
              ed.getStatusMessage() != null && ed.getStatusMessage().contains("allowed"));
    }

    /** コールバック未配線（GUI未接続の単体テスト等）でも例外にならないこと。 */
    static void testCommandWithoutCallbackDoesNotThrow() {
        System.out.println("[:set allowselfexecution: コールバック未配線でも例外にならない]");
        ModalEditor ed = new ModalEditor("abc");
        try {
            typeCommand(ed, "set allowselfexecution");
            check("例外を投げずに完了する", true);
        } catch (Exception e) {
            check("例外を投げずに完了する（実際は " + e + "）", false);
        }
    }
}
