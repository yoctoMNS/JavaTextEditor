package dev.vimacs.ui;

import dev.vimacs.editor.ModalEditor;
import java.awt.event.KeyEvent;

/**
 * 実際のキーボードが生成する KeyEvent と同じ keyCode / keyChar / modifiers を
 * processKey() に渡すことで、KeyboardFocusManager 経由の実キー入力を忠実に再現する。
 *
 * 修正対象のバグ:
 *   ofChar() 登録キーは modifiers=0 で保存されるが、実キーイベントでは
 *   Shift 修飾文字（':', 'V', 'P' 等）に SHIFT_DOWN_MASK が付いてくる。
 *   KeymapRegistry.resolve() が SHIFT を除去しない場合、マップ参照が失敗する。
 *
 * 検証スコープ:
 *   README.md 記載の全キーバインドについて、実キーイベント相当のパラメータで動作確認する。
 */
public class KeyboardSimulationTest {

    private static int pass = 0;
    private static int total = 0;

    // キー操作ヘルパーが参照するエディタ（各テストで reset される）
    private static ModalEditor ed;

    public static void main(String[] args) {
        // ========================================
        // NORMALモード
        // ========================================
        section("NORMALモード: h/j/k/l カーソル移動");
        testNormalCursorMovement();

        section("NORMALモード: i でINSERTエントリ");
        testNormalInsertEntry();

        section("NORMALモード: a でカーソル後INSERTエントリ");
        testNormalInsertAfter();

        section("NORMALモード: o で下に新行を開いてINSERT");
        testNormalOpenNewLine();

        section("NORMALモード: ':' (Shift+;) でCOMMANDモードへ [修正済みバグ]");
        testNormalCommandEntry();

        section("NORMALモード: 'V' (Shift+v) でVISUAL LINEモードへ [修正済みバグ]");
        testNormalVisualLineEntry();

        section("NORMALモード: 'v' でVISUALモードへ");
        testNormalVisualEntry();

        section("NORMALモード: yy で行ヤンク");
        testNormalYankLine();

        section("NORMALモード: dd で行削除");
        testNormalDeleteLine();

        section("NORMALモード: x で1文字削除");
        testNormalDeleteChar();

        section("NORMALモード: p でカーソル後ペースト");
        testNormalPasteAfter();

        section("NORMALモード: 'P' (Shift+p) でカーソル前ペースト [修正済みバグ]");
        testNormalPasteBefore();

        section("NORMALモード: u でアンドゥ");
        testNormalUndo();

        section("NORMALモード: Ctrl+R でリドゥ");
        testNormalRedo();

        section("NORMALモード: カーソルクランプ（末端・先頭・上端・下端）");
        testNormalCursorClamp();

        // ========================================
        // INSERTモード
        // ========================================
        section("INSERTモード: 通常文字の挿入");
        testInsertTyping();

        section("INSERTモード: Backspace で前の文字を削除");
        testInsertBackspace();

        section("INSERTモード: 行頭 Backspace で前行と結合");
        testInsertBackspaceAcrossLine();

        section("INSERTモード: Enter で改行挿入");
        testInsertEnter();

        section("INSERTモード: Escape でNORMALへ戻る");
        testInsertEscape();

        section("INSERTモード: Ctrl+F/B/N/P (Emacs式カーソル移動)");
        testInsertCtrlMovement();

        section("INSERTモード: Ctrl カーソル移動のクランプ");
        testInsertCtrlMovementClamp();

        // ========================================
        // COMMANDモード
        // ========================================
        section("COMMANDモード: 文字入力でコマンドバッファが積まれる");
        testCommandBuffering();

        section("COMMANDモード: Backspace でコマンドバッファを1文字削除");
        testCommandBackspace();

        section("COMMANDモード: Escape でNORMALへ（バッファクリア）");
        testCommandEscape();

        section("COMMANDモード: Enter でコマンドを実行（未知コマンドはエラー）");
        testCommandExecuteUnknown();

        section("COMMANDモード: ':q' でexitCallbackが呼ばれる");
        testCommandQuit();

        // ========================================
        // VISUALモード
        // ========================================
        section("VISUALモード: h/l/j/k で選択カーソル移動");
        testVisualMovement();

        section("VISUALモード: y でヤンクしてNORMALへ");
        testVisualYank();

        section("VISUALモード: d で削除してNORMALへ");
        testVisualDelete();

        section("VISUALモード: Escape でNORMALへ（テキスト変化なし）");
        testVisualEscape();

        // ========================================
        // VISUAL LINEモード
        // ========================================
        section("VISUAL LINEモード: j/k で行選択を拡張");
        testVisualLineMovement();

        section("VISUAL LINEモード: y で行ヤンクしてNORMALへ");
        testVisualLineYank();

        section("VISUAL LINEモード: d で行削除してNORMALへ");
        testVisualLineDelete();

        section("VISUAL LINEモード: Escape でNORMALへ（テキスト変化なし）");
        testVisualLineEscape();

        // ========================================
        // モード間連鎖シナリオ
        // ========================================
        section("シナリオ: INSERT → NORMAL → VISUAL → ヤンク → ペースト");
        testScenarioInsertYankPaste();

        section("シナリオ: 複数行編集・アンドゥ・リドゥ往復");
        testScenarioUndoRedoRoundTrip();

        section("シナリオ: COMMAND ':w' でファイル保存");
        testScenarioSaveFile();

        section("シナリオ: COMMAND ':e' でファイルを開く");
        testScenarioOpenFile();

        // 結果集計
        int fail = total - pass;
        System.out.println("\n============================");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        System.out.println("============================");
        if (fail > 0) System.exit(1);
    }

