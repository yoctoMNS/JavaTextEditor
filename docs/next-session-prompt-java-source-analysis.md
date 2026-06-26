# 次セッション向けプロンプト: ⑧ java-source-analysis

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

## 今回の作業: ⑧ java-source-analysis

### 目標

JDK 標準の **Compiler Tree API**（`com.sun.source.tree.*` / `javax.tools.JavaCompiler`）を使って、
エディタが開いているファイルの **AST（抽象構文木）を解析**し、以下の索引を構築する基盤を実装する：

1. **import索引**: ファイル内の `import` 文を列挙（auto-import の材料）
2. **シンボル索引**: トップレベルクラス名・メソッド名・フィールド名の位置情報（行番号/オフセット）
3. **型名解決の下準備**: 未修飾の型名（`String`、`List` 等）を FQN に解決するための参照先の列挙

この Skill は⑨（javac連携）・⑩（JDKナビゲーション）・⑭（マルチファイルリファクタリング）の基盤になる。

### 技術要件

- **言語**: Java 21・外部ライブラリなし（`com.sun.source.*` は JDK バンドル済みなので使用可能）
- **入力**: `String`（バッファ内容）または `java.nio.file.Path`（ファイルパス）
- **出力**: Java records でモデル化した索引オブジェクト
- **エラー**: 構文エラーがあっても部分的に解析できるよう graceful degradation する
- **パフォーマンス**: 通常のJavaファイル（〜1000行）で 200ms 以内の解析

### 期待するクラス構成

```
src/dev/vimacs/analysis/
├── SourceIndex.java          # 解析結果を保持するデータクラス (record)
├── ImportEntry.java          # import 文1件を表す record (fqn, isStatic, lineNumber)
├── SymbolEntry.java          # シンボル1件を表す record (name, kind, lineNumber, offset)
├── SourceAnalyzer.java       # Compiler Tree API を呼び出す本体
└── AnalysisException.java    # 解析不能時に投げる checked exception

test/dev/vimacs/analysis/
└── SourceAnalyzerTest.java   # main メソッド形式のテスト
```

### SourceIndex の設計案

```java
public record SourceIndex(
    String filePath,          // 解析対象ファイルパス（バッファ解析時は "<buffer>"）
    List<ImportEntry> imports, // import 文の一覧
    List<SymbolEntry> symbols, // クラス・メソッド・フィールドの一覧
    boolean hasParseError      // 構文エラーがあったかどうか
) {}

public record ImportEntry(
    String fullyQualifiedName, // "java.util.List"
    boolean isStatic,          // static import かどうか
    boolean isWildcard,        // "java.util.*" かどうか
    int lineNumber             // 0始まりの行番号
) {}

public record SymbolEntry(
    String name,         // "MyClass" / "doSomething" / "count"
    SymbolKind kind,     // CLASS / INTERFACE / METHOD / FIELD / CONSTRUCTOR
    int lineNumber,      // 0始まりの行番号
    int offset           // バッファ先頭からの文字オフセット
) {}

public enum SymbolKind { CLASS, INTERFACE, ENUM, METHOD, FIELD, CONSTRUCTOR }
```

### SourceAnalyzer の実装方針

```java
public class SourceAnalyzer {
    // バッファ内容から直接解析（ファイル保存不要）
    public SourceIndex analyzeText(String sourceCode) throws AnalysisException { ... }

    // ファイルパスから解析
    public SourceIndex analyzeFile(Path path) throws AnalysisException { ... }
}
```

Compiler Tree API の使い方：
1. `ToolProvider.getSystemJavaCompiler()` でコンパイラ取得
2. `JavaCompiler.getStandardFileManager()` でファイルマネージャ取得
3. `JavacTask` を `parseType=parse-only` で実行（実コンパイル不要・型解決不要）
4. `CompilationUnitTree` を走査して import/クラス/メソッド/フィールドを収集
5. `Trees.getSourcePositions()` で各ノードの行番号とオフセットを取得

バッファ内容（文字列）を直接解析するには `SimpleJavaFileObject` を継承して URI `"string:///..."` で文字列を返すダミーファイルオブジェクトを使う。

### テスト項目（案）

```
- 正常な Java ファイルから import 一覧を正確に取得できる
- static import / ワイルドカード import を区別できる
- トップレベルクラス名の行番号が正しい
- メソッド名の行番号が正しい
- フィールド名の行番号が正しい
- 構文エラーがあっても部分的に解析できる (hasParseError == true かつ imports が空でない)
- ネストしたクラスは収集対象外（トップレベルのみ）
- バッファ内容（文字列）から直接解析できる（ファイル不要）
- 空ファイルを解析しても例外にならない
```

### 作業手順

1. `.claude/skills/java-source-analysis/SKILL.md` を作成し設計を記録
2. `src/dev/vimacs/analysis/` 配下のクラスを実装
3. `test/dev/vimacs/analysis/SourceAnalyzerTest.java` を実装しテスト通過を確認
4. `scripts/test.sh` は `*Test.class` を自動検出するため追加不要
5. `CLAUDE.md` の Skill ⑧ を「完了」に更新
6. `README.md` に新機能を追記
7. `docs/session-java-source-analysis.md` に作業ログを作成
8. コミット・プッシュ・main マージ

### 注意事項

- `com.sun.source.*` は JDK の内部 API だが Java 9 以降は `--add-exports` なしで使える
  ただし `javac` と `java` の実行時に警告が出る場合は `scripts/build.sh` と `scripts/test.sh` に
  `--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED` 等を追加する
- **言語**: Java 21 (LTS)。外部ライブラリ不使用
- **ビルド**: `./scripts/build.sh`（javac 直接）
- **テスト**: `./scripts/test.sh`
- 実装前に `.claude/skills/` 配下の既存 SKILL.md を確認すること（特に `extension-language-runtime`）
  ③ の `PluginLoader` がすでに `javax.tools.JavaCompiler` を使っているので再利用・参考にすること

## ブランチ運用

- **作業ブランチ**: `claude/java-source-analysis-XXXXXX`（新規作成）
- 完了後に `main` へマージ

## 参考ファイル

- `src/dev/vimacs/extension/PluginLoader.java` — JavaCompiler の使用例（動的コンパイル）
- `src/dev/vimacs/buffer/PieceTable.java` — バッファ API（テキスト取得）
- `test/dev/vimacs/analysis/SourceAnalyzerTest.java` — 作成予定テストの場所
- `scripts/build.sh` / `scripts/test.sh` — ビルド/テストの実行方法
- `docs/session-testing-strategy.md` — 直前のセッションログ（テスト設計の参考）
