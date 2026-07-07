[← 目次](README.md)

# 4. Java開発支援

このエディタは `javax.tools.JavaCompiler`（JDK標準API）を使い、外部ライブラリなしでJavaのコンパイルエラー表示・定義ジャンプ・自動import・リファクタリングなどのIDE的機能を提供します。

## コンパイルエラー表示

`JavacTask.analyze()` による型解決まで含むコンパイルエラー・警告を、リアルタイムでガター（左端）と波下線で表示します。

- **ガター列**（左端2文字分）: エラー行に赤い `E`、警告行に黄色い `W`
- **波下線**: エラー/警告行のテキスト下に波線を描画
- **ステータスバー右端**: `2 errors, 1 warning` 形式で件数を表示
- 診断が空のときはガター幅0（既存レイアウトに影響なし）

バックグラウンドコンパイルは INSERT→NORMAL 復帰時、およびファイル保存時にトリガーされます。UIスレッドをブロックしないよう仮想スレッドで実行され、結果は `SwingUtilities.invokeLater()` で反映されます。コンパイラが使えない環境でも静かに失敗します。

| キー | 動作 |
|---|---|
| `[g` | 次のエラー/警告行へジャンプ（末尾に達すると先頭へ折り返し） |
| `[d` | 前のエラー/警告行へジャンプ（先頭に達すると末尾へ折り返し） |
| `F2` | カーソル行のエラー・警告一覧をダイアログ表示（エディタ全体のグローバルキー。エラーがなければ「この行にエラー・警告はありません。」と表示） |

## 定義ジャンプとドキュメント参照（`K` / `Shift+J`）

NORMALモードの `K` はカーソル位置の識別子について、以下の優先順で解決を試みます。

1. `receiver.member` 形式の場合、`receiver` をプロジェクト内のクラスとして解決し、そのメンバー宣言を探す。プロジェクト内で見つからなければ `receiver` の型（ローカル変数・引数・フィールドの宣言から推定）をJDKクラスとして解決を試みる
2. プロジェクト全体からカーソル位置の識別子と一致するフィールド・定数・メソッド・クラス宣言を検索し、見つかればそのファイル・行へジャンプ
3. JDKソース疑似バッファ内でnativeメソッドの実装（C/C++）を参照中の場合は `lib/openjdk-native/` から該当シンボルを検索
4. JDKソース疑似バッファ内でJavaのFQN表示中の場合、native実装のJNIマングル名トレースを試みる
5. `ClassName.methodName` 形式でカーソルがある場合、nativeメソッドかどうかを検出しJNIマングル名をトレース
6. 上記のいずれにも該当しない場合、JDKクラス名として検索する（下記「JDK API参照」を参照）

ジャンプが成功しファイル/カーソル位置が変わった場合、そのジャンプ元（バッファ内容・ファイルパス・カーソル位置）が1件だけ記録されます。

| キー | 動作 |
|---|---|
| `K` | 上記の解決順で定義・ドキュメントを参照/ジャンプ |
| `Shift+J` | 直前の `K` ジャンプ元へ戻る（1段のみ。ジャンプ履歴がない場合は "No previous jump to go back to"） |

