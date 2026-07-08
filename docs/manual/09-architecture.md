[← 目次](README.md)

# 9. 内部アーキテクチャ（開発者向け）

このページは、コードを読み書きする開発者向けに内部設計をまとめたものです。利用者向けの機能説明は 1〜8 章を参照してください。設計判断の経緯・理由は `CLAUDE.md` と `.claude/skills/` 配下の各SKILL.mdが正です。

## ディレクトリ構成

```
project-root/
├── src/dev/javatexteditor/
│   ├── Main.java                    # エントリポイント・GUI初期化・グローバルキー処理
│   ├── WorkingDirectoryManager.java # 作業ディレクトリの決定・変更・永続化なし
│   ├── buffer/
│   │   ├── Piece.java               # ピーステーブルのピース（record）
│   │   ├── PieceTable.java          # バッファ本体（insert/delete/getText）
│   │   └── UndoablePieceTable.java  # アンドゥ/リドゥ対応バッファ（PieceTable継承）
│   ├── editor/
│   │   ├── KeyBinding.java          # キーバインド（record）
│   │   ├── KeymapRegistry.java      # モード別キーバインド管理・カスタムアクション登録
│   │   ├── ModalEditor.java         # モード管理・カーソル管理・キー処理（最大のクラス）
│   │   ├── MatchPairs.java          # % による対応括弧検索
│   │   ├── Indenter.java            # VISUALモードの > / < インデント計算
│   │   ├── IndentSettings.java      # shiftwidth/tabstop/expandtab/shiftround
│   │   └── GetterSetterGenerator.java # フィールド宣言の解析・getter/setter生成
│   ├── extension/
│   │   ├── EditorContext.java       # プラグイン公開APIインタフェース
│   │   ├── EditorPlugin.java        # プラグインインタフェース
│   │   ├── PluginLoader.java        # JavaCompiler 動的ロード
│   │   └── SimpleEditorContext.java # ModalEditor → EditorContext アダプタ
│   ├── analysis/
│   │   ├── SourceAnalyzer.java      # Compiler Tree API 解析本体（parse-only）
│   │   ├── CompileAnalyzer.java     # JavacTask.analyze() 型解決込みコンパイル診断収集
│   │   ├── JdkClassIndex.java       # jrt:/ 走査による JDK クラス名→FQN インデックス
│   │   ├── JdkJavadocReader.java    # ローカル Javadoc HTML からサマリ文を抽出・キャッシュ
│   │   ├── OpenjdkSourceTracer.java # native メソッドの JNI マングル名計算・src.zip/openjdk-native 検索
│   │   ├── ProjectSymbolResolver.java # K キーのプロジェクト内シンボル解決
│   │   ├── ReceiverTypeResolver.java  # receiver.member の receiver 型推定
│   │   ├── EntryPointIndex.java     # :main のターゲット→ジャンプ先対応表
│   │   ├── WordIndex.java           # Alt+//Ctrl+Space の軽量単語索引（正規表現ベース）
│   │   ├── CompletionIndex.java     # Ctrl+Space の JDK クラス名索引
│   │   └── AutoImportHandler.java   # 未解決シンボル抽出・import自動挿入・organize imports
│   ├── search/
│   │   ├── FileNameSearcher.java    # ファイル名を正規表現で検索
│   │   ├── ProjectSearcher.java     # Files.walkFileTree + regex による全文検索エンジン
│   │   └── DirectoryLister.java     # FILERモード用のディレクトリ列挙・フィルタ
│   │   # ※ バッファ内文字列検索（/ * # n N）は ModalEditor に直接実装
│   ├── refactor/
│   │   └── RenameRefactorer.java    # 語境界付き正規表現による複数ファイル一括リネームエンジン
│   ├── telescope/                   # FuzzyMatcher・FilePicker・GrepPicker・BufferPicker
│   ├── system/
│   │   └── SystemStatsMonitor.java  # CPU/GPU使用率・メモリ使用率のバックグラウンド監視（2秒間隔）
│   ├── tutorial/
│   │   └── Tutorial.java            # :tutor で開く対話型チュートリアル本文（テキストブロック埋め込み）
│   ├── completion2/                 # 未接続の独立コンポーネント（詳細は「プラグインシステム」章）
│   └── ui/
│       ├── Theme.java               # カラーテーマ（LIGHT_MODE / DARK_MODE）
│       ├── EditorCanvas.java        # Swing描画コンポーネント
│       └── TtfMonoFont.java         # 半角ASCII描画（IBM Plex Mono Regular TTF、非等方向スケール）
├── test/dev/javatexteditor/         # 自作 main ハーネス形式のテスト（*Test.java）
├── docs/
│   ├── manual/                      # 本マニュアル
│   ├── requirements.md
│   ├── implementation-history.md
│   └── REFACTORING_PLAN.md
├── lib/                             # .gitignore 対象（setup.sh/bat で自動生成）
│   ├── src.zip                      # OpenJDK 21 Java ソース
│   ├── openjdk-native/              # HotSpot/JNI ネイティブソース
│   └── fonts/                       # IBM Plex Mono Regular (TTF, SIL OFL 1.1)
└── scripts/
    ├── setup.sh / setup.bat
    ├── build.sh / build.bat
    ├── test.sh  / test.bat
    ├── run.sh   / run.bat
    └── package.sh                   # 実行可能JARの生成
```

