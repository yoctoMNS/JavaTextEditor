# JavaTextEditor リファクタリング計画書

- 作成日: 2026-07-04
- 対象コミット: `b6d353e`（Merge pull request #57）
- 対象ブランチ: `claude/refactoring-plan-creation-igyix4`
- 本計画書は **調査と計画のみ** の成果物であり、対象コード・設定・テストへの変更は一切行っていない。
- 実行者への前提: 本計画書と対象コードだけを渡された実行者が、追加確認なしで作業を完遂できることを目標に書かれている。判断が割れうる箇所にはコードスケッチを付けた。

---

# 0. 変更検証ログ

## 作業前ベースライン（調査開始時に実行）

```
$ git status --short
（出力なし = 作業ツリーはクリーン）

$ git branch --show-current
claude/refactoring-plan-creation-igyix4

$ git log --oneline -1
b6d353e Merge pull request #57 from yoctoMNS/claude/class-var-navigation-skill-48pafr
```

## ビルド・テストのベースライン（調査中に実行。`build/` は .gitignore 対象のため追跡ファイルに影響なし）

```
$ java -version
openjdk version "21.0.10" 2026-01-20   （Linux / DISPLAY 未設定のヘッドレス環境）

$ ./scripts/build.sh
Build OK
```

**重要な実測結果**: この環境では `./scripts/test.sh` は**完走しない**。2番目のテストクラス `CompileAnalyzerTest` が `PASS: 15 / 15  (FAIL: 0)` を出力した後、JVM が終了せずに停止する（6時間以上放置しても終了しないことを確認）。原因はスレッドダンプで特定済み: `EditorCanvas` のコンストラクタが `javax.swing.Timer`（40ms 周期のステータスラインアニメ用）を start するため、`EditorCanvas` を生成したテストは main 終了後も AWT-EventQueue（非デーモンスレッド）にタイマーイベントが供給され続け、JVM が自然終了しない。テスト自体はすべて成功しており、**「テストの失敗」ではなく「テストハーネスがプロセス終了しない」問題**である（→ P-23、Item 1）。

このため、ベースラインは各テストクラスを個別 JVM ＋ ハング検知付きで実行して取得した（手順は §4 Item 0 参照）。全37テストクラスの結果:

```
- 34 クラス: 正常終了・全ケース PASS（rc=0）
- dev.javatexteditor.analysis.CompileAnalyzerTest: PASS: 15/15 表示後、JVM が終了せずハング（P-23）
- dev.javatexteditor.ui.EditorCanvasTest:          PASS: 30/30 表示後、JVM が終了せずハング（P-23）
- dev.javatexteditor.editor.ScrollTest: 18/20 PASS・2件 FAIL（既存の失敗。P-10 参照）
    FAIL [halfPageUp: cursor moved up by 20] expected=20 actual=40
    FAIL [halfPage interleaved: row 40] expected=40 actual=60
```

ScrollTest の失敗原因（コードから特定済み）: ScrollTest は NORMAL モードの Ctrl+U に「半ページ上スクロール」を期待しているが、現在の実装では Ctrl+U は「バッファ履歴を前へ」に再割当てされている（`ModalEditor.processNormalKey` 冒頭のハードコード。CLAUDE.md のチュートリアル節にも「Ctrl+U（バッファ履歴の前へ）」と明記があり、意図された新仕様と推定される）。つまり**テストが旧仕様の期待値のまま取り残されている**。`test.sh` は P-23 のハングにより ScrollTest まで到達しないため、この失敗は従来観測されていなかったと推定される。どちらを正とするか（テスト更新 or キー割当て復帰）は仕様判断のため本計画では変更せず、U-7 / Item 14 で扱う。

GUI 系の補足: `RobotKeyInputTest` は DISPLAY 未設定のため [SKIP] 表示で正常終了（rc=0）。`KeyboardSimulationTest` はヘッドレスでも 110/110 PASS。

## 作業後検証（計画書作成完了時に実行）

```
$ git status --short
?? docs/REFACTORING_PLAN.md        ← 本計画書（新規ファイル・成果物そのもの）のみ

$ git diff --stat
（出力なし）
```

**無変更の確認**: `git diff --stat` が空であること＝**既存の追跡ファイル（コード・設定・ドキュメント・テスト）には1文字の変更も発生していない**。`git status --short` に現れる唯一のエントリは本計画書 `docs/REFACTORING_PLAN.md`（新規作成の成果物）である。調査中に実行したビルド（`build/` 生成）とテストは .gitignore 対象のため追跡ファイルに影響しない。テスト実行用の補助スクリプト・ログはすべてリポジトリ外（/tmp）に置いた。

---

# 1. 現状理解

## 1.1 このコードベースが何を実現しているか

Vim のモーダル編集と Emacs のカーソル移動・拡張性を統合した、Java SE 標準 API のみで実装された GUI（Swing/AWT）テキストエディタ。外部ライブラリ・ビルドツール・テストフレームワークを一切使わないことが確定済みの制約（CLAUDE.md）。編集機能に加えて、javac（`javax.tools`）を使ったコンパイル診断表示・auto-import・シンボル定義ジャンプ（K キー）・JDK ソース閲覧・プロジェクト内 grep・telescope 風ファジーファインダー・FILER・チュートリアル等の IDE 的機能を持つ。

## 1.2 全体アーキテクチャの要約

3層＋支援パッケージ構成:

1. **バッファ層** `buffer/` — ピーステーブル（`PieceTable`）とスナップショット方式アンドゥ（`UndoablePieceTable`）。純粋データ構造で他層に依存しない。
2. **エディタ制御層** `editor/` — `ModalEditor`（3,517行）が全モードのキー処理・カーソル管理・コマンド実行・疑似バッファ管理を担う中心ハブ。`KeymapRegistry` がモード別キーバインドをアクション名文字列に解決する。
3. **描画層** `ui/` — `EditorCanvas`（JPanel 継承、922行）が全描画（テキスト・カーソル・診断ガター・telescope オーバーレイ・補完ポップアップ・ステータス行アニメ）を担う。`ModalEditor` → `EditorCanvas` は `syncCanvas()` による一方向 push。
4. **支援パッケージ** — `analysis/`（javac 連携・各種インデックス）、`search/`（grep・ファイル名検索・ディレクトリ列挙）、`telescope/`（ファジーファインダー）、`refactor/`（`:rename`）、`extension/`（プラグイン API。**本番コードからは未接続**）、`tutorial/`（`:tutor` 本文）。
5. **配線** `Main.java` — JFrame 構築、ペイン分割ツリー管理、`KeyboardFocusManager` によるグローバルキー捕捉、各インデックスの起動時バックグラウンド構築、コールバック配線。

## 1.3 実行フロー / データフロー

- 起動: `Main.main()` → セットアップスクリプト自動実行判定 → `WorkingDirectoryManager` 初期化 → `JdkClassIndex`/`CompletionIndex`/`WordIndex` を仮想スレッドでバックグラウンド構築 → EDT で JFrame + 初期 `Leaf`（`EditorCanvas`+`ModalEditor` の組）生成。
- キー入力: `KeyboardFocusManager` の dispatcher（`Main.main()` 内の無名クラス）が KEY_PRESSED/KEY_TYPED を捕捉 → アクティブペインの `ModalEditor.processKey()` → モード別 `processXxxKey()` → `KeymapRegistry.resolve()` でアクション名解決 → switch 分岐で編集操作 → 毎回 `syncCanvas()` で `EditorCanvas` に全文・カーソル・オーバーレイ状態を push → `repaint()`。
- コンパイル診断: INSERT→NORMAL 遷移・保存時に `Main.setupCompileAnalysis` が登録したコールバックが仮想スレッドで `CompileAnalyzer.analyze()` を実行 → EDT に戻して `canvas.setDiagnostics()` + `editor.handleAutoImport()`。
- 疑似バッファ: `*grep*`・`*rename*`・`*file-search*`・`*cd候補*`・jdk-source・チュートリアルは、いずれも `buffer` フィールドを新しい `UndoablePieceTable` に差し替えて表示する方式。復帰方法は機能ごとに異なる（`bufferHistory` / `cdSaved*` / `saved*` の3系統）。

## 1.4 外部境界

| 境界 | 使用箇所 |
|---|---|
| ファイルシステム読み書き | `ModalEditor.saveToFile/loadFromFile`、`RenameRefactorer`（**ファイル上書き**）、各 Searcher/Lister/Index、`PluginLoader`（一時ディレクトリ） |
| プロセス起動 | `Main.runSetupIfNeeded()`（`bash scripts/setup.sh` / `cmd.exe scripts/setup.bat`） |
| javac (`javax.tools`) | `CompileAnalyzer`、`SourceAnalyzer`、`PluginLoader` |
| `jrt:/` FileSystem・リフレクション | `JdkClassIndex`、`JdkTypeInfo`、`OpenjdkSourceTracer` |
| `lib/src.zip`・`lib/openjdk-native/`（setup.sh が配置、.gitignore 対象） | `OpenjdkSourceTracer` |
| JDK 付属 Javadoc HTML | `JdkJavadocReader`（graceful degradation） |
| システムプロパティ/環境変数 | `user.home`・`user.dir`・`java.home`・`JAVA_HOME`・`jte.javadoc.path`・`native.encoding` |
| GUI（Swing/AWT/IME） | `Main`、`EditorCanvas`、`JOptionPane`（F2 診断ダイアログ） |
| DB / HTTP / ネットワーク | **なし**（setup.sh 内の git clone を除く） |

## 1.5 リファクタリング上の重要前提

1. **依存ライブラリ・ビルドツール・テストフレームワーク導入は禁止**(CLAUDE.md)。改善提案にこれらを含めてはならない。
2. **テストは `main` メソッド式自作ハーネス**。`./scripts/test.sh` が `build/**/*Test.class` を全部実行し、1クラスでも非0終了なら失敗。ただし現状は §0 のとおり **`EditorCanvas` を生成するテストクラスで JVM が終了せず test.sh が完走しない**（P-23）。この修復が本計画の Item 1 である。
3. **数十万行ファイルの編集を想定**する方針が明文化されている。ただし現実装には全文 `split` の多用（P-15）などこれと矛盾する箇所があり、これは「既知の設計負債」として扱う（今回の計画では**挙動を変えない安全な範囲**のみ扱い、本丸のデータ構造変更はスコープ外とする）。
4. `.claude/skills/` 配下の SKILL.md 群が設計決定の一次資料。キーバインド変更・モード追加などはスキルとの整合確認が必要。
5. テスト側は `getYankType()` が `"char"/"line"` 文字列を返すこと等、**現在の公開アクセサの型と値に依存**している。公開シグネチャの変更は不可。

## 1.6 読んだ範囲 / 読めなかった範囲

**全文を読んだファイル（本番コード）**: `Main.java`、`WorkingDirectoryManager.java`、`editor/`（ModalEditor・KeymapRegistry・KeyBinding）、`buffer/`（PieceTable・UndoablePieceTable・Piece）、`ui/Theme.java`、`ui/EditorCanvas.java`、`analysis/` 全21ファイル、`search/` 全5ファイル、`telescope/` 全6ファイル、`refactor/` 全2ファイル、`extension/` 全5ファイル、`scripts/build.sh`・`test.sh`・`run.sh`。

**冒頭のみ確認（内容が定型データ・テキストのため）**: `ui/BitmapFont10x20.java`（636行。大半がグリフの byte 配列データ）、`ui/WalkingPersonSprite.java`（スプライトデータ＋描画。公開シグネチャのみ確認）、`tutorial/Tutorial.java`（376行。ほぼ全部がテキストブロック定数）。

**テストコード**: `PieceTableTest`・`ModalEditorTest`・`AutoImportHandlerTest`・`CompileAnalyzerTest`・`RobotKeyInputTest` の構造とアサーション方式を確認。他のテストはファイル名・対象クラスの対応関係と `System.exit(0)` / `EditorCanvas` 生成の有無のみ機械的に確認（合計 38 テストクラス）。

**未読**: `scripts/setup.sh`・`package.sh`・`*.bat`、`docs/` 配下の過去セッションログ、`.claude/skills/` の SKILL.md 本文。

## 1.7 不明点（実行者が最初に確認すべき事項）

