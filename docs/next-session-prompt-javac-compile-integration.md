# 次セッション向けプロンプト: ⑨ javac-compile-integration

## プロジェクト概要

Vim（モーダル編集）とEmacs（拡張性）の良い所を統合した Java SE 21 製テキストエディタ「Vimacs」。
外部ライブラリなし・JUnit なし・自作テストハーネス（`main` メソッド形式）で実装・テスト済み。

## 現在の状態（実装済み）

| Skill | 内容 | 状態 |
|---|---|---|
| ① editor-buffer-architecture | PieceTable + UndoablePieceTable | ✅ 完了 (15+11 テスト) |
| ② modal-editing-engine | NORMAL/INSERT/COMMAND/VISUAL/VISUAL_LINE | ✅ 完了 (151 テスト) |
| ③ extension-language-runtime | JavaCompiler 動的プラグインロード | ✅ 完了 (9 テスト) |
| ④ keymap-conflict-resolution | KeymapRegistry + Phase 3 プラグインキーバインド | ✅ 完了 (46 テスト) |
| ⑤ gui-rendering-pipeline | Swing/AWT GUI、縦横スクロール、JSplitPane | ✅ 完了 (22 テスト) |
| ⑥ plugin-api-design | EditorContext 公開API（行操作・カーソル・キーマップ） | ✅ 完了 (39 テスト) |
| ⑦ editor-testing-strategy | 境界値・パフォーマンス・深いアンドゥのテスト | ✅ 完了 (101 テスト追加・計394 全PASS) |
| ⑧ java-source-analysis | Compiler Tree API による AST 解析・import/シンボル索引 | ✅ 完了 (49 テスト・累計 553 全PASS) |

## 今回の作業: ⑨ javac-compile-integration

### 目標

エディタが現在開いているバッファの内容を `javax.tools.JavaCompiler` でコンパイルし、
**コンパイルエラーを EditorCanvas のガター（行番号欄）と波下線でリアルタイム表示する**機能を実装する。

具体的には:

1. **コンパイル実行**: バッファ内容を一時ファイルに書き出して `javac` でコンパイル（フルコンパイル）、または ⑧ の `JavacTask` の `analyze()` まで実行（型解決あり）
2. **エラー収集**: `DiagnosticCollector<JavaFileObject>` でコンパイルエラー・警告を行番号付きで収集
3. **エラーモデル**: `CompileDiagnostic` record（行番号・列番号・メッセージ・種別 ERROR/WARNING）
4. **EditorCanvas への表示**:
   - ガター（左端の行番号列）にエラーマーカー（例: `E` / `W` の赤・黄色テキスト）
   - エラー行のテキストに波下線（または背景色変化）
   - ステータスバーにエラー数を表示（例: `2 errors, 1 warning`）
5. **トリガー**: INSERT モードから NORMAL モードに戻ったとき（`:w` 保存後でも可）に自動で解析を走らせる

### 技術要件

- **言語**: Java 21・外部ライブラリなし
- **⑧ の SourceAnalyzer との関係**: SourceAnalyzer は parse-only。⑨ は型解決まで行う（`javac` フルコンパイルか `JavacTask.analyze()` まで実行）。別クラスで実装すること
- **クラスパス**: バッファ対象ファイルが依存するライブラリのクラスパスをどう扱うか要検討（初期実装では標準ライブラリのみでも可）
- **パフォーマンス**: コンパイルはバックグラウンドスレッドで実行。UI スレッドをブロックしない
- **エラーがない場合**: ガターを空にしてステータスバーをクリア

### 期待するクラス構成

```
src/dev/vimacs/analysis/
├── (既存: SourceAnalyzer, SourceIndex, ImportEntry, SymbolEntry, SymbolKind, AnalysisException)
├── CompileDiagnostic.java    # エラー1件を表す record (line, col, message, kind)
├── DiagnosticKind.java       # ERROR / WARNING (enum)
└── CompileAnalyzer.java      # javac フルコンパイルを実行してエラーを収集する

src/dev/vimacs/ui/
└── EditorCanvas.java         # setDiagnostics(List<CompileDiagnostic>) メソッドを追加
```

