package dev.vimacs.editor;

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
