package dev.javatexteditor.app;

import dev.javatexteditor.BufferRegistry;
import dev.javatexteditor.PaneTree;
import dev.javatexteditor.ProjectRootManager;
import dev.javatexteditor.WorkingDirectoryManager;
import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.ui.EditorCanvas;
import dev.javatexteditor.ui.FontChoice;
import dev.javatexteditor.ui.PaneResizeCalculator;
import dev.javatexteditor.ui.Theme;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JSplitPane;

/**
 * 画面分割のレイアウト・配線・アクティブペイン追跡を担う（MAIN_DECOMPOSITION_PLAN.md 段階6-1）。
 *
 * <p><b>本クラスの本質</b>: {@code Main.java} 側にあった {@code PaneTree.PaneNode[] root} /
 * {@code PaneTree.Leaf[] active} という「要素1個の配列＝書き換え可能な箱」（ラムダから
 * 外側のローカル変数を書き換えられないための回避策）を、このクラスのインスタンスフィールド
 * {@link #root} / {@link #active} に置き換えるためだけに新設した。旧 {@code Main} 側の
 * 該当メソッド群（{@code buildComponent}/{@code findLiveBuffer}/{@code syncSiblingBuffers}/
 * {@code setupSplitCallbacks}/{@code shareBufferWithSplit}/{@code createLeaf}×3/
 * {@code refreshCallbacks}/{@code rebuildLayout}/{@code resizeActivePane}/{@code updateBorders}）
 * は、本文を一切変えず {@code root[0]} → {@code root}・{@code active[0]} → {@code active} の
 * 機械的な置換のみを行って移設した。
 *
 * <p><b>{@code createLeaf} を静的メソッドではなくインスタンスメソッドにした理由</b>:
 * 分割時に新しいリーフを作る際、{@link #liveDiagnostics}・{@link #services}・
 * {@link #bufferRegistry}・{@link #javaBuildRunner}・{@link #wdManager}
 * という5つの協調オブジェクトを毎回配線する必要があり、これらを static フィールドとして
 * 複製するより、コンストラクタで受け取った1組をそのままインスタンスフィールドとして
 * 保持する方が「どの依存を握っているか」がクラス定義から読み取れて素直なため。
 *
 * <p><b>段階6-1と6-2を統合した経緯</b>: 当初の Option C 計画書（docs/STAGE6_OPTION_C_PLAN.md）は
 * 「6-1で機械的移設のみ・6-2で main() 側の配線をこのクラス経由に統一」と分けていたが、
 * {@code createLeaf} を含む責務4・10のメソッド群を {@code Main} から削除した時点で
 * {@code main()} は旧メソッドを呼べなくなり、ビルドが通らない状態になる。「1段階＝1コミットは
 * 必ずビルドが通る単位で区切る」という第1〜8弾の原則を優先し、6-1の時点で
 * {@code main()} 側の配線までを一体で行った（詳細は同計画書の進捗記録欄に記録）。
 *
 * <p><b>段階6-3で {@link EditorHost} を実装した</b>: {@code splitHorizontal}/{@code doSplit} 等の
 * 分割・ペイン移動・ペインクローズの実処理は、既存の per-leaf コールバック（{@code setup*Callback}系）
 * と {@link EditorHost} のメソッドの両方から呼べるよう private ヘルパー（{@code doSplit}/
 * {@code doMoveToPrevPane}/{@code doMoveToNextPane}/{@code doClosePane}）に集約した。
 * {@code ModalEditor} 側の配線（8個の setter/supplier/function）はまだ置き換えていない
 * （追加のみ。置き換えは6-4・6-5で行う）。
 */
public final class PaneManager implements EditorHost {

    private static final Color ACTIVE_BORDER_COLOR = new Color(0x88, 0x88, 0xFF);

    // Ctrl+Alt+矢印: アクティブペインのリサイズ量・最小ペインサイズ（ピクセル）
    private static final int PANE_RESIZE_STEP_PX = 20;
    private static final int PANE_RESIZE_MIN_PX   = 60;

    private final JFrame frame;
    private final LiveDiagnostics liveDiagnostics;
    private final AnalysisServices services;
    private final BufferRegistry bufferRegistry;
    private final JavaBuildRunner javaBuildRunner;
    private final WorkingDirectoryManager wdManager;
    private final ProjectRootManager projectRootManager;
    private final int initialCellW;
    private final int initialCellH;

