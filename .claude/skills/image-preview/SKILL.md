---
name: image-preview
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、画像ファイル（png/jpg/jpeg/gif/bmp）を開いた際の全画面プレビュー（Mode.IMAGE、アスペクト比維持の自動フィット・手動ズーム・パン）を設計・実装・修正する際に使用する。「画像を表示したい」「isImageFileの判定ロジックを直したい」「ズーム・パンの挙動を変えたい」「画像読み込みが失敗したときのフォールバック」といった相談、またdev.javatexteditor.ui.ImageRendererやModalEditorのenterImageMode/Mode.IMAGE周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# 画像プレビュー（Mode.IMAGE）

## このスキルが解決すること

`.png`/`.jpg`/`.jpeg`/`.gif`/`.bmp` を `:e`・telescope・`\f`/`\g`・`gr` など既存のファイルを開く
あらゆる経路で開いた際に、既存の「バイナリはhexdump」扱いにせず、アスペクト比を維持したまま
全画面プレビュー表示する。開いた直後は自動フィット、`+`/`-`で手動ズーム、`0`で自動フィットへ
リセット、`hjkl`/矢印キーでパンできる。仕様の確定版は
`docs/archive/image-preview-spec-2026-07-29.md`（2026-07-29確定）。

## 実装アーキテクチャ

### `Main.isImageFile(Path)`（拡張子ホワイトリスト + `ImageIO.read()`成否の二段判定）

拡張子ホワイトリスト `Set.of("png","jpg","jpeg","gif","bmp")` で事前フィルタし（A1）、通過した
ものだけ実際に `ImageIO.read()` を試して成否で最終判定する（A3、マジックナンバー確認はしない）。
`Main` クラスは元々 `EditorApplication.launch()` を呼ぶだけの薄いエントリポイントだが、仕様書で
明示的に `Main` への配置が指定されているためそのまま踏襲した。

### `dev.javatexteditor.ui.ImageRenderer`（Swing非依存の拡縮・パン計算 + 描画委譲）

- `computeFitSize(imgW, imgH, viewportW, viewportH)`: 幅・高さそれぞれの倍率のうち小さい方を
  採用するアスペクト比維持の自動フィット計算（B1）。縮小・拡大どちらの方向にも対応する
  （小さい画像はビューポートに合わせて拡大表示される）。
- `computeZoomSize(imgW, imgH, zoom)`: 手動ズーム倍率を単純に適用したサイズ計算。
- `zoomIn`/`zoomOut`: `ZOOM_STEP = 1.25` 倍ずつ、`MIN_ZOOM=0.1`〜`MAX_ZOOM=10.0` でクランプする。
- `clampOffset(offset, contentSize, viewportSize)`: パンオフセットを画像がビューポートから完全に
  はみ出さない範囲にクランプする（`maxOffset = max(0, contentSize - viewportSize)`）。画像が
  ビューポートより小さい場合は常に0を返す（この場合は`paint()`側で中央寄せする）。
- `paint(...)`: 上記の計算結果を使い実際に `drawImage` する。`RenderingHints.KEY_INTERPOLATION =
  VALUE_INTERPOLATION_BILINEAR` を設定する（B1）。`EditorCanvas.paintContent()` からは数行の
  分岐で呼ばれるだけで、実処理はすべてここに委譲する（EditorCanvasを薄く保つ、EditorCanvas
  リファクタリング第6〜7弾の方針を踏襲）。
- `paintCenteredMessage`/`LOADING_TEXT`: 読み込み中インジケーター（「読み込み中...」）の描画。

拡縮・クランプの計算部分（`computeFitSize`/`computeZoomSize`/`zoomIn`/`zoomOut`/`clampOffset`）は
Swing非依存の静的メソッドとして切り出してあり、`ImageRendererTest`から直接検証できる。

### `EditorCanvas`側の結線（C2方式、CardLayout不採用）

`EditorCanvas`は単一のJPanelのまま、内部に画像描画モードのフィールド（`imageBuffer`/
`imageViewActive`/`imageAutoFit`/`imageZoom`/`imageLoading`）を持つ。`setImageView(...)`で
一括更新し、`paintContent()`の冒頭（スプラッシュ分岐の直後、通常テキスト描画より前）に
`if (imageViewActive) { ... return; }`という薄い分岐を置くだけで、実処理は`ImageRenderer`に
委譲する。`PaneTree`/`PaneManager`は無改修（仕様書どおり）。

**パン(F3)は既存のテキストスクロールと全く同じ`scrollRow`/`scrollCol`フィールドをそのまま
セル単位のパンオフセットとして流用する**（新規フィールドを追加しない）。`JScrollPane`は
不採用。`ImageRenderer.paint()`が`scrollCol*charWidth`/`scrollRow*lineHeight`をpxオフセットへ
変換し`clampOffset`でクランプする。

