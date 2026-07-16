---
name: keymap-conflict-resolution
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、Vim式モーダルキーとEmacs式カーソル移動（Ctrl+F/B/N/P）が共存するキーバインドを追加・変更する際に使用する。「新しいキーバインドを追加したい」「このキーは空いているか」「Ctrl+N等の競合をどう解決したか」「KeymapRegistry/KeyBindingの仕組み」といった相談、またキーバインドの追加・変更・プラグインからのアクション登録に着手する前に、必ず最初に参照すること。確定済みキーの再割り当てを避けるための一次資料。"
---

# Skill: keymap-conflict-resolution

## 概要

Vim 式モーダルキー（`hjkl` / `i` / `v` など）と Emacs 式カーソル移動（`Ctrl+F/B/N/P`）が
共存するエディタで、キーバインドを文字列ハードコードから**設定可能なレジストリ**に移行する。

---

## 現状の問題

`ModalEditor.java` の `processNormalKey` / `processInsertKey` / `processVisualKey` は
`switch (keyChar)` の直書きでキーバインドを定義している。

```java
// 現状（ハードコード）
case 'h' -> moveCursor(0, -1);
case 'l' -> moveCursor(0, 1);
```

この方式の問題点：
- ユーザーがキーバインドをカスタマイズできない
- 同じキー処理がモードをまたいで重複（NORMAL / VISUAL / VISUAL_LINE に `hjkl` が3回ある）
- プラグインからキーバインドを登録する手段がない（③ extension-language-runtime との接続に必須）

---

## KeyBinding レコード設計

```java
package dev.javatexteditor.editor;

/**
 * 1つのキーバインドを表す不変レコード。
 * keyCode は KeyEvent の定数（特殊キー用）。
 * keyChar は文字キー用（KeyEvent.CHAR_UNDEFINED なら keyCode を使う）。
 */
public record KeyBinding(
    int keyCode,        // KeyEvent.VK_* （文字キーは KeyEvent.VK_UNDEFINED）
    char keyChar,       // 文字キー（特殊キーは KeyEvent.CHAR_UNDEFINED）
    int modifiers,      // KeyEvent.CTRL_DOWN_MASK 等のビットマスク（0 = なし）
    String actionName   // アクション識別名（例: "cursor.left", "enter.insert"）
) {
    /** 文字キー用ファクトリ（修飾なし） */
    public static KeyBinding ofChar(char c, String actionName) {
        return new KeyBinding(KeyEvent.VK_UNDEFINED, c, 0, actionName);
    }
    /** 特殊キー用ファクトリ */
    public static KeyBinding ofCode(int keyCode, int modifiers, String actionName) {
        return new KeyBinding(keyCode, KeyEvent.CHAR_UNDEFINED, modifiers, actionName);
    }
}
```

---

## KeymapRegistry 設計

```java
package dev.javatexteditor.editor;

import java.util.HashMap;
import java.util.Map;

/**
 * モード別キーバインド管理。
 * キーは "MODE:keyChar" または "MODE:VK_xxx+modifiers" 形式の文字列。
 * アクション名（String）→ Runnable のマップと組み合わせて使う。
 */
public class KeymapRegistry {
    public enum Mode { NORMAL, INSERT, COMMAND, VISUAL, VISUAL_LINE }

    private final Map<Mode, Map<String, String>> bindings = new HashMap<>();

    public KeymapRegistry() {
        for (Mode m : Mode.values()) bindings.put(m, new HashMap<>());
        loadDefaults();
    }

    /** キーバインドを登録（後から上書き可能 → カスタマイズ対応） */
    public void bind(Mode mode, KeyBinding key, String actionName) {
        bindings.get(mode).put(toKey(key), actionName);
    }

    /** キー入力からアクション名を解決（見つからなければ null） */
    public String resolve(Mode mode, int keyCode, char keyChar, int modifiers) {
        String k = toKey(new KeyBinding(keyCode, keyChar, modifiers, ""));
        return bindings.get(mode).get(k);
    }

    private String toKey(KeyBinding kb) {
        if (kb.keyChar() != KeyEvent.CHAR_UNDEFINED) {
            return kb.keyChar() + ":" + kb.modifiers();
        }
        return "VK" + kb.keyCode() + ":" + kb.modifiers();
    }

    private void loadDefaults() { /* デフォルトキーマップをここに定義 */ }
}
```

