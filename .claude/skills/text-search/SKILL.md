# テキスト内文字列検索（Vim 式）

## このスキルが解決すること

NORMAL モードからバッファ内の文字列を検索し、マッチ位置へカーソルをジャンプさせる。
Vim の `/` パターン検索・`*`/`#` 単語検索・`n`/`N` 次/前マッチジャンプを実装する。

---

## キーバインド一覧

| キー | モード | 動作 |
|---|---|---|
| `/` | NORMAL | SEARCH モードへ入り、パターン入力を開始する |
| Enter | SEARCH | 入力したパターンで前方検索を実行し NORMAL へ戻る |
| Esc | SEARCH | 検索をキャンセルして NORMAL へ戻る |
| `n` | NORMAL | 最後の検索方向へ次のマッチへジャンプ（折り返しあり） |
| `N` | NORMAL | 最後の検索の逆方向へジャンプ（折り返しあり） |
| `*` | NORMAL | カーソル位置の「単語」を **下方向（後方）** へ完全一致検索 |
| `#` | NORMAL | カーソル位置の「単語」を **上方向（前方）** へ完全一致検索 |

---

## 実装アーキテクチャ

### モード追加

`ModalEditor.Mode` に `SEARCH` を追加する。
SEARCH モード中はステータスバーに `/<入力中パターン>` を表示する。

```java
private enum Mode { NORMAL, INSERT, COMMAND, VISUAL, VISUAL_LINE, SEARCH }
```

### 状態フィールド（ModalEditor）

```java
private final StringBuilder searchBuffer = new StringBuilder();
private String   lastSearchPattern = "";
private boolean  lastSearchForward = true;
// 各要素: {offset, length}（buffer.getText() 上の絶対位置）
private List<int[]> searchMatches = List.of();
private int currentMatchIdx = -1;
```

### 検索ロジック

`/` 入力時:
1. SEARCH モードへ遷移、`searchBuffer` をクリア
2. Enter が押されたら `executeSearch(pattern, forward=true)` を呼ぶ

`executeSearch(pattern, forward)`:
1. `java.util.regex.Pattern.compile(pattern)` でコンパイル（PatternSyntaxException をステータス表示）
2. `buffer.getText()` 全体に `Matcher.find()` を繰り返してマッチオフセットリストを構築
3. カーソルの現在オフセットを基準に「次のマッチ」を選択（折り返し対応）
4. カーソルをマッチ先頭へ移動（`moveCursorToOffset`）
5. ハイライトリストを `EditorCanvas` に渡す

`*` / `#`:
- `wordAtCursor()` でカーソル位置の単語を取得
- `Pattern.quote(word)` を `\\b…\\b` で囲み、完全一致パターンを構築
- `executeSearch()` を呼ぶ

`n` / `N`:
- `currentMatchIdx` を ±1 して折り返し（`% size`）
- カーソルを新しいマッチへ移動

### 検索ハイライト（EditorCanvas）

`EditorCanvas` に `List<int[]> searchHighlights` フィールドを追加。
各要素は `{row, startCol, endCol}`（endCol は exclusive）。

`paintComponent` 内でテキスト描画の前に半透明黄色で矩形を塗る:

```java
// SEARCH_HIGHLIGHT_COLOR = new Color(0xFF, 0xE0, 0x00, 0x90)（半透明黄）
for (int[] h : searchHighlights) {
    int row = h[0], c1 = h[1], c2 = h[2];
    // スクロール範囲外はスキップ
    int xStart = xForCol(line, c1, charWidth) - scrollOffsetX + gutterWidth;
    int xEnd   = xForCol(line, c2, charWidth) - scrollOffsetX + gutterWidth;
    g2.fillRect(xStart, yTop, xEnd - xStart, lineHeight);
}
```

マルチラインマッチは `ModalEditor.updateSearchHighlights()` で行単位のセグメントに分割してから渡す。

---

## 注意点

- `searchMatches` はバッファ内容が変わっても自動更新しない（`n` 押下時に再計算するため表示がずれることがある。Vim も同様の挙動）
- `lastSearchPattern` はファイルロード時にはクリアしない（Vim 同様、別ファイルを開いても検索を継続できる）
- ただしハイライト（`searchMatches` の内容）はファイルロード時にクリアする
- `*`/`#` の単語境界は `\\b` を使う。Java の `\b` は `[a-zA-Z0-9_]` 境界に相当するため、Vim の `iskeyword` デフォルト設定とほぼ一致する
- `/` と `*`/`#` で `lastSearchPattern` と `lastSearchForward` を更新することで、後続の `n`/`N` が正しく動く
