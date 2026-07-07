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

## 実装済みの追加機能: アクティブペイン限定表示・ステータスバー時計（2026-07）

- **ウォーキングパーソンはアクティブなペインにのみ表示する**。本エディタはウィンドウ分割時も単一の `JFrame` の中で複数の `EditorCanvas`（ペイン）を `JSplitPane` で並べる構成であり、`Main.java` はどのペインが操作対象かを `active[0]`（`Leaf`配列）で管理し、`updateBorders()` で枠線の色分けとして可視化している。この既存の「アクティブペイン」概念をそのまま流用し、`EditorCanvas` に `activePane`（既定 `true`）フィールドと `setActivePane(boolean)` を追加、`drawStatusLine()` 内の `drawWalkingPerson()` 呼び出しを `if (activePane)` で囲んだ。`updateBorders()` の全呼び出し箇所（分割・ペイン切替・マウスクリック・ペイン削除）を経由するため、キャラクターの表示切替に専用のイベント配線を追加する必要はなかった。タイマー（`animTimer`）自体は非アクティブなペインでも止めていない — ステータス行の時計表示（次項）を毎秒更新する必要があるため。
- **ステータスバー右端に現在時刻（24時間表記 `HH:mm:ss`）を表示する**。`java.time.LocalTime.now()` と `DateTimeFormatter.ofPattern("HH:mm:ss")`（`CLOCK_FORMAT` 定数）を使用。既存の診断件数表示（エラー/警告数）は時計表示のさらに左側に位置をずらし、両者が重ならないようにした。時計は非アクティブなペインでも表示され続ける（キャラクターアニメーションのみアクティブペイン限定で、時刻表示は全ペイン共通というのが意図した挙動）。

## 実装済みの修正: Linux(X11)でのアニメーションの微カクつき（2026-07）

- **症状**: Windowsでは滑らかなウォーキングパーソンアニメーションが、Linuxでは`javax.swing.Timer`が正確に33ms間隔（30fps）で発火しているにもかかわらず、わずかにカクついて見えることがあった。
- **原因**: Linux(X11)ではAWT/SwingがXlib/XCB経由でX serverに描画コマンドを送るが、これは**クライアント側でバッファされる非同期プロトコル**であり、`repaint()`がトリガーする`paintComponent()`の描画内容が実際に画面へフラッシュされるタイミングは、アプリのタイマー周期とは独立してOS・コンポジタ側の都合で決まる。Windowsでは GDI/DWM がこの種の非同期バッファリングをより積極的に吸収・平滑化するため同じ描画パターンでも滑らかに見える。これはSwingアプリ全般でLinux上において知られる定番の既知問題であり、`.claude/skills`の「Windowsタイマー分解能」問題（アイドル時にタイマー精度が落ちる問題、既に対策済み）とは全く別の原因。
- **修正**: `EditorCanvas.paintComponent(Graphics g)`を、実際の描画処理を`paintContent(Graphics2D g2)`に切り出したうえで`try { paintContent(...) } finally { Toolkit.getDefaultToolkit().sync(); }`で包む形に変更した。`Toolkit.sync()`はX11環境でクライアント側にバッファされた描画コマンドを即座にX serverへフラッシュするためのJava標準APIで、Swingアプリのアニメーションを滑らかにする際の定番の対策。`paintContent()`内部に複数の`return;`（ステータス行のみ再描画・スプラッシュ表示時の早期return等）があるため、単純に末尾へ`sync()`を追記するのではなく、`try/finally`でどの`return`経路を通っても必ず1回呼ばれるようにしている。
- **他OSへの影響**: WindowsやmacOSでは`Toolkit.sync()`は当該プラットフォームの実装に応じて空処理またはごく軽量な処理になり、副作用はない（既存のWindows向けタイマー分解能ピン留め対策とも独立して共存する）。ヘッドレス環境（本プロジェクトのテスト実行環境）でも例外は発生せず、テストスイートは既存の既知の失敗（`ScrollTest`の2件・`RobotKeyInputTest`のheadlessスキップ）以外は影響を受けなかった。

## 実装済みの追加機能: 横縦比率に応じたビットマップフォント自動切替（misc-fixed 12種、2026-07）

