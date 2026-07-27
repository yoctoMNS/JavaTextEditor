package dev.javatexteditor.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * ファイルパスを持たない疑似バッファ（{@code :enew}・{@code :tutor} 等）を Ctrl+U / Ctrl+P で
 * 行き来するための、ブラウザの「戻る・進む」と同じ形の履歴。
 *
 * <p>実ファイルを開いているバッファの切り替えは、これではなく
 * {@code ModalEditor.switchToRelativeBuffer()}（{@code :bnext}/{@code :bprev} 相当。
 * 全ペインで共有される BUFFER_REGISTRY を巡回する）が担当する。
 * どちらを使うかの判断は {@code ModalEditor} 側にある。
 *
 * <p>このクラスはスナップショットの並びと現在位置だけを管理し、
 * スナップショットの作り方・戻し方（PieceTable の再構築やカーソルの復元）は関与しない。
 */
final class BufferHistory {

    private final List<BufferSnapshot> snapshots = new ArrayList<>();

    /** 現在位置。-1 は未初期化（最初の記録がまだ無い状態）。 */
    private int currentIdx = -1;

    /** 履歴を最初の1件で初期化する。 */
    void initializeWith(BufferSnapshot snapshot) {
        snapshots.add(snapshot);
        currentIdx = 0;
    }

    /**
     * 現在位置に新しいスナップショットを積む。
     * ブラウザの履歴と同じく、現在位置より後ろ（＝「進む」で辿れた分）は捨てられる。
     */
    void push(BufferSnapshot snapshot) {
        if (currentIdx >= 0 && currentIdx < snapshots.size() - 1) {
            snapshots.subList(currentIdx + 1, snapshots.size()).clear();
        }
        snapshots.add(snapshot);
        currentIdx = snapshots.size() - 1;
    }

    boolean hasPrevious() {
        return currentIdx > 0;
    }

    boolean hasNext() {
        return currentIdx >= 0 && currentIdx < snapshots.size() - 1;
    }

    int previousIndex() {
        return currentIdx - 1;
    }

    int nextIndex() {
        return currentIdx + 1;
    }

    int size() {
        return snapshots.size();
    }

    /**
     * 現在編集中の状態を今のスロットへ書き戻した上で、{@code idx} のスナップショットを返す。
     *
     * <p>書き戻しを伴うのは、ある疑似バッファを編集してから Ctrl+U で離れ、Ctrl+P で戻ってきたときに
     * 編集内容が失われないようにするため。
     *
     * @param currentState 離れる直前の現在バッファの写し
     * @return 移動先のスナップショット
     */
    BufferSnapshot moveTo(int idx, BufferSnapshot currentState) {
        if (currentIdx >= 0 && currentIdx < snapshots.size()) {
            snapshots.set(currentIdx, currentState);
        }
        currentIdx = idx;
        return snapshots.get(idx);
    }
}
