# CLAUDE.md

## プロジェクト概要

Vim（モーダル編集）とEmacs（拡張性）の良い所を統合した、Java SE製の軽量テキストエディタ。学習目的と実用目的を両立させる。

## 技術スタック・制約（厳守）

- **言語**: Java 21 (LTS)。`record`、パターンマッチング、テキストブロックなど Java 21 時点の標準機能は積極的に使ってよい。
- **依存ライブラリ**: 一切使用しない。本番コードはJava SE標準APIのみで実装する。
- **ビルドツール**: 使用しない。`javac`を直接呼び出す。Maven/Gradleの`pom.xml`・`build.gradle`は作成しない。
- **テスト**: JUnit等のテストフレームワークは使用しない。`main`メソッドを持つ自作テストハーネスで検証する（理由: テストの仕組み自体もJava SE標準機能だけで理解できることを学習目的の一部としているため）。
- **想定ファイル規模**: 数百行〜数十万行のファイルを編集対象とする。実装時は常にこの規模を意識すること（例: `String`の`+`連結や文書全体の再構築をループ内で行わない）。

これらの制約に反する提案（外部ライブラリの追加、ビルドツールの導入など）はしないこと。

## コマンド

```bash
# ビルド（src/配下の全.javaファイルをbuild/にコンパイル）
./scripts/build.sh

# テスト（build.shの後、src/+test/をコンパイルし、*Testクラスのmainメソッドを実行）
./scripts/test.sh

# 実行（Mainクラスを起動）
./scripts/run.sh
```

## ディレクトリ構成

```
project-root/
├── CLAUDE.md
├── docs/
│   └── requirements.md
├── .claude/
│   └── skills/                          ← 設計知識はここに集約する（下記ロードマップ参照）
│       ├── editor-buffer-architecture/
│       └── gui-rendering-pipeline/
├── src/
│   └── dev/vimacs/
│       ├── Main.java
│       ├── buffer/
│       │   ├── Piece.java
│       │   └── PieceTable.java
│       └── ui/
│           ├── Theme.java
│           └── EditorCanvas.java
├── test/
│   └── dev/vimacs/
│       ├── buffer/
│       │   └── PieceTableTest.java
│       └── ui/
│           ├── EditorCanvasTest.java
│           └── VisualPreview.java
├── scripts/
│   ├── build.sh
│   ├── test.sh
│   └── run.sh
└── build/                                ← コンパイル出力先（.gitignore対象）
```

パッケージ名: `dev.vimacs`（確定済み）

## 決定済みの設計事項

| 項目 | 決定内容 | 詳細・実装コード |
|---|---|---|
| バッファ構造 | ピーステーブル方式 | `.claude/skills/editor-buffer-architecture/SKILL.md` |
| アンドゥ/リドゥ | ピースリストのスナップショット方式 | `.claude/skills/editor-buffer-architecture/references/piece-table-delete-and-undo.md` |
| 拡張言語 | Lispインタプリタの自作ではなく、`javax.tools.JavaCompiler`（JDK標準API）による動的コンパイルでJavaそのものを拡張言語として使う | 未作成（`extension-language-runtime`スキルで設計予定） |
| GUI描画v1 | Swing/AWT・単一バッファ静的表示。全角文字幅対応、NORMAL=ブロック/INSERT=縦棒カーソル | `.claude/skills/gui-rendering-pipeline/SKILL.md` |
| GUI描画v1 〜 v3（実装済み） | v2=縦スクロール・v3=横スクロール＋JSplitPaneウィンドウ分割 | `.claude/skills/gui-rendering-pipeline/references/future-phases.md` |

## ロードマップ（Skill一覧）