    // 旧 Main.java の root[0] / active[0]（=52箇所参照されていた「箱」）に相当する唯一の状態。
    private PaneTree.PaneNode root;
    private PaneTree.Leaf     active;

    /**
     * 初期リーフを構築し、分割・共有バッファ等のコールバックを配線した状態で返す。
     *
     * @param frame            アプリの唯一の {@link JFrame}
     * @param initialText      初期バッファの内容
     * @param initialPath      初期ファイルパス（新規バッファなら {@code null}）
     * @param initialCellW     起動時フォントセル幅（{@code DisplayMetrics} で算出済みの値）
     * @param initialCellH     起動時フォントセル高
     * @param liveDiagnostics  インライン診断・auto-import/auto-#include の配線先
     * @param services         JDKクラス索引・補完索引一式
     * @param bufferRegistry   SPC+b で表示される開いたバッファの一覧
     * @param javaBuildRunner  F11 で複数 main クラスが見つかった際の実行コールバック用
     * @param wdManager        作業ディレクトリの中央管理
     * @param projectRootManager  :pr で固定するプロジェクトルートの中央管理（全ペイン共有）
     */
    public PaneManager(JFrame frame, String initialText, String initialPath,
                        int initialCellW, int initialCellH,
                        LiveDiagnostics liveDiagnostics, AnalysisServices services,
                        BufferRegistry bufferRegistry, JavaBuildRunner javaBuildRunner,
                        WorkingDirectoryManager wdManager, ProjectRootManager projectRootManager) {
        this.frame              = frame;
        this.liveDiagnostics    = liveDiagnostics;
        this.services           = services;
        this.bufferRegistry     = bufferRegistry;
        this.javaBuildRunner    = javaBuildRunner;
        this.wdManager          = wdManager;
        this.projectRootManager = projectRootManager;
        this.initialCellW       = initialCellW;
        this.initialCellH       = initialCellH;

        PaneTree.Leaf firstLeaf = createLeaf(initialText, initialPath);
        this.root   = firstLeaf;
        this.active = firstLeaf;
        refreshCallbacks();
        updateBorders();
    }

    // -------------------------------------------------------------------------
    // 外部（Main.java）から参照される状態
    // -------------------------------------------------------------------------

    /** 現在アクティブなリーフ。 */
    public PaneTree.Leaf active() {
        return active;
    }

    /** ツリー内の全リーフを左（上）から順に返す。 */
    public List<PaneTree.Leaf> allLeaves() {
        return PaneTree.allLeaves(root);
    }

    /** マウスクリックでアクティブペインを切り替える（呼び出し側で対象リーフを特定済みの前提）。 */
    public void setActive(PaneTree.Leaf leaf) {
        this.active = leaf;
        updateBorders();
    }

    /**
     * Ctrl+Alt+矢印: アクティブペインの縦横幅を伸縮する。
     * 対応する分割祖先が見つからない場合は何もしない。
     */
    public void resizeActivePane(int keyCode) {
        boolean horizontal = (keyCode == KeyEvent.VK_LEFT || keyCode == KeyEvent.VK_RIGHT);
        int neededOrientation = horizontal ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT;
        boolean grow = (keyCode == KeyEvent.VK_RIGHT || keyCode == KeyEvent.VK_DOWN);

        Component prev = active.canvas();
        Component cur  = prev.getParent();
        while (cur != null) {
            if (cur instanceof JSplitPane sp && sp.getOrientation() == neededOrientation) {
                boolean isFirstChildActive = (sp.getLeftComponent() == prev);
                int totalSpan = horizontal ? sp.getWidth() : sp.getHeight();
                int newLoc = PaneResizeCalculator.computeNewDividerLocation(
                    sp.getDividerLocation(), totalSpan, sp.getDividerSize(),
                    isFirstChildActive, grow, PANE_RESIZE_STEP_PX, PANE_RESIZE_MIN_PX);
                sp.setDividerLocation(newLoc);
                sp.revalidate();
                sp.repaint();
                return;
            }
            prev = cur;
            cur = cur.getParent();
        }
        // 対応方向の分割祖先が見つからない場合は何もしない（単一ペイン・非対応方向のみの入れ子等）
    }

    /** アクティブペインの枠線をハイライトし直す（キー処理後に毎回呼ばれる）。 */
    public void updateBorders() {
        updateBorders(allLeaves(), active);
    }

    // -------------------------------------------------------------------------
    // EditorHost 実装（段階6-3: 追加のみ。ModalEditor 側の配線はまだこれを使わない）
    // -------------------------------------------------------------------------

