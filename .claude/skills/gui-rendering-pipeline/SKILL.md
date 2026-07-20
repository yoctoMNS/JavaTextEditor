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
  - **`inputMethodTextChanged(InputMethodEvent)`**: `event.getText()`（`AttributedCharacterIterator`）から `event.getCommittedCharacterCount()` 分の確定済み部分だけを取り出し、`imeCommitHandler`（`Consumer<String>`、`Main.java`の`createLeaf()`から配線）に渡す。変換中（未確定）部分は自前描画しない（**2026-07にこの節の下の「変換中の文字列を自前描画しない」節の通り撤回**。撤回前は `composedText` フィールドに保持してカーソル位置へオーバーレイ表示していたが、この節はその設計を残したまま読むと誤解するため、実装は下の節を正としてこのフィールド・描画は既に削除済み）。
  - **`caretPositionChanged(InputMethodEvent)`**: 変換中文字列内でのIME側カーソル移動（変換候補の選択移動等）を通知するイベントだが、自前描画をしないため対応不要で `event.consume()` のみ行う。
  - **`Main.java`側の配線（`createLeaf()`）**: `canvas.setImeCommitHandler(committed -> ...)` で、`editor.isInsertMode() || editor.isCommandMode()` の場合のみ、確定文字列を1文字ずつ既存の `editor.processKey(0, ch, 0)`（`KEY_TYPED` 経由のIME確定文字と同じ経路）に流し込む。NORMALモード等では何もしない（既存の `KEY_TYPED` ハンドラのモードガードと同じ規約）。
- **意図的にスコープ外とした点**: IMEの「再変換」（確定済みテキストを選択してIMEに戻す機能）は非対応（`getCommittedText`/`cancelLatestCommittedText`が空実装のため）。
- **テスト**: `test/dev/javatexteditor/ui/EditorCanvasTest.java`。`InputMethodEvent` を直接構築して `canvas.inputMethodTextChanged(evt)` を呼び出すことで、実際のIMEやディスプレイなしに（`committedCharacterCount` を0や全文字数に変えることで）「変換中」「確定」両方の状態を検証している。実際のIME（fcitx/ibus/Windows IME等）との統合動作（候補ウィンドウの位置が正しく追従するか等）はヘッドレス環境のため検証できておらず、既知のテストギャップとして残る（⑫openjdk-source-tracing・⑳telescope-pickerと同種）。

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

## 変換中の文字列を自前描画しない（EditorCanvasの自前オーバーレイとネイティブIMEの表示が重なる不具合の修正）

- **症状**: フォーカス取得漏れの修正後、実機で確認したところ「日本語の入力描画とIMEの表示が重なってしまっている」という報告があった。`EditorCanvas`が実フォーカスを持てるようになったことで`getInputMethodRequests().getTextLocation()`が正しく機能し、OS/JDKのネイティブ側IME浮動ウィンドウ（変換中の文字列を表示するウィンドウ）が正しくカーソル位置に追従するようになった一方、`drawImeComposition()`で描画していた自前のオーバーレイ（下線付きの変換中文字列）も同時に同じ位置付近に表示されるようになり、両者が重なって見えるようになった。
- **原因の整理**: このプロジェクトは`JTextComponent`を使わない自前描画コンポーネントのため、AWTの入力方式（on-the-spot／over-the-spot／root-window等）を完全に自前実装で制御することはできない。多くのプラットフォーム（Windows IME・macOSの入力方式・LinuxのIBus/Fcitx等のXIM経由の実装）は、クライアントが`InputMethodRequests`を提供していても、変換中の文字列自体を表示する浮動ウィンドウを独自に描画する（`getTextLocation()`で渡した座標に追従させるだけで、文字列の描画自体はネイティブ側が行う）。そのため、アプリケーション側でも同じ文字列を`composedText`としてオーバーレイ描画すると、必然的に二重表示・重なりが発生する。JDKが内部的に「クライアントが変換中の文字列描画を完全に肩代わりする」true on-the-spotを実現できるのは`JTextComponent`のような、JDK内部の特別な連携（`Caret`/`Highlighter`経由で変換中テキストをドキュメントに一時挿入し下線付きで描画する仕組み）を持つコンポーネントに限られ、独自の`JPanel`描画コンポーネントではこの連携を再現できない（再現には非常に深いJDK内部APIへの依存が必要になり、このプロジェクトの「JDK標準APIのみ・シンプルさ優先」という方針と相容れない）。
- **修正**: `EditorCanvas`から変換中文字列の自前描画を完全に撤去した。
  - `composedText`フィールド・`drawImeComposition()`メソッド・`drawCursor()`冒頭の`composedText`分岐・`clearImeComposition()`メソッド（および`Main.java`の`setOnReturnToNormal`からの呼び出し）を削除した。
  - `inputMethodTextChanged(InputMethodEvent)`は「確定済み部分（`getCommittedCharacterCount()`）だけを`imeCommitHandler`へ通知する」処理のみに単純化した。変換中（未確定）部分は一切保持・描画しない。
  - `getInputMethodRequests()`（`imeRequests`）・`getTextLocation()`はそのまま維持している。これはネイティブ側のIME浮動ウィンドウを正しくカーソル位置に追従させるために必須で、撤去すると「フォーカス取得漏れ」修正前と同じ「IMEウィンドウがカーソルと無関係な位置に表示される」問題が再発するため。
