---
name: file-search
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、NORMALモードの\\f（ファイル名検索）・\\g（ファイル内容grep）による2打鍵の入力プロンプト（Mode.FILESEARCH）と、疑似バッファでの結果表示・Enterジャンプを設計・実装する際に使用する。「ファイル名検索を追加したい」「\\fや\\gの入力プロンプトの挙動を変えたい」「bang(!)付きの全ファイル検索に対応したい」「FileNameSearcherのアルゴリズムを直したい」といった相談、またFileNameSearcher/DirEntryやModalEditorのenterFileSearch/processFileSearchKey/Mode.FILESEARCH周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# ファイル名検索・ファイル内容grep（`\f` / `\g`）

## このスキルが解決すること

NORMAL モードから `\`（バックスラッシュ）に続けて `f`（ファイル名検索）または `g`（ファイル内容
grep）を押すと、1行のクエリ入力プロンプト（`Mode.FILESEARCH`）が開く。Enter で実行し、結果は
`*file-search*`/`*grep*` 疑似バッファに一覧表示され、Enter で該当ファイルへジャンプする。

`\f` はファイル**名**だけを対象にする独自エンジン（`FileNameSearcher`）を持つが、`\g` は
`project-wide-search` スキルが定義する `ProjectSearcher`/`executeGrep()` をそのまま呼ぶ
（=ファイル**内容**の grep エンジン自体は共有）。本スキルは「2打鍵の入力プロンプト」という
UI・状態管理層を主題とし、`\g` の検索エンジン自体の仕様は `project-wide-search` を参照すること。

---

## キーバインド一覧

| キー | モード | 動作 |
|---|---|---|
| `\` → `f` | NORMAL | FILESEARCH モードへ入り、ファイル名検索クエリの入力を開始する |
| `\` → `g` | NORMAL | FILESEARCH モードへ入り、grep クエリの入力を開始する |
| Enter | FILESEARCH | 入力したパターンで検索を実行し NORMAL へ戻る |
| Esc | FILESEARCH | 入力をキャンセルして NORMAL へ戻る |
| Backspace | FILESEARCH | 入力の末尾1文字を削除 |
| 先頭に `!` を含めて Enter（例: `\f!node`） | FILESEARCH | bang 版。`FileNameSearcher.SKIP_DIRS`（`\f`）/`ProjectSearcher.DEFAULT_SKIP_DIRS`（`\g`）を無視し全ファイルを対象にする |
| Enter | `*file-search*`/`*grep*` 疑似バッファ内（NORMAL） | カーソル行の結果ファイルを開く |

`\` 自体は `filesearch.pending` にバインド済み。2打鍵目（`f`/`g`）の判定は `processNormalKey()`
内の `prev == '\\'` 判定ブロックで行い、`\a`（getter/setter 生成プレフィックス）等の他の `\`
系2打鍵シーケンスより**前**に判定する必要がある（`\a` は3打鍵目まで見るため判定順序の詳細は
`keymap-conflict-resolution` スキルを参照）。

---

## 実装アーキテクチャ

### モード追加とエントリ

```java
private enum Mode { ..., FILESEARCH, ... }

private void enterFileSearch(FileSearchType type) {
    fileSearchType = type;
    fileSearchBuffer.setLength(0);
    mode = Mode.FILESEARCH;
    statusMessage = "";
}
```

`FileSearchType` は `NAME`（`\f`）/`GREP`（`\g`）の2値 enum。`fileSearchBuffer` は
`StringBuilder` で1行分の入力を保持する。

### 入力プロンプトの処理（`handleTextPromptKey` への委譲）

```java
private void processFileSearchKey(int keyCode, char keyChar) {
    handleTextPromptKey(keyCode, keyChar, fileSearchBuffer,
        () -> { fileSearchBuffer.setLength(0); mode = Mode.NORMAL; },
        this::runFileSearch);
}
```

`handleTextPromptKey()` は `SEARCH`（`/`）・`CLASSPATH_INPUT`（F10/F11/F12）・本モードの3つで
共通化された汎用ヘルパー（`ModalEditor` 神クラス解体リファクタリング第5弾で抽出。
Esc・Backspace・Enter・印字可能文字の4分岐だけを持つ）。「どの入力欄か」「取り消したら何を
するか」「確定したら何をするか」の3点だけを引数で渡す。

### bang（`!`）判定 — 入力バッファの先頭文字で判定する

```java
private void runFileSearch(String input) {
    mode = Mode.NORMAL;
    if (input.isEmpty()) return;
    boolean fullScan = input.startsWith("!");
    String pattern = fullScan ? input.substring(1) : input;
    if (pattern.isEmpty()) return;
    if (fileSearchType == FileSearchType.NAME) {
        executeFileNameSearch(pattern, fullScan);
    } else {
        executeGrep(pattern, getProjectRoot(), fullScan);
    }
}
```

**bang の判定はキー入力のタイミングではなく、Enter 時にバッファ全体の先頭文字を見て行う**。
これにより `\f`/`\g` という2打鍵の実行タイミング自体を変える必要がなく、既存のシーケンス処理と
安全に共存できる（`gr`/`gR` のようにキー自体を2種類用意する方式とは異なるアプローチ）。

### `FileNameSearcher`（`src/dev/javatexteditor/search/FileNameSearcher.java`）

```java
public static final Set<String> SKIP_DIRS =
    Set.of(".git", "build", "target", ".gradle", "node_modules", ".idea", ".vscode");

