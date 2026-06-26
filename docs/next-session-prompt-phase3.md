# 次セッション用プロンプト（④ Phase 3 または ⑥ plugin-api-design）

生成日: 2026-06-26
対象フェーズ: ④ keymap-conflict-resolution Phase 3（プラグイン統合）または ⑥ plugin-api-design

---

## プロンプト本文

```
# 作業引継：Vimacs エディタ ④ Phase 2 完了後セッション

## プロジェクト概要

Vim（モーダル編集）+ Emacs（拡張性）を統合した Java SE 製軽量テキストエディタの開発。
作業ディレクトリ: /home/user/JavaTextEditor

## 技術制約（絶対厳守）

- Java 21 (LTS) のみ・外部ライブラリ一切禁止・Maven/Gradle 禁止
- ビルド: javac 直呼び出し（./scripts/build.sh）
- テスト: JUnit 禁止・main メソッド形式の自作テストハーネスのみ
- 実装前に必ず .claude/skills/ 配下の関連 SKILL.md を確認すること

## 現在の完成状態

| Skill | 状態 | テスト数 |
|---|---|---|
| ① editor-buffer-architecture | ✅ 完了 | 15 |
| ② modal-editing-engine v5 | ✅ 完了 | 151 |
| ③ extension-language-runtime v1 | ✅ 完了 | 9 |
| ④ keymap-conflict-resolution Phase 1+2 | ✅ **完了** | **30** |
| ⑤ gui-rendering-pipeline v3 | ✅ 完了 | 22 |

合計テスト: **PASS 238 / 238**

## セッション開始手順（必ず実行）

1. テスト確認:
   ./scripts/test.sh 2>&1 | grep -E "^(PASS|FAIL|===)"
   → PASS: 238 / 238 を確認

2. 引継書を読む:
   docs/session-keymap-phase2.md

3. SKILL.md を確認:
   .claude/skills/keymap-conflict-resolution/SKILL.md
   → Phase 3 の内容を確認

4. 作業ブランチを作成:
   git checkout main && git fetch origin
   git checkout -b claude/<feature>-<8char>

## キーバインドの現在の構成

### KeymapRegistry（全モード完成）

```
NORMAL:       h/l/j/k(移動), i/a/o(INSERT), u/Ctrl+R(undo/redo),
              v/V(VISUAL), x/p/P(削除/貼付), y/d(ヤンク/削除), :(COMMAND)
INSERT:       ESC(NORMAL), Ctrl+F/B/N/P(Emacs移動), Backspace, Enter
COMMAND:      ESC(NORMAL), Enter(実行)
VISUAL:       h/l/j/k(移動), y(yank), d(delete), ESC(NORMAL)
VISUAL_LINE:  h/l/j/k(移動), y(yank), d(delete), ESC(NORMAL)
```

## 今セッションで実施するタスク（選択肢）

### 選択肢A: ④ Phase 3 — プラグインからのキーバインド登録

**目的**: プラグインが `EditorContext` 経由で `KeymapRegistry` にアクセスし、カスタムキーバインドを登録できるようにする

**実装スコープ**:
1. `EditorContext` インタフェースに `KeymapRegistry getKeymap()` メソッドを追加
2. `SimpleEditorContext` に実装（`ModalEditor` から `keymap` を返す）
3. `ModalEditor` に `getKeymap()` を公開するメソッドを追加
4. テスト: プラグインがキーバインドを登録できることを確認

**参考ファイル**:
- `src/dev/vimacs/extension/EditorContext.java`
- `src/dev/vimacs/extension/SimpleEditorContext.java`
- `src/dev/vimacs/editor/ModalEditor.java`

### 選択肢B: ⑥ plugin-api-design — プラグイン向け公開API設計

**目的**: プラグインが使えるエディタ操作の公開APIを設計・実装する

**実装スコープ**:
1. `EditorContext` インタフェースの拡張（テキスト操作・カーソル・モード変更）
2. `SimpleEditorContext` の実装完成
3. プラグインからのテキスト操作テスト（3件以上）

**参考ファイル**:
- `src/dev/vimacs/extension/EditorContext.java`（現在の定義を確認）
- `src/dev/vimacs/extension/EditorPlugin.java`
- `src/dev/vimacs/extension/PluginLoader.java`（実装済み・稼働中）

## 実装完了時のチェックリスト

- [ ] ./scripts/test.sh で PASS: 238+ / 238+ (既存テスト回帰なし)
- [ ] ./scripts/run.sh で起動確認
- [ ] docs/session-*.md に作業ログ追記
- [ ] docs/next-session-prompt-*.md 作成
- [ ] git commit → ブランチ push → main マージ

## 主要ファイル一覧

| ファイル | 役割 |
|---|---|
| src/dev/vimacs/editor/KeymapRegistry.java | モード別キーバインド管理（全5モード完成） |
| src/dev/vimacs/editor/KeyBinding.java | キーバインドレコード |
| src/dev/vimacs/editor/ModalEditor.java | モード管理（全メソッドが KeymapRegistry 経由） |
| src/dev/vimacs/extension/EditorContext.java | プラグイン向け EditorContext インタフェース |
| src/dev/vimacs/extension/EditorPlugin.java | プラグインインタフェース |
| src/dev/vimacs/extension/PluginLoader.java | JavaCompiler 動的ロード（実装済み） |
| src/dev/vimacs/extension/SimpleEditorContext.java | ModalEditor アダプタ |

## 参考資料

- `.claude/skills/keymap-conflict-resolution/SKILL.md`: Phase 3 の設計（プラグイン統合）
- `docs/session-keymap-phase2.md`: Phase 2 の実装内容
- `CLAUDE.md`: プロジェクト全体の制約・ロードマップ
```
