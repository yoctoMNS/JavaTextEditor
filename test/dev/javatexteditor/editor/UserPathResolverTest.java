package dev.javatexteditor.editor;

import java.io.File;
import java.nio.file.Path;

/**
 * UserPathResolver（コマンド行のパス文字列 → 絶対パス）の単体テスト。
 * ファイルシステムにも ModalEditor の状態にも依存しない純粋ロジックなので、
 * 一時ディレクトリを作らずに検証できる。
 */
public class UserPathResolverTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testExpandHomeAlone();
        testExpandHomeWithSlash();
        testExpandHomeWithBackslash();
        testExpandHomeLeavesOtherPathsAlone();
        testResolveRelativePathAgainstBase();
        testResolveAbsolutePathIgnoresBase();
        testResolveExpandsHomeBeforeResolving();

        System.out.println();
        System.out.println("PASS: " + passed + " / " + (passed + failed) + "  (FAIL: " + failed + ")");
        if (failed > 0) System.exit(1);
    }

    private static void testExpandHomeAlone() {
        String home = System.getProperty("user.home", "");
        check("~ 単体はホームディレクトリへ展開される",
                UserPathResolver.expandHome("~").equals(home));
    }

    private static void testExpandHomeWithSlash() {
        String home = System.getProperty("user.home", "");
        String expected = home + File.separator + "docs/memo.txt";
        check("~/... はホーム配下へ展開される",
                UserPathResolver.expandHome("~/docs/memo.txt").equals(expected));
    }

    private static void testExpandHomeWithBackslash() {
        String home = System.getProperty("user.home", "");
        String expected = home + File.separator + "docs";
        check("~\\... (Windows形式) もホーム配下へ展開される",
                UserPathResolver.expandHome("~\\docs").equals(expected));
    }

    private static void testExpandHomeLeavesOtherPathsAlone() {
        check("~ で始まらないパスは変更されない",
                UserPathResolver.expandHome("src/Main.java").equals("src/Main.java"));
        check("~user 形式は展開対象外（そのまま返す）",
                UserPathResolver.expandHome("~other/x").equals("~other/x"));
    }

    private static void testResolveRelativePathAgainstBase() {
        Path base = Path.of("/tmp/project").toAbsolutePath();
        String resolved = UserPathResolver.resolveAgainst(base, "src/Main.java");
        check("相対パスは基準ディレクトリ配下の絶対パスになる",
                resolved.equals(base.resolve("src/Main.java").toAbsolutePath().toString()));
        check("解決結果は常に絶対パス", Path.of(resolved).isAbsolute());
    }

    private static void testResolveAbsolutePathIgnoresBase() {
        Path base = Path.of("/tmp/project").toAbsolutePath();
        Path absolute = Path.of("/etc/hosts").toAbsolutePath();
        check("絶対パスは基準ディレクトリを無視してそのまま返る",
                UserPathResolver.resolveAgainst(base, absolute.toString()).equals(absolute.toString()));
    }

    private static void testResolveExpandsHomeBeforeResolving() {
        Path base = Path.of("/tmp/project").toAbsolutePath();
        String home = System.getProperty("user.home", "");
        String resolved = UserPathResolver.resolveAgainst(base, "~/memo.txt");
        check("~ 展開後は絶対パス扱いとなり基準ディレクトリが前置されない",
                resolved.startsWith(home) && !resolved.startsWith(base.toString()));
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
