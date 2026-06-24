# 作業セッション記録・引き継ぎ書

最終更新: 2026-06-24 (v2スクロール対応追記)

---

## セッション全体の概要

プロジェクト初期セットアップから始まり、①バッファ実装の実機検証、⑤GUI描画v1の実装・検証、②モーダル編集エンジンの実装・検証まで完了。mainブランチにマージ済み。

---

## 実施作業の記録

### フェーズ1: プロジェクト骨格の構築

**ブランチ**: `claude/quirky-sagan-kzsz6i`

アップロードされた設計ドキュメントをリポジトリに配置した。

| 配置ファイル | 内容 |
|---|---|
| `CLAUDE.md` | プロジェクト方針・技術制約・ロードマップ |
| `docs/requirements.md` | 要件定義書（draft v1） |
| `.claude/skills/editor-buffer-architecture/SKILL.md` | ピーステーブル設計書 |
| `.claude/skills/editor-buffer-architecture/references/piece-table-delete-and-undo.md` | 削除・アンドゥ設計 |

---

### フェーズ2: ① editor-buffer-architecture 実機検証

**対象**: `src/PieceTableTest.java`（当時はパッケージなし・単一ファイル）

`javac`でコンパイル・実行し、SKILL.md記載の8ケースをすべてパス。コード修正は不要だった。

```
PASS: 8 / 8  (FAIL: 0)
```

**確認ケース一覧:**
- 末尾挿入 / 先頭挿入 / 中間挿入（ピース分割）
- 複数回挿入後の中間挿入
- 末尾範囲削除 / 複数ピースをまたぐ全削除 / ピース内部の部分削除
- 空文書への挿入

---

### フェーズ3: ⑤ gui-rendering-pipeline v1 実装

**対象スキル**: `.claude/skills/gui-rendering-pipeline/SKILL.md`

#### プロジェクト構成の整備

`dev.vimacs` パッケージ構成に移行し、全ファイルを再編した。

```
src/dev/vimacs/
├── Main.java
├── buffer/
│   ├── Piece.java          ← PieceTableTest.javaから分離
│   └── PieceTable.java     ← PieceTableTest.javaから分離
└── ui/
    ├── Theme.java          ← 新規実装
    └── EditorCanvas.java   ← 新規実装

test/dev/vimacs/
├── buffer/
│   └── PieceTableTest.java ← test/に移動・パッケージ追加
└── ui/
    ├── EditorCanvasTest.java  ← 新規実装（BufferedImageピクセル検証）
    └── VisualPreview.java     ← 新規実装（PNG出力・目視確認用）

scripts/
├── build.sh / build.bat
├── test.sh  / test.bat
└── run.sh   / run.bat
```

#### SKILL.md初稿から判明した誤り（実装時に修正済み）

| # | 場所 | 誤り | 修正内容 |
|---|---|---|---|
| 1 | `drawStatusLine`の引数 | `FontMetrics fm`が本体内で未使用 | パラメータを削除 |
| 2 | `drawCursor`のカーソル文字再描画 | `line.charAt(cursorCol)` | `line.codePointAt(cursorCol)` + `Character.toChars`に修正（サロゲートペア対応） |
| 3 | `Theme`フィールドのアクセス修飾子 | `final`のみ | `public final`に変更 |

#### テスト結果

```
=== dev.vimacs.buffer.PieceTableTest ===   PASS: 8 / 8
=== dev.vimacs.ui.EditorCanvasTest ===     PASS: 5 / 5
```

---

### フェーズ4: ② modal-editing-engine v1 実装

**ブランチ**: `claude/hopeful-noether-g925gk`（mainにマージ済み）

#### 新規作成ファイル

| ファイル | 内容 |
|---|---|
| `src/dev/vimacs/editor/ModalEditor.java` | モード・カーソル管理、PieceTable/EditorCanvas橋渡し |
| `test/dev/vimacs/editor/ModalEditorTest.java` | 13テスト群・46ケース全PASS |

#### 実装したキーバインド

