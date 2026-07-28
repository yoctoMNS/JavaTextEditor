---
name: project-wide-search
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、作業ディレクトリ配下を正規表現でgrepするProjectSearcherエンジンと、gr/gR/:grep/:grep!によるプロジェクト全体検索・疑似バッファ表示を設計・実装する際に使用する。「grep検索を追加・変更したい」「Shift+Kやgrepがフリーズする」「巨大ファイル・巨大ディレクトリでタイムアウトさせたい」「node_modules等をスキップしたい」「並列grepの結果順序を保証したい」といった相談、またProjectSearcher/SearchResultやModalEditorのexecuteGrep/withTimeout周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# プロジェクト全体grep検索（`ProjectSearcher`）

## このスキルが解決すること

作業ディレクトリ（`getProjectRoot()`）配下のテキストファイルを正規表現で全文検索し、
一致行を `*grep*` 疑似バッファに一覧表示する。`gr`/`gR`（NORMALモード2打鍵）・`:grep`/`:grep!`
（COMMANDモード）・`\g`/`\g!`（`file-search` スキルが扱う FILESEARCH モード経由）のいずれもが
最終的にこの1つのエンジン・1つの疑似バッファ表示メソッドを共有する。

`gr` は `symbol-definition-navigation` スキルが扱う「参照一覧」機能の実体でもあり、
カーソル位置の識別子を `\bword\b` パターンへ変換して本エンジンへ渡すだけの薄いラッパー
（`goToReferences()`）として実装されている。

---

## キーバインド一覧

| キー/コマンド | モード | 動作 |
|---|---|---|
| `gr`（`g`→`r`） | NORMAL | カーソル位置の識別子を語境界付きで検索（既定スキップ対象を除外） |
| `gR`（`g`→Shift+`R`） | NORMAL | 同上・bang版（スキップ対象も含め全ファイル走査） |
| `:grep <pattern>` | COMMAND | 任意の正規表現でプロジェクト全体を検索 |
| `:grep! <pattern>` | COMMAND | 同上・bang版（全ファイル走査） |
| Enter | `*grep*` 疑似バッファ内（NORMAL） | カーソル行の結果ファイルを開き該当行へジャンプ |

`gr`/`gR` の2文字目判定は `keyChar == 'r'` / `keyChar == 'R'` の直接比較で行う（`matches()`
ヘルパーは `keyCode` 優先一致のため大文字小文字を区別できない。詳細は下記「大文字小文字区別」
参照）。`\g`/`\g!`（FILESEARCH モードの2打鍵入力プロンプト経由）は `file-search` スキール側の
守備範囲だが、内部的には本スキルの `executeGrep()` をそのまま呼ぶ。

---

## 実装アーキテクチャ

### `ProjectSearcher.search()`（`src/dev/javatexteditor/search/ProjectSearcher.java`）

```java
public List<SearchResult> search(Path baseDir, String pattern, boolean fullScan)
```

軽量性リファクタリング Phase 3（`CLAUDE.md`「軽量性リファクタリング計画」参照）で
**「①逐次 walk でパス収集 → ②仮想スレッドでファイルごとに並列 grep → ③submit 順に連結」**
の2段階構成になっている。

1. `collectCandidateFiles(baseDir, fullScan)`: `Files.walkFileTree()` で対象ファイルの
   `Path` だけを逐次収集する（内容は読まない）。`fullScan == false` の場合のみ
   `DEFAULT_SKIP_DIRS`（`.git`/`build`/`target`/`.gradle`/`node_modules`/`.idea`/`.vscode`。
   `FileNameSearcher.SKIP_DIRS` と共有）でディレクトリを丸ごとスキップし、`attrs.size() <=
   MAX_FILE_SIZE_BYTES`（2MB、`WordIndex` と同値）を超えるファイルは候補に含めない。
2. `grepFilesInParallel(files, regex, baseDir)`: `Executors.newVirtualThreadPerTaskExecutor()`
   でファイル数ぶんの仮想スレッドを一括生成し、各ファイルを `searchFile()` で並列に grep する。
   **`Future` を submit した順に `get()` して結果を連結する**ため、結果順序は従来の逐次実装
   （walk 順・ファイル内は行昇順）と完全に同一に保たれる（呼び出し側のテストが順序に依存できる）。
3. `searchFile()`: 先頭 8KB に NUL バイトがあればバイナリとみなしスキップ。
   `Files.readAllLines(file, StandardCharsets.UTF_8)` で読み、`MalformedInputException`
   （UTF-8 として不正）もスキップ対象にする。マッチした行を `SearchResult(relativePath,
   lineNumber, lineContent)` として集める。

### 協調キャンセル（タイムアウトとの連携）

`collectCandidateFiles()`（`visitFile`/`preVisitDirectory` 冒頭）・`grepFilesInParallel()`
（`InterruptedException` 捕捉時）・`searchFile()`（冒頭）のいずれも
`Thread.currentThread().isInterrupted()` を検査する。呼び出し元 `ModalEditor.withTimeout()`
がタイムアウト時に `future.cancel(true)` を呼ぶと、この3箇所が速やかに処理を打ち切る
（従来の「タイムアウト後もバックグラウンド検索スレッドが走り続ける」という既知の残課題は
軽量性リファクタリング Phase 3 で解消済み）。

### `SearchResult`（record）

```java
public record SearchResult(String filePath, int lineNumber, String lineContent) {
    public String toDisplayLine() { return filePath + ":" + lineNumber + ": " + lineContent; }
}
```

