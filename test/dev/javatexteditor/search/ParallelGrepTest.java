package dev.javatexteditor.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 軽量化リファクタリング Phase 3（ProjectSearcher の2段階並列化）の回帰テスト。
 * 並列化後も (1) 結果順序が決定的（同一入力で同一順序・ファイル内は行昇順）であること、
 * (2) 2MB上限・NULバイナリスキップ・DEFAULT_SKIP_DIRSが従来どおり効くこと、
 * (3) fullScan=true でスキップが無効化されること、を検証する。
 */
public class ParallelGrepTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("parallel-grep-test");
        try {
            setupTree(root);
            testDeterministicOrder(root);
            testPerFileLineOrderAscending(root);
            testSkipRulesPreserved(root);
            testFullScanIncludesSkippedDirs(root);
        } finally {
            deleteRecursively(root);
        }

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) System.exit(1);
    }

    static void setupTree(Path root) throws Exception {
        Files.createDirectories(root.resolve("sub"));
        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(root.resolve("sub/a.txt"), "no\nNEEDLE one\nno\nno\nNEEDLE two\n");
        Files.writeString(root.resolve("sub/b.txt"), "nothing here\n");
        Files.writeString(root.resolve("c.txt"), "NEEDLE three\n");
        Files.writeString(root.resolve("node_modules/skip.txt"), "NEEDLE skipped\n");
        // NULバイトを含むバイナリ（スキップされる）
        Files.write(root.resolve("bin.dat"), new byte[]{'N', 'E', 'E', 'D', 'L', 'E', 0, 1, 2});
        // 2MB超（スキップされる）。中身に一致行を含めても結果に出ないこと
        byte[] big = new byte[2 * 1024 * 1024 + 1024];
        byte[] needle = "NEEDLE big\n".getBytes();
        System.arraycopy(needle, 0, big, 0, needle.length);
        Arrays.fill(big, needle.length, big.length, (byte) 'z');
        Files.write(root.resolve("big.txt"), big);
    }

    static void testDeterministicOrder(Path root) {
        System.out.println("[結果順序の決定性: 2回実行して完全一致]");
        ProjectSearcher searcher = new ProjectSearcher();
        List<SearchResult> r1 = searcher.search(root, "NEEDLE");
        List<SearchResult> r2 = searcher.search(root, "NEEDLE");
        check("2回の実行で件数一致", r1.size(), r2.size());
        boolean sameOrder = true;
        for (int i = 0; i < Math.min(r1.size(), r2.size()); i++) {
            SearchResult a = r1.get(i);
            SearchResult b = r2.get(i);
            if (!a.filePath().equals(b.filePath()) || a.lineNumber() != b.lineNumber()) {
                sameOrder = false;
                break;
            }
        }
        check("2回の実行で順序完全一致", true, sameOrder);
        check("一致は3件（a.txt×2 + c.txt×1）", 3, r1.size());
    }

    static void testPerFileLineOrderAscending(Path root) {
        System.out.println("[同一ファイル内の一致は行番号昇順]");
        ProjectSearcher searcher = new ProjectSearcher();
        List<SearchResult> results = searcher.search(root, "NEEDLE");
        int prevLine = -1;
        String prevFile = null;
        boolean ascending = true;
        for (SearchResult r : results) {
            if (r.filePath().equals(prevFile) && r.lineNumber() <= prevLine) {
                ascending = false;
                break;
            }
            prevFile = r.filePath();
            prevLine = r.lineNumber();
        }
        check("ファイル内の行番号が昇順", true, ascending);
    }

    static void testSkipRulesPreserved(Path root) {
        System.out.println("[2MB上限・NULバイナリ・SKIP_DIRSが従来どおり効く]");
        ProjectSearcher searcher = new ProjectSearcher();
        List<SearchResult> results = searcher.search(root, "NEEDLE");
        boolean hasBig = results.stream().anyMatch(r -> r.filePath().contains("big.txt"));
        boolean hasBin = results.stream().anyMatch(r -> r.filePath().contains("bin.dat"));
        boolean hasNodeModules = results.stream().anyMatch(r -> r.filePath().contains("node_modules"));
        check("2MB超ファイルは結果に出ない", false, hasBig);
        check("NULバイナリは結果に出ない", false, hasBin);
        check("node_modulesはデフォルトでスキップ", false, hasNodeModules);
    }

    static void testFullScanIncludesSkippedDirs(Path root) {
        System.out.println("[fullScan=true でスキップ対象ディレクトリも走査する]");
        ProjectSearcher searcher = new ProjectSearcher();
        List<SearchResult> results = searcher.search(root, "NEEDLE", true);
        boolean hasNodeModules = results.stream().anyMatch(r -> r.filePath().contains("node_modules"));
        check("fullScanでnode_modules内も一致", true, hasNodeModules);
    }

    static void deleteRecursively(Path root) throws Exception {
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                  .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        }
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
