---
name: text-search
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、バッファ内文字列検索（*と#の単語検索・n/Nジャンプ・Emacs式インクリメンタルサーチC-s/C-r）を設計・実装する際に使用する。「検索機能を追加・変更したい」「検索ハイライトの描画」「n/Nの折り返し挙動」「インクリメンタルサーチを変更したい」といった相談、またsearchMatchesやisearch*周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# テキスト内文字列検索

## このスキルが解決すること

バッファ内の文字列を検索し、マッチ位置へカーソルをジャンプさせる。
`*`/`#` 単語検索・`n`/`N` 次/前マッチジャンプ（Vim互換）と、Emacs式インクリメンタルサーチ
（`Ctrl+S`/`Ctrl+R`、NORMAL/INSERT両モード）を実装する。

> **2026-07-29 廃止**: かつて存在した `/` によるVim式パターン入力検索（SEARCHモード。文字列を
> 入力してEnterで確定し、n/Nで移動する方式）は、検索アルゴリズムをEmacs式インクリメンタルサーチに
> 一本化するため廃止された。以下の「実装アーキテクチャ」冒頭に残る `/`/SEARCHモードの記述は
> **歴史的経緯**として残しているが、現在のコードには存在しない。詳細は本ファイル末尾の
> 「`/` Vim式検索の廃止とEmacs式インクリメンタルサーチのNORMALモード拡張（2026-07-29）」を参照。

---

## キーバインド一覧（現行）

| キー | モード | 動作 |
|---|---|---|
| `Ctrl+S` | NORMAL/INSERT | Emacs式インクリメンタルサーチを前方検索で開始/次候補へ進む（後述） |
| `Ctrl+R` | NORMAL/INSERT | Emacs式インクリメンタルサーチを後方検索で開始/前候補へ進む |
| `n` | NORMAL | 最後の `*`/`#` 検索と同方向へ次のマッチへジャンプ（折り返しあり） |
| `N` | NORMAL | 最後の `*`/`#` 検索の逆方向へジャンプ（折り返しあり） |
| `*` | NORMAL | カーソル位置の「単語」を **下方向（後方）** へ完全一致検索 |
| `#` | NORMAL | カーソル位置の「単語」を **上方向（前方）** へ完全一致検索 |
| Esc Esc（連続2回） | NORMAL | 検索ハイライトを強制的にクリアする |

Emacs式インクリメンタルサーチ（`Ctrl+S`/`Ctrl+R`）のキー一覧・設計判断は本ファイル末尾の
「Emacs式インクリメンタルサーチ（C-s / C-r、NORMAL/INSERT両モードで有効）」節を参照。

---

## 実装アーキテクチャ（`*`/`#`/`n`/`N` — 歴史的経緯として `/`/SEARCHモードの記述を含む）

### モード追加（廃止済み）

かつては `ModalEditor.Mode` に `SEARCH` を追加していた。
SEARCH モード中はステータスバーに `/<入力中パターン>` を表示していた。

```java
// 2026-07-29 廃止（Mode.SEARCH 自体を削除済み）
private enum Mode { NORMAL, INSERT, COMMAND, VISUAL, VISUAL_LINE, SEARCH }
```

### 状態フィールド（ModalEditor）

```java
// searchBuffer は Mode.SEARCH と共に廃止済み。以下は現存するフィールド。
private String   lastSearchPattern = "";
private boolean  lastSearchForward = true;
// 各要素: {offset, length}（buffer.getText() 上の絶対位置）
private List<int[]> searchMatches = List.of();
private int currentMatchIdx = -1;
```

### 検索ロジック

`executeSearch(pattern, forward)`:
1. `java.util.regex.Pattern.compile(pattern)` でコンパイル（PatternSyntaxException をステータス表示）
2. `buffer.getText()` 全体に `Matcher.find()` を繰り返してマッチオフセットリストを構築
3. カーソルの現在オフセットを基準に「次のマッチ」を選択（折り返し対応）
4. カーソルをマッチ先頭へ移動（`moveCursorToOffset`）
5. ハイライトリストを `EditorCanvas` に渡す

`*` / `#`（現存する唯一の `executeSearch()` 呼び出し元。かつては `/` からも呼ばれていた）:
- `wordAtCursor()` でカーソル位置の単語を取得
- `Pattern.quote(word)` を `\\b…\\b` で囲み、完全一致パターンを構築
- `executeSearch()` を呼ぶ

`n` / `N`:
- `currentMatchIdx` を ±1 して折り返し（`% size`）
- カーソルを新しいマッチへ移動

### 検索ハイライト（EditorCanvas）

`EditorCanvas` に `List<int[]> searchHighlights` フィールドを追加。
各要素は `{row, startCol, endCol}`（endCol は exclusive）。

