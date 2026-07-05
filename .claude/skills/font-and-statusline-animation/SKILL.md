---
name: font-and-statusline-animation
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、ビットマップフォントの生成・埋め込み方法と、Vimのステータスラインに人間のキャラクターを歩かせるアニメーションの実装方法を学ぶ際に使用する。「ビットマップフォントをJavaにどう埋め込むか」「PSF/BDFフォントからバイト配列を生成するには」「ステータスラインにスプライトアニメーションを表示したい」「javax.swing.Timerで周期的に再描画するには」といった相談に着手する前に必ず参照すること。bisqwit/that_terminalの実装から学んだ設計知識を集約している。"
---

# フォント生成とステータスラインアニメーション

## このスキルが解決すること

`bisqwit/that_terminal`（C++製カスタムターミナルエミュレータ）の実装から学んだ以下の2つの技術を、このJava SEテキストエディタに適用する方法を記述する。

1. **ビットマップフォントの生成・埋め込み**: PSF/BDF形式の既存フォントデータをJavaのバイト配列としてソースコードに埋め込み、`Graphics2D`で1ピクセル単位で描画する方法。
2. **ステータスラインの歩行キャラクターアニメーション**: Vimスタイルのステータスラインに、2フレームのスプライトアニメーションで人間のキャラクターを左右に歩かせる方法。

---

## 参考: `that_terminal` の実装概要

### フォント管理（`rendering/font.cc` / `font.hh`）

`that_terminal`のフォントシステムの特徴:

| 要素 | 内容 |
|------|------|
| フォントソース | X11 misc-fixed、Linux consolefont（.psf.gz）、BDF形式、IBM BIOSフォント |
| ストレージ形式 | バイト配列。1グリフ = `(width+7)/8 * height` バイト |
| グリフ検索 | Unicodeコードポイント → フォント固有インデックスの変換マップ |
| フォールバック | プライマリフォントにないグリフはセカンダリフォントで代替 |
| スケーリング | `fr_actual = scanline * use_fy / fy` で行位置を計算してスケール |

```
FontHandler クラス（概念）:
  +-----------------+
  | primary font    |  ← 要求サイズに最も近いフォント
  | fallback font   |  ← プライマリにないグリフ用
  +-----------------+
  | LoadGlyph(codepoint, scanline, width)
  |   → Glyph { unsigned long bitmap; bool bold; }
  +-----------------+
```

フォント選択アルゴリズム（距離計算）:
```
distance = wdiff*wdiff + hdiff*hdiff + bdiff*bdiff
```
ここで `wdiff`/`hdiff`/`bdiff` は要求サイズとフォントサイズの差。

### 歩行キャラクターアニメーション（`rendering/person.cc` / `person.hh`）

`PersonTransform(unsigned& bgcolor, unsigned& fgcolor, unsigned width, unsigned x, unsigned y, unsigned action_type)` が核心。

**スプライトデータ（`persondata`配列）**:
- 16行 × 32文字幅のASCIIアート
- 左16文字 = フレーム0（右足前）、右16文字 = フレーム1（左足前）
- 使用文字: `#`（不透明）、`'`（半透明シェーディング）、`.`（細部ディテール）、スペース（透明）

**アニメーション制御パラメータ**:
```cpp
constexpr double frame_rate  = 6.0;   // フレーム切り替え: 約333ms
constexpr double walk_speed  = 64.0;  // 横移動速度: 64ピクセル/秒
constexpr int    person_width = 16;   // スプライト幅（ピクセル）
```

**フレーム計算**:
```cpp
int frame = unsigned(time_elapsed * frame_rate) % 2;
```

**横座標計算**（ループ）:
```cpp
int px = unsigned(time_elapsed * walk_speed) % (width + person_width) - person_width;
// → -person_width 〜 width の範囲でラップアラウンド
```

**呼び出し規約**:
- `action_type = 1`: 画面最上部ターゲット
- `action_type = 2`: 上部セクションの最下行
- `action_type = 0`: その他の領域（アニメーション対象外）

---

## Javaへの適用: ビットマップフォント埋め込み

### 設計方針

このエディタはJava SEのみを使うため、外部フォントファイルを実行時に読み込む代わりに、以下のいずれかを採用する:

**方針A（推奨）: Javaの標準フォントをそのまま使う**
- `Font.MONOSPACED` / `"Monospaced"` を `Graphics2D.setFont()` で指定
- Java 21の`FontMetrics`で文字幅を計算（全角対応は`gui-rendering-pipeline` SKILL参照）
- 利点: 追加実装ゼロ、OSのフォント環境に合わせてレンダリング品質が高い
- 欠点: ピクセルアート的なレトロ感は出ない

