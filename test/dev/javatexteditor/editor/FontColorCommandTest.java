package dev.javatexteditor.editor;

import dev.javatexteditor.ui.EditorCanvas;
import dev.javatexteditor.ui.FontChoice;
import dev.javatexteditor.ui.Theme;
import java.awt.event.KeyEvent;

/**
 * :font 0/1/2/3（MiscFixed / IBM Plex Mono / JetBrains Mono / Comic Mono）・
 * :color 0/1（ダーク / ライト）コマンドの回帰テスト（mainメソッド形式・JUnit不使用）。
 */
public class FontColorCommandTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) {
        testDefaultsAreMiscFixedAndDark();
        testFontZeroSelectsMiscFixed();
        testFontOneSelectsIbmPlexMono();
        testFontTwoSelectsJetBrainsMono();
        testFontThreeSelectsComicMono();
        testColorZeroSelectsDark();
        testColorOneSelectsLight();
        testInvalidFontArgShowsError();
        testInvalidColorArgShowsError();
        testCanvasReflectsChoicesAfterSync();
        testCommandReturnsToNormalMode();
        testMissingFontFileShowsSetupGuidance();

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
        System.exit(fail > 0 ? 1 : 0);
    }

    static ModalEditor newEditorWithCanvas(String text) {
        return new ModalEditor(text, new EditorCanvas());
    }

    static void sendCommand(ModalEditor editor, String cmd) {
        editor.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        editor.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : cmd.toCharArray()) {
            editor.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        }
        editor.processKey(KeyEvent.VK_ENTER, KeyEvent.CHAR_UNDEFINED, 0);
    }

    static void testDefaultsAreMiscFixedAndDark() {
        ModalEditor ed = newEditorWithCanvas("abc");
        check("既定フォントはMiscFixed", FontChoice.MISC_FIXED, ed.getFontChoice());
        check("既定テーマはダークモード", Theme.DARK_MODE, ed.getTheme());
    }

    static void testFontZeroSelectsMiscFixed() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "font 1");
        check(":font 1 直後はIBM Plex Mono", FontChoice.IBM_PLEX_MONO, ed.getFontChoice());
        sendCommand(ed, "font 0");
        check(":font 0 でMiscFixedへ戻る", FontChoice.MISC_FIXED, ed.getFontChoice());
    }

    static void testFontOneSelectsIbmPlexMono() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "font 1");
        check(":font 1 でIBM Plex Monoになる", FontChoice.IBM_PLEX_MONO, ed.getFontChoice());
    }

    static void testFontTwoSelectsJetBrainsMono() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "font 2");
        check(":font 2 でJetBrains Monoになる", FontChoice.JETBRAINS_MONO, ed.getFontChoice());
        sendCommand(ed, "font 0");
        check(":font 0 でMiscFixedへ戻る", FontChoice.MISC_FIXED, ed.getFontChoice());
    }

    static void testFontThreeSelectsComicMono() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "font 3");
        check(":font 3 でComic Monoになる", FontChoice.COMIC_MONO, ed.getFontChoice());
        sendCommand(ed, "font 0");
        check(":font 0 でMiscFixedへ戻る", FontChoice.MISC_FIXED, ed.getFontChoice());
    }

    static void testColorZeroSelectsDark() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "color 1");
        sendCommand(ed, "color 0");
        check(":color 0 でダークモードになる", Theme.DARK_MODE, ed.getTheme());
    }

    static void testColorOneSelectsLight() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "color 1");
        check(":color 1 でライトモードになる", Theme.LIGHT_MODE, ed.getTheme());
    }

    static void testInvalidFontArgShowsError() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "font 9");
        check("不正な:font引数はフォントを変更しない", FontChoice.MISC_FIXED, ed.getFontChoice());
        check("不正な:font引数はエラーメッセージ", true, ed.getStatusMessage().startsWith("E:"));
    }

    static void testInvalidColorArgShowsError() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "color 9");
        check("不正な:color引数はテーマを変更しない", Theme.DARK_MODE, ed.getTheme());
        check("不正な:color引数はエラーメッセージ", true, ed.getStatusMessage().startsWith("E:"));
    }

    static void testCanvasReflectsChoicesAfterSync() {
        EditorCanvas canvas = new EditorCanvas();
        ModalEditor ed = new ModalEditor("abc", canvas);
        sendCommand(ed, "font 1");
        sendCommand(ed, "color 1");
        check("canvasにIBM Plex Monoが反映される", FontChoice.IBM_PLEX_MONO, canvas.getFontChoice());
        check("canvasにライトモードが反映される", Theme.LIGHT_MODE, canvas.getTheme());
        sendCommand(ed, "font 2");
        check("canvasにJetBrains Monoが反映される", FontChoice.JETBRAINS_MONO, canvas.getFontChoice());
        sendCommand(ed, "font 3");
        check("canvasにComic Monoが反映される", FontChoice.COMIC_MONO, canvas.getFontChoice());
    }

    static void testCommandReturnsToNormalMode() {
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "font 1");
        check(":font 実行後はNORMALモードに戻る", true, ed.isNormalMode());
        sendCommand(ed, "color 1");
        check(":color 実行後はNORMALモードに戻る", true, ed.isNormalMode());
    }

    /**
     * lib/fonts/ 配下のTTFが未取得（このコンテナ環境はscripts/setup.sh未実行が既定状態のため
     * 実際のネットワークアクセスなしに自然にシミュレートされている）の場合、:font 2/:font 3 実行後の
     * ステータスメッセージに setup.sh/setup.bat 実行を促す具体的な文言が出ることを確認する。
     * setup.sh実行済みの環境で実行した場合は、通常の "font: ..." メッセージになることを確認する
     * （どちらの分岐を通ったかは [INFO] で出力する）。
     */
    static void testMissingFontFileShowsSetupGuidance() {
        boolean jbLoaded = dev.javatexteditor.ui.JetBrainsMonoFont.INSTANCE.isBundledFontLoaded();
        ModalEditor ed = newEditorWithCanvas("abc");
        sendCommand(ed, "font 2");
        check(":font 2 はファイル有無に関わらずJetBrains Monoを選択する",
            FontChoice.JETBRAINS_MONO, ed.getFontChoice());
        if (jbLoaded) {
            System.out.println("[INFO] lib/fonts/JetBrainsMono-Regular.ttf が既に存在するため、"
                + "通常メッセージの分岐を検証します。");
            check(":font 2（ファイル有）は通常のfontメッセージ", "font: JetBrains Mono", ed.getStatusMessage());
        } else {
            System.out.println("[INFO] lib/fonts/JetBrainsMono-Regular.ttf が存在しないため、"
                + "setup案内メッセージの分岐を検証します。");
            check(":font 2（ファイル無）はscripts/setup.sh案内を含む",
                true, ed.getStatusMessage().contains("scripts/setup.sh"));
            check(":font 2（ファイル無）はsetup.batにも言及する",
                true, ed.getStatusMessage().contains("setup.bat"));
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
