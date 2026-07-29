package dev.javatexteditor.editor;

/**
 * ステータスバー用パス短縮ロジック {@link PathDisplay} のテストハーネス
 * （mainメソッド形式・JUnit不使用）。
 */
public class PathDisplayTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        testUnixAbsolutePath();
        testWindowsAbsolutePath();
        testDeeplyNestedUnixPath();
        testDeeplyNestedWindowsPath();
        testJapaneseAndSpaceInFileName();
        testDirectoryPathWithTrailingSlash();
        testDirectoryPathWithTrailingBackslash();
        testNoSeparatorReturnsAsIs();
        testMixedSeparators();

        System.out.printf("%nPASS: %d / %d  (FAIL: %d)%n", pass, pass + fail, fail);
        System.exit(fail > 0 ? 1 : 0);
    }

    static void testUnixAbsolutePath() {
        check("Unix絶対パス", "App.java",
            PathDisplay.baseName("/Users/geekjava/projects/sample-app/src/main/java/com/example/App.java"));
    }

    static void testWindowsAbsolutePath() {
        check("Windows絶対パス", "settings.yaml",
            PathDisplay.baseName("C:\\Users\\geekjava\\projects\\sample-app\\config\\settings.yaml"));
    }

    static void testDeeplyNestedUnixPath() {
        check("5階層以上のUnixパス", "z.txt",
            PathDisplay.baseName("/a/b/c/d/e/f/g/z.txt"));
    }

    static void testDeeplyNestedWindowsPath() {
        check("5階層以上のWindowsパス", "z.txt",
            PathDisplay.baseName("C:\\a\\b\\c\\d\\e\\f\\g\\z.txt"));
    }

    static void testJapaneseAndSpaceInFileName() {
        check("日本語・空白を含むファイル名", "設計 メモ.txt",
            PathDisplay.baseName("/home/user/ドキュメント/設計 メモ.txt"));
    }

    static void testDirectoryPathWithTrailingSlash() {
        check("末尾/のフォルダパス", "sample-app",
            PathDisplay.baseName("/Users/geekjava/projects/sample-app/"));
    }

    static void testDirectoryPathWithTrailingBackslash() {
        check("末尾\\のフォルダパス", "sample-app",
            PathDisplay.baseName("C:\\Users\\geekjava\\projects\\sample-app\\"));
    }

    static void testNoSeparatorReturnsAsIs() {
        check("区切り文字なしはそのまま", "App.java",
            PathDisplay.baseName("App.java"));
    }

    static void testMixedSeparators() {
        check("/と\\が混在するパス", "App.java",
            PathDisplay.baseName("/mnt/c/Users\\geekjava/App.java"));
    }

    // ユーティリティ
    // -------------------------------------------------------------------------

    static void check(String label, String expected, String actual) {
        boolean ok = expected.equals(actual);
        System.out.printf("[%s] %s -> expected=%s actual=%s%n", ok ? "OK" : "NG", label, expected, actual);
        if (ok) pass++; else fail++;
    }
}
