package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * モード別キーバインド管理。
 * キーは "keyChar:modifiers" または "VKkeyCode:modifiers" 形式の文字列。
 * アクション名（String）で処理を指定し、ModalEditor で Runnable に変換する。
 */
public class KeymapRegistry {
    public enum Mode { NORMAL, INSERT, COMMAND, VISUAL, VISUAL_LINE, VISUAL_BLOCK }

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
        bind(Mode.NORMAL, KeyBinding.ofChar(';', "enter.command"), "enter.command"); // ; → : (like Vim)
        bind(Mode.NORMAL, KeyBinding.ofChar('u', "undo"), "undo");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK, "redo"), "redo");
        bind(Mode.NORMAL, KeyBinding.ofChar('~', "case.toggle.char"), "case.toggle.char");
        bind(Mode.NORMAL, KeyBinding.ofChar('r', "replace.char.pending"), "replace.char.pending");
        bind(Mode.NORMAL, KeyBinding.ofChar('v', "enter.visual"), "enter.visual");
        bind(Mode.NORMAL, KeyBinding.ofChar('V', "enter.visual.line"), "enter.visual.line");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK, "enter.visual.block"), "enter.visual.block");
        bind(Mode.NORMAL, KeyBinding.ofChar('x', "delete.char"), "delete.char");
        bind(Mode.NORMAL, KeyBinding.ofChar('p', "paste.after"), "paste.after");
        bind(Mode.NORMAL, KeyBinding.ofChar('P', "paste.before"), "paste.before");
        bind(Mode.NORMAL, KeyBinding.ofChar('y', "yank.pending"), "yank.pending");
        bind(Mode.NORMAL, KeyBinding.ofChar('d', "delete.pending"), "delete.pending");
        // マクロ記録(q)・再生(@)
        bind(Mode.NORMAL, KeyBinding.ofChar('q', "macro.record.pending"), "macro.record.pending");
        bind(Mode.NORMAL, KeyBinding.ofChar('@', "macro.play.pending"),   "macro.play.pending");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_K, KeyEvent.SHIFT_DOWN_MASK, "jdk.doc"), "jdk.doc");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_J, KeyEvent.SHIFT_DOWN_MASK, "jump.back"), "jump.back");
        // %: 対応する括弧へジャンプ（(), [], {} のネストをスタック相当で解決）
        bind(Mode.NORMAL, KeyBinding.ofChar('%', "motion.match.pair"), "motion.match.pair");
        // 単語移動
        bind(Mode.NORMAL, KeyBinding.ofChar('w', "word.forward"),  "word.forward");
        bind(Mode.NORMAL, KeyBinding.ofChar('b', "word.backward"), "word.backward");
        bind(Mode.NORMAL, KeyBinding.ofChar('e', "word.end"),      "word.end");
        // 行頭・行末（0=絶対行頭, ^=最初の非空白, $=行末）
        bind(Mode.NORMAL, KeyBinding.ofChar('0', "line.start"),           "line.start");
        bind(Mode.NORMAL, KeyBinding.ofChar('^', "line.start.nonblank"),  "line.start.nonblank");
        bind(Mode.NORMAL, KeyBinding.ofChar('$', "line.end"),             "line.end");
        // ファイル末尾（先頭は gg シーケンス）
        bind(Mode.NORMAL, KeyBinding.ofChar('G', "file.end"),    "file.end");
        bind(Mode.NORMAL, KeyBinding.ofChar('g', "goto.pending"),  "goto.pending");
        // \ をリーダーキーとして使用（\f=ファイル名検索, \g=ファイル内容grep）
        bind(Mode.NORMAL, KeyBinding.ofChar('\\', "filesearch.pending"),                   "filesearch.pending");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_BACK_SLASH, 0, "filesearch.pending"), "filesearch.pending");

        // 文字列検索 (Vim 式: / で入力、n/N で移動、*/# で単語検索)
        bind(Mode.NORMAL, KeyBinding.ofChar('/', "search.enter"),    "search.enter");
        bind(Mode.NORMAL, KeyBinding.ofChar('n', "search.next"),     "search.next");
        bind(Mode.NORMAL, KeyBinding.ofChar('N', "search.prev"),     "search.prev");
        bind(Mode.NORMAL, KeyBinding.ofChar('*', "search.star"),     "search.star");
        bind(Mode.NORMAL, KeyBinding.ofChar('#', "search.hash"),     "search.hash");

        // ページスクロール（Ctrl+F/B=1ページ, Ctrl+D/U=半ページ, Ctrl+E/Y=1行）
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK, "scroll.page.down"),  "scroll.page.down");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK, "scroll.page.up"),    "scroll.page.up");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_D, KeyEvent.CTRL_DOWN_MASK, "scroll.half.down"),  "scroll.half.down");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_U, KeyEvent.CTRL_DOWN_MASK, "buffer.prev"), "buffer.prev");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK, "buffer.next"), "buffer.next");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_E, KeyEvent.CTRL_DOWN_MASK, "scroll.line.down"),  "scroll.line.down");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK, "scroll.line.up"),    "scroll.line.up");
        // 画面内ジャンプ（H=先頭行, M=中央行, L=末尾行）
        bind(Mode.NORMAL, KeyBinding.ofChar('H', "screen.top"),    "screen.top");
        bind(Mode.NORMAL, KeyBinding.ofChar('M', "screen.middle"), "screen.middle");
        bind(Mode.NORMAL, KeyBinding.ofChar('L', "screen.bottom"), "screen.bottom");
        // [ をプレフィックスとして使用（[g=次の診断, [d=前の診断）
        bind(Mode.NORMAL, KeyBinding.ofChar('[', "diag.pending"), "diag.pending");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_OPEN_BRACKET, 0, "diag.pending"), "diag.pending");
        // s: ofChar と ofCode 両方登録（KEY_PRESSED で keyChar が未定義になる環境に対応）
        bind(Mode.NORMAL, KeyBinding.ofChar('s', "split.pending"), "split.pending");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_S, 0, "split.pending"), "split.pending");
        // Space をリーダーキーとして使う（Space+h/l/j/k）
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_SPACE, 0, "leader.pending"), "leader.pending");
        // 行の入れ替え（Alt+J / Alt+K）
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_J, KeyEvent.ALT_DOWN_MASK, "line.swap.down"), "line.swap.down");
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_K, KeyEvent.ALT_DOWN_MASK, "line.swap.up"),   "line.swap.up");
        // import の整理（Eclipse: Ctrl+Shift+O）
        bind(Mode.NORMAL, KeyBinding.ofCode(KeyEvent.VK_O,
                KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK,
                "organize.imports"), "organize.imports");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_O,
                KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK,
                "organize.imports"), "organize.imports");

        // INSERT モード
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_ESCAPE, 0, "enter.normal"), "enter.normal");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK, "cursor.right"), "cursor.right");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK, "cursor.left"), "cursor.left");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_N, KeyEvent.CTRL_DOWN_MASK, "cursor.down"), "cursor.down");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK, "cursor.up"), "cursor.up");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_BACK_SPACE, 0, "delete.before"), "delete.before");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_ENTER, 0, "insert.newline"), "insert.newline");
        // Shift+Enter も通常の Enter と同じ改行として扱う（Shift 修飾があると
        // VK_ENTER:0 のバインドに一致せず何も入力できなくなっていた不具合の修正）。
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK, "insert.newline"), "insert.newline");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_TAB, 0, "insert.tab"), "insert.tab");
        // INSERT → NORMAL + 保存（Ctrl+] / Ctrl+[）
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_CLOSE_BRACKET, KeyEvent.CTRL_DOWN_MASK, "save.from.insert"), "save.from.insert");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_OPEN_BRACKET,  KeyEvent.CTRL_DOWN_MASK, "save.from.insert"), "save.from.insert");
        // 文字削除（Ctrl+D → 次を削除, Ctrl+K → 行末まで削除）
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_D, KeyEvent.CTRL_DOWN_MASK, "delete.next"),       "delete.next");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK, "delete.to.eol"),     "delete.to.eol");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_W, KeyEvent.CTRL_DOWN_MASK, "delete.word.before"), "delete.word.before");
        // Emacs 単語移動（Alt+F / Alt+B）
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_F, KeyEvent.ALT_DOWN_MASK, "word.forward"),  "word.forward");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_B, KeyEvent.ALT_DOWN_MASK, "word.backward"), "word.backward");
        // Emacs 行頭・行末（Ctrl+A / Ctrl+E）
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK, "line.start"), "line.start");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_E, KeyEvent.CTRL_DOWN_MASK, "line.end"),   "line.end");
        // Emacs ファイル先頭・末尾（Ctrl+Home / Ctrl+End）
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_HOME, KeyEvent.CTRL_DOWN_MASK, "file.start"), "file.start");
        bind(Mode.INSERT, KeyBinding.ofCode(KeyEvent.VK_END,  KeyEvent.CTRL_DOWN_MASK, "file.end"),   "file.end");

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
        bind(Mode.VISUAL, KeyBinding.ofChar('v', "enter.normal"), "enter.normal");
        bind(Mode.VISUAL, KeyBinding.ofChar('w', "word.forward"),  "word.forward");
        bind(Mode.VISUAL, KeyBinding.ofChar('b', "word.backward"), "word.backward");
        bind(Mode.VISUAL, KeyBinding.ofChar('e', "word.end"),      "word.end");
        bind(Mode.VISUAL, KeyBinding.ofChar('0', "line.start"),          "line.start");
        bind(Mode.VISUAL, KeyBinding.ofChar('^', "line.start.nonblank"), "line.start.nonblank");
        bind(Mode.VISUAL, KeyBinding.ofChar('$', "line.end"),            "line.end");
        bind(Mode.VISUAL, KeyBinding.ofChar('G', "file.end"),   "file.end");
        bind(Mode.VISUAL, KeyBinding.ofCode(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK, "scroll.page.down"), "scroll.page.down");
        bind(Mode.VISUAL, KeyBinding.ofCode(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK, "scroll.page.up"),   "scroll.page.up");
        bind(Mode.VISUAL, KeyBinding.ofCode(KeyEvent.VK_D, KeyEvent.CTRL_DOWN_MASK, "scroll.half.down"), "scroll.half.down");
        bind(Mode.VISUAL, KeyBinding.ofCode(KeyEvent.VK_U, KeyEvent.CTRL_DOWN_MASK, "scroll.half.up"),   "scroll.half.up");
        bind(Mode.VISUAL, KeyBinding.ofChar('%', "motion.match.pair"), "motion.match.pair");
        bind(Mode.VISUAL, KeyBinding.ofChar('>', "indent.right"), "indent.right");
        bind(Mode.VISUAL, KeyBinding.ofChar('<', "indent.left"),  "indent.left");
        bind(Mode.VISUAL, KeyBinding.ofChar('u', "case.lower"),  "case.lower");
        bind(Mode.VISUAL, KeyBinding.ofChar('U', "case.upper"),  "case.upper");
        bind(Mode.VISUAL, KeyBinding.ofChar('~', "case.toggle"), "case.toggle");
        bind(Mode.VISUAL, KeyBinding.ofChar(':', "enter.command.visual"), "enter.command.visual");
        bind(Mode.VISUAL, KeyBinding.ofChar(';', "enter.command.visual"), "enter.command.visual"); // ; → : (like Vim)

        // VISUAL LINE モード（行単位選択）
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('h', "cursor.left"), "cursor.left");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('l', "cursor.right"), "cursor.right");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('j', "cursor.down"), "cursor.down");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('k', "cursor.up"), "cursor.up");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('y', "yank"), "yank");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('d', "delete"), "delete");
        bind(Mode.VISUAL_LINE, KeyBinding.ofCode(KeyEvent.VK_ESCAPE, 0, "enter.normal"), "enter.normal");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('V', "enter.normal"), "enter.normal");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('G', "file.end"), "file.end");
        bind(Mode.VISUAL_LINE, KeyBinding.ofCode(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK, "scroll.page.down"), "scroll.page.down");
        bind(Mode.VISUAL_LINE, KeyBinding.ofCode(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK, "scroll.page.up"),   "scroll.page.up");
        bind(Mode.VISUAL_LINE, KeyBinding.ofCode(KeyEvent.VK_D, KeyEvent.CTRL_DOWN_MASK, "scroll.half.down"), "scroll.half.down");
        bind(Mode.VISUAL_LINE, KeyBinding.ofCode(KeyEvent.VK_U, KeyEvent.CTRL_DOWN_MASK, "scroll.half.up"),   "scroll.half.up");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('%', "motion.match.pair"), "motion.match.pair");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('>', "indent.right"), "indent.right");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('<', "indent.left"),  "indent.left");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('u', "case.lower"),  "case.lower");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('U', "case.upper"),  "case.upper");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar('~', "case.toggle"), "case.toggle");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar(':', "enter.command.visual"), "enter.command.visual");
        bind(Mode.VISUAL_LINE, KeyBinding.ofChar(';', "enter.command.visual"), "enter.command.visual"); // ; → : (like Vim)

        // VISUAL BLOCK モード（矩形選択、Ctrl+V）
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('h', "cursor.left"), "cursor.left");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('l', "cursor.right"), "cursor.right");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('j', "cursor.down"), "cursor.down");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('k', "cursor.up"), "cursor.up");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('y', "yank"), "yank");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('d', "delete"), "delete");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofCode(KeyEvent.VK_ESCAPE, 0, "enter.normal"), "enter.normal");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofCode(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK, "enter.normal"), "enter.normal");
        // 矩形挿入・変更・置換（I=左端挿入, A=右端挿入, c=変更, r=文字置換）
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('I', "block.insert.before"), "block.insert.before");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('A', "block.insert.after"),  "block.insert.after");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('c', "block.change"),       "block.change");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('r', "block.replace.pending"), "block.replace.pending");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('%', "motion.match.pair"), "motion.match.pair");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('>', "indent.right"), "indent.right");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('<', "indent.left"),  "indent.left");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('u', "case.lower"),  "case.lower");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('U', "case.upper"),  "case.upper");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar('~', "case.toggle"), "case.toggle");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar(':', "enter.command.visual"), "enter.command.visual");
        bind(Mode.VISUAL_BLOCK, KeyBinding.ofChar(';', "enter.command.visual"), "enter.command.visual"); // ; → : (like Vim)
    }
}
