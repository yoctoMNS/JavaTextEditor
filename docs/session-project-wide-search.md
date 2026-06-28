# セッションログ: Skill ⑬ project-wide-search

## 実施日

2026-06-28

## 目標

エディタ内から作業ディレクトリ配下のファイルを grep 的に検索し、結果をエディタ内で閲覧・ジャンプできるようにする。

## ブランチ

`claude/project-wide-search`

## 実装内容

### 新規ファイル

#### `src/dev/vimacs/search/SearchResult.java`

検索結果1件を表すイミュータブルな record。

```java
public record SearchResult(String filePath, int lineNumber, String lineContent) {
    public String toDisplayLine() {
        return filePath + ":" + lineNumber + ": " + lineContent;
    }
}
```

#### `src/dev/vimacs/search/ProjectSearcher.java`

Java SE 標準の `Files.walkFileTree()` と `java.util.regex.Pattern` を組み合わせた同期全文検索エンジン。

- **バイナリスキップ**: 先頭 8KB を読んで NUL バイト（`\0`）が含まれるファイルをスキップ
- **エンコードエラースキップ**: `MalformedInputException` を捕捉して UTF-8 非対応ファイルを静かにスキップ
- **ディレクトリスキップ**: `.git`・`build`・`target` を `SKIP_SUBTREE` で除外（高速化）
- **相対パス**: `baseDir.relativize(file)` でベースディレクトリからの相対パスを返す。OS 依存の区切り文字を `/` に統一

#### `test/dev/vimacs/search/ProjectSearchTest.java`

19テストケース。

### 変更ファイル

#### `src/dev/vimacs/editor/ModalEditor.java`

| 変更 | 内容 |
|------|------|
| import 追加 | `ProjectSearcher`, `SearchResult`, `PatternSyntaxException` |
| フィールド追加 | `projectSearcher: ProjectSearcher`、`grepResults: List<SearchResult>` |
| `processNormalKey()` | `grepResults != null` かつ `VK_ENTER` のとき `jumpToGrepResult()` を呼ぶ |
| `executeCommand()` | `:grep <pattern>` 分岐を追加 |
| `loadFromFile()` | ファイルを開くとき `grepResults = null` にリセット |
| `executeGrep()` 追加 | パターン検証→検索→バッファ生成 |
| `jumpToGrepResult()` 追加 | `cursorRow - 1` で結果インデックス特定→ファイル読み込み→行ジャンプ |

## 設計判断

### 同期 vs 非同期

最初の実装では同期検索を採用した。バックグラウンドスレッドを使う方がUIをブロックしないが、以下の理由で同期を選択:

1. `ModalEditor` はメインスレッドで動作しており、Swing の `invokeLater` と組み合わせた非同期設計は複雑さを増す
2. 現在の検索対象はローカルファイルシステムで、数百〜数千ファイル規模であれば同期でも十分高速
3. 将来的に遅さが問題になれば `Thread.ofVirtual().start()` + `SwingUtilities.invokeLater()` パターン（`CompileAnalyzer` と同じ方式）で非同期化できる

### grep 結果バッファの管理方法

疑似バッファ（`*grep*`）を `UndoablePieceTable` に読み込み、`grepResults` フィールドで元の `SearchResult` リストを保持する方式を採用。

- **理由**: 既存の描画・スクロール・カーソル移動がそのまま使える
- **トレードオフ**: grep 結果バッファでアンドゥが効いてしまうが、これは許容範囲。`:e` や `:grep` で再度切り替えれば問題なし

### ヘッダ行のジャンプ不可

row=0 はヘッダ行（`*grep* /pattern/ — N match(es)`）であり、ジャンプ対象外。`resultIdx < 0` でガードして `E: no result at this line` を返す。

### .gitignore 非対応

最初のバージョンでは `.gitignore` の解析は行わず `.git/build/target` ディレクトリのスキップのみとした。対応する場合は `git ls-files` を呼ぶか独自パーサーが必要になるが、外部コマンド呼び出しは CLAUDE.md の制約に反しないため将来の拡張候補。

## テスト結果

| テストクラス | 件数 | 結果 |
|---|---|---|
| `ProjectSearchTest` | 19 | PASS |
| 既存テスト（全クラス） | 711 | PASS |
| **合計** | **730** | **全 PASS** |

> RobotKeyInputTest は headless 環境のためスキップ（Xvfb 仮想ディスプレイが必要）。
> これは既存仕様通りであり、今回の変更による影響なし。

## CLAUDE.md / ロードマップへの反映

CLAUDE.md のロードマップ表で Skill ⑬ `project-wide-search` を ✅ 完了に更新すること（本セッションでは main ブランチへのマージ後に更新する）。

## 次のタスク候補

| # | Skill 名 | 概要 | 依存 |
|---|---|---|---|
| ⑫ | `openjdk-source-tracing` | JNI/HotSpot レベルのソーストレース（`native` メソッドの JDK ソースへジャンプ） | ⑩ |
| ⑭ | `multi-file-refactoring` | シンボル単位の複数ファイルリファクタリング（rename, extract など） | ①⑧ |