`paintComponent` 内でテキスト描画の前に半透明黄色で矩形を塗る:

```java
// SEARCH_HIGHLIGHT_COLOR = new Color(0xFF, 0xE0, 0x00, 0x90)（半透明黄）
for (int[] h : searchHighlights) {
    int row = h[0], c1 = h[1], c2 = h[2];
    // スクロール範囲外はスキップ
    int xStart = xForCol(line, c1, charWidth) - scrollOffsetX + gutterWidth;
    int xEnd   = xForCol(line, c2, charWidth) - scrollOffsetX + gutterWidth;
    g2.fillRect(xStart, yTop, xEnd - xStart, lineHeight);
}
```

マルチラインマッチは `ModalEditor.updateSearchHighlights()` で行単位のセグメントに分割してから渡す。

### ハイライトクリア（`clearSearchHighlights()`）

`searchMatches`/`currentMatchIdx`（ModalEditor 側の内部状態）と `EditorCanvas.searchHighlights`（実際に画面に描画される矩形リスト）は別々の状態であり、**両方を同時にクリアしないと画面上のハイライトは消えない**。この2つを一括でクリアする `ModalEditor.clearSearchHighlights()` に処理を一本化しており、ハイライトを消す必要がある箇所（SEARCH モードでの Esc キャンセル・バッファ切替・後述の NORMAL モード Esc Esc）は必ずこれを呼ぶこと。`searchMatches` だけをクリアして `canvas.setSearchHighlights(List.of())` を呼び忘れると、内部状態は空なのに画面には前のハイライトが残り続けるバグになる（後述「バッファ切替時のハイライト残留バグ」参照）。

### NORMAL モード Esc Esc（連続2回）で強制ハイライトクリア

- **背景**: NORMAL モードには元々 Esc に何のキーバインドも割り当てられていなかった（`KeymapRegistry` は INSERT/COMMAND/VISUAL系のみ Esc→`enter.normal` を束縛しており、NORMAL 自体は既にそのモードにいるため Esc は無反応だった）。ユーザーから「現在ハイライトを削除する機能がないので、NORMAL モードで Esc を2回押したら強制的にハイライトを削除してほしい」と明示的な依頼があり追加した。
- **実装**: `ModalEditor.processNormalKey()` の先頭付近（2打鍵シーケンス [`pendingSequence`] を消費する既存ブロックより前）で `keyCode == KeyEvent.VK_ESCAPE` を直接判定する。1回目の Esc は `pendingSequence = "ESC"` をセットするだけで何もしない。`pendingSequence` が既に `"ESC"` の状態（＝直前のキーも Esc だった）で2回目の Esc が来たら `clearSearchHighlights()` を呼んで `pendingSequence` をリセットする。
- **Esc は保留中の他シーケンスもキャンセルする**: この判定は `yy`/`dd`/`gg`/`SPC-` 等の2打鍵シーケンスを消費するブロックより前に置いているため、例えば `d`（削除待ち）の直後に Esc を押すと `pendingSequence` は `"ESC"` に上書きされ、保留していた `d` は破棄される（Vim で Esc が保留中のオペレータをキャンセルするのと同じ挙動）。
- **連続でない Esc は1回目扱いにリセットされる**: Esc → 別のキー → Esc の順で押した場合、2つ目の Esc は「1回目」として扱われクリアは発生しない（間の別キー入力で `pendingSequence` が消費されるため）。素早く2回連続で押す必要がある。

### バッファ切替時のハイライト残留バグ（修正済み）

- **症状**: 検索でハイライト表示中に別バッファへ切り替える（`:enew`・`Ctrl+U`/`Ctrl+P` でのバッファ履歴移動・`:e` での別ファイルオープン等）と、切替後の新しいバッファの画面上に**切替前のバッファのハイライト矩形がそのまま残ってしまい**、かつ切替後のバッファで改めて `/` 検索しても前回のパターンが引き継がれることはなかった（`lastSearchPattern` はクリアされないが、画面のハイライトだけが古いバッファの行・列基準のまま取り残される）。
- **原因**: バッファ切替の全経路（`newBuffer()`/`loadFromFile()`/`restoreBuffer()` 等）は共通ヘルパー `resetSearchAndResultState()` を呼んでいたが、このヘルパーは `searchMatches`/`currentMatchIdx`（ModalEditor 側の内部状態）だけをクリアし、`EditorCanvas.searchHighlights`（実際の描画用リスト）を消していなかった。上記「ハイライトクリア」節の通り、この2つの状態は別物であるため、内部状態だけクリアしても画面には反映されない。
- **修正**: `resetSearchAndResultState()` を `clearSearchHighlights()` を呼ぶように変更し、`searchMatches`/`currentMatchIdx`/`canvas.setSearchHighlights(List.of())` の3つを常に同時にクリアするよう一本化した。以後、ハイライトを消す処理を新規に書く場合は必ず `clearSearchHighlights()` を経由すること（`searchMatches = List.of()` を直接書く新しいコードを増やさない）。

