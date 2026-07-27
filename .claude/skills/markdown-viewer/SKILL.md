---
name: markdown-viewer
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、Markdownファイル（.md/.markdown）を開いた際のソース表示と、:viewコマンドで切り替える読み取り専用の閲覧ビュー（見出し下線・リスト正規化・インライン記法除去等）を設計・実装・修正する際に使用する。「Markdownをプレビューしたい」「:viewで見出しやリストを見やすく表示したい」「:markでソースに戻したい」「MarkdownRendererの変換ルールを変えたい」といった相談、またdev.javatexteditor.markdownパッケージやModalEditorの:view/:mark周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# Markdownビューア（`:view`/`:mark`）

## このスキルが解決すること

`.md`/`.markdown`ファイルを開いた直後は、他の全ファイルと同様に**そのままの生ソースを編集可能なテキストとして表示する**（新規のプレビュー機構を割り込ませない）。COMMANDモードで`:view`と入力した場合に限り、現在のバッファがMarkdownファイルであれば、`MarkdownRenderer`が生成した読み取り専用の「閲覧ビュー」（見出しの下線・箇条書きの正規化・インライン記法の除去等で構造を可読化したプレーンテキスト）へ切り替える。`:mark`で元のソース・ファイルパス・カーソル位置へ戻る。

## 前提となる制約（実装方針を決めた理由）

このエディタの本文描画（`EditorCanvas.drawLineWithFullWidthSupport`）は等幅ビットマップフォント（`MiscFixedBold9x15`、ASCII 0x20-0x7Eのみ対応）によるグリッド描画で、以下ができない。

- **フォントスタイルの動的切替**（太字・斜体・見出しの拡大表示）。1文字ごとに色（`Color`）は変えられる（構文ハイライト・error行の赤字描画で実証済み、`uiGlyphCache`参照）が、太さ・サイズは変えられない。
- **任意のUnicode記号の安全な幅表示**。ビットマップフォント非対応の文字（ASCII範囲外）はSwingの`drawString`フォールバックへ回るが、そのフォールバックの実際の描画幅と`EditorCanvas.charCellWidth()`が返す「セル数」が食い違うと表示がずれる。**このエディタは過去に一度この問題を実際に踏んでいる**: telescopeピッカーの選択行マーカーが当初`"▸ "`（Unicode矢印）だったが、ビットマップフォントのセルグリッドに正しく収まらず、ASCIIの`"> "`へ変更した経緯がある（`.claude/skills/gui-rendering-pipeline/SKILL.md`「telescope・ステータス行・補完ポップアップの文字描画をMiscFixedビットマップフォントに統一」節）。

このためMarkdownRendererが出力する記号は**すべてASCII印字可能文字(0x20-0x7E)の範囲に収める**という方針を取った（見出し下線の`=`/`-`、水平線の`-`、引用の`| `、箇条書きの`- `、タスクリストの`[ ]`/`[x]`、すべてASCII）。色分け・フォントスタイル変更による装飾も行わない（構文ハイライト用の`SyntaxHighlighter`/`SourceLanguage`機構は今回あえて拡張・流用しなかった。理由は下記「意図的にスコープ外とした点」を参照）。

## 実装アーキテクチャ

### `dev.javatexteditor.markdown.MarkdownRenderer`（Swing非依存の純粋ロジック）

Markdownソース全文を受け取り、`*view* <ファイル名> — markdown preview（:mark でソース表示に戻る）` というヘッダ行（既存の`*grep*`/`*binary*`/`*class*`等の疑似バッファと同じ規約）+ 空行 + 変換済み本文、を1つの文字列として返す`render(String fileName, String source)`が唯一の公開エントリポイントである。

内部は「フェンスコードブロックの開閉を1行ずつ状態機械で追跡する行走査（`renderBody`）」→「フェンス外の各行をブロック要素として分類する`appendStructuredLine`」→「ブロック内のテキストにインライン記法変換を適用する`applyInline`」の3段構成。

**ブロック要素の変換ルール**（`appendStructuredLine`内で上から順に判定、最初に一致したものを採用）:

