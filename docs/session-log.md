# 作業セッション記録・引き継ぎ書

最終更新: 2026-06-24

---

## セッション全体の概要

プロジェクト初期セットアップから始まり、①バッファ実装の実機検証と⑤GUI描画v1の実装・検証まで完了した。mainブランチにマージ済み。

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

SKILL.mdのコードサンプルは設計フェーズで書かれ、実機検証前だったため以下3件の誤りがあった。

| # | 場所 | 誤り | 修正内容 |
|---|---|---|---|
| 1 | `drawStatusLine`の引数 | `FontMetrics fm`が本体内で未使用 | パラメータを削除 |
| 2 | `drawCursor`のカーソル文字再描画 | `line.charAt(cursorCol)`（`char`単位） | `line.codePointAt(cursorCol)` + `Character.toChars`に修正（サロゲートペア対応） |
| 3 | `Theme`フィールドのアクセス修飾子 | `final`のみ（パッケージ外から不可視） | `public final`に変更 |

修正内容はSKILL.mdの「実機検証で判明した修正点」節に記録済み。

#### テスト結果

```
=== dev.vimacs.buffer.PieceTableTest ===   PASS: 8 / 8
=== dev.vimacs.ui.EditorCanvasTest ===     PASS: 5 / 5
```

EditorCanvasTestの検証内容:
1. LIGHT_MODE背景色がピクセルレベルで正しいか（#F5F0E6）
2. DARK_MODE背景色がピクセルレベルで正しいか（#1A1A1A）
3. NORMALモードのカーソルブロックが前景色で描画されているか
4. INSERTモードのカーソルバーが2px幅で描画されているか
5. `charCellWidth`が半角(1)/全角(2)を正しく判定するか

#### 目視確認

`VisualPreview`でPNGを生成し、以下をすべて目視確認済み:

| ファイル | 確認内容 |
|---|---|
| `preview_light_normal.png` | ベージュ背景・ブロックカーソル・`-- NORMAL --`ステータス行 |
| `preview_light_insert.png` | ベージュ背景・縦棒カーソル・`-- INSERT --`ステータス行 |
| `preview_dark_normal.png`  | 黒背景・ブロックカーソル・日本語全角文字の正常描画 |
| `preview_dark_insert.png`  | 黒背景・縦棒カーソル・`-- INSERT --`ステータス行 |
| `preview_dark_japanese.png` | 日本語行にカーソルを置いた状態の描画 |

#### Windows対応

`scripts/build.bat`, `test.bat`, `run.bat`を追加した。
`dir /s /b src\*.java > build\sources.txt`でJavaファイルを列挙し、`@argfile`経由で`javac`に渡す方式を採用。

---

## 現在の状態（2026-06-24時点）

### ロードマップ更新後の状態

| # | Skill名 | 状態 |
|---|---|---|
| ① | `editor-buffer-architecture` | ✅ 実機検証済み（8/8テスト成功） |
| ② | `modal-editing-engine` | **未着手**（次フェーズ推奨） |
| ③ | `extension-language-runtime` | 未着手 |
| ④ | `keymap-conflict-resolution` | 未着手（②③完了後） |
| ⑤ | `gui-rendering-pipeline` | ✅ v1実機検証済み（5/5テスト成功・目視確認済み） |
| ⑥〜⑭ | その他 | 未着手 |

### ブランチ状態

```
main  ← マージ済み（d4ee03a）
└── claude/quirky-sagan-kzsz6i（フィーチャーブランチ・作業完了）
```

---

## 次フェーズへの引き継ぎ事項

### 推奨次作業: ② modal-editing-engine

依存関係: ①完了済みのため着手可能。

実装すべき内容（要件定義書2・3章より）:
- **NORMALモード**: `h`/`j`/`k`/`l`によるカーソル移動、`i`/`a`/`o`でINSERTモード移行
- **INSERTモード**: 文字入力（PieceTableへのinsert呼び出し）、`Escape`でNORMALモード復帰
- **INSERTモード中のEmacsカーソル移動**: `Ctrl+F`/`B`/`N`/`P`（前後左右）

実装完了後の接続先:
- `EditorCanvas.setCursor(row, col)` ← カーソル行列を渡す
- `EditorCanvas.setInsertMode(boolean)` ← モード切替を渡す
- `PieceTable.insert()` / `PieceTable.delete()` ← 編集操作を渡す

### 注意: v2 GUI（スクロール）着手時の追加作業

`gui-rendering-pipeline`の`references/future-phases.md`に詳細があるが、v2実装時は①の`PieceTable`クラスに以下のメソッドを追加する必要がある:

```java
// src/dev/vimacs/buffer/PieceTable.java に追加
public String getTextInRange(int startOffset, int endOffset) { ... }
public int offsetOfLine(int lineNumber) { ... }
```

「①は完了済み」として読み飛ばさないこと。

### 既知の制限（v1スコープ外・将来対応）

| 制限 | 詳細 | 対応フェーズ |
|---|---|---|
| スクロールなし | ウィンドウを超えた行は表示されない | ⑤ v2 |
| 全角文字を含む行のカーソルX座標ずれ | `cursorCol * charWidth`の簡易計算のため | ⑤ v2（`future-phases.md`に正確な実装あり） |
| Windows改行`\r\n`の未対応 | `split("\n")`では行末に`\r`が残る | ⑤ v2、またはファイル読込時に正規化 |
| キー入力未接続 | v1のカーソルは外部から直接座標を渡す形式 | ② 完了後に接続 |

---

## 技術制約の再確認（厳守）

次フェーズ着手時にも以下を守ること:

- **言語**: Java 21 (LTS)のみ
- **依存ライブラリ**: 一切使用しない（Java SE標準APIのみ）
- **ビルドツール**: `javac`直接呼び出しのみ（Maven/Gradle禁止）
- **テスト**: JUnit不使用・`main`メソッド形式のテストハーネスのみ
- 何かを実装する前に`.claude/skills/`配下の関連SKILL.mdを必ず確認すること