### 同じバグが他のバッファ切替経路にも存在していた（Shift+K / grep 等）

- **経緯**: 上記の `resetSearchAndResultState()` 一本化は Ctrl+U/P・`:enew`・`:e` 等の主要なバッファ切替経路をカバーするが、ユーザーから「Shift+K や Grep などのバッファ切替にも対応できているか」と確認があり調査したところ、**`resetSearchAndResultState()` を経由しない別系統のバッファ切替コードが複数存在し、そちらは未対応のままだった**ことが判明した。これらは切替先バッファの `grepResults`/`fileNameResults` を自前でインラインに null クリアしており、共通ヘルパーを呼んでいなかったため見落とされていた。
- **対象と修正**: 以下8箇所すべてに、buffer 差し替え箇所で `clearSearchHighlights()` の呼び出しを追加した（`grepResults`/`fileNameResults` は各メソッドが独自に管理しているため、それらまで巻き込んでクリアする `resetSearchAndResultState()` ではなく、ハイライトだけをクリアする `clearSearchHighlights()` を個別に呼ぶ）。
  - `openTelescopeSelection()`（SPC+f/SPC+b/SPC+/ でのファイルオープン）
  - `switchToRelativeBuffer()`（`buffer.prev`/`buffer.next` キーマップアクション。既定キーからは到達不能だが「既知の未接続・二重定義」1. の通りプラグインからは到達しうる）
  - `executeFileNameSearch()`（`\f` ファイル名検索の疑似バッファ表示）
  - `jumpToFileNameResult()`（ファイル名検索結果からファイルを開く）
  - `executeGrep()`（`gr`/`gR`/`:grep`/`:grep!`/`\g`/`\g!` の疑似バッファ表示。**これがユーザーの言う「Grep」**）
  - `jumpToGrepResult()`（grep結果からファイルを開く）
  - `openJdkSourceBuffer()`（Shift+K で JDK ソース疑似バッファを開く。**これがユーザーの言う「Shift+K」**。`tryJdkMember()`/`lookupJdkDocAndJump()`/`openCSymbolBuffer()` はすべてこのメソッド経由なので個別修正は不要）
  - `closeJdkSourceBuffer()`（`q` で JDK ソース疑似バッファから元バッファへ戻る）
- **`jumpToSymbolLocation()`（Shift+K がプロジェクト内の実ファイルへジャンプする場合）は対応不要だった**: 同一ファイル内ジャンプはバッファを差し替えないため対象外。別ファイルへのジャンプは内部で `loadFromFile()` を呼んでおり、これは既に `resetSearchAndResultState()` 経由でカバー済みだったため。
- **テストでの検証と環境依存の制約**: `test/dev/javatexteditor/search/ProjectSearchTest.java` に `testGrepClearsSearchHighlight`/`testGrepJumpClearsSearchHighlight` を追加し、`EditorCanvas.getSearchHighlights()`（テスト専用ゲッター）でハイライトが実際に消えることを確認した。Shift+K（`openJdkSourceBuffer`/`closeJdkSourceBuffer`）側は `test/dev/javatexteditor/editor/JumpBackTest.java` に `testShiftKIntoJdkSourceClearsSearchHighlight`/`testCloseJdkSourceBufferClearsSearchHighlight` を追加したが、これらは `⑫ openjdk-source-tracing` スキルに記載の通り src.zip が見つからない実行環境ではジャンプ自体が成立しないため、ジャンプ不成立時は SKIP して pass 扱いにする（`OpenjdkSourceTracingTest` と同じ graceful degradation の方針）。

### テストで `EditorCanvas` を使う場合は `System.exit(0)` を忘れない（JVMハングの罠）

- **症状**: `EditorCanvas` のインスタンスを生成するテストで、かつ同一JVM内で `JdkClassIndex.buildSync()`（jrt:/ 走査によるJDKクラス索引構築）も実行するテストクラスは、全テストが `PASS` と出力されて `main()` が最後まで実行されたにもかかわらず、JVM プロセス自体が終了せずハングすることがある（`ps` で見ると当該 `java` プロセスの CPU 時間はほぼ増えず、I/O待ちでもなく単に生き続ける）。`EditorCanvas()` 単体、`JdkClassIndex.buildSync()` 単体はそれぞれ単独では正常終了するため、切り分けが難しい。
- **原因**: `EditorCanvasTest.java` に既存のコメント「EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する」の通り、`EditorCanvas` のコンストラクタは `animTimer.start()`（`javax.swing.Timer`）を無条件に呼ぶ。この Swing Timer 用の内部スレッドが非デーモンスレッドとして残ることがあり、`main()` 終了後も JVM の自然終了を妨げる。単独では発現しないタイミング依存の問題だが、同一プロセス内で他の重い処理（JDK クラス索引構築など）と組み合わさると発現しやすくなる（未解明・環境依存）。
- **対策（既存の確立済みパターンに追従）**: `EditorCanvas` を生成するテストクラスの `main()` の末尾には、成功時も失敗時も必ず `System.exit(...)` を呼ぶこと。`EditorCanvasTest.java` に倣い、失敗時は `System.exit(1)`、成功時は `System.exit(0)` を明示的に呼ぶ（return で自然終了させない）。本バグ修正作業で `TextSearchTest.java`/`ProjectSearchTest.java`/`JumpBackTest.java` の3ファイルに `EditorCanvas` を使うテストを追加した際にこの問題を踏み、同じ対策を適用した。今後 `EditorCanvas` を新規テストで使う場合も同様に対応すること。

