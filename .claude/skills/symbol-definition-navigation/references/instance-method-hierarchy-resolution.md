# インスタンスメソッド呼び出し（`変数名.メソッド名(引数)`）の定義ジャンプ — 型階層（継承）解決

## 背景・調査結果（2026-07）

「インスタンスメソッド呼び出し（`変数名.メソッド名(引数)`）の形式で `K` を押しても定義元へ
ジャンプできない」という要望を受けて調査した。

**まず確認した事実**: `変数名.メソッド名(引数)` の最も基本的なケース（レシーバの宣言型を
`ReceiverTypeResolver` で推定し、そのクラスのファイル内で直接宣言されたメンバーを検索する）は
既に実装済みで、正しく動作していた（本SKILL.mdの「レシーバ型解決」節、`tryResolveQualifiedMember()`
/ `ProjectSymbolResolver.resolveMemberInType()`）。同名メソッドが複数クラスに存在する場合の
誤ジャンプも、レシーバの宣言型による絞り込みで正しく回避できていることを実機相当の再現コードで確認した。

**実際に見つかったバグ**: レシーバの宣言型が指すクラス**自身**にはメソッドが無く、
**親クラス（スーパークラス）で継承しているメソッド**を呼び出しているケースで、誤ジャンプが発生していた。

```java
// Derived.java
public class Derived extends Base {
    void other() {}
    // helper() はここには無い（Base から継承）
}

// Base.java
public class Base {
    void helper() { ... }  // 本来ジャンプすべき場所
}

// Other.java（プロジェクト内の無関係なクラス）
public class Other {
    void helper() { ... }  // 同名だが無関係
}

// Caller.java
Derived d = new Derived();
d.helper();  // K を押すと Other.java にジャンプしてしまっていた（Base.java が正しい）
```

**原因**: `ProjectSymbolResolver.resolveMemberInType(baseDir, ..., "Derived", "helper")` は
`Derived` の宣言ファイル（`Derived.java`）**のみ**を対象に `helper` を探しており、
`Derived.java` 内に `helper` が無ければ即座に `Optional.empty()` を返していた。
呼び出し元の `ModalEditor.tryResolveQualifiedMember()` はこれを「型解決による絞り込みに失敗した」
と判断し、`jdkIndex` 側のJDKメンバー解決（`Derived` はJDK型ではないので失敗）を経て、
最終的に `lookupJdkDocAndJump()` 内のフォールバック（レシーバを無視して `word`＝`"helper"` だけで
プロジェクト全体を検索する `projectSymbolResolver.resolve()`）に落ちていた。このフォールバックは
型を一切見ないため、プロジェクト内に同名メソッドが複数あると最初に見つかった無関係なものへ
ジャンプしてしまう。

## 修正方針

Eclipse の "Open Declaration" は、レシーバの静的型（宣言型）から**型階層を辿って**
実際にメソッドが宣言されている型（自身 → スーパークラス → さらに上位のスーパークラス...）を
探し、最初に見つかった宣言へジャンプする。この階層探索の考え方を踏襲し、
`ProjectSymbolResolver.resolveMemberInType()` を「対象クラス1つだけを見る」実装から
「対象クラスに無ければスーパークラスを辿る」実装に変更した。

### なぜフル型解決（javac）を使わないか

引き続き CLAUDE.md / 本SKILL.mdの既存方針（「javacによるフル型解決は行わない。軽量な
正規表現/AST parse-onlyヒューリスティックで実用上十分な精度を狙う」）を踏襲する。
継承の "extends" 節はソースコード上に直接書かれている情報であり、Compiler Tree API の
parse-only解析（`SourceAnalyzer` が既に使っている `JavacTask.parse()`）でそのまま取得できる
（意味解析・シンボル解決は不要）。そのため既存の設計方針を破らずに実装できる。

### `SymbolEntry` への `superTypeName` フィールド追加

`SourceAnalyzer` は元々 `extends` 節の情報を一切保持していなかった。`SymbolEntry`
（`CLASS`/`INTERFACE`/`ENUM` 種別のエントリ）に `String superTypeName`（nullable）を追加し、
`ClassTree.getExtendsClause()` から単純型名を抽出して格納するようにした。

```java
public record SymbolEntry(
    String name,
    SymbolKind kind,
    int lineNumber,
    int offset,
    String superTypeName   // CLASS種別のみ意味を持つ。extends節が無ければnull
) {}
```

- ジェネリクス（`extends Base<String>`）は `<` より前の部分だけを取る。
- パッケージ修飾（`extends pkg.Base`）は最後の `.` より後ろ（単純名）だけを取る。
- `implements` 節（インタフェース）は対象外。Java のクラス継承は単一継承のため
  `extends` 節1つだけを見れば型階層を機械的に辿れるが、`implements` は複数あり得るため
  「最初に見つかった宣言」という単純な優先順位付けが曖昧になる。インタフェースの
  デフォルトメソッドを継承するケースは今回のスコープ外とした（既知の制限。下記参照）。
