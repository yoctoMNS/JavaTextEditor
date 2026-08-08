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
- **追加修正（`setIgnoreRepaint(true)`、2026-07）**: `Toolkit.sync()`だけでもLinux上のジッターはある程度改善したが、ユーザーから「描画フレームが一定しない」という追加報告があり、`Component#setIgnoreRepaint(true)`も併用するよう指示があった。`EditorCanvas`は`java.awt.Canvas`ではなく`JPanel`（`javax.swing.JComponent`）だが、`setIgnoreRepaint`は`java.awt.Component`で定義されているためJPanelでもそのまま呼べる。ウィンドウ露出（expose）やリサイズ等でOS/AWTが自動生成する「システム側の再描画要求」を無視させることで、`animTimer`（30fps・`javax.swing.Timer`）が発行する`repaint()`だけが唯一の描画トリガーになるようにし、システム起因の余計な再描画とタイマー駆動の再描画が重なってフレーム間隔が乱れる要因を排除する。呼び出し位置は`addNotify()`内の`super.addNotify()`直後（ピア生成後でないと設定が確定しないため）とし、`removeNotify()`側で明示的に`setIgnoreRepaint(false)`に戻す処理は加えていない（コンポーネント破棄後に再度同じインスタンスが`addNotify()`される場合も同じ`true`を再設定するだけで問題ないため）。

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

## 実装済みの変更: misc-fixed 生成ビットマップフォント方式から実TTF（IBM Plex Mono Regular）レンダリング方式への全面移行（2026-07・第2弾）

- **背景**: 直前の節（「misc-fixed から IBM Plex Mono Regular への半角フォント差し替え」）では、IBM Plex Mono を misc-fixed と同じ「事前生成した固定サイズのビットマップ配列」形式に変換して埋め込んでいた。ユーザーから改めて「MiscFixedのようなフォント形式ではなく、TTFフォントとしてそのまま使ってほしい」という明示的な指示があり、`FixedBitmapFont`/`FixedFontCatalog`/`BitmapFont5x7`〜`BitmapFont10x20`（12種類）・`FixedFontRenderer`を全廃止し、`TtfMonoFont`（新設）による実TTFのベクターレンダリングに一本化した。
- **仕様確認済みの4点**（ユーザーへの質問で確定）:
  1. TTF実体は `lib/fonts/IBMPlexMono-Regular.ttf` に実ファイルとして置き、実行時に相対パス的な探索で読む。
  2. 旧ビットマップ基盤（12種類切替の仕組み）は完全に削除し、TTFレンダリングに一本化する。
  3. `Ctrl+Shift+矢印` でセル幅・高さを個別に伸縮し縦横比がフォント本来の比率からずれた場合、セルに合わせて縦横別々に伸縮する（misc-fixed 版の独立軸ニアレストネイバー拡縮と同じ「セルを歪めてでも埋める」挙動を維持）。
  4. 描画はアンチエイリアス（滑らかな輪郭）を有効にする。
- **`lib/` が `.gitignore` 対象という制約への対応**: `lib/openjdk-native`・`lib/src.zip` と同じく `lib/` はまるごと `.gitignore` されており、コミットできない。ユーザーに確認した結果、「`scripts/setup.sh`/`setup.bat` 経由でダウンロードする外部リソースにする」方針を採用し、`lib/` の既存の意味（`setup.sh` が取得する外部リソース置き場）をそのまま維持した。`.gitignore` の変更・新しい追跡対象ディレクトリの追加は行っていない。
  - `scripts/setup.sh`: 冒頭の「JDKソース一式が揃っていれば即終了」ガードを `JDK_SOURCES_READY` フラグ方式に変更し、JDKソース取得済みでもフォント取得セクション（新設の第4節）が必ず実行されるようにした（早期 `exit 0` のままだと2回目以降の実行でフォントだけが未取得のケースをカバーできないため）。フォント取得は `curl -fsSL` で `https://raw.githubusercontent.com/IBM/plex/master/packages/plex-mono/fonts/complete/ttf/IBMPlexMono-Regular.ttf` から `lib/fonts/IBMPlexMono-Regular.ttf` へ、ライセンス文（`LICENSE.txt`、SIL OFL 1.1）を `lib/fonts/IBMPlexMono-OFL.txt` へダウンロードする。ダウンロード失敗（ネットワーク不可・`curl` 未インストール等）は warning を出すだけで exit 1 にはしない設計とした（フォントが無くてもフォールバックフォントで起動は継続できるため、致命的エラー扱いにしない）。IBM/plex リポジトリは `IBM-Plex-Mono/...` という旧パスでは 404 になり、実際には `packages/plex-mono/...` に再編されている点を実機の `curl` 疎通確認で特定した。
  - `scripts/setup.bat`: `windows-batch-and-subprocess` スキルのルールに従い、追加したブロックは ASCII のみ・`if`/`for` ブロック内に丸括弧を含む `echo` を置かない設計にした。JDKソース既存時の早期 `goto :eof` を `goto :setup_fonts` に変更し、`:cleanup` の後に `:setup_fonts` ラベルでフォント取得処理（`curl` の有無チェック→ダウンロード→`move`）を追加、最終サマリの前に `:setup_done` ラベルを置いて合流させた。
- **`TtfMonoFont`（新設・`src/dev/javatexteditor/ui/TtfMonoFont.java`）の設計**:
  - シングルトン（`INSTANCE`）。固定サイズ切替が不要になったため `FixedBitmapFont` のようなインタフェース抽象化はせず、`isSupported(int)`/`descentPixels(int)`/`renderGlyph(int,int,int,int)` の3メソッドのみを持つ具象クラスとした。
  - フォント読み込みは `⑫openjdk-source-tracing` で確立済みの `CodeSourceLocator.findUpward(anchorClass, "lib/xxx", maxLevels, predicate)` パターンをそのまま再利用し、実行形態（クラスパス直接実行・jar実行）やカレントディレクトリに依存せず `lib/fonts/IBMPlexMono-Regular.ttf` を発見できるようにした（`OpenjdkSourceTracer.findNativeSrcDir()`/`findBundledSrcZip()` と同型）。見つからない場合は `Font.MONOSPACED` にフォールバックする（フォントファイル欠如时でも起動自体はできる graceful degradation）。
  - **非等方向スケールによるセルサイズ追従**: 参照フォントサイズ（100pt、絶対値に意味は無い）でのメトリクス（`ascent`・`descent`・`charWidth('M')`）を起動時に一度だけ計測し、`sx = cellW / 参照アドバンス幅`・`sy = cellH / (参照ascent+参照descent)` を毎回のセルサイズから算出。`Graphics2D.translate(0, 参照ascent*sy)` してから `scale(sx, sy)` を適用し、参照サイズで `drawString` した結果を非等方向にアフィン変換することで、セル幅・高さの比率が崩れてもラスタ再サンプリングではなくベクターアウトライン自体の変形で正確にセル全体を埋める（misc-fixed版の「独立軸ニアレストネイバーでビットマップを引き伸ばす」挙動を、より高品質なベクター変形で再現したもの）。
  - `descentPixels(cellH)` は `Math.round(cellH * 参照descent / (参照ascent+参照descent))` で、旧 `BASE_DESCENT` ベースの比例計算と同じ考え方をフォント自体のメトリクスから動的に算出する形に置き換えた。
  - 空白文字（`' '`）は `drawString` を呼ばず即座に透明画像を返す（旧実装と同じ最適化）。
