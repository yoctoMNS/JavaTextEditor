package dev.javatexteditor;

import dev.javatexteditor.telescope.BufferPicker.BufferEntry;
import java.util.List;

/**
 * BufferRegistry（開いたファイルの一覧）の単体テスト。
 * Swing にも実ファイルにも依存しないため、そのまま検証できる。
 */
public class BufferRegistryTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testStartsEmpty();
        testRegisterKeepsInsertionOrder();
        testDuplicatePathIsIgnored();
        testEntryWithoutPathIsNotRegistered();
        testUnregisterRemovesByPath();
        testUnregisterUnknownPathIsNoOp();
        testEntriesReturnsDefensiveCopy();

        System.out.println();
        System.out.println("PASS: " + passed + " / " + (passed + failed) + "  (FAIL: " + failed + ")");
        if (failed > 0) System.exit(1);
    }

    private static BufferEntry entry(String name, String path) {
        return new BufferEntry(name, path);
    }

    private static void testStartsEmpty() {
        check("最初は空", new BufferRegistry().entries().isEmpty());
    }

    private static void testRegisterKeepsInsertionOrder() {
        BufferRegistry r = new BufferRegistry();
        r.register(entry("A.java", "/p/A.java"));
        r.register(entry("B.java", "/p/B.java"));
        List<BufferEntry> es = r.entries();
        check("登録順（＝開いた順）に並ぶ",
                es.size() == 2
             && es.get(0).filePath().equals("/p/A.java")
             && es.get(1).filePath().equals("/p/B.java"));
    }

    private static void testDuplicatePathIsIgnored() {
        BufferRegistry r = new BufferRegistry();
        r.register(entry("A.java", "/p/A.java"));
        r.register(entry("べつの表示名", "/p/A.java"));
        check("同じパスは重複登録されない（表示名が違っても）", r.entries().size() == 1);
        check("最初に登録した表示名が残る", r.entries().get(0).name().equals("A.java"));
    }

    private static void testEntryWithoutPathIsNotRegistered() {
        BufferRegistry r = new BufferRegistry();
        r.register(entry("*grep*", null));
        check("パスを持たない疑似バッファは登録されない", r.entries().isEmpty());
    }

    private static void testUnregisterRemovesByPath() {
        BufferRegistry r = new BufferRegistry();
        r.register(entry("A.java", "/p/A.java"));
        r.register(entry("B.java", "/p/B.java"));
        r.unregister(entry("なんでもよい", "/p/A.java"));
        List<BufferEntry> es = r.entries();
        check("パス一致で取り除かれる（表示名は見ない）",
                es.size() == 1 && es.get(0).filePath().equals("/p/B.java"));
    }

    private static void testUnregisterUnknownPathIsNoOp() {
        BufferRegistry r = new BufferRegistry();
        r.register(entry("A.java", "/p/A.java"));
        r.unregister(entry("X.java", "/p/X.java"));
        r.unregister(entry("*run*", null));
        check("一覧に無いパス・パス無しを渡しても壊れない", r.entries().size() == 1);
    }

    private static void testEntriesReturnsDefensiveCopy() {
        BufferRegistry r = new BufferRegistry();
        r.register(entry("A.java", "/p/A.java"));
        List<BufferEntry> snapshot = r.entries();
        r.register(entry("B.java", "/p/B.java"));
        check("entries() は写しなので、後から登録しても取得済みの一覧は変わらない",
                snapshot.size() == 1 && r.entries().size() == 2);
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS: " + label);
        } else {
            failed++;
            System.out.println("FAIL [" + label + "]");
        }
    }
}
