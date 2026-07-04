---
name: plugin-api-design
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、プラグイン向け公開APIを設計・実装する際に使用する。「プラグインからテキストをどう操作するか」「カーソルをプラグインから動かすには」「行単位のAPIが欲しい」「モードをプラグインから判定したい」「EditorContextに何を追加すべきか」といった相談、またEditorContextインタフェースを拡張する作業に着手する前に、必ず最初に参照すること。"
---

# Skill: plugin-api-design

## 概要

`EditorPlugin` が `EditorContext` を通じてエディタを操作するための公開 API を設計・実装する。
プラグインコードは `ModalEditor` の内部実装に直接依存せず、`EditorContext` インタフェースのみを通じて操作する（疎結合）。

---

## 設計方針

### 原則

1. **疎結合**: プラグインは `ModalEditor` を `import` せず、`EditorContext` のみを使う
2. **最小公開**: テスト・プラグインに不要な内部状態は公開しない
3. **行ベース操作を優先**: プラグインは行単位でテキストを操作することが多い
4. **副作用は明示**: カーソル移動・テキスト挿入など副作用を持つメソッドは `void` で明確に分離

### API の分類

| カテゴリ | メソッド | 用途 |
|---|---|---|
| テキスト読み取り | `getText()`, `length()`, `getLine(int)`, `getLineCount()` | バッファ内容の参照 |
| テキスト操作 | `insertAtOffset(int, String)`, `deleteRange(int, int)` | 挿入・削除 |
| カーソル読み取り | `getCursorRow()`, `getCursorCol()`, `offsetAt(int, int)` | カーソル位置の取得 |
| カーソル操作 | `setCursor(int, int)` | カーソル移動 |
| モード問い合わせ | `isNormalMode()`, `isInsertMode()` | 現在のモード確認 |
| UI | `setStatusMessage(String)` | ステータスバーへの表示 |

---

## EditorContext インタフェース（完成版）

```java
package dev.javatexteditor.extension;

/**
 * プラグインがエディタを操作するための窓口。
 * ModalEditor の内部実装から切り離し、プラグインを疎結合に保つ。
 */
public interface EditorContext {

    // ---- テキスト読み取り -------------------------------------------------------

    /** バッファ全体のテキストを返す。 */
    String getText();

    /** バッファの総文字数。 */
    int length();

    /** 総行数（getText().split("\n", -1).length と等価）。 */
    int getLineCount();

    /**
     * 指定行のテキスト（改行文字を含まない）。行番号は 0 始まり。
     * 範囲外の行番号を渡した場合は空文字列を返す。
     */
    String getLine(int row);

    // ---- テキスト操作 ----------------------------------------------------------

    /** offset は PieceTable の文字オフセット（先頭から数えた絶対位置）。 */
    void insertAtOffset(int offset, String text);

    /** startOffset から endOffset（排他）の範囲を削除する。 */
    void deleteRange(int startOffset, int endOffset);

    // ---- カーソル読み取り -------------------------------------------------------

    int getCursorRow();
    int getCursorCol();

    /**
     * (row, col) を絶対文字オフセットに変換する。
     * プラグインがカーソル位置の周辺テキストを操作するときに使う。
     */
    int offsetAt(int row, int col);

    // ---- カーソル操作 ----------------------------------------------------------

    /**
     * カーソルを (row, col) に移動する。
     * 範囲外の値は自動的にクランプされる（例外は投げない）。
     */
    void setCursor(int row, int col);

    // ---- モード問い合わせ -------------------------------------------------------

    /** NORMAL モードのとき true。 */
    boolean isNormalMode();

    /** INSERT モードのとき true。 */
    boolean isInsertMode();

    // ---- UI -------------------------------------------------------------------

    /** エディタのステータスバーにメッセージを表示する。 */
    void setStatusMessage(String message);

    // ---- キーマップ -----------------------------------------------------------

    /**
     * モード別キーマップレジストリを返す。
     * プラグインはこれを通じてキーバインドを追加・上書きしたり、
     * 独自アクションハンドラを登録できる。
     */
    KeymapRegistry getKeymap();
}
```

---

## ModalEditor への追加メソッド

```java
// --- 行ベース読み取り（プラグイン向けに public 化） ---
public int getLineCount() {
    return getLines().length;
}

public String getLine(int row) {
    String[] lines = getLines();
    return (row >= 0 && row < lines.length) ? lines[row] : "";
}

// offsetAt は既存の private メソッドを public に昇格
public int offsetAt(int row, int col) { ... }

// --- カーソル操作 ---
public void setCursor(int row, int col) {
    String[] lines = getLines();
    cursorRow = Math.max(0, Math.min(row, lines.length - 1));
    int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
    cursorCol = Math.max(0, Math.min(col, lineLen));
    syncCanvas();
}

// --- モード問い合わせ（isInsertMode は既存） ---
public boolean isNormalMode() { return mode == Mode.NORMAL; }
```

---

## SimpleEditorContext への追加

```java
@Override public int getLineCount()          { return editor.getLineCount(); }
@Override public String getLine(int row)     { return editor.getLine(row); }
@Override public int offsetAt(int row, int col) { return editor.offsetAt(row, col); }
@Override public void setCursor(int row, int col) { editor.setCursor(row, col); }
@Override public boolean isNormalMode()      { return editor.isNormalMode(); }
@Override public boolean isInsertMode()      { return editor.isInsertMode(); }
```

---

## テスト設計

### EditorContextApiTest（新規テストクラス）

テスト対象: `SimpleEditorContext` → `ModalEditor`

| テスト名 | 確認内容 |
|---|---|
| `testGetLineCount` | 単一行・複数行のカウント |
| `testGetLine` | 特定行のテキスト取得、範囲外は空文字 |
| `testOffsetAt` | 行0/行1のオフセット計算精度 |
| `testSetCursor` | カーソルを特定位置に移動できる |
| `testSetCursorClamping` | 範囲外でもクランプして例外なし |
| `testModeQueries` | NORMAL/INSERT モードの判定 |
| `testPluginUsesGetLine` | プラグインが getLine で行内容を読む E2E |
| `testPluginUsesSetCursor` | プラグインが setCursor でカーソルを動かす E2E |

### PluginLoaderTest.StubContext への追加

新しい `EditorContext` メソッドを `StubContext` に実装し、既存テストが引き続きコンパイル・実行できることを確認する。

---

## 設計判断ログ

| 判断 | 理由 |
|---|---|
| `getLine(int row)` を追加 | プラグインの大部分の操作は行単位であり、`getText().split()` より API として明確 |
| `offsetAt(int, int)` を公開 | プラグインからカーソル周辺のテキストを操作する際に必須 |
| `setCursor(int, int)` を追加 | ナビゲーション系プラグインがカーソルを動かせるようにするため |
| `isNormalMode()` / `isInsertMode()` を追加 | プラグインが「INSERTモード中は実行しない」などの条件分岐に必要 |
| `getKeymap()` を ④ Phase 3 で追加 | キーバインド登録は plugin-api より keymap-conflict-resolution のスコープ。④ Phase 3 完了により実装済み |
| デフォルト実装は持たない | テスト時に未実装メソッドを見落とさないよう、全メソッドを明示的に実装させる |
