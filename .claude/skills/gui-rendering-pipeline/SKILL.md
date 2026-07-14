---
name: gui-rendering-pipeline
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、バッファの内容をSwing/AWTのウィンドウに描画する際に使用する。「テキストをどう画面に表示するか」「カーソルをどう描画するか」「ライトモード/ダークモードの配色」「等幅フォントでの文字位置の計算」「JFrame/Canvas/Graphics2Dを使った描画クラスの実装」といった相談、またEditorCanvas/TextViewport等の描画コンポーネントを新規実装する作業に着手する前に、必ず最初に参照すること。ターミナル(TUI)ではなくGUIアプリケーションである点に注意。"
---

# GUI描画パイプライン（Swing/AWT・v1: 単一バッファ静的表示）

## このスキルが解決すること

`PieceTable`（バッファ）の内容を、Swing/AWTの`JFrame`内に`Graphics2D`で直接描画する。v1の範囲は「1つのバッファを静的に表示し、カーソルとステータス行を見せる」ことに限定する。

**v1のスコープ（実装済み範囲）**
- 単一バッファの内容を表示する（スクロール機能なし。ウィンドウからあふれた部分は表示しないだけで良い）
- カーソルを表示する（NORMAL=ブロック型、INSERT=縦棒型）
- ライトモード/ダークモードの配色
- モード名を表示するステータス行

**v1のスコープ外（後続フェーズに分離。`references/future-phases.md`参照）**
- 縦・横スクロール（→ `PieceTable`への範囲取得メソッド追加が前提になる）
- `JSplitPane`によるウィンドウ分割
- ②（モーダル編集エンジン）との接続（実際のキー入力によるカーソル移動。v1では座標を外部から直接指定する）

---

## なぜこの設計か

### 等幅フォントの罠（このプロジェクト特有の重要な注意点）

`Font.MONOSPACED`という論理フォント名は等幅フォントに解決されるが、これは「半角文字同士は同じ幅」を保証するだけで、**全角文字（日本語の漢字・かな等）は半角文字の2倍の幅で描画される**のが一般的である（Unicodeの East Asian Width 特性による）。日本語コメントを含むJavaファイルを扱う本プロジェクトでは、これを無視すると、全角文字を含む行でカーソル位置や次の文字の描画位置がずれる。

対策として、文字ごとに「半角扱いか全角扱いか」を判定し、半角なら1セル分、全角なら2セル分の幅を割り当てて座標を計算する。

### 見た目の検証もテストハーネスで行う

`java.awt.image.BufferedImage`はGraphics2Dの描画結果をメモリ上の画像として保持できるJava SE標準クラスで、実際にウィンドウを開かずに描画結果を検証できる。`BufferedImage.getRGB(x, y)`で指定座標のピクセル色を取得できるため、「背景色が正しいか」「カーソル位置に期待した色があるか」を①と同じ`main`メソッド形式のテストハーネスで確認できる。

---

## 実装

### 色の定義（`src/dev/javatexteditor/ui/Theme.java`）

```java
package dev.javatexteditor.ui;

import java.awt.Color;

/**
 * テーマごとの配色定義。
 * 純粋な黒(#000000)・純粋な白(#FFFFFF)を使わない理由:
 * コントラストが強すぎると目が疲れやすいため、わずかに調整した色を使う。
 */
public enum Theme {
    LIGHT_MODE(
        new Color(0xF5, 0xF0, 0xE6),  // ベージュ背景
        new Color(0x33, 0x33, 0x33),  // 薄い黒文字
        new Color(0x99, 0x99, 0x99)   // ステータス行区切り等に使う中間色
    ),
    DARK_MODE(
        new Color(0x1A, 0x1A, 0x1A),  // 黒背景（純黒より少し柔らかい）
        new Color(0xD4, 0xD4, 0xD4),  // 薄いグレー寄りの白文字
        new Color(0x66, 0x66, 0x66)
    );

    public final Color background;
    public final Color foreground;
    public final Color accent;

    Theme(Color background, Color foreground, Color accent) {
        this.background = background;
        this.foreground = foreground;
        this.accent = accent;
    }
}
```

