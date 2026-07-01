package dev.javatexteditor.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * クラス名・メソッド名・フィールド名の入力補完インデックス。
 *
 * TreeMap による昇順ソートを利用し、subMap(prefix, prefix+"￿") で
 * O(log n) のプレフィックス検索を実現する。
 *
 * インデックス構築はバックグラウンド仮想スレッドで行う。
 * 構築完了前に query() を呼んだ場合は空リストを返す。
 */
public class CompletionIndex {

    /** key = label（大文字小文字そのまま）, value = 最初に登録された CompletionItem */
    private final TreeMap<String, CompletionItem> index = new TreeMap<>();
    private final AtomicBoolean ready = new AtomicBoolean(false);

    private CompletionIndex() {}

    /**
     * バックグラウンドでインデックスを構築し、構築中のインスタンスを即座に返す。
     *
     * @param jdkIndex    JDK クラスインデックス（build() 完了済みでなくてもよい）
     * @param projectRoot プロジェクトルートディレクトリ（null のときスキップ）
     * @param analyzer    SourceAnalyzer インスタンス
     */
    public static CompletionIndex build(JdkClassIndex jdkIndex, Path projectRoot,
                                        SourceAnalyzer analyzer) {
        CompletionIndex ci = new CompletionIndex();
        Thread.ofVirtual().start(() -> {
            // JDK インデックスが完了するまで待つ
            try {
                jdkIndex.awaitReady();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ci.ready.set(true);
                return;
            }
            ci.addJdkClasses(jdkIndex);
            if (projectRoot != null) {
                ci.addProjectSymbols(projectRoot, analyzer);
            }
            ci.ready.set(true);
        });
        return ci;
    }

    /** テスト用: 同期的に構築して返す。 */
    public static CompletionIndex buildSync(JdkClassIndex jdkIndex, Path projectRoot,
                                            SourceAnalyzer analyzer) {
        CompletionIndex ci = new CompletionIndex();
        try {
            jdkIndex.awaitReady();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ci.ready.set(true);
            return ci;
        }
        ci.addJdkClasses(jdkIndex);
        if (projectRoot != null) {
            ci.addProjectSymbols(projectRoot, analyzer);
        }
        ci.ready.set(true);
        return ci;
    }

    /** インデックスが使用可能かどうか。 */
    public boolean isReady() {
        return ready.get();
    }

    /**
     * 入力文字列に対してスマートスコアリングを行い、上位 maxResults 件を返す。
     *
     * スコアリング優先度（CompletionScorer 参照）:
     *   1. 完全一致
     *   2. 大文字小文字区別ありプレフィックス
     *   3. 大文字小文字区別なしプレフィックス
     *   4. CamelCase 頭文字一致（例: "AL" → "ArrayList"）
     *   5. ファジー部分列一致
     *
     * インデックス未完了の場合は空リストを返す。
     */
    public List<CompletionItem> query(String query, int maxResults) {
        if (!ready.get() || query == null || query.isEmpty()) return Collections.emptyList();

        // 全エントリをスコアリングして収集（14k 件程度なら < 1ms）
        record Scored(CompletionItem item, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (CompletionItem item : index.values()) {
            int s = CompletionScorer.score(query, item.label());
            if (s > 0) scored.add(new Scored(item, s));
        }

        // スコア降順 → 同スコアはアルファベット昇順
        scored.sort((a, b) -> a.score() != b.score()
            ? b.score() - a.score()
            : a.item().label().compareTo(b.item().label()));

        List<CompletionItem> result = new ArrayList<>(Math.min(maxResults, scored.size()));
        for (int i = 0; i < scored.size() && result.size() < maxResults; i++) {
            result.add(scored.get(i).item());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * プロジェクト内の全 .java ファイルを再スキャンしてインデックスを更新する。
     * 保存時にバックグラウンドで呼ぶことを想定。
     */
    public void refreshProjectSymbols(Path projectRoot, SourceAnalyzer analyzer) {
        Thread.ofVirtual().start(() -> addProjectSymbols(projectRoot, analyzer));
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void addJdkClasses(JdkClassIndex jdkIndex) {
        for (String name : jdkIndex.allSimpleNames()) {
            index.putIfAbsent(name, new CompletionItem(name, "cls"));
        }
    }

    private void addProjectSymbols(Path projectRoot, SourceAnalyzer analyzer) {
        try (Stream<Path> files = Files.walk(projectRoot)) {
            files.filter(p -> p.toString().endsWith(".java"))
                 .forEach(p -> indexFile(p, analyzer));
        } catch (IOException | UncheckedIOException ignored) {
            // Files.walk はアクセス不可なディレクトリ（Windowsのジャンクション等）に遭遇すると
            // 走査中に UncheckedIOException を投げる。IOException だけでは捕捉できないため両方catchする。
        }
    }

    private void indexFile(Path path, SourceAnalyzer analyzer) {
        try {
            SourceIndex si = analyzer.analyzeFile(path);
            for (SymbolEntry sym : si.symbols()) {
                String kind = switch (sym.kind()) {
                    case CLASS, INTERFACE, ENUM -> "cls";
                    case METHOD, CONSTRUCTOR    -> "mth";
                    case FIELD                  -> "fld";
                };
                // クラス名は優先度が高いため putIfAbsent ではなく上書き条件付きで登録
                String label = sym.name();
                CompletionItem existing = index.get(label);
                if (existing == null || (kind.equals("cls") && !existing.kind().equals("cls"))) {
                    index.put(label, new CompletionItem(label, kind));
                }
            }
        } catch (AnalysisException ignored) {}
    }
}
