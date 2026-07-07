---
name: vim-substitution
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、Vim式の`:s`置換コマンド（現在行・`%`（全行）・`'<,'>`（Visual選択範囲）・`N,M`（行番号範囲）・正規表現・`g`/`i`フラグ）を設計・実装する際に使用する。「置換機能を追加・変更したい」「:s/pattern/replacement/の構文をどう解析するか」「VISUALモードで`:`を押すと`'<,'>`が自動入力される挙動」「置換文字列の`\1`や`&`の扱い」といった相談、またexecuteSubstitute周辺やCOMMANDモードの`s`プレフィックス処理を触る作業に着手する前に、必ず最初に参照すること。"
---

# Vim式置換コマンド（`:s`）

## このスキルが解決すること

COMMAND モード（`:` から始まる行）で Vim の `:s`（substitute）コマンド系統を実装する。
対象範囲（現在行・全行・Visual 選択範囲・行番号範囲）× 正規表現パターン × 置換文字列 × フラグ（`g`/`i`）
の組み合わせを解析し、`UndoablePieceTable` 上の行単位の delete+insert に変換する。

対応する構文:

| 構文 | 範囲 | 契機 |
|---|---|---|
| `:s/pattern/repl/[flags]` | カーソル行のみ | NORMAL モードで `:` |
| `:%s/pattern/repl/[flags]` | 全行 | NORMAL モードで `:` の後 `%s...` と入力 |
| `:N,Ms/pattern/repl/[flags]`（1始まり） | N行目〜M行目 | NORMAL モードで `:` の後 `N,Ms...` と入力 |
| `:'<,'>s/pattern/repl/[flags]` | 直前の Visual 選択範囲の行 | VISUAL/VISUAL_LINE/VISUAL_BLOCK モードで `:` を押すと自動的に `'<,'>` が入力された状態で COMMAND モードへ入る |

---

## 実装アーキテクチャ

### VISUAL 系モードでの `:` キー

`KeymapRegistry` に `Mode.VISUAL`/`Mode.VISUAL_LINE`/`Mode.VISUAL_BLOCK` それぞれへ
`':'` → `"enter.command.visual"` を追加する（既存の NORMAL モードの `"enter.command"` とは
別アクション名にする。Visual 側は事前に選択範囲を保存し `commandBuffer` を初期化する必要が
あるため、単純に COMMAND モードへ遷移するだけの NORMAL 側とロジックを共有できない）。

`processVisualKey`/`processVisualLineKey`/`processVisualBlockKey` の switch に追加:

```java
case "enter.command.visual" -> {
    saveLastVisualFromCurrentMode(); // gv と同じ既存メソッドを再利用（'<,'>の実体はこの保存値）
    commandBuffer.setLength(0);
    commandBuffer.append("'<,'>");
    mode = Mode.COMMAND;
}
```

`'<,'>` の実体は新規フィールドを持たず、㉕矩形選択などで既に実装済みの
`lastVisualAnchorRow`/`lastVisualCursorRow`/`lastVisualValid`（gv 機能用）をそのまま読む。
これらは Visual 系モードを抜けるたびに `saveLastVisualFromCurrentMode()` で更新されるため、
新しい状態管理を追加する必要がない。

### コマンド解析（`ModalEditor.executeCommand`）

`executeCommand(cmd)` の switch チェーンの先頭付近に、置換コマンドかどうかを判定する
`handleSubstituteCommand(cmd)`（範囲プレフィックスを剥がして `s` + 区切り文字から始まるかを
判定し、そうでなければ `false` を返して既存の分岐へフォールスルーする）を追加する。
既存の `"sp"`/`"split"` との衝突は起きない（`s` の直後が英数字の `split` は `s<非英数字区切り>`
パターンにマッチしないため）。

範囲プレフィックスの剥がし方（`parseSubstituteRange(String cmd)`）:

