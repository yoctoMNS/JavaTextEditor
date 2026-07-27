package dev.javatexteditor.app;

import dev.javatexteditor.analysis.AnalysisException;
import dev.javatexteditor.analysis.CCompileAnalyzer;
import dev.javatexteditor.analysis.CompileAnalyzer;
import dev.javatexteditor.analysis.CompileDiagnostic;
import dev.javatexteditor.analysis.JdkClassIndex;
import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.ui.EditorCanvas;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/**
 * 編集中のバッファをバックグラウンドでコンパイル解析し、診断をガター/波下線へ反映する。
 * Java は {@code javax.tools.JavaCompiler}、C は {@code gcc -fsyntax-only} を使う。
 * あわせて auto-import（Java）/ auto-#include（C）も駆動する。
 *
 * <p>{@code Main} から切り出した（MAIN_DECOMPOSITION_PLAN.md 段階3）。
 *
 * <p><b>作業ディレクトリを {@link Supplier} で受け取る理由</b>: 解析対象の projectRoot は
 * 切り出し前も「解析を実行する時点で」{@code WD_MANAGER.getWorkingDirectory()} を読んでいた。
 * 固定の {@link Path} を渡す設計にすると {@code :cd} 後の解析が古いディレクトリを見てしまうため、
 * 呼び出しのたびに解決できるよう {@link Supplier} にしてある。
 *
 * <p><b>{@code isJavaBuffer}/{@code isCBuffer} を他の言語判定と統合してはならない</b>
 * （MAIN_DECOMPOSITION_PLAN.md §7.2・CLAUDE.md 第8弾の記録）。
 * 本クラスの判定は {@code .java} / {@code .c} {@code .h} のみを対象とするが、
 * {@code ModalEditor.isCFilePath}（Shift+K の定義ジャンプ）と
 * {@code ui.SourceLanguage.detect}（構文ハイライト）は {@code .cpp} 等も含む広い集合を対象とする。
 * 統合すると C++ ファイルが C コンパイラへ回される。
 */
public final class LiveDiagnostics {

    private final CompileAnalyzer javaAnalyzer;
    private final CCompileAnalyzer cAnalyzer;
    private final JdkClassIndex jdkIndex;
    private final Supplier<Path> workingDirectory;

    public LiveDiagnostics(CompileAnalyzer javaAnalyzer, CCompileAnalyzer cAnalyzer,
            JdkClassIndex jdkIndex, Supplier<Path> workingDirectory) {
        this.javaAnalyzer = javaAnalyzer;
        this.cAnalyzer = cAnalyzer;
        this.jdkIndex = jdkIndex;
        this.workingDirectory = workingDirectory;
    }