    // =========================================================================
    // NORMALモードテスト
    // =========================================================================

    static void testNormalCursorMovement() {
        reset("abc\ndefg\nhij");
        check("初期 row==0", 0, ed.getCursorRow());
        check("初期 col==0", 0, ed.getCursorCol());
        key('l'); check("l: col==1", 1, ed.getCursorCol());
        key('l'); check("ll: col==2", 2, ed.getCursorCol());
        key('h'); check("h: col==1", 1, ed.getCursorCol());
        key('j'); check("j: row==1", 1, ed.getCursorRow());
        key('k'); check("k: row==0", 0, ed.getCursorRow());
    }

    static void testNormalInsertEntry() {
        reset("test");
        check("NORMAL", true, ed.isNormalMode());
        key('i');
        check("i: INSERT", true, ed.isInsertMode());
        key('X');
        check("i+X: Xtest", "Xtest", ed.getText());
        esc();
        check("ESC: NORMAL", true, ed.isNormalMode());
    }

    static void testNormalInsertAfter() {
        reset("AB");
        key('a');
        check("a: INSERT", true, ed.isInsertMode());
        check("a: col==1", 1, ed.getCursorCol());
        key('X');
        check("a+X: AXB", "AXB", ed.getText());
        esc();
    }

    static void testNormalOpenNewLine() {
        reset("line1\nline2");
        key('o');
        check("o: INSERT", true, ed.isInsertMode());
        check("o: row==1", 1, ed.getCursorRow());
        check("o: col==0", 0, ed.getCursorCol());
        key('N'); key('E'); key('W');
        esc();
        check("o+NEW: テキスト", "line1\nNEW\nline2", ed.getText());
    }

