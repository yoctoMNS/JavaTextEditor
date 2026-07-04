---
name: telescope-picker
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、telescope.nvim風ファジーファインダー（SPC+f=ファイル検索/SPC+/=ライブgrep/SPC+b=バッファ一覧・3ペインオーバーレイ）を設計・実装する際に使用する。「ファジー検索を追加したい」「FuzzyMatcherのスコアリング」「TELESCOPEモードのキー処理・モード遷移」といった相談、またFilePicker/GrepPicker/BufferPickerを触る作業に着手する前に、必ず最初に参照すること。"
---

# telescope-picker スキル

## このスキルが解決すること

telescope.vim の中核機能（ファジーファインダー・ライブgrep・バッファ一覧）を
Java SE + Swing の本エディタで再現する。

**参照元**: [telescope.nvim](https://github.com/nvim-telescope/telescope.nvim)

---

## telescope.vim の設計思想（参照元の整理）

### 3ペイン構造

telescope は常に3つのペインから成るフローティングウィンドウを表示する。

```
┌───────────────────────────────────────────────┐
│ > query_                                      │  ← Prompt（入力欄）
├───────────────────────┬───────────────────────┤
│ result 1              │ preview content here  │
│ ▸ result 2  ← selected│ (file or grep match)  │  ← Results + Preview
│ result 3              │                       │
└───────────────────────┴───────────────────────┘
```

- **Prompt**: ユーザーの入力を受け付ける。入力のたびにリストをリアルタイムフィルタリング。
- **Results**: フィルタリングされた候補一覧。`j/k` または `Ctrl+N/P` で選択。
- **Preview**: 選択中の候補のファイル内容（またはgrep結果の周辺行）を表示。

### Picker（ソース）

telescope では「何を検索するか」をピッカーとして抽象化する。

| ピッカー | 説明 | キーバインド例 |
|---|---|---|
| `find_files` | プロジェクト配下のファイル名をファジー検索 | `SPC+p` |
| `live_grep` | ファイル内容をリアルタイムgrep | `SPC+/` |
| `buffers` | 開いているバッファ（ファイル）一覧 | `SPC+b` |
| `help_tags` | ヘルプタグ検索 | `SPC+h` |

### ファジーマッチング

telescope は [fzy](https://github.com/jhawthorn/fzy) アルゴリズムベースの
スコアリングを使う。本実装では以下の簡略版で十分：

1. **部分列マッチ（Subsequence）**: クエリの全文字がターゲットに順序通りに出現するか
2. **スコアリング**:
   - 連続一致: +3点/文字
   - 単語境界一致（`/` `.` `_` `-` 直後、または大文字の先頭）: +2点
   - 通常一致: +1点
   - 一致間のギャップ: -1点/文字

---

## このエディタへの適用設計

### スコープ（実装する機能）

| 機能 | キー | 説明 |
|---|---|---|
| ファジーファイル検索 | `SPC+f` | プロジェクト配下のファイル名をファジー検索、プレビュー付き |
| ライブgrep | `SPC+/` | ファイル内容をリアルタイムgrep、マッチ行プレビュー |
| バッファ一覧 | `SPC+b` | 開いているバッファ（ModalEditor インスタンス）一覧 |

### スコープ外

- 複数選択（マルチセレクト）
- カスタムアクション（telescope actions）
- ソーターのカスタマイズ
- extensions（lsp_references 等）

### アーキテクチャ

```
ModalEditor (TELESCOPE モード追加)
  │
  ├── TelescopePicker (interface)
  │     ├── FilePicker          ファイル名ファジー検索
  │     ├── GrepPicker          ライブgrep（ProjectSearcher 再利用）
  │     └── BufferPicker        開きバッファ一覧
  │
  ├── FuzzyMatcher              部分列マッチ + スコアリング
  │
  └── TelescopeItem (record)    表示文字列・ファイルパス・行番号
        display: String
        filePath: String (nullable)
        lineNumber: int
        score: int
```

`EditorCanvas` に `drawTelescopeOverlay()` を追加し、
`paintComponent` の末尾（テキストの上）に半透明オーバーレイとして描画する。

### モード遷移

```
NORMAL
  SPC+f → TELESCOPE (FilePicker)
  SPC+/ → TELESCOPE (GrepPicker)
  SPC+b → TELESCOPE (BufferPicker)

TELESCOPE
  文字入力    → queryBuffer に追加 → リアルタイムフィルタ
  Backspace   → queryBuffer から削除
  Ctrl+N / j  → 次候補へ
  Ctrl+P / k  → 前候補へ
  Enter       → 選択候補を開く → NORMAL
  Escape      → キャンセル → NORMAL
```

### UIレイアウト（EditorCanvas 上のオーバーレイ）

```
canvasHeight = H, canvasWidth = W

オーバーレイ全体: 幅 W*0.85, 高さ H*0.75, 中央配置

┌─────────────────────────────────────────────┐
│ > query_text                                │  ← プロンプト（最上部 1行）
├─────────────────┬───────────────────────────┤
│ results (40%)   │ preview (60%)             │
│  ▸ Foo.java     │  1: package dev.jte;      │
│    Bar.java     │  2:                        │
│    Baz.java     │  3: public class Foo {    │
│    ...          │  ...                      │
└─────────────────┴───────────────────────────┘
```

### FuzzyMatcher の実装

```java
// クエリ "fob" がターゲット "FooBar.java" にマッチするか
// F-o-o-B-a-r → F(match), o(match), B(skip), a(skip), r(skip), .java → b? → 見つからない
// → 部分列として全文字が出現する必要がある

public record MatchResult(boolean matched, int score, int[] matchPositions) {}

public static MatchResult match(String query, String target) {
    // 大文字小文字無視
    String q = query.toLowerCase();
    String t = target.toLowerCase();
    int[] positions = new int[q.length()];
    int qi = 0, score = 0, lastMatch = -1;
    for (int ti = 0; ti < t.length() && qi < q.length(); ti++) {
        if (t.charAt(ti) == q.charAt(qi)) {
            positions[qi] = ti;
            // 連続ボーナス
            if (lastMatch == ti - 1) score += 3;
            // 単語境界ボーナス
            else if (ti == 0 || isBoundary(t, ti)) score += 2;
            else score += 1;
            // ギャップペナルティ
            if (lastMatch >= 0) score -= (ti - lastMatch - 1);
            lastMatch = ti;
            qi++;
        }
    }
    return new MatchResult(qi == q.length(), score, positions);
}
```

### テスト方針

- `FuzzyMatcherTest`: 部分列マッチ・スコアリング・境界ボーナス・大文字小文字無視
- `TelescopePickerTest`: クエリ変更時のフィルタ結果・スコア降順ソート
- `TelescopeIntegrationTest` (ModalEditor): モード遷移・キー入力・Enter で開く・Esc キャンセル

---

## 既存コードとの依存関係

| 依存先 | 利用内容 |
|---|---|
| `FileNameSearcher` | FilePicker のベースとなるファイルリスト取得 |
| `ProjectSearcher` | GrepPicker のgrep実行 |
| `EditorCanvas` | オーバーレイ描画（`drawTelescopeOverlay()` 追加） |
| `ModalEditor` | TELESCOPE モード追加・キー処理 |

`ModalEditor` は `List<ModalEditor>` 形式で開きバッファを保持しないため、
BufferPicker は `Main.java` 側から `List<Leaf>` を `ModalEditor` に渡す形にする。
