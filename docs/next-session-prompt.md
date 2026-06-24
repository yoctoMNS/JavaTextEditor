# 次セッション用プロンプト：② modal-editing-engine v2

---

## セッション開始時に必ず読むファイル（この順で読み終えてから実装に着手すること）

1. `CLAUDE.md` — 技術制約・パッケージ名・ロードマップ（厳守）
2. `docs/session-log.md` — 全作業履歴・未解決バグ・前セッション引き継ぎ事項
3. `docs/handover-modal-editor-v2.md` — **今セッションの詳細実装仕様書**（必読）
4. `src/dev/vimacs/editor/ModalEditor.java` — 改修対象のコード全体
5. `src/dev/vimacs/ui/EditorCanvas.java` — ステータス行変更のため確認
6. `test/dev/vimacs/editor/ModalEditorTest.java` — 既存テストの構造を把握してから追記

---

## 実装タスク

`docs/handover-modal-editor-v2.md` の仕様に従い、以下を実装する。

### 1. ModalEditor にコマンドラインモードを追加

- `boolean insertMode` を `enum Mode { NORMAL, INSERT, COMMAND }` に置き換える
- NORMALモードで `:` キー → COMMMANDモードへ移行
- COMMMANDモードで文字入力 → `commandBuffer` に蓄積・ステータス行にリアルタイム表示
- COMMMANDモードで `Enter` → コマンド実行後 NORMALへ
- COMMMANDモードで `Escape` → 中断して NORMALへ

### 2. 実装するコマンド（`:` で始まるVim式コマンド）

| コマンド | 動作 |
|---|---|
| `:w` | 現在パスへ上書き保存（パス未設定時はエラー表示） |
| `:w <path>` | 指定パスへ保存し、currentFilePath を更新 |
| `:e <path>` | 指定ファイルを開く・バッファ再初期化・カーソルを (0,0) へ |
| `:q` | 終了（コールバック経由で System.exit を呼ぶ設計にする） |
| `:wq` | 保存して終了 |
| 未定義 | ステータス行に `E: unknown command` を表示 |

### 3. EditorCanvas のステータス行を拡張

- `setCommandLineText(String text)` を追加（null = 通常モード表示）
- `drawStatusLine` でコマンド入力中は `:xxx` を表示するよう変更

### 4. Main.java を拡張

- `args[0]` にファイルパスが渡された場合、そのファイルを読み込んで開く
- `ModalEditor(String text, String filePath, EditorCanvas canvas)` コンストラクタを追加

---

## ブランチ運用

```bash
git checkout main
git checkout -b claude/command-mode-v2-<任意のサフィックス>
```

作業完了・テスト全通過後にコミット・プッシュ。main へのマージはユーザーの指示を待つこと。

---

## 制約（厳守・変更禁止）

- **言語**: Java 21 / Java SE 標準 API のみ（外部ライブラリ禁止）
- **ビルド**: `./scripts/build.sh` のみ（Maven/Gradle 禁止）
- **テスト**: `main` メソッド形式のみ（JUnit 禁止）
- **コメント**: 自明なコードにはコメントを書かない。WHY が非自明な箇所のみ

---

## テスト要件

`test/dev/vimacs/editor/ModalEditorTest.java` に以下のグループを追加すること。

```
[COMMMANDモード: 基本遷移]
  PASS: ':' でモード==COMMAND
  PASS: ESCでモード==NORMAL・commandBufferがクリア
  PASS: 文字入力でcommandBuffer蓄積

[COMMMANDモード: :w 保存]
  PASS: ':w <tmpPath>' でファイルが作成される
  PASS: 保存後の内容がバッファと一致する

[COMMMANDモード: :e 開閉]
  PASS: ':e <path>' でバッファが差し替わる
  PASS: ':e' 後カーソルが (0,0) になる

[COMMMANDモード: エラーケース]
  PASS: ':w' でパス未設定時にエラーステータスが設定される
  PASS: 未定義コマンドでエラーステータスが設定される
```

---

## 完了条件

1. `./scripts/test.sh` で **全テストクラスが PASS**（既存 46 ケースの回帰なし必須）
2. `./scripts/run.sh <実在するファイルパス>` でそのファイルが表示されること（目視確認）
3. `:w <path>` → エディタ外からファイル内容を確認できること（目視確認）
4. `docs/session-log.md` にこのセッションの作業記録を追記
5. コミット・プッシュ完了

---

## 実装しない範囲（スコープ外）

- 横スクロール（⑤ v3 で別途実装）
- VISUALモード（② v3 以降）
- アンドゥ/リドゥ（② v3 以降）
- タブ補完・履歴（コマンドライン機能の拡張）
- `:set` コマンド（設定変更）