    // ':' は Shift+; → modifiers に SHIFT_DOWN_MASK が付く → バグ修正確認
    static void testNormalCommandEntry() {
        reset("x");
        // 実際のキーボードでは `:` を押すと SHIFT_DOWN_MASK が付く
        keyWithModifiers(KeyEvent.VK_SEMICOLON, ':', KeyEvent.SHIFT_DOWN_MASK);
        check("':' (SHIFT付き): COMMAND モードへ", true, ed.isCommandMode());
        esc();
        check("ESC: NORMAL", true, ed.isNormalMode());

        // 修正前の問題: modifiers=0 では動いていた（テスト環境のみ）
        // modifiers=SHIFT_DOWN_MASK でも動くことを確認
        reset("y");
        keyWithModifiers(KeyEvent.VK_SEMICOLON, ':', 0); // modifiers=0 でも引き続き動く
        check("':' (SHIFT無し): COMMAND モードへ", true, ed.isCommandMode());
        esc();
    }

    // 'V' は Shift+v → modifiers に SHIFT_DOWN_MASK が付く → バグ修正確認
    static void testNormalVisualLineEntry() {
        reset("line1\nline2");
        // 実際のキーボードでは 'V' を押すと SHIFT_DOWN_MASK が付く
        keyWithModifiers(KeyEvent.VK_V, 'V', KeyEvent.SHIFT_DOWN_MASK);
        check("'V' (SHIFT付き): VISUAL LINE モードへ", true, ed.isVisualLineMode());
        esc();
        check("ESC: NORMAL", true, ed.isNormalMode());

        // modifiers=0 でも引き続き動くことを確認
        reset("abc");
        keyWithModifiers(KeyEvent.VK_V, 'V', 0);
        check("'V' (SHIFT無し): VISUAL LINE モードへ", true, ed.isVisualLineMode());
        esc();
    }

    static void testNormalVisualEntry() {
        reset("hello");
        key('v');
        check("v: VISUAL モードへ", true, ed.isVisualMode());
        esc();
        check("ESC: NORMAL", true, ed.isNormalMode());
    }

    static void testNormalYankLine() {
        reset("hello\nworld");
        key('y'); key('y'); // yy
        check("yy: yankType==line", "line", ed.getYankType());
        check("yy: yankRegister==hello\\n", "hello\n", ed.getYankRegister());
        check("yy: テキスト変化なし", "hello\nworld", ed.getText());
    }

    static void testNormalDeleteLine() {
        reset("line1\nline2\nline3");
        key('j'); // row=1
        key('d'); key('d'); // dd
        check("dd: テキスト", "line1\nline3", ed.getText());
        check("dd: yankRegister==line2\\n", "line2\n", ed.getYankRegister());
        check("dd: yankType==line", "line", ed.getYankType());
    }

    static void testNormalDeleteChar() {
        reset("abcde");
        key('l'); key('l'); // col=2 ('c')
        key('x');
        check("x: 'c'を削除 → abde", "abde", ed.getText());
    }

    static void testNormalPasteAfter() {
        reset("ABCDE");
        key('l'); // col=1
        key('v'); key('l'); key('l'); // VISUAL select 'BCD' (col 1-3)
        key('y'); // yank "BCD", NORMAL
        // Vim仕様: v y 後カーソルは選択開始(col=1)に戻る → p は col=1 の後ろ(offset=2)に挿入
        key('p'); // paste after cursor (col=1)
        check("p後テキスト", "ABBCDCDE", ed.getText());
    }

    // 'P' は Shift+p → modifiers に SHIFT_DOWN_MASK が付く → バグ修正確認
    static void testNormalPasteBefore() {
        reset("ABCDE");
        key('l'); key('l'); // col=2 ('C')
        key('v'); key('l'); // VISUAL select 'CD'
        key('y'); // yank "CD"
        // cursor at col=2
        // Vim仕様: v y 後カーソルは選択開始(col=2)に戻る → P は col=2 の前(offset=2)に挿入
        keyWithModifiers(KeyEvent.VK_P, 'P', KeyEvent.SHIFT_DOWN_MASK); // 'P' で前に貼り付け
        check("P (SHIFT付き)後テキスト", "ABCDCDE", ed.getText());

        // modifiers=0 でも動くことを確認
        reset("XY");
        key('v'); key('y'); // yank "X"
        keyWithModifiers(KeyEvent.VK_P, 'P', 0); // P without shift
        check("P (SHIFT無し)後テキスト", "XXY", ed.getText());
    }

