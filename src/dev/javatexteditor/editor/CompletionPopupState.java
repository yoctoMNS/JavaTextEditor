package dev.javatexteditor.editor;

import dev.javatexteditor.analysis.CompletionItem;
import java.util.List;

/**
 * 入力補完ポップアップ（Ctrl+Space のシンボル補完 / Alt+/ の単語補完）が
 * 「いま開いているか・何を候補として並べているか・何番目を選んでいるか」だけを保持するクラス。
 *
 * <p>候補をどうやって集めるか（{@code WordIndex}/{@code CompletionIndex} への問い合わせ）も、
 * 選んだ候補をどうバッファへ差し込むかも、ここには含まれない。
 * ポップアップの状態遷移だけを1箇所に閉じ込めることで、
 * {@code ModalEditor} 側から「5つのフィールドを毎回まとめて書き換える」記述が消える。
 *
 * <p>2つのトリガ（Ctrl+Space / Alt+/）はポップアップの見た目も操作も共通で、
 * 違いは「再クエリ時にどちらの索引を引き直すか」だけである。
 * それを {@link #isWordMode()} の1つのフラグで表す。
 */
final class CompletionPopupState {

    private boolean active = false;
    private List<CompletionItem> items = List.of();
    private int selectedIdx = 0;
    private String prefix = "";
    private boolean wordMode = false;

    /**
     * 候補リストでポップアップを開き直す（既に開いていれば差し替える）。選択は先頭へ戻る。
     *
     * @param wordMode true なら Alt+/ の単語補完。再クエリ時に WordIndex だけを引く。
     */
    void openWith(String prefix, List<CompletionItem> items, boolean wordMode) {
        this.prefix = prefix;
        this.items = items;
        this.selectedIdx = 0;
        this.active = true;
        this.wordMode = wordMode;
    }

    /** ポップアップを閉じ、候補を破棄する。既に閉じていれば false を返す。 */
    boolean close() {
        if (!active) return false;
        active = false;
        items = List.of();
        prefix = "";
        wordMode = false;
        return true;
    }

    boolean isActive() {
        return active;
    }

    /** 開いているが候補が1件も無い状態（表示すべきものが無い）かどうか。 */
    boolean hasNoVisibleItems() {
        return !active || items.isEmpty();
    }

    boolean isWordMode() {
        return wordMode;
    }

    String prefix() {
        return prefix;
    }

    List<CompletionItem> items() {
        return items;
    }

    int selectedIdx() {
        return selectedIdx;
    }

    /** 現在選択中の候補。開いていない・候補が空なら null。 */
    CompletionItem selectedItem() {
        if (hasNoVisibleItems()) return null;
        return items.get(selectedIdx);
    }

    /** 次の候補へ。末尾では末尾に留まる（循環しない）。 */
    void selectNext() {
        selectedIdx = Math.min(selectedIdx + 1, items.size() - 1);
    }

    /** 前の候補へ。先頭では先頭に留まる（循環しない）。 */
    void selectPrevious() {
        selectedIdx = Math.max(selectedIdx - 1, 0);
    }
}