---

## Emacs式インクリメンタルサーチ（C-s / C-r、NORMAL/INSERT両モードで有効）

> 以下は2026-07-29午前の初回実装（INSERT専用）時点の設計記録。同日中に NORMAL モードへも
> 拡張された。拡張の経緯・`/` 廃止・Ctrl+R/redo競合の解決は次節「`/` Vim式検索の廃止と
> Emacs式インクリメンタルサーチのNORMALモード拡張（2026-07-29）」を参照。
> 以下のアルゴリズム自体の設計判断（inclusive/exclusive切り替え・Backspaceスタック等）は
> NORMAL/INSERT両モードに共通してそのまま適用されている。

- **背景**: 当初 `/`・`n`/`N` はいずれも Vim 式（文字列入力を Enter で確定してから n/N で候補間移動）だった。ユーザーから「Emacs 式（1文字入力するたびにリアルタイムで最も近い候補へジャンプし、C-s/C-r 連打でさらに次/前の候補へ進める）にしてほしい。ただし INSERT モードの時のみ有効にすること」という明示的な依頼があり、既存の Vim 式検索とは別に追加した（この時点では Vim 式 `/` は NORMAL モードにそのまま残されており、両者は独立した機能として共存していた。後日 `/` 自体が廃止されたのは次節を参照）。
- **Mode を増やさず呼び出し元のモードのまま完結させる設計**: 本家 Emacs の isearch は「エディタの一状態」であって別モードには遷移しない（isearch中も自由に別バッファへは移れないが、モードという概念自体が薄い）。この エディタは Vim ライクなモーダル編集だが、Emacs式検索だけ独自に `Mode.SEARCH` 等の新モードを作ると「特定モードの時のみ有効」という要件をモード遷移で表現する必要が生じて逆に複雑になるため、`ModalEditor.Mode` は増やさず、`emacsIsearchActive`（boolean）という NORMAL/INSERT 内の疑似サブ状態として持つ。`isInsertMode()`/`isNormalMode()` は isearch 中もそれぞれ true のまま（isearchはどちらのモードから起動したかを変えない）。
- **キー横取りは KeymapRegistry を使わず `interceptEmacsIsearch()` で直接判定する**: 「Ctrl+Space→補完トリガー」「Alt+/→単語補完トリガー」という既存の2つの特殊キーも `KeymapRegistry` を経由せず `processInsertKey()` 先頭で `modifiers`/`keyCode` を直接見て判定している（`triggerCompletion()`/`triggerWordCompletion()` 呼び出し）。Emacs式isearchの起動キー（Ctrl+S/Ctrl+R）もこの既存パターンに合わせ、`KeymapRegistry` へのバインド追加はしていない。理由は2つ: (1) isearchは「特定モードの時のみ」有効という要件そのものが `processInsertKey()`/`processNormalKey()` という関数の存在範囲と一致するため、わざわざ登録・解決の間接層を挟む意味がない。(2) isearch起動中は後続のほぼ全キーを isearch 側が横取りする必要があり（`processEmacsIsearchKey()`）、これは「アクション文字列を1つ解決して switch する」という `KeymapRegistry` の設計とは形が合わない（1個のキーではなく「セッション中の全キー入力」を横取りする必要があるため）。
- **カーソル移動基準点は「基準オフセットより厳密に後方/前方」（Vimの `/` と同じ `BufferTextSearch.selectNearest` の厳密比較）を流用する**: ユーザー指定の仕様文言「カーソル位置よりも後方（下方向）にある候補を検索します」「カーソル位置よりも前方（上方向）にある候補を検索します」は、`selectNearest` が forward で `> refOffset`、backward で `< refOffset` と厳密比較する既存の挙動とそのまま一致する（カーソル直下の文字も候補に含めてしまう実装は仕様と食い違うため採用しなかった）。
- **「1文字入力するたびに同じ候補を拡張できる」ための inclusive/exclusive 切り替え**: 上記の厳密比較をそのまま毎回使うと、例えば `foo` を検索中に2文字目3文字目を打つたびに「今カーソルが乗っている候補自身」が基準オフセットと同一になり除外されてしまい、候補が毎回先へ飛んでしまう不具合になる（実装中に実機で確認済み）。これを防ぐため `runEmacsIsearch(refOffset, forward, inclusive)` に `inclusive` フラグを持たせ、`inclusive=true` のときは `forward` なら `refOffset-1`、`backward` なら `refOffset+1` を渡すことで「基準オフセット自身」も候補に含める（`selectNearest` 自体は変更しない）。
  - 1文字入力時（`appendEmacsIsearchChar()`）: **直前に既にマッチが成立していたか**（`currentMatchIdx >= 0 && !searchMatches.isEmpty()`）で inclusive/exclusive を切り替える。isearch開始直後の1文字目（まだ何もマッチしていない）は **exclusive**（＝カーソル位置そのものは候補に含めない。ユーザー仕様の「カーソル位置よりも後方/前方」の原点に忠実にするため）。2文字目以降、既に候補が見つかっている状態でさらに文字を追加する場合は **inclusive**（＝今マッチしている候補自身を拡張できるようにするため）。
  - C-s/C-r 連打（`advanceEmacsIsearch()`）: 検索文字列を変えずに次/前の候補へ進める操作なので常に **exclusive**（同じ候補に留まらず必ず次/前へ進む）。
