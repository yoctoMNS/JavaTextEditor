package dev.javatexteditor.editor;

import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Ctrl+Shift+T / :term（Mode.TERMINAL）のModalEditor側ロジックを検証する。
 * 実プロセス起動（TerminalSession）はGUIに依存しないため別途 TerminalSessionTest で
 * 実サブプロセスを使って検証済み。ここでは ModalEditor が terminalStartCallback/
 * terminalWriteCallback/terminalKillCallback をどう呼び分け、ローカルエコー・カーソル追従・
 * モード遷移をどう扱うかを、実プロセスを起動しない疑似コールバックで検証する。
 *
 * 注意: terminalBuffer/terminalAlive 等はエディタプロセス全体で1つだけの対話型セッションを
 * 表すため static（詳細はModalEditor.javaのMode.TERMINALセクション参照）。このテストクラスの
 * 全メソッドが同一JVM内で実行されるため、この静的状態はテストメソッドをまたいで残る。
 * resetSharedTerminalState() で各テスト冒頭に「前のセッションは死んだ」状態へ戻すことで
 * 次の :term が新しいバッファを作り直すようにしている。ただし
 * testSpcBShowsTerminalEntryOnlyAfterStarted() の「*terminal* が一度も :term していない間は
 * 候補に出ない」というアサーションだけは、このJVM内で本当に一度も :term していない状態が
 * 前提のため、main() の一番最初に置くこと（順序を入れ替えないこと）。
 */