- `SymbolEntry` はレコードだが本番コードで直接コンストラクトしているのは
  `SourceAnalyzer` のみ（grep で確認済み）であり、フィールド追加によるコンパイルエラーは
  他に発生しない。

### `ProjectSymbolResolver.resolveMemberInType()` の階層探索化

対象クラスにメンバーが見つからなければ、そのクラスの `superTypeName` を取得し、
**同じプロジェクト内**でその名前のクラスを再度探して同じ処理を繰り返す（ループ）。
訪問済みの型名を `Set` で記録し、無限ループ（循環継承のような壊れたソース）を防ぐ。

スーパークラスがプロジェクト内で見つからない場合（JDKクラスを継承している、
または単に見つからない）はそこで探索を打ち切り `Optional.empty()` を返す。
このとき呼び出し側 (`ModalEditor.tryResolveQualifiedMember()`) は変更していないため、
従来通りレシーバの宣言型（`Derived` などプロジェクト内の型そのもの）でしか
JDK側フォールバックを試みない。**「プロジェクト内クラスがJDKクラスを継承していて、
その継承したメソッドを呼んでいる」ケース（例: `class MyList extends ArrayList<String> {}` の
`myList.add(...)`）は今回のスコープ外**とした。理由: `tryJdkMember()` はJDKのクラス名でしか
`jdkIndex.lookup()` を引けず、プロジェクトの型名（`MyList`）をJDK側の型階層に接続する
仕組みが無い。実装するには「プロジェクトクラスの継承チェーンを辿った末にJDKクラス名に
到達したら、その名前で `tryJdkMember()` を呼ぶ」という追加の橋渡しが必要になるが、
報告された不具合（プロジェクト内クラス同士の継承）を直すことがまず優先度が高いと判断し、
一度に手を広げすぎないためスコープを絞った。次にこの領域を触る開発者は、
`resolveMemberInType()` が最終的に解決できなかった型名（ループを抜けた時点の `currentType`）を
呼び出し側に返す形に拡張し、`ModalEditor` 側でその型名を `tryJdkMember()` に渡すよう
接続すれば実現できる。

## 既知の制限（スコープ外として残したもの）

- **`implements`（インタフェースのデフォルトメソッド）は辿らない**。上記の通り複数実装
  可能なため優先順位が曖昧になる。
- **プロジェクト内クラスがJDKクラスを継承しているケースの、継承したJDKメソッドへのジャンプ**
  は非対応（上記参照）。
- **オーバーライドの区別はしない**。`Derived` がスーパークラスのメソッドを実際にはオーバーライド
  しているが、たまたま `Derived.java` の解析対象範囲（`SourceAnalyzer` はトップレベルの
  型宣言直下のメンバーのみ収集。ネストしたクラスは含まない、という既存の制約）に
  含まれない場合、意図せずスーパークラス側へ辿ってしまう可能性は理論上あるが、
  `Derived.java` 直下のメンバーは `findSymbol()` で最初に探すため、通常の（ネストしていない）
  オーバーライドは正しく `Derived.java` 側で先に見つかる。
- **循環継承や自己参照的な `extends`**（本来コンパイルエラーになる壊れたソース）は
  訪問済みSetで探索を打ち切るのみで、エラー表示はしない（`Optional.empty()` を返し
  通常の「見つからない」と同じ扱いになる）。

## テスト

`test/dev/javatexteditor/analysis/ProjectSymbolResolverTest.java` に追加:

- `test_resolveMemberInType_walksSuperclassChain()`: `Derived extends Base` で `Base` にのみ
  宣言されたメソッドを、`Derived` 型で問い合わせても正しく見つけられる（無関係な同名メソッドを
  持つ `Other` クラスへ誤ジャンプしないこと）。
- `test_resolveMemberInType_stopsAtUnknownSuperclass()`: スーパークラスがプロジェクト内に
  見つからない場合（JDKクラスを継承 or 単に無い）は `Optional.empty()` を返す。
- `test_resolveMemberInType_multiLevelInheritance()`: 2段階の継承（`C extends B`,
  `B extends A`, `A` にメソッド宣言）でも正しく `A` まで辿り着く。

`test/dev/javatexteditor/editor/JumpBackTest.java` へは追加せず、上記
`ProjectSymbolResolverTest` のみで検証した（`K` キー経由の統合部分 `tryResolveQualifiedMember()`
自体は変更していないため、既存の `resolveMemberInType()` の単体テストで十分と判断）。