- **結果として得られる挙動**: 変換中の文字列表示はネイティブ側のIME浮動ウィンドウのみが担当し、カーソル直下（`getTextLocation()`が返す位置）に正しく追従する。確定した文字列のみがエディタのバッファに反映され、通常の本文として描画される。これは多くの非`JTextComponent`系ネイティブアプリ（ターミナルエミュレータ等）と同じ一般的な挙動であり、「エディタ画面に直接文字が描画される」という当初の要望（テキストエディタ本体が変換中の文字列を独自にレンダリングする）を厳密には満たさないが、「入力中の文字列がカーソル位置から乖離した場所に表示されて分かりづらい」という本来の問題は解消される。真に本文と同じフォント・スタイルで変換中文字列をエディタ内に描画する（ネイティブIMEウィンドウを完全に無効化する）ことは、AWTの入力方式制御の性質上、標準APIの範囲では確実な方法がなく、将来的に要望があれば追加調査が必要（既知の制約として残す）。
- **テスト**: `test/dev/javatexteditor/ui/EditorCanvasTest.java`のTest 31〜32を「オーバーレイ描画のピクセル検証」から「`setImeCommitHandler`への確定文字列通知のみを検証（変換中はコールバックが呼ばれないことも確認）」に置き換えた（計35テスト、他のテスト番号もそれに合わせて詰めた）。
- **追記（撤回）**: この節の方針はその後のユーザーフィードバックで撤回された。詳細は次の節「変換中の文字列を自前描画に戻す（ネイティブIMEウィンドウとは表示位置をずらして共存させる）」を参照。

## 変換中の文字列を自前描画に戻す（ネイティブIMEウィンドウとは表示位置をずらして共存させる）

- **経緯**: 上記「変換中の文字列を自前描画しない」節の対応（自前オーバーレイを完全撤去し、ネイティブIME側の浮動ウィンドウのみに一本化）を実機で確認したところ、「重なりは解消したが、リアルタイムのキー入力描画に対応できていない」という指摘があった。つまりユーザーが本来求めていたのは「重ならないこと」に加えて「エディタ本体の画面上でも、入力中の文字列をリアルタイムに（本文と同じフォント・スタイルで）見られること」の両立であり、自前描画を完全に諦める撤去は行き過ぎだった。
- **方針転換**: 自前描画（`composedText`/`drawImeComposition()`/`clearImeComposition()`）を復活させ、**ネイティブIME側の浮動ウィンドウの表示位置（`getTextLocation()`の返り値）を意図的にずらすことで重なりを回避する**方式に変更した。「両方の表示を諦めずに共存させる」という、多くの実用的な日本語入力対応アプリ（インライン変換中テキスト＋別枠の変換候補リスト）と同様のUXパターンに倣った。
- **修正内容**:
  - `composedText`フィールド・`drawImeComposition()`（`drawCursor()`冒頭で`composedText`が非空なら通常のブロックカーソルの代わりに呼ぶ）・`clearImeComposition()`（および`Main.java`の`setOnReturnToNormal`からの呼び出し）を復元した。
  - `inputMethodTextChanged(InputMethodEvent)`も「確定済み部分は`imeCommitHandler`へ、未確定部分は`composedText`に保持してリアルタイムにオーバーレイ表示する」という分割ロジックに戻した。
  - **`getTextLocation(TextHitInfo)`の返り値だけを変更した**: 従来（初回のIME対応時）はカーソル位置の1行下（`y + lineHeight`）を返していたが、これがネイティブ側の浮動ウィンドウと自前オーバーレイ（カーソルのある現在行）の重なりの原因だったため、**2行下（`y + 2 * lineHeight`）**を返すように変更した。自前オーバーレイは常に「現在行」に描画されるため、ネイティブ側の候補ウィンドウを最低でも1行分の空白を挟んだ位置に配置することで、両者が視覚的に衝突しないようにしている。
  - この方式では、ネイティブIME側の浮動ウィンドウが変換中の文字列自体を重複して表示する可能性は残る（「変換中の文字列を自前描画しない」節で述べた通り、多くのプラットフォームでは`getTextLocation()`を実装してもクライアント側の自前描画を完全に信頼する「真のon-the-spot」にはならず、ネイティブ側も自身の浮動ウィンドウに同じ文字列を表示する「over-the-spot」的挙動になりやすいため）。ただし、実際の日本語入力の一般的なUX（インラインの読み仮名表示＋別枠の変換候補選択リスト）に近い見た目になり、位置がずれているため「重なって読めない」状態は解消される。
- **意図的にこれ以上調査しなかった点**: 「ネイティブ側の浮動ウィンドウを完全に非表示にし、自前描画だけで完結させる」ことは、AWTの入力方式が最終的にはOS/プラットフォーム側の実装に依存するため、標準APIの範囲では確実な制御方法がない（上記「変換中の文字列を自前描画しない」節の「原因の整理」参照）。2行下にずらす方式は実機での見え方を確認できないヘッドレス環境からの推測に基づくヒューリスティックであり、実際のプラットフォーム（Windows IME/macOS/Linux fcitx・ibus等）でのオフセット量の妥当性は要実機確認（既知のテストギャップ）。
- **テスト**: `test/dev/javatexteditor/ui/EditorCanvasTest.java`のTest 31〜33（計36テスト）。Test 31（変換中オーバーレイの下線色のピクセル検証）・Test 32（確定時のコールバック通知とオーバーレイ解除）を復元し、新たにTest 33（`getInputMethodRequests().getTextLocation(null)`が現在行から2行分下の位置を返すことを検証）を追加した。
- **追記（撤回・2026-07）**: この「2行下にずらす」オフセットはその後Windows11実機で不具合が確認され撤去された。詳細は次の節「ネイティブIME候補ウィンドウの人為的な位置オフセットを撤去（Windows11実機での重なり不具合の修正）」を参照。