public List<Path> search(Path baseDir, String pattern, boolean fullScan)
```

`ProjectSearcher.DEFAULT_SKIP_DIRS` はこの `SKIP_DIRS` をそのまま参照している（2クラス間で
スキップ対象ディレクトリの定義を共有）。ファイル**内容**は一切読まず、`Files.walkFileTree()`
でパス（正確にはファイル名部分）だけを `Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)`
で照合する。`fullScan == true`（bang 指定）の場合は `preVisitDirectory` の `SKIP_DIRS` 判定を
スキップして全ディレクトリを走査する。ファイル内容を読まないため、`ProjectSearcher` のような
2MB 上限は不要（存在しない）。

### `DirEntry`（record）— `simple-filer` との共有型

```java
public record DirEntry(String name, Path path, Kind kind) {
    public enum Kind { DIRECTORY, FILE }
}
```

`FileNameSearcher` 自体は `DirEntry` を使わず `List<Path>` を返すが、同パッケージ内の
`DirectoryLister`（`simple-filer` スキル参照）が同じ `dev.javatexteditor.search` パッケージで
`DirEntry` を共有する。ファイル走査系の型・スキップ対象は本パッケージ内で一貫性を保っている。

### 疑似バッファ表示（`executeFileNameSearch()`）

```java
private void executeFileNameSearch(String pattern, boolean fullScan) {
    ...
    sb.append("*file-search").append(bangLabel).append("* /").append(pattern).append("/ — ")
        .append(results.size()).append(" match(es)\n");
    for (Path p : results) { ... sb.append(rel).append("\n"); paths.add(rel); }
    fileNameResults = paths;
    buffer = new UndoablePieceTable(sb.toString());
    currentFilePath = null;
    grepResults = null;   // \g の疑似バッファと排他（同時に2種類の結果は保持しない）
    ...
}
```

`\g`（`executeGrep()`）は `project-wide-search` スキルの「実装アーキテクチャ」節で説明済みの
`*grep*` 疑似バッファをそのまま使う。`\f` 専用の `fileNameResults`（`List<String>`、相対パス）と
`\g` 用の `grepResults`（`List<SearchResult>`）は互いに排他的に管理され、一方を設定する際は
他方を必ず `null` にリセットする（両方が非 null のまま残ると `jumpToFileNameResult()`/
`jumpToGrepResult()` の Enter ジャンプ判定が競合するため）。

`jumpToFileNameResult()`（行0はヘッダなので `cursorRow - 1` が結果インデックス）は
`readFileContentForBuffer()`（バイナリ判定・`.class` プレビュー・BOM除去を含む共通の
ファイル読み込み入口）を経由してファイルを開く。

---

## 設計判断ログ（詳細は `docs/decision-log.md` を参照）

- **「検索・補完機能の大文字小文字区別に関する設計決定事項」節**: `\f`（`FileNameSearcher`）は
  実装当初から `Pattern.CASE_INSENSITIVE` だったため変更不要だった、という確認結果。
- **「gR / :grep! / \\f! / \\g!（bang付き全ファイル検索）を追加」節**: `\f!`/`\g!` の bang 判定を
  「先頭文字が `!`」という入力バッファレベルの判定にした理由（2打鍵シーケンスの実行タイミングを
  変えずに済むため）と、`FileNameSearcher.search(baseDir, pattern, boolean fullScan)` オーバー
  ロード追加の経緯。
- **`ModalEditor` 神クラス解体リファクタリング 第5弾**: `handleTextPromptKey()` への統合経緯
  （`SEARCH`/`FILESEARCH`/`CLASSPATH_INPUT` の3画面が完全に同型の重複だった）。

---

## テスト方針

`test/dev/javatexteditor/search/FileSearchTest.java`（43/43、ロードマップ⑲参照）。

- `FileNameSearcher` 単体: 基本検索・大文字小文字無視・`SKIP_DIRS`・bang 版での全走査。
- `ModalEditor` 統合: `\f`/`\g` の2打鍵遷移・Esc キャンセル・Backspace・Enter 実行・
  bang 判定（先頭 `!`）・`*file-search*`/`*grep*` 疑似バッファの表示内容・Enter ジャンプ・
  `fileNameResults`/`grepResults` の排他性。

---

## 関連Skill

- **`project-wide-search`**: `\g` が最終的に呼ぶ `ProjectSearcher`/`executeGrep()` の検索
  エンジン自体（2MB 上限・タイムアウト・並列 grep・`gr`/`:grep` との共有）はこちらが一次情報源。
- **`simple-filer`**: `DirEntry`/`DirectoryLister` を共有する別のファイル走査機能（`:cd` 後の
  ディレクトリブラウザ）。`\f` は「ファイル名パターン検索」、FILER は「現在ディレクトリの一覧
  表示」であり目的が異なる。
- **`telescope-picker`**: `SPC+f`（ファジーファイル検索）は `FileNameSearcher` ではなく独自の
  `FilePicker`（ファイルリスト取得は共有するがマッチングはあいまい検索）を使う。`\f` との
  違い（完全な正規表現 vs ファジーマッチ）はそちらのスキルを参照。
- **`keymap-conflict-resolution`**: `\`（バックスラッシュ）系の2〜3打鍵シーケンス（`\f`/`\g`/
  `\a` 等）の判定順序の全体像を確認する場合はこちら。
