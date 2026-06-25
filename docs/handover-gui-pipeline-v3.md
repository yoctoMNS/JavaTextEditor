# 作業引継書：⑤ gui-rendering-pipeline v3 完了

作成日: 2026-06-25
前セッション完了: ⑤ gui-rendering-pipeline v3（横スクロール・JSplitPane ウィンドウ分割）

---

## 現在のプロジェクト状態

### ロードマップ進捗（最新）

| # | Skill名 | 状態 | 詳細 |
|---|---|---|---|
| ① | `editor-buffer-architecture` | ✅ 完了 | テスト 15/15・ピーステーブル・アンドゥ/リドゥ完成 |
| ② | `modal-editing-engine` | ✅ v5 完了 | テスト 151/151・NORMAL/INSERT/COMMAND/VISUAL/VISUAL LINE の5モード |
| ③ | `extension-language-runtime` | ❌ 未着手 | Java動的コンパイルによるプラグイン機構（SKILL.md未作成） |
| ④ | `keymap-conflict-resolution` | ⏳ ③待ち | ③設計完了後に着手可能 |
| ⑤ | `gui-rendering-pipeline` | ✅ v3 完了 | テスト 22/22・縦横スクロール・JSplitPane ウィンドウ分割 |
| ⑥〜⑭ | その他 | 未着手 | 優先度: ③ > ⑦ > ⑧ |

### Git ブランチ状態

```
main  ← e0cef44 (Merge: ⑤ gui-rendering-pipeline v3 横スクロール・ウィンドウ分割完了)
         9aa0ff6 (⑤ v3: 横スクロール・JSplitPane ウィンドウ分割実装)
         84fcda4 (docs: ② v5 完了に向けた引継書・プロンプト作成)
         8c702b7 (docs: README.md を ② v5 対応に更新)
         d4007e6 (Merge: ② v5 行単位ヤンク・VISUAL LINE 完了)
```

### テスト結果（最新）

```
=== dev.vimacs.buffer.PieceTableTest ===          PASS: 15 / 15
=== dev.vimacs.buffer.UndoablePieceTableTest ===  PASS: 11 / 11
=== dev.vimacs.editor.ModalEditorTest ===         PASS: 151 / 151
=== dev.vimacs.ui.EditorCanvasTest ===            PASS: 22 / 22   ← +7 (v3)
=== Summary ===                                   PASS: 199 / 199
```

---

## ⑤ v3 で実装した内容

### 横スクロール（`EditorCanvas`）

| 追加/変更 | 内容 |
|---|---|
| `scrollCol` フィールド | 横スクロール量（半角セル単位。全角=2セル、半角=1セル） |
| `cachedCharWidth` フィールド | `paintComponent` 内で毎回 `FontMetrics.charWidth('M')` を取得しキャッシュ |
| `setScrollCol(int)` / `getScrollCol()` | 外部からの横スクロール操作用 |
| `ensureCursorColVisible(int col, String line)` | カーソル移動後に横スクロールを自動追従する。全角文字の2セル幅も正確に計算 |
| `drawLineWithFullWidthSupport` | `scrollOffsetX = scrollCol * charWidth` ピクセル分だけ左シフトして描画。画面外文字をスキップ |
| `drawCursor` | `xForCol(...) - scrollOffsetX` でカーソルX座標を計算 |
| `drawSelectionHighlight` | VISUAL モードのハイライト範囲を横スクロール対応にクリップ |

### ウィンドウ分割（`Main.java`）

| 追加/変更 | 内容 |
|---|---|
| `JSplitPane(HORIZONTAL_SPLIT)` | 左右2ペインを並列表示（初期50:50分割） |
| 各ペインが独立した `ModalEditor + EditorCanvas` | カーソル位置・スクロール状態・編集内容が独立 |
| `Ctrl+W` でアクティブペイン切り替え | 青枠（`#8888FF`）でアクティブペインを視覚的に表示 |
| ウィンドウサイズ変更 | 800×600 → 1200×700 |

### `ModalEditor.syncCanvas()` 変更

```java
// 横スクロール追従を追加（縦の ensureCursorVisible に加えて）
canvas.ensureCursorVisible(cursorRow);
String[] lines = buffer.getText().split("\n", -1);
String curLine = (cursorRow < lines.length) ? lines[cursorRow] : "";
canvas.ensureCursorColVisible(cursorCol, curLine);
```

---

## 重要な設計判断（記録）

### アクティブペインの管理方式

`KeyboardFocusManager` でフォーカス状態に関係なく全キーを捕捉する方式（v2から継続）を維持し、`int[] activePaneIdx` でどちらのペインにキーを送るかを管理する。
各 `ModalEditor` が独立した `:q` 実装を持つが、現状は両方 `System.exit(0)` で終了する（単一ペイン閉鎖は未実装）。

### scrollCol の単位設計

`scrollCol` は「ピクセル数」ではなく「半角文字セル数」で持つ。理由: フォントサイズが変わっても `scrollCol` の値を変更せずに済む。実際のピクセルオフセットは描画時に `scrollCol * charWidth` で計算する。

### `ensureCursorColVisible` の引数設計

`canvas` 内の `text` フィールドから行を取得する選択肢もあったが、`ModalEditor` 側で `buffer.getText().split()` した結果を渡す設計とした。理由: `EditorCanvas` が内部で行分割を重複して行うコストを避けるため（`syncCanvas` 内で既に行分割している）。

---

## 既知の制限と今後の改善

