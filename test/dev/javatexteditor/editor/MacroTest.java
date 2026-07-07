package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;

/**
 * Vim式マクロ機能（q記録 / @再生 / @@ / 大文字レジスタ追記）のテストハーネス
 * （mainメソッド形式・JUnit不使用）。設計は .claude/skills/vim-macro-recording/SKILL.md 参照。
 */
public class MacroTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testRecordAndReplayAcrossModes();
        testUppercaseRegisterAppends();
        testAtAtRepeatsLastPlayedRegister();
        testNestedMacroCallRecordsOnlyInvocationKeys();
        testPlayingEmptyRegisterShowsError();
        testInvalidRegisterCharShowsErrorAndDoesNotRecord();
        testQInInsertModeIsLiteralNotStopKey();
        testQStopsRecordingRegardlessOfPendingSequence();

        System.out.println();
        System.out.println("=== " + pass + " passed, " + fail + " failed ===");
        if (fail > 0) System.exit(1);
    }

    static void testRecordAndReplayAcrossModes() {
        System.out.println("[q記録→@再生: INSERTモードをまたぐキー列]");
        ModalEditor ed = new ModalEditor("ab");
        pressKey(ed, 'q');
        pressKey(ed, 'a');
        check("記録開始", ed.isRecordingMacro());

        pressKey(ed, 'i');
        pressKey(ed, 'X');
        ed.processKey(KeyEvent.VK_ESCAPE, (char) 27, 0);
        pressKey(ed, 'q');
        check("記録終了", !ed.isRecordingMacro());
        check("記録中の実行がそのまま反映される: text='Xab'", ed.getText().equals("Xab"));
        check("記録されたキー数=3 (i, X, ESC)", ed.getMacroLength('a') == 3);

        pressKey(ed, '@');
        pressKey(ed, 'a');
        check("再生後: text='XXab'", ed.getText().equals("XXab"));
    }

    static void testUppercaseRegisterAppends() {
        System.out.println("[大文字レジスタ: 既存の記録内容へ追記]");
        ModalEditor ed = new ModalEditor("abcdef");

        pressKey(ed, 'q');
        pressKey(ed, 'a');
        pressKey(ed, 'x'); // 'a' を削除
        pressKey(ed, 'q');
        check("1回目の記録後: text='bcdef'", ed.getText().equals("bcdef"));
        check("記録キー数=1", ed.getMacroLength('a') == 1);

        pressKey(ed, 'q');
        pressKey(ed, 'A'); // 大文字 = 追記
        pressKey(ed, 'x'); // 'b' を削除
        pressKey(ed, 'q');
        check("追記後: text='cdef'", ed.getText().equals("cdef"));
        check("追記後の記録キー数=2", ed.getMacroLength('a') == 2);

        pressKey(ed, '@');
        pressKey(ed, 'a');
        check("2キー分(x,x)を再生: text='ef'", ed.getText().equals("ef"));
    }

    static void testAtAtRepeatsLastPlayedRegister() {
        System.out.println("[@@: 直前に実行したレジスタを再現]");
        ModalEditor ed = new ModalEditor("abcdef");
        pressKey(ed, 'q');
        pressKey(ed, 'a');
        pressKey(ed, 'x');
        pressKey(ed, 'q');
        check("記録後: text='bcdef'", ed.getText().equals("bcdef"));

        pressKey(ed, '@');
        pressKey(ed, 'a');
        check("@a後: text='cdef'", ed.getText().equals("cdef"));

        pressKey(ed, '@');
        pressKey(ed, '@');
        check("@@後: text='def'", ed.getText().equals("def"));
    }

    static void testNestedMacroCallRecordsOnlyInvocationKeys() {
        System.out.println("[記録中に別マクロを@で呼ぶと展開されず呼び出し2キーだけ記録される]");
        ModalEditor ed = new ModalEditor("abcdef");

        // レジスタ b: 'x' 1つだけの単純なマクロ
        pressKey(ed, 'q');
        pressKey(ed, 'b');
        pressKey(ed, 'x');
        pressKey(ed, 'q');
        check("b定義後: text='bcdef'", ed.getText().equals("bcdef"));
        check("bの記録キー数=1", ed.getMacroLength('b') == 1);

        // レジスタ a: 記録中に @b を呼び出す
        pressKey(ed, 'q');
        pressKey(ed, 'a');
        pressKey(ed, '@');
        pressKey(ed, 'b');
        pressKey(ed, 'q');
        check("@b呼び出しはその場で実行される: text='cdef'", ed.getText().equals("cdef"));
        check("aの記録キー数は展開されず2 (@,b)のまま", ed.getMacroLength('a') == 2);
    }

    static void testPlayingEmptyRegisterShowsError() {
        System.out.println("[未記録レジスタへの@はエラー表示のみでバッファ不変]");
        ModalEditor ed = new ModalEditor("abc");
        pressKey(ed, '@');
        pressKey(ed, 'z');
        check("バッファ不変", ed.getText().equals("abc"));
        check("エラーメッセージ", ed.getStatusMessage().contains("空です"));
    }

    static void testInvalidRegisterCharShowsErrorAndDoesNotRecord() {
        System.out.println("[q/@に数字等の無効なレジスタ文字を渡すとエラーのみ]");
        ModalEditor ed = new ModalEditor("abc");

        pressKey(ed, 'q');
        pressKey(ed, '1');
        check("記録は開始しない", !ed.isRecordingMacro());
        check("エラーメッセージ", ed.getStatusMessage().contains("無効なレジスタです"));

        pressKey(ed, '@');
        pressKey(ed, '1');
        check("バッファ不変", ed.getText().equals("abc"));
    }

    static void testQInInsertModeIsLiteralNotStopKey() {
        System.out.println("[INSERTモード中のqは記録停止せず文字として挿入される]");
        ModalEditor ed = new ModalEditor("");
        pressKey(ed, 'q');
        pressKey(ed, 'a');
        pressKey(ed, 'i');
        pressKey(ed, 'q'); // INSERT中: 停止トリガーにならない
        check("記録継続中", ed.isRecordingMacro());
        ed.processKey(KeyEvent.VK_ESCAPE, (char) 27, 0);
        pressKey(ed, 'q'); // NORMALのq: ここで記録終了
        check("記録終了", !ed.isRecordingMacro());
        check("'q'が文字として挿入された: text='q'", ed.getText().equals("q"));
        check("記録キー数=3 (i, q, ESC)", ed.getMacroLength('a') == 3);
    }

    static void testQStopsRecordingRegardlessOfPendingSequence() {
        System.out.println("[記録中にpendingSequence途中でqを押しても最優先で記録終了]");
        ModalEditor ed = new ModalEditor("abc\ndef");
        pressKey(ed, 'q');
        pressKey(ed, 'a');
        pressKey(ed, 'g'); // gg/gr/gR/gv 待ちの pendingSequence="g" になる
        pressKey(ed, 'q'); // pendingSequenceの状態に関係なく記録終了するべき
        check("記録終了", !ed.isRecordingMacro());
        check("停止キー自体は記録されず、gのみ記録される", ed.getMacroLength('a') == 1);

        // pendingSequence が正しくクリアされていることを gg で確認
        pressKey(ed, 'g');
        pressKey(ed, 'g');
        check("gg: ファイル先頭へ (pendingSequence破損なし)", ed.getCursorRow() == 0);
    }

    static void pressKey(ModalEditor ed, char keyChar) {
        ed.processKey(0, keyChar, 0);
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
