# 次のセッション向けプロンプト（Getter/Setter 自動生成 完了後）

## 現状の確認

このプロジェクトは Vim のモーダル編集と Emacs の拡張性を統合した Java SE 製テキストエディタです。
現在のブランチ `main` には以下が実装済みです（詳細は `CLAUDE.md` のロードマップ参照）。

最新の追加機能（セッション: getter-setter-generation）:
- **Space+g+g**: カーソル行のフィールド宣言から getter を自動生成してクラス末尾に挿入
- **Space+g+s**: setter を自動生成
- **Space+g+d**: getter と setter の両方を自動生成
- boolean 型フィールドには `is` プレフィックスを自動判定
- `pendingNormalChar`（char）→ `pendingSequence`（String）に拡張し 3 打鍵シーケンスに対応

テスト: 全21クラス・226/226（ModalEditorTest）・113/113（RobotKeyInputTest）・全 PASS

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
例: `seq.equals("[g")` の判定を `prev == '['` の判定より前に書く。

### 診断 CompileDiagnostic の参照
`ModalEditor` は `List<CompileDiagnostic> diagnostics` フィールドを持っている（javac-compile-integration で追加済み）。`[g`/`[d` 実装時はこれを活用できる。
