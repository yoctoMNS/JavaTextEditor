---
name: modal-visual-block-selection
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、Vimの矩形選択（VISUAL BLOCK, Ctrl+V）のモード追加・ヤンク/削除/ペースト/矩形挿入(I/A)/矩形変更(c)/矩形置換(r)・描画を設計・変更する際に使用する。「矩形選択を追加したい」「Ctrl+Vで複数行の同じ列だけ編集したい」「矩形選択中にI/Aで全行同時入力したい」「ブロックペーストで行が足りないときどうするか」といった相談、またModalEditor.javaのVISUAL_BLOCK関連メソッドやEditorCanvasのvisualBlockMode描画を触る作業に着手する前に、必ず最初に参照すること。"
---

# modal-visual-block-selection — Vim矩形選択（VISUAL BLOCK）

## このスキルが解決すること

Vimの `Ctrl+V` による矩形選択（複数行の同じ列範囲だけを選択・ヤンク・削除・貼付けする機能）を、
既存の `VISUAL`（文字単位）/ `VISUAL_LINE`（行単位）と並ぶ第3のモードとして実装する。
②`modal-editing-engine`の基本編集モード系統（`KeymapRegistry`経由）に属する。

## モード追加の位置づけ

`KeymapRegistry.Mode` / `ModalEditor.Mode` の両方に `VISUAL_BLOCK` を追加した。
②のSkillが定める判断基準どおり、矩形選択はh/j/k/l等の**編集操作としてキーマップ経由でカスタマイズさせたい**
モードのため、SEARCH/TELESCOPE等のバイパス方式ではなくKeymapRegistry方式を採用している。

- `NORMAL` の `Ctrl+V` → `enter.visual.block`（anchorRow/anchorCol=cursor位置、mode=VISUAL_BLOCK）
- `VISUAL_BLOCK` 内: h/j/k/l移動・ESC・`Ctrl+V`（トグルでNORMAL復帰）・y・d・I（左端矩形挿入）・A（右端矩形挿入）・c（矩形変更）・r（矩形置換、2打鍵）
- ESC早期リターンガード（`processKey()`冒頭）は VISUAL/VISUAL_LINE と同様 VISUAL_BLOCK も対象に含める

## 列の扱い: 文字インデックスそのまま（表示幅は使わない）

矩形の列範囲は `anchorCol`/`cursorCol` という**既存の文字インデックス**をそのまま使う。
`VISUAL`（文字単位）の `getSelectedText()`/`deleteSelected()` が同様に文字オフセットベースで、
表示幅（全角・タブ）を意識していないことに合わせた設計判断。理由:
- 既存のカーソル移動・選択ロジック全体が文字インデックスで統一されており、矩形選択だけ表示幅ベースにすると
  「同じ列」の意味がVISUALモードと矛盾する
- タブは実際にはソフトタブ（スペース4個, `INDENT_UNIT`）のみが挿入される設計のため、実タブ文字の矩形整列は
  そもそも本プロジェクトの入力経路では発生しにくい

列の両端は他モードと同じ「両端含む（inclusive）」規約: `c1=min(anchor,cursor)`, `c2=max(anchor,cursor)`。
抽出・削除では `end = min(c2+1, line.length())` として文字列操作用にexclusive変換する。

**描画のみ**表示幅を考慮する。`EditorCanvas.xForCol()`（全角=2セル・半角=1セルを計算済み）を
そのまま流用し、矩形の左右端のピクセル位置を行ごとに計算する。短い行は `xForCol` が実在する文字までしか
進まないため、矩形ハイライトは自然にその行の末尾で止まる（パディングなしで見た目上は正しい）。

## 短い行の扱い（ヤンク・削除 vs ペースト）

- **ヤンク/削除**: 短い行（矩形範囲が行末を超える）は `start`/`end` を `line.length()` にクランプするだけ。
  存在しない列は「何もない」として扱い、空文字列がその行のセグメントになる。パディングはしない
  （ヤンク後にペーストし直すときに元の行の短さを再現するため、余計な空白を持ち込まない）。
- **ペースト（`pasteBlock()`）**: 貼り付け先の行が挿入列より短い場合は不足分を半角スペースで埋めてから挿入する。
  Vimの矩形貼付けと同じ挙動（貼付け位置に文字がない場合は空白で埋めて位置を合わせる）。

