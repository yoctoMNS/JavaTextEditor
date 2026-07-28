# Java Text Editor 完全マニュアル

Vimのモーダル編集とEmacsの拡張性を統合した、Java SE製の軽量テキストエディタの全機能リファレンスです。プロジェクト概要・技術的な制約は [リポジトリルートの README.md](../../README.md) を参照してください。

## 目次

| # | ドキュメント | 内容 |
|---|---|---|
| 1 | [セットアップ](01-getting-started.md) | 必要環境・セットアップスクリプト・ビルド/実行/テスト/パッケージング |
| 2 | [モーダル編集](02-modal-editing.md) | NORMAL/INSERT/COMMAND/VISUAL/VISUAL LINE/VISUAL BLOCK 各モードの操作 |
| 3 | [検索・ナビゲーション](03-search-and-navigation.md) | バッファ内検索、ファイル名/grep検索、telescope、FILER、作業ディレクトリ |
| 4 | [Java開発支援](04-java-tooling.md) | コンパイルエラー表示、定義ジャンプ、Javadoc、auto-import、リネーム、`:main`、OpenJDKソーストレース |
| 5 | [コード補完](05-completion.md) | `Ctrl+Space` / `Alt+/` によるコード補完 |
| 6 | [編集支援機能](06-editing-features.md) | Getter/Setter生成、インデント、括弧対応、ペイン分割、フォントサイズ調整など |
| 7 | [プラグインシステム](07-plugins.md) | `EditorPlugin` / `EditorContext` によるJava拡張 |
| 8 | [チュートリアル](08-tutorial.md) | `:tutor` コマンド |
| 9 | [内部アーキテクチャ](09-architecture.md) | バッファ・モーダルエンジン・GUI描画などの内部設計（開発者向け） |
| 10 | [キーバインド早見表](10-keybindings-reference.md) | 全モードのキーバインドを1ページに集約 |
| 11 | [C言語開発支援](11-c-tooling.md) | `gcc`連携によるC言語のコンパイル・実行(`F10`/`F11`/`F12`)、診断表示、`#include`自動挿入 |
| 12 | [Markdownビューア](12-markdown-viewer.md) | `:view`/`:mark`によるMarkdownファイルの読み取り専用閲覧ビュー切り替え |

## 対象読者

- **1〜8, 12**: エディタの利用者向け（機能の使い方）
- **9**: コードを読み書きする開発者向け（内部設計の理解）
- **10**: 素早くキーを引きたい全ユーザー向け

## 開発プロセス関連ドキュメント

このマニュアルとは別に、開発の背景・経緯を記録したドキュメントが `docs/` 直下にあります。

| ファイル | 内容 |
|---|---|
| [`../requirements.md`](../requirements.md) | 初期要件定義 |
| [`../implementation-history.md`](../implementation-history.md) | 実装の変遷・設計判断の記録 |
| [`../decision-log.md`](../decision-log.md) | 機能追加・不具合修正ごとの詳細な設計判断ログ（`CLAUDE.md`から分離） |
| [`../archive/`](../archive/) | 完了済みのリファクタリング実行計画書・提案書（既知の未接続コードの記録を含む） |

機能追加・設計変更を行う際は、まず `.claude/skills/` 配下の関連SKILL.mdと `CLAUDE.md` を確認してください（このマニュアルは「今何ができるか」の利用者向け説明であり、設計判断の理由は `CLAUDE.md`/SKILL.md が正です）。
