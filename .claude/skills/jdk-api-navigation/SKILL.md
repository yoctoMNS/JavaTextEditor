---
name: jdk-api-navigation
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、jrt:/ファイルシステムの走査によるJDKクラス索引（JdkClassIndex）とリフレクションによるクラス情報表示を設計・実装する際に使用する。「JDKクラスの情報を引きたい」「jrt:/の走査方法」「クラス名→FQN解決」といった相談の前に必ず参照すること。ただしKキーの現在の挙動は㉓symbol-definition-navigationで定義ジャンプに統合済みのため、Kキーの仕様については同スキルを正とする。"
---

# Skill ⑩: jdk-api-navigation

> ⚠️ **この文書の一部は㉓ `symbol-definition-navigation` により上書きされている。**
> 本書の「Kキーで種別・メソッド数等をステータスバーに1行表示する」という記述は初期実装時の仕様であり、
> 現在の `K` はプロジェクト内シンボル・JDKメンバーの**定義ジャンプ**に統合済み（`gd` は廃止）。
> `K` キーの現在の仕様は `.claude/skills/symbol-definition-navigation/SKILL.md` を正とし、
> 本書は JdkClassIndex（jrt:/ 索引）・JdkTypeInfo の基盤実装の資料として参照すること。

## 概要

NORMALモードの `K` キーで、カーソル位置にある識別子を JDK クラスとして検索し、
種別・メソッド数・フィールド数などの情報をステータスバーに1行表示する機能。

## 実装済みクラス

| クラス | 場所 | 役割 |
|---|---|---|
| `JdkClassIndex` | `src/dev/javatexteditor/analysis/JdkClassIndex.java` | jrt:/ 走査・クラス名→FQN マップ構築・Class<?> ロード |
| `JdkTypeInfo` | `src/dev/javatexteditor/analysis/JdkTypeInfo.java` | リフレクションによるクラス情報 (kind/methods/fields) |

## JDK クラスインデックス構築

```java
// JVM 起動時に自動マウントされている jrt:/ を取得（newFileSystem ではなく getFileSystem）
FileSystem jrtFs = FileSystems.getFileSystem(URI.create("jrt:/"));
Path modulesRoot = jrtFs.getPath("/modules");
Files.walk(modulesRoot)
    .filter(p -> p.toString().endsWith(".class"))
    .forEach(p -> { /* pathToFqn() で FQN を導出 */ });
```

パス変換ルール: `/modules/<module>/<pkg>/<Name>.class` → `<pkg>.<Name>`
- 匿名クラス・内部クラス（`$` を含む FQN）は除外する

## ModalEditor への接続

```java
// Main.java で起動時に1回だけ生成（バックグラウンドスレッドで構築）
private static final JdkClassIndex JDK_INDEX = JdkClassIndex.build();

// 各ペインの ModalEditor にセット
editor.setJdkClassIndex(JDK_INDEX);
```

## キーバインド

`KeymapRegistry.loadDefaults()` に追加:

```java
bind(Mode.NORMAL, KeyBinding.ofChar('K', "jdk.doc"), "jdk.doc");
```

`ModalEditor.processNormalKey()` の switch に追加:

```java
case "jdk.doc" -> lookupJdkDoc();
```

## 候補選択ポリシー

1. 候補が1件 → そのクラスをリフレクションでロードして情報表示
2. 候補が複数 → 優先順: `java.lang.*` > `java.util.*` > その他最初の候補

## ステータスバー表示フォーマット

```
ArrayList - class (java.util) [42 methods, 0 fields]
List - interface (java.util) [10 methods, 0 fields] (+3 more)
```

`JdkTypeInfo.toStatusLine()` で生成。

## テスト

`test/dev/javatexteditor/analysis/JdkClassIndexTest.java` に 18 テスト。

- jrt:/ からクラス数が 1000 件以上取得できる（走査が機能している）
- lookup("List") に "java.util.List" が含まれる
- lookup("String") に "java.lang.String" が含まれる
- 存在しない名前は空リストを返す
- lookup() の結果は変更不可リスト
- loadClass("java.lang.String") が存在する
- ロードされたクラスが String である
- loadClass("java.util.List") が存在する
- java.util.List はインタフェース
- 存在しない FQN で空 Optional を返す
- JdkTypeInfo.from(String.class) の fqn
- java.util.List の kind は interface
- StandardOpenOption の kind は enum
- String のメソッドリストが非空
- Integer のフィールドリストが非空
- toStatusLine() に ArrayList が含まれる
- toStatusLine() に java.util が含まれる
- インデックス構築が5秒以内

## 設計上の注意点

- `build()` はバックグラウンドスレッドで構築するため、未完了時の `lookup()` は空リストを返す
- `buildSync()` はテスト用（同期構築）
- `isReady()` で構築完了を確認可能
- 未完了時に `K` を押すと "JDK index building..." とステータスバーに表示
- `Class.forName()` は封印モジュールや JRE 専用クラスでは失敗することがある → `Optional.empty()` で graceful handling
