package dev.javatexteditor.telescope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * F11: public static void main(String[]) を持つクラスが複数見つかった場合に、
 * どれを実行するか選ばせるための TelescopePicker 実装。
 * ファイルを開く用途ではないため、各 TelescopeItem の filePath は常に null にする
 * （ModalEditor 側は telescopePurpose == RUN_MAIN_CLASS のとき item.display() を FQCN として扱う）。
 */
public class MainClassPicker implements TelescopePicker {

    private final List<TelescopeItem> allItems;

    public MainClassPicker(List<String> fqcns) {
        List<TelescopeItem> items = new ArrayList<>(fqcns.size());
        for (String fqcn : fqcns) {
            items.add(new TelescopeItem(fqcn, null, -1, 0));
        }
        this.allItems = items;
    }

    @Override
    public String title() { return "Select Main Class to Run"; }

    @Override
    public List<TelescopeItem> filter(String query) {
        if (query.isEmpty()) return List.copyOf(allItems);
        List<TelescopeItem> result = new ArrayList<>();
        for (TelescopeItem item : allItems) {
            FuzzyMatcher.MatchResult m = FuzzyMatcher.match(query, item.display());
            if (m.matched()) result.add(item.withScore(m.score()));
        }
        result.sort(Comparator.comparingInt(TelescopeItem::score).reversed());
        return result;
    }

    @Override
    public String preview(TelescopeItem item) {
        return "public static void main(String[] args)\n\n" + item.display();
    }
}