- **`EditorCanvas` 側の変更**: `cellW`/`cellH` の初期値・クランプ範囲（5〜40 / 8〜80）や `adjustCellWidth`/`adjustCellHeight`/`setInitialCellSize`・グリフキャッシュ（`glyphCacheFg`/`Bg`/`uiGlyphCache`）の仕組みは一切変更していない。`FixedFontCatalog.select()` によるフォント再選択（`updateBitmapFont()`）が不要になったため削除し、フィールド名を `bitmapFont`（型 `FixedBitmapFont`）から `ttfFont`（型 `TtfMonoFont`、`final`）に変更、呼び出し箇所は `isSupportedI`/`renderGlyphI`/`descentPixelsI` → `isSupported`/`renderGlyph`/`descentPixels`（インタフェース経由の `I` サフィックスが不要になったため）に統一した。`Main.java` の `BitmapFont10x20.BASE_CELL_W`/`BASE_CELL_H` 参照も `TtfMonoFont.BASE_CELL_W`/`BASE_CELL_H`（10, 20 の既定値を維持）に置き換えた。
- **削除したファイル**: `BitmapFont5x7`〜`BitmapFont10x20`（12ファイル）・`FixedBitmapFont`・`FixedFontCatalog`・`FixedFontRenderer`・`FixedFontCatalogTest`。`EditorCanvasTest` の `BitmapFont10x20.isSupported`/`renderGlyph` 呼び出しは `TtfMonoFont.INSTANCE.isSupported`/`renderGlyph`（インスタンスメソッド化に伴う書き換えのみ、テストの意図・アサーション内容は変更していない）。
- **ライセンス**: IBM Plex Mono は SIL Open Font License 1.1（`Copyright © 2017 IBM Corp. with Reserved Font Name "Plex"`）。OFL はソフトウェアへのフォント埋め込み・再配布を明示的に許可している。今回はビットマップへの変換ではなく TTF 実体そのものを配布物に含める（ただしリポジトリには直接コミットせず `scripts/setup.sh` 経由でダウンロードする）形になったため、ライセンス文（`LICENSE.txt`）もフォントと同じ場所（`lib/fonts/IBMPlexMono-OFL.txt`）に一緒にダウンロードするようにした。
- **品質**: misc-fixedバージョンで課題だった極小サイズ（5x7相当）でのつぶれも、ベクターアウトライン＋アンチエイリアスのおかげで大幅に改善した（ASCIIダンプで目視確認済み）。非等方向に大きく歪めた場合（例: 20x10）も文字の判読性を保ったまま正しくセル全体を埋めることを確認済み。

## 実装済みの追加機能: ステータスバーへのCPU/GPU温度・メモリ使用率表示（2026-07）

- **要望**: 右下の現在時刻表示のさらに左隣に、境界線として `|` を使い「CPU温度 | GPU温度 | メモリ使用率」の順で表示してほしいという依頼。
- **新設**: `dev.javatexteditor.system.SystemStatsMonitor`（シングルトン `INSTANCE`）。`ProjectSearcher`/`WordIndex` 等で確立済みの「バックグラウンドスレッドで定期的に値を再計算し、`volatile` フィールドへの参照差し替えだけでEDTに非ブロッキングで公開する」パターンをそのまま踏襲した。`ScheduledExecutorService`（デーモンスレッド、2秒間隔）で `refresh()` を呼び、`getStatusLabel()` はキャッシュ済み文字列を即座に返すだけなので `drawStatusLine()`（EDT）から呼んでも描画がブロックされない。
- **各項目の取得方法とgraceful degradation**:
  - **メモリ使用率**: `com.sun.management.OperatingSystemMXBean`（JDK標準の管理API、追加の依存ライブラリではない）の `getTotalMemorySize()`/`getFreeMemorySize()` から算出。ほぼ全環境で確実に取得できる。
  - **CPU温度**: Linuxの `/sys/class/thermal/thermal_zone*/temp` を読む。`type` ファイルに `cpu`/`x86_pkg_temp` を含むゾーンを優先し、無ければ最初に見つかったゾーンにフォールバックする。ディレクトリ自体が存在しない環境（Windows/macOS、コンテナ等）では `Optional.empty()` を返し、ステータスラインには `N/A` と表示する。
  - **GPU温度**: `nvidia-smi --query-gpu=temperature.gpu --format=csv,noheader,nounits` を `ProcessBuilder` でサブプロセス起動して取得する（NVIDIA環境のみ）。`waitFor(1500ms)` のタイムアウトを設け、コマンドが存在しない・応答がない場合は `Optional.empty()`（`N/A`表示）にフォールバックする。AMD/Intel GPU・コンテナ環境等、`nvidia-smi` が無い環境の方が多いことを前提にした設計。
  - この3項目とも「取得できないのは異常ではなく普通にありうる」という前提で、例外を投げずに `Optional.empty()` → `N/A` 表示に倒す（Shift+K/`gr` 等の既存コンポーネントと同じ graceful degradation の方針）。
- **表示位置**: `EditorCanvas.drawStatusLine()` で、時計表示（最右端）のすぐ左隣に `CPU 45°C | GPU N/A | MEM 62%` の形式で表示する。既存の診断件数表示（エラー/警告数）は、このシステムステータス表示のさらに左隣にずれる形になった（右から: 時計 → システムステータス → 診断件数）。
- **意図的に採用しなかった案**: 真のプラットフォーム非依存なCPU/GPU温度取得手段は存在しない（OSHI等の外部ライブラリがあるが、CLAUDE.mdの「依存ライブラリを一切使用しない」制約に反するため不採用）。Windows向けに `wmic`/PowerShell経由のACPI温度取得を追加することも検討したが、多くの環境で管理者権限が必要・信頼性が低いため今回のスコープでは見送り、Linuxの `/sys/class/thermal` とNVIDIAの `nvidia-smi` のみをサポート対象とした（それ以外の環境では単に `N/A` 表示になる）。
- **テスト**: `test/dev/javatexteditor/system/SystemStatsMonitorTest.java`（8/8）。このコンテナ環境には温度センサーも `nvidia-smi` も存在しないため、具体的な温度値ではなく「値が取れるなら妥当な範囲(-40〜150°C)」「取れないなら空」の両方を許容する形でテストしている。メモリ使用率のみ、JDK標準APIが常に利用可能なため必ず値が返ることを検証している。

## 不具合修正: ウォーキングパーソン（ステータス行の歩行キャラクター）の高さがフォント高さと揃わない問題（2026-07）

- **症状**: `Ctrl+Shift+矢印` で文字セル高さ（`cellH`）を変更すると、本文の文字（`TtfMonoFont`でセル高さぴったりに描画される）に対して、ステータス行を歩くキャラクター（`WalkingPersonSprite`）の見かけの高さが一致しないことがあった。
- **原因**: `EditorCanvas.drawWalkingPerson()` が `int scale = Math.max(1, lineHeight / WalkingPersonSprite.PERSON_H)` という**整数除算**でスプライトの拡大率を決めていた。`PERSON_H`（16px）に対し `lineHeight` が16の倍数ちょうどでない限り端数が切り捨てられ、`spriteH = PERSON_H * scale` が `lineHeight` と一致しない（例: `lineHeight=30` なら `scale=1` になり `spriteH=16px` にしかならず、既定値の `lineHeight=20` でも `scale=1` で `spriteH=16px` と、文字の高さ20pxに対し4px足りない）。フォントサイズを変えてもキャラクターの高さが飛び飛びにしか追従しない、という見た目のズレの原因だった。
- **修正**: `WalkingPersonSprite.drawFrame()`/`calcX()` の `scale` 引数を `int` から `double` に変更し、`Graphics2D.scale(scale, scale)` によるアフィン変換でスプライトを描画するようにした（従来の `col*scale`/`row*scale` を使った `fillRect` 呼び出しの手動スケーリングを廃止）。新設の `WalkingPersonSprite.heightScale(int targetHeight)` が `targetHeight / PERSON_H`（小数）を返し、`Math.round(PERSON_H * scale) == targetHeight` を保証する。`EditorCanvas.drawWalkingPerson()` はこれを呼んで `scale`/`spriteH` を求めるだけになり、スプライトの高さは常にステータス行の文字高さ（`lineHeight`）とちょうど一致するようになった。
- **意図的に変更しなかった点**: スプライトデータ（`PERSON_DATA`）自体・フレームレート・移動速度（`WALK_SPEED`）は変更していない。あくまで拡大率の計算方法（整数→小数）と描画方法（手動ピクセル拡大→`Graphics2D`のアフィン変換）のみを変更し、見た目上のドット絵表現（アンチエイリアスなしの矩形塗りつぶし）はそのまま維持した。

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

