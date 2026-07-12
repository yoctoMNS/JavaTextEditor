package dev.javatexteditor.editor;

import dev.javatexteditor.projectbuild.BuildResult;
import java.util.List;

/**
 * F10/F11/F12 の *compile* / *run* 疑似バッファの先頭に、実際に発行したjavac/javaコマンドが
 * 表示されることを検証するテストハーネス（mainメソッド形式・JUnit不使用）。
 */
public class BuildOutputCommandTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testShowCompileResultPutsCommandFirst();
        testShowCompileResultOmitsCommandLineWhenEmpty();
        testShowRunOutputPutsCommandFirst();

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

    // ユーティリティ
    // -------------------------------------------------------------------------

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
