---
name: simple-filer
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、:cd実行後に開くディレクトリ一覧・ファイルブラウザ（Mode.FILER）を設計・実装する際に使用する。「:cd後にディレクトリブラウザを開きたい」「FILERモードのキー処理を変えたい」「ディレクトリ移動でファイルを開きたい」「DirectoryListerの一覧・フィルタロジックを直したい」「:cdで存在しないディレクトリを指定した時の新規作成確認」といった相談、またDirectoryLister/DirEntryやModalEditorのenterFiler/processFilerKey/Mode.FILER周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# `:cd` 後のディレクトリブラウザ（`Mode.FILER`）

## このスキルが解決すること

`:cd <path>` で作業ディレクトリ（`projectRoot`）を変更すると、成功時に自動的にディレクトリ一覧
ブラウザ（`Mode.FILER`）が開く。カーソル移動でエントリを選び、Enter でディレクトリなら再帰的に
移動、ファイルなら開く。表示は `\f`/`\g`/telescope と同じ「ヘッダ行＋結果一覧」の疑似バッファ
方式に統一されている（3ペインオーバーレイは使わない）。

---

## キーバインド一覧

| キー | モード | 動作 |
|---|---|---|
| `:cd <path>` | COMMAND | 作業ディレクトリを変更し、成功したら FILER へ遷移する |
| Tab（`commandBuffer` が `"cd"`/`"cd "` で始まる時） | COMMAND | ディレクトリ名のシェル風パス補完 |
| ↑/↓・Ctrl+P/Ctrl+N・`j`/`k` | FILER（一覧表示中） | 選択エントリを移動（自由入力がない画面のため `j`/`k` も使える） |
| ↑/↓・Ctrl+P/Ctrl+N | FILER（`/` 検索中） | 同上（`j`/`k` は検索クエリの文字として扱われるため移動には使えない） |
| Enter | FILER | 選択中のエントリを開く（ディレクトリ→再帰移動、ファイル→`loadFromFile()`） |
| `/` | FILER（一覧表示中） | エントリ名の絞り込み検索モードへ入る |
| 印字可能文字 | FILER（検索中） | 検索クエリへ追記し、即座に再フィルタ |
| Backspace | FILER（検索中） | クエリ末尾1文字を削除し再フィルタ |
| Esc | FILER（検索中） | 検索をキャンセルし一覧表示へ戻る |
| Esc | FILER（一覧表示中） | FILER セッションを終了し `:cd` 実行前のバッファへ復元する |
| `y`/`Y` | `Mode.CD_CONFIRM_CREATE` | 存在しないディレクトリを新規作成して `:cd` を続行 |
| `n`/`N`/Esc | `Mode.CD_CONFIRM_CREATE` | 何もせず NORMAL へ戻る |

---

## 実装アーキテクチャ

### `:cd` の実行 → FILER 遷移（`changeDirectory()`/`applyChangeDirectory()`）

```java
private void changeDirectory(String pathStr) {
    pathStr = UserPathResolver.expandHome(pathStr);
    Path target = getProjectRoot().resolve(pathStr).toAbsolutePath().normalize();
    if (!Files.exists(target)) {
        cdConfirmTarget = target;
        mode = Mode.CD_CONFIRM_CREATE;
        statusMessage = "ディレクトリが存在しません: " + target + " 新規作成しますか? (y/n)";
        return;
    }
    applyChangeDirectory(target);
}

private void applyChangeDirectory(Path target) {
    String err = changeWdCallback.apply(target);
    if (err != null) { statusMessage = "E: " + err; return; }
    saveToStash(filerStash);   // ★ ここが「:cd 実行時にのみ」元バッファを退避する唯一の箇所
    enterFiler();
}
```

`changeWdCallback` は `Function<Path, String>`（成功時 `null`、失敗時エラー文字列を返す）。
`WD_MANAGER`（`WorkingDirectoryManager`）のリスナーが同期的に全ペインの `projectRoot` を更新
するため、`applyChangeDirectory()` が `enterFiler()` を呼ぶ時点で正しい `projectRoot` が読める。

存在しないディレクトリを指定した場合は `Mode.CD_CONFIRM_CREATE`（`KeymapRegistry` を経由せず
`processCdConfirmCreateKey()` で直接キーを処理する疑似モード）へ入る。`y`/`Y` で
`Files.createDirectories()` の後に `applyChangeDirectory()` を実行、`n`/`N`/Esc は何もせず
NORMAL へ戻る。

### `enterFiler()` / `renderFilerBuffer()` — 疑似バッファ描画

