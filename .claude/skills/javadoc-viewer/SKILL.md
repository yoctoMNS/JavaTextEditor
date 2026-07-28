---
name: javadoc-viewer
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、ローカルにインストールされたJDK付属Javadoc(HTML)からクラスのサマリ文を読み取りKキーの結果表示に反映する機能を設計・実装する際に使用する。「Javadocのコメントをエディタ内に表示したい」「Kキーでクラスの説明文も見たい」「Javadocが見つからない環境でも壊れないようにしたい」「JdkJavadocReaderのHTML解析ロジックを直したい」といった相談、またJdkJavadocReader/JdkTypeInfoやModalEditorのbuildDocLine周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# ローカル Javadoc(HTML) の読み取り表示

## このスキルが解決すること

JDK に同梱・別途インストールされた Javadoc（HTML 形式の API ドキュメント）をディスクから直接読み、
クラスのサマリ文（最初の1文）を抽出してステータスバーに表示する。Javadoc 生成物を持たない
環境（多くの CI・コンテナ）でも例外を投げずに動作すること（graceful degradation）が最重要要件。

このスキル自体は「HTML から説明文を取り出す」という一点に特化しており、`K` キーが
どの順序でどの解決手段を試すか（プロジェクト内シンボル解決・JDK ソースへのジャンプ等）は
`symbol-definition-navigation` スキルが定義する。本スキルはその中の「最終フォールバック段」
（ソースが読めた場合はそちらを優先し、読めない場合にステータスバー1行へ収める表示ロジック）
を担当する部品として読むこと。

---

## 実装アーキテクチャ

### `JdkJavadocReader`（`src/dev/javatexteditor/analysis/JdkJavadocReader.java`）

コンストラクタで一度だけ `findApiRoot()` を実行し、以降は結果（`apiRoot`、見つからなければ
`null`）を保持し続ける。**インスタンス生成のたびにディスクを再探索しない**。

```java
public JdkJavadocReader() {
    this.apiRoot = findApiRoot();
}

public boolean isAvailable() { return apiRoot != null; }

public Optional<String> readSummary(String fqn) {
    if (apiRoot == null) return Optional.empty();
    return cache.computeIfAbsent(fqn, this::loadSummary);
}
```

`readSummary()` は `Map<String, Optional<String>> cache` で FQN 単位にメモ化する。1つの編集
セッション中に同じクラスへ何度も `K` を押しても、2回目以降はディスク I/O を伴わない。

### Javadoc の探索順（`findApiRoot()`）

以下を順に試し、最初に見つかった実在ディレクトリを採用する。すべて失敗すれば `null`
（＝`isAvailable() == false`）。

1. システムプロパティ `jte.javadoc.path`（明示指定・テスト用の差し込み口も兼ねる）
2. `$JAVA_HOME/docs/api/`、次いで `$JAVA_HOME/../docs/api/`（旧レイアウト）
3. Debian/Ubuntu 系の `openjdk-<N>-doc` パッケージ（`/usr/share/doc/openjdk-<Runtime.version().feature()>-doc/api/`）

いずれも `Files.isDirectory()` で実在確認してから採用する。存在しないパスをそのまま `apiRoot`
に入れて後段で毎回失敗させる、という設計にはしていない。

### FQN → HTML パス → サマリ抽出

```
"java.util.ArrayList" → apiRoot.resolve("java/util/ArrayList.html")
```

ファイルが存在すれば UTF-8 で読み、`extractFirstSentence(html)` で本文を取り出す。

```java
private static final Pattern BLOCK_PAT =
    Pattern.compile("<div[^>]*class=\"block\"[^>]*>(.*?)</div>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
```

`<div class="block">...</div>` は JDK 17+ の Javadoc HTML が生成するクラス概要ブロック。
この正規表現で本文を切り出し、`<[^>]+>` で HTML タグを除去、`decodeEntities()`（`&lt;`/`&gt;`/
`&amp;`/`&quot;`/`&apos;`/`&#39;`/`&nbsp;` の6種のみ対応）でエンティティを平文へ戻す。

最初の文だけを取り出す `indexOfSentenceEnd()` は「`.`/`!`/`?` の直後が空白または改行」を文末
とみなす単純な規則（Javadoc の最初の文＝サマリという Javadoc の慣習に合わせている）。見つからな
ければ末尾の文末記号を、それも無ければ全文を返す。

### `buildDocLine()`（`ModalEditor`）— Javadoc 優先・JdkTypeInfo フォールバック

