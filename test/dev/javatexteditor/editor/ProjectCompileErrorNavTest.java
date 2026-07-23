package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ctrl+&gt;/Ctrl+&lt;（NORMAL/INSERT モードでのプロジェクト全体コンパイルエラー間ジャンプ）を
 * 検証するテストハーネス（mainメソッド形式・JUnit不使用）。
 * F10 と同じ {@link dev.javatexteditor.projectbuild.ProjectBuilder} を getBuildRoot() 配下で
 * 実行するため、実際にディスク上へ .java ファイルを用意して検証する。
 */
public class ProjectCompileErrorNavTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws IOException {
        testNoErrorsShowsMessage();
        testCtrlGtJumpsToNextErrorAcrossFiles();
        testCtrlLtJumpsToPrevErrorAcrossFiles();
        testWrapAroundForward();
        testWrapAroundBackward();
        testWorksFromInsertMode();

        System.out.println("\n=== ProjectCompileErrorNav: " + passed + "/" + (passed + failed) + " PASS ===");
        if (failed > 0) System.exit(1);
    }

    static void testNoErrorsShowsMessage() throws IOException {
        Path root = freshProjectDir();
        writeSrc(root, "Ok.java", "public class Ok {}\n");
        ModalEditor ed = new ModalEditor("");
        ed.setProjectRoot(root);
        ed.processKey(KeyEvent.VK_PERIOD, '.', KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
        assertTrue("エラーなし時のメッセージ", ed.getStatusMessage().contains("コンパイルエラーはありません"));
        passed("testNoErrorsShowsMessage");
    }

    static void testCtrlGtJumpsToNextErrorAcrossFiles() throws IOException {
        Path root = freshProjectDir();
        writeSrc(root, "A.java", "public class A {\n    void m() {\n        int x = ;\n    }\n}\n");
        writeSrc(root, "B.java", "public class B {\n    void n() {\n        int y = ;\n    }\n}\n");
        ModalEditor ed = new ModalEditor("");
        ed.setProjectRoot(root);
        ed.processKey(KeyEvent.VK_PERIOD, '.', KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
        assertTrue("最初のエラーへジャンプ", ed.getCurrentFilePath() != null);
        assertTrue("ファイル名がA.javaかB.java",
                ed.getCurrentFilePath().endsWith("A.java") || ed.getCurrentFilePath().endsWith("B.java"));
        assertTrue("エラーメッセージがステータスに出る", ed.getStatusMessage().startsWith("E: "));
        passed("testCtrlGtJumpsToNextErrorAcrossFiles");
    }

    static void testCtrlLtJumpsToPrevErrorAcrossFiles() throws IOException {
        Path root = freshProjectDir();
        writeSrc(root, "A.java", "public class A {\n    void m() {\n        int x = ;\n    }\n}\n");
        writeSrc(root, "B.java", "public class B {\n    void n() {\n        int y = ;\n    }\n}\n");
        ModalEditor ed = new ModalEditor("");
        ed.setProjectRoot(root);
        ed.processKey(KeyEvent.VK_COMMA, ',', KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
        assertTrue("前方向でもいずれかのエラーへジャンプする", ed.getCurrentFilePath() != null);
        assertTrue("エラーメッセージがステータスに出る", ed.getStatusMessage().startsWith("E: "));
        passed("testCtrlLtJumpsToPrevErrorAcrossFiles");
    }

    static void testWrapAroundForward() throws IOException {
        Path root = freshProjectDir();
        writeSrc(root, "Solo.java", "public class Solo {\n    void m() {\n        int x = ;\n    }\n}\n");
        ModalEditor ed = new ModalEditor("");
        ed.setProjectRoot(root);
        ed.processKey(KeyEvent.VK_PERIOD, '.', KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
        int firstRow = ed.getCursorRow();
        // 同じ唯一のエラーへ折り返して戻ってくるはず
        ed.processKey(KeyEvent.VK_PERIOD, '.', KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
        assertTrue("単一エラーで折り返しても同じ行", ed.getCursorRow() == firstRow);
        passed("testWrapAroundForward");
    }

    static void testWrapAroundBackward() throws IOException {
        Path root = freshProjectDir();
        writeSrc(root, "Solo.java", "public class Solo {\n    void m() {\n        int x = ;\n    }\n}\n");
        ModalEditor ed = new ModalEditor("");
        ed.setProjectRoot(root);
        ed.processKey(KeyEvent.VK_COMMA, ',', KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
        assertTrue("単一エラーへ折り返しでジャンプできる", ed.getCurrentFilePath() != null);
        passed("testWrapAroundBackward");
    }

    static void testWorksFromInsertMode() throws IOException {
        Path root = freshProjectDir();
        writeSrc(root, "A.java", "public class A {\n    void m() {\n        int x = ;\n    }\n}\n");
        ModalEditor ed = new ModalEditor("");
        ed.setProjectRoot(root);
        ed.processKey(KeyEvent.VK_I, 'i', 0); // NORMAL -> INSERT
        assertTrue("INSERTモードに入った", !ed.isNormalMode());
        ed.processKey(KeyEvent.VK_PERIOD, '.', KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
        assertTrue("INSERTモードのままジャンプできる", ed.getCurrentFilePath() != null);
        passed("testWorksFromInsertMode");
    }

    // -------------------------------------------------------------------------

    private static Path freshProjectDir() throws IOException {
        Path root = Files.createTempDirectory("compileErrNavTest");
        Files.createDirectories(root.resolve("src"));
        return root.resolve("src");
    }

    private static void writeSrc(Path srcDir, String name, String content) throws IOException {
        Files.writeString(srcDir.resolve(name), content);
    }

    private static void assertTrue(String label, boolean cond) {
        if (cond) passed(label); else failed(label);
    }

    private static void passed(String label) {
        passed++;
        System.out.println("[OK] " + label);
    }

    private static void failed(String label) {
        failed++;
        System.out.println("[NG] " + label);
    }
}
