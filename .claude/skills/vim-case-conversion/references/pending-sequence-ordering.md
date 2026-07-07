# 3段階以上の pendingSequence を追加するときの順序の罠

`gu`/`gU`/`g~`（`ModalEditor.processNormalKey()`）を実装する際に実際に踏んだバグと、その回避方法を記録する。
`guu`/`gUU`/`g~~` に限らず、将来 `g`（または他の任意の1文字）から始まる**3打鍵以上のシーケンス**を
追加する開発者は同じ構造の罠を踏みやすいため、②`modal-editing-engine`本体ではなくここに詳細を残す。

## 罠の構造

`processNormalKey()` の多打鍵シーケンス処理は次の形をしている（②スキル参照）。

```java
if (!pendingSequence.isEmpty()) {
    String seq = pendingSequence;
    pendingSequence = "";
    statusMessage = "";
    char prev = seq.charAt(0);   // ← ここが罠の元凶
    if (prev == 'g' && matches(keyCode, keyChar, KeyEvent.VK_G, 'g')) { moveFileStart(); return; } // gg
    if (prev == 'g' && keyChar == 'r') { goToReferences(false); return; } // gr
    ...
}
```

`prev` は `seq.charAt(0)` であり、**`seq` が `"g"` のときも `"gu"` のときも同じ `'g'` になる**。
既存の `gg`/`gr`/`gR`/`gv` はすべて「1文字プレフィックス→2打鍵目で完了」という2段階構造しか
持たなかったため、この`prev`ベースの判定で何の問題も起きていなかった。

しかし `gu`/`gU`/`g~` は3段階構造（`g` → `gu` → 3打鍵目で完了）を持つ。もし2打鍵目の遷移判定
（`pendingSequence` を `"g"` から `"gu"` へ進める処理）を、3打鍵目の完了判定より**前**に
`prev == 'g'` ベースで書いてしまうと、次のようにバグる:

```java
// バグる書き方（実際に一度これを書いて踏んだ）
if (prev == 'g' && keyChar == 'u') { pendingSequence = "gu"; return; }       // (A) 2打鍵目遷移
...
if (seq.equals("gu") && keyChar == 'u') { applyCaseToLines(...); return; }   // (B) 3打鍵目完了
```

3打鍵目で `u` を押したとき、`seq` は `"gu"` であり `prev` は `seq.charAt(0)` で `'g'` になる。
このとき `(A)` の条件 `prev == 'g' && keyChar == 'u'` は **`seq` が `"g"` か `"gu"` かを見ずに
成立してしまう**ため、`(B)` に到達する前に `(A)` が先にマッチし、`pendingSequence` が再び
`"gu"` にセットされて `return` してしまう。結果、何度 `u` を押しても `guu` が完了しない
（何も起こらないまま `pendingSequence` が `"gu"` のまま維持され続ける）。

## 回避方法

1. **完了判定（`seq.equals("gu")` 等、文字列全体の一致）を、遷移判定より必ず前に置く。**
   本実装での正しい順序:

   ```java
   if (seq.equals("gu") && keyChar == 'u') { applyCaseToLines(cursorRow, cursorRow, CaseOp.LOWER); return; }
   if (seq.equals("gU") && keyChar == 'U') { applyCaseToLines(cursorRow, cursorRow, CaseOp.UPPER); return; }
   if (seq.equals("g~") && keyChar == '~') { applyCaseToLines(cursorRow, cursorRow, CaseOp.TOGGLE); return; }
   if (seq.equals("g")  && keyChar == 'u') { pendingSequence = "gu"; statusMessage = "gu-"; return; }
   if (seq.equals("g")  && keyChar == 'U') { pendingSequence = "gU"; statusMessage = "gU-"; return; }
   if (seq.equals("g")  && keyChar == '~') { pendingSequence = "g~"; statusMessage = "g~-"; return; }
   ```

2. **遷移判定の条件自体を `prev == 'g'` ではなく `seq.equals("g")` にする。** これにより、
   `seq` の長さに関わらず「ちょうど1文字目の `g` だけを消費した状態」でのみ次の遷移が
   起こるようになり、順序を間違えても事故らない（本実装ではこちらも合わせて採用した）。
   逆に既存の `gg`/`gr`/`gR`/`gv`（2段階のみ）は `prev == 'g'` のままでも実害はないため、
   無関係な既存コードは変更していない。

いずれか一方だけでも回避できるが、本実装では両方を採用し二重に安全側へ倒した。

## 一般化した教訓

`pendingSequence` の階層が2段階を超える機能を追加するときは:

- 判定に使う変数は `seq.charAt(0)`（先頭1文字）ではなく `seq`（文字列全体）の等値比較にする。
- 「より長い（＝より完了に近い）シーケンスの判定」を「より短い（＝途中段階の）シーケンスの判定」
  より必ず前に書く。`switch` 文で `case "gu", "gU", "g~":` のように先にまとめてしまうのも有効。
- 新しいテストで実際に3打鍵をシミュレートし（`pressKey(ed,'g'); pressKey(ed,'u'); pressKey(ed,'u');`
  のように1打鍵ずつ）、2打鍵目で止めた場合と3打鍵目まで完了させた場合の両方を検証すること。
  `CaseConversionTest.testGuuLowercasesCurrentLine()` と
  `testGuIncompleteSequenceFallsThrough()` がこのペアの実例。
