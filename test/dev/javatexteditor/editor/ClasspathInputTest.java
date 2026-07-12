package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;

/**
 * F10/F11/F12 の追加クラスパス入力（CLASSPATH_INPUTモード）のテストハーネス
 * （mainメソッド形式・JUnit不使用）。
 */
public class ClasspathInputTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testEnterEntersMode();
        testEscSkipsWithEmptyList();
        testEnterParsesCommaSeparatedPaths();
        testEnterWithEmptyInputYieldsEmptyList();
        testBackspaceEditsBuffer();
        testCallbackFiresExactlyOnce();

        System.out.printf("%nPASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
        if (fail > 0) System.exit(1);
    }

    static void testEnterEntersMode() {
        System.out.println("[enterClasspathInput: CLASSPATH_INPUTモードに入る]");
        ModalEditor ed = new ModalEditor("abc");
        ed.enterClasspathInput("F10", extra -> {});
        check("CLASSPATH_INPUTモードになる", ed.isClasspathInputMode());
        check("入力バッファは空", ed.getClasspathInputBuffer().isEmpty());
    }

    static void testEscSkipsWithEmptyList() {
        System.out.println("[Esc: 追加クラスパスなしで即続行]");
        ModalEditor ed = new ModalEditor("abc");
        List<Path>[] captured = new List[1];
        ed.enterClasspathInput("F11", extra -> captured[0] = extra);
        pressChar(ed, 'r');
        pressChar(ed, 'e');
        pressChar(ed, 's');
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        check("NORMALモードに戻る", ed.isNormalMode());
        check("callbackは空リストで呼ばれる", captured[0] != null && captured[0].isEmpty());
    }

    static void testEnterParsesCommaSeparatedPaths() {
        System.out.println("[Enter: カンマ区切りの複数ディレクトリを解決する]");
        ModalEditor ed = new ModalEditor("abc");
        Path root = Path.of(System.getProperty("java.io.tmpdir"));
        ed.setProjectRoot(root);
        List<Path>[] captured = new List[1];
        ed.enterClasspathInput("F10", extra -> captured[0] = extra);
        for (char c : "res, lib/assets".toCharArray()) pressChar(ed, c);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
        check("NORMALモードに戻る", ed.isNormalMode());
        check("2件解決される", captured[0] != null && captured[0].size() == 2);
        check("1件目はprojectRoot基準の絶対パス", captured[0].get(0).equals(root.resolve("res").toAbsolutePath()));
        check("空白がtrimされる", captured[0].get(1).equals(root.resolve("lib/assets").toAbsolutePath()));
    }

    static void testEnterWithEmptyInputYieldsEmptyList() {
        System.out.println("[Enter: 何も入力せず確定した場合は空リスト]");
        ModalEditor ed = new ModalEditor("abc");
        List<Path>[] captured = new List[1];
        ed.enterClasspathInput("F12", extra -> captured[0] = extra);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
        check("callbackは空リストで呼ばれる", captured[0] != null && captured[0].isEmpty());
    }

    static void testBackspaceEditsBuffer() {
        System.out.println("[Backspace: 入力バッファを編集できる]");
        ModalEditor ed = new ModalEditor("abc");
        ed.enterClasspathInput("F10", extra -> {});
        pressChar(ed, 'r');
        pressChar(ed, 'e');
        pressChar(ed, 's');
        ed.processKey(KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED, 0);
        check("末尾1文字が削除される", ed.getClasspathInputBuffer().equals("re"));
    }

    static void testCallbackFiresExactlyOnce() {
        System.out.println("[callback: Enter/Escいずれも1回だけ呼ばれる]");
        ModalEditor ed = new ModalEditor("abc");
        int[] count = new int[1];
        ed.enterClasspathInput("F10", extra -> count[0]++);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
        check("1回だけ呼ばれる", count[0] == 1);
    }

    // ユーティリティ
    // -------------------------------------------------------------------------

    static void pressChar(ModalEditor ed, char keyChar) {
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
