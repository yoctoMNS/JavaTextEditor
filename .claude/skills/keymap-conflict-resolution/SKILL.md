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
package dev.vimacs.editor;

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
package dev.vimacs.editor;

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

### Phase 3（③との統合）
プラグインが `EditorContext` 経由で `KeymapRegistry` にアクセスし、
カスタムキーバインドを登録できるようにする。

---

## 実装ファイル一覧

| ファイル | 役割 |
|---|---|
| `src/dev/vimacs/editor/KeyBinding.java` | キーバインドを表すレコード |
| `src/dev/vimacs/editor/KeymapRegistry.java` | モード別キーマップ管理 |
| `src/dev/vimacs/editor/EditorAction.java` | アクション名と Runnable の対応（オプション） |
| `test/dev/vimacs/editor/KeymapRegistryTest.java` | 登録・上書き・競合検出テスト |

---

## 設計判断ログ

| 判断 | 理由 |
|---|---|
| アクション名を文字列で持つ | `enum` より拡張しやすく、プラグインが新アクションを追加しやすい |
| キーの正規化に `"keyChar:modifiers"` 形式を使う | `HashMap` の `equals` が単純に機能し、衝突検出が容易 |
| `KeymapRegistry` を `ModalEditor` とは別クラスに分離 | テスト容易性・プラグインからの参照のため |
| `loadDefaults()` でデフォルトを一元定義 | 設定ファイル対応（将来）で `loadDefaults()` を上書きするだけで済む |