1. `%` で始まる → 全行 `(0, lineCount-1)`
2. `'<,'>` で始まる → `lastVisualValid` が false なら `E: no previous visual selection` を表示して終了。
   true なら `(min(lastVisualAnchorRow, lastVisualCursorRow), max(...))`
3. 正規表現 `^(\d+),(\d+)(s.*)$` にマッチ → 1始まりの行番号を 0 始まりに変換
4. それ以外 → カーソル行のみ `(cursorRow, cursorRow)`

範囲は最終的に `[0, lineCount-1]` にクランプする（範囲外指定は Vim も同様にエラーになるが、
本実装ではクランプして寛容に扱う。CLAUDE.md の「学習目的のシンプルさ」を優先し、厳密な
Vim エラー再現よりも壊れにくさを優先する判断）。

### `s/pattern/replacement/flags` の解析

区切り文字は `pattern.charAt(1)`（`s` の次の1文字。既存の `:w s/.../.../ `
（`applyRegexSubstituteToPath`）と同じ流儀で `/` 以外の任意の1文字を区切り文字として許容する）。
`String.split(Pattern.quote(delimiter), 3)` で `[pattern, replacement, flags]` に分割する
（既存コードと同じ `limit=3` 方式。flags 部分に区切り文字が紛れ込むケースは考慮しない）。

- `pattern` が空文字列 → `lastSearchPattern`（①`text-search` skill の `/` 検索と共有するフィールド）
  を使う。Vim の「空パターンは直前の検索パターンを再利用する」挙動に合わせた。
- `flags` に `g` を含む → 行内の全マッチを置換（`Matcher.replaceAll` 相当）。含まない場合は
  行内の最初のマッチのみ（`Matcher.replaceFirst` 相当、Vim のデフォルト挙動）。
- `flags` に `i` を含む → `Pattern.CASE_INSENSITIVE` を付与してコンパイルする。
- `c`（確認しながら置換）・`&`（直前フラグの再利用）等の高度なフラグは未対応（対話的な
  確認 UI が必要になり本タスクのスコープ外。要望があれば別途スキル更新すること）。

### 置換文字列の Vim → Java 変換（`translateVimReplacement`）

Vim の後方参照（`\1`〜`\9`）・マッチ全体（`&`）は Java の `Matcher` の置換構文
（`$1`〜`$9`・`$0`）と異なるため、そのまま `Matcher.replaceAll`/`replaceFirst` に渡すと
壊れる。変換ルール:

| Vim 側入力 | 変換後（Java 置換文字列） | 備考 |
|---|---|---|
| `&`（エスケープなし） | `$0`（マッチ全体） | Vim のデフォルト挙動 |
| `\&` | リテラルの `&` | `\` によるエスケープ |
| `\1`〜`\9` | `$1`〜`$9` | 後方参照 |
| `\0` | `$0` | マッチ全体（`&` の別記法） |
| `\\` | リテラルの `\`（Java側は `\\\\` として埋め込む） | エスケープされたバックスラッシュ |
| `$`（変換元にリテラルで含まれる） | `\$` にエスケープ | Java の `Matcher` 置換で `$` は特殊文字のため |
| その他の `\X` | リテラルの `X` として扱う | 未知のエスケープは文字通りに解釈（Vim の寛容な挙動に合わせる） |

1文字ずつ走査する専用メソッドとして実装し、`java.util.regex.Matcher.quoteReplacement()` は
使わない（`quoteReplacement` は「全体をリテラル化する」機能であり、`\1` 等の後方参照を
部分的に活かしたい今回の用途には使えないため、自前でエスケープ規則を実装する）。

### 実行本体（`executeSubstitute`）

範囲 `[r1, r2]` の各行に対して:

```java
for (int row = r1; row <= r2; row++) {
    String[] lines = getLines();
    if (row >= lines.length) break; // 置換で行数が変わることはない（改行を跨がないため安全）
    String line = lines[row];
    Matcher m = compiledPattern.matcher(line);
    String replaced = global ? m.replaceAll(javaReplacement) : m.replaceFirst(javaReplacement);
    if (!replaced.equals(line)) {
        int lineStart = offsetAt(row, 0);
        buffer.delete(lineStart, line.length());
        buffer.insert(lineStart, replaced);
        // マッチ件数を別途カウントして合計する（ステータス表示用）
    }
}
```

`indentLines()`（②`modal-editing-engine`/`modal-visual-block-selection` で確立済みの
「行ごとに `getLines()` を取り直してから delete+insert する」パターン）をそのまま踏襲する。
1行の内容を丸ごと置き換えるため、パターン中の `^`/`$` は自然に行頭・行末に一致し、`.` は
デフォルトで改行を跨がない（Java の `Pattern` は `.` がデフォルトで改行にマッチしないため、
行単位で `Matcher` を作ることで Vim の「`:s` は基本的に1行内で完結する」挙動と自然に一致する）。

実行後:
- 1件もマッチしなかった場合 `statusMessage = "E: pattern not found: " + pattern"`
- 1件以上あれば `statusMessage = "<件数> substitutions on <行数> lines"`、カーソルを最後に
  変更された行の行頭へ移動する（Vim も最終変更行へカーソルを移動する）
