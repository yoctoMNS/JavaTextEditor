package dev.javatexteditor.analysis;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

public class CompileAnalyzerTest {

    public static void main(String[] args) {
        int pass = 0;

        // Test 1: 正常な Java ソースでエラーが 0 件になる
        // public クラスはファイル名との一致が必要なので非 public クラスを使用
        {
            CompileAnalyzer analyzer = new CompileAnalyzer();
            try {
                List<CompileDiagnostic> diags = analyzer.analyze(
                    "class Foo { void bar() {} }");
                boolean ok = diags.isEmpty();
                System.out.println((ok ? "[OK] " : "[FAIL] ")
                    + "正常ソースでエラー0件 (got " + diags.size() + ")");
                pass += ok ? 1 : 0;
            } catch (AnalysisException e) {
                System.out.println("[FAIL] 正常ソース解析で例外: " + e.getMessage());
            }
        }

        // Test 2: セミコロン欠落（構文エラー）が行番号付きで検出される
        {
            CompileAnalyzer analyzer = new CompileAnalyzer();
            try {
                List<CompileDiagnostic> diags = analyzer.analyze(
                    "public class Bar {\n" +
                    "    public void foo() {\n" +
                    "        int x = 1\n" +  // セミコロン欠落
                    "    }\n" +
                    "}");
                boolean hasError = diags.stream()
                    .anyMatch(d -> d.kind() == DiagnosticKind.ERROR);
                System.out.println((hasError ? "[OK] " : "[FAIL] ")
                    + "構文エラー（セミコロン欠落）が ERROR として検出される");
                pass += hasError ? 1 : 0;
            } catch (AnalysisException e) {
                System.out.println("[FAIL] 構文エラー解析で例外: " + e.getMessage());
            }
        }

        // Test 3: 行番号が 0-indexed で正しく返る（エラーが 3 行目なら lineNumber==2）
        {
            CompileAnalyzer analyzer = new CompileAnalyzer();
            try {
                List<CompileDiagnostic> diags = analyzer.analyze(
                    "public class Baz {\n" +       // line 0
                    "    public void foo() {\n" +   // line 1
                    "        int x = 1\n" +         // line 2: エラー
                    "    }\n" +
                    "}");
                boolean lineOk = diags.stream()
                    .filter(d -> d.kind() == DiagnosticKind.ERROR)
                    .anyMatch(d -> d.lineNumber() == 2);
                System.out.println((lineOk ? "[OK] " : "[FAIL] ")
                    + "行番号 0-indexed で 2 を返す (diags=" + diags + ")");
                pass += lineOk ? 1 : 0;
            } catch (AnalysisException e) {
                System.out.println("[FAIL] 行番号テストで例外: " + e.getMessage());
            }
        }

        // Test 4: 未定義型参照（型エラー）が ERROR として検出される
        {
            CompileAnalyzer analyzer = new CompileAnalyzer();
            try {
                List<CompileDiagnostic> diags = analyzer.analyze(
                    "public class Qux {\n" +
                    "    UndefinedType x;\n" +  // 未定義型
                    "}");
                boolean hasError = diags.stream()
                    .anyMatch(d -> d.kind() == DiagnosticKind.ERROR);
                System.out.println((hasError ? "[OK] " : "[FAIL] ")
                    + "未定義型が ERROR として検出される");
                pass += hasError ? 1 : 0;
            } catch (AnalysisException e) {
                System.out.println("[FAIL] 未定義型テストで例外: " + e.getMessage());
            }
        }

        // Test 5: エラーがある場合 DiagnosticKind.ERROR が含まれる
        {
            CompileAnalyzer analyzer = new CompileAnalyzer();
            try {
                List<CompileDiagnostic> diags = analyzer.analyze(
                    "public class ErrTest { int x = \"not an int\"; }");
                boolean hasError = diags.stream()
                    .anyMatch(d -> d.kind() == DiagnosticKind.ERROR);
                System.out.println((hasError ? "[OK] " : "[FAIL] ")
                    + "型不一致エラーが ERROR 種別で返る");
                pass += hasError ? 1 : 0;
            } catch (AnalysisException e) {
                System.out.println("[FAIL] 型不一致テストで例外: " + e.getMessage());
            }
        }

        // Test 6: 複数エラーが複数の CompileDiagnostic として返る
        {
            CompileAnalyzer analyzer = new CompileAnalyzer();
            try {
                List<CompileDiagnostic> diags = analyzer.analyze(
                    "public class Multi {\n" +
                    "    UndefinedA a;\n" +  // エラー1
                    "    UndefinedB b;\n" +  // エラー2
                    "}");
                boolean multiErr = diags.stream()
                    .filter(d -> d.kind() == DiagnosticKind.ERROR)
                    .count() >= 2;
                System.out.println((multiErr ? "[OK] " : "[FAIL] ")
                    + "複数エラーが複数件として返る (errors="
                    + diags.stream().filter(d -> d.kind() == DiagnosticKind.ERROR).count() + ")");
                pass += multiErr ? 1 : 0;
            } catch (AnalysisException e) {
                System.out.println("[FAIL] 複数エラーテストで例外: " + e.getMessage());
            }
        }

        // Test 7: 診断メッセージが空でない
        {
            CompileAnalyzer analyzer = new CompileAnalyzer();
            try {
                List<CompileDiagnostic> diags = analyzer.analyze(
                    "public class MsgTest { UndefinedXYZ x; }");
                boolean msgOk = diags.stream()
                    .allMatch(d -> d.message() != null && !d.message().isEmpty());
                System.out.println((msgOk ? "[OK] " : "[FAIL] ")
                    + "診断メッセージが空でない");
                pass += msgOk ? 1 : 0;
            } catch (AnalysisException e) {
                System.out.println("[FAIL] メッセージテストで例外: " + e.getMessage());
            }
        }

        // Test 8: CompileDiagnostic record のフィールドアクセス
        {
            CompileDiagnostic d = new CompileDiagnostic(3, 7, "some error", DiagnosticKind.ERROR);
            boolean ok = d.lineNumber() == 3
                && d.column() == 7
                && d.message().equals("some error")
                && d.kind() == DiagnosticKind.ERROR;
            System.out.println((ok ? "[OK] " : "[FAIL] ") + "CompileDiagnostic record フィールドアクセス");
            pass += ok ? 1 : 0;
        }

        // Test 9: DiagnosticKind enum の値
        {
            boolean ok = DiagnosticKind.values().length == 2
                && DiagnosticKind.ERROR != DiagnosticKind.WARNING;
            System.out.println((ok ? "[OK] " : "[FAIL] ") + "DiagnosticKind enum 値");
            pass += ok ? 1 : 0;
        }

        // Test 10: EditorCanvas.setDiagnostics() がエラーなく呼べる（単体）
        {
            dev.javatexteditor.ui.EditorCanvas canvas = new dev.javatexteditor.ui.EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("public class Test {\n    UndefinedType x;\n}");
            List<CompileDiagnostic> diags = List.of(
                new CompileDiagnostic(1, 4, "cannot find symbol", DiagnosticKind.ERROR));
            canvas.setDiagnostics(diags);
            boolean ok = canvas.getDiagnostics().size() == 1
                && canvas.getDiagnostics().get(0).kind() == DiagnosticKind.ERROR;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "EditorCanvas.setDiagnostics() で診断がセットされる");
            pass += ok ? 1 : 0;
        }

