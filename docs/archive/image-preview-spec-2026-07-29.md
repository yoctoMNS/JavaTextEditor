# 画像表示機能 仕様書（2026-07-29 確定）

## 1. 画像ファイルかどうかの判定
- 拡張子ホワイトリスト `Set.of("png","jpg","jpeg","gif","bmp")` で事前フィルタ（A1）
- 通過したファイルのみ `ImageIO.read()` を試行し、成否で最終判定（A3）。マジックナンバー確認は行わない
- `Main` に専用メソッド `isImageFile(Path)` として切り出す

## 2. 表示コンポーネントの切り替え方式
- C2: `EditorCanvas` は単一のまま、内部に画像描画モードを持つ（CardLayout不採用）
- `PaneTree`/`PaneManager` は無改修
- 状態は `ModalEditor` 側に保持：
  - `imageModeOwner`（`UndoablePieceTable`、既存 `classFileBufferOwner`/`markdownViewOwner` と同じ参照一致パターン）
  - `imageBuffer`（`BufferedImage`）
- 描画ロジックは `EditorCanvas.paintComponent()` に数行の分岐のみ置き、実処理は `ui.ImageRenderer` ヘルパークラスに委譲

## 3. アスペクト比維持の拡縮ロジック
- タイミング: A1、`paintComponent()` 毎にリアルタイム再計算
- 描画方法: B1、`drawImage(img,x,y,w,h,observer)` に計算済みサイズを渡す。`RenderingHints.KEY_INTERPOLATION = VALUE_INTERPOLATION_BILINEAR` を設定
- 拡大縮小: 自動フィットを基本としつつ手動ズームを追加
  - 開いた直後は自動フィット（縮小・拡大どちらも自動計算）
  - `+`/`-` 相当のキーで手動ズーム、`0`相当のキーで自動フィットにリセット
  - 手動ズーム中はリサイズによる自動再フィットを一時停止

## 4. 大きな画像ファイルへの対応
- 読み込み: A2、`SwingWorker<BufferedImage, Void>` で非同期読み込み
- 競合制御: `ModalEditor` に進行中ワーカー参照を1つ保持。新規読み込み時に前ワーカーへ `cancel(true)`、`done()` 側で `isCancelled()` を再チェックしてから反映
- ローディング表示: 画像モード内に簡易な読み込み中表示
- 解放: モード終了時（`imageModeOwner` クリアと同じ箇所）に `imageBuffer.flush()` → `imageBuffer = null`

## 5. 読み込み失敗時のフォールバック
- E3: エラーメッセージ表示を基本としつつ、既存 `:b`（Mode.BINARY）トグルで手動切替可能
- UI: ステータスライン風の1行表示（既存の操作結果表示経路を再利用）
- 文言例: 「この画像を表示できません（:b でバイナリ表示）」
- 破損ファイルと非対応フォーマットで文言は区別しない

## 6. スクロール・原寸表示
- F3: 自前スクロール（オフセット保持＋再描画）。既存テキストスクロール実装パターンを流用、`JScrollPane` は不採用
- スクロールキー: 既存テキストスクロールと同一キーを流用
- ズーム状態でのパン時、画像がフレームから完全に外れないようオフセットをクランプ
