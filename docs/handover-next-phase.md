# 作業引継書：次フェーズ選択と実装準備

作成日: 2026-06-25
前セッション完了: ② modal-editing-engine v4（VISUALモード・ヤンク/ペースト）

---

## 現在のプロジェクト状態

### ロードマップ進捗

| # | Skill名 | 状態 |
|---|---|---|
| ① | `editor-buffer-architecture` | ✅ 完了（テスト 15/15） |
| ② | `modal-editing-engine` | ✅ v4 完了（テスト 84/84）|
| ③ | `extension-language-runtime` | ❌ 未着手 |
| ④ | `keymap-conflict-resolution` | ⏳ ③待ち（着手可能は ② 完了後のため ②v4でアンロック） |
| ⑤ | `gui-rendering-pipeline` | ✅ v2 完了（テスト 10/10） |
| ⑥〜⑭ | その他 | 未着手 |

### Git ブランチ状態

```
main  ← af3fc09 (② v4: ペースト後のカーソル位置をペーストテキストの末尾に修正)
  └── claude/modal-editing-v4-visual-8dvxez 
      ├── プッシュ済み
      ├── まだ main にマージされていない
      ├── テスト: ModalEditorTest 84/84、EditorCanvasTest 10/10（全PASS）
      └── コミット数: 6コミット（v4実装とテスト追加）
```

### 実装済み機能サマリー

**ModalEditor** (v1〜v4):
- ✅ NORMAL/INSERT/COMMAND/VISUAL の 4 モード管理
- ✅ カーソル移動（hjkl）・挿入（ia）・改行（o）
- ✅ コマンドラインモード（`:w`・`:e`・`:q`・`:wq`）
- ✅ ファイル保存・読み込み
- ✅ アンドゥ/リドゥ（u/Ctrl+R）
- ✅ VISUALモード・選択・ヤンク/ペースト（v・y・d・p・P・x）

**EditorCanvas** (v1〜v2):
- ✅ テキスト描画・カーソル描画（ブロック/縦棒切り替え）
- ✅ 縦スクロール対応
- ✅ ステータス行・コマンドライン表示
- ✅ VISUALモード選択ハイライト表示

---

## 次フェーズの候補と推奨順序

### 候補1：② modal-editing-engine v5（行単位ヤンク・VISUAL LINE モード）

**概要**: 現在の文字単位（character-wise）のヤンク/削除を拡張し、行単位（line-wise）をサポート

**着手条件**: ✅ 満たす（② v4 完了）

**依存関係**: なし（自己完結）

**実装スケール**: 中程度（ModalEditorTest に約 15-20 ケース追加、EditorCanvasTest に約 5 ケース追加）

**主要タスク**:
```
1. ModalEditor.Mode enum へ VISUAL は既存、VISUAL_LINE を検討
   （または VISUAL の yankType を "char" / "line" で区別する）
2. NORMALモードに `yy` キーを追加（現在行をヤンク）
3. NORMALモードに `dd` キーを追加（現在行を削除）
4. VISUAL LINE モード（`V` キーで進入）の実装
   - 行単位の選択表示（行全体をハイライト）
   - `y`/`d` で行単位ヤンク/削除
5. ペースト時に yankType を考慮（行ヤンクはカーソル行の下に貼り付け）
6. テスト追加（各コマンドの正常系・境界値・複数行ケース）
```

**次に進める場合の開始ファイル**:
- `.claude/skills/modal-editing-engine/SKILL.md` を新規作成
- `src/dev/vimacs/editor/ModalEditor.java`
- `src/dev/vimacs/ui/EditorCanvas.java`
- `test/dev/vimacs/editor/ModalEditorTest.java`

---

### 候補2：③ extension-language-runtime（Java 動的コンパイルによるプラグイン機構）

**概要**: `javax.tools.JavaCompiler` を利用して、エディタ起動中に Jar/Java ファイルをプラグインとして読み込む仕組み

**着手条件**: ✅ 満たす（技術的には独立）

