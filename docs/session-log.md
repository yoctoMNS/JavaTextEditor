# 作業セッション記録・引き継ぎ書

最終更新: 2026-06-25 (② v4 VISUALモード・ヤンク/ペースト実装完了)

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

---

### フェーズ6: ② modal-editing-engine v2 実装（コマンドラインモード・ファイル保存/開閉）

**ブランチ**: `claude/command-mode-v2-1kcesl`

#### 実施作業

| 対象ファイル | 変更内容 |
|---|---|
| `src/dev/vimacs/editor/ModalEditor.java` | `boolean insertMode` → `enum Mode { NORMAL, INSERT, COMMAND }` に置き換え。コマンドバッファ・ファイルパス・ステータスメッセージ・exitCallbackフィールド追加。`processCommandKey`・`executeCommand`・`saveToFile`・`loadFromFile` 実装。3引数コンストラクタ追加 |
| `src/dev/vimacs/ui/EditorCanvas.java` | `commandLineText` フィールド・`setCommandLineText()` メソッド追加。`drawStatusLine` をコマンドライン表示対応に変更 |
| `src/dev/vimacs/Main.java` | `args[0]` でファイルパス受け取り。`Files.readString` で起動時ファイル読み込み。3引数コンストラクタ使用 |
| `test/dev/vimacs/editor/ModalEditorTest.java` | COMMMANDモードのテスト群（基本遷移・:w保存・:e開閉・エラーケース・:q）を追加（14ケース増加） |

#### 実装したコマンド

| コマンド | 動作 |
|---|---|
| `:` | NORMALからCOMMANDモードへ移行 |
| `:w` | currentFilePath へ上書き保存（未設定時はエラー） |
| `:w <path>` | 指定パスへ保存・currentFilePath 更新 |
| `:e <path>` | ファイル読み込み・PieceTable 再初期化・カーソル(0,0)・`\r\n`→`\n`正規化 |
| `:q` | exitCallback.run()（テスト時は差し替え可能） |
| `:wq` | 保存後に終了 |
| 未定義 | `statusMessage` にエラー表示 |

#### 設計判断

| 判断 | 理由 |
|---|---|
| `buffer` フィールドを `final` から非 `final` に変更 | `:e` でファイルを開く際に `new PieceTable(content)` で差し替える必要があるため。`reinitialize()` メソッドを PieceTable に追加するより侵襲が少ない |
| `exitCallback` を `Runnable` フィールドで注入可能にした | `System.exit(0)` を直接呼ぶと JVM ごとテストプロセスが終了するため。テストでは `setExitCallback()` で差し替えて検証する |
| COMMAND モード中のステータス行は `commandLineText` フィールドを通じて EditorCanvas に渡す | ModalEditor が EditorCanvas の描画詳細を知らなくて済む。既存の `drawStatusLine` パターンを最小変更で拡張できる |
| statusMessage は COMMAND/INSERT 移行時にクリアする | Vim の挙動に準拠。次のコマンド実行まで直前の結果（保存パスなど）を表示し続ける |

#### テスト結果

```
=== dev.vimacs.buffer.PieceTableTest ===   PASS: 15 / 15  (FAIL: 0)
=== dev.vimacs.editor.ModalEditorTest ===  PASS: 60 / 60  (FAIL: 0)  ← 46→60
=== dev.vimacs.ui.EditorCanvasTest ===     PASS: 8 / 8    (FAIL: 0)
```

---

---

### フェーズ7: ② modal-editing-engine v3 実装（アンドゥ/リドゥ）

**ブランチ**: `claude/undo-redo-v3-k8xmqp`

#### 実施作業

| 対象ファイル | 変更内容 |
|---|---|
| `src/dev/vimacs/buffer/PieceTable.java` | `protected getPieces()` / `protected restorePieces()` を追加（サブクラス用アクセサ） |
| `src/dev/vimacs/buffer/UndoablePieceTable.java` | 新規作成。`PieceTable` を継承し、`undoStack` / `redoStack` でスナップショット管理。`insert` / `delete` を `@Override` して編集前に自動スナップショット取得 |
| `src/dev/vimacs/editor/ModalEditor.java` | `buffer` フィールドを `PieceTable` → `UndoablePieceTable` に昇格。全コンストラクタ・`loadFromFile()` も `UndoablePieceTable` に変更。`processKey()` 冒頭で `Ctrl+R` → `redo()` を先捌き。`processNormalKey()` に `u` → `undo()` 追加。`clampCursorAfterUndoRedo()` 新規追加 |
| `test/dev/vimacs/buffer/UndoablePieceTableTest.java` | 新規作成（11ケース・mainメソッド形式） |
| `test/dev/vimacs/editor/ModalEditorTest.java` | `testUndoKey()` / `testRedoKey()` を追加（5ケース増加・60→65） |

#### 実装したキーバインド

| モード | キー | 動作 |
|---|---|---|
| NORMAL | `u` | `buffer.undo()` を呼び、カーソルをクランプ |
| NORMAL | `Ctrl+R` | `buffer.redo()` を呼び、カーソルをクランプ |

