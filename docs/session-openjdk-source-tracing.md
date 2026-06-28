# Skill ⑫ openjdk-source-tracing 作業ログ

## 概要

NORMALモードの `K` キーを拡張し、native メソッドにカーソルがある場合に
JNI マングル名と JDK ソース（src.zip）での実装位置を表示する機能を実装した。

## 実装内容

### 新規ファイル

- `src/dev/vimacs/analysis/OpenjdkSourceTracer.java`
  - `trace(Class<?> cls, String methodName)` → `TracingResult` を返す
  - リフレクションで native 修飾子を判定
  - JNI マングル名を計算（`Java_<pkg>_<Class>_<method>` 規則）
  - src.zip が存在すれば C/C++ ファイルを検索してスニペット抽出
  - src.zip がなければ graceful degradation（"no JDK source available"）

- `test/dev/vimacs/analysis/OpenjdkSourceTracingTest.java`
  - 29テスト全PASS

### 変更ファイル

- `src/dev/vimacs/editor/ModalEditor.java`
  - `OpenjdkSourceTracer` フィールドを追加
  - `lookupJdkDoc()` を拡張: `ClassName.methodName` 形式のカーソル位置で
    `classAndMethodAtCursor()` を呼び、native メソッドなら `sourceTracer.trace()` を実行
  - `classAndMethodAtCursor()` ヘルパーメソッドを追加

## 設計判断

### トリガー方式

カーソルが `System.arraycopy` の `arraycopy` 上にある場合、
行の文字列から `.` の前後を解析してクラス名とメソッド名を取得する。
インポート文の解析は不要（jdkIndex.lookup() でシンプル名から FQN を引く）。

### JNI マングル規則

`Java_<package>_<ClassName>_<methodName>` で、`_` は `_1` にエスケープ、
`.` は `_` に変換（JNI 仕様通り）。

### src.zip の探索

1. `java.home` プロパティから `lib/src.zip` を探す
2. 既知のパス（`/usr/lib/jvm/java-21-openjdk-amd64/lib/src.zip` 等）を試す
3. 見つからなければ graceful degradation

### graceful degradation

- src.zip なし → `"[native] Java_... (no JDK source available)"`
- C/C++ ファイル未検出 → Java ソースの native 宣言行をフォールバック表示
- native でないメソッド → 既存の JdkTypeInfo / Javadoc 表示にフォールバック

## テスト環境での動作確認

このリモート環境には src.zip が存在しないため、graceful degradation パスを主に確認した。
`System.arraycopy`、`String.intern` などの known native メソッドが正しく検出されることを確認。

## JNI マングル名の例

| Java メソッド | JNI 名 |
|---|---|
| `java.lang.System.arraycopy` | `Java_java_lang_System_arraycopy` |
| `java.lang.Object.hashCode` | `Java_java_lang_Object_hashCode` |
| `java.lang.String.intern` | `Java_java_lang_String_intern` |
| `java.lang.Math.sqrt` | `Java_java_lang_Math_sqrt` |
