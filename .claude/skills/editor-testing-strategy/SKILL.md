---
name: editor-testing-strategy
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、自作テストハーネス（mainメソッド形式・JUnit等の外部フレームワーク不使用）でテストを設計・追加・実行する際に使用する。「新しいテストクラスを追加したい」「境界値テストに何を含めるべきか」「大規模ファイルのパフォーマンステストの閾値をどう決めるか」「テストがO(n²)で遅い」といった相談、また*Test.javaという名前のクラスを新規作成する作業に着手する前に、必ず最初に参照すること。"
---

# editor-testing-strategy

## 概要

自作テストハーネス（`main`メソッド形式）で境界値・パフォーマンス・整合性を体系的に検証する戦略。
JUnit等の外部フレームワークは使用しない。

## テストクラス構成

```
test/dev/javatexteditor/
├── buffer/
│   ├── PieceTableTest.java          # 既存: 正常系・基本境界値 (15テスト)
│   ├── PieceTableEdgeCaseTest.java  # 新規: 空バッファ・多数操作・境界削除 (46テスト)
│   ├── UndoablePieceTableTest.java  # 既存: アンドゥ/リドゥ基本動作 (11テスト)
│   └── UndoRedoDeepTest.java        # 新規: 深いアンドゥチェーン・交互操作 (20テスト)
├── editor/
│   ├── ModalEditorTest.java         # 既存: モーダル編集全機能 (151テスト)
│   ├── ModalEditorEdgeCaseTest.java # 新規: カーソルクランプ・マルチバイト・深いアンドゥ (23テスト)
│   └── KeymapRegistryTest.java      # 既存: キーバインド解決 (46テスト)
├── extension/
│   ├── PluginLoaderTest.java        # 既存: 動的コンパイル (9テスト)
│   └── EditorContextApiTest.java    # 既存: プラグインAPI (39テスト)
├── performance/
│   └── LargeFileTest.java           # 新規: 大規模ファイルパフォーマンス (12テスト)
└── ui/
    └── EditorCanvasTest.java        # 既存: GUI描画 (22テスト)
```

> ⚠️ 上記の構成は本Skill完了時点（⑦）のスナップショット。その後のSkillでテストクラスは増え続けており（2026-07-04時点で29クラス）、最新の一覧は `find test -name "*Test.java"` で確認すること。命名規約 `*Test.java` に従えば `scripts/test.sh` が自動検出する。

## テストハーネス規約

各テストクラスは以下の構造を持つ：

```java
public class XxxTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) {
        testSomething();
        // ...
        int fail = total - pass;
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) System.exit(1);
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name + ...);
        if (ok) pass++;
    }
}
```

`scripts/test.sh` は `build/` 配下の `*Test.class`（内部クラス除く）を自動検出して実行する。
新しいテストクラスを追加した場合、`*Test.java` という命名にすれば自動的に実行対象になる。

## 境界値テスト指針

### PieceTableEdgeCaseTest で確認する事項

| ケース | 確認内容 |
|---|---|
| 空バッファ | `getText()==""`, `length()==0`, `getTextInRange(0,0)==""`, `offsetOfLine(0)==0` |
| 空バッファへの挿入→削除 | 元の空状態に戻ること |
| 1文字バッファ | 削除後に空になること |
| 先頭・末尾の削除 | 境界でのピース分割が正しく動作すること |
| ゼロ長操作 | `insert(n, "")` と `delete(n, 0)` は no-op |
| `getTextInRange` 境界値 | 空範囲・先頭1文字・末尾1文字・全体・ピースまたぎ |
| `offsetOfLine` 境界値 | 改行なし・末尾改行あり・空バッファ・存在しない行番号 |
| 多数の小さな挿入 | 1000回挿入後の整合性（ピース爆発を許容） |
| 改行のみの文書 | `\n\n\n` での行オフセット計算 |

### UndoRedoDeepTest で確認する事項

| ケース | 確認内容 |
|---|---|
| 50回アンドゥ | 全て undone 後に元のテキストに戻ること |
| アンドゥ/リドゥ交互 | 交互操作でテキストが常に整合すること |
| 空スタックのアンドゥ | no-op で例外が出ないこと |
| 新規編集でリドゥ無効化 | 編集後 `canRedo()==false` |
| 全アンドゥ→全リドゥ | 複雑な編集シーケンスで完全に復元できること |

