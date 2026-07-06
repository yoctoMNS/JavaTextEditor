package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;

/**
 * ":main <target>" コマンド（java/javac コマンドの実際の起動点へジャンプ）の統合テスト。
 *
 * CI/開発コンテナには lib/openjdk-native/・lib/src.zip が存在しない
 * （scripts/setup.sh で別途取得する外部リソースのため、OpenjdkSourceTracingTest や
 * NativeReferenceSearchTest と同じ既知の制約）。そのため ":main java" / ":main javac" の
 * 「実際にジャンプできる」経路はここでは検証できず、"not available" への graceful degradation
 * を確認する。ターゲット解決・引数パース部分（EntryPointIndex 連携）は
 * EntryPointIndexTest / OpenjdkSourceTracingTest 側で src.zip 等を偽装して検証済み。
 */
public class MainCommandTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testMainWithoutArgShowsUsageError();
        testMainWithUnknownTargetShowsError();
        testMainUnknownTargetErrorListsSupportedTargets();
        testMainJavaDegradesGracefullyWithoutNativeSrc();
        testMainJavacDegradesGracefullyWithoutSrcZip();
        testMainTargetIsCaseInsensitive();
        testMainDoesNotCrashBuffer();

        System.out.println("\n=== MainCommand: " + passed + "/" + (passed + failed) + " PASS ===");
        if (failed > 0) System.exit(1);
    }

    static void testMainWithoutArgShowsUsageError() {
        ModalEditor editor = new ModalEditor("hello");
        sendCommand(editor, "main");
        assertTrue("no-arg :main reports missing target",
            editor.getStatusMessage().contains("requires a target"));
        passed("testMainWithoutArgShowsUsageError");
    }

    static void testMainWithUnknownTargetShowsError() {
        ModalEditor editor = new ModalEditor("hello");
        sendCommand(editor, "main foo");
        assertTrue("unknown target reports error",
            editor.getStatusMessage().contains("unknown :main target 'foo'"));
        passed("testMainWithUnknownTargetShowsError");
    }

    static void testMainUnknownTargetErrorListsSupportedTargets() {
        ModalEditor editor = new ModalEditor("hello");
        sendCommand(editor, "main foo");
        assertTrue("error message lists 'java'", editor.getStatusMessage().contains("java"));
        assertTrue("error message lists 'javac'", editor.getStatusMessage().contains("javac"));
        passed("testMainUnknownTargetErrorListsSupportedTargets");
    }

    static void testMainJavaDegradesGracefullyWithoutNativeSrc() {
        ModalEditor editor = new ModalEditor("hello");
        sendCommand(editor, "main java");
        // lib/openjdk-native/ が無い環境では jump できないが、エラーとして自然に扱われる
        // (unknown command にはならない = "main" コマンド自体は認識されている)
        assertTrue("':main java' is recognized, not 'unknown command'",
            !editor.getStatusMessage().contains("unknown command"));
        passed("testMainJavaDegradesGracefullyWithoutNativeSrc");
    }

    static void testMainJavacDegradesGracefullyWithoutSrcZip() {
        ModalEditor editor = new ModalEditor("hello");
        sendCommand(editor, "main javac");
        assertTrue("':main javac' is recognized, not 'unknown command'",
            !editor.getStatusMessage().contains("unknown command"));
        passed("testMainJavacDegradesGracefullyWithoutSrcZip");
    }

    static void testMainTargetIsCaseInsensitive() {
        ModalEditor editor = new ModalEditor("hello");
        sendCommand(editor, "main JAVA");
        assertTrue("':main JAVA' is recognized like ':main java'",
            !editor.getStatusMessage().contains("unknown :main target"));
        passed("testMainTargetIsCaseInsensitive");
    }

    static void testMainDoesNotCrashBuffer() {
        ModalEditor editor = new ModalEditor("original buffer text");
        sendCommand(editor, "main java");
        // src.zip/nativeSrcDir が無い環境ではバッファは差し替わらず、元の内容が維持される
        assertTrue("buffer text unaffected when entry point unavailable",
            editor.getText().equals("original buffer text"));
        passed("testMainDoesNotCrashBuffer");
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private static void sendCommand(ModalEditor editor, String cmd) {
        editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        editor.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : cmd.toCharArray()) {
            editor.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        }
        editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
    }

    private static void assertTrue(String name, boolean condition) {
        if (!condition) fail(name);
    }

    private static void passed(String name) {
        passed++;
        System.out.println("[OK] " + name);
    }

    private static void fail(String name) {
        failed++;
        System.out.println("[FAIL] " + name);
    }
}
