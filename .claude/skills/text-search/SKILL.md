---
name: text-search
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、Vim式のバッファ内文字列検索（/パターン検索・*と#の単語検索・n/Nジャンプ・正規表現・ハイライト）を設計・実装する際に使用する。「検索機能を追加・変更したい」「検索ハイライトの描画」「n/Nの折り返し挙動」といった相談、またSEARCHモードやsearchMatches周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# テキスト内文字列検索（Vim 式）

## このスキルが解決すること

NORMAL モードからバッファ内の文字列を検索し、マッチ位置へカーソルをジャンプさせる。
Vim の `/` パターン検索・`*`/`#` 単語検索・`n`/`N` 次/前マッチジャンプを実装する。

---

## キーバインド一覧

| キー | モード | 動作 |
|---|---|---|
| `/` | NORMAL | SEARCH モードへ入り、パターン入力を開始する |
| Enter | SEARCH | 入力したパターンで前方検索を実行し NORMAL へ戻る |
| Esc | SEARCH | 検索をキャンセルして NORMAL へ戻る |
| `n` | NORMAL | 最後の検索方向へ次のマッチへジャンプ（折り返しあり） |
| `N` | NORMAL | 最後の検索の逆方向へジャンプ（折り返しあり） |
| `*` | NORMAL | カーソル位置の「単語」を **下方向（後方）** へ完全一致検索 |
| `#` | NORMAL | カーソル位置の「単語」を **上方向（前方）** へ完全一致検索 |
| Esc Esc（連続2回） | NORMAL | 検索ハイライトを強制的にクリアする |

---

## 実装アーキテクチャ

### モード追加

`ModalEditor.Mode` に `SEARCH` を追加する。
SEARCH モード中はステータスバーに `/<入力中パターン>` を表示する。

```java
private enum Mode { NORMAL, INSERT, COMMAND, VISUAL, VISUAL_LINE, SEARCH }
```

### 状態フィールド（ModalEditor）

```java
private final StringBuilder searchBuffer = new StringBuilder();
private String   lastSearchPattern = "";
private boolean  lastSearchForward = true;
// 各要素: {offset, length}（buffer.getText() 上の絶対位置）
private List<int[]> searchMatches = List.of();
private int currentMatchIdx = -1;
```

### 検索ロジック

`/` 入力時:
1. SEARCH モードへ遷移、`searchBuffer` をクリア
2. Enter が押されたら `executeSearch(pattern, forward=true)` を呼ぶ

`executeSearch(pattern, forward)`:
1. `java.util.regex.Pattern.compile(pattern)` でコンパイル（PatternSyntaxException をステータス表示）
2. `buffer.getText()` 全体に `Matcher.find()` を繰り返してマッチオフセットリストを構築
3. カーソルの現在オフセットを基準に「次のマッチ」を選択（折り返し対応）
4. カーソルをマッチ先頭へ移動（`moveCursorToOffset`）
5. ハイライトリストを `EditorCanvas` に渡す

`*` / `#`:
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

## 注意点

- `searchMatches` はバッファ内容が変わっても自動更新しない（`n` 押下時に再計算するため表示がずれることがある。Vim も同様の挙動）
- `lastSearchPattern` はファイルロード時にはクリアしない（Vim 同様、別ファイルを開いても検索を継続できる）
- ただしハイライト（`searchMatches` の内容および `EditorCanvas.searchHighlights`）はファイルロード時・バッファ切替時に `clearSearchHighlights()` 経由でクリアする
- `*`/`#` の単語境界は `\\b` を使う。Java の `\b` は `[a-zA-Z0-9_]` 境界に相当するため、Vim の `iskeyword` デフォルト設定とほぼ一致する
- `/` と `*`/`#` で `lastSearchPattern` と `lastSearchForward` を更新することで、後続の `n`/`N` が正しく動く
- `EditorCanvas.getSearchHighlights()` はテスト専用に追加したゲッター。本番コードから読み取り目的で使う想定はない（描画専用の内部状態を外部公開しているのはテストで実際に画面上の残留ハイライトを検証するため）