| 記法 | 変換後 | 備考 |
|---|---|---|
| ATX見出し `#`〜`######` | H1/H2は本文行+`=`/`-`の下線（setext見出し風）。H3-H6は元の`#`接頭辞をそのまま保持 | 下線の長さは全角文字を2セル換算した表示幅（`visualWidth`、`EditorCanvas.charCellWidth`と同じ判定基準をこのファイル内に複製）。閉じ`#`（`## Done ##`）は`TRAILING_HASHES`で除去 |
| 水平線 `---`/`***`/`___`/スペース区切り | `"-".repeat(RULE_WIDTH)`（60文字） | CommonMark同様、2個以下の同一記号は水平線と判定しない |
| フェンスコードブロック `` ``` ``/`~~~` | フェンス行自体は出力せず、中身を4スペースインデント。中身にはインライン変換を適用しない | 開始と同じ文字種・**同じ長さ以上**の閉じ記号でのみ閉じる（CommonMark仕様どおり。短い閉じ記号はコード内容として扱われる）。開始の言語指定（` ```java `）は無視。閉じずに文書末尾に達した場合は残り全行がコード扱いになる |
| 引用 `>` | `"\| ".repeat(depth)` + インライン変換後の内容 | `>`の連続数（スペース有無どちらでも可）でネスト階層を判定 |
| 箇条書き `-`/`*`/`+` | インデントを保持したまま `"- "` に正規化 | `[ ]`/`[x]`/`[X]`が続く場合はタスクリストとして`[ ]`/`[x]`表示に統一 |
| 番号リスト `1.`/`1)` | 番号・区切り文字はそのまま、内容のみインライン変換 | 行頭以外に現れる数字（例:「item 5. for details」）は誤判定しない（`\s+`が数字直後に必須なため） |
| それ以外 | インライン変換のみ適用しそのまま出力 | 空行もこの分岐を通り空行のまま出力される |

**インライン記法の変換**（`applyInline`。フェンスコードブロック内の行には適用されない）:

1. **インラインコード`` `code` ``を最初に退避する**。Private Use Area文字（``）+ 連番 + `` のプレースホルダに一時置換してから他の変換を行い、最後に元のテキストをそのまま復元する。これを怠ると、例えば `` `__init__` `` のようなコード片が後段の太字正規表現（`__([^_]+)__`）に誤って巻き込まれ `init` に壊されてしまう（実装時にテストで検出し対処した既知の落とし穴。`applyInline`のJavadoc参照）。
2. 画像 `![alt](url)` → `[image: alt] (url)`（altが空なら`[image] (url)`）。
3. リンク `[text](url)` → `text (url)`。画像を先に処理するため、画像変換後の文字列（`] (`の間にスペースが入る）がリンク正規表現で二重変換されることはない。
4. 太字 `**text**`/`__text__` → `text`。
5. 斜体 `*text*`/`_text_` → `text`。`_text_`側は`\b_...\b`で単語境界を要求しており、`snake_case_variable`のような識別子内のアンダースコアを斜体と誤認しない（アンダースコアは`\w`扱いのため識別子内部に単語境界が生じない）。
6. 最後にインラインコードのプレースホルダを元のテキストへ復元。

### `ModalEditor`側の結線

- **`isMarkdownBuffer()`**: `currentFilePath != null && (拡張子が.mdまたは.markdown)`。`isJavaBuffer()`/`isCBuffer()`と同じ規約（ファイルパス未設定の疑似バッファは対象外）。
- **`enterMarkdownView()`（`:view`）**: `markdownViewOwner == buffer`（既にこの閲覧ビューを見ている）なら何もせず案内メッセージのみ。`isMarkdownBuffer()`が`false`なら`"E: not a markdown (.md) file"`。それ以外は現在の`buffer`/`currentFilePath`/カーソル位置を`markdownViewSaved*`へ退避し、`buffer`を`MarkdownRenderer.render()`の結果で差し替え、**`currentFilePath`を`null`にする**。
- **`exitMarkdownView()`（`:mark`）**: `markdownViewOwner != buffer`（閲覧ビューを見ていない）なら`"E: not in markdown view"`。それ以外は`markdownViewSaved*`から`buffer`/`currentFilePath`/カーソル位置を復元する。
- **`markdownViewOwner`は`classFileBufferOwner`/`binaryModeOwner`と同じ「参照一致による自動失効」パターン**を採用した。`buffer`は約25箇所で`buffer = new UndoablePieceTable(...)`と再代入されるため、`:view`後に`:grep`やtelescope等で別バッファへ切り替えると`markdownViewOwner`が指す参照と現在の`buffer`が自然に不一致になり、その状態で`:mark`を実行すると（元のMarkdownソースへ戻さず）正しくエラーになる。個別の切り替え箇所（25箇所超）に何かを追記する必要はない。

### なぜ「読み取り専用プレビュー」を classfile-viewer 方式にしたか（jdk-source 方式ではなく）

このエディタには「現在のバッファをある種の疑似ビューへ差し替え、元に戻せるようにする」パターンが既に2種類ある。

1. **jdk-source疑似バッファ方式**（`savedBuffer`/`inJdkSourceBuffer`）: `currentFilePath`を`title`（説明的な文字列。実ファイルパスではない）のままにする。K/`gr`が開くのはJDK/nativeソースという「別ファイルの参照」であり、万一`:w`されても編集中の実ファイルは壊れない。
2. **classfile-viewer方式**（`classFileBufferOwner`）: `currentFilePath`を`null`にする。`.class`ファイルの構造ビューは「今開いているそのファイル」の代替表示であり、`:w`を許すとバイト列が構造ビューのテキストで上書きされる実害がある。

