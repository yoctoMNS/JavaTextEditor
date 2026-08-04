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
| `:`/`;`（一覧表示中のみ、検索中は不可） | FILER | `exitFiler()` で元バッファへ復元してから通常の COMMAND モードへ入る（`:cd`/`:e`/`:pr`/`:mkdir` 等をそのまま使える。`;` は NORMAL/VISUAL系と同じVim式の `:` エイリアス。2026-07-29追加、`;` 対応は同日追記） |
| `:pr` | COMMAND | 現在のディレクトリを F10/F11/F12 用プロジェクトルートとして固定。FILER表示中に実行した場合はFILERへ戻る（2026-07-29追加） |
| `:mkdir <path>` | COMMAND | 現在の作業ディレクトリ基準でディレクトリを新規作成する。`:cd` と異なり作成先へは移動せず親に留まる。FILER表示中に実行した場合は一覧を再読み込みしてFILERへ戻る（2026-07-29追加） |
| `y`/`Y` | `Mode.CD_CONFIRM_CREATE` | 存在しないディレクトリを新規作成して `:cd` を続行 |
| `n`/`N`/Esc | `Mode.CD_CONFIRM_CREATE` | 何もせず NORMAL へ戻る |
| `I`（一覧表示中のみ、`..` は対象外） | FILER | 選択中エントリの名前編集のため `Mode.INSERT` へ入る（2026-08-01追加。`:w` を打つまではディスクへ反映しない） |
| Esc（`I` で編集中の `Mode.INSERT` から） | INSERT | 保存はせず `Mode.FILER` へ戻る（編集中のテキストは保持。破棄したい場合はさらに Esc で `exitFiler()`） |
| `:w` | COMMAND（`I` 編集起点） | `applyFilerRename()` で名前が変わった行だけ `Files.move()` し、FILERへ戻り一覧を再読み込みする |
| Ctrl+D（一覧表示中のみ、`..` は対象外） | FILER | 選択中エントリの削除確認（`Mode.FILER_DELETE_CONFIRM`）へ入る |
| `y`/`Y` | `Mode.FILER_DELETE_CONFIRM` | 選択中エントリを削除する（ディレクトリは中身ごと再帰削除）しFILERへ戻る |
| それ以外（`n`/`N`/Esc等） | `Mode.FILER_DELETE_CONFIRM` | 何もせずFILERへ戻る |

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

### `:cd`/`:mkdir` の TAB 補完（`handleCdTabCompletion(verb)`、2026-08-04に`:mkdir`対応追加）

FILER 本体とは別の、`:cd`/`:mkdir` コマンドライン入力を助ける機能。`commandBuffer` が
`"<verb>"`/`"<verb> "`（`verb` は `"cd"` または `"mkdir"`）で始まる場合のみ Tab を横取りし、
`DirectoryLister.listDirectoryEntries()` で候補ディレクトリ（`Kind.DIRECTORY` のみ）を列挙して
入力中の末尾セグメントを前方一致でフィルタする。候補0件は何もしない、1件はその場で
`commandBuffer` を補完、複数件は `*<verb>-candidates*` 疑似バッファ（telescope と同型だが
独立実装）で選択させる。

**`:mkdir` への対応方針**: `:e`/`:enew`/`:w`（`handleEditTabCompletion(verb)`）のように
ファイルも候補に含める方式ではなく、`:cd` と全く同じ「ディレクトリのみ列挙」機構を
`cdVerb`フィールド（"cd"|"mkdir"）で使い回した。理由は `:mkdir` が「既存の親ディレクトリを
辿りながら、まだ存在しない末尾のディレクトリ名を入力する」用途のため、ファイルを候補に混ぜる
意味が無く、`:cd` の「1件なら末尾に `/` を付けて継続入力可能にする」挙動がそのまま
ネストしたディレクトリ作成（`mkdir a/b/c`）にも都合が良いため。`applyCdCandidate()`/
`openCdCandidateBuffer()` 等の共通処理は `cdVerb` を見てコマンド名・疑似バッファ名
（`*cd-candidates*`/`*mkdir-candidates*`）を出し分ける。

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

---

## FILER表示中の `:cd`/`:e` 直接実行（2026-07-29追加）

FILER で一覧を表示している最中でも `:` キーで COMMAND モードへ入り、`:cd <path>` や
`:e <path>` をそのまま実行できる。存在しないパスを指定した場合の新規作成確認
（`Mode.CD_CONFIRM_CREATE` / `Mode.CONFIRM_NEW_FILE`）も NORMAL モードから実行した場合と
完全に同じ経路を通る。

```java
// processFilerKey() 内、一覧表示中（!filerSearchMode）の分岐
if (keyChar == ':' || keyChar == ';') {
    filerCommandOrigin = true;
    exitFiler();
    enterCommandMode();
}
```