| # | 不明点 | 確認方法 |
|---|---|---|
| U-1 | **P-23（test.sh が完走しない）が実行者の環境でも再現するか**。計画作成環境（Linux・ヘッドレス）では確実に再現した。原理上（Swing Timer が EDT を生かし続ける）どの環境でも起きるはずだが、開発者の常用環境での再現は未確認 | Item 0 の手順で `timeout 120 java -cp build dev.javatexteditor.analysis.CompileAnalyzerTest` を実行し、`PASS: 15/15` 表示後にプロセスが終了するか観察する。終了しなければ再現 → Item 1 を実施。すんなり終了する環境だった場合も Item 1 は無害（成功時の exit 0 を明示するだけ）なので実施してよい |
| U-2 | GUI テスト（`RobotKeyInputTest`・`KeyboardSimulationTest`）が実行者の環境で SKIP になるか実走するか | `echo $DISPLAY` を確認し、単体実行して `[SKIP]` 表示の有無を見る |
| U-3 | `lib/src.zip`・`lib/openjdk-native/` が実行者の環境に存在するか（存在有無で `OpenjdkSourceTracingTest`・`JdkJavadocReaderTest` の通過パスが変わる。両テストとも graceful degradation を検証する設計） | `ls lib/` を確認。Item 0 のベースライン記録で「自分の環境での成功件数」を控える |
| U-4 | `KeymapRegistry` の COMMAND モード束縛（`enter.normal`/`execute.command`）が、プラグイン等の外部から `resolve()` 経由で参照される想定があるか（`processCommandKey` は registry を参照しないため現状コード上は不使用） | 不明。**削除しないこと**（Item 14 で「現状の記録」のみ行う） |
| U-5 | `CompletionIndex.refreshProjectSymbols()`（本番・テストとも呼び出しゼロ）が将来用に意図的に残されているか | Javadoc に「保存時に呼ぶことを想定」とあるため意図的な将来用と推定。**削除しないこと**（Item 14 で記録のみ） |
| U-6 | `extension/` パッケージ（PluginLoader 等）が本番コードから未接続（`:plugin` コマンド不在）なのは意図的な段階実装か | ロードマップ③⑥は「完了」扱いのため、UI 接続だけ未着手の段階実装と推定。**機能追加禁止のため今回は接続しない** |
| U-7 | **`ScrollTest` の既存失敗2件**（§0 参照）の正しい解消方法: (a) Ctrl+U=バッファ履歴が正（CLAUDE.md 記載どおり）→ テスト側の期待値を更新すべき、(b) Ctrl+U=半ページスクロールが正 → キー割当てを戻すべき、のどちらか | 仕様判断のため**本計画では変更しない**。ベースライン比較では「ScrollTest 18/20（既知の2件 FAIL）」を基準とし、この2件が増減しないことを確認する。Item 14 で記録し、ユーザーに判断を仰ぐ |

---

# 2. 構造マップ

## 2.1 ファイル / ディレクトリ役割一覧（本番コード、行数順）

| パス | 行数 | 役割 |
|---|---|---|
| `editor/ModalEditor.java` | 3517 | 全10モードのキー処理・カーソル・コマンド・疑似バッファ・K ジャンプ・getter/setter 生成・auto-import UI。**最大の神クラス** |
| `ui/EditorCanvas.java` | 922 | 全描画。ビットマップフォント・診断ガター・オーバーレイ・アニメ |
| `Main.java` | 668 | エントリポイント。ペインツリー・グローバルキー捕捉・配線・setup 自動実行 |
| `ui/BitmapFont10x20.java` | 636 | ビットマップフォントデータ＋グリフレンダリング（ほぼデータ） |
| `analysis/OpenjdkSourceTracer.java` | 446 | JNI トレース・src.zip / openjdk-native 探索 |
| `tutorial/Tutorial.java` | 376 | `:tutor` 本文定数（ほぼテキスト） |
| `analysis/AutoImportHandler.java` | 271 | 未解決シンボル抽出・import 挿入/削除 |
| `editor/KeymapRegistry.java` | 217 | モード別キーバインド → アクション名解決・既定バインド定義 |
| `analysis/ProjectSymbolResolver.java` | 182 | K キー用: プロジェクト内宣言の2段階検索 |
| `analysis/SourceAnalyzer.java` | 171 | parse-only AST 解析（import/シンボル索引） |
| `analysis/WordIndex.java` | 170 | Alt+/ 単語補完用 TreeSet インデックス |
| `analysis/CompletionIndex.java` | 164 | Ctrl+Space 補完用 TreeMap インデックス |
| `analysis/CompileAnalyzer.java` | 163 | javac analyze 診断取得 |
| `refactor/RenameRefactorer.java` | 155 | `:rename` 語境界一括置換（ファイル上書き） |
| `extension/PluginLoader.java` | 152 | 動的コンパイル・URLClassLoader プラグイン管理（未接続） |
| `analysis/JdkJavadocReader.java` | 140 | ローカル Javadoc HTML サマリ抽出 |
| `analysis/JdkClassIndex.java` | 140 | jrt:/ 走査による単純名→FQN 索引 |
| `buffer/PieceTable.java` | 137 | ピーステーブル本体 |
| `analysis/CompletionScorer.java` | 128 | 補完スコアリング（5段階） |
| `search/ProjectSearcher.java` | 122 | grep（NUL 判定によるバイナリスキップ） |
| `ui/WalkingPersonSprite.java` | 110 | ステータスラインの歩行アニメスプライト |
| その他 | ~700 | 小さな record・enum・picker・context 類 |

## 2.2 主な依存関係（パッケージ間、import 実測）

```
Main(root) ──→ analysis, editor, ui, telescope, (WorkingDirectoryManager)
editor/ModalEditor ──→ analysis, buffer, refactor, search, telescope, tutorial, ui
ui/EditorCanvas ──→ analysis(CompileDiagnostic), telescope(TelescopeItem)
analysis/ProjectSymbolResolver ──→ search
refactor/RenameRefactorer ──→ search
extension ──→ editor(KeymapRegistry, ModalEditor)   ※本番から未参照
buffer ──→ （依存なし）
search, telescope, tutorial ──→ （ほぼ自己完結。telescope→search）
```

## 2.3 変更の波及が大きい箇所

1. **`ModalEditor`** — 38 テストクラス中、editor/ui/search 系の大半が直接 new して叩く。ここの公開アクセサ・キー処理挙動の変更は最も波及が大きい。
2. **`KeymapRegistry` の既定バインド** — `keymap-conflict-resolution` スキルで確定済み。1つの変更が複数モードの操作感に波及。
3. **`PieceTable` / `UndoablePieceTable`** — 全機能の土台。オフセット計算の意味を変えると全テストに波及。
4. **`EditorCanvas` の公開 setter 群** — `ModalEditor.syncCanvas()` と `Main` から呼ばれる。シグネチャ変更は editor/ui 両方に波及。
5. **`SearchResult`・`TelescopeItem`・`CompileDiagnostic` 等の record** — 複数パッケージで共有される値オブジェクト。

## 2.4 外部境界一覧

§1.4 の表を参照。**特に注意**: `RenameRefactorer.rename()` は対象ファイルを**即座に上書き保存**する。この周辺のリファクタは実プロジェクトのファイルを壊すリスクがあるため、テストは必ず一時ディレクトリで行うこと（既存 `MultiFileRefactoringTest` がその方式）。

---

# 3. 問題一覧

> 行番号は対象コミット `b6d353e` 時点の実測値。ズレた場合はシンボル名で特定すること。

## P-23: テストハーネスが完走しない（EditorCanvas の Swing Timer が JVM を終了させない）【最優先】
- **対象箇所**: `ui/EditorCanvas.java:88-99`（`animTimer = new Timer(40, e -> repaint())` とコンストラクタでの `start()`）と、`EditorCanvas` を生成するのに成功時 `System.exit(0)` を呼ばないテストクラス群（§0 のベースライン表で「ハング」となったもの。例: `CompileAnalyzerTest`）
- **問題種別**: テストしにくい構造 / 障害リスク
- **何が問題か**: `javax.swing.Timer` は TimerQueue から AWT-EventQueue（非デーモンスレッド）へ 40ms ごとにイベントを送り続けるため、`EditorCanvas` を一度でも生成したプロセスは main が正常終了しても JVM が終了しない。`scripts/test.sh` は各テストの終了を待つ設計なので、該当クラスで永久に停止する（計画作成環境で実測。スレッドダンプで AWT-EventQueue-0 / TimerQueue / AWT-Shutdown の残存を確認）。
- **なぜ問題か**: (障害リスク) CI 相当の一括検証手段が失われており、**本計画のすべての Item の「完了条件」が検証不能**になる。(保守性) テストは全部 PASS を表示しており、成功と停止の区別がつかないため退行に気づけない。**実際に、ハング地点より後ろにある `ScrollTest` の失敗2件（P-10 の具体的な発現）がこのハングに隠されて観測されていなかった。**
- **想定される改善方針**: 成功パスの末尾で `System.exit(0)` を明示する（Item 1）。既に `System.exit(0)` を持つテスト（`RobotKeyInputTest` 等）と同じ流儀に揃えるだけで、製品コードには触れない。`EditorCanvas` 側の対処（タイマー開始を `addNotify` まで遅延）は描画アニメの開始タイミングという観測可能挙動を変えるため行わない。
- **放置リスク**: 全 Item の検証不能。CI 導入時の永久ハング。

## P-01: 神クラス `ModalEditor`（3,517行）
- **対象箇所**: `src/dev/javatexteditor/editor/ModalEditor.java` 全体
- **問題種別**: 巨大クラス / 責務混在
- **何が問題か**: 10 モードのキー処理、カーソル・オフセット計算、yank/paste、検索、grep、`:cd` タブ補完、FILER、telescope、import 選択 UI、jdk-source 疑似バッファ、K/gr ナビゲーション、getter/setter 生成、診断ジャンプ、バッファ履歴、ファイル I/O、canvas 同期がすべて1クラスに同居。フィールドは約 70 個。
- **なぜ問題か**: (保守性) 1機能の修正で無関係な状態フィールドを壊すリスクが高い。(可読性) 状態の生存期間（どのモードでどのフィールドが有効か）がコードから読み取れない。(変更容易性) 新モード追加のたびに `processKey` の switch とフィールド群が肥大。(障害リスク) 疑似バッファ3系統（`bufferHistory`/`cdSaved*`/`saved*`）の相互作用はコメントで注意書きされるほど壊れやすい。
- **想定される改善方針**: 一括分割はせず、**外縁の純粋ロジックから段階的に切り出す**。第一段として getter/setter 生成（Item 13）。検索・疑似バッファ管理の切り出しは今回スコープ外（§7）。
- **放置リスク**: 機能追加のたびに退行バグ混入率が上がる。既にコメント上「〜というバグがあった」という記述が複数ある。

## P-02: 巨大メソッド `Main.main()`（約200行）＋無名 KeyEventDispatcher
- **対象箇所**: `Main.java:363-560`（うち dispatcher が 433-541）
- **問題種別**: 巨大関数 / テストしにくい構造
- **何が問題か**: フレーム構築・インデックス起動・キー捕捉（Ctrl+Shift+矢印、F2 ダイアログ、IME 委譲、KEY_TYPED 二重処理防止）・マウスリスナーが1メソッド内。`pressedHandled[0]` のフラグ受け渡しが読みにくい。
- **なぜ問題か**: (テスト容易性) キーディスパッチの規則（どのキーを IME に委譲するか）は複雑なのにテスト不可能な位置にある。(可読性) F2 の診断ダイアログ組み立て約40行が main の中に埋まっている。
- **想定される改善方針**: 今回は対象外（GUI 検証手段が乏しく、抽出しても検証がdiffレビューのみになるため §7 へ）。Main に触るのは重複除去の Item 8 に限定する。
- **放置リスク**: キー処理修正時に IME 委譲ルールを壊す（過去に KEY_TYPED 二重処理バグの対処歴がコメントに残っている）。

## P-03: バッファ差し替え時の状態リセット4行組の重複（8箇所）
- **対象箇所**: `ModalEditor.java` — `openCdCandidateBuffer`(1090-1093)、`restoreCdSavedBuffer`(1129-1132)、`newBuffer`(1621-1624)、`openTutorial`(1639-1642)、`loadFromFile` の2分岐(1654-1657, 1668-1671)、`restoreBuffer`(1707-1710)、`jumpBack`(2901-2904)
- **問題種別**: 重複
- **何が問題か**: `grepResults = null; fileNameResults = null; searchMatches = List.of(); currentMatchIdx = -1;` の同一4行が8箇所にコピーされている。さらに `openTelescopeSelection` 等には**この4行の一部だけ**を行う類似箇所があり、どれが意図的な差分か判別できない。
- **なぜ問題か**: (障害リスク) 新しい「結果リスト状態」を足すとき8箇所全部に追記しないと、特定経路でのみ古い結果が残るバグになる。実際 `inJdkSourceBuffer` のリセットは `jumpToGrepResult` にしかなく、経路により残留する。(可読性) 「バッファを差し替えたら何をリセットすべきか」という不変条件がコードのどこにも一元化されていない。
- **想定される改善方針**: 完全一致する8箇所のみ private ヘルパーに抽出（Item 5）。部分実施箇所は挙動差の可能性があるため**触らない**。
- **放置リスク**: 疑似バッファ間遷移で検索ハイライト・grep 結果が残留する退行。