```java
private void enterFiler() {
    filerEntries = DirectoryLister.listDirectoryEntries(getProjectRoot());
    filerFiltered = filerEntries;
    filerSelectedIdx = 0;
    filerSearchMode = false;
    mode = Mode.FILER;
    renderFilerBuffer();
}

private void renderFilerBuffer() {
    sb.append("*filer* ").append(root).append(filerSearchMode ? " /" + filerQuery : "")
      .append(" — ").append(filerFiltered.size()).append("件\n");
    for (DirEntry e : filerFiltered)
        sb.append(e.kind() == DIRECTORY ? e.name() + "/" : e.name()).append('\n');
    buffer = new UndoablePieceTable(sb.toString());
    currentFilePath = null;
    cursorRow = filerFiltered.isEmpty() ? 0 : filerSelectedIdx + 1;  // +1 はヘッダ行の分
}
```

選択中のエントリは専用ハイライトではなく**実際のテキストカーソル**（`cursorRow`）をその行に
合わせることで示す（telescope-picker と同じ設計）。`moveSelection(delta)` は結果リストを
再構築せず `filerSelectedIdx`/`cursorRow` を動かすだけでよい。クエリが変わって結果件数が
変化したとき（`/` 検索中の文字入力・Backspace）だけ `renderFilerBuffer()` で `buffer` を
再構築する。

### `DirectoryLister`（`src/dev/javatexteditor/search/DirectoryLister.java`）— 純粋ロジック

```java
public static List<DirEntry> listDirectoryEntries(Path dir) throws IOException
public static List<DirEntry> filterEntries(List<DirEntry> entries, String query)
```

`Files.list(dir)`（非再帰、直下のみ）でディレクトリとファイルを別リストに分け、
**ディレクトリ優先、各グループ内は名前昇順（大文字小文字無視）**でソートしてから連結する。
`filterEntries()` は `query` を小文字化した部分一致（`contains`）でフィルタする単純な実装
（telescope のようなあいまいマッチではない）。`ModalEditor` はオーケストレーション（状態管理・
キー処理）のみを担い、列挙・フィルタのロジックはこのクラスに完全に分離されている
（Swing 非依存で単体テストしやすい）。

### 再帰的なディレクトリ移動と保存タイミングの非対称性（重要）

```java
private void openSelectedEntry() {
    DirEntry entry = filerFiltered.get(filerSelectedIdx);
    if (entry.kind() == DirEntry.Kind.DIRECTORY) {
        String err = changeWdCallback.apply(entry.path());
        if (err != null) { statusMessage = "E: " + err; return; }
        enterFiler();   // ★ saveToStash() を呼ばない
    } else {
        exitFiler();
        loadFromFile(entry.path().toString());
    }
}
```

FILER 内でサブディレクトリを選んで Enter を押した場合（`openSelectedEntry()` の DIRECTORY 分岐）
は `enterFiler()` を**呼び直すだけで `saveToStash()` は呼ばない**。元バッファの退避は
「外側から見て初めて FILER に入る瞬間」である `applyChangeDirectory()`（＝`:cd` 実行時）の
**1箇所に限定する必要がある**。telescope のセッション開始が1箇所なのに対し、FILER は `:cd` の
1回の起動から何度もディレクトリを移動できるため、ここで毎回退避すると Esc 時に「1つ前の
ディレクトリ一覧」に戻ってしまい `:cd` 実行前の本来のバッファへ戻れなくなる。

ファイルを選んだ場合（FILE 分岐）は `exitFiler()` で元バッファへ**先に復元してから**
`loadFromFile()` を呼ぶ。`loadFromFile()` 内部の `pushBuffer()` が正しい元バッファを
`bufferHistory` に積むために、復元を挟む順序が必須（挟まないと疑似バッファのテキストが
誤って履歴に積まれてしまう）。

### 状態の退避・復元（`filerStash` / `PseudoBufferStash`）

`saveToStash(filerStash)`/`restoreFromStash(filerStash)` は、jdk-source・telescope・`*cd候補*`
等の他の疑似バッファ退避系統と同じ `PseudoBufferStash` 型を使う（`ModalEditor` 神クラス解体
リファクタリング第2弾で7系統に共通化済み）。**バッファは本文の写しではなく生きた
`UndoablePieceTable` の参照として預かる**（Vim 方式の共有バッファを壊さないため。詳細は
`docs/decision-log.md`「第2弾」節）。`exitFiler()` は `mode = Mode.NORMAL` にした上で
`restoreFromStash(filerStash)` を呼ぶだけ。

### `:cd` の TAB 補完（`handleCdTabCompletion()`）

