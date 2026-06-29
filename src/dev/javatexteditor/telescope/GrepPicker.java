package dev.javatexteditor.telescope;

import dev.javatexteditor.search.ProjectSearcher;
import dev.javatexteditor.search.SearchResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/**
 * SPC+/: ファイル内容をリアルタイムgrepする。
 * クエリが空のときは何も返さない。クエリが2文字以上になったら検索を実行する。
 */
public class GrepPicker implements TelescopePicker {

    private final Path baseDir;
    private final ProjectSearcher searcher = new ProjectSearcher();

    public GrepPicker(Path baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public String title() { return "Live Grep"; }

    @Override
    public List<TelescopeItem> filter(String query) {
        if (query.length() < 2) return List.of();

        List<SearchResult> raw;
        try {
            raw = searcher.search(baseDir, query);
        } catch (PatternSyntaxException e) {
            // treat as literal string on invalid regex
            try {
                raw = searcher.search(baseDir, java.util.regex.Pattern.quote(query));
            } catch (Exception ex) {
                return List.of();
            }
        }

        List<TelescopeItem> result = new ArrayList<>(Math.min(raw.size(), MAX_RESULTS));
        for (int i = 0; i < Math.min(raw.size(), MAX_RESULTS); i++) {
            SearchResult r = raw.get(i);
            String display = r.filePath() + ":" + r.lineNumber() + ": " + r.lineContent().stripTrailing();
            result.add(new TelescopeItem(display, r.filePath(), r.lineNumber() - 1, 0));
        }
        return result;
    }

    @Override
    public String preview(TelescopeItem item) {
        if (item.filePath() == null) return "";
        try {
            Path p = Path.of(item.filePath());
            if (!Files.isRegularFile(p) || Files.size(p) > 1_000_000) return "(binary or too large)";
            List<String> lines = Files.readAllLines(p);
            int center = Math.max(0, item.lineNumber());
            int start = Math.max(0, center - 5);
            int end = Math.min(lines.size(), start + 40);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                if (i == item.lineNumber()) sb.append("▸ ");
                else sb.append("  ");
                sb.append(i + 1).append(": ").append(lines.get(i)).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return "(preview unavailable)";
        }
    }
}