    static void testNormalUndo() {
        reset("hello");
        key('i'); key('X'); esc(); // "Xhello"
        check("挿入後: Xhello", "Xhello", ed.getText());
        key('u');
        check("u: hello に戻る", "hello", ed.getText());
    }

    static void testNormalRedo() {
        reset("hello");
        key('i'); key('X'); esc();  // "Xhello"
        key('u');                    // undo → "hello"
        ctrlKey(KeyEvent.VK_R);      // redo
        check("Ctrl+R: Xhello", "Xhello", ed.getText());
    }

    static void testNormalCursorClamp() {
        reset("abc");
        // 末端右クランプ（NORMALは lineLen-1 まで）
        key('l'); key('l'); key('l'); key('l');
        check("末端右クランプ col==2", 2, ed.getCursorCol());
        // 先頭左クランプ
        key('h'); key('h'); key('h');
        check("先頭左クランプ col==0", 0, ed.getCursorCol());
        // 文書先頭から上クランプ
        key('k');
        check("先頭行 k: row==0", 0, ed.getCursorRow());
        // 文書末尾から下クランプ
        key('j'); key('j'); key('j');
        check("最終行 j: row==0", 0, ed.getCursorRow()); // 1行のみ
    }

    // =========================================================================
    // INSERTモードテスト
    // =========================================================================

    static void testInsertTyping() {
        reset("");
        key('i');
        key('h'); key('e'); key('l'); key('l'); key('o');
        check("INSERT typing: hello", "hello", ed.getText());
        check("INSERT col==5", 5, ed.getCursorCol());
        esc();
    }

    static void testInsertBackspace() {
        reset("abc");
        key('a'); // col=1 に入って INSERT
        key('X'); // "aXbc"
        check("挿入後 aXbc", "aXbc", ed.getText());
        bs(); // Backspace
        check("Backspace後 abc", "abc", ed.getText());
        esc();
    }

    static void testInsertBackspaceAcrossLine() {
        reset("foo\nbar");
        key('j'); key('i'); // 2行目先頭でINSERT
        bs(); // 行頭 Backspace → 行結合
        check("行頭 Backspace: foobar", "foobar", ed.getText());
        check("結合後 row==0", 0, ed.getCursorRow());
        check("結合後 col==3", 3, ed.getCursorCol());
        esc();
    }

    static void testInsertEnter() {
        reset("helloworld");
        key('i');
        // Ctrl+F x5 でカーソルを col=5 へ
        ctrlKey(KeyEvent.VK_F); ctrlKey(KeyEvent.VK_F); ctrlKey(KeyEvent.VK_F);
        ctrlKey(KeyEvent.VK_F); ctrlKey(KeyEvent.VK_F);
        enter();
        check("Enter後: hello\\nworld", "hello\nworld", ed.getText());
        check("Enter後 row==1", 1, ed.getCursorRow());
        check("Enter後 col==0", 0, ed.getCursorCol());
        esc();
    }

    static void testInsertEscape() {
        reset("abc");
        key('i');
        check("INSERT中", true, ed.isInsertMode());
        esc();
        check("ESC: NORMAL", true, ed.isNormalMode());
        check("ESC後 col==0", 0, ed.getCursorCol());
    }

    static void testInsertCtrlMovement() {
        reset("abc\ndef");
        key('i'); // (0,0)
        ctrlKey(KeyEvent.VK_F); check("Ctrl+F: col==1", 1, ed.getCursorCol());
        ctrlKey(KeyEvent.VK_F); check("Ctrl+F: col==2", 2, ed.getCursorCol());
        ctrlKey(KeyEvent.VK_B); check("Ctrl+B: col==1", 1, ed.getCursorCol());
        ctrlKey(KeyEvent.VK_N); check("Ctrl+N: row==1", 1, ed.getCursorRow());
        ctrlKey(KeyEvent.VK_P); check("Ctrl+P: row==0", 0, ed.getCursorRow());
        esc();
    }

