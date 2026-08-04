package dev.javatexteditor.buffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class UndoablePieceTable extends PieceTable {

    // Vimの 'undolevels'(既定1000)に倣った上限。旧実装は上限が無く、編集操作(insert/delete)の
    // たびにスナップショットが積み上がり続け、長時間の編集セッションでヒープを圧迫していた
    // (詳細はCLAUDE.mdのメモリ調査タスクの報告参照)。
    private static final int MAX_UNDO_HISTORY = 1000;

    // addBufferのコンパクション(Phase 4)を試みる間隔。MAX_UNDO_HISTORYより短い値にすることで、
    // 「上限に達するまでの間、削除済み文字列までaddBufferに積み上がり続ける」問題を、
    // undo履歴の上限化(Phase 1)だけでは救えない範囲でも軽減する。コンパクション自体は
    // 生存ピース総数に比例したコストがかかるため、毎編集ごとに行うと逆に重くなる
    // (打っては消すような高頻度編集で顕著)。500編集に1回という間隔は、コストと効果の
    // バランスを取った経験的な値(将来チューニング可能)。
    private static final int COMPACTION_INTERVAL = 500;
    private int editsSinceCompaction = 0;

    // ArrayDequeを使う理由: 「最新を先頭(push/pop)」「上限超過時に最古(末尾)を1件破棄」の
    // 両方が必要で、Dequeなら両端操作(push/pop/removeLast)をどちらもO(1)で行える。
    // LinkedListでも同じことはできるが、要素ごとにノードを持たないArrayDequeの方が
    // GC対象になる中間オブジェクトが少なく、この用途(単純な両端キュー)に軽量。
    private final Deque<List<Piece>> undoStack = new ArrayDeque<>();
    private final Deque<List<Piece>> redoStack = new ArrayDeque<>();
    // :wa/:qa（Vim互換の全保存・全終了）の判定に使う「最後の保存以降に変更があったか」フラグ。
    // undo/redoで保存時点のテキストと文字列として一致する状態に戻っても modified は false に戻らない
    // （厳密な内容比較はせず「編集操作が行われたか」だけを見る単純な近似。既知の制約）。
    private boolean modified = false;
    // コンパイル診断（ガター表示）の再解析トリガ用。insert/delete/undo/redoのたびに増分する。
    // modifiedと異なりundo/redoでも常に増えるため、「編集操作の結果テキストが変わった可能性がある」
    // ことを漏れなく検知できる（呼び出し側はこの値の変化だけを見て再解析要否を判定する）。
    private long version = 0;

    public UndoablePieceTable(String initialText) {
        super(initialText);
    }

    /** 大容量ファイル向け: mmap経由でファイル全体をStringへ展開せずに開く（PieceTable参照）。 */
    public UndoablePieceTable(MappedFileSource mappedSource) {
        super(mappedSource);
    }

    private void snapshotBeforeEdit() {
        pushBounded(undoStack, getPieces());
        redoStack.clear();
    }

    /** スタックへ積んだ上で、上限を超えていれば最古(末尾)のスナップショットを1件破棄する。 */
    private static void pushBounded(Deque<List<Piece>> stack, List<Piece> snapshot) {
        stack.push(snapshot);
        if (stack.size() > MAX_UNDO_HISTORY) {
            stack.removeLast();
        }
    }

    @Override
    public void insert(int offset, String text) {
        snapshotBeforeEdit();
        super.insert(offset, text);
        modified = true;
        version++;
        maybeCompactAddBuffer();
    }

    @Override
    public void delete(int offset, int length) {
        snapshotBeforeEdit();
        super.delete(offset, length);
        modified = true;
        version++;
        maybeCompactAddBuffer();
    }

    /**
     * COMPACTION_INTERVAL回の編集ごとに、addBufferのコンパクション(削除済みで
     * どのスナップショットからも参照されなくなった範囲の破棄)を試みる。
     *
     * <p>undoStack/redoStackの中身(過去のPieceスナップショット)もaddBufferの座標系に
     * 依存しているため、コンパクション後は必ず書き換える必要がある。{@link Deque}は
     * その場での要素置換に向かないため、いったん{@link ArrayList}へ写して
     * {@link PieceTable#compactAddBuffer}に渡し、書き換え後の結果でスタックを作り直す。
     *
     * <p><b>元の順序を保ったまま作り直せる理由</b>: {@code new ArrayList<>(stack)}は
     * {@link ArrayDeque}のイテレーション順(先頭=最新のpush、末尾=最古)をそのまま保持する。
     * 空のDequeに対してその順序のまま{@code addLast}で1件ずつ詰め直すと、最初の要素が
     * 先頭(=直後にpopされる要素)になり、以後の要素が順に後ろへ並ぶため、
     * push/popの意味(直近の編集から順にundoできる)を完全に保ったまま復元できる。
     */
    private void maybeCompactAddBuffer() {
        if (++editsSinceCompaction < COMPACTION_INTERVAL) return;
        editsSinceCompaction = 0;

        List<List<Piece>> undoList = new ArrayList<>(undoStack);
        List<List<Piece>> redoList = new ArrayList<>(redoStack);
        List<List<Piece>> combined = new ArrayList<>(undoList.size() + redoList.size());
        combined.addAll(undoList);
        combined.addAll(redoList);

        List<List<Piece>> remapped = compactAddBuffer(combined);

        undoStack.clear();
        for (List<Piece> snap : remapped.subList(0, undoList.size())) {
            undoStack.addLast(snap);
        }
        redoStack.clear();
        for (List<Piece> snap : remapped.subList(undoList.size(), remapped.size())) {
            redoStack.addLast(snap);
        }
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        pushBounded(redoStack, getPieces());
        restorePieces(undoStack.pop());
        modified = true;
        version++;
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        pushBounded(undoStack, getPieces());
        restorePieces(redoStack.pop());
        modified = true;
        version++;
    }

    /** バッファの版数。insert/delete/undo/redoのたびに増分する（コンパイル診断の再解析要否判定用）。 */
    public long getVersion() { return version; }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    /** テスト専用: undo履歴の件数上限が守られているかを確認するためのアクセサ。 */
    int undoStackSizeForTest() { return undoStack.size(); }

    /** テスト専用: COMPACTION_INTERVALを待たずに即座にaddBufferのコンパクションを実行する。 */
    void compactNowForTest() {
        editsSinceCompaction = COMPACTION_INTERVAL;
        maybeCompactAddBuffer();
    }

    /** :wa/:qa 用。最後の保存（{@link #markSaved()}）以降に編集操作が行われたか。 */
    public boolean isModified() { return modified; }

    /** 保存成功後に呼び、以降の isModified() を false に戻す。 */
    public void markSaved() { modified = false; }
}
