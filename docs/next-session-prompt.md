# 次セッション指示プロンプト

## 現在の状態

Skill ⑯ `auto-import-handler` が完了し、main ブランチにマージ済み。全 711 テストケース PASS（Robot テスト含む）。

INSERT→NORMAL 復帰時に `CompileAnalyzer` のエラー結果を `AutoImportHandler` に渡し、
「cannot find symbol: class/interface/enum X」から型名を抽出→ JDK 索引で FQN 候補を取得→
候補1件なら即自動挿入・複数件ならステータスバーに `[1] java.util.List  [2] java.awt.List  [Esc]=skip`
形式で表示して数字キーで選択する機能が動作している。

## 完了済み Skill 一覧（参考）

| # | Skill 名 | 状態 |
|---|---|---|
| ① | editor-buffer-architecture | ✅ 完了 |
| ② | modal-editing-engine | ✅ 完了 |
| ③ | extension-language-runtime | ✅ 完了 |
| ④ | keymap-conflict-resolution | ✅ 完了 |
| ⑤ | gui-rendering-pipeline | ✅ 完了 |
| ⑥ | plugin-api-design | ✅ 完了 |
| ⑦ | editor-testing-strategy | ✅ 完了 |
| ⑧ | java-source-analysis | ✅ 完了 |
| ⑨ | javac-compile-integration | ✅ 完了 |
| ⑩ | jdk-api-navigation | ✅ 完了 |
| ⑪ | javadoc-viewer | ✅ 完了 |
| ⑯ | auto-import-handler | ✅ 完了 |

## 次のタスク候補（未着手）

| # | Skill 名 | 概要 | 依存 |
|---|---|---|---|
| ⑫ | `openjdk-source-tracing` | JNI/HotSpot レベルのソーストレース | ⑩ |
| ⑬ | `project-wide-search` | 作業ディレクトリ配下の grep 的検索 | ① |
| ⑭ | `multi-file-refactoring` | シンボル単位の複数ファイルリファクタリング | ①⑧ |

## 推奨: Skill ⑬ project-wide-search

### 目標

エディタ内から作業ディレクトリ配下のファイルを grep 的に検索し、結果をエディタ内で
閲覧・ジャンプできるようにする。

### 設計方針（検討ポイント）

1. **トリガー**:
   - `:grep <pattern>` コマンドで手動トリガー
   - または NORMALモードの `Ctrl+/` などのキーバインド

2. **検索エンジン**:
   - Java SE 標準の `Files.walkFileTree()` + `Files.readAllLines()` で実装
   - バックグラウンドスレッドで走査してメインスレッドをブロックしない
   - 正規表現対応（`java.util.regex.Pattern`）

3. **結果表示**:
   - 結果を新しいバッファ（疑似ファイル "*grep*"）に一覧表示
   - `file:lineNumber: matchedLine` 形式
   - Enter/`gf` で該当ファイルへジャンプ

4. **作業ディレクトリの扱い**:
   - エディタ起動時の作業ディレクトリ（`System.getProperty("user.dir")`）を基点
   - `.gitignore` 対応は optional（最初は全ファイル対象でもよい）

### 着手前に確認すること

- `src/dev/vimacs/editor/ModalEditor.java` の `executeCommand()` を読んでコマンド追加方法を把握する
- `src/dev/vimacs/Main.java` のペイン切り替えロジックを確認し、grep 結果バッファをどちらのペインに表示するか設計する
- `docs/session-auto-import-handler.md` を参照して設計経緯を把握する
- `.claude/skills/` 配下の関連 SKILL.md を必ず参照すること

### ブランチ

`claude/project-wide-search`（新規作成して作業）

### 完了条件

- `ProjectSearchTest` が全 PASS
- 既存 711 テストケースが引き続き PASS（Robot テスト含む）
- README.md にキーバインドと動作説明を追記
- `docs/session-project-wide-search.md` に作業ログを記録
- main ブランチにマージ