`;` は NORMAL/VISUAL系モードで既に `KeymapRegistry` により `:` のVim式エイリアスとして
束縛されているのと同じ理由で追加した（`keymap-conflict-resolution` スキル参照）。FILER は
`KeymapRegistry` を経由しない独自のキー処理（`processFilerKey()`）のため、ここは同様に
`keyChar == ';'` を素朴に追記するだけで対応している。同じ理由で `Mode.IMAGE`
（`processImageKey()`）・`Mode.BINARY`（`processBinaryKey()`）の `:` ハードコード判定にも
同時に `;` を追加した（いずれも同じ「読み取り専用/バイナリ編集の疑似バッファからCOMMANDへ
入る」パターンのため。2026-07-29追記）。

**設計判断**: 「FILER 用に `:cd`/`:e` を個別分岐する」のではなく、**まず `exitFiler()` で
`:cd` 実行前の元バッファへ復元してから、通常の `enterCommandMode()` に入る**方式にした。
理由は「再帰的なディレクトリ移動と保存タイミングの非対称性」節にある `saveToStash()` の
1セッション1回制約と衝突するため:

- `changeDirectory()`（`:cd` 本体）は呼ばれるたびに `saveToStash(filerStash)` を実行する。
  もし FILER 表示中の疑似バッファのまま `:cd` を実行すると、`filerStash` に退避済みの
  「本来の元バッファ」が疑似バッファで上書きされ、Esc で元のファイルへ戻れなくなる。
- `:e <path>` も同様に、疑似バッファのまま `loadFromFile()` を呼ぶと `pushBuffer()` が
  疑似バッファ（FILER の一覧テキスト）を `bufferHistory` に積んでしまう
  （`openSelectedEntry()` の FILE 分岐が `exitFiler()` を先に呼ぶのと同じ理由、
  上記「再帰的なディレクトリ移動と保存タイミングの非対称性」節を参照）。

`exitFiler()` を先に呼んでおけば、`:cd` は「NORMAL モードから新規に `:cd` を実行した」のと
区別がつかない状態になるため、`changeDirectory()`/`loadFromFile()` 側は一切変更不要だった。
`:cd` 成功時は `changeDirectory()` が改めて `enterFiler()` を呼ぶため、ディレクトリ一覧は
自動的に再読み込みされる（新規作成したディレクトリもそのまま表示される）。

テストは `test/dev/javatexteditor/search/FilerTest.java` の
`testColonInFilerEntersCommandModeAndCdSwitchesAndReloads` /
`testColonCdNonexistentFromFilerPromptsAndCreates` /
`testColonEFromFilerOpensExistingFile` /
`testColonENonexistentFromFilerPromptsAndCreatesBuffer` を参照。

---

## `:pr`/`:mkdir` — バッファを操作しないコマンドはFILERに留まる（2026-07-29追加）

`:cd`/`:e` はバッファを開き直す（＝FILERを抜けるのが自然な）コマンドだが、`:pr`（プロジェクト
ルート固定）や `:mkdir <path>`（ディレクトリ新規作成。**`:cd` と異なり作成先へは移動せず、
親ディレクトリに留まる**）は `buffer`/`currentFilePath` を一切触らない。これらを FILER 表示中に
実行した場合は、一覧を再読み込みしつつ FILER に留まってほしい（毎回 `:cd` 実行前のバッファへ
戻されると連続してブラウジングできない）。

`exitFiler()` を先に呼んでしまうと「FILER表示中だった」という情報が失われるため、`processFilerKey()`
の `:` ハンドラで `filerCommandOrigin` フラグを立ててから `exitFiler()`/`enterCommandMode()` する。
`pinProjectRoot()`/`makeDirectory()` は自分の処理が終わった後に共通ヘルパー
`returnToFilerIfCommandFromFiler()` を呼び、フラグが立っていれば `enterFiler()` で FILER へ戻る
（同じディレクトリの一覧を再読み込みするため、新規作成したディレクトリもそのまま表示される）。
フラグは `processCommandKey()` の Esc/Enter の両方で必ず `false` にクリアする（コマンド側が
消費し忘れた場合の安全網、かつ次回の `:` 押下に持ち越さないため）。

この方式は「`modeAfterCommand()` が `imageModeOwner`/`binaryModeOwner` の参照一致で
IMAGE/BINARY に戻る」既存パターンと対になる設計だが、FILER の場合は疑似バッファが `:` 押下時点
（`exitFiler()` 実行前）で既に破棄されてしまうため参照一致方式が使えず、代わりに単純な
boolean フラグ方式にした。バッファを操作する新しいコマンドを追加する場合は
`returnToFilerIfCommandFromFiler()` を呼ばないこと（呼ぶと開いたはずのファイル/疑似バッファが
即座に上書きされてしまう）。