**依存関係**: 
- 設計のみならば ②③ の依存は「④ の前提」であり、③ 単体の実装着手はできる
- ただし「④ keymap-conflict-resolution」を実現するには ③ が必要
  （拡張言語で Keymap を定義することを想定）

**実装スケール**: 大規模（設計書作成・Java Compiler API 習得・テストハーネス構築が必要）

**主要タスク**:
```
1. `.claude/skills/extension-language-runtime/SKILL.md` を新規作成
   - プラグイン仕様の定義（インタフェース・ライフサイクル）
   - Java Compiler API の使用方法
   - ClassLoader の動的ロード方法
   - セキュリティと検証方法
2. 簡易なプラグイン API を設計
   - EditorPlugin インタフェース定義
   - プラグイン検出・コンパイル・ロード機構
3. プリセットプラグインの実装例（e.g. "Tab→Space変換"）
4. テスト用プラグインの作成
5. Main.java で プラグインディレクトリをスキャン・読み込み
```

**次に進める場合の開始ファイル**:
- `.claude/skills/extension-language-runtime/SKILL.md` を新規作成（設計ドキュメント）
- `src/dev/vimacs/plugin/EditorPlugin.java`（プラグイン API インタフェース）

---

### 候補3：⑤ gui-rendering-pipeline v3（横スクロール・ウィンドウ分割）

**概要**: 長い行に対応した横スクロール、および `JSplitPane` を使ったウィンドウ分割機能

**着手条件**: ✅ 満たす（⑤ v2 完了）

**依存関係**: ② v3 以上（アンドゥ/リドゥが必要・v4 完了なので問題なし）

**実装スケール**: 中〜大規模（横スクロール + ウィンドウ分割 で 2 段階）

**主要タスク（段階1：横スクロール）**:
```
1. EditorCanvas に scrollCol フィールド追加
2. drawText() を横スクロール対応に変更
3. ensureCursorVisible() に左右フリングロジック追加
4. Test で長い行でのカーソル移動・描画確認
```

**主要タスク（段階2：ウィンドウ分割）**:
```
1. Main.java の構成を JSplitPane 対応に変更
2. 複数の EditorCanvas + ModalEditor ペアを管理する仕組み
3. フォーカス切り替え（Ctrl+W など）のキーバインド
4. 各ペインの独立したスクロール・カーソル管理
```

**次に進める場合の開始ファイル**:
- `.claude/skills/gui-rendering-pipeline/references/future-phases.md` を確認
- `src/dev/vimacs/ui/EditorCanvas.java`

---

## 推奨フロー（実装難易度と価値のバランス）

### ステップ1（推奨）：② v5 実装 → 即座に機能が見える・スコープが明確

**理由**:
- ② v4 の延長線で実装できる（モード管理がそのままシューズイン）
- テスト体系が ② v4 を踏襲できる
- 目視で「行ヤンク」という分かりやすい機能追加を確認可能
- 所要時間: 半日〜1日（テスト含む）

**実装ファイル**: `ModalEditor.java`・`EditorCanvas.java`・テスト

---

### ステップ2（その後）：③ 設計書作成 → ④ 実装可能に

**理由**:
- ③ の設計ドキュメントを書くだけで ④ の着手条件が満たされる
- ③ 実装は ④ の後でも構わない（④ はキーマップ定義言語として使える）

**実装ファイル**: `.claude/skills/extension-language-runtime/SKILL.md`

---

### ステップ3（並行または順序切り替え）：④ キーマップ競合解決

**理由**:
- ② の VISUAL/VISUAL_LINE とアンドゥ/リドゥの複合操作時に Vim/Emacs のキー体系を整理する
- Insert モード中のバックスペース（`Ctrl+H`）など Emacs 式と Vim 式が衝突する部分

---

### ステップ4（将来）：⑤ v3 横スクロール

**理由**:
- 必要性は高いが実装量が大きい
- v1・v2 の検証があるため難易度は相対的に低い
- 大規模ファイル対応が必須になってから優先度が上がる

---

## 共通の確認事項

### 次セッション開始時のチェックリスト

