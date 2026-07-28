package dev.javatexteditor.app;

import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import javax.swing.JFrame;

/**
 * モードに依存しないグローバルキー処理（ペイン切替と無関係な単一の {@link JFrame} 全体に
 * かかるキー）を一手に引き受ける {@link KeyEventDispatcher}。
 *
 * <p>旧 {@code Main.main()} の {@code invokeLater} 内に直書きされていたラムダ（約105行）を
 * 本文を一切変えずに移した（MAIN_DECOMPOSITION_PLAN.md 段階7、docs/STAGE7_PLAN.md 7-1）。
 *
 * <h2>{@code pressedHandled} を配列からフィールドへ変換した理由</h2>
 *
 * <p>旧実装は {@code boolean[] pressedHandled = { false };} という「要素1個の配列＝
 * 書き換え可能な箱」を使っていた（ラムダは外側のローカル変数を再代入できないための回避策。
 * 段階6の {@code root[0]}/{@code active[0]} と同種のパターン）。本クラスは
 * {@link KeyboardFocusManager#addKeyEventDispatcher(KeyEventDispatcher)} に
 * <b>1つのインスタンスだけ</b>が登録され、{@link #dispatchKeyEvent(KeyEvent)} は
 * そのインスタンスに対して繰り返し呼ばれる。したがって配列という回避策は不要で、
 * 単純な {@code private boolean pressedHandled} フィールドに置き換えるだけで
 * 同じ意味論（=1つの可変状態を複数回の呼び出しにまたがって保持する）を実現できる。
 *
 * <h2>{@code pressedHandled} の役割（IMEとの関係）</h2>
 *
 * <p>{@code KEY_PRESSED} で {@code ModalEditor.processKey()} を呼んで処理したキーは、
 * 同じキー入力に対して後から届く {@code KEY_TYPED} でも重複して届く。INSERT/COMMANDモードで
 * 印字可能文字（Ctrl/Altなし）は IME（日本語入力等）に委譲するため {@code KEY_PRESSED} 側では
 * 何もせず {@code false} を返すが、この場合 IME が確定した文字は {@code KEY_TYPED} で届く。
 * {@code pressedHandled} は「{@code KEY_PRESSED} で既に処理済みのキーを {@code KEY_TYPED} で
 * 二重処理しない」ためのフラグであり、これが壊れると日本語入力で1文字のはずが2文字挿入される
 * （またはASCII入力が二重に挿入される）といった不具合になる。段階7の手動検証（IME確認）は
 * この意味論が移設後も保たれていることを確認するためのもの（docs/STAGE7_PLAN.md §4参照）。
 */
public final class GlobalKeyDispatcher implements KeyEventDispatcher {

    private final JFrame frame;
    private final PaneManager panes;
    private final JavaBuildRunner javaBuildRunner;
    private final CBuildRunner cBuildRunner;

    // KEY_PRESSEDで processKey を呼んだキーは KEY_TYPED でも届くため、
    // 二重処理を防ぐためにフラグで管理する（旧 boolean[] pressedHandled）。
    private boolean pressedHandled = false;

