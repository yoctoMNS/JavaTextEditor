package dev.javatexteditor.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 単純名（例: "List"）からインポート候補 FQN を返す。
 * JDK クラスインデックスに基いて候補を絞り、重複・既インポート済みを除外する。
 */
public class ImportSuggester {

    private final JdkClassIndex jdkIndex;

    public ImportSuggester(JdkClassIndex jdkIndex) {
        this.jdkIndex = jdkIndex;
    }

    /**
     * 単純名に対する FQN 候補リストを返す。
     * JDK インデックスが未完了なら空リスト。
     */
    public List<String> suggest(String simpleName) {
        return jdkIndex.lookup(simpleName);
    }

    /**
     * 候補リストから既インポート済みのものを除く。
     */
    public List<String> suggestNew(String simpleName, SourceIndex index) {
        List<String> candidates = suggest(simpleName);
        if (candidates.isEmpty()) return Collections.emptyList();

        List<String> alreadyImported = index.imports().stream()
            .map(ImportEntry::fullyQualifiedName)
            .toList();

        List<String> result = new ArrayList<>();
        for (String fqn : candidates) {
            if (!alreadyImported.contains(fqn)) result.add(fqn);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 指定 FQN がすでにインポート済みか確認する。
     */
    public boolean alreadyImported(String fqn, SourceIndex index) {
        return index.imports().stream()
            .anyMatch(e -> e.fullyQualifiedName().equals(fqn));
    }
}