- [ ] `git checkout main` してから `git log --oneline -5` で最新確認
- [ ] `git fetch origin` で最新ブランチを取得
- [ ] 選択したフェーズに対応する `.claude/skills/*/SKILL.md` を精読
- [ ] 既存テストを `./scripts/test.sh` で全 PASS 確認
- [ ] 新規ブランチ作成: `git checkout -b claude/<feature-name>-<8char-id>`
- [ ] 実装前に CLAUDE.md / SKILL.md に矛盾がないか確認

### テスト完了条件（すべてのフェーズ共通）

```bash
./scripts/test.sh
# 期待: 
#   === all test classes ===
#   PASS: XXX / XXX  (FAIL: 0)
# ※ 既存テストの回帰がないことが必須
```

### コミット・プッシュ前確認

```bash
git status          # コミット対象を確認
git diff --stat     # 変更ファイル数を確認
git diff HEAD~1     # 前のコミットからの差分を確認（大きすぎないか）
```

---

## 関連ドキュメント

| ファイル | 用途 |
|---|---|
| `CLAUDE.md` | プロジェクト全体の技術制約・ロードマップ・作業方針 |
| `docs/requirements.md` | 要件定義書（参考・古い可能性あり） |
| `docs/session-log.md` | 全セッション履歴・設計判断ログ |
| `.claude/skills/editor-buffer-architecture/SKILL.md` | ピーステーブル設計・実装済み |
| `.claude/skills/editor-buffer-architecture/references/piece-table-delete-and-undo.md` | 削除・アンドゥ設計 |
| `.claude/skills/gui-rendering-pipeline/SKILL.md` | GUI描画設計・v1/v2実装済み |
| `.claude/skills/gui-rendering-pipeline/references/future-phases.md` | GUI v3・分割パネル設計 |

---

## 既知の制限と設計判断

### 実装済みだが制限あり

| 制限 | 詳細 | 理由 | 対応フェーズ |
|---|---|---|---|
| 文字ヤンク のみ | `yy`・`dd`・`V` 未実装 | v4 スコープ外 | ② v5 |
| レジスタ 1 個 | `yankRegister` デフォルト のみ | 設計複雑化を避けるため | 将来拡張 |
| アンドゥ単位がグループ化されない | 複合操作も 1 アクション単位でアンドゥ | 現状で動作上問題ない | 将来改善 |
| 横スクロール なし | 80+ 文字行は画面外 | ⑤ v2 段階では不要だった | ⑤ v3 |
| プラグイン機構なし | Lisp/プラグイン言語がない | 設計中（③） | ③ |

### 設計判断ログ（このセッション）

なし（前セッション参照）

---

## 次回セッション用 Claude Code 最適化プロンプト

→ `next-session-prompt-next-phase.md` を参照

---

## トラブルシューティング

### ビルド・テストが失敗する場合

```bash
# 1. キャッシュをクリア
rm -rf build/

# 2. テスト実行
./scripts/test.sh

# 3. 特定テストクラスを直接実行
javac -cp build src/dev/vimacs/editor/ModalEditor.java test/dev/vimacs/editor/ModalEditorTest.java
cd build && java dev.vimacs.editor.ModalEditorTest
```

### キー入力が効かない場合

- `KeyboardFocusManager.addKeyEventDispatcher()` が登録されているか確認
- `Main.java` の `setFocusable(true)` を確認
- IDE の「トグルフォーカス」機能が干渉していないか確認

### 画面にテキストが表示されない場合

- `EditorCanvas.paintComponent()` に `super.paintComponent(g)` 呼び出しがあるか
- 背景色・文字色が同色になっていないか
- フォントが指定されているか

---

## 記録・引き継ぎ方針

このドキュメントの更新ルール:
- 新しいフェーズ開始時: 「前セッション完了」欄を更新
- フェーズ終了時: ロードマップ進捗表を更新・次フェーズを記載
- 既知の制限が解決した場合: 該当行を削除・「対応フェーズ」に解決コミットハッシュを記載

---

**最終確認**: このドキュメントと CLAUDE.md / session-log.md に矛盾がないことを確認してからセッション開始。
