---
name: vim-substitution
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、Vim式の`:s`置換コマンド（現在行・`%`（全行）・`'<,'>`（Visual選択範囲）・`N,M`（行番号範囲）・`.,+N`・magic正規表現（`\(`\)`\+`\?`\{n,m}`\|`\<`\>`）・置換文字列の`\1`/`&`/`\u`\U`\l`\L`\e`\E`・`g`/`i`/`c`/`e`フラグ）を設計・実装する際に使用する。「置換機能を追加・変更したい」「:s/pattern/replacement/の構文をどう解析するか」「VISUALモードで`:`を押すと`'<,'>`が自動入力される挙動」「置換文字列の`\1`や`&`の扱い」「Vimのmagic正規表現をJavaに変換したい」「\u\Uで大文字小文字変換したい」「:s ... /cで1件ずつ確認しながら置換したい」といった相談、またdev.javatexteditor.substituteパッケージ（VimSubstituteCommandParser/VimRegexTranslator/VimReplacementBuilder/VimSubstituteExecutor）やModalEditorのhandleSubstituteCommand周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# Vim式置換コマンド（`:s`）

## このスキルが解決すること

COMMAND モード（`:` から始まる行）で Vim の `:s`（substitute）コマンド系統を実装する。
対象範囲（現在行・全行・Visual 選択範囲・行番号範囲・`.,+N`）× Vim magic正規表現パターン ×
置換文字列（後方参照・大文字小文字変換）× フラグ（`g`/`i`/`c`/`e`）の組み合わせを解析し、
`UndoablePieceTable` 上の行単位の delete+insert に変換する。

対応する構文:

| 構文 | 範囲 | 契機 |
|---|---|---|
| `:s/pattern/repl/[flags]` | カーソル行のみ | NORMAL モードで `:` |
| `:%s/pattern/repl/[flags]` | 全行 | NORMAL モードで `:` の後 `%s...` と入力 |
| `:N,Ms/pattern/repl/[flags]`（1始まり） | N行目〜M行目 | NORMAL モードで `:` の後 `N,Ms...` と入力 |
| `:.,+Ns/pattern/repl/[flags]` | カーソル行〜N行先 | NORMAL モードで `:` の後 `.,+Ns...` と入力 |
| `:'<,'>s/pattern/repl/[flags]` | 直前の Visual 選択範囲の行 | VISUAL/VISUAL_LINE/VISUAL_BLOCK モードで `:` を押すと自動的に `'<,'>` が入力された状態で COMMAND モードへ入る |

---

## 2026-08-01 改訂: magic変換対応版（新実装）とレガシー版が併存する

このスキルは当初「Vimのvery magic等の正規表現モード切り替えには対応しない。パターンは常に
Java の `Pattern` 構文として直接解釈される」という単純な方針で実装されていた
（`ModalEditor.executeSubstitute`/`translateVimReplacement`、以下「レガシー実装」）。

その後、Vimのデフォルト（magic）モードの正規表現記法（`\(` `\)` `\+` `\?`/`\=` `\{n,m}` `\|`
`\<` `\>` など）と、置換文字列側の `\u` `\U` `\l` `\L` `\e` `\E`（大文字小文字変換）・`c`
（確認しながら置換）フラグへ対応する「新実装」（`dev.javatexteditor.substitute` パッケージ）
を追加した。**本番の呼び出し口（`ModalEditor.handleSubstituteCommand`）はこの新実装に
一本化されている。** レガシー実装（`ModalEditor.executeSubstituteLegacy`/
`translateVimReplacementLegacy`/`handleSubstituteCommandLegacy`、`Legacy` サフィックス付き）は
本番の呼び出しパスからは外してあり、新実装の単体テスト（`SubstituteCommandTest.
testLegacyReferenceStillMatchesForPlainJavaRegexPatterns`）が「magic変換を伴わないプレーンな
Java正規表現パターンでは新旧の結果が一致する」ことを検証するための参照実装としてのみ残す。

新規に何かを実装・修正する際は、以下の「新実装アーキテクチャ」節を正とすること
（レガシー実装節はそのまま参照専用として下に残してある）。

---

## 新実装アーキテクチャ（本番の呼び出しパス）

4クラス構成で責務を分離する（すべて `dev.javatexteditor.substitute` パッケージ、
`ModalEditor` からは疎結合）:

