# 次セッション用 Claude Code プロンプト（⑤ v3 完了後）

作成日: 2026-06-25
対象フェーズ: ③ extension-language-runtime または ⑦ editor-testing-strategy

---

## プロンプト本文

```
# 作業引継：Vimacs エディタ ⑤ v3 完了後セッション

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
| ③ extension-language-runtime | ❌ 未着手 | - |
| ④ keymap-conflict-resolution | ⏳ ③待ち | - |
| ⑤ gui-rendering-pipeline v3 | ✅ 完了 | 22 |

合計テスト: PASS 199 / 199

## セッション開始手順（必ず実行）

1. テスト確認:
   ./scripts/test.sh 2>&1 | grep -E "^(PASS|FAIL|=== Summary)"
   → PASS: 199 / 199 を確認

2. 引継書を読む:
   docs/handover-gui-pipeline-v3.md

3. 作業ブランチを作成:
   git checkout -b claude/<feature>-<8char-id>

## 今セッションで実施するタスク

[以下のどちらかを選んで着手する]

### 選択肢A: ③ extension-language-runtime（推奨）
目的: javax.tools.JavaCompiler を使った Java 動的コンパイルによるプラグイン機構を設計・実装する
→ ③完了で④（キーマップ競合解決）の着手が解禁される

実装スコープ:
1. .claude/skills/extension-language-runtime/SKILL.md を新規作成
   - EditorPlugin インタフェース仕様
   - JavaCompiler API / URLClassLoader の使い方
   - プラグインのライフサイクル設計
   - セキュリティモデル
2. src/dev/vimacs/extension/ パッケージを新規作成
   - EditorPlugin.java（インタフェース）
   - PluginLoader.java（動的コンパイル・ロード）
3. テスト: test/dev/vimacs/extension/PluginLoaderTest.java（5+ ケース）

### 選択肢B: ⑦ editor-testing-strategy
目的: 境界値テスト・大規模ファイルパフォーマンステストの戦略を整備する

実装スコープ:
1. .claude/skills/editor-testing-strategy/SKILL.md を新規作成
2. test/dev/vimacs/performance/PerformanceTest.java（100万行レベルの速度測定）
3. 境界値テストケースを既存テストに追加

## 実装完了時のチェックリスト

- [ ] ./scripts/test.sh で PASS: 199+ / 199+ (既存テスト回帰なし)
- [ ] ./scripts/run.sh で起動確認
- [ ] docs/session-log.md に追記
- [ ] README.md 更新
- [ ] git commit → main にマージ → push
- [ ] docs/handover-*.md 作成
- [ ] docs/next-session-prompt-*.md 作成

## 主要ファイル一覧（参照用）

| ファイル | 役割 |
|---|---|
| src/dev/vimacs/Main.java | JSplitPane 2ペイン・Ctrl+W でフォーカス切り替え |
| src/dev/vimacs/buffer/PieceTable.java | ピーステーブル（getTextInRange/offsetOfLine 実装済み） |
| src/dev/vimacs/buffer/UndoablePieceTable.java | アンドゥ/リドゥ（スナップショット方式） |
| src/dev/vimacs/editor/ModalEditor.java | 5モード管理・ファイルI/O・カーソル管理 |
| src/dev/vimacs/ui/EditorCanvas.java | 描画・縦横スクロール管理（scrollRow/scrollCol） |
| src/dev/vimacs/ui/Theme.java | LIGHT_MODE/DARK_MODE 配色 |

## 注意点

- ③の実装は「設計書（SKILL.md）を先に書き切ってから実装に入る」こと
- PluginLoader は javax.tools.JavaCompiler (JDK標準) を使うこと（外部ライブラリ禁止）
- URLClassLoader でロードしたクラスはアンロード不可（JVM仕様）。再ロードはクラスローダーごと破棄・再作成
- セキュリティ: 最初の実装ではシンプルなインタフェース実装確認のみで可。SecurityManager は Java 17+ で非推奨なので使わない
```

---

## 補足：プロンプトの使い方

このプロンプトを次のセッション開始時に Claude Code に貼り付ける。
「選択肢A/B」の部分は、その時の優先度に応じてどちらかを選ぶか、
または両方実施する旨を追記して渡すと良い。

---

## ③ extension-language-runtime の事前設計メモ（次セッションへの情報提供）

### javax.tools.JavaCompiler の基本パターン

```java
import javax.tools.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;

public class PluginLoader {

    public EditorPlugin load(Path sourceFile) throws Exception {
        // 1. コンパイル
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK 必須（JRE 不可）");

        Path buildDir = Files.createTempDirectory("vimacs-plugin-");
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(buildDir.toFile()));

        Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(sourceFile.toFile());
        boolean success = compiler.getTask(null, fm, null, null, null, units).call();
        if (!success) throw new RuntimeException("コンパイルエラー");

        // 2. ロード
        URLClassLoader loader = new URLClassLoader(
            new URL[]{buildDir.toUri().toURL()},
            Thread.currentThread().getContextClassLoader()
        );

        // クラス名はファイル名から推定（例: MyPlugin.java → MyPlugin）
        String className = sourceFile.getFileName().toString().replace(".java", "");
        Class<?> clazz = loader.loadClass(className);
        return (EditorPlugin) clazz.getDeclaredConstructor().newInstance();
    }
}
```

### EditorPlugin インタフェース（案）

```java
package dev.vimacs.extension;

/**
 * Vimacs プラグインが実装すべきインタフェース。
 * プラグイン作者は Java ファイルでこのインタフェースを実装し、
 * ~/.vimacs/plugins/ に置くことでエディタが自動ロードする（将来）。
 */
public interface EditorPlugin {
    /** プラグイン名。:plugin コマンドや設定で使う識別子 */
    String name();

    /** エディタ起動時（ロード直後）に1度呼ばれる */
    void onLoad(PluginContext ctx);

    /** エディタ終了時に呼ばれる（リソース解放） */
    void onUnload();
}
```

### PluginContext インタフェース（案）

```java
public interface PluginContext {
    /** バッファの内容を取得する（読み取り専用） */
    String getText();

    /** カーソル位置を移動する */
    void moveCursor(int row, int col);

    /** テキストを挿入する（カーソル位置） */
    void insertText(String text);

    /** カスタムコマンドを :コマンド として登録する */
    void registerCommand(String name, Runnable handler);
}
```