**方針B: カスタムビットマップフォントをバイト配列で埋め込む**
- PSF/BDFフォントの変換ツールを別途作成し、Javaの`byte[]`として生成する
- `Graphics2D.drawImage()`または1ピクセルずつ`fillRect(1, 1)`で描画
- 利点: that_terminal のような一貫したピクセルアート表現が可能
- 欠点: 実装コスト大・Unicodeフルカバーは現実的でない

**本プロジェクトの判断**: 方針Aを基本とし、方針Bはスプライト（キャラクターアニメーション）にのみ適用する。

### ビットマップフォントのバイト配列形式（方針B参考実装）

```java
// 8x16ビットマップフォント（ASCII 0x20-0x7Eのみ）の例
// 1グリフ = 16バイト（各バイトが1行・MSBが左端）
// (width+7)/8 * height = (8+7)/8 * 16 = 1 * 16 = 16 bytes/glyph
static final byte[] FONT_8X16 = {
    // 'A' (0x41)
    (byte)0x18, (byte)0x18,  // ..##....  ..##....
    (byte)0x3C, (byte)0x3C,  // ..####..  ..####..
    (byte)0x66, (byte)0x66,  // .##..##.  .##..##.
    (byte)0x66, (byte)0x66,  // .##..##.  .##..##.
    (byte)0x7E, (byte)0x7E,  // .######.  .######.
    (byte)0x66, (byte)0x66,  // .##..##.  .##..##.
    (byte)0x66, (byte)0x66,  // .##..##.  .##..##.
    (byte)0x00, (byte)0x00,  // ........  ........
    // ... 残りのグリフ
};

// グリフ描画メソッド
static void drawGlyph(Graphics2D g, int codepoint, int x, int y,
                      int charW, int charH, Color fg, Color bg) {
    int idx = codepoint - 0x20;
    int bytesPerRow = (charW + 7) / 8;
    int glyphOffset = idx * bytesPerRow * charH;
    for (int row = 0; row < charH; row++) {
        for (int col = 0; col < charW; col++) {
            int byteIdx = glyphOffset + row * bytesPerRow + col / 8;
            int bitMask = 0x80 >> (col % 8);
            boolean lit = (FONT_8X16[byteIdx] & bitMask) != 0;
            g.setColor(lit ? fg : bg);
            g.fillRect(x + col, y + row, 1, 1);
        }
    }
}
```

---

## Javaへの適用: ステータスラインのキャラクターアニメーション

### 全体アーキテクチャ

```
EditorCanvas (JPanel)
  ├── paintComponent(Graphics g)
  │     └── drawStatusLine(g)
  │           └── drawWalkingCharacter(g, statusLineY, statusLineWidth)
  └── javax.swing.Timer (アニメーションタイマー)
        └── 毎フレーム repaint() を呼び出す
```

### スプライトデータ定義

```java
// that_terminal の persondata を参考にしたJava版スプライト（8x16 ピクセル、2フレーム）
// 各行: 0=透明、1=前景色（不透明）、2=シェーディング（半透明）
// 左8列 = フレーム0、右8列 = フレーム1
private static final int[][] SPRITE = {
    //  frame0 (8px)        frame1 (8px)
    {0,0,1,1,1,0,0,0,  0,0,1,1,1,0,0,0},  // 頭
    {0,0,1,1,1,0,0,0,  0,0,1,1,1,0,0,0},  // 頭
    {0,0,0,1,0,0,0,0,  0,0,0,1,0,0,0,0},  // 首
    {0,1,1,1,1,1,0,0,  0,1,1,1,1,1,0,0},  // 胴体
    {0,1,1,1,1,1,0,0,  0,1,1,1,1,1,0,0},  // 胴体
    {0,1,1,1,1,1,0,0,  0,1,1,1,1,1,0,0},  // 胴体
    {0,0,1,0,0,1,0,0,  0,0,1,0,0,1,0,0},  // 腰
    {0,1,0,0,0,0,1,0,  0,0,1,0,1,0,0,0},  // 太もも（フレームで異なる）
    {1,0,0,0,0,0,0,1,  0,1,0,0,0,1,0,0},  // 膝
    {1,0,0,0,0,0,0,1,  0,1,0,0,0,1,0,0},  // 脛
    {1,0,0,0,0,0,0,0,  0,1,0,0,0,0,0,0},  // 足首
    {1,1,0,0,0,0,0,0,  0,1,1,0,0,0,0,0},  // 足
};

private static final int SPRITE_W = 8;
private static final int SPRITE_H = SPRITE[0].length / 2;  // = 12 ではなく行数
```

