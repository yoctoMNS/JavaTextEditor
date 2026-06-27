package dev.vimacs.ui;

import dev.vimacs.analysis.AutoImportHandler;
import dev.vimacs.analysis.CompileDiagnostic;
import dev.vimacs.analysis.DiagnosticKind;
import dev.vimacs.analysis.ImportSuggester;
import dev.vimacs.analysis.JdkClassIndex;
import dev.vimacs.analysis.SourceAnalyzer;
import dev.vimacs.editor.ModalEditor;
import java.awt.KeyboardFocusManager;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * java.awt.Robot を使い、OS レベルのキーイベントを生成してエディタの動作を検証する。
 *
 * 実行条件: DISPLAY 環境変数が設定されていること（Xvfb 等の仮想ディスプレイ可）。
 * テスト対象: Main.java と同じ KeyboardFocusManager ディスパッチャ経由でキーを処理する。
 *
 * 特に検証するバグ:
 *   - ':' (Shift+;) でCOMMANDモードに入れない
 *   - 'V' (Shift+v) でVISUAL LINEモードに入れない
 *   - 'P' (Shift+p) でカーソル前ペーストができない
 */
public class RobotKeyInputTest {

    private static Robot robot;
    private static ModalEditor editor;
    private static JFrame frame;
    private static java.awt.KeyEventDispatcher activeDispatcher;

    private static int pass = 0;
    private static int total = 0;

    private static final int KEY_DELAY_MS = 40;   // キー間の待機
    private static final int SETTLE_MS    = 80;   // イベント処理待機

    // =========================================================================
    // メインエントリ
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== RobotKeyInputTest: 実キーイベント統合テスト ===");
        System.out.println("DISPLAY=" + System.getenv("DISPLAY"));

        try {
            robot = new Robot();
            robot.setAutoDelay(KEY_DELAY_MS);
            // setAutoWaitForIdle は使用しない（repaintイベントで無限ブロックする可能性があるため）
        } catch (Exception e) {
            System.out.println("[SKIP] Robot 初期化失敗: " + e.getMessage());
            System.out.println("       DISPLAY が設定されていないか仮想ディスプレイが起動していません。");
            System.out.println("       KeyboardSimulationTest で代替検証を実施してください。");
            System.exit(0);
        }

        setupFrame();

        testNormalCursorMovement();
        testNormalInsertMode();
        testNormalCommandMode();       // ':' Shift バグ修正確認
        testNormalVisualLineMode();    // 'V' Shift バグ修正確認
        testNormalVisualMode();
        testNormalYankDelete();
        testNormalPasteBefore();       // 'P' Shift バグ修正確認
        testNormalUndoRedo();
        testInsertModeTyping();
        testInsertModeBackspace();
        testInsertModeEnterEscape();
        testInsertModeCtrlMovement();
        testCommandModeInteraction();
        testVisualModeYankDelete();
        testVisualLineModeYankDelete();
        testJdkDocLookup();             // Shift+K jdk.doc 動作確認
        testAutoImportSingleCandidate();  // auto-import: 候補1件自動挿入
        testAutoImportMultipleSelection(); // auto-import: 複数候補を数字キーで選択
        testAutoImportEscapeSkip();       // auto-import: Esc でスキップ
        testAutoImportNoDiagnostics();    // auto-import: エラーなしで何もしない

        teardown();

        int fail = total - pass;
        System.out.println("\n---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        System.exit(fail > 0 ? 1 : 0);  // AWT スレッドが残るため明示的に終了
    }

    // =========================================================================
    // セットアップ
    // =========================================================================

