package dev.vimacs.editor;

import java.awt.event.KeyEvent;

/**
 * 1つのキーバインドを表す不変レコード。
 * keyCode は KeyEvent の定数（特殊キー用）。
 * keyChar は文字キー用（KeyEvent.CHAR_UNDEFINED なら keyCode を使う）。
 */
public record KeyBinding(
    int keyCode,        // KeyEvent.VK_* （文字キーは KeyEvent.VK_UNDEFINED）
    char keyChar,       // 文字キー（特殊キーは KeyEvent.CHAR_UNDEFINED）
    int modifiers,      // KeyEvent.CTRL_DOWN_MASK 等のビットマスク（0 = なし）
    String actionName   // アクション識別名（例: "cursor.left", "enter.insert"）
) {
    /** 文字キー用ファクトリ（修飾なし） */
    public static KeyBinding ofChar(char c, String actionName) {
        return new KeyBinding(KeyEvent.VK_UNDEFINED, c, 0, actionName);
    }

    /** 特殊キー用ファクトリ */
    public static KeyBinding ofCode(int keyCode, int modifiers, String actionName) {
        return new KeyBinding(keyCode, KeyEvent.CHAR_UNDEFINED, modifiers, actionName);
    }
}