テストは `testColonPrFromFilerStaysInFilerMode` / `testColonMkdirFromFilerStaysAndReloadsListing` /
`testColonMkdirOutsideFilerDoesNotEnterFiler`（FILERを経由しない通常の `:mkdir` は FILER へ
遷移しないことの確認）を参照。

## 名前変更（`I`）と削除（Ctrl+D）（2026-08-01追加）

### `I` — 選択中エントリの名前編集

「本物の INSERT モードに入って編集し、`:w` を押すまでディスクへ反映しない」という要望を、
新しい疑似モードを作らず**既存の `Mode.INSERT` をそのまま再利用**する方式で実現した。

- `I`（一覧表示中のみ、`..` は対象外）で `filerRenameActive = true` にしてから `mode = Mode.INSERT`
  にするだけ。疑似バッファのテキスト（`*filer* ...` ヘッダ＋各エントリ1行）自体を素の編集対象
  として使うため、行削除・複数行同時編集も特別な実装なしにそのまま使える。
- カーソル位置を選択行の**末尾**に置く（`cursorCol = 現在行の長さ`）。`moveSelection()`/
  `renderFilerBuffer()` は選択行を `cursorCol = 0` にするため、そのまま Backspace を押すと
  ヘッダ行と結合してしまう（実際にこの不具合を手動テストで踏んだ）。
- Esc（`processInsertKey` の `"enter.normal"` アクション）で `filerRenameActive` が true の場合は
  `mode = Mode.NORMAL` ではなく `mode = Mode.FILER` に戻す。`clearLineIfIndentOnly()`・
  `clampCursorForNormal()`・`onReturnToNormal`（`LiveDiagnostics` の再コンパイル起動）は
  「本物のファイル編集から抜ける」ときのための処理であり、疑似バッファには不要かつ危険
  （`currentFilePath == null` の状態でコンパイルを起動する等）なので、この分岐では呼ばない。
- `:w` は "既存の `:w` コマンドの中" で分岐する（新しいコマンドは追加しない）。
  `buildCommandRegistry()` の `"w"` 登録を
  `() -> { if (filerRenameActive) applyFilerRename(); else requestSaveToFile(currentFilePath); }`
  にし、`applyFilerRename()` が `getLines()`（現在の疑似バッファのテキスト）を編集開始時点の
  `filerFiltered`（インデックス`i`→エントリ）と突き合わせて、名前が変わった行だけ
  `Files.move(entry.path(), entry.path().resolveSibling(newName))` する。ディレクトリ行は
  末尾の `/` を剥がしてから比較する。適用後は `enterFiler()` で一覧を再読み込みする。
- FILER で `:` を押すと通常は `exitFiler()`（stash復元）してから `enterCommandMode()` するが
  （「FILER表示中の `:cd`/`:e` 直接実行」節参照）、`filerRenameActive` が true のときは
  **`exitFiler()` を呼ばない**。呼ぶと `filerStash` に退避済みの元バッファで疑似バッファ
  （＝編集中の名前変更）が上書きされてしまい、`:w` を押しても消えたバッファに対して動作する
  ことになる。同じ理由で、`:` の後 Esc でコマンドをキャンセルした場合も
  `mode = Mode.NORMAL` ではなく `Mode.FILER` に戻す（`processCommandKey` の Esc ハンドラで
  `filerCommandOrigin && filerRenameActive` を判定）。
- 確定前に別の理由でFILERを抜ける（Esc の Esc → `exitFiler()`）と、`filerStash` から元バッファが
  復元され編集中の名前変更は自然に破棄される。「`:w` を打つまでは一切反映しない」という要件は
  この「`exitFiler()` は常に stash 復元＝破棄」という既存の性質にそのまま乗っている。

### Ctrl+D — 選択中エントリの削除

`Mode.CD_CONFIRM_CREATE`（既存の y/n 確認モード）と同型の新規疑似モード
`Mode.FILER_DELETE_CONFIRM` を追加した。Ctrl+D で確認対象を `filerDeleteTarget` に保持しつつ
モード遷移し、`y`/`Y` のみ実際に削除（ディレクトリは `Files.walk().sorted(reverseOrder())` で
中身ごと再帰削除）してから `enterFiler()` で一覧を再読み込みする。`y`/`Y` 以外（`n`/`N`/Esc含む）は
すべて「キャンセルして FILER に留まる」扱いにしている（既存の `CD_CONFIRM_CREATE` は Esc/`n`/`N`
を個別に判定しているが、削除確認は誤操作の被害が大きいため「y以外はすべて安全側」に倒した）。

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
