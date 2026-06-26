# 作業引継書：③ extension-language-runtime v1 完了

作成日: 2026-06-25
前セッション完了: ③ extension-language-runtime（JavaCompiler による動的プラグイン機構）

---

## 現在のプロジェクト状態

### ロードマップ進捗（最新）

| # | Skill名 | 状態 | 詳細 |
|---|---|---|---|
| ① | `editor-buffer-architecture` | ✅ 完了 | テスト 15/15・ピーステーブル・アンドゥ/リドゥ完成 |
| ② | `modal-editing-engine` | ✅ v5 完了 | テスト 151/151・NORMAL/INSERT/COMMAND/VISUAL/VISUAL LINE の5モード |
| ③ | `extension-language-runtime` | ✅ v1 完了 | テスト 9/9・JavaCompiler 動的コンパイル・EditorPlugin/EditorContext/PluginLoader 実装済み |
| ④ | `keymap-conflict-resolution` | ✅ **着手解禁** | ②③完了により着手可能 |
| ⑤ | `gui-rendering-pipeline` | ✅ v3 完了 | テスト 22/22・縦横スクロール・JSplitPane ウィンドウ分割 |
| ⑥〜⑭ | その他 | 未着手 | |

### テスト結果（最新）

```
=== dev.vimacs.buffer.PieceTableTest ===          PASS: 15 / 15
=== dev.vimacs.buffer.UndoablePieceTableTest ===  PASS: 11 / 11
=== dev.vimacs.editor.ModalEditorTest ===         PASS: 151 / 151
=== dev.vimacs.extension.PluginLoaderTest ===     PASS: 9 / 9    ← 今回追加
=== dev.vimacs.ui.EditorCanvasTest ===            PASS: 22 / 22
=== Summary ===                                   PASS: 208 / 208
```

### Git ブランチ状態

```
claude/sweet-fermat-vvc8d5  ← 88ee952 (feat: ③ extension-language-runtime 実装)
main                        ← e0cef44 (Merge: ⑤ gui-rendering-pipeline v3)
```

---

## ③ v1 で実装した内容

### パッケージ構成

```
src/dev/vimacs/extension/
├── EditorPlugin.java          プラグイン作者が実装するインタフェース
├── EditorContext.java         プラグインがエディタを操作するための窓口インタフェース
├── PluginLoader.java          動的コンパイル・ロード・ライフサイクル管理
├── SimpleEditorContext.java   ModalEditor を EditorContext にアダプト
└── PluginLoadException.java   コンパイル失敗・型不整合エラー

test/dev/vimacs/extension/
└── PluginLoaderTest.java      7 ケース・PASS 9/9
```

### EditorPlugin インタフェース

```java
public interface EditorPlugin {
    String getName();                       // プラグイン識別名（必須）
    default void onLoad(EditorContext ctx) {} // ロード直後に1度（任意）
    void execute(EditorContext ctx);        // コマンド呼び出しで実行（必須）
    default void onUnload() {}              // アンロード直前（任意）
}
```

### EditorContext インタフェース

```java
public interface EditorContext {
    String getText();
    int getCursorRow();
    int getCursorCol();
    int length();
    void insertAtOffset(int offset, String text);
    void deleteRange(int startOffset, int endOffset);
    void setStatusMessage(String message);
}
```

### PluginLoader の主要 API

| メソッド | 動作 |
|---|---|
| `loadPlugin(Path sourceFile)` | .java をコンパイル→URLClassLoader でロード→プラグインを返す |
| `executePlugin(String name, EditorContext ctx)` | 名前でプラグインを呼び出す |
| `unloadPlugin(String name)` | `onUnload()` 呼び出し→`URLClassLoader.close()` |
| `isLoaded(String name)` | ロード済み確認 |
| `loadedPluginNames()` | 全ロード済みプラグイン名を返す |
| `unloadAll()` | 全プラグインをアンロード |

### ModalEditor への追加メソッド

```java
public void insertAtOffset(int offset, String text)   // ピーステーブルに挿入
public void deleteRange(int startOffset, int endOffset) // ピーステーブルから削除
public void setStatusMessage(String message)            // ステータスバーに表示
```

---

## 設計判断（記録）

