---
name: auto-import-handler
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、コンパイル診断から未解決シンボルを検出しimport文を自動挿入するAutoImportHandlerを設計・実装・修正する際に使用する。「未定義シンボルにimportを自動挿入したい」「複数候補があるときの選択UIを作りたい」「import文の並び順をEclipse互換にしたい」「自プロジェクトの別パッケージのクラスがimport候補に出ない」「java.langやJDK内部クラスが誤って候補に出る」「auto-import挿入後に波下線の行番号がずれる」といった相談、またAutoImportHandler/ImportSuggester/ProjectClassSuggesterやModalEditorのhandleAutoImport/Mode.IMPORT_SELECT周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# 未定義シンボルの import 自動挿入（`AutoImportHandler`）

## このスキルが解決すること

`javac-compile-integration` が生成するコンパイル診断（`cannot find symbol` エラー）から
未解決の型名を抽出し、JDK クラス索引・自プロジェクトのクラス走査の両方から候補 FQN を集めて、
候補1件なら自動挿入、複数件なら選択 UI（`Mode.IMPORT_SELECT`）を経由してバッファへ `import` 文
を挿入する。挿入時は Eclipse の Organize Imports と同じグループ順・アルファベット順で
import ブロック全体を書き直す。

C言語版（`.c`/`.h` の `#include` 自動挿入、`CIncludeManager`）は別実装で本スキルの対象外
（「C言語開発支援」節参照。設計は似るが独立したクラス）。

---

## キーバインド一覧

| キー/コマンド | モード | 動作 |
|---|---|---|
| （自動） | INSERT→NORMAL 遷移時・保存時 | `Main.setupCompileAnalysis()` がコンパイル解析後に自動で `handleAutoImport()` を呼ぶ |
| ↑/↓・Ctrl+P/Ctrl+N・`j`/`k` | IMPORT_SELECT | 候補 FQN の選択を移動（自由入力を持たない画面のため `j`/`k` も使える） |
| Enter | IMPORT_SELECT | 選択中の FQN を挿入し次の未解決シンボルへ進む |
| Esc | IMPORT_SELECT | 現在のシンボルをスキップして次へ進む |
| `SPC i o` / `:oi` / `:organize-imports` | NORMAL/COMMAND | 未使用 import の削除（並び替えは伴わない。下記「注意点」参照） |

---

## 実装アーキテクチャ

### `AutoImportHandler`（`src/dev/javatexteditor/analysis/AutoImportHandler.java`）

```java
public List<String> findMissingSymbols(List<CompileDiagnostic> diags)
public Map<String, List<String>> resolveCandidates(List<CompileDiagnostic> diags, String source, Path baseDir)
public boolean applyImport(String fqn, PieceTable buffer)
public List<String> applyImports(List<String> fqns, PieceTable buffer)
public boolean removeImport(String fqn, PieceTable buffer)
public List<String> removeUnusedImports(PieceTable buffer)
```

- `findMissingSymbols()`: `SYMBOL_PATTERN = "symbol:\\s*(?:class|interface|enum)\\s+(\\w+)"` で
  `CompileDiagnostic.kind() == ERROR` の診断メッセージから単純名を抽出する（javac の
  `cannot find symbol` 診断が付随情報として出力する `symbol: class Xxx` 行を利用する）。
- `resolveCandidates(diags, source, baseDir)`: 未解決シンボルごとに `ImportSuggester.suggest(name,
  baseDir)` を呼び、既に import 済みの FQN を除外した結果を `LinkedHashMap<String, List<String>>`
  （シンボル名→候補 FQN リスト、候補ゼロは含めない）で返す。`baseDir == null` の場合は JDK
  クラスのみを候補にする後方互換オーバーロード（`resolveCandidates(diags, source)`）がある。
- `applyImport(fqn, buffer)` / `applyImports(fqns, buffer)`: 重複チェック（`SourceAnalyzer` で
  既存 import を再解析）の後、`insertAndReorganize()` で import ブロック全体を書き直す
  （下記「Eclipse 互換の並べ替え」参照）。`SourceAnalyzer` が使えない場合（構文エラー等）は
  重複チェックなしで挿入する（graceful degradation）。

### `ImportSuggester`（`src/dev/javatexteditor/analysis/ImportSuggester.java`）— 候補ソースの統合

```java
public List<String> suggest(String simpleName)                    // JDK クラスのみ
public List<String> suggest(String simpleName, Path baseDir)      // JDK + 自プロジェクト
```