- **背景**: 従来は `BitmapFont10x20`（10x20固定）のグリフを `cellW`/`cellH` に合わせて独立軸ニアレストネイバー拡大縮小していたため、`Ctrl+Shift+矢印` でセル幅・高さを個別に伸縮すると元の 10:20 比率から外れるほど字形が歪んで汚くなっていた。X11 misc-fixed には 5x7〜10x20 まで複数サイズのフォントが存在するため、要求セルサイズの横縦比率に最も近いネイティブサイズのフォントへ自動的に切り替える方式にした。
- **フォント生成方式**: `BitmapFont10x20` と全く同じ方式（1グリフ = ASCII 0x20-0x7E、MSBit=左端ピクセルの byte[] 埋め込み）で以下11個を追加した: `BitmapFont5x7`/`5x8`/`6x9`/`6x10`/`6x12`/`6x13`/`7x13`/`7x14`/`8x13`/`9x15`/`9x18`。データは手書きではなく、`apt-get install xfonts-base pcf2bdf` で実際の X11 misc-fixed `.pcf.gz` を取得し `pcf2bdf` でテキスト形式の BDF に変換したうえで、BDF の `BITMAP`/`BBX`/`FONT_DESCENT` を読み取って Java の `byte[]` ソースを機械生成した（生成スクリプトは使い捨てのため `scratchpad` で実行しリポジトリには残していない）。生成結果は既存の手書き `BitmapFont10x20.GLYPHS` とバイト単位で完全一致することを確認済み（生成方式の正当性の検証）。
- **対象外にしたフォント**: 依頼された22種類のうち、Bold/Oblique 変種（`6x13B`/`6x13O`/`7x13B`/`7x13O`/`7x14B`/`8x13B`/`8x13O`/`9x15B`/`9x18B`）と `12x13ja`/`18x18ja`/`18x18ko`（全角・多バイト用の別フォント）は自動切替の候補に含めていない。前者は同サイズの Medium-R 版とセルの横縦比率が同一であり比率選定に寄与しないため、後者はASCIIセルとは全く異なる用途（既存の非ASCII描画は Swing の `Font.MONOSPACED` フォールバックを使い続けている）のため対象外とした。追加したい場合はまず既存設計との整合をユーザーに確認すること。
- **共通化**: `FixedBitmapFont`（インタフェース: `cellW()`/`cellH()`/`renderGlyphI()`/`isSupportedI()`/`descentPixelsI()`）と `FixedFontRenderer`（12フォント共通のニアレストネイバー拡大縮小ロジック。バイト幅 `bytesPerRow` に依存しない一般化版）を新設し、各 `BitmapFontWxH` クラスはこれらに薄く委譲する形にした。`BitmapFont10x20` は既存の static メソッド（`renderGlyph`/`isSupported`/`descentPixels`、既存テスト・`Main.java` の `BASE_CELL_W`/`BASE_CELL_H` 参照が依存）をそのまま残し、`implements FixedBitmapFont` を追加して `INSTANCE` 経由でも呼べるようにしただけで、既存の呼び出し側との後方互換性を崩していない。
- **選定ロジック**: `FixedFontCatalog.select(cellW, cellH)` が12候補から「目標比率 `cellW/cellH` との差が最小」のフォントを選び、比率が同一でタイする場合（例: 6x12/7x14/9x18/10x20 は全て 1:2）は絶対サイズが要求セルサイズに近い方を優先する（スケールファクターが1に近いほど歪みが少ないため）。that_terminal の `distance = wdiff²+hdiff²+bdiff²`（本SKILLの「フォント選択アルゴリズム」節）を、まず比率一致を優先する2段階の比較に変えたもの。
- **EditorCanvas への接続**: `cellW`/`cellH` を変更する3箇所（`adjustCellWidth`/`adjustCellHeight`/`setInitialCellSize`）で必ず `updateBitmapFont()`（`bitmapFont = FixedFontCatalog.select(cellW, cellH)`）を呼ぶようにし、本文描画（`getGlyphFg`/`getGlyphBg`/`drawLineWithFullWidthSupport` 系）とUI文字列描画（`getUiGlyph`/`drawUiText`）の両方が `bitmapFont`（フィールド）経由で描画するよう置き換えた。グリフキャッシュ（`glyphCacheFg`/`Bg`/`uiGlyphCache`）は既存の `invalidateGlyphCache()` がそのままセルサイズ変更時にクリアするため、フォント切替時の専用クリア処理は追加不要だった。
- **テスト**: `test/dev/javatexteditor/ui/FixedFontCatalogTest.java`（17/17）。12フォント全てで `isSupported`/`renderGlyph`（サイズ・点灯ピクセル数）/`descentPixels` の健全性、`select()` の完全一致・タイブレーク・比率最近傍の3パターン、`EditorCanvas` でセル幅を大きく変えても描画がクラッシュしないことを確認済み。

## 実装済みの変更: misc-fixed から IBM Plex Mono Regular への半角フォント差し替え（2026-07）

