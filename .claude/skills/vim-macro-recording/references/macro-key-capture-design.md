# マクロのキャプチャ位置とエッジケース設計

このファイルは `SKILL.md` から参照される詳細資料。`processKey()` パイプラインの基礎
（② `modal-editing-engine`）は理解済みであることを前提にする。

## なぜ `processNormalKey()` ではなく `processKey()` の入口で記録するか

マクロは NORMAL モードのコマンドだけでなく、`i` で INSERT に入ってテキストを打ち込み `ESC` で
戻る、といった **モード遷移をまたぐキー列** を記録できなければならない
（例: `qaA;<ESC>jq` は「行末に`;`を追記して次行へ」を記録する典型的なマクロ）。
`processNormalKey()` の中だけで記録すると、INSERT モード中に打った文字（`processInsertKey()`
が処理する）が記録から漏れてしまう。したがって記録の実装は、全モード共通の入口である
`processKey(keyCode, keyChar, modifiers)` の **先頭**（モード別 switch に入る前）に置く。

## q の優先順位: なぜ「多打鍵シーケンス処理の前」に置く必要があるか

② `modal-editing-engine` の2打鍵シーケンス処理は次の形をしている（`processNormalKey()` 内）:

```java
if (!pendingSequence.isEmpty()) {
    String seq = pendingSequence;
    pendingSequence = "";       // ← ここで無条件にクリアされる
    char prev = seq.charAt(0);
    if (prev == 'y' && ...) { ...; return; }
    if (prev == 'g' && ...) { ...; return; }
    // ... どの条件にもマッチしなければ「落下してキーを通常処理」
}
String action = keymap.resolve(...); // 通常の単発キー解決
```

もしマクロ記録の終了判定（`q`）をこのブロックの**中**や**後**に置くと、次のような壊れ方をする:

1. ユーザーがマクロ記録中に `g` を押す → `pendingSequence = "g"` になる（gg/gr/gR/gv待ち）。
2. 続けて `q` を押す（記録を終えるつもりで）。
3. `pendingSequence` は `"g"` なので上のブロックに入り、`prev == 'g'` の分岐群
   （`gg`/`gr`/`gR`/`gv`）のどれにも `q` はマッチしない → 「落下してキーを通常処理」に流れる。
4. 通常処理で `keymap.resolve()` が単発の `q` を解決し、`"macro.record.pending"` アクションが
   発火 → `pendingSequence = "q"` にセットされてしまう。
5. しかし `macroRecording` は既に `true` のまま。次のキー入力が「新しいレジスタ選択」として
   誤解釈され、記録中のマクロが正しく終了しない・二重に記録状態へ入るという不整合が起きる。

この事故を防ぐため、**「記録中に NORMAL モードで `q` が来たら、`pendingSequence` の状態に
一切関係なく最優先で記録を終了する」** という専用ガードを、2打鍵シーケンス処理ブロックより
**前**（Ctrl+U/Ctrl+P や jdk-source疑似バッファの `q` ハンドラ等、既存の「最優先の早期return群」
と同じ並び）に置く:

```java
// マクロ記録中の q: 実行中の他の状態（多打鍵シーケンス等）に関わらず最優先で記録終了
if (macroRecording && keyChar == 'q') {
    stopMacroRecording();
    pendingSequence = ""; // 中途半端な "g" 等が残らないようにクリア
    return;
}
```

この設計により、`gg` の1打目 `g` を押した直後にマクロ記録を終了させても
（＝ `gg` を完成させる前に `q` を押しても）、`pendingSequence` の残骸が残らない。

## 記録の開始・終了キー自体はログしない

`q{register}` の2キーと、終了の `q` の計3キーは **マクロの内容そのものには含まれない**
（Vim本家の挙動と同じ。再生時にもう一度 `q{register}...q` が実行されたら困る）。

これは実装上、次の順序関係によって自然に成立する:

| タイミング | `macroRecording` の値 | ログされるか |
|---|---|---|
| 1キー目 `q`（記録開始の合図） | `false`（まだ記録開始前） | されない |
| 2キー目 レジスタ文字（例 `a`） | `false`（`startMacroRecording()`はこの後で呼ばれる） | されない |
| 3キー目〜 実際に記録したい操作 | `true` | される |
| 最終キー 終了の `q` | `true` だが `isStopKey` 判定で除外 | されない |

`macroRecording` を `true` にする（`startMacroRecording()`）のは、レジスタ文字を受け取った
**後**の処理内なので、レジスタ文字そのものは「記録中」になる前に処理が終わっている。

## 記録中に別マクロを `@` で呼んだ場合（入れ子）は「展開」せず「呼び出し2キー」だけを記録する

Vim本家の重要な仕様: マクロ記録中に `@b` を打つと、記録される内容は文字通り `@` と `b` の
**2キーだけ**であり、レジスタ `b` の中身がその場で展開されて記録に混ざることはない
（そうでないと、後で `b` の中身を書き換えても `a` 側の記録に反映されない、という直感に反する
挙動になってしまう）。

これを実現するため、再生ループ (`executeMacroKeys()`) の実行中は `macroReplayDepth` を
インクリメントし、記録判定側は次のように「再生によって内部生成されたキー」を除外する:

```java
if (macroRecording && macroReplayDepth == 0 && !isStopKey) {
    macroRecordBuffer.add(new RecordedKey(keyCode, keyChar, modifiers));
}
```

具体的な流れ（マクロ `a` を記録中に `@b` を打った場合）:

1. `@` キーが届く。この時点で `macroReplayDepth == 0` なので**記録される**
   （`pendingSequence = "@"` になる）。
2. `b` キーが届く。まだ `macroReplayDepth == 0` なので**記録される**。
   → `processNormalKey()` 側で `prev == '@'` の分岐が `playMacro('b')` を呼ぶ。
3. `playMacro()` → `executeMacroKeys(macroRegisters.get('b'))` が呼ばれ、
   ループに入る前に `macroReplayDepth++`（`0→1`）。
4. ループ内で `processKey()` をレジスタ `b` の記録内容の数だけ再帰的に呼ぶ。
   この間 `macroReplayDepth == 1`（`> 0`）なので、**一切記録されない**。
5. ループを抜けたら `finally` で `macroReplayDepth--`（`1→0`）に戻す。

結果として、レジスタ `a` の記録内容には `@` と `b` の2エントリだけが追加され、
`b` の中身が何個のキーで構成されていても `a` の記録サイズに影響しない。

## 再生時にモード遷移をまたぐ理由

`executeMacroKeys()` は記録済みキー列を単純に `processKey()` へ1個ずつ再投入するだけであり、
「今 NORMAL か INSERT か」を意識しない。これは意図的な単純化: 記録された `RecordedKey` は
生キーイベントの列でしかなく、`processKey()` 自身が現在の `mode` に応じて正しいモード別
ハンドラへディスパッチしてくれるため、再生ロジック側でモードを気にする必要がない。
（＝記録時に INSERT へ入るキー `i` が含まれていれば、再生時もそのキーが `mode = Mode.INSERT`
への遷移を引き起こし、続く文字キー列が正しく INSERT ハンドラに渡る。）

## 記録ボタン（保存タイミング）

`macroRecordBuffer` は記録中の一時バッファであり、`stopMacroRecording()` が呼ばれて初めて
`macroRegisters` へコミットされる。記録中にエディタがクラッシュした場合などは記録内容は
失われるが、これは Vim 本家も同じ（未確定のレジスタ内容はメモリ上にしかない）ため、
永続化やクラッシュ耐性は本Skillのスコープ外とする。
