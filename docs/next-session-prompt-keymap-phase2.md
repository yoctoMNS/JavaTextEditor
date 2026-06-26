# 次セッション用プロンプト（④ keymap-conflict-resolution Phase 2）

生成日: 2026-06-26
対象フェーズ: ④ keymap-conflict-resolution Phase 2

---

## プロンプト本文

```
# 作業引継：Vimacs エディタ ④ Phase 1 完了後セッション

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
| ④ keymap-conflict-resolution **Phase 1** | ✅ **完了** | **16** |
| ⑤ gui-rendering-pipeline v3 | ✅ 完了 | 22 |

合計テスト: **PASS 224 / 224**

## セッション開始手順（必ず実行）

1. テスト確認:
   ```
   ./scripts/test.sh 2>&1 | grep -E "^(PASS|FAIL|===)"
   ```
   → PASS: 224 / 224 を確認

2. 引継書を読む:
   ```
   docs/session-keymap-phase1.md
   ```

3. SKILL.md を再度確認（Phase 2 の内容）:
   ```
   .claude/skills/keymap-conflict-resolution/SKILL.md
   ```
   → 「実装方針（段階的移行）」セクションの Phase 2 を確認

4. 作業ブランチを作成:
   ```
   git checkout main && git fetch origin
   git checkout -b claude/keymap-conflict-resolution-phase2-<8char>
   ```
   または既存ブランチから:
   ```
   git checkout claude/keymap-conflict-resolution-sf591f
   git pull origin claude/keymap-conflict-resolution-sf591f
   ```

## 今セッションで実施するタスク: Phase 2 実装

### 目標

INSERT / VISUAL / VISUAL_LINE モードも KeymapRegistry 経由に移行し、キーバインド管理を完全統一する。

### スコープ

#### 1. processInsertKey を KeymapRegistry に統合

**ファイル**: `src/dev/vimacs/editor/ModalEditor.java`

**修正内容**:
- processKey から processInsertKey に keyCode, keyChar, modifiers を全て渡す
- processInsertKey のシグネチャを変更：`(int keyCode, char keyChar, int modifiers)` に統一
- 処理内容をキーバインド名から実行する形に変更

**既存実装の参考**:
```java
private void processInsertKey(int keyCode, char keyChar, int modifiers) {
    // 現在の実装（if-else で keyCode/modifiers チェック）
    if (keyCode == KeyEvent.VK_ESCAPE) { ... }
    else if (ctrl && keyCode == KeyEvent.VK_F) { ... }
    // ...
}
```

**修正後のイメージ**:
```java
private void processInsertKey(int keyCode, char keyChar, int modifiers) {
    String action = keymap.resolve(KeymapRegistry.Mode.INSERT, keyCode, keyChar, modifiers);
    if (action == null && keyChar >= ' ') {
        // 通常文字の挿入
        buffer.insert(offsetOfCursor(), String.valueOf(keyChar));
        cursorCol++;
        return;
    }
    if (action == null) return;
    
    switch (action) {
        case "enter.normal" -> {
            mode = Mode.NORMAL;
            clampCursorForNormal();
        }
        case "cursor.right" -> moveCursor(0, 1);
        // ... 他のアクション
    }
}
```

#### 2. processVisualKey を KeymapRegistry に統合

**ファイル**: `src/dev/vimacs/editor/ModalEditor.java`

**修正内容**:
- processKey から processVisualKey に keyCode, keyChar, modifiers を全て渡す
- processVisualKey のシグネチャを変更
- キー処理を KeymapRegistry 経由に変更

**既存キー（VISUAL モード）**:
- h/l/j/k: 選択範囲拡張（cursor.left/right/up/down）
- y: ヤンク（yank）
- d: 削除（delete）
- ESC: NORMAL 復帰（enter.normal）

#### 3. processVisualLineKey を KeymapRegistry に統合

**ファイル**: `src/dev/vimacs/editor/ModalEditor.java`

**修正内容**:
- processKey から processVisualLineKey に keyCode, keyChar, modifiers を全て渡す
- processVisualLineKey のシグネチャを変更
- キー処理を KeymapRegistry 経由に変更

**既存キー（VISUAL LINE モード）**:
- j/k: 行選択拡張（cursor.down/up）
- y: 行ヤンク（yank）
- d: 行削除（delete）
- ESC: NORMAL 復帰（enter.normal）

#### 4. KeymapRegistry にモード別キーを追加

**ファイル**: `src/dev/vimacs/editor/KeymapRegistry.java`

INSERT/VISUAL/VISUAL_LINE モードのデフォルトキーバインドを loadDefaults() に追加：

```java
private void loadDefaults() {
    // 既存の NORMAL / INSERT / COMMAND
    // ...
    
    // VISUAL モード（Phase 2 追加）
    bind(Mode.VISUAL, KeyBinding.ofChar('h', "cursor.left"), "cursor.left");
    bind(Mode.VISUAL, KeyBinding.ofChar('l', "cursor.right"), "cursor.right");
    bind(Mode.VISUAL, KeyBinding.ofChar('j', "cursor.down"), "cursor.down");
    bind(Mode.VISUAL, KeyBinding.ofChar('k', "cursor.up"), "cursor.up");
    bind(Mode.VISUAL, KeyBinding.ofChar('y', "yank"), "yank");
    bind(Mode.VISUAL, KeyBinding.ofChar('d', "delete"), "delete");
    bind(Mode.VISUAL, KeyBinding.ofCode(KeyEvent.VK_ESCAPE, 0, "enter.normal"), "enter.normal");
    
    // VISUAL LINE モード（Phase 2 追加）
    bind(Mode.VISUAL_LINE, KeyBinding.ofChar('h', "cursor.left"), "cursor.left");
    bind(Mode.VISUAL_LINE, KeyBinding.ofChar('l', "cursor.right"), "cursor.right");
    bind(Mode.VISUAL_LINE, KeyBinding.ofChar('j', "cursor.down"), "cursor.down");
    bind(Mode.VISUAL_LINE, KeyBinding.ofChar('k', "cursor.up"), "cursor.up");
    bind(Mode.VISUAL_LINE, KeyBinding.ofChar('y', "yank"), "yank");
    bind(Mode.VISUAL_LINE, KeyBinding.ofChar('d', "delete"), "delete");
    bind(Mode.VISUAL_LINE, KeyBinding.ofCode(KeyEvent.VK_ESCAPE, 0, "enter.normal"), "enter.normal");
}
```

#### 5. テスト確認

**既存テスト**: 151 件全て通ることを確認
```bash
./scripts/test.sh 2>&1 | grep "ModalEditorTest"
# → PASS: 151 / 151 を確認
```

**追加テストケース** (3 件以上):
- KeymapRegistryTest に VISUAL / VISUAL_LINE モードのキーマップ確認を追加
  - VISUAL 'y' → "yank"
  - VISUAL 'd' → "delete"
  - VISUAL_LINE 'y' → "yank"
  - VISUAL_LINE 'd' → "delete"

### 実装のコツ

1. **段階的移行**: processInsertKey → processVisualKey → processVisualLineKey の順に移行し、各段階でテストを実行

2. **テスト駆動**: 移行前に以下を確認
   - `./scripts/test.sh` で ModalEditorTest が 151/151 PASS
   - 各モード別のキー処理が KeymapRegistry 経由で正しく動作

3. **アクション名の統一**: 
   - 同じ概念（カーソル移動、ヤンク、削除）は NORMAL/VISUAL/VISUAL_LINE で同じアクション名を使用
   - 例: "cursor.left" は全モード共通

4. **null チェック**:
   - resolve() が null を返した場合の処理（通常文字の挿入など）を適切に実装

## 実装完了時のチェックリスト

- [ ] ./scripts/test.sh で PASS: 224+ / 224+ (既存テスト回帰なし)
- [ ] processInsertKey / processVisualKey / processVisualLineKey が KeymapRegistry 経由に移行
- [ ] KeymapRegistry に INSERT / VISUAL / VISUAL_LINE モードのデフォルトキーマップを追加
- [ ] KeymapRegistryTest に新規テストケース 3 件以上追加
- [ ] ./scripts/run.sh で起動確認（GUI表示確認）
- [ ] docs/session-keymap-phase2.md に作業ログを追記
- [ ] git commit → ブランチ push
- [ ] main ブランチへの PR 作成（または merge 準備）

## 主要ファイル一覧（参照用）

| ファイル | 役割 | 修正対象 |
|---|---|---|
| src/dev/vimacs/editor/KeymapRegistry.java | キーバインド管理 | ✏️ loadDefaults() に INSERT/VISUAL/VISUAL_LINE 追加 |
| src/dev/vimacs/editor/ModalEditor.java | モード管理・キー処理 | ✏️ processInsertKey/processVisualKey/processVisualLineKey 移行 |
| test/dev/vimacs/editor/KeymapRegistryTest.java | レジストリテスト | ✏️ INSERT/VISUAL/VISUAL_LINE モード確認テスト追加 |
| test/dev/vimacs/editor/ModalEditorTest.java | モーダル編集テスト | ✓ 回帰テスト確認 |

## 注意点

- ⚠️ INSERT モードは通常文字の挿入があるため、resolve() が null の場合の処理が必要
- ⚠️ VISUAL / VISUAL_LINE モードでは ESC 処理が processKey 内で先に処理されているため、重複を避けること
- ⚠️ processKey の switch 内で VISUAL/VISUAL_LINE の ESC を処理しているが、processVisualKey/processVisualLineKey からも enter.normal アクション名で処理可能にしておく（冗長性）

## 参考資料

- `.claude/skills/keymap-conflict-resolution/SKILL.md`: Phase 2 の詳細設計
- `docs/session-keymap-phase1.md`: Phase 1 の実装内容・テスト結果
- CLAUDE.md: プロジェクト全体の制約・ロードマップ
```

---

## 補足

このプロンプトを使用する際は、以下の点に注意してください：

1. **ブランチ管理**: 既存ブランチ `claude/keymap-conflict-resolution-sf591f` を使用するか、新しいブランチを作成するか選択
2. **段階的なテスト実行**: 各モード移行後に必ず `./scripts/test.sh` を実行
3. **コミットメッセージ**: Phase 2 の内容を明確に記載

