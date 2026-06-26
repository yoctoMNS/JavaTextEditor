# 次セッション用 Claude Code プロンプト（③ extension-language-runtime 完了後）

作成日: 2026-06-25
対象フェーズ: ④ keymap-conflict-resolution または ③ v2（:plugin コマンド統合）

---

## プロンプト本文

```
# 作業引継：Vimacs エディタ ③ v1 完了後セッション

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
| ④ keymap-conflict-resolution | ✅ 着手解禁（SKILL.md 作成済み） | - |
| ⑤ gui-rendering-pipeline v3 | ✅ 完了 | 22 |
| ⑥〜⑭ | 未着手 | - |

合計テスト: PASS 208 / 208

## セッション開始手順（必ず実行）

1. テスト確認:
   ./scripts/test.sh 2>&1 | grep -E "^(PASS|FAIL|=== Summary)"
   → PASS: 208 / 208 を確認

2. 引継書を読む:
   docs/handover-extension-runtime-v1.md

3. SKILL.md を読む（実装前に必ず）:
   選択肢Aなら: .claude/skills/keymap-conflict-resolution/SKILL.md
   選択肢Bなら: .claude/skills/extension-language-runtime/SKILL.md

4. 作業ブランチを作成:
   git checkout main && git fetch origin
   git checkout -b claude/<feature>-<8char-id>

## 今セッションで実施するタスク

[以下のどちらかを選んで着手する]

### 選択肢A: ④ keymap-conflict-resolution（推奨）
目的: ModalEditor のキー処理をハードコードからキーマップレジストリに移行し、
      Vim キーと Emacs キーの共存を設定可能にする

事前確認必須: .claude/skills/keymap-conflict-resolution/SKILL.md（設計書作成済み）

実装スコープ:
1. src/dev/vimacs/editor/KeyBinding.java（record）を新規作成
   - keyCode / keyChar / modifiers / actionName を持つ不変レコード
   - ofChar(char, String) / ofCode(int, int, String) ファクトリメソッド

2. src/dev/vimacs/editor/KeymapRegistry.java を新規作成
   - モード別キーバインド管理（NORMAL / INSERT / COMMAND / VISUAL / VISUAL_LINE）
   - loadDefaults() でデフォルトキーマップを一元定義
   - bind(Mode, KeyBinding, String) で外部から上書き可能
   - resolve(Mode, int, char, int) → String でアクション名を解決

3. ModalEditor.processNormalKey を KeymapRegistry 経由に移行（Phase 1）
   - switch(keyChar) をレジストリ解決に置き換え
   - 既存テスト 151 件が全て通ることを確認

4. テスト: test/dev/vimacs/editor/KeymapRegistryTest.java（5+ ケース）
   - デフォルトバインド確認
   - bind() で上書きできることの確認
   - resolve() で未登録キーが null を返すことの確認

重要: ModalEditor の既存テスト 151 件を回帰させないこと。
      processNormalKey の switch を一気に置き換えず、
      テストを通しながら1モードずつ移行すること。

### 選択肢B: ③ v2 — :plugin コマンド統合
目的: ModalEditor の COMMAND モードに :plugin / :load-plugin を追加し、
      PluginLoader と実際に接続する

実装スコープ:
1. ModalEditor に PluginLoader フィールドを追加（setter 注入）
2. processCommandKey に以下のケースを追加:
   - :load-plugin <path>  → PluginLoader.loadPlugin(Path.of(path))
   - :plugin <name>       → PluginLoader.executePlugin(name, new SimpleEditorContext(this))
3. Main.java で PluginLoader を生成し各 ModalEditor に渡す
4. テスト: ModalEditorTest に :plugin コマンドのケースを追加（3+ ケース）

## 実装完了時のチェックリスト

- [ ] ./scripts/test.sh で PASS: 208+ / 208+ (既存テスト回帰なし)
- [ ] ./scripts/run.sh で起動確認
- [ ] docs/session-log.md に追記
- [ ] README.md 更新
- [ ] git commit → ブランチ push
- [ ] docs/handover-*.md 作成
- [ ] docs/next-session-prompt-*.md 作成

## 主要ファイル一覧（参照用）

| ファイル | 役割 |
|---|---|
| src/dev/vimacs/Main.java | JSplitPane 2ペイン・Ctrl+W でフォーカス切り替え |
| src/dev/vimacs/buffer/PieceTable.java | ピーステーブル |
| src/dev/vimacs/buffer/UndoablePieceTable.java | アンドゥ/リドゥ（スナップショット方式） |
| src/dev/vimacs/editor/ModalEditor.java | 5モード管理（processNormalKey が switch 直書き） |
| src/dev/vimacs/extension/EditorPlugin.java | プラグインインタフェース |
| src/dev/vimacs/extension/PluginLoader.java | JavaCompiler 動的ロード（実装済み・未接続） |
| src/dev/vimacs/extension/SimpleEditorContext.java | ModalEditor → EditorContext アダプタ |
| src/dev/vimacs/ui/EditorCanvas.java | 描画・縦横スクロール |

## 注意点

- ④ の実装は .claude/skills/keymap-conflict-resolution/SKILL.md を必ず読んでから着手すること
- ModalEditor の switch(keyChar) を一気にレジストリ移行しようとしない
  → processNormalKey → processVisualKey → processInsertKey の順に1つずつ移行する
- KeymapRegistry のテストは ModalEditor に依存しない単体テストで書けるはず
- ③ v2（:plugin 統合）は選択肢Aと並行可能だが、先に④を安定させてから着手推奨
```