## P-04: `StringJavaFileObject` の完全重複
- **対象箇所**: `CompileAnalyzer.java` 末尾と `SourceAnalyzer.java` 末尾の `private static final class StringJavaFileObject`（`toUri` の実装含めほぼ同一。差分はコメント1行のみ）
- **問題種別**: 重複
- **なぜ問題か**: (保守性) URI サニタイズ規則（`[<>"{}|\^\`\[\] ]` → `_`）を片方だけ直すと、`analyze` 系と `parse` 系でファイル名の扱いが食い違う。
- **想定される改善方針**: `analysis` パッケージ内の package-private クラスに統合（Item 3）。
- **放置リスク**: Windows パスや特殊文字ファイル名の不具合修正が片側にしか入らない。

## P-05: スキップ対象ディレクトリ集合の重複と不統一
- **対象箇所**: `FileNameSearcher.SKIP_DIRS` と `WordIndex.SKIP_DIRS`（同一の7要素）。`ProjectSearcher.preVisitDirectory` は `.git`/`build`/`target` の3つだけを文字列直値で判定（意図的かは**不明**）。
- **問題種別**: 重複 / 直値の散在
- **なぜ問題か**: (保守性) スキップ対象を増やす修正が3箇所に分散し、機能ごとに検索対象が食い違う（既に食い違っている: `\g` grep は node_modules を走査するが `\f` は走査しない）。
- **想定される改善方針**: 同一の2箇所のみ共通定数化（Item 4）。`ProjectSearcher` は**挙動が異なるため変更しない**（統一すると grep の検索結果件数が変わる＝仕様変更）。
- **放置リスク**: 大きな node_modules を含むプロジェクトで grep 系機能だけ極端に遅い、という不整合が温存される。

## P-06: `Main.setupCompileAnalysis` 内の解析ジョブ2連コピー
- **対象箇所**: `Main.java:186-212`（trigger）と `220-243`（onOrganizeImports）
- **問題種別**: 重複
- **何が問題か**: 「仮想スレッドで `JDK_INDEX.awaitReady()` → `COMPILE_ANALYZER.analyze` → EDT で `setDiagnostics` + `setOnImportComplete` + `handleAutoImport`、失敗時は診断クリア＋メッセージ」がほぼ同一の2ブロック。差分は (a) trigger 側のみ保存済みパスがあれば `analyzeWithPath` を使う、(b) ステータス文言、の2点のみ。
- **なぜ問題か**: (障害リスク) 片方だけに入った修正（実際に `analyzeWithPath` 対応は trigger 側にしか入っていない。organize-imports 側が意図的に `analyze` のままなのかは**不明**）が仕様なのかバグなのか後から判別できない。
- **想定される改善方針**: 差分をパラメータ化した private ヘルパーに抽出。**`analyzeWithPath` の適用範囲は現状のまま**とし、差分は引数で明示する（Item 8）。
- **放置リスク**: エラーメッセージやスレッド処理の修正漏れ。

## P-07: 補完トリガ/再クエリの2系統コピー
- **対象箇所**: `ModalEditor.java` — `triggerCompletion`(659-682) / `triggerWordCompletion`(685-707)、`recheckCompletion`(744-766) / `recheckWordCompletion`(768-788)
- **問題種別**: 重複
- **何が問題か**: 「prefix 抽出 → クエリ → 空なら dismiss → フィールド5個更新 → `syncCompletionCanvas()`」が4メソッドにコピーされている。ただし ready 判定の仕方が微妙に違う（`recheckWordCompletion` は `isReady()` を見ない等）。
- **なぜ問題か**: (保守性) 補完 UI の挙動修正（例: 候補件数変更）が4箇所同時修正になる。(可読性) 微妙な差分が意図的か事故か読み取れない。
- **想定される改善方針**: フィールド更新部分（共通の末尾5行）だけを `activateCompletion(prefix, items, wordMode)` に抽出し、**ready 判定・メッセージの差分は各メソッドに残す**（Item 6）。
- **放置リスク**: 片系統だけ候補選択インデックスがリセットされない類の非対称バグ。

## P-08: import 行スキャン削除ループの重複
- **対象箇所**: `AutoImportHandler.removeImport()` と `removeUnusedImports()` 内のループ（どちらも「行を split → `import <fqn>;` に一致する行のオフセットを求めて `buffer.delete(offset, len+1)`」）
- **問題種別**: 重複
- **なぜ問題か**: (障害リスク) 行末に空白がある import 行やタブインデントの扱いを直すとき、片方だけ直すと `:remove-import` と `:oi` で挙動が食い違う。
- **想定される改善方針**: スキャン＋削除だけを private ヘルパー化。`ensureBlankLineAfterImports` の呼び出しタイミング差（removeImport は削除の都度、removeUnusedImports は最後に1回）は**現状維持**（Item 7）。
- **放置リスク**: 上記の非対称バグ。

## P-09: 「コード位置から親を遡って lib/scripts を探す」ロジックの4重実装
- **対象箇所**: `Main.resolveLibDir()`(633-650)、`Main.resolveScriptDir()`(652-667)、`OpenjdkSourceTracer.findBundledSrcZip()`、`OpenjdkSourceTracer.findNativeSrcDir()`
- **問題種別**: 重複
- **何が問題か**: `getProtectionDomain().getCodeSource().getLocation()` から最大4階層親を遡って `lib/`・`scripts/`・`lib/src.zip`・`lib/openjdk-native` を探す同型ロジックが4つ。細部（ファイル/ディレクトリ判定、fallback）だけ違う。
- **なぜ問題か**: (保守性) 配布形態（jar 化等）を変えたとき4箇所の修正が必要。(可読性) 「アプリのインストールルートはどこか」という概念が名前を持っていない。
- **想定される改善方針**: 「code source から上方向に relative パスを探す」1関数に集約し、各呼び出し元は自分固有の fallback を保持（Item 12）。
- **放置リスク**: 探索深度や判定条件の修正漏れによる、環境依存の「src.zip が見つからない」不具合。

## P-10: Ctrl+U / Ctrl+P の二重定義（レジストリ側が到達不能）
- **対象箇所**: `ModalEditor.processNormalKey():307-323`（ハードコードで `bufferHistory` を操作）と `KeymapRegistry:123-124`（同キーを `buffer.prev`/`buffer.next` に束縛）＋ `ModalEditor:525-526` の case ＋ `switchToRelativeBuffer():1302-1335`
- **問題種別**: デッドコード / 依存方向の不整合（レジストリを迂回するハードコード）
- **何が問題か**: `processNormalKey` は keymap 解決の**前に** Ctrl+U/P を横取りして `restoreBuffer`（スナップショット履歴）を実行するため、レジストリの `buffer.prev`/`buffer.next` → `switchToRelativeBuffer`（レジストリ由来のバッファ一覧巡回）には既定キーから到達できない。2つの異なる「バッファ切替」実装が並存している。
- **なぜ問題か**: (可読性) Ctrl+U の実挙動をレジストリから読むと誤読する。(障害リスク) ただし `switchToRelativeBuffer` はプラグインが別キーに `buffer.prev` を束縛すれば到達**可能**なため、安易に消すと外部契約（`KeymapRegistry.registerAction`/`bind` 経由の拡張）を壊しうる。**さらにこの再割当ては `ScrollTest` の半ページ上スクロール期待（2ケース）を既に壊しており、P-23 のハングに隠れて未観測だった**（§0 ベースライン・U-7 参照）。
- **想定される改善方針**: **コードは変更しない**。CLAUDE.md に既知の二重定義として記録し、どちらを正とするか（テスト更新 or キー割当て復帰）の設計判断をユーザーに委ねる（Item 14・U-7）。
- **放置リスク**: 次にバッファ切替を触る開発者が片方だけ修正する。ScrollTest の失敗が恒常化しテスト全体の信頼性が下がる。

## P-11: COMMAND モードのレジストリ束縛が未使用
- **対象箇所**: `KeymapRegistry:176-179`（COMMAND モードの `enter.normal`/`execute.command` 束縛）。`ModalEditor.processCommandKey():980-1001` は registry を一切参照せずハードコードで ESC/Enter/TAB を処理。
- **問題種別**: デッドコード
- **なぜ問題か**: (可読性) COMMAND モードのキーカスタマイズが可能に見えるが実際は不可能。
- **想定される改善方針**: U-4 のとおり外部参照の想定が不明なため**削除しない**。Item 14 で記録のみ。
- **放置リスク**: プラグイン作者が COMMAND モードに bind して「効かない」と混乱する。

## P-12: `CompletionIndex.refreshProjectSymbols()` が未使用＋潜在データ競合
- **対象箇所**: `CompletionIndex.java:122-124`
- **問題種別**: デッドコード / 障害リスク（潜在）
- **何が問題か**: 本番・テストとも呼び出しゼロ。かつ、もし呼ばれると `ready==true` の後にバックグラウンド仮想スレッドが `TreeMap` を更新し、EDT の `query()`（`index.values()` 走査）と同期なしに競合する。
- **なぜ問題か**: (障害リスク) 将来「保存時に索引更新」を実装する人がこのメソッドをそのまま呼ぶと、再現困難な `ConcurrentModificationException` / 破損読み取りを踏む。
- **想定される改善方針**: U-5 のとおり削除はしない。Item 14 で「呼ぶ前に同期設計が必要」と記録。
- **放置リスク**: 上記の時限バグ。

## P-13: `extension/` パッケージが本番コードから未接続
- **対象箇所**: `extension/PluginLoader.java`・`SimpleEditorContext.java`・`EditorPlugin.java`（参照元は同パッケージとテストのみ。`executeCommand` に `:plugin` 系コマンドは存在しない）
- **問題種別**: デッドコード（未配線の完成機能）
- **なぜ問題か**: (可読性) ロードマップ上「✅ 完了」だが起動経路がない。読み手がプラグイン機構の有効性を誤解する。
- **想定される改善方針**: 接続は機能追加なので**今回は禁止**。Item 14 で現状を記録。
- **放置リスク**: 実質未使用コードの保守コスト。

## P-14: `ModalEditor` の未使用 import
- **対象箇所**: `ModalEditor.java:11` `import dev.javatexteditor.buffer.PieceTable;`（コード中で `PieceTable` を単純名参照している箇所はコメントのみ）
- **問題種別**: デッドコード
- **なぜ問題か**: (可読性) 依存関係の実態を誤認させる（微小）。
- **想定される改善方針**: 削除（Item 2）。
- **放置リスク**: 実害はほぼないが、依存グラフ調査時のノイズ。

## P-15: `getLines()` による全文 split の多用（性能と方針の矛盾）
- **対象箇所**: `ModalEditor.getLines()`(1977-1979)。呼び出し44箇所。加えて `syncCanvas()`(2504-2573) が毎キー入力で `buffer.getText()` を2回呼び全文を canvas へ push。
- **問題種別**: テストしにくい構造 / 性能（CLAUDE.md の「文書全体の再構築をループ内で行わない」方針との矛盾）
- **何が問題か**: 1キー入力ごとに O(文書長) の文字列結合＋split が複数回走る。数十万行想定と矛盾。`PieceTable.getTextInRange`/`offsetOfLine` という部分取得 API は存在するのに `ModalEditor` はほぼ使っていない。
- **なぜ問題か**: (変更容易性) 行キャッシュを後付けするには 44 箇所の呼び出し規約（「毎回最新を取る」前提）を洗う必要があり、放置するほど困難になる。(性能) 大ファイルで入力遅延。
- **想定される改善方針**: **今回はスコープ外**（挙動等価性の検証が重く、破壊半径が全機能に及ぶ）。将来課題として §7 に明記。既存の `LargeFileTest` が計測の土台になる。
- **放置リスク**: 大ファイルでの実用性が出ない。

## P-16: `EditorCanvas.paintComponent` 内で全文 split を複数回実行
- **対象箇所**: `EditorCanvas.java` — `paintComponent`(345, 351, 356 で計3回 `text.split`)、さらに `drawGutter`(610-611) と `drawCompletionPopup`(488-489) が内部で再 split
- **問題種別**: 重複 / 性能
- **なぜ問題か**: (性能) 1回の再描画（アニメタイマーで 40ms ごと）につき最大5回の全文 split。(保守性) 行配列の受け渡しが統一されていないため、描画メソッド追加のたびに再 split が増える。
- **想定される改善方針**: `paintComponent` 冒頭で1回だけ split し、各 draw メソッドに引数で渡す（挙動不変、Item 9）。
- **放置リスク**: 大ファイル表示時の描画負荷増。

## P-17: マジックナンバーの散在
- **対象箇所**（代表): `ModalEditor` — タブ幅 4（`insertTab`:851-860、`insertNewlineWithIndent`:920、`insertCloseBrace`:894）、補完候補件数 10（671, 720, 756, 778 の4箇所）、FILER プレビュー行数 20/30（2480, 2492）。`EditorCanvas` — セル幅範囲 5〜40 / 高さ範囲 8〜80（188, 196）。`TelescopePicker.MAX_RESULTS = 200`・`WordIndex` 2MB・picker 群 1MB は定数化済みの良例。
- **問題種別**: 直値の散在
- **なぜ問題か**: (変更容易性) 「タブ幅を設定可能にする」等の将来変更時に取りこぼす。特にタブ幅 4 は3箇所が独立していて、1箇所だけ変えるとインデントが崩れる。
- **想定される改善方針**: 同一意味の直値のみ private static final 定数化（Item 10）。意味の異なる数値（プレビュー行数など）は無理にまとめない。
- **放置リスク**: 設定化・変更時の取りこぼしバグ。

