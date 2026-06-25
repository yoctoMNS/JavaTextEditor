# Vimacs Editor — ② modal-editing-engine v4 実装指示

## セッション開始時に必ず読むファイル（この順で・読み終えるまで実装着手禁止）

1. `CLAUDE.md` — 技術制約・パッケージ名・ロードマップ（厳守事項の源泉）
2. `docs/session-log.md` — 全作業履歴と前セッション引き継ぎ事項
3. `docs/handover-modal-editor-v4.md` — 今セッションの詳細仕様書（必読）
4. `src/dev/vimacs/editor/ModalEditor.java` — 変更対象：VISUALモード・ヤンク/ペースト追加
5. `src/dev/vimacs/ui/EditorCanvas.java` — 変更対象：選択ハイライト・VISUAL ステータス表示
6. `test/dev/vimacs/editor/ModalEditorTest.java` — 既存テスト構造の把握

---

## 現在の実装状態（前セッション完了済み）

| Skill | 状態 | テスト数 |
|---|---|---|
| ① editor-buffer-architecture | ✅ 完了 | 15/15 |
| ② modal-editing-engine | ✅ v3 完了（NORMAL/INSERT/COMMAND・アンドゥ/リドゥ） | 65/65 |
| ⑤ gui-rendering-pipeline | ✅ v2 完了（縦スクロール） | 8/8 |

**main ブランチは最新。新しいフィーチャーブランチを作成してから作業を開始すること。**

```bash
git checkout main && git checkout -b claude/visual-mode-v4-XXXXXX
```

---

## 今セッションで実装する機能

### ② v4：VISUALモード・ヤンク/ペースト

#### 変更1：ModalEditor の Mode enum に VISUAL を追加

```java
private enum Mode { NORMAL, INSERT, COMMAND, VISUAL }
```

フィールドを追加:
```java
private int anchorRow = 0;
private int anchorCol = 0;
private String yankRegister = "";
```

#### 変更2：processKey() に VISUAL ブランチと ESC 捌きを追加

`processKey()` の冒頭（switch の前）に以下を追加する。
Ctrl+R の処理と並べて書くこと:

```java
if (mode == Mode.VISUAL && keyCode == KeyEvent.VK_ESCAPE) {
    mode = Mode.NORMAL;
    syncCanvas();
    return;
}
```

switch に VISUAL ブランチを追加:
```java
case VISUAL -> processVisualKey(keyChar);
```

#### 変更3：processNormalKey() に v/x/p/P を追加

```java
case 'v' -> { anchorRow = cursorRow; anchorCol = cursorCol; mode = Mode.VISUAL; }
case 'x' -> deleteCharAtCursor();
case 'p' -> pasteAfter();
case 'P' -> pasteBefore();
```

#### 変更4：processVisualKey() を新規実装

h/j/k/l 移動、`y`（ヤンク→NORMAL）、`d`（削除→NORMAL）。
詳細は `handover-modal-editor-v4.md` の「変更1-4」を参照。

#### 変更5：ヘルパーメソッドを追加

- `getSelectedText()` — アンカー〜カーソルの inclusive テキスト取得
- `deleteSelected()` — 選択範囲を削除してカーソルを先頭へ
- `moveCursorToOffset(int offset)` — オフセットから行/列を逆算
- `deleteCharAtCursor()` — x コマンド
- `pasteAfter()` / `pasteBefore()` — p/P コマンド

各メソッドの実装コードは `handover-modal-editor-v4.md` の「変更2」に記載。

#### 変更6：パブリックアクセサを追加

```java
public boolean isVisualMode()   { return mode == Mode.VISUAL; }
public String getYankRegister() { return yankRegister; }
```

#### 変更7：syncCanvas() で選択状態を同期

```java
canvas.setVisualMode(mode == Mode.VISUAL);
if (mode == Mode.VISUAL) {
    canvas.setSelection(anchorRow, anchorCol, cursorRow, cursorCol);
} else {
    canvas.clearSelection();
}
```

---

#### 変更8：EditorCanvas に選択ハイライト機能を追加

追加するフィールド・メソッド:
- `boolean visualMode` フィールド
- `int selAnchorRow/Col, selCursorRow/Col` フィールド
- `setVisualMode(boolean)` / `setSelection(...)` / `clearSelection()` セッター
- `drawSelectionHighlight()` — 選択範囲に背景色（`theme.accent`）を描画
- `drawStatusLine()` に `"-- VISUAL --"` 分岐を追加
- `paintComponent()` に選択ハイライト描画の呼び出しを追加

詳細な実装コードは `handover-modal-editor-v4.md` の「変更4」に記載。

---

## テスト追加要件

### ModalEditorTest への追記

既存の 65 ケースはすべて PASS のまま保つこと（回帰禁止）。

```
[v キー: VISUALモード基本]
  PASS: v でVISUALモードに入る
  PASS: ESC でNORMALモードに戻る（選択キャンセル）
  PASS: VISUAL中に h/l でカーソルが移動する

[y キー: ヤンク]
  PASS: v → l → y で選択文字がヤンクされ yankRegister に入る
  PASS: ヤンク後にNORMALモードに戻る
  PASS: yankRegister が空の状態で p を押してもクラッシュしない

[d キー: 選択削除]
  PASS: v → l → d で選択範囲が削除される
  PASS: 削除後にNORMALモードに戻る
  PASS: 削除後にカーソルが選択開始位置にある

[p/P キー: ペースト]
  PASS: ヤンク後 p でカーソル後にテキストが挿入される
  PASS: ヤンク後 P でカーソル前にテキストが挿入される

[x キー: 1文字削除]
  PASS: x でカーソル下の文字が削除される
  PASS: x で行末文字を削除するとカーソルがクランプされる
  PASS: 空行で x を押してもクラッシュしない
```

### EditorCanvasTest への追記

```
[VISUALモード: ステータス行]
  PASS: setVisualMode(true) で "-- VISUAL --" が表示される
  PASS: clearSelection() + setVisualMode(false) で "-- NORMAL --" に戻る
```

---

## 完了条件（すべて満たしてからコミット・プッシュ）

1. `./scripts/test.sh` で全テストクラスが PASS
   - `ModalEditorTest` のケース数が 65 より増えていること（目標 78+）
   - `EditorCanvasTest` のケース数が 8 より増えていること（目標 10+）
   - 既存ケースの回帰なし
2. `./scripts/run.sh` で起動し以下を目視確認:
   - `v` キーでVISUALモード（`-- VISUAL --` 表示・選択ハイライト表示）
   - `l` でカーソル移動しながら選択範囲が広がる
   - `y` でヤンク → `p` でペーストできる
   - `d` で選択範囲が削除される
3. `docs/session-log.md` に今セッションの作業記録・次フェーズ引き継ぎ事項を追記
4. コミット・プッシュ完了（main へのマージはユーザーの指示を待つ）

---

## 制約（変更禁止）

- Java 21 / Java SE 標準 API のみ（外部ライブラリ禁止）
- ビルド: `./scripts/build.sh` のみ（Maven/Gradle 禁止）
- テスト: `main` メソッド形式のみ（JUnit 禁止）

## このセッションでは実装しない（スコープ外）

- 行単位ヤンク（`yy`）・行削除（`dd`）— v5 以降
- VISUAL LINE モード（`V`）— v5 以降
- アンドゥ単位のグループ化（複合操作を1アンドゥ単位にまとめる）
- 横スクロール（⑤ v3 で別途対応）
- ④ keymap-conflict-resolution（③ 完了を待つ必要あり）