`filePath` は `baseDir.relativize(file)` の結果（OS依存の `\` は `/` に正規化済み）。

### `ModalEditor.executeGrep()` — EDT ブロッキング対策とタイムアウト

```java
private void executeGrep(String pattern, Path baseDir, boolean fullScan) {
    ...
    List<SearchResult> results = withTimeout(() -> projectSearcher.search(baseDir, pattern, fullScan));
    if (results == null) {
        statusMessage = "grep: search timed out（作業ディレクトリが大きすぎる可能性があります）";
        return;
    }
    ...
}
```

`ProjectSearcher.search()` は EDT 上で同期的に呼ばれる（結果を待って `processKey()` 直後に
`buffer` を反映する、という本プロジェクトの同期契約を維持するため）。作業ディレクトリの既定値が
ホームディレクトリになりうる（`WorkingDirectoryManager` 参照）ため無制限にブロックすると危険で、
`withTimeout()`（`Executors.newVirtualThreadPerTaskExecutor()` + `Future.get(1500ms, ...)`、
`PROJECT_SYMBOL_SEARCH_TIMEOUT_MS` 定数）で必ず 1.5 秒以内に打ち切る。

結果は `*grep[!]* /<pattern>/ — N match(es)` のヘッダ行 + 結果1行ずつの疑似バッファとして
`buffer` を直接差し替える（`pushBuffer()` を呼ばないため `Ctrl+U`/`Ctrl+P` の履歴には積まれない。
telescope-picker・file-search と同じ「ヘッダ行＋結果一覧」の疑似バッファ表示規約に従う）。
`grepResults`/`grepBaseDir` に検索結果と起点ディレクトリを保持し、`jumpToGrepResult()`
（NORMAL モードでの Enter）がカーソル行から結果を逆引きしてファイルを開く。

`goToReferences(fullScan)`（`gr`/`gR` の実体）は `jdk-source` 疑似バッファ内で native ソース
（`lib/openjdk-native/`）が利用可能な場合、`baseDir` を `sourceTracer.getNativeSrcDir()` に
差し替えて C/C++ 側の参照も検索できるようにする（`openjdk-source-tracing` との連携点）。

---

## 設計判断ログ（詳細は `docs/decision-log.md` を参照）

- **「Shift+K フリーズ修正（`ProjectSearcher` の巨大ファイル上限）」節**: `K`（`jdk.doc`）から
  `ProjectSymbolResolver` 経由で本エンジンを呼ぶ際、2MB 上限がなかったために巨大ファイル1つで
  同期的な EDT フリーズが起きた不具合と、その後の「ファイル数自体が多いだけでも遅い」問題への
  `withTimeout()`（1500ms）追加の経緯。実測値（15,000ファイルで552ms・50,000ファイルで2,391ms・
  150,000ファイルで4,621ms）もここに記録されている。真の非同期化（`SwingUtilities.invokeLater`
  での結果反映）は、`processKey()` 直後に同期 assert するテストハーネスの契約と衝突するため
  見送られた経緯も含む。
- **「gR / :grep! / \\f! / \\g!（bang付き全ファイル検索）を追加」節**: 当初「意図的に `.git`/
  `build`/`target` のみをスキップする」としていた設計を、`node_modules` 等を含む
  `FileNameSearcher.SKIP_DIRS` へデフォルトを統一した経緯。`gr`/`gR` の2文字目判定に
  `keyChar` 直接比較が必要だった理由（`matches()` の `keyCode` 優先一致では大文字小文字を
  区別できない）もここにある。
- **「検索・補完機能の大文字小文字区別に関する設計決定事項」節**: `ProjectSearcher.search()` に
  `Pattern.CASE_INSENSITIVE` を追加した経緯（`\g`/`gr`/`gR`/`:grep`/`:grep!` すべてに影響）。
- **「軽量性リファクタリング計画」Phase 3 節**: 「逐次 walk → 仮想スレッド並列 grep」への再構成と
  `future.cancel(true)` による協調キャンセルの導入経緯。`ParallelGrepTest`（8/8）が結果順序の
  決定性・2MB 上限・NUL バイナリ判定・`SKIP_DIRS`・`fullScan` の維持を検証している。

---

## テスト方針

- `test/dev/javatexteditor/search/ProjectSearchTest.java`（21/21）: 基本的な検索・複数行
  マッチ・スキップ対象ディレクトリ・バイナリ判定・2MB 上限などの単体テスト。
- `test/dev/javatexteditor/search/BangSearchTest.java`（10/10）: `gr`/`gR`・`:grep`/`:grep!`・
  `\g!` 経由の統合動作を含む bang 版の検証。
- `test/dev/javatexteditor/search/ParallelGrepTest.java`（4/4）: Phase 3 再構成後の結果順序・
  既存制約の維持を検証。

---

## 関連Skill

- **`file-search`**: `\f`/`\g` という NORMAL モード2打鍵の入力プロンプト（FILESEARCH モード）
  自体のUI・状態管理を扱う。`\g` は本スキルの `executeGrep()` をそのまま呼ぶだけなので、
  検索エンジン自体の仕様変更は本スキル、入力プロンプトの挙動変更は `file-search` を参照。
- **`symbol-definition-navigation`**: `gr`/`gR` を「定義・参照ジャンプ」の一部として扱う高レベル
  フロー（`K`・`Shift+J` との関係）を統括する。キーの割り当て自体はこちらが正。
- **`telescope-picker`**: `SPC+/`（ライブ grep）は独立した `GrepPicker` を持つが、
  内部で本スキルの `ProjectSearcher` を再利用する。表示方式（疑似バッファ）の設計判断は
  そちらのスキルを参照。
- **`openjdk-source-tracing`**: jdk-source 疑似バッファ内での `gr` が `lib/openjdk-native/`
  を検索対象に切り替える連携（native 参照検索）を担当。
- **`multi-file-refactoring`**: `:rename` は本エンジンの `ProjectSearcher` を「対象ファイルの
  発見」フェーズに再利用する（`RenameRefactorer` 参照）。