## 実装済みの変更: TTF（IBM Plex Mono Regular）から MiscFixedFont10x20 への回帰（2026-07・第3弾）

直前の節（「misc-fixed 生成ビットマップフォント方式から実TTFレンダリング方式への全面移行」）でTTF方式へ全面移行していたが、ユーザーから改めて「やっぱりMiscFixedFontの10x20を採用してほしい。ただし文字の大きさの比率は維持してほしい」という指示があり、TTFベースの `TtfMonoFont` を廃止して `dev.javatexteditor.ui.MiscFixedFont10x20`（新設）によるビットマップ描画へ回帰した。

- **実装前に `AskUserQuestion` で2点確認済み**: ①変更範囲は「単一の10x20ビットマップのみ復活」（TTF移行前に存在した5x7〜9x18の12サイズ自動切替アーキテクチャ（`FixedBitmapFont`/`FixedFontCatalog`/`FixedFontRenderer`）は再導入しない）、②「比率を維持」は「セルの縦横比（幅:高さ）」を指す。`TtfMonoFont.BASE_CELL_W`/`BASE_CELL_H` は移行後も10/20のまま変わっていなかったため、この確認により「既定セル比率10:20は変更不要、glyph自体のネイティブ描画に戻すだけでよい」ことが確定した。
- **グリフデータの再取得**: 過去に存在した `BitmapFont10x20.java`（手書きバイト配列）は、TTF移行時に完全削除されリポジトリ履歴にも一切残っていなかった（`git log --all -S` で確認済み、コミットされたことが無かった）。そのため `apt-get install xfonts-base pcf2bdf` で実際の X11 misc-fixed 10x20（`/usr/share/fonts/X11/misc/10x20-ISO8859-1.pcf.gz`）を取得し直し、`pcf2bdf` でBDF化した上でPythonスクリプトでBBX/BITMAPセクションを読み取ってJavaの`byte[]`ソースを機械生成した（生成スクリプトは使い捨てのためリポジトリには残していない。手法自体は本SKILLの「横縦比率に応じたビットマップフォント自動切替」節で確立済みの生成パイプラインの再利用）。全95グリフ（ASCII 0x20-0x7E）ともBBXが `10 20 0 -4`（幅10・高さ20・descent 4・ascent 16）で完全に統一されており、パディングオフセット計算は不要だった。
- **`MiscFixedFont10x20` の設計**: シングルトン（`INSTANCE`）。`isSupported(int)`/`descentPixels(int)`/`renderGlyph(int,int,int,int)` という `TtfMonoFont` と全く同じ3メソッド契約を維持したドロップイン置き換えにした（`EditorCanvas`/`Main` 側の呼び出し規約変更を最小化するため）。`renderGlyph()` はネイティブ10x20ビットマップを `cellW`/`cellH` へ縦横独立のニアレストネイバーで拡縮する（TTF版が非等方向アフィン変換で実現していた「Ctrl+Shift+矢印でセル比率が崩れても破綻しない」という可変仕様を、ビットマップ版では独立軸ニアレストネイバーで実現するという、TTF移行前の`FixedFontRenderer`と同じ考え方に回帰している）。`descentPixels(cellH)` はネイティブ比率 `4/20` をセル高さへ比例配分する。
- **`EditorCanvas`/`Main`側の変更**: `TtfMonoFont` → `MiscFixedFont10x20` への機械的な置き換え（型・`BASE_CELL_W`/`BASE_CELL_H`参照）に加え、フィールド名 `ttfFont` → `bitmapFont`（実体を正確に表す名前へ変更）、コメント中の「IBM Plex Mono」「TTF」表記を「misc-fixed」表記へ更新した。`cellW`/`cellH` の可変範囲（5〜40 / 8〜80）・グリフキャッシュ機構（`glyphCacheFg`/`Bg`/`uiGlyphCache`）は無変更。
- **`scripts/setup.sh`/`setup.bat` のフォントダウンロード処理（第2弾で追加した「4. IBM Plex Mono Regular (TTF) の取得」節）を削除した**。misc-fixed版はグリフデータをソースに直接埋め込むため、`lib/fonts/`への実行時ダウンロードが不要になったため（TTF移行前の状態と同じ、フォント関連の外部リソース取得ステップ自体が存在しない）。`lib/fonts/IBMPlexMono-Regular.ttf`・`IBMPlexMono-OFL.txt` は今後生成されない。
- **削除したファイル**: `TtfMonoFont.java`。テスト（`EditorCanvasTest`）は `TtfMonoFont.INSTANCE.xxx` の呼び出し3箇所を `MiscFixedFont10x20.INSTANCE.xxx` に機械的に置き換えただけで、アサーション内容自体は変更していない（サイズ検証・点灯ピクセル数検証はフォント実装に依存しない検証のため、そのまま有効）。
- **ライセンス**: misc-fixedはPublic domain（BDFの`COPYRIGHT`プロパティに`"Public domain font. Share and enjoy."`と明記）。IBM Plex Mono（SIL OFL 1.1）に伴っていたライセンス表記・配布条件の考慮は不要になった。
- **テスト・目視確認**: 既存の `EditorCanvasTest`（フォント関連3テスト含む51/51）・全体テストスイート（既知のベースラインFAIL、`ScrollTest`2件・`ModalEditorTest`1件を除き全PASS）を確認済み。加えてスクラッチパッドで簡易プレビュー画像を生成し、10x20セルでのASCII全体・記号類の可読性を目視確認済み。
- **意図的にスコープ外とした点**: TTF移行前に存在した「セル縦横比に応じて5x7〜9x18の12サイズから自動選択する」仕組み（`FixedFontCatalog.select()`）は今回のユーザー指示（「単一の10x20ビットマップのみ復活」を選択）により再導入していない。`Ctrl+Shift+矢印`でセルサイズを大きく変えた場合、TTF版のようなアンチエイリアス付きベクター変形ではなく、ネイティブ10x20ビットマップをニアレストネイバーで拡縮した結果になる（拡大時は角ばった見た目になる）ことは、ユーザーが明示的に選択した仕様である。

## 実装済みの変更: MiscFixedFont10x20 から TerminusBold10x20 への差し替え（2026-07・第4弾）

直前の節でmisc-fixed 10x20へ回帰した直後、ユーザーから「添付画像のフォントと同じものを使ってほしい。`misc-fixed-bold-r-*-*-45-*-90-*-*-*-iso10646-1` というXLFDに該当する」という追加依頼があった。調査の結果、そのXLFD文字列自体は標準のX11 misc-fixedのどのサイズとも一致しない（misc-fixedのBold版は6x13B/7x13B/7x14B/8x13B/9x15B/9x18Bの6種のみで10x20のBoldは存在しない）ことが判明し、ユーザーに`AskUserQuestion`で確認を試みたが2回とも却下された。その後ユーザーが実際のスクリーンショット（`bisqwit@hariyu`という端末タイトルバー、本SKILLが参考にしている`bisqwit/that_terminal`の作者本人の環境と思われる、`ntsc.cc`というNTSC信号レンダリングコードを編集中の画面）を添付し、「これを参考にしてください」と指示した。