### 文字幅の判定（全角・半角対応）

```java
/**
 * 1文字（コードポイント）が全角（2セル分）か半角（1セル分）かを判定する。
 * 厳密なUnicode East Asian Width判定は複雑だが、Javaプログラミング用途では
 * CJK・ひらがな・カタカナの範囲を押さえれば実用上十分。
 */
static int charCellWidth(int codePoint) {
    if (codePoint >= 0x3040 && codePoint <= 0x30FF) return 2; // ひらがな・カタカナ
    if (codePoint >= 0x4E00 && codePoint <= 0x9FFF) return 2; // CJK統合漢字
    if (codePoint >= 0xFF00 && codePoint <= 0xFFEF) return 2; // 全角英数・記号
    return 1;
}
```

### 描画コンポーネント本体（`src/dev/javatexteditor/ui/EditorCanvas.java`）

```java
package dev.javatexteditor.ui;

import javax.swing.JPanel;
import java.awt.*;

public class EditorCanvas extends JPanel {

    private String text = "";
    private int cursorRow = 0;
    private int cursorCol = 0;
    private boolean insertMode = false;
    private Theme theme = Theme.LIGHT_MODE;

    private static final Font FONT = new Font(Font.MONOSPACED, Font.PLAIN, 16);

    public void setText(String text) { this.text = text; repaint(); }
    public void setCursor(int row, int col) { this.cursorRow = row; this.cursorCol = col; repaint(); }
    public void setInsertMode(boolean insertMode) { this.insertMode = insertMode; repaint(); }
    public void setTheme(Theme theme) { this.theme = theme; repaint(); }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setFont(FONT);
        FontMetrics fm = g2.getFontMetrics();
        int charWidth = fm.charWidth('M');
        int lineHeight = fm.getHeight();

        // 1. 背景を塗る
        g2.setColor(theme.background);
        g2.fillRect(0, 0, getWidth(), getHeight());

        // 2. テキストを行ごとに描画する
        g2.setColor(theme.foreground);
        String[] lines = text.split("\n", -1);
        for (int row = 0; row < lines.length; row++) {
            int y = (row + 1) * lineHeight;
            drawLineWithFullWidthSupport(g2, lines[row], 0, y, charWidth);
        }

        // 3. カーソルを描画する
        drawCursor(g2, lines, charWidth, lineHeight);

        // 4. ステータス行を描画する（画面最下部）
        drawStatusLine(g2, lineHeight);
    }

    private void drawLineWithFullWidthSupport(Graphics2D g2, String line, int xStart, int y, int charWidth) {
        int x = xStart;
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            int cellWidth = charCellWidth(codePoint);
            g2.drawString(new String(Character.toChars(codePoint)), x, y);
            x += charWidth * cellWidth;
            i += Character.charCount(codePoint);
        }
    }

    private void drawCursor(Graphics2D g2, String[] lines, int charWidth, int lineHeight) {
        // 簡易版: 全角文字を含む行では誤差が出る。正確な実装はfuture-phases.mdを参照。
        int x = cursorCol * charWidth;
        int yTop = cursorRow * lineHeight;

        if (insertMode) {
            g2.setColor(theme.foreground);
            g2.fillRect(x, yTop, 2, lineHeight);
        } else {
            g2.setColor(theme.foreground);
            g2.fillRect(x, yTop, charWidth, lineHeight);
            if (cursorRow < lines.length) {
                String line = lines[cursorRow];
                if (cursorCol < line.length()) {
                    // サロゲートペア対応のため charAt ではなく codePointAt を使う
                    int codePoint = line.codePointAt(cursorCol);
                    g2.setColor(theme.background);
                    g2.drawString(new String(Character.toChars(codePoint)), x, (cursorRow + 1) * lineHeight);
                }
            }
        }
    }

    private void drawStatusLine(Graphics2D g2, int lineHeight) {
        int y = getHeight() - 4;
        g2.setColor(theme.accent);
        g2.fillRect(0, y - lineHeight, getWidth(), lineHeight);
        g2.setColor(theme.background);
        String modeLabel = insertMode ? "-- INSERT --" : "-- NORMAL --";
        g2.drawString(modeLabel, 4, y - 4);
    }

    static int charCellWidth(int codePoint) {
        if (codePoint >= 0x3040 && codePoint <= 0x30FF) return 2;
        if (codePoint >= 0x4E00 && codePoint <= 0x9FFF) return 2;
        if (codePoint >= 0xFF00 && codePoint <= 0xFFEF) return 2;
        return 1;
    }
}
```

