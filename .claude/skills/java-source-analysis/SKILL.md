---
name: java-source-analysis
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、JDK標準のCompiler Tree API（com.sun.source.tree.*）でJavaソースのASTを解析し、import索引・シンボル索引を構築する際に使用する。「Javaソースを解析したい」「import文を読み取りたい」「クラス/メソッド/フィールドの一覧が欲しい」「構文エラーがあっても部分解析したい（graceful degradation）」といった相談、またSourceAnalyzer/SourceIndex/SymbolEntryを触る作業に着手する前に、必ず最初に参照すること。⑨〜⑯の解析系Skillの共通基盤。"
---

# Skill: java-source-analysis

## 概要

JDK 標準の Compiler Tree API (`com.sun.source.tree.*` / `javax.tools.JavaCompiler`) を使って Java ソースの AST を解析し、import・シンボル索引を構築する基盤。

parse-only モードで動作するため型解決は行わず、構文エラーがあっても部分解析（graceful degradation）する。

## 実装済みクラス

```
src/dev/javatexteditor/analysis/
├── AnalysisException.java   checked exception
├── ImportEntry.java         import 文1件 (record)
├── SourceAnalyzer.java      Compiler Tree API 呼び出し本体
├── SourceIndex.java         解析結果 (record)
├── SymbolEntry.java         シンボル1件 (record)
└── SymbolKind.java          CLASS / INTERFACE / ENUM / METHOD / FIELD / CONSTRUCTOR (enum)
```

## データモデル

```java
record SourceIndex(
    String filePath,           // "<buffer>" or absolute path
    List<ImportEntry> imports,
    List<SymbolEntry> symbols,
    boolean hasParseError
)

record ImportEntry(
    String fullyQualifiedName, // ワイルドカード時は ".*" を除いた部分 e.g. "java.util"
    boolean isStatic,
    boolean isWildcard,
    int lineNumber             // 0-indexed
)

record SymbolEntry(
    String name,               // コンストラクタはクラス名と同じ
    SymbolKind kind,
    int lineNumber,            // 0-indexed
    int offset                 // バッファ先頭からの文字オフセット
)
```

## SourceAnalyzer API

```java
// バッファ内容から直接解析（ファイル保存不要）
SourceIndex analyzeText(String sourceCode) throws AnalysisException

// ファイルパスから解析
SourceIndex analyzeFile(Path path) throws AnalysisException
```

## 実装上の重要ポイント

### 文字列ソースの URI 正規化

`SimpleJavaFileObject` に `string:///...` 形式の URI を渡す際、`<buffer>` のような
角括弧を含む文字列は URI として不正。`toUri()` で `<>` 等を `_` に置換する。

```java
private static URI toUri(String filePath) {
    String safe = filePath.replace('\\', '/')
                          .replaceAll("[<>\"{}|\\\\^`\\[\\] ]", "_");
    return URI.create("string:///" + safe);
}
```

### parse-only 実行

`JavaCompiler.getTask()` に `-proc:none` オプションを渡し、`JavacTask.parse()` のみ呼ぶ。
`analyze()` は呼ばないため型解決なしで高速に動作する。

### シンボル収集スコープ

- トップレベルの型宣言（`CompilationUnitTree.getTypeDecls()`）のクラス/インタフェース/enum を収集
- その直接メンバー（メソッド・フィールド・コンストラクタ）を収集
- **ネストしたクラスは収集しない**（`ClassTree` のメンバーのうち `ClassTree` は無視）
- コンストラクタの `MethodTree.getName()` は `"<init>"` を返すが、`SymbolEntry.name` にはクラス名を格納

### 行番号

`Trees.getSourcePositions().getStartPosition()` → `CompilationUnitTree.getLineMap().getLineNumber()` で取得。
戻り値は 1-indexed なので `-1` して 0-indexed に変換する。

## テスト

```
test/dev/javatexteditor/analysis/SourceAnalyzerTest.java
```

49 テスト全 PASS（import 収集・static/wildcard 区別・シンボル収集・行番号・構文エラー耐性・空ソース・ファイル解析・バッファ解析・ネスト除外）

## 後続 Skill への注意

- ⑨ `javac-compile-integration`: コンパイルエラーを表示する際は `DiagnosticCollector` を再利用できる
- ⑩ `jdk-api-navigation`: クラスパス上の型を索引するには `JavacTask.analyze()` まで実行する必要がある
- ⑭ `multi-file-refactoring`: 複数ファイルを同一 `CompilationTask` でまとめて解析できる
