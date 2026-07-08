package dev.javatexteditor.telescope;

import dev.javatexteditor.search.FileNameSearcher;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * SPC+f: プロジェクト配下のファイル名をファジー検索する。
 * クエリ空のときは全ファイルをリスト表示する。
 */
public class FilePicker implements TelescopePicker {

    private final Path baseDir;
    private final FileNameSearcher searcher = new FileNameSearcher();
    /** 起動時に全ファイルをキャッシュ（ファジーはクライアント側でフィルタ）。 */
    private List<TelescopeItem> allItems = null;

    public FilePicker(Path baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public String title() { return "Find Files"; }

    @Override
    public List<TelescopeItem> filter(String query) {
        if (allItems == null) loadAll();

        if (query.isEmpty()) {
            return allItems.size() <= MAX_RESULTS
                ? List.copyOf(allItems)
                : allItems.subList(0, MAX_RESULTS);
        }

        List<TelescopeItem> result = new ArrayList<>();
        for (TelescopeItem item : allItems) {
            FuzzyMatcher.MatchResult m = FuzzyMatcher.match(query, item.display());
            if (m.matched()) result.add(item.withScore(m.score()));
        }
        result.sort(Comparator.comparingInt(TelescopeItem::score).reversed());
        return result.size() <= MAX_RESULTS ? result : result.subList(0, MAX_RESULTS);
    }

    private void loadAll() {
        try {
            List<Path> paths = searcher.search(baseDir, ".*");
            allItems = new ArrayList<>(paths.size());
            for (Path rel : paths) {
                String display = rel.toString().replace('\\', '/');
                Path abs = baseDir.resolve(rel);
                allItems.add(new TelescopeItem(display, abs.toString(), 0, 0));
            }
        } catch (Exception e) {
            allItems = List.of();
        }
    }
}
