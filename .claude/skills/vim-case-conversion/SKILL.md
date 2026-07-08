---
name: vim-case-conversion
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、Vim式の大文字小文字変換（NORMALモードの`~`・`guu`/`gUU`/`g~~`、VISUAL/VISUAL_LINE/VISUAL_BLOCKモードの`u`/`U`/`~`）を設計・実装する際に使用する。「大文字小文字を変換したい」「~でカーソル位置の1文字をトグルしたい」「guu/gUU/g~~で行全体を変換したい」「VISUAL選択範囲やVISUAL BLOCKの矩形だけを小文字化/大文字化したい」といった相談、またtoggleCaseUnderCursor/applyCaseToLines/applyCaseToSelection/applyCaseToBlock・CaseOp周辺やgu/gU/g~のpendingSequence処理を触る作業に着手する前に、必ず最初に参照すること。"
---

# vim-case-conversion — Vim式大文字小文字変換

## このスキルが解決すること

Vimの大文字小文字変換コマンド系統を実装する。対応する構文:

| 構文 | モード | 対象範囲 | 実装メソッド |
|---|---|---|---|
| `~` | NORMAL | カーソル位置の1文字。実行後カーソルが右へ1つ進む | `toggleCaseUnderCursor()` |
| `guu` | NORMAL | 現在行全体を小文字化 | `applyCaseToLines(cursorRow, cursorRow, CaseOp.LOWER)` |
| `gUU` | NORMAL | 現在行全体を大文字化 | `applyCaseToLines(cursorRow, cursorRow, CaseOp.UPPER)` |
| `g~~` | NORMAL | 現在行全体をtoggle case | `applyCaseToLines(cursorRow, cursorRow, CaseOp.TOGGLE)` |
| `u` | VISUAL | 選択範囲（文字単位）を小文字化 | `applyCaseToSelection(CaseOp.LOWER)` |
| `U` | VISUAL | 選択範囲（文字単位）を大文字化 | `applyCaseToSelection(CaseOp.UPPER)` |
| `~` | VISUAL | 選択範囲（文字単位）をtoggle case | `applyCaseToSelection(CaseOp.TOGGLE)` |
| `u`/`U`/`~` | VISUAL_LINE | 選択行全体（複数行可） | `applyCaseToLines(r1, r2, op)` |
| `u`/`U`/`~` | VISUAL_BLOCK | 矩形の列範囲(c1〜c2)のみ、行ごと | `applyCaseToBlock(op)` |

---

## スコープの決定: operator-pending モーションは実装しない

本家Vimの `gu`/`gU`/`g~` は本来 **オペレータ**であり、`guiw`（カーソル位置の単語）・`gu$`（行末まで）・
`gu3j`（3行下まで）のように任意のモーションと組み合わせられる。しかし②`modal-editing-engine`の
SKILL.mdが明記する通り、本プロジェクトはオペレータ組み合わせ（`d3j`/`c2w`等）を
「v6以降の候補のまま未着手」として既にスコープ外に確定させている。

この既存方針と矛盾させないため、`gu`/`gU`/`g~` は **doubled-letter方式**（`yy`/`dd`と同型の
「同じ文字を2回押すと現在行に適用」）のみを実装した。Vim本家でも `guu`/`gUU`/`g~~` はこの
doubled-letter形式の正式なシンタックス（linewiseオペレータの慣例）であり、独自拡張ではない。
`guiw`・`gu$`・`gu{count}{motion}` 等の任意モーション対応は本Skillのスコープ外。

VISUAL系3モードでの `u`/`U`/`~`（選択範囲全体に適用）は本家Vimと完全に同じ挙動であり、
モーション処理を経由しないため上記の制約を受けない。

---

## 実装アーキテクチャ

### KeymapRegistry への登録

`KeymapRegistry.Mode.NORMAL` に `~` → `"case.toggle.char"` を1件のみ追加した
（`gu`/`gU`/`g~` は多打鍵シーケンスのため`KeymapRegistry`を経由しない。後述）。

`Mode.VISUAL` / `Mode.VISUAL_LINE` / `Mode.VISUAL_BLOCK` の3モードそれぞれに、同じ3アクション名
（`"case.lower"` / `"case.upper"` / `"case.toggle"`）で `u`/`U`/`~` を登録した。アクション名を
3モード共通にすることで、`ModalEditor`側は `caseOpFor(String action)` という1つの変換関数を
3箇所の switch から共用できる（③`vim-substitution`スキルの`translateVimReplacement`と同様、
「同じ変換ロジックを複数モードで再利用する」設計）。