---

## デフォルトキーマップ（移行後のイメージ）

### NORMAL モード

| キー | アクション名 | 現状の処理 |
|---|---|---|
| `h` | `cursor.left` | `moveCursor(0, -1)` |
| `l` | `cursor.right` | `moveCursor(0, 1)` |
| `j` | `cursor.down` | `moveCursor(1, 0)` |
| `k` | `cursor.up` | `moveCursor(-1, 0)` |
| `i` | `enter.insert` | INSERT モードへ |
| `a` | `enter.insert.after` | カーソル右1→INSERT モードへ |
| `o` | `enter.insert.newline` | 次行開いて INSERT |
| `v` | `enter.visual` | VISUAL モードへ |
| `V` | `enter.visual.line` | VISUAL LINE モードへ |
| `x` | `delete.char` | カーソル文字を削除 |
| `p` | `paste.after` | ヤンク内容をカーソル後に貼り付け |
| `P` | `paste.before` | ヤンク内容をカーソル前に貼り付け |
| `u` | `undo` | アンドゥ |
| `Ctrl+R` | `redo` | リドゥ |

### INSERT モード

| キー | アクション名 | 現状の処理 |
|---|---|---|
| `ESC` | `enter.normal` | NORMAL モードへ |
| `Ctrl+F` | `cursor.right` | カーソル右 |
| `Ctrl+B` | `cursor.left` | カーソル左 |
| `Ctrl+N` | `cursor.down` | カーソル下 |
| `Ctrl+P` | `cursor.up` | カーソル上 |
| `Backspace` | `delete.before` | カーソル前1文字削除 |
| `Enter` | `insert.newline` | 改行 |

---

## Vim vs Emacs 競合の解決方針

| 競合キー | Vim 意味 | Emacs 意味 | v1 解決策 |
|---|---|---|---|
| `Ctrl+B` | INSERT 時は1文字後退 | バッファ切り替え | INSERT のみ後退（Vim 採用）。バッファ切り替えは `:b` コマンドで代替 |
| `Ctrl+F` | INSERT 時は1文字前進 | 検索 | INSERT のみ前進（Vim 採用）。検索は `/` で代替 |
| `Ctrl+N` | INSERT 時は下移動 | 次バッファ | INSERT のみ下（Vim 採用） |
| `Ctrl+P` | INSERT 時は上移動 | 前バッファ | INSERT のみ上（Vim 採用） |

**基本方針**: INSERT モードの Emacs 式カーソル移動（`Ctrl+F/B/N/P`）は現行通り維持する。
NORMAL モードでは完全に Vim キーマップを使う。将来的にはモード別設定ファイルで上書き可能にする。

---

## 実装方針（段階的移行）

### Phase 1（推奨の最初のステップ）
`KeyBinding` レコードと `KeymapRegistry` クラスを新規作成する。
`ModalEditor` の `processNormalKey` を `KeymapRegistry.resolve()` 経由に移行する。
既存テスト（151件）が全て通ることを確認する。

### Phase 2
INSERT / VISUAL / VISUAL_LINE モードも移行する。
`KeymapRegistry.bind()` で外部からキーバインドを上書きできることをテストする。

### Phase 3（③との統合）✅ 完了
プラグインが `EditorContext` 経由で `KeymapRegistry` にアクセスし、
カスタムキーバインドを登録できるようにする。

実装内容:
- `EditorContext.getKeymap()` を追加（`KeymapRegistry` を返す）
- `KeymapRegistry.registerAction(String, Runnable)` / `getCustomAction(String)` を追加
- `ModalEditor` の各モード処理（NORMAL/INSERT/VISUAL/VISUAL_LINE）でカスタムアクションを先行チェック
- プラグインは既存アクションを上書きすることも、新規アクション名を定義することも可能

### VISUAL / VISUAL_LINE モードの `v`/`V` トグル離脱（追加実装）

`ESC` に加え、VISUAL モード中に `v` をもう一度押す、または VISUAL_LINE モード中に
`V` をもう一度押すと NORMAL モードへ戻れるようにした（本家 Vim と同じ挙動）。

