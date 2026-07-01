# Skill ⑫: openjdk-source-tracing

## 概要

JNIグルーコード（`src/*/share/native` 等、⑨⑩で先行実装済み）だけでなく、
**HotSpot JVM本体**（`src/hotspot/**`）のソースまでトレース対象に含める Skill。

CLAUDE.md のロードマップでは「未着手」とされていたが、
㉓ `symbol-definition-navigation` の `gr`（参照検索）実装後に発覚した以下の問題を
解決するため、Phase 1（HotSpot共通ソースの取得と検索対象化）を先行実装した。

## 発端となった問題

`Java_java_lang_Runtime_gc` のJNI実装（`gr`で辿り着ける）は次のようになっている:

```c
JNIEXPORT jlong JNICALL Java_java_lang_Runtime_gc(JNIEnv *env, jobject this)
{
    JVM_GC();
}
```

ここで `JVM_GC()` にカーソルを合わせて `gr`（参照検索）を押しても、呼び出し元へジャンプできなかった。
調査の結果、これはバグではなく **`JVM_GC()` の定義自体が `lib/openjdk-native/` に存在しない**
ことが原因だった。

- `scripts/setup.sh` が取得していたのは `src/*/share/native` 等 — これは
  `java.base` 等の**JNIグルーコード**（Javaのnativeメソッド宣言に対応するCラッパー）のみ。
- `JVM_GC()` は HotSpot本体の `src/hotspot/share/prims/jvm.cpp` に実装されている。
  HotSpotは `src/hotspot/share|os|cpu` という独自のディレクトリ構成を取り、
  「native」という名前のサブディレクトリは存在しない。そのため既存の取得パターンでは
  一切拾われていなかった。

## 対応方針（Phase 1: share のみ）

- `src/hotspot/os/*`（OS依存）・`src/hotspot/cpu/*`（CPUアーキテクチャ依存）は
  サイズが大きく、かつ対応プラットフォームの絞り込みが必要になるため **対象外**。
  まずは共通実装である `src/hotspot/share` のみを取得する。
- 取得先は既存の `lib/openjdk-native/` を再利用し、その下に `hotspot/share/...` として配置する。
  こうすることで `OpenjdkSourceTracer`/`ProjectSearcher` は**追加のコード変更なしに**
  同じ `nativeSrcDir` 配下を再帰的に検索するだけで HotSpot ソースも対象になる
  （`gr` の実装は「baseDirを再帰grepする」だけなので拡張子・ディレクトリ構造を問わない）。

## scripts/setup.sh / setup.bat の変更

```bash
git -C "$WORK_DIR" sparse-checkout set \
    "src/*/share/classes" "src/*/unix/classes" "src/*/windows/classes" \
    "src/*/share/native" "src/*/unix/native" "src/*/windows/native" \
    "src/hotspot/share"    # 追加
```

`src/hotspot` は「native」という名前のサブディレクトリを持たないため、既存の
`find "$WORK_DIR/src" -type d -name "native"` ループでは拾えない。そのため
Phase 3 として `cp -r "$WORK_DIR/src/hotspot/share" "$NATIVE_DIR/hotspot/"` を
別途追加した。

### べき等性チェックの拡張

既存の「既に存在するなら何もしない」early-exit判定（`$NATIVE_DIR` に `.c` ファイルがあるか）は、
hotspot追加**前**に一度セットアップ済みの環境では常に真になってしまい、
スクリプトを再実行しても hotspot ソースが追加されない。これを避けるため、
`$NATIVE_DIR/hotspot` に `.cpp` ファイルが存在するかも判定条件に加えた
（`sh`/`bat` 両方）。既存環境で hotspot だけを追加したい場合は、
このチェックにより自動的に再クローン・追加が走る。

## OpenjdkSourceTracer の拡張

HotSpotは `.cpp`/`.hpp` という拡張子を使う（`.h` ではなく `.hpp`）。
`findCSymbol()`（Shift+K でのC/C++シンボル定義ジャンプに使用）の対象拡張子に
`.hpp` を追加した:

```java
return n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".h") || n.endsWith(".hpp");
```

`gr`（`ProjectSearcher` ベース）は拡張子を問わず全文検索するため、この対応は不要
（既に `.hpp`/`.cpp` どちらも検索対象に含まれていた）。

`trace()`/`searchInNativeSrcDir()`（JNIマングル名からJNI実装ファイルを探す処理）は
`.c`/`.cpp` のみのままにした。JNIグルーコードの実装は常に `.c`/`.cpp` にあり、
ヘッダに実体が書かれることはないため、拡張の必要がない。

## テスト

`test/dev/javatexteditor/analysis/OpenjdkSourceTracingTest.java`

- `testFindCSymbolMatchesHotspotStyleCppDefinition()`: `.hpp`/`.cpp` に分割された
  HotSpot形式のソース（`JVM_GC` の宣言が `.hpp`、定義が `.cpp`）でも `findCSymbol` が
  正しく定義を発見できることを確認する。

**既知のテストギャップ**: `scripts/setup.sh`/`setup.bat` 自体（実際に openjdk/jdk を
git clone してファイルを配置する部分）は自動テストされていない
（ネットワークアクセスを伴う一度きりのセットアップ処理のため。既存の
`src.zip`/`openjdk-native` の取得ロジックも同様に未テストであり、この制約を引き継いでいる）。

## 依存関係

- ⑩ `jdk-api-navigation`（`OpenjdkSourceTracer` の既存実装を拡張。新しいクラスは追加していない）
- ㉓ `symbol-definition-navigation`（`gr` のnative参照検索がこのSkillの主な利用箇所。
  `gr` 自体のコードは無変更で、検索対象ディレクトリが広がっただけ）

## 今後のPhase（未着手）

- `src/hotspot/os/<os>/`・`src/hotspot/cpu/<arch>/` の対応プラットフォーム分の取得
- JVMの内部シンボル（`Universe::heap()` 等のC++クラスメソッド）に対する、
  単純な関数名マッチではなく名前空間・クラスを考慮した解決
