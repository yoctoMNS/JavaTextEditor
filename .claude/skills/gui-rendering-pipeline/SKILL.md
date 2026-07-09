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