- **Backspace は「その文字を入力する直前の基準点」へ正確に戻すため、専用のスタック（`isearchLegAnchorHistory`）を使う**: ヒューリスティックな再計算（例:「現在の legAnchor からもう一度inclusive検索」）では、直前の1文字がマッチ失敗（例: `fooX` で候補なし）だった場合に legAnchor が更新されず古い値のまま残ることがあり、そこから単純にinclusive再検索すると偶然正しく戻ることもあるが原理的に保証がないため、`appendEmacsIsearchChar()` で1文字追加するたびに「追加する直前の `isearchLegAnchor`」を `isearchLegAnchorHistory`（`ArrayList<Integer>` をスタックとして使用。新規importを避けるため `ArrayDeque` ではなくこちらを採用）へ push し、Backspace時にpopして正確に復元する。C-s/C-r連打（`advanceEmacsIsearch()`）はこのスタックに積まない（クエリ文字列自体の巻き戻しではなく候補間ナビゲーションのため、範囲外とした。Backspaceで「直前のC-s連打」まで遡って戻す本家Emacsの完全な undo stack は本実装のスコープ外）。
- **検索対象は正規表現ではなくリテラル文字列**（`Pattern.quote(isearchQuery.toString())`）: 本家 Emacs の `isearch-forward`（非regexp版）に合わせた。かつての Vim式 `/` は正規表現対応だったが、1文字ずつ評価するインクリメンタルサーチで正規表現の特殊文字を都度解釈すると入力体験が不安定になる（例: `(` を打った瞬間に構文エラーになる）ため、意図的にリテラル一致にしている。大文字小文字は `executeSearch()`（`*`/`#`用）と足並みを揃え `Pattern.CASE_INSENSITIVE` のまま。
- **ハイライト・`searchMatches`/`currentMatchIdx` は `*`/`#` と同じフィールドを共有する**: 別々の状態を持つと「バッファ切替時のハイライト残留バグ」節で述べた事故が再発するリスクがあるため、既存の `searchMatches`/`currentMatchIdx`/`updateSearchHighlights()`/`clearSearchHighlights()` をそのまま再利用している。ただし `lastSearchPattern`/`lastSearchForward`（`n`/`N` が参照する状態）には一切書き込まない。isearch は独立した `isearchQuery`/`isearchForward` を持ち、NORMALモードの `n`/`N` の挙動には影響しない。
- **Enter（確定）・Esc（キャンセル）・その他キー（未対応キー）の3種類の終了経路**、いずれも終了時に必ず `clearSearchHighlights()` を呼ぶ（前述「バッファ切替時のハイライト残留バグ」と同じ理由: isearch終了後は通常のNORMAL/INSERT入力に戻り、NORMALでの編集やINSERTでの文字挿入でテキストが変化するため、ハイライト矩形を残すとずれて表示される）。
  - Enter（`commitEmacsIsearch()`）: 現在のマッチ位置にカーソルを残したまま通常のNORMAL/INSERT入力へ戻る。
  - Esc（`cancelEmacsIsearch()`）: isearch開始時点のカーソル位置（`isearchOriginOffset`）へ戻してから通常のNORMAL/INSERT入力へ戻る（「開始前の状態に戻す」という、廃止済みのVim式SEARCHモードのEscキャンセルと同じ考え方を踏襲）。
  - それ以外のキー（矢印キー・Ctrl+B等、isearch専用キーのいずれでもないキー）: 本家Emacsが「isearch中に未束縛のコマンドを叩くとisearchを終了させてからそのコマンドを実行する」のに倣い、現在位置はそのままisearchを終了し、同じキーイベントを isearch を開始した側（`mode == Mode.INSERT` なら `processInsertKey()`、それ以外（NORMAL）なら `processNormalKey()`）へ再度渡す（再帰呼び出し。`emacsIsearchActive` は既にfalseになっているため無限ループしない）。
