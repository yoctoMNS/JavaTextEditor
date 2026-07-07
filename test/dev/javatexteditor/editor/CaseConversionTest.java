package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;

/**
 * Vim式の大文字小文字変換（`~`・`guu`/`gUU`/`g~~`・VISUAL/VISUAL_LINE/VISUAL_BLOCKの`u`/`U`/`~`）の
 * テストハーネス（mainメソッド形式・JUnit不使用）。
 */
public class CaseConversionTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // NORMAL ~
        testTildeTogglesSingleCharAndAdvances();
        testTildeOnNonLetterNoOpButAdvances();
        testTildeAtLastCharDoesNotAdvancePastLineEnd();

        // NORMAL guu / gUU / g~~
        testGuuLowercasesCurrentLine();
        testGUULowercasesUpperOnCurrentLine();
        testGTildeTildeTogglesCurrentLine();
        testGuuDoesNotAffectOtherLines();
        testGuIncompleteSequenceFallsThrough();

        // VISUAL (charwise)
        testVisualLowerSelection();
        testVisualUpperSelection();
        testVisualToggleSelection();
        testVisualCaseReturnsToNormalAtSelectionStart();

        // VISUAL LINE
        testVisualLineUpperMultiLine();
        testVisualLineLowerOnlyAffectsSelectedLines();

        // VISUAL BLOCK
        testVisualBlockUpperOnlyColumnRange();
        testVisualBlockLowerShortLineUnaffectedOutsideRange();

        System.out.println();
        System.out.println("=== " + pass + " passed, " + fail + " failed ===");
        if (fail > 0) System.exit(1);
    }

    // -------------------------------------------------------------------------
    // NORMAL ~
    // -------------------------------------------------------------------------

    static void testTildeTogglesSingleCharAndAdvances() {
        System.out.println("[NORMAL ~: カーソル位置の1文字をtoggleしカーソルが右へ進む]");
        ModalEditor ed = new ModalEditor("aBc");
        pressKey(ed, '~');
        check("先頭文字が toggle される", ed.getText().equals("ABc"));
        check("カーソルが右へ1つ進む", ed.getCursorCol() == 1);
        pressKey(ed, '~');
        check("2文字目も toggle される", ed.getText().equals("Abc"));
        check("カーソルがさらに右へ進む", ed.getCursorCol() == 2);
    }

    static void testTildeOnNonLetterNoOpButAdvances() {
        System.out.println("[NORMAL ~: 非アルファベット文字は無変化だがカーソルは進む]");
        ModalEditor ed = new ModalEditor("1bc");
        pressKey(ed, '~');
        check("数字はそのまま", ed.getText().equals("1bc"));
        check("カーソルは右へ進む", ed.getCursorCol() == 1);
    }

    static void testTildeAtLastCharDoesNotAdvancePastLineEnd() {
        System.out.println("[NORMAL ~: 行末文字ではカーソルがクランプされる]");
        ModalEditor ed = new ModalEditor("ab");
        pressKey(ed, 'l'); // col1（行末文字）
        pressKey(ed, '~');
        check("行末文字も toggle される", ed.getText().equals("aB"));
        check("NORMALモードのクランプ規約(col<=lineLen-1)に従う", ed.getCursorCol() == 1);
    }

    // -------------------------------------------------------------------------
    // NORMAL guu / gUU / g~~
    // -------------------------------------------------------------------------

    static void testGuuLowercasesCurrentLine() {
        System.out.println("[guu: 現在行全体を小文字化]");
        ModalEditor ed = new ModalEditor("ABC\nDEF");
        pressKey(ed, 'g'); pressKey(ed, 'u'); pressKey(ed, 'u');
        check("1行目のみ小文字化される", ed.getText().equals("abc\nDEF"));
    }

    static void testGUULowercasesUpperOnCurrentLine() {
        System.out.println("[gUU: 現在行全体を大文字化]");
        ModalEditor ed = new ModalEditor("abc\ndef");
        pressKey(ed, 'g'); pressKey(ed, 'U'); pressKey(ed, 'U');
        check("1行目のみ大文字化される", ed.getText().equals("ABC\ndef"));
    }

    static void testGTildeTildeTogglesCurrentLine() {
        System.out.println("[g~~: 現在行全体をtoggle case]");
        ModalEditor ed = new ModalEditor("AbC\ndef");
        pressKey(ed, 'g'); pressKey(ed, '~'); pressKey(ed, '~');
        check("1行目のみ toggle される", ed.getText().equals("aBc\ndef"));
    }

    static void testGuuDoesNotAffectOtherLines() {
        System.out.println("[guu: カーソル行を移動してから実行すると対象行も移動する]");
        ModalEditor ed = new ModalEditor("ABC\nDEF");
        pressKey(ed, 'j'); // 2行目へ
        pressKey(ed, 'g'); pressKey(ed, 'u'); pressKey(ed, 'u');
        check("2行目のみ小文字化される", ed.getText().equals("ABC\ndef"));
    }

    static void testGuIncompleteSequenceFallsThrough() {
        System.out.println("[gu の後に無関係なキーが来たらシーケンスを破棄する（NORMALでは未束縛のESCで確認）]");
        ModalEditor ed = new ModalEditor("abc");
        pressKey(ed, 'g'); pressKey(ed, 'u');
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        check("gu の後の無関係なキーでは何も変更されない", ed.getText().equals("abc"));
        check("NORMALモードのまま", ed.isNormalMode());
    }

    // -------------------------------------------------------------------------
    // VISUAL（文字単位）
    // -------------------------------------------------------------------------

    static void testVisualLowerSelection() {
        System.out.println("[VISUAL u: 選択範囲を小文字化]");
        ModalEditor ed = new ModalEditor("ABCDE");
        pressKey(ed, 'v');
        pressKey(ed, 'l'); pressKey(ed, 'l'); // ABC を選択
        pressKey(ed, 'u');
        check("選択部分のみ小文字化", ed.getText().equals("abcDE"));
        check("NORMALへ戻る", ed.isNormalMode());
    }

    static void testVisualUpperSelection() {
        System.out.println("[VISUAL U: 選択範囲を大文字化]");
        ModalEditor ed = new ModalEditor("abcde");
        pressKey(ed, 'v');
        pressKey(ed, 'l'); pressKey(ed, 'l');
        pressKey(ed, 'U');
        check("選択部分のみ大文字化", ed.getText().equals("ABCde"));
    }

    static void testVisualToggleSelection() {
        System.out.println("[VISUAL ~: 選択範囲をtoggle case]");
        ModalEditor ed = new ModalEditor("aBcDe");
        pressKey(ed, 'v');
        pressKey(ed, 'l'); pressKey(ed, 'l');
        pressKey(ed, '~');
        check("選択部分のみtoggleされる", ed.getText().equals("AbCDe"));
    }

    static void testVisualCaseReturnsToNormalAtSelectionStart() {
        System.out.println("[VISUAL u 後: カーソルは選択開始位置に戻る]");
        ModalEditor ed = new ModalEditor("ABCDE");
        pressKey(ed, 'l'); // col1から選択開始
        pressKey(ed, 'v');
        pressKey(ed, 'l'); pressKey(ed, 'l');
        pressKey(ed, 'u');
        check("カーソルは選択開始位置(col1)", ed.getCursorCol() == 1);
    }

    // -------------------------------------------------------------------------
    // VISUAL LINE
    // -------------------------------------------------------------------------

    static void testVisualLineUpperMultiLine() {
        System.out.println("[VISUAL LINE U: 複数行を大文字化]");
        ModalEditor ed = new ModalEditor("abc\ndef\nghi");
        pressKey(ed, 'V');
        pressKey(ed, 'j');
        pressKey(ed, 'U');
        check("選択2行のみ大文字化され3行目は無変化",
                ed.getText().equals("ABC\nDEF\nghi"));
    }

    static void testVisualLineLowerOnlyAffectsSelectedLines() {
        System.out.println("[VISUAL LINE u: 選択1行のみ小文字化]");
        ModalEditor ed = new ModalEditor("ABC\nDEF");
        pressKey(ed, 'V');
        pressKey(ed, 'u');
        check("1行目のみ小文字化され2行目は無変化", ed.getText().equals("abc\nDEF"));
    }

    // -------------------------------------------------------------------------
    // VISUAL BLOCK
    // -------------------------------------------------------------------------

    static void testVisualBlockUpperOnlyColumnRange() {
        System.out.println("[VISUAL BLOCK U: 矩形の列範囲のみ大文字化]");
        ModalEditor ed = new ModalEditor("abcdef\nabcdef\nabcdef");
        pressKey(ed, 'l'); // col1へ
        pressCtrl(ed, KeyEvent.VK_V, '\0'); // アンカー=(0,1)
        pressKey(ed, 'j'); pressKey(ed, 'l'); // カーソル(1,2) 列1-2, 行0-1
        pressKey(ed, 'U');
        check("対象2行の列1-2のみ大文字化・3行目は無変化",
                ed.getText().equals(
                        "aBCdef\n" +
                        "aBCdef\n" +
                        "abcdef"));
    }

    static void testVisualBlockLowerShortLineUnaffectedOutsideRange() {
        System.out.println("[VISUAL BLOCK u: 短い行は範囲がクランプされる]");
        ModalEditor ed = new ModalEditor("ABCDEF\nAB\nABCDEF");
        pressKey(ed, 'l'); pressKey(ed, 'l'); // col2へ
        pressCtrl(ed, KeyEvent.VK_V, '\0'); // アンカー=(0,2)
        // 短い中間行(1行目, 長さ2)を j で経由すると moveCursor() が列をその場でクランプしてしまう
        // （modal-visual-block-selection スキル記載の既知の制約）ため、setCursor() で直接
        // カーソル(2,3)へジャンプして列2-3・行0-2の矩形を作る。
        ed.setCursor(2, 3);
        pressKey(ed, 'u');
        check("短い行(1行目)は列が届かないため無変化、他行は列2-3のみ小文字化",
                ed.getText().equals(
                        "ABcdEF\n" +
                        "AB\n" +
                        "ABcdEF"));
    }

    // -------------------------------------------------------------------------
    // ヘルパー
    // -------------------------------------------------------------------------

    static void pressKey(ModalEditor ed, char keyChar) {
        ed.processKey(0, keyChar, 0);
    }

    static void pressCtrl(ModalEditor ed, int keyCode, char keyChar) {
        ed.processKey(keyCode, keyChar, KeyEvent.CTRL_DOWN_MASK);
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