### ModalEditorEdgeCaseTest で確認する事項

| ケース | 確認内容 |
|---|---|
| 空バッファのカーソル移動 | 全方向で `(0,0)` にクランプ |
| 1行末端・先頭クランプ | NORMALモードで `col ≤ lineLen-1` |
| 削除後クランプ | 行数が減った後もカーソルが有効範囲内 |
| アンドゥ後クランプ | アンドゥでバッファが変化後もカーソルが有効範囲内 |
| 全角文字移動 | Java の `char` 単位で3文字の「あいう」を正しく扱う |
| 文書境界移動 | 先頭行から上・最終行から下が no-op |
| NORMALモード末端クランプ | ESC 後に `col == lineLen-1` |

## パフォーマンステスト指針

### LargeFileTest の各テストとその意図

| テスト | シナリオ | 閾値 |
|---|---|---|
| `testOpen100kLines` | 10万行の文字列をPieceTableに渡す（ファイルopen相当） | 500ms |
| `testInsertAtBeginning1k` | 1000行文書の先頭に1000回挿入 | 500ms |
| `testDeleteFromEnd1k` | 2000行文書の末尾から1000行削除 | 500ms |
| `testGetTextOn100kLines` | 10万行のPieceTableから `getText()` | 1000ms |
| `testUndoRedo50Times` | 1万行文書で50回編集→50回アンドゥ | 500ms |
| `testOffsetOfLineLargeDocument` | 1万行文書で `offsetOfLine` を1000回呼び出し | 1000ms |

### 注意: O(n²) になる操作

現在の PieceTable 実装は各 `insert()` でピースリストを線形走査する。
`t.insert(t.length(), text)` を `n` 回繰り返すと：
- `t.length()` の呼び出し自体が O(pieces数)
- `insert` の内部ループも O(pieces数)
- 合計 O(n²) になる

**回避策**: テスト側で挿入オフセットを自前で追跡するか、大きなテキストはコンストラクタに渡してから編集する。

```java
// NG: t.length() を毎回呼ぶと O(n²)
for (int i = 0; i < N; i++) t.insert(t.length(), "...");

// OK: オフセットを追跡する O(n) 相当
int offset = 0;
for (int i = 0; i < N; i++) {
    String s = "...";
    t.insert(offset, s);
    offset += s.length();
}
// ただしこれでも insert の内部ループが O(pieces) なので最悪 O(n²)

// ベスト: 大きなファイルのテストはコンストラクタを使う
StringBuilder sb = new StringBuilder();
for (int i = 0; i < N; i++) sb.append("...");
PieceTable t = new PieceTable(sb.toString()); // O(1)
```

> **追記（2026-07 軽量化リファクタリング Phase 1）**: `PieceTable.insert()` に連続挿入のピース結合が、
> `length()` に O(1) キャッシュが入ったため、上記の「NG」パターン（`t.insert(t.length(), ...)` の反復）は
> 連続タイピングに関しては O(n) 相当で完走するようになった（`LargeFileTest.testTypingAtLengthOffset20k`
> が回帰テストとして固定）。ただしランダムな位置への編集の蓄積ではピース数が増え、線形走査のコストが
> 戻ってくる点は変わらないため、「大きな初期テキストはコンストラクタに渡す」指針は引き続き有効。

## マルチバイト文字の扱い

Java の `String` は UTF-16 で、全角文字（U+0000〜U+FFFF）は `charAt()` 1文字 = オフセット1。
サロゲートペア（絵文字など U+10000以上）は `length()` で2カウントされる点に注意。

本エディタは Java の `char` 単位でオフセットを管理しているため、
テストでは全角文字（「あいう」等）を使って `char` 単位の一貫性を確認する。
サロゲートペアのテストは将来の課題。

## 実装経緯

- ①〜⑥ のSkillで正常系・基本境界値は十分カバーされていた
- 本Skillでは「そのまま実行したら壊れるかもしれないが誰も試していないケース」を網羅
- パフォーマンステストは実際の閾値を明示し、将来の最適化のベースラインとして活用する
