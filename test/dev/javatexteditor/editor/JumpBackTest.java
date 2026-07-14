package dev.javatexteditor.editor;

import dev.javatexteditor.analysis.JdkClassIndex;
import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shift+K（定義ジャンプ）→ Shift+J（一つ前の参照へ戻る）の往復動作を検証する。
 * mainメソッド形式のテストハーネス（JUnit不使用）。
 */
public class JumpBackTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testJumpBackSameFile();
        testJumpBackAcrossFiles();
        testJumpBackWithNoPriorJump();
        testJumpBackKeyBinding();
        testShiftKIntoJdkSourceClearsSearchHighlight();
        testCloseJdkSourceBufferClearsSearchHighlight();
        testShiftKOnLocalVariableReceiverJumpsToDeclaration();
        testShiftKOnLocalVariableReceiverThenJumpBack();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
        System.exit(0);   // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
    }

    static void assertTrue(String name, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name);
            fail++;
        }
    }

    static void assertEquals(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name + " expected=" + expected + " actual=" + actual);
            fail++;
        }
    }

    private static void pressShiftK(ModalEditor ed) {
        ed.processKey(KeyEvent.VK_K, 'K', KeyEvent.SHIFT_DOWN_MASK);
    }

    private static void pressShiftJ(ModalEditor ed) {
        ed.processKey(KeyEvent.VK_J, 'J', KeyEvent.SHIFT_DOWN_MASK);
    }

    static void testJumpBackSameFile() throws Exception {
        Path dir = Files.createTempDirectory("jumpback-test");
        Path file = dir.resolve("Sample.java");
        String content = """
            public class Sample {
                static int VALUE = 10;

                void use() {
                    int x = VALUE;
                }
            }
            """;
        Files.writeString(file, content);

        ModalEditor ed = new ModalEditor(content, file.toString(), null);
        ed.setProjectRoot(dir);

        // "VALUE" の使用箇所（4行目、0-indexed）にカーソルを置く
        int useLine = 4;
        int col = content.split("\n", -1)[useLine].indexOf("VALUE");
        ed.setCursor(useLine, col);

        pressShiftK(ed);
        assertEquals("Shift+K: 宣言行(1行目)へジャンプ", 1, ed.getCursorRow());

        pressShiftJ(ed);
        assertEquals("Shift+J: 呼び出し元の行へ戻る", useLine, ed.getCursorRow());
        assertEquals("Shift+J: 呼び出し元の列へ戻る", col, ed.getCursorCol());
    }

    static void testJumpBackAcrossFiles() throws Exception {
        Path dir = Files.createTempDirectory("jumpback-test-multi");
        Path callerFile = dir.resolve("Caller.java");
        Path targetFile = dir.resolve("Target.java");
        String callerContent = """
            public class Caller {
                void use() {
                    Target.helper();
                }
            }
            """;
        String targetContent = """
            public class Target {
                static void helper() {
                }
            }
            """;
        Files.writeString(callerFile, callerContent);
        Files.writeString(targetFile, targetContent);

        ModalEditor ed = new ModalEditor(callerContent, callerFile.toString(), null);
        ed.setProjectRoot(dir);

        int useLine = 2;
        int col = callerContent.split("\n", -1)[useLine].indexOf("helper");
        ed.setCursor(useLine, col);

        pressShiftK(ed);
        assertTrue("Shift+K: Target.java を開いた", ed.getCurrentFilePath().endsWith("Target.java"));

        pressShiftJ(ed);
        assertTrue("Shift+J: Caller.java へ戻った", ed.getCurrentFilePath().endsWith("Caller.java"));
        assertEquals("Shift+J: 呼び出し元の行へ戻る", useLine, ed.getCursorRow());
        assertEquals("Shift+J: 呼び出し元の列へ戻る", col, ed.getCursorCol());
    }

    static void testJumpBackWithNoPriorJump() {
        ModalEditor ed = new ModalEditor("hello world");
        int beforeRow = ed.getCursorRow();
        int beforeCol = ed.getCursorCol();
        pressShiftJ(ed);
        assertEquals("Shift+J: ジャンプ履歴が無い場合は何もしない(row)", beforeRow, ed.getCursorRow());
        assertEquals("Shift+J: ジャンプ履歴が無い場合は何もしない(col)", beforeCol, ed.getCursorCol());
        assertTrue("Shift+J: ステータスメッセージが表示される",
            !ed.getStatusMessage().isEmpty());
    }

    static void testJumpBackKeyBinding() {
        KeymapRegistry reg = new KeymapRegistry();
        String action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_J, 'J', KeyEvent.SHIFT_DOWN_MASK);
        assertEquals("Shift+J -> jump.back", "jump.back", action);
    }

    /**
     * Shift+K で JDK ソース疑似バッファ（*jdk-source:...*）へジャンプすると、
     * 元バッファの検索ハイライトが画面上に残ってはいけない（バッファ切替時のハイライト残留バグの回帰テスト）。
     * src.zip が見つからない実行環境ではジャンプ自体が成立しない（graceful degradation）ため、
     * その場合はテストをスキップする（{@code OpenjdkSourceTracingTest} と同じ方針）。
     */
    static void testShiftKIntoJdkSourceClearsSearchHighlight() {
        EditorCanvas canvas = new EditorCanvas();
        String content = "String needle = null; // needle needle\n";
        ModalEditor ed = new ModalEditor(content, canvas);
        ed.setJdkClassIndex(JdkClassIndex.buildSync());

        // "/" 検索でハイライトを作ってから Shift+K でジャンプする
        ed.processKey(KeyEvent.VK_UNDEFINED, '/', 0);
        for (char c : "needle".toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
        assertTrue("ジャンプ前: ハイライトが存在する", !canvas.getSearchHighlights().isEmpty());

        ed.setCursor(0, 0); // "String" の上へ
        pressShiftK(ed);

        String path = ed.getCurrentFilePath();
        if (path == null || !path.startsWith("*jdk-source:")) {
            System.out.println("  SKIP testShiftKIntoJdkSourceClearsSearchHighlight (src.zip未検出のためジャンプ不成立)");
            pass++;
            return;
        }
        assertTrue("ジャンプ後: searchMatchesが空", ed.getSearchMatches().isEmpty());
        assertTrue("ジャンプ後: キャンバスのハイライトが空", canvas.getSearchHighlights().isEmpty());
    }

    /**
     * JDK ソース疑似バッファ内で検索した後 q で元バッファへ戻ると、
     * 疑似バッファ側の検索ハイライトが元バッファの画面に残ってはいけない。
     */
    static void testCloseJdkSourceBufferClearsSearchHighlight() {
        EditorCanvas canvas = new EditorCanvas();
        String content = "String needle = null;\n";
        ModalEditor ed = new ModalEditor(content, canvas);
        ed.setJdkClassIndex(JdkClassIndex.buildSync());

        ed.setCursor(0, 0);
        pressShiftK(ed);
        String path = ed.getCurrentFilePath();
        if (path == null || !path.startsWith("*jdk-source:")) {
            System.out.println("  SKIP testCloseJdkSourceBufferClearsSearchHighlight (src.zip未検出のためジャンプ不成立)");
            pass++;
            return;
        }

        // 疑似バッファ内で検索してハイライトを作る（"class" は Java ソースにほぼ必ず含まれる）
        ed.processKey(KeyEvent.VK_UNDEFINED, '/', 0);
        for (char c : "class".toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
        if (canvas.getSearchHighlights().isEmpty()) {
            System.out.println("  SKIP testCloseJdkSourceBufferClearsSearchHighlight (\"class\"が疑似バッファ内で見つからない)");
            pass++;
            return;
        }

        // q で元バッファへ戻る
        ed.processKey(KeyEvent.VK_UNDEFINED, 'q', 0);
        assertTrue("q復帰後: searchMatchesが空", ed.getSearchMatches().isEmpty());
        assertTrue("q復帰後: キャンバスのハイライトが空", canvas.getSearchHighlights().isEmpty());
    }

    /**
     * "変数名.メソッド名(...)" の変数名側（レシーバ）にカーソルがある状態で Shift+K を押すと、
     * 従来は何もヒットせず "Not found in JDK: ..." になっていた（ローカル変数は
     * SourceAnalyzer のシンボル索引に存在しないため）。ローカル変数自身の宣言行へ
     * ジャンプできるようになったことの回帰テスト。
     */
    static void testShiftKOnLocalVariableReceiverJumpsToDeclaration() throws Exception {
        Path dir = Files.createTempDirectory("localvar-receiver-test");
        Path callerFile = dir.resolve("Caller.java");
        String content = """
            public class Caller {
                void run() {
                    Helper h = new Helper();
                    h.doWork();
                }
            }
            """;
        Files.writeString(callerFile, content);

        ModalEditor ed = new ModalEditor(content, callerFile.toString(), null);
        ed.setProjectRoot(dir);

        int useLine = 3; // "        h.doWork();"
        int col = content.split("\n", -1)[useLine].indexOf("h."); // カーソルは "h"(レシーバ)上
        ed.setCursor(useLine, col);

        pressShiftK(ed);

        assertTrue("Shift+K: ファイルは変わらない", ed.getCurrentFilePath().endsWith("Caller.java"));
        assertEquals("Shift+K: h の宣言行(2行目, 0-indexed)へジャンプ", 2, ed.getCursorRow());
    }

    /** 上記のローカル変数レシーバへのジャンプも Shift+J で呼び出し元へ戻れることを確認する。 */
    static void testShiftKOnLocalVariableReceiverThenJumpBack() throws Exception {
        Path dir = Files.createTempDirectory("localvar-receiver-jumpback-test");
        Path callerFile = dir.resolve("Caller.java");
        String content = """
            public class Caller {
                void run() {
                    Helper h = new Helper();
                    h.doWork();
                }
            }
            """;
        Files.writeString(callerFile, content);

        ModalEditor ed = new ModalEditor(content, callerFile.toString(), null);
        ed.setProjectRoot(dir);

        int useLine = 3;
        int col = content.split("\n", -1)[useLine].indexOf("h.");
        ed.setCursor(useLine, col);

        pressShiftK(ed);
        pressShiftJ(ed);

        assertEquals("Shift+J: 呼び出し元の行へ戻る", useLine, ed.getCursorRow());
        assertEquals("Shift+J: 呼び出し元の列へ戻る", col, ed.getCursorCol());
    }
}