    /**
     * 1つの編集対象（ペイン）に解析トリガを取り付ける。ペインを作るたびに1回だけ呼ぶ。
     *
     * <p><b>世代カウンタは必ずこのメソッドの中で作ること。</b>
     * インスタンスフィールドに移すと全ペインで共有されてしまい、
     * 別ペインの解析が互いの結果を捨て合って診断が消える
     * （MAIN_DECOMPOSITION_PLAN.md 段階3 の注意）。
     * 切り出し前もここでローカル変数として作りクロージャで捕捉していた。
     */
    public void install(ModalEditor editor, EditorCanvas canvas) {
        // onReturnToNormal（INSERT離脱）とonSave（:w等）は同じ"save.from.insert"アクション
        // （INSERT中のCtrl+[/Ctrl+]保存）や「Escした直後にすぐ:wする」操作で立て続けに両方発火しうる。
        // その場合、内容が同一の2つのコンパイル解析がほぼ同時にバックグラウンドスレッドで走り、
        // 完了順序が入れ替わると「先に完了した解析がambiguous importを正しく選択・適用した直後に、
        // 後から届いた古い（選択前の）診断結果を使うhandleAutoImportが再実行され、既にimport済みの
        // 候補が除外された結果『残り1件』に見えてしまい確認なしで誤ったimportを追加する」という
        // 実害のある不具合につながる（AutoImportHandlerTest等では再現しないが、実機で
        // "cannot find symbol"が解消されないまま import 選択ポップアップが再発し続ける形で観測される）。
        // compileGeneration で「最後に発行した解析要求」だけを世代番号として追跡し、結果が返って
        // きた時点で世代が古ければ（＝その後により新しい解析要求が発行済みなら）黙って破棄する。
        AtomicLong compileGeneration = new AtomicLong(0);
        Runnable trigger = () -> {
            if (isJavaBuffer(editor)) {
                editor.setStatusMessage("auto-import: 解析中...");
                runCompileAnalysis(editor, canvas, true, "auto-import: 解析失敗", compileGeneration);
            } else if (isCBuffer(editor)) {
                runCAnalysis(editor, canvas, true);
            }
        };
        // INSERT→NORMAL 遷移時: IMEを半角英数字に切り替え、変換中表示を消してからコンパイル解析を実行する
        editor.setOnReturnToNormal(() -> {
            canvas.switchToHalfWidth();
            canvas.clearImeComposition();
            trigger.run();
        });
        editor.setOnSave(trigger);
        // Ctrl+Shift+O: コンパイル→未定義シンボルへの import 挿入→未使用 import 削除
        editor.setOnOrganizeImports(() -> {
            if (isJavaBuffer(editor)) {
                editor.setStatusMessage("import 整理中...");
                runCompileAnalysis(editor, canvas, false, "E: コンパイル解析失敗", compileGeneration);
            } else if (isCBuffer(editor)) {
                organizeCIncludes(editor, canvas);
            } else {
                editor.setStatusMessage("E: Java/Cファイルではありません");
            }
        });
        // dd/p/u/Ctrl+R等、INSERT離脱・保存を経由しないバッファ変更操作は上記2フックの対象外で、
        // 行が増減しても診断（ガターの赤線）が古い行番号のまま残り、保存するまで直らない不具合が
        // あった。バッファのversionが変わるたびに再解析するが、INSERT中は入力途中の構文を
        // 都度解析しても無駄なため対象外にする（onReturnToNormalが離脱時に既に解析する）。
        // 連続編集での解析多発を避けるためデバウンスする。
        javax.swing.Timer debounceTimer = new javax.swing.Timer(400, e -> trigger.run());
        debounceTimer.setRepeats(false);
        editor.setOnBufferChanged(() -> {
            if (!editor.isInsertMode()) {
                debounceTimer.restart();
            }
        });
    }

