package dev.vimacs.editor;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * モード別キーバインド管理。
 * キーは "keyChar:modifiers" または "VKkeyCode:modifiers" 形式の文字列。
 * アクション名（String）で処理を指定し、ModalEditor で Runnable に変換する。
 */
public class KeymapRegistry {
    public enum Mode { NORMAL, INSERT, COMMAND, VISUAL, VISUAL_LINE }

    private final Map<Mode, Map<String, String>> bindings = new HashMap<>();
    private final Map<String, Runnable> customActions = new HashMap<>();

    public KeymapRegistry() {
        for (Mode m : Mode.values()) {
            bindings.put(m, new HashMap<>());
        }
        loadDefaults();
    }

    /** キーバインドを登録（後から上書き可能） */
    public void bind(Mode mode, KeyBinding key, String actionName) {
        bindings.get(mode).put(toKey(key), actionName);
    }

    /**
     * キー入力からアクション名を解決（見つからなければ null）。
     * keyCode ベースで先に探し、見つからなければ keyChar ベースでフォールバックする。
     * これにより ofCode() 登録キー（ESC/Enter/Ctrl+X 等）と
     * ofChar() 登録キー（h/j/k/l 等）の両方を、実際のキーイベントから正しく解決できる。
     *
     * keyChar ベースのフォールバックでは SHIFT_DOWN_MASK を除去する。
     * ':' や 'V' のような Shift 修飾文字は keyChar 自体が Shift を反映しており、
     * ofChar() は modifiers=0 で登録するため、実キーイベントの SHIFT を除かないと
     * マップのキーが一致しない。
     */
    public String resolve(Mode mode, int keyCode, char keyChar, int modifiers) {
        Map<String, String> map = bindings.get(mode);
        // keyCode ベースで探す（特殊キーや Ctrl+X 系はこちらで解決）
        if (keyCode != KeyEvent.VK_UNDEFINED) {
            String action = map.get("VK" + keyCode + ":" + modifiers);
            if (action != null) return action;
        }
        // keyChar ベースにフォールバック（h/j/k/l、':'、'V' 等）
        // keyChar は Shift 状態を既に反映しているため（':' vs ';'、'V' vs 'v'）、
        // ofChar() が modifiers=0 で登録したバインドと一致させるために SHIFT を除く
        if (keyChar != KeyEvent.CHAR_UNDEFINED) {
            int charModifiers = modifiers & ~KeyEvent.SHIFT_DOWN_MASK;
            return map.get(keyChar + ":" + charModifiers);
        }
        return null;
    }

    /** プラグインが独自アクションのハンドラを登録する。既存のアクション名も上書き可能。 */
    public void registerAction(String actionName, Runnable handler) {
        customActions.put(actionName, handler);
    }

    /** アクション名に紐付いたカスタムハンドラを返す（未登録なら null）。 */
    public Runnable getCustomAction(String actionName) {
        return customActions.get(actionName);
    }

    private String toKey(KeyBinding kb) {
        if (kb.keyCode() != KeyEvent.VK_UNDEFINED) {
            return "VK" + kb.keyCode() + ":" + kb.modifiers();
        }
        return kb.keyChar() + ":" + kb.modifiers();
    }

    private void loadDefaults() {
        // NORMAL モード
        bind(Mode.NORMAL, KeyBinding.ofChar('h', "cursor.left"), "cursor.left");
        bind(Mode.NORMAL, KeyBinding.ofChar('l', "cursor.right"), "cursor.right");
        bind(Mode.NORMAL, KeyBinding.ofChar('j', "cursor.down"), "cursor.down");
        bind(Mode.NORMAL, KeyBinding.ofChar('k', "cursor.up"), "cursor.up");
        bind(Mode.NORMAL, KeyBinding.ofChar('i', "enter.insert"), "enter.insert");
        bind(Mode.NORMAL, KeyBinding.ofChar('a', "enter.insert.after"), "enter.insert.after");
        bind(Mode.NORMAL, KeyBinding.ofChar('o', "enter.insert.newline"), "enter.insert.newline");
        bind(Mode.NORMAL, KeyBinding.ofChar(':', "enter.command"), "enter.command");
        bind(Mode.NORMAL, KeyBinding.ofChar('u', "undo"), "undo");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK, "redo"), "redo");
        bind(Mode.NORMAL, KeyBinding.ofChar('v', "enter.visual"), "enter.visual");
        bind(Mode.NORMAL, KeyBinding.ofChar('V', "enter.visual.line"), "enter.visual.line");
        bind(Mode.NORMAL, KeyBinding.ofChar('x', "delete.char"), "delete.char");
        bind(Mode.NORMAL, KeyBinding.ofChar('p', "paste.after"), "paste.after");
        bind(Mode.NORMAL, KeyBinding.ofChar('P', "paste.before"), "paste.before");
        bind(Mode.NORMAL, KeyBinding.ofChar('y', "yank.pending"), "yank.pending");
        bind(Mode.NORMAL, KeyBinding.ofChar('d', "delete.pending"), "delete.pending");

        // INSERT モード
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_ESCAPE, 0, "enter.normal"), "enter.normal");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK, "cursor.right"), "cursor.right");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK, "cursor.left"), "cursor.left");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_N, KeyEvent.CTRL_DOWN_MASK, "cursor.down"), "cursor.down");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK, "cursor.up"), "cursor.up");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_BACK_SPACE, 0, "delete.before"), "delete.before");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_ENTER, 0, "insert.newline"), "insert.newline");

        // COMMAND モード（:に続く入力）
        // 基本的には文字入力をそのまま溜めるので、特別なキーバインドは ESC だけ
        bind(Mode.COMMAND, KeyBinding.ofCode(KeyEvent.VK_ESCAPE, 0, "enter.normal"), "enter.normal");
        bind(Mode.COMMAND, KeyBinding.ofCode(KeyEvent.VK_ENTER, 0, "execute.command"), "execute.command");

        // VISUAL モード（文字単位選択）
        bind(Mode.VISUAL, KeyBinding.ofChar('h', "cursor.left"), "cursor.left");
        bind(Mode.VISUAL, KeyBinding.ofChar('l', "cursor.right"), "cursor.right");
        bind(Mode.VISUAL, KeyBinding.ofChar('j', "cursor.down"), "cursor.down");
        bind(Mode.VISUAL, KeyBinding.ofChar('k', "cursor.up"), "cursor.up");
        bind(Mode.VISUAL, KeyBinding.ofChar('y', "yank"), "yank");
        bind(Mode.VISUAL, KeyBinding.ofChar('d', "delete"), "delete");
        bind(Mode.VISUAL, KeyBinding.ofCode(KeyEvent.VK_ESCAPE, 0, "enter.normal"), "enter.normal");

        // VISUAL LINE モード（行単位選択）
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('h', "cursor.left"), "cursor.left");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('l', "cursor.right"), "cursor.right");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('j', "cursor.down"), "cursor.down");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('k', "cursor.up"), "cursor.up");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('y', "yank"), "yank");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('d', "delete"), "delete");
        bind(Mode.VISUAL_LINE, KeyBinding.ofCode(KeyEvent.VK_ESCAPE, 0, "enter.normal"), "enter.normal");
    }
}
