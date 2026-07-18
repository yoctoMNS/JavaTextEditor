package dev.javatexteditor.ui;

import dev.javatexteditor.analysis.AutoImportHandler;
import dev.javatexteditor.analysis.CompileDiagnostic;
import dev.javatexteditor.analysis.DiagnosticKind;
import dev.javatexteditor.analysis.ImportSuggester;
import dev.javatexteditor.analysis.JdkClassIndex;
import dev.javatexteditor.analysis.SourceAnalyzer;
import dev.javatexteditor.editor.ModalEditor;
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
        testRenameCommand();               // :rename コマンドでバッファが *rename* ヘッダを含む
        testNativeMethodTracing();         // Shift+K on ClassName.method で native トレース
        testSwapLineDownUp();             // Alt+J / Alt+K 行入れ替え
        testLeaderSpacePrefix();          // Space+h/l/k/j リーダーシーケンス
        testInsertDeleteNext();           // Ctrl+D カーソル位置削除
        testInsertDeleteToEol();          // Ctrl+K 行末まで削除
        testInsertTabSkipClosingPair();   // Tab で閉じカッコをスキップ
        testSaveFromInsert();             // Ctrl+] INSERT→NORMAL
        testOrganizeImportsSpcIo();       // SPC+i+o で未使用 import 削除
        testCtrlShiftOInsertsOverrideStub(); // Ctrl+Shift+O で @Override + 改行を挿入
        testOrganizeImportsCommandOi();   // :oi コマンドで未使用 import 削除
        testRemoveImportCommand();        // :remove-import <fqn> で特定 import 削除
        testDiagJumpNextRobot();          // [g で次のエラー行へジャンプ
        testDiagJumpPrevRobot();          // [d で前のエラー行へジャンプ

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

    /**
     * :rename <old> <new> コマンドのエンドツーエンド検証。
     * 一時ディレクトリにファイルを作成し、user.dir を差し替えてコマンドを実行する。
     */
    static void testRenameCommand() throws Exception {
        System.out.println("\n--- :rename コマンド: プロジェクト全体リネーム ---");

        // 一時ディレクトリとファイルを用意
        java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("robot_rename_");
        java.nio.file.Files.writeString(tmpDir.resolve("Alpha.java"), "class Alpha { Alpha() {} }\n");
        java.nio.file.Files.writeString(tmpDir.resolve("Beta.java"),  "Alpha a = new Alpha();\n");

        String origUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tmpDir.toString());
        try {
            resetEditorTo("// current file");
            Thread.sleep(SETTLE_MS);

            // ESC → ':' → "rename Alpha Gamma" → Enter
            pressEscape();
            pressShiftKey(KeyEvent.VK_SEMICOLON); // ':'
            Thread.sleep(SETTLE_MS);
            for (char c : "rename Alpha Gamma".toCharArray()) pressChar(c);
            pressEnter();
            Thread.sleep(SETTLE_MS * 2);

            String buf = editor.getText();
            String status = editor.getStatusMessage();

            check(":rename: バッファが *rename* ヘッダを含む", true, buf.contains("*rename*"));
            check(":rename: Alpha→Gamma が表示される", true, buf.contains("Alpha") && buf.contains("Gamma"));
            check(":rename: ステータスに replacement(s) が含まれる", true,
                  status.contains("replacement(s)"));

            // 実際のファイルが変更されているか確認
            String alpha = java.nio.file.Files.readString(tmpDir.resolve("Alpha.java"));
            String beta  = java.nio.file.Files.readString(tmpDir.resolve("Beta.java"));
            check(":rename: Alpha.java に Gamma が含まれる", true, alpha.contains("Gamma"));
            check(":rename: Alpha.java から Alpha が消えた", false, alpha.contains("Alpha"));
            check(":rename: Beta.java に Gamma が含まれる", true, beta.contains("Gamma"));
        } finally {
            System.setProperty("user.dir", origUserDir);
        }
    }

    /**
     * Shift+K on "System.arraycopy": native メソッドのJNIトレース結果が表示されること。
     */
    static void testNativeMethodTracing() throws Exception {
        System.out.println("\n--- Shift+K: native メソッドのソーストレース ---");

        JdkClassIndex idx = JdkClassIndex.buildSync();

        // (1) "System.arraycopy" の "arraycopy" 上で K → [native] JNI名が表示される
        // カーソルを arraycopy の先頭文字 'a'（col=7）に置く
        resetEditorTo("System.arraycopy");
        editor.setJdkClassIndex(idx);
        Thread.sleep(SETTLE_MS);
        // l で9回右移動して col=9（arraycopy の 'a' の位置）にカーソルを置く
        for (int i = 0; i < 9; i++) pressChar('l');
        Thread.sleep(SETTLE_MS);
        pressShiftKey(KeyEvent.VK_K);
        Thread.sleep(SETTLE_MS);
        String msg1 = editor.getStatusMessage();
        check("native trace: [native] が含まれる", true, msg1.contains("[native]"));
        check("native trace: JNI名が含まれる", true, msg1.contains("Java_java_lang_System_arraycopy"));

        // (2) "String.intern" の "intern" 上で K → [native] が表示される
        resetEditorTo("String.intern");
        editor.setJdkClassIndex(idx);
        Thread.sleep(SETTLE_MS);
        for (int i = 0; i < 7; i++) pressChar('l');
        Thread.sleep(SETTLE_MS);
        pressShiftKey(KeyEvent.VK_K);
        Thread.sleep(SETTLE_MS);
        String msg2 = editor.getStatusMessage();
        check("native trace: String.intern [native] が含まれる", true, msg2.contains("[native]"));

        // (3) "String.length" の "length" 上: 非native → 通常の K 動作（[native]なし）
        resetEditorTo("String.length");
        editor.setJdkClassIndex(idx);
        Thread.sleep(SETTLE_MS);
        for (int i = 0; i < 7; i++) pressChar('l');
        Thread.sleep(SETTLE_MS);
        pressShiftKey(KeyEvent.VK_K);
        Thread.sleep(SETTLE_MS);
        String msg3 = editor.getStatusMessage();
        // 非native なので [native] は含まれない（クラス情報か Not found が表示）
        check("非native: [native] が含まれない", false, msg3.contains("[native]"));
        check("非native: ステータスが空でない", true, !msg3.isEmpty());
    }

    // =========================================================================
    // Neovim キーマップ機能テスト (Robot)
    // =========================================================================

    static void testSwapLineDownUp() throws Exception {
        System.out.println("\n--- Alt+J / Alt+K: 行入れ替え ---");
        resetEditorTo("aaa\nbbb\nccc");
        // Alt+J: aaa と bbb を入れ替え
        robot.keyPress(KeyEvent.VK_ALT);
        robot.keyPress(KeyEvent.VK_J);
        robot.keyRelease(KeyEvent.VK_J);
        robot.keyRelease(KeyEvent.VK_ALT);
        Thread.sleep(SETTLE_MS);
        String[] lines = editor.getText().split("\n", -1);
        check("Alt+J: 行0がbbb", "bbb", lines[0]);
        check("Alt+J: 行1がaaa", "aaa", lines[1]);
        check("Alt+J: cursorRow=1", 1, editor.getCursorRow());

        // Alt+K: 行1(aaa)を上(行0)と入れ替えて元に戻す
        robot.keyPress(KeyEvent.VK_ALT);
        robot.keyPress(KeyEvent.VK_K);
        robot.keyRelease(KeyEvent.VK_K);
        robot.keyRelease(KeyEvent.VK_ALT);
        Thread.sleep(SETTLE_MS);
        lines = editor.getText().split("\n", -1);
        check("Alt+K: 行0がaaa", "aaa", lines[0]);
        check("Alt+K: 行1がbbb", "bbb", lines[1]);
        check("Alt+K: cursorRow=0", 0, editor.getCursorRow());
    }

    static void testLeaderSpacePrefix() throws Exception {
        System.out.println("\n--- Space リーダーシーケンス ---");
        // Space+j: ファイル末尾
        resetEditorTo("aaa\nbbb\nccc");
        pressChar(' ');
        pressChar('j');
        Thread.sleep(SETTLE_MS);
        check("Space+j: row=2", 2, editor.getCursorRow());

        // Space+k: ファイル先頭
        pressChar(' ');
        pressChar('k');
        Thread.sleep(SETTLE_MS);
        check("Space+k: row=0", 0, editor.getCursorRow());

        // Space+l: 行末
        resetEditorTo("hello");
        pressChar(' ');
        pressChar('l');
        Thread.sleep(SETTLE_MS);
        check("Space+l: col=4", 4, editor.getCursorCol());

        // Space+h: 最初の非空白
        resetEditorTo("   hello");
        pressChar(' ');
        pressChar('h');
        Thread.sleep(SETTLE_MS);
        check("Space+h: col=3", 3, editor.getCursorCol());
    }

    static void testInsertDeleteNext() throws Exception {
        System.out.println("\n--- INSERT Ctrl+D: カーソル位置の文字を削除 ---");
        resetEditorTo("hello");
        pressChar('i'); // INSERT at col=0
        Thread.sleep(SETTLE_MS);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_D);
        robot.keyRelease(KeyEvent.VK_D);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        Thread.sleep(SETTLE_MS);
        check("Ctrl+D: 'h'が削除", "ello", editor.getText());
        check("Ctrl+D: col=0のまま", 0, editor.getCursorCol());
    }

    static void testInsertDeleteToEol() throws Exception {
        System.out.println("\n--- INSERT Ctrl+K: 行末まで削除 ---");
        resetEditorTo("hello world");
        pressChar('i'); // INSERT at col=0
        // Ctrl+F で5回右移動して col=5へ
        for (int i = 0; i < 5; i++) {
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_F);
            robot.keyRelease(KeyEvent.VK_F);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            Thread.sleep(20);
        }
        Thread.sleep(SETTLE_MS);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_K);
        robot.keyRelease(KeyEvent.VK_K);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        Thread.sleep(SETTLE_MS);
        check("Ctrl+K: ' world'が削除", "hello", editor.getText());
    }

    static void testInsertTabSkipClosingPair() throws Exception {
        System.out.println("\n--- INSERT Tab: 閉じカッコをスキップ ---");
        resetEditorTo("()");
        pressChar('i'); // INSERT at col=0
        // Ctrl+F で1回右移動して col=1（')'の上）
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_F);
        robot.keyRelease(KeyEvent.VK_F);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        Thread.sleep(SETTLE_MS);
        // Tab → ')' をスキップ
        robot.keyPress(KeyEvent.VK_TAB);
        robot.keyRelease(KeyEvent.VK_TAB);
        Thread.sleep(SETTLE_MS);
        check("Tab skip ')': col=2", 2, editor.getCursorCol());
        check("Tab skip ')': テキスト変化なし", "()", editor.getText());

        // 通常位置での Tab: 4スペース挿入
        resetEditorTo("hello");
        pressChar('i'); // INSERT at col=0
        robot.keyPress(KeyEvent.VK_TAB);
        robot.keyRelease(KeyEvent.VK_TAB);
        Thread.sleep(SETTLE_MS);
        check("Tab insert: 4スペース挿入", "    hello", editor.getText());
        check("Tab insert: col=4", 4, editor.getCursorCol());
    }

    static void testSaveFromInsert() throws Exception {
        System.out.println("\n--- INSERT Ctrl+]: NORMAL へ戻る ---");
        resetEditorTo("hello");
        pressChar('i'); // INSERT
        check("i: INSERTモード", true, editor.isInsertMode());
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_CLOSE_BRACKET);
        robot.keyRelease(KeyEvent.VK_CLOSE_BRACKET);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        Thread.sleep(SETTLE_MS);
        check("Ctrl+]: NORMALモードへ", true, editor.isNormalMode());
    }

    static void pressBracketThenKey(char key) throws Exception {
        robot.keyPress(KeyEvent.VK_OPEN_BRACKET);
        robot.keyRelease(KeyEvent.VK_OPEN_BRACKET);
        Thread.sleep(KEY_DELAY_MS);
        int vk = (key == 'g') ? KeyEvent.VK_G : KeyEvent.VK_D;
        robot.keyPress(vk);
        robot.keyRelease(vk);
        Thread.sleep(KEY_DELAY_MS);
    }

    static void pressCtrlShift(int keyCode) throws Exception {
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
        robot.keyRelease(KeyEvent.VK_SHIFT);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        Thread.sleep(KEY_DELAY_MS);
    }

    /**
     * SPC+i+o でオーガナイズ: 未使用 import が削除され、使用中 import は残る。
     */
    static void testOrganizeImportsSpcIo() throws Exception {
        System.out.println("\n--- SPC+i+o: 未使用 import 削除 ---");
        JdkClassIndex idx = JdkClassIndex.buildSync();
        String src = "package foo;\nimport java.util.List;\nimport java.util.Map;\nclass X { List<String> x; }";
        resetEditorTo(src);
        editor.setAutoImportHandler(new AutoImportHandler(new ImportSuggester(idx), new SourceAnalyzer()));

        pressChar(' ');
        pressChar('i');
        pressChar('o');
        Thread.sleep(SETTLE_MS);

        String text = editor.getText();
        check("SPC+i+o: List import 残存", true, text.contains("import java.util.List;"));
        check("SPC+i+o: Map import 削除済", false, text.contains("import java.util.Map;"));
        check("SPC+i+o: NORMALモード維持", true, editor.isNormalMode());
        String msg = editor.getStatusMessage();
        check("SPC+i+o: statusMessage に '削除' が含まれる", true, msg.contains("削除"));
    }

    /**
     * Ctrl+Shift+O: @Override + 改行を挿入し INSERT モードへ入る（NORMAL/INSERT いずれからも）。
     * 元々 Eclipse 互換の import 整理が割り当てられていたが、ユーザー確認の上でこの機能に
     * 差し替えた（import 整理自体は SPC+i+o / :oi から引き続き利用できる。上記テスト参照）。
     */
    static void testCtrlShiftOInsertsOverrideStub() throws Exception {
        System.out.println("\n--- Ctrl+Shift+O (NORMAL): @Override 挿入 ---");
        // 2行目は既にインデントだけの空行（"    "）: カーソルをその行末（インデント直後）に
        // 置いた状態が「メソッドを書く直前」の実際の使い方に相当する（自動インデントの既存テストと
        // 同じ慣例。カーソル列0のまま挿入するとインデントが二重になる既知の落とし穴を避けている）。
        String src = "class X {\n    \n    void foo() {}\n}\n";
        resetEditorTo(src);
        editor.setCursor(1, 4);

        pressCtrlShift(KeyEvent.VK_O);
        Thread.sleep(SETTLE_MS);

        String[] lines = editor.getText().split("\n", -1);
        check("Ctrl+Shift+O(NORMAL): 2行目が \"    @Override\" になる", "    @Override", lines[1]);
        check("Ctrl+Shift+O(NORMAL): INSERT モードへ遷移する", true, editor.isInsertMode());

        System.out.println("\n--- Ctrl+Shift+O (INSERT): @Override 挿入 ---");
        String src2 = "class Y {\n    \n    void bar() {}\n}\n";
        resetEditorTo(src2);
        editor.setCursor(1, 4);
        pressChar('i'); // INSERT モードへ（列位置は維持される）
        Thread.sleep(SETTLE_MS);
        pressCtrlShift(KeyEvent.VK_O);
        Thread.sleep(SETTLE_MS);
        String[] lines2 = editor.getText().split("\n", -1);
        check("Ctrl+Shift+O(INSERT): 2行目が \"    @Override\" になる", "    @Override", lines2[1]);
        check("Ctrl+Shift+O(INSERT): INSERT モード維持", true, editor.isInsertMode());
    }

    /**
     * :oi コマンドで未使用 import を削除する。
     */
    static void testOrganizeImportsCommandOi() throws Exception {
        System.out.println("\n--- :oi コマンド: 未使用 import 削除 ---");
        JdkClassIndex idx = JdkClassIndex.buildSync();
        String src = "package foo;\nimport java.util.List;\nimport java.util.Map;\nclass X { Map<String,Integer> m; }";
        resetEditorTo(src);
        editor.setAutoImportHandler(new AutoImportHandler(new ImportSuggester(idx), new SourceAnalyzer()));

        pressShiftKey(KeyEvent.VK_SEMICOLON); // ':'
        for (char c : "oi".toCharArray()) pressChar(c);
        pressEnter();
        Thread.sleep(SETTLE_MS);

        String text = editor.getText();
        check(":oi: Map import 残存", true, text.contains("import java.util.Map;"));
        check(":oi: List import 削除済", false, text.contains("import java.util.List;"));
        check(":oi: NORMALモードへ戻る", true, editor.isNormalMode());
    }

    /**
     * :remove-import <fqn> で特定 import 行を削除する。
     */
    static void testRemoveImportCommand() throws Exception {
        System.out.println("\n--- :remove-import: 特定 import 削除 ---");
        JdkClassIndex idx = JdkClassIndex.buildSync();
        String src = "package foo;\nimport java.util.List;\nimport java.util.Map;\nclass X {}";
        resetEditorTo(src);
        editor.setAutoImportHandler(new AutoImportHandler(new ImportSuggester(idx), new SourceAnalyzer()));

        pressShiftKey(KeyEvent.VK_SEMICOLON); // ':'
        // "remove-import java.util.List" を1文字ずつ入力（'-' は VK_MINUS で個別処理）
        for (char c : "remove".toCharArray()) pressChar(c);
        robot.keyPress(KeyEvent.VK_MINUS); robot.keyRelease(KeyEvent.VK_MINUS); Thread.sleep(KEY_DELAY_MS);
        for (char c : "import java.util.".toCharArray()) pressChar(c);
        for (char c : "List".toCharArray()) pressChar(c);
        pressEnter();
        Thread.sleep(SETTLE_MS);

        String text = editor.getText();
        check(":remove-import: List import 削除済", false, text.contains("import java.util.List;"));
        check(":remove-import: Map import 残存", true, text.contains("import java.util.Map;"));
        String msg = editor.getStatusMessage();
        check(":remove-import: statusMessage に fqn が含まれる",
              true, msg.contains("java.util.List"));
    }

    /**
     * [g でカーソルが次のエラー行に移動することを確認する。
     */
    static void testDiagJumpNextRobot() throws Exception {
        System.out.println("\n--- [g: 次の診断行へジャンプ ---");
        resetEditorTo("line0\nline1\nline2\nline3\nline4");
        // 行1・行3 にエラー診断を設定
        editor.setDiagnostics(java.util.List.of(
            new CompileDiagnostic(1, 0, "error", DiagnosticKind.ERROR),
            new CompileDiagnostic(3, 0, "error", DiagnosticKind.ERROR)
        ));
        Thread.sleep(SETTLE_MS);
        // row=0 から [g → row=1
        pressBracketThenKey('g');
        Thread.sleep(SETTLE_MS);
        check("[g: row=1 へジャンプ", 1, editor.getCursorRow());
        // row=1 から [g → row=3
        pressBracketThenKey('g');
        Thread.sleep(SETTLE_MS);
        check("[g: row=3 へジャンプ", 3, editor.getCursorRow());
        // row=3 から [g → 折り返し row=1
        pressBracketThenKey('g');
        Thread.sleep(SETTLE_MS);
        check("[g wrap: row=1 へ折り返し", 1, editor.getCursorRow());
    }

    /**
     * [d でカーソルが前のエラー行に移動することを確認する。
     */
    static void testDiagJumpPrevRobot() throws Exception {
        System.out.println("\n--- [d: 前の診断行へジャンプ ---");
        resetEditorTo("line0\nline1\nline2\nline3\nline4");
        editor.setDiagnostics(java.util.List.of(
            new CompileDiagnostic(1, 0, "error", DiagnosticKind.ERROR),
            new CompileDiagnostic(3, 0, "error", DiagnosticKind.ERROR)
        ));
        // G で row=4 へ
        pressChar('G');
        Thread.sleep(SETTLE_MS);
        pressBracketThenKey('d');
        Thread.sleep(SETTLE_MS);
        check("[d: row=3 へジャンプ", 3, editor.getCursorRow());
        pressBracketThenKey('d');
        Thread.sleep(SETTLE_MS);
        check("[d: row=1 へジャンプ", 1, editor.getCursorRow());
        // row=1 から [d → 折り返し row=3
        pressBracketThenKey('d');
        Thread.sleep(SETTLE_MS);
        check("[d wrap: row=3 へ折り返し", 3, editor.getCursorRow());
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK]   " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
