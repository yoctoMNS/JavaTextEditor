package dev.javatexteditor.editor;

import dev.javatexteditor.analysis.AutoImportHandler;
import dev.javatexteditor.analysis.CompileDiagnostic;
import dev.javatexteditor.analysis.DiagnosticKind;
import dev.javatexteditor.analysis.ImportSuggester;
import dev.javatexteditor.analysis.JdkClassIndex;
import dev.javatexteditor.analysis.SourceAnalyzer;
import java.util.List;

/**
 * auto-import が import 文を挿入した際、既存の波下線/ガター診断の行番号が挿入で増えた
 * 行数だけ正しく補正されることを検証する（修正前は挿入前の行番号のまま残り、実際の
 * エラー行とずれた位置に下線が表示され続けるバグがあった）。
 */
public class AutoImportDiagnosticShiftTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testSingleCandidateShiftsExistingDiagnostics();
        testMultiCandidateSelectionShiftsExistingDiagnostics();
        testNoImportInsertedLeavesDiagnosticsUnchanged();

        System.out.println("\n=== AutoImportDiagnosticShiftTest: " + passed + "/" + (passed + failed) + " PASS ===");
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

    static void testSingleCandidateShiftsExistingDiagnostics() throws Exception {
        System.out.println("--- 単一候補の自動挿入で診断行がシフトされる ---");
        // "ArrayList" は候補が java.util.ArrayList の1件のみなので即自動挿入される。
        String src = "package p;\n\npublic class Foo {\n    ArrayList x;\n}\n";
        ModalEditor ed = new ModalEditor(src);
        ed.setAutoImportHandler(newHandler());

        // 修正前の挿入前ソースを基準にした診断行(3行目=ArrayList xの行)をあらかじめ表示させておく。
        CompileDiagnostic before = new CompileDiagnostic(3, 4,
            "cannot find symbol\n  symbol:   class ArrayList", DiagnosticKind.ERROR);
        ed.setDiagnostics(List.of(before));

        ed.handleAutoImport(List.of(before));

        // import 文が1行 + 空行1行 = 2行増えているはず（package文の直後に挿入される）
        String after = ed.getText();
        check("import が挿入された", after.contains("import java.util.ArrayList;"));

        List<CompileDiagnostic> shown = ed.getLocalDiagnosticsForTest();
        check("診断が1件のまま残っている", shown.size() == 1);
        int newLine = shown.get(0).lineNumber();
        // 実際に "ArrayList x;" が存在する行番号と一致するはず
        String[] lines = after.split("\n", -1);
        int actualListLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("ArrayList x;")) { actualListLine = i; break; }
        }
        check("シフト後の診断行が実際の ArrayList x; 行と一致する (expected=" + actualListLine + " actual=" + newLine + ")",
              newLine == actualListLine);
    }

    static void testMultiCandidateSelectionShiftsExistingDiagnostics() throws Exception {
        System.out.println("--- 複数候補選択後の挿入でも診断行がシフトされる ---");
        // "List" は java.util.List / java.awt.List の2候補があるため IMPORT_SELECT モードに入る。
        String src = "package p;\n\npublic class Foo {\n    List x;\n}\n";
        ModalEditor ed = new ModalEditor(src);
        ed.setAutoImportHandler(newHandler());

        CompileDiagnostic beforeDiag = new CompileDiagnostic(3, 4,
            "cannot find symbol\n  symbol:   class List", DiagnosticKind.ERROR);
        ed.setDiagnostics(List.of(beforeDiag));
        ed.handleAutoImport(List.of(beforeDiag));

        // IMPORT_SELECT モードで先頭候補を Enter で確定する
        ed.processKey(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.CHAR_UNDEFINED, 0);

        String after = ed.getText();
        check("import が挿入された", after.contains("import java.util.List;") || after.contains("import java.awt.List;"));
        List<CompileDiagnostic> shown = ed.getLocalDiagnosticsForTest();
        check("診断が1件のまま残っている", shown.size() == 1);
        String[] lines = after.split("\n", -1);
        int actualListLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("List x;")) { actualListLine = i; break; }
        }
        check("シフト後の診断行が実際の List x; 行と一致する (expected=" + actualListLine + " actual=" + shown.get(0).lineNumber() + ")",
              shown.get(0).lineNumber() == actualListLine);
    }

    static void testNoImportInsertedLeavesDiagnosticsUnchanged() throws Exception {
        System.out.println("--- 挿入対象がなければ診断行は変化しない ---");
        String src = "package p;\n\npublic class Foo {\n    int x;\n}\n";
        ModalEditor ed = new ModalEditor(src);
        ed.setAutoImportHandler(newHandler());
        CompileDiagnostic d = new CompileDiagnostic(3, 4, "unrelated", DiagnosticKind.ERROR);
        ed.setDiagnostics(List.of(d));
        ed.handleAutoImport(List.of(d));
        List<CompileDiagnostic> shown = ed.getLocalDiagnosticsForTest();
        check("診断行が変化していない", shown.size() == 1 && shown.get(0).lineNumber() == 3);
    }
}
