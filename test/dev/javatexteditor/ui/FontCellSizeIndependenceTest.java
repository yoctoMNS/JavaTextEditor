package dev.javatexteditor.ui;

/**
 * :font 0 (MiscFixed) / :font 1 (IBM Plex Mono) 切替時のセルサイズ独立性の
 * 回帰テスト（mainメソッド形式・JUnit不使用）。
 *
 * 従来 EditorCanvas.cellW/cellH は fontChoice に関わらず単一のグローバル状態を
 * 共有しており、MiscFixed(9x15)で表示中に:font 1へ切り替えるとPlex Monoが
 * MiscFixedのセルサイズをそのまま引き継いでしまうバグがあった（本来は
 * IbmPlexMonoFont.BASE_CELL_W/H=7x15 に復元されるべき）。この修正で
 * EditorCanvasにフォントごとの独立したセルサイズ退避（miscCellW/H・plexCellW/H）を
 * 追加した。詳細はfont-and-statusline-animationスキル参照。
 */
public class FontCellSizeIndependenceTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) {
        testDefaultIsMiscFixed();
        testSwitchToPlexMonoWithoutResizeUsesPlexDefault();
        testSwitchBackToMiscFixedRestoresMiscDefault();
        testResizingOneFontDoesNotAffectTheOther();
        testRoundTripPreservesEachFontsCustomSize();

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

    static void testSwitchToPlexMonoWithoutResizeUsesPlexDefault() {
        EditorCanvas canvas = new EditorCanvas();
        // Ctrl+Shift+矢印での変更は一切行わない。
        canvas.setFontChoice(FontChoice.IBM_PLEX_MONO);
        check(":font 1直後はMiscFixedの幅を引き継がずPlex Mono本来の既定幅になる",
            IbmPlexMonoFont.BASE_CELL_W, canvas.getCellW());
        check(":font 1直後はMiscFixedの高さを引き継がずPlex Mono本来の既定高さになる",
            IbmPlexMonoFont.BASE_CELL_H, canvas.getCellH());
    }

    static void testSwitchBackToMiscFixedRestoresMiscDefault() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setFontChoice(FontChoice.IBM_PLEX_MONO);
        canvas.setFontChoice(FontChoice.MISC_FIXED);
        check(":font 0へ戻すとMiscFixedの既定幅に復元される",
            MiscFixedBold9x15.BASE_CELL_W, canvas.getCellW());
        check(":font 0へ戻すとMiscFixedの既定高さに復元される",
            MiscFixedBold9x15.BASE_CELL_H, canvas.getCellH());
    }

    static void testResizingOneFontDoesNotAffectTheOther() {
        EditorCanvas canvas = new EditorCanvas();
        // MiscFixedのまま大きくリサイズする。
        canvas.setInitialCellSize(45, 75);
        // Plex Monoへ切り替えても、一度もリサイズしていないPlex Mono側は本来の既定値のまま。
        canvas.setFontChoice(FontChoice.IBM_PLEX_MONO);
        check("MiscFixedのリサイズ後にPlex Monoへ切り替えても幅はPlex Mono既定値",
            IbmPlexMonoFont.BASE_CELL_W, canvas.getCellW());
        check("MiscFixedのリサイズ後にPlex Monoへ切り替えても高さはPlex Mono既定値",
            IbmPlexMonoFont.BASE_CELL_H, canvas.getCellH());

        // 逆方向: Plex Monoをリサイズしても、MiscFixed側の状態は変化しない。
        canvas.setInitialCellSize(21, 45);
        canvas.setFontChoice(FontChoice.MISC_FIXED);
        check("Plex Monoのリサイズ後にMiscFixedへ戻すと幅は直前のMiscFixedリサイズ値のまま", 45, canvas.getCellW());
        check("Plex Monoのリサイズ後にMiscFixedへ戻すと高さは直前のMiscFixedリサイズ値のまま", 75, canvas.getCellH());
    }

    static void testRoundTripPreservesEachFontsCustomSize() {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(18, 30); // MiscFixed側をカスタムサイズに
        canvas.setFontChoice(FontChoice.IBM_PLEX_MONO);
        canvas.setInitialCellSize(28, 60); // Plex Mono側を別のカスタムサイズに

        canvas.setFontChoice(FontChoice.MISC_FIXED);
        check(":font 0 -> :font 1 -> :font 0 でMiscFixedのカスタム幅が保持される", 18, canvas.getCellW());
        check(":font 0 -> :font 1 -> :font 0 でMiscFixedのカスタム高さが保持される", 30, canvas.getCellH());

        canvas.setFontChoice(FontChoice.IBM_PLEX_MONO);
        check(":font 0 -> :font 1 でPlex Monoのカスタム幅が保持される", 28, canvas.getCellW());
        check(":font 0 -> :font 1 でPlex Monoのカスタム高さが保持される", 60, canvas.getCellH());
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
