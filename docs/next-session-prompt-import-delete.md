# 次のセッション向けプロンプト（import 削除機能 完了後）

## 現状の確認

このプロジェクトは Vim のモーダル編集と Emacs の拡張性を統合した Java SE 製テキストエディタです。
現在のブランチ `main` には以下が実装済みです（詳細は `CLAUDE.md` のロードマップ参照）。

最新の追加機能（セッション: import-delete）:
- **removeImport(fqn, buffer)**: 特定 FQN の import 行をバッファから削除
- **findUnusedImports(source)**: SourceAnalyzer を使い未使用 import を検出（ワイルドカード除外）
- **removeUnusedImports(buffer)**: 未使用 import を一括削除
- **SPC+i+o**: NORMAL モードから未使用 import を一括削除
- **Ctrl+Shift+O**: Eclipse 互換キーバインド（NORMAL/INSERT 両モード対応）
- **:oi / :organize-imports**: コマンドラインから未使用 import 一括削除
- **:remove-import \<fqn\>**: 特定 FQN の import を1件削除

テスト: 全21クラス・818/818 全 PASS（AutoImportHandlerTest 42件・RobotKeyInputTest 128件）

## 次に実装すべき機能の候補

### A. バッファ切り替え（`:bnext` / `:bprev`）
Neovim設定: `Ctrl+U` → `:bprev`、`Ctrl+P` → `:bnext`
複数ファイルを開いたまま切り替える機能。
- `BufferManager` クラスで開いているファイルの履歴を管理
- `:bnext` / `:bprev` コマンドおよびキーバインドを追加
- Main.java でアクティブペインのエディタに対してバッファ切り替えを適用

### B. Auto-pair（括弧の自動補完）
INSERT モードで `(` `[` `{` `"` `'` を入力したとき対応する閉じ括弧を自動挿入する機能。
現在は Tab によるペアスキップのみ実装済み。
- カーソルは開き括弧の直後（閉じ括弧の前）に留まる
- Backspace で一対まとめて削除
- `ModalEditor.processInsertKey()` に追加実装

### C. 行番号表示
`EditorCanvas` のガター（左端の描画領域）に絶対行番号を表示する機能。
- 現在のガターはコンパイルエラーの `E/W` マーカーに使用中
- 行番号をその左隣りに追加、または設定で切り替え可能にする
- 相対行番号オプション（Neovim の `relativenumber`）も追加

### E. 次の診断へジャンプ（`[g` / `[d`）
コンパイルエラー・警告の診断結果間をジャンプする機能。
- `[g` で次のエラー行へカーソルジャンプ、`[d` で前のエラー行へジャンプ
- 2打鍵シーケンス `[` → `g` / `[` → `d` として実装（`pendingSequence` を使用）
- `CompileDiagnostic` リストを `ModalEditor` 経由で参照（既存の診断機能を再利用）

### F. ウィンドウリサイズ（Ctrl+H/J/K/L）
Neovim設定: `Ctrl+H` → 幅縮小、`Ctrl+K` → 高さ拡大等。
- `JSplitPane.setDividerLocation()` を用いてペインサイズを調整
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

## 重要実装メモ

### pendingSequence（3打鍵シーケンス）
`ModalEditor.processNormalKey()` の冒頭にある `pendingSequence` 処理を拡張する場合、**複数文字シーケンスの判定を単一文字シーケンスの判定より先に行うこと**。
例: `seq.equals(" g")` / `seq.equals(" i")` の判定を `prev == ' '` の判定より前に書く。

### findUnusedImports の限界
現在の実装は単純な文字列検索（`String.contains(simpleName)`）のため、ワイルドカード import・static import は削除対象外。同名のメソッド/変数がコード本体にあると「使用中」と誤判定されることがある（保守的な設計）。より正確な判定が必要な場合は CompileAnalyzer と `-Xlint:all` を組み合わせた未使用 import 診断を検討。

### RobotKeyInputTest の pressChar()
`pressChar()` は `-` をサポートしていない。`:remove-import` 等のコマンドをタイプする際は `VK_MINUS` を直接呼び出すこと（実装例: `RobotKeyInputTest.testRemoveImportCommand()` 参照）。
