package dev.javatexteditor.editor;

import dev.javatexteditor.telescope.BufferPicker;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ctrl+U(:bprev相当)/Ctrl+P(:bnext相当)で、開いている複数のファイルバッファを
 * BUFFER_REGISTRY（Main.java相当）経由で循環できることを検証する。
 * mainメソッド形式のテストハーネス（JUnit不使用）。
 */
public class BufferSwitchTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testCtrlPCyclesThroughOpenedFileBuffers();
        testCtrlUCyclesBackward();
        testCtrlUFallsBackToHistoryWithoutFilePath();

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

        // 修正前は bufferHistory に1件しか積まれず、Ctrl+P/Ctrl+U は何も動かなかった。
        ctrlP(ed); // wraps around to A (bnext)
        assertEquals("Ctrl+P wraps to first file (A)", a.toString(), ed.getCurrentFilePath());

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

        ctrlU(ed); // wraps around backward to B
        assertEquals("Ctrl+U wraps back to B", b.toString(), ed.getCurrentFilePath());
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
}