#### 設計判断

| 判断 | 理由 |
|---|---|
| `UndoablePieceTable` を別クラスとして `PieceTable` から継承 | `PieceTable` 本体を変更せず、アンドゥ機能の有無を型で区別できる。既存テストへの影響を最小化できる |
| `snapshotBeforeEdit()` を `insert`/`delete` の `@Override` 内で呼ぶ | 呼び出し元（`ModalEditor`）を修正せずに透過的にスナップショットを取得できる |
| `processKey()` の冒頭で `Ctrl+R` を先捌き | `processNormalKey(char keyChar)` にはキャラクタのみが渡り、`Ctrl`修飾子が取れないため |
| `clampCursorAfterUndoRedo()` を追加 | アンドゥ/リドゥでテキストが短くなった場合にカーソルが範囲外になる問題を防ぐ |
| アンドゥ単位のグループ化は今回スコープ外 | `insert`/`delete` 呼び出し1回が1アンドゥ単位。複合コマンドのグループ化は ② v4以降で対応 |

#### テスト結果

```
=== dev.vimacs.buffer.PieceTableTest ===         PASS: 15 / 15  (FAIL: 0)
=== dev.vimacs.buffer.UndoablePieceTableTest ===  PASS: 11 / 11  (FAIL: 0)  ← 新規
=== dev.vimacs.editor.ModalEditorTest ===         PASS: 65 / 65  (FAIL: 0)  ← 60→65
=== dev.vimacs.ui.EditorCanvasTest ===            PASS: 8 / 8    (FAIL: 0)
```

---

## 現在の状態（2026-06-24時点、② v3 アンドゥ/リドゥ完了後）

### ロードマップ更新後の状態

| # | Skill名 | 状態 |
|---|---|---|
| ① | `editor-buffer-architecture` | ✅ 実機検証済み（15/15テスト成功・getTextInRange/offsetOfLine/getPieces/restorePieces追加済み） |
| ② | `modal-editing-engine` | ✅ v3完了（65/65テスト成功・アンドゥ/リドゥ実装済み） |
| ③ | `extension-language-runtime` | 未着手 |
| ④ | `keymap-conflict-resolution` | 未着手（②完了により着手可能） |
| ⑤ | `gui-rendering-pipeline` | ✅ v2実機検証済み（8/8テスト成功）スクロール対応完了 |
| ⑥〜⑭ | その他 | 未着手 |

### ブランチ状態

```
main  ← 4be1317
└── claude/modal-editing-v4-visual-8dvxez（フィーチャーブランチ・作業進行中）
```

---

### フェーズ5b: ② modal-editing-engine v4（VISUALモード・ヤンク/ペースト）

**対象スキル**: `.claude/skills/editor-buffer-architecture/` (既存・参照用)

**ブランチ**: `claude/modal-editing-v4-visual-8dvxez`

#### 実装内容

##### ModalEditor.java への追加

| 項目 | 内容 |
|---|---|
| Mode enum | `VISUAL` を追加（従来 NORMAL/INSERT/COMMAND） |
| フィールド | `anchorRow`, `anchorCol` (選択開始点)・`yankRegister` (デフォルトレジスタ) |
| NORMALモードキー | `v` (VISUAL進入)・`x` (1文字削除)・`p`/`P` (ペースト) |
| VISUALモードキー | `h/l/j/k` (カーソル移動)・`y` (ヤンク)・`d` (削除) |
| メソッド | `processVisualKey()`・`getSelectedText()`・`deleteSelected()`・`deleteCharAtCursor()`・`pasteAfter()`・`pasteBefore()` 等 |

##### EditorCanvas.java への追加

| 項目 | 内容 |
|---|---|
| フィールド | `visualMode` flag・`selAnchorRow/Col`, `selCursorRow/Col` |
| メソッド | `drawSelectionHighlight()` (アクセント色でハイライト)・`setVisualMode()`・`setSelection()`・`clearSelection()` |
| 修正 | `drawStatusLine()` で "-- VISUAL --" 表示 |

##### テスト追加（v3 の 65 ケースから v4 は 84 ケースに拡大）

**ModalEditorTest.java**:
```
新規メソッド6個（11項目・計19ケース）:
  - testVisualEnter(), testVisualEscape(), testVisualMovement()
  - testVisualYank(), testVisualDelete()
  - testPasteAfter(), testPasteBefore(), testPasteEmptyRegister()
  - testDeleteChar(), testDeleteCharEolClamping(), testDeleteCharEmptyLine()
```

**EditorCanvasTest.java**:
```
新規メソッド2個（計2ケース）:
  - VISUALモード setVisualMode(true) でrepaint呼出確認
  - setSelection(0, 0, 0, 4) で選択設定確認
```

**テスト実行結果**:
```
ModalEditorTest:    84/84 PASS
EditorCanvasTest:   10/10 PASS
合計:              94/94 PASS（全テストクラス 4/4）
既存テスト回帰:     なし
```

