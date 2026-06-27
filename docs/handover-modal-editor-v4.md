# 作業引継書：② modal-editing-engine v4（VISUALモード・ヤンク/ペースト）

作成日: 2026-06-25
前セッションブランチ: `claude/undo-redo-v3-k8xmqp`（main にマージ済み）

---

## 前セッションまでの完了状態

| Skill | 状態 | テスト |
|---|---|---|
| ① `editor-buffer-architecture` | ✅ 完了 | 15/15 PASS |
| ② `modal-editing-engine` | ✅ **v3** 完了（NORMAL/INSERT/COMMAND・アンドゥ/リドゥ） | 65/65 PASS |
| ⑤ `gui-rendering-pipeline` | ✅ **v2**（縦スクロール）完了 | 8/8 PASS |
| その他 | 未着手 | — |

### ⚠️ 依存関係の注意点（前セッションの記録ミスを訂正）

CLAUDE.md の依存表より:
```
| ④ | ②③ |
```
④ `keymap-conflict-resolution` は ②（完了）だけでなく **③ `extension-language-runtime`（未着手）にも依存** しているため、④ はまだ着手できない。前セッション引継書の「④ が次優先」という記述は誤り。正しい次候補は **② v4**（VISUALモード）。

### main ブランチの最新コミット

```
4be1317  Merge: ② modal-editing-engine v3 アンドゥ/リドゥ
636da90  docs: README.md に ② v3 アンドゥ/リドゥの内容を追記
8842d91  ② modal-editing-engine v3: アンドゥ/リドゥを実装
eeaa3c7  docs: ② v3 作業引継書とClaude Code次セッション用プロンプトを追加
```

---

## 今セッションで実装する機能

### ② modal-editing-engine v4：VISUALモード・ヤンク/ペースト

---

### 変更1：ModalEditor にVISUALモードを追加

#### 1-1. Mode enum に VISUAL を追加

```java
private enum Mode { NORMAL, INSERT, COMMAND, VISUAL }
```

#### 1-2. フィールドを追加

```java
private int anchorRow = 0;    // v を押した時のカーソル行
private int anchorCol = 0;    // v を押した時のカーソル列
private String yankRegister = "";  // デフォルトレジスタ（ヤンク/削除で更新）
```

#### 1-3. NORMALモードに新しいキーを追加（processNormalKey）

| キー | 処理 |
|---|---|
| `v` | `anchorRow = cursorRow; anchorCol = cursorCol; mode = Mode.VISUAL;` |
| `x` | `deleteCharAtCursor()` — カーソル下1文字を削除 |
| `p` | `pasteAfter()` — yankRegister をカーソル後に挿入 |
| `P` | `pasteBefore()` — yankRegister をカーソル前に挿入 |

`yankRegister` が空の場合、`p`/`P` は何もしない。

#### 1-4. VISUALモード処理メソッドを追加（processVisualKey）

```java
private void processVisualKey(char keyChar) {
    switch (keyChar) {
        case 'h' -> moveCursor(0, -1);
        case 'l' -> moveCursor(0, 1);
        case 'j' -> moveCursor(1, 0);
        case 'k' -> moveCursor(-1, 0);
        case 'y' -> {
            yankRegister = getSelectedText();
            mode = Mode.NORMAL;
        }
        case 'd' -> {
            yankRegister = getSelectedText();
            deleteSelected();
            mode = Mode.NORMAL;
            clampCursorForNormal();
        }
    }
}
```

ESC はキャラクタとして渡らないため `processKey()` の冒頭で捌く（Ctrl+R と同じパターン）:

```java
// processKey() の冒頭（switch の前）に追加
if (mode == Mode.VISUAL && keyCode == KeyEvent.VK_ESCAPE) {
    mode = Mode.NORMAL;
    syncCanvas();
    return;
}
```

#### 1-5. processKey() への VISUAL ブランチ追加

```java
switch (mode) {
    case INSERT  -> processInsertKey(keyCode, keyChar, modifiers);
    case COMMAND -> processCommandKey(keyCode, keyChar);
    case NORMAL  -> processNormalKey(keyChar);
    case VISUAL  -> processVisualKey(keyChar);  // ← 追加
}
```

---

### 変更2：ヘルパーメソッドを追加

#### 2-1. getSelectedText() — 選択範囲のテキストを返す

アンカーとカーソルのどちらが前にあってもよいように min/max で正規化する。
選択はカーソル下の文字を**含む**（inclusive）。