    @Override
    public void splitHorizontal() {
        doSplit(JSplitPane.HORIZONTAL_SPLIT);
    }

    @Override
    public void splitVertical() {
        doSplit(JSplitPane.VERTICAL_SPLIT);
    }

    @Override
    public void closePane() {
        doClosePane();
    }

    @Override
    public void onCloseBlocked() {
        // 現状 :q は無条件に許可されるため到達しない（EditorHost の Javadoc参照）。
    }

    @Override
    public void moveToPrevPane() {
        doMoveToPrevPane();
    }

    @Override
    public void moveToNextPane() {
        doMoveToNextPane();
    }

    @Override
    public List<dev.javatexteditor.editor.ModalEditor> allEditors() {
        return PaneTree.allLeaves(root).stream().map(PaneTree.Leaf::editor).toList();
    }

    @Override
    public void syncSiblingBuffers(dev.javatexteditor.editor.ModalEditor source) {
        for (PaneTree.Leaf l : PaneTree.allLeaves(root)) {
            if (l.editor() == source) {
                syncSiblingBuffers(l);
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // ペインツリー
    // -------------------------------------------------------------------------

    /** ツリーを Swing コンポーネントに変換する。 */
    private static Component buildComponent(PaneTree.PaneNode node) {
        return switch (node) {
            case PaneTree.Leaf l -> l.canvas();
            case PaneTree.Split s -> {
                JSplitPane sp = new JSplitPane(s.orientation,
                    buildComponent(s.left), buildComponent(s.right));
                sp.setResizeWeight(0.5);
                sp.setDividerSize(4);
                sp.setBorder(null);
                yield sp;
            }
        };
    }

    /**
     * Vim方式の共有バッファ: absolutePath を現在の currentFilePath として持つ生きたペインが
     * あれば、そのペインが参照する UndoablePieceTable をそのまま返す（無ければ null）。
     * ModalEditor.acquireBufferForOpen() から liveBufferLookup 経由で呼ばれる。
     */
    @Override
    public dev.javatexteditor.buffer.UndoablePieceTable findLiveBuffer(String absolutePath) {
        if (absolutePath == null) return null;
        for (PaneTree.Leaf l : PaneTree.allLeaves(root)) {
            if (absolutePath.equals(l.editor().getCurrentFilePath())) {
                return l.editor().getBuffer();
            }
        }
        return null;
    }

    /**
     * source と同じバッファ参照（Vim方式の共有バッファ）を表示している他ペインへ、
     * カーソル位置を現在の値で再クランプしつつ画面を再描画させる。setCursor() が
     * getLines()（＝共有バッファの最新内容）を基準に行/列をクランプしたうえで
     * syncCanvas() まで行うため、他ペインでの削除等でカーソルが範囲外になっていても安全。
     */
    private void syncSiblingBuffers(PaneTree.Leaf source) {
        dev.javatexteditor.buffer.UndoablePieceTable buf = source.editor().getBuffer();
        for (PaneTree.Leaf l : PaneTree.allLeaves(root)) {
            if (l != source && l.editor().getBuffer() == buf) {
                l.editor().setCursor(l.editor().getCursorRow(), l.editor().getCursorCol());
            }
        }
    }

    /**
     * アクティブペインを指定方向に分割する（{@code s v}/{@code s s} の実処理。
     * {@link EditorHost#splitHorizontal()}/{@link EditorHost#splitVertical()} と
     * 上記の per-leaf コールバックの両方から呼ばれる共通処理）。
     */
    private void doSplit(int orientation) {
        PaneTree.Leaf cur     = active;
        PaneTree.Leaf newLeaf = createLeaf(cur.editor().getText(),
                                  cur.editor().getCurrentFilePath(),
                                  cur.canvas().getCellW(), cur.canvas().getCellH(),
                                  cur.editor().getTheme(), cur.editor().getFontChoice());
        shareBufferWithSplit(cur, newLeaf);
        root   = PaneTree.splitLeaf(root, cur, newLeaf, orientation);
        active = newLeaf;
        rebuildLayout();
        refreshCallbacks();
        active.canvas().requestFocusInWindow();
    }

    /**
     * :split/:vsplit: 本家Vimと同じく、分割で作った新ペインは分割元と同じバッファを共有する
     * （新ペインを作った瞬間から真に同一の UndoablePieceTable インスタンスを指すため、
     * createLeaf() が内部で一旦構築した独自バッファを捨てて置き換える。カーソル位置も
     * 分割元に揃える。以後は liveBufferLookup を経由せずとも参照が同一のまま保たれる）。
     */
    private static void shareBufferWithSplit(PaneTree.Leaf source, PaneTree.Leaf newLeaf) {
        newLeaf.editor().setBuffer(source.editor().getBuffer());
        newLeaf.editor().setCursor(source.editor().getCursorRow(), source.editor().getCursorCol());
    }

    /** 新しいリーフを生成してコールバックを設定する（既定のフォントセルサイズ・テーマ・フォントを使用）。 */
    private PaneTree.Leaf createLeaf(String text, String path) {
        return createLeaf(text, path, initialCellW, initialCellH, Theme.DARK_MODE, FontChoice.MISC_FIXED);
    }

    /**
     * 新しいリーフを生成してコールバックを設定する。分割元ペインのフォントセルサイズを
     * 引き継ぐために cellW/cellH を明示指定できる（分割後は Ctrl+Shift+矢印で他ペインとは
     * 独立に変更可能。あくまで「分割直後の初期値」を揃えるだけ）。
     */
    private PaneTree.Leaf createLeaf(String text, String path, int cellW, int cellH) {
        return createLeaf(text, path, cellW, cellH, Theme.DARK_MODE, FontChoice.MISC_FIXED);
    }

    /**
     * 新しいリーフを生成してコールバックを設定する。:split/:vsplit時は分割元ペインの
     * カラーテーマ・フォント（:color/:font コマンドで変更済みの値）も引き継ぐ
     * （cellW/cellHの引き継ぎと同じ「分割直後の初期値を揃える」考え方。以後は各ペインで
     * 独立に :color/:font を実行できる）。
     */
    private PaneTree.Leaf createLeaf(String text, String path, int cellW, int cellH,
                                    Theme theme, FontChoice fontChoice) {
        EditorCanvas canvas = new EditorCanvas();
        canvas.setInitialCellSize(cellW, cellH);
        canvas.setTheme(theme);
        canvas.setFontChoice(fontChoice);
        ModalEditor editor = new ModalEditor(text, path, canvas);
        editor.setTheme(theme);
        editor.setFontChoice(fontChoice);
        liveDiagnostics.install(editor, canvas);
        // IME（日本語入力等）が確定した文字列を、KEY_TYPEDの1文字コミットと同じ経路で挿入する。
        // 変換中の未確定文字列自体は EditorCanvas 側でカーソル位置にオーバーレイ表示される。
        canvas.setImeCommitHandler(committed -> {
            if (!editor.isInsertMode() && !editor.isCommandMode()) return;
            for (int i = 0; i < committed.length(); ) {
                int cp = committed.codePointAt(i);
                for (char ch : Character.toChars(cp)) {
                    editor.processKey(0, ch, 0);
                }
                i += Character.charCount(cp);
            }
        });
        // 解析サービス一式（JDKクラス索引・auto-import・補完索引・単語索引）を配線する。
        services.wireInto(editor);
        // Shift+K の最優先段（Eclipse JDT 流バインディング解決）を有効化する。
        // javac の属性付けはプロジェクト規模に比例して重いため EDT では実行せず、
        // 仮想スレッドで解析して invokeLater で結果を反映する（完全非同期）。
        editor.enableBindingDefinitionLookup(
            task -> Thread.ofVirtual().name("binding-definition-lookup").start(task),
            javax.swing.SwingUtilities::invokeLater);
        // メンバー補完（obj. の後）の正確な型解決も同じ理由で完全非同期にする。
        // 有効化しない場合はリフレクションによる軽量解決だけで動作する。
        editor.enableMemberCompletionLookup(
            task -> Thread.ofVirtual().name("member-completion-lookup").start(task),
            javax.swing.SwingUtilities::invokeLater);
        editor.setBufferListSupplier(bufferRegistry::entries);
        editor.setOnFileOpened(bufferRegistry::register);
        editor.setOnBufferDelete(bufferRegistry::unregister);
        editor.setOnRunMainClassSelected(
            fqcn -> javaBuildRunner.runSelectedMainClass(editor, editor.getBuildRoot(), fqcn));
        // 作業ディレクトリを反映
        if (wdManager != null) {
            Path wd = wdManager.getWorkingDirectory();
            editor.setProjectRoot(wd);
            editor.setChangeWorkingDirectoryCallback(p -> wdManager.setWorkingDirectory(p));
        }
        // :pr で固定するプロジェクトルートを反映（:cd の作業ディレクトリと同じ「中央管理へ
        // 変更を委譲し、通知で全ペインへ反映する」方式。バグ修正の経緯は decision-log.md 参照）。
        if (projectRootManager != null) {
            editor.setProjectRootOverride(projectRootManager.getProjectRootOverride());
            editor.setChangeProjectRootCallback(projectRootManager::setProjectRootOverride);
        }
        return new PaneTree.Leaf(canvas, editor);
    }

    /**
     * 全リーフの {@link EditorHost} 配線を再設定する（段階6-5でsetHost()1本化に統一）。
     * :q 時、ペインが1つなら終了、複数なら現在のリーフを閉じる。
     *
     * <p>以前はここで9個の setter を個別に呼んでいた（{@code setSplitHorizontalCallback}/
     * {@code setSplitVerticalCallback}/{@code setAllEditorsSupplier}/{@code setLiveBufferLookup}/
     * {@code setOnSharedBufferSync}/{@code setMovePanePrevCallback}/{@code setMovePaneNextCallback}/
     * {@code setExitCallback}、うち分割2個は {@code setupSplitCallbacks()} という別メソッドに
     * 分かれていた）。{@code ModalEditor.setHost(EditorHost)}（段階6-4で新設）が同じ9個の委譲を
     * 内部で行うため、ここでは1行に統一した。{@code PaneManager} 自身が {@link EditorHost} を
     * 実装しているため {@code this} をそのまま渡せる。挙動が変わらないことは
     * docs/STAGE6_OPTION_C_PLAN.md 段階6-5の検証記録を参照。
     */
    private void refreshCallbacks() {
        for (PaneTree.Leaf leaf : PaneTree.allLeaves(root)) {
            leaf.editor().setHost(this);
        }
    }

    /** {@code s h}/{@code s k} の実処理（{@link #moveToPrevPane()} と per-leaf コールバックが共有）。 */
    private void doMoveToPrevPane() {
        List<PaneTree.Leaf> leaves = PaneTree.allLeaves(root);
        if (leaves.size() <= 1) return;
        int idx = leaves.indexOf(active);
        active = leaves.get((idx - 1 + leaves.size()) % leaves.size());
        updateBorders(leaves, active);
        active.canvas().requestFocusInWindow();
    }

    /** {@code s l}/{@code s j} の実処理（{@link #moveToNextPane()} と per-leaf コールバックが共有）。 */
    private void doMoveToNextPane() {
        List<PaneTree.Leaf> leaves = PaneTree.allLeaves(root);
        if (leaves.size() <= 1) return;
        int idx = leaves.indexOf(active);
        active = leaves.get((idx + 1) % leaves.size());
        updateBorders(leaves, active);
        active.canvas().requestFocusInWindow();
    }

    /**
     * {@code :q} の実処理（{@link #closePane()} と per-leaf コールバックが共有）。
     * ペインが1つなら終了、複数ならアクティブなリーフを閉じる。
     */
    private void doClosePane() {
        List<PaneTree.Leaf> leaves = PaneTree.allLeaves(root);
        if (leaves.size() <= 1) {
            System.exit(0);
            return;
        }
        // アクティブを閉じる
        PaneTree.Leaf closing = active;
        PaneTree.PaneNode newRoot = PaneTree.removeLeaf(root, closing);
        root = newRoot;

        // 次のアクティブは閉じたリーフの直前 or 先頭
        List<PaneTree.Leaf> remaining = PaneTree.allLeaves(root);
        int idx = leaves.indexOf(closing);
        active = remaining.get(Math.min(idx, remaining.size() - 1));

        rebuildLayout();
        refreshCallbacks();
        active.canvas().requestFocusInWindow();
    }

    /** フレームのコンテンツを再構築してボーダーを更新する。 */
    private void rebuildLayout() {
        frame.getContentPane().removeAll();
        frame.getContentPane().add(buildComponent(root));
        frame.revalidate();
        frame.repaint();
        updateBorders(PaneTree.allLeaves(root), active);
    }

    private static void updateBorders(List<PaneTree.Leaf> leaves, PaneTree.Leaf active) {
        for (PaneTree.Leaf l : leaves) {
            boolean isActive = l == active;
            l.canvas().setBorder(isActive
                ? BorderFactory.createLineBorder(ACTIVE_BORDER_COLOR, 2)
                : BorderFactory.createLineBorder(Color.DARK_GRAY, 1));
            l.canvas().setActivePane(isActive);
        }
    }
}