1. **`VimSubstituteCommandParser`** — コマンド文字列（例: `"%s/foo/bar/gc"`）を
   `RangeSpec`（sealed interface: `WholeFile`/`CurrentLine`/`VisualRange`/`LineRange`/
   `CursorPlus`）・パターン・置換文字列・フラグ文字列に分解する。範囲プレフィックスの
   実際の行番号への解決（Visual選択履歴やカーソル行の参照など、エディタの状態が必要な
   部分）は行わず、`ModalEditor.resolveSubstituteRange(RangeSpec)` に委ねる
   （パーサーをエディタの状態から独立させ、単体テスト可能にするため）。
2. **`VimRegexTranslator`** — Vim magicモードの正規表現記法をJavaの `Pattern` 構文へ
   変換する（詳細は次節）。
3. **`VimReplacementBuilder`** — 置換文字列側の `\1`〜`\9`・`&`・`\u`/`\U`/`\l`/`\L`/`\e`/`\E`
   を、`Matcher` から1件ずつ `group(n)` を取り出して自前で組み立てる。`Matcher.replaceAll`の
   `$1`構文は使わない（大文字小文字変換を内蔵していないため）。
4. **`VimSubstituteExecutor`** — 上記2つを組み合わせ、範囲内の各行に対してマッチ・置換を
   行う。確認なし一括実行の `execute(...)` と、`c` フラグ用に1件ずつ確認しながら進める
   `beginConfirm(...)`（`ConfirmSession` を返す）の2つの入口を持つ。

**`VimSubstituteExecutor` 自身は Vim magic 変換を行わない** — `execute`/`beginConfirm` に渡す
`javaRegex` 引数は呼び出し側（`ModalEditor`）が変換済みのJava正規表現文字列である。これは
「空パターン（`s//repl/`）は直前検索パターン（`lastSearchPattern`）を再利用する」という
Vimの挙動と、`lastSearchPattern` フィールドの実体（①`text-search` skillの `*`/`#` 単語検索が
`\b\Qfoo\E\b` のような **既にJava正規表現構文** で書き込む共有フィールド）が食い違うために
必要な区別である。ユーザーがその場で `:s/pat/rep/` と明示的に書いたパターンのみ
`VimRegexTranslator.translate()` に通し、空パターンで `lastSearchPattern` を再利用する場合は
変換をスキップしてそのままJava正規表現として使う。この区別を怠ると、
`lastSearchPattern` に含まれる `\Q`/`\E`（Javaのクォート境界）がmagic変換の「未知の `\X` は
リテラルのXとして扱う」ルールに巻き込まれて壊れる（実装時に実際に踏んだ不具合）。

### `VimRegexTranslator`（Vim magic → Java正規表現）

Vimの「magic」モード（デフォルト）のみ対応。「very magic」（`\v`）・「very nomagic」
（`\V`）・「nomagic」モードは対象外。

| Vim記法（magic） | 変換後（Java） | 備考 |
|---|---|---|
| `\(` `\)` | `(` `)` | グループ化 |
| `\+` | `+` | 1回以上 |
| `\?` / `\=` | `?` | 0または1回 |
| `\{n,m}` | `{n,m}` | 繰り返し回数指定（開くカッコのみエスケープが要る点はVim本家と同じ） |
| `\|` | `\|` | OR |
| 無エスケープの `(` `)` `+` `?` `{` `\|` | エスケープしてリテラル扱い | Vim magicでは無エスケープはリテラル文字そのもの |
| `\d` `\w` `\s` `\D` `\W` `\S` `\b` `\B` `\n` `\t` | 同じ | Java と同じ意味 |
| `\<` `\>` | `\b` | 単語境界（開始/終了の区別はJavaには無いため近似） |
| `^` `$` | 同じ | 行頭・行末（無エスケープのまま） |
| `\.` `\*` `\^` `\$` `\[` `\]` `\\` | そのまま（エスケープ維持） | リテラルエスケープはJavaも同じ記法 |
| `[...]`（文字クラス内部） | そのまま通す | クラス内の `+` `?` 等を誤って処理しないよう、走査中は「クラス内」フラグで素通りする |
| その他未知の `\X` | リテラルの `X` | 未定義のVimエスケープは寛容に文字通り解釈する |

1文字ずつ走査する専用クラスとして実装（文字クラス `[...]` の中は素通しするフラグ管理のみ
持つ。ネストしたクラスや `[^...]` の否定などVimの文字クラス構文の細部までは踏み込まない）。

### `VimReplacementBuilder`（置換文字列の展開）