## ネイティブIME候補ウィンドウの人為的な位置オフセットを撤去（Windows11実機での重なり不具合の修正）

- **症状**: Windows11実機で、画面下寄りの行で日本語入力（変換）を行うと、ネイティブIMEの変換候補ウィンドウ（Tabキーで選択する候補一覧）が、カーソル位置とは無関係な画面上部の行（既存の本文テキスト）と重なって表示され、読めなくなるという報告があった。上記「変換中の文字列を自前描画に戻す」節で導入した`getTextLocation()`の「カーソル位置より2行下」という人為的なオフセットは、この節の時点で「実機での見え方を確認できないヘッドレス環境からの推測に基づくヒューリスティックであり…要実機確認（既知のテストギャップ）」と明記した通り、実機未検証のまま採用されていた値だった。
- **原因**: `getTextLocation()`が返す座標に`+2*lineHeight`を機械的に加算していたため、カーソルが画面（モニタ）下端に近い行にある場合、「カーソル行+2行」という報告座標がモニタの表示可能領域を超えてしまうケースが生じる。WindowsのIMEは候補ウィンドウを表示する際、報告された座標を基準に「下に十分な空きがなければ上に反転して表示する」という判定を行うが、この判定の基準点自体が人為的に(実際のカーソルより)2行分下にずらされた誤った値になっているため、反転後の候補ウィンドウの実際の表示位置も、本来カーソルがあるべき位置から大きくかけ離れてしまい、無関係な本文行と重なって見える結果になっていた。
- **修正**: `EditorCanvas.imeRequests.getTextLocation()`から`+2*lineHeight`の加算を撤去し、カーソルの実際の画面座標（`base.x + x`, `base.y + y`）をそのまま返すようにした。IME側（Windows/macOS/Linuxいずれも）はこの座標を基準に、画面の残り空間を見て候補ウィンドウを下または上に自動配置するロジックを標準で持っており、これは`JTextComponent`を使わない他の多くのネイティブアプリ（ターミナルエミュレータ等）が採用している一般的な方式でもある。人為的なオフセットで「先回りして」ずらす必要はなく、むしろその方が画面端付近での誤配置を誘発することが実機検証で判明した。
- **自前オーバーレイ（`drawImeComposition()`、現在行に描画）との重なりについて**: ネイティブの候補ウィンドウは通常この矩形の下端（＝現在行の下）を起点に表示されるため、オフセットを撤去しても自前オーバーレイと直接重なることは基本的にない。なお、変換中の未確定文字列自体をネイティブ側が別途カーソル直上に描画する場合（プラットフォームのover-the-spot挙動）、自前オーバーレイと同じ文字列が二重に見える可能性は残るが、これは同一内容の重複描画であり、今回報告された「候補ウィンドウが無関係なテキストと重なって読めなくなる」問題とは別種の軽微な既知の制約として許容した。
- **教訓**: IME候補ウィンドウの表示位置はOS側の空間判定ロジックに委ねるのが正しく、アプリケーション側で「重ならないように」と座標を先回りしてずらす対処は、ヘッドレス環境で実機検証できないまま推測値を採用すると、画面端という境界条件で新たな不具合を生みやすい。今後IME関連の座標計算を変更する場合は、`getTextLocation()`は常に実際のカーソル座標をそのまま返す方針を維持し、見た目の重なり対策が必要な場合は自前描画側（`drawImeComposition()`等）の表示内容・タイミングの調整で対応することを優先する。
- **テスト**: `test/dev/javatexteditor/ui/EditorCanvasTest.java`のTest 33を、「`getTextLocation(null)`が現在行から2行分下の位置を返す」検証から「オフセットなしでカーソルの実座標（ヘッドレス環境でbase=(0,0)、現在行0のためy==0）をそのまま返す」検証に更新した（計36/36 PASS）。実際のWindows11実機での最終的な見た目確認（候補ウィンドウが画面端でも正しく表示されるか）はヘッドレス環境のため引き続き検証できておらず、既知のテストギャップとして残る。

## `:wrap` / `:nowrap`（画面端での折り返し表示）の実装