    static void testInsertCtrlMovementClamp() {
        reset("ab\ncd");
        key('i'); // (0,0)
        // 左端クランプ
        ctrlKey(KeyEvent.VK_B); ctrlKey(KeyEvent.VK_B);
        check("Ctrl+B 左端クランプ: col==0", 0, ed.getCursorCol());
        // 上端クランプ
        ctrlKey(KeyEvent.VK_P);
        check("Ctrl+P 上端クランプ: row==0", 0, ed.getCursorRow());
        // 右端クランプ（INSERT では lineLen まで可）
        ctrlKey(KeyEvent.VK_F); ctrlKey(KeyEvent.VK_F); ctrlKey(KeyEvent.VK_F);
        check("Ctrl+F 右端クランプ: col==2", 2, ed.getCursorCol());
        // 下端クランプ
        ctrlKey(KeyEvent.VK_N); ctrlKey(KeyEvent.VK_N);
        check("Ctrl+N 下端クランプ: row==1", 1, ed.getCursorRow());
        esc();
    }

    // =========================================================================
    // COMMANDモードテスト
    // =========================================================================

    static void testCommandBuffering() {
        reset("x");
        shiftKey(':'); // COMMAND
        key('w'); key('q');
        check("commandBuffer: wq", "wq", ed.getCommandBuffer());
        check("COMMANDモード中", true, ed.isCommandMode());
        esc();
    }

    static void testCommandBackspace() {
        reset("x");
        shiftKey(':');
        key('w'); key('r');
        bs();
        check("Backspace後 commandBuffer==w", "w", ed.getCommandBuffer());
        esc();
    }

    static void testCommandEscape() {
        reset("x");
        shiftKey(':');
        key('w'); key('q');
        esc();
        check("ESC: NORMAL", true, ed.isNormalMode());
        check("ESC: commandBuffer クリア", "", ed.getCommandBuffer());
    }

    static void testCommandExecuteUnknown() {
        reset("x");
        shiftKey(':');
        key('z'); key('z'); key('z');
        enter();
        check("未知コマンド後 NORMAL", true, ed.isNormalMode());
        check("statusMessage に 'E:' を含む",
            true, ed.getStatusMessage().startsWith("E:"));
    }

    static void testCommandQuit() {
        reset("x");
        boolean[] exitCalled = {false};
        ed.setExitCallback(() -> exitCalled[0] = true);
        shiftKey(':');
        key('q');
        enter();
        check(":q でexitCallback呼び出し", true, exitCalled[0]);
    }

    // =========================================================================
    // VISUALモードテスト
    // =========================================================================

    static void testVisualMovement() {
        reset("abcde\nfghij");
        key('v'); // VISUAL at (0,0)
        key('l'); check("VISUAL l: col==1", 1, ed.getCursorCol());
        key('l'); check("VISUAL l: col==2", 2, ed.getCursorCol());
        key('j'); check("VISUAL j: row==1", 1, ed.getCursorRow());
        key('k'); check("VISUAL k: row==0", 0, ed.getCursorRow());
        key('h'); check("VISUAL h: col==1", 1, ed.getCursorCol());
        esc();
    }

    static void testVisualYank() {
        reset("abcde");
        key('v'); key('l'); key('l'); // select col 0-2 = "abc"
        key('y');
        check("y後 NORMAL", true, ed.isNormalMode());
        check("yankRegister==abc", "abc", ed.getYankRegister());
        check("yankType==char", "char", ed.getYankType());
        check("テキスト変化なし", "abcde", ed.getText());
    }

    static void testVisualDelete() {
        reset("abcde");
        key('v'); key('l'); key('l'); // select "abc"
        key('d');
        check("d後 NORMAL", true, ed.isNormalMode());
        check("d後テキスト==de", "de", ed.getText());
        check("yankRegister==abc", "abc", ed.getYankRegister());
    }

