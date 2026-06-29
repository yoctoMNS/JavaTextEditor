# 次のセッション向けプロンプト

## 現在の状態

Java SE 製テキストエディタ（Vim モーダル編集 + Emacs 拡張性）の開発を続けています。
直前のセッションで「診断行ジャンプ（`[g` / `[d`）」を実装しました。

- ブランチ: `claude/new-session-24zizb`（main にマージ済み）
- テスト: 21 クラス全 PASS（931 テストケース）

## 実装済み機能（抜粋）

| # | Skill | 状態 |
|---|---|---|
| ① | editor-buffer-architecture | ✅ |
| ② | modal-editing-engine | ✅ |
| ③ | extension-language-runtime | ✅ |
| ④ | keymap-conflict-resolution | ✅ |
| ⑤ | gui-rendering-pipeline | ✅ |
| ⑥ | plugin-api-design | ✅ |
| ⑦ | editor-testing-strategy | ✅ |
| ⑧ | java-source-analysis | ✅ |
| ⑨ | javac-compile-integration | ✅ |
| ⑩ | jdk-api-navigation | ✅ |
| ⑪ | javadoc-viewer | ✅ |
| ⑬ | project-wide-search | ✅ |
| ⑭ | multi-file-refactoring | ✅ |
| ⑯ | auto-import-handler | ✅（挿入 + 削除 + organize imports） |
| - | 診断行ジャンプ（[g/[d） | ✅ |

## 次に実装すべき候補

### A. コードフォーマット（`:fmt`）
カーソル行または選択範囲のインデントを自動整形。
- INSERT モードで `}` を挿入したときのインデント縮小は実装済み
- 次は複数行の一括インデント整形（選択範囲に対して `Tab` / `Shift+Tab`）
- Visual Line モードで `>` / `<` でインデント増減（Vim 互換）

### B. 文字列検索（`/pattern`）
NORMALモードで `/` を押して検索文字列を入力し、`n` / `N` で次/前のマッチへジャンプ。
- コマンドモードと似た UI が流用できる
- ハイライト表示は EditorCanvas への拡張が必要

### C. LSP クライアント（Language Server Protocol）
jdt.ls（Eclipse JDT Language Server）などとソケット通信し、より高精度な補完・診断を取得。
- 現在の CompileAnalyzer は javac 直呼び出しで十分機能しているが、LSP に置き換えると補完や定義ジャンプが可能になる
- 実装コストが高く、外部プロセス起動が必要

### D. マクロ記録/再生（`q` / `@`）
NORMALモードで `qa` でキー操作の記録を開始し、`q` で停止、`@a` で再生。
- Vim の q/@ マクロに相当
- キー列をリストとして保持し、再生時に processKey() を順次呼び出す実装で実現可能

### E. ファイルツリー表示（`:tree`）
カレントディレクトリのファイルツリーを `*tree*` 疑似バッファで表示し、Enter でファイルを開く。
- ProjectSearcher の Files.walkFileTree() を流用できる
- `*grep*` バッファのパターンを踏襲

## ビルドとテストの実行方法

```bash
./scripts/build.sh
DISPLAY=:99 Xvfb :99 -screen 0 1280x800x24 &
DISPLAY=:99 JAVA_TOOL_OPTIONS="" ./scripts/test.sh
```

## 重要な制約（CLAUDE.md より）

- Java 21 SE 標準 API のみ（外部ライブラリ禁止）
- ビルドツール不使用（javac 直接呼び出し）
- テストフレームワーク不使用（main() メソッド形式）
- 実装前に `.claude/skills/` 配下の SKILL.md を確認すること