## バッファ: ピーステーブル方式

テキストの挿入・削除はバッファ全体をコピーせず、「ピース（範囲参照）」のリストを操作することで実現しています。大規模ファイルでも定数時間に近い挿入・削除が可能です。

```
PieceTable
  ├── original: String          （初期テキスト。変更しない）
  ├── addBuffer: StringBuilder  （挿入テキストの追記バッファ）
  └── pieces: List<Piece>       （original/addBufferへの範囲参照リスト）

UndoablePieceTable（PieceTable継承）
  ├── undoStack: Deque<List<Piece>>  （編集前スナップショットのスタック）
  └── redoStack: Deque<List<Piece>>  （リドゥ用スナップショットのスタック）
```

アンドゥ/リドゥは `pieces` リストのコピー（参照のみ、実データ複製なし）をスタックに積む方式で実現しているため、スナップショットのコストはほぼゼロです。詳細は `.claude/skills/editor-buffer-architecture/SKILL.md` を参照してください。

## モーダル編集エンジン: ModalEditor と KeymapRegistry

`ModalEditor` がモード状態・カーソル位置を管理し、`PieceTable`（バッファ）と `EditorCanvas`（描画）を橋渡しします。キーバインドは `KeymapRegistry` により一元管理され、モード別に設定可能です。

```
キー入力 (KeyboardFocusManager)
    ↓
ModalEditor.processKey(keyCode, keyChar, modifiers)
    ├── KeymapRegistry.resolve(mode, keyCode, keyChar, modifiers)
    │   → アクション名の取得（ハードコード不要・外部から設定変更可能）
    │
    ├── COMMAND/SEARCH/FILESEARCH/TELESCOPE/FILER モードは
    │   KeymapRegistry を経由せず processXxxKey() で直接キーを処理
    │       ↓
    PieceTable.insert() / delete()           ← バッファ更新
    Files.writeString() / Files.readString() ← ファイルI/O
    EditorCanvas.setText() / setCursor() / ...        ← 再描画
```

**KeymapRegistry** の主な機能:

- `loadDefaults()`: デフォルトキーマップを定義（Vim標準 + Emacs式INSERTモード移動）
- `bind(mode, keyBinding, actionName)`: 新規キーバインドの登録・既存バインドの上書き
- `resolve(mode, keyCode, keyChar, modifiers)`: キー入力からアクション名を解決
- `registerAction(actionName, handler)`: カスタムアクションハンドラの登録（プラグインから呼び出し可能）

カスタムアクションはビルトインアクションに優先して実行されるため、既存のキー動作を上書きすることも可能です。

> **既知の未接続コード**: NORMALモードの `Ctrl+U`/`Ctrl+P`（バッファ切替）は `ModalEditor.processNormalKey` 冒頭のハードコードが優先され、`KeymapRegistry` の `buffer.prev`/`buffer.next` バインドには既定キーからは到達しません。COMMANDモードの `KeymapRegistry` バインドも同様に到達不能です。詳細は `CLAUDE.md` の「既知の未接続・二重定義」節を参照してください。

## ソース解析エンジン: SourceAnalyzer

`SourceAnalyzer` はJDK標準のCompiler Tree API（`com.sun.source.tree.*`）を使ってJavaソースをparse-onlyモードで解析し、`SourceIndex` を生成します。型解決を行わないため高速（通常ファイルで200ms以内）で、構文エラーがあっても部分的に解析を継続します（graceful degradation）。

```
SourceIndex
  ├── filePath: String                  // "<buffer>" or 絶対パス
  ├── imports: List<ImportEntry>        // import 文の一覧
  ├── symbols: List<SymbolEntry>        // トップレベル型の直接メンバー（ネストクラスは対象外）
  └── hasParseError: boolean
```

`SourceAnalyzer` は [Java開発支援](04-java-tooling.md) のコンパイルエラー表示・JDKナビゲーション・マルチファイルリファクタリングの基盤として再利用されています。

## コンパイルエラー表示: CompileAnalyzer

`SourceAnalyzer`（parse-only）とは別に、`CompileAnalyzer` が `JavacTask.analyze()` まで実行して型解決エラーも収集します。バックグラウンドコンパイルは `ModalEditor.setOnReturnToNormal(Runnable)`（INSERT→NORMAL復帰時）と `setOnSave(Runnable)`（ファイル保存時）でトリガーされ、仮想スレッドで実行後 `SwingUtilities.invokeLater()` で結果を反映します。

## JDK API ナビゲーション: JdkClassIndex / JdkJavadocReader / ProjectSymbolResolver

