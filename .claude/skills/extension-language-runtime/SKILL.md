---
name: extension-language-runtime
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、javax.tools.JavaCompiler（JDK標準API）による動的コンパイルでJavaソースをその場でロードするプラグイン機構を設計・実装する際に使用する。「プラグインをどう読み込むか」「エディタ起動中に拡張コードを追加したい」「URLClassLoaderでのロード・アンロード」「:pluginコマンド」といった相談、またPluginLoader/EditorPluginを触る作業に着手する前に、必ず最初に参照すること。EditorContextインタフェースの最新定義はplugin-api-designスキル側を正とする。"
---

# Skill: extension-language-runtime

## 概要

`javax.tools.JavaCompiler`（JDK 標準 API）を使い、エディタ起動中に Java ソースファイルを
その場でコンパイル・ロードするプラグイン機構。外部ライブラリ一切不使用。

---

## インタフェース設計

### EditorContext（プラグインがエディタを操作するための窓口）

> ⚠️ 以下は v1 設計時点の最小定義。**現在の完成版インタフェース（getLine/offsetAt/setCursor/isNormalMode/getKeymap 等を含む）は `.claude/skills/plugin-api-design/SKILL.md` を正とする。**メソッドを追加する際はそちらを更新すること。

```java
package dev.javatexteditor.extension;

public interface EditorContext {
    String getText();
    int getCursorRow();
    int getCursorCol();
    void insertAt(int row, int col, String text);
    void deleteRange(int startOffset, int endOffset);
    void setStatusMessage(String message);
}
```

### EditorPlugin（プラグイン作者が実装するインタフェース）

```java
package dev.javatexteditor.extension;

public interface EditorPlugin {
    String getName();
    void onLoad(EditorContext ctx);
    void execute(EditorContext ctx);
    void onUnload();
}
```

`execute` はコマンドモードからプラグイン名で呼び出す（`:plugin <name>` で起動予定）。
`onLoad` / `onUnload` はライフサイクルイベント。最初の実装ではオプション（空実装でよい）。

---

## PluginLoader 設計

```
loadPlugin(Path sourceFile) → EditorPlugin
  1. javax.tools.ToolProvider.getSystemJavaCompiler() でコンパイラ取得
  2. StandardJavaFileManager でソースファイルをコンパイル（クラスファイル出力先: 一時ディレクトリ）
  3. URLClassLoader(new URL[]{ tmpDir.toUri().toURL() }) でクラスをロード
  4. Class.forName(className, true, loader) → newInstance() → EditorPlugin にキャスト
  5. 取得した (loader, plugin) ペアを Map<String, LoadedPlugin> に格納

unloadPlugin(String name)
  1. Map から LoadedPlugin を取得
  2. plugin.onUnload() 呼び出し
  3. loader.close() で URLClassLoader を閉じる（Java 7+ で Closeable）
  4. Map から削除
  ※ クラスオブジェクトの完全な GC は loader への参照がなくなった後
```

### コンパイルエラーの扱い

`JavaCompiler.CompilationTask.call()` が `false` を返したら `PluginLoadException` をスロー。
`DiagnosticCollector<JavaFileObject>` で収集したエラーメッセージを例外メッセージに含める。

---

## セキュリティモデル（v1 方針）

Java 17+ で `SecurityManager` は非推奨・Java 21 で削除済みのため使用しない。

v1 ではインタフェース実装確認のみ（信頼できるプラグインを前提とした「パワーユーザー向け拡張」）。
危険な操作への制限は将来課題（モジュールシステム + カスタム ClassLoader による制限が現実的）。

---

## PluginLoader 実装の注意点

1. **`javax.tools` は JDK のみ**：`ToolProvider.getSystemJavaCompiler()` は JRE 単体では `null` を返す。
   ビルド・実行は JDK 21 で行うため問題ないが、`null` チェックで明示的なエラーメッセージを出す。

2. **クラスパスの継承**：プラグインが `dev.javatexteditor.*` の型を使えるように、コンパイル時に
   `-classpath` として現在の JVM クラスパス（`System.getProperty("java.class.path")`）を渡す。

3. **URLClassLoader の親**：`Thread.currentThread().getContextClassLoader()` を親に設定し、
   エディタ本体のクラスを可視にする。

4. **クラス名の決定**：ソースファイル名（拡張子除く）をクラス名として使う。
   パッケージ宣言がある場合は `package dev.javatexteditor.plugins;` を先頭に付けることを規約化する。

5. **再ロード**：同名プラグインを再ロードする場合は、先に `unloadPlugin` を呼んでから再 `loadPlugin`。

---

## 実装ファイル一覧

| ファイル | 役割 |
|---|---|
| `src/dev/javatexteditor/extension/EditorContext.java` | プラグイン向けエディタ操作インタフェース |
| `src/dev/javatexteditor/extension/EditorPlugin.java` | プラグイン実装インタフェース |
| `src/dev/javatexteditor/extension/PluginLoadException.java` | コンパイル失敗・型不整合エラー |
| `src/dev/javatexteditor/extension/PluginLoader.java` | 動的コンパイル・ロード・ライフサイクル管理 |
| `src/dev/javatexteditor/extension/SimpleEditorContext.java` | ModalEditor をラップした EditorContext 実装 |
| `test/dev/javatexteditor/extension/PluginLoaderTest.java` | 自作テストハーネス（5+ ケース） |

---

## 設計判断ログ

| 判断 | 理由 |
|---|---|
| 拡張言語に Java 自身を採用 | Lisp インタプリタを自作するより学習コストが低く、IDE との親和性が高い |
| URLClassLoader 1プラグイン1インスタンス | プラグイン間のクラス競合回避・unload 可能にするため |
| クラスファイル出力先を一時ディレクトリに | ソースと分離・複数バージョン共存を防ぐ |
| SecurityManager 不使用 | Java 17+ で非推奨・Java 21 で削除済み |
| EditorContext 経由の間接アクセス | プラグインが ModalEditor 内部に直接依存しないように疎結合を保つ |