## P-18: 文字列による型表現（stringly-typed）
- **対象箇所**: `ModalEditor.yankType`（`"char"`/`"line"`、フィールド定義 70 行目、使用 2091・2100 ほか）。`CompletionItem.kind`（`"cls"/"mth"/"fld"/"wd"`）。
- **問題種別**: 命名不統一 / 障害リスク
- **なぜ問題か**: (障害リスク) タイプミス（`"Line"` 等）がコンパイル時に検出されない。(可読性) 取りうる値の全集合が宣言に現れない。
- **想定される改善方針**: `yankType` は private enum 化し、テストが依存する `getYankType()` の**文字列戻り値は維持**（Item 11）。`CompletionItem.kind` は `EditorCanvas` の描画とテストが文字列前提で、変換層が増えるだけなので**今回は変更しない**（§7）。
- **放置リスク**: 新しい yank 種別追加時の分岐漏れ。

## P-19: 広域 `catch (Exception ignored)` とエラー処理の欠落
- **対象箇所**（代表): `Main.detectMouseScreen`(173)、`Main.resolveLibDir/resolveScriptDir`(648, 665)、`ModalEditor.changeDirectory`(2392: `catch (Exception ex)`)、`EditorCanvas.switchToHalfWidth`(117)、`OpenjdkSourceTracer` 内の `catch (Exception | Error ignored)` 複数。また `ModalEditor.saveToFile` は一時ファイル経由でない直接上書きで、書き込み途中失敗時に元ファイルが破損しうる。
- **問題種別**: エラー処理の欠落
- **なぜ問題か**: (障害リスク) graceful degradation 自体は本プロジェクトの明示方針だが、**何が握りつぶされたか痕跡が残らない**ため、環境起因の不具合（src.zip 不検出等）の調査コストが高い。`saveToFile` は唯一ユーザーデータを失いうる箇所。
- **想定される改善方針**: 今回は挙動を変えないため握りつぶし自体は温存。`saveToFile` の安全化（テンポラリ→ATOMIC_MOVE）は**書き込み観測挙動が変わりうる**（別ボリューム等で例外種別が変わる）ため、問題として記録し §7 で「今回やらない・要ユーザー判断」に置く。
- **放置リスク**: 保存中のクラッシュ・ディスクフルでファイル消失。

## P-20: 命名・表記の不統一
- **対象箇所**（代表): `ModalEditor.lookupJdkDoc()`（実態は「定義ジャンプ」であり Javadoc 表示は最後のフォールバックのみ）。`Main`・`ModalEditor` での FQN インライン参照（`dev.javatexteditor.analysis.CompletionIndex` 等）と import の混在。ステータスメッセージの日英混在（`"E: no file name"` と `"診断なし"`）。
- **問題種別**: 命名不統一
- **なぜ問題か**: (可読性) `lookupJdkDoc` は grep で「定義ジャンプ」実装を探すときに見つからない。FQN 混在は依存把握を妨げる。
- **想定される改善方針**: いずれも実害が小さく、リネームは diff ノイズが大きい。**今回は問題の記録のみ**とし、メッセージ言語統一・一括リネームは §7 で禁止（ユーザーの方針決定が先）。
- **放置リスク**: 軽微（調査コスト増）。

## P-21: 疑似バッファ退避状態の二重実装
- **対象箇所**: `ModalEditor` — `cdSaved*` 6フィールド(139-144) と `savedBufferText`/`savedFilePath`/`savedCursorRow`/`savedCursorCol`(152-155)。同型の「退避→復元」を別々のフィールド群で実装。既に `BufferSnapshot` record(174) が存在するのに使われていない。
- **問題種別**: 重複 / 責務混在
- **なぜ問題か**: (障害リスク) jdk-source 閲覧中に `:cd` の TAB 補完を開くなど、2系統が重なったときの整合性が未定義（CLAUDE.md にも相互作用の記述なし。テストでの検証有無は**不明**）。
- **想定される改善方針**: `BufferSnapshot` ベースの共通退避機構への統合が本筋だが、復元時の付帯処理（mode 遷移先・commandBuffer 復元）が系統ごとに異なり、挙動等価の立証が重い。**今回はスコープ外**（§7）。Item 14 で相互作用が未定義であることを記録。
- **放置リスク**: 2系統が重なる操作順での状態破損。

## P-22: `PieceTable` の境界値バリデーション欠落
- **対象箇所**: `PieceTable.insert()`(20-45)・`delete()`(47-76) — 負のオフセット・範囲超過に対する検証がない。
- **問題種別**: エラー処理の欠落
- **なぜ問題か**: (障害リスク) 呼び出し側（`ModalEditor` のオフセット計算）のバグが静かに飲み込まれ、文書破損として遠くで発現する。
- **想定される改善方針**: `IllegalArgumentException` を投げる事前条件検証の追加は**呼び出し側に潜む既存の境界バグを顕在化させる（挙動変更）**リスクがあるため、今回は追加しない。§7 に「要・事前調査」として記録。
- **放置リスク**: 文書破損バグの原因調査が困難。

---

# 4. 安全網の構築（必ず項目0）

## Item 0: 安全網の構築（最初に実行）

### 0-1. ブランチ方針・作業前コミット手順

```bash
cd /path/to/JavaTextEditor
git status --short          # 出力なし（クリーン）であることを確認。差分があれば作業を中断して報告
git fetch origin main
git log --oneline -1        # 起点コミットを記録する（本計画の前提は b6d353e）
git checkout -b refactor/plan-items origin/main   # リファクタ作業用ブランチを main から作成
```

- **1項目 = 1コミット**。コミット前に必ず「確認コマンド」を全部通すこと。
- コミットメッセージは各 Item の「想定コミットメッセージ」を使う（既存リポジトリの慣例に合わせ日本語）。
- 途中で想定外の差分・テスト失敗が出たら `git restore .` で戻し、中断して報告する。

### 0-2. ベースライン確認方法（ビルド / テスト）

lint・型チェックツールは本プロジェクトに存在しない（導入も禁止）。javac のコンパイル成功とテストハーネスが唯一の自動検証手段である。

```bash
./scripts/build.sh    # 期待: "Build OK"
```

**テスト実行の注意（P-23）**: `./scripts/test.sh` は `EditorCanvas` を生成するテストクラスで JVM が終了せず停止する（§0 の実測。U-1 で自環境の再現を確認すること）。**Item 1 を完了するまでは**、次の per-class ランナーでベースラインを取る（このスクリプトはリポジトリ外 `/tmp` に置き、リポジトリにはコミットしない）:

```bash
# 事前に一度テストをコンパイルしておく
./scripts/build.sh
find test -name "*.java" | xargs javac -encoding UTF-8 -cp build -d build

cat > /tmp/run_tests_baseline.sh <<'EOF'
#!/bin/bash
# 各 *Test クラスを個別 JVM で実行し、出力が30秒間増えないか300秒経過で kill する。
# 判定: rc=0 → PASS / kill された場合はログ末尾の PASS/FAIL 表示を目視確認（全テストは失敗時に
#       exit(1) する設計なので、main が正常に戻って kill されたなら「PASS 後の JVM ハング」である）
cd "$(git rev-parse --show-toplevel)"
for classfile in $(find build -name "*Test.class" ! -name '*$*' | sort); do
  classname=$(echo "$classfile" | sed 's|build/||; s|/|.|g; s|\.class$||')
  out="/tmp/testlogs_$classname.log"
  java -cp build "$classname" > "$out" 2>&1 &
  pid=$!; start=$(date +%s); last=-1; stable=0; killed=0
  while kill -0 $pid 2>/dev/null; do
    sleep 5
    size=$(stat -c %s "$out" 2>/dev/null || echo 0)
    if [ "$size" -eq "$last" ]; then stable=$((stable+5)); else stable=0; last=$size; fi
    if [ $stable -ge 30 ] || [ $(( $(date +%s) - start )) -ge 300 ]; then
      kill -9 $pid 2>/dev/null; killed=1; break
    fi
  done
  wait $pid 2>/dev/null
  echo "$classname rc=$? killed=$killed  tail: $(tail -1 "$out")"
done
EOF
bash /tmp/run_tests_baseline.sh | tee /tmp/test_baseline.txt
```

- **必ず自分の環境での結果（クラスごとの PASS/FAIL と killed の有無）を控えること**（`lib/src.zip` の有無・DISPLAY の有無で SKIP 数が変わるため、他人の数値ではなく自分のベースラインと比較する。本計画作成環境での結果は §0 参照）。
- **Item 1 完了後は素の `./scripts/test.sh` が完走するようになる**ので、以降の Item の検証は `./scripts/test.sh` で行う（各 Item の「確認コマンド」はそれを前提に書いてある）。
- **既知の失敗**: `ScrollTest` はベースライン時点で 18/20（2件 FAIL、U-7）。したがって Item 1 完了後の `./scripts/test.sh` の期待値は「**36 class(es) passed, 1 class(es) failed**（failed は ScrollTest のみ・FAIL 2件のみ）」であり、**exit code は 1 になる**。以降の全 Item で「テストがベースラインと同一」とは、この Summary とまったく同じ数字・同じ失敗内容であることを指す。**新たな FAIL が1件でも増えたらその Item は失敗**として戻すこと。
- 単一テストの再実行: `java -cp build dev.javatexteditor.editor.ModalEditorTest` のようにクラス名指定で個別実行できる。

### 0-3. 既存テストの分布（何が守られているか）

- buffer: `PieceTableTest`・`PieceTableEdgeCaseTest`・`UndoablePieceTableTest`・`UndoRedoDeepTest`
- editor: `ModalEditorTest`（1593行・yank/paste/getter生成等）・`ModalEditorEdgeCaseTest`・`KeymapRegistryTest`・`ImportSelectTest`・`WordCompletionTest`・`ScrollTest`・`JumpBackTest` ほか
- analysis: 各インデックス・アナライザに対応するテストが一対一で存在
- search/telescope/refactor/tutorial: 機能単位のテストあり
- **カバレッジ空白**: `Main.java`（配線・ペインツリー・キーディスパッチ）、`EditorCanvas` の描画結果そのもの（設定 setter の状態遷移は `EditorCanvasTest` にあり）、`WorkingDirectoryManager`、`Theme`

### 4-4. 特性テスト仕様（テストがない箇所に触れる Item 8 の前に作成する）

Item 8 は `Main.java`（テストなし）に触れるため、以下の特性テストを**Item 8 の実施前に**追加する。それ以外の Item は既存テストで検証可能。

**特性テスト: `test/dev/javatexteditor/editor/CompileTriggerCallbackTest.java`（新規）**

- **対象機能**: `ModalEditor` のコールバック発火規約（`Main.setupCompileAnalysis` が依存している契約）。Main 自体は GUI・static 依存でテスト不能なため、Main が依存する側の契約を固定する。
- **前提条件**: DISPLAY 不要。`new ModalEditor("...")`（canvas なしコンストラクタ）を使用。
- **入力 / 実行手順 / 期待挙動**:
  1. `setOnReturnToNormal(counter++)` を登録 → `processKey('i')` で INSERT へ → `processKey(ESC)` → **期待: counter == 1**（INSERT→NORMAL 遷移で1回発火）
  2. `setOnSave(counter2++)` を登録 → 一時ファイルパスで `ModalEditor(text, tmpPath, null)` を作り `:w` を実行（`processKey(';')` → `w` → Enter）→ **期待: counter2 == 1、一時ファイルの内容がバッファと一致**
  3. 保存先なし（`currentFilePath == null`）で `:w` → **期待: counter2 は増えない、`getStatusMessage()` が `"E: no file name"`**
  4. `setOnOrganizeImports(counter3++)` を登録 → NORMAL で Ctrl+Shift+O（`processKey(KeyEvent.VK_O, KeyEvent.CHAR_UNDEFINED, CTRL_DOWN_MASK|SHIFT_DOWN_MASK)`）→ **期待: counter3 == 1**
- **副作用 / 永続化 / 例外の観測点**: 一時ファイルは `Files.createTempFile` で作り、テスト末尾で削除。例外は握りつぶさず main を非0終了させる。**EditorCanvas は生成しない**（canvas 無しなら AWT が起動せずハングしない）が、流儀統一のため main 末尾に `System.exit(fail > 0 ? 1 : 0)` を書く。
- **テストコード様式**: 既存 `ModalEditorTest` と同じ「`public static void main` + pass/fail カウント + 失敗時 exit 1」で書く。JUnit は使用禁止。

