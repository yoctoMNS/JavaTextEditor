package dev.javatexteditor.editor;

import dev.javatexteditor.markdown.MarkdownRenderer;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * :viewコマンドで.mdバッファがMarkdownRendererによる読み取り専用の閲覧ビューへ切り替わり、
 * :markコマンドで元のソース・ファイルパス・カーソル位置へ復元されることを検証する。
 * mainメソッド形式のテストハーネス（JUnit不使用）。
 */
public class MarkdownViewTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testViewRendersMarkdownAndNullsFilePath();
        testMarkRestoresSourceFilePathAndCursor();
        testViewPreservesBufferReferenceThroughRoundTrip();
        testViewOnNonMarkdownFileShowsError();
        testMarkWithoutViewShowsError();
        testViewIdempotentWhenAlreadyViewing();
        testViewInvalidatedAfterSwitchingToAnotherFile();
        testSaveWhileViewingFailsAndDoesNotTouchDiskFile();
        testMarkdownExtensionVariantAccepted();
        testEditsWhileViewingDoNotAffectRestoredSource();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
        System.exit(0);
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
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name + " expected=" + expected + " actual=" + actual);
            fail++;
        }
    }

    private static void openViaCommand(ModalEditor ed, String path) {
        ed.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : ("e " + path).toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    private static void runCommand(ModalEditor ed, String cmd) {
        ed.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : cmd.toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    private static Path writeTempFile(String prefix, String suffix, String content) throws IOException {
        Path file = Files.createTempFile(prefix, suffix);
        Files.writeString(file, content);
        return file;
    }

    static void testViewRendersMarkdownAndNullsFilePath() throws IOException {
        String source = "# Hello\n\nSome **bold** text.\n";
        Path md = writeTempFile("markdown-view-a", ".md", source);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, md.toString());

        runCommand(ed, "view");

        String expected = MarkdownRenderer.render(md.getFileName().toString(), source);
        assertEquals("MarkdownRenderer.render()と同じ内容が表示される", expected, ed.getText());
        assertTrue("ヘッダは*view*で始まる", ed.getText().startsWith("*view* "));
        assertEquals("読み取り専用プレビューのためcurrentFilePathはnull", null, ed.getCurrentFilePath());
        assertEquals("カーソルは先頭に戻る(行)", 0, ed.getCursorRow());
        assertEquals("カーソルは先頭に戻る(列)", 0, ed.getCursorCol());
    }

    static void testMarkRestoresSourceFilePathAndCursor() throws IOException {
        String source = "# Hello\nline two\nline three\n";
        Path md = writeTempFile("markdown-view-b", ".md", source);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, md.toString());
        ed.setCursor(1, 2); // 適当な位置へカーソルを移動してから:viewする

        runCommand(ed, "view");
        runCommand(ed, "mark");

        assertEquals("ソースが完全に復元される", source, ed.getText());
        assertEquals("ファイルパスが復元される", md.toString(), ed.getCurrentFilePath());
        assertEquals(":view前のカーソル行が復元される", 1, ed.getCursorRow());
        assertEquals(":view前のカーソル列が復元される", 2, ed.getCursorCol());
    }

    static void testViewPreservesBufferReferenceThroughRoundTrip() throws IOException {
        // :split等の共有バッファ機構は同一ファイルを指す全ペインが同じUndoablePieceTable参照を
        // 共有する設計（CLAUDE.md「共有バッファ」節参照）のため、:view/:markの往復でも
        // 参照そのものを保つ必要がある（Stringスナップショットで退避すると壊れてしまう）。
        String source = "# Shared\n";
        Path md = writeTempFile("markdown-view-c", ".md", source);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, md.toString());
        var originalBuffer = ed.getBuffer();

        runCommand(ed, "view");
        assertTrue(":view中はbuffer参照が新しい閲覧ビュー用インスタンスに入れ替わる",
            ed.getBuffer() != originalBuffer);

        runCommand(ed, "mark");
        assertTrue(":mark後は元のbuffer参照がそのまま(同一オブジェクトとして)戻る",
            ed.getBuffer() == originalBuffer);
    }

    static void testViewOnNonMarkdownFileShowsError() throws IOException {
        Path txt = writeTempFile("markdown-view-notmd", ".txt", "plain text\n");
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, txt.toString());
        String before = ed.getText();

        runCommand(ed, "view");

        assertTrue("エラーメッセージが表示される", ed.getStatusMessage().startsWith("E:"));
        assertEquals(".mdでないバッファの内容は変化しない", before, ed.getText());
        assertEquals("currentFilePathも変化しない", txt.toString(), ed.getCurrentFilePath());
    }

    static void testMarkWithoutViewShowsError() throws IOException {
        String source = "# Not viewing yet\n";
        Path md = writeTempFile("markdown-view-d", ".md", source);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, md.toString());

        runCommand(ed, "mark");

        assertTrue("エラーメッセージが表示される", ed.getStatusMessage().startsWith("E:"));
        assertEquals("ソースの内容は変化しない", source, ed.getText());
        assertEquals("currentFilePathも変化しない", md.toString(), ed.getCurrentFilePath());
    }

    static void testViewIdempotentWhenAlreadyViewing() throws IOException {
        String source = "# Twice\n";
        Path md = writeTempFile("markdown-view-e", ".md", source);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, md.toString());

        runCommand(ed, "view");
        String afterFirstView = ed.getText();
        runCommand(ed, "view");

        assertEquals("2回目の:viewは内容を変えない", afterFirstView, ed.getText());
        assertTrue("案内メッセージが出る", ed.getStatusMessage().contains("already in markdown view"));
    }

    static void testViewInvalidatedAfterSwitchingToAnotherFile() throws IOException {
        String source = "# A\n";
        Path md = writeTempFile("markdown-view-f", ".md", source);
        Path other = writeTempFile("markdown-view-other", ".md", "# B\n");
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, md.toString());
        runCommand(ed, "view");

        openViaCommand(ed, other.toString()); // 別ファイルへ切り替え（bufferの参照が変わる）
        String before = ed.getText();
        runCommand(ed, "mark");

        assertTrue("別バッファへ切り替え後は:markが無効化されエラーになる", ed.getStatusMessage().startsWith("E:"));
        assertEquals("別バッファの内容は変化しない", before, ed.getText());
    }

    static void testSaveWhileViewingFailsAndDoesNotTouchDiskFile() throws IOException {
        String source = "# Precious\n\nDo not overwrite me.\n";
        Path md = writeTempFile("markdown-view-g", ".md", source);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, md.toString());

        runCommand(ed, "view");
        runCommand(ed, "w");

        assertTrue(":view中の:wは保存先が無くエラーになる", ed.getStatusMessage().startsWith("E:"));
        assertEquals("ディスク上の実ファイルは書き換えられない", source, Files.readString(md));
    }

    static void testMarkdownExtensionVariantAccepted() throws IOException {
        String source = "# Long extension\n";
        Path md = writeTempFile("markdown-view-h", ".markdown", source);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, md.toString());

        runCommand(ed, "view");

        assertTrue(".markdown拡張子でも:viewが働く", ed.getText().startsWith("*view* "));
    }

    static void testEditsWhileViewingDoNotAffectRestoredSource() throws IOException {
        String source = "# Original\nkeep me\n";
        Path md = writeTempFile("markdown-view-i", ".md", source);
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, md.toString());

        runCommand(ed, "view");
        String beforeEdit = ed.getText();
        // プレビュー中に誤って何か入力してしまうケースを想定（既存の疑似バッファと同じく
        // キー入力自体はブロックしない「保存先が無い」だけの読み取り専用規約）。
        ed.processKey(KeyEvent.VK_UNDEFINED, 'x', 0); // NORMALモードの'x'はカーソル位置の1文字削除
        assertTrue("プレビューバッファ自体は編集できてしまう(既存の疑似バッファと同じ仕様)",
            !ed.getText().equals(beforeEdit));

        runCommand(ed, "mark");
        assertEquals("プレビュー中の編集は元のソースに影響しない", source, ed.getText());
    }
}