- **要望**: `:wrap`で画面端に達した長い行を折り返して表示し、`:nowrap`で折り返さない（従来通りの横スクロール表示に戻す）機能を追加してほしいという依頼。
- **状態の持ち方**: `ModalEditor`に`wrapEnabled`（既定`false`＝nowrap相当。従来の横スクロール表示と完全に後方互換）を追加し、`:wrap`/`:nowrap`コマンドで切り替える。`syncCanvas()`が`canvas.setWrapEnabled(wrapEnabled)`を呼び、`EditorCanvas`側にも同名フィールドを持たせるモデル→ビューの同期パターン（`setInsertMode`等と同じ）を踏襲した。
- **wrap時は横スクロール(`scrollCol`)を完全に無効化する**。`ensureCursorColVisible()`はwrap時、`scrollCol`を常に`0`に強制するだけの早期returnになる（横スクロールする必要がそもそも無いため）。
- **折返しの単位はセル（半角=1・全角=2）で、全角文字が折返し境界をまたがない**よう`EditorCanvas.wrapSegments(line, visibleCols)`が貪欲法で行を`{開始charIndex, 終了charIndex}`のセグメント列に分割する。空行でも必ず1セグメント（`{0,0}`）を返す。
- **描画は「wrapPlan」（`List<WrapRow>`、各要素が1画面行=1論理行の1セグメントに対応）を`paintContent()`内で一度だけ構築し、本文描画・カーソル・ガター・診断アンダーライン・選択ハイライト・検索ハイライトの全てで使い回す設計**にした。`scrollRow`は常に文書行の境界（折返し途中ではなく行頭）を指す前提を維持し、`buildWrapPlan()`は`scrollRow`から`visibleRows`分（画面に収まる分だけ）のセグメントを積み上げる。これにより「1論理行が画面上で複数行になる」ことをO(可視領域)の作業量で扱える（数十万行規模のファイルでも`buildWrapPlan`が全文書を舐めることはない）。
- **`scrollRow`の意味は変えていない**（wrap時も非wrap時も「先頭に表示する文書行番号」のまま）。折返しで生じる画面行のカウントは`wrapPlan`がその場で計算するだけで、`scrollRow`自体を「画面行番号」に変換するような設計変更はしなかった（`zz`等、既存の`canvas.setScrollRow()`直接呼び出し箇所に一切手を入れずに済むため）。
- **`ensureCursorVisible()`のwrap時実装（`ensureCursorVisibleWrapped()`）**は、`scrollRow`からカーソル行までの折返し行数を積算し、`visibleRows`に収まるよう`scrollRow`を1行ずつ前進させる（非wrap時の「カーソル行が画面下端を超えたら`scrollRow`をカーソル行-可視行数+1に一気に飛ばす」というO(1)実装とは異なり、折返し行数はスクロール位置に依存するため積算が必要）。`G`・`gg`・`:行番号`等で`scrollRow`からカーソル行までの距離が`WRAP_SCROLL_SCAN_LIMIT`（4096行）を超える大きなジャンプの場合は、正確な積算を打ち切り「`scrollRow = cursorRow - visibleRows + 1`」の近似値にフォールバックする（数十万行規模のファイルで無制限にO(距離)の計算が走るのを防ぐ、③`project-wide-search`の`WRAP_SCROLL_SCAN_LIMIT`的な安全装置と同じ設計思想）。
- **カーソル・IME候補ウィンドウ位置・補完ポップアップのアンカー計算は`wrapScreenPosition()`（`scrollRow`から対象行までの折返し行数を辿って画面座標を求める）に一本化**した。`paintContent()`内（`wrapPlan`が既に手元にある場面）ではさらに軽量な`findSegmentPixel(wrapPlan, ...)`（構築済みプランを線形走査するだけ）を使う。IMEの`getTextLocation()`は`paintContent()`のサイクル外で任意のタイミングで呼ばれるため`wrapPlan`を持たず、`wrapScreenPosition()`を直接使う。
- **選択ハイライト・検索ハイライトの行内範囲描画は`drawWrappedRangeSpan()`に共通化**した。1つの文書行が複数の`wrapPlan`エントリ（セグメント）にまたがる場合、各エントリとの交差区間だけを塗るセグメント単位の分割描画になる。VISUAL LINEモードは元々行全体を塗るため、そのモードのみ「該当`docRow`を持つ`wrapPlan`エントリ全てを全幅で塗る」という別経路（`drawSelectionHighlightWrapped()`内で分岐）にした。
- **ガター（E/Wマーカー）は折返しの継続行（`segStart != 0`）には表示しない**。診断は論理行単位の情報であり、折返し2行目以降にも同じマーカーが繰り返し表示されると冗長なため、先頭セグメントのみに限定した。一方、診断のアンダーライン（波線）は継続行も含め全セグメントの下に描画する（アンダーラインは「このセグメントの文字列の下」という視覚的な意味合いが強く、マーカーとは性質が異なるため）。
- **意図的にスコープ外とした点**: `j`/`k`によるカーソル移動はVim本家と同じく折返し後の画面行単位ではなく、常に論理行（文書行）単位のままとした（Vimの`gj`/`gk`相当の画面行移動キーは本プロジェクトでは未実装。今回の要望はあくまで「表示の折返し」であり、カーソル移動の意味論変更は求められていないため）。`:split`/`:vsplit`の各ペインは独立した`ModalEditor`+`EditorCanvas`を持つため、`:wrap`はアクティブペインのみに効く（ペイン間で共有されない。既存の`cellW`/`cellH`等ペインローカルな表示設定と同じ扱い）。
- **テスト**: `test/dev/javatexteditor/editor/WrapCommandTest.java`（新設・6テスト）で`:wrap`/`:nowrap`コマンドの状態遷移・`syncCanvas()`経由での`EditorCanvas`への反映を検証。`test/dev/javatexteditor/ui/EditorCanvasTest.java`にTest 37〜43（7テスト追加・計43/43）で、wrap時に長い行が2画面行目まで折り返して描画されること（nowrap時は同条件で「文書末尾超過」の白塗り領域になることとの対比）・折返し先セグメントのカーソル座標・`ensureCursorColVisible`が横スクロールしないこと・`ensureCursorVisible`の折返し考慮scrollRow計算を検証した。