#### 実装の注意点

1. **選択ハイライト**: アンカー（`v` 押下時）とカーソル位置を min/max で正規化し、その範囲を `Theme.accent` 色でハイライト表示。
2. **ヤンク単位**: 今回は**文字単位（character-wise）のみ**。行ヤンク (`yy`)・行削除 (`dd`) は v5 以降。
3. **アンドゥとの連携**: VISUAL 中に削除した場合、`buffer.delete()` は `UndoablePieceTable` のスナップショットを自動記録するため、別途対応は不要。
4. **レジスタ**: デフォルトレジスタ `yankRegister` のみ。名前付きレジスタはスコープ外。

#### コミット情報

```
7192dcf ② modal-editing-engine v4: VISUALモード・ヤンク/ペースト実装
```

---

### フェーズのまとめ: ④ keymap-conflict-resolution への依存性

**注意**: CLAUDE.md の依存表に記載通り、④ `keymap-conflict-resolution` は **②③ 両方に依存** している（前セッション引継書に誤記があった）。

```
②: modal-editing-engine  ✅ v3 完了・v4 完了
③: extension-language-runtime  ❌ 未着手
④: keymap-conflict-resolution  ⏳ ③が完了するまで着手不可
```

④ が次優先ではなく、③ `extension-language-runtime`（Java動的コンパイルによるプラグイン機構）が先。

---

## 前フェーズまでの git 状態

```
main  ← 4be1317
└── claude/modal-editing-v4-visual-8dvxez（フィーチャーブランチ・作業完了・プッシュ済み）
```

### 主要ファイルの役割（現時点）

| ファイル | 役割 |
|---|---|
| `src/dev/vimacs/Main.java` | JFrame生成・args[0]でファイル起動対応・ModalEditor+KeyboardFocusManager接続 |
| `src/dev/vimacs/buffer/Piece.java` | ピース（record: source/start/length） |
| `src/dev/vimacs/buffer/PieceTable.java` | バッファ本体（insert/delete/getText/getTextInRange/offsetOfLine/length・protected getPieces/restorePieces） |
| `src/dev/vimacs/buffer/UndoablePieceTable.java` | PieceTable サブクラス（undoStack/redoStack によるスナップショット管理・undo/redo/canUndo/canRedo） |
| `src/dev/vimacs/editor/ModalEditor.java` | NORMAL/INSERT/COMMAND/VISUALの4モード管理・ファイルI/O・カーソル管理・キー処理・u/Ctrl+R でアンドゥ/リドゥ・ヤンク/ペースト |
| `src/dev/vimacs/ui/Theme.java` | LIGHT_MODE / DARK_MODE 配色定数 |
| `src/dev/vimacs/ui/EditorCanvas.java` | Swing描画（テキスト・カーソル・ステータス行/コマンドライン）＋スクロール管理 |

---

## 次フェーズへの引き継ぎ事項

### 推奨次作業の優先順位

| 優先 | Skill | 理由 |
|---|---|---|
| 1位 | ④ `keymap-conflict-resolution` | ②v3完了により着手条件が揃った |
| 2位 | ② `modal-editing-engine` v4 | VISUALモード・範囲選択 |

### 既知の制限（未解決）

| 制限 | 詳細 | 対応フェーズ |
|---|---|---|
| 横スクロールなし | 長い行が画面外にはみ出す | ⑤ v3 |
| 行単位ヤンク/削除なし | `yy`・`dd` が未実装（文字ヤンクのみ） | ② v5以降 |
| アンドゥ単位のグループ化なし | 各 insert/delete が個別アンドゥ単位。複合コマンドのグループ化は未実装 | ② v5以降 |
| ③ extension-language-runtime 未着手 | Java動的コンパイルによるプラグイン機構 | ③ 予定（④の依存関係） |
| ④ keymap-conflict-resolution 未着手 | ③完了後に着手可能 | ④ 予定 |
| `:w` 後に currentFilePath が設定される前の `:wq` | `:wq` は `:w` が成功した場合のみ `:q` を実行する（正しい挙動） | 問題なし |
| コマンド補完・履歴なし | Vim の `<Up>` でコマンド履歴を辿る機能 | スコープ外 |
| `getTextInRange` を描画に未使用 | 現状 `getText()` で全文を渡しており将来最適化用として実装のみ | 巨大ファイルで速度問題が出た場合に対応 |

---

## 技術制約の再確認（厳守）

次フェーズ着手時にも以下を守ること:

- **言語**: Java 21 (LTS)のみ
- **依存ライブラリ**: 一切使用しない（Java SE標準APIのみ）
- **ビルドツール**: `javac`直接呼び出しのみ（Maven/Gradle禁止）
- **テスト**: JUnit不使用・`main`メソッド形式のテストハーネスのみ
- 何かを実装する前に`.claude/skills/`配下の関連SKILL.mdを必ず確認すること
- 新たな設計判断はCLAUDE.mdまたは該当SKILL.mdに記録すること
