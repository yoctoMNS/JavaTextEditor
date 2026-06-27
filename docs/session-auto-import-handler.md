# Session: Skill ⑯ auto-import-handler

## 概要

INSERT→NORMAL 復帰時にコンパイルエラーから未解決の型名を抽出し、JDK クラス索引を使って `import` 文を自動挿入する機能を実装した。

## 新規ファイル

| ファイル | 役割 |
|---|---|
| `src/dev/vimacs/analysis/ImportSuggester.java` | 単純名→FQN候補のルックアップ・重複チェック |
| `src/dev/vimacs/analysis/AutoImportHandler.java` | エラー解析・挿入offset計算・バッファへのimport挿入 |
| `test/dev/vimacs/analysis/AutoImportHandlerTest.java` | 26テスト（全PASS） |

## 変更ファイル

| ファイル | 変更内容 |
|---|---|
| `src/dev/vimacs/editor/ModalEditor.java` | `handleAutoImport()` メソッド追加・import選択待ち状態の処理 |
| `src/dev/vimacs/Main.java` | `AUTO_IMPORT_HANDLER` の生成と `handleAutoImport()` の呼び出し配線 |
| `README.md` | 特徴・キーバインドに auto-import を追記 |
| `CLAUDE.md` | ロードマップ ⑯ を「完了」に更新 |

## 設計判断

### トリガー

INSERT→NORMAL 復帰時に CompileAnalyzer がバックグラウンド実行される。その結果（`List<CompileDiagnostic>`）が EDT に届いた際に `editor.handleAutoImport(diags)` を呼ぶ。`Main.setupCompileAnalysis()` 内の `SwingUtilities.invokeLater` ブロックに追加した。

### 未解決シンボルの抽出

`AutoImportHandler.findMissingSymbols()` が `CompileDiagnostic.message()` を正規表現 `symbol:\s*(?:class|interface|enum)\s+(\w+)` でマッチさせて型名を抽出する。変数名（`symbol: variable`）は意図的に除外している。

### 挿入位置の計算

`findImportInsertOffset()` がソースを行単位で走査し、最後の `import` 行の次、なければ `package` 行の次、どちらもなければファイル先頭（offset=0）を返す。

### 候補1件の場合

`applyImport()` が `SourceAnalyzer.analyzeText()` で既存 import を確認してから挿入。すでに存在する場合は挿入せず `false` を返す。

### 候補複数の場合

`ModalEditor` に `pendingImports`（選択待ちエントリのリスト）と `pendingImportIdx`（現在のインデックス）を追加。`processNormalKey()` の先頭で `pendingImports` が非空かどうかを確認し：

- 数字キー `1`〜`9`: 対応 FQN を挿入して次のシンボルへ
- `Escape`: スキップして次のシンボルへ
- 通常キー入力: 無視（選択が完了するまで）

### ステータスバー表示

```
import List? [1] java.util.List  [2] java.awt.List  [Esc]=skip
```

候補が9件を超える場合は `(+N more)` を末尾に表示。

## テスト結果

```
=== AutoImportHandlerTest: 26/26 passed ===
=== RobotKeyInputTest: PASS: 83 / 83  (auto-import 10件を含む)
=== Summary: 18 class(es) passed, 0 class(es) failed ===
```

合計テスト数: 675（既存）+ 26（AutoImportHandlerTest）+ 10（RobotKeyInputTest 追加）= **711 テスト全 PASS**

## Robot テストの追加内容

| テスト名 | 検証内容 |
|---|---|
| `testAutoImportSingleCandidate` | ArrayList など候補1件のシンボルが自動挿入され待ち状態にならないこと |
| `testAutoImportMultipleSelection` | List など複数候補があると待ち状態になり、プロンプト（[1]/[Esc]=skip）が表示され、数字キー '1' で選択・挿入できること |
| `testAutoImportEscapeSkip` | Esc でスキップすると待ち状態が解消され import が挿入されないこと |
| `testAutoImportNoDiagnostics` | コンパイルエラーがない場合は何も起きないこと |
