# Java Text Editor

Vimのモーダル編集とEmacsの拡張性の良い所を統合した、Java SE製の軽量テキストエディタ。学習目的と実用目的を両立させる。

## 特徴

- **モーダル編集**: Vim式のNORMAL/INSERT/COMMAND/VISUAL/VISUAL LINE/VISUAL BLOCKモード
- **Emacs式カーソル移動**: INSERTモード中でも `Ctrl+F/B/N/P` などで移動可能
- **Vim式置換コマンド**: `:s`/`:%s`/`:'<,'>s`/`:N,Ms` による正規表現置換（`g`/`i`フラグ、`\1`/`&`置換対応）
- **高速バッファ**: ピーステーブル方式により大規模ファイル（数十万行）でも高速に挿入・削除
- **Java開発支援**: コンパイルエラー表示、定義ジャンプ、Javadoc参照、auto-import、プロジェクト全体のリネーム、`java`/`javac` launcher・HotSpotソースへのジャンプ
- **検索・ナビゲーション**: バッファ内検索、ファイル名/grep検索、telescope風ファジーファインダー、ディレクトリブラウザ（FILER）
- **コード補完**: 作業ディレクトリ全体の識別子とJDKクラス名を対象にしたリアルタイム補完
- **プラグインシステム**: `javax.tools.JavaCompiler` による動的コンパイルでJavaファイルをその場でロード
- **対話型チュートリアル**: `:tutor` コマンドでvimtutor形式の操作練習
- **Java SE標準APIのみ**: 外部ライブラリ不使用。Java 21で動作

すべての機能の詳細な使い方は **[docs/manual/](docs/manual/README.md)** を参照してください。

## 必要環境

- Java 21 JDK（`javac` が使えること）

## セットアップ・ビルド・実行

```bash
# 初回のみ: OpenJDK 21 のソース(src.zip)を lib/ に自動取得
./scripts/setup.sh      # Windows: scripts\setup.bat

# ビルド
./scripts/build.sh

# 実行（ファイル指定は任意）
./scripts/run.sh [/path/to/file.txt]

# テスト
./scripts/test.sh

# 実行可能JARの生成
./scripts/package.sh
```

Windowsの場合は `build.bat` / `run.bat` / `test.bat` を使用してください。セットアップの詳細（`src.zip` の自動検出順序など）は [docs/manual/01-getting-started.md](docs/manual/01-getting-started.md) を参照してください。

## ドキュメント

| ドキュメント | 内容 |
|---|---|
| [docs/manual/](docs/manual/README.md) | 全機能の利用者向けマニュアル（セットアップ・モーダル編集・検索・Java開発支援・補完・プラグイン・内部アーキテクチャなど） |
| [docs/requirements.md](docs/requirements.md) | 初期要件定義 |
| [docs/implementation-history.md](docs/implementation-history.md) | 実装の変遷・設計判断の記録 |
| [docs/REFACTORING_PLAN.md](docs/REFACTORING_PLAN.md) | リファクタリング計画・既知の未接続コードの記録 |
| [CLAUDE.md](CLAUDE.md) | 開発方針・技術制約・設計決定事項（開発者向け） |

## 技術制約

- **言語**: Java 21 (LTS)
- **依存ライブラリ**: なし（Java SE標準APIのみ）
- **ビルドツール**: なし（`javac` 直接呼び出し）
- **テストフレームワーク**: なし（`main` メソッド形式の自作ハーネス）
