# 将来フェーズの設計（v2: スクロール、v3: ウィンドウ分割）

このファイルは`SKILL.md`から参照される、v1完成後の拡張方針。v1（単一バッファ静的表示）が動作確認できてから着手すること。

## v2: スクロール対応

### 前提作業：PieceTableへのメソッド追加

①(`editor-buffer-architecture`)のSKILL.mdで触れられていた「範囲取得メソッド」をここで実装する。

```java
/**
 * 文書全体ではなく、指定した文字オフセット範囲だけを取り出す。
 * なぜ必要か: getText()は全ピースを連結するため、数十万行規模のファイルで
 * 画面に表示する数十行だけを取り出したい場合でも、毎回全文字列を構築してしまう。
 */
public String getTextInRange(int startOffset, int endOffset) {
    StringBuilder result = new StringBuilder(endOffset - startOffset);
    int runningOffset = 0;
    for (Piece p : pieces) {
        int pieceEnd = runningOffset + p.length();
        if (pieceEnd > startOffset && runningOffset < endOffset) {
            int from = Math.max(0, startOffset - runningOffset);
            int to = Math.min(p.length(), endOffset - runningOffset);
            String source = (p.source() == Piece.Source.ORIGINAL) ? original : addBuffer.toString();
            result.append(source, p.start() + from, p.start() + to);
        }
        runningOffset = pieceEnd;
        if (runningOffset >= endOffset) break; // 早期終了で巨大ファイルでも余計な走査をしない
    }
    return result.toString();
}

/**
 * 「N行目が何文字目から始まるか」を調べる。
 * 注意: これは毎回先頭から数えるため、数十万行規模で頻繁に呼ぶと遅い。
 * 実際に遅さが問題になった場合は「改行位置の索引（行番号→オフセットの配列）」を
 * 別途キャッシュし、編集時に差分更新する設計に切り替えること（①と同じ「まずは
 * シンプルな実装で動かし、遅ければ最適化する」という方針を踏襲する）。
 */
public int offsetOfLine(int lineNumber) {
    String fullText = getText(); // 簡易実装。最適化の余地は上記の通り
    int offset = 0;
    int currentLine = 0;
    for (int i = 0; i < fullText.length() && currentLine < lineNumber; i++) {
        if (fullText.charAt(i) == '\n') currentLine++;
        offset = i + 1;
    }
    return offset;
}
```

### スクロール時の描画範囲

`EditorCanvas`に「現在表示している最初の行番号（`firstVisibleLine`）」を持たせ、`paintComponent`では`getTextInRange`で計算した範囲だけを取り出して描画するように変更する。縦スクロールは行番号の加減算、横スクロールは描画開始時のX座標オフセットの加減算で実現する。

### v2でのカーソルX座標の正確な計算

v1の`drawCursor`では`cursorCol * charWidth`という簡易計算を使っているが、全角文字を含む行では誤差が出る。v2では以下のように行先頭から累積幅を計算する:

```java
private int calcCursorX(String line, int cursorCol, int charWidth) {
    int x = 0;
    for (int i = 0; i < cursorCol && i < line.length(); ) {
        int codePoint = line.codePointAt(i);
        x += charWidth * charCellWidth(codePoint);
        i += Character.charCount(codePoint);
    }
    return x;
}
```

## v3: ウィンドウ分割（JSplitPane）

```java
import javax.swing.JSplitPane;

// 2つのEditorCanvasを左右に並べる例
EditorCanvas leftPane = new EditorCanvas();
EditorCanvas rightPane = new EditorCanvas();
JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, rightPane);
splitPane.setResizeWeight(0.5); // 50:50の初期配分
```

**設計原則（要件定義書5章より再掲）**: モード（NORMAL/INSERT等）はエディタ全体で1つの状態として持ち、各`EditorCanvas`インスタンスはカーソル位置とスクロール位置だけを個別に持つ。`insertMode`フィールドのような状態は、本来は各`EditorCanvas`ではなく、それらを束ねる上位のエディタ全体のクラスが持ち、各`EditorCanvas`の`paintComponent`はそこから値を読むだけにする設計に、v1から修正する必要がある。
