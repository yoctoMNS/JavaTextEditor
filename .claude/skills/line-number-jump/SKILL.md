---
name: line-number-jump
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、COMMANDモードで`:X`（Xは行番号）と入力してX行目へジャンプする機能を設計・実装する際に使用する。「行番号ジャンプを追加したい」「:42のような数値コマンドをどう解析するか」「行番号の範囲外/0以下をどうエラー表示するか」といった相談、またexecuteCommand内の数値コマンド判定やjumpToLineNumber周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# 行ジャンプコマンド（`:X`）

## このスキルが解決すること

COMMAND モード（`:` から始まる行）で、数値のみのコマンド `:X`（Xは1始まりの行番号）を
入力すると、カーソルをX行目へ移動する。Vimの`:42`（行番号ジャンプ）相当。

## 実装アーキテクチャ

### コマンド解析（`ModalEditor.executeCommand`）

`executeCommand(cmd)` の switch チェーンに、`cmd` が数字のみで構成されるかを判定する分岐を追加する。
既存の `handleSubstituteCommand`（`vim-substitution` スキル参照）が `N,Ms/.../.../ ` のような
「数字,数字+s」の形を専用に処理しているため、**カンマを含まない純粋な数字列のみ**を本機能の対象とする
ことで衝突を避ける（`SUBSTITUTE_RANGE_PATTERN`は`\d+,\d+s.*`なので、`,`を含まない`\d+`のみの文字列とは
そもそもマッチせず、`handleSubstituteCommand`は`false`を返して素通りする。追加の除外ロジックは不要）。

```java
} else if (cmd.matches("\\d+")) {
    jumpToLineNumber(Integer.parseInt(cmd));
}
```

判定は既存の `sPart_isSubstitute`／`SUBSTITUTE_RANGE_PATTERN` と同じ「switch チェーンの中で
文字列パターンにマッチしたら専用メソッドを呼ぶ」という確立済みのスタイルに揃えている。

### `jumpToLineNumber(int oneBasedLine)`

- 引数はユーザーが `:` で入力した**1始まりの行番号**（Vimの慣例）。
- **バリデーション**: `oneBasedLine < 1` または `oneBasedLine > 総行数` はどちらもエラーとし、
  `statusMessage = "E: invalid line number"` を設定してカーソルは一切動かさない
  （`SubstituteCommandTest.testNoPreviousVisualSelectionError` 等、既存のエラーパターンに合わせて
  バッファ・カーソルへの副作用なしでエラーメッセージだけを返す設計を踏襲）。
- 妥当な場合は `cursorRow = oneBasedLine - 1`（0始まり内部表現へ変換）。
- **カーソル列は`0`に固定する**。`moveFileStart()`（`gg`）と同じ「行の先頭へ」という挙動に揃えており、
  Vimの「その行の最初の非空白文字へ」という厳密な挙動（`^`相当の列移動）は本実装のスコープ外とした
  （このプロジェクトの`gg`/`G`も列移動は単純に`0`または行末に固定しており、非空白スキップは
  他のどのジャンプコマンドでも実装していないため、`:X`だけ特別扱いする理由がない）。
- 移動後の画面スクロールは既存の`syncCanvas()`内`canvas.ensureCursorVisible(cursorRow)`が
  自動的に処理する（`moveFileEnd`等と同じで、本機能専用のスクロール処理は追加しない）。

### 総行数の数え方

`getLines().length`（`String[] getLines()`は既存の`buffer.getText().split("\n", -1)`ラッパー、
`moveFileEnd()`等と同じソース）を使う。空文字列のバッファは`getLines().length == 1`（要素0個の空行1つ）
になる点に注意（`String.split(..., -1)`の仕様）。よって空バッファでも`:1`は有効な行ジャンプになる。

## テスト

`test/dev/javatexteditor/editor/LineJumpTest.java`（自作mainハーネス方式、`SubstituteCommandTest.java`と
同じ`sendCommand`/`pressKey`/`typeString`/`check`ヘルパーを複製して使用）。境界値
（`:1`・`:総行数`・`:0`・負の値・総行数超過・数字以外の混在文字列でジャンプが起きないこと）を検証する。