---

## 実機検証で判明した修正点（SKILL.md初稿からの差分）

SKILL.md初稿（設計フェーズ）のコードには以下の誤りがあった。実装・コンパイル・実行時に修正済み。

| # | 場所 | 誤り | 修正内容 |
|---|---|---|---|
| 1 | `drawStatusLine`の引数 | `FontMetrics fm`パラメータが本体内で未使用 | パラメータを削除。呼び出し側の引数も削除 |
| 2 | `drawCursor`内のカーソル文字再描画 | `line.charAt(cursorCol)`（`char`単位） | `line.codePointAt(cursorCol)` + `Character.toChars`（サロゲートペア対応）に修正 |
| 3 | `Theme`フィールド | `final`（アクセス修飾子なし）のためパッケージ外から不可視 | `public final`に変更 |

---

## よくある誤解・つまずきポイント

> ⚠️ **誤解1：「列番号 × 固定幅」だけでカーソルのX座標を計算できる」**
> 全角文字を含む行では誤る。`cursorCol`が「何文字目か」であって「何セル目か」ではない場合、上記`drawCursor`の簡易計算では位置がずれる。正確には、行の先頭からカーソル位置の手前までの文字を1つずつ`charCellWidth`で積算する必要がある（v1では簡易実装、`references/future-phases.md`に正確な実装を記載）。

> ⚠️ **誤解2：「`text.split("\n")`で行分割すれば十分」**
> Windows形式の改行（`\r\n`）が混在するファイルでは、各行の末尾に`\r`が残る。`Files.readString`で読み込んだ際に改行コードの正規化を検討するか、`split("\r?\n")`を使う必要がある。

> ⚠️ **誤解3：「`paintComponent`は1回呼べば十分」**
> Swingでは、ウィンドウのリサイズ・他のウィンドウに隠れて再表示される時など、システムが任意のタイミングで`paintComponent`を再呼び出しする。`setText`等のメソッドで明示的に`repaint()`を呼ぶのはあくまで「すぐに反映したい時」のためであり、`paintComponent`自体は毎回フルで再計算・再描画される前提で実装すること。

---

## テスト方針（`BufferedImage`によるピクセル検証）

```java
// EditorCanvasを実際の画面に出さずに描画結果を検証する例
EditorCanvas canvas = new EditorCanvas();
canvas.setSize(400, 300);
canvas.setText("Hello");
canvas.setTheme(Theme.LIGHT_MODE);

BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
Graphics2D g2 = image.createGraphics();
canvas.paint(g2); // paintComponentを含む描画処理が走る
g2.dispose();

int pixel = image.getRGB(350, 150); // 文字がない領域の背景色を確認
int r = (pixel >> 16) & 0xFF;
int g = (pixel >> 8) & 0xFF;
int b = pixel & 0xFF;
// 期待値: Theme.LIGHT_MODE.background のRGB値 (0xF5, 0xF0, 0xE6) と一致するはず
```

実装済みテスト: `test/dev/javatexteditor/ui/EditorCanvasTest.java`（v1時点は5ケース。v3完了時点で描画系は22/22テスト全PASS。スクロールは`test/dev/javatexteditor/editor/ScrollTest.java`、ウィンドウ分割は同`CtrlWTest.java`も参照）

目視確認用PNG生成: `test/dev/javatexteditor/ui/VisualPreview.java`（`build/preview_*.png`に出力）

## 次に学ぶべきこと