- `pattern` が空文字列でなければ `lastSearchPattern = pattern` を更新する（Vim は `:s` の
  パターンを次回の `n`/`N` にも使い回せるようにする。①`text-search` skill の
  `lastSearchPattern` フィールドと共有）

### Undo の粒度

専用のグルーピング機構は追加しない。`UndoablePieceTable` には `beginGroup`/`endGroup`
のような機構がそもそも存在せず（`insert`/`delete` の呼び出し単位でスナップショットを積む
だけの単純設計）、`indentLines()`（Visual `>`/`<`）も同じ制約の中で「変更された行1行 = 1回の
`buffer.delete`+`buffer.insert` = 実質2回のスナップショット」という粒度のまま実装されている。
`:%s` で複数行が変わった場合、`Ctrl+/u`（バッファ履歴切替ではなく本来の undo。もし将来
実装される場合）は行単位で少しずつ戻ることになるが、これは既存の `indentLines()` と完全に
同じトレードオフであり、本タスク単独で undo グルーピング機構を新設することはスコープ外
とする。

---

## 注意点

- **区切り文字は `/` 以外にも対応するが、Vim の「very magic」等の正規表現モード切り替え
  （`\v`）には対応しない**。パターンは常に Java の `Pattern` 構文として直接解釈される
  （既存の `:w s/.../.../ `＝`applyRegexSubstituteToPath` と同じ方針。Vim 独自のマジック
  レベル切り替えを実装すると正規表現エンジンの差異を埋めるための翻訳層が必要になり、
  スコープが大きく膨らむため見送った）。
- **`'<,'>` は Visual の種別（CHAR/LINE/BLOCK）に関わらず常に行単位の範囲として扱う**。
  これは本家 Vim も同じ挙動（Visual Block で数列だけ選択していても `:s` は選択範囲の
  行全体が対象になる）。
- **範囲外・逆順（`r1 > r2` になり得るケースは `Math.min`/`Math.max` で必ず正規化する）**。
- **空バッファ・0行の状態では `r1==r2==0` として扱い、`getLines()` が空配列を返す場合は
  ループが即座に終わり「0 substitutions」となる**（例外を投げない）。
- テストは `test/dev/javatexteditor/editor/SubstituteCommandTest.java` に自作ハーネスで追加する
  （⑦`editor-testing-strategy` 準拠）。カーソル行のみ・`%s`・`'<,'>s`（VISUAL/VISUAL_LINE 両方）・
  `N,Ms`・`g`/`i` フラグ・`\1`/`&` 置換・空パターンでの `lastSearchPattern` 再利用・
  マッチなし時のエラーメッセージ・区切り文字が `/` 以外のケースを網羅する。