| 制限 | 詳細 | 対応予定 |
|-----|-----|--------|
| `:q` でアプリ全体が終了 | 片方のペインだけ閉じる機能なし | ② v6 以降か⑤ v4 |
| ペインが常に2つ固定 | `:split` コマンドでの動的分割未実装 | 長期課題 |
| 複数キーマップなし | `"a` 等のレジスタ指定未実装 | ② v6 以降 |
| 検索・置換なし | `/pattern` / `:s/old/new` 未実装 | ⑧以降 |
| プラグイン機構なし | 拡張言語が未定義 | ③ |

---

## 次フェーズの候補と推奨順序

### 優先度1：③ extension-language-runtime（設計ドキュメント作成）

**概要**: `javax.tools.JavaCompiler` を用いた Java 動的コンパイル・プラグイン API の設計書作成

**着手条件**: ✅（技術的に独立）

**実装スケール**: 小〜中（SKILL.md 設計書作成: 1〜2時間、実装例作成: 半日）

**主要タスク**:
```
1. `.claude/skills/extension-language-runtime/SKILL.md` 新規作成
   - プラグイン仕様（EditorPlugin インタフェース）
   - JavaCompiler API / URLClassLoader 活用方法
   - セキュリティモデル
   - 実装例コード（簡易プラグイン）

2. プラグイン API インタフェース設計
   - EditorPlugin / EditorCommand インタフェース定義
   - ライフサイクル（ロード・初期化・実行・アンロード）

3. src/dev/vimacs/extension/ パッケージ作成
   - PluginLoader.java（動的コンパイル・ロード機構）
   - EditorPlugin.java（プラグインインタフェース）
```

**推奨理由**: ③設計完了で④（キーマップ競合解決）が着手可能になる

---

### 優先度2：⑦ editor-testing-strategy（テスト戦略の整備）

**概要**: 境界値・大規模ファイル（100万行規模）のテスト戦略設計と実装

**着手条件**: ✅（① ② ⑤ 完了済み）

**実装スケール**: 小（設計書作成: 1〜2時間・パフォーマンステスト実装: 半日）

**主要タスク**:
```
1. `.claude/skills/editor-testing-strategy/SKILL.md` 新規作成
   - 境界値テスト方針
   - 大規模ファイルパフォーマンステスト方針
   - BufferedImage ピクセル検証パターンのカタログ化

2. test/dev/vimacs/performance/ パッケージ作成
   - PerformanceTest.java（100万行ファイルでの insert/delete 速度測定）

3. 境界値テストの追加
   - PieceTableTest: 空文字列・1文字・境界オフセット
   - ModalEditorTest: 1行ファイル・空ファイルでの各操作
```

---

### 優先度3：② v6（追加Vim機能）

**未実装機能（候補）**:
```
1. 検索: /pattern + n/N で次/前の検索結果に移動
2. 置換: :s/old/new/g
3. オペレータ組み合わせ: d3j（3行削除）・c2w（2単語変更）
4. マーク機能: ma でマーク設定・'a で移動
5. 複数レジスタ: "a でレジスタ a を指定
```

**着手条件**: ④キーマップレジストリが整備されてから段階的に実装推奨

---

## セッション開始時の確認フロー

### 1. 環境確認（3分）

```bash
cd /home/user/JavaTextEditor
git log main --oneline -5           # コミット履歴確認
git status                           # 作業ツリー状態確認
./scripts/test.sh 2>&1 | grep -E "^(PASS|FAIL|=== Summary)"
```

**期待値**:
```
PASS: 199 / 199  (FAIL: 0)
=== Summary: 4 class(es) passed, 0 class(es) failed ===
```

### 2. ドキュメント精読（10分）

| ドキュメント | 重要度 | 確認内容 |
|------------|--------|---------|
| `CLAUDE.md` | ⭐⭐⭐ | ロードマップ・技術制約 |
| `docs/session-log.md` 末尾100行 | ⭐⭐⭐ | 直前セッション内容 |
| 次フェーズの SKILL.md（作成済みなら） | ⭐⭐⭐ | 設計ガイド確認 |

### 3. ブランチ準備（1分）

```bash
git checkout main
git fetch origin
git checkout -b claude/<feature>-<8char-id>
```

---

## 関連ドキュメント一覧

| ファイル | 用途 |
|---------|------|
| `CLAUDE.md` | プロジェクト全体・技術制約・ロードマップ |
| `docs/session-log.md` | 全セッション履歴・設計判断ログ |
| `docs/handover-gui-pipeline-v3.md` | 本ドキュメント（⑤ v3 完了引継書） |
| `docs/handover-modal-editor-v5.md` | ② v5 完了引継書 |
| `.claude/skills/gui-rendering-pipeline/SKILL.md` | GUI描画 v1 設計書 |
| `.claude/skills/gui-rendering-pipeline/references/future-phases.md` | v2/v3 実装詳細（実装済み） |
| `.claude/skills/editor-buffer-architecture/SKILL.md` | バッファ設計書 |

---

## セッション完了チェックリスト（次フェーズ用テンプレート）

- [ ] `./scripts/test.sh` で全テスト PASS（既存テスト回帰なし）
- [ ] `./scripts/run.sh` で起動・目視で新機能動作確認
- [ ] `docs/session-log.md` にセッション内容を追記
- [ ] `README.md` を新機能で更新（テスト結果含む）
- [ ] git コミット・プッシュ完了（`claude/<feature>-<id>` ブランチ）
- [ ] main にマージ・プッシュ
- [ ] `docs/handover-*.md` を新規作成
- [ ] `docs/next-session-prompt-*.md` を新規作成
- [ ] 必要なら `.claude/skills/*/SKILL.md` を作成・更新
