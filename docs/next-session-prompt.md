# Vimacs Editor — ② modal-editing-engine v3 実装指示

## セッション開始時に必ず読むファイル（この順で・読み終えるまで実装着手禁止）

1. `CLAUDE.md` — 技術制約・パッケージ名・ロードマップ（厳守事項の源泉）
2. `docs/session-log.md` — 全作業履歴と前セッション引き継ぎ事項
3. `docs/handover-modal-editor-v3.md` — 今セッションの詳細仕様書（必読）
4. `.claude/skills/editor-buffer-architecture/references/piece-table-delete-and-undo.md` — アンドゥ設計の根拠（必読）
5. `src/dev/vimacs/buffer/PieceTable.java` — 変更対象：protected メソッドを追加する
6. `src/dev/vimacs/editor/ModalEditor.java` — 変更対象：buffer 型変更・u/Ctrl+R 追加
7. `test/dev/vimacs/editor/ModalEditorTest.java` — 既存テスト構造の把握

---

## 現在の実装状態（前セッション完了済み）

| Skill | 状態 | テスト数 |
|---|---|---|
| ① editor-buffer-architecture | ✅ 完了 | 15/15 |
| ② modal-editing-engine | ✅ v2 完了（NORMAL/INSERT/COMMAND・:w/:e/:q/:wq） | 60/60 |
| ⑤ gui-rendering-pipeline | ✅ v2 完了（縦スクロール） | 8/8 |

**main ブランチは最新。新しいフィーチャーブランチを作成してから作業を開始すること。**

```bash
git checkout main && git checkout -b claude/undo-redo-v3-XXXXXX
```

---

## 今セッションで実装する機能

### ② v3：アンドゥ/リドゥ

#### 変更1：PieceTable に protected アクセサを追加

`src/dev/vimacs/buffer/PieceTable.java` に以下を追加する。
サブクラス（`UndoablePieceTable`）からのみ参照できるよう `protected` にすること。

```java
protected List<Piece> getPieces() {
    return List.copyOf(pieces);
}

protected void restorePieces(List<Piece> snapshot) {
    pieces.clear();
    pieces.addAll(snapshot);
}
```

#### 変更2：UndoablePieceTable を新規作成

`src/dev/vimacs/buffer/UndoablePieceTable.java` を新規作成する。
設計の詳細は `handover-modal-editor-v3.md` と `piece-table-delete-and-undo.md` を参照。

重要な設計判断：
- `snapshotBeforeEdit()` を `insert`/`delete` の先頭で呼ぶ（`@Override` で透過的に差し込む）
- 新しい `insert`/`delete` が呼ばれた瞬間に `redoStack.clear()` して履歴を無効化する
- `canUndo()` / `canRedo()` はステータス行への表示や将来のキーバインド制御に使う

#### 変更3：ModalEditor の変更

追加するキーバインド：

| モード | キー | 動作 |
|---|---|---|
| NORMAL | `u` | `buffer.undo()` を呼び、カーソルをクランプ |
| NORMAL | `Ctrl+R` | `buffer.redo()` を呼び、カーソルをクランプ |

Ctrl+R は `processNormalKey(char keyChar)` にキャラクタが渡らないため、
`processKey()` の冒頭で `mode == NORMAL && ctrl && keyCode == VK_R` を先に捌くこと。

アンドゥ/リドゥ後のカーソルクランプ：テキストが短くなった場合に
`cursorRow` / `cursorCol` が範囲外になるため `clampCursorAfterUndoRedo()` で正規化する。

---

## テスト追加要件

### 新規ファイル：UndoablePieceTableTest

`test/dev/vimacs/buffer/UndoablePieceTableTest.java` を新規作成する。
既存の `PieceTableTest` と同形式（`main` メソッド形式・JUnit 不使用）。

```
[アンドゥ基本]
  PASS: insert 後に undo() でテキストが元に戻る
  PASS: delete 後に undo() でテキストが元に戻る
  PASS: undoStack が空のとき undo() を呼んでも例外なく何も起きない

[リドゥ基本]
  PASS: undo() 後に redo() でテキストが再適用される
  PASS: redoStack が空のとき redo() を呼んでも例外なく何も起きない

[アンドゥ連続]
  PASS: 3回 insert 後、3回 undo() で初期テキストに戻る
  PASS: 3回 undo() 後に 3回 redo() で最終テキストに戻る

[リドゥ無効化]
  PASS: undo() 後に新しい insert() をすると canRedo()==false になる

[canUndo / canRedo]
  PASS: 初期状態で canUndo()==false かつ canRedo()==false
  PASS: insert 後に canUndo()==true
  PASS: undo 後に canRedo()==true
```

### ModalEditorTest への追記

既存の 60 ケースはすべて PASS のまま保つこと（回帰禁止）。

```
[u キー: アンドゥ]
  PASS: INSERT で文字入力後 ESC → u でテキストが元に戻る
  PASS: アンドゥ後にカーソルが有効範囲内にクランプされる
  PASS: アンドゥできない状態で u を押してもクラッシュしない

[Ctrl+R キー: リドゥ]
  PASS: u の後 Ctrl+R でテキストが再適用される
  PASS: リドゥできない状態で Ctrl+R を押してもクラッシュしない
```

---

## 完了条件（すべて満たしてからコミット・プッシュ）

1. `./scripts/test.sh` で全テストクラスが PASS
   - `UndoablePieceTableTest` が新規追加されていること
   - `ModalEditorTest` のケース数が 60 より増えていること
   - 既存ケースの回帰なし
2. `./scripts/run.sh` で起動 → テキスト編集 → `u` でアンドゥ → `Ctrl+R` でリドゥ（目視確認）
3. `docs/session-log.md` に今セッションの作業記録・次フェーズ引き継ぎ事項を追記
4. コミット・プッシュ完了（main へのマージはユーザーの指示を待つ）

---

## 制約（変更禁止）

- Java 21 / Java SE 標準 API のみ（外部ライブラリ禁止）
- ビルド: `./scripts/build.sh` のみ（Maven/Gradle 禁止）
- テスト: `main` メソッド形式のみ（JUnit 禁止）
- `Files.writeString` / `Files.readString` は Java SE 標準 API（`java.nio.file`）

## このセッションでは実装しない（スコープ外）

- アンドゥ単位のグループ化（複合コマンドを1アンドゥ単位にまとめる「トランザクション」）
- VISUALモード・範囲選択（② v4 以降）
- 横スクロール（⑤ v3 で別途対応）
- `:set` コマンドや補完・履歴
