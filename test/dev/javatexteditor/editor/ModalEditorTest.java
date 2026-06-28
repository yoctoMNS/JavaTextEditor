package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ModalEditor のテストハーネス（mainメソッド形式・JUnit不使用）。
 *
 * PieceTableに対してキーシーケンスを送り、getText()/getCursorRow()/getCursorCol() で検証する。
 */
public class ModalEditorTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testInitialState();
        testNormalCursorMovement();
        testNormalCursorBoundary();
        testEnterInsertModeWithI();
        testTypeTextInInsert();
        testBackspaceInInsert();
        testBackspaceAcrossLine();
        testEscapeToNormal();
        testEscapeClampsCursor();
        testAppendCommand();
        testOpenLineBelow();
        testCtrlMovementInInsert();
        testCtrlMovementBoundary();
        testCommandModeTransitions();
        testCommandSaveFile();
        testCommandLoadFile();
        testCommandErrors();
        testCommandQuit();
        testUndoKey();
        testRedoKey();
        testVisualEnter();
        testVisualEscape();
        testVisualMovement();
        testVisualYank();
        testVisualDelete();
        testPasteAfter();
        testPasteBefore();
        testDeleteChar();
        testPasteAfterCursorPosition();
        testPasteCursorAtEndOfPasted();
        // ② v5: 行単位ヤンク・VISUAL LINE モード
        testYankLine();
        testDeleteLine();
        testLinePasteAfter();
        testLinePasteBefore();
        testVisualLineEnter();
        testVisualLineMovement();
        testVisualLineYank();
        testVisualLineDelete();
        testVisualLineEscape();
        testYankTypeDefault();

        // 単語・行・ファイル移動
        testWordForwardNormal();
        testWordBackwardNormal();
        testWordEndNormal();
        testLineStartNormal();
        testLineEndNormal();
        testFileStartEndNormal();
        testGgSequence();
        testInsertWordMovement();
        testInsertLineStartEnd();
        testInsertFileStartEnd();
        testLineStartNonBlank();
        testTabInsert();
        testAutoIndentNoIndent();
        testAutoIndentPreserve();
        testAutoIndentAfterOpenBrace();
        testCloseBraceDedent();
        testCloseBraceNoChange();

        System.out.printf("%nPASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
        if (fail > 0) System.exit(1);
    }

    // -------------------------------------------------------------------------
    // 初期状態
    // -------------------------------------------------------------------------

    static void testInitialState() {
        System.out.println("[初期状態]");
        ModalEditor ed = new ModalEditor("hello");
        check("初期行=0", ed.getCursorRow() == 0);
        check("初期列=0", ed.getCursorCol() == 0);
        check("初期はNORMALモード", !ed.isInsertMode());
        check("テキスト保持", ed.getText().equals("hello"));
    }

    // -------------------------------------------------------------------------
    // NORMALモード: カーソル移動
    // -------------------------------------------------------------------------

    static void testNormalCursorMovement() {
        System.out.println("[NORMALモード: h/j/k/l移動]");
        ModalEditor ed = new ModalEditor("abc\ndef\nghi");

        pressKey(ed, 'l'); // col -> 1
        check("l: col=1", ed.getCursorCol() == 1);

        pressKey(ed, 'l'); // col -> 2
        check("l: col=2", ed.getCursorCol() == 2);

        pressKey(ed, 'h'); // col -> 1
        check("h: col=1", ed.getCursorCol() == 1);

        pressKey(ed, 'j'); // row -> 1
        check("j: row=1", ed.getCursorRow() == 1);

        pressKey(ed, 'k'); // row -> 0
        check("k: row=0", ed.getCursorRow() == 0);
    }

    static void testNormalCursorBoundary() {
        System.out.println("[NORMALモード: 境界クランプ]");
        ModalEditor ed = new ModalEditor("abc\ndef");

        // 左端を超えない
        pressKey(ed, 'h');
        check("h at col=0: col=0", ed.getCursorCol() == 0);

        // 右端を超えない ("abc" の最大col=2)
        pressKey(ed, 'l'); pressKey(ed, 'l'); pressKey(ed, 'l'); pressKey(ed, 'l');
        check("l*4 on 'abc': col=2", ed.getCursorCol() == 2);

        // 上端を超えない
        pressKey(ed, 'k');
        check("k at row=0: row=0", ed.getCursorRow() == 0);

        // 下端を超えない
        pressKey(ed, 'j'); pressKey(ed, 'j'); pressKey(ed, 'j');
        check("j*3 on 2-line: row=1", ed.getCursorRow() == 1);
    }

    // -------------------------------------------------------------------------
    // INSERTモード移行
    // -------------------------------------------------------------------------

    static void testEnterInsertModeWithI() {
        System.out.println("[INSERTモード移行: i]");
        ModalEditor ed = new ModalEditor("hello");
        pressKey(ed, 'i');
        check("i: INSERTモード", ed.isInsertMode());
        check("i: カーソル位置変わらず col=0", ed.getCursorCol() == 0);
    }

    // -------------------------------------------------------------------------
    // INSERTモード: 文字入力
    // -------------------------------------------------------------------------

    static void testTypeTextInInsert() {
        System.out.println("[INSERTモード: 文字入力]");
        ModalEditor ed = new ModalEditor("");
        pressKey(ed, 'i');
        typeString(ed, "hello");
        check("'hello'と入力: テキスト一致", ed.getText().equals("hello"));
        check("col=5", ed.getCursorCol() == 5);
    }

    // -------------------------------------------------------------------------
    // INSERTモード: Backspace
    // -------------------------------------------------------------------------

    static void testBackspaceInInsert() {
        System.out.println("[INSERTモード: Backspace（行内）]");
        ModalEditor ed = new ModalEditor("ab");
        pressKey(ed, 'i');
        // Ctrl+F で col=2 へ移動
        pressCtrl(ed, KeyEvent.VK_F, 'f');
        pressCtrl(ed, KeyEvent.VK_F, 'f');
        check("ctrl+f*2: col=2", ed.getCursorCol() == 2);

        ed.processKey(KeyEvent.VK_BACK_SPACE, '\b', 0);
        check("backspace: text='a'", ed.getText().equals("a"));
        check("backspace: col=1", ed.getCursorCol() == 1);
    }

    static void testBackspaceAcrossLine() {
        System.out.println("[INSERTモード: Backspace（行頭・行結合）]");
        ModalEditor ed = new ModalEditor("foo\nbar");
        pressKey(ed, 'j'); // NORMAL j -> row=1
        pressKey(ed, 'i'); // INSERT
        check("row=1 col=0", ed.getCursorRow() == 1 && ed.getCursorCol() == 0);

        ed.processKey(KeyEvent.VK_BACK_SPACE, '\b', 0);
        check("行頭backspace: テキスト結合", ed.getText().equals("foobar"));
        check("行頭backspace: row=0", ed.getCursorRow() == 0);
        check("行頭backspace: col=3 (fooの末尾)", ed.getCursorCol() == 3);
    }

    // -------------------------------------------------------------------------
    // ESCでNORMALモード復帰
    // -------------------------------------------------------------------------

    static void testEscapeToNormal() {
        System.out.println("[ESCでNORMALモード復帰]");
        ModalEditor ed = new ModalEditor("abc");
        pressKey(ed, 'i');
        check("INSERT中", ed.isInsertMode());
        ed.processKey(KeyEvent.VK_ESCAPE, (char) 27, 0);
        check("ESC: NORMALモード", !ed.isInsertMode());
    }

    static void testEscapeClampsCursor() {
        System.out.println("[ESC: カーソルクランプ（行末より1つ手前）]");
        ModalEditor ed = new ModalEditor("abc");
        pressKey(ed, 'i');
        typeString(ed, "xyz"); // cursor at col=3, text="xyzabc"
        // INSERT中は col=3 が有効
        check("INSERT col=3", ed.getCursorCol() == 3);
        ed.processKey(KeyEvent.VK_ESCAPE, (char) 27, 0);
        // "xyzabc" 長さ6, NORMAL最大col=5
        check("ESC後: col=3 (変化なし)", ed.getCursorCol() == 3);

        // 行末にカーソルがある場合のクランプ確認
        ModalEditor ed2 = new ModalEditor("hi");
        pressKey(ed2, 'i');
        pressCtrl(ed2, KeyEvent.VK_F, 'f');
        pressCtrl(ed2, KeyEvent.VK_F, 'f');
        check("INSERT col=2 (行末)", ed2.getCursorCol() == 2);
        ed2.processKey(KeyEvent.VK_ESCAPE, (char) 27, 0);
        check("ESC後: col=1 (len-1にクランプ)", ed2.getCursorCol() == 1);
    }

    // -------------------------------------------------------------------------
    // aコマンド（後挿入）
    // -------------------------------------------------------------------------

    static void testAppendCommand() {
        System.out.println("[aコマンド: カーソル後に挿入]");
        ModalEditor ed = new ModalEditor("xy");
        // col=0 で 'a' -> col=1, INSERT
        pressKey(ed, 'a');
        check("a: INSERTモード", ed.isInsertMode());
        check("a: col=1", ed.getCursorCol() == 1);

        typeString(ed, "Z");
        check("a+Z: text='xZy'", ed.getText().equals("xZy"));
        check("a+Z: col=2", ed.getCursorCol() == 2);
    }

    // -------------------------------------------------------------------------
    // oコマンド（下に新行を開く）
    // -------------------------------------------------------------------------

    static void testOpenLineBelow() {
        System.out.println("[oコマンド: 下に新行を開く]");
        ModalEditor ed = new ModalEditor("line1\nline2");
        pressKey(ed, 'o');
        check("o: INSERTモード", ed.isInsertMode());
        check("o: row=1", ed.getCursorRow() == 1);
        check("o: col=0", ed.getCursorCol() == 0);

        typeString(ed, "new");
        check("o+'new': text='line1\\nnew\\nline2'", ed.getText().equals("line1\nnew\nline2"));
    }

    // -------------------------------------------------------------------------
    // INSERTモード: Ctrl+F/B/N/P 移動
    // -------------------------------------------------------------------------

    static void testCtrlMovementInInsert() {
        System.out.println("[INSERTモード: Ctrl+F/B/N/P]");
        ModalEditor ed = new ModalEditor("abc\ndef");
        pressKey(ed, 'i');

        pressCtrl(ed, KeyEvent.VK_F, 'f');
        check("ctrl+f: col=1", ed.getCursorCol() == 1);

        pressCtrl(ed, KeyEvent.VK_B, 'b');
        check("ctrl+b: col=0", ed.getCursorCol() == 0);

        pressCtrl(ed, KeyEvent.VK_N, 'n');
        check("ctrl+n: row=1", ed.getCursorRow() == 1);

        pressCtrl(ed, KeyEvent.VK_P, 'p');
        check("ctrl+p: row=0", ed.getCursorRow() == 0);
    }

    static void testCtrlMovementBoundary() {
        System.out.println("[INSERTモード: Ctrl移動境界]");
        ModalEditor ed = new ModalEditor("ab\ncd");
        pressKey(ed, 'i');

        // 左端を超えない
        pressCtrl(ed, KeyEvent.VK_B, 'b');
        check("ctrl+b at col=0: col=0", ed.getCursorCol() == 0);

        // 右端（INSERT: lineLen まで）
        pressCtrl(ed, KeyEvent.VK_F, 'f');
        pressCtrl(ed, KeyEvent.VK_F, 'f');
        pressCtrl(ed, KeyEvent.VK_F, 'f');
        check("ctrl+f*3 on 'ab': col=2 (lineLen)", ed.getCursorCol() == 2);

        // 上端を超えない
        pressCtrl(ed, KeyEvent.VK_P, 'p');
        check("ctrl+p at row=0: row=0", ed.getCursorRow() == 0);

        // 下端を超えない
        pressCtrl(ed, KeyEvent.VK_N, 'n');
        pressCtrl(ed, KeyEvent.VK_N, 'n');
        check("ctrl+n*2 on 2-line: row=1", ed.getCursorRow() == 1);
    }

    // -------------------------------------------------------------------------
    // COMMMANDモード: 基本遷移
    // -------------------------------------------------------------------------

    static void testCommandModeTransitions() {
        System.out.println("[COMMMANDモード: 基本遷移]");
        ModalEditor ed = new ModalEditor("hello");

        pressKey(ed, ':');
        check("':' キーで isCommandMode()", ed.isCommandMode());
        check("':' キーで isInsertMode()=false", !ed.isInsertMode());

        ed.processKey(KeyEvent.VK_ESCAPE, (char) 27, 0);
        check("ESC で NORMAL に戻る (isCommandMode=false)", !ed.isCommandMode());
        check("ESC で NORMAL に戻る (isInsertMode=false)", !ed.isInsertMode());
        check("ESC で commandBuffer がクリアされる", ed.getCommandBuffer().isEmpty());

        pressKey(ed, ':');
        typeString(ed, "wq");
        check("文字入力で commandBuffer に 'wq' が蓄積される",
              ed.getCommandBuffer().equals("wq"));
    }

    // -------------------------------------------------------------------------
    // COMMMANDモード: :w 保存
    // -------------------------------------------------------------------------

    static void testCommandSaveFile() {
        System.out.println("[COMMMANDモード: :w 保存]");
        Path tmp = null;
        try {
            tmp = Files.createTempFile("jte-test-", ".txt");
            String tmpPath = tmp.toString();

            ModalEditor ed = new ModalEditor("save me");

            pressKey(ed, ':');
            typeString(ed, "w " + tmpPath);
            ed.processKey(KeyEvent.VK_ENTER, '\n', 0);

            check("':w <path>' でファイルが作成される", Files.exists(tmp));
            check("保存ファイルの内容がバッファの getText() と一致する",
                  Files.readString(tmp).equals(ed.getText()));

            // ':w' でパス未設定時にエラー
            ModalEditor ed2 = new ModalEditor("no path");
            pressKey(ed2, ':');
            typeString(ed2, "w");
            ed2.processKey(KeyEvent.VK_ENTER, '\n', 0);
            check("':w' でパス未設定時に statusMessage がエラー文字列になる",
                  ed2.getStatusMessage().startsWith("E:"));

        } catch (IOException e) {
            check("IOException が発生しないこと: " + e.getMessage(), false);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // COMMMANDモード: :e 開閉
    // -------------------------------------------------------------------------

    static void testCommandLoadFile() {
        System.out.println("[COMMMANDモード: :e 開閉]");
        Path tmp = null;
        try {
            tmp = Files.createTempFile("jte-load-", ".txt");
            Files.writeString(tmp, "loaded content");
            String tmpPath = tmp.toString();

            ModalEditor ed = new ModalEditor("original");
            pressKey(ed, 'l'); // カーソルを移動しておく
            pressKey(ed, 'l');

            pressKey(ed, ':');
            typeString(ed, "e " + tmpPath);
            ed.processKey(KeyEvent.VK_ENTER, '\n', 0);

            check("':e <path>' でバッファが差し替わる (getText() が新しい内容になる)",
                  ed.getText().equals("loaded content"));
            check("':e' 後にカーソルが row=0 になる", ed.getCursorRow() == 0);
            check("':e' 後にカーソルが col=0 になる", ed.getCursorCol() == 0);

        } catch (IOException e) {
            check("IOException が発生しないこと: " + e.getMessage(), false);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // COMMMANDモード: エラーケース
    // -------------------------------------------------------------------------

    static void testCommandErrors() {
        System.out.println("[COMMMANDモード: エラーケース]");
        ModalEditor ed = new ModalEditor("test");

        pressKey(ed, ':');
        typeString(ed, "foo");
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
        check("未定義コマンド ':foo' で statusMessage に \"E: unknown command\" が含まれる",
              ed.getStatusMessage().contains("E: unknown command"));
    }

    // -------------------------------------------------------------------------
    // COMMMANDモード: :q
    // -------------------------------------------------------------------------

    static void testCommandQuit() {
        System.out.println("[COMMMANDモード: :q]");
        ModalEditor ed = new ModalEditor("quit test");
        boolean[] exited = { false };
        ed.setExitCallback(() -> exited[0] = true);

        pressKey(ed, ':');
        typeString(ed, "q");
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
        check("':q' で exitCallback が呼ばれる", exited[0]);
    }

    // -------------------------------------------------------------------------
    // u キー: アンドゥ
    // -------------------------------------------------------------------------

    static void testUndoKey() {
        System.out.println("[u キー: アンドゥ]");

        // 文字1文字入力後 ESC → u でテキストが元に戻る（各 insert が1アンドゥ単位）
        ModalEditor ed = new ModalEditor("hello");
        pressKey(ed, 'i');
        typeString(ed, "X");
        ed.processKey(KeyEvent.VK_ESCAPE, (char) 27, 0);
        pressKey(ed, 'u');
        check("INSERT で文字入力後 ESC → u でテキストが前の状態に戻る",
              ed.getText().equals("hello"));

        // アンドゥ後カーソルクランプ確認
        ModalEditor ed2 = new ModalEditor("hello");
        pressKey(ed2, 'i');
        for (int i = 0; i < 5; i++) pressCtrl(ed2, KeyEvent.VK_F, 'f');
        typeString(ed2, "xyz");
        ed2.processKey(KeyEvent.VK_ESCAPE, (char) 27, 0);
        pressKey(ed2, 'u');
        pressKey(ed2, 'u');
        pressKey(ed2, 'u');
        int maxCol = Math.max(0, ed2.getText().split("\n", -1)[ed2.getCursorRow()].length() - 1);
        check("アンドゥ後にカーソルが有効範囲内にクランプされる",
              ed2.getCursorCol() <= maxCol);

        // アンドゥ履歴なし → クラッシュしない
        ModalEditor ed3 = new ModalEditor("hello");
        pressKey(ed3, 'u');
        check("アンドゥできない状態で u を押しても何も起きない（クラッシュしない）",
              ed3.getText().equals("hello"));
    }

    // -------------------------------------------------------------------------
    // Ctrl+R キー: リドゥ
    // -------------------------------------------------------------------------

    static void testRedoKey() {
        System.out.println("[Ctrl+R キー: リドゥ]");

        // undo 後に Ctrl+R でテキストが再適用される
        ModalEditor ed = new ModalEditor("hello");
        pressKey(ed, 'i');
        typeString(ed, "abc");
        ed.processKey(KeyEvent.VK_ESCAPE, (char) 27, 0);
        String textAfterInput = ed.getText();
        pressKey(ed, 'u');
        pressCtrl(ed, KeyEvent.VK_R, (char) 18);
        check("u の後 Ctrl+R → テキストが再適用される",
              ed.getText().equals(textAfterInput));

        // リドゥ履歴なし → クラッシュしない
        ModalEditor ed2 = new ModalEditor("hello");
        pressCtrl(ed2, KeyEvent.VK_R, (char) 18);
        check("リドゥできない状態で Ctrl+R を押しても何も起きない",
              ed2.getText().equals("hello"));
    }

    // -------------------------------------------------------------------------
    // VISUALモード: 基本動作
    // -------------------------------------------------------------------------

    static void testVisualEnter() {
        System.out.println("[VISUALモード: v で進入]");
        ModalEditor ed = new ModalEditor("hello");
        check("初期はNORMALモード", !ed.isVisualMode());
        pressKey(ed, 'v');
        check("v でVISUALモードに", ed.isVisualMode());
    }

    static void testVisualEscape() {
        System.out.println("[VISUALモード: ESC で脱出]");
        ModalEditor ed = new ModalEditor("hello");
        pressKey(ed, 'v');
        check("v でVISUALモード", ed.isVisualMode());
        ed.processKey(KeyEvent.VK_ESCAPE, (char) 0, 0);
        check("ESC でNORMALモード", !ed.isVisualMode());
    }

    static void testVisualMovement() {
        System.out.println("[VISUALモード: カーソル移動]");
        ModalEditor ed = new ModalEditor("abc\ndef");
        pressKey(ed, 'v');
        check("v でVISUAL", ed.isVisualMode());
        pressKey(ed, 'l');
        check("VISUAL中に l でカーソル移動", ed.getCursorCol() == 1);
        pressKey(ed, 'h');
        check("VISUAL中に h で戻る", ed.getCursorCol() == 0);
        pressKey(ed, 'j');
        check("VISUAL中に j で行移動", ed.getCursorRow() == 1);
    }

    // -------------------------------------------------------------------------
    // VISUALモード: ヤンク・削除
    // -------------------------------------------------------------------------

    static void testVisualYank() {
        System.out.println("[VISUALモード: y でヤンク]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'v');        // アンカー = (0, 0)
        pressKey(ed, 'l');        // カーソル = (0, 1)
        pressKey(ed, 'l');        // カーソル = (0, 2)
        pressKey(ed, 'y');
        check("y でNORMALに戻る", !ed.isVisualMode());
        check("yankRegister に選択分がヤンクされる", ed.getYankRegister().equals("abc"));
    }

    static void testVisualDelete() {
        System.out.println("[VISUALモード: d で削除]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'v');        // アンカー = (0, 0)
        pressKey(ed, 'l');        // カーソル = (0, 1)
        pressKey(ed, 'l');        // カーソル = (0, 2)
        pressKey(ed, 'd');
        check("d でNORMALに戻る", !ed.isVisualMode());
        check("選択範囲が削除される", ed.getText().equals("def"));
        check("yankRegister に削除分が保存", ed.getYankRegister().equals("abc"));
        check("カーソルが選択開始位置に", ed.getCursorCol() == 0);
    }

    // -------------------------------------------------------------------------
    // ペースト: p/P
    // -------------------------------------------------------------------------

    static void testPasteAfter() {
        System.out.println("[NORMALモード: p で後ろにペースト]");
        ModalEditor ed = new ModalEditor("abcdef");

        // ヤンク準備: xy でカーソル位置の "a" をヤンク
        pressKey(ed, 'v');
        pressKey(ed, 'y');
        check("yankRegister = 'a'", ed.getYankRegister().equals("a"));

        // カーソルを列2に移動して p
        pressKey(ed, 'l');
        pressKey(ed, 'l');
        pressKey(ed, 'p');
        check("p でカーソル後にペースト", ed.getText().equals("abcadef"));
        check("p後のカーソルはペーストテキスト末尾に", ed.getCursorCol() == 3);  // 'a' の位置
    }

    static void testPasteBefore() {
        System.out.println("[NORMALモード: P で前にペースト]");
        ModalEditor ed = new ModalEditor("abcdef");

        // v y でカーソル位置をヤンク
        pressKey(ed, 'v');
        pressKey(ed, 'y');

        // カーソルを列2に移動して P
        pressKey(ed, 'l');
        pressKey(ed, 'l');
        pressKey(ed, 'P');
        check("P でカーソル前にペースト", ed.getText().equals("abacdef"));
        check("P後のカーソルはペーストテキスト末尾に", ed.getCursorCol() == 2);  // 'a' の位置
    }

    static void testPasteEmptyRegister() {
        System.out.println("[NORMALモード: 空のレジスタでペースト]");
        ModalEditor ed = new ModalEditor("hello");
        check("初期は yankRegister 空", ed.getYankRegister().isEmpty());
        pressKey(ed, 'p');
        check("yankRegister 空で p を押しても何も起きない", ed.getText().equals("hello"));
        pressKey(ed, 'P');
        check("yankRegister 空で P を押しても何も起きない", ed.getText().equals("hello"));
    }

    // -------------------------------------------------------------------------
    // x: 1文字削除
    // -------------------------------------------------------------------------

    static void testDeleteChar() {
        System.out.println("[NORMALモード: x で1文字削除]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'x');
        check("x でカーソル下の 'a' が削除", ed.getText().equals("bcdef"));
        check("カーソルは位置変わらず", ed.getCursorCol() == 0);
    }

    static void testDeleteCharEolClamping() {
        System.out.println("[NORMALモード: x で行末文字削除時のクランプ]");
        ModalEditor ed = new ModalEditor("abc");
        pressKey(ed, 'l');
        pressKey(ed, 'l');
        check("カーソルが 'c' 上", ed.getCursorCol() == 2);
        pressKey(ed, 'x');
        check("x で 'c' が削除", ed.getText().equals("ab"));
        check("カーソルが行末を超えないように調整", ed.getCursorCol() == 1);
    }

    static void testDeleteCharEmptyLine() {
        System.out.println("[NORMALモード: x で空行]");
        ModalEditor ed = new ModalEditor("a\n\nb");
        pressKey(ed, 'j');
        check("2行目（空行）に移動", ed.getCursorRow() == 1);
        pressKey(ed, 'x');
        check("空行で x を押してもクラッシュしない", ed.getText().equals("a\n\nb"));
    }

    static void testPasteAfterCursorPosition() {
        System.out.println("[NORMALモード: p 後のカーソル位置（複数文字）]");
        ModalEditor ed = new ModalEditor("abc");

        // "ab" をヤンク
        pressKey(ed, 'v');
        pressKey(ed, 'l');
        pressKey(ed, 'y');
        check("yankRegister=\"ab\"", ed.getYankRegister().equals("ab"));

        // Vim仕様: v y 後カーソルは選択開始(col=0)に戻るため、col=2 へは l を2回押す
        pressKey(ed, 'l');
        pressKey(ed, 'l');
        pressKey(ed, 'p');
        // cursor=(0,2) で offset=3, "abc"のoffset 3に"ab"を挿入 → "abcab"
        check("p でペースト: abc → abcab", ed.getText().equals("abcab"));
        // newOffset = 3 + 2 - 1 = 4 = 末尾の'b'
        check("p後のカーソルは末尾: col=4", ed.getCursorCol() == 4);
    }

    static void testPasteCursorAtEndOfPasted() {
        System.out.println("[NORMALモード: p/P でカーソルがペーストテキスト末尾に]");
        ModalEditor ed = new ModalEditor("12345");

        // "12" をヤンク
        pressKey(ed, 'v');
        pressKey(ed, 'l');
        pressKey(ed, 'y');

        // Vim仕様: v y 後カーソルは選択開始(col=0)に戻るため、col=4 へは l を4回押す
        pressKey(ed, 'l');
        pressKey(ed, 'l');
        pressKey(ed, 'l');
        pressKey(ed, 'l');

        // p でペースト
        pressKey(ed, 'p');
        // cursor=(0,4) で offset=5, "12345"のoffset 5に"12"を挿入 → "1234512"
        check("p: 12345 → 1234512", ed.getText().equals("1234512"));
        // newOffset = 5 + 2 - 1 = 6 = 末尾の'2'
        check("カーソルは末尾の'2'上: col=6", ed.getCursorCol() == 6);
    }

    // -------------------------------------------------------------------------
    // ② v5: yy — 行単位ヤンク
    // -------------------------------------------------------------------------

    static void testYankLine() {
        System.out.println("[NORMALモード: yy で行ヤンク]");

        // 単一行ファイルで yy
        ModalEditor ed = new ModalEditor("hello");
        pressKey(ed, 'y');
        pressKey(ed, 'y');
        check("yy で yankRegister = 'hello\\n'", ed.getYankRegister().equals("hello\n"));
        check("yy の yankType = 'line'", ed.getYankType().equals("line"));
        check("yy 後もカーソルは同じ行", ed.getCursorRow() == 0);

        // 複数行ファイルの先頭行で yy
        ModalEditor ed2 = new ModalEditor("line0\nline1\nline2");
        pressKey(ed2, 'y');
        pressKey(ed2, 'y');
        check("先頭行で yy → 'line0\\n'", ed2.getYankRegister().equals("line0\n"));

        // 複数行ファイルの中間行で yy
        ModalEditor ed3 = new ModalEditor("line0\nline1\nline2");
        pressKey(ed3, 'j');
        pressKey(ed3, 'y');
        pressKey(ed3, 'y');
        check("中間行で yy → 'line1\\n'", ed3.getYankRegister().equals("line1\n"));

        // 最終行で yy
        ModalEditor ed4 = new ModalEditor("line0\nline1");
        pressKey(ed4, 'j');
        pressKey(ed4, 'y');
        pressKey(ed4, 'y');
        check("最終行で yy → 'line1\\n'", ed4.getYankRegister().equals("line1\n"));

        // yy でテキストは変化しない
        ModalEditor ed5 = new ModalEditor("abc\ndef");
        pressKey(ed5, 'y');
        pressKey(ed5, 'y');
        check("yy でテキストが変化しない", ed5.getText().equals("abc\ndef"));
    }

    // -------------------------------------------------------------------------
    // ② v5: dd — 行削除ヤンク
    // -------------------------------------------------------------------------

    static void testDeleteLine() {
        System.out.println("[NORMALモード: dd で行削除・ヤンク]");

        // 中間行を dd
        ModalEditor ed = new ModalEditor("line0\nline1\nline2");
        pressKey(ed, 'j');          // row1
        pressKey(ed, 'd');
        pressKey(ed, 'd');
        check("中間行 dd → テキストから削除", ed.getText().equals("line0\nline2"));
        check("中間行 dd → yankRegister='line1\\n'", ed.getYankRegister().equals("line1\n"));
        check("中間行 dd → yankType='line'", ed.getYankType().equals("line"));
        check("中間行 dd → カーソルが同じ行番号", ed.getCursorRow() == 1);

        // 先頭行を dd（複数行）
        ModalEditor ed2 = new ModalEditor("line0\nline1\nline2");
        pressKey(ed2, 'd');
        pressKey(ed2, 'd');
        check("先頭行 dd → 'line1\\nline2'", ed2.getText().equals("line1\nline2"));
        check("先頭行 dd → カーソルrow=0", ed2.getCursorRow() == 0);

        // 最終行を dd（他の行あり）
        ModalEditor ed3 = new ModalEditor("line0\nline1\nline2");
        pressKey(ed3, 'j');
        pressKey(ed3, 'j');         // row2 (最終行)
        pressKey(ed3, 'd');
        pressKey(ed3, 'd');
        check("最終行 dd → 'line0\\nline1'", ed3.getText().equals("line0\nline1"));
        check("最終行 dd → カーソルが前の行へ", ed3.getCursorRow() == 1);

        // 唯一の行を dd
        ModalEditor ed4 = new ModalEditor("only");
        pressKey(ed4, 'd');
        pressKey(ed4, 'd');
        check("唯一行 dd → テキストが空", ed4.getText().equals(""));
        check("唯一行 dd → row=0", ed4.getCursorRow() == 0);

        // y が来なければ dd にならない
        ModalEditor ed5 = new ModalEditor("hello");
        pressKey(ed5, 'd');
        pressKey(ed5, 'h');         // 'd' + 'h' はシーケンス不成立、h はカーソル移動
        check("d + h でテキスト変化なし", ed5.getText().equals("hello"));
    }

    // -------------------------------------------------------------------------
    // ② v5: 行ヤンク後の p（下に貼り付け）
    // -------------------------------------------------------------------------

    static void testLinePasteAfter() {
        System.out.println("[NORMALモード: 行ヤンク後 p で下に貼り付け]");

        // 中間行にペースト
        ModalEditor ed = new ModalEditor("line0\nline1\nline2");
        pressKey(ed, 'y');
        pressKey(ed, 'y');          // "line0\n" をヤンク
        pressKey(ed, 'p');
        check("先頭行ヤンク→p で1行下に挿入", ed.getText().equals("line0\nline0\nline1\nline2"));
        check("p後カーソルが貼り付け行(row=1)に", ed.getCursorRow() == 1);
        check("p後カーソルのcol=0", ed.getCursorCol() == 0);

        // 最終行にペースト
        ModalEditor ed2 = new ModalEditor("line0\nline1");
        pressKey(ed2, 'j');         // row1 (最終行)
        pressKey(ed2, 'y');
        pressKey(ed2, 'y');         // "line1\n" をヤンク
        pressKey(ed2, 'p');
        check("最終行ヤンク→p で最終行の下に挿入", ed2.getText().equals("line0\nline1\nline1"));
        check("p後カーソルが row=2", ed2.getCursorRow() == 2);

        // dd後にpで行を移動
        ModalEditor ed3 = new ModalEditor("line0\nline1\nline2");
        pressKey(ed3, 'j');         // row1
        pressKey(ed3, 'd');
        pressKey(ed3, 'd');         // "line1\n" を削除・ヤンク
        pressKey(ed3, 'p');         // "line1" を row2(現row1=line2) の下に貼り付け
        check("dd→p でテキスト復元", ed3.getText().equals("line0\nline2\nline1"));
    }

    // -------------------------------------------------------------------------
    // ② v5: 行ヤンク後の P（上に貼り付け）
    // -------------------------------------------------------------------------

    static void testLinePasteBefore() {
        System.out.println("[NORMALモード: 行ヤンク後 P で上に貼り付け]");

        // 中間行の上にペースト
        ModalEditor ed = new ModalEditor("line0\nline1\nline2");
        pressKey(ed, 'j');          // row1
        pressKey(ed, 'y');
        pressKey(ed, 'y');          // "line1\n" をヤンク
        pressKey(ed, 'P');
        check("P で現在行の上に挿入", ed.getText().equals("line0\nline1\nline1\nline2"));
        check("P後カーソルが貼り付け行(row=1)に", ed.getCursorRow() == 1);
        check("P後カーソルのcol=0", ed.getCursorCol() == 0);

        // 先頭行の上にペースト
        ModalEditor ed2 = new ModalEditor("line0\nline1");
        pressKey(ed2, 'y');
        pressKey(ed2, 'y');         // "line0\n" をヤンク
        pressKey(ed2, 'P');
        check("先頭行 P で先頭に挿入", ed2.getText().equals("line0\nline0\nline1"));
        check("P後カーソルが row=0", ed2.getCursorRow() == 0);
    }

    // -------------------------------------------------------------------------
    // ② v5: VISUAL LINE モード進入
    // -------------------------------------------------------------------------

    static void testVisualLineEnter() {
        System.out.println("[VISUAL LINEモード: V で進入]");
        ModalEditor ed = new ModalEditor("line0\nline1\nline2");
        check("初期は VISUAL LINE でない", !ed.isVisualLineMode());
        pressKey(ed, 'V');
        check("V で VISUAL LINE モードに", ed.isVisualLineMode());
        check("NORMAL/INSERT/VISUAL/COMMAND でない", !ed.isInsertMode() && !ed.isVisualMode());
    }

    // -------------------------------------------------------------------------
    // ② v5: VISUAL LINE モード移動
    // -------------------------------------------------------------------------

    static void testVisualLineMovement() {
        System.out.println("[VISUAL LINEモード: j/k で行移動]");
        ModalEditor ed = new ModalEditor("line0\nline1\nline2");
        pressKey(ed, 'V');
        check("V でVISUAL LINE、anchorRow=0", ed.isVisualLineMode());

        pressKey(ed, 'j');
        check("VISUAL LINE中 j で row=1", ed.getCursorRow() == 1);

        pressKey(ed, 'j');
        check("VISUAL LINE中 j で row=2", ed.getCursorRow() == 2);

        pressKey(ed, 'k');
        check("VISUAL LINE中 k で row=1", ed.getCursorRow() == 1);
    }

    // -------------------------------------------------------------------------
    // ② v5: VISUAL LINE y でヤンク
    // -------------------------------------------------------------------------

    static void testVisualLineYank() {
        System.out.println("[VISUAL LINEモード: y でヤンク]");

        // 1行選択
        ModalEditor ed = new ModalEditor("line0\nline1\nline2");
        pressKey(ed, 'V');
        pressKey(ed, 'y');
        check("V y で1行ヤンク (yankType=line)", ed.getYankType().equals("line"));
        check("V y で 'line0\\n' がヤンク", ed.getYankRegister().equals("line0\n"));
        check("y 後NORMAL モードに戻る", !ed.isVisualLineMode());
        check("y 後カーソルがanchorRowに", ed.getCursorRow() == 0);

        // 複数行選択
        ModalEditor ed2 = new ModalEditor("line0\nline1\nline2");
        pressKey(ed2, 'V');         // anchorRow=0
        pressKey(ed2, 'j');         // cursorRow=1
        pressKey(ed2, 'y');
        check("2行選択 y で 'line0\\nline1\\n'", ed2.getYankRegister().equals("line0\nline1\n"));
        check("2行選択 y 後カーソルがanchorRow=0に", ed2.getCursorRow() == 0);

        // 上方向選択（k）でもヤンク範囲が正しい
        ModalEditor ed3 = new ModalEditor("line0\nline1\nline2");
        pressKey(ed3, 'j');
        pressKey(ed3, 'j');         // row=2
        pressKey(ed3, 'V');         // anchorRow=2
        pressKey(ed3, 'k');         // cursorRow=1
        pressKey(ed3, 'y');
        check("上方向選択 y で 'line1\\nline2\\n'", ed3.getYankRegister().equals("line1\nline2\n"));
        check("上方向選択 y 後カーソルがmin行=1に", ed3.getCursorRow() == 1);
    }

    // -------------------------------------------------------------------------
    // ② v5: VISUAL LINE d で削除
    // -------------------------------------------------------------------------

    static void testVisualLineDelete() {
        System.out.println("[VISUAL LINEモード: d で削除]");

        // 中間行を削除
        ModalEditor ed = new ModalEditor("line0\nline1\nline2");
        pressKey(ed, 'j');          // row1
        pressKey(ed, 'V');          // anchorRow=1
        pressKey(ed, 'd');
        check("VISUAL LINE d で中間行削除", ed.getText().equals("line0\nline2"));
        check("d 後 yankRegister='line1\\n'", ed.getYankRegister().equals("line1\n"));
        check("d 後 yankType='line'", ed.getYankType().equals("line"));
        check("d 後 NORMALモードに戻る", !ed.isVisualLineMode());
        check("d 後カーソルがrow=1に", ed.getCursorRow() == 1);

        // 複数行削除
        ModalEditor ed2 = new ModalEditor("line0\nline1\nline2\nline3");
        pressKey(ed2, 'j');         // row1
        pressKey(ed2, 'V');         // anchorRow=1
        pressKey(ed2, 'j');         // cursorRow=2
        pressKey(ed2, 'd');
        check("2行選択 d で削除", ed2.getText().equals("line0\nline3"));
        check("2行選択 d のヤンク", ed2.getYankRegister().equals("line1\nline2\n"));

        // 全行削除
        ModalEditor ed3 = new ModalEditor("line0\nline1");
        pressKey(ed3, 'V');         // row0
        pressKey(ed3, 'j');         // row1
        pressKey(ed3, 'd');
        check("全行選択 d でテキストが空", ed3.getText().equals(""));
        check("全行削除後 row=0", ed3.getCursorRow() == 0);
    }

    // -------------------------------------------------------------------------
    // ② v5: VISUAL LINE ESC で脱出
    // -------------------------------------------------------------------------

    static void testVisualLineEscape() {
        System.out.println("[VISUAL LINEモード: ESC でNORMAL復帰]");
        ModalEditor ed = new ModalEditor("line0\nline1");
        pressKey(ed, 'V');
        check("V でVISUAL LINE", ed.isVisualLineMode());
        ed.processKey(KeyEvent.VK_ESCAPE, (char) 0, 0);
        check("ESC でNORMALに戻る", !ed.isVisualLineMode());
        check("テキスト変化なし", ed.getText().equals("line0\nline1"));
    }

    // -------------------------------------------------------------------------
    // ② v5: デフォルト yankType は "char"
    // -------------------------------------------------------------------------

    static void testYankTypeDefault() {
        System.out.println("[yankType: デフォルトは 'char'・文字ヤンク後も 'char']");
        ModalEditor ed = new ModalEditor("abcdef");
        check("初期 yankType='char'", ed.getYankType().equals("char"));

        // VISUAL で文字ヤンク後も 'char'
        pressKey(ed, 'v');
        pressKey(ed, 'l');
        pressKey(ed, 'y');
        check("文字ヤンク後 yankType='char'", ed.getYankType().equals("char"));

        // 行ヤンク後は 'line'
        pressKey(ed, 'y');
        pressKey(ed, 'y');
        check("行ヤンク後 yankType='line'", ed.getYankType().equals("line"));

        // 再度 文字ヤンクで 'char' に戻る
        pressKey(ed, 'v');
        pressKey(ed, 'y');
        check("再度文字ヤンクで yankType='char'", ed.getYankType().equals("char"));
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // 単語・行・ファイル移動
    // -------------------------------------------------------------------------

    static void testWordForwardNormal() {
        System.out.println("--- word.forward (NORMAL: w) ---");
        // "hello world foo"
        ModalEditor ed = new ModalEditor("hello world foo", null, null);
        // 初期: row=0, col=0
        pressKey(ed, 'w');
        check("w: hello→world (col=6)", ed.getCursorCol() == 6);
        pressKey(ed, 'w');
        check("w: world→foo (col=12)", ed.getCursorCol() == 12);
        pressKey(ed, 'w');
        check("w: foo→末尾で止まる", ed.getCursorCol() == 12 || ed.getCursorCol() == 15);
    }

    static void testWordBackwardNormal() {
        System.out.println("--- word.backward (NORMAL: b) ---");
        ModalEditor ed = new ModalEditor("hello world foo", null, null);
        // col=12 に移動してから
        pressKey(ed, 'w'); pressKey(ed, 'w'); // col=12
        pressKey(ed, 'b');
        check("b: foo→world先頭 (col=6)", ed.getCursorCol() == 6);
        pressKey(ed, 'b');
        check("b: world→hello先頭 (col=0)", ed.getCursorCol() == 0);
    }

    static void testWordEndNormal() {
        System.out.println("--- word.end (NORMAL: e) ---");
        ModalEditor ed = new ModalEditor("hello world", null, null);
        pressKey(ed, 'e');
        check("e: hello末尾 (col=4)", ed.getCursorCol() == 4);
        pressKey(ed, 'e');
        check("e: world末尾 (col=10)", ed.getCursorCol() == 10);
    }

    static void testLineStartNormal() {
        System.out.println("--- line.start (NORMAL: 0) ---");
        ModalEditor ed = new ModalEditor("hello\nworld", null, null);
        pressKey(ed, 'w'); // col=6は次行、とにかく右へ
        // まず右に動かす
        pressKey(ed, 'l'); pressKey(ed, 'l');
        int col = ed.getCursorCol();
        pressKey(ed, '0');
        check("0: 行頭へ (col=0)", ed.getCursorCol() == 0);
    }

    static void testLineEndNormal() {
        System.out.println("--- line.end (NORMAL: $) ---");
        ModalEditor ed = new ModalEditor("hello\nworld", null, null);
        pressKey(ed, '$');
        check("$: hello末尾 (col=4)", ed.getCursorCol() == 4);
    }

    static void testFileStartEndNormal() {
        System.out.println("--- file.end (NORMAL: G) / file.start (NORMAL: gg) ---");
        ModalEditor ed = new ModalEditor("line1\nline2\nline3", null, null);
        pressKey(ed, 'G');
        check("G: 最終行 (row=2)", ed.getCursorRow() == 2);
        check("G: 最終行の有効列", ed.getCursorCol() >= 0);
    }

    static void testGgSequence() {
        System.out.println("--- gg: ファイル先頭へ ---");
        ModalEditor ed = new ModalEditor("line1\nline2\nline3", null, null);
        pressKey(ed, 'G'); // row=2
        pressKey(ed, 'g');
        pressKey(ed, 'g');
        check("gg: row=0", ed.getCursorRow() == 0);
        check("gg: col=0", ed.getCursorCol() == 0);
    }

    static void testInsertWordMovement() {
        System.out.println("--- INSERT: Alt+F/Alt+B ---");
        ModalEditor ed = new ModalEditor("hello world", null, null);
        pressKey(ed, 'i'); // INSERT
        ed.processKey(KeyEvent.VK_F, KeyEvent.CHAR_UNDEFINED, KeyEvent.ALT_DOWN_MASK);
        check("Alt+F: hello次 (col=5 or 6)", ed.getCursorCol() >= 5);
        ed.processKey(KeyEvent.VK_B, KeyEvent.CHAR_UNDEFINED, KeyEvent.ALT_DOWN_MASK);
        check("Alt+B: 戻る (col <= 6)", ed.getCursorCol() <= 6);
    }

    static void testInsertLineStartEnd() {
        System.out.println("--- INSERT: Ctrl+A / Ctrl+E ---");
        ModalEditor ed = new ModalEditor("hello world", null, null);
        pressKey(ed, 'i'); // INSERT
        ed.processKey(KeyEvent.VK_F, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK); // 右
        ed.processKey(KeyEvent.VK_F, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK);
        ed.processKey(KeyEvent.VK_A, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK); // Ctrl+A
        check("Ctrl+A: col=0", ed.getCursorCol() == 0);
        ed.processKey(KeyEvent.VK_E, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK); // Ctrl+E
        check("Ctrl+E: 行末 (col=11)", ed.getCursorCol() == 11);
    }

    static void testInsertFileStartEnd() {
        System.out.println("--- INSERT: Ctrl+Home / Ctrl+End ---");
        ModalEditor ed = new ModalEditor("line1\nline2\nline3", null, null);
        pressKey(ed, 'i'); // INSERT
        ed.processKey(KeyEvent.VK_END, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK);
        check("Ctrl+End: 最終行 (row=2)", ed.getCursorRow() == 2);
        ed.processKey(KeyEvent.VK_HOME, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK);
        check("Ctrl+Home: row=0", ed.getCursorRow() == 0);
        check("Ctrl+Home: col=0", ed.getCursorCol() == 0);
    }

    static void testLineStartNonBlank() {
        System.out.println("--- line.start.nonblank (NORMAL: ^) ---");
        ModalEditor ed = new ModalEditor("   hello world", null, null);
        // $で行末へ
        pressKey(ed, '$');
        check("^の前: 行末にいる", ed.getCursorCol() == 13);
        pressKey(ed, '^');
        check("^: 最初の非空白 (col=3)", ed.getCursorCol() == 3);

        // インデントなし行では col=0
        ModalEditor ed2 = new ModalEditor("hello", null, null);
        pressKey(ed2, '$');
        pressKey(ed2, '^');
        check("^: インデントなし → col=0", ed2.getCursorCol() == 0);

        // 全空白行では行末（またはcol=0）
        ModalEditor ed3 = new ModalEditor("   ", null, null);
        pressKey(ed3, '^');
        check("^: 全空白行 → col=行末以下", ed3.getCursorCol() <= 3);
    }

    // -------------------------------------------------------------------------
    // Tab / 自動インデント
    // -------------------------------------------------------------------------

    static void testTabInsert() {
        System.out.println("--- Tab: 4スペース挿入 ---");
        ModalEditor ed = new ModalEditor("", null, null);
        pressKey(ed, 'i');
        ed.processKey(KeyEvent.VK_TAB, '\t', 0);
        check("Tab: テキストに4スペース", ed.getText().equals("    "));
        check("Tab: col=4", ed.getCursorCol() == 4);
    }

    static void testAutoIndentNoIndent() {
        System.out.println("--- 自動インデント: インデントなし行 ---");
        ModalEditor ed = new ModalEditor("hello", null, null);
        pressKey(ed, '$'); pressKey(ed, 'a'); // 行末INSERT
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
        String[] lines = ed.getText().split("\n", -1);
        check("Enter後に2行", lines.length == 2);
        check("2行目はインデントなし", lines[1].equals(""));
        check("col=0", ed.getCursorCol() == 0);
    }

    static void testAutoIndentPreserve() {
        System.out.println("--- 自動インデント: インデント継承 ---");
        ModalEditor ed = new ModalEditor("    hello", null, null);
        pressKey(ed, '$'); pressKey(ed, 'a');
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
        String[] lines = ed.getText().split("\n", -1);
        check("2行目が4スペースインデント", lines[1].startsWith("    "));
        check("col=4", ed.getCursorCol() == 4);
    }

    static void testAutoIndentAfterOpenBrace() {
        System.out.println("--- 自動インデント: { 後に追加インデント ---");
        ModalEditor ed = new ModalEditor("public void foo() {", null, null);
        pressKey(ed, '$'); pressKey(ed, 'a');
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
        String[] lines = ed.getText().split("\n", -1);
        check("{ 後の行が4スペース", lines[1].equals("    "));
        check("col=4", ed.getCursorCol() == 4);
    }

    static void testCloseBraceDedent() {
        System.out.println("--- } 入力: インデント1レベル下げ ---");
        // "public void foo() {\n    " という状態から } を入力
        ModalEditor ed = new ModalEditor("public void foo() {\n    ", null, null);
        // 2行目末（col=4）でINSERT
        pressKey(ed, 'j');        // row=1
        pressKey(ed, '$');        // col=3 (NORMAL末尾)
        pressKey(ed, 'a');        // INSERT, col=4
        ed.processKey(0, '}', 0);
        String[] lines = ed.getText().split("\n", -1);
        check("} で行が '}'", lines[1].equals("}"));
        check("col=1", ed.getCursorCol() == 1);
    }

    static void testCloseBraceNoChange() {
        System.out.println("--- } 入力: 通常行では位置を変えずに挿入 ---");
        ModalEditor ed = new ModalEditor("foo", null, null);
        pressKey(ed, '$'); pressKey(ed, 'a'); // 行末INSERT col=3
        ed.processKey(0, '}', 0);
        check("} がそのまま挿入される", ed.getText().equals("foo}"));
        check("col=4", ed.getCursorCol() == 4);
    }

    // -------------------------------------------------------------------------
    // ユーティリティ
    // -------------------------------------------------------------------------

    /** NORMALモードのキー（keyCharのみ使用） */
    static void pressKey(ModalEditor ed, char keyChar) {
        ed.processKey(0, keyChar, 0);
    }

    /** Ctrl修飾付きキー */
    static void pressCtrl(ModalEditor ed, int keyCode, char keyChar) {
        ed.processKey(keyCode, keyChar, KeyEvent.CTRL_DOWN_MASK);
    }

    /** INSERT中に文字列を1文字ずつ入力する */
    static void typeString(ModalEditor ed, String s) {
        for (char c : s.toCharArray()) {
            ed.processKey(0, c, 0);
        }
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
