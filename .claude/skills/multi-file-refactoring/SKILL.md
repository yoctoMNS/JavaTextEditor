---
name: multi-file-refactoring
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、:renameコマンドによるプロジェクト全体にまたがる識別子の一括リネームを設計・実装する際に使用する。「複数ファイルにまたがるリネームをしたい」「識別子を安全に一括置換したい」「語境界マッチで誤置換を防ぎたい」「*rename*疑似バッファの表示形式を変えたい」といった相談、またRenameRefactorer/RenameResultやModalEditorのexecuteRename周辺を触る作業に着手する前に、必ず最初に参照すること。"
---

# シンボル単位の複数ファイルリファクタリング（`:rename`）

## このスキルが解決すること

作業ディレクトリ配下の全テキストファイルを対象に、識別子（クラス名・メソッド名・変数名等）を
語境界付きで一括置換する。`:rename <oldName> <newName>` コマンド1つで、型解析（フルコンパイル）
を行わずに `project-wide-search` の `ProjectSearcher` を土台にした軽量な文字列置換として実装する。

---

## キーバインド一覧

| コマンド | モード | 動作 |
|---|---|---|
| `:rename <oldName> <newName>` | COMMAND | `oldName` を語境界付きで検索し、ヒットした全ファイルで `newName` へ一括置換・上書き保存する |

専用の NORMAL モードキーは持たない（`:s` 置換コマンドのようなバッファ内単位ではなく、
最初からファイル横断の一括操作として設計されているため）。

---

## 実装アーキテクチャ

### 3フェーズ構成（`RenameRefactorer.rename()`、`src/dev/javatexteditor/refactor/RenameRefactorer.java`）

```java
public List<RenameResult> rename(Path baseDir, String oldName, String newName)
```

1. **Phase 1（発見）**: `\boldName\b`（`Pattern.quote(oldName)` を語境界で囲んだパターン）を
   `ProjectSearcher.search(baseDir, searchPattern)` に渡し、一致した `SearchResult` から
   対象ファイルのパスを `LinkedHashMap`（発見順を保持し重複を除去）で収集する。
   `ProjectSearcher` は `project-wide-search` スキルが定義するエンジンをそのまま再利用する
   （2MB 上限・`SKIP_DIRS`・CASE_INSENSITIVE 等の特性もそのまま引き継ぐ。ただしタイムアウトは
   `:rename` 自体には掛かっていない点に注意。詳細は下記「注意点」参照）。
2. **Phase 2（置換）**: 対象ファイルごとに `Files.readString()` でファイル全体を1つの文字列
   として読み込み、同じ語境界パターンで `Matcher.appendReplacement()`/`appendTail()` を使い
   置換件数を数えながら全置換する。改行コードは変更しない（ファイル全体を文字列として扱う
   ため、元の改行文字がそのまま保持される）。
3. **Phase 3（適用）**: `Files.writeString()` で UTF-8 として上書き保存する。読み込み失敗
   （`MalformedInputException` = 非UTF-8ファイル、その他 `IOException`）・書き込み失敗は
   いずれも例外を投げず `RenameResult(relPath, count, false, errorMessage)` として結果に
   含める（1ファイルの失敗が他ファイルの処理を止めない）。

### `RenameResult`（record）

```java
public record RenameResult(String filePath, int replacementCount, boolean success, String errorMessage) {
    public String toDisplayLine() {
        return success ? filePath + ": " + replacementCount + " replacement(s)"
                        : filePath + ": ERROR — " + errorMessage;
    }
}
```

`rename()` の戻り値は**Phase 1 で1件でも一致したファイルのみ**を含む（一致0件のファイルは
リストに登場しない）。ただし Phase 2 の語境界マッチが理論上ゼロ件になった場合（Phase 1 の
`ProjectSearcher` の一致判定と Phase 2 の `Pattern` が完全に同一パターンのため通常は起きない）
も `success=true, replacementCount=0` として結果に含む安全策になっている。

### `ModalEditor.executeRename()` — 疑似バッファ表示

```java
r.onPrefix("rename ", this::executeRename);
```

（`CommandRegistry` への登録。`multi-file-refactoring` 第3弾リファクタリングの表化以降の形。
詳細は `docs/decision-log.md` の「ModalEditor 神クラス解体リファクタリング 第3弾」参照）