### `ModalEditor`側の結線

- **`Mode.IMAGE`を新規モードとして追加**した（`Mode.BINARY`と同じ「専用の`process*Key`メソッドを
  持つ独立モード」パターン。`KeymapRegistry`は経由しない。理由: `+`/`-`/`0`はNORMALモードの既存
  キー（`0`は行頭移動、等）と衝突するため、classfile/markdownビューアのような「NORMALモードのまま
  参照一致で追加コマンドだけ足す」方式ではなく、Mode.BINARYと同じ「モードごと分離する」方式を
  選んだ）。
- **`imageModeOwner`（`UndoablePieceTable`）**: `binaryModeOwner`/`classFileBufferOwner`/
  `markdownViewOwner`と全く同じ「参照一致による自動失効」パターン。`buffer`は約25箇所で
  再代入されるため、`:grep`やtelescope等で別バッファへ切り替えると自動的に`imageModeOwner`との
  参照が不一致になる。
- **`imageBuffer`（`BufferedImage`）**: 読み込み済みの画像本体。モード終了時（`imageModeOwner`が
  指すバッファから離れる、つまり新しい画像を開く直前・別のファイルを開く直前の両方）に
  `releaseImageBufferIfActive()`が`imageBuffer.flush()` → `imageBuffer = null`を行う。
- **非同期読み込み（A2）**: `enterImageMode(Path, String)`が`SwingWorker<BufferedImage, Void>`を
  起動する。`imageLoadWorker`に進行中ワーカーの参照を1つだけ保持し、新規読み込み開始時
  （`releaseImageBufferIfActive()`経由）に前ワーカーへ`cancel(true)`する。`done()`側では
  `isCancelled()`を最初にチェックし、続けて`imageModeOwner != owner`（=別バッファへ切り替え
  済み）ならそのまま何もしない、という2段の競合ガードを行う。
- **読み込み中表示**: `enterImageMode()`はプレースホルダの疑似バッファ（`"*image* " + 表示名 +
  "\n\n読み込み中...\n"`）を即座に表示し、`imageLoadPending = true`にする（`currentFilePath`は
  classfile/markdownビューアと同じくnull、保存不可の読み取り専用プレビュー）。読み込み完了後は
  `imageBuffer`と`statusMessage`（幅x高さ）を更新するだけで、プレースホルダのテキスト自体は
  差し替えない（実際に画面に出るのは`EditorCanvas`側の画像描画分岐であり、疑似バッファの
  テキストは`Mode.IMAGE`中はユーザーの目に触れない）。
- **読み込み失敗時のフォールバック（E3、spec§5）**: `applyImageLoadFailure(Path, owner)`が
  `readFileContentForBuffer()`による通常のファイルオープン処理へ素通りする。実際の画像バイナリは
  ほぼ確実にUTF-8として不正なため、`BinaryFileDetector`により自動的に`Mode.BINARY`
  （既存の`:b`と全く同じhexdumpプレビュー）へ入る。文言は「この画像を表示できません（:b で
  バイナリ表示）」で固定し、破損ファイルと非対応フォーマットを区別しない（spec§5どおり）。
- **ファイルを開く4箇所すべて**（`openBufferEntry`/`jumpToFileNameResult`/`loadFromFile`/
  `jumpToGrepResult`。classfile-viewerスキルの`readFileContentForBuffer`呼び出し一覧と同じ4箇所）
  で`readFileContentForBuffer()`を呼ぶ前に`releaseImageBufferIfActive()`→`Main.isImageFile(path)`
  判定→真なら`enterImageMode()`へ分岐、を追加した。

## 意図的な実装判断（仕様書に明記のない部分の判断）