Markdownビューアは**2.と全く同じ実害のリスク**（`:view`中に`:w`すると、レンダリング後のプレーンテキストで実`.md`ファイルが上書きされてしまう）を持つため、classfile-viewer方式を踏襲し`currentFilePath`を`null`にした。`:view`中に`:w`すると（保存先が無いため）既存の`"E: no file name"`エラーに自然にフォールバックする。新規の「保存禁止フラグ」やキー入力ブロック機構は追加していない（既存の全疑似バッファと同じ「ファイルパスなし＝保存不可」パターンをそのまま踏襲しただけ）。

### 共有バッファ（`:split`）との整合性

`markdownViewSavedBuffer`は`String`ではなく`UndoablePieceTable`の**参照**として退避する（jdk-source/telescope/FILER等の既存の疑似バッファ退避フィールドが2026-07の「共有バッファ」対応で全てString→参照方式に統一された、その方針をそのまま踏襲）。`:view`→`:mark`の往復後、`ed.getBuffer()`が**同一オブジェクト**として戻ることを`MarkdownViewTest.testViewPreservesBufferReferenceThroughRoundTrip`で回帰テストしている。これにより、同じ`.md`ファイルを`:split`で複数ペインに開いている状態で片方だけ`:view`しても、もう片方のペインの編集は失われず、`:mark`で戻れば再び共有状態に復帰する。

## 意図的にスコープ外とした点

- **色分け・フォントスタイルによる装飾はしない**。既存の`SyntaxHighlighter`/`SourceLanguage`（Java/C構文ハイライト）機構は「原文の各トークンに色を付ける」設計であり、Markdownビューアのように「記法記号そのものを取り除いて別の文字列を生成する」用途とは性質が異なる。将来的に色分けを追加する場合は、レンダリング後のテキストに対する新しいスタイル注釈の仕組み（行/範囲ごとの色情報を`EditorCanvas`へ渡す経路）が別途必要になる。本実装ではその経路自体を作らなかった。
- **エスケープ記法（`\*`/`\_`/`` \` ``等）は解釈しない**。CommonMarkのバックスラッシュエスケープに対応するには、構造判定の正規表現自体をエスケープ考慮に書き換える必要があり、今回のヒューリスティックなregexベース実装のスコープを超えるため見送った。
- **参照形式リンク（`[text][ref]` + `[ref]: url`）・脚注・定義リストは非対応**。インライン形式`[text](url)`のみ対応する。
- **テーブル（`\| a \| b \|`）の専用整形はしない**。特別な処理をせずそのまま通過させる（元々モノスペースグリッドで十分読めるため、パイプ位置の揃え直し等は行わない）。
- **ネストしたブロック要素の組み合わせ（引用の中の箇条書き、箇条書きの中の引用等）は非対応**。1行につき「見出しorルールor引用or箇条書きor番号リストorそれ以外」のいずれか1種類にのみ分類する単純な行走査であり、入れ子構造の解析はしない。
- **他の疑似バッファ機構との重ね掛けは未検証**（CLAUDE.md「既知の未接続・二重定義」項目5と同種）。例えば`:view`実行後にFILER（`:cd`）やjdk-source（K）を経由して別バッファへ移った場合の`markdownViewSaved*`との相互作用は、`markdownViewOwner`の参照一致による自動失効に委ねており、個別の組み合わせを網羅的にテストしてはいない。
- **`:view`中のキー入力はブロックしない**（既存の全疑似バッファと同じ設計。読み取り専用とは「保存先が無い」ことを意味し、バッファ自体は編集できてしまう。`MarkdownViewTest.testEditsWhileViewingDoNotAffectRestoredSource`で「編集はできるが`:mark`後の復元には影響しない」ことを確認済み）。

## テスト

- `test/dev/javatexteditor/markdown/MarkdownRendererTest.java`（72テスト、自作mainハーネス方式）: 見出し（H1-H6・全角下線幅・閉じ`#`・インライン記法混在）・水平線・フェンスコードブロック（バッククォート/チルダ・言語指定・未閉鎖・閉じ長さ不足）・引用（単一/ネスト/スペース無し）・箇条書き（正規化・ネストインデント・タスクリスト）・番号リスト（誤判定防止含む）・インライン記法（太字/斜体/コード/リンク/画像、`snake_case`保護・`` `__init__` ``保護・複数コード片・画像とリンクの独立変換）・境界値（空文字列・段落そのまま・空行保持・末尾改行）・複数構造混在の統合テストを検証。
- `test/dev/javatexteditor/editor/MarkdownViewTest.java`（26テスト）: `:view`でのレンダリング結果一致・`currentFilePath`のnull化・カーソル先頭リセット、`:mark`でのソース/パス/カーソル復元、バッファ参照が往復で同一オブジェクトのまま保たれること（共有バッファ整合性）、非Markdownファイルでの`:view`エラー、`:view`せず`:mark`した場合のエラー、`:view`の冪等性（2回押しても壊れない）、別バッファへ切り替え後の`:mark`自動失効、`:view`中の`:w`が実ファイルを書き換えないこと、`.markdown`拡張子の受理、プレビュー中の編集が復元後のソースに影響しないことを検証。
