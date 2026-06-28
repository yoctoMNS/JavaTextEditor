# 次のセッション向けプロンプト

## 現状の確認

このプロジェクトは Vim のモーダル編集と Emacs の拡張性を統合した Java SE 製テキストエディタです。
現在のブランチ `main` には以下が実装済みです（詳細は `CLAUDE.md` のロードマップ参照）。

最新の追加機能（セッション: session-neovim-keymap-integration）:
- Space リーダーキー（Space+h/l/k/j）
- Alt+J/K による行入れ替え
- s+h/k/l/j によるペインナビゲーション
- Ctrl+]/[ による INSERT→NORMAL + 保存
- Ctrl+D/K による文字・行削除（INSERT モード）
- Tab キーのペアスキップ機能

テスト: 全21クラス・合計 430+ テスト PASS（ModalEditorTest 210/210、RobotKeyInputTest 113/113）

## 次に実装すべき機能の候補

以下は未着手の機能です。ユーザーのNeovim設定（`keymaps.lua`/`myoptions.lua`）との差分も含まれます。

### A. バッファ切り替え（`:bprev` / `:bnext`）
Neovim設定: `Ctrl+U` → `:bprev`、`Ctrl+P` → `:bnext`
エディタには現在バッファ管理機能がない。複数ファイルを開いたまま切り替える機能を実装する。
- `BufferManager` クラスで開いているファイルの履歴を管理
- `:bnext` / `:bprev` コマンドおよびキーバインドを追加
- Main.java でアクティブペインのエディタに対してバッファ切り替えを適用

### B. Auto-pair（括弧の自動補完）
`(` を押したら `()` が挿入されカーソルが括弧の内側に留まる機能。
現在は Tab によるペアスキップのみ実装済み。自動補完側が未実装。
Neovim設定: `nvim-autopairs` プラグイン相当。
- INSERT モードで `(` `[` `{` `"` `'` を入力したとき対応する閉じ括弧を自動挿入
- カーソルは開き括弧の直後（閉じ括弧の前）に留まる
- Backspace で一対まとめて削除する

### C. 行番号表示
`myoptions.lua` に `number = true`、`relativenumber = true` の設定がある。
- `EditorCanvas` のガター（左端の描画領域）に絶対行番号を表示
- 現在のカーソル行からの相対行番号オプションも追加

### D. Getter / Setter 自動生成
`keymaps.lua` に `<leader>gg` / `<leader>gs` / `<leader>gd` のマッピングがあり、カーソル行のフィールド定義を解析して getter/setter を自動生成する Lua 関数が実装されている。
Java 版として実装する:
- NORMAL モードでフィールド定義行（例: `private int hp;`）にカーソルを置いて実行
- `Space+g+g` → getter を生成してクラス末尾の `}` 直前に挿入
- `Space+g+s` → setter を生成
- `Space+g+d` → getter + setter 両方を生成
- `parse_field()` に相当するパーサは Java で実装（`ModalEditor` または専用クラス）

### E. 次の診断へジャンプ（`[g` / `[d`）
`keymaps.lua` に `[g` → `vim.diagnostic.goto_next`、`[d` → `vim.diagnostic.goto_prev` がある。
コンパイルエラー・警告の診断結果間をジャンプする機能:
- `CompileDiagnostic` リストを `ModalEditor` 経由で参照
- `[g` で次のエラー行へカーソルジャンプ
- `[d` で前のエラー行へカーソルジャンプ
- 2打鍵シーケンス `[` → `g` / `[` → `d` として実装

### F. ウィンドウリサイズ（Ctrl+H/J/K/L）
Neovim設定: `Ctrl+H` → `<C-w><`（幅縮小）、`Ctrl+K` → `<C-w>+`（高さ拡大）等。
`JSplitPane` の `setDividerLocation()` を用いてペインサイズを調整する。
- NORMAL モードの Ctrl+H/J/K/L でアクティブペインを中心に JSplitPane の分割比を変更

## 作業手順の指示

1. `CLAUDE.md` のロードマップと `.claude/skills/` を確認してから着手する
2. `./scripts/build.sh` → `./scripts/test.sh` で既存テストがすべて PASS することを確認
3. 実装 → テスト追加 → `./scripts/test.sh` で全 PASS を確認
4. `Robot` テスト（`test/dev/javatexteditor/ui/RobotKeyInputTest.java`）にも新機能のテストを追加する（Xvfb 環境では `DISPLAY=:99` で実行）
5. `README.md` の「特徴」と「キーバインド」テーブルを更新する
6. `docs/session-<feature>.md` に作業ログを作成する
7. `docs/next-session-prompt-<feature>.md` に次のセッション向けプロンプトを作成する
8. コミットして `main` ブランチにマージ・プッシュする

## 重要な制約

- 外部ライブラリ禁止（Java 21 SE 標準 API のみ）
- ビルドツール不使用（`javac` 直接呼び出し）
- テストフレームワーク不使用（`main()` メソッドを持つ自作ハーネス）
- パッケージ: `dev.javatexteditor`
- ブランチ: `claude/new-session-<id>` で開発し、完了後 `main` にマージ
