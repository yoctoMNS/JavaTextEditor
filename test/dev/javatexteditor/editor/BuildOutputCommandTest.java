package dev.javatexteditor.editor;

import dev.javatexteditor.projectbuild.BuildResult;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * F10/F11/F12 の *compile* / *run* 疑似バッファの先頭に、実際に発行したjavac/javaコマンドが
 * 表示されることを検証するテストハーネス（mainメソッド形式・JUnit不使用）。
 * また、これらの疑似バッファがSPC+b（BufferPicker）からいつでも再度開けることも検証する。
 */
public class BuildOutputCommandTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testShowCompileResultPutsCommandFirst();
        testShowCompileResultOmitsCommandLineWhenEmpty();
        testShowRunOutputPutsCommandFirst();
        testCompileBufferReachableViaSpcB();
        testRunBufferReachableViaSpcB();
        testSpcBOmitsPseudoBuffersBeforeTheyExist();
        testCompileBufferReopenPreservesContentAfterNavigatingAway();

        System.out.printf("%nPASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
        if (fail > 0) System.exit(1);
    }

    static void testShowCompileResultPutsCommandFirst() {
        System.out.println("[showCompileResult: 先頭にjavacコマンドを表示する]");
        ModalEditor ed = new ModalEditor("abc");
        BuildResult result = new BuildResult(true, 1, List.of(), null, "javac -d bin Hello.java");
        ed.showCompileResult(result);
        String[] lines = ed.getText().split("\n", -1);
        check("1行目がjavacコマンド", lines[0].equals("javac -d bin Hello.java"));
        check("2行目が*compile*サマリ", lines[1].startsWith("*compile* SUCCESS"));
    }

    static void testShowCompileResultOmitsCommandLineWhenEmpty() {
        System.out.println("[showCompileResult: commandが空なら先頭行を追加しない]");
        ModalEditor ed = new ModalEditor("abc");
        BuildResult result = new BuildResult(false, 0, List.of(), "コンパイル対象がありません", "");
        ed.showCompileResult(result);
        String[] lines = ed.getText().split("\n", -1);
        check("1行目が直接*compile*サマリ", lines[0].startsWith("*compile* FAILED"));
    }

    static void testShowRunOutputPutsCommandFirst() {
        System.out.println("[showRunOutput: 先頭にjavaコマンドを表示する]");
        ModalEditor ed = new ModalEditor("abc");
        ed.showRunOutput("java -cp bin Hello", "Hello", "hi\n", 0);
        String[] lines = ed.getText().split("\n", -1);
        check("1行目がjavaコマンド", lines[0].equals("java -cp bin Hello"));
        check("2行目が*run*サマリ", lines[1].startsWith("*run* Hello"));
        check("3行目以降が実行出力", lines[2].equals("hi"));
    }

    static void testCompileBufferReachableViaSpcB() {
        System.out.println("[SPC+b: *compile*疑似バッファをいつでも再度開ける]");
        ModalEditor ed = new ModalEditor("abc");
        BuildResult result = new BuildResult(true, 1, List.of(), null, "javac -d bin Hello.java");
        ed.showCompileResult(result);
        String compileText = ed.getText();
        openSpcB(ed);
        boolean foundCompile = telescopeHasEntry(ed, "*compile*");
        check("SPC+bの候補に*compile*が含まれる", foundCompile);
        selectEntry(ed, "*compile*");
        check("選択後に*compile*疑似バッファへ復元される", ed.getText().equals(compileText));
    }

    static void testRunBufferReachableViaSpcB() {
        System.out.println("[SPC+b: *run*疑似バッファをいつでも再度開ける]");
        ModalEditor ed = new ModalEditor("abc");
        ed.showRunOutput("java -cp bin Hello", "Hello", "hi\n", 0);
        String runText = ed.getText();
        openSpcB(ed);
        boolean foundRun = telescopeHasEntry(ed, "*run*");
        check("SPC+bの候補に*run*が含まれる", foundRun);
        selectEntry(ed, "*run*");
        check("選択後に*run*疑似バッファへ復元される", ed.getText().equals(runText));
    }

    static void testSpcBOmitsPseudoBuffersBeforeTheyExist() {
        System.out.println("[SPC+b: F10/F11を一度も実行していなければ*compile*/*run*は候補に出ない]");
        ModalEditor ed = new ModalEditor("abc");
        openSpcB(ed);
        check("*compile*が候補に含まれない", !telescopeHasEntry(ed, "*compile*"));
        check("*run*が候補に含まれない", !telescopeHasEntry(ed, "*run*"));
    }

    static void testCompileBufferReopenPreservesContentAfterNavigatingAway() {
        System.out.println("[SPC+b: 別バッファへ移動した後でも*compile*の内容を保持する]");
        ModalEditor ed = new ModalEditor("abc");
        BuildResult result = new BuildResult(true, 1, List.of(), null, "javac -d bin Hello.java");
        ed.showCompileResult(result);
        String compileText = ed.getText();
        // F11実行結果で*compile*から画面が差し替わった状態を模す
        ed.showRunOutput("java -cp bin Hello", "Hello", "hi\n", 0);
        check("画面は*run*に差し替わっている", ed.getText().startsWith("java -cp bin Hello"));
        openSpcB(ed);
        selectEntry(ed, "*compile*");
        check("*compile*の内容がキャッシュから正しく復元される", ed.getText().equals(compileText));
    }

    // ユーティリティ
    // -------------------------------------------------------------------------

    private static void openSpcB(ModalEditor ed) {
        ed.processKey(KeyEvent.VK_SPACE, ' ', 0);
        ed.processKey(KeyEvent.VK_B, 'b', 0);
    }

    private static boolean telescopeHasEntry(ModalEditor ed, String display) {
        for (var item : ed.getTelescopeResults()) {
            if (item.display().equals(display)) return true;
        }
        return false;
    }

    private static void selectEntry(ModalEditor ed, String display) {
        int idx = -1;
        var results = ed.getTelescopeResults();
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).display().equals(display)) { idx = i; break; }
        }
        if (idx < 0) throw new IllegalStateException("entry not found: " + display);
        for (int i = 0; i < idx; i++) {
            ed.processKey(KeyEvent.VK_N, 'n', KeyEvent.CTRL_DOWN_MASK);
        }
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
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
