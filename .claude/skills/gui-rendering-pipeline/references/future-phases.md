# 将来フェーズの設計（v2: スクロール、v3: ウィンドウ分割）

このファイルは`SKILL.md`から参照される、v1完成後の拡張方針。v1（単一バッファ静的表示）が動作確認できてから着手すること。

> **実装状態（2026-07-08 更新）**
> - v2（縦スクロール）: ✅ 実装済み（`scrollRow` / `ensureCursorVisible`）
> - v3（横スクロール + JSplitPane ウィンドウ分割）: ✅ 実装済み（`scrollCol` / `ensureCursorColVisible` / Ctrl+W フォーカス切り替え）
> - v4: Ctrl+Alt+矢印によるペインリサイズ ✅ 実装済み（`references/pane-resize.md` 参照）
> - v4 その他の候補: 本ファイル末尾の「v4 候補」セクション参照

---

## v2: スクロール対応（✅ 実装済み）

### 前提作業：PieceTableへのメソッド追加（実装済み）

①(`editor-buffer-architecture`)のSKILL.mdで触れられていた「範囲取得メソッド」をここで実装した。

```java
/**
 * 文書全体ではなく、指定した文字オフセット範囲だけを取り出す。
 * なぜ必要か: getText()は全ピースを連結するため、数十万行規模のファイルで
 * 画面に表示する数十行だけを取り出したい場合でも、毎回全文字列を構築してしまう。
 */
public String getTextInRange(int startOffset, int endOffset) {
    StringBuilder result = new StringBuilder(endOffset - startOffset);
    int runningOffset = 0;
    for (Piece p : pieces) {
        int pieceEnd = runningOffset + p.length();
        if (pieceEnd > startOffset && runningOffset < endOffset) {
            int from = Math.max(0, startOffset - runningOffset);
            int to = Math.min(p.length(), endOffset - runningOffset);
            String source = (p.source() == Piece.Source.ORIGINAL) ? original : addBuffer.toString();
            result.append(source, p.start() + from, p.start() + to);
        }
        runningOffset = pieceEnd;
        if (runningOffset >= endOffset) break; // 早期終了で巨大ファイルでも余計な走査をしない
    }
    return result.toString();
}

/**
 * 「N行目が何文字目から始まるか」を調べる。
 * 注意: これは毎回先頭から数えるため、数十万行規模で頻繁に呼ぶと遅い。
 * 実際に遅さが問題になった場合は「改行位置の索引（行番号→オフセットの配列）」を
 * 別途キャッシュし、編集時に差分更新する設計に切り替えること（①と同じ「まずは
 * シンプルな実装で動かし、遅ければ最適化する」という方針を踏襲する）。
 */
public int offsetOfLine(int lineNumber) {
    String fullText = getText(); // 簡易実装。最適化の余地は上記の通り
    int offset = 0;
    int currentLine = 0;
    for (int i = 0; i < fullText.length() && currentLine < lineNumber; i++) {
        if (fullText.charAt(i) == '\n') currentLine++;
        offset = i + 1;
    }
    return offset;
}
```

### 実装済みの設計

`EditorCanvas` に `scrollRow`（縦スクロール行番号）を持たせ、`ensureCursorVisible(int cursorRow)` でカーソルが表示範囲に収まるよう自動調整する。`paintComponent` は `scrollRow` 〜 `scrollRow + visibleRows` の行だけを描画する。

カーソルの X 座標は行先頭から `col` 文字分の `charCellWidth` を累積する `xForCol` で正確に計算する（v1 の `cursorCol * charWidth` 簡易計算を廃止）。

---

## v3: 横スクロール + ウィンドウ分割（✅ 実装済み）

### 横スクロール実装（EditorCanvas）

`scrollCol`（半角セル単位の横スクロール量）を追加し、描画時に `scrollOffsetX = scrollCol * charWidth` ピクセル分左シフトする。

```java
// drawLineWithFullWidthSupport の核心部分
int x = -scrollOffsetX;
for (int i = 0; i < line.length(); ) {
    int codePoint = line.codePointAt(i);
    int charPixelWidth = charWidth * charCellWidth(codePoint);
    if (x + charPixelWidth > 0 && x < getWidth()) {
        g2.drawString(new String(Character.toChars(codePoint)), x, y);
    }
    x += charPixelWidth;
    i += Character.charCount(codePoint);
    if (x >= getWidth()) break;
}
```

カーソル移動後は `ensureCursorColVisible(int col, String line)` で横スクロールを追従させる。全角文字の2セル幅も `cellsForCol` で正確に計算する。

### ウィンドウ分割実装（Main.java）

各ペインが独立した `ModalEditor + EditorCanvas` を持つ設計を採用した（future-phases.md の「モード共有」案より実装がシンプルなため）。

```java
JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftCanvas, rightCanvas);
splitPane.setResizeWeight(0.5);
```

`Ctrl+W` は `KeyboardFocusManager` の dispatcher レベルでインターセプトし、`int[] activePaneIdx` でアクティブペインを切り替える。

