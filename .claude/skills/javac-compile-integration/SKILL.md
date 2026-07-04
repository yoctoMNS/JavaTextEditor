---
name: javac-compile-integration
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、javax.tools.JavaCompilerで型解決まで実行し、コンパイルエラー・警告をEditorCanvasのガター（E/Wマーカー）と波下線で表示する機能を設計・実装する際に使用する。「コンパイルエラーをエディタ内に表示したい」「バックグラウンドコンパイル」「INSERTモード離脱時のフック」といった相談、またCompileAnalyzer/CompileDiagnosticを触る作業に着手する前に、必ず最初に参照すること。"
---

# Skill ⑨: javac-compile-integration

## 概要

`javax.tools.JavaCompiler` を使ってバッファ内容を型解決まで解析し、
コンパイルエラー・警告を `EditorCanvas` のガター（E/W マーカー）と波下線でリアルタイム表示する機能。

## 実装済みクラス

| クラス | 場所 | 役割 |
|---|---|---|
| `DiagnosticKind` | `src/dev/javatexteditor/analysis/DiagnosticKind.java` | ERROR / WARNING の enum |
| `CompileDiagnostic` | `src/dev/javatexteditor/analysis/CompileDiagnostic.java` | 診断1件を表すレコード（lineNumber, column, message, kind） |
| `CompileAnalyzer` | `src/dev/javatexteditor/analysis/CompileAnalyzer.java` | JavacTask.analyze() まで実行して診断を収集 |

## ⑧ SourceAnalyzer との違い

| | SourceAnalyzer | CompileAnalyzer |
|---|---|---|
| 解析レベル | `javacTask.parse()` のみ | `parse()` + `analyze()` |
| 型解決 | なし | あり（未定義型・型不一致を検出） |
| 用途 | import 索引・シンボル索引の高速生成 | コンパイルエラー表示 |

## EditorCanvas の変更

```java
// 診断をセット → ガター描画 + アンダーライン描画 + ステータスバー件数表示
canvas.setDiagnostics(List<CompileDiagnostic>);
canvas.getDiagnostics(); // テスト用
```

**描画の仕組み**:
- `diagnostics` が空のとき `gutterWidth = 0`（既存テストへの影響なし）
- `diagnostics` が非空のとき `gutterWidth = 2 * charWidth`（"E "/"W " 2文字分）
- ガター背景: `theme.background.darker()`
- E マーカー: `Color(0xCC, 0x33, 0x33)` (赤)
- W マーカー: `Color(0xCC, 0x99, 0x00)` (黄)
- 波下線: 4px 周期の折れ線。エラー行全体の幅に描画
- ステータスバー右端: "N error(s), M warning(s)"

## ModalEditor の変更

```java
// INSERT→NORMAL 復帰時フック（バックグラウンドコンパイルのトリガー）
editor.setOnReturnToNormal(Runnable callback);
```

`processInsertKey()` の `"enter.normal"` ケースでのみ呼ばれる（INSERT→NORMAL のみ対象）。

## バックグラウンドコンパイルの接続（Main.java での使用例）

```java
CompileAnalyzer compileAnalyzer = new CompileAnalyzer();
editor.setOnReturnToNormal(() -> {
    String src = editor.getText();
    Thread.ofVirtual().start(() -> {
        try {
            List<CompileDiagnostic> diags = compileAnalyzer.analyze(src);
            SwingUtilities.invokeLater(() -> canvas.setDiagnostics(diags));
        } catch (AnalysisException e) {
            SwingUtilities.invokeLater(() -> canvas.setDiagnostics(List.of()));
        }
    });
});
```

## 設計上の注意点

### public クラスとファイル名の不一致

`analyze(String sourceCode)` は仮想ファイル名 `<buffer>` を使うため、
`public class Foo { ... }` を渡すと "class Foo is public, should be declared in a file named Foo.java"
という ERROR が出る。

- **実用時**: `analyzeFile(Path path)` を使うか、実際のファイルパスを URI に設定する
- **テスト時**: `class Foo { ... }`（非 public）を使う
- この挙動は仕様で、`SourceAnalyzer`（parse-only）は型解決しないため同じ問題が出ない

### UI スレッド安全性

`CompileAnalyzer` はバックグラウンドスレッドで呼び、結果を `SwingUtilities.invokeLater()` で
`EditorCanvas.setDiagnostics()` に渡すこと。`setDiagnostics()` は `repaint()` を含むため。

### 行番号の変換

javac は 1-indexed の行番号を返す。`CompileAnalyzer` 内で 0-indexed に変換済み（`lineNumber - 1`）。
`lineNumber <= 0`（不明）の場合は 0 扱い。

## テスト

`test/dev/javatexteditor/analysis/CompileAnalyzerTest.java` に 15 テスト。

- 正常ソースでエラー0件
- 構文エラー（セミコロン欠落）の行番号付き検出
- 未定義型の型エラー検出
- 複数エラーの複数件返却
- `CompileDiagnostic` record のフィールドアクセス
- `EditorCanvas.setDiagnostics()` の単体動作
- `ModalEditor.setOnReturnToNormal()` フックの動作確認