- **フォント特定の手順**: 添付画像の字形（`g`の開いたフック型ディセンダー、二階建ての`a`、太いストローク）から、`misc-fixed`ではなく **Terminus**（Dimitar Toshkov Zhekov氏によるビットマップフォント、Debian/Ubuntuでは`console-setup`/`kbd`パッケージ経由で`/usr/share/consolefonts/`にPSF形式で配布）の**Bold**である可能性が高いと判断した。`apt-get install kbd`でPSFフォント一式を取得し、Pythonで自作したPSF1/PSF2パーサーで`Lat15-TerminusBold20x10.psf.gz`（ファイル名の`20x10`はheightxwidthの意味で、実際のグリフ寸法は幅10×高さ20px = 既存の`MiscFixedFont10x20`と完全に同じセルサイズ）をレンダリングし、添付画像と同じコード行（`static void RenderSignal(unsigned* texture, ...)`）を再現して`SendUserFile`で提示、ユーザーの明示的な確認（「trueタイプフォントである必要はない、前のバイナリで表現した形で」）を得てから実装した。
- **ネイティブディセント値の確認**: PSFフォーマット自体はBDFの`FONT_DESCENT`のようなメタデータを持たないため、`A`（ディセンダーなし文字、上から16行分=行0-15に収まる）と`g`（ディセンダーあり文字、残り4行のうち3行=行16-18まで伸びる）の実際のビットパターンを目視確認し、misc-fixed版と同じ「20行中下4行がディセント領域」（`NATIVE_DESCENT=4`）であることを確認した。この値はソースコードを変更せず流用できた。
- **`TerminusBold10x20`（新設、`MiscFixedFont10x20`を置き換え）の設計**: シングルトン（`INSTANCE`）。`isSupported(int)`/`descentPixels(int)`/`renderGlyph(int,int,int,int)`という同じ3メソッド契約・同じ`GLYPH_W`/`GLYPH_H`/`BYTES_PER_ROW`/`BYTES_PER_GLYPH`/`NATIVE_DESCENT`の定数構成をそのまま維持し、`GLYPHS`バイト配列の中身だけをmisc-fixedからTerminus Boldへ総入れ替えした（生成パイプライン・データ形式が実質的に共通のため、ドロップイン置き換えで済んだ）。PSF2フォーマットはASCII範囲でコードポイントとグリフインデックスが一致するため、BDF版と同じ「codePoint - FIRST_CHARでオフセット計算」がそのまま使えた。
- **`EditorCanvas`/`Main`/テスト側の変更**: `MiscFixedFont10x20` → `TerminusBold10x20`への機械的なクラス名置き換え（`bitmapFont`というフィールド名は実体を正しく表しているため変更不要）。コメント中の「misc-fixed」表記を「Terminus Bold」へ更新した。
- **`scripts/setup.sh`/`setup.bat`**: フォントデータをソースに直接埋め込む方式（ダウンロード不要）である点は変更なしのため、ファイル名の言及のみ`MiscFixedFont10x20.java`→`TerminusBold10x20.java`に更新した。
- **ライセンス**: Terminus フォントは SIL Open Font License 1.1で配布されている（Debianパッケージ`xfonts-terminus`/`console-setup`も同ライセンス）。OFLはビットマップへの変換・ソースコードへの埋め込みを妨げない。
- **削除したファイル**: `MiscFixedFont10x20.java`。
- **テスト・目視確認**: `EditorCanvasTest`のフォント関連3テストを含む全体テストスイートが、既知のベースラインFAIL（`ScrollTest`2件・`ModalEditorTest`1件）を除き全PASSであることを確認。加えて実際の`TerminusBold10x20`クラス経由でサンプルコードをレンダリングしたPNGを目視確認し、Python版プロトタイプでの検証結果と一致することを確認した。
- **教訓（今後同種の依頼を受けた場合の参考）**: ユーザーが提示したXLFD文字列やフォント名の記憶は必ずしも正確とは限らない（今回は「misc-fixed-bold」という誤った手がかりだった）。標準フォントパッケージに実際に存在するサイズ・バリエーションを`find`/`apt-cache`等で機械的に確認し、疑わしい場合はスクリーンショットの字形を最終的な判断材料にするのが確実。`AskUserQuestion`（構造化選択式)が繰り返し却下される場合、それ以上の選択式確認を重ねず、証拠に基づく最有力候補をレンダリングして`SendUserFile`で提示し、プレーンテキストで確認を求める方が受け入れられやすい。

## 訂正: TerminusBold10x20 は誤りで、実際は X11 misc-fixed Bold 9x18 だった（2026-07・第5弾）

直前の節でTerminus Boldに差し替えた直後、ユーザーから「フォントが全然違う」という指摘とともに、実際に使用している **xtermの起動コマンドそのもの**が提示された:

```
xterm -bg black -fg gray -fn -misc-fixed-bold-r-*-*-45-*-90-*-*-*-iso10646-1 -fb -misc-fixed-bold-r-*-*-45-*-90-*-*-*-iso10646-1
```

- **判明した事実**: 最初に提示されたXLFD（本SKILL冒頭の「misc-fixed-bold」節参照）は誤りではなく、文字通り「misc-fixed-bold」を指定するX11の正式なXLFDワイルドカードパターンだった。フォント族の特定を誤ったのはこちらの判断ミスで、`AskUserQuestion`によるBold版の存在確認（「10x20のBoldは無いのでどう対応するか」を尋ねた質問）が2回とも却下されたことを「misc-fixedではない」というシグナルだと誤読し、Terminus Boldという全く別のフォント族へ飛躍してしまっていた。実際にはユーザーは終始「misc-fixed-bold」と言い続けており、こちらが「標準パッケージに存在しないサイズだから別のフォントのはず」と早合点したのが誤りの本質。
- **XLFD `pixelsize=45, resolution_x=90` の意味**: misc-fixedのBold版は標準の`xfonts-base`パッケージに実在する最大サイズが `9x18B`（高さ18px）までしかない（本SKILL「misc-fixed-bold-r-\*-\*-45-\*-90-\*-\*-\*-iso10646-1」節で確認済み）。Xサーバー（またはフォントサーバー）は、要求されたpixelsize=45にジャストフィットするビットマップが無い場合、**実在する最大サイズのビットマップをニアレストネイバーで要求サイズまで拡大**して提供する（`fonts.scale`/`mkfontscale`によるX11のビットマップフォントスケーリング機構）。つまりユーザーが実際に画面で見ているのは「9x18Bを2.5倍(45/18)程度にニアレストネイバー拡大したmisc-fixed-bold」であり、これは本プロジェクトが既に採用している「ネイティブ1サイズのビットマップを`cellW×cellH`へ縦横独立ニアレストネイバー拡縮する」というアーキテクチャと**原理的に完全に同一**の仕組みだった。
- **修正**: `TerminusBold10x20`を`MiscFixedBold9x18`（新設）へ差し替えた。`apt-get install xfonts-base pcf2bdf`で取得した`/usr/share/fonts/X11/misc/9x18B-ISO8859-1.pcf.gz`（Public domain、BBXは全グリフ`9 18 0 -4`で統一・`FONT_ASCENT=14`/`FONT_DESCENT=4`）を`pcf2bdf`でBDF化し、既存と全く同じ生成パイプライン（BBX/BITMAPセクションをPythonで読み取りJavaの`byte[]`へ機械生成）でグリフデータを抽出した。
- **`GLYPH_W`/`GLYPH_H`をネイティブグリフの実寸（9×18）に変更した点が今回の設計上の要点**: `BASE_CELL_W`/`BASE_CELL_H`（既定の画面セルサイズ、10×20）はこれまでの決定通り変更していない。`renderGlyph()`は元々「ネイティブグリフサイズ→`cellW`×`cellH`への独立軸ニアレストネイバー拡縮」という設計だったため、ネイティブサイズが10×20か9×18かに関わらず同じロジックがそのまま使え、コード変更は定数（`GLYPH_W`/`GLYPH_H`/`NATIVE_DESCENT`）とグリフデータだけで済んだ。これにより、既定表示（9x18を10x20セルへ軽く拡大）から`Ctrl+Shift+矢印`で大きなセルサイズへ変更した場合（例: 23x45相当）まで、X11の実際のビットマップフォントスケーリング挙動を一貫して再現する。
- **削除したファイル**: `TerminusBold10x20.java`。
- **テスト・目視確認**: 全体テストスイート（既知のベースラインFAIL、`ScrollTest`2件・`ModalEditorTest`1件を除き全PASS）を確認。加えて実際の`MiscFixedBold9x18`クラス経由で、ユーザー添付画像と同じコード行を23×45セル（xtermの pixelsize=45 相当）でレンダリングしたPNGが、添付画像の字形・太さ・ブロック感と一致することを目視確認した。
- **教訓（さらなる追記）**: ユーザーが実際のコマンドライン・設定ファイルなど「一次情報」を提示してきた場合はそれを最優先の正とすべきで、標準パッケージに完全一致するサイズが見当たらないという事実だけで「別のフォント族のはず」と結論づけてはならない。X11・PSF・BDFのようなレガシーなビットマップフォント基盤には、要求サイズに対する動的スケーリング機構（本件のような`fonts.scale`によるビットマップ拡大）が存在することを踏まえ、まず「実在する最大/最小サイズを拡大縮小した結果ではないか」を検証すること。