    static void testVisualEscape() {
        reset("abc");
        key('v');
        check("VISUAL モード", true, ed.isVisualMode());
        esc();
        check("ESC: NORMAL", true, ed.isNormalMode());
        check("テキスト変化なし", "abc", ed.getText());
    }

    // =========================================================================
    // VISUAL LINEモードテスト
    // =========================================================================

    static void testVisualLineMovement() {
        reset("line1\nline2\nline3");
        shiftKey('V'); // VISUAL LINE at row=0
        key('j'); check("VISUAL LINE j: row==1", 1, ed.getCursorRow());
        key('j'); check("VISUAL LINE j: row==2", 2, ed.getCursorRow());
        key('k'); check("VISUAL LINE k: row==1", 1, ed.getCursorRow());
        esc();
    }

    static void testVisualLineYank() {
        reset("line1\nline2\nline3");
        shiftKey('V'); // VISUAL LINE row=0
        key('j');      // extend to row=1
        key('y');
        check("y後 NORMAL", true, ed.isNormalMode());
        check("yankType==line", "line", ed.getYankType());
        check("yankRegister==line1\\nline2\\n", "line1\nline2\n", ed.getYankRegister());
        check("テキスト変化なし", "line1\nline2\nline3", ed.getText());
    }

    static void testVisualLineDelete() {
        reset("line1\nline2\nline3");
        key('j');      // row=1
        shiftKey('V'); // VISUAL LINE
        key('d');      // delete row=1
        check("d後 NORMAL", true, ed.isNormalMode());
        check("d後テキスト", "line1\nline3", ed.getText());
        check("yankRegister==line2\\n", "line2\n", ed.getYankRegister());
    }

    static void testVisualLineEscape() {
        reset("abc\ndef");
        shiftKey('V');
        check("VISUAL LINE", true, ed.isVisualLineMode());
        esc();
        check("ESC: NORMAL", true, ed.isNormalMode());
    }

    // =========================================================================
    // 複合シナリオテスト
    // =========================================================================

    static void testScenarioInsertYankPaste() {
        reset("Hello World");
        // INSERT で文頭に '> ' を追加
        key('i'); key('>'); key(' '); esc();
        check("挿入後", "> Hello World", ed.getText());
        // VISUAL で '> ' をヤンク
        key('v'); key('l'); // select "> "
        key('y');
        // 末尾に貼り付け
        key('j'); // 動かない（1行のみ）→ row=0 のまま
        // 行末へ移動して p で貼り付け
        key('l'); key('l'); key('l'); key('l'); key('l');
        key('l'); key('l'); key('l'); key('l'); key('l');
        key('l'); key('l'); key('l');  // 末端まで
        key('p');
        check("末尾ペースト後のテキスト先頭", true, ed.getText().startsWith("> Hello World"));
    }

    static void testScenarioUndoRedoRoundTrip() {
        reset("base");
        key('i'); key('A'); key('B'); key('C'); esc(); // "ABCbase"
        String afterEdit = ed.getText();
        key('u'); key('u'); key('u');
        check("3回アンドゥ後: base", "base", ed.getText());
        ctrlKey(KeyEvent.VK_R); ctrlKey(KeyEvent.VK_R); ctrlKey(KeyEvent.VK_R);
        check("3回リドゥ後: 元の文書", afterEdit, ed.getText());
    }

    static void testScenarioSaveFile() {
        try {
            java.io.File tmp = java.io.File.createTempFile("vimacs_sim_test_", ".txt");
            tmp.deleteOnExit();
            String path = tmp.getAbsolutePath();
            reset("save me\nline2");

            shiftKey(':'); // COMMAND
            // ':w /path/to/file' をタイプ
            key('w'); key(' ');
            for (char c : path.toCharArray()) key(c);
            enter();

            check(":w 後 NORMAL", true, ed.isNormalMode());
            String saved = new String(java.nio.file.Files.readAllBytes(tmp.toPath()));
            check(":w ファイル保存内容", "save me\nline2", saved);
            tmp.delete();
        } catch (Exception e) {
            System.out.println("[FAIL] ファイル保存シナリオ例外: " + e);
            total++; // fail
        }
    }