| Vim側入力 | 展開内容 | 備考 |
|---|---|---|
| `\1`〜`\9` | `Matcher.group(n)` | 存在しない・マッチしなかった（optional）グループは空文字列扱い |
| `\0` / `&` | `Matcher.group(0)`（マッチ全体） | `&` は無エスケープ時のみ特殊、`\&` はリテラル |
| `\u` | 直後の1文字だけ大文字化 | 1回限り（消費後は元の大小文字状態に戻る） |
| `\l` | 直後の1文字だけ小文字化 | 同上 |
| `\U` | 以降を `\e`/`\E` または置換文字列末尾まで大文字化し続ける | 範囲指定 |
| `\L` | 以降を `\e`/`\E` または置換文字列末尾まで小文字化し続ける | 同上 |
| `\e` / `\E` | `\U`/`\L` の範囲指定を終了する | `\u`/`\l` の一回限り状態も同時にクリアする |
| `\\` | リテラルの `\` | エスケープ |
| その他未知の `\X` | リテラルの `X` | パターン側と同じ寛容な解釈 |

`\u`/`\l`（一回限り）は `\U`/`\L`（範囲指定）が有効な間でも優先され、直後の1文字だけを
上書きしてから範囲指定状態へ戻る（例: `\U\1 \l\2` で1つ目のグループは全体大文字化、
2つ目のグループは先頭だけ小文字化）。後方参照・`&` で複数文字を挿入する場合、大文字小文字の
状態はグループ内の文字ごとに順次適用される（`\u\1` はグループの先頭1文字だけを大文字化する）。

### `VimSubstituteCommandParser`

`RangeSpec` は sealed interface（`WholeFile`/`CurrentLine`/`VisualRange`/`LineRange`/
`CursorPlus`）。`ModalEditor.resolveSubstituteRange(RangeSpec)` が現在のカーソル行・
`lastVisualValid`/`lastVisualAnchorRow`/`lastVisualCursorRow`（gv 機能と共有）・総行数を使って
実際の `[r1, r2]`（0始まり・両端含む）へ解決する。`'<,'>` で Visual 選択履歴が無い場合は
`E: no previous visual selection` を表示して終了する。

区切り文字は `sPart.charAt(1)`（`s` の次の1文字。既存の `:w s/.../.../ `
（`applyRegexSubstituteToPath`）と同じ流儀で `/` 以外の任意の1文字を区切り文字として許容する）。
`String.split(Pattern.quote(delimiter), 3)` で `[pattern, replacement, flags]` に分割する
（既存コードと同じ `limit=3` 方式。flags 部分に区切り文字が紛れ込むケースは考慮しない）。

範囲は最終的に `[0, lineCount-1]` にクランプする（範囲外指定は Vim も同様にエラーになるが、
本実装ではクランプして寛容に扱う。CLAUDE.md の「学習目的のシンプルさ」を優先し、厳密な
Vim エラー再現よりも壊れにくさを優先する判断）。

### フラグ

- `g` — 行内の全マッチを置換。無ければ各行最初の一致のみ（Vimのデフォルト挙動）。
- `i` — `Pattern.CASE_INSENSITIVE` を付与する。
- `c` — 1件ずつ確認しながら置換する（後述の確認モード）。
- `e` — マッチが無くてもエラーメッセージを表示しない。
- `&`（直前フラグの再利用）は未対応（要望があれば別途スキル更新すること）。

### `c` フラグ（確認しながらの置換, `Mode.SUBSTITUTE_CONFIRM`）

`VimSubstituteExecutor.beginConfirm(...)` が返す `ConfirmSession` を使い、`Mode.CONFIRM_NEW_FILE`
と同じ「専用モード + y/n 相当のキー処理」パターンを踏襲した新規モード
`Mode.SUBSTITUTE_CONFIRM` で実装する。

- `ConfirmSession` は行配列の**コピー**を内部に保持し、`advance()` で次の一致を探し、
  `applyYes()`/`applyNo()`/`applyAllRemaining()`/`quit()` のいずれかで進める（Vimの
  `y`/`n`/`a`/`q` 相当。`l`（最後の1件だけ置換して終了）は未対応、要望があれば追加）。
  実際のバッファ（`UndoablePieceTable`）への delete+insert 反映は、セッション終了時
  （`ModalEditor.finishSubstituteConfirm()`）にまとめて変更行だけ書き戻す。確認の途中経過は
  `ConfirmSession` 内のコピー上でのみ完結させ、ユーザーが `ESC`/`q` で中断した場合は
  それまでに `y` で確定した行だけが反映される。
- ステータス行には `"replace with " + 提案テキスト + " (y/n/a/q)?"` を表示する。
- `!global`（`g` フラグ無し）の場合は1行につき最初の一致のみ確認対象になる点はレガシー版・
  一括実行版と同じ。

### 実行本体（一括実行 `VimSubstituteExecutor.execute`）

行ごとに `Matcher.find(pos)` を手動ループし、`VimReplacementBuilder.build(m, vimReplacement)`
で得た置換テキストを `StringBuilder` へ追記していく（`Matcher.appendReplacement` は
使わず、同等のことを自前でやる。理由は `VimReplacementBuilder` が生成するのは
「展開済みの実テキスト」であり、`$1` 等のJava置換構文ではないため）。1行の内容を丸ごと
置き換えるため、パターン中の `^`/`$` は自然に行頭・行末に一致し、`.` はデフォルトで改行を
跨がない。

実行後:
- 1件もマッチしなかった場合 `statusMessage = "E: pattern not found: " + pattern"`（`e`フラグ時は
  メッセージを出さない）
