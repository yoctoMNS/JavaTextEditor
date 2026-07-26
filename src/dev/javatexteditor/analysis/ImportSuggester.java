package dev.javatexteditor.analysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 単純名（例: "List"）からインポート候補 FQN を返す。
 * JDK クラスインデックスに基いて候補を絞り、重複・既インポート済みを除外する。
 */
public class ImportSuggester {

    private final JdkClassIndex jdkIndex;
    private final ProjectClassSuggester projectClassSuggester = new ProjectClassSuggester();

    public ImportSuggester(JdkClassIndex jdkIndex) {
        this.jdkIndex = jdkIndex;
    }

    /** インポート文なしで常に参照できるパッケージ（インポート候補から除外する）。 */
    private static final String IMPLICIT_IMPORT_PACKAGE = "java.lang";

    /**
     * 単純名に対する FQN 候補リストを返す（JDK クラスのみ）。
     * JDK インデックスが未完了なら空リスト。
     * java.lang パッケージのクラスは import 文が不要なため候補から除外する。
     */
    public List<String> suggest(String simpleName) {
        return filterImportable(jdkIndex.lookup(simpleName));
    }

    /**
     * 単純名に対する FQN 候補リストを返す。JDK クラスに加え、baseDir 配下の
     * 自プロジェクトの class/interface/enum/record も候補に含める。
     * baseDir が null の場合は {@link #suggest(String)} と同じ（JDK のみ）。
     * java.lang パッケージのクラスは import 文が不要なため候補から除外する。
     */
    public List<String> suggest(String simpleName, Path baseDir) {
        LinkedHashSet<String> result = new LinkedHashSet<>(filterImportable(jdkIndex.lookup(simpleName)));
        if (baseDir != null) {
            result.addAll(projectClassSuggester.suggest(baseDir, simpleName));
        }
        return List.copyOf(result);
    }

    /**
     * java.lang パッケージ直下の FQN（import 不要）を候補から取り除く。
     * java.lang.reflect 等のサブパッケージは import が必要なため対象外にしない
     * （パッケージ名の完全一致で判定し、前方一致にはしない）。
     */
    private static List<String> filterImportable(List<String> fqns) {
        return fqns.stream()
            .filter(fqn -> !packageOf(fqn).equals(IMPLICIT_IMPORT_PACKAGE))
            .toList();
    }

    /** "java.lang.String" → "java.lang"、パッケージなしの場合は "" */
    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(0, dot) : "";
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
