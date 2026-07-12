package dev.javatexteditor.analysis;

import dev.javatexteditor.search.ProjectSearcher;
import dev.javatexteditor.search.SearchResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * プロジェクト内（作業ディレクトリ配下）で宣言された class/interface/enum/record から
 * 単純名に対する FQN 候補を検索する（auto-import の候補ソース）。
 * JdkClassIndex と異なりディスクを都度検索するため、新規作成・別パッケージのファイルも
 * インデックス再構築なしにその場で候補に反映される。
 */
public class ProjectClassSuggester {

    private static final Pattern PACKAGE_PATTERN =
        Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private final ProjectSearcher searcher = new ProjectSearcher();

    /**
     * simpleName を宣言しているトップレベルの public class/interface/enum/record を
     * baseDir 配下から探し、そのファイルの package 宣言と組み合わせた FQN を返す。
     * Java の「public トップレベル型はファイル名と一致する」慣例を利用し、
     * ファイル名が simpleName と一致するものだけを対象にする（内部クラス等は対象外）。
     */
    public List<String> suggest(Path baseDir, String simpleName) {
        if (baseDir == null || simpleName == null || simpleName.isBlank()) return List.of();

        String pattern = "\\b(?:class|interface|enum|record)\\s+" + Pattern.quote(simpleName) + "\\b";
        List<SearchResult> matches;
        try {
            matches = searcher.search(baseDir, pattern);
        } catch (Exception e) {
            return List.of();
        }

        LinkedHashSet<String> fqns = new LinkedHashSet<>();
        for (SearchResult r : matches) {
            if (!r.filePath().endsWith(".java")) continue;
            Path abs = baseDir.resolve(r.filePath());
            if (!fileSimpleName(abs).equals(simpleName)) continue;
            String content;
            try {
                content = Files.readString(abs);
            } catch (IOException e) {
                continue;
            }
            Matcher pm = PACKAGE_PATTERN.matcher(content);
            fqns.add(pm.find() ? pm.group(1) + "." + simpleName : simpleName);
        }
        return List.copyOf(fqns);
    }

    private String fileSimpleName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }
}
