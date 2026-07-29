package dev.javatexteditor.editor;

import dev.javatexteditor.analysis.AutoImportHandler;
import dev.javatexteditor.analysis.CompletionIndex;
import dev.javatexteditor.analysis.CompletionItem;
import dev.javatexteditor.analysis.ImportSuggester;
import dev.javatexteditor.analysis.JdkClassIndex;
import dev.javatexteditor.analysis.SourceAnalyzer;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Java バッファの入力補完（IntelliJ IDEA 方式）のテスト。
 *
 * <p>キーバインドは従来どおり（Ctrl+Space / 1文字目からの自動表示 / Tab・Enter で確定）であり、
 * 変わったのは「候補の作り方・並べ方・確定したときに何が起きるか」である。ここではその3点を見る。
 *
 * <p>JDK クラス索引の構築を伴うため、他のテストより実行に時間がかかる。
 */
public class IntelliJCompletionTest {

    private static int passed = 0;
    private static int failed = 0;

    private static JdkClassIndex jdkIndex;

    public static void main(String[] args) throws Exception {
        System.out.println("=== IntelliJCompletionTest ===");
        jdkIndex = JdkClassIndex.buildSync();

        testMemberCompletionAfterDot();
        testMemberCandidateShowsSignature();
        testMethodInsertsParenthesesAndPlacesCaretInside();
        testFieldInsertsPlainName();
        testKeywordIsCandidate();
        testCamelCaseMatchesLocalWord();
        testDotOnUnknownReceiverShowsNothing();
        testAccurateMemberResolutionForMethodChain();
        testClassCandidateInsertsImport();
        testNonJavaBufferKeepsWordCompletion();

        System.out.println("=== " + passed + "/" + (passed + failed) + " PASSED ===");
        if (failed > 0) System.exit(1);
    }

    // -------------------------------------------------------------------------

    private static ModalEditor javaEditor(String text, Path dir) {
        ModalEditor editor = new ModalEditor(text, dir.resolve("Sample.java").toString(), null);
        editor.setProjectRoot(dir);
        editor.setJdkClassIndex(jdkIndex);
        return editor;
    }

    /** INSERT モードに入り、末尾に text を1文字ずつ打ち込む（自動表示の経路をそのまま通す）。 */
    private static void typeAtEnd(ModalEditor editor, int row, String text) {
        editor.setCursor(row, editor.getLine(row).length());
        editor.processKey(0, 'i', 0);
        for (char c : text.toCharArray()) {
            if (c == '\n') {
                editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
            } else {
                editor.processKey(0, c, 0);
            }
        }
    }

    private static boolean hasLabel(List<CompletionItem> items, String label) {
        return items.stream().anyMatch(i -> i.label().equals(label));
    }

    // -------------------------------------------------------------------------

    private static void testMemberCompletionAfterDot() throws IOException {
        Path dir = Files.createTempDirectory("ijcomp_member_");
        try {
            ModalEditor editor = javaEditor("class Sample {\n  void run() {\n    StringBuilder sb;\n", dir);
            typeAtEnd(editor, 2, "\n    sb.");
            assertTrue("ドットだけで候補が出る", editor.isCompletionActive());
            assertTrue("レシーバの型のメソッドが出る",
                hasLabel(editor.getCompletionItems(), "append"));
        } finally {
            deleteDir(dir);
        }
    }

    private static void testMemberCandidateShowsSignature() throws IOException {
        Path dir = Files.createTempDirectory("ijcomp_sig_");
        try {
            ModalEditor editor = javaEditor("class Sample {\n  void run() {\n    StringBuilder sb;\n", dir);
            typeAtEnd(editor, 2, "\n    sb.rev");
            CompletionItem reverse = editor.getCompletionItems().stream()
                .filter(i -> i.label().equals("reverse")).findFirst().orElse(null);
            assertTrue("reverse が候補にある", reverse != null);
            if (reverse == null) return;
            assertEquals("種別はメソッド", "mth", reverse.kind());
            assertEquals("引数リストが付く", "()", reverse.tailText());
            assertEquals("戻り値型が付く", "StringBuilder", reverse.typeText());
        } finally {
            deleteDir(dir);
        }
    }

    private static void testMethodInsertsParenthesesAndPlacesCaretInside() throws IOException {
        Path dir = Files.createTempDirectory("ijcomp_paren_");
        try {
            ModalEditor editor = javaEditor("class Sample {\n  void run() {\n    StringBuilder sb;\n", dir);
            typeAtEnd(editor, 2, "\n    sb.appe");
            editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("確定で括弧まで入る", editor.getLine(3).contains("sb.append()"));
            String line = editor.getLine(3);
            assertEquals("カーソルは括弧の内側", line.indexOf("append(") + "append(".length(),
                editor.getCursorCol());
        } finally {
            deleteDir(dir);
        }
    }

    private static void testFieldInsertsPlainName() throws IOException {
        Path dir = Files.createTempDirectory("ijcomp_field_");
        try {
            ModalEditor editor = javaEditor("class Sample {\n  void run(String[] args) {\n", dir);
            typeAtEnd(editor, 1, "\n    args.len");
            assertTrue("配列の length が候補に出る",
                hasLabel(editor.getCompletionItems(), "length"));
            editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("フィールドは括弧なしで入る", editor.getLine(2).endsWith("args.length"));
        } finally {
            deleteDir(dir);
        }
    }

