---
name: modal-editing-engine
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、モーダル編集エンジン（ModalEditor）のモード遷移・キー処理を設計・変更する際に使用する。「新しいモードを追加したい」「NORMALモードにキーを追加したい」「yy/dd/ggのような多打鍵コマンドを作りたい」「ヤンク/ペーストの挙動を変えたい」「ESCの処理がどこにあるか」といった相談、またModalEditor.javaのprocessKey系メソッドやMode enumを触る作業に着手する前に、必ず最初に参照すること。ほぼ全ての機能SkillがModalEditorへの接続を必要とするため、本Skillはそれらの共通の土台である。"
---

# modal-editing-engine（②）— Vimモーダル編集エンジン

> 本Skillは②完了（v5・2026-06-25）からしばらくSKILL.md未作成のままだった（`docs/implementation-history.md` 8.1節参照）。
> 2026-07-04のスキル棚卸しで、`docs/handover-modal-editor-v2〜v5.md`・現行の`ModalEditor.java`（約3,500行）を
> 一次資料として遡及作成した。実装の詳細経緯は上記handover文書を参照。

## このスキルが解決すること

Vimのモーダル編集（NORMAL/INSERT/VISUAL…のモードを切り替えて編集する方式）を
`dev.javatexteditor.editor.ModalEditor` として実装・拡張する。①（ピーステーブルバッファ）を
編集対象とし、⑤（EditorCanvas）へ描画状態を同期する、エディタの中枢クラスである。

---

## モードの全体像（最重要: 2系統あることを理解する）

`ModalEditor.Mode` は現在10モードあるが、キー解決の仕組みが**2系統**に分かれている。

```java
private enum Mode { NORMAL, INSERT, COMMAND, VISUAL, VISUAL_LINE,   // ← KeymapRegistry 経由
                    SEARCH, FILESEARCH, TELESCOPE, IMPORT_SELECT, FILER } // ← バイパス（直接処理）
```

| 系統 | モード | キー解決 |
|---|---|---|
| 基本編集モード | NORMAL / INSERT / COMMAND / VISUAL / VISUAL_LINE | `KeymapRegistry.resolve()` でアクション名に解決してから switch する（④ `keymap-conflict-resolution` 参照）。ユーザー/プラグインが上書き可能 |
| 一時UIモード | SEARCH / FILESEARCH / TELESCOPE / IMPORT_SELECT / FILER | `KeymapRegistry` を**バイパス**し、`processXxxKey()` が生のキーを直接処理する |

**新モードを追加するときの判断基準**（確立済みの設計判断）:
- 検索クエリ等の**自由文字入力を受け付ける一時的なUI**（入力した文字がバッファに挿入されない）
  → バイパス方式。文字キーをすべてクエリバッファに流すため、キーマップ解決を挟む意味がなく、
  誤って既存バインドに吸われる事故も防げる（TELESCOPE/FILER が先例。CLAUDE.md「FILERモードの設計決定事項」参照）。
- **編集操作としてカスタマイズさせたいモード**
  → `KeymapRegistry.Mode` にも追加し、`bind()`/`resolve()` 経由にする。

---

## processKey パイプライン

すべてのキー入力は `processKey(keyCode, keyChar, modifiers)` の1点に入る。処理順序が重要:

```java
public void processKey(int keyCode, char keyChar, int modifiers) {
    // 1. 初回キーでスプラッシュ画面消去
    // 2. VISUAL/VISUAL_LINE中のESCだけは、モード別処理より先に早期returnで処理する
    //    （pendingSequenceのクリアを伴う。このガードを消すと選択解除が壊れる）
    if ((mode == Mode.VISUAL || mode == Mode.VISUAL_LINE) && keyCode == KeyEvent.VK_ESCAPE) {
        mode = Mode.NORMAL; pendingSequence = ""; syncCanvas(); return;
    }
    // 3. モード別ディスパッチ（switch式・全10モード）
    switch (mode) { case INSERT -> processInsertKey(...); /* ... */ }
    // 4. 最後に必ず syncCanvas()（カーソル・モード名・スクロール位置をEditorCanvasへ反映）
    syncCanvas();
}
```

## 多打鍵シーケンス（yy / dd / gg / SPC+f 等）

2打鍵以上のコマンドは `pendingSequence`（String フィールド）で管理する。専用の状態機械クラスはない。

1. 1打目のキーが `"yank.pending"` / `"goto.pending"` 等のアクションに解決されると、
   `pendingSequence = "y"` のように**待ち状態を文字列で記録**し、ステータスバーに `g-` 等を表示する。
