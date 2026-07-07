package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;

/**
 * Vim式置換コマンド（:s / :%s / :'&lt;,'&gt;s / :N,Ms）のテストハーネス（mainメソッド形式・JUnit不使用）。
 * 設計判断は .claude/skills/vim-substitution/SKILL.md 参照。
 */
public class SubstituteCommandTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testCurrentLineFirstMatchOnly();
        testCurrentLineGlobalFlag();
        testCaseInsensitiveFlag();
        testPercentSubstitutesWholeBuffer();
        testNumericRange();
        testVisualCharwiseRange();
        testVisualLinewiseRange();
        testNoPreviousVisualSelectionError();
        testBackreferenceReplacement();
        testAmpersandReplacement();
        testEmptyPatternReusesLastSearch();
        testNoMatchShowsError();
        testAlternateDelimiter();
        testCursorMovesToLastChangedLine();

        System.out.println();
        System.out.println("=== SubstituteCommand: " + pass + " passed, " + fail + " failed ===");
        if (fail > 0) System.exit(1);
    }

    static void testCurrentLineFirstMatchOnly() {
        System.out.println("[:s はカーソル行の最初の一致のみ置換]");
        ModalEditor ed = new ModalEditor("foo foo\nfoo foo");
        sendCommand(ed, "s/foo/bar/");
        check("1行目のみ変更", ed.getText().equals("bar foo\nfoo foo"));
    }

    static void testCurrentLineGlobalFlag() {
        System.out.println("[:s ... /g はカーソル行の全一致を置換]");
        ModalEditor ed = new ModalEditor("foo foo\nfoo foo");
        sendCommand(ed, "s/foo/bar/g");
        check("1行目の全一致が変更", ed.getText().equals("bar bar\nfoo foo"));
    }

    static void testCaseInsensitiveFlag() {
        System.out.println("[:s ... /i は大小無視で一致]");
        ModalEditor ed = new ModalEditor("FOO bar");
        sendCommand(ed, "s/foo/baz/i");
        check("大文字FOOも一致して置換", ed.getText().equals("baz bar"));
    }

    static void testPercentSubstitutesWholeBuffer() {
        System.out.println("[:%s は全行対象]");
        ModalEditor ed = new ModalEditor("foo\nfoo\nfoo");
        sendCommand(ed, "%s/foo/bar/");
        check("全行が変更", ed.getText().equals("bar\nbar\nbar"));
    }

    static void testNumericRange() {
        System.out.println("[:N,Ms は行番号範囲(1始まり)を対象]");
        ModalEditor ed = new ModalEditor("foo\nfoo\nfoo\nfoo");
        sendCommand(ed, "2,3s/foo/bar/");
        check("2〜3行目のみ変更", ed.getText().equals("foo\nbar\nbar\nfoo"));
    }

    static void testVisualCharwiseRange() {
        System.out.println("[VISUALモードで ':' → '<,'>s は選択範囲の行を対象]");
        ModalEditor ed = new ModalEditor("foo\nfoo\nfoo");
        pressKey(ed, 'j'); // カーソルを2行目へ
        pressKey(ed, 'v'); // VISUAL開始（アンカー=2行目）
        pressKey(ed, 'j'); // カーソルを3行目まで拡張
        pressKey(ed, ':');
        check("commandBufferが'<,'>で初期化される", ed.getCommandBuffer().equals("'<,'>"));
        typeString(ed, "s/foo/bar/");
        ed.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
        check("2〜3行目のみ変更", ed.getText().equals("foo\nbar\nbar"));
        check("COMMAND終了後はNORMALモード", ed.isNormalMode());
    }

    static void testVisualLinewiseRange() {
        System.out.println("[VISUAL LINEモードで ':' → '<,'>s]");
        ModalEditor ed = new ModalEditor("foo\nfoo\nfoo");
        pressKey(ed, 'V');
        pressKey(ed, ':');
        typeString(ed, "s/foo/bar/");
        ed.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
        check("1行目のみ変更", ed.getText().equals("bar\nfoo\nfoo"));
    }

    static void testNoPreviousVisualSelectionError() {
        System.out.println("[Visual選択履歴が無い状態で '<,'>s を送るとエラー]");
        ModalEditor ed = new ModalEditor("foo");
        sendCommand(ed, "'<,'>s/foo/bar/");
        check("エラーメッセージ", ed.getStatusMessage().contains("no previous visual selection"));
        check("バッファは変更されない", ed.getText().equals("foo"));
    }

    static void testBackreferenceReplacement() {
        System.out.println("[置換文字列の \\1 は後方参照として展開される]");
        ModalEditor ed = new ModalEditor("hello world");
        sendCommand(ed, "s/(hello) (world)/\\2 \\1/");
        check("グループが入れ替わる", ed.getText().equals("world hello"));
    }

    static void testAmpersandReplacement() {
        System.out.println("[置換文字列の & はマッチ全体を表す]");
        ModalEditor ed = new ModalEditor("foo");
        sendCommand(ed, "s/foo/[&]/");
        check("マッチ全体が角括弧で囲まれる", ed.getText().equals("[foo]"));
    }

    static void testEmptyPatternReusesLastSearch() {
        System.out.println("[空パターンは直前の検索パターンを再利用する]");
        ModalEditor ed = new ModalEditor("foo bar");
        pressKey(ed, '/');
        typeString(ed, "foo");
        ed.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
        sendCommand(ed, "s//baz/");
        check("直前検索パターンfooが使われる", ed.getText().equals("baz bar"));
    }

    static void testNoMatchShowsError() {
        System.out.println("[マッチしない場合はエラーメッセージ]");
        ModalEditor ed = new ModalEditor("hello");
        sendCommand(ed, "s/xyz/abc/");
        check("pattern not foundエラー", ed.getStatusMessage().contains("pattern not found"));
        check("バッファは変更されない", ed.getText().equals("hello"));
    }

    static void testAlternateDelimiter() {
        System.out.println("[区切り文字は / 以外も使える]");
        ModalEditor ed = new ModalEditor("a/b/c");
        sendCommand(ed, "s#a/b#x#");
        check("代替区切り文字での置換", ed.getText().equals("x/c"));
    }

    static void testCursorMovesToLastChangedLine() {
        System.out.println("[置換後カーソルは最後に変更された行へ移動]");
        ModalEditor ed = new ModalEditor("foo\nbar\nfoo");
        sendCommand(ed, "%s/foo/baz/");
        check("カーソル行が最後の変更行(index2)", ed.getCursorRow() == 2);
    }

    // -------------------------------------------------------------------------

    static void sendCommand(ModalEditor ed, String cmd) {
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        pressKey(ed, ':');
        typeString(ed, cmd);
        ed.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
    }

    static void pressKey(ModalEditor ed, char keyChar) {
        ed.processKey(0, keyChar, 0);
    }

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