- **ステータス行の表示は既存の `statusMessage` をそのまま利用する**: `syncCanvas()` 側に isearch 専用の分岐は追加していない。isearch中は毎キー入力ごとに `statusMessage` を `"I-search: <query>"`（backward時は `"I-search backward: <query>"`）に上書きしており、NORMAL/INSERTいずれのモードでも他のどの `else if` 分岐（COMMAND/FILESEARCH/TELESCOPE/CLASSPATH_INPUT）にも一致しないため、既存の `else if (!statusMessage.isEmpty())` 分岐でそのままコマンドライン領域に表示される。

---

## `/` Vim式検索の廃止とEmacs式インクリメンタルサーチのNORMALモード拡張（2026-07-29）

- **要望**: ユーザーから「検索アルゴリズムをEmacs方式に標準化したい。よってVimの `/` キーによる検索機能を廃止し、Emacs式インクリメンタルサーチ（C-s/C-r）をNORMALモードでも有効にしてほしい」という明示的な依頼があった。
- **`n`/`N`/`*`/`#` は廃止範囲外と確認済み**: 「Vimの `/` キーによる検索機能」という表現が `*`/`#`/`n`/`N` まで含むか曖昧だったため、`AskUserQuestion` で確認しようとしたところユーザーから先に「`/` を使っての検索機能のみを廃止してください。`#`、`*`、`n`、`N` に関しては廃止する必要はありません」と明示があった。そのため `*`/`#`（`searchWordAtCursor()`）・`n`/`N`（`jumpToNextMatch()`）・`lastSearchPattern`/`lastSearchForward`・`executeSearch()` は一切変更していない。
- **`/` に関するコードを完全削除した**: `Mode.SEARCH` enum値・`enterSearchMode()`・`processSearchKey()`・`searchBuffer`フィールド・`isSearchMode()`/`getSearchBuffer()`アクセサ・`KeymapRegistry`の`/`→`search.enter`バインド・`processKey()`ディスパッチャの`case SEARCH`・`syncCanvas()`の`mode == Mode.SEARCH`分岐をすべて削除した。「使われなくなったが念のため残す」を避け、`n`/`N`/`*`/`#`が使う`executeSearch()`/`searchMatches`等の共有インフラだけを残した（`handleTextPromptKey()`は`\f`/`\g`/F10-F12クラスパス入力が引き続き使うため残置、Javadocから`/`の言及のみ削除）。
- **NORMALモードへの拡張は共通ヘルパーへの抽出で実現した**: `processInsertKey()`冒頭にあった「isearch起動中なら横取り、起動キー(C-s/C-r)ならセッション開始」のロジックを`interceptEmacsIsearch(keyCode, keyChar, modifiers): boolean`として切り出し、`processNormalKey()`冒頭（`handleNormalModeInterrupt()`より前）でも同じヘルパーを呼ぶだけにした。isearchの本体ロジック（`enterEmacsIsearch`/`processEmacsIsearchKey`/`appendEmacsIsearchChar`/`backspaceEmacsIsearch`/`advanceEmacsIsearch`/`runEmacsIsearch`/`commitEmacsIsearch`/`cancelEmacsIsearch`）はモードを一切意識しない設計だったため変更不要だった（唯一の例外が「未対応キーのフォールバック」で、`mode`フィールドを見て`processInsertKey`/`processNormalKey`のどちらへ委譲するかを分岐するようにした。詳細は前節参照）。
- **NORMALモードのCtrl+Rは元々`redo`に割り当て済みだったため、redoをCtrl+Shift+Rへ移動した**: `KeymapRegistry`で`Mode.NORMAL`の`Ctrl+R`は`"redo"`アクションに束縛されていたが、`interceptEmacsIsearch()`はKeymapRegistryより先にNORMAL/INSERT両方で`Ctrl+R`を「isearch後方検索の起動/前候補へ進む」として横取りするため、両立できない（設計上、独自ハードコード判定はKeymapRegistry解決より常に手前で行われる）。`AskUserQuestion`でユーザーに確認しようとしたが応答が無かったため、「redoをCtrl+Shift+Rへ移動する」という選択肢（提示した2択のうち推奨としていた方）を採用して実装し、その旨をユーザーへの返信で明示した。`KeymapRegistry`の該当バインドを`ofCode(VK_R, CTRL_DOWN_MASK, "redo")`から`ofCode(VK_R, CTRL_DOWN_MASK | SHIFT_DOWN_MASK, "redo")`へ変更した。影響を受けたテスト（`KeymapRegistryTest`/`ModalEditorTest`/`KeyboardSimulationTest`/`RobotKeyInputTest`）と、ユーザー向けドキュメント（`docs/manual/02-modal-editing.md`・`docs/manual/10-keybindings-reference.md`・`Tutorial.java`のレッスン4/9・`editor-tutorial` skillのレッスン一覧表）をすべて更新した。
- **isearch起動時に`pendingSequence`を破棄するようにした**: NORMALモードでは`gg`/`dd`/`yy`等の多打鍵シーケンスの1打鍵目が`pendingSequence`に保留される。`interceptEmacsIsearch()`はこのシーケンス消費ブロック（`handlePendingSequence()`）より前で割り込むため、例えば`g`を押して`gg`の完成を待っている最中に`Ctrl+S`を押すと、対処しない限り`pendingSequence="g"`が残ったままisearchへ入ってしまい、isearch終了後に無関係な`g`単押しが古い`gg`待ち状態と結合して暴走する恐れがあった。そこで`enterEmacsIsearch()`の先頭で`pendingSequence = ""`を明示的にクリアするようにした（既存のEsc Esc処理が保留シーケンスを破棄するのと同じ考え方）。回帰テストは`EmacsIsearchTest.testPendingSequenceDiscardedWhenIsearchStarts()`。
- **`/`廃止に伴い、`/`を使って検索ハイライトを作っていた既存の回帰テストを`*`に置き換えた**: `JumpBackTest`（Shift+K関連のハイライト残留バグ回帰テスト）・`ProjectSearchTest`（grep関連の同種テスト）・`SubstituteCommandTest`（`:s//repl/`の空パターン再利用テスト）が`/`でハイライトを作ってから検証する構造だったため、いずれもカーソルを対象単語の位置へ移動してから`*`を押す形に書き換えた（`*`も同じ`lastSearchPattern`/`searchMatches`/highlight系インフラを使うため、テストの意図は変えずに移行できた）。`TextSearchTest`は`/`固有のテスト（SEARCHモード遷移・searchBuffer・正規表現/不正規表現・任意位置からの自由文字列検索等、`*`/`#`では再現できないもの）を削除し、`*`/`#`/`n`/`N`で再現可能なテストのみ`*`ベースへ書き換えて残した。`EmacsIsearchTest`にはNORMALモード版のテスト一式（起動・前方/後方ジャンプ・連打・Backspace・Enter確定・Escキャンセル・ハイライトクリア・未対応キーのフォールバック・pendingSequence破棄）を追加した。