FILER 本体とは別の、`:cd` コマンドライン入力を助ける機能。`commandBuffer` が `"cd"`/`"cd "` で
始まる場合のみ Tab を横取りし、`DirectoryLister.listDirectoryEntries()` で候補ディレクトリ
（`Kind.DIRECTORY` のみ）を列挙して入力中の末尾セグメントを前方一致でフィルタする。候補0件は
何もしない、1件はその場で `commandBuffer` を補完、複数件は `*cd候補*` 疑似バッファ（telescope
と同型だが独立実装）で選択させる。

---

## 親ディレクトリへの移動（`..` エントリ、2026-07-29追加）

`enterFiler()` は `DirectoryLister.listDirectoryEntries()` の結果の先頭に、`getProjectRoot()`
に親ディレクトリが存在する場合のみ `DirEntry("..", parent, Kind.DIRECTORY)` を1件だけ追加する
（ファイルシステムのルートで親が無い場合は追加しない＝`Path.getParent()` が `null` を返すかで判定）。
`..` は通常のディレクトリエントリと同じ `DirEntry` として扱うため、`openSelectedEntry()` 等の
既存のディレクトリ遷移ロジック（Enter で `changeWdCallback.apply()` → `enterFiler()` 再実行）を
一切変更せずに動作する。`/` 検索フィルタの対象にも自然に含まれる（`".."` を含む文字列で絞り込む
ことは通常無いため実用上の影響はない）。ソート処理より前に先頭固定で挿入するため、
`DirectoryLister` 側のディレクトリ優先ソートには影響しない。

## 設計判断ログ（詳細は `docs/decision-log.md` を参照）

- **「FILERモードの設計決定事項」節**: `Mode.FILER` 新設・`currentDirectory` を `projectRoot`
  に統合した経緯・`changeWdCallback` の型を `Function<Path, String>` にした経緯・
  `processCommandKey` の Enter ハンドラを `modeAfterCommand()` 経由に変更した理由。
- **「作業ディレクトリ・`:pwd`/`:cd`の設計決定事項」節**: `:cd` の `~` 展開
  （`UserPathResolver.expandHome()`）・`WorkingDirectoryManager` の永続化廃止（起動時は常に
  ホームディレクトリが既定値）・存在しないディレクトリの y/n 新規作成確認（`CD_CONFIRM_CREATE`）
  の追加経緯・TAB 補完の詳細仕様・`*cd候補*` 疑似バッファが telescope オーバーレイ方式ではなく
  `*grep*`/jdk-source と同じ疑似バッファ方式に変更された経緯。
- **`telescope-picker` スキル「追記（2026-07）」節**: FILER の描画方式が
  `EditorCanvas.drawTelescopeOverlay()` から `\f`/`\g` と同じ疑似バッファ方式へ変更された経緯
  （「`:cd` でディレクトリ移動している間も telescope 風のオーバーレイ画面が表示されてしまう」
  という指摘への対応）・一覧選択キーの Vim 式統一（`j`/`k` を自由入力の有無で使い分ける方針）。
- **`ModalEditor` 神クラス解体リファクタリング 第2弾**: 疑似バッファ退避の7系統統合
  （`PseudoBufferStash`）の経緯。FILER はこの7系統の1つ。

---

## テスト方針

`test/dev/javatexteditor/search/FilerTest.java`（46/46、ロードマップ㉑参照）。

- `DirectoryLister` 単体: ディレクトリ優先ソート・大文字小文字無視のフィルタ・空ディレクトリ。
- `ModalEditor` 統合: `:cd` 成功時の FILER 遷移・存在しないディレクトリでの y/n 確認フロー・
  一覧表示中と検索中でのキー処理の違い（`j`/`k` の扱い）・Enter でのディレクトリ再帰移動と
  ファイルオープンの両方・Esc での退避バッファ復元・TAB 補完（0件/1件/複数件）。

---

## 関連Skill

- **`file-search`**: `DirEntry` 型を共有する別のファイル走査機能（`\f`/`\g` の正規表現検索）。
  FILER は「現在ディレクトリの一覧をそのまま見せる」、`\f` は「パターンに一致するファイルを
  プロジェクト全体から探す」という異なる目的を持つ。
- **`telescope-picker`**: 疑似バッファ表示方式・一覧選択キー（`j`/`k`/Ctrl+N/P/矢印）の設計
  思想を共有する。FILER の描画方式の変更経緯もこちらのスキルに記録されている。
- **`project-wide-search`**: `:cd` で移動した先の `projectRoot` が `gr`/`:grep`/`K` 等の
  検索起点になる（FILER 自体はファイル内容を検索しない）。
