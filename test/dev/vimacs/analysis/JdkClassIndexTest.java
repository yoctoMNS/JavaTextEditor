package dev.vimacs.analysis;

import java.util.List;
import java.util.Optional;

/**
 * JdkClassIndex / JdkTypeInfo のテスト。
 * buildSync() を使って同期的にインデックスを構築し検証する。
 */
public class JdkClassIndexTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== JdkClassIndexTest ===");

        long start = System.currentTimeMillis();
        JdkClassIndex index = JdkClassIndex.buildSync();
        long elapsed = System.currentTimeMillis() - start;

        // インデックス構築の基本確認
        testBuildTime(elapsed);
        testIndexNotEmpty(index);
        testLookupList(index);
        testLookupString(index);
        testLookupNotFound(index);
        testLookupReturnsUnmodifiable(index);

        // loadClass テスト
        testLoadClassString(index);
        testLoadClassListInterface(index);
        testLoadClassNotFound(index);

        // JdkTypeInfo テスト
        testTypeInfoFromString();
        testTypeInfoKindInterface();
        testTypeInfoKindEnum();
        testTypeInfoMethodsNotEmpty();
        testTypeInfoFieldsNotEmpty();
        testTypeInfoStatusLine();

        System.out.println("\n結果: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // -------------------------------------------------------------------------

    private static void testBuildTime(long elapsedMs) {
        // インデックス構築が 5 秒以内に完了することを確認
        check("インデックス構築が5秒以内", elapsedMs < 5000,
              "elapsed=" + elapsedMs + "ms");
    }

    private static void testIndexNotEmpty(JdkClassIndex index) {
        check("インデックスが 1000 件以上", index.totalClassCount() >= 1000,
              "count=" + index.totalClassCount());
    }

    private static void testLookupList(JdkClassIndex index) {
        List<String> candidates = index.lookup("List");
        boolean found = candidates.stream().anyMatch(f -> f.equals("java.util.List"));
        check("lookup(\"List\") に java.util.List が含まれる", found,
              "candidates=" + candidates);
    }

    private static void testLookupString(JdkClassIndex index) {
        List<String> candidates = index.lookup("String");
        boolean found = candidates.stream().anyMatch(f -> f.equals("java.lang.String"));
        check("lookup(\"String\") に java.lang.String が含まれる", found,
              "candidates=" + candidates);
    }

    private static void testLookupNotFound(JdkClassIndex index) {
        List<String> candidates = index.lookup("XyzDoesNotExist12345");
        check("存在しない名前は空リストを返す", candidates.isEmpty(),
              "candidates=" + candidates);
    }

    private static void testLookupReturnsUnmodifiable(JdkClassIndex index) {
        List<String> candidates = index.lookup("String");
        boolean threw = false;
        try {
            candidates.add("test");
        } catch (UnsupportedOperationException e) {
            threw = true;
        }
        check("lookup() の結果リストは変更不可", threw, "no exception thrown");
    }

    private static void testLoadClassString(JdkClassIndex index) {
        Optional<Class<?>> cls = index.loadClass("java.lang.String");
        check("loadClass(\"java.lang.String\") が存在する", cls.isPresent(), "empty");
        if (cls.isPresent()) {
            check("ロードされたクラスが String である", cls.get() == String.class,
                  cls.get().getName());
        }
    }

    private static void testLoadClassListInterface(JdkClassIndex index) {
        Optional<Class<?>> cls = index.loadClass("java.util.List");
        check("loadClass(\"java.util.List\") が存在する", cls.isPresent(), "empty");
        if (cls.isPresent()) {
            check("java.util.List はインタフェース", cls.get().isInterface(), "not interface");
        }
    }

    private static void testLoadClassNotFound(JdkClassIndex index) {
        Optional<Class<?>> cls = index.loadClass("com.example.DoesNotExist");
        check("存在しない FQN で空 Optional を返す", cls.isEmpty(), "not empty");
    }

    private static void testTypeInfoFromString() {
        JdkTypeInfo info = JdkTypeInfo.from(String.class);
        check("JdkTypeInfo.from(String.class) の fqn", "java.lang.String".equals(info.fqn()),
              info.fqn());
    }

    private static void testTypeInfoKindInterface() {
        JdkTypeInfo info = JdkTypeInfo.from(java.util.List.class);
        check("java.util.List の kind は interface", "interface".equals(info.kind()), info.kind());
    }

    private static void testTypeInfoKindEnum() {
        JdkTypeInfo info = JdkTypeInfo.from(java.nio.file.StandardOpenOption.class);
        check("StandardOpenOption の kind は enum", "enum".equals(info.kind()), info.kind());
    }

    private static void testTypeInfoMethodsNotEmpty() {
        JdkTypeInfo info = JdkTypeInfo.from(String.class);
        check("String のメソッドリストが非空", !info.methodSignatures().isEmpty(),
              "empty methods");
    }

    private static void testTypeInfoFieldsNotEmpty() {
        // Integer には MAX_VALUE 等の public フィールドがある
        JdkTypeInfo info = JdkTypeInfo.from(Integer.class);
        check("Integer のフィールドリストが非空", !info.fieldNames().isEmpty(),
              "empty fields: " + info.fieldNames());
    }

    private static void testTypeInfoStatusLine() {
        JdkTypeInfo info = JdkTypeInfo.from(java.util.ArrayList.class);
        String line = info.toStatusLine();
        check("toStatusLine() に ArrayList が含まれる", line.contains("ArrayList"), line);
        check("toStatusLine() に java.util が含まれる", line.contains("java.util"), line);
    }

    // -------------------------------------------------------------------------

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            System.out.println("  PASS: " + name);
            passed++;
        } else {
            System.out.println("  FAIL: " + name + " [" + detail + "]");
            failed++;
        }
    }
}