    /**
     * currentFilePath の拡張子が ".java" である場合のみ Javaバッファと判定する
     * （コンパイル解析が無意味なため）。ファイルパス未設定（:enew 等の疑似バッファ）は
     * 拡張子が確定していないため、デフォルトではJavaバッファとして扱わない。
     */
    public static boolean isJavaBuffer(ModalEditor editor) {
        String path = editor.getCurrentFilePath();
        return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".java");
    }

    /**
     * currentFilePath の拡張子が ".c" または ".h" である場合のみ Cバッファと判定する
     * （isJavaBuffer と同じく、ファイルパス未設定の疑似バッファは対象外）。
     * C の診断・auto-#include・F10/F11/F12 のルーティングに使う。
     */
    public static boolean isCBuffer(ModalEditor editor) {
        String path = editor.getCurrentFilePath();
        if (path == null) return false;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".c") || lower.endsWith(".h");
    }

    /** バックグラウンド仮想スレッドでコンパイル解析し、EDT で診断反映と auto-import を行う。
     *  @param useRealPathIfSaved true のとき、保存済みファイルなら analyzeWithProject を使う
     *                            （INSERT→NORMAL / 保存トリガ用。public class 名不一致エラーを防ぐ）。
     *                            false のとき常に analyzeWithProject を使う（Ctrl+Shift+O 用。複数ファイル対応）。
     *  @param failureMessage 解析失敗時にステータス行へ出す文言
     *  @param generation install が編集対象ごとに1つ保持する世代カウンタ。
     *                     結果反映時にこの呼び出し以降より新しい解析要求が発行されていれば
     *                     （＝このスレッドが取得した診断は古い）、EDT反映を丸ごと破棄する。 */
    private void runCompileAnalysis(ModalEditor editor, EditorCanvas canvas,
            boolean useRealPathIfSaved, String failureMessage, AtomicLong generation) {
        String source = editor.getText();
        String snapshotPath = editor.getCurrentFilePath();
        long myGeneration = generation.incrementAndGet();
        Thread.ofVirtual().start(() -> {
            try {
                // クラス索引が未完了なら完了まで待つ（起動直後の INSERT→NORMAL 対策）
                jdkIndex.awaitReady();
                Path projectRoot = workingDirectory.get();
                List<CompileDiagnostic> diags = (useRealPathIfSaved && snapshotPath != null)
                    ? javaAnalyzer.analyzeWithProject(snapshotPath, source, projectRoot)
                    : javaAnalyzer.analyzeWithProject("<buffer>", source, projectRoot);
                SwingUtilities.invokeLater(() -> {
                    if (generation.get() != myGeneration) return; // より新しい解析要求に上書き済み: 破棄
                    canvas.setDiagnostics(diags);
                    // 未使用削除は handleAutoImport の全候補処理完了後に実行
                    editor.setOnImportComplete(editor::organizeImportsRemoveUnused);
                    editor.handleAutoImport(diags);
                });
            } catch (AnalysisException e) {
                SwingUtilities.invokeLater(() -> {
                    if (generation.get() != myGeneration) return;
                    canvas.setDiagnostics(List.of());
                    editor.setStatusMessage(failureMessage);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * C バッファ版のバックグラウンド解析。gcc -fsyntax-only の診断をガター表示し、autoInclude が
     * true なら未定義シンボル（implicit declaration / unknown type name / undeclared）に対応する
     * 標準ヘッダを自動 #include する（Java の auto-import の C 版）。gcc が無い環境では静かに
     * 診断なしにフォールバックする。
     */
    private void runCAnalysis(ModalEditor editor, EditorCanvas canvas, boolean autoInclude) {
        String source = editor.getText();
        String snapshotPath = editor.getCurrentFilePath();
        Thread.ofVirtual().start(() -> {
            try {
                List<CompileDiagnostic> diags = (snapshotPath != null)
                    ? cAnalyzer.analyzeWithPath(snapshotPath, source)
                    : cAnalyzer.analyze(source);
                // 未定義シンボルから必要ヘッダを算出（ガター表示前の source を基準にする）
                java.util.Set<String> symbols = new java.util.LinkedHashSet<>();
                for (CompileDiagnostic d : diags) {
                    String sym = dev.javatexteditor.analysis.CIncludeManager
                        .extractSymbolFromMessage(d.message());
                    if (sym != null) symbols.add(sym);
                }
                List<String> headers = dev.javatexteditor.analysis.CIncludeManager
                    .missingHeadersForSymbols(source, symbols);
                SwingUtilities.invokeLater(() -> {
                    canvas.setDiagnostics(diags);
                    if (autoInclude && !headers.isEmpty()) {
                        int n = editor.applyCIncludes(headers);
                        if (n > 0) editor.setStatusMessage("#include " + n + "件 追加しました");
                    }
                });
            } catch (AnalysisException e) {
                SwingUtilities.invokeLater(() -> canvas.setDiagnostics(List.of()));
            }
        });
    }

    /**
     * :oi / SPC+i+o の C 版。ソース中に現れる標準ライブラリシンボルに対応する未 include のヘッダを
     * まとめて追加する（ソース走査ベース。gcc 不要で同期実行）。
     *
     * <p>{@code canvas} は切り出し前から未使用のまま引き回されている
     * （MAIN_DECOMPOSITION_PLAN.md §8 R-7）。
     */
    private void organizeCIncludes(ModalEditor editor, EditorCanvas canvas) {
        List<String> headers = dev.javatexteditor.analysis.CIncludeManager
            .missingHeadersForSource(editor.getText());
        if (headers.isEmpty()) {
            editor.setStatusMessage("#include 整理完了（追加なし）");
            return;
        }
        int n = editor.applyCIncludes(headers);
        editor.setStatusMessage("#include " + n + "件 追加しました");
    }
}