```
起動時: JdkClassIndex.build()（バックグラウンドスレッドで jrt:/ を走査）

K キー押下:
  1. receiver.member → ProjectSymbolResolver.resolveMemberInType()（型推定は ReceiverTypeResolver）
  2. プロジェクト全体シンボル検索 → ProjectSymbolResolver.resolve()（タイムアウト付き）
  3. JDK ソース疑似バッファ内の native/Java 参照 → OpenjdkSourceTracer
  4. JdkClassIndex.lookup(simpleName) → FQN 候補 → JdkJavadocReader.readSummary(fqn)
       または JdkTypeInfo.from(cls).toStatusLine()（リフレクションフォールバック）
```

`Shift+K`によるプロジェクト全体検索は `ModalEditor.withSearchTimeout()`（`Executors.newVirtualThreadPerTaskExecutor()` + `Future.get(timeout,...)`）で `PROJECT_SYMBOL_SEARCH_TIMEOUT_MS`（1500ms）に制限され、EDTの無制限フリーズを防いでいます。`gr`/`:grep` も同じタイムアウトが適用されます。

## 作業ディレクトリ管理: WorkingDirectoryManager

`:grep`・`:rename`・ファイル名検索・telescope・FILERの基準ディレクトリは、すべて `WorkingDirectoryManager` が管理する単一の作業ディレクトリを参照します。

**初期値の決定順**（上が優先）: 1. 起動時ヒント（開いたファイルの親ディレクトリ）→ 2. ホームディレクトリ → 3. JVM起動ディレクトリ（`user.dir`）。セッションをまたいだ永続化は行いません。

**変更時の検証**（`setWorkingDirectory()`）: 絶対パス化・正規化後、存在確認・ディレクトリ確認・読み取り権限確認をすべて通過した場合のみ採用し、登録済みリスナー（全エディタの `projectRoot` 更新・ステータスバー表示・タイトル更新）へ通知します。

## プロジェクト全文検索: ProjectSearcher

`Files.walkFileTree()` で再帰的にファイルを走査し、`.git`/`build`/`target`/`.gradle`/`node_modules`/`.idea`/`.vscode`（`fullScan=true` の場合はスキップなし）を除外しつつ、2MB超のファイルとバイナリファイル（NULバイト検出・UTF-8デコード失敗）を読み飛ばします。各行に `java.util.regex.Pattern` でマッチ判定し `SearchResult(filePath, lineNumber, lineContent)` のリストを返します。

## マルチファイルリファクタリング: RenameRefactorer

```
Phase 1: 発見    — \bOldName\b（語境界付き）で ProjectSearcher.search() を実行
Phase 2&3: 置換と保存 — 各ファイルを読み込み、Matcher で全置換しUTF-8で書き込み
```

`RenameResult(filePath, replacementCount, success, errorMessage)` のリストを返し、書き込み失敗があっても他のファイルの処理は継続します。

## バッファ内文字列検索: ModalEditor（SEARCHモード）

```
/ キー押下 → SEARCH モードへ遷移
Enter → Pattern.compile(pattern) → Matcher.find() ループで全マッチのオフセット/長さを収集
      → カーソルより後方の最初のマッチへジャンプ（なければ先頭へ折り返し）
      → updateSearchHighlights() で半透明黄色（#FFE000, alpha=90）の矩形描画
n/N → currentMatchIdx を ±1（折り返しあり）
*/# → wordAtCursor() + "\\b" 付き語境界パターンで executeSearch()
```

## GUI描画: EditorCanvas

`JPanel` を継承した `EditorCanvas` が `Graphics2D` で直接描画します。

- 全角文字（CJK・ひらがな・カタカナ）を2セル幅として正確に描画。半角ASCIIは `TtfMonoFont`（IBM Plex Mono Regular TTF）をアンチエイリアス付き・非等方向スケールでセルに合わせて描画（固定サイズのビットマップフォントカタログは廃止済み）
- NORMALモード: ブロックカーソル。INSERTモード: 縦棒カーソル（2px幅）
- VISUAL: 文字単位ハイライト。VISUAL LINE: 行全幅ハイライト。VISUAL BLOCK: 矩形ハイライト
- 画面最下部のステータス行（右から）: 診断件数バッジ ← システムステータス（`CPU 12% | GPU N/A | MEM 62%`、`SystemStatsMonitor` が2秒間隔のバックグラウンドスレッドで更新しEDTは非ブロッキングでキャッシュを読むだけ） ← 時計（`HH:mm:ss`）。歩行キャラクターアニメーションも表示（いずれもアクティブペインのみ）
- アニメーションは `javax.swing.Timer` でステータス行のみを周期的に再描画（キャンバス全体は再描画しない）
- 縦・横スクロール対応（カーソルが画面外に出ると自動追従）、`JSplitPane` によるペイン分割

## テスト戦略

JUnit等は使わず、`main` メソッドを持つ自作テストハーネスで検証します（`test/dev/javatexteditor/**/*Test.java`）。境界値テスト（空バッファ・1文字・行末/行頭など）とパフォーマンステスト（10万行ファイルopen・大規模文書への1000回挿入/削除等）を体系的に整備しています。詳細な方針は `.claude/skills/editor-testing-strategy/SKILL.md` を参照してください。テスト件数の内訳は `docs/implementation-history.md` を参照してください。