### `javax.swing.Timer` によるアニメーションループ

```java
import javax.swing.Timer;

// EditorCanvas のフィールド
private final Timer animTimer;
private long animStartMs = System.currentTimeMillis();

// コンストラクタ内
animTimer = new Timer(50, e -> repaint());  // 20fps
animTimer.start();
```

**タイマー停止（リソース解放）**:
```java
@Override
public void removeNotify() {
    super.removeNotify();
    animTimer.stop();
}
```

### `drawWalkingCharacter()` の実装

```java
private void drawWalkingCharacter(Graphics2D g, int statusY, int statusW) {
    // 1. 経過時間（秒）
    double elapsed = (System.currentTimeMillis() - animStartMs) / 1000.0;

    // 2. フレーム番号（0 or 1）
    int frame = (int)(elapsed * 6.0) % 2;

    // 3. 横位置（ピクセル）- 画面端でラップアラウンド
    int spriteW = SPRITE_W * SCALE;      // SCALE = charWidth 相当
    int walkway = statusW + spriteW;
    int px = (int)(elapsed * 64.0) % walkway - spriteW;

    // 4. スプライト描画
    int spriteH = SPRITE.length;
    int drawY = statusY + (statusLineHeight - spriteH * SCALE) / 2;
    for (int row = 0; row < spriteH; row++) {
        for (int col = 0; col < SPRITE_W; col++) {
            int cell = SPRITE[row][frame * SPRITE_W + col];
            if (cell == 0) continue;           // 透明: スキップ
            Color c = (cell == 2)
                ? new Color(theme.foreground.getRed(),
                            theme.foreground.getGreen(),
                            theme.foreground.getBlue(), 128)
                : theme.foreground;
            g.setColor(c);
            g.fillRect(px + col * SCALE, drawY + row * SCALE, SCALE, SCALE);
        }
    }
}
```

### `drawStatusLine()` への組み込み

```java
private void drawStatusLine(Graphics2D g, ModalEditor editor) {
    int y = getHeight() - lineHeight;
    // 背景塗りつぶし
    g.setColor(theme.accent);
    g.fillRect(0, y, getWidth(), lineHeight);

    // モード名・ファイル名等のテキスト描画
    g.setColor(theme.background);
    g.drawString(modeLabel, PADDING, y + ascent);

    // キャラクターアニメーション（ステータス行上を歩く）
    drawWalkingCharacter((Graphics2D)g.create(), y, getWidth());
}
```

---

## テスト戦略

ビジュアルアニメーションは自動テストが難しいため、以下の方針を採用する:

| テスト種別 | 方法 |
|------------|------|
| スプライト座標計算 | `elapsed=0.0/0.5/1.0/5.0` の各時点での `px`/`frame` を `main`メソッドで検証 |
| フレーム切り替え周期 | `frame_rate=6.0` で `elapsed=0.0→0.166→0.333` の変化を検証 |
| ラップアラウンド | `elapsed` が大きくなっても `px` が範囲外にならないことを検証 |
| タイマー停止 | `removeNotify()` を呼んだ後 `animTimer.isRunning() == false` を確認 |
| 目視確認 | `VisualPreview.java`（既存）を拡張してスプライト1フレームをPNG出力 |

```java
// テスト例（main メソッド形式）
public static void main(String[] args) {
    int totalFailed = 0;
    totalFailed += testFrameCalculation();
    totalFailed += testXPosition();
    totalFailed += testWrapAround();
    System.out.printf("PASS: %d / %d  (FAIL: %d)%n", ...);
}

static int testFrameCalculation() {
    int failed = 0;
    // elapsed=0.0 → frame=0
    failed += check("frame at t=0",   calcFrame(0.0, 6.0),  0);
    // elapsed=0.2 → frame=1 (0.2*6=1.2 → int 1 → %2 = 1)
    failed += check("frame at t=0.2", calcFrame(0.2, 6.0),  1);
    // elapsed=0.4 → frame=0 (0.4*6=2.4 → int 2 → %2 = 0)
    failed += check("frame at t=0.4", calcFrame(0.4, 6.0),  0);
    return failed;
}
```

---

## 既知の制限と注意点