```java
private String buildDocLine(String fqn, Class<?> cls, String suffix) {
    Optional<String> summary = javadocReader.readSummary(fqn);
    if (summary.isPresent()) {
        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        return simpleName + ": " + summary.get() + suffix;
    }
    return JdkTypeInfo.from(cls).toStatusLine() + suffix;
}
```

`javadocReader` は `ModalEditor` に `private final` で1インスタンスだけ保持される
（`private final JdkJavadocReader javadocReader = new JdkJavadocReader();`）。呼び出しは
`lookupJdkDocAndJump()` の最終段（`src.zip` からソースを疑似バッファで開けなかった場合のみ）
から `setStatusMessage(buildDocLine(best, cls.get(), extra));` の1箇所に限られる。`src.zip`
が見つかりソースを疑似バッファで開けた場合は、そちらが優先されこのメソッドは呼ばれない
（Javadoc のサマリ表示は「実ソースを見せられないときの次善策」という位置づけ）。

`suffix`（`extra`）には `"(+N more)"` のような曖昧候補件数の付記がそのまま連結される。

### JdkTypeInfo によるフォールバック（`jdk-api-navigation` スキルの守備範囲）

`JdkJavadocReader.readSummary()` が `Optional.empty()` を返した場合（Javadoc 自体が未インストール、
または対象クラスの HTML が存在しない場合）に使われる `JdkTypeInfo`（リフレクションでクラス種別・
メソッド数・フィールド数を集計する record）自体の実装詳細は `jdk-api-navigation` スキルを参照。
本スキルは「Javadoc が使えるときはそちらを優先する」という優先順位の側だけを扱う。

---

## graceful degradation の設計

- `JdkJavadocReader` のコンストラクタ・`readSummary()` はいずれも Javadoc 不在時に**例外を投げず**、
  `null`/`Optional.empty()` を返すだけに留める。呼び出し側（`ModalEditor.buildDocLine()`）は
  `summary.isPresent()` の分岐だけで安全にフォールバックできる。
- HTML の読み込み失敗（`IOException`）・対象ファイル不在（`Files.exists()` で事前チェック）は
  いずれも `loadSummary()` 内で握りつぶし `Optional.empty()` を返す。呼び出し元にスタックトレースが
  伝播することはない。
- `⑫ openjdk-source-tracing`・`⑳ telescope-picker` と同種の「本番資産（Javadoc/src.zip 等）が
  無い環境でもクラッシュしない」という本プロジェクト共通の設計方針をこの機能にも適用している。

---

## テスト方針

`test/dev/javatexteditor/analysis/JdkJavadocReaderTest.java`（15/15、ロードマップ⑪参照）。

- **Javadoc 不要な単体テスト**: 一時ディレクトリに偽の HTML（`<div class="block">...</div>` を
  含む簡易ファイル）を書き `jte.javadoc.path` システムプロパティで差し込み、`extractFirstSentence()`
  相当の抽出結果を検証する（複数 `<div class="block">` が並ぶ場合に最初の1つだけを使うこと・
  HTML タグ除去・エンティティデコード・空白正規化・存在しない FQN への `readSummary()` が例外を
  投げないこと・キャッシュが効くこと、など）。
- **Javadoc が実際に利用可能な環境でのみ追加実行するライブテスト**: `reader.isAvailable()` が
  `true` の場合のみ `java.util.ArrayList`/`java.lang.String` の実サマリを検証する。`false` の
  場合は `"[INFO] Javadoc not installed — skipping live tests (graceful degradation OK)"` を
  出力してスキップする（CI/コンテナ環境で Javadoc が入っていないことを failure として扱わない）。

---

## 関連Skill

- **`jdk-api-navigation`**: `JdkClassIndex`（jrt:/ 走査によるクラス名→FQN索引）・`JdkTypeInfo`
  （リフレクションによるフォールバック情報）の基盤実装を担当。本スキルはその上に「Javadoc が
  あればそちらを優先する」という追加の情報源を積むだけで、索引の構築方法自体はそちらを参照。
- **`symbol-definition-navigation`**: `K` キー全体の解決順序（プロジェクト内シンボル→JDT流
  バインディング解決→JDK クラス→src.zip ソース→本スキルの Javadoc/JdkTypeInfo フォールバック）
  を統括する現行仕様の正。`K` キーの挙動を変える場合はまずこちらを確認すること。
- **`openjdk-source-tracing`**: `src.zip` が見つかった場合にソース本文を疑似バッファで開く経路
  （本スキルのフォールバックより優先される）を担当。