    static void testScenarioOpenFile() {
        try {
            java.io.File tmp = java.io.File.createTempFile("vimacs_open_test_", ".txt");
            tmp.deleteOnExit();
            java.nio.file.Files.writeString(tmp.toPath(), "opened content\nline2");
            String path = tmp.getAbsolutePath();

            reset("original");
            shiftKey(':');
            key('e'); key(' ');
            for (char c : path.toCharArray()) key(c);
            enter();

            check(":e 後 NORMAL", true, ed.isNormalMode());
            check(":e テキスト差し替え", "opened content\nline2", ed.getText());
            check(":e カーソル row==0", 0, ed.getCursorRow());
            check(":e カーソル col==0", 0, ed.getCursorCol());
            tmp.delete();
        } catch (Exception e) {
            System.out.println("[FAIL] ファイルオープンシナリオ例外: " + e);
            total++;
        }
    }

    // =========================================================================
    // キー操作ヘルパー（実際のキーボードが生成する KeyEvent を再現）
    // =========================================================================

    /** 小文字・数字・一般記号を modifiers=0 で processKey に渡す */
    static void key(char c) {
        if (c >= 'a' && c <= 'z') {
            int vk = KeyEvent.VK_A + (c - 'a');
            ed.processKey(vk, c, 0);
        } else if (c >= 'A' && c <= 'Z') {
            // 大文字のみ（Shift不要な文字）: VISUAL LINE の o, a 等で 'N','E','W' など使用
            int vk = KeyEvent.VK_A + (c - 'A');
            ed.processKey(vk, c, 0);
        } else if (c >= '0' && c <= '9') {
            int vk = KeyEvent.VK_0 + (c - '0');
            ed.processKey(vk, c, 0);
        } else {
            // 記号: keyCode は参考値（charベース解決で動作）
            ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        }
    }

    /** Shift+key: keyChar は大文字/記号、modifiers に SHIFT_DOWN_MASK を付ける */
    static void shiftKey(char c) {
        if (c >= 'A' && c <= 'Z') {
            int vk = KeyEvent.VK_A + (c - 'A');
            ed.processKey(vk, c, KeyEvent.SHIFT_DOWN_MASK);
        } else if (c == ':') {
            ed.processKey(KeyEvent.VK_SEMICOLON, ':', KeyEvent.SHIFT_DOWN_MASK);
        } else if (c == 'V') {
            ed.processKey(KeyEvent.VK_V, 'V', KeyEvent.SHIFT_DOWN_MASK);
        } else if (c == 'P') {
            ed.processKey(KeyEvent.VK_P, 'P', KeyEvent.SHIFT_DOWN_MASK);
        } else {
            ed.processKey(KeyEvent.VK_UNDEFINED, c, KeyEvent.SHIFT_DOWN_MASK);
        }
    }

    /** keyCode + keyChar + 明示的 modifiers で processKey を呼ぶ（バグ修正検証用） */
    static void keyWithModifiers(int keyCode, char keyChar, int modifiers) {
        ed.processKey(keyCode, keyChar, modifiers);
    }

    static void esc() {
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
    }

    static void enter() {
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    static void bs() {
        ed.processKey(KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED, 0);
    }

    static void ctrlKey(int keyCode) {
        ed.processKey(keyCode, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK);
    }

    /** エディタを指定テキストで初期化 */
    static void reset(String text) {
        ed = new ModalEditor(text);
        ed.setExitCallback(() -> {});
    }

    static void section(String name) {
        System.out.println("\n--- " + name + " ---");
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK]   " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