`baseDir` 付き版は `jdkIndex.lookup(simpleName)` と `projectClassSuggester.suggest(baseDir,
simpleName)` の結果を `LinkedHashSet` でマージする（JDK 候補が先、プロジェクト候補が後）。
両方とも `filterImportable()` で **`java.lang` パッケージ直下のクラスを候補から除外**する
（`import` 不要な暗黙 import 対象のため。前方一致ではなく完全一致で判定し、`java.lang.reflect`
等のサブパッケージは除外しない）。

### `ProjectClassSuggester`（`src/dev/javatexteditor/analysis/ProjectClassSuggester.java`）

自プロジェクト内で宣言された `class`/`interface`/`enum`/`record` を `ProjectSearcher`
（`project-wide-search` スキル参照）で `\b(?:class|interface|enum|record)\s+SimpleName\b` を
grep して探す。**ヒットしたファイルのうちファイル名が simpleName と一致するものだけ**を対象に
する（Java の「public トップレベル型はファイル名と一致する」慣例を利用し、内部クラス等の誤検出を
避ける）。対象ファイルの `package` 宣言を正規表現（`PACKAGE_PATTERN`）で読んで FQN を組み立てる。
**キャッシュを持たない**設計（`WordIndex`/`CompletionIndex` のような起動時1回きりの索引ではなく、
呼び出しの都度ディスクを検索する）。理由は新規作成直後のファイルも即座に候補に反映するため
（詳細は `docs/decision-log.md`「自動 import 挿入がプロジェクト内の別パッケージのクラスに対して
働かない不具合の修正」節）。

### Eclipse 互換の並べ替え（`insertAndReorganize()`）

新規 import を挿入するたびに、既存 import 行もすべて解析し直して import ブロック全体を
Eclipse のデフォルト（Organize Imports）と同じ規則で書き直す。

```java
private static final List<String> IMPORT_GROUP_ORDER = List.of("java", "javax", "org", "com");
```

- **グループ順**: `java`→`javax`→`org`→`com`→その他、の順（前方一致判定、`packageOf(fqn)`
  が接頭辞と完全一致または `prefix + "."` で始まるか）。
- **グループ内**: FQN の `String#compareTo` によるアルファベット順。
- **static import** は非 static のブロックより前に独立したブロックとして配置（グループ順・
  アルファベット順は同じ規則）。
- **空行**: グループ間・static/非static ブロック間にそれぞれ1行だけ挿入（グループ内には入れない）。

実装は既存の import 行区間をまるごと `buffer.delete()` してから `formatImportBlock()` の結果を
再挿入する（部分的な差分挿入ではなく全体再構築）。既存 import が1件も無い場合は
`findImportInsertOffset()`（最後の import 行の次、無ければ package 行の次、それも無ければ
オフセット0）の位置に新規ブロックを挿入する。

### `Mode.IMPORT_SELECT` — 複数候補の選択 UI

`ModalEditor.handleAutoImport(diags)` が候補1件のシンボルはその場で `applyImport()` して
自動挿入し、複数候補が残るシンボルは `pendingImports` に貯めて `enterImportSelect()` で
`Mode.IMPORT_SELECT` へ遷移する。1シンボルずつ順に選択させ（`advanceImportPrompt()`）、
全シンボル処理完了後に `onImportComplete` コールバックを呼ぶ。表示は `EditorCanvas` の
オーバーレイ（`setTelescopeState()` を流用、`telescope-picker` の3ペインオーバーレイ廃止後も
IMPORT_SELECT だけはこのオーバーレイ方式を維持している。詳細は `telescope-picker` スキール
参照）。

### `shiftDiagnosticsAfterImportEdit()` — 挿入直後の診断行ズレ補正

import 挿入で行数が変わると、既に画面に表示済みの波下線・ガター診断（`javac-compile-integration`
が担当）の行番号が古いままズレる。`handleAutoImport()`/`exitImportSelect()` の両方で、挿入前後の
行数差（`countLines(after) - countLines(before)`）だけ現在表示中の診断リストを一律シフトし直す
（import は常にコード本体より前にのみ挿入されるため、一律シフトで正しく補正できるという前提）。

---

## 設計判断ログ（詳細は `docs/decision-log.md` を参照）

- **「自動 import 挿入（⑯ auto-import-handler）の並び順を Eclipse 互換に修正」節**: 単純追記
  方式から全体再構築方式（`insertAndReorganize()`）へ変更した経緯。
