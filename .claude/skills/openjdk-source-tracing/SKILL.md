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

## バグ修正: Windows で setup.bat を実行すると文字化け＋終了コード255になる

Windows のコマンドプロンプト（`cmd.exe`）で `scripts/setup.bat` を実行すると、
コメント文字列が文字化けした上で `Exited with code 255` になり実行が失敗する不具合があった。

**原因**: `setup.bat` に日本語の `rem` コメントを UTF-8 のまま埋め込んでいたが、
`cmd.exe` はバッチファイルを**アクティブなコンソールコードページ**（日本語Windowsの既定は
CP932/Shift-JIS）で解釈する。UTF-8 の日本語コメントを CP932 として誤読すると、
本来1つの日本語文字を構成するはずのマルチバイト列が、たまたま `)` `(` `%` `&` `|` などの
バッチ制御文字に該当するバイトへ分解されてしまうことがあり、パーサが構文エラーで
異常終了する（終了コード255は `cmd.exe` がバッチのパースに失敗した際の典型的な戻り値）。

**修正**: `scripts/setup.bat` と `scripts/test.bat` に含まれていた日本語コメントを
すべて英語（ASCII）に置き換えた。`setup.bat` の冒頭にも「このファイルはASCII専用で
保つこと」という注意書きを英語コメントとして残した。

**注意点（今後この種のファイルを触る際に厳守すること）**:
- **`.bat`/`.cmd` ファイルにはASCII文字（英語コメント）のみを使う**。日本語等の説明は
  代わりに対応する `.claude/skills/*/SKILL.md` に書く。
- `.sh` ファイル（bash）は対象外。Linux/macOSのターミナルはUTF-8前提で動作するため
  日本語コメントで問題ない（本プロジェクトの `scripts/*.sh` は引き続き日本語コメント可）。
- `chcp 65001`（UTF-8コードページへの切り替え）で回避する方法もあるが、バッチファイル自体には
  古くから存在する `echo`/`for` 絡みの副作用が知られており、確実性に欠けるため採用しない。
  ASCII化のほうがシンプルで確実。

## バグ修正（続報）: setup.bat をASCII化しても文字化け＋255が再発した

上記の `.bat` ASCII化だけでは不十分だった。実際には `scripts/setup.bat` 自体は正しく
実行されており、`git`・`xcopy` などの**Windowsコマンド自身が出す（日本語ロケールの
Windowsではローカライズされた）出力**を、`Main.java` 側がUTF-8決め打ちで読み取っていたために
文字化けしていた。つまり文字化けの発生源は同じ「エンコーディング不一致」でも、
バッチファイルの静的テキストではなく**サブプロセスの標準出力を読み取るJava側**にあった。

**原因**: `Main.java` の `runSetupIfNeeded()` は次のようにサブプロセス出力を読んでいた:

```java
new java.io.InputStreamReader(proc.getInputStream())  // 文字セット省略 = JVMのデフォルト文字セット
```

JDK 18以降は JEP 400 により `Charset.defaultCharset()` がプラットフォームに関わらず常に
UTF-8になる。しかし `cmd.exe` 経由で起動した子プロセス（`git`・`xcopy` 等）が実際に出力する
バイト列は**OSのネイティブエンコーディング**（Windowsではコンソールのアクティブコードページ、
日本語版なら通常 CP932）である。この不一致により、`git`/`xcopy` が出す日本語のメッセージ
（コピー件数やエラー内容など、こちらのスクリプトが直接書いた文字列ではなく、Windows/git自体が
出す文言）がUTF-8として誤ってデコードされ、`?` の連続として文字化けする。

**修正**: `native.encoding`（JEP 400 で追加されたシステムプロパティ。無ければ
`sun.jnu.encoding`）で明示的に `Charset` を解決し、`InputStreamReader` に渡すよう変更した:

```java
String nativeEncodingName = System.getProperty("native.encoding",
    System.getProperty("sun.jnu.encoding", "UTF-8"));
Charset nativeEncoding = Charset.forName(nativeEncodingName); // 失敗時は defaultCharset() にフォールバック
new java.io.InputStreamReader(proc.getInputStream(), nativeEncoding)
```

併せて、HotSpotのディレクトリ構造は非常に深くネストしており、Windowsの
MAX_PATH（260文字）制限に抵触しやすい。特にユーザーの作業ディレクトリパス自体が長い場合
（例: `C:\Users\<name>\workspace\...\JavaTextEditor\...`）、`git checkout` や `xcopy` が
長いパスで失敗し、それがまた別の（今回はエンコーディング修正後なら正しく読めるはずの）
エラーとして `Exited with code 255` に繋がる可能性があるため、クローン直後に
`git config core.longpaths true` を設定するようにした（`setup.bat` のみ。Linux/macOSの
git・ファイルシステムにはこの制限がないため `setup.sh`には追加していない）。

**教訓**: サブプロセスの出力を読む際は、JVMのデフォルト文字セット（`file.encoding`）と
「そのプロセスが実際に出力に使っているエンコーディング」は別物であり、特にWindowsでは
一致しない。子プロセスの標準出力/エラー出力を読み取るコードは常に
`native.encoding`（または `sun.jnu.encoding`）を明示的に使うこと。

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