`gr`/`gR`（プロジェクト全体の参照検索）については [検索・ナビゲーション](03-search-and-navigation.md#参照検索gr--grとシンボル定義ジャンプk--shiftj) を参照してください。

## JDK API参照・Javadocビューア

`K` キーで識別子がJDKクラス名として解決された場合、`JdkClassIndex`（起動時にバックグラウンドで `jrt:/` を走査して構築されるJDKクラス索引）から候補を検索し、ステータスバーに1行表示します。

- **Javadocが利用可能な場合**: ローカルJavadoc HTMLから最初の説明文を抽出して表示（例: `"ArrayList: Resizable-array implementation of the List interface."`）
- **Javadocが利用できない場合**: リフレクションによる種別・メソッド数・フィールド数を表示（例: `"ArrayList - class (java.util) [42 methods, 0 fields]"`）
- JDKソースがある（`lib/src.zip`）場合は `*jdk-source:FQN*` 疑似バッファでクラスのソースコードを開く

Javadoc HTMLの検索パス（優先順）:

1. システムプロパティ `jte.javadoc.path` で明示指定したディレクトリ
2. `$JAVA_HOME/docs/api/`
3. `/usr/share/doc/openjdk-<N>-doc/api/`（Debian/Ubuntu系。`openjdk-21-doc` パッケージ）

インデックス構築中は `"JDK index building..."`、存在しない識別子は `"Not found in JDK: <word>"` を表示します。

### OpenJDK native メソッドトレース

`K` キーで `ClassName.methodName` 形式のカーソル位置にあるnativeメソッドを検出すると、JNIマングル名（例: `Java_java_lang_System_arraycopy`）をステータスバーに表示します。`lib/openjdk-native/`（`scripts/setup.sh` で取得）が存在すればC/C++実装ファイルの位置とコードスニペットも表示し、存在しない場合は "no JDK source available" にフォールバックします。

## auto-import（未定義シンボルの自動import挿入）

INSERT→NORMAL復帰時にコンパイルエラーを解析し、未解決の型名に対してJDKクラス索引から候補を検索します。

- 候補が1件の場合、即座に自動挿入
- 候補が複数ある場合、ステータスバーに `[1] java.util.List  [2] java.awt.List  [Esc]=skip` 形式で候補を表示

| キー | 動作 |
|---|---|
| `1`〜`9`（import選択中） | 番号を押してimportを選択・挿入 |
| `Esc`（import選択中） | 現在のimport候補をスキップして次のシンボルへ |

## import整理（organize imports）

| キー/コマンド | 動作 |
|---|---|
| `Ctrl+Shift+O` | 未使用importを一括削除（Eclipse互換。NORMAL/INSERT両モードで有効） |
| `Space+i+o` | 同上 |
| `:oi` / `:organize-imports` | 同上（COMMANDモード） |
| `:remove-import <fqn>` | 指定したFQNのimport行を1件削除（例: `:remove-import java.util.List`） |

## マルチファイルリファクタリング（`:rename`）

`:rename <oldName> <newName>` コマンドで、プロジェクト全体にわたってシンボル名を一括置換します。

```
:rename Foo Bar
```

- 語境界（`\bOldName\b`）マッチにより、`Foo` は `FooBar` や `aFoo` には影響しません
- 結果は `*rename*` 疑似バッファに `path: N replacement(s)` 形式で一覧表示され、ステータスバーに合計置換件数とファイル数を表示
- 型解析は行いません（parse-only）。同名の別シンボルも置換対象になる点に注意してください
- 引数不足時は `E: usage: rename <oldName> <newName>`、一致なしは `rename: no occurrences of '<old>' found` を表示
- 書き込みに失敗したファイル（権限なし等）は該当ファイルのみ `ERROR` 表示し、他のファイルの処理は継続します

## `:main <target>`（java/javac の実際の起動点へジャンプ）

`java`/`javac` コマンドを実行したときに実際に呼ばれる「起動点（launcher entry point）」へジャンプするコマンドです。HotSpot本体（`JVM_GC` 等の実行時関数）ではなく、あくまでlauncherが最初にmainクラスを解決・起動する箇所を指します。

| コマンド | ジャンプ先 |
|---|---|
| `:main java` | `java` launcherのnativeエントリポイント（`src/java.base/share/native/launcher/main.c` の `main()`）。ここから `JLI_Launch()` が呼ばれ、コマンドライン引数から実行するmainクラスが解決される。`java Foo.java` の単一ソース実行も同じnative `main()` を通る |
| `:main javac` | `com.sun.tools.javac.Main.main(String[] args)`（`jdk.compiler` モジュール）。javacには専用のnative/JNI実装はなく、launcherからこのJavaメソッドが直接呼ばれる |

- 対応表は `dev.javatexteditor.analysis.EntryPointIndex` に集約されており、`jar`/`javadoc`/`jshell` 等への対応はエントリ追加のみで拡張可能です
- ターゲット名は大文字小文字を区別しません（`:main JAVA` も可）
- 引数なし・未対応ターゲットはステータスバーにエラーメッセージ（対応ターゲット一覧つき）を表示するのみで、バッファには影響しません
- `java` ターゲットのジャンプには `lib/openjdk-native/`、`javac` ターゲットには `lib/src.zip` が必要です。未取得の場合は "not available" エラーを表示します（他のJDKソース系コマンドと同じgraceful degradation）
- 開いた疑似バッファは `K`・`gr` と同じ挙動: `q` で元のバッファに戻ります

## Getter/Setter自動生成

コンパイル支援というより編集支援の機能ですが、Java開発支援の一部として利用します。詳細は [編集支援機能](06-editing-features.md#gettersetter自動生成) を参照してください。