## Ctrl+S連打で検索語がリセットされる不具合の修正（実機AWTキーイベント固有・2026-08-01）

- **症状**: `ModalEditor.processKey()` を1回だけ直接呼ぶ単体テスト（`EmacsIsearchTest`）は全PASSしていたにもかかわらず、実際のGUI（`Main`起動・`GlobalKeyDispatcher`経由の実キー入力）では「`C-s`→キーワード入力→`Enter`を押さず続けて`C-s`」を行うと検索語が消え、新規セッション扱いになってしまう不具合が実機でのみ報告された。
- **調査方法**: ユーザー指示に従い、まず`interceptEmacsIsearch()`/`processEmacsIsearchKey()`/`appendEmacsIsearchChar()`/`advanceEmacsIsearch()`に一時的な`System.out.println`デバッグログを仕込み、Xvfb仮想ディスプレイ上で実際に`dev.javatexteditor.Main`を起動し、別プロセスの`java.awt.Robot`から同一Xディスプレイ経由で実キーイベントを送って再現した（`ModalEditor.processKey()`を直接呼ぶ単体テストでは発生しない、実機のAWTキーイベント列に依存する不具合だったため）。
- **ログで確認できた事実**: `Ctrl+S`は実際には「Ctrlキー単体のKEY_PRESSED（`keyCode=VK_CONTROL`, `keyChar=CHAR_UNDEFINED`, `modifiers=CTRL_DOWN_MASK`）」→「SキーのKEY_PRESSED（同じくCTRL修飾子つき）」という**2つの独立したKEY_PRESSEDイベント**としてOSから届く。isearchセッション中（`emacsIsearchActive=true`）にこの前者（Ctrl単体）が`processEmacsIsearchKey()`へ渡ると、`Ctrl+S`/`Ctrl+R`/Backspace/Enter/Escape/印字可能文字のいずれの分岐にも一致しないため「isearch未対応キー（矢印キー等を想定した分岐）」として扱われ、`commitEmacsIsearch()`が即座に呼ばれて`isearchQuery`がクリアされ`emacsIsearchActive=false`になっていた。直後に届く本来の`S`キーイベントは、既にセッションが終了しているため`enterEmacsIsearch()`（新規セッション開始）として処理され、結果的に検索語が消えていた。
- **原因**: `processEmacsIsearchKey()`が「Ctrl/Shift/Alt等の修飾キー単体のKEY_PRESSED」を考慮しておらず、「isearch専用キーのどれにも一致しない＝未対応の操作」という前提の判定に、修飾キー単体イベントまで巻き込んでしまっていたこと。
- **修正**: `processEmacsIsearchKey()`の冒頭で`keyCode`が`VK_CONTROL`/`VK_SHIFT`/`VK_ALT`/`VK_META`のいずれかなら即`return`し、何もせずセッションを維持するようにした（`ModalEditor.java`）。この4つの定数は「単体では意味を持たない修飾キー」を網羅する（`VK_ALT_GRAPH`は日本語配列等一部環境限定のため、実機再現で確認できた範囲に留め、必要になれば追加検討する）。
- **再発防止テスト**: `EmacsIsearchTest.testBareCtrlKeyDuringIsearchDoesNotResetQuery()`を追加。`ctrlS()`ヘルパー（`VK_S`単発）の前に明示的に`sendCode(ed, KeyEvent.VK_CONTROL, CTRL_DOWN_MASK)`を送ることで、実機のイベント順序を単体テストレベルでも再現できるようにした。既存の`ctrlS()`/`ctrlR()`ヘルパーは変更していない（`VK_S`/`VK_R`単発イベントのみを送る既存の全テストは、本修正が「未対応キー」分岐の範囲を狭めただけなので影響を受けない）。
- **教訓**: `ModalEditor.processKey()`を直接呼ぶ単体テストは「1つの論理キー入力＝1回の`processKey()`呼び出し」という前提を置いているため、実際のAWT/OSが1つの論理キー入力に対して複数のKEY_PRESSEDイベント（修飾キー単体分を含む）を発生させるケースを原理的に検証できない。今後isearchのような「セッション中は特定キー以外を『未対応』として即終了する」設計を新規に追加する場合は、単体テストだけで十分と判断せず、Robot+Xvfb（`RobotKeyInputTest`と同じ手法）での実機検証も行うこと。