---

# 5. 作業項目リスト（実行順）

> 共通ルール: 各 Item とも、実施前に `git status --short` がクリーンであること。確認コマンドの `./scripts/test.sh` は Item 1 完了後に完走するようになる前提で書いてある（Item 1 実施前は §4 0-2 のランナーで代替する）。
>
> **「期待結果: failed 0」の読み替え**: ベースラインに既知の失敗（ScrollTest 18/20、U-7）が含まれるため、各 Item の「期待結果: failed 0」は正確には「**Item 0 で記録した自分のベースラインと同一の結果**（本計画作成環境では `Summary: 36 class(es) passed, 1 class(es) failed`、failed は ScrollTest の既知2件のみ）であり、**新たな失敗が1件もないこと**」と読み替える。

---

## Item 1: テストハーネスの完走性回復（成功時 System.exit(0) の明示）
- **対象箇所**: `EditorCanvas` を生成し、かつ成功パスで `System.exit(0)` を呼ばないテストクラス群。**Item 0 のベースライン計測で `killed=1` になったクラスがそのまま対象リストである**。計画作成環境での実測では次の2クラス: `test/dev/javatexteditor/analysis/CompileAnalyzerTest.java`、`test/dev/javatexteditor/ui/EditorCanvasTest.java`（実行者の環境で増えていればそれも対象に含める）。
- **目的**: `./scripts/test.sh` を完走可能にし、以降の全 Item の検証手段を回復する（P-23）
- **問題**: `EditorCanvas` のコンストラクタが `javax.swing.Timer` を start するため、生成したテストは main 正常終了後も AWT 非デーモンスレッドで JVM が終了しない。
- **変更内容**: 対象テストクラスの `main` メソッド**末尾**（成功サマリ出力の後）に `System.exit(0);` を1行追加する。**テストの検証内容・期待値・失敗時の exit(1) は一切変更しない**。既に `System.exit(0)` を持つテスト（`RobotKeyInputTest` 等）と同じ流儀に揃えるだけである。**製品コード（`EditorCanvas`）は変更しない**（タイマー開始を遅らせるとアニメ開始タイミングという観測可能挙動が変わるため）。
- **変更前後のコードスケッチ**（例: `CompileAnalyzerTest`）:
```java
// 前（main 末尾）
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) {
            System.exit(1);
        }
    }
// 後
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) {
            System.exit(1);
        }
        System.exit(0);   // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
    }
```
- **実施手順**: (1) Item 0 の計測で `killed=1` だったクラスを列挙 (2) 各クラスの main 末尾に上記1行（コメント含む）を追加 (3) 再コンパイル (4) `./scripts/test.sh` を実行し**完走すること**を確認
- **完了条件**: `./scripts/test.sh` が停止せず最後まで走り、Summary 行を表示する。本計画作成環境での期待値は `Summary: 36 class(es) passed, 1 class(es) failed`（failed は ScrollTest の既知2件のみ。§0・U-7）。**ScrollTest の失敗はこの Item で直さない**（仕様判断のため。§7 参照）。
- **確認コマンド**: `time ./scripts/test.sh`（完走時間も記録しておく）
- **期待結果**: ハングせず終了し、失敗クラスが ScrollTest のみ（exit code は 1 だが、これはベースライン既知の失敗による想定内の値）
- **リスク**: 低。`System.exit(0)` は「テスト成功として終了」という test.sh の期待そのもの。唯一の注意は、main の途中 return がある場合に末尾へ到達しない可能性 → 対象クラスの main に早期 return がないか目視確認する（`RobotKeyInputTest` 型の SKIP 早期 return を持つクラスは、その return 前に exit(0) 相当があるか確認する）。
- **失敗時の戻し方**: `git restore test/`
- **依存**: Item 0（対象リストの確定に計測結果が必要）
- **想定コミットメッセージ**: `EditorCanvas 生成テストが main 終了後も JVM が残り test.sh が停止する問題を修正する（成功時 System.exit(0) を明示）`

---

## Item 2: ModalEditor の未使用 import 削除
- **対象箇所**: `src/dev/javatexteditor/editor/ModalEditor.java:11`
- **目的**: 依存関係の実態を import 文に正しく反映する（P-14）
- **問題**: `import dev.javatexteditor.buffer.PieceTable;` はコード中で単純名 `PieceTable` を参照する箇所がなく（コメント内言及のみ）、不要。
- **変更内容**: 該当 import 行を1行削除する。他の行は変更しない。
- **変更前後のコードスケッチ**:
```java
// 前
import dev.javatexteditor.buffer.PieceTable;
import dev.javatexteditor.buffer.UndoablePieceTable;
// 後
import dev.javatexteditor.buffer.UndoablePieceTable;
```
- **実施手順**: (1) 行を削除 (2) ビルド (3) 全テスト
- **完了条件**: ビルド成功・全テストがベースラインと同一結果
- **確認コマンド**: `./scripts/build.sh && ./scripts/test.sh`
- **期待結果**: `Build OK`、failed 0
- **リスク**: ほぼゼロ。もしビルドエラーになる場合は単純名参照が存在するということなので、削除を取りやめて報告する。
- **失敗時の戻し方**: `git restore src/dev/javatexteditor/editor/ModalEditor.java`
- **依存**: Item 1（test.sh での検証を可能にするため）
- **想定コミットメッセージ**: `ModalEditor の未使用 import (PieceTable) を削除する`

---

## Item 3: StringJavaFileObject の共通クラス化
- **対象箇所**: `src/dev/javatexteditor/analysis/CompileAnalyzer.java`（内部クラス `StringJavaFileObject`）、`src/dev/javatexteditor/analysis/SourceAnalyzer.java`（同名内部クラス）
- **目的**: 完全重複した文字列ソースアダプタを1箇所にする（P-04）
- **問題**: 同一実装（URI サニタイズ含む）が2ファイルにコピーされている。
- **変更内容**: `src/dev/javatexteditor/analysis/StringJavaFileObject.java` を **package-private トップレベルクラス**として新設し、両クラスの内部クラス定義を削除して参照を差し替える。public にはしない（analysis パッケージ外からの利用実績がないため公開 API を増やさない）。
- **変更前後のコードスケッチ**:
```java
// 新規: src/dev/javatexteditor/analysis/StringJavaFileObject.java
package dev.javatexteditor.analysis;

import javax.tools.SimpleJavaFileObject;
import java.net.URI;

/** 文字列ソースを JavaFileObject として渡すためのアダプタ（CompileAnalyzer / SourceAnalyzer 共用）。 */
final class StringJavaFileObject extends SimpleJavaFileObject {
    private final String source;

    StringJavaFileObject(String filePath, String source) {
        super(toUri(filePath), Kind.SOURCE);
        this.source = source;
    }

    private static URI toUri(String filePath) {
        // URI パスセグメントとして不正な文字を除去する
        String safe = filePath.replace('\\', '/')
                              .replaceAll("[<>\"{}|\\\\^`\\[\\] ]", "_");
        return URI.create("string:///" + safe);
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return source;
    }
}
```
両ファイルからは `private static final class StringJavaFileObject … }` のブロックを丸ごと削除するのみ。呼び出し箇所（`new StringJavaFileObject(filePath, sourceCode)`）は変更不要。
- **実施手順**: (1) 新ファイル作成（既存2箇所の実装と**一字一句同じロジック**をコピーする。正規表現文字列を書き直さないこと） (2) 2ファイルから内部クラス削除 (3) ビルド (4) 全テスト
- **完了条件**: ビルド成功、全テストがベースラインと同一結果
- **確認コマンド**: `./scripts/build.sh && ./scripts/test.sh`、追加で `java -cp build dev.javatexteditor.analysis.SourceAnalyzerTest && java -cp build dev.javatexteditor.analysis.CompileAnalyzerTest`
- **期待結果**: failed 0
- **リスク**: 低。正規表現のコピーミスだけ注意（テストが URI 起因で落ちれば検出可能）。
- **失敗時の戻し方**: `git restore src/dev/javatexteditor/analysis/ && rm src/dev/javatexteditor/analysis/StringJavaFileObject.java`
- **依存**: Item 1
- **想定コミットメッセージ**: `CompileAnalyzer/SourceAnalyzer で重複していた StringJavaFileObject を共通クラスに抽出する`

---

## Item 4: SKIP_DIRS の共通定数化（同一の2箇所のみ）
- **対象箇所**: `src/dev/javatexteditor/search/FileNameSearcher.java`（`SKIP_DIRS`）、`src/dev/javatexteditor/analysis/WordIndex.java`（`SKIP_DIRS`）
- **目的**: 同一内容のスキップ対象集合を1定義にする（P-05）
- **問題**: `.git, build, target, .gradle, node_modules, .idea, .vscode` の同一 Set が2箇所に定義されている。
- **変更内容**: `FileNameSearcher.SKIP_DIRS` を `public static final` に昇格し Javadoc を付与。`WordIndex` は自前定義を削除して `FileNameSearcher.SKIP_DIRS` を参照する（`analysis → search` の依存は `ProjectSymbolResolver` で既存のため方向違反にならない）。**`ProjectSearcher` の3ディレクトリ直値判定は変更しない**（スキップ集合が異なるため、揃えると `:grep`・`\g`・`gr`・`:rename` の検索結果が変わる＝仕様変更になる）。
- **変更前後のコードスケッチ**:
```java
// FileNameSearcher.java（変更後）
/** ファイル走査系機能で共通に使う、慣例的なスキップ対象ディレクトリ名。
 *  注意: ProjectSearcher(grep) は意図的に .git/build/target のみをスキップしており、この集合は使わない。 */
public static final Set<String> SKIP_DIRS =
    Set.of(".git", "build", "target", ".gradle", "node_modules", ".idea", ".vscode");

// WordIndex.java（変更後）
import dev.javatexteditor.search.FileNameSearcher;
...
private static final Set<String> SKIP_DIRS = FileNameSearcher.SKIP_DIRS;  // 実体は search 側の1定義
```
- **実施手順**: (1) FileNameSearcher の修飾子変更＋Javadoc (2) WordIndex の定義差し替え (3) ビルド (4) 全テスト
- **完了条件**: ビルド成功、全テストがベースラインと同一
- **確認コマンド**: `./scripts/build.sh && ./scripts/test.sh`
- **期待結果**: failed 0
- **リスク**: 低。集合の内容は変えないので挙動不変。public 化により API 面が1つ増える点のみ（定数のためリスク小）。
- **失敗時の戻し方**: `git restore src/dev/javatexteditor/search/FileNameSearcher.java src/dev/javatexteditor/analysis/WordIndex.java`
- **依存**: Item 1
- **想定コミットメッセージ**: `FileNameSearcher と WordIndex で重複していた SKIP_DIRS を共通定数にする`

---

## Item 5: ModalEditor の検索/結果リスト状態リセットをヘルパー抽出
- **対象箇所**: `src/dev/javatexteditor/editor/ModalEditor.java` の8箇所（P-03 の行番号一覧参照）
- **目的**: バッファ差し替え時の不変条件を1メソッドに集約する（P-03）
- **問題**: 同一4行（`grepResults=null; fileNameResults=null; searchMatches=List.of(); currentMatchIdx=-1;`）が8箇所にコピーされている。
- **変更内容**: private メソッドを新設し、**4行が完全一致で並んでいる8箇所のみ**置換する。4行のうち一部しかない箇所（`openTelescopeSelection`・`switchToRelativeBuffer`・`executeFileNameSearch`・`executeGrep`・`executeRename`・`openJdkSourceBuffer` 等）は**挙動差の可能性があるため絶対に触らない**。
- **変更前後のコードスケッチ**:
```java
/** バッファを差し替える際に、旧バッファ由来の検索・結果リスト状態を破棄する。
 *  （grep結果 / ファイル名検索結果 / テキスト内検索マッチ）。
 *  注意: inJdkSourceBuffer / cdSelectionActive はここでは触らない（呼び出し元ごとに扱いが異なるため）。 */