```java
private CaseOp caseOpFor(String action) {
    return switch (action) {
        case "case.lower" -> CaseOp.LOWER;
        case "case.upper" -> CaseOp.UPPER;
        default -> CaseOp.TOGGLE; // "case.toggle"
    };
}
```

### `gu`/`gU`/`g~` の3打鍵シーケンス（pendingSequence）

`g`（`"goto.pending"`）は既に `gg`/`gr`/`gR`/`gv` で使われている1文字プレフィックスであり、
`gu`/`gU`/`g~` はその延長で2打鍵目を消費し、`pendingSequence`をさらに`"gu"`/`"gU"`/`"g~"`へ
遷移させたうえで3打鍵目（同じ文字の繰り返し）を待つ、**3段階のpendingSequence**として実装した。
既存の`" g"`/`" i"`（SPCリーダーの2打鍵目待ち）と同型の「文字列全体を`seq.equals(...)`で判定する」
方式を踏襲している。

```java
// 3打鍵目の完了判定を先に置く（理由は次節「つまずきポイント」参照）
if (seq.equals("gu") && keyChar == 'u') { applyCaseToLines(cursorRow, cursorRow, CaseOp.LOWER); return; }
if (seq.equals("gU") && keyChar == 'U') { applyCaseToLines(cursorRow, cursorRow, CaseOp.UPPER); return; }
if (seq.equals("g~") && keyChar == '~') { applyCaseToLines(cursorRow, cursorRow, CaseOp.TOGGLE); return; }
// 2打鍵目の遷移判定
if (seq.equals("g") && keyChar == 'u') { pendingSequence = "gu"; statusMessage = "gu-"; return; }
if (seq.equals("g") && keyChar == 'U') { pendingSequence = "gU"; statusMessage = "gU-"; return; }
if (seq.equals("g") && keyChar == '~') { pendingSequence = "g~"; statusMessage = "g~-"; return; }
```

不完全なシーケンス（`gu`の後に無関係なキー）は、既存の`yw`（`y`の後に`w`）等と同じ「フォールスルーして
そのキーを通常処理する」挙動になる（②スキルの多打鍵シーケンス節に記載の既存動作。専用のキャンセル処理は
追加していない）。

### 変換ロジック（`CaseOp`・`convertCase`）

```java
private enum CaseOp { UPPER, LOWER, TOGGLE }

private String convertCase(String s, CaseOp op) {
    return switch (op) {
        case UPPER -> s.toUpperCase(Locale.ROOT);
        case LOWER -> s.toLowerCase(Locale.ROOT);
        case TOGGLE -> { /* 1文字ずつ Character.isUpperCase/isLowerCase で判定して反転 */ }
    };
}
```

`Locale.ROOT`を使う理由: 既存コード（`ModalEditor`内の`toLowerCase(Locale.ROOT)`使用箇所）と同じく、
実行環境のデフォルトロケール（トルコ語の`i`/`İ`問題等）に挙動が左右されないようにするため。

### 3つの適用範囲ヘルパー（indentLines/indentBlock/replaceBlockCharと同型）

| ヘルパー | モデルにした既存メソッド | 対象 |
|---|---|---|
| `applyCaseToLines(r1, r2, op)` | `indentLines()` | 行全体。「行ごとに`getLines()`を取り直してから delete+insert する」パターンをそのまま踏襲 |
| `applyCaseToSelection(op)` | `getSelectedText()`/`deleteSelected()` | 文字単位の選択範囲（offsetベース、両端inclusive→exclusive変換も同じ規約） |
| `applyCaseToBlock(op)` | `replaceBlockChar()` | 矩形の列範囲(c1〜c2)のみ、行ごとに独立してsubstring変換 |

新しい探索・削除・置換ロジックを一切書き下ろしていない点が重要: 既存の3系統（linewise/charwise/blockwise）
それぞれの「範囲の求め方」は完成済みのメソッド群とまったく同じ計算式を再利用し、「その範囲に対して何をするか」
だけを差し替えた（indentLines/replaceBlockCharは文字列を別の文字列に置き換える、substitution/applyCaseToXxxは
変換関数を適用する、という同じ骨格）。

### VISUAL系モードでのモード遷移・カーソル位置

`saveLastVisualFromCurrentMode()` → `mode = Mode.NORMAL` という既存の`"yank"`/`"indent.right"`ケースと
完全に同じ順序を踏襲した。カーソル位置の扱いも既存パターンをそのまま流用:

- VISUAL（charwise）: `applyCaseToSelection()`内部で`moveCursorToOffset(start)`を呼び、選択開始位置へ戻す
  （`"yank"`ケースと同じ）。
