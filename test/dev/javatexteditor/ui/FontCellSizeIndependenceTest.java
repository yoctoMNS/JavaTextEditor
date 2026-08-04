package dev.javatexteditor.ui;

/**
 * :font 0 (MiscFixed) / :font 1 (IBM Plex Mono) / :font 2 (JetBrains Mono) /
 * :font 3 (Comic Mono) 切替時のセルサイズ引き継ぎの回帰テスト（mainメソッド形式・JUnit不使用）。
 *
 * <p>2026-08-04より前は {@code EditorCanvas} がフォントごとに独立したセルサイズ退避
 * （miscCellW/H・plexCellW/H・jetbrainsCellW/H・comicCellW/H）を持ち、フォント切替のたびに
 * 「切替先フォントの最後に使ったサイズ（未使用ならそのフォントの既定値）」へ cellW/cellH を
 * 復元していた。この仕様は「フォントファミリーの変更だけを行ったのに、意図せずフォントサイズ
 * まで変わってしまう」という不具合として報告され、撤回した（経緯は decision-log.md
 * 「EditorCanvas.setFontChoice() のフォント別セルサイズ退避機構を撤回」節参照）。
 *
 * 本テストはクラス名を維持しつつ、新仕様（フォント変更はサイズに一切影響せず、常に切替直前の
 * cellW/cellH をそのまま引き継ぐ）を検証する内容に全面的に書き換えたもの。
 */
public class FontCellSizeIndependenceTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) {
        testDefaultIsMiscFixed();
        testSwitchToPlexMonoKeepsCurrentSize();
        testSwitchBackToMiscFixedKeepsCurrentSize();
        testResizingAfterSwitchDoesNotAffectOtherFontsPastSize();
        testRoundTripAlwaysKeepsLastSize();
        testSwitchToJetBrainsMonoKeepsCurrentSize();
        testSwitchToComicMonoKeepsCurrentSize();
        testAllFourFontsChainKeepsSameSizeThroughout();

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        System.exit(fail > 0 ? 1 : 0);
    }

    static void testDefaultIsMiscFixed() {
        EditorCanvas canvas = new EditorCanvas();
        check("起動直後は既定でMiscFixedの9x15幅", MiscFixedBold9x15.BASE_CELL_W, canvas.getCellW());
        check("起動直後は既定でMiscFixedの9x15高さ", MiscFixedBold9x15.BASE_CELL_H, canvas.getCellH());
    }

    /** フォント変更のみを行った場合、サイズは変更前の値のまま（依頼された期待動作そのもの）。 */
    static void testSwitchToPlexMonoKeepsCurrentSize() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setFontChoice(FontChoice.IBM_PLEX_MONO);
        check(":font 1直後もMiscFixedのデフォルト幅のまま変化しない",
            MiscFixedBold9x15.BASE_CELL_W, canvas.getCellW());
        check(":font 1直後もMiscFixedのデフォルト高さのまま変化しない",
            MiscFixedBold9x15.BASE_CELL_H, canvas.getCellH());
    }

    static void testSwitchBackToMiscFixedKeepsCurrentSize() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(45, 75); // MiscFixedのままリサイズ
        canvas.setFontChoice(FontChoice.IBM_PLEX_MONO);
        canvas.setFontChoice(FontChoice.MISC_FIXED);
        check(":font 1 -> :font 0 と往復してもリサイズ後の幅がそのまま引き継がれる", 45, canvas.getCellW());
        check(":font 1 -> :font 0 と往復してもリサイズ後の高さがそのまま引き継がれる", 75, canvas.getCellH());
    }

    /** 手動リサイズ（Ctrl+Shift+矢印 = adjustCellWidth/Height）自体の挙動は変更していないことの確認。 */
    static void testResizingAfterSwitchDoesNotAffectOtherFontsPastSize() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(20, 30);
        canvas.setFontChoice(FontChoice.IBM_PLEX_MONO);
        canvas.adjustCellWidth(5);
        canvas.adjustCellHeight(10);
        check("Plex Mono切替後に手動リサイズすると幅に反映される", 25, canvas.getCellW());
        check("Plex Mono切替後に手動リサイズすると高さに反映される", 40, canvas.getCellH());
    }

    /** :font 0 -> :font 1 -> :font 0 の往復で、常に「直前のサイズ」が引き継がれ続けることの確認。 */
    static void testRoundTripAlwaysKeepsLastSize() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(18, 30);
        canvas.setFontChoice(FontChoice.IBM_PLEX_MONO);
        check(":font 0 -> :font 1 でサイズが変化しない", 18, canvas.getCellW());
        check(":font 0 -> :font 1 でサイズが変化しない（高さ）", 30, canvas.getCellH());

        canvas.setInitialCellSize(28, 60);
        canvas.setFontChoice(FontChoice.MISC_FIXED);
        check(":font 1でリサイズ後 :font 0 へ戻ってもそのサイズが引き継がれる", 28, canvas.getCellW());
        check(":font 1でリサイズ後 :font 0 へ戻ってもそのサイズが引き継がれる（高さ）", 60, canvas.getCellH());
    }

    static void testSwitchToJetBrainsMonoKeepsCurrentSize() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(21, 45);
        canvas.setFontChoice(FontChoice.JETBRAINS_MONO);
        check(":font 2直後もサイズが変化しない", 21, canvas.getCellW());
        check(":font 2直後もサイズが変化しない（高さ）", 45, canvas.getCellH());
    }

    static void testSwitchToComicMonoKeepsCurrentSize() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(24, 40);
        canvas.setFontChoice(FontChoice.COMIC_MONO);
        check(":font 3直後もサイズが変化しない", 24, canvas.getCellW());
        check(":font 3直後もサイズが変化しない（高さ）", 40, canvas.getCellH());
    }

    /** 4フォントを連続で切り替えても、一度も setInitialCellSize/adjustCell* を呼ばなければサイズ不変。 */
    static void testAllFourFontsChainKeepsSameSizeThroughout() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(18, 30);
        canvas.setFontChoice(FontChoice.IBM_PLEX_MONO);
        canvas.setFontChoice(FontChoice.JETBRAINS_MONO);
        canvas.setFontChoice(FontChoice.COMIC_MONO);
        canvas.setFontChoice(FontChoice.MISC_FIXED);
        check("4フォントを連続で切り替えても幅は最初の値のまま", 18, canvas.getCellW());
        check("4フォントを連続で切り替えても高さは最初の値のまま", 30, canvas.getCellH());
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