## 再訂正: ネイティブサイズは9x18ではなく10x20を厳守する（`MiscFixedBold10x20`、2026-07・第6弾）

直前の節で`MiscFixedBold9x18`（実在するmisc-fixed Boldの最大サイズ9x18Bをニアレストネイバー拡大する方式）に修正したところ、ユーザーから「9x18ではなく10x20のフォントを指定しています、その部分もしっかり守ってください」という明確な指摘があった。会話の最初（本SKILL冒頭）から一貫して「10x20」というネイティブサイズの指定があり、Bold化にあたってもこの制約を落としてはならなかった。

- **問題の核心**: 標準のX11 `xfonts-base`パッケージには、`misc-fixed-bold`の10x20サイズが物理的に存在しない（Bold版は6x13B/7x13B/7x14B/8x13B/9x15B/9x18Bの6サイズのみ）。「フォント族(misc-fixed-bold)を厳守する」と「ネイティブサイズ(10x20)を厳守する」の両方を同時に満たす実在のPCF/BDFファイルは存在しないため、いずれかを実データからの**合成**で補う必要がある。前節では「フォント族」を優先し「サイズ」を妥協した（9x18を拡大）が、ユーザーは「サイズ」の厳守を優先するよう明確に指示した。
- **採用した合成方法**: 本物の X11 misc-fixed **Regular** 10x20（`10x20-ISO8859-1.pcf.gz`、Public domain。本SKILL「MiscFixedFontの10x20のフォントを採用」節で最初に確認・抽出済みのデータ）を出発点にし、各行を「1ピクセル右にずらしたコピーを元の行にOR演算で重ねる」という古典的なビットマップフォント太字化アルゴリズムで疑似Bold化した。これは多くの端末エミュレータが、真のBold書体グリフが存在しない場合に同じ文字を1ピクセルずらして2回描画する「フェイクボールド」と全く同じ原理である。Java実装では各行を16bit整数として扱い `bold = v | (v >>> 1)` の1行で実現した（`v`のビット位置0=左端ピクセルというMSB-first規約のため、右シフトが「1ピクセル右にコピーを重ねる」操作に対応する）。
  - 太さの選定にあたり、実際に太字化した文字（`A`/`g`/`R`）のASCIIアートをレンダリングして確認し、10px幅の枠内でストロークが右方向にのみ1px太くなり、文字同士が潰れずに判読可能であることを目視確認した。
  - これによりネイティブサイズが正確に10×20になり、`BASE_CELL_W`/`BASE_CELL_H`（10,20）と完全に一致するため、既定表示ではニアレストネイバー拡縮が一切発生しない（前節の9x18版は既定表示でも軽い拡大が常に発生していた）。
- **`MiscFixedBold9x18`を`MiscFixedBold10x20`（新設）へ差し替えた**。`GLYPH_W`/`GLYPH_H`を9/18から10/20へ戻し、`BYTES_PER_GLYPH`も36→40へ戻った（misc-fixed版の`MiscFixedFont10x20`と同じ定数値）。`NATIVE_DESCENT=4`は太字化処理（水平方向のOR演算）では変化しないため、元の10x20 Regularと同じ値をそのまま維持できた。
- **削除したファイル**: `MiscFixedBold9x18.java`。
- **テスト・目視確認**: 全体テストスイート（既知のベースラインFAIL、`ScrollTest`2件・`ModalEditorTest`1件を除き全PASS）を確認。加えて実際の`MiscFixedBold10x20`クラス経由で、ネイティブサイズ(10×20、拡縮なし)と23×45セル（xtermのpixelsize=45相当、拡大あり）の両方でユーザー添付画像と同じコード行をレンダリングし、いずれも添付画像の字形・太さと一致することを目視確認した。
- **教訓（今後同種の依頼を受けた場合の参考、更新版）**: 「フォント族」と「ネイティブサイズ」がともにユーザーの明示的な要求事項である場合、標準配布物にその組み合わせが実在しないからといって、どちらか一方を暗黙に妥協してはならない。実在しない場合は、実在する最も近いデータ（今回は本物のRegular 10x20）を土台に、既知のアルゴリズム（太字化・ニアレストネイバー拡縮等）で欠けている属性を合成する方が、両方の要求を満たせる可能性が高い。ユーザーからの明確な訂正（「Xではなく Y」という言い回し）が来た場合、それは「一方の要求が満たされていない」という直接的なシグナルであり、次の実装ではそれを字義通り最優先すること。

## 最終確定: 本物のネイティブサイズは9x15だった（`MiscFixedBold9x15`、2026-07・第7弾）

前節の`MiscFixedBold10x20`（本物のRegular 10x20を疑似太字化して合成）に対し、ユーザーが自分の実機で実行中の xterm プロセス一覧（`ps auxwww|grep xterm` のスクリーンショット3枚）を提示した。これが本SKILLで最も確度の高い一次情報となり、以降のフォント選定の最終決着となった。

- **決定的な証拠**: `ps` の出力に写っていた6つのxtermプロセスの `-fn`/`-fb` 引数を比較したところ、実際に使われているフォントのpixelsizeは **15 / 30 / 45** の3種類のみで、いずれも15の整数倍（1倍・2倍・3倍）だった。さらにそのうち1つ（`-misc-fixed-bold-r-normal--15-140-75-75-c-90-iso10646-1`）は、標準の`xfonts-base`パッケージに実在する `9x15B-ISO8859-1.pcf.gz` のBDFヘッダ（`-Misc-Fixed-Bold-R-Normal--15-140-75-75-C-90-ISO8859-1`）と、charset registry（`ISO8859-1` vs `iso10646-1`。ASCII範囲のグリフ形状には影響しない）を除いて完全に一致した。これにより「本当のネイティブサイズは9x15であり、30pxと45pxはXサーバーがそれを2倍・3倍にニアレストネイバー拡大して表示しているだけ」であることが動かぬ証拠として確定した。
- **これまでの誤りの整理**: ①最初の`misc-fixed-bold`という手がかりを誤ってTerminus Boldに読み違えた（本SKILL「TerminusBold10x20」節）。②実在する最大Boldサイズを優先して9x18Bを採用したが、これはユーザーの「10x20」という発言と食い違った（`MiscFixedBold9x18`節）。③ユーザーの「10x20厳守」という発言を字義通り優先し、本物のRegular 10x20を疑似太字化して合成した（`MiscFixedBold10x20`節）。しかし今回の`ps`出力という一次情報により、そもそも「10x20」自体がユーザーの記憶違いであり、本当のネイティブサイズは9x15だったことが判明した。合成（疑似太字化）は不要で、9x15Bという本物のBoldビットマップデータをそのまま使えばよかった。
- **`MiscFixedBold10x20`を`MiscFixedBold9x15`（新設）へ差し替えた**。`apt-get install xfonts-base pcf2bdf`で取得した`/usr/share/fonts/X11/misc/9x15B-ISO8859-1.pcf.gz`（Public domain、BBXは全グリフ`9 15 0 -3`で統一・`FONT_ASCENT=12`/`FONT_DESCENT=3`）を`pcf2bdf`でBDF化し、以前と同じ生成パイプラインでグリフデータを抽出した。`GLYPH_W`/`GLYPH_H`=9/15、`BYTES_PER_GLYPH`=30、`NATIVE_DESCENT`=3。太字化アルゴリズム（`v | (v>>>1)`）は不要になった（本物のBoldデータのため）。
- **`BASE_CELL_W`/`BASE_CELL_H`を9/15（ネイティブサイズと同一）に変更した**。これまでの節では「10x20」というセルサイズを死守する方針だったが、根拠だったユーザーの発言自体が誤りだったと判明したため、今回は素直にネイティブサイズをそのまま既定値にした。実機のxtermで最も頻繁に使われているサイズが必ずしも15pxとは限らない（`ps`出力では30px・45pxのプロセスも複数存在した）が、`Ctrl+Shift+矢印`で9x15の整数倍（18x30・27x45等）に変更すれば実機と同じニアレストネイバー拡大結果を再現できるため、既定値としては最もシンプルで歪みのない「1倍（ネイティブサイズそのまま）」を採用した。
- **テストの副作用と対応**: `BASE_CELL_H`が20→15に変わったことで、`EditorCanvasTest`の2件（IME変換中オーバーレイの下線y座標をハードコードしていたテスト、wrap時の`ensureCursorVisible`のスクリーン行数計算をcanvas高さ80px・lineHeight=20前提で書いていたテスト）がFAILするようになった。前者は`canvas.getCellH() - 1`を使う座標非依存の書き方に修正し、後者はcanvas高さを80→60に変更して「visibleRows=3」という意図されたシナリオを新しい既定cellH=15のまま再現するよう修正した（いずれも回帰ではなく、フォントサイズ変更に伴うテスト側のハードコード値の追従漏れ）。修正後は既存のベースラインFAIL（`ScrollTest`2件・`ModalEditorTest`1件）のみに戻り、全82クラス中80クラスPASSを確認。
- **削除したファイル**: `MiscFixedBold10x20.java`。
- **テスト・目視確認**: 全体テストスイート確認に加え、実際の`MiscFixedBold9x15`クラス経由でネイティブサイズ(9×15)・2倍(18×30)・3倍(27×45、xtermのpixelsize=45相当)の3パターンでユーザー添付画像と同じコード行をレンダリングし、いずれも添付画像の字形・太さと一致することを目視確認した。
- **教訓（最終版）**: ユーザーが提示する情報の確度には階層がある——記憶に基づく発言（「10x20と言った」）よりも、実際に動いているシステムの生の出力（`ps`のプロセス引数、設定ファイル、ログ）の方が優先度が高い一次情報である。今回のように複数の情報源が一見矛盾する場合、後から提示された、より検証可能な（他者が同じ手順で再現・確認できる）証拠を採用するのが妥当。また「複数の観測値が同じ値の整数倍になっている」（15/30/45）という規則性は、スケーリングの基準単位を特定する強力な手がかりになる。