**NORMALモード:**
- `h`/`l` → 左右移動（行端でクランプ）
- `j`/`k` → 上下移動（新行長さにcolクランプ）
- `i` → INSERTモードへ（カーソル前）
- `a` → INSERTモードへ（カーソル後・col+1）
- `o` → 現在行末に`\n`挿入、次行冒頭でINSERTへ

**INSERTモード:**
- 通常文字 → `PieceTable.insert()`
- `Backspace` → `PieceTable.delete()`（行頭では行結合）
- `Enter` → 改行挿入
- `Escape` → NORMALモード復帰（colをlen-1にクランプ）
- `Ctrl+F`/`B` → 左右移動（Emacs式）
- `Ctrl+N`/`P` → 下上移動（Emacs式）

#### キー入力の接続方法

`canvas.addKeyListener()`ではなく`KeyboardFocusManager.addKeyEventDispatcher()`を採用。
フォーカス状態に依存せず全キーを確実に捕捉するため。

#### 修正済みバグ

| バグ | 原因 | 修正 |
|---|---|---|
| キー入力が一切効かない | `Main.java`がModalEditorを使わずEditorCanvasを直接操作・KeyListener未設定 | ModalEditor+KeyboardFocusManagerで接続 |
| 全角文字の行でカーソル位置がずれる | `drawCursor`が`cursorCol * charWidth`（全文字1セル扱い） | `xForCol()`でセル幅を積算・NORMALブロック幅も全角対応 |

#### テスト結果

```
=== dev.vimacs.buffer.PieceTableTest ===   PASS: 8 / 8
=== dev.vimacs.editor.ModalEditorTest ===  PASS: 46 / 46
=== dev.vimacs.ui.EditorCanvasTest ===     PASS: 5 / 5
```

---

---

### フェーズ5: ⑤ gui-rendering-pipeline v2 実装（スクロール対応）

**ブランチ**: `claude/gui-rendering-scroll-k24jsa`

#### 実施作業

| 対象ファイル | 変更内容 |
|---|---|
| `src/dev/vimacs/buffer/PieceTable.java` | `getTextInRange(int, int)`・`offsetOfLine(int)` を追加 |
| `src/dev/vimacs/ui/EditorCanvas.java` | `scrollRow`/`cachedLineHeight` フィールド追加、`ensureCursorVisible()`・`computeVisibleRows()` 追加、`paintComponent` と `drawCursor` をスクロール座標対応に変更 |
| `src/dev/vimacs/editor/ModalEditor.java` | `syncCanvas()` に `canvas.ensureCursorVisible(cursorRow)` 呼び出しを追加 |
| `src/dev/vimacs/Main.java` | デモテキストを110行以上に変更（スクロール目視確認用） |
| `test/dev/vimacs/buffer/PieceTableTest.java` | `getTextInRange`×4ケース、`offsetOfLine`×3ケースを追加（計15テスト） |
| `test/dev/vimacs/ui/EditorCanvasTest.java` | `scrollRow`初期値・`ensureCursorVisible`下方追従・上方追従の3テストを追加（計8テスト） |

#### 設計判断

| 判断 | 理由 |
|---|---|
| `EditorCanvas` が `scrollRow` を持ち `ensureCursorVisible()` で自己管理する | `ModalEditor` がウィンドウ高さを知らなくて済む。ウィンドウ分割（v3）でも各ペインが独立して管理できる |
| `cachedLineHeight = 20` でデフォルト近似し paint 時に更新 | `ensureCursorVisible` は `Graphics` 不要のまま行数計算できる。初回 paint 前でも0除算が発生しない |
| `getText()` で全文を渡す方式は維持（`getTextInRange` は将来最適化用） | 現フェーズではボトルネックにならず実装をシンプルに保てる。巨大ファイルで問題が生じた場合は `syncCanvas()` で `getTextInRange` を使うよう切り替える |

#### テスト結果

```
=== dev.vimacs.buffer.PieceTableTest ===   PASS: 15 / 15  (FAIL: 0)
=== dev.vimacs.editor.ModalEditorTest ===  PASS: 46 / 46  (FAIL: 0)
=== dev.vimacs.ui.EditorCanvasTest ===     PASS: 8 / 8    (FAIL: 0)
```

---

## 現在の状態（2026-06-24時点、v2スクロール完了後）