2. 次のキー入力時、`processNormalKey()` 冒頭付近で `pendingSequence` が非空なら2打目として解釈し、
   完了後に `pendingSequence = ""` に戻す。
3. 現在使われているプレフィックス: `y`(yy)・`d`(dd)・`g`(gg/gr)・`[`(診断ジャンプ)・`s`(分割)・
   `" "`(SPCリーダー・` g`/` i`の3打鍵あり)・`\`(\f/\g ファイル検索)。
4. ESCや解釈不能キーで必ず `pendingSequence = ""` にクリアすること（クリア漏れは「次の1打が誤爆する」バグになる）。

**注意**: `gr` のような一部の2打目は `KeymapRegistry` を経由せず生キー比較で分岐している
（㉓ `symbol-definition-navigation` 参照）。新しい2打鍵を足す場合はまず `KeymapRegistry` 経由を検討し、
プレフィックス衝突（例: `g` の2打目同士）だけ生キー比較の並びに追加する。

## ヤンク/ペーストの単位

`yankType` フィールド（`"char"` / `"line"`）がヤンクバッファの単位を表す。
- `v`（文字単位VISUAL）でのヤンク/削除 → `"char"`、`p` はカーソル位置に挿入
- `yy`/`dd`/`V`（行単位） → `"line"`、`p` は**次の行**に行として挿入
- ペースト処理は必ず `yankType` で分岐する。片方だけ変更すると Vim との挙動差になる。

### ヤンクレジスタはペイン（ModalEditorインスタンス）をまたいで共有する（`static`）

- **不具合**: `:split`/`:vsplit` で複数ペインに分かれている状態で、一方のペインでヤンクしたテキストが
  もう一方のペインで貼り付けられなかった。
- **原因**: `ModalEditor` は1ペイン=1インスタンスであり（`Main.java` の `buildComponent` が
  ペインごとに `new ModalEditor(...)` を生成する）、`yankRegister`/`yankType` がインスタンスフィールド
  だったため、ペインごとに独立したレジスタを持ってしまっていた。Vim のレジスタはウィンドウ単位ではなく
  エディタプロセス単位で共有されるのが本来の意味論。
- **修正**: `yankRegister`/`yankType` を `private static` に変更し、全 `ModalEditor` インスタンス
  （＝全ペイン）で共有するようにした。マクロ専用レジスタ（`macroRegisters`）はこの変更の対象外
  （マクロは記録・再生ともに単一ペインで完結する操作のため、共有する理由がない）。

## 疑似バッファの割り込みキー処理（確立済みの並び）

`:grep`・`*cd候補*`・jdk-source 等の疑似バッファ表示中は、NORMAL モードのまま
`processNormalKey()` 冒頭の確立済みの位置で Enter（ジャンプ/選択）・`q`（閉じる）を
横取りする（`grepResults != null` / `inJdkSourceBuffer && keyChar == 'q'` などの並び）。
新しい疑似バッファを追加する場合はこの並びに揃えること（CLAUDE.md「:cd の設計決定事項」参照）。

---

## つまずきポイント

> ⚠️ **誤解1: 「モードを増やすには Mode enum に足すだけ」**
> `processKey()` の switch・`syncCanvas()`（ステータスバーのモード名表示）・ESC からの復帰経路の
> 3点セットが必要。COMMAND モードの Enter ハンドラのように「処理後に `mode = Mode.NORMAL` へ戻す」
> 箇所では、`enterFiler()` 等が設定した新モードを上書きしないよう `if (mode == Mode.COMMAND)` の
> ガード付きで戻すこと（FILER 実装時に踏んだ実バグ。CLAUDE.md 参照）。

> ⚠️ **誤解2: 「NORMALモードのカーソル列は行末まで行ける」**
> NORMAL では `col <= lineLen - 1` にクランプされる（Vim と同じ。INSERT は `lineLen` まで）。
> ESC で INSERT→NORMAL に戻る際にもクランプが走る。境界の期待値は
> `ModalEditorEdgeCaseTest` に明文化されている。

> ⚠️ **誤解3: 「1回のコマンド = 1回のバッファ操作」**
> `cw` 相当のような複合操作は内部で delete→insert に分解されることがあり、アンドゥ単位が
> ユーザーの感覚とズレうる（① `references/piece-table-delete-and-undo.md` の注意点参照）。
> 複合コマンドを追加する際はアンドゥのグループ化の要否を必ず検討すること。

## 未実装（スコープ外として確定済み）

汎用の名前付きヤンクレジスタ指定（`"ayy`/`"ap`）・オペレータ組み合わせ（`d3j`/`c2w`）・
マーク（`ma`/`'a`）は v6 以降の候補のまま未着手（`docs/handover-modal-editor-v5.md` 参照）。
実装する場合は本Skillと④を先に更新すること。