---

## v4: Ctrl+Alt+矢印によるペインリサイズ（✅ 実装済み）

詳細な仕様決定・実装は `references/pane-resize.md` に分離した（現在のアクティブペインを
Ctrl+Alt+矢印で伸縮する機能。祖先の`JSplitPane`を辿って対応方向の分割を1つだけ調整する設計）。

## 分割ペインの初期フォントサイズは分割元ペインを引き継ぐ（✅ 実装済み・2026-07）

- **要望**: 「分割ペインのフォントは他のペインと連動しなくて良いが、分割した際の初期フォントの
  大きさは、分割元のペインで設定されていたフォントの大きさにしてほしい」。
- **修正前の挙動**: `Main.createLeaf(text, path)` は常に起動時固定値
  `initialCellW`/`initialCellH`（画面解像度から算出する static フィールド）で
  `canvas.setInitialCellSize(...)` していたため、`Ctrl+Shift+矢印` でどのペインのフォントを
  拡大縮小していても、新しく`:split`/`:vsplit`（`ss`/`sv`）で分割したペインは常に起動時サイズに
  戻ってしまっていた。
- **修正**: `createLeaf(text, path, int cellW, int cellH)`オーバーロードを追加し、
  `setSplitHorizontalCallback`/`setSplitVerticalCallback`（`Main.setupSplitCallbacks`）が
  分割元の`active[0].canvas().getCellW()`/`getCellH()`を渡すように変更した。従来の
  `createLeaf(text, path)`（初回ペイン生成、`Main`起動処理から1回だけ呼ばれる）は
  `initialCellW`/`initialCellH`を使う後方互換オーバーロードとして残した。
- **分割後は完全に独立**: `setInitialCellSize`はあくまで生成直後の初期値であり、分割後に
  一方のペインで`Ctrl+Shift+矢印`を押しても他方には一切影響しない（各`EditorCanvas`が
  自分の`cellW`/`cellH`フィールドを個別に持つ既存設計のまま。今回変更したのは「分割**した
  瞬間**の初期値をどこから取るか」のみ）。
- **`:split`/`:vsplit`コマンドも同じコールバック経由**なので、キー操作（`ss`/`sv`）と
  コロンコマンドのどちらで分割しても同じ挙動になる。

## v4 候補（未着手）

以下は将来検討する機能。優先度は他 Skill（③④⑧）の進捗に依存する。

### 候補A: 行番号表示

`EditorCanvas` の左端にガター領域（行番号列）を追加する。
- `GUTTER_WIDTH = charWidth * 4` 程度の固定幅を確保
- `paintComponent` 内でガター背景を薄い色で塗り、行番号を右揃えで描画
- テキスト描画と選択ハイライトの `x` 開始位置をガター幅分ずらす

```java
private static final int GUTTER_COLS = 5; // 行番号表示に使う列数（右端に " " を含む）

// paintComponent 内
int gutterWidth = charWidth * GUTTER_COLS;
// ガター描画
g2.setColor(theme.accent);
g2.fillRect(0, 0, gutterWidth, getHeight() - lineHeight);
for (int row = scrollRow; row < lastRow; row++) {
    int screenRow = row - scrollRow;
    String lineNum = String.format("%" + (GUTTER_COLS - 1) + "d ", row + 1);
    g2.setColor(theme.foreground);
    g2.drawString(lineNum, 0, (screenRow + 1) * lineHeight);
}
// テキスト描画は x = gutterWidth から開始
```

### 候補B: 検索ハイライト（/pattern との連携）

② v6 で `/pattern` 検索が実装された後、マッチ位置を `EditorCanvas` に渡してハイライト描画する。
- `List<int[]> searchMatches` フィールドを追加（各要素 = `{row, colStart, colEnd}`）
- VISUAL ハイライトと同じ `drawSelectionHighlight` の仕組みを流用可能

### 候補C: `:split` / `:vsplit` コマンド

動的にペインを追加・削除する機能。`JSplitPane` を入れ子にする設計か、
`JPanel` + `CardLayout` で管理する設計が候補。
現状の2ペイン固定（`Main.java`）から、`PaneManager` クラスに責務を分離する必要がある。

---

## よくある誤解・つまずきポイント（v3 実装で判明したもの）

> ⚠️ **v3実装時の注意: scrollCol の単位**
> `scrollCol` はピクセル数ではなくセル数（半角文字単位）で持つこと。
> 全角文字がある行では `cellsForCol` でセル数を正確に数える必要があり、
> 単純に `cursorCol * charWidth` でオフセットを計算すると全角行でずれる。

> ⚠️ **v3実装時の注意: JSplitPane でのキーフォーカス**
> `JSplitPane` を使うと各子コンポーネントが Swing のフォーカス管理に入り、
> `KeyboardFocusManager` との兼ね合いが複雑になる。
> 解決策: アクティブペインを `int[] activePaneIdx` で自前管理し、
> dispatcher 内で明示的にアクティブな `ModalEditor` だけにキーを送る。