| 制限 | 詳細 | 対応方針 |
|------|------|----------|
| `javax.swing.Timer` はEDTで動く | `repaint()` の呼び出しはスレッドセーフだが、重い処理をコールバック内でやると描画が詰まる | コールバックは `repaint()` のみ |
| 全角文字の座標計算 | 歩行位置の `px` 計算はステータス行のピクセル幅を使うため全角/半角の影響を受けない | 問題なし |
| アニメーション停止条件 | `removeNotify()`はパネルがウィンドウから外れた時に呼ばれるが、`dispose()`時には呼ばれない場合がある | `WindowListener.windowClosing()`でも`animTimer.stop()`を呼ぶ |
| 高DPIディスプレイ | Swingの`Graphics2D`はHiDPI対応が`JFrame`の設定次第 | `System.setProperty("sun.java2d.uiScale", "2")` 等はJava 21で有効 |
| ビットマップフォントのUnicode対応 | 方針Bでは全漢字をカバーするデータ量が膨大（数MB〜数十MB） | ASCII/Latin + 頻出漢字に絞るか、方針Aとのハイブリッドにする |

## 実装済みの修正: キー入力時だけ滑らかになる不具合（Windows タイマー分解能）

- **症状**: `EditorCanvas` のウォーキングパーソンアニメーションが、キー入力（IME処理含む）をしていない間はカクついて見え、キー入力中だけ滑らかになる。
- **原因**: Windows では、いずれかのスレッドが短い `Thread.sleep()` を実行している間だけ、JVM（HotSpotの`os::sleep`実装）がシステムタイマー分解能を約1msに引き上げる。`javax.swing.Timer`は内部で`Object.wait()`を使っており、キー入力やIME処理で短いスリープが断続的に発生している間だけタイマー精度が上がって滑らかになり、アイドル時は既定のタイマー分解能（環境によっては数十ms単位、かつ電源プランによってはタイマー・コアレッシング）にジッターしていた。Linuxではこの問題は再現しない（`java.util.Timer`系のOS依存の既知のJVM挙動）。実機で `javax.swing.Timer` 単体の発火間隔を計測しても、Linux環境ではキー入力の有無に関わらず一定間隔（33ms付近）で安定しており、この診断を裏付けている。
- **修正**: `EditorCanvas` に「タイマー分解能ピン留めスレッド」を追加した（`acquireTimerResolutionPin()`/`releaseTimerResolutionPin()`）。エディタ画面が最低1つ表示されている間（`addNotify()`〜`removeNotify()`のライフサイクルに連動、複数ペイン分割時も参照カウントで1本だけ起動）、`Thread.sleep(1)`を繰り返す最低優先度のデーモンスレッドを立て、システムタイマー分解能を引き上げたままにする。これはJava製デスクトップ/ゲームアプリで広く使われる既知の回避策で、ネイティブライブラリや外部依存を追加せずJava SE標準APIのみで完結する。
- **fps修正**: 併せてタイマー間隔を `40ms`（25fps）から `1000/30 = 33ms`（30fps）に変更した（`ANIM_FRAME_INTERVAL_MS`定数）。
- **意図的に採用しなかった案**: JNIで`timeBeginPeriod`を直接呼ぶ案は「依存ライブラリ・ビルドツールを使わない」というCLAUDE.mdの制約に反するため見送った。ネイティブコード無しでも同じ効果が得られるスリープスレッド方式を採用した。

---

## このスキルを使うタイミング

- ステータスラインにアニメーションを追加したい場合 → `drawWalkingCharacter()` の実装を参照
- カスタムビットマップフォントを埋め込みたい場合 → 「Javaへの適用: ビットマップフォント埋め込み」を参照
- `javax.swing.Timer` の使い方を確認したい場合 → タイマー停止のライフサイクルに注意
- that_terminal の実装を参照したい場合 → `rendering/person.cc` と `rendering/font.cc` が主要ソース

---

## 参考文献

- `bisqwit/that_terminal`: https://github.com/bisqwit/that_terminal
  - `rendering/person.cc`: 歩行キャラクターアニメーションの実装
  - `rendering/font.cc` / `font.hh`: `FontHandler` クラスによるビットマップフォント管理
  - `doc/fonts.md`: サポートするフォント一覧とビットマップ形式の説明
- このプロジェクトの関連スキル:
  - `.claude/skills/gui-rendering-pipeline/SKILL.md`: Swing/AWT描画の基礎（カーソル・全角対応）
  - `.claude/skills/gui-rendering-pipeline/references/future-phases.md`: v4以降の描画拡張計画