| 判断 | 理由 |
|---|---|
| 1プラグイン1 URLClassLoader | プラグイン間クラス競合回避・unload 可能にするため |
| EditorContext インタフェースで疎結合 | ModalEditor 内部変更がプラグイン API に波及しないよう |
| SecurityManager 不使用 | Java 21 では削除済み。v1 は信頼ユーザー向けで可 |
| クラスファイル出力先を一時ディレクトリ | ソースと分離し複数バージョン共存を防ぐ |
| クラス名をファイル名から推定 | パッケージなしプラグイン（v1 想定）で十分かつシンプル |

---

## 既知の制限と次フェーズ課題

| 制限 | 詳細 | 対応予定 |
|---|---|---|
| `:plugin <name>` コマンド未接続 | `PluginLoader.executePlugin()` は実装済みだが `ModalEditor` の COMMAND モード処理に未接続 | ④ または次フェーズで統合 |
| プラグイン自動ロード未実装 | `~/.vimacs/plugins/` の監視・自動ロードなし | ⑥ plugin-api-design |
| パッケージ付きプラグイン非対応 | `package` 宣言があると `Class.forName(className)` が失敗する | 必要になったら対応 |
| プラグイン間依存未対応 | プラグインAがプラグインBのクラスを参照できない | ⑥ 以降 |

---

## 次フェーズの候補と推奨順序

### 優先度1：④ keymap-conflict-resolution（着手解禁）

**概要**: Vim 式モーダルキー（hjkl / i / v など）と Emacs 式カーソル移動（Ctrl+F/B/N/P）の
キーマップ定義を、文字列ハードコードではなく設定可能なキーマップレジストリに移行する

**着手条件**: ✅（②③完了により解禁）

**主要タスク**:
```
1. .claude/skills/keymap-conflict-resolution/SKILL.md 新規作成
   - キーマップ競合の定義と解決方針
   - KeyBinding レコード設計
   - モード別キーマップレジストリ設計

2. src/dev/vimacs/editor/ に KeymapRegistry.java 新規作成
   - モード別キーバインド定義
   - デフォルトキーマップ（Vim + Emacs 混在）

3. ModalEditor のキー処理をレジストリ経由に段階移行
   - processNormalKey / processInsertKey をリファクタ

4. テスト: KeymapRegistryTest.java（キーバインドの登録・上書き・競合検出）
```

---

### 優先度2：③ v2 — `:plugin` コマンド統合

**概要**: ModalEditor の COMMAND モードに `:plugin <name>` を追加し、
PluginLoader と接続することで実際にコマンドからプラグインを呼べるようにする

**主要タスク**:
```
1. ModalEditor にコンストラクタ引数 or setter で PluginLoader を渡す
2. processCommandKey に :plugin / :load-plugin のケースを追加
3. Main.java で PluginLoader を生成し ModalEditor に渡す
4. 統合テスト: ModalEditorTest にプラグイン呼び出しケースを追加
```

---

### 優先度3：⑦ editor-testing-strategy（パフォーマンステスト）

**概要**: 100万行スケールでの insert/delete 速度測定・境界値テストの整備

---

## セッション開始時の確認フロー

```bash
# 1. テスト確認
./scripts/test.sh 2>&1 | grep -E "^(PASS|FAIL|=== Summary)"
# → PASS: 208 / 208 を確認

# 2. ブランチ状態確認
git log --oneline -5

# 3. 作業ブランチ作成
git checkout main
git fetch origin
git checkout -b claude/<feature>-<8char-id>
```

---

## 関連ドキュメント一覧

| ファイル | 用途 |
|---|---|
| `CLAUDE.md` | プロジェクト全体・技術制約・ロードマップ |
| `docs/session-log.md` | 全セッション履歴・設計判断ログ |
| `docs/handover-extension-runtime-v1.md` | 本ドキュメント |
| `docs/handover-gui-pipeline-v3.md` | ⑤ v3 完了引継書 |
| `.claude/skills/extension-language-runtime/SKILL.md` | プラグイン機構設計書 |
| `.claude/skills/gui-rendering-pipeline/SKILL.md` | GUI 描画設計書 |
| `.claude/skills/editor-buffer-architecture/SKILL.md` | バッファ設計書 |
