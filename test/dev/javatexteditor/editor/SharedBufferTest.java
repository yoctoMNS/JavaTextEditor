package dev.javatexteditor.editor;

import dev.javatexteditor.buffer.UndoablePieceTable;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Vim方式の共有バッファ（複数ペインで同一ファイルを開いた場合、真に同じ
 * UndoablePieceTable インスタンスを参照させることで編集がリアルタイムに他ペインへ
 * 反映される）を検証する。Main.java の findLiveBuffer/syncSiblingBuffers/
 * shareBufferWithSplit 相当をテスト内の最小フェイクとして再現する。
 * mainメソッド形式のテストハーネス（JUnit不使用）。
 */
public class SharedBufferTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testOpeningSameFileInTwoPanesSharesBufferInstance();
        testEditInOnePaneReflectsInOtherPaneImmediately();
        testOnSharedBufferSyncFiresOnEdit();
        testSplitSharesBufferAndCursorPosition();
        testTelescopeExcursionPreservesSharedBufferIdentity();
        testOpeningDifferentFilesDoesNotShareBuffer();
        testUndoIsSharedAcrossPanes();

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

    static void assertTrue(String name, boolean cond) {
        if (cond) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name);
            fail++;
        }
    }

    static void assertSame(String name, Object expected, Object actual) {
        if (expected == actual) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name + " (expected same reference, got different instances)");
            fail++;
        }
    }

    /** Main.java の findLiveBuffer() を模した最小のフェイクペインレジストリ。 */
    private static final class FakePaneRegistry {
        final List<ModalEditor> editors = new ArrayList<>();

        void add(ModalEditor ed) { editors.add(ed); }

        UndoablePieceTable findLiveBuffer(String absolutePath) {
            if (absolutePath == null) return null;
            for (ModalEditor ed : editors) {
                if (absolutePath.equals(ed.getCurrentFilePath())) return ed.getBuffer();
            }
            return null;
        }
    }

    private static ModalEditor makeEditor(FakePaneRegistry reg) {
        ModalEditor ed = new ModalEditor("");
        ed.setLiveBufferLookup(reg::findLiveBuffer);
        reg.add(ed);
        return ed;
    }

    private static void openViaCommand(ModalEditor ed, String path) {
        ed.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : ("e " + path).toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    private static void insertText(ModalEditor ed, String text) {
        ed.processKey(KeyEvent.VK_UNDEFINED, 'i', 0);
        for (char c : text.toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
    }

    static void testOpeningSameFileInTwoPanesSharesBufferInstance() throws IOException {
        Path f = Files.createTempFile("shared-a", ".txt");
        Files.writeString(f, "hello");
        FakePaneRegistry reg = new FakePaneRegistry();
        ModalEditor a = makeEditor(reg);
        ModalEditor b = makeEditor(reg);

        openViaCommand(a, f.toString());
        openViaCommand(b, f.toString());

        assertSame("both panes reference the same UndoablePieceTable instance",
                a.getBuffer(), b.getBuffer());
    }

    static void testEditInOnePaneReflectsInOtherPaneImmediately() throws IOException {
        Path f = Files.createTempFile("shared-b", ".txt");
        Files.writeString(f, "hello");
        FakePaneRegistry reg = new FakePaneRegistry();
        ModalEditor a = makeEditor(reg);
        ModalEditor b = makeEditor(reg);
        openViaCommand(a, f.toString());
        openViaCommand(b, f.toString());

        insertText(a, "X");

        assertEquals("edit in pane A is visible via pane B's getText()", "Xhello", b.getText());
    }

    static void testOnSharedBufferSyncFiresOnEdit() throws IOException {
        Path f = Files.createTempFile("shared-c", ".txt");
        Files.writeString(f, "hello");
        FakePaneRegistry reg = new FakePaneRegistry();
        ModalEditor a = makeEditor(reg);
        ModalEditor b = makeEditor(reg);
        openViaCommand(a, f.toString());
        openViaCommand(b, f.toString());

        int[] syncCount = {0};
        a.setOnSharedBufferSync(() -> syncCount[0]++);

        insertText(a, "Y");
        assertTrue("onSharedBufferSync fired after edit", syncCount[0] > 0);

        int before = syncCount[0];
        a.processKey(KeyEvent.VK_L, 'l', 0); // カーソル移動のみ: バッファのversionは変わらない
        assertEquals("onSharedBufferSync does not fire on cursor-only movement",
                before, syncCount[0]);
    }

    static void testSplitSharesBufferAndCursorPosition() {
        ModalEditor a = new ModalEditor("line1\nline2\nline3");
        a.setCursor(1, 2);

        // :split 相当（Main.shareBufferWithSplit と同じ操作をここで再現する）
        ModalEditor b = new ModalEditor("line1\nline2\nline3");
        b.setBuffer(a.getBuffer());
        b.setCursor(a.getCursorRow(), a.getCursorCol());

        assertSame("split pane shares the same buffer instance", a.getBuffer(), b.getBuffer());
        assertEquals("split pane starts at the same cursor row", 1, b.getCursorRow());
        assertEquals("split pane starts at the same cursor col", 2, b.getCursorCol());

        insertText(a, "Z");
        assertEquals("edit in original pane visible in split pane", a.getText(), b.getText());
    }

    static void testTelescopeExcursionPreservesSharedBufferIdentity() throws IOException {
        Path f = Files.createTempFile("shared-d", ".txt");
        Files.writeString(f, "hello");
        FakePaneRegistry reg = new FakePaneRegistry();
        ModalEditor a = makeEditor(reg);
        openViaCommand(a, f.toString());
        UndoablePieceTable before = a.getBuffer();

        // SPC+f でtelescopeへ入り、Escで即キャンセルする（一時退避→復元がバッファ参照を
        // 保つこと＝共有が切れないことを確認する）
        a.processKey(KeyEvent.VK_SPACE, ' ', 0);
        a.processKey(KeyEvent.VK_F, 'f', 0);
        assertTrue("entered telescope mode", a.isTelescopeMode());
        a.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);

        assertSame("buffer identity preserved across telescope excursion", before, a.getBuffer());
    }

    static void testOpeningDifferentFilesDoesNotShareBuffer() throws IOException {
        Path f1 = Files.createTempFile("shared-e1", ".txt");
        Path f2 = Files.createTempFile("shared-e2", ".txt");
        Files.writeString(f1, "one");
        Files.writeString(f2, "two");
        FakePaneRegistry reg = new FakePaneRegistry();
        ModalEditor a = makeEditor(reg);
        ModalEditor b = makeEditor(reg);
        openViaCommand(a, f1.toString());
        openViaCommand(b, f2.toString());

        assertTrue("different files do not share a buffer instance",
                a.getBuffer() != b.getBuffer());
    }

    static void testUndoIsSharedAcrossPanes() throws IOException {
        Path f = Files.createTempFile("shared-f", ".txt");
        Files.writeString(f, "hello");
        FakePaneRegistry reg = new FakePaneRegistry();
        ModalEditor a = makeEditor(reg);
        ModalEditor b = makeEditor(reg);
        openViaCommand(a, f.toString());
        openViaCommand(b, f.toString());

        insertText(a, "Q");
        assertEquals("edit applied", "Qhello", a.getText());

        // undoはペインではなくバッファ単位（Vim互換）: bで押したuでもaの編集が取り消せる
        b.processKey(KeyEvent.VK_U, 'u', 0);
        assertEquals("undo from the other pane undoes the shared buffer's last edit",
                "hello", a.getText());
    }
}