---

## 補足：プロンプトの使い方

このプロンプトを次のセッション開始時に Claude Code に貼り付ける。
選択肢A（④ keymap-conflict-resolution）が最優先。
時間に余裕があれば選択肢Bも同一セッションで実施可。

---

## ④ keymap-conflict-resolution の事前設計メモ

### 最小実装パターン（Phase 1 の具体イメージ）

```java
// KeyBinding.java
package dev.vimacs.editor;
import java.awt.event.KeyEvent;

public record KeyBinding(int keyCode, char keyChar, int modifiers, String actionName) {
    public static KeyBinding ofChar(char c, String action) {
        return new KeyBinding(KeyEvent.VK_UNDEFINED, c, 0, action);
    }
    public static KeyBinding ofCode(int code, int mod, String action) {
        return new KeyBinding(code, KeyEvent.CHAR_UNDEFINED, mod, action);
    }
}
```

```java
// KeymapRegistry.java（最小版）
package dev.vimacs.editor;
import java.util.*;

public class KeymapRegistry {
    public enum Mode { NORMAL, INSERT, COMMAND, VISUAL, VISUAL_LINE }
    private final Map<Mode, Map<String, String>> map = new HashMap<>();

    public KeymapRegistry() {
        for (Mode m : Mode.values()) map.put(m, new HashMap<>());
        loadDefaults();
    }

    public void bind(Mode mode, KeyBinding kb, String action) {
        map.get(mode).put(keyOf(kb), action);
    }

    public String resolve(Mode mode, int keyCode, char keyChar, int mods) {
        return map.get(mode).get(keyOf(new KeyBinding(keyCode, keyChar, mods, "")));
    }

    private String keyOf(KeyBinding kb) {
        return (kb.keyChar() != java.awt.event.KeyEvent.CHAR_UNDEFINED)
            ? kb.keyChar() + ":" + kb.modifiers()
            : "VK" + kb.keyCode() + ":" + kb.modifiers();
    }

    private void loadDefaults() {
        bind(Mode.NORMAL, KeyBinding.ofChar('h', "cursor.left"),    "cursor.left");
        bind(Mode.NORMAL, KeyBinding.ofChar('l', "cursor.right"),   "cursor.right");
        bind(Mode.NORMAL, KeyBinding.ofChar('j', "cursor.down"),    "cursor.down");
        bind(Mode.NORMAL, KeyBinding.ofChar('k', "cursor.up"),      "cursor.up");
        // ... 続く
    }
}
```

```java
// ModalEditor.processNormalKey 移行後のイメージ
private void processNormalKey(char keyChar) {
    String action = keymap.resolve(Mode.NORMAL, KeyEvent.VK_UNDEFINED, keyChar, 0);
    if (action == null) return;
    switch (action) {
        case "cursor.left"  -> moveCursor(0, -1);
        case "cursor.right" -> moveCursor(0, 1);
        // ...
    }
}
```