**例外（2026-07追加）**: `q`（マクロ記録）/`@`（マクロ再生）は ㉗ `vim-macro-recording` で実装済み。
マクロは記録内容の保存先として`a`-`z`のレジスタ名を使うが、これは上記の「汎用ヤンクレジスタ」とは
**別の独立したストレージ**（`macroRegisters`）であり、上記の未実装項目を解消したものではない。
汎用ヤンクレジスタ自体は引き続きスコープ外のまま。

## `%`（対応する括弧へジャンプ）・Visual `>`/`<`（インデント）・`gv`（2026-07 追加）

- **`%` は `dev.javatexteditor.editor.MatchPairs`（Swing/ModalEditor 非依存の純粋ロジック）に切り出した**。
  `findMatch(text, offset)` が深さカウント（スタック相当）でネストを解決し、対応する相手の offset を
  `OptionalInt` で返す。ペア定義を `Map<Character,Character>` で持たせているため、将来 `matchpairs`
  オプション相当の追加ペアはこの Map を拡張するだけで済む設計にしてある。
  **スコープの簡略化**: 本家 Vim は「カーソルが括弧上になければ行内を前方検索する」が、本実装は
  「カーソルが括弧そのものの上にあるときのみ」動く（無効位置では no-op）。行内前方検索は未実装。
- **NORMAL/VISUAL/VISUAL_LINE/VISUAL_BLOCK すべてで `%` はカーソル移動のみを行う `jumpToMatchingBracket()`**
  を共有する。VISUAL 系ではこのメソッドが `cursorRow`/`cursorCol` だけを書き換え `anchorRow`/`anchorCol`
  には触れないため、「アンカーを保持したままカーソル終点だけ動く」という Visual モーションの既存の
  慣例（`moveWordForward()` 等）にそのまま乗っている。operator-pending（将来の `d%` 等）を実装する場合も
  `offsetOfCursor()` + `MatchPairs.findMatch()` の2行をそのまま流用できる。
- **Visual `>`/`<` は `IndentSettings`（shiftwidth/tabstop/expandtab/shiftround を保持するだけの値クラス）と
  `Indenter`（1行分の新インデントを計算する static 純粋ロジック）に分離した**。`ModalEditor` は
  `indentSettings` フィールドを1つ持ち、`getIndentSettings()` で外部（テスト・将来の設定UI）から変更できる。
  丸め（shiftround）の式は Vim 本家の `shift_line()` と同じ「現在幅を shiftwidth の倍数に切り下げてから
  shiftwidth*count を加減算する」を採用した（丸めなしの場合は単純加減算）。
- **charwise VISUAL の `>`/`<` は linewise と同じ行全体に作用する**（`indentLines(r1, r2, left, count)` を
  VISUAL/VISUAL_LINE で共有）。選択が行の途中から始まっていても対象は選択に含まれる全行。
- **VISUAL BLOCK の `>`/`<` だけは行全体ではなく矩形の左端列(`c1`)基準の専用ロジック
  (`indentBlock()`)を使う**。本家 Vim は実は blockwise でも linewise と同じ「行全体シフト」だが、
  本プロジェクトでは要件により「矩形領域だけをシフトする」という独自仕様を採用した
  （右シフトは c1 位置に挿入、左シフトは c1 直前の空白を除去）。詳細は
  `.claude/skills/modal-visual-block-selection/SKILL.md` 参照。
- **count は `>`/`<` 専用の軽量な数字プレフィックス（`visualCountBuffer`）で実装した**。数字キーが
  押されるたびにバッファへ蓄積し、`>`/`<` 以外の任意のキーが来た時点で無条件に破棄する
  （汎用的な `3j` 等のカウント付きモーションは前述の「未実装」節のスコープのままで、今回は広げていない）。
- **`gv` は Visual モード脱出時の全経路（ESC 早期リターンガード・`enter.normal`・`yank`・`delete`・
  矩形置換 `r`・`indent.right`/`indent.left`）で `saveLastVisualFromCurrentMode()` を呼んで保存する**。
  インデント実行後に `gv` で再選択できるようにするため、この保存は「カーソルを整形後の位置
  （`moveLineStartNonBlank()` 等）へ動かす前」に行う必要がある（先に動かすと選択終端の行番号が
  失われる。実装時に一度ここで躓いたため、呼び出し順序を変えるときは要注意）。