## 新規行自動生成（ペースト時のみ）

ヤンクした矩形が3行、貼付け先が2行しかない場合、**新規行を自動生成**する（ユーザー要件で確定済み）。
`pasteBlock()` は `while (targetRow >= lines.length) buffer.insert(buffer.length(), "\n");` で
不足行をバッファ末尾に追加してから該当行にテキストを挿入する。この新規行は空行として生成されるため、
挿入列より前の部分は上記のパディングで埋まる。

## ヤンク/ペーストの単位

`YankType` に `BLOCK` を追加した（既存: `CHAR`, `LINE`）。`yankRegister` には各行のセグメントを
`"\n"` 区切りで文字列として保存する（改行区切りテキストという表現は `LINE` と同じ形式だが、
`pasteAfter()`/`pasteBefore()` は `yankType` で分岐して `pasteBlock(insertCol)` を呼ぶため、
`LINE` と混同されることはない）。`insertCol` は `p`(after)で`cursorCol+1`、`P`(before)で`cursorCol`
（文字単位ペーストの列オフセット規約に合わせた）。

## 描画（EditorCanvas）

- `visualBlockMode` フラグを新設（`visualMode`/`visualLineMode` と並列）。`clearSelection()` でも
  必ずリセットすること（VISUAL_LINEのリセット漏れパターンを踏襲）。
- `drawSelectionHighlight()` は `visualBlockMode` を最優先分岐にした: 行ごとにanchor/cursorのどちらが
  行頭かを判定する既存の文字単位ロジック（`colStart = (row==r1)?c1:0` 等）ではなく、
  **全行で固定された `c1`/`c2` の列範囲**を使う。これが文字単位VISUALとブロックVISUALの本質的な違い。
- ステータス行ラベルに `"-- VISUAL BLOCK --"` を追加（`visualBlockMode` を `visualLineMode`/`visualMode` より先に判定）。

## 矩形挿入（I/A）: 「r1行だけ入力→ESCで複製」方式

Vimの実際の挙動を踏襲し、**INSERTモードを1つだけ増やすのではなく既存のINSERTモードを再利用**する。
`I`/`A`/`c` を押すとカーソルを矩形の左端(`I`,`c`)/右端+1(`A`)の列に置いて**通常のMode.INSERTへ遷移**し、
ユーザーは選択範囲の最初の行（`blockInsertR1`）にだけ文字を入力する。ESC/Ctrl+]でNORMALへ戻る
（`processInsertKey`の`enter.normal`/`save.from.insert`ケース）タイミングで`finalizeBlockInsertIfActive()`を呼び、
`blockInsertStartOffset`〜現在カーソル位置の文字列を「入力されたテキスト」として捕捉し、
`blockInsertR1+1`〜`blockInsertR2`行の同じ列へ複製挿入する。

**新モードを増やさない理由**: ②`modal-editing-engine`は「編集操作としてカスタマイズしたいモードは
KeymapRegistry経由の新モードに」としているが、矩形挿入の文字入力そのものは既存のINSERT処理
（補完・自動インデント・Ctrl+W等）をすべてそのまま使いたいため、専用モードを作らず状態フラグ
（`blockInsertActive`等）でINSERTモードの終了処理にフックする方式を選んだ。二重実装を避けるためのトレードオフ。

### I と A の非対称: パディングの有無

- `I`（`blockInsertPad=false`）: 複製先の行が挿入列に届かない（短い）場合は**スキップ**する（パディングしない）。
- `A`（`blockInsertPad=true`）: 複製先の行が短い場合は**半角スペースで埋めてから**挿入する。
- 実際のVimの矩形挿入の挙動（Iは短い行を無視、Aは短い行を空白で埋めて追記）に合わせた。
- `c`（change）は`I`と同じ`pad=false`（既存のVISUAL BLOCK削除の直後に列頭へ挿入するため、パディングの必要性がIと同じ）。

### 複数行入力（Enter）で複製を諦める

矩形挿入中に改行（`insert.newline`）が入力されたら`blockInsertAborted=true`をセットし、
ESC時の`finalizeBlockInsertIfActive()`は複製を行わずに終了する。矩形挿入は「1行だけ入力してN行に複製」という
単一行入力を前提にした機能のため、複数行にまたがった入力の複製先・列位置の意味が定義できないことによる
意図的なスコープの簡略化（Vim本家はこの場合エラーを出さず特殊な扱いをするが、本プロジェクトでは
「複製しない」という単純なフォールバックにとどめている）。

