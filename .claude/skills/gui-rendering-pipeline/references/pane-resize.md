# v4: Ctrl+Alt+矢印によるペインリサイズ（✅ 実装済み）

`future-phases.md` の「v4 候補」に続く追加実装。`JSplitPane` によるウィンドウ分割（v3）が
複数ペイン・入れ子分割（`:split`/`:vsplit` による `PaneNode` ツリー）まで拡張された後、
「画面分割時に現在のペインの縦横幅をキーボードで伸縮したい」という要望に基づいて追加した。

## 決定済み仕様（ユーザー確認済み）

すべて `AskUserQuestion` でユーザーに選択してもらい確定した。

| 項目 | 決定内容 |
|---|---|
| 矢印キーの意味 | **「現在のアクティブペインを伸縮する」**固定。Right/Down = 常に現在ペインを拡大、Left/Up = 常に現在ペインを縮小。（tmux的な「境界を矢印方向へ動かす」方式は不採用。現在ペインが分割の左右/上下どちら側にいても、Right/Downを押せば必ず現在ペインが大きくなる、という直感的な意味づけを優先した） |
| 1回あたりの増減量 | 固定ピクセル数（`PANE_RESIZE_STEP_PX`）。キーを押しっぱなしにするとOSのキーリピートでKEY_PRESSEDが連続発火し、連続的に伸縮する（Ctrl+Shift+矢印のフォントセルサイズ変更と同じ操作感） |
| 入れ子分割時の対応分割の探し方 | アクティブペインの Swing コンポーネント親を辿り、**キーの方向に対応する orientation を持つ最初の祖先 `JSplitPane`** だけを調整する（Left/Right → `HORIZONTAL_SPLIT` を探す、Up/Down → `VERTICAL_SPLIT` を探す）。見つからなければ何もしない（例: 縦分割しかない画面で Left/Right を押しても無反応）。祖先を1つ見つけた時点で探索を止め、その1つだけを調整する（複数の同方向祖先を同時に調整することはしない） |
| 最小ペインサイズ | 固定ピクセル数（`PANE_RESIZE_MIN_PX`）でクランプ。分割の合計幅/高さから `dividerSize` と `PANE_RESIZE_MIN_PX` を引いた範囲に `dividerLocation` を収める |

## なぜ「アクティブペインの直接の親 `Split` ノード」ではなく Swing コンポーネント階層を辿るのか

`Main.java` の `PaneNode`/`Split`（`Leaf`/`Split` の sealed interface ツリー）は「どのリーフがどう分割されているか」という**構造**だけを保持しており、実際に画面に貼られている `JSplitPane` インスタンスへの参照は持たない。`buildComponent(PaneNode)` は呼ばれるたびに新しい `JSplitPane` を作り直す（`rebuildLayout` は `:split`/`:vsplit`/ペインを閉じる操作のたびに `frame.getContentPane().removeAll()` → `buildComponent(root)` で全部作り直す）。

リサイズは構造を変えない操作なので、`PaneNode` ツリーを介さず、**現在画面に実際に貼られている `JSplitPane` を `Component.getParent()` で直接辿る**方式を採用した。`buildComponent` は `Split` の子が `Leaf` の場合そのリーフの `EditorCanvas` を直接 `JSplitPane` の子として追加する（中間ラッパーpanelを挟まない）ため、`activeCanvas.getParent()` は必ず直近の `JSplitPane`（またはペインが1つだけなら `JFrame` のcontent pane）になる。これを利用してシンプルに親方向へ辿るだけで済み、`PaneNode` 側に新しいフィールドを追加する必要がない。

## 既知の制約（意図的にスコープ外としたもの）

- **リサイズ結果は `:split`/`:vsplit`/ペインを閉じる操作をまたいで保持されない**。これらの操作は `rebuildLayout` で `JSplitPane` を全部作り直すため、手動で調整した `dividerLocation` は失われ、デフォルト位置（`setResizeWeight(0.5)` ベースの初期配置）に戻る。もともと現在の実装は分割操作のたびに `dividerLocation` を明示的に保持する仕組みを持っていない（`Split` レコードに位置を保存していない）ため、これは今回の新規デグレードではなく既存設計の性質の延長として許容した。将来的に「分割してもリサイズ位置を保ちたい」という要望が出た場合は、`Split` レコードに `dividerLocation` を持たせ `buildComponent` で `sp.setDividerLocation(...)` するよう拡張する必要がある。
- **`Ctrl+Alt+矢印` はOS/ウィンドウマネージャのグローバルショートカットと衝突する場合がある**（例: Linux GNOMEの仮想デスクトップ切り替え、一部Windows環境のIntelグラフィックドライバによる画面回転）。この場合キーイベント自体がアプリケーションに届かず、アプリ側では対処不可能。ユーザーからの明示的な指定に基づきこのキー組み合わせを採用しており、対応不可のOS設定がある旨は既知の制約として残す。
- **モード非依存**: `Ctrl+Shift+矢印`（フォントセルサイズ変更）・`Ctrl+W`（ペインフォーカス切替）と同じ理由で、`KeymapRegistry` を経由せず `Main.java` のグローバル `KeyboardFocusManager` dispatcher で直接処理する。NORMAL/INSERT/COMMAND等どのモードでも動作する（テキスト編集操作ではなくウィンドウレイアウト操作のため）。

