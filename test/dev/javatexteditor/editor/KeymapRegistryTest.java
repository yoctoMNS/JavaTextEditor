package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;

public class KeymapRegistryTest {
    static int testCount = 0;
    static int passCount = 0;

    public static void main(String[] args) {
        testDefaultBindings();
        testOverrideBindings();
        testUnregisteredKey();
        testMultipleModifiers();
        testResolveDifferentModes();
        testVisualModeBindings();
        testVisualLineModeBindings();
        testRegisterCustomAction();
        testCustomActionOverridesBuiltin();
        testResolveWithRealKeyEventFormat();

        System.out.println("\n--- Summary ---");
        System.out.println("PASS: " + passCount + " / " + testCount);
        if (passCount < testCount) {
            System.exit(1);
        }
    }

    static void testDefaultBindings() {
        System.out.println("[デフォルトキーマップ確認]");
        KeymapRegistry reg = new KeymapRegistry();

        // NORMAL: h -> cursor.left
        String action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_UNDEFINED, 'h', 0);
        check("NORMAL 'h' -> cursor.left", action.equals("cursor.left"));

        // NORMAL: l -> cursor.right
        action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_UNDEFINED, 'l', 0);
        check("NORMAL 'l' -> cursor.right", action.equals("cursor.right"));

        // NORMAL: i -> enter.insert
        action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_UNDEFINED, 'i', 0);
        check("NORMAL 'i' -> enter.insert", action.equals("enter.insert"));

        // NORMAL: Ctrl+R -> redo
        action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_R, (char) 18, KeyEvent.CTRL_DOWN_MASK);
        check("NORMAL Ctrl+R -> redo", action.equals("redo"));

        // INSERT: ESC -> enter.normal
        action = reg.resolve(KeymapRegistry.Mode.INSERT, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        check("INSERT ESC -> enter.normal", action.equals("enter.normal"));

        // INSERT: Ctrl+F -> cursor.right
        action = reg.resolve(KeymapRegistry.Mode.INSERT, KeyEvent.VK_F, (char) 6, KeyEvent.CTRL_DOWN_MASK);
        check("INSERT Ctrl+F -> cursor.right", action.equals("cursor.right"));
    }

    static void testOverrideBindings() {
        System.out.println("[バインド上書き確認]");
        KeymapRegistry reg = new KeymapRegistry();

        // デフォルトは 'h' -> cursor.left
        String before = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_UNDEFINED, 'h', 0);
        check("デフォルト: 'h' -> cursor.left", before.equals("cursor.left"));

        // 'h' を custom.action に上書き
        reg.bind(KeymapRegistry.Mode.NORMAL, KeyBinding.ofChar('h', "custom.action"), "custom.action");
        String after = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_UNDEFINED, 'h', 0);
        check("上書き後: 'h' -> custom.action", after.equals("custom.action"));
    }

    static void testUnregisteredKey() {
        System.out.println("[未登録キー確認]");
        KeymapRegistry reg = new KeymapRegistry();

        // 登録されていないキー '@' を NORMAL モードで解決
        String action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_UNDEFINED, '@', 0);
        check("未登録キー '@' -> null", action == null);

        // 登録されていない Ctrl+A を NORMAL モードで解決
        action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_A, (char) 1, KeyEvent.CTRL_DOWN_MASK);
        check("未登録キー Ctrl+A -> null", action == null);
    }

    static void testMultipleModifiers() {
        System.out.println("[複数修飾子の確認]");
        KeymapRegistry reg = new KeymapRegistry();

        // Shift+Ctrl+R を登録してみる（実際には使用していないが、テストして動作確認）
        int shiftCtrl = KeyEvent.SHIFT_DOWN_MASK | KeyEvent.CTRL_DOWN_MASK;
        reg.bind(KeymapRegistry.Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_R, shiftCtrl, "custom.redo"), "custom.redo");

        String action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_R, (char) 18, shiftCtrl);
        check("Shift+Ctrl+R -> custom.redo", action.equals("custom.redo"));

        // Ctrl+R だけは異なるアクション（まだ登録されている）
        action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_R, (char) 18, KeyEvent.CTRL_DOWN_MASK);
        check("Ctrl+R だけ -> redo (Shift+Ctrl+R と異なる)", action.equals("redo"));
    }

    static void testResolveDifferentModes() {
        System.out.println("[モード別解決確認]");
        KeymapRegistry reg = new KeymapRegistry();

        // NORMAL と INSERT で同じキーでも異なるアクション
        String normalAction = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        String insertAction = reg.resolve(KeymapRegistry.Mode.INSERT, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);

        check("NORMAL ESC -> null (未登録)", normalAction == null);
        check("INSERT ESC -> enter.normal", insertAction.equals("enter.normal"));

        // NORMAL では 'i' -> enter.insert
        // INSERT では 'i' は登録されていないので null
        normalAction = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_UNDEFINED, 'i', 0);
        insertAction = reg.resolve(KeymapRegistry.Mode.INSERT, KeyEvent.VK_UNDEFINED, 'i', 0);

        check("NORMAL 'i' -> enter.insert", normalAction.equals("enter.insert"));
        check("INSERT 'i' -> null (未登録)", insertAction == null);
    }

    static void testVisualModeBindings() {
        System.out.println("[VISUAL モードキーマップ確認]");
        KeymapRegistry reg = new KeymapRegistry();

        // 移動キー
        check("VISUAL 'h' -> cursor.left",
            "cursor.left".equals(reg.resolve(KeymapRegistry.Mode.VISUAL, KeyEvent.VK_UNDEFINED, 'h', 0)));
        check("VISUAL 'l' -> cursor.right",
            "cursor.right".equals(reg.resolve(KeymapRegistry.Mode.VISUAL, KeyEvent.VK_UNDEFINED, 'l', 0)));
        check("VISUAL 'j' -> cursor.down",
            "cursor.down".equals(reg.resolve(KeymapRegistry.Mode.VISUAL, KeyEvent.VK_UNDEFINED, 'j', 0)));
        check("VISUAL 'k' -> cursor.up",
            "cursor.up".equals(reg.resolve(KeymapRegistry.Mode.VISUAL, KeyEvent.VK_UNDEFINED, 'k', 0)));

        // ヤンク・削除
        check("VISUAL 'y' -> yank",
            "yank".equals(reg.resolve(KeymapRegistry.Mode.VISUAL, KeyEvent.VK_UNDEFINED, 'y', 0)));
        check("VISUAL 'd' -> delete",
            "delete".equals(reg.resolve(KeymapRegistry.Mode.VISUAL, KeyEvent.VK_UNDEFINED, 'd', 0)));

        // ESC は processKey で先行処理されるが、登録はされている
        check("VISUAL ESC -> enter.normal",
            "enter.normal".equals(reg.resolve(KeymapRegistry.Mode.VISUAL, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0)));

        // VISUAL モード固有確認: NORMAL にある 'i' が VISUAL では null
        check("VISUAL 'i' -> null (NORMAL のキーは引き継がない)",
            reg.resolve(KeymapRegistry.Mode.VISUAL, KeyEvent.VK_UNDEFINED, 'i', 0) == null);
    }

    static void testVisualLineModeBindings() {
        System.out.println("[VISUAL LINE モードキーマップ確認]");
        KeymapRegistry reg = new KeymapRegistry();

        // 移動キー
        check("VISUAL_LINE 'j' -> cursor.down",
            "cursor.down".equals(reg.resolve(KeymapRegistry.Mode.VISUAL_LINE, KeyEvent.VK_UNDEFINED, 'j', 0)));
        check("VISUAL_LINE 'k' -> cursor.up",
            "cursor.up".equals(reg.resolve(KeymapRegistry.Mode.VISUAL_LINE, KeyEvent.VK_UNDEFINED, 'k', 0)));

        // ヤンク・削除
        check("VISUAL_LINE 'y' -> yank",
            "yank".equals(reg.resolve(KeymapRegistry.Mode.VISUAL_LINE, KeyEvent.VK_UNDEFINED, 'y', 0)));
        check("VISUAL_LINE 'd' -> delete",
            "delete".equals(reg.resolve(KeymapRegistry.Mode.VISUAL_LINE, KeyEvent.VK_UNDEFINED, 'd', 0)));

        // VISUAL と VISUAL_LINE が独立したマップを持つことを確認
        reg.bind(KeymapRegistry.Mode.VISUAL_LINE, KeyBinding.ofChar('y', "custom.yank"), "custom.yank");
        check("VISUAL_LINE 'y' を上書き -> custom.yank",
            "custom.yank".equals(reg.resolve(KeymapRegistry.Mode.VISUAL_LINE, KeyEvent.VK_UNDEFINED, 'y', 0)));
        check("VISUAL 'y' は影響を受けない -> yank",
            "yank".equals(reg.resolve(KeymapRegistry.Mode.VISUAL, KeyEvent.VK_UNDEFINED, 'y', 0)));
    }

    static void testRegisterCustomAction() {
        System.out.println("[カスタムアクション登録確認]");
        KeymapRegistry reg = new KeymapRegistry();

        // 未登録アクション -> null
        check("未登録アクション -> null", reg.getCustomAction("my.custom") == null);

        // 登録後 -> ハンドラが返る
        boolean[] ran = { false };
        reg.registerAction("my.custom", () -> ran[0] = true);
        Runnable handler = reg.getCustomAction("my.custom");
        check("登録後 getCustomAction -> non-null", handler != null);

        handler.run();
        check("ハンドラを run() すると実行される", ran[0]);

        // 上書き登録
        boolean[] ran2 = { false };
        reg.registerAction("my.custom", () -> ran2[0] = true);
        reg.getCustomAction("my.custom").run();
        check("上書き登録したハンドラが実行される", ran2[0]);
        check("旧ハンドラの ran[0] は true のまま (副作用なし)", ran[0]);
    }

    static void testCustomActionOverridesBuiltin() {
        System.out.println("[カスタムアクションがビルトインを上書き]");
        dev.javatexteditor.editor.ModalEditor editor = new dev.javatexteditor.editor.ModalEditor("abc");
        KeymapRegistry km = editor.getKeymap();

        // 'z' に新アクションを割り当てる
        boolean[] triggered = { false };
        km.bind(KeymapRegistry.Mode.NORMAL, KeyBinding.ofChar('z', "custom.z"), "custom.z");
        km.registerAction("custom.z", () -> triggered[0] = true);

        editor.processKey(KeyEvent.VK_UNDEFINED, 'z', 0);
        check("NORMAL モードで 'z' を押すとカスタムアクションが実行される", triggered[0]);

        // 既存の 'h' をカスタムアクションで上書きするとデフォルト動作（cursor.left）を置き換えられる
        boolean[] hRan = { false };
        int colBefore = editor.getCursorCol();
        km.registerAction("cursor.left", () -> hRan[0] = true);
        editor.processKey(KeyEvent.VK_UNDEFINED, 'h', 0);
        check("'h' の cursor.left をカスタムに差し替え -> カスタムが実行", hRan[0]);
        check("'h' のデフォルト移動は実行されない (col 変化なし)", editor.getCursorCol() == colBefore);
    }

    static void testResolveWithRealKeyEventFormat() {
        System.out.println("[実際のキーイベント形式での解決確認]");
        // 実際の KeyEvent では、文字キーも keyCode が VK_H=72 のように設定される。
        // resolve() は keyCode で先に探して見つからなければ keyChar にフォールバックすることで正しく動作する。
        KeymapRegistry reg = new KeymapRegistry();

        // 'h' キー: KeyEvent は keyCode=VK_H(72), keyChar='h' を持つ
        String action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_H, 'h', 0);
        check("実キーイベント形式: VK_H+'h' -> cursor.left", "cursor.left".equals(action));

        // 'j' キー
        action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_J, 'j', 0);
        check("実キーイベント形式: VK_J+'j' -> cursor.down", "cursor.down".equals(action));

        // 'i' キー
        action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_I, 'i', 0);
        check("実キーイベント形式: VK_I+'i' -> enter.insert", "enter.insert".equals(action));

        // 'v' キー
        action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_V, 'v', 0);
        check("実キーイベント形式: VK_V+'v' -> enter.visual", "enter.visual".equals(action));

        // Ctrl+R: keyCode=VK_R, keyChar=(char)18 — ofCode で登録済み、こちらは keyCode で解決
        action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_R, (char)18, KeyEvent.CTRL_DOWN_MASK);
        check("実キーイベント形式: Ctrl+R -> redo (keyCode 優先)", "redo".equals(action));

        // ESC: keyCode=VK_ESCAPE, keyChar=CHAR_UNDEFINED
        action = reg.resolve(KeymapRegistry.Mode.INSERT, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        check("実キーイベント形式: VK_ESCAPE -> enter.normal", "enter.normal".equals(action));

        // Ctrl+F: keyCode=VK_F, keyChar=(char)6
        action = reg.resolve(KeymapRegistry.Mode.INSERT, KeyEvent.VK_F, (char)6, KeyEvent.CTRL_DOWN_MASK);
        check("実キーイベント形式: Ctrl+F -> cursor.right", "cursor.right".equals(action));

        // 'h' を NORMAL で Ctrl 付き押した場合は登録なし (NORMAL 'h' はmodifiers=0 のみ)
        action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_H, 'h', KeyEvent.CTRL_DOWN_MASK);
        check("実キーイベント形式: Ctrl+H -> null (未登録)", action == null);

        // Shift+K: keyChar='K' 大文字で届く環境
        action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_K, 'K', KeyEvent.SHIFT_DOWN_MASK);
        check("Shift+K (keyChar='K') -> jdk.doc", "jdk.doc".equals(action));

        // Shift+K: keyChar='k' 小文字で届く環境（AWT プラットフォーム差）
        action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_K, 'k', KeyEvent.SHIFT_DOWN_MASK);
        check("Shift+K (keyChar='k') -> jdk.doc", "jdk.doc".equals(action));

        // 小文字 k はカーソル上移動（Shift なし）
        action = reg.resolve(KeymapRegistry.Mode.NORMAL, KeyEvent.VK_K, 'k', 0);
        check("k (Shift なし) -> cursor.up", "cursor.up".equals(action));
    }

    static void check(String desc, boolean result) {
        testCount++;
        if (result) {
            passCount++;
            System.out.println("  PASS: " + desc);
        } else {
            System.out.println("  FAIL: " + desc);
        }
    }
}