- `KeymapRegistry`: `Mode.VISUAL` に `v → enter.normal`、`Mode.VISUAL_LINE` に
  `V → enter.normal` を追加バインド（NORMAL モードの `v → enter.visual` /
  `V → enter.visual.line` とは別モードなので衝突しない）。
- `ModalEditor.processVisualKey` / `processVisualLineKey` の switch に
  `case "enter.normal" -> mode = Mode.NORMAL;` を追加。
  `ESC` は `processKey()` 冒頭で VISUAL/VISUAL_LINE 中は早期 return する既存ガード
  （`pendingSequence` クリアあり）で処理されるため、この case は実質 `v`/`V` 用。
- アンカー（`anchorRow`/`anchorCol`）は再利用しない設計のまま: NORMAL に戻った後
  もう一度 `v`/`V` を押すと、その時点のカーソル位置から新しい選択が始まる
  （Vim 本家と同じ）。

---

## 実装ファイル一覧

| ファイル | 役割 |
|---|---|
| `src/dev/javatexteditor/editor/KeyBinding.java` | キーバインドを表すレコード |
| `src/dev/javatexteditor/editor/KeymapRegistry.java` | モード別キーマップ管理 |
| `src/dev/javatexteditor/editor/EditorAction.java` | アクション名と Runnable の対応（オプション） |
| `test/dev/javatexteditor/editor/KeymapRegistryTest.java` | 登録・上書き・競合検出テスト |

---

## 設計判断ログ