1. ②（`modal-editing-engine`スキル）との接続は完了済み。キー入力とカーソル描画の対応を変更する際は両スキルを併読すること
2. スクロール対応・`PieceTable.getTextInRange()`の追加・`JSplitPane`によるウィンドウ分割は`references/future-phases.md`を参照

## telescope・ステータス行・補完ポップアップの文字描画を MiscFixed ビットマップフォントに統一

- **症状**: 本文（`drawLineWithFullWidthSupport`）は `BitmapFont10x20`（MiscFixed）でASCIIを描画していたが、telescopeオーバーレイ・ステータス行・入力補完ポップアップは独自に `new Font(Font.MONOSPACED, ...)` を生成して `g2.drawString()` していたため、同じ画面内でASCII文字のフォントが本文とUI要素とで見た目が異なっていた。
- **修正**: `EditorCanvas` に汎用ヘルパー `drawUiText(g2, s, x, y, cw, ch, color)` / `uiTextWidth(s, cw)` / `clipToUiWidth(s, cw, maxWidth)` を新設し、`drawTelescopeOverlay()`・`drawCompletionPopup()`・`drawStatusLine()` はすべてこれを使うように統一した。ASCII(0x20-0x7E)は本文と同じ `BitmapFont10x20.renderGlyph()`、それ以外（日本語等）は既存の Swing フォールバックフォントで描画する、という本文描画（`drawLineWithFullWidthSupport`）と同じ配色規則をそのまま踏襲している。
- **セルサイズの選定**: UI要素は独自のフォントサイズ（`lineHeight - 2`等）を計算するのをやめ、本文と全く同じ `cellW`/`cellH`（Ctrl+Shift+矢印で変更されるセルサイズ）をそのまま使う。これにより「本文と全く同じピクセルフォント」という最も強い意味での統一を実現している。telescopeの行間隔（`fh`）・補完ポップアップの行高さも `lineHeight`（=`cellH`）に統一した。
- **グリフキャッシュ**: 本文用の `glyphCacheFg`/`glyphCacheBg` は色が `theme.foreground`/`theme.background` 固定の前提でキャッシュしているため流用できない（telescope選択行やkindラベルは `theme.accent` 等、任意の色を使う）。そのため `UiGlyphKey(codePoint, cellW, cellH, rgb)` をキーとする別キャッシュ `uiGlyphCache` を新設し、`invalidateGlyphCache()`（セルサイズ・テーマ変更時）で一緒にクリアするようにした。
- **telescope選択行マーカーの変更**: 選択行マーカーを `"▸ "`（Unicode矢印、ASCII範囲外でSwingフォールバックが必要）から `"> "`（ASCII）に変更した。これにより選択行のマーカー込みで完全にビットマップフォントのセル幅グリッドに収まり、フォールバックによる幅ズレが起きない。
- **意図的に対象外としたもの**: スプラッシュ画面（`drawSplashScreen`）は日本語主体の説明文とキーバインド一覧が混在しており、センタリング計算が `FontMetrics` に強く依存しているため今回は変更していない。英語のみのUI要素（telescope・ステータス行・補完ポップアップ）に限定した。

## 非ASCIIフォールバック文字（Swingフォント）にアンチエイリアスが効いていなかった問題の修正

