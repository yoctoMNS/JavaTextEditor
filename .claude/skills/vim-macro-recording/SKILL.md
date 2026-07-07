---
name: vim-macro-recording
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、Vim互換のマクロ機能（NORMALモードの q によるキーストローク記録・@ による再生・@@ による直前マクロ再実行）を設計・実装する際に使用する。「マクロを記録したい」「q{register}/@{register}をどう実装するか」「キーストロークをどこで捕まえるか」「記録中にマクロ自体を呼び出すとどうなるか」「レジスタは既存のヤンクレジスタと同じものか」といった相談、またModalEditor.javaにマクロの記録・再生ロジックを追加する作業に着手する前に、必ず最初に参照すること。"
---

# vim-macro-recording — Vim式マクロ（q記録 / @再生）

## このスキルが解決すること

Vimの `q{register}`（マクロ記録開始/終了）・`@{register}`（再生）・`@@`（直前マクロの再実行）を
`dev.javatexteditor.editor.ModalEditor` に実装する。② `modal-editing-engine` の
`processKey()` パイプラインの上に乗る機能であり、本Skールは② を前提知識とする
（未読なら先に② `SKILL.md` を参照すること）。

**前提として必ず読むこと**: ② `modal-editing-engine/SKILL.md` の
「未実装（スコープ外として確定済み）」節に「レジスタ指定（`"a`）… は v6 以降の候補のまま未着手」
と明記されている。本Skillが追加するレジスタ相当の仕組みは **マクロ専用の最小限のストレージ**
であり、`"ayy`/`"ap` のような汎用ヤンクレジスタ選択機能ではない（下記「スコープの境界」参照）。
このスコープの区別を混同しないこと。

---

## キーストロークをどこで捕まえるか（最重要の設計判断）

Vimのマクロは「実行された効果」ではなく **「実際にタイプされた生のキーストローク」** をそのまま記録し、
再生時にそれをもう一度入力キューに流し込むだけ、という仕組みである。本エディタでは全キー入力が
`ModalEditor.processKey(int keyCode, char keyChar, int modifiers)` の1点を通るため
（② `modal-editing-engine` の「processKeyパイプライン」参照）、**記録・再生ともにこの`processKey()`の
入口1箇所だけで完結させる**。モード別の `processNormalKey()`/`processInsertKey()`等を個別に
フックする必要はない・してはならない（INSERT中に打った文字やESCも同じ経路で記録されるべきため）。

```java
public void processKey(int keyCode, char keyChar, int modifiers) {
    // ...スプラッシュ消去・VISUAL系ESCガード（既存）...

    // マクロ記録: 記録中はここで生キーをそのまま記録バッファへ積む
    boolean isStopKey = macroRecording && mode == Mode.NORMAL && keyChar == 'q';
    if (macroRecording && macroReplayDepth == 0 && !isStopKey) {
        macroRecordBuffer.add(new RecordedKey(keyCode, keyChar, modifiers));
    }

    switch (mode) { /* 既存のモード別ディスパッチ */ }
    syncCanvas();
}
```

詳細な設計理由・エッジケースの扱いは `references/macro-key-capture-design.md` を参照。

---

## レジスタストレージ（マクロ専用・最小限）

```java
private record RecordedKey(int keyCode, char keyChar, int modifiers) {}

private boolean macroRecording = false;
private char macroRecordingRegister;
private final List<RecordedKey> macroRecordBuffer = new ArrayList<>();
private final Map<Character, List<RecordedKey>> macroRegisters = new HashMap<>();
private char lastPlayedMacroRegister = '\0'; // @@ 用
private int macroReplayDepth = 0;            // 再帰ガード（下記参照）
```

- レジスタ名は `a`-`z`（小文字キーで新規記録=上書き、大文字キーで既存内容に**追記**）。
  Vim本家の「大文字レジスタ = 追記」の慣習をそのまま踏襲する。
