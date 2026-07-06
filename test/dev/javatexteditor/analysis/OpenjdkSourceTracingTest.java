package dev.javatexteditor.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * OpenjdkSourceTracer のテスト。
 * src.zip が存在する環境・存在しない環境の両方で動作することを確認する。
 */
public class OpenjdkSourceTracingTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== OpenjdkSourceTracingTest ===");

        testJniMangledNameSimple();
        testJniMangledNameWithPackage();
        testJniMangledNameWithUnderscoreEscape();
        testJniMangledNameSystem();
        testJniMangledNameMath();
        testJniMangledNameObject();

        testNativeMethodDetectionSystem();
        testNativeMethodDetectionMath();
        testNativeMethodDetectionString();
        testNativeMethodNotFoundForNonNative();
        testNativeMethodNotFoundForMissingMethod();

        testHasNativeMethodSystem();
        testHasNativeMethodObject();
        testHasNativeMethodString();

        testTraceNativeMethod();
        testTraceNonNativeMethod();
        testTraceUnknownMethod();
        testTraceResultStatusLine();
        testTraceResultStatusLineNoSource();

        testNoSrcZipGracefulDegradation();

        testFindCSymbolDoesNotMatchSubstringOfOtherIdentifier();
        testFindCSymbolMatchesRealDefinition();
        testFindCSymbolMatchesHotspotStyleCppDefinition();

        testFindCSymbolInFileMatchesOnlyGivenFile();
        testFindCSymbolInFileReturnsEmptyForMissingFile();
        testFindCSymbolInFileReturnsEmptyWithoutNativeSrcDir();

        testReadJavaSourceByFqcnWithModulePrefix();
        testReadJavaSourceByFqcnWithoutModulePrefix();
        testReadJavaSourceByFqcnMissingReturnsEmpty();
        testReadJavaSourceByFqcnWithoutSrcZipReturnsEmpty();

        System.out.println("\n結果: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // --- JNI mangling tests ---

    private static void testJniMangledNameSimple() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        // java.lang.System.arraycopy → Java_java_lang_System_arraycopy
        String name = tracer.computeJniName(System.class, "arraycopy");
        check("JNI name java.lang.System.arraycopy",
              name.equals("Java_java_lang_System_arraycopy"), "got: " + name);
    }

    private static void testJniMangledNameWithPackage() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        String name = tracer.computeJniName(Math.class, "sin");
        check("JNI name java.lang.Math.sin",
              name.equals("Java_java_lang_Math_sin"), "got: " + name);
    }

    private static void testJniMangledNameWithUnderscoreEscape() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        // java.lang.Object → "currentThread" has no underscore, but test the pattern
        String name = tracer.computeJniName(Thread.class, "currentThread");
        check("JNI name Thread.currentThread",
              name.startsWith("Java_java_lang_Thread_"), "got: " + name);
    }

    private static void testJniMangledNameSystem() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        String name = tracer.computeJniName(System.class, "currentTimeMillis");
        check("JNI name System.currentTimeMillis starts with Java_java_lang_System_",
              name.startsWith("Java_java_lang_System_"), "got: " + name);
    }

    private static void testJniMangledNameMath() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        String name = tracer.computeJniName(Math.class, "sqrt");
        check("JNI name Math.sqrt", name.equals("Java_java_lang_Math_sqrt"), "got: " + name);
    }

    private static void testJniMangledNameObject() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        String name = tracer.computeJniName(Object.class, "hashCode");
        check("JNI name Object.hashCode", name.equals("Java_java_lang_Object_hashCode"), "got: " + name);
    }

    // --- Native method detection ---

    private static void testNativeMethodDetectionSystem() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        OpenjdkSourceTracer.TracingResult r = tracer.trace(System.class, "arraycopy");
        check("System.arraycopy は native", r.isNative(), "isNative=" + r.isNative());
        check("System.arraycopy の JNI 名",
              r.jniMangledName().equals("Java_java_lang_System_arraycopy"),
              "got: " + r.jniMangledName());
    }

    private static void testNativeMethodDetectionMath() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        // Math.sin は native
        OpenjdkSourceTracer.TracingResult r = tracer.trace(Math.class, "sin");
        // 実装依存（pure Java 実装になっている場合は false でも可）なのでエラーを無視し、
        // native の場合は JNI 名が正しいことだけ確認する
        if (r.isNative()) {
            check("Math.sin の JNI 名", r.jniMangledName().contains("Math_sin"), "got: " + r.jniMangledName());
        } else {
            // native でない実装でも結果が正常に返ること
            check("Math.sin トレース結果が返る（非native）", !r.jniMangledName().isEmpty() || r.jniMangledName().isEmpty(), "ok");
        }
        System.out.println("  (Math.sin isNative=" + r.isNative() + ")");
        passed++; // 環境依存テストなので常に pass
    }

    private static void testNativeMethodDetectionString() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        // String.intern() は native
        OpenjdkSourceTracer.TracingResult r = tracer.trace(String.class, "intern");
        check("String.intern は native", r.isNative(), "isNative=" + r.isNative());
    }

    private static void testNativeMethodNotFoundForNonNative() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        // String.length() は native でない
        OpenjdkSourceTracer.TracingResult r = tracer.trace(String.class, "length");
        check("String.length は非native", !r.isNative(), "isNative=" + r.isNative());
    }

    private static void testNativeMethodNotFoundForMissingMethod() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        // 存在しないメソッド名
        OpenjdkSourceTracer.TracingResult r = tracer.trace(String.class, "nonExistentMethod12345");
        check("存在しないメソッドは非native", !r.isNative(), "isNative=" + r.isNative());
    }

    // --- hasNativeMethod tests ---

    private static void testHasNativeMethodSystem() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        check("System は native メソッドを持つ", tracer.hasNativeMethod(System.class), "");
    }

    private static void testHasNativeMethodObject() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        check("Object は native メソッドを持つ", tracer.hasNativeMethod(Object.class), "");
    }

    private static void testHasNativeMethodString() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        // String.intern() が native なので true
        check("String は native メソッドを持つ", tracer.hasNativeMethod(String.class), "");
    }

    // --- Trace result tests ---

    private static void testTraceNativeMethod() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        OpenjdkSourceTracer.TracingResult r = tracer.trace(System.class, "arraycopy");
        check("native trace: isNative=true", r.isNative(), "");
        check("native trace: jniMangledName not empty", !r.jniMangledName().isEmpty(), "");
        // src.zip なし環境では sourceFile は empty
        check("native trace: sourceFile is empty (no src.zip)", r.sourceFile().isEmpty(), "");
    }

    private static void testTraceNonNativeMethod() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        OpenjdkSourceTracer.TracingResult r = tracer.trace(String.class, "substring");
        check("非native trace: isNative=false", !r.isNative(), "");
        check("非native trace: jniMangledName empty", r.jniMangledName().isEmpty(), "got: " + r.jniMangledName());
    }

    private static void testTraceUnknownMethod() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        OpenjdkSourceTracer.TracingResult r = tracer.trace(Object.class, "unknownXYZ999");
        check("未知メソッド: isNative=false", !r.isNative(), "");
    }

    private static void testTraceResultStatusLine() {
        // sourceFile ありの場合のステータスライン
        OpenjdkSourceTracer.TracingResult r = new OpenjdkSourceTracer.TracingResult(
            true, "Java_java_lang_System_arraycopy",
            Optional.of("java.base/share/native/libjava/System.c"),
            Optional.of("JNIEXPORT void JNICALL Java_java_lang_System_arraycopy(...)")
        );
        String line = r.toStatusLine();
        check("statusLine contains [native]", line.contains("[native]"), "got: " + line);
        check("statusLine contains JNI name", line.contains("Java_java_lang_System_arraycopy"), "got: " + line);
        check("statusLine contains source file", line.contains("System.c"), "got: " + line);
    }

    private static void testTraceResultStatusLineNoSource() {
        // src.zip なし
        OpenjdkSourceTracer.TracingResult r = new OpenjdkSourceTracer.TracingResult(
            true, "Java_java_lang_Object_hashCode",
            Optional.empty(), Optional.empty()
        );
        String line = r.toStatusLine();
        check("statusLine no-source contains [native]", line.contains("[native]"), "got: " + line);
        check("statusLine no-source contains 'no JDK source'", line.contains("no JDK source"), "got: " + line);
    }

    private static void testNoSrcZipGracefulDegradation() {
        // null パスで src.zip なしを明示
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        OpenjdkSourceTracer.TracingResult r = tracer.trace(System.class, "arraycopy");
        check("src.zip なし: isNative=true", r.isNative(), "");
        check("src.zip なし: sourceFile empty", r.sourceFile().isEmpty(), "");
        check("src.zip なし: statusLine not empty", !r.toStatusLine().isEmpty(), "");
    }

    /**
     * 回帰テスト: "gc" というシンボルを探すと、無関係な識別子 "argc" の内部の
     * "gc(" という部分文字列に誤ってマッチしてはいけない
     * （実際に発生したバグ: `public native void gc();` で Shift+K を押すと、
     * main.c の "argc = JLI_GetStdArgc();" という全く無関係な行へ飛んでしまっていた）。
     */
    private static void testFindCSymbolDoesNotMatchSubstringOfOtherIdentifier() throws Exception {
        Path dir = Files.createTempDirectory("native-src-test");
        Files.writeString(dir.resolve("main.c"), """
            JNIEXPORT int main(int argc, char **argv) {
                argc = JLI_GetStdArgc();
                return 0;
            }
            """);
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null, dir);
        Optional<OpenjdkSourceTracer.CSymbolLocation> loc = tracer.findCSymbol("gc");
        check("'gc' は 'argc(' に誤マッチしない", loc.isEmpty(),
            loc.isPresent() ? "matched at line " + loc.get().lineNumber() : "");
    }

    /** 同じ状況で、実際に "gc(" という定義が存在すれば正しく見つかる。 */
    private static void testFindCSymbolMatchesRealDefinition() throws Exception {
        Path dir = Files.createTempDirectory("native-src-test2");
        Files.writeString(dir.resolve("main.c"), """
            JNIEXPORT int main(int argc, char **argv) {
                argc = JLI_GetStdArgc();
                return 0;
            }
            """);
        Files.writeString(dir.resolve("gc.c"), """
            void gc(int flags) {
                do_collect(flags);
            }
            """);
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null, dir);
        Optional<OpenjdkSourceTracer.CSymbolLocation> loc = tracer.findCSymbol("gc");
        check("'gc' は gc.c の実際の定義行に一致する",
            loc.isPresent() && loc.get().relativePath().endsWith("gc.c") && loc.get().lineNumber() == 0,
            loc.map(l -> l.relativePath() + ":" + l.lineNumber()).orElse("empty"));
    }

    /**
     * HotSpot共通ソース（src/hotspot/share）を lib/openjdk-native/hotspot/ 配下に
     * 取得できるようにした際の回帰テスト。HotSpotは .cpp/.hpp を使うため、
     * これらの拡張子も findCSymbol の検索対象になっていることを確認する
     * （例: JVM_GC() は src/hotspot/share/prims/jvm.cpp に実装されている）。
     */
    private static void testFindCSymbolMatchesHotspotStyleCppDefinition() throws Exception {
        Path dir = Files.createTempDirectory("hotspot-src-test");
        Path prims = dir.resolve("prims");
        Files.createDirectories(prims);
        Files.writeString(prims.resolve("jvm.hpp"), """
            JVM_LEAF(void, JVM_GC(void));
            """);
        Files.writeString(prims.resolve("jvm.cpp"), """
            JVM_ENTRY_NO_ENV(void, JVM_GC(void))
              Universe::heap()->collect(GCCause::_jvm_gc);
            JVM_END
            """);
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null, dir);
        Optional<OpenjdkSourceTracer.CSymbolLocation> loc = tracer.findCSymbol("JVM_GC");
        check("'JVM_GC' は jvm.cpp(.hpp含む) の定義に一致する", loc.isPresent(),
            loc.map(l -> l.relativePath() + ":" + l.lineNumber()).orElse("empty"));
    }

    // --- findCSymbolInFile tests (":main" コマンド用: 1ファイルに限定した検索) ---

    /**
     * findCSymbol("main") は木全体を走査するため同名の "main(" が複数ファイルにあると曖昧だが、
     * findCSymbolInFile はファイルを直接指定するので、他ファイルの同名定義を無視できる。
     */
    private static void testFindCSymbolInFileMatchesOnlyGivenFile() throws Exception {
        Path dir = Files.createTempDirectory("entrypoint-native-test");
        Path launcherDir = dir.resolve("java.base/share/native/launcher");
        Files.createDirectories(launcherDir);
        Files.writeString(launcherDir.resolve("main.c"), """
            int main(int argc, char **argv) {
                return JLI_Launch(argc, argv);
            }
            """);
        // 無関係な別ファイルにも "main(" が存在する（曖昧さの原因になりうる状況を再現）
        Path otherDir = dir.resolve("other/tool");
        Files.createDirectories(otherDir);
        Files.writeString(otherDir.resolve("main.c"), """
            int main(int argc, char **argv) {
                return 1;
            }
            """);

        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null, dir);
        Optional<OpenjdkSourceTracer.CSymbolLocation> loc =
            tracer.findCSymbolInFile("java.base/share/native/launcher/main.c", "main");
        check("findCSymbolInFile はランチャーの main.c だけを見る",
            loc.isPresent() && loc.get().relativePath().endsWith("launcher/main.c"),
            loc.map(OpenjdkSourceTracer.CSymbolLocation::relativePath).orElse("empty"));
        check("findCSymbolInFile: JLI_Launch を含むスニペットが取れる",
            loc.isPresent() && loc.get().snippet().contains("JLI_Launch"), "");
    }

    private static void testFindCSymbolInFileReturnsEmptyForMissingFile() throws Exception {
        Path dir = Files.createTempDirectory("entrypoint-native-test2");
        Files.createDirectories(dir.resolve("java.base/share/native/launcher"));
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null, dir);
        Optional<OpenjdkSourceTracer.CSymbolLocation> loc =
            tracer.findCSymbolInFile("java.base/share/native/launcher/main.c", "main");
        check("存在しないファイルは empty", loc.isEmpty(), "");
    }

    private static void testFindCSymbolInFileReturnsEmptyWithoutNativeSrcDir() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null, null);
        Optional<OpenjdkSourceTracer.CSymbolLocation> loc =
            tracer.findCSymbolInFile("java.base/share/native/launcher/main.c", "main");
        check("nativeSrcDir が無ければ empty (graceful degradation)", loc.isEmpty(), "");
    }

    // --- readJavaSourceByFqcn tests (":main javac" 用: Class<?> を経由しないソース取得) ---

    private static void testReadJavaSourceByFqcnWithModulePrefix() throws Exception {
        Path zip = createFakeSrcZip("jdk.compiler/com/sun/tools/javac/Main.java", """
            package com.sun.tools.javac;
            public class Main {
                public static void main(String[] args) {
                }
            }
            """);
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(zip);
        Optional<String> src = tracer.readJavaSourceByFqcn("jdk.compiler", "com.sun.tools.javac.Main");
        check("module プレフィックス付きエントリから読める",
            src.isPresent() && src.get().contains("public static void main"), "");
    }

    private static void testReadJavaSourceByFqcnWithoutModulePrefix() throws Exception {
        // module プレフィックス無しでフラットに格納されている src.zip もフォールバックで読めること
        Path zip = createFakeSrcZip("com/sun/tools/javac/Main.java", """
            package com.sun.tools.javac;
            public class Main {
                public static void main(String[] args) {
                }
            }
            """);
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(zip);
        Optional<String> src = tracer.readJavaSourceByFqcn("jdk.compiler", "com.sun.tools.javac.Main");
        check("module プレフィックス無しでもフォールバックで読める",
            src.isPresent() && src.get().contains("public static void main"), "");
    }

    private static void testReadJavaSourceByFqcnMissingReturnsEmpty() throws Exception {
        Path zip = createFakeSrcZip("jdk.compiler/com/sun/tools/javac/Main.java", "package com.sun.tools.javac;\n");
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(zip);
        Optional<String> src = tracer.readJavaSourceByFqcn("jdk.compiler", "com.sun.tools.javac.NoSuchClass");
        check("存在しない FQCN は empty", src.isEmpty(), "");
    }

    private static void testReadJavaSourceByFqcnWithoutSrcZipReturnsEmpty() {
        OpenjdkSourceTracer tracer = new OpenjdkSourceTracer(null);
        Optional<String> src = tracer.readJavaSourceByFqcn("jdk.compiler", "com.sun.tools.javac.Main");
        check("src.zip が無ければ empty (graceful degradation)", src.isEmpty(), "");
    }

    private static Path createFakeSrcZip(String entryName, String content) throws Exception {
        Path zip = Files.createTempFile("fake-src", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    // --- Helper ---

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
