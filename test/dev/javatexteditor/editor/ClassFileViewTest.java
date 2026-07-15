package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * .classファイルを:eで開くと（hexdumpではなく）JVM仕様通りの構造ビューが表示され、
 * :nimoコマンドでニーモニック（javap -c風）ビューに切り替えられることを検証する。
 * mainメソッド形式のテストハーネス（JUnit不使用）。
 */
public class ClassFileViewTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testOpenClassFileShowsStructureView();
        testNimoShowsMnemonicView();
        testNimoWithoutClassFileShowsError();
        testNimoInvalidatedAfterSwitchingToAnotherFile();
        testMalformedClassFileFallsBackToHexdump();

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

    /** ProjectBuilder等と同様、実際にjavax.tools.JavaCompilerでコンパイルして本物の.classを用意する。 */
    private static Path compileSampleClass(String simpleName) throws IOException {
        Path tmpDir = Files.createTempDirectory("classfile-view-test");
        Path srcFile = tmpDir.resolve(simpleName + ".java");
        Files.writeString(srcFile, """
                public class %s {
                    public %s() {
                    }

                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """.formatted(simpleName, simpleName));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, Locale.ENGLISH, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tmpDir.toFile()));
            var units = fm.getJavaFileObjectsFromPaths(List.of(srcFile));
            boolean ok = Boolean.TRUE.equals(
                    compiler.getTask(null, fm, null, List.of("-proc:none"), null, units).call());
            if (!ok) throw new IOException("test fixture failed to compile: " + simpleName);
        }
        return tmpDir.resolve(simpleName + ".class");
    }

    static void testOpenClassFileShowsStructureView() throws IOException {
        Path classFile = compileSampleClass("ViewA");
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, classFile.toString());

        assertTrue("構造ビューのヘッダ(*class*)で始まる", ed.getText().startsWith("*class*"));
        assertTrue("マジックナンバー行を含む", ed.getText().contains("magic: 0xCAFEBABE"));
        assertTrue("methodsセクションを含む", ed.getText().contains("methods:"));
        assertEquals(".classは読み取り専用のためcurrentFilePathはnull", null, ed.getCurrentFilePath());
    }

    static void testNimoShowsMnemonicView() throws IOException {
        Path classFile = compileSampleClass("ViewB");
        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, classFile.toString());

        runCommand(ed, "nimo");

        assertTrue("ニーモニックビューのヘッダ(*nimo*)で始まる", ed.getText().startsWith("*nimo*"));
        assertTrue("aload_0ニーモニックを含む", ed.getText().contains("aload_0"));
        assertTrue("iaddニーモニックを含む", ed.getText().contains("iadd"));
    }

    static void testNimoWithoutClassFileShowsError() throws IOException {
        Path txt = Files.createTempFile("classfile-view-notclass", ".txt");
        Files.writeString(txt, "hello world\n");

        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, txt.toString());
        String before = ed.getText();

        runCommand(ed, "nimo");

        assertTrue("エラーメッセージが表示される", ed.getStatusMessage().startsWith("E:"));
        assertEquals(".classではないバッファの内容は変化しない", before, ed.getText());
    }

    static void testNimoInvalidatedAfterSwitchingToAnotherFile() throws IOException {
        Path classFile = compileSampleClass("ViewC");
        Path txt = Files.createTempFile("classfile-view-other", ".txt");
        Files.writeString(txt, "plain text\n");

        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, classFile.toString());
        openViaCommand(ed, txt.toString()); // 別バッファへ切り替え（bufferの参照が変わる）

        String before = ed.getText();
        runCommand(ed, "nimo");

        assertTrue("別バッファへ切り替え後は:nimoが無効化されエラーになる", ed.getStatusMessage().startsWith("E:"));
        assertEquals("別バッファの内容は変化しない", before, ed.getText());
    }

    static void testMalformedClassFileFallsBackToHexdump() throws IOException {
        Path broken = Files.createTempFile("classfile-view-broken", ".class");
        // マジックナンバーは一致するが、その後が正規のクラスファイル構造として不正（途中で切れている）
        Files.write(broken, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0});

        ModalEditor ed = new ModalEditor("");
        openViaCommand(ed, broken.toString());

        assertTrue("壊れた.classはhexdumpプレビューにフォールバックする", ed.getText().startsWith("*binary*"));
        assertEquals("フォールバック時もcurrentFilePathはnull", null, ed.getCurrentFilePath());
    }
}