- `macroRegisters` は `yankRegister`（既存の単一ヤンクバッファ）とは**完全に独立**したフィールド。
  ヤンク/ペーストの`"`レジスタ選択機能とは統合しない（下記「スコープの境界」参照）。

---

## q（記録開始/終了）の状態遷移

1. NORMALモードで `q` を押す → `pendingSequence = "q"`（`KeymapRegistry`のアクション名
   `"macro.record.pending"`経由。既存の `yank.pending`/`goto.pending` と同じパターン）。
2. 次のキーが `a`-`z`/`A`-`Z` の文字なら、そのレジスタで記録開始（`macroRecording = true`）。
   それ以外の文字は「無効なレジスタです」とエラー表示して記録を開始しない。
3. 記録中に **NORMALモードで** `q` が押されたら、多打鍵シーケンスの状態（`pendingSequence`）に
   一切関係なく最優先で記録を終了する。これは②の2打鍵シーケンス処理ブロック
   （`if (!pendingSequence.isEmpty())`）より**前**に置く専用ガードとして実装すること。
   理由: `pendingSequence`が`"g"`等の未確定状態のときに`q`が来ると、通常の2打鍵解決ロジックに
   飲まれて`q`が「新規マクロ記録の開始キー」として誤解決されうる（`macroRecording`が
   trueのまま新しい記録が始まろうとする不整合が起きる）。詳細は
   `references/macro-key-capture-design.md` の「q の優先順位」節を参照。
4. INSERTモード等、NORMAL以外のモードで`q`が押された場合は通常の文字入力として扱う
   （記録停止のトリガーにしない。Vim本家と同じ）。

---

## @（再生）の状態遷移

1. NORMALモードで `@` を押す → `pendingSequence = "@"`（アクション名 `"macro.play.pending"`）。
2. 次のキーが `a`-`z`/`A`-`Z` ならそのレジスタ（大文字は小文字に正規化）を再生し、
   `lastPlayedMacroRegister` を更新する。
3. 次のキーが `@` そのものなら、`lastPlayedMacroRegister` を再生する（`@@`）。
   `lastPlayedMacroRegister`が未設定（`'\0'`）なら「直前に実行したマクロがありません」を表示。
4. レジスタが空、または未記録の場合は何もせずエラーメッセージを表示する。

再生の実体は、記録済みキー列を1個ずつ `processKey()` に**再投入**するだけ:

```java
private void executeMacroKeys(List<RecordedKey> keys) {
    if (macroReplayDepth >= MACRO_MAX_REPLAY_DEPTH) {
        statusMessage = "マクロの再帰が深すぎます（中断しました）";
        return;
    }
    macroReplayDepth++;
    try {
        for (RecordedKey k : keys) processKey(k.keyCode(), k.keyChar(), k.modifiers());
    } finally {
        macroReplayDepth--;
    }
}
```

## 再帰ガード（Vimにはない安全装置・意図的な逸脱）

Vim本家は「マクロ内で自分自身を`@`で呼ぶと、モーション失敗（バッファ末尾到達等）で
自然に停止する」という設計だが、本エディタには「コマンド失敗検知」の仕組みがない
（`d3j`のようなcount付きモーションが未実装なのと同根で、失敗/成功を戻り値で区別する
統一的な仕組みがまだ存在しない）。そのため自己参照マクロは無限再帰し
`StackOverflowError`でエディタごと落ちる恐れがある。

これを避けるため、Vimには存在しない `macroReplayDepth` 上限（`MACRO_MAX_REPLAY_DEPTH`）
を安全装置として追加した。CLAUDE.mdの「バリデーションはシステム境界のみ」という方針に
一見反するように見えるが、ここでの「境界」はプロセスクラッシュを防ぐという意味での
安全境界であり、通常のマクロ利用（数百〜数千キー程度の再生）ではまず到達しない値
（1000）にしてあるため実用上の挙動には影響しない。

