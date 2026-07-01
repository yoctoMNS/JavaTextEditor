package dev.javatexteditor.editor;

import dev.javatexteditor.analysis.OpenjdkSourceTracer;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * gr (go to references) の baseDir 一般化を検証する（mainメソッド形式のテストハーネス）。
 *
 * 本来の狙いは「jdk-source 疑似バッファ内で gr を押すと lib/openjdk-native/ を検索対象にする」
 * ことだが、CI/開発コンテナには lib/openjdk-native/ が存在しない（scripts/setup.sh で
 * 別途取得する外部リソースのため）。そのため OpenjdkSourceTracer.hasNativeSrcDir() は
 * このテスト環境では常に false になり、goToReferences() の native 分岐そのものは
 * ここでは再現できない（OpenjdkSourceTracingTest と同じ既知のテストギャップ）。
 * 代わりに、native 分岐追加のためにリファクタリングした executeGrep(pattern, baseDir) /
 * jumpToGrepResult() の baseDir 一般化が、従来のプロジェクト全体grep（デフォルト経路）を
 * 壊していないことを回帰確認する。
 */
public class NativeReferenceSearchTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testProjectWideReferencesStillWork();
        testNativeSrcDirGetterMatchesHasNativeSrcDir();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
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

    static void testProjectWideReferencesStillWork() throws Exception {
        Path dir = Files.createTempDirectory("gr-test");
        Path callerFile = dir.resolve("Caller.java");
        Path targetFile = dir.resolve("Target.java");
        Files.writeString(callerFile, """
            public class Caller {
                void use() {
                    Target.helper();
                }
            }
            """);
        Files.writeString(targetFile, """
            public class Target {
                static void helper() {
                }
            }
            """);

        ModalEditor ed = new ModalEditor(Files.readString(callerFile), callerFile.toString(), null);
        ed.setProjectRoot(dir);
        ed.setCursor(0, "public class ".length()); // "Caller" の上

        // g -> r (goto.pending -> jump.back とは別の "gr" シーケンス)
        ed.processKey(KeyEvent.VK_G, 'g', 0);
        ed.processKey(KeyEvent.VK_R, 'r', 0);

        assertTrue("gr: *grep* 疑似バッファに切り替わる (filePath==null)",
            ed.getCurrentFilePath() == null);
        assertTrue("gr: ステータスに一致件数が表示される",
            ed.getStatusMessage().contains("match"));

        // 結果1件目へジャンプ（1行目はヘッダなので2行目=結果1件目へ）
        ed.setCursor(1, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);

        assertTrue("gr: Enterでプロジェクト内のファイルを開ける",
            ed.getCurrentFilePath() != null && ed.getCurrentFilePath().startsWith(dir.toString()));
    }

    static void testNativeSrcDirGetterMatchesHasNativeSrcDir() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        assertEquals("getNativeSrcDir().isPresent() == hasNativeSrcDir()",
            tracer.hasNativeSrcDir(), tracer.getNativeSrcDir().isPresent());
    }
}
