# セッションログ: Neovimキーバインド統合

日付: 2026-06-28

## 概要

ユーザーの実際のNeovim設定（`keymaps.lua` / `myoptions.lua`）を参照し、Java Text EditorのキーバインドをNeovimの操作感に近づけた。

## 実装した機能

### 1. Space リーダーキー（NORMAL モード）

`KeymapRegistry` に `leader.pending` アクション、`ModalEditor` に `pendingNormalChar = ' '` 処理を追加。

| キーシーケンス | 動作 |
|---|---|
| `Space` → `h` | 行の最初の非空白文字へ（`^` 相当） |
| `Space` → `l` | 行末へ（`$` 相当） |
| `Space` → `k` | ファイル先頭へ（`gg` 相当） |
| `Space` → `j` | ファイル末尾へ（`G` 相当） |

元のNeovim設定:
```lua
map("n", "<Space>l", "$", opts)
map("n", "<Space>h", "^", opts)
map("n", "<Space>k", "gg", opts)
map("n", "<Space>j", "G", opts)
```

### 2. 行入れ替え（NORMAL モード: Alt+J / Alt+K）

`swapLineDown()` / `swapLineUp()` メソッドを追加。バッファの行を直接入れ替え（delete + insert）し、カーソルを追従させる。

| キー | 動作 |
|---|---|
| `Alt+J` | 現在行と次行を入れ替え |
| `Alt+K` | 現在行と前行を入れ替え |

元のNeovim設定:
```lua
map("n", "<A-j>", "Vdp", opts)
map("n", "<A-k>", "VdkP", opts)
```

### 3. ペインナビゲーション（`s` プレフィックス拡張）

既存の `s` プレフィックス（`sv` = 左右分割、`ss` = 上下分割）を拡張。`movePanePrevCallback` / `movePaneNextCallback` を追加し、`Main.java` の `refreshCallbacks()` でリーフ順（`allLeaves()` が返す順）を基準にサイクルする。

| キーシーケンス | 動作 |
|---|---|
| `s` → `h` / `s` → `k` | 前のペインへフォーカス移動 |
| `s` → `l` / `s` → `j` | 次のペインへフォーカス移動 |

元のNeovim設定:
```lua
map("n", "sj", "<C-w>j", opts)
map("n", "sk", "<C-w>k", opts)
map("n", "sl", "<C-w>l", opts)
map("n", "sh", "<C-w>h", opts)
```

### 4. INSERT モードからの保存（Ctrl+] / Ctrl+[）

`save.from.insert` アクションを追加。INSERT モードを抜けて NORMAL モードに戻り、即座に `saveToFile(currentFilePath)` を呼ぶ。`onReturnToNormal` コールバックも実行されるため、コンパイルフックも正常に発火する。

元のNeovim設定:
```lua
map("i", "<C-]>", "<C-[>:w<CR>", opts)
map("i", "<C-[>", "<Esc>:w<CR>", opts)
```

### 5. INSERT モードの文字削除（Ctrl+D / Ctrl+K）

| キー | 動作 | 対応する Emacs コマンド |
|---|---|---|
| `Ctrl+D` | カーソル位置の1文字削除 | `delete-char` |
| `Ctrl+K` | カーソルから行末まで削除 | `kill-line` |

元のNeovim設定:
```lua
vim.keymap.set("i", "<C-d>", "<Del>")
vim.keymap.set("i", "<C-k>", "<C-o>D")
```

### 6. Tab キーのペアスキップ（INSERT モード）

`insertTab()` メソッドを追加。`CLOSING_PAIRS = Set.of(')', ']', '}', '"', '\'', '>')` に含まれる文字の直前でTabを押した場合、スペース挿入をせずカーソルを1文字右（括弧の外側）へ移動する。

元のNeovim設定（カスタムLua関数）:
```lua
local function skip_pair_or_tab()
  local pairs = { [")"] = true, ["]"] = true, ["}"] = true, ... }
  if pairs[next_char] then return "<Right>"
  else return "<Tab>"
  end
end
vim.keymap.set("i", "<Tab>", skip_pair_or_tab, { expr = true, noremap = true })
```

## 変更したファイル

| ファイル | 変更内容 |
|---|---|
| `src/dev/javatexteditor/editor/KeymapRegistry.java` | `;` → enter.command、Space → leader.pending、Alt+J/K → line.swap.down/up、Ctrl+]/[ → save.from.insert、Ctrl+D → delete.next、Ctrl+K → delete.to.eol を追加 |
| `src/dev/javatexteditor/editor/ModalEditor.java` | `movePanePrevCallback`/`movePaneNextCallback` フィールド・セッター追加、Space/s プレフィックス2打鍵処理、`leader.pending`/`line.swap.down`/`line.swap.up`/`save.from.insert`/`delete.next`/`delete.to.eol` アクション実装、`insertTab()`/`swapLineDown()`/`swapLineUp()` メソッド追加 |
| `src/dev/javatexteditor/Main.java` | `refreshCallbacks()` で `movePanePrevCallback`/`movePaneNextCallback` を全リーフに設定 |
| `test/dev/javatexteditor/editor/ModalEditorTest.java` | 13件の新規テスト追加（合計210件PASS） |
| `test/dev/javatexteditor/ui/RobotKeyInputTest.java` | 19件の新規 Robot テスト追加（合計113件PASS） |

## テスト結果

- ModalEditorTest: 210 / 210 PASS
- RobotKeyInputTest: 113 / 113 PASS（Xvfb :99 使用）
- 全21クラス合計 PASS

## 設計上の注意点

- **Space + x の2打鍵**: `pendingNormalChar = ' '` を設定。Space は VK_SPACE で ofCode 登録されているため、keyCode ベース解決で `leader.pending` アクションが確実に解決される
- **Alt+J/K の行入れ替え**: `KeyEvent.ALT_DOWN_MASK` 修飾キーを使用。`swapLineDown()/Up()` は delete + insert の2操作なのでアンドゥ時に2ステップ戻る（将来的にアトミック操作への改善余地あり）
- **ペインナビのサイクル順**: `allLeaves()` が返す順（DFS左優先）でインデックスを循環。Neovim の方向ベース（`<C-w>h/j/k/l`）ではなくリニアサイクルで実装
- **Ctrl+K のコンフリクト**: NORMAL モードでは `KeyEvent.VK_K + ALT_DOWN_MASK` で `line.swap.up`、INSERT モードでは `Ctrl+K` で `delete.to.eol`。モード別解決のため干渉しない
