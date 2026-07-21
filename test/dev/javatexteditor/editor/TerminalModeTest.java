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

    public static void main(String[] args) throws Exception {
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
        testEchoedInputIsSuppressedFromOutput();
        testEchoSuppressionAcrossSplitChunks();
        testEchoSuppressionGracefullyIgnoresNonEchoingShell();
        testTypedCdSyncsEditorProjectRoot();
        testTypedCdWithNoArgumentGoesHome();
        testTypedNonCdCommandDoesNotChangeProjectRoot();
        testEditorCdSyncsToLiveTerminal();
        testEditorCdDoesNotSyncToDeadTerminal();
        testEchoSuppressionSurvivesStdoutArrivingBeforeStderrEcho();
        testToggleTerminalModeSyncsCanvasImmediately();
        testExitTerminalSyncsCanvasImmediately();
        testExitTerminalResetsEchoSuppressState();
        testTabTriggersWordCompletionInTerminal();
        testTabCompletionSyncsPendingInputOnEnterSubmit();
        testEscDismissesCompletionWithoutExitingTerminal();
        testTypingWhileCompletionActiveDismissesAndContinuesEcho();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        // testToggleTerminalModeClearsSplashScreen() が new EditorCanvas() を生成しており、
        // Swingのトップレベルウィンドウを一度も表示/破棄しないままだとAWTイベントディスパッチ
        // スレッド（非daemon）がJVM終了を妨げてプロセスが終了しない。RobotKeyInputTestと
        // 同じ理由・同じ対策（明示的なSystem.exit）で解消する。
        System.exit(fail > 0 ? 1 : 0);
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

    /** TERMINALモード中に1行分の文字列をタイプしてEnterを押す（ローカルエコー経由）。 */
    private static void typeInTerminal(ModalEditor ed, String line) {
        for (char c : line.toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) { count++; idx += needle.length(); }
        return count;
    }

    /** Tab単語補完（WordIndex）付きのTERMINAL用エディタを作る。Alt+/（WordCompletionTest）と同じ配線。 */
    private static ModalEditor makeEditorWithTerminalWords(java.nio.file.Path root) {
        ModalEditor ed = new ModalEditor("x");
        ed.setProjectRoot(root);
        ed.setWordIndex(dev.javatexteditor.analysis.WordIndex.buildSync(root));
        return ed;
    }

    /** :cd をサポートする（FilerTestと同じパターンの）モック changeWdCallback 付きエディタを作る。 */
    private static ModalEditor makeEditorWithCdSupport(java.nio.file.Path root) {
        ModalEditor ed = new ModalEditor("hello");
        ed.setProjectRoot(root);
        ed.setChangeWorkingDirectoryCallback(p -> {
            if (!java.nio.file.Files.isDirectory(p)) return "no such directory: " + p;
            ed.setProjectRoot(p);
            return null;
        });
        return ed;
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

    /**
     * バグ修正の回帰テスト: tty無しの対話型シェル（bash -i を実測して確認済み。CLAUDE.md参照）は
     * 読み取った入力行をそのまま出力へエコーバックするフォールバック動作を行う。ローカルエコーで
     * 既に表示済みの内容と二重表示されないよう、送信直後に届く一致するチャンクは抑制する必要がある。
     */
    static void testEchoedInputIsSuppressedFromOutput() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("x");
        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        typeInTerminal(ed, "echo HI");
        // シェル自身のエコーバック（実測どおりstderr経由）+ 実際のコマンド出力（stdout）を模擬
        ed.appendTerminalOutput("echo HI\n", true);
        ed.appendTerminalOutput("HI\n", false);
        String text = ed.getText();
        assertEquals("ローカルエコー分の1回だけ表示される（シェルの二重エコーは抑制）",
            1, countOccurrences(text, "echo HI"));
        assertTrue("実際のコマンド出力は表示される", text.contains("HI"));
    }

    /** エコーバックが複数回のread()に分割されて届く場合でも正しく抑制できる必要がある。 */
    static void testEchoSuppressionAcrossSplitChunks() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("x");
        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        typeInTerminal(ed, "pwd");
        ed.appendTerminalOutput("pw", true);
        ed.appendTerminalOutput("d\n", true);
        ed.appendTerminalOutput("/tmp\n", false);
        String text = ed.getText();
        assertEquals("分割チャンクでもエコーは1回だけ表示される", 1, countOccurrences(text, "pwd"));
        assertTrue("実際のコマンド出力は表示される", text.contains("/tmp"));
    }

    /** エコーバックしないシェル（bash -i以外）でも出力が失われず表示される必要がある。 */
    static void testEchoSuppressionGracefullyIgnoresNonEchoingShell() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("x");
        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        typeInTerminal(ed, "ls");
        // 一致しない出力がそのまま素通しされることを確認
        ed.appendTerminalOutput("file1.txt\nfile2.txt\n", false);
        String text = ed.getText();
        assertTrue("エコーしないシェルでも出力は失われない",
            text.contains("file1.txt") && text.contains("file2.txt"));
    }

    /** ターミナルで typed した "cd <path>" がエディタのprojectRootへ同期される。 */
    static void testTypedCdSyncsEditorProjectRoot() throws Exception {
        resetSharedTerminalState();
        java.nio.file.Path target = java.nio.file.Files.createTempDirectory("term_cd_target_");
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("term_cd_root_");
        ModalEditor ed = makeEditorWithCdSupport(root);
        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        typeInTerminal(ed, "cd " + target);
        assertEquals("ターミナルで入力したcdがエディタのprojectRootに反映される",
            target.toAbsolutePath().normalize(), ed.getProjectRoot());
    }

    /** 引数なしの "cd" はホームディレクトリへ移動する（shellの既定動作と同じ）。 */
    static void testTypedCdWithNoArgumentGoesHome() throws Exception {
        resetSharedTerminalState();
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("term_cd_root2_");
        ModalEditor ed = makeEditorWithCdSupport(root);
        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        typeInTerminal(ed, "cd");
        java.nio.file.Path home = java.nio.file.Path.of(System.getProperty("user.home"))
            .toAbsolutePath().normalize();
        assertEquals("引数なしcdはホームディレクトリへ", home, ed.getProjectRoot());
    }

    /** "cd" ではない通常のコマンドはprojectRootへ影響しない。 */
    static void testTypedNonCdCommandDoesNotChangeProjectRoot() {
        resetSharedTerminalState();
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("user.dir"));
        ModalEditor ed = makeEditorWithCdSupport(root);
        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        typeInTerminal(ed, "echo cd_lookalike");
        assertEquals("cd以外のコマンドはprojectRootを変更しない", root, ed.getProjectRoot());
    }

    /** エディタの :cd 成功時、生存中のTERMINALセッションへも "cd <path>" が転送される。 */
    static void testEditorCdSyncsToLiveTerminal() throws Exception {
        resetSharedTerminalState();
        java.nio.file.Path target = java.nio.file.Files.createTempDirectory("term_cd_target2_");
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("term_cd_root3_");
        ModalEditor ed = makeEditorWithCdSupport(root);
        FakeTerminal fake = new FakeTerminal();
        fake.wire(ed);
        typeCommand(ed, "term");
        ed.toggleTerminalMode(); // NORMALに戻る。セッション自体は生存したまま。
        typeCommand(ed, "cd " + target);
        boolean forwarded = fake.written.stream().anyMatch(w ->
            w.startsWith("cd ") && w.contains(target.toString()) && w.endsWith("\n"));
        assertTrue(":cd が生存中のターミナルへcdコマンドとして転送される", forwarded);
    }

    /** ターミナルセッションが生存していない場合は転送を試みない（NPE等でクラッシュしない）。 */
    static void testEditorCdDoesNotSyncToDeadTerminal() throws Exception {
        resetSharedTerminalState();
        java.nio.file.Path target = java.nio.file.Files.createTempDirectory("term_cd_target3_");
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("term_cd_root4_");
        ModalEditor ed = makeEditorWithCdSupport(root);
        FakeTerminal fake = new FakeTerminal();
        fake.wire(ed);
        // ターミナルセッションを一度も開始していない状態で :cd する
        typeCommand(ed, "cd " + target);
        assertEquals(":cd 自体は成功しprojectRootが変わる",
            target.toAbsolutePath().normalize(), ed.getProjectRoot());
        assertEquals("生存中のターミナルが無いのでwrite callbackは呼ばれない", 0, fake.written.size());
    }

    /**
     * コードレビューで指摘された競合状態の回帰テスト: 標準出力/標準エラーは独立した
     * 読み取りスレッドがそれぞれ個別にディスパッチするため到着順の保証がない。実際の
     * コマンド出力（標準出力）がシェルの自己エコー（標準エラー）より先に届いても、
     * 抑制状態が誤ってクリアされず、後から届く自己エコーが正しく抑制される必要がある
     * （標準出力チャンクはsuppressEchoedInputの対象外にする修正で解消）。
     */
    static void testEchoSuppressionSurvivesStdoutArrivingBeforeStderrEcho() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("x");
        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        typeInTerminal(ed, "echo HI");
        // 到着順が入れ替わるケース: 実出力（stdout）が自己エコー（stderr）より先に届く
        ed.appendTerminalOutput("HI\n", false);
        ed.appendTerminalOutput("echo HI\n", true);
        String text = ed.getText();
        assertEquals("標準出力が先着してもechoは1回だけ表示される", 1, countOccurrences(text, "echo HI"));
        assertTrue("実際のコマンド出力は表示される", text.contains("HI"));
    }

    /**
     * コードレビューで指摘されたバグの回帰テスト: Ctrl+Shift+TはprocessKey()を経由せず
     * toggleTerminalMode()を直接呼ぶため、syncCanvas()が呼ばれず画面（ステータスラベル・
     * バッファ内容）が次のキー入力まで更新されない不具合があった。
     */
    static void testToggleTerminalModeSyncsCanvasImmediately() {
        resetSharedTerminalState();
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("hello", canvas);
        new FakeTerminal().wire(ed);

        assertTrue("前提: TERMINALモードに入る前はfalse", !canvas.isTerminalMode());
        ed.toggleTerminalMode(); // processKey()を経由しない直接呼び出し
        assertTrue("TERMINALモードに入る", ed.isTerminalMode());
        assertTrue("キー入力なしでcanvasのterminalModeが即座に反映される", canvas.isTerminalMode());
    }

    /** 上記と対になる離脱側の回帰テスト: exitTerminal()も同様にsyncCanvas()を呼ぶ必要がある。 */
    static void testExitTerminalSyncsCanvasImmediately() {
        resetSharedTerminalState();
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("hello", canvas);
        new FakeTerminal().wire(ed);

        ed.toggleTerminalMode();
        assertTrue("前提: TERMINALモードに入っている", canvas.isTerminalMode());
        ed.toggleTerminalMode(); // 離脱（processKey()を経由しない）
        assertTrue("NORMALに戻る", ed.isNormalMode());
        assertTrue("キー入力なしでcanvasのterminalModeが即座にfalseへ戻る", !canvas.isTerminalMode());
    }

    /**
     * コードレビューで指摘されたバグの回帰テスト: exitTerminal()（Escによる離脱を含む）が
     * terminalPendingEchoSuppressをリセットしないと、コマンド送信直後にEscで抜けて
     * セッションが生存したまま後で再アタッチした場合、無関係な後続出力が古い抑制状態と
     * 偶然前方一致して誤って消費されうる。
     */
    static void testExitTerminalResetsEchoSuppressState() {
        resetSharedTerminalState();
        ModalEditor ed = new ModalEditor("x");
        new FakeTerminal().wire(ed);
        typeCommand(ed, "term");
        // コマンドを送信した直後（自己エコーがまだ届いていない状態）にEscで抜ける
        for (char c : "lsx".toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        assertTrue("Escで抜けてNORMALに戻る", ed.isNormalMode());

        // 同じ生きたセッションに再アタッチしてから、たまたま古い抑制文字列("lsx\n")と
        // 前方一致する無関係な出力（標準エラー、抑制ロジックの対象ストリーム）が届く。
        // リセットされていなければ先頭の"lsx\n"だけ誤って消費され、countが1のままになる。
        ed.toggleTerminalMode();
        assertTrue("再度TERMINALモードに入る", ed.isTerminalMode());
        ed.appendTerminalOutput("lsx\nmarker_HERE\n", true);
        String text = ed.getText();
        assertEquals("リセットされていれば古い抑制の影響を受けず'lsx'が計2回残る（ローカルエコー分+新規出力分）",
            2, countOccurrences(text, "lsx"));
    }

    /**
     * TERMINALモードでTabを押すとAlt+/（WordCompletionTest）と同じWordIndexで単語補完ポップアップが
     * 開く。ttyを介さないOS共通のJava側KeyboardFocusManager経由の処理のため、Windows/Linux問わず
     * 同じ経路で動作する。
     */
    static void testTabTriggersWordCompletionInTerminal() throws Exception {
        resetSharedTerminalState();
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("term_tab_comp_");
        try {
            java.nio.file.Files.writeString(root.resolve("Sample.java"), "int targetWordXYZ = 1;");
            ModalEditor ed = makeEditorWithTerminalWords(root);
            new FakeTerminal().wire(ed);
            typeCommand(ed, "term");
            for (char c : "target".toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
            ed.processKey(KeyEvent.VK_TAB, '\t', 0);
            assertTrue("Tabで補完ポップアップが開く", ed.isCompletionActive());
            assertTrue("targetWordXYZが候補に含まれる",
                ed.getCompletionItems().stream().anyMatch(it -> it.label().equals("targetWordXYZ")));
        } finally {
            deleteDir(root);
        }
    }

    /**
     * バグ回帰テスト: applyCompletion()はbuffer（画面表示用）のみを書き換え、Enter押下時に
     * シェルへ送信されるterminalPendingInputの存在を知らない。Tab確定後にterminalPendingInputへ
     * 反映し忘れると、実際にシェルへ送られる行が補完前の切り詰められたプレフィックスのままになる。
     */
    static void testTabCompletionSyncsPendingInputOnEnterSubmit() throws Exception {
        resetSharedTerminalState();
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("term_tab_comp2_");
        try {
            java.nio.file.Files.writeString(root.resolve("Sample.java"), "int uniqueTermWordABC = 1;");
            ModalEditor ed = makeEditorWithTerminalWords(root);
            FakeTerminal fake = new FakeTerminal();
            fake.wire(ed);
            typeCommand(ed, "term");
            for (char c : "uniqueTerm".toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
            ed.processKey(KeyEvent.VK_TAB, '\t', 0);
            assertTrue("補完ポップアップが開く", ed.isCompletionActive());
            ed.processKey(KeyEvent.VK_ENTER, '\n', 0); // ポップアップ確定（送信ではない）
            assertTrue("確定後は補完が閉じる", !ed.isCompletionActive());
            assertEquals("バッファに補完結果が反映される", "uniqueTermWordABC", ed.getText());
            ed.processKey(KeyEvent.VK_ENTER, '\n', 0); // ここで初めてシェルへ送信
            assertEquals("write callbackに補完後の完全な単語が渡る（切り詰められたプレフィックスではない）",
                "uniqueTermWordABC\n", fake.written.get(0));
        } finally {
            deleteDir(root);
        }
    }

    /** 補完ポップアップが開いている間のEscはポップアップを閉じるだけで、TERMINALモード自体は抜けない。 */
    static void testEscDismissesCompletionWithoutExitingTerminal() throws Exception {
        resetSharedTerminalState();
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("term_tab_comp3_");
        try {
            java.nio.file.Files.writeString(root.resolve("Sample.java"), "int anotherTermWordDEF = 1;");
            ModalEditor ed = makeEditorWithTerminalWords(root);
            new FakeTerminal().wire(ed);
            typeCommand(ed, "term");
            for (char c : "anotherTerm".toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
            ed.processKey(KeyEvent.VK_TAB, '\t', 0);
            assertTrue("補完ポップアップが開く", ed.isCompletionActive());
            ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("Escで補完だけが閉じる", !ed.isCompletionActive());
            assertTrue("TERMINALモードのままである", ed.isTerminalMode());
        } finally {
            deleteDir(root);
        }
    }

    /** 補完ポップアップが開いている間に通常文字を打つと、ポップアップを閉じて通常のローカルエコーに戻る。 */
    static void testTypingWhileCompletionActiveDismissesAndContinuesEcho() throws Exception {
        resetSharedTerminalState();
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("term_tab_comp4_");
        try {
            java.nio.file.Files.writeString(root.resolve("Sample.java"), "int thirdTermWordGHI = 1;");
            ModalEditor ed = makeEditorWithTerminalWords(root);
            new FakeTerminal().wire(ed);
            typeCommand(ed, "term");
            for (char c : "thirdTerm".toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
            ed.processKey(KeyEvent.VK_TAB, '\t', 0);
            assertTrue("補完ポップアップが開く", ed.isCompletionActive());
            ed.processKey(KeyEvent.VK_UNDEFINED, 'Z', 0);
            assertTrue("通常文字入力で補完が閉じる", !ed.isCompletionActive());
            assertEquals("通常文字はそのままローカルエコーされる", "thirdTermZ", ed.getText());
        } finally {
            deleteDir(root);
        }
    }

    private static void deleteDir(java.nio.file.Path dir) throws java.io.IOException {
        if (!java.nio.file.Files.exists(dir)) return;
        java.nio.file.Files.walk(dir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p -> { try { java.nio.file.Files.delete(p); } catch (java.io.IOException ignored) {} });
    }
}
