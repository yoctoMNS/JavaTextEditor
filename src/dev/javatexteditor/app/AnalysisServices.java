package dev.javatexteditor.app;

import dev.javatexteditor.analysis.AutoImportHandler;
import dev.javatexteditor.analysis.CompletionIndex;
import dev.javatexteditor.analysis.ImportSuggester;
import dev.javatexteditor.analysis.JdkClassIndex;
import dev.javatexteditor.analysis.SourceAnalyzer;
import dev.javatexteditor.analysis.WordIndex;
import dev.javatexteditor.editor.ModalEditor;
import java.nio.file.Path;

/**
 * 各編集ペインが必要とする解析サービス一式を保持し、構築の開始タイミングを一箇所に集約する。
 *
 * <p>{@code Main} から切り出した（MAIN_DECOMPOSITION_PLAN.md 段階5）。
 * ここに置くのは「解析サービス」だけである。{@code WorkingDirectoryManager}（作業ディレクトリ管理）や
 * {@code BufferRegistry}（開いたバッファの一覧）は性質が異なるため<b>含めない</b>
 * （名前に無関係なものを詰めると、この一連のリファクタリングが解消しようとしている
 * 「1クラスに複数の責務が同居する」状態を再生産してしまう）。
 *
 * <h2>★構築開始のタイミングを遅らせてはならない★</h2>
 *
 * <p>{@link JdkClassIndex#build()} と {@link CompletionIndex#build}、{@link WordIndex#build}
 * はいずれも<b>非同期</b>である（内部で仮想スレッドを起動して即座に返る）。
 * つまりこれらの呼び出しは「重い処理」ではなく<b>バックグラウンド構築の開始合図</b>にすぎない。
 *
 * <p>したがってこれらを {@code SwingUtilities.invokeLater} の中で生成するように変えると、
 * 構築の開始が遅れ、<b>起動直後の Ctrl+Space（補完）や Shift+K が空振りする</b>。
 * しかもクラッシュしないため自動テストでは検知できない。
 *
 * <p>この事故を防ぐため、次の2点を守ること。
 * <ul>
 *   <li>{@link #createAndStartJdkIndexing()} は {@code Main} の {@code static final} フィールドの
 *       初期化子から呼ぶ（＝クラスロード時。切り出し前の
 *       {@code private static final JdkClassIndex JDK_INDEX = JdkClassIndex.build();} と同じタイミング）</li>
 *   <li>{@link #startProjectIndexing(Path)} は作業ディレクトリが確定した直後、
 *       <b>{@code SwingUtilities.invokeLater} に入るより前</b>に呼ぶ</li>
 * </ul>
 *
 * <h2>completionIndex / wordIndex を volatile にしていない理由</h2>
 *
 * <p>切り出し前の {@code Main.COMPLETION_INDEX} / {@code Main.WORD_INDEX} も
 * {@code volatile} ではなかった。書き込みは main スレッド、読み出しは EDT だが、
 * 書き込みが {@code SwingUtilities.invokeLater} の呼び出しより前に行われており、
 * {@code invokeLater} 内部のキュー同期が happens-before 関係を確立するため可視性は保証される。
 * 元の性質をそのまま維持している。
 */
public final class AnalysisServices {

    private final JdkClassIndex jdkClassIndex;
    private final SourceAnalyzer sourceAnalyzer;
    private final ImportSuggester importSuggester;
    private final AutoImportHandler autoImportHandler;

    /** 作業ディレクトリが確定してから構築を開始するため、生成時点では null。 */
    private CompletionIndex completionIndex = null;
    private WordIndex wordIndex = null;

    private AnalysisServices() {
        this.jdkClassIndex = JdkClassIndex.build();
        this.sourceAnalyzer = new SourceAnalyzer();
        this.importSuggester = new ImportSuggester(jdkClassIndex);
        this.autoImportHandler = new AutoImportHandler(importSuggester, sourceAnalyzer);
    }

    /**
     * 解析サービスを生成し、JDK クラス索引のバックグラウンド構築を<b>即座に開始</b>する。
     * クラスロード時に呼ぶこと（クラス Javadoc の「構築開始のタイミング」参照）。
     */
    public static AnalysisServices createAndStartJdkIndexing() {
        return new AnalysisServices();
    }

    /**
     * 作業ディレクトリに依存する索引（Ctrl+Space の補完索引・Alt+/ の単語索引）の
     * バックグラウンド構築を開始する。作業ディレクトリ確定直後、
     * <b>{@code SwingUtilities.invokeLater} に入るより前</b>に呼ぶこと。
     */
    public void startProjectIndexing(Path projectRoot) {
        // 補完インデックス（JDK クラス名のみ）をバックグラウンドで構築
        completionIndex = CompletionIndex.build(jdkClassIndex);
        // Alt+/ 単語補完インデックス（作業ディレクトリ配下の単語）もバックグラウンドで構築
        wordIndex = WordIndex.build(projectRoot);
    }

    /** インライン診断（{@link LiveDiagnostics}）が索引の完成を待つために参照する。 */
    public JdkClassIndex jdkClassIndex() {
        return jdkClassIndex;
    }

    /**
     * 1つの編集ペインに解析サービス一式を配線する。ペインを作るたびに呼ぶ。
     *
     * <p>索引が未構築（{@code null}）の場合は設定をスキップする。
     * 切り出し前も {@code createLeaf} 側に同じ null チェックがあった。
     *
     * <p>ここで呼ぶ4つの setter はいずれも {@code ModalEditor} 側で単純な代入であり、
     * 呼び出し順に依存しない（切り出し前は他の配線を挟んで離れた位置にあったものを
     * まとめたが、順序の入れ替えによる影響は無いことを確認済み）。
     */
    public void wireInto(ModalEditor editor) {
        editor.setJdkClassIndex(jdkClassIndex);
        editor.setAutoImportHandler(autoImportHandler);
        if (completionIndex != null) {
            editor.setCompletionIndex(completionIndex);
        }
        if (wordIndex != null) {
            editor.setWordIndex(wordIndex);
        }
    }
}
