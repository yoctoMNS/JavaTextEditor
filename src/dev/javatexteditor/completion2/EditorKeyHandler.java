package dev.javatexteditor.completion2;

import javax.swing.text.JTextComponent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/**
 * キー入力（Swing の KeyEvent）を {@link CompletionController} への呼び出しへ変換するだけの
 * 薄いアダプタ層。補完ロジック自体は一切持たない（UIとロジックの分離）。
 *
 * 実装上の注意（Alt/IME の環境差対策）:
 * <ul>
 *   <li>プラットフォームによっては Alt+/ の押下で KEY_PRESSED（VK_SLASH, altDown）に加えて
 *       別途 KEY_TYPED（'/'）が生成されることがある。KEY_PRESSED を消費(consume)しても
 *       KEY_TYPED は自動的には消費されないため、armed フラグで直後の KEY_TYPED('/') も
 *       明示的に消費し、テキストへの意図しない "/" 挿入を防ぐ。</li>
 *   <li>IME 変換中はキーイベント自体が IME に横取りされることがあるため、Alt を伴う
 *       キーストロークはIME変換の対象になりにくいものの、完全な回避を保証するものではない。
 *       環境によって Alt+/ が奪われる場合は、他のキーストロークへの割り当て変更を検討すること
 *       （このクラスは KeyStroke をハードコードしていないため、triggerKeyCode/altとの
 *       組合せは容易に差し替え可能）。</li>
 *   <li>矢印キー・PageUp/Down・マウスクリックによるカーソル移動は、ここでは何も処理しない。
 *       それらは JTextComponent のデフォルト処理でカーソルが実際に動いた後、
 *       CompletionController が自前で登録している CaretListener が検知して自動的に確定する。</li>
 * </ul>
 */
public final class EditorKeyHandler extends KeyAdapter {

    private final CompletionController controller;
    private boolean altSlashArmed;

    public EditorKeyHandler(CompletionController controller) {
        this.controller = controller;
    }

    /** 指定した JTextComponent にこのハンドラを登録する。 */
    public static EditorKeyHandler install(JTextComponent textComponent, CompletionController controller) {
        EditorKeyHandler handler = new EditorKeyHandler(controller);
        textComponent.addKeyListener(handler);
        return handler;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int code = e.getKeyCode();

        if (code == KeyEvent.VK_SLASH && e.isAltDown()) {
            if (e.isShiftDown()) {
                controller.triggerOrRetreat();
            } else {
                controller.triggerOrAdvance();
            }
            altSlashArmed = true;
            e.consume();
            return;
        }

        if (!controller.isActive()) {
            return;
        }

        switch (code) {
            case KeyEvent.VK_ESCAPE -> {
                controller.cancel();
                e.consume();
            }
            case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                controller.commit();
                e.consume();
            }
            default -> {
                // 矢印キー等はここでは何もしない。デフォルト処理でカーソルが動いた後、
                // CompletionController の CaretListener が確定処理を行う。
            }
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
        if (altSlashArmed && e.getKeyChar() == '/') {
            altSlashArmed = false;
            e.consume();
            return;
        }
        altSlashArmed = false;

        if (controller.isActive() && (e.getKeyChar() == '\n' || e.getKeyChar() == '\t')) {
            // keyPressed 側で既に commit 済みだが、環境によっては別途 KEY_TYPED が
            // 発生し改行/タブが二重に入力される場合があるため念のため消費する。
            e.consume();
        }
    }
}