```java
private String getSelectedText() {
    int o1 = offsetAt(anchorRow, anchorCol);
    int o2 = offsetOfCursor();
    int start = Math.min(o1, o2);
    int end   = Math.min(Math.max(o1, o2) + 1, buffer.length());
    return buffer.getText().substring(start, end);
}
```

#### 2-2. deleteSelected() — 選択範囲を削除してカーソルを先頭に戻す

```java
private void deleteSelected() {
    int o1 = offsetAt(anchorRow, anchorCol);
    int o2 = offsetOfCursor();
    int start = Math.min(o1, o2);
    int end   = Math.min(Math.max(o1, o2) + 1, buffer.length());
    buffer.delete(start, end - start);
    moveCursorToOffset(start);
}
```

#### 2-3. moveCursorToOffset(int offset) — オフセットから行/列を逆算してカーソルを移動

```java
private void moveCursorToOffset(int offset) {
    String[] lines = getLines();
    int pos = 0;
    for (int i = 0; i < lines.length; i++) {
        int lineEnd = pos + lines[i].length();
        if (offset <= lineEnd) {
            cursorRow = i;
            cursorCol = offset - pos;
            return;
        }
        pos = lineEnd + 1; // +1 は改行文字
    }
    // 末尾クランプ
    cursorRow = Math.max(0, lines.length - 1);
    cursorCol = lines[cursorRow].length();
}
```

#### 2-4. deleteCharAtCursor() — x コマンド

```java
private void deleteCharAtCursor() {
    String[] lines = getLines();
    int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
    if (lineLen == 0) return;
    buffer.delete(offsetOfCursor(), 1);
    clampCursorForNormal(); // 行末文字を削除した場合のクランプ
}
```

#### 2-5. pasteAfter() / pasteBefore() — p/P コマンド

```java
private void pasteAfter() {
    if (yankRegister.isEmpty()) return;
    int offset = Math.min(offsetOfCursor() + 1, buffer.length());
    buffer.insert(offset, yankRegister);
    // カーソルをペースト先の先頭へ
    moveCursorToOffset(offset);
    clampCursorForNormal();
}

private void pasteBefore() {
    if (yankRegister.isEmpty()) return;
    buffer.insert(offsetOfCursor(), yankRegister);
    clampCursorForNormal();
}
```

---

### 変更3：パブリックアクセサを追加（テスト用）

```java
public boolean isVisualMode()  { return mode == Mode.VISUAL; }
public String getYankRegister(){ return yankRegister; }
```

---

### 変更4：EditorCanvas にVISUAL選択ハイライトを追加

#### 4-1. フィールドを追加

```java
private boolean visualMode = false;
private int selAnchorRow = -1, selAnchorCol = -1;
private int selCursorRow = -1, selCursorCol = -1;
```

#### 4-2. セッターを追加

```java
public void setVisualMode(boolean visualMode) {
    this.visualMode = visualMode;
    repaint();
}

public void setSelection(int anchorRow, int anchorCol, int cursorRow, int cursorCol) {
    this.selAnchorRow = anchorRow; this.selAnchorCol = anchorCol;
    this.selCursorRow = cursorRow; this.selCursorCol = cursorCol;
    repaint();
}

public void clearSelection() {
    this.selAnchorRow = -1;
    repaint();
}
```

#### 4-3. drawStatusLine を VISUAL 対応に変更

```java
String label = (commandLineText != null) ? commandLineText
             : visualMode   ? "-- VISUAL --"
             : insertMode   ? "-- INSERT --"
             : "-- NORMAL --";
```

#### 4-4. paintComponent に選択ハイライト描画を追加

テキスト描画の直前（背景塗りの直後）に以下を挿入:

```java
// 選択ハイライト（VISUALモード時）
if (visualMode && selAnchorRow >= 0) {
    drawSelectionHighlight(g2, lines, charWidth, lineHeight);
}
```

