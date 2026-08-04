package dev.javatexteditor.editor;

import dev.javatexteditor.analysis.AutoImportHandler;
import dev.javatexteditor.analysis.CompileDiagnostic;
import dev.javatexteditor.analysis.DiagnosticKind;
import dev.javatexteditor.analysis.ImportSuggester;
import dev.javatexteditor.analysis.JdkClassIndex;
import dev.javatexteditor.analysis.SourceAnalyzer;
import java.util.List;

/**
 * auto-import が import 文を挿入した際、カーソルが挿入前と同じ論理位置（同じ文字）を
 * 指し続けることを検証する（修正前は cursorRow が挿入行数分ずれず、内容とカーソル位置が
 * 噛み合わなくなるバグがあった。詳細は decision-log 参照）。
 */
public class AutoImportCursorPositionTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testSingleCandidateKeepsCursorOnSameLine();
        testMultiLineInsertKeepsCursorOnSameLine();
        testMultiCandidateSelectionKeepsCursorOnSameLine();
        testCursorAboveInsertPointUnaffected();
        testOrganizeImportsRemoveUnusedKeepsCursorOnSameLine();
        testRemoveImportCommandKeepsCursorOnSameLine();

        System.out.println("\n=== AutoImportCursorPositionTest: " + passed + "/" + (passed + failed) + " PASS ===");
        if (failed > 0) System.exit(1);
    }

    static void check(String label, boolean cond) {
        if (cond) { System.out.println("[OK] " + label); passed++; }
        else { System.out.println("[FAIL] " + label); failed++; }
    }

    static AutoImportHandler newHandler() throws Exception {
        JdkClassIndex jdkIndex = JdkClassIndex.buildSync();
        ImportSuggester suggester = new ImportSuggester(jdkIndex);
        return new AutoImportHandler(suggester, new SourceAnalyzer());
    }

    /** ケース1: 候補1件・1行挿入。カーソルは編集していた行の内容を指し続ける。 */
    static void testSingleCandidateKeepsCursorOnSameLine() throws Exception {
        System.out.println("--- 単一候補の自動挿入（1行挿入）でカーソルが同じ内容の位置に留まる ---");
        // "ArrayList" は候補が java.util.ArrayList の1件のみなので即自動挿入される。
        String src = "package p;\n\npublic class Foo {\n    ArrayList x;\n}\n";
        ModalEditor ed = new ModalEditor(src);
        ed.setAutoImportHandler(newHandler());

        // カーソルを "// ここで作業していた" 相当の行（ArrayList xの行、row=3）の末尾に置く。
        ed.setCursor(3, "    ArrayList x;".length());

        CompileDiagnostic diag = new CompileDiagnostic(3, 4,
            "cannot find symbol\n  symbol:   class ArrayList", DiagnosticKind.ERROR);
        ed.handleAutoImport(List.of(diag));

        String after = ed.getText();
        check("import が挿入された", after.contains("import java.util.ArrayList;"));

        String[] lines = after.split("\n", -1);
        int actualLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("ArrayList x;")) { actualLine = i; break; }
        }
        check("カーソル行が挿入後の ArrayList x; 行と一致する (expected=" + actualLine + " actual=" + ed.getCursorRow() + ")",
              ed.getCursorRow() == actualLine);
        check("カーソル列が変化していない", ed.getCursorCol() == "    ArrayList x;".length());
    }

    /** ケース2: 複数行（package文が無い＝import 2件で複数行）挿入でもカーソルが正しく追従する。 */
    static void testMultiLineInsertKeepsCursorOnSameLine() throws Exception {
        System.out.println("--- 複数行挿入でもカーソルが同じ内容の位置に留まる ---");
        // ArrayList と HashMap はいずれも候補1件のみなので、2行分の import が自動挿入される。
        String src = "package p;\n\npublic class Foo {\n    ArrayList a;\n    HashMap m;\n}\n";
        ModalEditor ed = new ModalEditor(src);
        ed.setAutoImportHandler(newHandler());

        // カーソルを "HashMap m;" 行に置く。
        ed.setCursor(4, "    HashMap m;".length());

        CompileDiagnostic d1 = new CompileDiagnostic(3, 4,
            "cannot find symbol\n  symbol:   class ArrayList", DiagnosticKind.ERROR);
        CompileDiagnostic d2 = new CompileDiagnostic(4, 4,
            "cannot find symbol\n  symbol:   class HashMap", DiagnosticKind.ERROR);
        ed.handleAutoImport(List.of(d1, d2));

        String after = ed.getText();
        check("両方の import が挿入された",
              after.contains("import java.util.ArrayList;") && after.contains("import java.util.HashMap;"));

        String[] lines = after.split("\n", -1);
        int actualLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("HashMap m;")) { actualLine = i; break; }
        }
        check("カーソル行が挿入後の HashMap m; 行と一致する (expected=" + actualLine + " actual=" + ed.getCursorRow() + ")",
              ed.getCursorRow() == actualLine);
    }

    /** ケース3: 複数候補の選択UI（IMPORT_SELECT）経由の挿入でもカーソルが追従する。 */
    static void testMultiCandidateSelectionKeepsCursorOnSameLine() throws Exception {
        System.out.println("--- 複数候補選択後の挿入でもカーソルが同じ内容の位置に留まる ---");
        // "List" は java.util.List / java.awt.List の2候補があるため IMPORT_SELECT モードに入る。
        String src = "package p;\n\npublic class Foo {\n    List x;\n}\n";
        ModalEditor ed = new ModalEditor(src);
        ed.setAutoImportHandler(newHandler());

        ed.setCursor(3, "    List x;".length());

        CompileDiagnostic diag = new CompileDiagnostic(3, 4,
            "cannot find symbol\n  symbol:   class List", DiagnosticKind.ERROR);
        ed.handleAutoImport(List.of(diag));

        // IMPORT_SELECT モードで先頭候補を Enter で確定する
        ed.processKey(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.CHAR_UNDEFINED, 0);

        String after = ed.getText();
        check("import が挿入された", after.contains("import java.util.List;") || after.contains("import java.awt.List;"));

        String[] lines = after.split("\n", -1);
        int actualLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("List x;")) { actualLine = i; break; }
        }
        check("カーソル行が挿入後の List x; 行と一致する (expected=" + actualLine + " actual=" + ed.getCursorRow() + ")",
              ed.getCursorRow() == actualLine);
    }

    /** ケース4: カーソルが挿入位置より上（package行）にある場合は変化しない。 */
    static void testCursorAboveInsertPointUnaffected() throws Exception {
        System.out.println("--- 挿入位置より上にカーソルがある場合は位置が変わらない ---");
        String src = "package p;\n\npublic class Foo {\n    ArrayList x;\n}\n";
        ModalEditor ed = new ModalEditor(src);
        ed.setAutoImportHandler(newHandler());

        ed.setCursor(0, 0); // package 文の行・先頭

        CompileDiagnostic diag = new CompileDiagnostic(3, 4,
            "cannot find symbol\n  symbol:   class ArrayList", DiagnosticKind.ERROR);
        ed.handleAutoImport(List.of(diag));

        check("import が挿入された", ed.getText().contains("import java.util.ArrayList;"));
        check("カーソル位置が変化していない (row=" + ed.getCursorRow() + " col=" + ed.getCursorCol() + ")",
              ed.getCursorRow() == 0 && ed.getCursorCol() == 0);
    }

    /**
     * バグ①修正: SPC+i+o / :oi（organizeImportsRemoveUnused）で未使用 import を削除した際も、
     * 挿入時と同じくカーソルが同じ内容の行に留まることを検証する。修正前は削除経路にだけ
     * shiftCursorAfterImportEdit()/shiftDiagnosticsAfterImportEdit() が呼ばれておらず、
     * カーソルが削除された行数分ずれたままになっていた。
     */
    static void testOrganizeImportsRemoveUnusedKeepsCursorOnSameLine() throws Exception {
        System.out.println("--- 未使用importの削除（:oi）でもカーソルが同じ内容の位置に留まる ---");
        // ArrayList は未使用（本文中で参照されていない）ため削除される。HashMap は使用中なので残る。
        String src = "package p;\n\nimport java.util.ArrayList;\nimport java.util.HashMap;\n\n"
            + "public class Foo {\n    HashMap m;\n}\n";
        ModalEditor ed = new ModalEditor(src);
        ed.setAutoImportHandler(newHandler());

        // カーソルを "HashMap m;" 行の末尾に置く。
        int cursorRowBefore = -1;
        String[] beforeLines = src.split("\n", -1);
        for (int i = 0; i < beforeLines.length; i++) {
            if (beforeLines[i].contains("HashMap m;")) { cursorRowBefore = i; break; }
        }
        ed.setCursor(cursorRowBefore, "    HashMap m;".length());

        ed.organizeImportsRemoveUnused();

        String after = ed.getText();
        check("未使用のArrayList importが削除された", !after.contains("import java.util.ArrayList;"));
        check("使用中のHashMap importは残る", after.contains("import java.util.HashMap;"));

        String[] afterLines = after.split("\n", -1);
        int actualLine = -1;
        for (int i = 0; i < afterLines.length; i++) {
            if (afterLines[i].contains("HashMap m;")) { actualLine = i; break; }
        }
        check("削除で行数が減った (before=" + cursorRowBefore + " after=" + actualLine + ")", actualLine < cursorRowBefore);
        check("カーソル行が削除後の HashMap m; 行と一致する (expected=" + actualLine + " actual=" + ed.getCursorRow() + ")",
              ed.getCursorRow() == actualLine);
        check("カーソル列が変化していない", ed.getCursorCol() == "    HashMap m;".length());
    }

    /** バグ①修正: :remove-import <fqn> でも同様にカーソルが追従することを検証する。 */
    static void testRemoveImportCommandKeepsCursorOnSameLine() throws Exception {
        System.out.println("--- :remove-import でもカーソルが同じ内容の位置に留まる ---");
        String src = "package p;\n\nimport java.util.ArrayList;\nimport java.util.HashMap;\n\n"
            + "public class Foo {\n    HashMap m;\n}\n";
        ModalEditor ed = new ModalEditor(src);
        ed.setAutoImportHandler(newHandler());

        int cursorRowBefore = -1;
        String[] beforeLines = src.split("\n", -1);
        for (int i = 0; i < beforeLines.length; i++) {
            if (beforeLines[i].contains("HashMap m;")) { cursorRowBefore = i; break; }
        }
        ed.setCursor(cursorRowBefore, "    HashMap m;".length());

        ed.processKey(java.awt.event.KeyEvent.VK_ESCAPE, java.awt.event.KeyEvent.CHAR_UNDEFINED, 0);
        ed.processKey(java.awt.event.KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : "remove-import java.util.ArrayList".toCharArray()) {
            ed.processKey(java.awt.event.KeyEvent.VK_UNDEFINED, c, 0);
        }
        ed.processKey(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.CHAR_UNDEFINED, 0);

        String after = ed.getText();
        check("指定したimportが削除された", !after.contains("import java.util.ArrayList;"));

        String[] afterLines = after.split("\n", -1);
        int actualLine = -1;
        for (int i = 0; i < afterLines.length; i++) {
            if (afterLines[i].contains("HashMap m;")) { actualLine = i; break; }
        }
        check("カーソル行が削除後の HashMap m; 行と一致する (expected=" + actualLine + " actual=" + ed.getCursorRow() + ")",
              ed.getCursorRow() == actualLine);
    }
}
