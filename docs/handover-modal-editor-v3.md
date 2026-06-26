# 作業引継書：② modal-editing-engine v3（アンドゥ/リドゥ）

作成日: 2026-06-24
前セッションブランチ: `claude/command-mode-v2-1kcesl`（main にマージ済み）

---

## 前セッションまでの完了状態

| Skill | 状態 | テスト |
|---|---|---|
| ① `editor-buffer-architecture` | ✅ 完了 | 15/15 PASS |
| ② `modal-editing-engine` | ✅ **v2** 完了（NORMAL/INSERT/COMMAND・:w/:e/:q/:wq） | 60/60 PASS |
| ⑤ `gui-rendering-pipeline` | ✅ **v2**（縦スクロール）完了 | 8/8 PASS |
| その他 | 未着手 | — |

### main ブランチの最新コミット

```
a98c7ca  Merge: ② modal-editing-engine v2 コマンドラインモード・ファイル保存/開閉
e62e014  docs: README.md に ② v2 コマンドラインモード・ファイル操作の内容を追記
e90e8b8  ② modal-editing-engine v2: コマンドラインモード・ファイル保存/開閉を実装
8261cfa  docs: ② v2 作業引継書とClaude Code次セッション用プロンプトを追加
7120c78  Merge: ⑤ gui-rendering-pipeline v2 スクロール対応
```

---

## 今セッションで実装する機能

### ② modal-editing-engine v3：アンドゥ/リドゥ

#### 設計の前提（必読）

アンドゥ/リドゥの設計詳細は `.claude/skills/editor-buffer-architecture/references/piece-table-delete-and-undo.md` に記載済み。必ずそちらを参照してから実装に着手すること。

要点:
- `PieceTable` は `insert`/`delete` で **pieces リストの参照だけを操作し、original/addBuffer の実データは変更しない**
- `Piece` は `record`（イミュータブル）なので `List.copyOf(pieces)` で参照の浅いコピーが取れ、実データ複製コストがほぼゼロ
- この性質によりアンドゥ/リドゥは「スナップショット（pieces リストのコピー）のスタック管理」で実現できる

---

#### 変更1：PieceTable に protected メソッドを追加

`src/dev/vimacs/buffer/PieceTable.java` への最小変更。外部 API は変えない。

```java
// UndoablePieceTable がスナップショットを取るための読み取り口
protected List<Piece> getPieces() {
    return List.copyOf(pieces);
}

// UndoablePieceTable がスナップショットから復元するための書き込み口
protected void restorePieces(List<Piece> snapshot) {
    pieces.clear();
    pieces.addAll(snapshot);
}
```

#### 変更2：UndoablePieceTable クラスを新規作成

`src/dev/vimacs/buffer/UndoablePieceTable.java` を新規作成する。

```java
package dev.vimacs.buffer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class UndoablePieceTable extends PieceTable {

    private final Deque<List<Piece>> undoStack = new ArrayDeque<>();
    private final Deque<List<Piece>> redoStack = new ArrayDeque<>();

    public UndoablePieceTable(String initialText) {
        super(initialText);
    }

    private void snapshotBeforeEdit() {
        undoStack.push(getPieces());
        redoStack.clear(); // 新しい編集はリドゥ履歴を無効化する
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
        if (undoStack.isEmpty()) return;
        redoStack.push(getPieces());
        restorePieces(undoStack.pop());
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(getPieces());
        restorePieces(redoStack.pop());
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }
}
```

#### 変更3：ModalEditor の buffer 型を変更

`src/dev/vimacs/editor/ModalEditor.java` の変更点：

**フィールドの型変更：**
```java
// 変更前
private PieceTable buffer;

// 変更後（UndoablePieceTable に昇格）
private UndoablePieceTable buffer;
```

**コンストラクタの変更：**
```java
// すべてのコンストラクタで PieceTable → UndoablePieceTable に変更
this.buffer = new UndoablePieceTable(initialText);
```

**loadFromFile() の変更：**
```java
// :e で新しいファイルを開いたときも UndoablePieceTable で差し替える
buffer = new UndoablePieceTable(content);
```

**processKey() に Ctrl+R を追加（NORMALモード限定）：**

```java
public void processKey(int keyCode, char keyChar, int modifiers) {
    boolean ctrl = (modifiers & KeyEvent.CTRL_DOWN_MASK) != 0;

    // Ctrl+R は NORMALモードでのリドゥ（switch の前で先に捌く）
    if (mode == Mode.NORMAL && ctrl && keyCode == KeyEvent.VK_R) {
        buffer.redo();
        clampCursorAfterUndoRedo();
        syncCanvas();
        return;
    }

    switch (mode) { ... }
}
```