## 実装

### 純粋ロジックの分離（テスト容易性のため）

Swing コンポーネント階層に依存しない「新しい `dividerLocation` を計算するだけ」の部分を
`dev.javatexteditor.ui.PaneResizeCalculator`（新設）に分離した。`ProjectBuilder`/`MainClassFinder`
が「ロジック」と「`ProcessBuilder`起動などの配線」を分離しているのと同じ理由で、
`JSplitPane`・`JFrame` を実際に表示しなくても計算部分だけを自作テストハーネスで検証できるようにした。

```java
package dev.javatexteditor.ui;

public final class PaneResizeCalculator {
    private PaneResizeCalculator() {}

    /**
     * @param currentDividerLocation 現在のdividerLocation（ピクセル）
     * @param totalSpan JSplitPaneの合計幅（HORIZONTAL_SPLIT）または高さ（VERTICAL_SPLIT）
     * @param dividerSize JSplitPaneのdivider自体の太さ
     * @param isFirstChildActive アクティブペインがsp.getLeftComponent()側（横分割なら左、縦分割なら上）か
     * @param grow true=現在ペインを拡大（Right/Down）、false=縮小（Left/Up）
     * @param stepPx 1回の操作で動かすピクセル数
     * @param minPanePx 分割された各ペインが下回ってはいけない最小ピクセル数
     */
    public static int computeNewDividerLocation(
            int currentDividerLocation, int totalSpan, int dividerSize,
            boolean isFirstChildActive, boolean grow, int stepPx, int minPanePx) {
        int delta = (grow == isFirstChildActive) ? stepPx : -stepPx;
        int maxLoc = Math.max(minPanePx, totalSpan - dividerSize - minPanePx);
        return Math.clamp(currentDividerLocation + delta, minPanePx, maxLoc);
    }
}
```

`grow == isFirstChildActive` の真理値表:

| grow (Right/Down) | isFirstChildActive (左/上側が現在ペイン) | delta | 理由 |
|---|---|---|---|
| true | true | +stepPx | 左/上側を伸ばす＝dividerLocationを増やす |
| true | false | -stepPx | 右/下側を伸ばす＝dividerLocationを減らす |
| false | true | -stepPx | 左/上側を縮める＝dividerLocationを減らす |
| false | false | +stepPx | 右/下側を縮める＝dividerLocationを増やす |

### 配線（`Main.java`、テスト対象外・既知のギャップ）

```java
// KeyboardFocusManagerのdispatcher内、Ctrl+Shift+矢印(フォントセルサイズ変更)ブロックの直後
boolean alt = (e.getModifiersEx() & KeyEvent.ALT_DOWN_MASK) != 0;
if (ctrl && alt && !shift) {
    int kc = e.getKeyCode();
    if (kc == VK_LEFT || kc == VK_RIGHT || kc == VK_UP || kc == VK_DOWN) {
        resizeActivePane(active[0], kc);
        pressedHandled[0] = true; return true;
    }
}
```

```java
private static void resizeActivePane(Leaf active, int keyCode) {
    boolean horizontal = (keyCode == KeyEvent.VK_LEFT || keyCode == KeyEvent.VK_RIGHT);
    int neededOrientation = horizontal ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT;
    boolean grow = (keyCode == KeyEvent.VK_RIGHT || keyCode == KeyEvent.VK_DOWN);

    Component prev = active.canvas();
    Component cur  = prev.getParent();
    while (cur != null) {
        if (cur instanceof JSplitPane sp && sp.getOrientation() == neededOrientation) {
            boolean isFirstChildActive = (sp.getLeftComponent() == prev);
            int totalSpan = horizontal ? sp.getWidth() : sp.getHeight();
            int newLoc = PaneResizeCalculator.computeNewDividerLocation(
                sp.getDividerLocation(), totalSpan, sp.getDividerSize(),
                isFirstChildActive, grow, PANE_RESIZE_STEP_PX, PANE_RESIZE_MIN_PX);
            sp.setDividerLocation(newLoc);
            return;
        }
        prev = cur;
        cur = cur.getParent();
    }
    // 対応方向の分割祖先が見つからない場合は何もしない（単一ペイン・非対応方向のみの入れ子等）
}
```

### テスト

`test/dev/javatexteditor/ui/PaneResizeCalculatorTest.java`（純粋ロジックのみ）。
`Main.java` の `resizeActivePane` 自体は実際の `JFrame`/`JSplitPane` 表示・`KeyboardFocusManager`
配線に依存するため、F10/F11/F12（`⑫`openjdk-source-tracing・`⑳`telescope-pickerと同様）と
同じ理由で自動テスト対象外の既知のギャップとして残す。