public class TerminalModeTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testSpcBShowsTerminalEntryOnlyAfterStarted(); // 必ず最初（コメント参照）

        testTermCommandEntersTerminalMode();
        testTypingLocalEchoesIntoBuffer();
        testBackspaceRemovesLocalEcho();
        testEnterSendsPendingLineAndClearsIt();
        testToggleTerminalModeExitsAndReentersWithoutRestart();
        testDeadSessionIgnoresKeystrokes();
        testCtrlCTriggersKillCallback();
        testSpcBSelectionReattachesWithoutRestart();
        testExitTerminalRestoresSavedBuffer();
        testEscapeExitsTerminalMode();
        testEscapeExitsDeadTerminalSession();
        testToggleTerminalModeClearsSplashScreen();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
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
        assertTrue(name + " (expected=" + expected + ", actual=" + actual + ")",
            expected == null ? actual == null : expected.equals(actual));
    }

    /** 前のテストが残した static なターミナル状態を「死んでいる」扱いにし、次の :term が作り直せるようにする。 */
    private static void resetSharedTerminalState() {
        if (ModalEditor.getSharedTerminalBuffer() != null) {
            new ModalEditor("").markTerminalStartFailed("test reset");
        }
    }

    /** 実プロセスを起動しない疑似コールバック一式。呼び出し回数・内容だけを記録する。 */
    private static final class FakeTerminal {
        int startCount = 0;
        final List<String> written = new ArrayList<>();
        int killCount = 0;

        void wire(ModalEditor ed) {
            ed.setTerminalStartCallback(() -> startCount++);
            ed.setTerminalWriteCallback(written::add);
            ed.setTerminalKillCallback(() -> killCount++);
        }
    }

    private static void typeCommand(ModalEditor ed, String cmd) {
        ed.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : cmd.toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    private static void openSpcB(ModalEditor ed) {
        ed.processKey(KeyEvent.VK_SPACE, ' ', 0);
        ed.processKey(KeyEvent.VK_B, 'b', 0);
    }

    private static boolean telescopeHasEntry(ModalEditor ed, String display) {
        for (var item : ed.getTelescopeResults()) {
            if (item.display().equals(display)) return true;
        }
        return false;
    }

    private static void selectEntry(ModalEditor ed, String display) {
        var results = ed.getTelescopeResults();
        int idx = -1;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).display().equals(display)) { idx = i; break; }
        }
        if (idx < 0) throw new IllegalStateException("entry not found: " + display);
        for (int i = 0; i < idx; i++) {
            ed.processKey(KeyEvent.VK_N, 'n', KeyEvent.CTRL_DOWN_MASK);
        }
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    static void testSpcBShowsTerminalEntryOnlyAfterStarted() {
        System.out.println("[SPC+b: 一度も:termしていなければ*terminal*は候補に出ない]");
        ModalEditor ed = new ModalEditor("abc");
        openSpcB(ed);
        assertTrue("*terminal*が候補に含まれない（未起動）", !telescopeHasEntry(ed, "*terminal*"));
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);

        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        ed.toggleTerminalMode(); // NORMALへ戻る（SPC+bはNORMALから）
        openSpcB(ed);
        assertTrue("*terminal*が候補に含まれる（:term後）", telescopeHasEntry(ed, "*terminal*"));
    }

    static void testTermCommandEntersTerminalMode() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("hello");
        FakeTerminal fake = new FakeTerminal();
        fake.wire(ed);
        typeCommand(ed, "term");
        assertTrue("TERMINALモードに入る", ed.isTerminalMode());
        assertEquals("start callbackが1回呼ばれる", 1, fake.startCount);
        assertEquals("ターミナルバッファは空から始まる", "", ed.getText());
    }

    static void testTypingLocalEchoesIntoBuffer() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("x");
        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        for (char c : "ls".toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        assertEquals("入力文字がローカルエコーされる", "ls", ed.getText());
        assertEquals("カーソルが末尾に追従する", 2, ed.getCursorCol());
    }

    static void testBackspaceRemovesLocalEcho() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("x");
        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        for (char c : "ls".toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_BACK_SPACE, KeyEvent.CHAR_UNDEFINED, 0);
        assertEquals("Backspaceでローカルエコーの末尾1文字が消える", "l", ed.getText());
    }

    static void testEnterSendsPendingLineAndClearsIt() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("x");
        FakeTerminal fake = new FakeTerminal();
        fake.wire(ed);
        typeCommand(ed, "term");
        for (char c : "echo hi".toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
        assertEquals("write callbackが1回呼ばれる", 1, fake.written.size());
        assertEquals("write callbackへ改行込みの1行が渡る", "echo hi\n", fake.written.get(0));
        assertEquals("バッファにも入力行+改行が反映される", "echo hi\n", ed.getText());
        ed.processKey(KeyEvent.VK_UNDEFINED, 'x', 0);
        assertEquals("Enter後は新しい入力として蓄積される", "echo hi\nx", ed.getText());
    }

    static void testToggleTerminalModeExitsAndReentersWithoutRestart() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("original content");
        FakeTerminal fake = new FakeTerminal();
        fake.wire(ed);
        typeCommand(ed, "term");
        assertEquals("初回のstart callbackは1回", 1, fake.startCount);
        ed.toggleTerminalMode();
        assertTrue("トグルでNORMALに戻る", ed.isNormalMode());
        assertEquals("元のバッファ内容が復元される", "original content", ed.getText());
        ed.toggleTerminalMode();
        assertTrue("再度トグルでTERMINALに戻る", ed.isTerminalMode());
        assertEquals("生存中セッションへの再入場ではstart callbackは呼ばれない", 1, fake.startCount);
    }

    static void testDeadSessionIgnoresKeystrokes() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("x");
        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        ed.markTerminalExited(0);
        ed.processKey(KeyEvent.VK_UNDEFINED, 'a', 0);
        assertEquals("プロセス終了後のキー入力は無視される（終了メッセージのみ残る）",
            "[process exited with code 0]\n", ed.getText());
    }

    static void testCtrlCTriggersKillCallback() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("x");
        FakeTerminal fake = new FakeTerminal();
        fake.wire(ed);
        typeCommand(ed, "term");
        ed.processKey(KeyEvent.VK_C, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK);
        assertEquals("Ctrl+Cでkill callbackが呼ばれる", 1, fake.killCount);
    }

    static void testSpcBSelectionReattachesWithoutRestart() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("x");
        FakeTerminal fake = new FakeTerminal();
        fake.wire(ed);
        typeCommand(ed, "term");
        for (char c : "hello".toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.toggleTerminalMode();
        openSpcB(ed);
        selectEntry(ed, "*terminal*");
        assertTrue("SPC+b選択でTERMINALモードに戻る", ed.isTerminalMode());
        assertEquals("ターミナルの内容が保持される（再起動されない）", "hello", ed.getText());
        assertEquals("SPC+b再アタッチではstart callbackが呼ばれない", 1, fake.startCount);
    }

    static void testExitTerminalRestoresSavedBuffer() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("keep me");
        ed.setCursor(0, 3);
        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        ed.toggleTerminalMode();
        assertEquals("行が復元される", 0, ed.getCursorRow());
        assertEquals("列が復元される", 3, ed.getCursorCol());
        assertEquals("テキストが復元される", "keep me", ed.getText());
    }

    /**
     * バグ修正の回帰テスト: Ctrl+Shift+Tを覚えていない・押せない状況でTERMINALモードに
     * 取り残されないよう、Escでも元のバッファへ抜けられる必要がある。
     */
    static void testEscapeExitsTerminalMode() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("original content");
        ed.setCursor(0, 3);
        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        assertTrue("TERMINALモードに入る", ed.isTerminalMode());
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        assertTrue("EscでNORMALに戻る", ed.isNormalMode());
        assertEquals("元のバッファ内容が復元される", "original content", ed.getText());
        assertEquals("行が復元される", 0, ed.getCursorRow());
        assertEquals("列が復元される", 3, ed.getCursorCol());
    }

    /** プロセス終了後（ログ閲覧のみの状態）でもEscで抜けられる必要がある。 */
    static void testEscapeExitsDeadTerminalSession() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("original content");
        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        ed.markTerminalExited(0);
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        assertTrue("死んだセッション表示中でもEscでNORMALに戻る", ed.isNormalMode());
        assertEquals("元のバッファ内容が復元される", "original content", ed.getText());
    }

    /**
     * バグ修正の回帰テスト: Ctrl+Shift+TはMain.javaのグローバルディスパッチャから
     * processKey()を経由せずtoggleTerminalMode()を直接呼ぶため、processKey()冒頭でのみ
     * 行われていたスプラッシュ画面消去が効かなかった。ファイル未指定でエディタを開き、
     * 最初のキー操作としてCtrl+Shift+Tを押した場合を再現する（他のキーでスプラッシュが
     * 消えた後にトグルするケースはスプラッシュが既に消えているため区別がつかない）。
     */
    static void testToggleTerminalModeClearsSplashScreen() {
        resetSharedTerminalState();
        EditorCanvas canvas = new EditorCanvas();
        canvas.setShowSplash(true);
        ModalEditor ed = new ModalEditor("", canvas);
        new FakeTerminal().wire(ed);

        assertTrue("前提: スプラッシュ表示中", canvas.isShowSplash());
        ed.toggleTerminalMode(); // processKey()を経由しない直接呼び出し
        assertTrue("TERMINALモードに入る", ed.isTerminalMode());
        assertTrue("スプラッシュが消える", !canvas.isShowSplash());
    }
}