```java
private void drawSelectionHighlight(Graphics2D g2, String[] lines,
                                     int charWidth, int lineHeight) {
    // 正規化: (r1,c1) が前、(r2,c2) が後
    int r1 = selAnchorRow, c1 = selAnchorCol;
    int r2 = selCursorRow, c2 = selCursorCol;
    if (r1 > r2 || (r1 == r2 && c1 > c2)) {
        int tr = r1; r1 = r2; r2 = tr;
        int tc = c1; c1 = c2; c2 = tc;
    }

    g2.setColor(theme.accent); // アクセント色を選択背景に流用

    for (int row = Math.max(r1, scrollRow);
         row <= Math.min(r2, scrollRow + computeVisibleRows(lineHeight) - 1);
         row++) {
        int screenRow = row - scrollRow;
        int yTop = screenRow * lineHeight;
        String line = (row < lines.length) ? lines[row] : "";

        int colStart = (row == r1) ? c1 : 0;
        int colEnd   = (row == r2) ? c2 : Math.max(0, line.length() - 1);

        int xStart = xForCol(line, colStart, charWidth);
        // c2 はその文字自体を含む（inclusive）ので +1 文字分の幅を確保
        int xEnd   = xForCol(line, Math.min(colEnd + 1, line.length()), charWidth);
        if (xEnd <= xStart) xEnd = xStart + charWidth; // 空行・末尾でも最低1セル

        g2.fillRect(xStart, yTop, xEnd - xStart, lineHeight);
    }
}
```

#### 4-5. syncCanvas() で選択状態を同期

`ModalEditor.syncCanvas()` の末尾に追加:

```java
if (canvas != null) {
    canvas.setVisualMode(mode == Mode.VISUAL);
    if (mode == Mode.VISUAL) {
        canvas.setSelection(anchorRow, anchorCol, cursorRow, cursorCol);
    } else {
        canvas.clearSelection();
    }
}
```

---

## テスト追加要件

### ModalEditorTest への追記（既存 65 ケースは回帰禁止）

```
[v キー: VISUALモード基本]
  PASS: v でVISUALモードに入る
  PASS: ESC でNORMALモードに戻る（選択キャンセル）
  PASS: VISUAL中に h/l でカーソルが移動する

[y キー: ヤンク]
  PASS: v → l → y で選択文字がヤンクされ yankRegister に入る
  PASS: ヤンク後にNORMALモードに戻る
  PASS: yankRegister が空の初期状態でも p を押してもクラッシュしない

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

追記後の想定テスト数: 65 + 13 = **78 ケース以上**

### EditorCanvasTest への追記

```
[VISUALモード: ステータス行]
  PASS: setVisualMode(true) で drawStatusLine が "-- VISUAL --" を表示する
  PASS: clearSelection() 後に visualMode=false でステータスが "-- NORMAL --" に戻る
```

---

## 実装上の注意点

1. **processKey() での Escape 捌き順**
   VISUAL の Escape を switch の前に追加するとき、既存の Ctrl+R の処理と同じ位置に並べる。
   順序: `Ctrl+R(NORMAL)` → `ESC(VISUAL)` → `switch(mode)`

2. **ヤンク単位の制限（今回はスコープ外）**
   今回は**文字ヤンク（character-wise）のみ**。行ヤンク（`yy`）・行削除（`dd`）は v5 以降。

3. **選択ハイライト色**
   現行 `Theme.accent` を流用する。将来的にテーマへ `selection` フィールドを追加しても後方互換を保てる。

4. **アンドゥとVISUALモードの関係**
   `d`（削除）は `buffer.delete()` を呼ぶため `UndoablePieceTable` のスナップショットが自動的に取られる。VISUAL 中に `u` を押した場合は `processVisualKey` が処理されないので（NORMAL専用）、意図せずアンドゥされることはない。

5. **Theme クラスの `xForCol` への参照**
   `drawSelectionHighlight` は `xForCol(line, col, charWidth)` を呼ぶ。このメソッドは現在 `private static` なので `package-private` に変えるか、`drawSelectionHighlight` を `EditorCanvas` 内に置けばそのままアクセスできる。**後者（同一クラス内）を推奨**。

---

## 未解決の既知制限（このセッションでは対応不要）

| 制限 | 詳細 |
|---|---|
| 横スクロールなし | 長い行が画面外にはみ出す（⑤ v3 で対応予定） |
| 行単位ヤンク/削除なし | `yy`・`dd` は v5 以降 |
| アンドゥ単位のグループ化なし | v+d は「削除」1回がアンドゥ単位 |
| ④ keymap-conflict-resolution 未着手 | ③（extension-language-runtime）が完了するまで着手不可 |

---

## 完了条件

1. `./scripts/test.sh` で全テストクラスが PASS
   - `ModalEditorTest` のケース数が 65 より増えていること
   - `EditorCanvasTest` のケース数が 8 より増えていること
   - 既存ケースの回帰なし
2. `./scripts/run.sh` で起動 → `v` → カーソル移動 → `y` → `p` でペースト、`d` で削除を目視確認
3. `docs/session-log.md` に今セッションの作業記録・次フェーズ引き継ぎ事項を追記
4. コミット・プッシュ完了（main へのマージはユーザーの指示を待つ）