## 実装済みの追加機能: `:font`/`:color` コマンドによる実行時フォント・カラーテーマ切替（2026-07・第8弾）

これまでの節（第1〜7弾）は「半角ASCIIフォントを1種類に固定し、ユーザーの指示のたびに総入れ替えする」という変遷だったが、今回は「MiscFixed（既定）とIBM Plex Mono Regular (TTF) の2種類をユーザーがコマンドで自由に選べるようにしてほしい」「カラーテーマもコマンドで切り替えたい」という明示的な依頼があり、初めて**複数フォントの実行時切替**を実装した。

- **`MonoFont`インタフェース（新設）**: `MiscFixedBold9x15`（ビットマップ・ニアレストネイバー拡縮、第7弾で確定したネイティブ9x15データ）と、第2弾で一度削除した`TtfMonoFont`相当の実装を`IbmPlexMonoFont`として復元したものの両方が実装する共通契約（`isSupported(int)`/`descentPixels(int)`/`renderGlyph(int,int,int,int)`）。両クラスは元々この3メソッド契約で設計されていた（第2弾のJavadocに明記済み）ため、インタフェースを被せるだけで済んだ。`EditorCanvas.bitmapFont`フィールドを`final MiscFixedBold9x15`から可変の`MonoFont`型に変更し、`setFontChoice(FontChoice)`で実装を差し替える。
- **`IbmPlexMonoFont`は第2弾で削除した`TtfMonoFont.java`のコードをほぼそのまま復元した**（git履歴からコミットc3d8eb0の直前バージョンを取得）。TTF実体（`lib/fonts/IBMPlexMono-Regular.ttf`）が見つからない場合は`Font.MONOSPACED`にフォールバックする既存の graceful degradation もそのまま維持している。`scripts/setup.sh`/`setup.bat`のフォントダウンロード処理（第2弾で追加し第3弾で削除していたもの）も同じ内容で復元した。
- **`FontChoice`列挙型（新設）**: `MISC_FIXED`（既定、`:font 0`）/`IBM_PLEX_MONO`（`:font 1`）。`ModalEditor`が`executeCommand()`で`:font N`をパースしてこのフィールドを更新し、`syncCanvas()`が毎回`canvas.setFontChoice(fontChoice)`を呼んで反映する。
- **`:color 0`（ダークモード）/`:color 1`（ライトモード）**も同じパターンで実装した。`ModalEditor`に`Theme theme`フィールド（既定`DARK_MODE`、従来`Main.java`が全ペイン一律で`canvas.setTheme(Theme.DARK_MODE)`と固定していた値をそのままデフォルトに採用）を追加し、`syncCanvas()`が`canvas.setTheme(theme)`を呼ぶ。
- **`syncCanvas()`は1キー入力ごとに呼ばれるため、`EditorCanvas.setTheme()`/`setFontChoice()`に「値が変化していない場合は何もしない」ガードを追加する必要があった**。既存の`setTheme()`は無条件に`invalidateGlyphCache()`+`repaint()`を行っていたが（第1弾実装当初、Main.javaが起動時に1回だけ呼ぶ想定だったため問題にならなかった）、`:color`/`:font`導入により毎キー入力で同じ値を渡して呼ばれるようになったため、値の変更を伴わない呼び出しでグリフキャッシュを毎回破棄してしまうと性能劣化（軽量化リファクタリングPhase 2でのsyncCanvasキャッシュ徹底と同種の問題）を招く。両メソッドとも`if (this.field == newValue) return;`を先頭に追加して解決した。
- **`:font`/`:color`は per-pane（EditorCanvas単位）の設定**。`Ctrl+Shift+矢印`によるセルサイズ変更が per-pane で独立に効くのと同じ扱いにした（グローバル一括切替にはしていない）。`:split`/`:vsplit`で新規ペインを作った直後は、分割元ペインの`Theme`/`FontChoice`を引き継ぐ（既存のcellW/cellH引き継ぎ＝「分割直後の初期値を揃えるだけで以後は独立」という設計をそのまま踏襲。`Main.createLeaf()`に`Theme`/`FontChoice`引数を追加したオーバーロードを新設し、`setSplitHorizontalCallback`/`setSplitVerticalCallback`から`cur.editor().getTheme()`/`getFontChoice()`を渡す）。
- **意図的にスコープ外とした点**: フォント・カラーテーマの選択をセッションをまたいで永続化する仕組みは実装していない（`WorkingDirectoryManager`の`Preferences`永続化を廃止した既存判断——CLAUDE.md「作業ディレクトリ・`:pwd`/`:cd`の設計決定事項」節参照——と同じ思想。起動時は常に既定値＝MiscFixed・ダークモードから始まる）。`:font`/`:color`ともに数値以外・範囲外の引数は`E: usage ...`のエラーメッセージのみでモード変更もフィールド変更も行わない（他のコマンドと同じ`statusMessage`エラーパターン）。
- **テスト**: `test/dev/javatexteditor/editor/FontColorCommandTest.java`（新設・15テスト）。既定値（MiscFixed・ダークモード）・`:font 0`/`:font 1`双方向の切替・`:color 0`/`:color 1`双方向の切替・不正な引数でのエラー表示とフィールド不変・`EditorCanvas`への反映・コマンド実行後にNORMALモードへ戻ることを検証。既存の`EditorCanvasTest`（フォント関連の既存アサーション含む）・全体テストスイートは無修正で回帰なし（既知のベースラインFAIL、`ScrollTest`2件・`ModalEditorTest`1件を除き全PASS）。