- **症状**: 半角ASCII本文は `TtfMonoFont`（IBM Plex Mono TTFをラスタライズしてキャッシュしたビットマップグリフ）で描画されており、ラスタライズ時点で `RenderingHints.KEY_TEXT_ANTIALIASING`/`KEY_ANTIALIASING` を`ON`にして生成していたため滑らかだった。一方、日本語等の非ASCIIフォールバック文字（`getSwingFont()` で生成する `Font(Font.MONOSPACED, ...)`）や、スプラッシュ画面・ステータス行・telescope/補完ポップアップの `drawUiText()`/直接 `g2.drawString()` 呼び出しは、`paintContent()` に渡される `Graphics2D` 自体にアンチエイリアスのヒントを一切設定していなかった。Swingのデフォルトはデスクトップのフォントレンダリングヒント（`awt.font.desktophints`）依存で、環境によってはOFF相当になり、本文のビットマップフォントと非ASCII部分とでギザギザ具合が異なって見えていた。
- **修正**: `EditorCanvas.paintContent(Graphics2D g2)` の冒頭（本文セルサイズを読む前）で、`KEY_ANTIALIASING`/`KEY_TEXT_ANTIALIASING`/`KEY_FRACTIONALMETRICS`/`KEY_STROKE_CONTROL` の4つのヒントを一度だけ設定するようにした（`TtfMonoFont` のグリフラスタライズ時に使っているのと同じ4つの値）。`paintContent()` 配下の `drawSplashScreen`/`drawStatusLine`/`drawTelescopeOverlay`/`drawCompletionPopup`/`drawGutter`/`drawUiText`/`drawLineWithFullWidthSupport` 等は全て同じ `g2` インスタンスを引数として使い回す設計（本ファイル冒頭の「telescope・ステータス行・補完ポップアップの文字描画を MiscFixed ビットマップフォントに統一」節参照）のため、呼び出しの起点1箇所にヒントを設定するだけで画面内の全描画経路に反映される。
- **意図的に選ばなかった対策**: 個々の `drawString` 呼び出し箇所（10箇所以上）にそれぞれヒント設定を追加する案は、呼び出し漏れのリスクがあり、かつ本ファイルにある「共有 `g2` を使い回す」という既存設計の意図とも逆行するため採用しなかった。

## カーソルをINSERTモードでも常にブロック（■）表示にする変更

- **経緯**: v1〜のスコープ定義（本ファイル冒頭）では「NORMAL=ブロック/INSERT=縦棒」だったが、ユーザーから「カーソルをずっと■のままにしてほしい」という明示的な要望があり、モードによる見た目の切り替えを廃止した。
- **修正**: `EditorCanvas.drawCursor()` の `if (insertMode) { ... 2px縦棒 ... } else { ... ブロック ... }` という分岐をやめ、常に旧`else`側（ブロック塗り＋文字を背景色で再描画）だけを実行するようにした。
- **意図的に変更しなかった点**: `insertMode` フィールド・`setInsertMode()`・ステータス行の `"-- INSERT --"`/`"-- NORMAL --"` ラベル表示（`drawStatusLine`）はそのまま残した。要望は「カーソル形状」のみに限定されており、モード名の文字表示までは対象外のため。
- **テスト**: `test/dev/javatexteditor/ui/EditorCanvasTest.java` の Test 4（旧: 「INSERTモードのカーソルバーが2px幅で描画されているか」）を「INSERTモードでもカーソルはブロック（■）のまま描画されるか」に更新し、Test 3（NORMALモード）と同じアサーション形（`(1,1)` が前景色）に揃えた。

## 日本語IME変換中の文字列が画面に表示されない不具合の修正（InputMethodListener/InputMethodRequests対応）