## `zz`（カーソル行を viewport 中央にスクロール、2026-07 追加）

- **`z` を新規プレフィックスとして追加した**（`KeymapRegistry` に `z → screen.center.pending`、
  NORMAL モードのみ）。2打鍵目が `z` の場合のみ `centerCursorLineInViewport()` を呼ぶ。それ以外の
  2打鍵目は `g`/`s` 等と同じ「マッチしない場合は落下して通常処理へ」の既存方針に従い、`pendingSequence`
  を消費しつつ通常のキー処理にフォールスルーする（`zt`/`zb` 相当は未実装のため今回は追加していない）。
  `z` はバイパス方式の一時UIモード（SEARCH/TELESCOPE等）には存在しないため、INSERT/COMMAND等では
  `KeymapRegistry` に `z` バインドが無く、通常の文字入力として扱われる（NORMALモード専用というスコープが
  モード分岐の仕組みだけで自然に保証される）。
- **`centerCursorLineInViewport()` は既存のスクロールAPI（`canvas.getScrollRow()`/`setScrollRow()`・
  `ModalEditor.getVisibleRows()`）をそのまま再利用**し、新しい描画・座標系コードは追加していない。
  `newScrollRow = cursorRow - visibleRows/2` を `[0, totalLines - visibleRows]` にクランプするだけで、
  ファイル先頭・末尾付近でも不正な `scrollRow` にならない。カーソルの `row`/`col` は一切変更しない
  （`H`/`M`/`L`＝`jumpToScreenRow()` とは逆に、`zz` は「画面を動かす」側でカーソルは動かさない）。
  `syncCanvas()` が毎回呼ぶ `canvas.ensureCursorVisible(cursorRow)` は、中央寄せ後のカーソルが常に
  可視範囲内に収まる（クランプの結果カーソルが画面端に来た場合も可視範囲内ではある）ため、
  centerCursorLineInViewport() の結果を上書きしない。
- **テスト**: `test/dev/javatexteditor/editor/ZzCenterScrollTest.java`（新設）。`ScrollTest.java` とは
  異なり、`getVisibleRows()`（≒`scrollRow`反映）を検証するには実際の `EditorCanvas`（`setSize()`で
  固定）が必要なため、canvas なしの `ModalEditor` ではなく `new ModalEditor(text, canvas)` を使う。
  **`EditorCanvas` を生成するテストは `System.exit()` を明示的に呼ばないと JVM が終了しない**（Swing
  Timer が非デーモンの `AWT-EventQueue-0` を起動させ続けるため）。`EditorCanvasTest.java` に既存の
  コメントで明記されている既知の注意点で、今回このテストを書く際にも同じ理由でハングを実機で踏んだ
  （`System.exit(fail > 0 ? 1 : 0)` を追加して解消）。次に `EditorCanvas` を伴う新規テストクラスを
  書く開発者は、同じハングを踏まないようこの節を参照すること。

## テスト（`%`・Visual インデント・`gv`）

- `test/dev/javatexteditor/editor/MatchPairAndIndentTest.java`（18テスト）: `MatchPairs.findMatch()`の
  ネスト解決・Visual中の`%`拡張・charwise/linewise/blockwiseの`>`/`<`・空行/0未満クランプ・
  expandtab/shiftround・`gv`の範囲/種別復元。

## テスト（完了条件）

- 変更後は `./scripts/build.sh && ./scripts/test.sh` で全テスト PASS を確認する。
- 主担当テスト: `test/dev/javatexteditor/editor/ModalEditorTest.java`（v5時点151テスト）・
  `ModalEditorEdgeCaseTest.java`（クランプ・マルチバイト境界）。
- キー入力のテストは GUI 不要: `processKey(KeyEvent.VK_UNDEFINED, 'x', 0)` を直接呼び、
  `getText()`/カーソル位置/モード判定メソッドで検証する（⑦ `editor-testing-strategy` 参照）。

## 関連スキル

- ① `editor-buffer-architecture`: 編集対象のバッファ本体
- ④ `keymap-conflict-resolution`: キー→アクション名の解決・競合判断（キー追加時は必読）
- ⑤ `gui-rendering-pipeline`: syncCanvas() の反映先
- ⑥ `plugin-api-design`: プラグインから見た ModalEditor の公開面