## INSERT→NORMAL遷移時の自動半角切り替え（`EditorCanvas.switchToHalfWidth()`）がWindowsで効かない不具合の修正

- **経緯**: INSERTモードで全角入力（日本語IME）中に`Esc`でNORMALモードへ戻ると、IMEが全角のままになりNORMALモードのキーバインドを誤入力してしまう問題への対策として、`switchToHalfWidth()`（`ic.selectInputMethod(Locale.ENGLISH)`のみを呼ぶ実装）が既に存在し、`ModalEditor.onReturnToNormal`経由でINSERT→NORMAL遷移のたびに呼ばれるよう配線済みだった。Linux環境では動作したが、Windows実機では切り替わらないという報告があった。
- **原因**: `InputContext.selectInputMethod(Locale)`は「指定したLocaleに対応する別のInputMethodエンジンへ切り替える」API であり、Windowsで有効なのは、コントロールパネルの言語設定に日本語IMEとは別に「英語(米国)」等の入力方式（別のキーボードレイアウト）を追加インストールしている場合のみ。日本語IME（Microsoft IME）1つしか入力方式を追加していない一般的な環境では`Locale.ENGLISH`に対応するエンジンが存在せず`UnsupportedOperationException`になり、既存の`try/catch(Exception ignored)`で握りつぶされて何も起きない。
- **修正**: `switchToHalfWidth()`に`ic.setCompositionEnabled(false)`の呼び出しを追加した（`selectInputMethod`より先に、独立したtry/catchで）。`setCompositionEnabled(false)`はWindows上ではIMM32の`ImmSetOpenStatus(FALSE)`相当に対応し、追加のキーボードレイアウトが無い単一の日本語IME環境でも直接入力（半角英数字）へ切り替えられる。`selectInputMethod(Locale.ENGLISH)`の呼び出しはLinux（IBus/Fcitx等、Localeごとに別エンジンが登録される環境）向けに残しており、どちらか一方しかサポートされないプラットフォームでも他方の例外を握りつぶすだけで済むようにした（両方試す設計）。
- **意図的にテストを追加しなかった理由**: `InputContext`/IMEの実際の切り替わり確認はOSのネイティブIME実装に依存するため、このコンテナ（ヘッドレス環境）は元より通常のCI環境でも自動テストできない。既存の`ClipboardTest`（システムクリップボード連携）と同種の既知のテストギャップとして扱い、Windows実機での動作確認はユーザー側に委ねた。

## `charCellWidth()`のUnicode範囲漏れによるマルチバイト文字（かぎ括弧等）の重なり不具合の修正

- **症状**: 「「1あ」のようなかぎ括弧を含む文章や、日本語コメントの行末付近で、文字同士が重なって描画される」という報告があった。全角/半角の判定を担う`EditorCanvas.charCellWidth(int codePoint)`が対象とする文字数分だけ`xForCol()`/`drawLineWithFullWidthSupport()`が横方向の描画位置(`x`)を進めるため、実際にレンダリングされるグリフ幅より`charCellWidth()`の判定値が小さいと、次の文字が手前の文字の右半分に食い込んで重なって見える。
- **原因**: `charCellWidth()`の全角判定範囲が「ひらがな・カタカナ(0x3040-0x30FF)」「CJK統合漢字(0x4E00-0x9FFF)」「0xFF00-0xFFEF」の3レンジのみで、**CJK記号・句読点ブロック(U+3000-U+303F)が漏れていた**。「」『』・、。・全角スペース(U+3000)・波ダッシュ(U+301C)等はこのブロックに属するが、ひらがな・カタカナのブロック(0x3040〜)の手前で判定漏れし、デフォルトの半角(1)扱いになっていた。一方、これらの文字はSwingフォールバックフォント（`ttfFont.isSupported()`がfalseを返しASCII外として扱われる）で実際には全角幅のグリフとして描画されるため、次の文字の描画開始位置(x)が1セル分早すぎて、かぎ括弧等の右半分に次の文字が重なっていた。
- **副次的な誤りも修正**: `0xFF00-0xFFEF`（Halfwidth and Fullwidth Forms ブロック）を丸ごと全角(2)扱いにしていたが、このブロックには**半角カタカナ(U+FF61-U+FF9F)・半角ハングル(U+FFA0-U+FFDC)**という半角(1セル)幅の文字も含まれており、これらを誤って全角判定すると逆に不要な余白が生じる（重なりの逆の不具合）。正しい範囲である`0xFF01-0xFF60`（全角英数・記号）と`0xFFE0-0xFFE6`（全角記号: ￠￡￢￤￥￦）のみを全角(2)とし、それ以外の同ブロック内の文字（半角カタカナ等）はデフォルトの半角(1)に落ちるよう修正した。
- **修正箇所**: `EditorCanvas.charCellWidth(int codePoint)`に`0x3000-0x303F`（CJK記号・句読点）のレンジを追加し、`0xFF00-0xFFEF`の全角判定を`0xFF01-0xFF60`・`0xFFE0-0xFFE6`に絞り込んだ。`charCellWidth()`は`drawLineWithFullWidthSupport()`（本文描画）・`xForCol()`（カーソル/選択範囲/検索ハイライトのX座標計算）・`drawCursor()`（カーソルブロック幅）・`uiTextWidth()`系（telescope等UI要素の幅計算）など描画パイプライン全体で共有される唯一の判定関数のため、この1箇所の修正だけで全ての描画経路に反映される。
- **意図的にスコープ外とした点**: 真のUnicode East Asian Width判定（`Character.UnicodeScript`や外部データを使った完全な判定）は行わず、既存方針（SKILL.md冒頭「厳密なUnicode East Asian Width判定は複雑だが実用上十分」）を踏襲し、Java開発・日本語文章で頻出する範囲の追加漏れの補完に留めた。絵文字（サロゲートペアの補助面）・囲み文字（丸数字①②③等、U+2460-24FF）・CJK互換用字（U+F900-FAFF）等の追加は、今回の不具合報告（かぎ括弧の重なり）の再現範囲を超えるため追加していない。
- **テスト**: `test/dev/javatexteditor/ui/EditorCanvasTest.java`のTest 5bを新設（計37テスト）。`charCellWidth(0x300C)`（「）・`charCellWidth(0x300D)`（」）・`charCellWidth(0x3001)`（、）・`charCellWidth(0x3002)`（。）・`charCellWidth(0x3000)`（全角スペース）が2、`charCellWidth(0xFF71)`（半角カタカナ「ｱ」）・`charCellWidth(0xFF9F)`（半角濁点）が1であることを検証。

