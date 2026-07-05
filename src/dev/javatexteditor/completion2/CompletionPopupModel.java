package dev.javatexteditor.completion2;

import javax.swing.AbstractListModel;
import java.util.List;

/**
 * 候補一覧ポップアップ（JList）用のUI側モデル。
 * 補完ロジック（CompletionSession/Engine）からは独立しており、update() で
 * 現在の候補一覧と選択インデックスを渡されるだけの薄いアダプタ。
 */
public final class CompletionPopupModel extends AbstractListModel<CompletionCandidate> {

    private List<CompletionCandidate> candidates = List.of();
    private int selectedIndex = -1;

    public void update(List<CompletionCandidate> newCandidates, int newSelectedIndex) {
        int oldSize = candidates.size();
        this.candidates = List.copyOf(newCandidates);
        this.selectedIndex = newSelectedIndex;
        if (oldSize > 0) {
            fireIntervalRemoved(this, 0, oldSize - 1);
        }
        if (!candidates.isEmpty()) {
            fireIntervalAdded(this, 0, candidates.size() - 1);
        }
    }

    public void clear() {
        update(List.of(), -1);
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    @Override
    public int getSize() {
        return candidates.size();
    }

    @Override
    public CompletionCandidate getElementAt(int index) {
        return candidates.get(index);
    }
}