## 不具合修正: `:font 1`(Plex Mono)がMiscFixedのセルサイズをそのまま引き継いでいた問題・Plex Mono専用の`:fs`絶対値テーブル新設（2026-07-29）

- **症状**: `EditorCanvas.cellW`/`cellH`は`fontChoice`に関わらず単一のグローバル状態として共有されており、`setFontChoice()`はビットマップ実装（`bitmapFont`）を差し替えるだけでセルサイズには一切触れていなかった。そのため、MiscFixed(9x15)表示中に`:font 1`へ切り替えても`cellW`/`cellH`は9x15のままPlex Monoで描画され、`IbmPlexMonoFont`本来の縦長比率が崩れて横に間延びした見た目になっていた。加えて`IbmPlexMonoFont.BASE_CELL_W`/`BASE_CELL_H`は`10`/`20`という値が定義されていたが、実測に基づかない値で、`:font 1`直後の見た目デフォルトとしてはどこからも参照されていなかった（前掲「`:font`/`:color`コマンドによる実行時フォント・カラーテーマ切替」節の実装漏れ）。
- **実測**: IBM Plex Mono Regular TTF（`lib/fonts/IBMPlexMono-Regular.ttf`）の`hhea`/`hmtx`テーブルを実際にパースし、`unitsPerEm=1000`・`ascender=1025`・`descender=-275`・`advanceWidth('M')=600`を確認した。この実測に基づく比率(600/1300≒0.4615)は、`IbmPlexMonoFont`が実際の描画で使う`FontMetrics`実測値（REF_SIZE=100pt換算でadvance=60・ascent+descent=131、比率≒0.458）ともほぼ一致する。ユーザーが仮説として挙げた「幅高比約3:5(0.6)」は、advance(600)をラインハイト(1300)ではなくem正方形(1000)で割った値であり、実際の描画比率とは異なることが判明した。
- **修正1（`IbmPlexMonoFont.BASE_CELL_W`/`BASE_CELL_H`）**: MiscFixedの既定高さ(15px)に合わせ、実測比率から幅を逆算する形で`BASE_CELL_W=7`・`BASE_CELL_H=15`に変更した（`round(15*0.458)=7`）。単純にMiscFixedの9x15を流用せず、高さの基準だけ揃えて幅は実測比率から独立に算出している。
- **修正2（`EditorCanvas`のフォント別セルサイズ独立化）**: `miscCellW`/`miscCellH`・`plexCellW`/`plexCellH`の4フィールドを新設し、それぞれのフォントの「現在のセルサイズ」を退避する。`adjustCellWidth()`/`adjustCellHeight()`/`setInitialCellSize()`（Ctrl+Shift+矢印・`:fs`のいずれも経由する）は変更後に`stashCurrentCellSize()`で現在アクティブなフォント側の退避フィールドへ書き戻す。`setFontChoice()`は切替元フォントの`cellW`/`cellH`を退避フィールドへ保存してから、切替先フォントの退避フィールド（一度もリサイズしていなければ各フォントの`BASE_CELL_W`/`H`のまま）を`cellW`/`cellH`へ復元する。これにより「:font 0 → :font 1 → :font 0」のように何度切り替えても、各フォントの直前の状態（デフォルトのままか、Ctrl+Shift+矢印や`:fs`でカスタムサイズにした状態か）がフォントごとに独立して保持される。
- **修正3（`:fs`のPlex Mono対応）**: 従来`ModalEditor.applyFontSizeCommand()`はPlex Mono選択中のみ「現在サイズへの相対倍率」という別方式（`:fs 2`で現在サイズの2倍）を使っており、MiscFixedの絶対値10段階テーブルとは仕様が非対称だった。`IbmPlexMonoFont.BASE_CELL_W`/`BASE_CELL_H`(7x15)を基準(N=0)とした同形式の絶対値10段階テーブル（`cellW=7*(N+1)`・`cellH=15*(N+1)`、N=0〜9）に統一し、`MISC_FIXED_FS_STEPS`だった定数名を`FONT_FS_STEPS`（両テーブル共通のステップ数）に変更した。2つのテーブルは完全に独立しており、どちらの`:fs`実行も他方のフォントの退避セルサイズ（`miscCellW/H`・`plexCellW/H`）には一切影響しない（修正2のフォント別退避の仕組みにより自動的に満たされる）。
- **意図的にスコープ外とした点**: `DisplayMetrics.cellSize()`（起動時のディスプレイ解像度に応じた初期セルサイズ計算）は`MiscFixedBold9x15.BASE_CELL_W`/`H`を参照したまま変更していない。起動直後の既定フォントは常にMiscFixed（`ModalEditor.fontChoice`の初期値）であり、Plex Monoは`:font 1`実行後にのみ選択されるため、起動時スケーリングの基準をPlex Mono側に合わせる必要がない。
- **テスト**: `test/dev/javatexteditor/ui/FontCellSizeIndependenceTest.java`（新設・14テスト）。起動直後の既定値・`:font 1`直後（一度もリサイズしていない）がPlex Mono本来のデフォルト(7x15)になること・`:font 0`へ戻すとMiscFixedのデフォルト(9x15)に復元されること・一方のフォントをリサイズしてももう一方には影響しないこと・`:font 0 → :font 1 → :font 0`のラウンドトリップで各フォントのカスタムサイズが保持されることを検証。`test/dev/javatexteditor/editor/FontSizeCommandTest.java`は`testPlexMonoStillUsesMultiplier`を`testPlexMonoUsesOwnAbsoluteTable`（Plex Monoの`:fs`絶対値テーブルの0/2/9段階を検証）と`testPlexMonoFsDoesNotAffectMiscFixedStash`（Plex Monoの`:fs`実行後にMiscFixedへ戻してもMiscFixedのデフォルトサイズが変化しないことを検証）に置き換えた（計25テスト）。全体テストスイートは既知のベースラインFAIL（`ScrollTest`2件）のみで回帰なし。

## 実装済みの追加機能: `:font 2`（JetBrains Mono）・`:font 3`（Comic Mono）の追加、フォント未取得時のガイダンス表示（2026-08）

