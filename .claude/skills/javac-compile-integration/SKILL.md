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

## メモリ肥大化の修正: 編集のたびにプロジェクト全体を再コンパイルしていた問題（2026-08-07）

- **症状**: Javaファイルを開いて数分編集しているだけで、プロセスのメモリ使用量が
  4,000MB超（＝物理メモリ16GB機での `MaxHeapSize` 既定値 = RAMの1/4）まで増え続け、
  以後も下がらない。Emacsで同じ作業をした場合は28MB程度。
- **原因**: `CompileAnalyzer.analyzeSourceWithProject()` が
  「作業ディレクトリ配下の全 `.java` を `Files.walk` で列挙 → 全ファイルの内容を `String` として
  読み込み → 全部まとめて `javac` に渡して `parse()` + `analyze()`」を
  **解析要求のたびに毎回**実行していた。このプロジェクト自身（約27,000行）を対象にした実測で
  **1回あたり約371〜418MBの割り当て・0.8〜3.2秒**。解析は INSERT離脱・保存・
  バッファ変更（400msデバウンス）で駆動されるため、編集中は数秒に1回この規模の割り当てが起き、
  G1はヒープを最大値まで拡張したまま返さなくなる。
  さらに `LiveDiagnostics` が解析要求ごとに `Thread.ofVirtual().start()` していたため、
  1回の解析が終わる前に次が始まり、**数百MB級の作業メモリが同時に何本も生存**しうる状態だった
  （世代カウンタは「返ってきた結果を捨てる」だけで解析自体は止めない）。
- **修正**:
  1. **`-sourcepath` 方式へ変更**（`CompileAnalyzer` + 新規 `JavaSourceRoots`）。
     javacのコンパイル対象として明示的に渡すのは編集中のバッファ1件だけにし、他のプロジェクト
     ソースは `-sourcepath` 経由で「シンボル解決に必要になった分だけ」javacに遅延読み込みさせる。
     sourcepathに渡すソースルートは、①編集中ファイルのパスと `package` 宣言から直接導出、
     ②プロジェクトルート配下を走査し `.java` を含むディレクトリごとに先頭1ファイルの
     `package` 宣言だけを読んで導出（60秒TTLでキャッシュ）、の2段構えで求める。
     `-implicit:none` も併用する。
  2. **解析の直列化**（`LiveDiagnostics`）。`Thread.ofVirtual()` の都度起動をやめ、
     デーモンの単一スレッド `ExecutorService` に載せた。生存する解析は常に1本だけになる。
     加えて、実行開始時点で世代カウンタを確認し、追い越されていれば **javacを動かさずに破棄**する
     （従来は結果が出てから捨てていた）。
  3. **同一ソースの再解析をスキップ**。直前に解析したパスと内容を（ペインごとに）保持し、
     一致すれば javac を再実行せずキャッシュした診断を使う。診断表示・auto-import の
     EDT側処理は従来どおり実行するため、観測できる挙動は変わらない。
     （ESC直後に `:w` する、auto-import適用後にバッファ変更フックが走る等で頻繁に起きるケース。）
- **効果（同一4ファイルでの実測）**: 371〜418MB / 0.8〜3.2秒 → **12〜65MB / 0.02〜0.5秒**。
  「20回のINSERT編集＋ESC」を再現したシナリオでは、コミット済みヒープのピークが
  442MB → **254MB（＝初期ヒープのまま拡張されない）** に収まった。
- **診断結果は変えていない**（むしろ正確になった）: クロスパッケージのシンボル解決、
  import欠落時の `cannot find symbol` はいずれも従来と同一の行・件数で検出される。
  旧実装が余分に出していた `duplicate class`（バッファを `string:///` として渡しつつ
  同じファイルを `Files.walk` でも拾うことによる自己重複）は消える。
- **テスト**: `test/dev/javatexteditor/analysis/JavaSourceRootsTest.java` に7テスト追加。
  ソースルート導出（package宣言あり/なし/`src/main/java` 入れ子/未保存バッファ）と、
  クロスパッケージ解決・import欠落検出・「参照していない壊れたファイルは解析対象にならない」ことを確認。
  既存の `CompileAnalyzerTest`（17件）は変更なしで全PASS。

### 既知の差分: 構文が壊れた途中状態での診断内容

正しくコンパイルできるソースに対しては、新旧の診断は完全に一致する
（このプロジェクト自身の全154ファイルを解析し、誤検知0件を確認済み。
`JavaSourceRootsTest` はパッケージごとの代表ファイルで同じ確認を継続的に行う）。

一方、**`package` 宣言そのものが壊れているような編集途中のバッファ**では、
javacのエラー回復の挙動が旧実装と変わり、診断の件数・文言が一致しないことがある。
実測例（`package` 文の途中に別のクラス宣言が割り込んだ状態のバッファ）:

| | 診断件数 | うち `cannot find symbol` | うち `bad source file` |
|---|---|---|---|
| 旧（全ファイル一括） | 87 | 71 | 0 |
| 新（`-sourcepath`） | 35 | 28 | 3 |

どちらも「壊れたファイルに対する壊れた診断」であり、優劣は無い。auto-import は
`cannot find symbol` のみを入力にするため、`bad source file` が誤ったimport挿入を
引き起こすことはない（構文を直せば次の解析で正常な診断に戻る）。