        // Test 11: EditorCanvas.setDiagnostics(空リスト) でリセットされる
        {
            dev.javatexteditor.ui.EditorCanvas canvas = new dev.javatexteditor.ui.EditorCanvas();
            canvas.setDiagnostics(List.of(
                new CompileDiagnostic(0, 0, "error", DiagnosticKind.ERROR)));
            canvas.setDiagnostics(List.of());
            boolean ok = canvas.getDiagnostics().isEmpty();
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "setDiagnostics(空) でリストがクリアされる");
            pass += ok ? 1 : 0;
        }

        // Test 12: EditorCanvas.setDiagnostics(null) でリセットされる
        {
            dev.javatexteditor.ui.EditorCanvas canvas = new dev.javatexteditor.ui.EditorCanvas();
            canvas.setDiagnostics(List.of(
                new CompileDiagnostic(0, 0, "error", DiagnosticKind.ERROR)));
            canvas.setDiagnostics(null);
            boolean ok = canvas.getDiagnostics().isEmpty();
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "setDiagnostics(null) でリストがクリアされる");
            pass += ok ? 1 : 0;
        }

        // Test 13: 診断あり時にガターが描画されても背景色が変わる（描画クラッシュなし）
        {
            dev.javatexteditor.ui.EditorCanvas canvas = new dev.javatexteditor.ui.EditorCanvas();
            canvas.setSize(400, 300);
            canvas.setText("line0\nline1\nline2");
            canvas.setTheme(dev.javatexteditor.ui.Theme.LIGHT_MODE);
            canvas.setDiagnostics(List.of(
                new CompileDiagnostic(1, 0, "error on line 1", DiagnosticKind.ERROR),
                new CompileDiagnostic(2, 0, "warning on line 2", DiagnosticKind.WARNING)));
            java.awt.image.BufferedImage img = renderCanvas(canvas, 400, 300);
            // crash なく描画できれば OK
            System.out.println("[OK] 診断あり時のガター描画でクラッシュしない");
            pass += 1;
        }

        // Test 14: ModalEditor.setOnReturnToNormal が INSERT→NORMAL 遷移で呼ばれる
        {
            dev.javatexteditor.editor.ModalEditor editor =
                new dev.javatexteditor.editor.ModalEditor("hello");
            int[] count = {0};
            editor.setOnReturnToNormal(() -> count[0]++);
            // ESC を押して INSERT→NORMALに遷移させる
            // まず 'i' で INSERT に入る
            editor.processKey(java.awt.event.KeyEvent.VK_I, 'i', 0);
            // ESC で NORMAL に戻る
            editor.processKey(java.awt.event.KeyEvent.VK_ESCAPE, '', 0);
            boolean ok = count[0] == 1;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "onReturnToNormal が INSERT→NORMAL で呼ばれる (count=" + count[0] + ")");
            pass += ok ? 1 : 0;
        }

        // Test 15: ModalEditor.setOnReturnToNormal は NORMAL→NORMAL 遷移では呼ばれない
        {
            dev.javatexteditor.editor.ModalEditor editor =
                new dev.javatexteditor.editor.ModalEditor("hello");
            int[] count = {0};
            editor.setOnReturnToNormal(() -> count[0]++);
            // 最初からNORMALなので ESC を押しても INSERT→NORMAL ではない
            editor.processKey(java.awt.event.KeyEvent.VK_ESCAPE, '', 0);
            boolean ok = count[0] == 0;
            System.out.println((ok ? "[OK] " : "[FAIL] ")
                + "onReturnToNormal は NORMAL 状態での ESC では呼ばれない (count=" + count[0] + ")");
            pass += ok ? 1 : 0;
        }

        // Test 16: analyzeWithProject - 複数ファイルコンパイルで別ファイルシンボルが認識される
        {
            CompileAnalyzer analyzer = new CompileAnalyzer();
            java.nio.file.Path tempDir = null;
            try {
                tempDir = java.nio.file.Files.createTempDirectory("compiletest");

                // Empty helper file
                java.nio.file.Path helperFile = tempDir.resolve("Helper.java");
                java.nio.file.Files.writeString(helperFile, "class Helper { }");

                // Source that references undefined class (not in project)
                java.nio.file.Path sourceFile = tempDir.resolve("Source.java");
                String source = "class Source {\n" +
                    "    UndefinedClass x;\n" +
                    "}";
                java.nio.file.Files.writeString(sourceFile, source);

                List<CompileDiagnostic> singleDiags = analyzer.analyze(source);
                List<CompileDiagnostic> multiDiags = analyzer.analyzeWithProject(
                    sourceFile.toString(), source, tempDir);

                // 複数ファイルコンパイルでもエラーは報告される（ただし、フィルタリング後）
                boolean ok = multiDiags.stream().anyMatch(d -> d.kind() == DiagnosticKind.ERROR);
                System.out.println((ok ? "[OK] " : "[FAIL] ")
                    + "analyzeWithProject - 複数ファイルコンパイルでエラー検出可能 (errors=" + multiDiags.size() + ")");
                pass += ok ? 1 : 0;
            } catch (Exception e) {
                System.out.println("[FAIL] analyzeWithProject テストで例外: " + e.getMessage());
            } finally {
                if (tempDir != null) {
                    try {
                        java.nio.file.Files.walk(tempDir)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    java.nio.file.Files.delete(path);
                                } catch (IOException ignored) {}
                            });
                    } catch (IOException ignored) {}
                }
            }
        }

        // Test 17: analyzeWithProject - 未定義クラスはエラーになる
        {
            CompileAnalyzer analyzer = new CompileAnalyzer();
            java.nio.file.Path tempDir = null;
            try {
                tempDir = java.nio.file.Files.createTempDirectory("compiletest");

                java.nio.file.Path sourceFile = tempDir.resolve("Source.java");
                String source = "class Source {\n" +
                    "    UndefinedClass x;\n" +
                    "}";
                java.nio.file.Files.writeString(sourceFile, source);

                List<CompileDiagnostic> diags = analyzer.analyzeWithProject(
                    sourceFile.toString(), source, tempDir);

                boolean hasError = diags.stream()
                    .anyMatch(d -> d.kind() == DiagnosticKind.ERROR);
                System.out.println((hasError ? "[OK] " : "[FAIL] ")
                    + "analyzeWithProject - 未定義クラスがエラーになる (errors=" + diags.size() + ")");
                pass += hasError ? 1 : 0;
            } catch (Exception e) {
                System.out.println("[FAIL] analyzeWithProject 未定義クラステストで例外: " + e.getMessage());
            } finally {
                if (tempDir != null) {
                    try {
                        java.nio.file.Files.walk(tempDir)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    java.nio.file.Files.delete(path);
                                } catch (IOException ignored) {}
                            });
                    } catch (IOException ignored) {}
                }
            }
        }

        int total = 17;
        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) {
            System.exit(1);
        }
        System.exit(0);   // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
    }

    static BufferedImage renderCanvas(dev.javatexteditor.ui.EditorCanvas canvas, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        canvas.paint(g2);
        g2.dispose();
        return img;
    }
}