`:font 0`(MiscFixed)/`:font 1`(IBM Plex Mono)の2択に、`:font 2`(JetBrains Mono)・`:font 3`(Comic Mono、参考: https://dtinth.github.io/comic-mono-font/ )を追加してほしいという依頼を受け、既存の`FontChoice`/`MonoFont`/`IbmPlexMonoFont`の枠組みをそのまま水平展開する形で実装した。

- **`JetBrainsMonoFont`/`ComicMonoFont`（新設）**: `IbmPlexMonoFont`と全く同一の設計（`MonoFont`実装、`CodeSourceLocator.findUpward`でTTF実体を探索、`Font.createFont`失敗時は`Font.MONOSPACED`へフォールバック、参照サイズ(100pt)でのFontMetrics実測値から非等方向スケール係数を算出）をそのまま複製した。`BASE_CELL_W`/`BASE_CELL_H`は実際にTTFを`Font.createFont`で読み込みFontMetricsを実測して算出した値（JetBrains Mono: ascent=102・descent=30・advance('M')=60・比率≒0.4545→round(15*0.4545)=7、Comic Mono: ascent=80・descent=32・advance('M')=55・比率≒0.4911→round(15*0.4911)=7）で、いずれもIBM Plex Monoと同じ`BASE_CELL_W=7`・`BASE_CELL_H=15`になった（偶然の一致）。
- **配布元・ライセンス（実機確認済み）**:
  - JetBrains Mono: `https://raw.githubusercontent.com/JetBrains/JetBrainsMono/master/fonts/ttf/JetBrainsMono-Regular.ttf`（SIL OFL 1.1、ライセンス文は同リポジトリの`OFL.txt`）
  - Comic Mono: `https://raw.githubusercontent.com/dtinth/comic-mono-font/master/ComicMono.ttf`（MIT License、ライセンス文は同リポジトリの`LICENSE`）。ユーザー提示の配布元`https://dtinth.github.io/comic-mono-font/`はこのプロジェクトの通信ポリシー上直接は取得できなかったため、実体が同一の GitHub リポジトリ`dtinth/comic-mono-font`のraw URLを採用した（同一ファイルであることをダウンロード後`file`コマンドでTrueType実体と確認済み）。
- **`EditorCanvas`のフォント別セルサイズ独立化を4択へ一般化**: 従来`miscCellW/H`・`plexCellW/H`の2組をif-elseで切り替えていた`setFontChoice()`/`stashCurrentCellSize()`を、`jetbrainsCellW/H`・`comicCellW/H`を追加した上で`CellSize`レコード＋`switch`式（`stashedCellSize(FontChoice)`/`setStashedCellSize(FontChoice,w,h)`/`monoFontFor(FontChoice)`）に書き換えた。4フォントとも完全に独立したセルサイズ状態を持ち、どのフォントをリサイズしても他のフォントには影響しない（既存のPlex Mono/MiscFixed間の独立性保証をそのまま4フォントに拡張）。
- **`ModalEditor.applyFontCommand`/`applyFontSizeCommand`も4択へ一般化**: `:font 2`/`:font 3`のパース追加、`:fs`の絶対値テーブルの基準セルサイズ取得を`fontBaseCellW(FontChoice)`/`fontBaseCellH(FontChoice)`（switch式）に一般化した。表示名も`fontDisplayName(FontChoice)`に集約。
- **【新規】フォントファイル未取得時のステータスバー案内**: 依頼で「`:font 2`/`:font 3`実行時、対応するフォントファイルが無い場合はステータスバーで対処方法を明示した上でデフォルトフォントを使う」という要件があったが、従来の`IbmPlexMonoFont`（`:font 1`）はこの案内を一切出さず無言でフォールバックしていた（潜在的な既知のギャップだった）。今回`IbmPlexMonoFont`/`JetBrainsMonoFont`/`ComicMonoFont`の3クラスすべてに`isBundledFontLoaded()`（TTF実体を実際に読み込めたか、falseならMonospacedへフォールバック中）を追加し、`ModalEditor.applyFontCommand()`が`:font 1`/`:font 2`/`:font 3`選択後に`isBundledFontFileMissing(FontChoice)`で判定して、真の場合は`"font file not found for <フォント名>. Run scripts/setup.sh (or setup.bat) to download it. Using the default font for now."`という具体的な対処方法を含むステータスメッセージに差し替える。コマンド自体は失敗させない（`fontChoice`は正しく変更され、実際の描画は`renderGlyph`内部で既にMonospacedフォールバック済みのFontを使うため、案内表示の追加だけで機能面は変わらない）。`:font 1`にもこの案内を適用したのは、3フォントを一貫した挙動にする意図的な判断（依頼文は`:font 2`/`:font 3`のみ明示していたが、同じ仕組みを持つ`:font 1`だけ無言フォールバックのままにする理由がないため）。
- **`Tutorial.java`の`:font`セクションに`:font 2`/`:font 3`の説明行を追加**した。
- **セルサイズ独立性・等幅前提の整合性確認（実装前の調査で確認済み、コード変更なし）**: `xForCol()`等の列×セル幅計算は`MonoFont.renderGlyph()`が常に要求された`cellW×cellH`へ非等方向スケールして描画する設計（`IbmPlexMonoFont`と同じ）のため、JetBrains Mono・Comic Mono自体が完全な等幅グリフでなくても、セルグリッドは常に厳密に等幅で成立する。
- **グリフキャッシュキーの整合性確認（コード変更なし）**: `glyphCacheFg`/`glyphCacheBg`はcodePointのみをキーにしているが、`setFontChoice()`が呼び出しの都度必ず`invalidateGlyphCache()`を呼ぶため、フォント切替時に古いフォントのグリフが残ることはない（`uiGlyphCache`/`nonAsciiGlyphCache`も同様）。この設計は既存のPlex Mono導入時から成立済みで、今回の4択化でも変わらない。
- **`scripts/setup.sh`/`scripts/setup.bat`にJetBrains Mono・Comic Monoのダウンロード処理を追加**した。IBM Plex Monoの節（「4. IBM Plex Mono Regular (TTF) の取得」）と全く同じ構造（既存チェック→冪等スキップ→`curl`有無チェック→ダウンロード失敗時はwarningのみで非致命的→ライセンスファイルも同時取得）を「5. JetBrains Mono Regular (TTF)」「6. Comic Mono (TTF)」として追加した。`setup.bat`側は`windows-batch-and-subprocess`スキルのルール（ASCII専用・ifブロック内丸括弧禁止）を踏襲し、`:setup_fonts`→`:setup_fonts_jb`→`:setup_fonts_cm`→`:setup_done`とラベルをチェーンする形にした（いずれの段階で失敗・スキップしても次の段階へフォールスルーする）。
- **依頼文中の`script/setup.sh`という表記は、既存の`scripts/`（複数形）ディレクトリの表記ゆれと判断し、既存の`scripts/setup.sh`/`scripts/setup.bat`に追記する形にした**（新規に`script/`ディレクトリを作らなかった）。
- **テスト**: `test/dev/javatexteditor/ui/NewMonoFontTest.java`（新設・21テスト）。`JetBrainsMonoFont`/`ComicMonoFont`の`isSupported`/`renderGlyph`寸法/`descentPixels`の健全性、`isBundledFontLoaded()`が例外なく取得できること（このコンテナ環境は`scripts/setup.sh`未実行が既定状態のため、実際のネットワークアクセス無しに「ファイル未配置」のシナリオが自然にシミュレートされている。`setup.sh`実行済み環境ではtrueの分岐を検証する形に自動的に切り替わる）、MiscFixed→JetBrains Mono→Comic Monoと切り替えた際に同一セルサイズ・同一文字の描画結果が毎回変化する（古いフォントのグリフがキャッシュに残らない）ことを検証。`test/dev/javatexteditor/editor/FontColorCommandTest.java`に`:font 2`/`:font 3`選択・フォントファイル未取得時のガイダンスメッセージ検証（`scripts/setup.sh`/`setup.bat`という文言を含むことを確認）を追加（計24テスト）。`test/dev/javatexteditor/ui/FontCellSizeIndependenceTest.java`に4フォント間の独立性検証を追加（計26テスト）。全体テストスイートは既知のベースラインFAIL（`ScrollTest`のhalfPageUp系2件・`ModalEditorTest`1件・`FilerTest`1件）のみで回帰なし（115クラスPASS、3クラスFAILは全て既知）。
- **`setup.sh`/`setup.bat`自体の自動テスト化について**: 実際のネットワークアクセス（GitHubからのダウンロード）を伴うため、CI・自動テストの対象にはせず、既存の`scripts/setup.sh`（JDKソース取得）と同様に手動確認（開発者が実行してlib/fonts/配下にファイルが生成されることを目視確認）を推奨する。`isBundledFontLoaded()`を使ったユニットテスト（上記`NewMonoFontTest`）で「ファイルが存在する場合／しない場合のいずれでもクラッシュしない」というロジック面は自動テストでカバーしているため、ダウンロード処理自体（curlコマンドの成否）だけが手動確認の対象になる。

## 歩行アニメーションの割り当て削減（2026-08-07）

`WalkingPersonSprite.drawFrame()` は半透明ピクセルごとに `new Color(...)` を生成していた。
30fpsで永久に呼ばれる経路のため、エディタを放置しているだけでゴミが積み上がる主要因の一つに
なっていた。前景色ごとに1組だけ生成してキャッシュする形に変更済み。

**このスプライトの描画経路（拡大変換した `Graphics2D` に1ピクセル1回の `fillRect`）は
軽量化目的で変更してはならない。** 画像化キャッシュも矩形のまとめ描きも、実測でピクセル単位の
描画結果が変わることを確認している（理由と実測値は
`.claude/skills/gui-rendering-pipeline/SKILL.md`「アイドル時に描画経路がゴミを出し続けていた
問題の修正（2026-08-07）」を参照）。