    public GlobalKeyDispatcher(JFrame frame, PaneManager panes,
                                JavaBuildRunner javaBuildRunner, CBuildRunner cBuildRunner) {
        this.frame = frame;
        this.panes = panes;
        this.javaBuildRunner = javaBuildRunner;
        this.cBuildRunner = cBuildRunner;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // モーダルダイアログが前面にある場合はエディタのキー処理をスキップする
        java.awt.Window focused = KeyboardFocusManager
            .getCurrentKeyboardFocusManager().getFocusedWindow();
        if (focused != frame) return false;

        if (e.getID() == KeyEvent.KEY_PRESSED) {
            pressedHandled = false;

            // Ctrl+Shift+矢印: アクティブペインのビットマップフォントセルサイズを変更
            boolean ctrl  = (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK)  != 0;
            boolean shift = (e.getModifiersEx() & KeyEvent.SHIFT_DOWN_MASK) != 0;
            if (ctrl && shift) {
                int kc = e.getKeyCode();
                if (kc == KeyEvent.VK_RIGHT) {
                    panes.active().canvas().adjustCellWidth(+1);
                    pressedHandled = true; return true;
                } else if (kc == KeyEvent.VK_LEFT) {
                    panes.active().canvas().adjustCellWidth(-1);
                    pressedHandled = true; return true;
                } else if (kc == KeyEvent.VK_DOWN) {
                    panes.active().canvas().adjustCellHeight(+1);
                    pressedHandled = true; return true;
                } else if (kc == KeyEvent.VK_UP) {
                    panes.active().canvas().adjustCellHeight(-1);
                    pressedHandled = true; return true;
                }
            }

            // Ctrl+Alt+矢印: 画面分割中、アクティブペインの縦横幅を伸縮する
            boolean alt = (e.getModifiersEx() & KeyEvent.ALT_DOWN_MASK) != 0;
            if (ctrl && alt && !shift) {
                int kc = e.getKeyCode();
                if (kc == KeyEvent.VK_LEFT || kc == KeyEvent.VK_RIGHT
                        || kc == KeyEvent.VK_UP || kc == KeyEvent.VK_DOWN) {
                    panes.resizeActivePane(kc);
                    pressedHandled = true; return true;
                }
            }

            // F2: カーソル行の診断をモーダルダイアログで表示
            if (e.getKeyCode() == KeyEvent.VK_F2) {
                DiagnosticPopup.showForCursorRow(
                    frame, panes.active().editor(), panes.active().canvas());
                pressedHandled = true;
                return true;
            }

            // F10/F11/F12: プロジェクト全体のコンパイル・実行（NORMALモードのみ）
            if (e.getKeyCode() == KeyEvent.VK_F10
                    || e.getKeyCode() == KeyEvent.VK_F11
                    || e.getKeyCode() == KeyEvent.VK_F12) {
                dev.javatexteditor.editor.ModalEditor edBuild = panes.active().editor();
                if (edBuild.isNormalMode()) {
                    boolean c = LiveDiagnostics.isCBuffer(edBuild);
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_F10 -> { if (c) cBuildRunner.triggerCompile(edBuild); else javaBuildRunner.triggerCompile(edBuild); }
                        case KeyEvent.VK_F11 -> { if (c) cBuildRunner.triggerRun(edBuild); else javaBuildRunner.triggerRun(edBuild); }
                        case KeyEvent.VK_F12 -> { if (c) cBuildRunner.triggerCompileAndRun(edBuild); else javaBuildRunner.triggerCompileAndRun(edBuild); }
                    }
                }
                pressedHandled = true;
                return true;
            }

            // INSERT/COMMANDモードで印字可能文字（Ctrl/Altなし）はIMEに委譲する。
            // IMEがコミットした文字は KEY_TYPED で受け取る。
            boolean noCtrlAlt = (e.getModifiersEx() &
                (KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK)) == 0;
            char kc2 = e.getKeyChar();
            boolean isPrintable = kc2 != KeyEvent.CHAR_UNDEFINED && kc2 >= ' ';
            dev.javatexteditor.editor.ModalEditor ed = panes.active().editor();
            if (noCtrlAlt && isPrintable &&
                    (ed.isInsertMode() || ed.isCommandMode())) {
                return false; // IMEに委譲（pressedHandled は false のまま）
            }

            ed.processKey(e.getKeyCode(), e.getKeyChar(), e.getModifiersEx());
            panes.updateBorders();
            pressedHandled = true; // KEY_TYPED で二重処理しないようにマーク
            return true;
        }

        // KEY_TYPED: IMEがコミットした文字（日本語など）をINSERT/COMMANDモードで処理する。
        // KEY_PRESSEDで既に処理したキーは無視する（';'→COMMMANDモードへの遷移後に
        // KEY_TYPED の';'がコマンドバッファに追記される問題を防ぐ）。
        if (e.getID() == KeyEvent.KEY_TYPED) {
            if (pressedHandled) {
                pressedHandled = false;
                return false;
            }
            char ch = e.getKeyChar();
            dev.javatexteditor.editor.ModalEditor ed = panes.active().editor();
            if (ch != KeyEvent.CHAR_UNDEFINED && ch >= ' ' &&
                    (ed.isInsertMode() || ed.isCommandMode())) {
                ed.processKey(0, ch, 0);
                panes.updateBorders();
                return true;
            }
        }

        return false;
    }
}
