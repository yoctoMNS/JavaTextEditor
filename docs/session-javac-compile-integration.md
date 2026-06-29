# 作業ログ: ⑨ javac-compile-integration

**日付**: 2026-06-26  
**ブランチ**: `claude/javac-compile-integration`  
**マージ先**: `main`

## 作業概要

`javax.tools.JavaCompiler` を使ってバッファ内容を型解決まで解析し、コンパイルエラー・警告を `EditorCanvas` のガター（E/W マーカー）と波下線でリアルタイム表示する機能を実装した。

## 前提: 累計テスト数

| 作業前 | 作業後 |
|---|---|
| 553 テスト全 PASS (14クラス) | 568 テスト全 PASS (15クラス) |

## 実装したクラス

### 新規作成

| ファイル | 内容 |
|---|---|
| `src/dev/vimacs/analysis/DiagnosticKind.java` | `ERROR` / `WARNING` の enum |
| `src/dev/vimacs/analysis/CompileDiagnostic.java` | 診断1件を表す record（lineNumber/column/message/kind、全て 0-indexed） |
| `src/dev/vimacs/analysis/CompileAnalyzer.java` | `JavacTask.parse()` + `JavacTask.analyze()` を実行して `List<CompileDiagnostic>` を返す |
| `test/dev/vimacs/analysis/CompileAnalyzerTest.java` | 15 テスト（下記参照） |
| `.claude/skills/javac-compile-integration/SKILL.md` | 設計知識の記録 |

### 変更

| ファイル | 変更内容 |
|---|---|
| `src/dev/vimacs/ui/EditorCanvas.java` | `setDiagnostics()` / `getDiagnostics()` 追加、ガター描画・波下線描画・ステータスバー件数表示を `paintComponent()` に追加 |
| `src/dev/vimacs/editor/ModalEditor.java` | `setOnReturnToNormal(Runnable)` 追加（INSERT→NORMAL 復帰フック） |
| `CLAUDE.md` | Skill ⑨ を「完了」に更新 |
| `README.md` | 新機能説明・ディレクトリ構成・アーキテクチャ・テスト結果を更新 |

## 設計上の決定と理由

### ⑧ SourceAnalyzer との分離

`SourceAnalyzer` は parse-only（型解決なし）のまま維持し、`CompileAnalyzer` を別クラスとして実装した。理由:

- parse-only は構文エラーがあっても動作し import 索引を返せる（graceful degradation）
- `analyze()` まで実行すると型解決が必要なすべての依存クラスを参照しようとし、失敗したクラスへの参照はエラーとして報告される
- 用途が異なる（高速な索引生成 vs 正確なエラー表示）

### ガター幅の動的制御

既存の 22 テストが `gutterWidth = 0` を前提にカーソル位置のピクセル座標をチェックしていた。診断なしのときガター幅を 0 にすることで、既存テストに影響を与えずにガター機能を追加できた。

```java
int gutterWidth = diagnostics.isEmpty() ? 0 : 2 * charWidth;
```

### public クラスとファイル名の不一致

`analyze(String sourceCode)` に `public class Foo {...}` を渡すと、仮想ファイル名 `<buffer>` との不一致により javac がエラーを出す（Java の仕様）。これは意図した動作で、SKILL.md に注意点として記録した。実用では `analyzeFile(Path)` を使うことで回避できる。

### INSERT→NORMAL 復帰フックのスコープ

`processInsertKey()` の `"enter.normal"` アクション（ESC キー）でのみコールバックを呼ぶ設計にした。COMMAND モードや VISUAL モードからの NORMAL 復帰ではコンパイルをトリガーしない（テキスト内容が変わっていないため）。

## テストケース (15件)

| # | テスト名 | 概要 |
|---|---|---|
| 1 | 正常ソースでエラー0件 | 非 public クラスを使用（public はファイル名不一致エラーが出るため） |
| 2 | 構文エラー（セミコロン欠落）が ERROR として検出 | |
| 3 | 行番号が 0-indexed で正しく返る | エラー3行目 → lineNumber==2 |
| 4 | 未定義型参照が ERROR として検出 | 型解決エラー |
| 5 | 型不一致エラーが ERROR として返る | int x = "not an int" |
| 6 | 複数エラーが複数件として返る | 2つの未定義型 → 2件以上 |
| 7 | 診断メッセージが空でない | |
| 8 | CompileDiagnostic record のフィールドアクセス | |
| 9 | DiagnosticKind enum の値 | ERROR, WARNING の 2値 |
| 10 | EditorCanvas.setDiagnostics() でセットされる | getDiagnostics() で検証 |
| 11 | setDiagnostics(空) でリセット | |
| 12 | setDiagnostics(null) でリセット | |
| 13 | 診断あり時のガター描画でクラッシュしない | BufferedImage に描画して例外なし |
| 14 | onReturnToNormal が INSERT→NORMAL で呼ばれる | 'i' + ESC でカウント増加 |
| 15 | onReturnToNormal は NORMAL 状態の ESC では呼ばれない | カウント増加なし |

## Robot テストについて

`RobotKeyInputTest` は `java.awt.Robot` を使った統合テストで、実 DISPLAY が必要なため headless 環境では SKIP になる。同等のロジックは `KeyboardSimulationTest`（110件）で網羅されており、`Shift` 修飾キー（`:` / `V` / `P`）のキーマップ解決バグを含めて検証済み。

## 未接続の機能（Main.java への組み込みは未着手）

`CompileAnalyzer` と `EditorCanvas.setDiagnostics()` は実装・テスト済みだが、`Main.java` への接続（バックグラウンドコンパイルの起動）は実装していない。接続のサンプルコードは SKILL.md に記載している。

次のセッションで ⑩ jdk-api-navigation を実装する際、または別途 "Main.java にコンパイルエラー表示を接続する" タスクとして着手すること。