**processNormalKey() に u を追加：**
```java
case 'u' -> {
    buffer.undo();
    clampCursorAfterUndoRedo();
}
```

**clampCursorAfterUndoRedo() を追加：**

アンドゥ/リドゥでテキストが短くなった場合にカーソルを有効範囲内に収める。

```java
private void clampCursorAfterUndoRedo() {
    String[] lines = getLines();
    cursorRow = Math.min(cursorRow, Math.max(0, lines.length - 1));
    int lineLen = (cursorRow < lines.length) ? lines[cursorRow].length() : 0;
    cursorCol = Math.min(cursorCol, Math.max(0, lineLen - 1));
}
```

---

## テスト追加要件

`test/dev/vimacs/buffer/UndoablePieceTableTest.java` を**新規作成**し、
`test/dev/vimacs/editor/ModalEditorTest.java` にもキーバインド側のテストを追記する。

既存の 60 ケースはすべて PASS のまま保つこと（回帰禁止）。

### UndoablePieceTableTest（新規）

```
[アンドゥ基本]
  PASS: insert 後に undo() でテキストが元に戻る
  PASS: delete 後に undo() でテキストが元に戻る
  PASS: undoStack が空のとき undo() を呼んでも例外なく何も起きない

[リドゥ基本]
  PASS: undo() 後に redo() でテキストが再適用される
  PASS: redoStack が空のとき redo() を呼んでも例外なく何も起きない

[アンドゥ連続]
  PASS: 3回挿入後、3回 undo() で初期テキストに戻る
  PASS: 3回 undo() 後に 3回 redo() で最終テキストに戻る

[リドゥ無効化]
  PASS: undo() 後に新しい insert() をすると redoStack が空になる

[canUndo / canRedo]
  PASS: 初期状態で canUndo()==false, canRedo()==false
  PASS: insert 後に canUndo()==true
  PASS: undo 後に canRedo()==true
```

### ModalEditorTest への追記

```
[u キー: アンドゥ]
  PASS: INSERT で文字入力後 ESC して u → テキストが前の状態に戻る
  PASS: アンドゥ後にカーソルが有効範囲内にクランプされる
  PASS: アンドゥできない状態で u を押しても何も起きない（クラッシュしない）

[Ctrl+R キー: リドゥ]
  PASS: u の後 Ctrl+R → テキストが再適用される
  PASS: リドゥできない状態で Ctrl+R を押しても何も起きない
```

---

## 実装上の注意点

1. **UndoablePieceTable の継承設計**
   `PieceTable` の `pieces` フィールドはパッケージプライベート（`private`）のまま。`getPieces()` / `restorePieces()` を `protected` で追加することでサブクラスからのみアクセスを許可する。テスト等の外部からは引き続きアクセス不可。

2. **アンドゥ単位のグループ化（今回はスコープ外）**
   現実装では `insert`/`delete` 呼び出し1回が1アンドゥ単位になる。Vim の `cw`（削除＋挿入の組み合わせ）のような複合操作を1単位にまとめる「トランザクション」機構は今回は実装しない。

3. **:e 後のアンドゥ履歴**
   `:e` でバッファを `new UndoablePieceTable(content)` に差し替えるため、以前のファイルの編集履歴は自動的にリセットされる。これは意図した挙動。

4. **importの追記**
   `ModalEditor.java` に `import dev.vimacs.buffer.UndoablePieceTable;` を追加すること。

---

## 未解決の既知制限（このセッションでは対応不要）

| 制限 | 詳細 |
|---|---|
| 横スクロールなし | 長い行が画面外にはみ出す（⑤ v3 で対応予定） |
| VISUALモードなし | 範囲選択・ヤンク・ペーストが未実装（② v4以降） |
| アンドゥ単位のグループ化なし | 複合コマンドが複数ステップに分解されてアンドゥされる |
| `getTextInRange` を描画に未使用 | 現状 `getText()` で全文を渡す（巨大ファイルで問題が出た場合に対応） |

---

## 完了条件

1. `./scripts/test.sh` で全テストクラスが PASS
   - `UndoablePieceTableTest` が新規追加されていること
   - `ModalEditorTest` のケース数が 60 より増えていること
   - 既存ケースの回帰なし
2. `./scripts/run.sh` で起動後、テキストを編集 → `u` でアンドゥ → `Ctrl+R` でリドゥ できること（目視確認）
3. `docs/session-log.md` に今セッションの作業記録・次フェーズ引き継ぎ事項を追記
4. コミット・プッシュ完了（main へのマージはユーザーの指示を待つ）