### ロードマップ更新後の状態

| # | Skill名 | 状態 |
|---|---|---|
| ① | `editor-buffer-architecture` | ✅ 実機検証済み（15/15テスト成功・getTextInRange/offsetOfLine追加済み） |
| ② | `modal-editing-engine` | ✅ v1実機検証済み（46/46テスト成功・動作確認済み） |
| ③ | `extension-language-runtime` | 未着手 |
| ④ | `keymap-conflict-resolution` | 未着手（②完了により着手可能） |
| ⑤ | `gui-rendering-pipeline` | ✅ v2実機検証済み（8/8テスト成功）スクロール対応完了 |
| ⑥〜⑭ | その他 | 未着手 |

### ブランチ状態

```
main  ← マージ済み（7f85ee9）
└── claude/gui-rendering-scroll-k24jsa（フィーチャーブランチ・作業完了・プッシュ済み）
```

### 主要ファイルの役割（現時点）

| ファイル | 役割 |
|---|---|
| `src/dev/vimacs/Main.java` | JFrame生成・ModalEditor+KeyboardFocusManager接続（デモ110行） |
| `src/dev/vimacs/buffer/Piece.java` | ピース（record: source/start/length） |
| `src/dev/vimacs/buffer/PieceTable.java` | バッファ本体（insert/delete/getText/getTextInRange/offsetOfLine/length） |
| `src/dev/vimacs/editor/ModalEditor.java` | モード・カーソル管理、キー処理、ensureCursorVisible呼び出し |
| `src/dev/vimacs/ui/Theme.java` | LIGHT_MODE / DARK_MODE 配色定数 |
| `src/dev/vimacs/ui/EditorCanvas.java` | Swing描画（テキスト・カーソル・ステータス行）＋スクロール管理 |

---

## 次フェーズへの引き継ぎ事項

### 推奨次作業の優先順位

| 優先 | Skill | 理由 |
|---|---|---|
| 1位 | ② `modal-editing-engine` v2（ファイル開閉・コマンドラインモード） | `:w`保存・`:e`ファイル開閉がないと実用できない |
| 2位 | ④ `keymap-conflict-resolution` | ②が安定してから |

### ② v2（ファイル開閉・コマンドラインモード）着手時の設計要点

- NORMALモードで `:` を押すとコマンドラインモードへ移行する仕組みが必要
- `:w` → `Files.writeString()` でファイル書き込み
- `:e <path>` → `Files.readString()` で読み込み・PieceTable再初期化
- コマンドライン入力欄をステータス行に描画する（EditorCanvasへの追加）
- `ModalEditor`のモード定義を `enum Mode { NORMAL, INSERT, COMMAND }` に拡張する

### 既知の制限（未解決）

| 制限 | 詳細 | 対応フェーズ |
|---|---|---|
| ファイル開閉なし | 起動時のハードコードテキストしか編集できない | ② v2 |
| Windows改行`\r\n`未対応 | `split("\n")`では行末に`\r`が残る | ファイル読込時に正規化（② v2） |
| VISUALモードなし | 範囲選択・ヤンク・ペーストが未実装 | ② v2以降 |
| アンドゥ/リドゥなし | PieceTableのスナップショット方式は設計済み（SKILL.md参照）だが未実装 | ② v2以降 |
| `getTextInRange` を描画に未使用 | 現状 `getText()` で全文をEditorCanvasに渡しており、`getTextInRange` は将来最適化用として実装のみ | 巨大ファイルで速度問題が出た場合に対応 |

---

## 技術制約の再確認（厳守）

次フェーズ着手時にも以下を守ること:

- **言語**: Java 21 (LTS)のみ
- **依存ライブラリ**: 一切使用しない（Java SE標準APIのみ）
- **ビルドツール**: `javac`直接呼び出しのみ（Maven/Gradle禁止）
- **テスト**: JUnit不使用・`main`メソッド形式のテストハーネスのみ
- 何かを実装する前に`.claude/skills/`配下の関連SKILL.mdを必ず確認すること
- 新たな設計判断はCLAUDE.mdまたは該当SKILL.mdに記録すること
