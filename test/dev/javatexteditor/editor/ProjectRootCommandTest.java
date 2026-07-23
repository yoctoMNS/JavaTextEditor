package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;

/**
 * :pr / :pr?（F10/F11/F12 用プロジェクトルートの記憶・確認）と、追加クラスパスが
 * getBuildRoot() 基準で解決されることを検証するテストハーネス（mainメソッド形式・JUnit不使用）。
 *
 * ねらい: :cd でサブディレクトリへ移動しても、:pr で固定したプロジェクトルートから
 * クラスパスの相対パスが解決されること（本タスクの主目的）。
 */
public class ProjectRootCommandTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testPrRecordsCurrentDir();
        testPrOverwrites();
        testPrQueryUnsetVsSet();
        testBuildRootFallsBackToProjectRoot();
        testClasspathResolvesAgainstBuildRootAfterCd();
        testClasspathFallsBackToCdWhenNoPr();
        testAbsoluteClasspathUnaffected();

        System.out.println("\n=== ProjectRootCommand: " + passed + "/" + (passed + failed) + " PASS ===");
        if (failed > 0) System.exit(1);
    }

    static void testPrRecordsCurrentDir() {
        ModalEditor ed = new ModalEditor("abc");
        Path root = tmp().resolve("proj");
        ed.setProjectRoot(root);
        assertTrue(":pr 前は override 未設定", ed.getProjectRootOverride() == null);
        sendCommand(ed, "pr");
        assertTrue(":pr で override に現在ディレクトリが記憶される",
                root.equals(ed.getProjectRootOverride()));
        assertTrue(":pr 実行後は NORMAL に戻る", ed.isNormalMode());
        passed("testPrRecordsCurrentDir");
    }

    static void testPrOverwrites() {
        ModalEditor ed = new ModalEditor("abc");
        Path first = tmp().resolve("first");
        Path second = tmp().resolve("second");
        ed.setProjectRoot(first);
        sendCommand(ed, "pr");
        assertTrue("1回目の :pr で first が記憶される", first.equals(ed.getProjectRootOverride()));
        // :cd 相当でディレクトリを移動してから再度 :pr を打つ
        ed.setProjectRoot(second);
        sendCommand(ed, "pr");
        assertTrue("2回目の :pr で second に上書きされる", second.equals(ed.getProjectRootOverride()));
        passed("testPrOverwrites");
    }

    static void testPrQueryUnsetVsSet() {
        ModalEditor ed = new ModalEditor("abc");
        Path root = tmp().resolve("wd");
        ed.setProjectRoot(root);
        sendCommand(ed, "pr?");
        assertTrue("未設定時の :pr? は :cd 追従中である旨を表示",
                ed.getStatusMessage().contains("未設定"));
        sendCommand(ed, "pr");
        sendCommand(ed, "pr?");
        assertTrue("設定後の :pr? はルートパスを表示",
                ed.getStatusMessage().contains(root.toString()) && !ed.getStatusMessage().contains("未設定"));
        passed("testPrQueryUnsetVsSet");
    }

    static void testBuildRootFallsBackToProjectRoot() {
        ModalEditor ed = new ModalEditor("abc");
        Path wd = tmp().resolve("wd2");
        ed.setProjectRoot(wd);
        assertTrue("未設定時 getBuildRoot() は getProjectRoot() と一致",
                ed.getBuildRoot().equals(ed.getProjectRoot()));
        sendCommand(ed, "pr");
        Path other = tmp().resolve("other");
        ed.setProjectRoot(other); // :cd 相当で移動
        assertTrue(":pr 設定後 getBuildRoot() は :cd に追従せず固定ルートを返す",
                ed.getBuildRoot().equals(wd));
        assertTrue("getProjectRoot() は :cd に追従する", ed.getProjectRoot().equals(other));
        passed("testBuildRootFallsBackToProjectRoot");
    }

    static void testClasspathResolvesAgainstBuildRootAfterCd() {
        ModalEditor ed = new ModalEditor("abc");
        Path root = tmp().resolve("projectRoot");
        ed.setProjectRoot(root);
        sendCommand(ed, "pr"); // ルートを固定
        Path sub = root.resolve("src").resolve("deep");
        ed.setProjectRoot(sub); // :cd でサブディレクトリへ移動
        List<Path> captured = captureClasspath(ed, "res, lib/assets");
        assertTrue("2件解決される", captured.size() == 2);
        assertTrue("相対クラスパスは :cd の sub ではなく :pr の root 基準で解決される",
                captured.get(0).equals(root.resolve("res").toAbsolutePath()));
        assertTrue("2件目も root 基準",
                captured.get(1).equals(root.resolve("lib/assets").toAbsolutePath()));
        passed("testClasspathResolvesAgainstBuildRootAfterCd");
    }

    static void testClasspathFallsBackToCdWhenNoPr() {
        ModalEditor ed = new ModalEditor("abc");
        Path wd = tmp().resolve("plainWd");
        ed.setProjectRoot(wd); // :pr 未設定
        List<Path> captured = captureClasspath(ed, "res");
        assertTrue(":pr 未設定なら従来どおり :cd 現在ディレクトリ基準で解決",
                captured.size() == 1 && captured.get(0).equals(wd.resolve("res").toAbsolutePath()));
        passed("testClasspathFallsBackToCdWhenNoPr");
    }

    static void testAbsoluteClasspathUnaffected() {
        ModalEditor ed = new ModalEditor("abc");
        ed.setProjectRoot(tmp().resolve("whatever"));
        sendCommand(ed, "pr");
        Path abs = tmp().resolve("abs").resolve("classes").toAbsolutePath();
        List<Path> captured = captureClasspath(ed, abs.toString());
        assertTrue("絶対パスはルートに関係なくそのまま",
                captured.size() == 1 && captured.get(0).equals(abs));
        passed("testAbsoluteClasspathUnaffected");
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    /** 追加クラスパス入力モードに入り input を打鍵して Enter で確定し、確定した List<Path> を返す。 */
    @SuppressWarnings("unchecked")
    private static List<Path> captureClasspath(ModalEditor ed, String input) {
        List<Path>[] box = new List[1];
        ed.enterClasspathInput("F10", extra -> box[0] = extra);
        for (char c : input.toCharArray()) ed.processKey(0, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
        return box[0];
    }

    private static Path tmp() {
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    private static void sendCommand(ModalEditor editor, String cmd) {
        editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        editor.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : cmd.toCharArray()) {
            editor.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        }
        editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
    }

    private static void assertTrue(String name, boolean condition) {
        if (!condition) fail(name);
    }

    private static void passed(String name) {
        passed++;
        System.out.println("[OK] " + name);
    }

    private static void fail(String name) {
        failed++;
        System.out.println("[FAIL] " + name);
    }
}
