package dev.javatexteditor.editor;

import dev.javatexteditor.telescope.BufferPicker;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ctrl+U(:bprev相当)/Ctrl+P(:bnext相当)、および:bnext/:bprev/:bn/:bpコマンドで、
 * 開いている複数のファイルバッファをBUFFER_REGISTRY（Main.java相当）経由で
 * 移動できることを検証する。末尾/先頭ではラップアラウンドせず留まる。
 * mainメソッド形式のテストハーネス（JUnit不使用）。
 */
public class BufferSwitchTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testCtrlPCyclesThroughOpenedFileBuffers();
        testCtrlUCyclesBackward();
        testCtrlUFallsBackToHistoryWithoutFilePath();
        testBnextCommandMovesForward();
        testBprevCommandMovesBackward();
        testBnextStaysAtLastBuffer();
        testBprevStaysAtFirstBuffer();
        testNewFileCreatedViaColonEIsRegistered();
        testSaveNewBufferRegistersAbsolutePath();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
        System.exit(0);
    }

    static void assertEquals(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name + " expected=" + expected + " actual=" + actual);
            fail++;
        }
    }

    /** Main.java の BUFFER_REGISTRY を模した最小のレジストリ実装。 */
    private static final class FakeRegistry {
        final List<BufferPicker.BufferEntry> entries = new ArrayList<>();

        void register(BufferPicker.BufferEntry e) {
            if (e.filePath() == null) return;
            for (var existing : entries) {
                if (existing.filePath().equals(e.filePath())) return;
            }
            entries.add(e);
        }
    }

    private static ModalEditor makeEditorWithRegistry(FakeRegistry reg) {
        ModalEditor ed = new ModalEditor("");
        ed.setBufferListSupplier(() -> new ArrayList<>(reg.entries));
        ed.setOnFileOpened(reg::register);
        return ed;
    }

    private static void openViaCommand(ModalEditor ed, String path) {
        ed.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : ("e " + path).toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    private static void ctrlU(ModalEditor ed) {
        ed.processKey(KeyEvent.VK_U, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK);
    }

    private static void ctrlP(ModalEditor ed) {
        ed.processKey(KeyEvent.VK_P, KeyEvent.CHAR_UNDEFINED, KeyEvent.CTRL_DOWN_MASK);
    }

    static void testCtrlPCyclesThroughOpenedFileBuffers() throws IOException {
        Path a = Files.createTempFile("bswitch-a", ".txt");
        Path b = Files.createTempFile("bswitch-b", ".txt");
        Path c = Files.createTempFile("bswitch-c", ".txt");
        Files.writeString(a, "AAA");
        Files.writeString(b, "BBB");
        Files.writeString(c, "CCC");

        FakeRegistry reg = new FakeRegistry();
        ModalEditor ed = makeEditorWithRegistry(reg);

        // ここで3ファイルを開く（本番では telescope/openTelescopeSelection や :e で開くのと同等）。
        openViaCommand(ed, a.toString());
        openViaCommand(ed, b.toString());
        openViaCommand(ed, c.toString());
        assertEquals("registry has 3 entries", 3, reg.entries.size());
        assertEquals("current file is C after opening 3 files", c.toString(), ed.getCurrentFilePath());

        // 末尾(C)にいる状態でCtrl+Pを押してもラップアラウンドせず留まる。
        ctrlP(ed);
        assertEquals("Ctrl+P at last buffer stays at C", c.toString(), ed.getCurrentFilePath());

        // Ctrl+Uで先頭方向へ戻ってからCtrl+Pで前進できることを確認する。
        ctrlU(ed);
        ctrlU(ed);
        assertEquals("Ctrl+U twice moves to A", a.toString(), ed.getCurrentFilePath());

        ctrlP(ed);
        assertEquals("Ctrl+P moves to B", b.toString(), ed.getCurrentFilePath());
    }

    static void testCtrlUCyclesBackward() throws IOException {
        Path a = Files.createTempFile("bswitch2-a", ".txt");
        Path b = Files.createTempFile("bswitch2-b", ".txt");
        Files.writeString(a, "AAA");
        Files.writeString(b, "BBB");

        FakeRegistry reg = new FakeRegistry();
        ModalEditor ed = makeEditorWithRegistry(reg);

        openViaCommand(ed, a.toString());
        openViaCommand(ed, b.toString());
        assertEquals("current file is B", b.toString(), ed.getCurrentFilePath());

        ctrlU(ed); // :bprev
        assertEquals("Ctrl+U moves back to A", a.toString(), ed.getCurrentFilePath());

        // 先頭(A)にいる状態でCtrl+Uを押してもラップアラウンドせず留まる。
        ctrlU(ed);
        assertEquals("Ctrl+U at first buffer stays at A", a.toString(), ed.getCurrentFilePath());
    }

    static void testCtrlUFallsBackToHistoryWithoutFilePath() {
        // ファイルパスを持たないバッファ（:enew相当）では、従来の bufferHistory 方式に
        // フォールバックし、Ctrl+U で元のバッファに戻れることを確認する。
        ModalEditor ed = new ModalEditor("original text");
        // BufferListSupplier を設定しない = Main.java 未接続の状態でも壊れないことも兼ねて確認。
        ed.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char ch : "enew".toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, ch, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
        assertEquals("new buffer has no file path", null, ed.getCurrentFilePath());

        ctrlU(ed);
        assertEquals("Ctrl+U restores original buffer text", "original text", ed.getText());
    }

    private static void colonCommand(ModalEditor ed, String cmd) {
        ed.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : cmd.toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    static void testBnextCommandMovesForward() throws IOException {
        Path a = Files.createTempFile("bswitch3-a", ".txt");
        Path b = Files.createTempFile("bswitch3-b", ".txt");
        Files.writeString(a, "AAA");
        Files.writeString(b, "BBB");

        FakeRegistry reg = new FakeRegistry();
        ModalEditor ed = makeEditorWithRegistry(reg);
        openViaCommand(ed, a.toString());
        openViaCommand(ed, b.toString());

        colonCommand(ed, "bprev");
        assertEquals(":bprev moves to A", a.toString(), ed.getCurrentFilePath());

        colonCommand(ed, "bnext");
        assertEquals(":bnext moves to B", b.toString(), ed.getCurrentFilePath());
    }

    static void testBprevCommandMovesBackward() throws IOException {
        Path a = Files.createTempFile("bswitch4-a", ".txt");
        Path b = Files.createTempFile("bswitch4-b", ".txt");
        Files.writeString(a, "AAA");
        Files.writeString(b, "BBB");

        FakeRegistry reg = new FakeRegistry();
        ModalEditor ed = makeEditorWithRegistry(reg);
        openViaCommand(ed, a.toString());
        openViaCommand(ed, b.toString());
        assertEquals("current file is B", b.toString(), ed.getCurrentFilePath());

        colonCommand(ed, "bp");
        assertEquals(":bp moves to A", a.toString(), ed.getCurrentFilePath());
    }

    static void testBnextStaysAtLastBuffer() throws IOException {
        Path a = Files.createTempFile("bswitch5-a", ".txt");
        Path b = Files.createTempFile("bswitch5-b", ".txt");
        Files.writeString(a, "AAA");
        Files.writeString(b, "BBB");

        FakeRegistry reg = new FakeRegistry();
        ModalEditor ed = makeEditorWithRegistry(reg);
        openViaCommand(ed, a.toString());
        openViaCommand(ed, b.toString());
        assertEquals("current file is B (last)", b.toString(), ed.getCurrentFilePath());

        colonCommand(ed, "bn");
        assertEquals(":bn at last buffer stays at B", b.toString(), ed.getCurrentFilePath());
    }

    static void testBprevStaysAtFirstBuffer() throws IOException {
        Path a = Files.createTempFile("bswitch6-a", ".txt");
        Path b = Files.createTempFile("bswitch6-b", ".txt");
        Files.writeString(a, "AAA");
        Files.writeString(b, "BBB");

        FakeRegistry reg = new FakeRegistry();
        ModalEditor ed = makeEditorWithRegistry(reg);
        openViaCommand(ed, a.toString());
        openViaCommand(ed, b.toString());

        colonCommand(ed, "bprev");
        assertEquals("current file is A (first)", a.toString(), ed.getCurrentFilePath());

        colonCommand(ed, "bprev");
        assertEquals(":bprev at first buffer stays at A", a.toString(), ed.getCurrentFilePath());
    }

    /**
     * 不具合修正の回帰テスト: `:e newfile.txt`（存在しない相対パス＝新規ファイル）で
     * 開いたバッファが BUFFER_REGISTRY に登録されず、Ctrl+U で元々開いていたファイルへ
     * 戻れなくなっていた（バッファ遷移が0個になる）不具合。
     */
    static void testNewFileCreatedViaColonEIsRegistered() throws IOException {
        Path dir = Files.createTempDirectory("bswitch-newfile");
        Path a = dir.resolve("a.txt");
        Files.writeString(a, "AAA");

        FakeRegistry reg = new FakeRegistry();
        ModalEditor ed = makeEditorWithRegistry(reg);
        ed.setProjectRoot(dir);

        openViaCommand(ed, a.toString());
        assertEquals("registry has 1 entry after opening A", 1, reg.entries.size());

        colonCommand(ed, "e newfile.txt");
        assertEquals("registry has 2 entries after creating new file", 2, reg.entries.size());

        ctrlU(ed);
        assertEquals("Ctrl+U returns to originally-open file A", a.toString(), ed.getCurrentFilePath());
    }

    /**
     * 不具合修正の回帰テスト: `:enew` で作った無名バッファを相対パスで `:w` 保存すると、
     * currentFilePath が絶対パスへ更新されず（ディスクへの書き込み先とcurrentFilePathの
     * パス形式が食い違い）、BUFFER_REGISTRY にも登録されなかった不具合。
     */
    static void testSaveNewBufferRegistersAbsolutePath() throws IOException {
        Path dir = Files.createTempDirectory("bswitch-savenew");
        Path a = dir.resolve("a.txt");
        Files.writeString(a, "AAA");

        FakeRegistry reg = new FakeRegistry();
        ModalEditor ed = makeEditorWithRegistry(reg);
        ed.setProjectRoot(dir);

        openViaCommand(ed, a.toString());
        assertEquals("registry has 1 entry after opening A", 1, reg.entries.size());

        colonCommand(ed, "enew");
        assertEquals("new buffer has no file path", null, ed.getCurrentFilePath());

        ed.processKey(KeyEvent.VK_UNDEFINED, 'i', 0);
        for (char c : "hello".toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);

        colonCommand(ed, "w newname.txt");

        Path expected = dir.resolve("newname.txt").toAbsolutePath();
        assertEquals("currentFilePath is resolved to an absolute path", expected.toString(), ed.getCurrentFilePath());
        assertEquals("registry has 2 entries after saving new buffer", 2, reg.entries.size());
        assertEquals("file content written to disk", "hello", Files.readString(expected));

        ctrlU(ed);
        assertEquals("Ctrl+U returns to originally-open file A", a.toString(), ed.getCurrentFilePath());
    }
}