private void resetSearchAndResultState() {
    grepResults = null;
    fileNameResults = null;
    searchMatches = List.of();
    currentMatchIdx = -1;
}
```
各対象箇所では該当4行を `resetSearchAndResultState();` の1行に置換。
- **実施手順**: (1) ヘルパー追加 (2) 8箇所を1箇所ずつ置換し、各置換ごとに周辺の行（`cursorRow` 等の代入）を巻き込んでいないか diff で確認 (3) ビルド (4) 全テスト
- **完了条件**: `git diff` の削除行が「同一4行 × 8箇所」＋追加行が「ヘルパー定義＋呼び出し8行」だけであること。全テストがベースラインと同一。
- **確認コマンド**: `git diff --stat`、`./scripts/build.sh && ./scripts/test.sh`、特に `java -cp build dev.javatexteditor.search.ProjectSearchTest && java -cp build dev.javatexteditor.search.FileSearchTest && java -cp build dev.javatexteditor.tutorial.TutorialTest && java -cp build dev.javatexteditor.editor.JumpBackTest`
- **期待結果**: failed 0
- **リスク**: 低〜中。置換対象の取り違え（部分一致箇所への誤適用）が唯一のリスク。P-03 の行番号一覧に**ない**箇所は変更しないこと。
- **失敗時の戻し方**: `git restore src/dev/javatexteditor/editor/ModalEditor.java`
- **依存**: Item 2（同一ファイルのため、先に済ませて diff を汚さない）
- **想定コミットメッセージ**: `バッファ差し替え時の検索・結果リスト状態リセットを resetSearchAndResultState() に集約する`

---

## Item 6: 補完候補の適用処理を activateCompletion() に共通化
- **対象箇所**: `ModalEditor.java` — `triggerCompletion`・`triggerWordCompletion`・`recheckCompletion`・`recheckWordCompletion`
- **目的**: 4メソッドに散った補完状態フィールド更新を1箇所にする（P-07）
- **問題**: `completionPrefix/completionItems/completionSelectedIdx/completionActive/completionIsWordMode` の更新＋`syncCompletionCanvas()` が4箇所にコピーされている。
- **変更内容**: フィールド更新部分**のみ**を抽出する。ready 判定・ステータスメッセージ・dismiss 条件は各メソッドに残す（微妙な差分が意図的である可能性を排除できないため、そこは動かさない）。
- **変更前後のコードスケッチ**:
```java
/** 補完候補リストを有効化して canvas に反映する（4つのトリガ/再クエリ経路の共通末尾処理）。 */
private void activateCompletion(String prefix,
        java.util.List<dev.javatexteditor.analysis.CompletionItem> items, boolean wordMode) {
    completionPrefix      = prefix;
    completionItems       = items;
    completionSelectedIdx = 0;
    completionActive      = true;
    completionIsWordMode  = wordMode;
    syncCompletionCanvas();
}
```
- `triggerCompletion` 末尾 → `activateCompletion(prefix, items, false);`
- `triggerWordCompletion` 末尾 → `activateCompletion(prefix, items, true);`
- `recheckCompletion` 末尾 → `activateCompletion(prefix, items, false);`（※現行の recheckCompletion は `completionIsWordMode` を**書いていない**が、このメソッドは冒頭の `if (completionIsWordMode) { recheckWordCompletion(); return; }` により wordMode=false の文脈でしか末尾に到達しないため、false を明示代入しても状態は等価）
- `recheckWordCompletion` 末尾 → `activateCompletion(prefix, items, true);`（※同様に、このメソッドに到達する時点で `completionIsWordMode==true`）
- **実施手順**: (1) ヘルパー追加 (2) 4箇所の末尾ブロックを置換 (3) ビルド (4) 全テスト（特に `WordCompletionTest`）
- **完了条件**: 全テストがベースラインと同一。`WordCompletionTest` の全ケース PASS。
- **確認コマンド**: `./scripts/build.sh && ./scripts/test.sh && java -cp build dev.javatexteditor.editor.WordCompletionTest`
- **期待結果**: failed 0
- **リスク**: 中。上記※の等価性論証が崩れるのは「`completionIsWordMode` が true のまま `recheckCompletion` の末尾に到達する」場合だが、冒頭ガードによりその経路は存在しない。
- **失敗時の戻し方**: `git restore src/dev/javatexteditor/editor/ModalEditor.java`
- **依存**: Item 5（同一ファイル。コンフリクト回避のため順番に実施）
- **想定コミットメッセージ**: `補完トリガ/再クエリ4経路の状態更新を activateCompletion() に共通化する`

---

## Item 7: AutoImportHandler の import 行削除ループを共通化
- **対象箇所**: `src/dev/javatexteditor/analysis/AutoImportHandler.java` — `removeImport()`・`removeUnusedImports()`
- **目的**: import 行のスキャン＋削除ロジックを1箇所にする（P-08）
- **問題**: 「`import <fqn>;` に `stripLeading().equals` で一致する行を探しオフセット計算して `buffer.delete(offset, len+1)`」がほぼ同一の2ループ。
- **変更内容**: 削除処理のみを private ヘルパーに抽出。`ensureBlankLineAfterImports` の呼び出し位置・回数は**現状のまま**にする（removeImport: 削除成功時に1回 / removeUnusedImports: ループ後に必ず1回、という差を維持）。
- **変更前後のコードスケッチ**:
```java
/** buffer から "import <fqn>;" に一致する最初の行を削除する。
 *  @return 削除したら true、見つからなければ false */
private static boolean deleteImportLine(String fqn, PieceTable buffer) {
    String importLine = "import " + fqn + ";";
    String[] lines = buffer.getText().split("\n", -1);
    int offset = 0;
    for (String line : lines) {
        if (line.stripLeading().equals(importLine)) {
            buffer.delete(offset, line.length() + 1); // +1 for '\n'
            return true;
        }
        offset += line.length() + 1;
    }
    return false;
}

// removeImport（変更後）
public boolean removeImport(String fqn, PieceTable buffer) {
    boolean removed = deleteImportLine(fqn, buffer);
    if (removed) ensureBlankLineAfterImports(buffer);
    return removed;
}

// removeUnusedImports（変更後・ループ本体のみ差し替え）
for (String fqn : unused) {
    if (deleteImportLine(fqn, buffer)) removed.add(fqn);
}
ensureBlankLineAfterImports(buffer);
```
（注意: 現行 `removeUnusedImports` は各 fqn につき `buffer.getText()` を取り直してからスキャンしている。ヘルパーも呼び出しごとに getText するため走査タイミングは等価。）
- **実施手順**: (1) ヘルパー追加 (2) 2メソッドを書き換え (3) ビルド (4) 全テスト（特に `AutoImportHandlerTest` の removeImport / removeUnusedImports セクション）
- **完了条件**: 全テストがベースラインと同一
- **確認コマンド**: `./scripts/build.sh && ./scripts/test.sh && java -cp build dev.javatexteditor.analysis.AutoImportHandlerTest`
- **期待結果**: failed 0
- **リスク**: 低。テストが `removeImport` の戻り値・結果文字列を直接検証している。
- **失敗時の戻し方**: `git restore src/dev/javatexteditor/analysis/AutoImportHandler.java`
- **依存**: Item 1
- **想定コミットメッセージ**: `AutoImportHandler の import 行削除ループを deleteImportLine() に共通化する`

---

## Item 8: Main.setupCompileAnalysis の解析ジョブ共通化
- **対象箇所**: `src/dev/javatexteditor/Main.java:185-244`（`setupCompileAnalysis`）
- **目的**: ほぼ同一の2つの仮想スレッド解析ブロックを1メソッドにする（P-06）
- **問題**: trigger 側と onOrganizeImports 側で「awaitReady → analyze → EDT反映 → 例外処理」が重複。差分は (a) analyzeWithPath の使用有無 (b) 失敗時メッセージ、のみ。
- **変更内容**: 差分を引数化した private static ヘルパーに抽出。**analyzeWithPath を使うのは現行どおり trigger 側だけ**とする（organize 側に広げるのは挙動変更なので行わない）。
- **変更前後のコードスケッチ**:
```java
/** バックグラウンド仮想スレッドでコンパイル解析し、EDT で診断反映と auto-import を行う。
 *  @param useRealPathIfSaved true のとき、保存済みファイルなら analyzeWithPath を使う（INSERT→NORMAL / 保存トリガ用）。
 *                            false のとき常に analyze を使う（Ctrl+Shift+O 用。現行挙動を維持）。
 *  @param failureMessage 解析失敗時にステータス行へ出す文言 */
