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

## 不具合修正: NORMALモードでの行増減が診断に反映されず保存するまで古い行に赤線が残る

- **症状**: `dd`（行削除）・`p`（ペースト）・`u`（undo）・`Ctrl+R`（redo）等、INSERT離脱（`onReturnToNormal`）・
  保存（`onSave`）のどちらも経由しないバッファ変更操作で行数が増減しても、`EditorCanvas` のガター・波下線は
  古い行番号のまま更新されず、`:w` で保存して初めて正しい位置に付け替わっていた。
- **原因**: `Main.setupCompileAnalysis()` が再解析をトリガーする経路は `onReturnToNormal`（INSERT→NORMAL遷移時）
  と `onSave`（保存成功時）の2つしかなく、NORMAL/VISUALモード内で完結する編集操作（dd/p/u/Ctrl+R/`:s`置換等）は
  どちらの経路にも該当しないため、診断が一切再計算されないまま古い診断オブジェクト（＝古い行番号）がそのまま
  描画され続けていた。
- **修正**: `UndoablePieceTable` に `version`（insert/delete/undo/redoのたびに増分するカウンタ。`getVersion()`）を
  追加し、`ModalEditor.processKey()` の末尾（既存の `syncCanvas()` の直後）でこの版数が変化したかを常に
  チェックする一箇所に集約した。変化した場合のみ新設の `onBufferChanged` コールバックを呼ぶ
  （`setOnBufferChanged(Runnable)`）。これにより「どのキー操作経由でバッファが変わったか」を個々のアクション
  ハンドラに手を入れず一律検知できる（`modified`フラグは保存判定用で一度trueになると`markSaved()`まで
  戻らないため今回の用途には使えず、版数として別カウンタにした）。
  - `Main.setupCompileAnalysis()` は `onBufferChanged` に `javax.swing.Timer`（400ms・`setRepeats(false)`・
    キー入力のたびに `restart()`）で束ねたデバウンス経由の再解析を登録した。連続編集のたびに毎回コンパイルを
    走らせるとdd/pの連打・macro再生等で重くなるため。
  - **INSERTモード中は対象外にした**（`if (!editor.isInsertMode()) debounceTimer.restart();`）。INSERT中は
    1文字ごとにバッファversionが変わり続けるが、入力途中の構文を都度解析しても無駄であり、離脱時の解析は
    既存の `onReturnToNormal` がそのまま担う。ESC押下自体はテキストを変更しないため、ESC時点で
    `onBufferChanged` が別途発火することもない（version比較は「前回チェック時からの差分」のみを見るため）。
- **意図的に変更しなかった点**: `onReturnToNormal`/`onSave` の既存トリガ自体は変更していない（即時発火の
  ままで、`onBufferChanged` 経由のデバウンスに統合していない）。両者は「離脱直後に確実に1回」という強い
  保証がある方が望ましいため、あえて別経路として残した。
- **テスト**: `test/dev/javatexteditor/editor/CompileTriggerCallbackTest.java` に4テスト追加（計8テスト）。
  NORMALモードの `dd` で発火・カーソル移動のみでは発火しない・INSERT中は生の `onBufferChanged` は毎回
  発火するがMain契約（`isInsertMode()`時は無視）なら実質カウントされないこと・`undo`（`u`）でも発火することを
  確認済み。`Main.java` 側の実際の `javax.swing.Timer` デバウンス配線はGUI依存のため自動テスト対象外
  （既知のテストギャップ。F10/F11/F12等と同種）。