- VISUAL_LINE: `cursorRow = r1; cursorCol = 0;`（`"yank"`ケースと同じ）。
- VISUAL_BLOCK: `applyCaseToBlock()`内部で`cursorRow = r1; cursorCol = c1;`を設定し、呼び出し側で
  `clampCursorForNormal()`を呼ぶ（`"delete"`/`"indent.right"`ケースと同じ）。

### NORMAL `~` のカーソル移動

Vim既定の`'notildeop'`（`~`はオペレータではない単純トグル）に合わせ、`toggleCaseUnderCursor()`は
1文字変換後にカーソルを右へ1つ進める。行末では②スキルの「NORMALモードのカーソル列は
`col <= lineLen - 1`にクランプされる」既存規約にそのまま従うため、追加のクランプ処理は不要
（`Math.min(cursorCol + 1, Math.max(0, lineLen - 1))`という通常のクランプ式で自然に止まる）。
非アルファベット文字（数字・記号）の上で押しても変換は起きないが、カーソルは進む
（Vim本家と同じ挙動）。**count前置き（`3~`）は未対応**（②スキルが汎用カウント付きモーションを
スコープ外としているため、`~`だけ特別扱いはしていない）。

---

## つまずきポイント

> ⚠️ **`prev == 'g'`（`seq.charAt(0)`）は`seq`が`"g"`でも`"gu"`でも常に`'g'`になる**。
> `gu`の2打鍵目遷移判定（`pendingSequence`を`"g"`から`"gu"`へ進める処理）を
> `seq.equals("gu")`（3打鍵目の完了判定）より**前**に置くと、3打鍵目で`u`を押した瞬間に
> 2打鍵目遷移判定が先にマッチしてしまい、`pendingSequence`が`"gu"`のまま再セットされるだけで
> 永久に完了しない（`guu`が何も起こさないバグになる）。実装時に一度この順序で踏んだため、
> 完了判定を必ず遷移判定より前に置くこと。新しい3段階以上のpendingSequenceを追加する開発者は
> 同じ罠を踏みやすいため、詳細は `references/pending-sequence-ordering.md` を参照。

> ⚠️ **VISUAL BLOCKのテストで短い行を`j`で経由すると列がクランプされる**（㉕`modal-visual-block-selection`
> スキル記載済みの既知の制約と同一）。`applyCaseToBlock()`自体の実装には影響しないが、
> テストで矩形の対角を指定する際に短い中間行を`hjkl`で経由すると`moveCursor()`が
> desired-column記憶なしにその場で列をクランプするため、`ed.setCursor(row, col)`で
> 直接ジャンプして矩形の対角を作ること（`CaseConversionTest.testVisualBlockLowerShortLineUnaffectedOutsideRange`
> で実際に踏んだ）。

---

## テスト（完了条件）

`test/dev/javatexteditor/editor/CaseConversionTest.java`（23テスト、自作mainハーネス）:
- NORMAL `~`: トグル+カーソル前進・非アルファベットでの無変化+前進・行末でのクランプ
- NORMAL `guu`/`gUU`/`g~~`: 現在行のみに適用・カーソル行を移動してからの実行・
  不完全シーケンス（`gu`の後に無関係キー）のフォールスルー
- VISUAL: `u`/`U`/`~`・NORMALへの復帰・カーソルが選択開始位置へ戻ること
- VISUAL_LINE: 複数行選択・選択行以外は無変化
- VISUAL_BLOCK: 矩形の列範囲のみ変換・短い行が範囲外になるクランプ

変更後は `./scripts/build.sh && ./scripts/test.sh` で全テストPASSを確認する
（`ScrollTest`の恒常FAIL2件はCLAUDE.md記載の既知の問題で本Skillと無関係）。

## 関連スキル

- ② `modal-editing-engine`: pendingSequence機構・NORMALのカーソルクランプ規約・
  operator-pendingモーションのスコープ外判断の一次資料
- ④ `keymap-conflict-resolution`: `~`/`u`/`U`のキー空き状況確認（衝突なし。NORMALの`u`は
  既存の`undo`のため`~`のみ追加、VISUAL系3モードの`u`/`U`/`~`はすべて未使用だった）
- ㉕ `modal-visual-block-selection`: 矩形の列範囲計算・短い行のクランプ・
  「短い行を経由するテストはsetCursor()を使う」というテスト作法
- ㉖ `vim-substitution`: 「複数モードで1つの変換関数を共有する」設計の先例（`translateVimReplacement`）