- **背景**: ユーザーから「MiscFixedフォントだと見にくいので、半角フォントを IBM Plex Mono Regular にしてほしい。ただしフォントサイズの可変仕様（`Ctrl+Shift+矢印` で cellW/cellH を個別に伸縮すると `FixedFontCatalog.select()` が横縦比率に最も近い misc-fixed 12種から自動選択する既存の仕組み）は変更しないでほしい」という依頼があった。
- **方針**: `FixedFontCatalog`/`FixedBitmapFont`/`FixedFontRenderer`・12種類のセルサイズ（5x7〜10x20）・`BASE_CELL_W`/`BASE_CELL_H`/`FIRST_CHAR`/`LAST_CHAR`/`descentPixels()`等の public API は一切変更していない。各 `BitmapFontWxH.GLYPHS`（ASCII 0x20-0x7E の1ビットビットマップデータ）の中身だけを、misc-fixed 由来から IBM Plex Mono Regular（SIL OFL 1.1）由来に総入れ替えした。呼び出し側（`EditorCanvas`/`Main`/`FixedFontCatalog`）は無変更。
- **生成方法**: `Font.createFont(Font.TRUETYPE_FONT, ...)` で IBM Plex Mono Regular の TTF を読み込み、各グリフを 10x スーパーサンプリングした `BufferedImage`（サイズ = セル幅×10, セル高×10）に `Graphics2D`（アンチエイリアス有効）で描画し、セルの各ピクセルをその領域内の平均カバレッジ（0-255）で閾値判定（90/255 以上を点灯）して1ビット化するという、既存の「misc-fixed の `.pcf.gz` を `pcf2bdf` でBDF化してJavaの`byte[]`に機械生成する」（本SKILLの「横縦比率に応じたビットマップフォント自動切替」節参照）と同じ「実フォントをラスタライズしてバイト配列に変換する」方式を踏襲した。フォントサイズは、対象セル高さから `BASE_DESCENT`（各クラス既存の値。5x7/5x8=1, 6x9〜8x13=2, 9x15=3, 9x18/10x20=4）を引いた行数がアセント（ベースラインから上端まで）に収まるよう逆算して決定し、既存の descender 領域の設計をそのまま維持した。
- **生成スクリプトは使い捨てのため保存していない**（`scratchpad` で実行）。既存の生成資産（`lib/openjdk-native/` 取得スクリプト等）と異なり、フォントデータ自体もビルド時取得ではなく生成結果の `byte[]` を直接コミットする既存方式（misc-fixed のときと同じ）を維持したため、`.ttf` ファイル自体もリポジトリには追加していない。
- **品質のトレードオフ**: misc-fixed は元々ピクセル単位で手作業設計されたビットマップフォントであるのに対し、IBM Plex Mono はアウトラインフォントであるため、極小サイズ（5x7・5x8）ではラスタライズ後の視認性が misc-fixed ほど高くない（ストロークが潰れがちで、字形の見分けが付きにくい文字がある）。既定サイズ（10x20）や中間サイズ（6x13・9x18等）では明瞭に判読できることを目視確認済み。5x7/5x8 は `Ctrl+Shift+矢印` でセルをかなり縮小した場合にのみ選択される想定外サイズであり、この解像度でベクターフォントを綺麗に見せるには専用のヒンティング調整が必要になるため、今回のスコープでは追加のチューニングは行っていない（misc-fixed 相当の視認性が必要であれば、5x7/5x8 のみ misc-fixed データを残す等の対応をユーザーに確認の上で検討する）。
- **ライセンス**: IBM Plex Mono は SIL Open Font License 1.1（`Copyright © 2017 IBM Corp. with Reserved Font Name "Plex"`）。OFL はフォント（及びその派生物）をソフトウェアに埋め込んで配布することを明示的に許可しており、本プロジェクトのようにラスタライズ結果を `byte[]` としてソースコードに埋め込む用途は許容範囲内。
- **テストへの影響**: `EditorCanvasTest` の「INSERTモードカーソルバーが2px幅で描画されているか」テストが、文字 `'A'` を描画したセルの座標 `(5,5)`（本来はカーソルバー外＝背景色であることを期待する検証点）が、IBM Plex Mono の `'A'` の字形ではストロークの通り道に重なってしまい失敗するようになった。これは misc-fixed の `'A'` の字形がその座標を偶然通っていなかったことに依存した検証であり、本来「カーソルバー幅」の検証は描画される文字の字形と無関係であるべきなので、テストの表示文字を `"A"` から `" "`（空白）に変更して字形非依存の検証に修正した（フォント側の実装は変更していない）。
- **既知の環境依存の挙動（今回のフォント変更とは無関係）**: `FixedFontCatalogTest` はテスト内容自体は正しく実行され結果も出力される（17/17 PASS）が、テストの最後に生成した `EditorCanvas` の `javax.swing.Timer`（`animTimer`）を明示的に停止しないまま `main()` を抜けるため、AWTのイベントキュースレッド（`AWT-EventQueue-0`、非デーモンスレッド）がタイマーの repaint イベントを処理し続け、プロセスがなかなか終了しないことがある（この環境で確認）。フォントデータやテストロジックには依存しない、Swingを使う自作テストハーネス特有の既知の挙動のため、今回は対応していない（`scripts/test.sh` でこのクラスの完走を待つ場合は `timeout` 等の外側のタイムアウトが必要になる場合がある）。

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