## `charCellWidth()`に幾何学記号（◯●等）の判定漏れがあった不具合の修正

- **症状**: 「◯●の描画がマルチバイト対応していない」という報告。上記のかぎ括弧不具合と同種で、◯（U+25EF LARGE CIRCLE）・●（U+25CF BLACK CIRCLE）等を含む行で後続文字がグリフの右半分に重なって描画される。
- **原因**: これらはUnicodeの Geometric Shapes ブロック（U+25A0-U+25FF。■□▲△▼▽◆◇○◎●等、日本語の文章で正誤記号・箇条書き記号として頻出）に属するが、`charCellWidth()`の判定範囲に含まれておらず、デフォルトの半角(1)扱いになっていた。この記号群はUnicode East Asian Width上は「Ambiguous」だが、日本語フォント・日本語の文章中では全角幅のグリフとして描画されるため、既存の全角記号（0xFF01-0xFF60等）と同じ理由で全角(2)判定が必要だった。
- **修正**: `EditorCanvas.charCellWidth(int codePoint)`に`0x25A0-0x25FF`（Geometric Shapes ブロック全体）を全角(2)判定として追加した。かぎ括弧不具合の修正時と同様、この1箇所の修正だけで`drawLineWithFullWidthSupport()`/`xForCol()`/`drawCursor()`等の描画パイプライン全体に反映される。
- **意図的にスコープ外とした点**: 上記かぎ括弧不具合の節で明示的にスコープ外とした囲み文字（丸数字①②③等、U+2460-24FF、Enclosed Alphanumerics ブロック）は、今回の不具合報告（◯●）の再現範囲に含まれないため引き続き対象外とした。絵文字（サロゲートペアの補助面）も同様に対象外。
- **テスト**: `test/dev/javatexteditor/ui/EditorCanvasTest.java`のTest 5cを新設（計48テスト）。`charCellWidth(0x25EF)`（◯）・`charCellWidth(0x25CF)`（●）・`charCellWidth(0x25CB)`（○）・`charCellWidth(0x25A0)`（■、ブロック先頭）・`charCellWidth(0x25FF)`（ブロック末尾）がいずれも2であることを検証。

## LinuxOSでマルチバイトIME入力がリアルタイム描画されない不具合の調査・対処（scripts/run.sh）

- **報告**: LinuxOS環境でマルチバイト（日本語等）入力の変換中文字列がリアルタイムに描画されず、これまでWindows実機で行ってきたIME関連修正（`switchToHalfWidth()`のsetCompositionEnabled/selectInputMethod併用、`getInputMethodRequests().getTextLocation()`の実カーソル座標返却、`composedText`のリアルタイムオーバーレイ描画等）が反映されていないように見える、という報告があった。
- **調査結果**: `EditorCanvas`側のIME実装（`InputMethodListener`/`InputMethodRequests`/`composedText`オーバーレイ・`switchToHalfWidth()`）はいずれもOS分岐を持たない共通コードであり、Windows実機検証を経て確定した修正はすべてLinux上でも同一コードパスとして既に有効になっている（`git log`で該当コミットが本ブランチのベースに全てマージ済みであることを確認済み）。そのため「コードとして未反映」という状態ではなかった。
- **真因**: Linux（特にIBus）環境では、IBusがキーイベントを非同期モードで処理するとJavaのXIM連携（`sun.awt.X11.XInputMethod`）へ変換中文字列の`InputMethodEvent`が確定（コミット）まで配送されない、というJava/IBus間の既知の相互運用問題がある。この場合、アプリ側コードの実装に関わらず`inputMethodTextChanged()`が未確定部分を伴って呼ばれないため、`composedText`のリアルタイムオーバーレイは原理的に発火しない。
- **対処**: `scripts/run.sh`でJava起動前に`IBUS_ENABLE_SYNC_MODE=1`を（未設定の場合のみ）exportするようにした。IBusを同期モードに切り替えることで、Java側へ変換中文字列の`InputMethodEvent`が逐次配送されるようになる（IBus/Java相互運用における広く知られた対処）。この変更はシェルスクリプトのみで完結し、CLAUDE.mdの「依存ライブラリ・ビルドツール不使用」方針に抵触しない。
- **意図的にスコープ外とした点**: Fcitx等IBus以外の入力メソッドフレームワークの類似問題や、デスクトップ環境側の`GTK_IM_MODULE`/`QT_IM_MODULE`/`XMODIFIERS`設定は、ユーザーのOS設定に依存するためスクリプトから強制していない（`IBUS_ENABLE_SYNC_MODE`のみ、既存の値を尊重する形でデフォルト値としてのみ設定）。実機での改善確認はヘッドレス環境のため未検証（他のIME関連修正と同じ既知のテストギャップ）。

