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
     * JDK クラス名索引（{@link CompletionIndex}）のバックグラウンド構築を開始する。
     * 起動時に必ず1回呼ぶこと。<b>{@code SwingUtilities.invokeLater} に入るより前</b>に呼ぶこと。
     *
     * <p><b>{@link WordIndex}（Alt+/ の単語索引・作業ディレクトリ配下のディスク走査）は
     * ここでは構築しない</b>（2026-08-10 変更）。{@link #startWordIndexing(Path)} を参照。
     */
    public void startProjectIndexing(Path projectRoot) {
        // 補完インデックス（JDK クラス名のみ）をバックグラウンドで構築。ディスク走査を伴わず
        // JVM 起動ごとに1回だけ・固定サイズなので、作業ディレクトリの規模に関わらず常時構築してよい。
        completionIndex = CompletionIndex.build(jdkClassIndex);
    }

    /**
     * Alt+/ 単語索引（{@link WordIndex}）のバックグラウンド構築を開始する。
     *
     * <p><b>{@code :pr} でプロジェクトルートが固定されたときにのみ呼ぶ（2026-08-10 追加）</b>。
     * 以前は {@link #startProjectIndexing(Path)} が起動直後に無条件でディスク走査していたが、
     * 走査の起点は {@code WorkingDirectoryManager} が決める作業ディレクトリで、その既定値は
     * <b>ユーザーのホームディレクトリ</b>（{@code scripts/run.sh} を引数なしで起動した場合）
     * だった。索引はプロセスが終わるまで生き続けるため、`:pr` 未実行のセッションでも
     * ホーム配下すべてを索引化した分（実測157MB、上限を入れた後でも52MB）が常に
     * ヒープに載っていた（詳細は decision-log.md 「WordIndex がホームディレクトリ全体を
     * 無制限に索引化していた問題の修正（2026-08-10）」）。
     *
     * <p>`:pr` 未実行の間は {@link #wordIndex()} が {@code null} を返し続け、呼び出し側
     * （{@code ModalEditor} の補完系メソッド）は既存の null 分岐でバッファ内単語のみに
     * フォールバックする（JDK クラス名・メンバーは {@link #completionIndex} / JDK 索引が
     * 別途常時利用可能なので、Java バッファは実質的に「現在バッファの単語 + 標準API」になる）。
     *
     * <p>呼び出し側（{@code EditorApplication}）は、構築後のインスタンスを
     * {@link #wordIndex()} 経由で取得し、既に開いている全ペインへ手動で反映すること
     * （新規ペインは {@link #wireInto} が自動的に拾う）。
     */
    public void startWordIndexing(Path projectRoot) {
        wordIndex = WordIndex.build(projectRoot);
    }

    /** インライン診断（{@link LiveDiagnostics}）が索引の完成を待つために参照する。 */
    public JdkClassIndex jdkClassIndex() {
        return jdkClassIndex;
    }

    /**
     * Alt+/ 単語索引。{@link LiveDiagnostics} が保存時の差分更新（{@code WordIndex.updateFile}）に使う。
     * {@link #startProjectIndexing(Path)} 呼び出し前は {@code null}（呼び出し側は {@link java.util.function.Supplier}
     * 経由で遅延解決すること。クラス Javadoc「completionIndex / wordIndex を volatile にしていない理由」参照）。
     */
    public WordIndex wordIndex() {
        return wordIndex;
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
        // クラス名の補完候補を確定したその場で import を挿入するために使う
        editor.setImportSuggester(importSuggester);
        if (completionIndex != null) {
            editor.setCompletionIndex(completionIndex);
        }
        if (wordIndex != null) {
            editor.setWordIndex(wordIndex);
        }
    }
}
