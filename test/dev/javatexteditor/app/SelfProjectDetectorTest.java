package dev.javatexteditor.app;

import dev.javatexteditor.analysis.CodeSourceLocator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link SelfProjectDetector#isOwnProject} の検証。
 * 実際にこのテストが動いているJVM自身が「エディタ自身のプロジェクト」から起動されている
 * （build/配下のクラスとして実行される）ことを利用し、実物のプロジェクトルートで検証する。
 */
public class SelfProjectDetectorTest {

    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) throws Exception {
        Optional<Path> ownRootOpt = CodeSourceLocator
            .findUpward(SelfProjectDetectorTest.class, "scripts", 4, Files::isDirectory)
            .map(Path::getParent);
        if (ownRootOpt.isEmpty()) {
            System.out.println("[SKIP] このテスト環境では自プロジェクトのルート"
                + "（scripts/を持つ祖先ディレクトリ）が見つからないためスキップします");
            System.out.println("PASS: 0 / 0  (FAIL: 0)");
            return;
        }
        Path ownRoot = ownRootOpt.get();
        SelfProjectDetector detector = new SelfProjectDetector(SelfProjectDetectorTest.class);

        testExactMatchIsOwnProject(detector, ownRoot);
        testUnrelatedDirectoryIsNotOwnProject(detector);
        testSymlinkToOwnRootIsDetectedAsOwnProject(detector, ownRoot);

        int fail = total - pass;
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) System.exit(1);
    }

    static void check(String name, boolean expected, boolean actual) {
        total++;
        boolean ok = expected == actual;
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " (expected=" + expected + ", actual=" + actual + ")");
        if (ok) pass++;
    }

    private static void testExactMatchIsOwnProject(SelfProjectDetector detector, Path ownRoot) {
        check("自分自身のプロジェクトルートと完全一致すればtrue", true, detector.isOwnProject(ownRoot));
    }

    private static void testUnrelatedDirectoryIsNotOwnProject(SelfProjectDetector detector) throws IOException {
        Path other = Files.createTempDirectory("spd-other-");
        other.toFile().deleteOnExit();
        check("無関係な別ディレクトリはfalse（誤検知がない）", false, detector.isOwnProject(other));
    }

    /**
     * 追加要件: パス比較の前に toRealPath() でシンボリックリンクを解決してから比較すること。
     * このテストは、自プロジェクトルートを指すシンボリックリンク経由で projectRoot を渡しても
     * 正しく自己判定できる（＝toRealPath()の正規化が効いている）ことを確認する。
     */
    private static void testSymlinkToOwnRootIsDetectedAsOwnProject(
            SelfProjectDetector detector, Path ownRoot) throws IOException {
        Path linkParent = Files.createTempDirectory("spd-symlink-parent-");
        linkParent.toFile().deleteOnExit();
        Path link = linkParent.resolve("own-root-link");
        try {
            Files.createSymbolicLink(link, ownRoot);
        } catch (UnsupportedOperationException | IOException e) {
            System.out.println("[SKIP] このファイルシステムはシンボリックリンクに対応していないため"
                + "symlinkテストをスキップします: " + e.getMessage());
            return;
        }
        check("シンボリックリンク経由のprojectRootでも toRealPath() 正規化により自プロジェクトと判定される",
            true, detector.isOwnProject(link));
    }
}