## `switchToHalfWidth()`がLinux(IBus)で全く効かない不具合の追加修正（`ibus engine` CLI呼び出しの併用）

- **報告**: 前節（`IBUS_ENABLE_SYNC_MODE`対応）の後も、Linux実機で「INSERTモードからNORMALモードに戻っても半角入力に切り替わらない」「マルチバイト入力のリアルタイム描画も依然として反映されない」という報告が継続した。
- **原因（半角切替について）**: `InputContext.setCompositionEnabled(false)`/`selectInputMethod(Locale.ENGLISH)`はいずれもJavaの`InputContext`という「1つの入力方式」に対するAPIだが、LinuxのIBusは「IBusという1つのXIM/GTK入力方式」の内部で複数の「エンジン」（日本語エンジン・`xkb:us::eng`等）を切り替える2階層構造になっている。JavaのInputContext APIからはIBus自体は1つの入力方式としてしか見えず、内部エンジンの切替はIBusの管轄外API（IBusのD-Bus/CLIインタフェース）を使わない限り制御できない。そのため、これら2メソッドはいずれも例外を投げず正常終了するにもかかわらず、実際のIBusエンジンは切り替わらないという実機報告に一致する。
- **修正**: `EditorCanvas.switchToHalfWidth()`にLinux限定（`os.name`に`nux`を含む）のフォールバックとして、`ProcessBuilder`で`ibus engine xkb:us::eng`を仮想スレッド上で起動するようにした（`SystemStatsMonitor`の`nvidia-smi`呼び出しと同じ「OS標準コマンドをサブプロセスとして呼ぶ」パターンで、外部ライブラリの追加ではない）。IBus未導入環境（Fcitx使用時等）ではコマンド起動自体が`IOException`で失敗するだけで、既存の`try/catch`によりgraceful degradationする。EDTをブロックしないよう結果を待たずfire-and-forgetする（`waitFor`はタイムアウト付きで仮想スレッド内のみに閉じる）。
- **`scripts/run.sh`の追加修正（リアルタイム描画について）**: `IBUS_ENABLE_SYNC_MODE`に加え、`XMODIFIERS=@im=ibus`・`GTK_IM_MODULE=ibus`・`QT_IM_MODULE=ibus`を（未設定の場合のみ）exportするようにした。ターミナルやランチャー経由でJavaプロセスを起動する場合、デスクトップ環境のログインシェルでは通常これらがエクスポートされているが、`ssh`経由・`systemd`ユニット経由・一部のターミナルエミュレータ経由の起動ではこれらの環境変数が継承されず、AWT(XToolkit)がXIMサーバ(IBus)を発見できずローカルの素朴な入力方式にフォールバックし、`InputMethodListener`へ変換中文字列が一切通知されない状態になりうる。
- **既知の限界**: `ibus engine xkb:us::eng`は「日本語入力から抜けて英数キーボードへ切り替える」という一般的なユースケースを想定した固定値であり、ユーザーが`xkb:us::eng`以外のレイアウトを主に使っている環境（US以外のキーボード配列等）では意図と異なるエンジンに切り替わる可能性がある。IBus自体が未導入（Fcitx等）の場合はこのフォールバックは効果を持たず、Fcitx固有のCLI（`fcitx5-remote`等）を使った同様のフォールバックは今回は未実装（要望があれば追加検討）。リアルタイム描画の環境変数フォールバックも、IBusデーモン自体が起動していない・XIMブリッジが提供されていない等より根本的な環境不備がある場合は効果がない。いずれもヘッドレス環境のため実機（実際のLinuxデスクトップ+IBus）での動作確認はできておらず、既知のテストギャップとして残る。

## `ibus engine`によるエンジン切替が原因でMozcの入力補完が壊れていた不具合の修正（Robotキー合成方式へ変更）

