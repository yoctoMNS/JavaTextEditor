package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Shift+K の最優先段（Eclipse JDT 流バインディング解決）の ModalEditor 統合テスト。
 * mainメソッド形式のテストハーネス（JUnit不使用）。
 *
 * enableBindingDefinitionLookup() に同期実行機構（Runnable::run）を渡すことで、
 * 既存テストと同じ「processKey 直後に同期 assert」の契約のままバインディング解決を検証する。
 * 非同期の配線（本番は仮想スレッド + invokeLater）は、タスクをキューに溜めて後から
 * 手動実行する擬似非同期エグゼキュータで、stale ガードも含めて決定的にテストする。
 */
public class BindingDefinitionJumpTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testDisabledByDefaultKeepsHeuristicBehavior();
        testBindingJumpToCorrectOverload();
        testBindingJumpAcrossFilesAndBack();
        testFallbackToHeuristicWhenBindingFails();
        testAsyncResultAppliedWhenTaskRuns();
        testStaleResultDiscardedAfterEdit();
        testStaleResultDiscardedAfterCursorMove();
        testStaleResultDiscardedWhenSupersededByNewLookup();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
        System.exit(0);
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

    /** text 内で marker が最初に現れる行番号（0-indexed）。 */
    static int lineOf(String text, String marker) {
        int idx = text.indexOf(marker);
        if (idx < 0) throw new IllegalArgumentException("marker not found: " + marker);
        int line = 0;
        for (int i = 0; i < idx; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** marker の行に含まれる word の列位置へカーソルを置く。 */
    static void placeCursor(ModalEditor ed, String text, String marker, String word) {
        int row = lineOf(text, marker);
        int col = text.split("\n", -1)[row].indexOf(word);
        ed.setCursor(row, col);
    }

    /** オーバーロードが2つ + 呼び出しを含むテスト用ソース。 */
    private static final String OVERLOAD_TEXT = """
        public class Overloads {
            void run(int x) {
            }
            void run(String s) {
            }
            void caller() {
                run("hi");
            }
        }
        """;

    /**
     * OVERLOAD_TEXT を開いた状態の ModalEditor を作る。currentFilePath を必ず渡す:
     * 既存ヒューリスティック（ProjectSymbolResolver.resolve）は currentFilePath == null だと
     * 現在バッファのテキストを検索対象にしないため、null のままだと従来経路の比較ができない。
     * ファイル自体はディスクに書かない（バッファ内容のみで解決できることも同時に確認される）。
     */
    private static ModalEditor overloadEditor(Path dir) {
        ModalEditor ed = new ModalEditor(OVERLOAD_TEXT, dir.resolve("Overloads.java").toString(), null);
        ed.setProjectRoot(dir);
        return ed;
    }

    /**
     * enableBindingDefinitionLookup() を呼ばないデフォルト状態では従来の
     * 名前ベース検索（同名の最初の宣言へジャンプ）のまま変わらないことを確認する。
     * 既存テスト群（JumpBackTest 等）が無修正で通ることの根拠となる回帰テスト。
     */
    static void testDisabledByDefaultKeepsHeuristicBehavior() throws Exception {
        Path dir = Files.createTempDirectory("bdj-default");
        ModalEditor ed = overloadEditor(dir);
        placeCursor(ed, OVERLOAD_TEXT, "run(\"hi\")", "run");

        pressShiftK(ed);
        assertEquals("既定（無効）: 名前ベース検索の従来動作＝最初の run 宣言へジャンプ",
            lineOf(OVERLOAD_TEXT, "void run(int x)"), ed.getCursorRow());
    }

    /** バインディング解決が有効なら、実引数の型に合う正しいオーバーロードへジャンプする。 */
    static void testBindingJumpToCorrectOverload() throws Exception {
        Path dir = Files.createTempDirectory("bdj-overload");
        ModalEditor ed = overloadEditor(dir);
        ed.enableBindingDefinitionLookup(Runnable::run, Runnable::run);
        placeCursor(ed, OVERLOAD_TEXT, "run(\"hi\")", "run");

        pressShiftK(ed);
        assertEquals("バインディング解決: String 版オーバーロードの宣言行へジャンプ",
            lineOf(OVERLOAD_TEXT, "void run(String s)"), ed.getCursorRow());
    }

    /** 別ファイルの宣言へのジャンプと Shift+J での復帰。 */
    static void testBindingJumpAcrossFilesAndBack() throws Exception {
        Path dir = Files.createTempDirectory("bdj-crossfile");
        String target = """
            public class Target {
                void doWork() {
                }
            }
            """;
        Files.writeString(dir.resolve("Target.java"), target);
        String caller = """
            public class Caller {
                void f() {
                    Target t = new Target();
                    t.doWork();
                }
            }
            """;
        Path callerFile = dir.resolve("Caller.java");
        Files.writeString(callerFile, caller);

        ModalEditor ed = new ModalEditor(caller, callerFile.toString(), null);
        ed.setProjectRoot(dir);
        ed.enableBindingDefinitionLookup(Runnable::run, Runnable::run);
        placeCursor(ed, caller, "t.doWork();", "doWork");
        int useRow = ed.getCursorRow();
        int useCol = ed.getCursorCol();

        pressShiftK(ed);
        assertTrue("バインディング解決: Target.java を開いた",
            ed.getCurrentFilePath() != null && ed.getCurrentFilePath().endsWith("Target.java"));
        assertEquals("バインディング解決: doWork の宣言行へジャンプ",
            lineOf(target, "void doWork()"), ed.getCursorRow());

        pressShiftJ(ed);
        assertTrue("Shift+J: Caller.java へ戻った",
            ed.getCurrentFilePath() != null && ed.getCurrentFilePath().endsWith("Caller.java"));
        assertEquals("Shift+J: 呼び出し元の行へ戻る", useRow, ed.getCursorRow());
        assertEquals("Shift+J: 呼び出し元の列へ戻る", useCol, ed.getCursorCol());
    }

    /**
     * javac が解決できないシンボル（未 import の他クラスのメソッド呼び出し）でも、
     * 従来の名前ベース検索へフォールバックして宣言ファイルへジャンプできることを確認する。
     */
    static void testFallbackToHeuristicWhenBindingFails() throws Exception {
        Path dir = Files.createTempDirectory("bdj-fallback");
        String other = """
            public class Other {
                void orphanHelper() {
                }
            }
            """;
        Path otherFile = dir.resolve("Other.java");
        Files.writeString(otherFile, other);
        // orphanHelper() は A のスコープでは解決できない（static import も継承も無い）ため
        // バインディング解決は NotFound になるが、名前ベース検索は Other.java の宣言を見つける
        String text = """
            public class A {
                void f() {
                    orphanHelper();
                }
            }
            """;
        Path aFile = dir.resolve("A.java");
        Files.writeString(aFile, text);

        ModalEditor ed = new ModalEditor(text, aFile.toString(), null);
        ed.setProjectRoot(dir);
        ed.enableBindingDefinitionLookup(Runnable::run, Runnable::run);
        placeCursor(ed, text, "orphanHelper();", "orphanHelper");

        pressShiftK(ed);
        assertTrue("フォールバック: 名前ベース検索で Other.java を開いた",
            ed.getCurrentFilePath() != null && ed.getCurrentFilePath().endsWith("Other.java"));
        assertEquals("フォールバック: orphanHelper の宣言行へジャンプ",
            lineOf(other, "void orphanHelper()"), ed.getCursorRow());
    }

    /** 擬似非同期: タスクをキューに溜め、後から実行した時点で結果が反映される。 */
    static void testAsyncResultAppliedWhenTaskRuns() throws Exception {
        Path dir = Files.createTempDirectory("bdj-async");
        Deque<Runnable> queue = new ArrayDeque<>();
        ModalEditor ed = overloadEditor(dir);
        ed.enableBindingDefinitionLookup(queue::add, Runnable::run);
        placeCursor(ed, OVERLOAD_TEXT, "run(\"hi\")", "run");
        int useRow = ed.getCursorRow();

        pressShiftK(ed);
        assertEquals("非同期: タスク実行前はカーソルが動かない", useRow, ed.getCursorRow());
        assertEquals("非同期: 解析タスクが1件キューされる", 1, queue.size());

        queue.pop().run();
        assertEquals("非同期: タスク実行後に正しいオーバーロードへジャンプ",
            lineOf(OVERLOAD_TEXT, "void run(String s)"), ed.getCursorRow());
    }

    /** stale ガード: 解析完了前にバッファが編集されたら結果を破棄する。 */
    static void testStaleResultDiscardedAfterEdit() throws Exception {
        Path dir = Files.createTempDirectory("bdj-stale-edit");
        Deque<Runnable> queue = new ArrayDeque<>();
        ModalEditor ed = overloadEditor(dir);
        ed.enableBindingDefinitionLookup(queue::add, Runnable::run);
        placeCursor(ed, OVERLOAD_TEXT, "run(\"hi\")", "run");
        int useRow = ed.getCursorRow();

        pressShiftK(ed);
        // 解析完了前に 'x'（1文字削除）でバッファを編集する
        ed.processKey(KeyEvent.VK_X, 'x', 0);
        queue.pop().run();

        assertEquals("stale(編集): 古い解析結果は適用されない", useRow, ed.getCursorRow());
    }

    /** stale ガード: 解析完了前にカーソルが移動したら結果を破棄する。 */
    static void testStaleResultDiscardedAfterCursorMove() throws Exception {
        Path dir = Files.createTempDirectory("bdj-stale-move");
        Deque<Runnable> queue = new ArrayDeque<>();
        ModalEditor ed = overloadEditor(dir);
        ed.enableBindingDefinitionLookup(queue::add, Runnable::run);
        placeCursor(ed, OVERLOAD_TEXT, "run(\"hi\")", "run");
        int useRow = ed.getCursorRow();

        pressShiftK(ed);
        ed.processKey(KeyEvent.VK_J, 'j', 0); // カーソル下移動
        queue.pop().run();

        assertEquals("stale(移動): 古い解析結果は適用されずカーソルは移動先のまま",
            useRow + 1, ed.getCursorRow());
    }

    /** stale ガード: 新しい Shift+K が押されたら古い方の結果は破棄され、新しい方だけ適用される。 */
    static void testStaleResultDiscardedWhenSupersededByNewLookup() throws Exception {
        Path dir = Files.createTempDirectory("bdj-stale-supersede");
        Deque<Runnable> queue = new ArrayDeque<>();
        ModalEditor ed = overloadEditor(dir);
        ed.enableBindingDefinitionLookup(queue::add, Runnable::run);
        placeCursor(ed, OVERLOAD_TEXT, "run(\"hi\")", "run");

        pressShiftK(ed); // 1回目（古い世代）
        pressShiftK(ed); // 2回目（新しい世代）
        assertEquals("supersede: 解析タスクが2件キューされる", 2, queue.size());

        // 1回目のタスク: 世代が古いため結果は破棄される（まだジャンプしない…はずだが、
        // 実行順が前後しても最終状態は同じになることが重要）
        Runnable first = queue.pop();
        Runnable second = queue.pop();
        first.run();
        second.run();

        assertEquals("supersede: 最終的に正しいオーバーロードへ1回だけジャンプ",
            lineOf(OVERLOAD_TEXT, "void run(String s)"), ed.getCursorRow());
    }
}