- 1件以上あれば `statusMessage = "<件数> substitutions on <行数> lines"`、カーソルを最後に
  変更された行の行頭へ移動する（Vim も最終変更行へカーソルを移動する）
- `pattern` が空文字列でなければ `lastSearchPattern = pattern` を更新する（Vim は `:s` の
  パターンを次回の `n`/`N` にも使い回せるようにする。①`text-search` skill の
  `lastSearchPattern` フィールドと共有。ここに書き込む値は変換前の生のユーザー入力＝
  vim-magic文字列である点に注意。次回 `n`/`N` 側がこれをどう解釈するかは①のスコープ）

### Undo の粒度

専用のグルーピング機構は追加しない。`UndoablePieceTable` には `beginGroup`/`endGroup`
のような機構がそもそも存在せず、`indentLines()`（Visual `>`/`<`）も同じ制約の中で
「変更された行1行 = 1回の `buffer.delete`+`buffer.insert` = 実質2回のスナップショット」
という粒度のまま実装されている。`c` フラグの確認セッションも、セッション終了時に変更行
それぞれへ同じ粒度で書き戻す。本タスク単独で undo グルーピング機構を新設することは
スコープ外とする。

---

## レガシー実装（参照専用・本番からは呼ばれない）

以下は 2026-07 に実装された最初のバージョンの設計メモで、`ModalEditor` 内に
`handleSubstituteCommandLegacy`/`executeSubstituteLegacy`/`translateVimReplacementLegacy`
として現存する。新実装の単体テストが「magic変換の影響を受けないプレーンなパターン」で
新旧の結果が一致することを確認するための参照実装としてのみ残しており、本番の
`handleSubstituteCommand` からは呼び出されない。**新規の実装・修正はレガシー実装ではなく
上記の新実装アーキテクチャ側に対して行うこと。**

- パターンは常に Java の `Pattern` 構文として直接解釈される（vim magic変換なし）。
- 置換文字列の変換（`translateVimReplacementLegacy`）は `\1`〜`\9` → `$1`〜`$9`、
  `&` → `$0`、`\&`/`\\` のエスケープのみに対応し、`Matcher.replaceAll`/`replaceFirst` に
  そのまま渡す方式（`\u`/`\U`/`\l`/`\L`/`\e`/`\E` 大文字小文字変換は無い）。
- `c`/`e` フラグ、`.,+N` 範囲には対応しない。

---

## 注意点

- **区切り文字は `/` 以外にも対応するが、Vim の「very magic」等の正規表現モード切り替え
  （`\v`）には対応しない**（新実装でも同じ。`VimRegexTranslator` は magic モードのみ扱う）。
- **`'<,'>` は Visual の種別（CHAR/LINE/BLOCK）に関わらず常に行単位の範囲として扱う**。
  これは本家 Vim も同じ挙動（Visual Block で数列だけ選択していても `:s` は選択範囲の
  行全体が対象になる）。
- **範囲外・逆順（`r1 > r2` になり得るケースは `Math.min`/`Math.max` で必ず正規化する）**。
- **空バッファ・0行の状態では `r1==r2==0` として扱い、`getLines()` が空配列を返す場合は
  ループが即座に終わり「0 substitutions」となる**（例外を投げない）。
- **`lastSearchPattern` は既にJava正規表現構文（①`text-search`の単語検索が書き込む）である
  ため、空パターン再利用時はmagic変換を通さない**（前述）。ユーザーが明示的に打った
  パターンのみ変換する。
- テストは `test/dev/javatexteditor/editor/SubstituteCommandTest.java`（`ModalEditor` 経由の
  結合テスト、`c`フラグの確認モード・レガシー実装との比較を含む）と
  `test/dev/javatexteditor/substitute/VimRegexTranslatorTest.java`・
  `VimReplacementBuilderTest.java`（変換ロジック単体の網羅テスト）に自作ハーネスで追加する
  （⑦`editor-testing-strategy` 準拠）。
