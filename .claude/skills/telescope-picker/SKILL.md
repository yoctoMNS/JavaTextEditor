---
name: telescope-picker
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、telescope.nvim風ファジーファインダー（SPC+f=ファイル検索/SPC+/=ライブgrep/SPC+b=バッファ一覧。あいまい検索は維持しつつ表示は\\f/\\gと同じ疑似バッファ方式。3ペインオーバーレイは2026-07に廃止。FILER（:cd後のディレクトリブラウザ）も同時期に同じ疑似バッファ方式へ統一済み）を設計・実装する際に使用する。「ファジー検索を追加したい」「FuzzyMatcherのスコアリング」「TELESCOPEモードのキー処理・モード遷移」といった相談、またFilePicker/GrepPicker/BufferPickerを触る作業に着手する前に、必ず最初に参照すること。"
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

### モード遷移

```
NORMAL
  SPC+f → TELESCOPE (FilePicker)
  SPC+/ → TELESCOPE (GrepPicker)
  SPC+b → TELESCOPE (BufferPicker)

TELESCOPE
  文字入力    → queryBuffer に追加 → リアルタイムフィルタ
  Backspace   → queryBuffer から削除
  Ctrl+N      → 次候補へ
  Ctrl+P      → 前候補へ
  Enter       → 選択候補を開く → NORMAL
  Escape      → キャンセル → NORMAL
```

### UIレイアウト：`\f`/`\g` と同じ疑似バッファ表示（3ペインオーバーレイは廃止）

**2026-07 に設計変更**: 当初は `EditorCanvas.drawTelescopeOverlay()` による
「半透明の3ペインフローティングウィンドウ（プロンプト＋Results 40%＋Preview 60%）」
として実装していたが、ユーザーから「telescopeのウィンドウを廃止し、`\f`（ファイル名検索）・
`\g`（grep）と同じ表示方法に揃えてほしい」という明示的な指示があり、表示方式のみを刷新した。

- **あいまい検索・スコアリング（FuzzyMatcher）自体は維持**。ユーザーへの確認の上、
  「1行入力→Enterで一括実行」という `\f`/`\g` の実行モデルまでは真似せず、
  1文字入力するたびに `TelescopePicker.filter(query)` を呼び直すリアルタイムフィルタは
  そのまま残した（変わったのは結果の描画方法だけ）。
- **結果は `EditorCanvas` の半透明オーバーレイではなく、`\f`（`*file-search*`）・
  `\g`（`*grep*`）と全く同じ「ヘッダ行＋結果1行ずつ」の疑似バッファとして `buffer`
  （実際のテキストバッファ）に直接描画する**（`ModalEditor.renderTelescopeBuffer()`）。
  ヘッダ行の書式は `*<picker.title()>* <query> — N result(s)`。
  プレビューペインは廃止し、`TelescopePicker.preview()` メソッド自体を
  インタフェースおよび全実装（FilePicker/GrepPicker/BufferPicker/MainClassPicker）
  から削除した（呼び出し元がなくなり死コードになるため）。
- **選択中の候補は専用のハイライトではなく、実際のテキストカーソル（`cursorRow`）を
  その行に合わせることで示す**。`moveTelescope()`（Ctrl+N/P）は結果リスト自体を
  再構築せず `cursorRow` を動かすだけでよい（`telescopeSelectedIdx + 1`。+1は
  ヘッダ行の分）。クエリが変わって結果リストが変化したとき（`refreshTelescope()`）
  だけ `renderTelescopeBuffer()` で `buffer` を再構築する。
- **入力中のクエリはステータス行（コマンドライン領域）に `title  > query` として表示する**
  （`\f`/`\g` が `\f` + `fileSearchBuffer` を表示するのと同じ場所・同じ仕組み）。
- **telescope 起動時は現在の `buffer`/`currentFilePath`/カーソル位置を
  `telescopeSaved*` フィールドに退避し、Esc キャンセル時にそのまま復元する**
  （`ModalEditor.beginTelescopeSession()` / `exitTelescope()`）。これは jdk-source
  疑似バッファの `saved*` や `*cd候補*` の `cdSaved*` と同じ「一時退避→復元」パターンだが、
  専用のフィールド群を持つ独立実装にした（3系統の相互作用は未定義のまま。
  CLAUDE.md「既知の未接続・二重定義」参照）。
  **telescope 起動時にたまたま `*grep*`/`*file-search*` 結果バッファの上にいた場合**、
  `grepResults`/`grepBaseDir`/`fileNameResults` も退避・復元する
  （telescope が `buffer` を上書きするため、退避しないと Esc 後にその疑似バッファの
  Enter ジャンプが効かなくなる退行が起きるため。単なるテキストの巻き戻しだけでなく、
  疑似バッファの「意味」まで含めて復元する必要があった）。
- **F11 の `MainClassPicker`（複数 main クラスからの選択）も同じ疑似バッファ表示に統一した**。
  `enterMainClassPicker()` は `beginTelescopeSession()` を共有し、Enter で選択した際は
  `exitTelescope()` が退避済みの元バッファ（F11 を押した時点で開いていたソースファイル）を
  先に復元してから `onRunMainClassSelected` コールバックを呼ぶため、実行対象のソース
  バッファ自体は以前（オーバーレイ方式で `buffer` に一切触れなかった頃）と同じ状態に戻る。
- **`EditorCanvas.setTelescopeState()`/`drawTelescopeOverlay()` 自体は削除していない**。
  IMPORT_SELECT（未定義シンボルの import 選択）が同じオーバーレイ描画を流用しているため、
  これは変更対象外（今回廃止したのは telescope-picker が使っていた「3ペイン・あいまい検索
  フローティングウィンドウ」という UI パターンであり、EditorCanvas 側の汎用オーバーレイ
  描画インフラそのものではない）。
- **追記（2026-07）: FILER（`:cd` 後のディレクトリブラウザ）も同じ疑似バッファ表示に統一した**。
  「`:cd` でディレクトリ移動している間も telescope 風のオーバーレイ画面が表示されてしまう」
  という指摘を受け、`ModalEditor.enterFiler()`/`renderFilerBuffer()` を telescope の
  `beginTelescopeSession()`/`renderTelescopeBuffer()` と同じ設計で書き直した。
  `:cd`（`changeDirectory()`）実行時にのみ元バッファを `filerSaved*` へ退避し、ディレクトリ間の
  再帰移動（`openSelectedEntry()` でサブディレクトリへ進む場合）は保存し直さない
  （telescope はセッション開始が1箇所のみなのに対し、FILER は `:cd` の1回の起動から
  ディレクトリを何度も移動できるため、保存タイミングを「外側から見て初めて FILER に入る瞬間」
  である `changeDirectory()` に限定する必要があった）。Esc で `exitFiler()` により退避済みの
  バッファへ復元する。ファイルを選択した場合は `exitFiler()` で元バッファに戻してから
  既存の `loadFromFile()` を呼ぶ（`pushBuffer()` が正しい元バッファを履歴に積むようにするため。
  FILERモードの設計決定事項節の「ファイルオープンの再利用」を参照）。プレビュー欄
  （`buildFilerPreview()`）は telescope 同様に廃止した。

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
| `ModalEditor` | TELESCOPE モード追加・キー処理・`renderTelescopeBuffer()` で疑似バッファ描画（`EditorCanvas` のオーバーレイは使わない。上記「UIレイアウト」節参照） |

`ModalEditor` は `List<ModalEditor>` 形式で開きバッファを保持しないため、
BufferPicker は `Main.java` 側から `List<Leaf>` を `ModalEditor` に渡す形にする。