### CompileDiagnostic の設計案

```java
public enum DiagnosticKind { ERROR, WARNING }

public record CompileDiagnostic(
    int lineNumber,    // 0-indexed
    int column,        // 0-indexed
    String message,    // コンパイラのエラーメッセージ
    DiagnosticKind kind
) {}
```

### CompileAnalyzer の設計案

```java
public class CompileAnalyzer {
    // バッファ内容を仮クラス名でコンパイルしてエラーを収集
    public List<CompileDiagnostic> analyze(String sourceCode) throws AnalysisException { ... }

    // ファイルパスからコンパイル
    public List<CompileDiagnostic> analyzeFile(Path path) throws AnalysisException { ... }
}
```

### EditorCanvas 変更案

```java
// EditorCanvas に追加
public void setDiagnostics(List<CompileDiagnostic> diagnostics) { ... }

// paintComponent() 内で
// ガター列を追加（行番号の左側に E/W マーカー）
// エラー行のテキストに下線
```

### テスト項目（案）

```
- 正常な Java ソースでエラーが0件になる
- 構文エラー（Missing semicolon など）が行番号付きで検出される
- 未定義の型参照（型エラー）が検出される
- WARNING レベルの診断が区別される
- バッファ文字列から直接解析できる（ファイル不要）
- エラーがある場合に DiagnosticKind.ERROR が含まれる
- EditorCanvas.setDiagnostics() でガター描画が更新される（テストは単体で OK）
```

### 作業手順

1. `.claude/skills/javac-compile-integration/SKILL.md` を作成し設計を記録
2. `src/dev/vimacs/analysis/` に `CompileDiagnostic`, `DiagnosticKind`, `CompileAnalyzer` を実装
3. `EditorCanvas` に `setDiagnostics()` とガター描画を追加
4. `ModalEditor` のモード遷移フック（NORMAL 復帰時）でバックグラウンドコンパイルを呼び出す
5. `test/dev/vimacs/analysis/CompileAnalyzerTest.java` を実装しテスト通過を確認
6. `scripts/test.sh` は自動検出するため追加不要
7. `CLAUDE.md` の Skill ⑨ を「完了」に更新
8. `README.md` に新機能を追記
9. `docs/session-javac-compile-integration.md` に作業ログを作成
10. コミット・プッシュ・main マージ

### 注意事項

- ⑧ `SourceAnalyzer` は parse-only で型解決しない。`CompileAnalyzer` は `analyze()` まで（または `compiler.run()`）実行すること
- `JavacTask.analyze()` は `parse()` の後に呼ぶ（同じ `JavacTask` インスタンスで連続して呼べる）
- バックグラウンドスレッドからの UI 更新は `SwingUtilities.invokeLater()` 経由で行うこと
- `EditorCanvas.paintComponent()` のガター幅を動的に拡張する場合は、`fm.stringWidth()` で計算すること
- 既存の 553 テスト全 PASS を維持すること（`./scripts/test.sh` で確認）

## ブランチ運用

- **作業ブランチ**: `claude/javac-compile-integration-XXXXXX`（新規作成）
- 完了後に `main` へマージ

## 参考ファイル

- `src/dev/vimacs/analysis/SourceAnalyzer.java` — ⑧ の parse-only 実装（`JavacTask.parse()` の使い方）
- `src/dev/vimacs/extension/PluginLoader.java` — `compiler.run()` の使用例
- `src/dev/vimacs/ui/EditorCanvas.java` — 描画コンポーネント（ガター追加の対象）
- `src/dev/vimacs/editor/ModalEditor.java` — モード遷移の実装（NORMAL 復帰フックの挿入場所）
- `.claude/skills/java-source-analysis/SKILL.md` — ⑧ の設計知識（URI 正規化・行番号変換）
- `.claude/skills/gui-rendering-pipeline/SKILL.md` — EditorCanvas の描画アーキテクチャ
- `docs/session-java-source-analysis.md` — ⑧ のセッションログ
