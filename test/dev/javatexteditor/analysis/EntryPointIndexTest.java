package dev.javatexteditor.analysis;

import java.util.Optional;

/**
 * EntryPointIndex（":main <target>" コマンドのターゲット→ジャンプ先マッピング）のテスト。
 */
public class EntryPointIndexTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testLookupJava();
        testLookupJavac();
        testLookupIsCaseInsensitive();
        testLookupTrimsWhitespace();
        testLookupUnknownTargetIsEmpty();
        testLookupNullIsEmpty();
        testSupportedTargetsContainsJavaAndJavac();

        System.out.println("\n結果: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void testLookupJava() {
        Optional<EntryPointIndex.Target> t = EntryPointIndex.lookup("java");
        check("java is present", t.isPresent(), "");
        check("java resolves to NativeLauncher",
            t.get() instanceof EntryPointIndex.Target.NativeLauncher, "got: " + t.get());
        EntryPointIndex.Target.NativeLauncher nl = (EntryPointIndex.Target.NativeLauncher) t.get();
        check("java launcher relativePath points at launcher/main.c",
            nl.relativePath().endsWith("launcher/main.c"), "got: " + nl.relativePath());
        check("java launcher symbol is main", nl.symbol().equals("main"), "got: " + nl.symbol());
    }

    private static void testLookupJavac() {
        Optional<EntryPointIndex.Target> t = EntryPointIndex.lookup("javac");
        check("javac is present", t.isPresent(), "");
        check("javac resolves to JavaSource",
            t.get() instanceof EntryPointIndex.Target.JavaSource, "got: " + t.get());
        EntryPointIndex.Target.JavaSource js = (EntryPointIndex.Target.JavaSource) t.get();
        check("javac fqcn is com.sun.tools.javac.Main",
            js.fqcn().equals("com.sun.tools.javac.Main"), "got: " + js.fqcn());
        check("javac member is main", js.memberName().equals("main"), "got: " + js.memberName());
    }

    private static void testLookupIsCaseInsensitive() {
        check("JAVA resolves same as java",
            EntryPointIndex.lookup("JAVA").isPresent(), "");
        check("Javac resolves same as javac",
            EntryPointIndex.lookup("Javac").isPresent(), "");
    }

    private static void testLookupTrimsWhitespace() {
        check("'  java  ' resolves", EntryPointIndex.lookup("  java  ").isPresent(), "");
    }

    private static void testLookupUnknownTargetIsEmpty() {
        check("unknown target is empty", EntryPointIndex.lookup("foo").isEmpty(), "");
    }

    private static void testLookupNullIsEmpty() {
        check("null target is empty", EntryPointIndex.lookup(null).isEmpty(), "");
    }

    private static void testSupportedTargetsContainsJavaAndJavac() {
        check("supportedTargets contains java", EntryPointIndex.supportedTargets().contains("java"), "");
        check("supportedTargets contains javac", EntryPointIndex.supportedTargets().contains("javac"), "");
    }

    private static void check(String name, boolean cond, String detail) {
        if (cond) {
            System.out.println("  PASS: " + name);
            passed++;
        } else {
            System.out.println("  FAIL: " + name + (detail.isEmpty() ? "" : " (" + detail + ")"));
            failed++;
        }
    }
}