- **症状**: 日本語入力（IME）で全角文字を変換中、入力中の文字列がエディタ画面には一切表示されず、OSのIME候補ウィンドウ（フローティングウィンドウ）にしか表示されないため、何を入力しているか分かりづらいという報告があった。確定後の文字列自体はバッファに正しく反映されていた。
- **原因**: 本プロジェクトは `JTextComponent` を使わず `EditorCanvas`（`JPanel`）に自前描画しているため、既存実装ではキー入力を `Main.java` の `KeyboardFocusManager.addKeyEventDispatcher` で受け、INSERT/COMMANDモードかつCtrl/Altなしの印字可能文字は `return false`（デフォルト処理へ委譲）してAWTのIME機構に処理を任せていた。ところが `EditorCanvas` は `InputMethodListener` を実装しておらず `getInputMethodRequests()` も未対応（`null`）だったため、AWTは「on-the-spot」入力方式（コンポーネント自身が変換中文字列を描画する方式）を選択できず、変換確定後の文字だけを合成 `KEY_TYPED` イベントとして配送する簡易方式（本ファイルでの通称: 対応前の状態）にフォールバックしていた。この方式では変換中の未確定文字列（preedit）そのものはアプリケーション側に一切渡されず、IME自身の浮動候補ウィンドウにのみ表示される。
- **修正**: `EditorCanvas implements InputMethodListener` とし、コンストラクタで `enableInputMethods(true)` + `addInputMethodListener(this)` を呼ぶようにした。
  - **`getInputMethodRequests()`**: `InputMethodRequests` の最小実装（`imeRequests`、匿名クラス）を返すようにした。`getTextLocation(TextHitInfo)` はカーソルの画面座標（`getLocationOnScreen()` + 既存の `xForCol()`/`cachedLineHeight`/`cachedCharWidth` から計算するピクセル位置）を返し、IMEの候補選択ウィンドウがカーソル直下に正しく追従するようにする。`getCommittedText`/`getSelectedText`/`cancelLatestCommittedText` 等の再変換（reconversion）用メソッドは、このエディタが対応する必要のある最小要件（変換中文字列の表示と確定文字列の反映）には不要なため、空文字列またはnullを返す簡略実装のままにしている（IME側の再変換機能は今回のスコープ外）。
  - **`inputMethodTextChanged(InputMethodEvent)`**: `event.getText()`（`AttributedCharacterIterator`）を `event.getCommittedCharacterCount()` で確定済み部分と変換中部分に分割する。確定済み部分は新設の `imeCommitHandler`（`Consumer<String>`、`Main.java`の`createLeaf()`から配線）に渡し、変換中部分は新設フィールド `composedText` に保持して画面に随時オーバーレイ表示する。
  - **`caretPositionChanged(InputMethodEvent)`**: 変換中文字列内でのIME側カーソル移動（変換候補の選択移動等）を通知するイベントだが、`composedText` を丸ごと再描画する現在の簡易実装では対応不要のため、`event.consume()` のみ行う。
  - **描画**: `drawCursor()` の冒頭で `composedText` が非空なら通常のブロックカーソル描画をスキップし、新設の `drawImeComposition()` を呼ぶ。カーソル位置に `composedText` を前景色で描画し、変換中であることが分かるようテーマの `accent` 色で下線を引く（Vim等のIME対応エディタでよくある表現）。下線は `g2.drawLine()` ではなく `g2.fillRect(x, y, w, 1)` で描画している点に注意（`drawLine` は `KEY_ANTIALIASING`/`KEY_STROKE_CONTROL` が有効な状態だと1px幅の水平ストロークが座標を中心に上下2行へ50%ずつ分散し、アクセント色と背景色の中間色ににじんで見えるため。`EditorCanvasTest` の実機検証でこの現象を確認して`fillRect`に変更した）。
  - **`Main.java`側の配線（`createLeaf()`）**: `canvas.setImeCommitHandler(committed -> ...)` で、`editor.isInsertMode() || editor.isCommandMode()` の場合のみ、確定文字列を1文字ずつ既存の `editor.processKey(0, ch, 0)`（`KEY_TYPED` 経由のIME確定文字と同じ経路）に流し込む。NORMALモード等では何もしない（既存の `KEY_TYPED` ハンドラのモードガードと同じ規約）。
  - **`setOnReturnToNormal`（INSERT→NORMAL遷移フック）**: 既存の `canvas.switchToHalfWidth()` 呼び出しに加え `canvas.clearImeComposition()` を追加し、モード遷移時に変換中オーバーレイの表示が残留しないようにした。
- **意図的にスコープ外とした点**: IMEの「再変換」（確定済みテキストを選択してIMEに戻す機能）は非対応（`getCommittedText`/`cancelLatestCommittedText`が空実装のため）。変換中に一部の文字だけを選択・移動するような高度なIME UI（`caretPositionChanged`を使った変換中カーソル位置の描画）も今回は実装していない。これらはこのエディタが一般的なテキスト入力用途に必要な最小要件を超えるため、要望があれば別途対応する。
- **テスト**: `test/dev/javatexteditor/ui/EditorCanvasTest.java` に3テスト追加（Test 31〜33）。`InputMethodEvent` を直接構築して `canvas.inputMethodTextChanged(evt)` を呼び出すことで、実際のIMEやディスプレイなしに（`committedCharacterCount` を0や全文字数に変えることで）「変換中」「確定」両方の状態を検証している。変換中オーバーレイの下線色（ピクセル検証）・`setImeCommitHandler`への確定文字列通知・`clearImeComposition()`による強制クリアの3点を確認済み。実際のIME（fcitx/ibus/Windows IME等）との統合動作（候補ウィンドウの位置が正しく追従するか等）はヘッドレス環境のため検証できておらず、既知のテストギャップとして残る（⑫openjdk-source-tracing・⑳telescope-pickerと同種）。