| 判断 | 理由 |
|---|---|
| 手動ズームキーに`+`/`-`/`0`を採用（`+`は`=`も許容） | `keymap-conflict-resolution`スキルで既存バインドを確認したところ、NORMALモードでは`+`/`-`/`0`はいずれも別用途に割り当て済みだが、これらは**Mode.IMAGE専用の`processImageKey`が完全に独立して処理する**ため衝突しない（NORMALモードの`0`=行頭移動等は一切参照されない）。`=`も`+`の別名として許容したのは、多くのアプリでズームインに`Ctrl+=`（Shiftなし配列で`+`と同じ物理キー）が使われる慣習に合わせた軽微な配慮。 |
| パンキーに`hjkl`+矢印キーを採用、ステップ幅は3セル固定 | Vimの基本移動キーをそのまま踏襲。ステップ幅は`IMAGE_PAN_STEP_CELLS = 3`で固定し、`Ctrl+D`/`Ctrl+U`等のページスクロール相当のキーは今回スコープ外とした（1ステップの粒度で十分実用になるため）。 |
| 読み込み失敗時、`:b`を手動で押す前提ではなく`Mode.BINARY`へ自動遷移させた | 仕様書は「既存`:b`（Mode.BINARY）トグルで手動切替可能」と書いているが、実装では`readFileContentForBuffer()`の既存フォールバック（`BinaryFileDetector`）にそのまま素通りさせているため、実際の画像バイナリは自動的にMode.BINARYへ入る。これは仕様の「手動切替」の字面とは異なる判断だが、`:b`が最終的に到達する状態（Mode.BINARY、同じhexdump描画）と完全に同一であり、既存の`readFileContentForBuffer`の分岐をそのまま再利用する方が新規の「保存先エラー」パターンを作らずに済むため、この形にした。壊れた`.class`ファイルが同じ関数で自動的にhexdumpへフォールバックする既存挙動（classfile-viewerスキル参照）と一貫性がある。 |
| `Mode.IMAGE`を新設し`KeymapRegistry`を経由しない | classfile/markdownビューアは「NORMALモードのまま`buffer`参照一致で追加コマンドを差し込む」方式だが、画像プレビューは`+`/`-`/`0`/`hjkl`という**NORMALモードの既存キーと文字面が重複するキー**を必要とするため、同じ方式は使えない。`Mode.BINARY`（`processBinaryKey`が独立してhjkl等を再解釈する）と全く同じ「モードごと分離」パターンを踏襲した。 |
| テスト用に`simulateImageLoadFailureForTest(Path)`をpackage-private公開 | `SwingWorker`の`doInBackground()`は別スレッドで実行されるため、「`isImageFile()`の事前チェック後、非同期読み込みが完了する前にファイルが壊れる」という失敗経路をテストで安定的にレースさせることはできない（`editor-testing-strategy`スキルの方針どおり、GUIスレッド依存の厳密な検証は避けた）。かわりに`applyImageLoadFailure()`を直接呼び出せるテスト専用フックを追加し、フォールバック処理そのものを決定的に検証している。 |
| 非同期読み込み成功パスは`SwingUtilities.invokeAndWait`でEventQueueを手動ポンプして待つ | `ImagePreviewTest`はヘッドレス環境で実行されるが、`SwingWorker`は可視コンポーネントを必要としないため`EventQueue`自体は動作する。`done()`が`invokeLater`でキューされるのを待つため、`isImageLoadPending()`がfalseになるかタイムアウトするまで`Thread.sleep`+`SwingUtilities.invokeAndWait(() -> {})`を繰り返すポーリングを行っている。 |

## 意図的にスコープ外とした点

- **画像の編集（回転・トリミング・リサイズ保存等）は一切実装していない**。読み取り専用プレビュー
  であり、`currentFilePath`は常にnullのため`:w`は既存の「no file name」エラーに自然に
  フォールバックする（classfile/markdownビューアと同じ設計）。
- **アニメーションGIFの複数フレーム再生はしない**。`ImageIO.read()`は最初のフレームのみを
  `BufferedImage`として返す（標準APIの挙動そのまま、追加のフレーム分解ロジックは実装していない）。
- **EXIF回転情報の自動補正はしない**。`ImageIO.read()`の結果をそのままpx単位で扱う。
- **サムネイルキャッシュ・先読みはしない**。telescope/FILERでの画像一覧プレビュー等への拡張は
  今回のスコープ外（現状は`:e`等でファイルを直接開いたときのみ動作する）。

## テスト

- `test/dev/javatexteditor/ui/ImageRendererTest.java`（13テスト、自作mainハーネス方式）:
  `computeFitSize`（横長/縦長画像・拡大/縮小両対応・境界値・不正入力）・`computeZoomSize`・
  `zoomIn`/`zoomOut`のクランプ・`clampOffset`（範囲内/負値/上限超過/ビューポートより小さい画像）
  を検証。Swing非依存のため純粋ロジックとして直接テストできる。
- `test/dev/javatexteditor/editor/ImagePreviewTest.java`（25テスト）: `Main.isImageFile()`の
  拡張子+`ImageIO.read()`二段判定（正常png/非画像拡張子/拡張子だけ画像の壊れたファイル/存在しない
  ファイル）、`:e`で画像を開くと即座にMode.IMAGEへ入ること、非同期読み込み完了後に正しい
  `BufferedImage`（幅・高さ一致）が反映されること、別バッファへ切り替えると`imageModeOwner`の
  参照一致による自動失効でMode.IMAGEを抜けること、`+`/`-`キーによる手動ズームの自動フィット解除
  とMIN/MAX_ZOOMでのクランプ、`0`キーによる自動フィットへのリセット、読み込み失敗フォールバック
  （エラーメッセージ表示・Mode.IMAGEからの離脱）を検証。`SwingWorker`の実タイミング依存部分は
  上記「意図的な実装判断」表のとおりポーリング待機またはテスト専用フックで決定的に検証している。