    static void setupFrame() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            frame = new JFrame("RobotKeyInputTest");
            frame.setSize(800, 600);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(new JPanel());
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        Thread.sleep(300);
        resetEditorTo("hello world\nline2\nline3");
    }

    static void teardown() throws Exception {
        if (activeDispatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(activeDispatcher);
        }
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> { frame.dispose(); latch.countDown(); });
        latch.await(3, TimeUnit.SECONDS);
    }

    static void resetEditorTo(String text) throws Exception {
        if (activeDispatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(activeDispatcher);
        }
        editor = new ModalEditor(text);
        editor.setExitCallback(() -> {});

        activeDispatcher = e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED) return false;
            editor.processKey(e.getKeyCode(), e.getKeyChar(), e.getModifiersEx());
            return true;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(activeDispatcher);
        Thread.sleep(SETTLE_MS);
    }

    // =========================================================================
    // テスト
    // =========================================================================

    static void testNormalCursorMovement() throws Exception {
        resetEditorTo("abc\ndefg\nhij");
        System.out.println("\n--- NORMALモード: カーソル移動 ---");
        check("初期 row==0", 0, editor.getCursorRow());
        check("初期 col==0", 0, editor.getCursorCol());
        pressChar('l'); check("l: col==1", 1, editor.getCursorCol());
        pressChar('l'); check("ll: col==2", 2, editor.getCursorCol());
        pressChar('h'); check("h: col==1", 1, editor.getCursorCol());
        pressChar('j'); check("j: row==1", 1, editor.getCursorRow());
        pressChar('k'); check("k: row==0", 0, editor.getCursorRow());
        // 末端クランプ
        pressChar('l'); pressChar('l'); pressChar('l'); pressChar('l');
        check("末端右クランプ col==2", 2, editor.getCursorCol());
        pressChar('k'); // 先頭から上
        check("先頭行 k: row==0", 0, editor.getCursorRow());
    }

    static void testNormalInsertMode() throws Exception {
        resetEditorTo("test");
        System.out.println("\n--- NORMALモード → INSERT (i/a/o) ---");
        pressChar('i');
        check("i: INSERT", true, editor.isInsertMode());
        pressChar('X');
        check("i+X: Xtest", "Xtest", editor.getText());
        pressEscape();
        check("ESC: NORMAL", true, editor.isNormalMode());

        // 'a' でカーソル後に挿入
        resetEditorTo("AB");
        pressChar('a');
        check("a: INSERT", true, editor.isInsertMode());
        check("a: col==1", 1, editor.getCursorCol());
        pressEscape();

        // 'o' で下に新行
        resetEditorTo("line1\nline2");
        pressChar('o');
        check("o: INSERT", true, editor.isInsertMode());
        check("o: row==1", 1, editor.getCursorRow());
        pressChar('N'); pressChar('E'); pressChar('W');
        pressEscape();
        check("o+NEW", "line1\nNEW\nline2", editor.getText());
    }

    static void testNormalCommandMode() throws Exception {
        resetEditorTo("test");
        System.out.println("\n--- COMMANDモード: ':' (Shift+; → SHIFT バグ修正) ---");
        check("初期 NORMAL", true, editor.isNormalMode());
        pressShiftKey(KeyEvent.VK_SEMICOLON); // Shift+; = ':'
        check("':' (Shift+;): COMMAND モードへ", true, editor.isCommandMode());
        pressEscape();
        check("ESC: NORMAL", true, editor.isNormalMode());
    }

    static void testNormalVisualLineMode() throws Exception {
        resetEditorTo("line1\nline2\nline3");
        System.out.println("\n--- VISUAL LINEモード: 'V' (Shift+v → SHIFT バグ修正) ---");
        pressShiftKey(KeyEvent.VK_V); // Shift+V
        check("'V' (Shift+v): VISUAL LINE モードへ", true, editor.isVisualLineMode());
        pressEscape();
        check("ESC: NORMAL", true, editor.isNormalMode());
    }

    static void testNormalVisualMode() throws Exception {
        resetEditorTo("hello");
        System.out.println("\n--- VISUALモード: 'v' ---");
        pressChar('v');
        check("v: VISUAL モードへ", true, editor.isVisualMode());
        pressChar('l'); check("VISUAL l: col==1", 1, editor.getCursorCol());
        pressChar('l'); check("VISUAL l: col==2", 2, editor.getCursorCol());
        pressEscape();
        check("ESC: NORMAL", true, editor.isNormalMode());
    }

    static void testNormalYankDelete() throws Exception {
        resetEditorTo("line1\nline2\nline3");
        System.out.println("\n--- NORMALモード: yy / dd / x ---");
        // yy
        pressChar('y'); pressChar('y');
        check("yy: yankType==line", "line", editor.getYankType());
        check("yy: yankRegister==line1\\n", "line1\n", editor.getYankRegister());
        // dd
        pressChar('j'); // row=1
        pressChar('d'); pressChar('d');
        check("dd: テキスト", "line1\nline3", editor.getText());
        check("dd: yankRegister==line2\\n", "line2\n", editor.getYankRegister());
        // x
        resetEditorTo("abcde");
        pressChar('l'); pressChar('l'); // col=2
        pressChar('x');
        check("x: abde", "abde", editor.getText());
    }

    static void testNormalPasteBefore() throws Exception {
        resetEditorTo("ABCDE");
        System.out.println("\n--- NORMALモード: 'P' (Shift+p → SHIFT バグ修正) ---");
        pressChar('l'); pressChar('l'); // col=2 ('C')
        pressChar('v'); pressChar('l');  // VISUAL select 'CD'
        pressChar('y');                  // yank "CD"
        // 'P' で前に貼り付け
        pressShiftKey(KeyEvent.VK_P);    // Shift+p = 'P'
        check("P (Shift+p)後テキスト: ABCDCDE", "ABCDCDE", editor.getText());
    }

    static void testNormalUndoRedo() throws Exception {
        resetEditorTo("hello");
        System.out.println("\n--- NORMALモード: u / Ctrl+R ---");
        pressChar('i'); pressChar('X'); pressEscape(); // "Xhello"
        check("挿入後: Xhello", "Xhello", editor.getText());
        pressChar('u');
        check("u: hello", "hello", editor.getText());
        pressCtrl(KeyEvent.VK_R);
        check("Ctrl+R: Xhello", "Xhello", editor.getText());
    }

    static void testInsertModeTyping() throws Exception {
        resetEditorTo("");
        System.out.println("\n--- INSERTモード: 文字入力 ---");
        pressChar('i');
        pressChar('h'); pressChar('e'); pressChar('l'); pressChar('l'); pressChar('o');
        check("typing: hello", "hello", editor.getText());
        check("col==5", 5, editor.getCursorCol());
        pressEscape();
    }

    static void testInsertModeBackspace() throws Exception {
        resetEditorTo("abc");
        System.out.println("\n--- INSERTモード: Backspace ---");
        pressChar('a'); // col=1
        pressChar('X'); // "aXbc"
        check("挿入後: aXbc", "aXbc", editor.getText());
        pressBackspace();
        check("Backspace後: abc", "abc", editor.getText());
        pressEscape();

        // 行頭 Backspace → 行結合
        resetEditorTo("foo\nbar");
        pressChar('j'); pressChar('i'); // 2行目先頭 INSERT
        pressBackspace();
        check("行頭 Backspace: foobar", "foobar", editor.getText());
        check("結合後 row==0", 0, editor.getCursorRow());
        check("結合後 col==3", 3, editor.getCursorCol());
        pressEscape();
    }

    static void testInsertModeEnterEscape() throws Exception {
        resetEditorTo("helloworld");
        System.out.println("\n--- INSERTモード: Enter / Escape ---");
        pressChar('i');
        pressCtrl(KeyEvent.VK_F); pressCtrl(KeyEvent.VK_F);
        pressCtrl(KeyEvent.VK_F); pressCtrl(KeyEvent.VK_F); pressCtrl(KeyEvent.VK_F);
        pressEnter();
        check("Enter後: hello\\nworld", "hello\nworld", editor.getText());
        check("Enter後 row==1", 1, editor.getCursorRow());
        pressEscape();
        check("ESC: NORMAL", true, editor.isNormalMode());
    }

    static void testInsertModeCtrlMovement() throws Exception {
        resetEditorTo("abc\ndef");
        System.out.println("\n--- INSERTモード: Ctrl+F/B/N/P ---");
        pressChar('i');
        pressCtrl(KeyEvent.VK_F); check("Ctrl+F: col==1", 1, editor.getCursorCol());
        pressCtrl(KeyEvent.VK_B); check("Ctrl+B: col==0", 0, editor.getCursorCol());
        pressCtrl(KeyEvent.VK_N); check("Ctrl+N: row==1", 1, editor.getCursorRow());
        pressCtrl(KeyEvent.VK_P); check("Ctrl+P: row==0", 0, editor.getCursorRow());
        pressEscape();
    }

    static void testCommandModeInteraction() throws Exception {
        resetEditorTo("test text");
        System.out.println("\n--- COMMANDモード: 文字入力 / Backspace / Escape ---");
        pressShiftKey(KeyEvent.VK_SEMICOLON); // ':'
        pressChar('w'); pressChar('q');
        check("commandBuffer: wq", "wq", editor.getCommandBuffer());
        pressBackspace();
        check("Backspace後: w", "w", editor.getCommandBuffer());
        pressEscape();
        check("ESC: NORMAL", true, editor.isNormalMode());
        check("ESC: commandBuffer クリア", "", editor.getCommandBuffer());

        // 未知コマンドのエラーメッセージ
        pressShiftKey(KeyEvent.VK_SEMICOLON);
        pressChar('z'); pressChar('z'); pressChar('z');
        pressEnter();
        check("未知コマンド後 NORMAL", true, editor.isNormalMode());
        check("未知コマンド statusMessage", true,
            editor.getStatusMessage().startsWith("E:"));
    }

    static void testVisualModeYankDelete() throws Exception {
        resetEditorTo("abcde");
        System.out.println("\n--- VISUALモード: y / d ---");
        pressChar('v'); pressChar('l'); pressChar('l'); // select "abc"
        pressChar('y');
        check("y後 NORMAL", true, editor.isNormalMode());
        check("yankRegister==abc", "abc", editor.getYankRegister());
        check("テキスト変化なし", "abcde", editor.getText());

        resetEditorTo("abcde");
        pressChar('v'); pressChar('l'); pressChar('l');
        pressChar('d');
        check("d後 NORMAL", true, editor.isNormalMode());
        check("d後テキスト==de", "de", editor.getText());
    }

    static void testVisualLineModeYankDelete() throws Exception {
        resetEditorTo("line1\nline2\nline3");
        System.out.println("\n--- VISUAL LINEモード: y / d ---");
        pressShiftKey(KeyEvent.VK_V);
        pressChar('j'); // select row 0-1
        pressChar('y');
        check("y後 NORMAL", true, editor.isNormalMode());
        check("yankType==line", "line", editor.getYankType());
        check("yankRegister==line1\\nline2\\n", "line1\nline2\n", editor.getYankRegister());

        resetEditorTo("line1\nline2\nline3");
        pressChar('j');
        pressShiftKey(KeyEvent.VK_V);
        pressChar('d');
        check("d後 NORMAL", true, editor.isNormalMode());
        check("d後テキスト", "line1\nline3", editor.getText());
    }

    // =========================================================================
    // キー操作ヘルパー
    // =========================================================================

    static void pressChar(char c) throws Exception {
        int vk;
        if (c >= 'a' && c <= 'z')       vk = KeyEvent.VK_A + (c - 'a');
        else if (c >= 'A' && c <= 'Z') { pressShiftKey(KeyEvent.VK_A + (c - 'A')); return; }
        else if (c >= '0' && c <= '9')   vk = KeyEvent.VK_0 + (c - '0');
        else if (c == ' ')               vk = KeyEvent.VK_SPACE;
        else if (c == '/')               vk = KeyEvent.VK_SLASH;
        else if (c == '.')               vk = KeyEvent.VK_PERIOD;
        else { System.err.println("[WARN] pressChar: unsupported '" + c + "'"); return; }
        robot.keyPress(vk);
        robot.keyRelease(vk);
        Thread.sleep(KEY_DELAY_MS);
    }

    /** Shift+keyCode を押す (例: VK_SEMICOLON→':', VK_V→'V', VK_P→'P') */
    static void pressShiftKey(int keyCode) throws Exception {
        robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
        robot.keyRelease(KeyEvent.VK_SHIFT);
        Thread.sleep(KEY_DELAY_MS);
    }

    static void pressEscape() throws Exception {
        robot.keyPress(KeyEvent.VK_ESCAPE);
        robot.keyRelease(KeyEvent.VK_ESCAPE);
        Thread.sleep(SETTLE_MS);
    }

    static void pressEnter() throws Exception {
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
        Thread.sleep(KEY_DELAY_MS);
    }

    static void pressBackspace() throws Exception {
        robot.keyPress(KeyEvent.VK_BACK_SPACE);
        robot.keyRelease(KeyEvent.VK_BACK_SPACE);
        Thread.sleep(KEY_DELAY_MS);
    }

    static void pressCtrl(int keyCode) throws Exception {
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        Thread.sleep(KEY_DELAY_MS);
    }

    /**
     * Shift+K (jdk.doc): カーソル位置の識別子を JDK クラスとして検索しステータスメッセージをセット。
     * JdkClassIndex はバックグラウンド構築なので、isReady() 前後の両ケースを確認する。
     */
    static void testJdkDocLookup() throws Exception {
        System.out.println("\n--- Shift+K: JDK APIナビゲーション ---");

        // (1) jdkIndex 未設定の状態: "JDK index building..." が返る
        resetEditorTo("String");
        Thread.sleep(SETTLE_MS);
        pressShiftKey(KeyEvent.VK_K);
        Thread.sleep(SETTLE_MS);
        String msg1 = editor.getStatusMessage();
        // jdkIndex が null か未完了 → "JDK index building..." / "Not found..." / 実際の情報 のいずれか
        check("Shift+K: ステータスが空でない", true, !msg1.isEmpty());

        // (2) JdkClassIndex をセットして同期的に構築し、"String" にカーソルを当てて検索
        JdkClassIndex idx = JdkClassIndex.buildSync();
        resetEditorTo("String");
        editor.setJdkClassIndex(idx);   // resetEditorTo の後にセット（新しい ModalEditor に対して）
        Thread.sleep(SETTLE_MS);
        pressShiftKey(KeyEvent.VK_K);
        Thread.sleep(SETTLE_MS);
        String msg2 = editor.getStatusMessage();
        check("Shift+K: String が含まれる", true, msg2.contains("String"));
        check("Shift+K: class または interface が含まれる", true,
              msg2.contains("class") || msg2.contains("interface"));

        // (3) 非識別子（スペース）にカーソルがある場合
        resetEditorTo("  foo");
        editor.setJdkClassIndex(idx);   // resetEditorTo の後にセット
        Thread.sleep(SETTLE_MS);
        pressShiftKey(KeyEvent.VK_K); // cursor at col=0, char=' '
        Thread.sleep(SETTLE_MS);
        String msg3 = editor.getStatusMessage();
        check("Shift+K: 非識別子で 'No identifier' が含まれる", true,
              msg3.contains("No identifier") || msg3.contains("Not found"));

        // (4) JDK に存在しない識別子
        resetEditorTo("XyzNoSuchClass12345");
        editor.setJdkClassIndex(idx);   // resetEditorTo の後にセット
        Thread.sleep(SETTLE_MS);
        pressShiftKey(KeyEvent.VK_K);
        Thread.sleep(SETTLE_MS);
        String msg4 = editor.getStatusMessage();
        check("Shift+K: 未知クラスで 'Not found' が含まれる", true, msg4.contains("Not found"));

        // (5) Robot で実際に Shift+K を2回押しても安定して動くことの確認（バウンス・重複実行なし）
        resetEditorTo("List");
        editor.setJdkClassIndex(idx);   // resetEditorTo の後にセット
        Thread.sleep(SETTLE_MS);
        pressShiftKey(KeyEvent.VK_K);
        Thread.sleep(SETTLE_MS);
        String msg5a = editor.getStatusMessage();
        pressShiftKey(KeyEvent.VK_K);
        Thread.sleep(SETTLE_MS);
        String msg5b = editor.getStatusMessage();
        check("Shift+K 2回: 同じメッセージが返る", msg5a, msg5b);
    }

    // =========================================================================
    // auto-import テスト
    // =========================================================================

    /**
     * 候補が1件の場合: import が自動挿入され、ユーザー入力は不要。
     * java.util.ArrayList は JDK に1候補（java.util.ArrayList）のみ存在するはず。
     */
    static void testAutoImportSingleCandidate() throws Exception {
        System.out.println("\n--- auto-import: 候補1件 → 自動挿入 ---");
        JdkClassIndex idx = JdkClassIndex.buildSync();
        List<String> candidates = idx.lookup("ArrayList");
        if (candidates.size() != 1) {
            System.out.println("[SKIP] auto-import single: ArrayList の候補が1件でない (" + candidates.size() + ")");
            return;
        }

        String src = "package foo;\nclass X { ArrayList x; }";
        resetEditorTo(src);
        AutoImportHandler handler = new AutoImportHandler(
            new ImportSuggester(idx), new SourceAnalyzer());
        editor.setAutoImportHandler(handler);

        // 疑似的にコンパイルエラーを渡す
        CompileDiagnostic diag = new CompileDiagnostic(1, 10,
            "error: cannot find symbol\n  symbol:   class ArrayList", DiagnosticKind.ERROR);
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            editor.handleAutoImport(java.util.List.of(diag));
            latch.countDown();
        });
        latch.await(3, TimeUnit.SECONDS);
        Thread.sleep(SETTLE_MS);

        // 候補1件なので選択待ちにならず即挿入
        check("single: import 待ちなし", false, editor.hasImportPending());
        check("single: import 文が挿入された",
              true, editor.getText().contains("import java.util.ArrayList;"));
    }

    /**
     * 候補が複数件の場合: ステータスバーに候補を表示し、数字キー '1' で選択して挿入。
     * "List" は java.util.List / java.awt.List など複数候補があるはず。
     */
    static void testAutoImportMultipleSelection() throws Exception {
        System.out.println("\n--- auto-import: 複数候補 → 数字キーで選択 ---");
        JdkClassIndex idx = JdkClassIndex.buildSync();
        List<String> candidates = idx.lookup("List");
        if (candidates.size() < 2) {
            System.out.println("[SKIP] auto-import multi: List の候補が2件未満");
            return;
        }

        String src = "package foo;\nclass X { List x; }";
        resetEditorTo(src);
        AutoImportHandler handler = new AutoImportHandler(
            new ImportSuggester(idx), new SourceAnalyzer());
        editor.setAutoImportHandler(handler);

        CompileDiagnostic diag = new CompileDiagnostic(1, 10,
            "error: cannot find symbol\n  symbol:   class List", DiagnosticKind.ERROR);
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            editor.handleAutoImport(java.util.List.of(diag));
            latch.countDown();
        });
        latch.await(3, TimeUnit.SECONDS);
        Thread.sleep(SETTLE_MS);

        check("multi: import 待ち状態", true, editor.hasImportPending());
        String prompt = editor.getStatusMessage();
        check("multi: プロンプトに [1] が含まれる", true, prompt.contains("[1]"));
        check("multi: プロンプトに [Esc]=skip が含まれる", true, prompt.contains("[Esc]=skip"));

        // '1' を押して最初の候補を選択
        pressChar('1');
        Thread.sleep(SETTLE_MS);

        check("multi: 選択後 import 待ち解消", false, editor.hasImportPending());
        String firstFqn = candidates.get(0);
        check("multi: 選択した import が挿入された",
              true, editor.getText().contains("import " + firstFqn + ";"));
    }

    /**
     * 複数候補で Escape を押すとスキップして待ち状態が解消される。
     */
    static void testAutoImportEscapeSkip() throws Exception {
        System.out.println("\n--- auto-import: Esc でスキップ ---");
        JdkClassIndex idx = JdkClassIndex.buildSync();
        List<String> candidates = idx.lookup("List");
        if (candidates.size() < 2) {
            System.out.println("[SKIP] auto-import escape: List の候補が2件未満");
            return;
        }

        String src = "class X { List x; }";
        resetEditorTo(src);
        AutoImportHandler handler = new AutoImportHandler(
            new ImportSuggester(idx), new SourceAnalyzer());
        editor.setAutoImportHandler(handler);

        CompileDiagnostic diag = new CompileDiagnostic(0, 10,
            "error: cannot find symbol\n  symbol:   class List", DiagnosticKind.ERROR);
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            editor.handleAutoImport(java.util.List.of(diag));
            latch.countDown();
        });
        latch.await(3, TimeUnit.SECONDS);
        Thread.sleep(SETTLE_MS);

        check("escape: import 待ち状態", true, editor.hasImportPending());

        pressEscape(); // スキップ
        Thread.sleep(SETTLE_MS);

        check("escape: Esc でスキップ後 import 待ち解消", false, editor.hasImportPending());
        // import は挿入されていない
        check("escape: import 文なし", false,
              editor.getText().contains("import java.util.List;"));
    }

    /**
     * コンパイルエラーがない場合は何もしない（待ち状態にならない）。
     */
    static void testAutoImportNoDiagnostics() throws Exception {
        System.out.println("\n--- auto-import: エラーなしで何もしない ---");
        JdkClassIndex idx = JdkClassIndex.buildSync();
        String src = "class X {}";
        resetEditorTo(src);
        AutoImportHandler handler = new AutoImportHandler(
            new ImportSuggester(idx), new SourceAnalyzer());
        editor.setAutoImportHandler(handler);

        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            editor.handleAutoImport(java.util.List.of());
            latch.countDown();
        });
        latch.await(3, TimeUnit.SECONDS);
        Thread.sleep(SETTLE_MS);

        check("no-diag: import 待ちなし", false, editor.hasImportPending());
        check("no-diag: テキスト変化なし", src, editor.getText());
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK]   " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
