package dev.javatexteditor.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * WordIndex（Alt+/ 単語補完）のテスト。
 * buildSync() を使って同期的にインデックスを構築し検証する。
 */
public class WordIndexTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== WordIndexTest ===");

        testBuildAndQueryOnTempDir();
        testExcludesSkipDirs();
        testExcludesNonTextExtension();
        testMaxResults();
        testNoMatch();
        testEmptyPrefix();
        testExtraWordsMerged();
        testExactMatchIncluded();
        testExtractWords();
        testCaseInsensitivePrefix();
        testBuildTimeOnProjectSrc();
        testUpdateFileAddsNewClass();
        testUpdateFileReflectsAddedMethod();
        testUpdateFileRemovesStaleWords();
        testUpdateFileDeletedFileRemovesWords();
        testUpdateFileSharedWordSurvivesOtherFileUpdate();
        testUpdateFileDoesNotRescanWholeProject();

        System.out.println("=== " + passed + "/" + (passed + failed) + " PASSED ===");
        if (failed > 0) System.exit(1);
    }

    private static void testBuildAndQueryOnTempDir() throws IOException {
        Path tmp = Files.createTempDirectory("wordidx_");
        try {
            Files.writeString(tmp.resolve("Sample.java"),
                "class Sample { int hitPoint; void attackEnemy() { int attackPower = 10; } }");
            WordIndex idx = WordIndex.buildSync(tmp);
            assertTrue("buildSync 後は isReady()==true", idx.isReady());

            List<String> results = idx.query("attack", 10, null);
            assertTrue("'attack' で attackEnemy/attackPower が返る", results.size() >= 2);
            assertTrue("attackEnemy が含まれる", results.contains("attackEnemy"));
            assertTrue("attackPower が含まれる", results.contains("attackPower"));
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testExcludesSkipDirs() throws IOException {
        Path tmp = Files.createTempDirectory("wordidx_skip_");
        try {
            Path buildDir = Files.createDirectory(tmp.resolve("build"));
            Files.writeString(buildDir.resolve("Generated.java"), "class GeneratedIgnoredWord {}");
            Files.writeString(tmp.resolve("Real.java"), "class RealIncludedWord {}");
            WordIndex idx = WordIndex.buildSync(tmp);
            assertTrue("build/ 配下の単語は除外される",
                idx.query("GeneratedIgnored", 10, null).isEmpty());
            assertTrue("build/ 以外の単語は含まれる",
                !idx.query("RealIncluded", 10, null).isEmpty());
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testExcludesNonTextExtension() throws IOException {
        Path tmp = Files.createTempDirectory("wordidx_bin_");
        try {
            // .class 拡張子はテキスト対象外
            Files.write(tmp.resolve("Weird.class"), "BinaryMarkerWord".getBytes());
            WordIndex idx = WordIndex.buildSync(tmp);
            assertTrue(".class ファイルの単語は拾わない",
                idx.query("BinaryMarkerWord", 10, null).isEmpty());
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testMaxResults() throws IOException {
        Path tmp = Files.createTempDirectory("wordidx_max_");
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20; i++) sb.append("prefixWord").append(i).append(" ");
            Files.writeString(tmp.resolve("many.txt"), sb.toString());
            WordIndex idx = WordIndex.buildSync(tmp);
            List<String> results = idx.query("prefixWord", 5, null);
            assertTrue("maxResults=5 で結果が5件以下", results.size() <= 5);
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testNoMatch() throws IOException {
        Path tmp = Files.createTempDirectory("wordidx_nomatch_");
        try {
            Files.writeString(tmp.resolve("a.txt"), "hello world");
            WordIndex idx = WordIndex.buildSync(tmp);
            assertTrue("マッチしないプレフィックスは空リスト",
                idx.query("ZzZzZqQqQ", 10, null).isEmpty());
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testEmptyPrefix() throws IOException {
        Path tmp = Files.createTempDirectory("wordidx_empty_");
        try {
            Files.writeString(tmp.resolve("a.txt"), "hello world");
            WordIndex idx = WordIndex.buildSync(tmp);
            assertTrue("空文字列クエリは空リスト", idx.query("", 10, null).isEmpty());
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testExtraWordsMerged() throws IOException {
        Path tmp = Files.createTempDirectory("wordidx_extra_");
        try {
            Files.writeString(tmp.resolve("a.txt"), "diskWordOnly");
            WordIndex idx = WordIndex.buildSync(tmp);
            // まだディスクに保存されていない現在編集中バッファの単語（bufferOnlyWord）を extraWords で補う
            List<String> results = idx.query("buffer", 10, Set.of("bufferOnlyWord"));
            assertTrue("extraWords の単語も候補に含まれる", results.contains("bufferOnlyWord"));
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testExactMatchIncluded() throws IOException {
        // 入力済みプレフィックスと完全一致する単語も、他ファイル/他箇所に同名識別子がある
        // ケースを想定して候補から除外しない（Vim の ins-completion と同様の挙動）。
        Path tmp = Files.createTempDirectory("wordidx_exact_");
        try {
            Files.writeString(tmp.resolve("a.txt"), "exactWord exactWordLonger");
            WordIndex idx = WordIndex.buildSync(tmp);
            List<String> results = idx.query("exactWord", 10, null);
            assertTrue("完全一致の単語も候補に含まれる", results.contains("exactWord"));
            assertTrue("それ以外の前方一致も候補に残る", results.contains("exactWordLonger"));
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testExtractWords() {
        Set<String> words = WordIndex.extractWords("int hitPoint = 10; hitPoint += damage_value;");
        assertTrue("hitPoint が抽出される", words.contains("hitPoint"));
        assertTrue("damage_value が抽出される", words.contains("damage_value"));
        assertTrue("int が抽出される（キーワードも区別しない）", words.contains("int"));
        assertTrue("数値リテラルは抽出されない", !words.contains("10"));
    }

    private static void testCaseInsensitivePrefix() throws IOException {
        Path tmp = Files.createTempDirectory("wordidx_ci_");
        try {
            Files.writeString(tmp.resolve("Sample.java"), "class ArrayListWrapper { int arraySize; }");
            WordIndex idx = WordIndex.buildSync(tmp);

            List<String> lower = idx.query("array", 10, null);
            assertTrue("小文字プレフィックスで大文字始まりの単語もヒットする", lower.contains("ArrayListWrapper"));
            assertTrue("小文字プレフィックスで小文字始まりの単語もヒットする", lower.contains("arraySize"));

            List<String> upper = idx.query("ARRAY", 10, null);
            assertTrue("大文字プレフィックスでも大文字始まりの単語がヒットする", upper.contains("ArrayListWrapper"));
            assertTrue("大文字プレフィックスでも小文字始まりの単語がヒットする", upper.contains("arraySize"));

            // extraWords（現在編集中バッファの単語）側も大文字小文字を区別しない
            List<String> extra = idx.query("hit", 10, Set.of("HitPoint"));
            assertTrue("extraWords も大文字小文字を区別せずマッチする", extra.contains("HitPoint"));

            // extractWordsByProximity 側も大文字小文字を区別しない
            List<String> proximity = WordIndex.extractWordsByProximity("int HitPoint = 1;", 0, "hit");
            assertTrue("extractWordsByProximity も大文字小文字を区別しない", proximity.contains("HitPoint"));
        } finally {
            deleteDir(tmp);
        }
    }

    private static void testBuildTimeOnProjectSrc() {
        // このプロジェクト自身の src/ をスキャンしても高速に終わることを確認
        long start = System.currentTimeMillis();
        WordIndex idx = WordIndex.buildSync(Path.of("src"));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("プロジェクト src/ の buildSync() は5秒以内に完了する", elapsed < 5_000);
        assertTrue("isReady()==true", idx.isReady());
        // このクラス自身に含まれる識別子が拾えるはず
        assertTrue("'CompletionIndex' が拾える", !idx.query("CompletionIndex", 5, null).isEmpty());
        System.out.println("  buildSync(src/) elapsed: " + elapsed + " ms");
    }

    /** 再現手順1・2: 起動後（=buildSync 済み）に新規作成・保存したクラスの単語が updateFile() で反映される。 */
    private static void testUpdateFileAddsNewClass() throws IOException {
        Path tmp = Files.createTempDirectory("wordidx_newclass_");
        try {
            Files.writeString(tmp.resolve("Existing.java"), "class Existing {}");
            WordIndex idx = WordIndex.buildSync(tmp);
            assertTrue("更新前は新規クラス名がヒットしない",
                idx.query("BrandNewWidget", 10, null).isEmpty());

            Path newFile = tmp.resolve("BrandNewWidget.java");
            Files.writeString(newFile,
                "class BrandNewWidget { int widgetField; void widgetMethod() {} }");
            idx.updateFile(newFile);

            assertTrue("新規クラス名が反映される", idx.query("BrandNewWidget", 10, null).contains("BrandNewWidget"));
            assertTrue("新規フィールド名が反映される", idx.query("widgetField", 10, null).contains("widgetField"));
            assertTrue("新規メソッド名が反映される", idx.query("widgetMethod", 10, null).contains("widgetMethod"));
        } finally {
            deleteDir(tmp);
        }
    }

    /** 再現手順6-2: 既存クラスへメソッドを追加保存すると updateFile() で反映される。 */
    private static void testUpdateFileReflectsAddedMethod() throws IOException {
        Path tmp = Files.createTempDirectory("wordidx_addmethod_");
        try {
            Path file = tmp.resolve("Widget.java");
            Files.writeString(file, "class Widget { void oldMethod() {} }");
            WordIndex idx = WordIndex.buildSync(tmp);
            assertTrue("更新前は新メソッド名がヒットしない",
                idx.query("freshlyAddedMethod", 10, null).isEmpty());

            Files.writeString(file, "class Widget { void oldMethod() {} void freshlyAddedMethod() {} }");
            idx.updateFile(file);

            assertTrue("追加したメソッド名が反映される",
                idx.query("freshlyAddedMethod", 10, null).contains("freshlyAddedMethod"));
            assertTrue("既存メソッド名も引き続き候補に残る",
                idx.query("oldMethod", 10, null).contains("oldMethod"));
        } finally {
            deleteDir(tmp);
        }
    }

    /** そのファイルにしか出現しない単語が、内容の書き換えで消えれば候補からも消える。 */
    private static void testUpdateFileRemovesStaleWords() throws IOException {
        Path tmp = Files.createTempDirectory("wordidx_stale_");
        try {
            Path file = tmp.resolve("Renamed.java");
            Files.writeString(file, "class Renamed { void staleOnlyHereMethod() {} }");
            WordIndex idx = WordIndex.buildSync(tmp);
            assertTrue("更新前は存在する", idx.query("staleOnlyHereMethod", 10, null).contains("staleOnlyHereMethod"));

            Files.writeString(file, "class Renamed { void renamedMethod() {} }");
            idx.updateFile(file);

            assertTrue("書き換え後は古い単語が消える", idx.query("staleOnlyHereMethod", 10, null).isEmpty());
            assertTrue("新しい単語は反映される", idx.query("renamedMethod", 10, null).contains("renamedMethod"));
        } finally {
            deleteDir(tmp);
        }
    }

    /** テスト観点3: ファイル削除を updateFile() に伝えると、そのファイル由来の単語が候補から消える。 */
    private static void testUpdateFileDeletedFileRemovesWords() throws IOException {
        Path tmp = Files.createTempDirectory("wordidx_delete_");
        try {
            Path file = tmp.resolve("Doomed.java");
            Files.writeString(file, "class Doomed { void doomedOnlyMethod() {} }");
            WordIndex idx = WordIndex.buildSync(tmp);
            assertTrue("削除前は存在する", idx.query("doomedOnlyMethod", 10, null).contains("doomedOnlyMethod"));

            Files.delete(file);
            idx.updateFile(file); // ファイル削除後も安全に呼べる（読み取り不可→空集合扱い）

            assertTrue("削除後は候補から消える", idx.query("doomedOnlyMethod", 10, null).isEmpty());
        } finally {
            deleteDir(tmp);
        }
    }

    /** 複数ファイルにまたがる単語は、片方だけ更新しても消えない（wordFiles の参照カウント設計の検証）。 */
    private static void testUpdateFileSharedWordSurvivesOtherFileUpdate() throws IOException {
        Path tmp = Files.createTempDirectory("wordidx_shared_");
        try {
            Path a = tmp.resolve("A.java");
            Path b = tmp.resolve("B.java");
            Files.writeString(a, "class A { void sharedHelperMethod() {} }");
            Files.writeString(b, "class B { void sharedHelperMethod() {} }");
            WordIndex idx = WordIndex.buildSync(tmp);
            assertTrue("両ファイルに存在する単語がヒットする",
                idx.query("sharedHelperMethod", 10, null).contains("sharedHelperMethod"));

            // A.java だけを書き換えて、その単語を削除する
            Files.writeString(a, "class A { void onlyInAMethod() {} }");
            idx.updateFile(a);

            assertTrue("B.java にまだ残っているので候補から消えない",
                idx.query("sharedHelperMethod", 10, null).contains("sharedHelperMethod"));
            assertTrue("A.java の新しい単語も反映される",
                idx.query("onlyInAMethod", 10, null).contains("onlyInAMethod"));
        } finally {
            deleteDir(tmp);
        }
    }

    /**
     * テスト観点4: 1ファイルの updateFile() が他ファイルの単語に影響しないこと
     * （＝プロジェクト全体の再スキャンが走っていないこと）を、他ファイル側の単語が
     * 保存のたびに消えたりしないことで間接的に確認する。
     */
    private static void testUpdateFileDoesNotRescanWholeProject() throws IOException {
        Path tmp = Files.createTempDirectory("wordidx_norescan_");
        try {
            Path untouched = tmp.resolve("Untouched.java");
            Path touched = tmp.resolve("Touched.java");
            Files.writeString(untouched, "class Untouched { void untouchedMethod() {} }");
            Files.writeString(touched, "class Touched { void touchedMethod() {} }");
            WordIndex idx = WordIndex.buildSync(tmp);

            // Untouched.java を消してからディスク上の Touched.java だけを更新する。
            // もし updateFile がプロジェクト全体を再スキャンしていたら、削除済みの
            // Untouched.java 由来の単語まで巻き込んで消えてしまうはず。
            Files.delete(untouched);
            Files.writeString(touched, "class Touched { void touchedMethod() {} void touchedMethod2() {} }");
            idx.updateFile(touched);

            assertTrue("更新対象ファイルの新しい単語は反映される",
                idx.query("touchedMethod2", 10, null).contains("touchedMethod2"));
            assertTrue("更新していない（かつディスクからは消えた）他ファイルの単語は影響を受けず残る",
                idx.query("untouchedMethod", 10, null).contains("untouchedMethod"));
        } finally {
            deleteDir(tmp);
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
