---
name: modal-visual-block-selection
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、Vimの矩形選択（VISUAL BLOCK, Ctrl+V）のモード追加・ヤンク/削除/ペースト・描画を設計・変更する際に使用する。「矩形選択を追加したい」「Ctrl+Vで複数行の同じ列だけ編集したい」「ブロックペーストで行が足りないときどうするか」といった相談、またModalEditor.javaのVISUAL_BLOCK関連メソッドやEditorCanvasのvisualBlockMode描画を触る作業に着手する前に、必ず最初に参照すること。"
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
- `VISUAL_BLOCK` 内: h/j/k/l移動・ESC・`Ctrl+V`（トグルでNORMAL復帰）・y・d
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

## テスト（完了条件）

- `test/dev/javatexteditor/editor/ModalEditorTest.java` に追加（6テスト）:
  進入・ESC脱出・hjkl移動・矩形ヤンク・矩形削除・ペースト時の新規行自動生成。
- 既知の恒常FAIL（`ScrollTest` halfPageUp系2件、CLAUDE.md記載）は本変更と無関係のため対象外。
- 変更後は `./scripts/build.sh && ./scripts/test.sh` で確認する。

## 未実装（スコープ外）

- 矩形挿入（Vimの `I`/`A` を矩形選択中に押して全行同時にINSERTするコマンド）は未実装。
  現状は `y`/`d` によるヤンク・削除・その後の通常`p`/`P`ペーストのみ対応。
- レジスタ指定・矩形の `c`（change）・`r`（置換）等は未着手。

## 関連スキル

- ② `modal-editing-engine`: モード追加の基本ルール（processKeyパイプライン・ESCガード・syncCanvas）
- ⑤ `gui-rendering-pipeline`: `xForCol`・全角幅計算等、描画側の前提
- ④ `keymap-conflict-resolution`: `Ctrl+V` のキー競合有無（本実装時点で衝突なし）