## zz（`centerCursorLineInViewport`）が文書末尾でクランプしていた挙動の廃止・文書末尾を超えた領域の白/黒塗り

- **不具合/要望**: NORMALモード `zz`（カーソル行を viewport 中央に表示する）は、文書末尾付近で `maxScrollRow = totalLines - visibleRows` にクランプしており、カーソル行が実際には画面中央より上に表示されてしまっていた（Vim本家の `zz` は文書末尾を超えてでもカーソル行を中央に置き、はみ出た分は `~` で埋める）。ユーザーから「文書末尾を超えるスクロールが要求されても中央にカーソル行を表示してほしい。また文書末尾を超えた領域はライトテーマなら白、ダークテーマなら黒で描画してほしい」という要望があった。
- **修正1（`ModalEditor.centerCursorLineInViewport()`）**: `maxScrollRow` によるクランプを削除した。`newScrollRow = Math.max(0, cursorRow - visibleRows / 2)` のみ（先頭方向の 0 クランプだけ残す）。`EditorCanvas.setScrollRow()` はもともと下限（0）しかクランプしておらず上限クランプを持っていなかったため、`ModalEditor` 側の呼び出し元だけを直せば済んだ。
- **修正2（`EditorCanvas.paintContent()`、本文描画ループ直後）**: 文書行が尽きて描画すべき行がない画面領域（`scrollRow + visibleRows > lines.length` になるすべてのケース）を、`theme.background`（ベージュ/柔らかい黒）ではなく `Color.WHITE`（ライト）/`Color.BLACK`（ダーク）で明示的に塗るようにした。**この白黒塗りは zz 起因のケースに限定せず、「viewportが文書より広い」という状況全般（例: 3行しかない短いファイルを開いた直後、scrollRow=0でも末尾3行目より下は全て白/黒になる）に常に適用される**。これはユーザーに「zz起因のケースだけに絞るか、Vim互換で常時か」を確認した上での決定（`AskUserQuestion`でVim互換・常時適用を選択）。Vimの `~`（文書末尾より下の行に表示される記号）と同じ「文書の外」を視覚的に明示する目的の一貫した挙動として実装した。
  - 実装は「本文描画ループ（`scrollRow`〜`lastRow-1`）が描いた最後の画面行より下」を `voidScreenRowStart = Math.max(0, lastRow - scrollRow)` として求め、そこから `visibleRows` の末尾（ステータス行の直前）まで1回の `fillRect` で塗るだけ。`lastRow - scrollRow` が負になるケース（scrollRowが文書行数を大きく超えて画面全体が「文書の外」になる場合）に備え `Math.max(0, ...)` でクランプしている。
  - ステータス行帯（`drawStatusLine()`、本文描画より後に呼ばれる）はこの白黒塗りの対象外（`visibleRows * lineHeight` までしか塗らないため、ステータス行の高さ分は最初から対象範囲に含まれない）。
  - カーソルは常に有効な `cursorRow`（実在する行）にしかクランプされないため、この白黒領域にカーソルが描画されることはない（既存のカーソル移動系コマンドの制約をそのまま利用しており、今回新規のクランプ処理は追加していない）。
