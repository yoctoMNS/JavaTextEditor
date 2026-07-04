# ピーステーブルの delete 実装とアンドゥ機構

このファイルは `SKILL.md` から参照される詳細資料。`insert`の実装は理解済みであることを前提にする。

## delete の実装

### 考え方

`insert`では「1点にピースを挟み込む」だけで済んだが、`delete(start, length)`では削除範囲が複数のピースにまたがる可能性がある。そのため「削除範囲に重なる部分を持つピースをすべて洗い出し、各ピースの『削除範囲の外側に残る部分』だけを残す」という処理になる。

```java
/**
 * offsetからlength文字分を削除する。
 * なぜ全ピースを舐めるループになるか:
 * 削除範囲が複数のピースに重なるケース（例: 1文字目のピースの末尾2文字 + 2番目のピース全体
 * + 3番目のピースの先頭3文字を削除する、など）に対応する必要があるため。
 */
public void delete(int offset, int length) {
    if (length <= 0) return;
    int deleteEnd = offset + length;
    List<Piece> result = new ArrayList<>();
    int runningOffset = 0;

    for (Piece p : pieces) {
        int pieceStart = runningOffset;
        int pieceEnd = runningOffset + p.length();
        runningOffset = pieceEnd;

        boolean noOverlap = (pieceEnd <= offset) || (pieceStart >= deleteEnd);
        if (noOverlap) {
            // 削除範囲と全く重ならないピースはそのまま残す
            result.add(p);
            continue;
        }

        // 重なる場合、ピースのうち「削除範囲より前」と「削除範囲より後」の部分だけを残す
        int keepBeforeLen = Math.max(0, offset - pieceStart);
        int keepAfterStart = Math.max(pieceStart, deleteEnd);
        int keepAfterLen = pieceEnd - keepAfterStart;

        if (keepBeforeLen > 0) {
            result.add(new Piece(p.source(), p.start(), keepBeforeLen));
        }
        if (keepAfterLen > 0) {
            result.add(new Piece(p.source(), p.start() + (keepAfterStart - pieceStart), keepAfterLen));
        }
        // keepBeforeLen も keepAfterLen も0なら、このピースは完全に削除範囲に含まれ、消える
    }

    pieces.clear();
    pieces.addAll(result);
}
```

## アンドゥ／リドゥの実装

### なぜ「ほぼ無料」なのか

`insert`も`delete`も、`original`バッファと`addBuffer`の中身そのものは一切書き換えない。変化するのは`pieces`（`Piece`オブジェクトへの参照のリスト）だけである。`Piece`自体はイミュータブル（recordなので再代入不可）なので、**`List<Piece>`をコピーするコストは「参照のコピー」だけで済み、実データの複製は発生しない**。これがギャップバッファ方式（実データそのものを上書きする）との決定的な違いであり、アンドゥが軽量に実装できる理由。

### 実装

```java
import java.util.ArrayDeque;
import java.util.Deque;

public class UndoablePieceTable extends PieceTable {

    // 編集前のピースリストのスナップショットを積んでいくスタック
    private final Deque<List<Piece>> undoStack = new ArrayDeque<>();
    private final Deque<List<Piece>> redoStack = new ArrayDeque<>();

    /** 編集操作の直前に必ず呼ぶ：現在のピースリストを保存する */
    private void snapshotBeforeEdit() {
        // List.copyOf は「浅いコピー」。Piece自体がイミュータブルなので浅いコピーで十分。
        undoStack.push(List.copyOf(getPieces()));
        redoStack.clear(); // 新しい編集をしたら、それまでのredo履歴は無効になる
    }

    @Override
    public void insert(int offset, String text) {
        snapshotBeforeEdit();
        super.insert(offset, text);
    }

    @Override
    public void delete(int offset, int length) {
        snapshotBeforeEdit();
        super.delete(offset, length);
    }

    public void undo() {
        if (undoStack.isEmpty()) return; // これ以上戻れない
        redoStack.push(List.copyOf(getPieces()));
        restorePieces(undoStack.pop());
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(List.copyOf(getPieces()));
        restorePieces(redoStack.pop());
    }
}
```

※ `getPieces()`（保護されたgetter）と`restorePieces(List<Piece>)`（パッケージプライベートのsetter）を`PieceTable`側に追加する必要がある。これは実装時に追記すること。

### 注意点

Vimのアンドゥは「1回のキー入力（例: `dd`）」を1つのアンドゥ単位として扱う。上記の実装は`insert`/`delete`メソッド単位でスナップショットを取るため、もし1回のキー入力が複数回の`insert`/`delete`呼び出しに分解される場合（例: 置換コマンド`cw`が内部的にdelete→insertの2回呼び出しになる場合）、アンドゥ単位がユーザーの感覚とズレる可能性がある。これを防ぐには「編集操作のグループ化」（トランザクションのように、複数の変更を1つのアンドゥ単位としてまとめる仕組み）が必要になる。この詳細は`modal-editing-engine`スキル側でコマンドの単位を扱う際に再検討すること。