| 判断 | 理由 |
|---|---|
| アクション名を文字列で持つ | `enum` より拡張しやすく、プラグインが新アクションを追加しやすい |
| キーの正規化に `"keyChar:modifiers"` 形式を使う | `HashMap` の `equals` が単純に機能し、衝突検出が容易 |
| `KeymapRegistry` を `ModalEditor` とは別クラスに分離 | テスト容易性・プラグインからの参照のため |
| `loadDefaults()` でデフォルトを一元定義 | 設定ファイル対応（将来）で `loadDefaults()` を上書きするだけで済む |
| 単語補完（Alt+/）のトリガーに `Ctrl+N` を使わない | ユーザーから「作業ディレクトリ配下の単語・クラス名・変数名・定数名・メソッド名を Ctrl+N で補完したい」という要望があったが、INSERT モードの `Ctrl+N` は本表の通り既に Emacs 式「カーソル下移動」に確定済み。ユーザーに確認したところ「別キーを使う」を選択したため、`Ctrl+N` は現状維持し、単語補完のトリガーには未使用の `Alt+/` を新規に割り当てた（`ModalEditor.processInsertKey()` で `KeyEvent.VK_SLASH` + `ALT_DOWN_MASK` を直接判定。Ctrl+Space のシンボル補完トリガーと同じくキーマップレジストリを経由しないハードコード方式に揃えた）。 |
| `%`（対応括弧ジャンプ）を NORMAL/VISUAL/VISUAL_LINE/VISUAL_BLOCK 全モードに `KeymapRegistry` 経由で追加 | `motion.match.pair` アクション名で統一。衝突なし（`%` は本プロジェクトで未使用だった）。 |
| Visual の `>`/`<`（インデント）を VISUAL/VISUAL_LINE/VISUAL_BLOCK に追加 | `indent.right`/`indent.left`。count前置き（`3>` 等）は `KeymapRegistry` を経由しない専用の数字バッファ（`visualCountBuffer`）で実装し、`>`/`<` 以外のキーが来たら破棄する軽量方式にとどめた（②スキル参照）。 |
| `q`/`@`（マクロ記録/再生）を NORMAL に追加 | `macro.record.pending`/`macro.play.pending`。どちらも本プロジェクトで未使用だったキーで衝突なし。実際のレジスタ選択（2打鍵目の`a`-`z`/`A`-`Z`）は`gg`等と同じ`pendingSequence`の`prev`分岐で処理し、`KeymapRegistry`側は「マクロ記録/再生待ちへ入る」ところまでしか受け持たない（㉗ `vim-macro-recording` 参照）。 |
| `;`（`:`のエイリアス）を VISUAL/VISUAL_LINE/VISUAL_BLOCK にも追加 | NORMAL では既に `KeyBinding.ofChar(';', ...)` を `enter.command` に束縛済みだったが、VISUAL系3モードには `:`（`enter.command.visual`）のみが束縛されており `;` が未対応だった。同じ `enter.command.visual` アクションに `;` を追加束縛するだけで対応（3モードとも既存の `:` 束縛の直後に1行追加）。衝突なし（`;` はVISUAL系モードで未使用だった）。 |
| `z`（NORMALのみ）を `zz` のプレフィックスとして追加 | `screen.center.pending`。カーソル行を viewport 中央にスクロールする。衝突なし（`z` は本プロジェクトで未使用だったキー）。`zt`/`zb` は今回スコープ外（詳細は modal-editing-engine スキル参照）。 |
| getter/setter生成の新規プレフィックスに `\g`（`\gg`/`\gs`/`\gd`）ではなく `\a`（`\ag`/`\as`/`\ad`）を採用 | ユーザーから「`\gg`=getter生成・`\gs`=setter生成・`\gd`=getter/setter両方生成」の追加を要望されたが、`\g`（ファイル内容grep検索、FILESEARCHモード起動）と直接衝突する（`\`+`g`の2打鍵で即座にgrepモードへ入る既存仕様のため、3打鍵目の`g`/`s`/`d`はgrepクエリ文字列の先頭文字として食われてしまう）。`AskUserQuestion`でユーザーに確認し「`\g`の挙動は変更せず、別プレフィックスを使う」を選択、続けて具体的な文字も確認し `\a`（accessorの頭文字）に決定した。実装は`seq.equals("\\a")`の3打鍵目判定を`prev == '\\'`の2打鍵目判定より前に置く（`gu`/`gU`/`g~`と同じ理由: `prev`は`seq.charAt(0)`のため`\a`の3打鍵目でも`'\\'`に一致してしまう）。生成ロジック自体は既存の`generateGetter()`/`generateSetter()`/`generateGetterAndSetter()`（元々`SPC g g`/`SPC g s`/`SPC g d`から呼ばれていた）をそのまま再利用し、`SPC g g/s/d`のバインドも削除していない（両方から呼べる）。 |
| `Ctrl+Shift+O` の割り当てを `organize.imports` から `insert.override`（@Override+改行挿入）へ差し替え | ユーザーから「Ctrl+Shift+Oで@Overrideと改行を挿入する機能」を要望されたが、このキーは既に Eclipse 互換の import 整理（`organize.imports`、NORMAL/INSERT両モード）に割り当て済みだった。`AskUserQuestion`で確認し「Ctrl+Shift+Oの挙動をこの機能に差し替える」を選択。organize imports自体は削除せず、`SPC+i+o`と`:oi`/`:organize-imports`コマンドから引き続き呼び出せる（`organizeImports()`private メソッドは変更なし。到達経路がCtrl+Shift+Oの1つ減っただけ）。挿入ロジック（`insertOverrideStub()`）は既存の`insertNewlineWithIndent()`（INSERT中のEnterキー）と全く同じ契約にした: カーソル行の**先頭インデント**を検出し、`"@Override\n" + indent`をカーソルの**生の位置**（`offsetOfCursor()`、列を強制的に動かさない）にそのまま挿入する。この契約上、カーソル列が0（インデント文字より前）で使うとインデントが二重になる（`insertNewlineWithIndent()`をEnterキーとして同じ位置で使った場合も同じ結果になる、この既存メソッドと共通の性質であり本機能固有のバグではない）。既存の自動インデントテスト（`testAutoIndentPreserve`等）はカーソルを行末に置いてからEnterを検証しており、`insertOverrideStub()`のテストもこれに揃えて「インデントのみの空行の行末（列=indentLen）にカーソルを置いてから呼ぶ」という実際の使い方（メソッドを書く直前の位置）で検証している（`ModalEditorTest.testInsertOverrideStubCtrlShiftO`等）。NORMAL/INSERTいずれから呼んでも常にINSERTモードへ遷移する（後続のメソッドシグネチャ入力をそのまま続けられるようにするため）。 |