- **「`Main.isJavaBuffer()` の判定基準変更」節**: `currentFilePath == null` の疑似バッファでは
  auto-import・コンパイル解析自体を走らせない、という前提条件の確定経緯（`.java` 拡張子が
  明示的に確定した場合のみ対象にする）。
- **「自動 import 挿入がプロジェクト内の別パッケージのクラスに対して働かない不具合の修正」節**:
  `ProjectClassSuggester` 新設の経緯・キャッシュを持たない設計判断の理由。
- **「auto-import選択ポップアップの無限再発とimport挿入位置がpackage文より前になる不具合の修正」
  節（2026-07-25）**: UTF-8 BOM 未除去が2つの症状（挿入位置ズレ・選択ポップアップ無限再発）を
  同時に引き起こしていたこと、およびコンパイル解析トリガの二重発火（`onReturnToNormal`+`onSave`）
  による古い診断での上書きレースが無限再発を単独でも起こしうる別原因だったこと。世代ガード
  （`AtomicLong compileGeneration`）による解決方法の詳細。
- **「auto-import が JDK 内部の非公開クラス・`java.lang` を候補にしてしまう不具合の修正」節
  （2026-07-26）**: `JdkClassIndex` へのモジュールエクスポート判定追加（`com.sun.org.apache...`
  等の内部実装クラスを索引から除外）と、`ImportSuggester.filterImportable()` による
  `java.lang` 除外の2段構えの修正経緯。`ModuleFinder.ofSystem()` を使う理由（`ModuleLayer.boot()`
  ではオプションモジュールが判定できない）も記録されている。
- **「auto-import 挿入直後、波下線（診断）の表示位置が実際のエラー行とずれる不具合の修正」節
  （2026-07-26）**: `shiftDiagnosticsAfterImportEdit()` 新設の経緯。

---

## 注意点

- **`SPC i o` / `:oi` / `Ctrl+Shift+O`（旧）の役割分担**: `Ctrl+Shift+O` は現在 `insert.override`
  （`@Override` スタブ挿入）に差し替え済みで、import 整理機能ではない（`keymap-conflict-resolution`
  スキルの設計判断ログ参照）。未使用 import の削除・整理は `SPC i o` と `:oi`/`:organize-imports`
  コマンドが担い、`removeUnusedImports()` を直接呼ぶ（**並び替えは伴わない**。並び替えが起きるのは
  `applyImport`/`applyImports` による新規挿入時のみ）。
- **`resolveCandidates()` は既に import 済みの FQN を候補から除外する**ため、複数解析要求が
  競合すると「1つ選んだのにもう1つ勝手に追加される」という事故が起きうる（上記「無限再発」節）。
  この種の不具合を疑う場合はまず世代ガードが正しく機能しているかを確認すること。

---

## テスト方針

`test/dev/javatexteditor/analysis/AutoImportHandlerTest.java`（26/26、ロードマップ⑯参照。
Eclipse 互換並べ替えの追加テストを含め随時テストが増えている）・
`test/dev/javatexteditor/analysis/ProjectClassSuggesterTest.java`（`ProjectClassSuggester` 単体）。
`javac-compile-integration` 側の `CompileAnalyzer`/`CompileDiagnostic` と組み合わせた統合テスト
（世代ガード等の GUI/バックグラウンドスレッド依存部分）は `Main`/`app` パッケージ側の既知の
テストギャップ（`docs/decision-log.md` 参照）。

---

## 関連Skill

- **`java-source-analysis`**: `SourceAnalyzer`/`SourceIndex`/`ImportEntry` による既存 import
  文の索引（重複チェックの基盤）を提供する。
- **`javac-compile-integration`**: `CompileDiagnostic`/`CompileAnalyzer` によるコンパイル診断
  生成（本スキルの入力）を担当。
- **`jdk-api-navigation`**: `JdkClassIndex`（jrt:/ 走査によるクラス名索引）が `ImportSuggester`
  の JDK 側候補ソース。モジュールエクスポート判定もこの索引に実装されている。
- **`project-wide-search`**: `ProjectClassSuggester` が自プロジェクトのクラスを探す際に
  `ProjectSearcher` を再利用する。
- **`keymap-conflict-resolution`**: `Ctrl+Shift+O` の役割変更（organize imports → `@Override`
  挿入）の経緯を記録している。
