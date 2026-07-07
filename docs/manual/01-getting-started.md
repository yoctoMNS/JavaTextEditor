[← 目次](README.md)

# 1. セットアップ

## 必要環境

- Java 21 JDK（`javac` が使えること）
- 依存ライブラリ・ビルドツールは一切不要（Java SE標準APIのみ、`javac` を直接呼び出す）

## 初回セットアップ

クローン後、最初に一度だけセットアップスクリプトを実行してください。OpenJDK 21 のソース（`src.zip` と HotSpot ネイティブソース）を `lib/` に配置します。これは [Java開発支援](04-java-tooling.md) の `K`（定義ジャンプ）・`gr`（参照検索）・`:main` コマンドが JDK / HotSpot 内部のソースコードを表示するために必要です。

```bash
# Linux / macOS / WSL
./scripts/setup.sh

# Windows
scripts\setup.bat
```

スクリプトは以下の順で `src.zip` を探します。

| 優先順 | Linux/macOS | Windows |
|---|---|---|
| 1 | `$JAVA_HOME/lib/src.zip` | `%JAVA_HOME%\lib\src.zip` |
| 2 | `java` コマンドのパスから JDK ルートを推定 | `java.exe` のパスから JDK ルートを推定 |
| 3 | `apt install openjdk-21-source`（Ubuntu/Debian） | `%ProgramFiles%\Java`, Eclipse Adoptium, Microsoft, Azul Zulu, BellSoft Liberica を探索 |
| 4 | `dnf install java-21-openjdk-src`（Fedora/RHEL） | `winget install EclipseAdoptium.Temurin.21.JDK` |

自動検出に失敗した場合は、`lib/src.zip` に手動で配置してください。`src.zip` がなくてもエディタ自体は起動しますが、`K` キーによる Java ソース表示・native メソッドのソース表示が無効になります（graceful degradation）。

> エディタ起動時にも `lib/src.zip` または `lib/openjdk-native/` が存在しない場合、バックグラウンドの仮想スレッドで自動的にセットアップスクリプトが実行されます（`Main.runSetupIfNeeded()`）。UIの起動はブロックされません。

## ビルド・実行・テスト

```bash
# ビルド（src/配下の全.javaファイルをbuild/にコンパイル）
./scripts/build.sh

# 実行（デモテキストで起動）
./scripts/run.sh

# ファイルを指定して起動
./scripts/run.sh /path/to/file.txt

# テスト（build.shの後、src/+test/をコンパイルし、*Testクラスのmainメソッドを実行）
./scripts/test.sh
```

Windowsの場合は `build.bat` / `run.bat` / `test.bat` を使用してください。

## パッケージング（実行可能JAR）

```bash
./scripts/package.sh
# 既にビルド済みの場合はビルドをスキップできる
./scripts/package.sh --skip-build
```

`dist/javatexteditor.jar` に `Main-Class: dev.javatexteditor.Main` を指定したマニフェスト付きJARを生成します。

## 起動後の初期作業ディレクトリ

作業ディレクトリ（`:grep`・`:rename`・ファイル検索・telescope・FILERモードの基準ディレクトリ）は、起動時に以下の優先順で自動決定されます（セッションをまたいだ永続化はしません）。

1. 起動時に指定したファイルの親ディレクトリ
2. ユーザーのホームディレクトリ
3. JVM起動ディレクトリ（`user.dir`）

詳細は [検索・ナビゲーション](03-search-and-navigation.md#作業ディレクトリと-cd) を参照してください。