    private static void testKeywordIsCandidate() throws IOException {
        Path dir = Files.createTempDirectory("ijcomp_keyword_");
        try {
            ModalEditor editor = javaEditor("class Sample {\n", dir);
            typeAtEnd(editor, 0, "\n  publ");
            assertTrue("キーワードが候補に出る", hasLabel(editor.getCompletionItems(), "public"));
            CompletionItem keyword = editor.getCompletionItems().stream()
                .filter(i -> i.label().equals("public")).findFirst().orElseThrow();
            assertEquals("種別はキーワード", "kw", keyword.kind());
        } finally {
            deleteDir(dir);
        }
    }

    private static void testCamelCaseMatchesLocalWord() throws IOException {
        Path dir = Files.createTempDirectory("ijcomp_camel_");
        try {
            ModalEditor editor = javaEditor(
                "class Sample {\n  int attackPowerBonus = 1;\n  void run() {\n", dir);
            typeAtEnd(editor, 2, "\n    int x = apb");
            assertTrue("CamelCase 頭文字で同ファイルの識別子に一致する",
                hasLabel(editor.getCompletionItems(), "attackPowerBonus"));
        } finally {
            deleteDir(dir);
        }
    }

    private static void testDotOnUnknownReceiverShowsNothing() throws IOException {
        Path dir = Files.createTempDirectory("ijcomp_unknown_");
        try {
            ModalEditor editor = javaEditor("class Sample {\n  void run() {\n", dir);
            typeAtEnd(editor, 1, "\n    unknownReceiver.");
            assertTrue("型が分からないレシーバでは候補を出さない", !editor.isCompletionActive());
        } finally {
            deleteDir(dir);
        }
    }

    private static void testAccurateMemberResolutionForMethodChain() throws IOException {
        Path dir = Files.createTempDirectory("ijcomp_chain_");
        try {
            ModalEditor editor = javaEditor(
                "class Sample {\n  void run() {\n    StringBuilder sb = new StringBuilder();\n", dir);
            // 同期実行機構を渡すことで、javac の型解決結果をその場で反映させる
            editor.enableMemberCompletionLookup(Runnable::run, Runnable::run);
            typeAtEnd(editor, 2, "\n    sb.reverse().appendC");
            assertTrue("メソッドチェーンの戻り値型から候補が出る",
                hasLabel(editor.getCompletionItems(), "appendCodePoint"));
        } finally {
            deleteDir(dir);
        }
    }

    private static void testClassCandidateInsertsImport() throws IOException {
        Path dir = Files.createTempDirectory("ijcomp_import_");
        try {
            ModalEditor editor = javaEditor(
                "package sample;\n\nclass Sample {\n  void run() {\n", dir);
            ImportSuggester suggester = new ImportSuggester(jdkIndex);
            editor.setCompletionIndex(CompletionIndex.buildSync(jdkIndex));
            editor.setImportSuggester(suggester);
            editor.setAutoImportHandler(new AutoImportHandler(suggester, new SourceAnalyzer()));

            typeAtEnd(editor, 3, "\n    ArrayLis");
            assertTrue("JDK クラス名が候補に出る",
                hasLabel(editor.getCompletionItems(), "ArrayList"));
            // 先頭候補が ArrayList であることを確認してから確定する
            assertEquals("最も一致する候補が先頭", "ArrayList",
                editor.getCompletionItems().get(0).label());
            editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
            assertTrue("クラス名が挿入される", editor.getText().contains("ArrayList"));
            assertTrue("import 文が挿入される",
                editor.getText().contains("import java.util.ArrayList;"));
            assertTrue("カーソルは挿入したクラス名の直後のまま",
                editor.getLine(editor.getCursorRow()).endsWith("ArrayList"));
        } finally {
            deleteDir(dir);
        }
    }

    private static void testNonJavaBufferKeepsWordCompletion() throws IOException {
        Path dir = Files.createTempDirectory("ijcomp_nonjava_");
        try {
            Files.writeString(dir.resolve("main.c"), "int attackPower = 1;\n");
            ModalEditor editor = new ModalEditor("", dir.resolve("main.c").toString(), null);
            editor.setProjectRoot(dir);
            editor.setWordIndex(dev.javatexteditor.analysis.WordIndex.buildSync(dir));
            typeAtEnd(editor, 0, "att");
            assertTrue("C バッファでは従来の単語補完が動く",
                hasLabel(editor.getCompletionItems(), "attackPower"));
            assertTrue("C バッファにキーワード候補（Java）は出ない",
                !hasLabel(editor.getCompletionItems(), "abstract"));
        } finally {
            deleteDir(dir);
        }
    }

    // -------------------------------------------------------------------------

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void assertEquals(String msg, Object expected, Object actual) {
        if (expected.equals(actual)) {
            System.out.println("  PASS: " + msg);
            passed++;
        } else {
            System.out.println("  FAIL: " + msg + " expected=" + expected + " actual=" + actual);
            failed++;
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
