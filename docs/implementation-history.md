# 実装履歴ドキュメント

最終更新: 2026-06-30
対象コミット: `a6aa4a2`（Add FILER mode: in-editor file browser triggered by :cd）まで

このドキュメントは、本プロジェクト（Java SE製テキストエディタ）でこれまでに行われた実装を、設計判断・実装内容・テスト結果を含めて漏れなく記録するものである。`README.md`（利用者向け機能一覧）・`docs/requirements.md`（初期要件定義）・`.claude/skills/`配下の各`SKILL.md`（設計知識）と役割が異なり、本書は**プロジェクト全体の実装経緯を時系列・領域別に俯瞰できる単一の参照点**として作成した。

---

## 目次

1. [プロジェクトのビジョンと制約](#1-プロジェクトのビジョンと制約)
2. [全体アーキテクチャ](#2-全体アーキテクチャ)
3. [Skillロードマップと実装状況の一覧](#3-skillロードマップと実装状況の一覧)
4. [領域別 実装詳細](#4-領域別-実装詳細)
5. [ディレクトリ構成と実装規模](#5-ディレクトリ構成と実装規模)
6. [テスト戦略とテスト結果](#6-テスト戦略とテスト結果)
7. [開発の時系列（コミット履歴ハイライト）](#7-開発の時系列コミット履歴ハイライト)
8. [ドキュメント間の不整合・既知の差異](#8-ドキュメント間の不整合既知の差異)
9. [未着手・スコープ外の機能](#9-未着手スコープ外の機能)
10. [付録: 主要ファイル一覧](#10-付録-主要ファイル一覧)

---

## 1. プロジェクトのビジョンと制約

### 1.1 ビジョン（`docs/requirements.md`より）

- 普段使いのキーバインドはVim（NeoVim）。一方でEmacsの「編集モードを変えずにカーソル移動できる」操作感も気に入っており、これをVimの編集モデル（特にINSERTモード）の中で併用したい。
- 既存のJava向けIDEは「重い」「機能が大げさ」と感じており、IDEの補助機能に依存せずコーディングを楽しめる、無駄を削ぎ落としたシンプルなJavaプログラミング環境を志向する。
- エディタ本体もJavaのみで実装する（学習目的と実用目的の両立）。
- JDK内部構造（JNI・HotSpot）への学習・研究目的も併せ持つ。

### 1.2 技術スタック・制約（厳守事項）

| 項目 | 内容 |
|---|---|
| 言語 | Java 21 (LTS)。`record`・パターンマッチング・テキストブロック等の標準機能は積極利用 |
| 依存ライブラリ | 一切不使用。本番コードはJava SE標準APIのみ |
| ビルドツール | 不使用。`javac`を直接呼び出す（Maven/Gradle不使用） |
| テスト | JUnit等不使用。`main`メソッドを持つ自作テストハーネスで検証 |
| 想定ファイル規模 | 数百行〜数十万行。実装時は常にこの規模を意識（`String`の`+`連結や全体再構築をループ内で行わない） |

パッケージ名は `dev.javatexteditor`（初期の設計ドキュメントでは `dev.vimacs` という旧パッケージ名が使われていたが、実装時に `dev.javatexteditor` に確定している）。

### 1.3 スコープ外と確定した機能（要件定義時点）

- Maven/Gradleプロジェクトのインポート対応
- 既存のVim/Emacsプラグイン（VimScript/Emacs Lisp）との互換性
- デバッガ統合（ブレークポイント・ステップ実行）
- IntelliSense的な一般コード補完（※後に方針転換し実装。[8.2](#82-コード補完機能はスコープ外決定後に方針転換して実装された)参照）
- マルチプロジェクト/ワークスペース管理のサイドバーUI
- Git/バージョン管理統合
- クラウド同期・リモート編集
- コードスニペット・テンプレート機能
- プラグインの共有・マーケットプレイス機能
- 自動アップデート機能
- マウス操作の充実（ドラッグ選択・右クリックメニュー等）
- エディタUI自体の多言語対応

---

## 2. 全体アーキテクチャ

```
キー入力 (KeyboardFocusManager)
    ↓
ModalEditor.processKey(keyCode, keyChar, modifiers)
    ├── KeymapRegistry.resolve(mode, keyCode, keyChar, modifiers)
    │     → アクション名解決（NORMAL/INSERT/COMMAND/VISUAL/VISUAL_LINE/SEARCH/
    │        FILESEARCH/TELESCOPE/IMPORT_SELECT/FILER の各モード）
    │
    ├── PieceTable / UndoablePieceTable        ← バッファ本体（挿入・削除・アンドゥ）
    ├── SourceAnalyzer / CompileAnalyzer        ← Compiler Tree API による解析
    ├── JdkClassIndex / JdkJavadocReader        ← jrt:/ 索引・Javadoc要約
    ├── OpenjdkSourceTracer                     ← native メソッドのJNIトレース
    ├── ProjectSearcher / FileNameSearcher      ← プロジェクト全体検索
    ├── RenameRefactorer                        ← マルチファイルリファクタリング
    ├── TelescopePicker（File/Grep/Buffer）      ← ファジーファインダー
    ├── DirectoryLister                         ← FILERモードのディレクトリ列挙
    ├── WorkingDirectoryManager                 ← 作業ディレクトリの一元管理
    └── EditorContext / PluginLoader            ← プラグイン公開API・動的コンパイル
            ↓
    EditorCanvas (Swing JPanel, Graphics2D)      ← 描画（全角文字幅・スクロール・
                                                     オーバーレイ・ガター・波下線・
                                                     歩行キャラクターアニメーション）
```

ModalEditorが「モード状態・カーソル位置・全機能のオーケストレーション」を担う中心クラスであり、各機能（解析・検索・リファクタリング・ピッカー）は単体で完結したロジッククラスとして`analysis/` `search/` `refactor/` `telescope/`パッケージに分離されている。これは CLAUDE.md の FILER モード設計方針にも明記された「純粋ロジックの分離」原則が、FILERモード以前から一貫して踏襲されている設計である。

---

## 3. Skillロードマップと実装状況の一覧

CLAUDE.mdに記載されたロードマップを基に、実装状況・テスト件数を付記した一覧。

| # | Skill名 | 担当領域 | 状態 | 主なテスト |
|---|---|---|---|---|
| ① | `editor-buffer-architecture` | バッファ（ピーステーブル） | ✅ 完了 | PieceTableTest 15 + PieceTableEdgeCaseTest 46 |
| ② | `modal-editing-engine` | Vimモーダル編集 | ✅ v5完了 | ModalEditorTest 235 + EdgeCase 23 |
| ③ | `extension-language-runtime` | Java動的コンパイル拡張機構 | ✅ v1完了 | PluginLoaderTest 9 |
| ④ | `keymap-conflict-resolution` | Vim/Emacsキー共存 | ✅ Phase 3完了 | KeymapRegistryTest 49 |
| ⑤ | `gui-rendering-pipeline` | Swing/AWT描画 | ✅ v3完了 | EditorCanvasTest 22 |
| ⑥ | `plugin-api-design` | プラグイン公開API | ✅ 完了 | EditorContextApiTest 39 |
| ⑦ | `editor-testing-strategy` | 境界値・大規模ファイルテスト | ✅ 完了 | 101件追加 |
| ⑧ | `java-source-analysis` | AST解析・import/シンボル索引 | ✅ 完了 | SourceAnalyzerTest 49 |
| ⑨ | `javac-compile-integration` | javacコンパイルエラー表示 | ✅ 完了 | CompileAnalyzerTest 15 |
| ⑩ | `jdk-api-navigation` | JDKクラス/メソッド参照 | ✅ 完了 | JdkClassIndexTest 18 |
| ⑪ | `javadoc-viewer` | ローカルJavadoc表示 | ✅ 完了 | JdkJavadocReaderTest 15 |
| ⑫ | `openjdk-source-tracing` | JNI/HotSpotソーストレース | ✅ 実装済み（SKILL.md未作成。[§8.1](#81-12-openjdk-source-tracing-は実装済みだがskillmd未作成)参照） | OpenjdkSourceTracingTest 30 |
| ⑬ | `project-wide-search` | `:grep`プロジェクト全文検索 | ✅ 完了 | ProjectSearchTest 19 |
| ⑭ | `multi-file-refactoring` | `:rename`マルチファイルリネーム | ✅ 完了 | MultiFileRefactoringTest 19 |
| ⑯ | `auto-import-handler` | 未定義シンボルのimport自動挿入 | ✅ 完了 | AutoImportHandlerTest 42 |
| ⑰ | `font-and-statusline-animation` | ビットマップフォント・歩行アニメーション | ✅ 実装済み（方針A採用＋スプライトのみ方針B） | 目視確認中心 |
| ⑱ | `text-search` | バッファ内検索（`/` `*` `#` `n` `N`） | ✅ 完了 | TextSearchTest 34 |
| ⑲ | `file-search` | `\f`ファイル名検索・`\g`内容grep | ✅ 完了 | FileSearchTest 43 |
| ⑳ | `telescope-picker` | telescope.vim風ファジーファインダー | ✅ 完了 | TelescopeTest 28 |
| ㉑ | `simple-filer` | `:cd`後のFILERモード（ファイルブラウザ） | ✅ 完了 | FilerTest（46件、search配下） |

**注**: ⑮は欠番（CLAUDE.mdのロードマップ上もこの番号は使われていない）。

### 3.1 Skill間の依存関係

| Skill | 依存先 |
|---|---|
| ①（最初に着手・実機検証済み） | なし |
| ②③⑤⑦⑧⑪⑬ | ① |
| ④ | ②③ |
| ⑥ | ③ |
| ⑨⑩⑭⑯ | ①⑧（⑧の索引・AST解析基盤を再利用） |
| ⑫ | ⑩（nativeメソッドのナビゲーション拡張） |

⑧〜⑭はいずれも「裏側のロジック」と「画面への表示」が分かれており、ロジックは依存関係表の順で着手できるが、画面表示は⑤の完成が前提。

---

## 4. 領域別 実装詳細

### 4.1 バッファ構造（①editor-buffer-architecture）

**採用方式**: ピーステーブル。検討した4方式（行配列・ギャップバッファ・ピーステーブル・ロープ）のうち、「Java SE標準クラスのみで実装可能」「1行の長さに依存しない」「Vimのアンドゥと相性が良い」という3条件を満たすものとして選定。

```
PieceTable
  ├── original: String          （初期テキスト。変更しない）
  ├── addBuffer: StringBuilder   （挿入テキストの追記バッファ）
  └── pieces: List<Piece>        （original/addBufferへの範囲参照リスト。Piece は record）

UndoablePieceTable（PieceTable継承）
  ├── undoStack: Deque<List<Piece>>
  └── redoStack: Deque<List<Piece>>
```

- `insert(offset, text)`: 該当ピースを分割して新ピースを挟み込む。`original`/`addBuffer`の実データは一切書き換えない。
- `delete(offset, length)`: 削除範囲に重なる全ピースを洗い出し、「削除範囲の外側に残る部分」だけを残す。
- アンドゥ/リドゥ: `pieces`（参照のリスト）の浅いコピーをスタックに積むだけで実現。`Piece`がイミュータブル（record）なため実データ複製が発生せず「ほぼ無料」。
- 既知の制約: 編集回数が増えるとピース数も増え続け、`insert`内の線形探索が遅くなる。数百〜数千回の編集では問題にならないが、将来的には「直前アクセスピースのキャッシュ」「ピースツリーへの置き換え」が改善策として残されている（未着手）。
- v2でスクロール対応のため`getTextInRange(start, end)`と`offsetOfLine(lineNumber)`を追加（後者は現状「毎回先頭から数える」素朴な実装で、巨大ファイルで頻繁に呼ぶと遅くなる既知の最適化余地がある）。

### 4.2 モーダル編集エンジン（②modal-editing-engine）

NORMAL/INSERT/COMMAND/VISUAL/VISUAL_LINEの5モードを基本とし、後続Skillで SEARCH・FILESEARCH・TELESCOPE・IMPORT_SELECT・FILER の各モードが追加された（v5時点で151テスト→最終的に235テストまで拡張）。

代表的な実装済み機能（詳細キー一覧は`README.md`を参照）:
- 基本移動（`h``j``k``l``w``b``e``0``^``$``gg``G`）
- ヤンク/削除/貼り付け（`yy``dd``x``p``P`）、VISUAL/VISUAL LINEモードの範囲選択
- アンドゥ/リドゥ（`u` / `Ctrl+R`）
- INSERTモード中のEmacs式カーソル移動（`Ctrl+F/B/N/P/A/E`、`Alt+F/B`、`Ctrl+Home/End`）
- 行入れ替え（`Alt+J` / `Alt+K`）、ペインナビゲーション（`s`プレフィックス）
- Tabペアスキップ、自動インデント・自動デデント（`}` の入力時）
- Space リーダーキー（`Space+h/l/k/j`、`Space+g+g/s/d` でGetter/Setter自動生成）

### 4.3 拡張言語ランタイム（③extension-language-runtime）

Lispを自作する代わりに、JDK標準の`javax.tools.JavaCompiler`を使い、エディタ起動中にJavaソースをその場で動的コンパイル・ロードするプラグイン機構。

- `EditorPlugin`インタフェース（`getName()` / `onLoad()` / `execute()` / `onUnload()`）をプラグイン作者が実装。
- `PluginLoader.loadPlugin(Path)`が`StandardJavaFileManager`でコンパイル→一時ディレクトリへ出力→`URLClassLoader`でロード→`Class.forName().newInstance()`。
- セキュリティモデル: Java 17+で`SecurityManager`が非推奨・Java 21で削除済みのため使用しない。v1はインタフェース実装確認のみの「信頼できるプラグイン」前提。
- `unloadPlugin`は`onUnload()`呼び出し後に`URLClassLoader.close()`。

### 4.4 キーバインド管理（④keymap-conflict-resolution）

`switch(keyChar)`の直書きハードコードから、`KeyBinding`（record）+`KeymapRegistry`（モード別マップ）への移行を3フェーズで実施。

- Phase 1: `KeyBinding`/`KeymapRegistry`新設、NORMALモードを`resolve()`経由に移行
- Phase 2: INSERT/VISUAL/VISUAL_LINEモードも移行
- Phase 3（✅完了）: `EditorContext.getKeymap()`を追加し、プラグインが`registerAction()`/`bind()`でキーバインドを動的登録・上書き可能に

Vim/Emacsキー競合（`Ctrl+B/F/N/P`）はINSERTモードのみEmacs式を採用し、Vim相当機能は別キー（`/`検索、`:b`バッファ切替想定）で代替する方針を確定。

### 4.5 GUI描画パイプライン（⑤gui-rendering-pipeline）

`JPanel`継承の`EditorCanvas`が`Graphics2D`で直接描画。v1（単一バッファ静的表示）→v2（縦スクロール）→v3（横スクロール＋`JSplitPane`ウィンドウ分割）の順に拡張、全て実装済み。

- 全角文字幅判定（ひらがな・カタカナ・CJK統合漢字・全角英数記号を2セル幅として計算）
- NORMAL=ブロックカーソル、INSERT=縦棒カーソル
- ライト/ダークモードの配色（`Theme` enum。純黒・純白を避けた調整色）
- v2: `getTextInRange()` / `offsetOfLine()`をPieceTableに追加し、`scrollRow`+`ensureCursorVisible()`で縦スクロール
- v3: `scrollCol`（セル単位）で横スクロール、`Main.java`で各ペインが独立した`ModalEditor`+`EditorCanvas`を持つ設計を採用（モード共有案より実装がシンプルなため）。`Ctrl+W`は`KeyboardFocusManager`のdispatcherレベルでインターセプトしてアクティブペインを切替。
- 実機検証で判明し修正された誤り3件: `drawStatusLine`未使用引数の削除、カーソル文字再描画を`charAt`から`codePointAt`+サロゲートペア対応へ修正、`Theme`フィールドの可視性修正
- テスト方式: `BufferedImage`にオフスクリーン描画し`getRGB()`でピクセル検証

将来候補（未着手、`references/future-phases.md`に設計のみ記載）: 行番号ガター表示、`:split`/`:vsplit`コマンドによる動的ペイン管理。

### 4.6 プラグイン公開API（⑥plugin-api-design）

`EditorContext`インタフェースを「テキスト読み取り」「テキスト操作」「カーソル読み取り/操作」「モード問い合わせ」「UI」「キーマップ」の6カテゴリで設計。`ModalEditor`内部に直接依存しない疎結合を原則とする。

主要メソッド: `getText()` `length()` `getLineCount()` `getLine(row)` `insertAtOffset()` `deleteRange()` `getCursorRow/Col()` `offsetAt()` `setCursor()`（自動クランプ） `isNormalMode()` `isInsertMode()` `setStatusMessage()` `getKeymap()`。

### 4.7 テスト戦略（⑦editor-testing-strategy）

①〜⑥で正常系・基本境界値はカバー済みという前提のもと、「そのまま実行したら壊れるかもしれないが誰も試していないケース」を体系的に追加した。

- `PieceTableEdgeCaseTest`（46）: 空バッファ・境界削除・ゼロ長操作・多数挿入の整合性・改行のみ文書
- `UndoRedoDeepTest`（20）: 50回深いアンドゥ・交互アンドゥ/リドゥ・リドゥスタック無効化
- `ModalEditorEdgeCaseTest`（23）: カーソルクランプ・全角文字境界・文書端移動
- `LargeFileTest`（12）: 10万行ファイルopen・1000回挿入/削除・`offsetOfLine`速度計測（全閾値: 500ms〜1000ms）
- O(n²)の罠: `t.insert(t.length(), text)`をn回繰り返すと`length()`呼び出し自体がO(ピース数)のため全体でO(n²)になる、という注意点をテスト設計に明記

### 4.8 ソース解析エンジン（⑧java-source-analysis）

JDK標準のCompiler Tree API（`com.sun.source.tree.*`）でJavaソースをparse-onlyモードで解析し、`SourceIndex`（import一覧・シンボル一覧・構文エラー有無）を構築。型解決を行わないため高速（通常ファイルで200ms以内）。

- 文字列ソースをコンパイルする際、`<buffer>`のような不正URI文字を`_`に置換するURI正規化処理を実装
- シンボル収集はトップレベル型宣言の直接メンバーのみ（ネストクラスは対象外）
- 行番号はjavacの1-indexedから0-indexedへ変換
- 後続Skill（⑨コンパイルエラー表示、⑩JDKナビゲーション、⑭マルチファイルリファクタリング）の解析基盤として再利用される設計

### 4.9 javac連携・コンパイルエラー表示（⑨javac-compile-integration）

`SourceAnalyzer`（parse-only）とは別に`CompileAnalyzer`が`JavacTask.analyze()`まで実行し、型解決エラーも含めて`CompileDiagnostic`（行/列/メッセージ/種別）として収集。

- `EditorCanvas.setDiagnostics()`でガター（E=赤/W=黄）と波下線（4px周期）、ステータスバー右端に件数表示
- `ModalEditor.setOnReturnToNormal(Runnable)`でINSERT→NORMAL復帰時、`setOnSave(Runnable)`で保存時にバックグラウンドコンパイルをトリガー（仮想スレッド実行＋`SwingUtilities.invokeLater()`でUIスレッド安全に反映）
- 既知の注意点: 仮想ファイル名`<buffer>`で`public class`を解析すると「ファイル名不一致」エラーが出るため、保存済みファイルは実パスをURIに渡して回避

### 4.10 JDK APIナビゲーション（⑩jdk-api-navigation）

NORMALモードの`K`キーでカーソル位置の識別子をJDKクラスとして検索し、ステータスバーに1行表示。

- `JdkClassIndex`が起動時にバックグラウンドスレッドで`jrt:/`（JVM起動時に自動マウントされる標準URIスキーム）を走査し、クラス名→FQNのインデックスを構築（匿名/内部クラスは除外）
- 候補が複数ある場合は `java.lang.*` > `java.util.*` > その他、の優先順で選択
- `JdkTypeInfo`がリフレクションでクラス情報（種別・メソッド数・フィールド数）を取得し`toStatusLine()`で整形
- インデックス未完了時は"JDK index building..."、未知の識別子は"Not found in JDK: <word>"を表示

### 4.11 Javadocビューア（⑪javadoc-viewer）

`K`キーの表示をローカルJavadoc(HTML)のサマリ文に格上げ。`JdkJavadocReader`が`<div class="block">`のfirst sentenceを抽出。

- 検索パス優先順: `jte.javadoc.path`システムプロパティ → `$JAVA_HOME/docs/api/` → `/usr/share/doc/openjdk-<N>-doc/api/`
- Javadoc未インストール時は`JdkTypeInfo.toStatusLine()`（種別・メソッド数・フィールド数）にgraceful degradation
- HTMLエンティティデコード・タグ除去・空白正規化・結果キャッシュを実装

### 4.12 OpenJDKソーストレース（⑫openjdk-source-tracing）

CLAUDE.mdのロードマップ表では「未着手」と記載されているが、実際には実装済み（[§8.1](#81-12-openjdk-source-tracing-は実装済みだがskillmd未作成)で詳述）。

- `K`キーで`ClassName.methodName`形式のカーソル位置にあるnativeメソッドを検出し、JNI命名規則（`Java_パッケージ_クラス_メソッド`、アンダースコアエスケープ対応）でマングル名を計算
- `lib/src.zip`（OpenJDKソース、`scripts/setup.sh`/`setup.bat`が自動配置）が存在すればC/C++実装ファイルの位置とコードスニペットも表示
- `src.zip`不在時は"no JDK source available"にgraceful degradation
- jdk-source疑似バッファ内で`K`を押すとCシンボル定義へジャンプする機能も実装済み（`bb1eb6b` `dfef791` `b23e4bb`のコミット）
- native C ソースは`sparse-checkout`で取得し起動時に自動セットアップ（`ced14aa`）

### 4.13 プロジェクト全文検索（⑬project-wide-search）

`:grep <pattern>`コマンドで、`WorkingDirectoryManager`が管理する作業ディレクトリ配下を正規表現検索。

- `ProjectSearcher`が`Files.walkFileTree()`で再帰走査。`.git`/`build`/`target`はスキップ、NULバイトを含むファイルや UTF-8でデコードできないファイルはバイナリ判定してスキップ
- 結果は`*grep*`疑似バッファに`path:line: content`形式で展開、`Enter`でジャンプ
- `:e <path>`で別ファイルを開くとgrepモードは自動解除

### 4.14 マルチファイルリファクタリング（⑭multi-file-refactoring）

`:rename <old> <new>`コマンドでプロジェクト全体のシンボルを一括リネーム。

- `RenameRefactorer`が`\bOldName\b`（語境界付き）パターンで`ProjectSearcher`を呼び出して対象ファイルを発見→各ファイルを読み込み→`Matcher`で全置換→`Files.writeString()`で保存
- 結果は`*rename*`疑似バッファに`path: N replacement(s)`形式で表示
- 型解析は行わない（parse-only）ため、同名の別シンボルも置換される点が既知の制約として明記されている

### 4.15 auto-import自動挿入・削除（⑯auto-import-handler）

INSERT→NORMAL復帰時にコンパイルエラーを解析し、未解決の型名に対してJDKクラス索引から候補を検索。

- 候補1件なら即自動挿入。複数候補はステータスバーに`[1] java.util.List [2] java.awt.List [Esc]=skip`形式で表示し数字キーで選択（後にtelescopeモーダルUIへ改良）
- `Ctrl+Shift+O`（Eclipse互換）/ `SPC+i+o` / `:oi`コマンドで未使用import一括削除（organize imports）
- `:remove-import <fqn>`で特定import1件削除
- import文と宣言文の間に空行を確保するロジックも追加済み

### 4.16 フォント・ステータスラインアニメーション（⑰font-and-statusline-animation）

`bisqwit/that_terminal`（C++製ターミナルエミュレータ）の実装を参考に設計知識を整理。

- 文字描画は「方針A: `Font.MONOSPACED`論理フォントをそのまま使う」を基本採用（実装コストゼロ・OSフォント環境に追従）
- 歩行キャラクターアニメーションのみ「方針B: ビットマップスプライトをバイト配列/2次元配列で埋め込む」を適用
- `MiscFixed 10x20`ビットマップフォントを実際に埋め込み（`BitmapFont10x20.java`）、`Ctrl+Shift+Arrow`でセルサイズを動的調整可能に
- `WalkingPersonSprite.java`が`javax.swing.Timer`（50ms間隔・20fps）でステータスライン上を歩く2フレームスプライトアニメーションを実装。フレーム切替は`frame_rate=6.0`（約333ms周期）、横移動は`walk_speed=64px/秒`で画面端ラップアラウンド
- 画面分割後にアニメーションが停止する不具合を修正済み（`49a8df8`）

### 4.17 バッファ内文字列検索（⑱text-search）

`/`キーでSEARCHモードに入り、`java.util.regex.Pattern`によるバッファ全体検索。

- `Enter`で前方検索実行、マッチ位置を`{offset, length}`のリストとして保持
- `n`/`N`で次/前マッチへ折り返しジャンプ、`*`/`#`でカーソル位置の単語を`\b`語境界付きで前方/後方検索
- マッチ箇所は半透明黄色（`#FFE000`, alpha=0x90）矩形でハイライト。マルチラインマッチは行単位セグメントに分割して描画
- 検索パターンはファイルロード時にもクリアしない（Vim同様、別ファイルを開いても`n`/`N`を継続可能）。ハイライトのみクリアする

### 4.18 ファイル名検索・内容grep（⑲file-search）

NORMALモードの`\f`（ファイル名検索）/ `\g`（内容grep）でFILESEARCHモードに入る。

- `FileNameSearcher`が大文字小文字無視の正規表現でファイル名をマッチ
- `\g`は`:grep`と同じ疑似バッファUIを共有
- `.git`/`build`ディレクトリのスキップ、相対パス表示などは`ProjectSearcher`と同じ規約を踏襲

### 4.19 telescopeファジーファインダー（⑳telescope-picker）

telescope.nvimの3ペイン構造（Prompt/Results/Preview）をSwingオーバーレイで再現。

- `SPC+f`（ファイル検索）/ `SPC+/`（ライブgrep）/ `SPC+b`（バッファ一覧）の3ピッカーを実装（telescopeの`help_tags`等は対象外と明記）
- `FuzzyMatcher`が部分列マッチ＋スコアリング（連続一致+3、単語境界一致+2、通常一致+1、ギャップ-1/文字）を実装
- `EditorCanvas.drawTelescopeOverlay()`がオーバーレイ全体（幅85%・高さ75%・中央配置）を描画。Results 40% / Preview 60%の横分割
- `Ctrl+N`/`Ctrl+P`でリスト移動、`Enter`で開く、`Escape`でキャンセル
- マルチセレクト・カスタムアクション・ソーターカスタマイズ・extensions（lsp_references等）は明示的にスコープ外

### 4.20 作業ディレクトリ管理（working-directory feature）

`WorkingDirectoryManager`が`:grep`・`:rename`・ファイル名検索・telescopeの基準ディレクトリを一元管理する横断的基盤（ロードマップの番号は振られていないが、⑬⑭⑲⑳⑳すべてが依存する重要な実装）。

- 初期値決定順: ①コンストラクタヒント（起動時に開いたファイルの親ディレクトリ）→②`Preferences`永続化された前回値→③ユーザーホーム→④JVM起動ディレクトリ（`user.dir`）。「有効」の判定は`Files.isDirectory()`のみ
- `setWorkingDirectory()`での検証順: null禁止→絶対パス化＋正規化→存在確認→ディレクトリ確認→読み取り権限確認。全通過後のみ`Preferences`へ永続化し、登録済みリスナー（全エディタの`projectRoot`更新・ステータスバー・`JFrame`タイトル）へ同期通知
- 変更手段: `:cd <path>`コマンド、メニュー`File > Set Working Directory...`

### 4.21 FILERモード（㉑simple-filer）

`:cd`実行後に表示されるディレクトリ一覧・ファイルブラウザ。CLAUDE.mdに明記された設計決定事項:

- `Mode.FILER`はTELESCOPE/FILESEARCH/IMPORT_SELECTと同様、`KeymapRegistry`をバイパスし`processFilerKey()`で直接キー処理
- `currentDirectory`は独立フィールドを持たず`projectRoot`（`getProjectRoot()`）と統合。`changeWdCallback`の型は`Consumer<Path>`から`Function<Path, String>`へ変更（成功時null、失敗時エラー文字列を返し、ModalEditorが同期的に成否判定）
- `processCommandKey`のEnterハンドラを`mode = Mode.NORMAL`から`if (mode == Mode.COMMAND) mode = Mode.NORMAL`に変更し、`enterFiler()`が直前にセットした`Mode.FILER`が上書きされる事故を防止
- 描画は`EditorCanvas.setTelescopeState()`/`drawTelescopeOverlay()`をFILERモードでも流用（`DirEntry`→`TelescopeItem`変換）
- ディレクトリ列挙・フィルタは`DirectoryLister`に純粋ロジックとして分離。ファイルオープンは既存の`loadFromFile(String)`を再利用（`pushBuffer`・`onFileOpened`コールバックの確実な動作を担保）

### 4.22 コード補完機能（要件定義後に方針転換して実装）

`docs/requirements.md`の4.3節で「IntelliSense的な一般コード補完（メソッド名等の入力補助）」は当初スコープ外と明記されていたが、実装段階で方針転換し`CompletionIndex`/`CompletionScorer`/`CompletionItem`として実装された（詳細は[§8.2](#82-コード補完機能はスコープ外決定後に方針転換して実装された)）。

- INSERTモードで1文字入力するたびにJDKクラス名・プロジェクト内シンボルから候補を自動検索しポップアップ表示
- `Ctrl+Space`で手動トリガー、`↑`/`↓`で選択、`Tab`/`Enter`で確定挿入、`Escape`で閉じる
- IntelliJ/VS Code相当のスマートスコアリングを`CompletionScorer`で実装

### 4.23 その他の補助機能

- 複数バッファ対応（`SPC+b`でグローバルバッファ一覧、`d`キーで削除）
- NORMALモードの`Ctrl+U`/`Ctrl+P`によるバッファ履歴ナビゲーション
- 診断行ジャンプ（`[g`次のエラー/警告、`[d`前のエラー/警告。折り返し対応）
- INSERT保存（`Ctrl+]`/`Ctrl+[`でNORMAL復帰と同時に保存）
- INSERT単語削除（`Ctrl+W`、Vim互換の単語境界判定）
- `:enew`/`:e`で存在しないファイル名を新規空バッファとして開く
- 実行可能JARパッケージングスクリプト（`scripts/package.sh`）
- IME日本語入力の有効化、INSERT→NORMAL遷移時のIME半角切替

---

## 5. ディレクトリ構成と実装規模

```
project-root/
├── CLAUDE.md
├── README.md
├── docs/
│   ├── requirements.md            # 要件定義書（draft v1）
│   └── implementation-history.md  # 本書
├── .claude/skills/                # 設計知識（14スキルディレクトリ）
├── src/dev/javatexteditor/
│   ├── Main.java
│   ├── buffer/      (Piece, PieceTable, UndoablePieceTable)
│   ├── editor/      (KeyBinding, KeymapRegistry, ModalEditor)
│   ├── extension/   (EditorContext, EditorPlugin, PluginLoader, SimpleEditorContext)
│   ├── analysis/    (14クラス: SourceAnalyzer/CompileAnalyzer/JdkClassIndex/
│   │                  JdkJavadocReader/OpenjdkSourceTracer/AutoImportHandler/
│   │                  CompletionIndex/CompletionScorer 等)
│   ├── search/      (ProjectSearcher, FileNameSearcher, DirectoryLister, SearchResult, DirEntry)
│   ├── refactor/    (RenameRefactorer, RenameResult)
│   ├── telescope/   (TelescopePicker, FilePicker, GrepPicker, BufferPicker, FuzzyMatcher, TelescopeItem)
│   ├── ui/          (Theme, EditorCanvas, BitmapFont10x20, WalkingPersonSprite)
│   └── WorkingDirectoryManager.java
├── test/dev/javatexteditor/        # src/ と並行したパッケージ構成（29ファイル）
├── lib/                            # .gitignore対象。setup.sh/batでsrc.zip自動配置
└── scripts/                        # build/test/run/setup/package（各.sh/.bat）
```

**実装規模**（2026-06-30時点、`wc -l`実測）:

| 領域 | 行数 |
|---|---|
| `src/` （本番コード、48ファイル） | 8,732行 |
| `test/` （テストコード、29ファイル） | 10,477行 |

テストコードが本番コードの行数を上回っており、`editor-testing-strategy`Skillで明文化された境界値・大規模ファイル・パフォーマンステストの厚みを反映している。

---

## 6. テスト戦略とテスト結果

### 6.1 テストハーネスの規約

JUnit等は使わず、各テストクラスが独自に`main`メソッド・`pass`/`total`カウンタ・`check(name, expected, actual)`ヘルパーを持つ。`scripts/test.sh`が`build/`配下の`*Test.class`（内部クラス除く）を自動検出して実行する。新規テストクラスは`*Test.java`命名規則に従えば自動的に実行対象になる。

### 6.2 直近のテスト結果（README.mdより、2026-06-30時点）

```
=== dev.javatexteditor.analysis.AutoImportHandlerTest ===          PASS: 42 / 42
=== dev.javatexteditor.analysis.CompileAnalyzerTest ===            PASS: 15 / 15
=== dev.javatexteditor.analysis.JdkClassIndexTest ===               PASS: 18 / 18
=== dev.javatexteditor.analysis.JdkJavadocReaderTest ===            PASS: 15 / 15
=== dev.javatexteditor.analysis.OpenjdkSourceTracingTest ===        PASS: 30 / 30
=== dev.javatexteditor.analysis.SourceAnalyzerTest ===               PASS: 49 / 49
=== dev.javatexteditor.buffer.PieceTableTest ===                    PASS: 15 / 15
=== dev.javatexteditor.buffer.PieceTableEdgeCaseTest ===            PASS: 46 / 46
=== dev.javatexteditor.buffer.UndoablePieceTableTest ===            PASS: 11 / 11
=== dev.javatexteditor.buffer.UndoRedoDeepTest ===                  PASS: 20 / 20
=== dev.javatexteditor.editor.KeymapRegistryTest ===                PASS: 49 / 49
=== dev.javatexteditor.editor.ModalEditorTest ===                   PASS: 235 / 235
=== dev.javatexteditor.editor.ModalEditorEdgeCaseTest ===           PASS: 23 / 23
=== dev.javatexteditor.extension.EditorContextApiTest ===           PASS: 39 / 39
=== dev.javatexteditor.extension.PluginLoaderTest ===                PASS: 9 / 9
=== dev.javatexteditor.performance.LargeFileTest ===                PASS: 12 / 12
=== dev.javatexteditor.refactor.MultiFileRefactoringTest ===        PASS: 19 / 19
=== dev.javatexteditor.search.ProjectSearchTest ===                  PASS: 19 / 19
=== dev.javatexteditor.search.FileSearchTest ===                     PASS: 43 / 43
=== dev.javatexteditor.search.TextSearchTest ===                     PASS: 34 / 34
=== dev.javatexteditor.ui.EditorCanvasTest ===                       PASS: 22 / 22
=== dev.javatexteditor.ui.KeyboardSimulationTest ===                 PASS: 110 / 110
=== dev.javatexteditor.ui.RobotKeyInputTest ===                      PASS: 134 / 134  (Xvfb 仮想ディスプレイ)

合計: 1009 テストケース全 PASS
```

> 上記のREADME集計には`telescope.TelescopeTest`（28件）と`search.FilerTest`（46件）、`editor.CtrlWTest`/`editor.ImportSelectTest`/`editor.ScrollTest`/`analysis.CompletionIndexTest`/`analysis.CompletionScorerTest`が個別行として列挙されていない。これらは機能追加時にコミットされたテストクラスであり、`scripts/test.sh`実行時には自動検出されて合計に含まれる（README記載の合計1009件という数字とテストクラス一覧表が完全に同期していない可能性がある点は[§8.3](#83-readmemd-のテスト結果集計とテストファイル一覧の差異)を参照）。

`RobotKeyInputTest`は`java.awt.Robot`を使い実キーイベント経由で動作検証する。`DISPLAY`環境変数が必要なため、Xvfbなどの仮想ディスプレイがあればCI環境でも実行可能。

### 6.3 境界値・パフォーマンステストの方針

- O(n²)の罠の回避（[4.7](#47-テスト戦略-⑦editor-testing-strategy)参照）
- マルチバイト文字（サロゲートペア）はJavaの`char`単位オフセット管理という前提のもと、全角文字（「あいう」等）でのchar単位一貫性をテスト。サロゲートペア自体（絵文字等）のテストは将来課題として明記されたまま未着手
- パフォーマンス閾値はすべて明示（10万行ファイルopen 500ms以内、`getText()` 1000ms以内 等）し、将来の最適化のベースラインとして機能させる方針

---

## 7. 開発の時系列（コミット履歴ハイライト）

リポジトリは2026-06-24の`Initial commit`から開始し、2026-06-30時点で86コミット。

| 日付 | 主な出来事 |
|---|---|
| 06-24 | Initial commit |
| 06-28 | フォント・統計ライン関連、`}`自動デデント、auto-indent、`sv`/`ss`によるペイン分割、Neovim風キーマップ（leader/行入れ替え/ペインナビ）、Getter/Setter自動生成、organize imports（Ctrl+Shift+O） |
| 06-29 | ⑰font-and-statusline-animation Skill追加・MiscFixed 10x20フォント埋め込み、IME日本語入力修正、歩行キャラクターアニメーション、テキスト内検索（/ * # n N）、ファイル名/内容検索（\f \g）、telescopeファジーファインダー、auto-importの複数候補telescope UI化、診断行ジャンプ（[g [d）、F2キー診断ダイアログ、`:enew`/`:e`新規バッファ、OpenJDK src.zipセットアップスクリプト、Kキーによるnativeメソッドトレース・JDKソースジャンプ、複数バッファ対応、Ctrl+Spaceコード補完、1文字入力での自動補完、Ctrl+U/Ctrl+Pバッファ履歴ナビ、実行可能JARパッケージング、作業ディレクトリ管理機構（WorkingDirectoryManager） |
| 06-30 | Fileメニューバー削除、README更新（作業ディレクトリ管理・コード補完の説明追記）、FILERモード追加（`:cd`実行後のファイルブラウザ） |

全期間を通じて、機能追加コミットには対応するテストクラスの追加・既存テストの拡張が一貫して伴っている（コミットログ中に`fix:`系コミットが多数あるのは、実機検証で発覚した不具合をその場で修正する開発スタイルを反映している。例: auto-import関連のlocaleバグ修正連鎖 `776d1b8` `9754a6c` `1025787` `448bd8d`、画面分割後のアニメーション停止修正`49a8df8`、JdkClassIndex未完了時のauto-import失敗修正`829e31a`）。

---

## 8. ドキュメント間の不整合・既知の差異

このセクションは、本ドキュメント作成のための調査で見つかった、既存ドキュメント間の食い違いを記録する。今後の整理の参考にすること。

### 8.1 ⑫ openjdk-source-tracing は実装済みだがSKILL.md未作成

CLAUDE.mdのSkillロードマップ表では⑫`openjdk-source-tracing`が「未着手」と記載されている。しかし実際には:

- `src/dev/javatexteditor/analysis/OpenjdkSourceTracer.java`が実装済み
- `test/dev/javatexteditor/analysis/OpenjdkSourceTracingTest.java`に30テスト（全PASS）
- README.mdに機能説明（「OpenJDK native メソッドトレース」節）が存在
- `.claude/skills/`配下に`openjdk-source-tracing`ディレクトリ・SKILL.mdは存在しない

つまり「コードとテストは実装済みだが、設計知識をSKILL.mdに書き残す」というCLAUDE.mdの方針（「新しい設計判断を行った場合、その判断と理由を該当するSKILL.mdに書き残すこと」）が、この機能については未実行のまま残っている。次にこの機能へ手を入れる際は、CLAUDE.mdのロードマップ表の状態欄を更新し、SKILL.mdを作成することが望ましい。

同様に、⑬project-wide-search・⑭multi-file-refactoring・⑯auto-import-handler・⑲file-search・㉑simple-filerもCLAUDE.mdでは「完了」マーク済みだが、`.claude/skills/`配下に対応するSKILL.mdディレクトリが存在しない（⑳telescope-pickerのみSKILL.mdが存在する）。実装自体はREADME.mdとソースコードで裏付けが取れているが、設計判断ログの記録先がSKILL.mdに一本化されていない状態である。

### 8.2 コード補完機能はスコープ外決定後に方針転換して実装された

`docs/requirements.md`（最終更新2026-06-24、ドラフトv1）の4.3節「今回の協議で明確に『やらない』と決定したもの」には「IntelliSense的な一般コード補完（メソッド名等の入力補助）」が明記されている。

しかし実際には2026-06-29に`feat: クラス名・メソッド名の入力補完機能を実装（Ctrl+Space）`（`e1fdea0`）、続けて`feat: 1文字入力で自動補完・IntelliJ/VS Code 相当のスマートスコアリング`（`d6ee2de`）がマージされ、`CompletionIndex`/`CompletionScorer`/`CompletionItem`として実装・README.mdにも機能説明が追記されている。

`docs/requirements.md`はドラフトのまま日付が更新されておらず、この方針転換が要件定義書側に反映されていない。今後要件定義書を更新する機会があれば、この決定の上書きを明記すべきである。

### 8.3 README.md のテスト結果集計とテストファイル一覧の差異

README.mdの「テスト結果」セクションに列挙された22テストクラスの合計が「1009テストケース全PASS」と記載されているが、実際の`test/`配下には29個の`*Test.java`ファイルが存在する。一覧に明記されていないクラス（`TelescopeTest`・`FilerTest`・`CtrlWTest`・`ImportSelectTest`・`ScrollTest`・`CompletionIndexTest`・`CompletionScorerTest`）は、README本文中の「⑳telescope-picker で追加したテスト（28件）」のような個別セクションでは言及されているが、冒頭の集計表には反映されていない。実行は`scripts/test.sh`が自動検出するため機能的な問題はないが、ドキュメントの数字の正確性という観点では更新の余地がある。

### 8.4 パッケージ名の旧称

`.claude/skills/`配下のSKILL.md（特に③④⑥⑨⑩⑬等の初期に書かれたもの）には、コード例のパッケージ宣言が`dev.vimacs.*`という旧パッケージ名のまま残っている箇所がある。CLAUDE.mdでは`dev.javatexteditor`が確定済みパッケージ名として明記されており、実際のソースコードもすべて`dev.javatexteditor`を使用している。SKILL.md内のコード例はあくまで設計時の参考実装であり実害はないが、今後SKILL.mdを更新する際にパッケージ名を揃えると一貫性が増す。

---

## 9. 未着手・スコープ外の機能

### 9.1 要件定義時点で明確にスコープ外と決定されたもの

デバッガ統合、マルチプロジェクト/ワークスペースのサイドバーUI、Git統合、クラウド同期・リモート編集、コードスニペット・テンプレート機能、プラグインのマーケットプレイス、自動アップデート、マウス操作の充実、エディタUI自体の多言語対応。（[§8.2](#82-コード補完機能はスコープ外決定後に方針転換して実装された)の通りコード補完のみ後に方針転換）

### 9.2 設計上「将来検討」と位置付けられたまま未着手のもの

- ピーステーブルの最適化（直前アクセスピースのキャッシュ・ピースツリー化）
- `offsetOfLine()`の差分更新索引（改行位置インデックス）
- `gui-rendering-pipeline`の「v4候補」: 行番号ガター表示、検索ハイライトとの統合（実際には⑱で先に実装済み）、`:split`/`:vsplit`コマンドによる動的ペイン管理
- サロゲートペア（絵文字等）の境界値テスト
- アンドゥ単位のグループ化（1キー入力＝1アンドゥ単位の厳密な保証。現状は`insert`/`delete`呼び出し単位でスナップショットを取るため、内部的に複数回呼ばれるコマンドではアンドゥ粒度がユーザー感覚とズレる可能性が残っている）

---

## 10. 付録: 主要ファイル一覧

### 10.1 src/dev/javatexteditor/ 全48ファイル（パッケージ別）

| パッケージ | ファイル |
|---|---|
| (root) | `Main.java`, `WorkingDirectoryManager.java` |
| `buffer/` | `Piece.java`, `PieceTable.java`, `UndoablePieceTable.java` |
| `editor/` | `KeyBinding.java`, `KeymapRegistry.java`, `ModalEditor.java` |
| `extension/` | `EditorContext.java`, `EditorPlugin.java`, `PluginLoadException.java`, `PluginLoader.java`, `SimpleEditorContext.java` |
| `analysis/` | `AnalysisException.java`, `AutoImportHandler.java`, `CompileAnalyzer.java`, `CompileDiagnostic.java`, `CompletionIndex.java`, `CompletionItem.java`, `CompletionScorer.java`, `DiagnosticKind.java`, `ImportEntry.java`, `ImportSuggester.java`, `JdkClassIndex.java`, `JdkJavadocReader.java`, `JdkTypeInfo.java`, `OpenjdkSourceTracer.java`, `SourceAnalyzer.java`, `SourceIndex.java`, `SymbolEntry.java`, `SymbolKind.java` |
| `search/` | `DirEntry.java`, `DirectoryLister.java`, `FileNameSearcher.java`, `ProjectSearcher.java`, `SearchResult.java` |
| `refactor/` | `RenameRefactorer.java`, `RenameResult.java` |
| `telescope/` | `BufferPicker.java`, `FilePicker.java`, `FuzzyMatcher.java`, `GrepPicker.java`, `TelescopeItem.java`, `TelescopePicker.java` |
| `ui/` | `BitmapFont10x20.java`, `EditorCanvas.java`, `Theme.java`, `WalkingPersonSprite.java` |

### 10.2 test/dev/javatexteditor/ 全29ファイル（パッケージ別）

| パッケージ | ファイル |
|---|---|
| `buffer/` | `PieceTableTest.java`, `PieceTableEdgeCaseTest.java`, `UndoablePieceTableTest.java`, `UndoRedoDeepTest.java` |
| `editor/` | `KeymapRegistryTest.java`, `ModalEditorTest.java`, `ModalEditorEdgeCaseTest.java`, `CtrlWTest.java`, `ImportSelectTest.java`, `ScrollTest.java` |
| `analysis/` | `SourceAnalyzerTest.java`, `CompileAnalyzerTest.java`, `JdkJavadocReaderTest.java`, `JdkClassIndexTest.java`, `OpenjdkSourceTracingTest.java`, `AutoImportHandlerTest.java`, `CompletionIndexTest.java`, `CompletionScorerTest.java` |
| `extension/` | `EditorContextApiTest.java`, `PluginLoaderTest.java` |
| `performance/` | `LargeFileTest.java` |
| `search/` | `FileSearchTest.java`, `ProjectSearchTest.java`, `TextSearchTest.java`, `FilerTest.java` |
| `refactor/` | `MultiFileRefactoringTest.java` |
| `telescope/` | `TelescopeTest.java` |
| `ui/` | `EditorCanvasTest.java`, `KeyboardSimulationTest.java`, `RobotKeyInputTest.java`, `ScrollPreview.java`*, `VisualModePreview.java`*, `VisualPreview.java`*, `YankPasteDemo.java`* |

\* `main`メソッドはあるが`pass`/`total`カウンタによる自動判定テストではなく、目視確認用のプレビュー生成・動作デモプログラム。

### 10.3 scripts/ 一覧

| スクリプト | 役割 |
|---|---|
| `build.sh` / `build.bat` | `src/`配下の全`.java`を`build/`にコンパイル |
| `test.sh` / `test.bat` | build後、`src/`+`test/`をコンパイルし`*Test`クラスの`main`を実行 |
| `run.sh` / `run.bat` | `Main`クラスを起動 |
| `setup.sh` / `setup.bat` | OpenJDK 21の`src.zip`を`lib/`に自動配置（native トレース機能用） |
| `package.sh` | 実行可能JARのパッケージング |

---

*本ドキュメントは2026-06-30時点の実装状況を記録したものである。以降の実装はCLAUDE.mdの方針に従い、関連するSKILL.mdへの追記を優先し、本ドキュメントは大きな区切り（メジャーなSkill完了など）のタイミングで更新することを推奨する。*