- **報告**: 前節（`ibus engine xkb:us::eng`のCLI呼び出し追加）の後も、①INSERT→NORMALでの半角切替・②マルチバイト入力のリアルタイム描画のいずれも改善しなかった上、新たに「入力補完のロジックが変わった」という報告があった。ユーザーの使用IMEはMozc（Linux上ではibus-mozc経由が一般的）であることが判明した。
- **原因**: `ibus engine xkb:us::eng`は「IME内の半角/全角モードを切り替える」ものではなく、**IBusのアクティブなエンジンそのものをMozc（日本語エンジン）から`xkb:us`（IME非搭載の素のキーボードレイアウト）へ完全に切り替えてしまう**操作だった。この呼び出しによりMozcエンジン自体が非活性化され、以後INSERTモードへ戻ってもMozcの変換・予測候補機能（Mozc自身が持つ「入力補完」機能）が一切働かなくなる重大な副作用があった。これが「入力補完のロジックが変わった」という報告の直接の原因であり、当然ながら本来の目的だった「半角切替」も実現できていなかった（エンジンごと消えるため、次にIME入力したいときに手動でMozcへ戻す必要が生じるだけで、「IME有効のまま半角/全角を切り替える」という要求とは異なる操作だった）。
- **修正**: `ibus engine`によるエンジン切替を撤去し、代わりに`java.awt.Robot`で「英数」キー（`KeyEvent.VK_ALPHANUMERIC`、JIS配列の物理英数キーに対応するAWT仮想キー）のキー押下イベントを合成する方式に変更した（Linux限定・`GraphicsEnvironment.isHeadless()`でガード）。これはIMEから見て物理的な英数キー押下と区別が付かないシステムレベルのキーイベントであり、Mozcの既定キーマップでは前候補選択状態(precomposition)から"IMEOff"（半角直接入力への切替）にバインドされている。**エンジン自体は切り替えないため、Mozc（変換・予測候補機能含む）は活性化されたまま維持される**。JDKのX11 Robot実装は、対象キーシムが現在のキーボードレイアウトにキーコード未割当でも一時的なキーコード割当（`XChangeKeyboardMapping`）で送出する内部処理を持つため、日本語物理キーボードでない環境（US配列+IME等）でも動作が期待できる。
- **`scripts/run.sh`の整理**: `GTK_IM_MODULE`/`QT_IM_MODULE`を撤去した。これらはGTK/Qtツールキット自身のIME連携方式を指定する環境変数であり、GTK/Qtを使わない本アプリ（Swing/AWTのみ）には効果がない（設定しても無害だが無意味なため、前節での追加は過剰だった）。`IBUS_ENABLE_SYNC_MODE`/`XMODIFIERS`はAWT(XToolkit)自体がXIMサーバとの通信で参照しうる変数のため維持している。
- **リアルタイム描画（②）が依然改善しない場合の既知の限界**: AWTのXIM style negotiation（`sun.awt.X11.XInputMethod`）は、IBus側のXIMブリッジがどの入力スタイル（on-the-spot/over-the-spot/root-window）をサポートしているかに応じて自動的にネゴシエーションする。IBusのバージョン・ディストリビューションの設定（`ibus-daemon`がXIMサーバとして正しく起動しているか等）によっては、preedit（変換中文字列）のコールバック配信自体をサポートしないケースがあり、この場合`inputMethodTextChanged()`に未確定部分が一切渡されないため、アプリ側のコード（`composedText`のオーバーレイ描画）に問題がなくても改善しない。これは標準API（JDK標準機能のみ、ネイティブ実装不可というCLAUDE.mdの制約）の範囲内では確実な回避策が無い既知の環境依存の限界であり、IntelliJ IDEA・Eclipse等の他のJavaデスクトップアプリで同一環境において日本語変換中の文字列がインライン表示されるかどうかが、この限界に当たっているかどうかの切り分けの目安になる。
- **意図的にスコープ外とした点**: Fcitx等ibus以外のIME基盤向けの類似のキー合成対応は今回追加していない（`VK_ALPHANUMERIC`のキーイベント自体はIME基盤に依存しない一般的なOS/デスクトップ環境レベルの仕組みのため、Fcitxでも同じキーマップ設定であれば機能する可能性はあるが、Mozc実機での確認に基づく対応のため明言はしない）。

## リアルタイムIME描画未対応の原因を確定（素のJTextAreaでも再現・環境側の限界と結論）

- **切り分け**: ユーザー実機（Linux + Mozc）で、JavaTextEditorのコードを一切使わない最小限の診断プログラム（`JTextArea`のみのSwingアプリ）を作成・実行してもらったところ、**素のJTextAreaでも変換中の文字列がリアルタイムに表示されない**ことを確認した。
- **結論**: この症状は`EditorCanvas`固有のバグではなく、**このユーザー環境のJDK（AWT/Swingの標準IME実装）とIBus/Mozcの組み合わせが「on-the-spot」方式（クライアント側に変換中文字列をコールバックで逐次通知する方式）に対応していない**ことに起因する、環境側の既知の制約であると確定した。標準の`JTextComponent`ですら再現するため、本プロジェクトが自前描画コンポーネント（`JPanel`）である点は原因ではない。
- **今後同じ調査をしないための指針**: このプロジェクトはCLAUDE.mdの制約上ネイティブコード（JNI等）を使えないため、この制約下では回避策が無い。次にこの症状の報告を受けた場合、まず本節の診断プログラム（`ImeDiagnostic.java`、素の`JTextArea`のみで構成）に相当するものを実機で試してもらい、素のSwingコンポーネントでも再現するかどうかを最初に切り分けること。再現する場合はアプリ側のコード変更では直せないため、`switchToHalfWidth()`や`inputMethodTextChanged()`等の実装を追加でいじる前にこの事実を踏まえて判断すること。
- **実用上の代替**: IBus/Mozc自体の候補ウィンドウ（ネイティブの浮動ウィンドウ）には変換中の文字列が表示されており、確定後はエディタのバッファに正しく反映される。「エディタ本体の画面内に変換中文字列を描画する」ことだけができない状態であり、日本語入力自体が機能しなくなっているわけではない。
- **意図的に見送った対応**: JDKのバージョンアップやFcitx5への乗り換えでこの制約が改善する可能性はあるが、いずれもこのプロジェクトのビルド・実行環境やユーザーのデスクトップ環境設定に関わる領域であり、アプリケーションコードの変更では対応できないためスコープ外とした。
