package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Mode.BINARY（:bコマンド・非UTF-8ファイル自動判定オープンで入るhexdump編集モード）のテスト。
 * カーソル移動（1バイト単位）・16進数2桁上書き入力での自動前進・undo・:wでの保存・
 * :bによるテキスト表示との相互トグルを検証する。
 * mainメソッド形式のテストハーネス（JUnit不使用）。
 */
public class BinaryEditModeTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testHexDigitOverwriteAndSave();
        testNibbleAutoAdvance();
        testCursorClampsAtStartAndEnd();
        testUndoRevertsHexEdit();
        testToggleTextToBinaryAndBackRoundTrips();
        testToggleReflectsEditsMadeInBinaryMode();
        testToggleNonUtf8BytesStaysInBinaryMode();
        testColonCommandFromBinaryReturnsToBinary();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
        System.exit(0);
    }

    static void assertTrue(String name, boolean condition) {
        if (condition) { System.out.println("  PASS: " + name); pass++; }
        else { System.out.println("  FAIL: " + name); fail++; }
    }

    static void assertEquals(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) { System.out.println("  PASS: " + name); pass++; }
        else { System.out.println("  FAIL: " + name + " expected=" + expected + " actual=" + actual); fail++; }
    }

    private static void openViaCommand(ModalEditor ed, String path) {
        ed.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : ("e " + path).toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    private static void type(ModalEditor ed, String s) {
        for (char c : s.toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
    }

    private static void runColonCommand(ModalEditor ed, String cmd) {
        ed.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        type(ed, cmd);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    static void testHexDigitOverwriteAndSave() throws IOException {
        Path bin = Files.createTempFile("binedit-a", ".dat");
        byte[] original = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04};
        Files.write(bin, original);

        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, bin.toString());

        // カーソルはbyte0にある。lで2回進めてbyte2へ移動し、"ff"で上書きする。
        ed.processKey(KeyEvent.VK_UNDEFINED, 'l', 0);
        ed.processKey(KeyEvent.VK_UNDEFINED, 'l', 0);
        type(ed, "ff");

        runColonCommand(ed, "w");

        byte[] expected = new byte[]{0x00, 0x01, (byte) 0xFF, 0x03, 0x04};
        byte[] actual = Files.readAllBytes(bin);
        assertTrue("byte2だけが0xFFに上書きされ、他は変化しない", Arrays.equals(expected, actual));
    }

    static void testNibbleAutoAdvance() throws IOException {
        Path bin = Files.createTempFile("binedit-b", ".dat");
        Files.write(bin, new byte[]{0x00, 0x00});

        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, bin.toString());

        int rowBefore = ed.getCursorRow();
        int colBefore = ed.getCursorCol();
        ed.processKey(KeyEvent.VK_UNDEFINED, 'a', 0); // 高位4bit入力
        assertEquals("1桁目入力後は同じ行", rowBefore, ed.getCursorRow());
        assertEquals("1桁目入力後は表示カーソルが1つ右（低位4bit待ち）", colBefore + 1, ed.getCursorCol());

        ed.processKey(KeyEvent.VK_UNDEFINED, 'b', 0); // 低位4bit入力→確定・次バイトへ自動前進
        assertEquals("2桁目入力後は次バイトの列位置へ前進", colBefore + 3, ed.getCursorCol());

        runColonCommand(ed, "w");
        byte[] actual = Files.readAllBytes(bin);
        assertEquals("2桁入力(ab)でbyte0が0xABになる", (byte) 0xAB, actual[0]);
    }

    static void testCursorClampsAtStartAndEnd() throws IOException {
        Path bin = Files.createTempFile("binedit-c", ".dat");
        Files.write(bin, new byte[]{0x11, 0x22, 0x33});

        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, bin.toString());

        int colAtStart = ed.getCursorCol();
        ed.processKey(KeyEvent.VK_UNDEFINED, 'h', 0); // 先頭で左移動しても留まる
        assertTrue("先頭でhを押しても同じ位置に留まる", colAtStart == ed.getCursorCol());

        ed.processKey(KeyEvent.VK_UNDEFINED, 'l', 0);
        ed.processKey(KeyEvent.VK_UNDEFINED, 'l', 0);
        int colAtLastByte = ed.getCursorCol();
        ed.processKey(KeyEvent.VK_UNDEFINED, 'l', 0); // 末尾を超えて移動しようとしても留まる
        assertEquals("末尾でlを押しても最後のバイトに留まる", colAtLastByte, ed.getCursorCol());
    }

    static void testUndoRevertsHexEdit() throws IOException {
        Path bin = Files.createTempFile("binedit-d", ".dat");
        byte[] original = new byte[]{0x05};
        Files.write(bin, original);

        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, bin.toString());

        type(ed, "ff");
        // 1桁の上書きは(16進数字2文字+ASCII文字1文字)のdelete+insertで複数undo単位になるため、
        // 十分な回数uを押す（スタックが空になれば以降は安全なno-op）。
        for (int i = 0; i < 12; i++) {
            ed.processKey(KeyEvent.VK_UNDEFINED, 'u', 0);
        }

        runColonCommand(ed, "w");
        byte[] actual = Files.readAllBytes(bin);
        assertTrue("uを繰り返すと元のバイト列に戻る", Arrays.equals(original, actual));
    }

    static void testToggleTextToBinaryAndBackRoundTrips() {
        ModalEditor ed = new ModalEditor("Hello\nWorld\n");
        runColonCommand(ed, "b");
        assertTrue(":bでMode.BINARYへ入る", ed.isBinaryMode());
        assertTrue("hexdumpヘッダを含む", ed.getText().startsWith("*binary*"));

        runColonCommand(ed, "b");
        assertTrue("再度:bでテキスト表示へ戻る", !ed.isBinaryMode());
        assertEquals("往復後もテキスト内容が完全に一致する", "Hello\nWorld\n", ed.getText());
    }

    static void testToggleReflectsEditsMadeInBinaryMode() {
        ModalEditor ed = new ModalEditor("AB");
        runColonCommand(ed, "b");
        // 'A'(0x41)を'Z'(0x5A)へ上書きする
        type(ed, "5a");
        runColonCommand(ed, "b");
        assertEquals("バイナリモード中の編集はテキスト復帰後も反映される", "ZB", ed.getText());
    }

    static void testToggleNonUtf8BytesStaysInBinaryMode() {
        ModalEditor ed = new ModalEditor("A");
        runColonCommand(ed, "b");
        // 0x41('A')をUTF-8として不正な単独継続バイト0x80へ上書きする
        type(ed, "80");
        runColonCommand(ed, "b");
        assertTrue("不正なUTF-8バイト列はテキストへ戻せずMode.BINARYのまま", ed.isBinaryMode());
    }

    static void testColonCommandFromBinaryReturnsToBinary() throws IOException {
        Path bin = Files.createTempFile("binedit-e", ".dat");
        Files.write(bin, new byte[]{0x00, 0x01, 0x02}); // NULを含めてBinaryFileDetectorに検出させる

        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, bin.toString());
        assertTrue("初回オープン後はMode.BINARY", ed.isBinaryMode());

        // :b以外のコマンド（:w）を実行してもMode.BINARYへ戻る（NORMALへ落ちない）
        runColonCommand(ed, "w");
        assertTrue(":w実行後もMode.BINARYを維持する", ed.isBinaryMode());
    }
}