| # | Skill名 | 担当領域 | 状態 |
|---|---|---|---|
| ① | `editor-buffer-architecture` | バッファ・データ構造 | ✅ 完了（15/15テスト・getTextInRange/offsetOfLine追加済み） |
| ② | `modal-editing-engine` | Vimモーダル編集（Insert中のEmacs式カーソル移動含む） | ✅ v5 完了（151/151テスト・NORMAL/INSERT/COMMAND/VISUAL/VISUAL LINE） |
| ③ | `extension-language-runtime` | Java動的コンパイルによる拡張機構 | ✅ v1 完了（9/9テスト） |
| ④ | `keymap-conflict-resolution` | Vim式モーダルキー / Emacs式カーソル移動の共存 | ✅ Phase 3 完了（38/38テスト・getKeymap()/registerAction() でプラグインがキーバインド登録可能） |
| ⑤ | `gui-rendering-pipeline` | Swing/AWT GUI描画 | ✅ v3 完了（22/22テスト・縦横スクロール・JSplitPane・Ctrl+W） |
| ⑥ | `plugin-api-design` | プラグイン向け公開API | ✅ 完了（39/39テスト・getLine/offsetAt/setCursor/isNormalMode/getKeymap追加） |
| ⑦ | `editor-testing-strategy` | 境界値・大規模ファイルのテスト戦略 | ✅ 完了（101テスト追加・計394/394テスト全PASS） |
| ⑧ | `java-source-analysis` | Compiler Tree APIによるAST解析・auto-import索引基盤 | ✅ 完了（49/49テスト・import索引/シンボル索引/graceful degradation） |
| ⑨ | `javac-compile-integration` | javac連携・コンパイルエラー表示 | ✅ 完了（15/15テスト・ガター描画・波下線・INSERTモード離脱フック） |
| ⑩ | `jdk-api-navigation` | JDKクラス/メソッド/フィールドの参照・ナビゲーション | ✅ 完了（18/18テスト・K キー・jrt:/ 索引・リフレクション表示） |
| ⑪ | `javadoc-viewer` | ローカルJavadoc(HTML)のエディタ内表示 | ✅ 完了（15/15テスト・graceful degradation・`K`キーでサマリ表示） |
| ⑫ | `openjdk-source-tracing` | JNI/HotSpotレベルのソーストレース | 未着手 |
| ⑬ | `project-wide-search` | 作業ディレクトリ配下のgrep的検索 | ✅ 完了（19/19テスト・`:grep`コマンド・Enter でジャンプ） |
| ⑭ | `multi-file-refactoring` | シンボル単位の複数ファイルリファクタリング | ✅ 完了（25テスト・`:rename`コマンド・語境界マッチ・`*rename*`疑似バッファ） |
| ⑯ | `auto-import-handler` | 未定義シンボルの import 自動挿入 | ✅ 完了（26/26テスト・INSERT→NORMAL フック・候補1件自動挿入・複数候補選択UI） |

### 依存関係（Skillを作る順序の制約）

| Skill | 依存先（先に固まっていないと着手すべきでない） |
|---|---|
| ① | なし（最初に着手・実機検証済みにすること） |
| ②③⑤⑦⑧⑪⑬ | ① |
| ④ | ②③ |
| ⑥ | ③ |
| ⑨⑩⑭⑯ | ①⑧（⑧の索引・AST解析基盤を再利用するため。⑨のコンパイルエラーから未定義シンボルを抽出） |
| ⑫ | ⑩（nativeメソッドのナビゲーションを拡張する機能のため） |

**補足**: ⑧〜⑭はいずれも「裏側のロジック」と「画面への表示」が分かれている。ロジック部分は上表の依存関係で着手できるが、実際に画面に結果を表示する部分は⑤の完成が前提になる。

**⑧ と ⑯ の関係**: ⑧ `java-source-analysis` は「既存の import 文を読む索引」と「シンボルを解析する基盤」のみ提供。⑯ `auto-import-handler` は ⑧ の索引と ⑨ のコンパイルエラー（未定義シンボル）を組み合わせて、「import 文の自動挿入」UI を実装する（✅ 完了）。

**注意**: `TextEditorSettings.java`（テーマ等の設定ファイル）は通常の`.java`ファイルとして他のソースと一緒に`javac`でビルドするだけで良く、③（`extension-language-runtime`の動的コンパイル機構）には依存しない。③は「エディタ起動中に新しいプラグイン/マクロをその場で読み込む」という、より高度な用途専用。設定ファイルとプラグイン機構を混同しないこと。

## 作業時の方針

- 何かを実装・設計する前に、関連する`.claude/skills/`配下のSKILL.mdを必ず確認すること。
- 既存のSkillの内容と矛盾する実装をしようとしている場合は、実装を進める前にユーザーに確認すること。
- 新しい設計判断を行った場合、その判断と理由を該当するSKILL.md（またはこのCLAUDE.md）に書き残すこと。口頭の会話だけで終わらせない。
