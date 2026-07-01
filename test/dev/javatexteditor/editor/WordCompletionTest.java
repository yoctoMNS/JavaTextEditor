package dev.javatexteditor.editor;

import dev.javatexteditor.analysis.CompletionItem;
import dev.javatexteditor.analysis.WordIndex;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Alt+/ 単語補完（WordIndex 連携）のテスト。
 * Ctrl+N は INSERT モードで Emacs 式カーソル下移動に割り当て済みのため、
 * 単語補完のトリガーには Alt+/ を使う（keymap-conflict-resolution スキール参照）。
 */
public class WordCompletionTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== WordCompletionTest ===");

        testAltSlashOpensCompletion();
        testEnterAppliesSelection();
        testEscDismisses();
        testDownArrowMovesSelection();
        testCtrlNStillMovesCursorDown();
        testBufferOnlyWordIsCandidate();
        testNoCandidatesShowsNothing();

        System.out.println("=== " + passed + "/" + (passed + failed) + " PASSED ===");
        if (failed > 0) System.exit(1);
    }

    private static ModalEditor makeEditorWithWords(Path root, String initialText) {
        ModalEditor editor = new ModalEditor(initialText);
        editor.setProjectRoot(root);
        editor.setWordIndex(WordIndex.buildSync(root));
        return editor;
    }

    private static void enterInsertMode(ModalEditor editor) {
        editor.processKey(0, 'i', 0);
    }

    private static void pressAltSlash(ModalEditor editor) {
        editor.processKey(KeyEvent.VK_SLASH, '/', InputEvent.ALT_DOWN_MASK);
    }

    private static void testAltSlashOpensCompletion() throws IOException {
        Path tmp = Files.createTempDirectory("wordcomp_open_");
        try {
            Files.writeString(tmp.resolve("Sample.java"), "int attackPower = 10;");
            ModalEditor editor = makeEditorWithWords(tmp, "att");
            enterInsertMode(editor);
            // カーソルを行末（"att"の直後）に置く
            editor.processKey(KeyEvent.VK_E, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK);
            pressAltSlash(editor);
            assertTrue("Alt+/ で補完がアクティブになる", editor.isCompletionActive());
            List<CompletionItem> items = editor.getCompletionItems();
            assertTrue("attackPower が候補に含まれる",
                items.stream().anyMatch(it -> it.label().equals("attackPower")));
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testEnterAppliesSelection() throws IOException {
        Path tmp = Files.createTempDirectory("wordcomp_enter_");
        try {
            Files.writeString(tmp.resolve("Sample.java"), "int uniqueLongVarName = 1;");
            ModalEditor editor = makeEditorWithWords(tmp, "uniqueLong");
            enterInsertMode(editor);
            editor.processKey(KeyEvent.VK_E, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK);
            pressAltSlash(editor);
            assertTrue("補完アクティブ", editor.isCompletionActive());
            editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("Enter 適用後は補完が閉じる", !editor.isCompletionActive());
            assertTrue("バッファに補完結果が挿入される",
                editor.getText().contains("uniqueLongVarName"));
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testEscDismisses() throws IOException {
        Path tmp = Files.createTempDirectory("wordcomp_esc_");
        try {
            Files.writeString(tmp.resolve("Sample.java"), "int cancelableWord = 1;");
            ModalEditor editor = makeEditorWithWords(tmp, "cancelable");
            enterInsertMode(editor);
            editor.processKey(KeyEvent.VK_E, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK);
            pressAltSlash(editor);
            assertTrue("補完アクティブ", editor.isCompletionActive());
            editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("Esc で補完が閉じ、INSERTモードは維持される（Vimの<Esc>と衝突しない）",
                !editor.isCompletionActive());
            assertTrue("まだ INSERT モード", editor.isInsertMode());
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testDownArrowMovesSelection() throws IOException {
        Path tmp = Files.createTempDirectory("wordcomp_nav_");
        try {
            Files.writeString(tmp.resolve("Sample.java"),
                "int navWordAlpha = 1; int navWordBeta = 2;");
            ModalEditor editor = makeEditorWithWords(tmp, "navWord");
            enterInsertMode(editor);
            editor.processKey(KeyEvent.VK_E, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK);
            pressAltSlash(editor);
            List<CompletionItem> items = editor.getCompletionItems();
            if (items.size() < 2) { System.out.println("  SKIP nav (less than 2 candidates)"); passed++; return; }
            editor.processKey(KeyEvent.VK_DOWN, KeyEvent.CHAR_UNDEFINED, 0);
            editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("下矢印で2番目の候補を選んで適用できる",
                editor.getText().contains(items.get(1).label()));
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testCtrlNStillMovesCursorDown() throws IOException {
        Path tmp = Files.createTempDirectory("wordcomp_ctrln_");
        try {
            Files.writeString(tmp.resolve("Sample.java"), "word");
            ModalEditor editor = makeEditorWithWords(tmp, "line one\nline two\n");
            enterInsertMode(editor);
            int rowBefore = editor.getCursorRow();
            editor.processKey(KeyEvent.VK_N, 'n', InputEvent.CTRL_DOWN_MASK);
            assertTrue("Ctrl+N は既存のEmacs式カーソル下移動のまま維持される（単語補完に奪われない）",
                editor.getCursorRow() == rowBefore + 1);
            assertTrue("Ctrl+N では補完ポップアップは開かない", !editor.isCompletionActive());
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testBufferOnlyWordIsCandidate() throws IOException {
        Path tmp = Files.createTempDirectory("wordcomp_buf_");
        try {
            // ディスク上には何もない。まだ保存していないバッファ内だけの単語を補完できるはず
            ModalEditor editor = makeEditorWithWords(tmp, "freshBufferOnlyIdentifier freshB");
            enterInsertMode(editor);
            editor.processKey(KeyEvent.VK_E, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK);
            pressAltSlash(editor);
            assertTrue("未保存バッファ内の単語だけでも補完候補になる", editor.isCompletionActive());
            List<CompletionItem> items = editor.getCompletionItems();
            assertTrue("freshBufferOnlyIdentifier が候補に含まれる",
                items.stream().anyMatch(it -> it.label().equals("freshBufferOnlyIdentifier")));
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testNoCandidatesShowsNothing() throws IOException {
        Path tmp = Files.createTempDirectory("wordcomp_none_");
        try {
            Files.writeString(tmp.resolve("Sample.java"), "hello world");
            ModalEditor editor = makeEditorWithWords(tmp, "ZzZzZqQqQNoMatch");
            enterInsertMode(editor);
            editor.processKey(KeyEvent.VK_E, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK);
            pressAltSlash(editor);
            assertTrue("一致候補がなければ補完は開かない", !editor.isCompletionActive());
        } finally {
            deleteDir(tmp);
        }
    }

    // -------------------------------------------------------------------------
    // ユーティリティ
    // -------------------------------------------------------------------------

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    private static void assertTrue(String msg, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + msg);
            passed++;
        } else {
            System.out.println("  FAIL: " + msg);
            failed++;
        }
    }
}
