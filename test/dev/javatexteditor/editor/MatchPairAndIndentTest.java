package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.util.OptionalInt;

/**
 * `%`（対応する括弧へのジャンプ）と Visual `>`/`<`（インデントシフト）・`gv`
 * （直前 Visual 選択の再選択）のテストハーネス（mainメソッド形式・JUnit不使用）。
 */
public class MatchPairAndIndentTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        // %
        testMatchOpenToClose();
        testMatchCloseToOpen();
        testMatchNested();
        testVisualExtendToMatch();
        testMatchInvalidPositionNoOp();

        // Visual > <
        testCharwiseVisualIndentRight();
        testCharwiseVisualIndentLeft();
        testLinewiseVisualIndentRightMultiLine();
        testLinewiseVisualIndentLeftMultiLine();
        testBlockwiseVisualIndentOnlyRectangle();
        testRightShiftSkipsEmptyLine();
        testLeftShiftClampsAtZero();
        testExpandtabTrueUsesSpacesOnly();
        testExpandtabFalseUsesTabs();
        testShiftroundRounds();

        // gv
        testGvAfterIndentRight();
        testGvAfterIndentLeft();
        testGvRestoresVisualKind();

        System.out.println();
        System.out.println("=== " + pass + " passed, " + fail + " failed ===");
        if (fail > 0) System.exit(1);
    }

    // -------------------------------------------------------------------------
    // %
    // -------------------------------------------------------------------------

    static void testMatchOpenToClose() {
        System.out.println("[%: 開き括弧から対応する閉じ括弧へ]");
        OptionalInt m = MatchPairs.findMatch("(abc)", 0);
        check("対応する ) の位置", m.isPresent() && m.getAsInt() == 4);
    }

    static void testMatchCloseToOpen() {
        System.out.println("[%: 閉じ括弧から対応する開き括弧へ]");
        OptionalInt m = MatchPairs.findMatch("(abc)", 4);
        check("対応する ( の位置", m.isPresent() && m.getAsInt() == 0);
    }

    static void testMatchNested() {
        System.out.println("[%: ネストした括弧で正しい対応先へ]");
        // index:            0123456789
        String text = "a(b(c)d)e";
        OptionalInt outer = MatchPairs.findMatch(text, 1); // 外側の (
        check("外側の ( は外側の ) (index 7) へ", outer.isPresent() && outer.getAsInt() == 7);
        OptionalInt inner = MatchPairs.findMatch(text, 3); // 内側の (
        check("内側の ( は内側の ) (index 5) へ", inner.isPresent() && inner.getAsInt() == 5);
        OptionalInt innerClose = MatchPairs.findMatch(text, 5); // 内側の )
        check("内側の ) は内側の ( (index 3) へ", innerClose.isPresent() && innerClose.getAsInt() == 3);
    }

    static void testVisualExtendToMatch() {
        System.out.println("[VISUAL中 % で選択が対応括弧まで拡張される]");
        ModalEditor ed = new ModalEditor("(abc)");
        pressKey(ed, 'v');   // アンカー = (0,0)
        pressKey(ed, '%');   // カーソルを対応する ) (index4) へ
        check("VISUALモードのまま", ed.isVisualMode());
        check("カーソルが index4 (col4) に移動", ed.getCursorCol() == 4);
        pressKey(ed, 'y');
        check("選択範囲全体がヤンクされる", ed.getYankRegister().equals("(abc)"));
    }

    static void testMatchInvalidPositionNoOp() {
        System.out.println("[%: 括弧上でなければ no-op]");
        ModalEditor ed = new ModalEditor("abc");
        pressKey(ed, '%');
        check("カーソル行は変化しない", ed.getCursorRow() == 0);
        check("カーソル列は変化しない", ed.getCursorCol() == 0);
        check("テキストも変化しない", ed.getText().equals("abc"));
    }

    // -------------------------------------------------------------------------
    // Visual > <
    // -------------------------------------------------------------------------

    static void testCharwiseVisualIndentRight() {
        System.out.println("[charwise VISUAL: > で対象行全体が右シフト]");
        ModalEditor ed = new ModalEditor("abc\ndef");
        pressKey(ed, 'l');  // カーソル col1（行途中から選択開始）
        pressKey(ed, 'v');
        pressKey(ed, '>');
        check("選択行(0行目)のみ4スペース分右シフト", ed.getText().equals("    abc\ndef"));
        check("> 後は NORMAL に戻る", ed.isNormalMode());
    }

    static void testCharwiseVisualIndentLeft() {
        System.out.println("[charwise VISUAL: < で対象行全体が左シフト]");
        ModalEditor ed = new ModalEditor("    abc\ndef");
        pressKey(ed, 'l'); pressKey(ed, 'l'); // 行途中(インデント内)から選択開始
        pressKey(ed, 'v');
        pressKey(ed, '<');
        check("選択行(0行目)のみ4スペース分左シフト", ed.getText().equals("abc\ndef"));
    }

    static void testLinewiseVisualIndentRightMultiLine() {
        System.out.println("[linewise VISUAL: > で複数行右シフト]");
        ModalEditor ed = new ModalEditor("abc\ndef\nghi");
        pressKey(ed, 'V');
        pressKey(ed, 'j');
        pressKey(ed, '>');
        check("選択2行のみ右シフトされ3行目は無変化",
                ed.getText().equals("    abc\n    def\nghi"));
    }

    static void testLinewiseVisualIndentLeftMultiLine() {
        System.out.println("[linewise VISUAL: < で複数行左シフト]");
        ModalEditor ed = new ModalEditor("    abc\n    def\nghi");
        pressKey(ed, 'V');
        pressKey(ed, 'j');
        pressKey(ed, '<');
        check("選択2行のみ左シフトされ3行目は無変化",
                ed.getText().equals("abc\ndef\nghi"));
    }

    static void testBlockwiseVisualIndentOnlyRectangle() {
        System.out.println("[blockwise VISUAL: > は矩形領域(列c1以降)だけをシフトする]");
        ModalEditor ed = new ModalEditor("0123456789\n0123456789\n0123456789");
        pressKey(ed, 'l'); pressKey(ed, 'l'); pressKey(ed, 'l'); // col3へ
        pressCtrl(ed, KeyEvent.VK_V, '\0'); // アンカー=(0,3)
        pressKey(ed, 'j');                  // カーソル行1へ拡張
        pressKey(ed, '>');
        check("対象2行の列3位置に4スペース挿入・3行目は無変化",
                ed.getText().equals(
                        "012    3456789\n" +
                        "012    3456789\n" +
                        "0123456789"));
    }

    static void testRightShiftSkipsEmptyLine() {
        System.out.println("[空行は右シフトで変更しない]");
        ModalEditor ed = new ModalEditor("abc\n\ndef");
        pressKey(ed, 'V');
        pressKey(ed, 'j');
        pressKey(ed, 'j');
        pressKey(ed, '>');
        check("空行(2行目)は無変化のまま前後だけシフト",
                ed.getText().equals("    abc\n\n    def"));
    }

    static void testLeftShiftClampsAtZero() {
        System.out.println("[左シフトでインデントが0未満にならない]");
        ModalEditor ed = new ModalEditor("ab\ncd");
        pressKey(ed, 'V');
        pressKey(ed, 'j');
        pressKey(ed, '<');
        check("インデントが無い行は変化しない（0未満にならない）",
                ed.getText().equals("ab\ncd"));
    }

    static void testExpandtabTrueUsesSpacesOnly() {
        System.out.println("[expandtab=true: スペースのみになる]");
        ModalEditor ed = new ModalEditor("abc");
        ed.getIndentSettings().setExpandtab(true);
        pressKey(ed, 'V');
        pressKey(ed, '>');
        check("タブを含まずスペースのみで4桁インデント",
                ed.getText().equals("    abc") && !ed.getText().contains("\t"));
    }

    static void testExpandtabFalseUsesTabs() {
        System.out.println("[expandtab=false: 必要に応じてタブを使う]");
        ModalEditor ed = new ModalEditor("abc");
        ed.getIndentSettings().setExpandtab(false);
        ed.getIndentSettings().setTabstop(4);
        ed.getIndentSettings().setShiftwidth(4);
        pressKey(ed, 'V');
        pressKey(ed, '>');
        check("shiftwidth=tabstop=4 のためタブ1つでインデント",
                ed.getText().equals("\tabc"));
    }

    static void testShiftroundRounds() {
        System.out.println("[shiftround=true: shiftwidthの倍数に丸める]");
        ModalEditor ed = new ModalEditor("   abc"); // 幅3のインデント
        ed.getIndentSettings().setShiftround(true);
        ed.getIndentSettings().setShiftwidth(4);
        pressKey(ed, 'V');
        pressKey(ed, '>');
        // Vim本家のshift_line()と同じ式: 3を4の倍数に切り下げ(0)、shiftwidthを1回分加算 → 4
        check("丸めなしなら幅7になるところ、丸めで幅4になる",
                ed.getText().equals("    abc"));
    }

    // -------------------------------------------------------------------------
    // gv
    // -------------------------------------------------------------------------

    static void testGvAfterIndentRight() {
        System.out.println("[VISUAL LINE で > 実行後 gv で同じ範囲を再選択]");
        ModalEditor ed = new ModalEditor("abc\ndef\nghi");
        pressKey(ed, 'V');
        pressKey(ed, 'j');
        pressKey(ed, '>');
        check("> 後は NORMAL", ed.isNormalMode());
        pressKey(ed, 'g');
        pressKey(ed, 'v');
        check("gv で VISUAL LINE に戻る", ed.isVisualLineMode());
        check("カーソルが選択終端行(1行目)に戻る", ed.getCursorRow() == 1);
    }

    static void testGvAfterIndentLeft() {
        System.out.println("[VISUAL LINE で < 実行後 gv で同じ範囲を再選択]");
        ModalEditor ed = new ModalEditor("    abc\n    def\nghi");
        pressKey(ed, 'V');
        pressKey(ed, 'j');
        pressKey(ed, '<');
        pressKey(ed, 'g');
        pressKey(ed, 'v');
        check("gv で VISUAL LINE に戻る", ed.isVisualLineMode());
        check("カーソルが選択終端行(1行目)に戻る", ed.getCursorRow() == 1);
    }

    static void testGvRestoresVisualKind() {
        System.out.println("[gv は Visual種別(char/line/block)も復元する]");

        ModalEditor edChar = new ModalEditor("abcdef");
        pressKey(edChar, 'v');
        pressKey(edChar, 'l');
        edChar.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        pressKey(edChar, 'g');
        pressKey(edChar, 'v');
        check("charwise Visual を復元", edChar.isVisualMode());

        ModalEditor edBlock = new ModalEditor("abc\ndef\nghi");
        pressCtrl(edBlock, KeyEvent.VK_V, '\0');
        pressKey(edBlock, 'j');
        edBlock.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        pressKey(edBlock, 'g');
        pressKey(edBlock, 'v');
        check("blockwise Visual を復元", edBlock.isVisualBlockMode());
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