`executeRename(args)` は引数を `\s+` で2分割し `oldName`/`newName` を取り出す（不足時は
`E: usage: rename <oldName> <newName>`）。結果は `RenameRefactorer.buildDisplayText()` が
組み立てた `*rename* <old> → <new> — N file(s), M replacement(s)[, K error(s)]` ヘッダ行 +
`RenameResult.toDisplayLine()` を1行ずつ並べたテキストとして `buffer` を直接差し替える
（`:grep`・`\f`・`\g` と同じ「疑似バッファへの直接差し替え」パターン。`pushBuffer()` は呼ばない
ため `Ctrl+U`/`Ctrl+P` の履歴には積まれない）。

`*rename*` バッファは `*grep*`/`*file-search*` と異なり **Enter によるジャンプ機能を持たない**
（`grepResults`/`fileNameResults` のいずれにも結果を保持しないため）。これは意図的な設計で、
リネームは実行と同時にディスクへ書き込み済み（Phase 3）であり、`*rename*` バッファは実行結果の
サマリレポートという位置づけにとどまる。

---

## 注意点

- **型解析を行わない**: フルコンパイル（`javax.tools.JavaCompiler` による意味解析）は使わず、
  単純な語境界マッチ（`\bidentifier\b`）のみで対象を判定する。そのため、コメント・文字列
  リテラル中の同名文字列や、スコープの異なる同名ローカル変数も無差別に置換される
  （Java の binding 解決を要する厳密なリネーム＝`symbol-definition-navigation` の
  バインディング解決とは異なるレイヤーの機能であることに注意）。
- **`:rename` 自体にはタイムアウトが掛かっていない**: `Shift+K`/`gr`/`:grep` が
  `ModalEditor.withTimeout()`（1500ms）で保護されているのに対し、`executeRename()` は
  `RenameRefactorer.rename()` を直接呼び出しており同様の保護は入っていない。巨大な作業
  ディレクトリでは EDT が長時間ブロックされる可能性がある（既知のギャップ、修正は今後の課題）。
- **Undo は各ファイルの書き込み単位**: `:rename` はディスクへ直接書き込むため、エディタの
  Undo（`u`）でリネームを取り消すことはできない（現在編集中のバッファ自体は `Files.writeString()`
  の対象になっていても、`UndoablePieceTable` の undo スタックとは無関係にディスクが変わる）。
  取り消したい場合は Git 等の外部バージョン管理に頼る必要がある。
- **非UTF-8ファイルはスキップ**: Phase 1 の `ProjectSearcher` 側で既に UTF-8 として読めない
  ファイルは除外されるが、念のため Phase 2 側でも `MalformedInputException` を個別に捕捉し
  `"not UTF-8 text"` エラーとして結果に含める（バイナリファイルの誤破壊を防ぐ二重の防御）。

---

## テスト方針

`test/dev/javatexteditor/refactor/MultiFileRefactoringTest.java`（25テスト、ロードマップ⑭参照）。

- **`RenameRefactorer` 単体テスト**: 基本置換・複数ファイルにまたがる置換・語境界マッチ
  （部分一致しないこと）・一致なし・改行コードの保持・1行に複数出現する場合・空の
  oldName/newName での例外・存在しないディレクトリ・`RenameResult`/`buildDisplayText()` の
  表示フォーマット。
- **`ModalEditor` 統合テスト**: `:rename` コマンドの基本動作・引数なし/引数1つのみのエラー
  メッセージ・一致なしメッセージ・`*rename*` 疑似バッファの表示内容検証。

---

## 関連Skill

- **`project-wide-search`**: `RenameRefactorer` が Phase 1（対象ファイルの発見）で使う
  `ProjectSearcher` エンジン自体の仕様（2MB 上限・`SKIP_DIRS`・並列 grep・タイムアウト）は
  こちらが一次情報源。
- **`symbol-definition-navigation`**: `K`（定義ジャンプ）・`gr`（参照一覧）は型・スコープを
  考慮した（バインディング解決またはヒューリスティックによる）ジャンプを提供する。`:rename` の
  単純な語境界マッチとは解決精度のレイヤーが異なる点に注意し、両者を混同しないこと。
- **`vim-substitution`**: `:s`/`%s` はバッファ1つの中での正規表現置換（Vim互換の `g`/`i` フラグ・
  `\1`/`&` 置換）を提供する。`:rename` はファイル横断・語境界固定という別の設計思想の機能。