- **既存テストへの影響**: `test/dev/javatexteditor/ui/EditorCanvasTest.java` の Test 1/2（LIGHT_MODE/DARK_MODE背景色）・Test 13（clearSelection後の背景色確認）は、いずれも「文書が短い（1〜2行）のに300px高のcanvasでy=100〜150付近のピクセルを検証する」という書き方をしていたため、この変更で白黒塗り領域に入ってしまい失敗するようになった。文書内（実在する行のy範囲）のピクセルに検証対象を変更して修正した（該当行にコメントで理由を記載済み）。
- **テスト**: `test/dev/javatexteditor/editor/ZzCenterScrollTest.java` の `testZzClampedNearFileEnd` を `testZzCentersEvenPastFileEnd` に改名し、期待値をクランプ後の値からクランプなしの値（101行・visibleRows=14・cursorRow=99 → scrollRow=92）に更新した（計16テスト、他は変更なし）。`test/dev/javatexteditor/ui/EditorCanvasTest.java` に3テスト追加（Test 34〜36）: 文書末尾を大きく超えてスクロールした場合にLIGHT_MODEでは純白・DARK_MODEでは純黒で塗られること、および文書内領域（実在する行）は従来通りの通常背景色のままであることを確認する。IME関連の3テスト（Test 31〜33）と合わせて計36/36 PASS。

## IME実装（InputMethodListener対応）を入れても変換中文字列が表示されなかった追加修正: EditorCanvasがフォーカスを一切持てていなかった問題

- **症状**: 上記「日本語IME変換中の文字列が画面に表示されない不具合の修正」で`EditorCanvas`に`InputMethodListener`/`InputMethodRequests`を実装したにもかかわらず、実機で確認したところ症状が全く変わらず、変換中の文字列は依然としてエディタ画面に描画されなかった。
- **根本原因**: `JPanel`は既定で`isFocusable() == false`である。本プロジェクトは`Main.java`の`KeyboardFocusManager.addKeyEventDispatcher(...)`（`frame`単位でウィンドウがフォーカスされているかだけを見るグローバルディスパッチャ）でキー入力を処理する設計のため、通常のテキスト編集操作は`EditorCanvas`が実際のAWTフォーカスオーナーになっていなくても問題なく動作していた。そのため、`canvas().requestFocusInWindow()`が呼ばれている箇所（`Ctrl+W`のペイン移動等）はあったものの、それ以外の「アクティブペインが切り替わる」全ての箇所（起動直後・マウスクリックでのペイン切替・`:split`/`:vsplit`直後・ペインを閉じた後）でこの呼び出しが漏れており、`EditorCanvas`が`setFocusable(true)`すら呼んでいなかったため、そもそも`requestFocusInWindow()`を呼んでも常に失敗する状態だった。
  - AWTのInputContext（IME）は「実際のフォーカスオーナーであるComponent」にのみ関連付けられる。フォーカスオーナーになれないコンポーネントに`enableInputMethods(true)`や`InputMethodListener`をいくら実装しても、AWT側から一切呼び出されない。これが「実装したのに直らない」の直接の原因だった。
- **修正**:
  1. `EditorCanvas`のコンストラクタに`setFocusable(true)`を追加した。
  2. `Main.java`内で`active[0]`（アクティブペイン）が変わる全箇所に`active[0].canvas().requestFocusInWindow()`を追加した: 起動時（`frame.setVisible(true)`直後）・マウスクリックによるペイン切替（`mousePressed`）・`:split`/`:vsplit`直後（`setSplitHorizontalCallback`/`setSplitVerticalCallback`）・ペインを閉じた後（`setExitCallback`）。既存の`Ctrl+W`によるペイン移動（`setMovePanePrevCallback`/`setMovePaneNextCallback`）は元々呼んでいたためそのまま。
- **教訓**: `KeyboardFocusManager`のグローバルディスパッチャでキー入力を処理する設計は「どのコンポーネントが実際にフォーカスを持っているか」を意識しなくても通常のキー入力自体は動いてしまうため、フォーカス依存の別機能（IME、将来的にコピー&ペーストのシステムクリップボード連携以外でフォーカスに依存する機能等）を追加する際は、必ず「対象コンポーネントが実際にフォーカスオーナーになれているか」を疑って確認すること。今回のように「機能は正しく実装したのに全く効果がない」場合、機能自体のロジックよりも前提条件（フォーカス）を先に疑うべきだった。
