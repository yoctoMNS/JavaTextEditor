package dev.javatexteditor.editor;

import dev.javatexteditor.analysis.CompletionIndex;
import dev.javatexteditor.analysis.CompletionItem;
import dev.javatexteditor.analysis.JdkClassIndex;
import dev.javatexteditor.analysis.WordIndex;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Cバッファの入力補完（Ctrl+Space / Alt+/）が、プロジェクトルート配下の単語 + #include している
 * ヘッダの識別子だけを候補にし、Javaクラス名（CompletionIndex）を一切表示しないことを検証する
 * （CLAUDE.md「C言語のファイルを開いているときの入力補完候補」節）。
 */
public class CWordCompletionTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== CWordCompletionTest ===");

        testCtrlSpaceExcludesJdkClassNamesForCBuffer();
        testCtrlSpaceIncludesIncludedHeaderSymbols();
        testAltSlashIncludesIncludedHeaderSymbols();
        testJavaBufferStillShowsJdkClassNames();

        System.out.println("=== " + passed + "/" + (passed + failed) + " PASSED ===");
        if (failed > 0) System.exit(1);
    }

    private static void enterInsertMode(ModalEditor editor) {
        editor.processKey(0, 'i', 0);
    }

    private static void pressAltSlash(ModalEditor editor) {
        editor.processKey(KeyEvent.VK_SLASH, '/', InputEvent.ALT_DOWN_MASK);
    }

    private static void pressCtrlSpace(ModalEditor editor) {
        editor.processKey(KeyEvent.VK_SPACE, ' ', InputEvent.CTRL_DOWN_MASK);
    }

    /** カーソルを現在行の末尾へ移動する（Ctrl+E = Emacs式行末移動）。 */
    private static void moveToLineEnd(ModalEditor editor) {
        editor.processKey(KeyEvent.VK_E, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK);
    }

    private static void testCtrlSpaceExcludesJdkClassNamesForCBuffer() throws IOException {
        Path dir = Files.createTempDirectory("ccomp_noclass_");
        try {
            // "St" で始まる単語を12個用意し wordIndex 単独で枠を埋め尽くす状況を作る
            // （WordCompletionTest の Java 版テストと対になる、Cバッファでは絶対に cls が出ないことの確認）。
            StringBuilder sample = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sample.append("int StVar").append(i).append(" = 0;\n");
            }
            String initial = "St";
            Path mainFile = dir.resolve("main.c");
            Files.writeString(mainFile, sample.toString());
            Files.writeString(dir.resolve("Sample.java"), sample.toString());

            ModalEditor editor = new ModalEditor(initial, mainFile.toString(), null);
            editor.setProjectRoot(dir);
            editor.setWordIndex(WordIndex.buildSync(dir));
            JdkClassIndex jdkIndex = JdkClassIndex.buildSync();
            editor.setJdkClassIndex(jdkIndex);
            editor.setCompletionIndex(CompletionIndex.buildSync(jdkIndex));

            enterInsertMode(editor);
            moveToLineEnd(editor);
            pressCtrlSpace(editor);

            assertTrue("Ctrl+Space で補完がアクティブになる", editor.isCompletionActive());
            List<CompletionItem> items = editor.getCompletionItems();
            assertTrue("Cバッファでは JDK クラス名（kind=cls）が一切候補に出ない",
                items.stream().noneMatch(it -> "cls".equals(it.kind())));
        } finally {
            deleteDir(dir);
        }
    }

    private static void testCtrlSpaceIncludesIncludedHeaderSymbols() throws IOException {
        Path dir = Files.createTempDirectory("ccomp_header_");
        try {
            Files.writeString(dir.resolve("mylib.h"),
                "int myLibraryHelperFunction(int x);\n");
            String initial = "#include \"mylib.h\"\nmyLibrary";
            Path mainFile = dir.resolve("main.c");
            Files.writeString(mainFile, initial);

            ModalEditor editor = new ModalEditor(initial, mainFile.toString(), null);
            editor.setProjectRoot(dir);
            editor.setWordIndex(WordIndex.buildSync(dir));

            enterInsertMode(editor);
            // カーソルを2行目の末尾（"myLibrary"の直後）に置く
            editor.setCursor(1, "myLibrary".length());
            pressCtrlSpace(editor);

            assertTrue("Ctrl+Space で補完がアクティブになる", editor.isCompletionActive());
            List<CompletionItem> items = editor.getCompletionItems();
            assertTrue("#include したヘッダ内の識別子が候補に含まれる",
                items.stream().anyMatch(it -> it.label().equals("myLibraryHelperFunction")));
        } finally {
            deleteDir(dir);
        }
    }

    private static void testAltSlashIncludesIncludedHeaderSymbols() throws IOException {
        Path dir = Files.createTempDirectory("ccomp_altslash_header_");
        try {
            Files.writeString(dir.resolve("mylib.h"),
                "#define MY_SPECIAL_CONSTANT 42\n");
            String initial = "#include \"mylib.h\"\nMY_SPEC";
            Path mainFile = dir.resolve("main.c");
            Files.writeString(mainFile, initial);

            ModalEditor editor = new ModalEditor(initial, mainFile.toString(), null);
            editor.setProjectRoot(dir);
            editor.setWordIndex(WordIndex.buildSync(dir));

            enterInsertMode(editor);
            editor.setCursor(1, "MY_SPEC".length());
            pressAltSlash(editor);

            assertTrue("Alt+/ で補完がアクティブになる", editor.isCompletionActive());
            List<CompletionItem> items = editor.getCompletionItems();
            assertTrue("#include したヘッダ内のマクロ定数が候補に含まれる",
                items.stream().anyMatch(it -> it.label().equals("MY_SPECIAL_CONSTANT")));
        } finally {
            deleteDir(dir);
        }
    }

    private static void testJavaBufferStillShowsJdkClassNames() throws IOException {
        Path dir = Files.createTempDirectory("ccomp_javastillworks_");
        try {
            Path mainFile = dir.resolve("Main.java");
            String initial = "St";
            Files.writeString(mainFile, initial);

            ModalEditor editor = new ModalEditor(initial, mainFile.toString(), null);
            editor.setProjectRoot(dir);
            editor.setWordIndex(WordIndex.buildSync(dir));
            JdkClassIndex jdkIndex = JdkClassIndex.buildSync();
            editor.setJdkClassIndex(jdkIndex);
            editor.setCompletionIndex(CompletionIndex.buildSync(jdkIndex));

            enterInsertMode(editor);
            moveToLineEnd(editor);
            pressCtrlSpace(editor);

            assertTrue("Ctrl+Space で補完がアクティブになる", editor.isCompletionActive());
            List<CompletionItem> items = editor.getCompletionItems();
            assertTrue("Javaバッファでは従来どおり JDK クラス名が候補に含まれる（回帰なし）",
                items.stream().anyMatch(it -> "cls".equals(it.kind()) && it.label().startsWith("St")));
        } finally {
            deleteDir(dir);
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
