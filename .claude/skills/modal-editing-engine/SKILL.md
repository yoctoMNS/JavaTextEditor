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

レジスタ指定（`"a`）・オペレータ組み合わせ（`d3j`/`c2w`）・マーク（`ma`/`'a`）は v6 以降の候補のまま未着手
（`docs/handover-modal-editor-v5.md` 参照）。実装する場合は本Skillと④を先に更新すること。

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