---

## KeymapRegistry への追加

`Mode.NORMAL` に以下を追加する（`q`/`@`とも本プロジェクトで未使用だったキーのため衝突なし）:

```java
bind(Mode.NORMAL, KeyBinding.ofChar('q', "macro.record.pending"), "macro.record.pending");
bind(Mode.NORMAL, KeyBinding.ofChar('@', "macro.play.pending"),   "macro.play.pending");
```

`ModalEditor.processNormalKey()` の switch に以下を追加する（既存の `yank.pending` 等と同じ並び）:

```java
case "macro.record.pending" -> { pendingSequence = "q"; statusMessage = "q-"; }
case "macro.play.pending"   -> { pendingSequence = "@"; statusMessage = "@-"; }
```

`pendingSequence`が`"q"`/`"@"`のときの2打鍵目（レジスタ文字）の処理は、既存の
`if (prev == 'g') { ... }` 等と同じ並びに `if (prev == 'q') { ... }` / `if (prev == '@') { ... }`
として追加する（②「多打鍵シーケンス」節のパターンをそのまま踏襲）。

---

## スコープの境界（何を実装し、何を実装しないか）

| 機能 | 対応 | 理由 |
|---|---|---|
| `q{a-z}` 記録・大文字追記・`q`終了 | ✅ 実装 | 本Skillの主題 |
| `@{a-z}` 再生・`@@` | ✅ 実装 | 本Skillの主題 |
| `"ayy`/`"ap` 等の汎用名前付きヤンクレジスタ | ❌ 対象外のまま | ②で「v6以降の候補」と明記済み。マクロ専用の`macroRegisters`はこれの代替ではない。両者を統合したくなっても、まず②のスコープ判断を更新してからにすること |
| `3@a`（count付き再生） | ❌ 未実装 | NORMALモードには`3j`のような汎用count前置きの仕組みがそもそも存在しない（`visualCountBuffer`はVISUALの`>`/`<`専用）。汎用count機構の設計は別スコープ |
| マクロ実行中のコマンド失敗による自動停止 | ❌ 未実装（代わりに`macroReplayDepth`上限で代替） | 上記「再帰ガード」参照 |
| `q`をレジスタなしで押した際のvimの「直前のレジスタで再記録」等の細かい挙動 | ❌ 未実装 | 無効なレジスタとしてエラー表示するのみ。厳密な100%互換より単純さを優先 |

---

## テスト（完了条件）

- 変更後は `./scripts/build.sh && ./scripts/test.sh` で全テスト PASS を確認する。
- 新規テストは `test/dev/javatexteditor/editor/MacroTest.java`（自作mainハーネス、⑦
  `editor-testing-strategy` 参照）。最低限カバーすべきケース:
  - 記録→再生で編集内容が2回分反映される（例: `qaA!<ESC>q` を2回`@a`で計3回分の"!"）
  - 大文字レジスタでの追記記録
  - `@@`が直前に実行したレジスタを再現する
  - 記録中に別のマクロを`@`で呼んだ場合、展開後の全キーではなく`@`+レジスタ文字の
    **2キーだけ**が記録される（`references/macro-key-capture-design.md`の核心）
  - 未記録レジスタへの`@`・無効なレジスタ文字への`q`/`@`がエラーメッセージのみで
    バッファを変化させない
  - INSERTモード中に`q`をタイプしても記録は止まらず文字として挿入される
  - 記録中にNORMALモードで`q`を押すと、多打鍵シーケンス（`pendingSequence`）の
    途中状態に関わらず記録が終了する

## 関連スキル

- ② `modal-editing-engine`: `processKey()`パイプライン・`pendingSequence`多打鍵シーケンスの土台
- ④ `keymap-conflict-resolution`: `q`/`@`のキー空き状況確認・`KeymapRegistry`への追加方法
- ⑦ `editor-testing-strategy`: 自作テストハーネスの書き方