## 矩形置換（r）: pendingSequence を使った2打鍵

`r`は「次に押した1文字」で矩形内の全文字を置換する（複数文字選択でも全て同じ文字になる）。
`yy`/`dd`と同じ`pendingSequence`機構を流用し、`processVisualBlockKey()`の**先頭**で
`pendingSequence.equals("r")`を判定して次のキーを生キー比較で消費する（`KeymapRegistry`は経由しない）。
ESCによるキャンセルは`processKey()`冒頭のVISUAL系ESC早期リターンガードが`pendingSequence`ごとクリアして
NORMALへ抜けるため、`processVisualBlockKey()`側に専用のキャンセル分岐は不要（このガードが`r`待ちの
キャンセルも兼ねる）。

## テスト（完了条件）

- `test/dev/javatexteditor/editor/ModalEditorTest.java` に追加（12テスト）:
  進入・ESC脱出・hjkl移動・矩形ヤンク・矩形削除・ペースト時の新規行自動生成・
  矩形挿入I/A・Iの短い行スキップ・矩形変更c・矩形置換r・Enterによる複製中断。
- `I`/`A`テストの「短い行をIがスキップする」検証は、キー移動（j/j）で短い行を経由すると
  `moveCursor()`が列をその場でクランプしてしまう（desired-column記憶がない）ため、
  プラグインAPIの`setCursor(row,col)`で直接カーソルを移動させて検証している
  （短い行を経由せず直接ジャンプすることで、選択範囲だけ列を保持する状況を作れる）。
- 既知の恒常FAIL（`ScrollTest` halfPageUp系2件、CLAUDE.md記載）は本変更と無関係のため対象外。
- 変更後は `./scripts/build.sh && ./scripts/test.sh` で確認する。

## 矩形選択の `>`/`<`（インデント、2026-07 追加）: 本家Vimと異なる独自仕様

- 本家 Vim では blockwise Visual の `>`/`<` も linewise と同じ「選択行全体をシフトする」挙動になる
  （インデントは本質的に行単位の概念であり、列の概念を持ち込まないため）。
  しかし本プロジェクトでは要件により **「矩形領域(列 c1 以降)だけをシフトする」独自仕様**を採用した
  （`ModalEditor.indentBlock(r1, r2, c1, left, count)`）。
  - 右シフト: 各行の列 `c1` の位置に `count*shiftwidth` 幅ぶんのインデント文字列を挿入する
    （`c1` に届かない行・空行は対象外）。
  - 左シフト: 列 `c1` の直前にある空白文字（スペース/タブ）を `count*shiftwidth` 幅ぶんだけ手前から
    除去する（タブの幅換算は簡略化して `tabstop` を一律加算しているだけで、列位置に応じた正確な
    展開幅計算はしていない）。
  - 行頭の既存インデント（`c1` より前の部分）には一切触れない。これが linewise `>`/`<`
    （`ModalEditor.indentLines()`、行頭からのシフト）との本質的な違い。
- この設計は `②modal-editing-engine` の SKILL.md にも記録済み。実際の Vim 挙動に合わせたい場合は
  `indentBlock()` を呼ばず `indentLines()` に統一すればよいが、その場合は今回の要件
  （矩形領域だけのシフト）を満たさなくなるため、変更前にユーザーに確認すること。

## 未実装（スコープ外）

- レジスタ指定・矩形の `$`（行末まで矩形を伸ばす）修飾・複数文字置換パターン等は未着手。
- 矩形挿入中のUndo/Redoの単位化（複製された複数行への挿入は行ごとに別々のバッファ操作になる）は
  他のVISUAL BLOCK操作（y/d）と同様に未対応（① `piece-table-delete-and-undo.md` 参照の既存トレードオフを踏襲）。

## 関連スキル

- ② `modal-editing-engine`: モード追加の基本ルール（processKeyパイプライン・ESCガード・syncCanvas）
- ⑤ `gui-rendering-pipeline`: `xForCol`・全角幅計算等、描画側の前提
- ④ `keymap-conflict-resolution`: `Ctrl+V` のキー競合有無（本実装時点で衝突なし）