---

## 注意点

- **`executeSearch()` は `Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)` で大文字小文字を区別しない**（ユーザー要望により追加）。`\g`/`gr`/`:grep`（`ProjectSearcher`）・`\f`（`FileNameSearcher`、元々CASE_INSENSITIVE）・Alt+/ 単語補完（`WordIndex`、元々大文字小文字無視）・Ctrl+Space 補完（`CompletionIndex`/`CompletionScorer`、元々大文字小文字無視プレフィックスをスコアリング対象に含む）と足並みを揃えた形。大文字小文字を区別する検索が必要になった場合は、既存の `Pattern.compile(pattern)` に戻すのではなく、`(?-i)` インラインフラグや専用の切替オプションを検討すること（挙動を後退させる形の変更はしない）。
- `searchMatches` はバッファ内容が変わっても自動更新しない（`n` 押下時に再計算するため表示がずれることがある。Vim も同様の挙動）
- `lastSearchPattern` はファイルロード時にはクリアしない（Vim 同様、別ファイルを開いても検索を継続できる）
- ただしハイライト（`searchMatches` の内容および `EditorCanvas.searchHighlights`）はファイルロード時・バッファ切替時に `clearSearchHighlights()` 経由でクリアする
- `*`/`#` の単語境界は `\\b` を使う。Java の `\b` は `[a-zA-Z0-9_]` 境界に相当するため、Vim の `iskeyword` デフォルト設定とほぼ一致する
- `*`/`#` で `lastSearchPattern` と `lastSearchForward` を更新することで、後続の `n`/`N` が正しく動く
- `EditorCanvas.getSearchHighlights()` はテスト専用に追加したゲッター。本番コードから読み取り目的で使う想定はない（描画専用の内部状態を外部公開しているのはテストで実際に画面上の残留ハイライトを検証するため）
- Emacs式インクリメンタルサーチ（C-s/C-r、NORMAL/INSERT両モード）のテストは `test/dev/javatexteditor/search/EmacsIsearchTest.java`（57テスト）に分離している。`*`/`#`/`n`/`N` とは別のテストクラス（`TextSearchTest.java`）のまま残しており、統合していない（起動条件・状態遷移が大きく異なるため、1ファイルにまとめるとテストの意図が読み取りにくくなると判断した）。`ModalEditor.isEmacsIsearchActive()`/`getIsearchQuery()`/`isIsearchForward()` はこのテスト専用に追加した公開アクセサ。
