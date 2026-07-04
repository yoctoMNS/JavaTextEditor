package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ModalEditor のコールバック発火規約を固定する特性テスト。
 * Main.setupCompileAnalysis はこの契約（INSERT→NORMAL 遷移で onReturnToNormal、
 * :w 成功で onSave、Ctrl+Shift+O で onOrganizeImports が発火すること）に依存しているが、
 * Main 自体は GUI・static 依存でテスト不能なため、依存される側の契約をここで固定する。
 * mainメソッド形式のテストハーネス（JUnit不使用）。EditorCanvas は生成しない。
 */
public class CompileTriggerCallbackTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testOnReturnToNormalFiresOnInsertToNormal();
        testOnSaveFiresOnSuccessfulWrite();
        testOnSaveNotFiredWithoutFileName();
        testOnOrganizeImportsFiresOnCtrlShiftO();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        System.exit(fail > 0 ? 1 : 0);
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

    /** INSERT→NORMAL 遷移（ESC）で onReturnToNormal が1回発火する。 */
    static void testOnReturnToNormalFiresOnInsertToNormal() {
        System.out.println("[onReturnToNormal: INSERT→NORMAL 遷移で発火]");
        ModalEditor ed = new ModalEditor("hello");
        int[] counter = {0};
        ed.setOnReturnToNormal(() -> counter[0]++);

        ed.processKey(0, 'i', 0);                              // NORMAL → INSERT
        check("INSERT 遷移時点では発火しない", counter[0] == 0);
        ed.processKey(KeyEvent.VK_ESCAPE, (char) 27, 0);       // INSERT → NORMAL
        check("INSERT→NORMAL 遷移で1回発火する", counter[0] == 1);
    }

    /** 保存先パスがある場合、:w 成功で onSave が1回発火しファイル内容がバッファと一致する。 */
    static void testOnSaveFiresOnSuccessfulWrite() throws IOException {
        System.out.println("[onSave: :w 成功で発火]");
        Path tmp = Files.createTempFile("jte-callback-test-", ".txt");
        try {
            ModalEditor ed = new ModalEditor("callback save test", tmp.toString(), null);
            int[] counter = {0};
            ed.setOnSave(() -> counter[0]++);

            ed.processKey(0, ';', 0);                          // ; → COMMAND（Vim 同様 : の別名）
            ed.processKey(0, 'w', 0);
            ed.processKey(KeyEvent.VK_ENTER, '\n', 0);

            check(":w 成功で onSave が1回発火する", counter[0] == 1);
            check("一時ファイルの内容がバッファと一致する",
                  Files.readString(tmp).equals(ed.getText()));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 保存先パスがない場合、:w は失敗し onSave は発火せずエラーメッセージが出る。 */
    static void testOnSaveNotFiredWithoutFileName() {
        System.out.println("[onSave: パス未設定の :w では発火しない]");
        ModalEditor ed = new ModalEditor("no path");
        int[] counter = {0};
        ed.setOnSave(() -> counter[0]++);

        ed.processKey(0, ';', 0);
        ed.processKey(0, 'w', 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);

        check("パス未設定の :w では onSave が発火しない", counter[0] == 0);
        check("statusMessage が \"E: no file name\" になる",
              "E: no file name".equals(ed.getStatusMessage()));
    }

    /** NORMAL モードの Ctrl+Shift+O で onOrganizeImports が1回発火する。 */
    static void testOnOrganizeImportsFiresOnCtrlShiftO() {
        System.out.println("[onOrganizeImports: Ctrl+Shift+O で発火]");
        ModalEditor ed = new ModalEditor("import java.util.List;\nclass A {}\n");
        int[] counter = {0};
        ed.setOnOrganizeImports(() -> counter[0]++);

        ed.processKey(KeyEvent.VK_O, KeyEvent.CHAR_UNDEFINED,
                KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);

        check("Ctrl+Shift+O で onOrganizeImports が1回発火する", counter[0] == 1);
    }
}
