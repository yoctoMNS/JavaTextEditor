package dev.javatexteditor.telescope;

import java.util.ArrayList;
import java.util.List;

/**
 * SPC+b: 現在開いているバッファ（ファイル）一覧を表示する。
 * バッファリストは ModalEditor 経由で Main.java から渡される。
 */
public class BufferPicker implements TelescopePicker {

    /** (表示名, 絶対パス or null) のペア。nullパスはファイルなし疑似バッファ。 */
    public record BufferEntry(String name, String filePath) {}

    private final List<BufferEntry> entries;

    public BufferPicker(List<BufferEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    @Override
    public String title() { return "Buffers"; }

    @Override
    public List<TelescopeItem> filter(String query) {
        List<TelescopeItem> result = new ArrayList<>();
        for (BufferEntry e : entries) {
            if (query.isEmpty()) {
                result.add(new TelescopeItem(e.name(), e.filePath(), 0, 0));
            } else {
                FuzzyMatcher.MatchResult m = FuzzyMatcher.match(query, e.name());
                if (m.matched()) {
                    result.add(new TelescopeItem(e.name(), e.filePath(), 0, m.score()));
                }
            }
        }
        if (!query.isEmpty()) {
            result.sort(java.util.Comparator.comparingInt(TelescopeItem::score).reversed());
        }
        return result.size() <= MAX_RESULTS ? result : result.subList(0, MAX_RESULTS);
    }
}
