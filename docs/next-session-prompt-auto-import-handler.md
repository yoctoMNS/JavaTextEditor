# 次セッション: Skill ⑯ auto-import-handler

## 現在の状態

Skill ⑪ `javadoc-viewer` が完了し、main ブランチにマージ済み。全 675 テストケース PASS。

NORMALモードで `Shift+K` を押すと、ローカル Javadoc HTML があればサマリ文を、なければ種別・メソッド数・フィールド数をステータスバーに表示する機能が動作している。

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

## 次のタスク: Skill ⑯ auto-import-handler

### 目標

編集中の Java ソースで未解決の識別子（型名）が現れたとき、`import` 文を自動挿入する UI を実装する。

### 依存関係（すべて完了済み）

- ① `editor-buffer-architecture` ✅（バッファへの挿入操作）
- ⑧ `java-source-analysis` ✅（JDK クラス索引 `JdkClassIndex` ・ソース解析 `SourceAnalyzer`）
- ⑨ `javac-compile-integration` ✅（`CompileAnalyzer` でコンパイルエラーから未解決シンボルを特定）

### 設計方針（検討ポイント）

1. **トリガー**:  
   - `INSERT → NORMAL` 復帰時（既存の `setOnReturnToNormal()` フック）に未解決シンボルを検出。  
   - または `:import` コマンドで手動トリガー。

2. **未解決シンボルの特定**:  
   - `CompileAnalyzer.analyze()` の結果から「cannot find symbol」などのエラーを抽出。  
   - エラーメッセージ内の識別子名を `JdkClassIndex.lookup()` に渡してFQN候補を取得。

3. **候補が1件の場合**:  
   - 自動でバッファの適切な位置（既存 import 文の末尾、またはファイル先頭）に `import <fqn>;` を挿入。

4. **候補が複数件の場合**:  
   - ステータスバーに選択肢を表示（例: `[1] java.util.List  [2] java.awt.List`）し、数字キーで選択。  
   - `Escape` でキャンセル。

5. **重複防止**:  
   - 既存の import 文（`SourceAnalyzer` で取得）と照合し、すでに import 済みなら挿入しない。

6. **作業ディレクトリ索引との統合**:  
   - `JdkClassIndex` は JDK クラスしか持たないため、作業ディレクトリ内のクラスにも対応するには  
     `SourceAnalyzer` で作業ディレクトリをスキャンした結果も合わせて検索する（要: 索引設計の確認）。

### 実装ステップ案

1. **`ImportSuggester.java`** を新規作成  
   - `List<String> suggest(String simpleName)` → JDK 索引 + 作業ディレクトリ索引から FQN 候補を返す  
   - `boolean alreadyImported(String fqn, SourceIndex index)` → 重複チェック

2. **`AutoImportHandler.java`** を新規作成  
   - `List<String> findMissingSymbols(List<CompileDiagnostic> diags)` → エラーから未解決シンボル名を抽出  
   - `void applyImport(String fqn, PieceTable buffer)` → 既存 import の末尾に行を追加

3. **`ModalEditor`** を拡張  
   - `onReturnToNormal()` フックで `AutoImportHandler` を呼び出す  
   - 候補複数の場合は「選択待ち状態」へ遷移（新しいサブモード or ステータスバー UI）

4. **テスト**  
   - `AutoImportHandlerTest.java`（候補1件の自動挿入・複数候補・重複スキップ・エラー0件で何もしない）

### 着手前に確認すること

- `src/dev/vimacs/analysis/JdkClassIndex.java` を読んで `lookup()` の戻り値形式を把握する
- `src/dev/vimacs/analysis/CompileAnalyzer.java` を読んで診断メッセージのフォーマットを把握する
- `src/dev/vimacs/editor/ModalEditor.java` の `setOnReturnToNormal()` フックの現在の使われ方を確認する
- `docs/session-javadoc-viewer.md`・`docs/session-javac-compile-integration.md` を参照して設計経緯を把握する
- `.claude/skills/` 配下の関連 SKILL.md を必ず参照すること

### ブランチ

`claude/auto-import-handler`（新規作成して作業）

### 完了条件

- `AutoImportHandlerTest` が全 PASS
- 既存 675 テストケースが引き続き PASS（Robot テスト含む）
- README.md にキーバインドと動作説明を追記
- `docs/session-auto-import-handler.md` に作業ログを記録
- main ブランチにマージ
