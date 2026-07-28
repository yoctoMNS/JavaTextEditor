package dev.javatexteditor.app;

import dev.javatexteditor.buffer.UndoablePieceTable;
import dev.javatexteditor.editor.ModalEditor;
import java.util.List;

/**
 * {@link ModalEditor} から「外の世界（ペイン管理）」へ伝える手段をまとめたポート
 * （MAIN_DECOMPOSITION_PLAN.md 段階6 §6.3、docs/STAGE6_OPTION_C_PLAN.md 段階6-3）。
 *
 * <p><b>本段階（6-3）では追加のみで、まだ何も置き換えていない</b>。{@code ModalEditor} は
 * 引き続き {@code setSplitHorizontalCallback}/{@code setExitCallback}/{@code setCloseBlockedCallback}/
 * {@code setMovePanePrevCallback}/{@code setMovePaneNextCallback}/{@code setAllEditorsSupplier}/
 * {@code setLiveBufferLookup}/{@code setOnSharedBufferSync} という8個の setter/supplier/function を
 * 個別に受け取る現行方式のまま動作する。{@link PaneManager} がこのインタフェースを実装した
 * （= 各メソッドを呼べば同じ操作ができる状態にした）だけであり、{@code ModalEditor} 側を
 * {@code setHost(EditorHost)} 1本に統一する配線の置き換えは後続のサブ段階（6-4・6-5）で行う。
 *
 * <p><b>親計画書 §6.3 のインタフェース案との差分</b>: 親計画書のスケッチは
 * {@code closePane()}/{@code onCloseBlocked()} という名前だったが、実際の {@code ModalEditor}
 * 側のsetterは {@code setExitCallback}/{@code setCloseBlockedCallback} である（{@code setClosePaneCallback}/
 * {@code setOnCloseBlocked} という名前のsetterは存在しない）。本インタフェースのメソッド名は
 * 親計画書のスケッチ（ポート側の命名）をそのまま踏襲しつつ、実装（{@link PaneManager}）側で
 * 実在するsetter名へ対応付ける。
 */
public interface EditorHost {

    /** {@code s v}: アクティブペインを左右分割する。 */
    void splitHorizontal();

    /** {@code s s}: アクティブペインを上下分割する。 */
    void splitVertical();

    /** {@code :q}: アクティブペインを閉じる（ペインが1つだけならアプリ全体を終了する）。 */
    void closePane();

    /**
     * ペインを閉じられない事情がある場合に呼ばれる（{@code ModalEditor.closeBlockedCallback} 相当）。
     * 現状この経路を実際に使う呼び出し元は無い（:q は常にペイン1つならアプリ終了、複数ならアクティブ
     * ペインを閉じる、という無条件の実装のため）。将来 :q を拒否する条件が追加された場合の受け口として
     * インタフェースにだけ用意してある。
     */
    void onCloseBlocked();

    /** {@code s h}/{@code s k}: 前のペインへフォーカスを移す。 */
    void moveToPrevPane();

    /** {@code s l}/{@code s j}: 次のペインへフォーカスを移す。 */
    void moveToNextPane();

    /** 現在開いている全ペインのエディタ一覧（{@code :wa}/{@code :qa}/{@code :qa!} の対象決定に使う）。 */
    List<ModalEditor> allEditors();

    /**
     * Vim方式の共有バッファ: {@code absolutePath} を {@code currentFilePath} として持つ生きたペインが
     * あれば、そのペインが参照する {@link UndoablePieceTable} を返す（無ければ {@code null}）。
     */
    UndoablePieceTable findLiveBuffer(String absolutePath);

    /**
     * {@code source} と同じバッファ参照を表示している他ペインへ、カーソル位置を再クランプしつつ
     * 画面を再描画させる。
     */
    void syncSiblingBuffers(ModalEditor source);
}