private static void runCompileAnalysis(ModalEditor editor, EditorCanvas canvas,
        boolean useRealPathIfSaved, String failureMessage) {
    String source = editor.getText();
    String snapshotPath = editor.getCurrentFilePath();
    Thread.ofVirtual().start(() -> {
        try {
            JDK_INDEX.awaitReady();
            List<CompileDiagnostic> diags = (useRealPathIfSaved && snapshotPath != null)
                ? COMPILE_ANALYZER.analyzeWithPath(snapshotPath, source)
                : COMPILE_ANALYZER.analyze(source);
            SwingUtilities.invokeLater(() -> {
                canvas.setDiagnostics(diags);
                editor.setOnImportComplete(editor::organizeImportsRemoveUnused);
                editor.handleAutoImport(diags);
            });
        } catch (AnalysisException e) {
            SwingUtilities.invokeLater(() -> {
                canvas.setDiagnostics(List.of());
                editor.setStatusMessage(failureMessage);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    });
}
```
`setupCompileAnalysis` は次の形になる（`editor.setStatusMessage("auto-import: 解析中...")` / `"import 整理中..."` の事前表示は呼び出し側に残し、現行と同じタイミングで表示されることを維持する）:
```java
Runnable trigger = () -> {
    editor.setStatusMessage("auto-import: 解析中...");
    runCompileAnalysis(editor, canvas, true, "auto-import: 解析失敗");
};
editor.setOnReturnToNormal(() -> { canvas.switchToHalfWidth(); trigger.run(); });
editor.setOnSave(trigger);
editor.setOnOrganizeImports(() -> {
    editor.setStatusMessage("import 整理中...");
    runCompileAnalysis(editor, canvas, false, "E: コンパイル解析失敗");
});
```
（注意: 現行 organize 側はローカル変数 `filePath` を取るが未使用であり、ヘルパー化で自然に消える。）
- **実施手順**: (1) **先に §4-4 の特性テスト `CompileTriggerCallbackTest` を追加してベースラインで PASS させ、単独コミットする** (2) ヘルパー抽出 (3) ビルド (4) 全テスト＋特性テスト
- **完了条件**: 全テスト＋`CompileTriggerCallbackTest` PASS。`git diff` に上記以外の変更がない。
- **確認コマンド**: `./scripts/build.sh && ./scripts/test.sh && java -cp build dev.javatexteditor.editor.CompileTriggerCallbackTest`
- **期待結果**: failed 0
- **リスク**: 中。Main には自動テストがないため、抽出は**機械的に**行い、文言・分岐条件を1文字も変えないこと。可能なら DISPLAY のある環境で `./scripts/run.sh` を起動し、(a) INSERT で `List x;` と打って ESC → import 候補が出る、(b) Ctrl+Shift+O で「import 整理中...」が出る、の2点を目視確認する（不可能な環境なら diff レビューを二重に行い、その旨をコミットメッセージに書く）。
- **失敗時の戻し方**: `git restore src/dev/javatexteditor/Main.java`
- **依存**: Item 1、§4-4 の特性テスト（本 Item の1コミット目）
- **想定コミットメッセージ**: 1コミット目 `ModalEditor のコールバック発火規約を固定する特性テストを追加する` / 2コミット目 `Main の2つのコンパイル解析ジョブを runCompileAnalysis() に共通化する`

---

## Item 9: EditorCanvas paintComponent の行分割を1回に集約
- **対象箇所**: `src/dev/javatexteditor/ui/EditorCanvas.java` — `paintComponent`(313-390)、`drawGutter`(607-625)、`drawCompletionPopup`(469-537)
- **目的**: 1描画あたり最大5回走る全文 `text.split("\n", -1)` を1回にする（P-16）
- **問題**: `paintComponent` 内の 345/351/356 行で3回、`drawGutter` と `drawCompletionPopup` の内部で各1回、同じ split を実行している。40ms 周期のアニメ再描画でも毎回走る。
- **変更内容**: `paintComponent` で `String[] lines = text.split("\n", -1);` を1回だけ実行し、`drawSelectionHighlight`/`drawSearchHighlights`（既に引数で受けている）に加えて `drawGutter`・`drawCompletionPopup` にも引数で渡す。分割結果の意味・順序は一切変えない。
- **変更前後のコードスケッチ**:
```java
// paintComponent（変更後・抜粋）
String[] lines = text.split("\n", -1);   // ← この1回だけにする
...
if (visualMode && selAnchorRow >= 0) {
    drawSelectionHighlight(g2, lines, charWidth, lineHeight, scrollOffsetX, gutterWidth);
}
if (!searchHighlights.isEmpty()) {
    drawSearchHighlights(g2, lines, charWidth, lineHeight, scrollOffsetX, gutterWidth);
}
...
if (gutterWidth > 0) {
    drawGutter(g2, lines, charWidth, lineHeight, gutterWidth);   // シグネチャに lines を追加
}
...
if (completionActive && !completionLabels.isEmpty()) {
    drawCompletionPopup(g2, lines, charWidth, lineHeight, gutterWidth); // 同上
}

// drawGutter（変更後・冒頭のみ）
private void drawGutter(Graphics2D g2, String[] lines, int charWidth, int lineHeight, int gutterWidth) {
    int lastRow = Math.min(lines.length, scrollRow + visibleRows ...);  // text.split の再実行を削除
```
- **実施手順**: (1) paintComponent で lines を1回確保し3箇所の split を置換 (2) drawGutter/drawCompletionPopup のシグネチャに lines を追加し内部 split を削除（private メソッドのため外部影響なし） (3) ビルド (4) 全テスト
- **完了条件**: `EditorCanvas.java` 内の `text.split` 出現が描画経路で1箇所だけになる。全テストがベースラインと同一。
- **確認コマンド**: `grep -n 'text.split' src/dev/javatexteditor/ui/EditorCanvas.java`、`./scripts/build.sh && ./scripts/test.sh && java -cp build dev.javatexteditor.ui.EditorCanvasTest`
- **期待結果**: split 出現1箇所、failed 0
- **リスク**: 低。split は同一入力に対して決定的であり、描画内容は不変。DISPLAY がある環境なら `./scripts/run.sh` で診断ガター（エラーのある .java を開く）と補完ポップアップ（Ctrl+Space）の表示を目視確認するとなお良い。
- **失敗時の戻し方**: `git restore src/dev/javatexteditor/ui/EditorCanvas.java`
- **依存**: Item 1
- **想定コミットメッセージ**: `EditorCanvas の再描画1回あたりの全文 split を1回に集約する`

---

## Item 10: タブ幅・補完候補件数の定数化
- **対象箇所**: `ModalEditor.java` — `insertTab`(851-860)・`insertNewlineWithIndent`(905-926)・`insertCloseBrace`(889-903) のタブ幅 4／`"    "`、および補完クエリの `10`（671, 720, 756, 778）
- **目的**: 同一意味の直値を named constant にする（P-17 の一部）
- **問題**: タブ幅がリテラル `4` と4連スペース文字列で3メソッドに分散。補完候補件数 `10` が4箇所に分散。
- **変更内容**: フィールド定義部に定数を追加し、該当箇所を置換する。**値は変えない**。
```java
/** ソフトタブのインデント幅（スペース数）。 */
private static final int TAB_WIDTH = 4;
private static final String INDENT_UNIT = " ".repeat(TAB_WIDTH);
/** 補完ポップアップに出す最大候補数（Ctrl+Space / Alt+/ 共通）。 */
private static final int COMPLETION_MAX_RESULTS = 10;
```
置換対象: `insertTab` の `"    "`→`INDENT_UNIT`・`cursorCol += 4`→`+= TAB_WIDTH`、`insertCloseBrace` の `Math.min(4, cursorCol)`→`Math.min(TAB_WIDTH, cursorCol)`、`insertNewlineWithIndent` の `indent += "    "`→`+= INDENT_UNIT`、補完 `query(prefix, 10)` 4箇所→`query(prefix, COMPLETION_MAX_RESULTS)`。**これ以外の数値（プレビュー行数 20/30 等、意味が別のもの）は触らない。**
- **実施手順**: (1) 定数追加 (2) `grep -n '"    "' src/dev/javatexteditor/editor/ModalEditor.java` と `grep -n ', 10)' 同ファイル` で対象を確認してから置換 (3) ビルド (4) 全テスト
- **完了条件**: 全テストがベースラインと同一（タブ・インデント挙動は `ModalEditorTest`/`ModalEditorEdgeCaseTest` がカバー）
- **確認コマンド**: `./scripts/build.sh && ./scripts/test.sh`
- **期待結果**: failed 0
- **リスク**: 低。`" ".repeat(4)` は `"    "` と等値。
- **失敗時の戻し方**: `git restore src/dev/javatexteditor/editor/ModalEditor.java`
- **依存**: Item 6（同一ファイルの順次実施）
- **想定コミットメッセージ**: `ModalEditor のタブ幅と補完候補件数の直値を定数に集約する`

---

## Item 11: yankType の enum 化（公開文字列契約は維持）
- **対象箇所**: `ModalEditor.java` — フィールド `yankType`(70)、代入箇所（`processVisualKey` の yank/delete、`processVisualLineKey` の yank/delete、`yankCurrentLine`）、参照箇所（`pasteAfter`(2091)・`pasteBefore`(2100)）、公開アクセサ `getYankType()`(2622)
- **目的**: 取りうる値をコンパイル時に閉じる（P-18）
- **問題**: `"char"`/`"line"` の文字列比較で分岐しており、タイプミスが検出されない。
- **変更内容**: private enum を導入し内部は enum で扱う。**`getYankType()` は現行どおり `"char"`/`"line"` の String を返す**（`ModalEditorTest` が文字列比較で検証しているため。テスト側は変更しない）。
- **変更前後のコードスケッチ**:
```java
private enum YankType { CHAR, LINE }
private YankType yankType = YankType.CHAR;
...
// 代入箇所（例）: yankType = "line";  →  yankType = YankType.LINE;
// 分岐箇所:      if ("line".equals(yankType))  →  if (yankType == YankType.LINE)
// 公開アクセサ（シグネチャ・戻り値とも不変）:
public String getYankType() { return yankType == YankType.LINE ? "line" : "char"; }
```
- **実施手順**: (1) `grep -n 'yankType' src/dev/javatexteditor/editor/ModalEditor.java` で全出現を列挙 (2) enum 追加と全置換 (3) ビルド（置換漏れは型エラーで検出される） (4) 全テスト
- **完了条件**: 全テストがベースラインと同一（`ModalEditorTest` の yankType 検証 5 箇所が PASS すること）
- **確認コマンド**: `./scripts/build.sh && ./scripts/test.sh && java -cp build dev.javatexteditor.editor.ModalEditorTest`
- **期待結果**: failed 0
- **リスク**: 低。コンパイラが置換漏れを全部検出する。
- **失敗時の戻し方**: `git restore src/dev/javatexteditor/editor/ModalEditor.java`
- **依存**: Item 10（同一ファイルの順次実施）
- **想定コミットメッセージ**: `yankType を文字列から enum に変更する（getYankType() の文字列契約は維持）`

---

## Item 12: コード位置からの上方向探索ロジックの共通化
- **対象箇所**: `Main.resolveLibDir()`・`Main.resolveScriptDir()`、`OpenjdkSourceTracer.findBundledSrcZip()`・`findNativeSrcDir()`
- **目的**: 4重実装された「code source から親を最大4階層遡って相対パスを探す」を1関数にする（P-09）
- **問題**: 同型ロジックの4重複。細部の fallback（cwd 探索、`scripts/` があれば `lib/` を返す等）だけが違う。
- **変更内容**: `src/dev/javatexteditor/analysis/CodeSourceLocator.java` を新設（`Main` は既に analysis へ依存済み、`OpenjdkSourceTracer` は同一パッケージ。root パッケージに置くと analysis→root の循環依存になるため analysis に置く）。**各呼び出し元固有の fallback はそれぞれの元メソッドに残す**。
- **変更前後のコードスケッチ**:
```java
package dev.javatexteditor.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Predicate;

/** 実行中クラスの code source 位置から親ディレクトリを遡り、相対パスに一致する実在パスを探す。
 *  build/ 直接実行・jar 実行の両形態で、プロジェクト同梱の lib/ や scripts/ を発見するために使う。 */
public final class CodeSourceLocator {
    private CodeSourceLocator() {}

    /** anchor クラスの code source から maxLevels 階層まで親を遡り、
     *  dir.resolve(relative) が accept を満たす最初のパスを返す。 */
    public static Optional<Path> findUpward(Class<?> anchor, String relative,
                                            int maxLevels, Predicate<Path> accept) {
        try {
            var url = anchor.getProtectionDomain().getCodeSource().getLocation();
            if (url == null) return Optional.empty();
            Path code = Paths.get(url.toURI());
            Path dir = Files.isDirectory(code) ? code : code.getParent();
            for (int i = 0; i < maxLevels; i++) {
                if (dir == null) break;
                Path candidate = dir.resolve(relative);
                if (accept.test(candidate)) return Optional.of(candidate);
                dir = dir.getParent();
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }
}
```
呼び出し例（`OpenjdkSourceTracer.findNativeSrcDir` 変更後）:
```java
private static Optional<Path> findNativeSrcDir() {
    Optional<Path> found = CodeSourceLocator.findUpward(
        OpenjdkSourceTracer.class, "lib/openjdk-native", 4, Files::isDirectory);
    if (found.isPresent()) return found;
    Path fromCwd = Paths.get("lib", "openjdk-native");           // 既存 fallback を維持
    return Files.isDirectory(fromCwd) ? Optional.of(fromCwd.toAbsolutePath()) : Optional.empty();
}
```
`findBundledSrcZip` は accept に `Files::exists` を渡す。`Main.resolveLibDir` の「`lib` が無くても `scripts/` があれば `dir.resolve("lib")` を返す」特殊分岐は `findUpward` に押し込まず **resolveLibDir 内に残す**（無理に一般化しない）。判断に迷う場合は Main 側の置換を見送り、OpenjdkSourceTracer 側だけで止めてよい（本 Item は他と独立）。
- **実施手順**: (1) 新クラス追加 (2) `findNativeSrcDir`・`findBundledSrcZip` を置換 (3) ビルド＋全テスト＋コミット可否判断 (4) `Main` 側2メソッドの置換は挙動等価を diff で確信できる場合のみ実施。確信できなければ Main 側は変更せずその旨を報告
- **完了条件**: 全テストがベースラインと同一。`OpenjdkSourceTracingTest`・`JdkJavadocReaderTest` PASS。
- **確認コマンド**: `./scripts/build.sh && ./scripts/test.sh && java -cp build dev.javatexteditor.analysis.OpenjdkSourceTracingTest`
- **期待結果**: failed 0
- **リスク**: 中。既定探索経路（`lib/src.zip` 自動発見）はテストが明示パスコンストラクタを使うため**自動テストで守られていない**。`lib/` が存在する環境なら、置換前後で `hasSrcZip()` の結果が変わらないことを小さな確認コード（またはエディタ起動 → K キーで jdk-source が開くこと）で確かめる。`lib/` が無い環境では「両方 empty」の等価性は自明なので diff レビューのみでよい。
- **失敗時の戻し方**: `git restore src/dev/javatexteditor/ && rm -f src/dev/javatexteditor/analysis/CodeSourceLocator.java`
- **依存**: Item 1
- **想定コミットメッセージ**: `code source からの lib/scripts 上方向探索を CodeSourceLocator に共通化する`

---

## Item 13: getter/setter 生成ロジックを GetterSetterGenerator に抽出
- **対象箇所**: `ModalEditor.java` — `parseFieldAtCursor`(3280-3301)・`capitalize`(3303-3306)・`generateGetter`(3309-3322)・`generateSetter`(3325-3337)・`generateGetterAndSetter`(3340-3356)・`detectIndent`(3491-3498)
- **目的**: 神クラス分割の第一段として、バッファ状態に依存しない純粋文字列ロジックを別クラスへ移す（P-01/P-15 の部分対処）
- **問題**: フィールド宣言のパース・メソッド文字列組み立てはエディタ状態と無関係なのに `ModalEditor` に同居し、単体テストもエディタ経由でしかできない。
- **変更内容**: `src/dev/javatexteditor/editor/GetterSetterGenerator.java`（public final・static メソッドのみ）を新設し、**文字列の生成規則を1文字も変えずに**移す。`ModalEditor` 側にはオーケストレーション（カーソル行取得・`insertBeforeLastBrace` 呼び出し・ステータス表示）だけ残す。`insertBeforeLastBrace` はバッファ・カーソルを操作するため**移さない**。`detectIndent` は入力を `String[] lines` にして移す。
- **変更前後のコードスケッチ**:
```java
package dev.javatexteditor.editor;

/** カーソル行のフィールド宣言から getter/setter のソース文字列を生成する純粋ロジック。
 *  バッファへの挿入・カーソル移動は行わない（ModalEditor 側の責務）。 */
public final class GetterSetterGenerator {
    private GetterSetterGenerator() {}

    /** "private int hp;" のような行を [型, フィールド名] に解析する。失敗時 null。
     *  （旧 ModalEditor.parseFieldAtCursor の本体。引数を「カーソル行の生文字列」に変えただけ） */
    public static String[] parseFieldDeclaration(String line) { ... }

    /** ファイル先頭側から最初に見つかるインデント単位を返す（旧 detectIndent と同一規則）。 */
    public static String detectIndent(String[] lines) { ... }

    public static String buildGetter(String type, String name, String indent) { ... }
    public static String buildSetter(String type, String name, String indent) { ... }
    public static String buildGetterAndSetter(String type, String name, String indent) { ... }
}
```
`ModalEditor.generateGetter()`（変更後の形）:
```java
private void generateGetter() {
    String[] lines = getLines();
    String line = cursorRow < lines.length ? lines[cursorRow] : "";
    String[] field = GetterSetterGenerator.parseFieldDeclaration(line);
    if (field == null) { statusMessage = "Getter: フィールド宣言が見つかりません"; syncCanvas(); return; }
    String method = GetterSetterGenerator.buildGetter(field[0], field[1],
                        GetterSetterGenerator.detectIndent(lines));
    insertBeforeLastBrace(method);
    statusMessage = (field[0].equals("boolean") ? "is" : "get") + capitalize(field[1]) + "() を生成しました";
    syncCanvas();
}
```
（注意: ステータス文言・生成文字列（`indent + indent` の二重インデント含む）を**1文字も変えない**こと。「きれいに直したく」なっても等価移動に徹する。）
- **実施手順**: (1) 新クラス作成（既存コードの移動。`boolean` 判定・`is`/`get` プレフィックス・改行/インデント構成を一切変えない） (2) `ModalEditor` の3つの generate メソッドを委譲形に書き換え、`parseFieldAtCursor`/`detectIndent` を削除 (3) ビルド (4) 全テスト（getter 生成は `ModalEditorTest`・`RobotKeyInputTest` がカバー）
- **完了条件**: 全テストがベースラインと同一。生成される getter/setter 文字列がバイト単位で不変（`ModalEditorTest` の期待値文字列がそのまま通ることで担保）。
- **確認コマンド**: `./scripts/build.sh && ./scripts/test.sh && java -cp build dev.javatexteditor.editor.ModalEditorTest`
- **期待結果**: failed 0
- **リスク**: 中。移動時の「ついでの整形」が最大の敵。等価移動に徹すること。
- **失敗時の戻し方**: `git restore src/dev/javatexteditor/editor/ && rm -f src/dev/javatexteditor/editor/GetterSetterGenerator.java`
- **依存**: Item 11（同一ファイルの順次実施）
- **想定コミットメッセージ**: `getter/setter 生成の文字列組み立てを GetterSetterGenerator に抽出する`

---

## Item 14: 既知の二重定義・未接続コードの記録（ソースコード変更なし）
- **対象箇所**: `CLAUDE.md`（追記のみ）
- **目的**: P-10〜P-13・P-21 の「消してよいか判断できない」コードについて、次の開発者が片側だけ修正する事故を防ぐ（本プロジェクトの「新しい設計判断は SKILL.md か CLAUDE.md に書き残す」方針に従う）
- **問題**: 到達不能・未接続コードの存在理由が口頭知識になっている。
- **変更内容**: CLAUDE.md 末尾に「## 既知の未接続・二重定義（リファクタ調査 2026-07 時点）」節を追加し、以下を箇条書きで記録する。**ソースコードは1文字も変更しない。**
  1. NORMAL モード Ctrl+U/P は `processNormalKey` のハードコード（bufferHistory 方式）が優先され、`KeymapRegistry` の `buffer.prev`/`buffer.next`（`switchToRelativeBuffer` = レジストリ一覧巡回方式）には既定キーから到達しない。2実装のどちらを正とするかは未決定。
  2. `processCommandKey` は KeymapRegistry を参照しないため、COMMAND モードの registry 束縛は現状機能しない。
  3. `CompletionIndex.refreshProjectSymbols()` は未使用。呼ぶ場合は `TreeMap` の並行更新対策（不変マップ差し替え等）が先に必要。
  4. `extension/`（PluginLoader ほか）は `:plugin` 等の起動コマンドが未実装のため本番経路から未接続。
  5. jdk-source 疑似バッファ（`saved*`）と `*cd候補*` 疑似バッファ（`cdSaved*`）を重ねて使った場合の挙動は未定義・未テスト。
  6. `ScrollTest` の2ケース（halfPageUp 系）は Ctrl+U の仕様変更（半ページスクロール → バッファ履歴）に追従しておらず恒常的に FAIL する。テストを更新するかキー割当てを戻すかは未決定（U-7）。
- **実施手順**: (1) CLAUDE.md に追記 (2) `git diff` で CLAUDE.md 以外に差分がないことを確認
- **完了条件**: `git diff --stat` が CLAUDE.md 1ファイルのみ
- **確認コマンド**: `git diff --stat && ./scripts/build.sh && ./scripts/test.sh`
- **期待結果**: CLAUDE.md のみの差分、failed 0
- **リスク**: なし（ドキュメントのみ）
- **失敗時の戻し方**: `git restore CLAUDE.md`
- **依存**: Item 1〜13 の完了後（調査結果が確定してから記録する）
- **想定コミットメッセージ**: `既知の未接続コード・キー二重定義を CLAUDE.md に記録する`

---

# 6. 実施順の妥当性チェック

## この順序で安全な理由
- **Item 1（テストハーネス回復）が他のすべての Item の検証手段の前提**なので最初に置く。Item 1 自体の検証は「test.sh が完走するか」という自己完結した判定で行える。
- Item 2→5→6→10→11→13 は同一ファイル（`ModalEditor.java`）を触るため、**直列に並べてコンフリクトと diff 混濁を防ぐ**。各 Item は互いに別メソッド群を触るため論理的な依存はなく、失敗時はその Item だけ `git restore` で戻せる。
- Item 3・4・7・9・12 は別ファイル同士で完全独立。順序を入れ替えても安全（ただし並行ブランチにはしない）。
- Item 8 のみ「特性テスト追加 → 本体」の2コミット構成で、テストが先に現行挙動を固定する。
- Item 14（ドキュメント）は全調査結果が出揃う最後に置く。

## 依存崩れの有無
- 各 Item の抽出はすべて「呼び出し規約・公開シグネチャ・表示文字列を不変」に設計しており、前の Item の変更が後続の前提を壊す組み合わせはない。唯一の共有点は「同一ファイルの行番号がずれる」ことで、後続 Item はシンボル名で対象を特定すれば問題ない（本計画の行番号は b6d353e 基準の参考値）。

## 先にやると危険な項目
- **Item 1 より先にどの Item にも着手しない**こと（検証手段がない状態での変更になる）。
- **Item 8（Main）を特性テストより先にやること**は禁止。Main はテストゼロのため、テストなしの抽出は検証手段が diff レビューだけになる。
- **Item 12 の Main 側置換**は fallback 分岐の等価性判断が必要なため、迷ったら OpenjdkSourceTracer 側だけで止めて報告する（Item 内に明記済み）。

## 並行実施してはいけない項目
- `ModalEditor.java` を触る Item 2・5・6・10・11・13 は同時に着手しない（1つ完了・コミットしてから次へ）。
- Item 8 と Item 9 は別ファイルだが、どちらも描画/解析のタイミングに関わるため、動作確認（run.sh 目視）をまとめて行いたくても**コミットは分ける**。

## ロールバック可能性
- 全 Item が「新規ファイル追加＋既存ファイルの局所書き換え」で構成され、`git restore`（＋新規ファイル削除）または `git revert <commit>` で単独に戻せる。他 Item への波及はない（Item 間で共有する新規シンボルはない）。Item 1 を revert すると test.sh が再び停止するようになるが、それ以外の影響はない。

---

# 7. やらないことリスト（実行者が善意で逸脱しそうなこと）

以下は本計画では**明示的に禁止**する。実施したくなった場合は中断してユーザーに確認すること。

1. **機能追加**: `:plugin` コマンドの実装（extension 接続）、`refreshProjectSymbols` の呼び出し追加、Ctrl+U/P の挙動統一。いずれも仕様決定が先。
2. **仕様変更**: `ProjectSearcher` のスキップディレクトリを SKIP_DIRS に合わせること（grep 結果件数が変わる）。`analyzeWithPath` を organize-imports 側にも適用すること。エラーメッセージ・ステータス文言の書き換え（日英統一含む）。**`EditorCanvas` のタイマー開始タイミング変更（P-23 対処を製品側で行うこと）**。
3. **依存ライブラリ・ビルドツール・テストフレームワークの導入**（JUnit 追加・Gradle 化・SpotBugs 等も一切禁止。CLAUDE.md の絶対制約）。
4. **公開 API の変更**: `getYankType()` の戻り値型変更、`CompletionItem.kind` の enum 化、`EditorContext` インタフェースの変更、`KeymapRegistry` の既定バインド削除（COMMAND モードの死にバインド含む）。
5. **命名の全面変更**: `lookupJdkDoc` 等のリネーム、FQN インライン参照の一括 import 化、フィールド名の統一。diff ノイズが検証コストを上回る。
6. **フォーマット一括変更**: インデント・改行位置・import 順の整形。既存スタイル（行末コメント・日本語コメント）に合わせること。
7. **ついでの最適化**: `getLines()` の行キャッシュ導入（P-15）、`syncCanvas` の差分更新化、`PieceTable.offsetOfLine` の高速化、`saveToFile` のアトミック書き込み化（P-19）、`PieceTable` への境界値バリデーション追加（P-22）、疑似バッファ退避の統一（P-21）、`FuzzyMatcher` と `CompletionScorer` の統合、`ModalEditor.Mode` と `KeymapRegistry.Mode` の enum 統一、`Main.main()` の分割（P-02）。**いずれも価値はあるが挙動等価の立証が重く、本計画のスコープ外**。着手する場合は別計画としてユーザー合意を得ること。
8. **テストの検証内容・期待値の変更**: 期待値を変えたくなった時点でそれは仕様変更である。テストへの変更は (a) Item 1 の「main 末尾への `System.exit(0)` 追加」と (b) Item 8 の特性テスト**追加**、の2つだけを許可する。**特に `ScrollTest` の失敗2件（U-7）を「ついでに」直さないこと** — テスト側とキー割当て側のどちらが正か未決定であり、どちらの修正も仕様判断を伴う。
9. **`RenameRefactorer` 周辺の「試し実行」**: 実プロジェクトディレクトリで `:rename` を実行しないこと（ファイルを実際に上書きする）。
10. **`lib/`・`build/`・`dist/` 配下や `scripts/` の変更**（test.sh に timeout を仕込む等の「ハーネス側対処」も禁止。P-23 の対処は Item 1 のテスト側 exit(0) に統一する）。

---

# 8. 実行者への指示文

> 以下をそのまま実行者に渡すこと。

---

あなたには JavaTextEditor のリファクタリングを依頼します。`docs/REFACTORING_PLAN.md` が唯一の作業指示書です。以下のルールを厳守してください。

1. **最初に計画書の「Item 0: 安全網の構築」を実施**してください。`git status --short` がクリーンであること、`./scripts/build.sh` が通ることを確認してください。**注意①: `./scripts/test.sh` は現状 `CompileAnalyzerTest` で停止します（計画書 P-23）**。計画書 §4 0-2 の per-class ランナーでベースライン（クラスごとの PASS/FAIL と「killed」の一覧）を取得・記録してから作業を始めてください。**注意②: `ScrollTest` はベースライン時点で 18/20（2件 FAIL）です（計画書 U-7）。これは既知の失敗であり、直さずにそのまま基準として扱ってください。**
2. **1項目ずつ、計画書の実行順（Item 1 → 14）どおりに実施**してください。順序の入れ替え・複数 Item の同時着手は禁止です。**Item 1（テストハーネスの完走性回復）を最初に完了させるまで、他の Item に着手してはいけません。**
3. **1項目ごとに必ず1コミット**（Item 8 のみ計画書記載どおり「特性テスト追加」「本体」の2コミット）。コミット前に各 Item の「確認コマンド」をすべて実行し、「期待結果」を満たすことを確認してください。コミットメッセージは各 Item の「想定コミットメッセージ」を使ってください。
4. **完了条件を満たせない場合は、その Item を `git restore` で完全に戻し、次に進まず中断して報告**してください。失敗した状態のまま先の Item に着手することを禁止します。
5. **計画外の変更をしない**でください。特に計画書 §7「やらないことリスト」に挙げた行為（ついでの最適化・リネーム・整形・仕様変更・テスト期待値の変更）は、良い改善に見えても禁止です。
6. **不明点を推測で埋めない**でください。計画書に「不明」「触らない」と書かれている箇所（§1.7 U-1〜U-6、P-10〜P-13 関連コード等）は、判断せずそのままにするか、中断して質問してください。
7. 対象コードの行番号はコミット `b6d353e` 基準の参考値です。ずれていたら**シンボル名（メソッド名・クラス名）で対象を特定**してください。行番号だけを頼りに機械置換しないでください。
8. 各 Item の変更は「挙動等価」が絶対条件です。ステータス表示の文字列・生成コードの文字列・検索結果の件数など、**観測可能な出力を1文字も変えない**でください（唯一の例外は Item 1 の「テスト JVM が終了するようになる」ことで、これは意図した修正です）。
9. GUI での目視確認（`./scripts/run.sh`）は DISPLAY がある環境でのみ行ってください。ない場合は各 Item に記載の代替（diff レビュー・単体テスト）で検証し、目視を省略した旨を報告に含めてください。
10. すべての Item 完了後、`./scripts/test.sh` の最終結果とベースラインの比較、および実施した Item / 見送った Item の一覧を報告してください。

---

*（本計画書はコミット `b6d353e` 時点のコードベース調査に基づく。計画作成時にコード・設定・テストへの変更は行っていない — §0 の検証ログ参照。）*
