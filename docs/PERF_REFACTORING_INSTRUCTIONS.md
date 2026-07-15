# 軽量性リファクタリング実行指示書（Claude Code 向け）

- 対象計画: `docs/PERF_REFACTORING_PLAN.md`（背景・ゴール・非ゴールはそちらを先に読むこと）
- アンカー検証済みコミット: `780725e`（2026-07-15）
- 本書の読者: このリファクタリングを実行する Claude Code セッション（以下「実行者」）

## 0. この文書の使い方（実行者への契約）

1. 本書は**上から順に実行するだけで Phase 1→2→3 を完遂できる**ように書かれている。設計判断は完了済みであり、実行者が新たな設計判断を行う必要はない。
2. 手順・コードは本書のものを**そのまま**使うこと。改善案を思いついても本書から逸脱しない（逸脱が必要と判断した場合は §1.7 の STOP 手順に従い、作業を止めてユーザーに報告する）。
3. 各フェーズ末尾の**完了ゲートをすべて満たした場合に限り**、ユーザーへの追加確認なしで main へのマージまで進んでよい（§1.5 の事前承認を参照）。
4. フェーズ完了のたびに §7 の進捗チェックリストと CLAUDE.md の状態表を更新すること。

---

## 1. 全フェーズ共通ルール

### 1.1 事前に読むもの（プロジェクト規約: 実装前に関連 SKILL.md を必ず確認）

| フェーズ | 必読 |
|---|---|
| 全フェーズ | `CLAUDE.md`（特に「軽量性リファクタリング計画」節）、`docs/PERF_REFACTORING_PLAN.md` |
| Phase 1 | `.claude/skills/editor-buffer-architecture/SKILL.md`、`.claude/skills/editor-testing-strategy/SKILL.md` |
| Phase 2 | `.claude/skills/gui-rendering-pipeline/SKILL.md`、`.claude/skills/modal-editing-engine/SKILL.md`、`.claude/skills/editor-testing-strategy/SKILL.md` |
| Phase 3 | `.claude/skills/symbol-definition-navigation/SKILL.md`（withTimeout の利用元）、`.claude/skills/editor-testing-strategy/SKILL.md` |

### 1.2 ブランチ運用

- セッションのハーネス（システムプロンプト）が開発ブランチを指定している場合は**そのブランチ**を使う。指定がない場合は `claude/perf-phase1-piecetable` / `claude/perf-phase2-synccanvas` / `claude/perf-phase3-searcher` を使う。
- 各フェーズは必ず最新 main から開始する（前フェーズのブランチに積み上げない）:
  ```bash
  git fetch origin main
  git checkout -B <ブランチ名> origin/main
  ```
- push は `git push -u origin <ブランチ名>`。ネットワークエラー時のみ 2s/4s/8s/16s の指数バックオフで最大4回リトライ。

### 1.3 ビルド・テストコマンド

```bash
./scripts/build.sh                 # src/ 全コンパイル（"Build OK" が出ること）
./scripts/test.sh 2>&1 | tee /tmp/phaseN-test.log   # 全テスト（数分かかる）
java -cp build <FQCN>              # 単一テストクラスの反復実行（test.sh が build を済ませた後）
```

### 1.4 完了ゲートの判定コマンド（全フェーズ共通・機械的に判定する）

```bash
grep "=== Summary" /tmp/phaseN-test.log
# 期待: "=== Summary: <M> class(es) passed, 1 class(es) failed ==="
#（M はベースライン69 ＋ そのフェーズで追加したテストクラス数。failed は必ず 1）

grep -c "\[FAIL\]" /tmp/phaseN-test.log
# 期待: 0（自作ハーネス標準書式のFAILがゼロ）

grep -c "^FAIL \[" /tmp/phaseN-test.log
# 期待: 2（ScrollTest の既知2件のみ。書式が他と異なる）

grep "^FAIL \[" /tmp/phaseN-test.log
# 期待: 次の2行と完全一致
# FAIL [halfPageUp: cursor moved up by 20] expected=20 actual=40
# FAIL [halfPage interleaved: row 40] expected=40 actual=60
```

- 上記4条件＋「そのフェーズで追加した新規テストクラスがログに `PASS:` を出して rc=0」がゲート合格。
- `RobotKeyInputTest` の `[SKIP] ... headless` は正常（FAILではない）。
- **ScrollTest の2件は絶対に修正しない**（仕様判断未決。CLAUDE.md 既知の未接続6）。3件以上/1件以下に変化したら STOP。

### 1.5 main へのマージ（事前承認の根拠と手順）

本指示書に基づく各フェーズの main マージは、2026-07-15 のユーザー指示（「計画書を読みすすめれば、Claude Code が一切迷わず作業を進め自動でテストを行い main ブランチにマージすることができるまで詳細に記載する事」）によって**事前に承認されている**。`.claude/commands/merge-main.md` の「ユーザーが明示的にこのコマンドを実行しない限りマージしない」という要件は、本指示書経由の実行指示によって満たされる。ただし:

- **ゲートを1つでも満たさない状態でのマージは禁止**（修正を試み、それでも満たせなければ STOP）。
- 手順: コミット → push 後、`/merge-main` を実行する（内容は `.claude/commands/merge-main.md` の通り: 既存PR確認 → `mcp__github__create_pull_request` → `mcp__github__merge_pull_request`（merge_method: "merge"） → PR URL とマージコミット SHA を報告）。
- PR 本文は次のテンプレートを使う:
  ```markdown
  ## Summary
  - （そのフェーズの変更点を箇条書き。docs/PERF_REFACTORING_PLAN.md の Phase 節を要約）

  ## Test plan
  - [x] ./scripts/build.sh
  - [x] ./scripts/test.sh — Summary: <M> passed / 1 failed（ScrollTest 既知2件のみ・ベースライン同等）
  - [x] 新規テスト <クラス名> <N>/<N> PASS
  - 性能実測: <LargeFileTest 等の [PERF] 行を貼る>
  ```

### 1.6 アンカー検証（各フェーズの編集前に必ず実行）

本書の「変更前コード」は `780725e` 時点のもの。フェーズ開始時に各対象箇所を Read し、本書の引用と一致することを確認する。**空白や周辺コメント程度の差異**なら現状に合わせて編集を続行してよいが、**構造が変わっている**（メソッドが消えた・分割された・ロジックが別物になった）場合は STOP。

### 1.7 STOP 条件（該当したら作業を止め、状況と差分をユーザーに報告して指示を待つ）

1. アンカー不一致（構造レベル）。
2. ゲート不合格が2回の修正試行後も解消しない。
3. ScrollTest の FAIL 件数が2件から変化した。
4. 本書に書かれていない設計判断が必要になった（例: 新たな競合、想定外のテスト依存）。
5. main とのマージコンフリクトが本書の対象ファイル以外に波及した。

### 1.8 禁止事項（全フェーズ）

- 外部ライブラリ・ビルドツール・JUnit の導入（CLAUDE.md 根本制約）。
- `SwingUtilities.invokeLater` 等による検索の非同期化（テストハーネスの同期契約を壊す。計画書 非ゴール3）。
- undo の粒度・キー割り当て・検索のタイムアウト値（1500ms）・2MB上限・スキップ対象ディレクトリの変更。
- 既存テストの**テキスト内容に関する**アサーションの弱体化・削除。
- ScrollTest への変更。
- ファイルサイズ上限の導入（計画書 非ゴール4）。

---

## 2. Phase 1: PieceTable の結合・キャッシュ

### 2.1 開始手順

```bash
git fetch origin main && git checkout -B <ブランチ名> origin/main
./scripts/build.sh
```

アンカー検証: `src/dev/javatexteditor/buffer/PieceTable.java` を Read し、`insert()` が「`offset <= runningOffset + p.length()` で分岐し結合処理を持たない」こと、`length()` が `pieces.stream()...sum()` であること、`getText()`/`getTextInRange()` に `addBuffer.toString()` があることを確認する。

### 2.2 変更1: `src/dev/javatexteditor/buffer/PieceTable.java` を以下の内容に全置換（Write）

```java
package dev.javatexteditor.buffer;

import java.util.ArrayList;
import java.util.List;

public class PieceTable {
    private final String original;
    private final StringBuilder addBuffer;
    private final List<Piece> pieces;
    // length() のキャッシュ。以前は呼ばれるたびに全ピースを stream().sum() しており
    // ピース数に比例するコストがかかっていた（軽量化リファクタリング Phase 1）。
    // insert()/delete()/restorePieces() だけが更新する。
    private int totalLength;

    public PieceTable(String originalText) {
        this.original = originalText;
        this.addBuffer = new StringBuilder();
        this.pieces = new ArrayList<>();
        if (!originalText.isEmpty()) {
            pieces.add(new Piece(Piece.Source.ORIGINAL, 0, originalText.length()));
        }
        this.totalLength = originalText.length();
    }

    public void insert(int offset, String text) {
        if (text.isEmpty()) return;
        int addStart = addBuffer.length();
        addBuffer.append(text);
        totalLength += text.length();

        int runningOffset = 0;
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            int pieceEnd = runningOffset + p.length();
            if (offset < pieceEnd) {
                // ピース内部への挿入: p を分割して新ピースを挟む
                int splitPoint = offset - runningOffset;
                pieces.remove(i);
                int insertAt = i;
                if (splitPoint > 0) {
                    pieces.add(insertAt++, new Piece(p.source(), p.start(), splitPoint));
                }
                pieces.add(insertAt++, new Piece(Piece.Source.ADD, addStart, text.length()));
                // offset < pieceEnd により splitPoint < p.length() が保証されるため後半は常に非空
                pieces.add(insertAt, new Piece(p.source(), p.start() + splitPoint, p.length() - splitPoint));
                return;
            }
            if (offset == pieceEnd) {
                // ピース境界（p の直後）への挿入。
                // p が追加バッファの末尾（今回 append する直前の終端 addStart）をちょうど指している
                // 場合は、新ピースを作らず p を伸長する（連続タイピングの結合）。
                // この結合が無いと1キー入力ごとにピースが1個ずつ増え続け、insert/getText が
                // 編集回数に比例して遅くなる（セッション累計で O(K^2)）。
                // 条件を「addBuffer 末尾の所有者」に限定しているのは、削除等でピース末尾と
                // addBuffer 末尾がズレた後に誤って結合し、削除済みの文字が復活するのを防ぐため。
                if (p.source() == Piece.Source.ADD && p.start() + p.length() == addStart) {
                    pieces.set(i, new Piece(Piece.Source.ADD, p.start(), p.length() + text.length()));
                } else {
                    pieces.add(i + 1, new Piece(Piece.Source.ADD, addStart, text.length()));
                }
                return;
            }
            runningOffset = pieceEnd;
        }
        // 空文書（pieces が空）への挿入、または文書末尾を超えるオフセット（従来仕様どおり末尾扱い）
        pieces.add(new Piece(Piece.Source.ADD, addStart, text.length()));
    }

    public void delete(int offset, int length) {
        if (length <= 0) return;
        int deleteEnd = offset + length;
        List<Piece> result = new ArrayList<>();
        int runningOffset = 0;
        int removed = 0;

        for (Piece p : pieces) {
            int pieceStart = runningOffset;
            int pieceEnd = runningOffset + p.length();
            runningOffset = pieceEnd;

            boolean noOverlap = (pieceEnd <= offset) || (pieceStart >= deleteEnd);
            if (noOverlap) {
                result.add(p);
                continue;
            }
            int keepBeforeLen = Math.max(0, offset - pieceStart);
            int keepAfterStart = Math.max(pieceStart, deleteEnd);
            int keepAfterLen = pieceEnd - keepAfterStart;

            if (keepBeforeLen > 0) {
                result.add(new Piece(p.source(), p.start(), keepBeforeLen));
            }
            if (keepAfterLen > 0) {
                result.add(new Piece(p.source(), p.start() + (keepAfterStart - pieceStart), keepAfterLen));
            }
            removed += p.length() - keepBeforeLen - Math.max(0, keepAfterLen);
        }
        pieces.clear();
        pieces.addAll(result);
        totalLength -= removed;
    }

    public int length() {
        return totalLength;
    }

    public String getText() {
        StringBuilder result = new StringBuilder(totalLength);
        for (Piece p : pieces) {
            // addBuffer.toString() を使わず CharSequence として範囲 append する。
            // 以前は ADD ピースごとに追加バッファ全体を String へコピーしており、
            // 長い編集セッション後の getText() が「ADDピース数×追加バッファ長」の
            // 無駄なアロケーションを発生させていた（軽量化リファクタリング Phase 1）。
            if (p.source() == Piece.Source.ORIGINAL) {
                result.append(original, p.start(), p.start() + p.length());
            } else {
                result.append(addBuffer, p.start(), p.start() + p.length());
            }
        }
        return result.toString();
    }

    /**
     * 文書全体ではなく指定オフセット範囲だけを返す。
     * 画面に表示する数十行分だけを取り出すことで getText() の全文字列構築コストを避けられる。
     */
    public String getTextInRange(int startOffset, int endOffset) {
        StringBuilder result = new StringBuilder(Math.max(0, endOffset - startOffset));
        int runningOffset = 0;
        for (Piece p : pieces) {
            int pieceEnd = runningOffset + p.length();
            if (pieceEnd > startOffset && runningOffset < endOffset) {
                int from = Math.max(0, startOffset - runningOffset);
                int to = Math.min(p.length(), endOffset - runningOffset);
                if (p.source() == Piece.Source.ORIGINAL) {
                    result.append(original, p.start() + from, p.start() + to);
                } else {
                    result.append(addBuffer, p.start() + from, p.start() + to);
                }
            }
            runningOffset = pieceEnd;
            if (runningOffset >= endOffset) break;
        }
        return result.toString();
    }

    /**
     * N行目が何文字目（0-based オフセット）から始まるかを返す。
     * ピースを直接走査するため getText() による全文再構築・アロケーションを伴わない
     * （軽量化リファクタリング Phase 1。従来は毎回全文 String を構築していた）。
     */
    public int offsetOfLine(int lineNumber) {
        if (lineNumber == 0) return 0;
        int currentLine = 0;
        int runningOffset = 0;
        for (Piece p : pieces) {
            CharSequence src = (p.source() == Piece.Source.ORIGINAL) ? original : addBuffer;
            int end = p.start() + p.length();
            for (int i = p.start(); i < end; i++) {
                if (src.charAt(i) == '\n') {
                    currentLine++;
                    if (currentLine == lineNumber) {
                        return runningOffset + (i - p.start()) + 1;
                    }
                }
            }
            runningOffset += p.length();
        }
        return totalLength;
    }

    protected List<Piece> getPieces() {
        return List.copyOf(pieces);
    }

    protected void restorePieces(List<Piece> snapshot) {
        pieces.clear();
        pieces.addAll(snapshot);
        // undo/redo でピースリストが丸ごと差し替わるため、キャッシュを再集計する。
        // スナップショットのピース数は結合により小さく保たれるので O(P) でも実質定数。
        int sum = 0;
        for (Piece p : snapshot) sum += p.length();
        totalLength = sum;
    }
}
```

**注意**: `UndoablePieceTable.java`・`Piece.java` は Phase 1 では**変更しない**。

### 2.3 変更2: `test/dev/javatexteditor/buffer/PieceTableTest.java` にテスト追加（Edit）

`// Test 11:` ブロックの後（`int total = 15;` の前）に以下を挿入し、`int total = 15;` を `int total = 26;` に変更する:

```java
        // Test 12: 連続タイピングのピース結合（Phase 1）: 連続insertでピースが増えない
        PieceTable t12 = new PieceTable("");
        t12.insert(0, "a");
        t12.insert(1, "b");
        t12.insert(2, "c");
        pass += check("連続挿入の結合: テキスト", "abc", t12.getText());
        pass += check("連続挿入の結合: ピース数1", "1", String.valueOf(t12.getPieces().size()));

        // Test 13: 文書中間での連続タイピングも結合される
        PieceTable t13 = new PieceTable("AB");
        t13.insert(1, "x");
        t13.insert(2, "y");
        t13.insert(3, "z");
        pass += check("中間連続挿入: テキスト", "AxyzB", t13.getText());
        pass += check("中間連続挿入: ピース数3", "3", String.valueOf(t13.getPieces().size()));

        // Test 14: 離れた位置への挿入は結合されない（正しさ優先）
        PieceTable t14 = new PieceTable("abcdef");
        t14.insert(1, "X");
        t14.insert(4, "Y");
        pass += check("離れた挿入: テキスト", "aXbcYdef", t14.getText());

        // Test 15: 削除で追加バッファ末尾の所有が切れた後の挿入は結合しない（誤結合防止）
        PieceTable t15 = new PieceTable("");
        t15.insert(0, "abc");
        t15.delete(2, 1);       // "ab"（ピース末尾と addBuffer 末尾がズレる）
        t15.insert(2, "d");     // 誤って結合すると "abc"+"d" の断片になり壊れる
        pass += check("削除後の挿入: テキスト", "abd", t15.getText());

        // Test 16: length() キャッシュの整合性（挿入・削除・範囲外にはみ出す削除）
        PieceTable t16 = new PieceTable("hello");
        t16.insert(5, " world");
        t16.delete(0, 6);
        pass += check("length==getText().length()",
            String.valueOf(t16.getText().length()), String.valueOf(t16.length()));
        t16.delete(3, 100);     // 実在部分だけ消える既存仕様
        pass += check("範囲外削除後のlength整合",
            String.valueOf(t16.getText().length()), String.valueOf(t16.length()));

        // Test 17: 結合された連続挿入でも undo 粒度は1操作ずつ（スナップショット互換）
        UndoablePieceTable t17 = new UndoablePieceTable("");
        t17.insert(0, "a");
        t17.insert(1, "b");
        t17.insert(2, "c");
        t17.undo();
        pass += check("結合後undo1回目", "ab", t17.getText());
        t17.undo();
        pass += check("結合後undo2回目", "a", t17.getText());
        t17.redo();
        pass += check("結合後redo", "ab", t17.getText());
```

（`PieceTableTest` は `dev.javatexteditor.buffer` パッケージ内のため protected の `getPieces()` にアクセスできる。import 追加は不要。）

### 2.4 変更3: `test/dev/javatexteditor/performance/LargeFileTest.java` にテスト追加（Edit）

1. 閾値定数ブロックに追記:
```java
    private static final long THRESHOLD_TYPING_20K   = 500;  // 連続タイピング2万キー相当
```
2. `main()` の `testOffsetOfLineLargeDocument();` の直後に追記:
```java
        testSequentialTyping20k();
        testTypingAtLengthOffset20k();
```
3. クラス末尾の `checkPerf` の前にメソッド追加:
```java
    // 連続タイピング2万キー相当（Phase 1 ピース結合の回帰テスト）。
    // 結合が無いと1キーごとにピースが増え、この操作は O(K^2)（数十秒規模）に劣化する。
    static void testSequentialTyping20k() {
        PieceTable t = new PieceTable("");
        long start = System.currentTimeMillis();
        int offset = 0;
        for (int i = 0; i < 20_000; i++) {
            t.insert(offset, "x");
            offset++;
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[PERF] 連続タイピング2万キー: " + elapsed + "ms (threshold=" + THRESHOLD_TYPING_20K + "ms)");
        checkPerf("連続タイピング2万キーが閾値内", THRESHOLD_TYPING_20K, elapsed);
        check("連続タイピング後length==20000", true, t.length() == 20_000);
    }

    // かつて editor-testing-strategy スキルで「NG（O(n²)）」とされていた
    // 「t.length() を毎回呼ぶ」パターンが、length() O(1)化＋ピース結合で実用速度になったことの固定
    static void testTypingAtLengthOffset20k() {
        PieceTable t = new PieceTable("");
        long start = System.currentTimeMillis();
        for (int i = 0; i < 20_000; i++) {
            t.insert(t.length(), "y");
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[PERF] length()参照タイピング2万キー: " + elapsed + "ms (threshold=" + THRESHOLD_TYPING_20K + "ms)");
        checkPerf("length()参照タイピング2万キーが閾値内", THRESHOLD_TYPING_20K, elapsed);
        check("length()参照タイピング後length==20000", true, t.length() == 20_000);
    }
```

### 2.5 検証

```bash
./scripts/build.sh
./scripts/test.sh 2>&1 | tee /tmp/phase1-test.log
java -cp build dev.javatexteditor.buffer.PieceTableTest          # 26/26 PASS
java -cp build dev.javatexteditor.performance.LargeFileTest      # 全PASS・[PERF]実測値を記録
```

- §1.4 のゲート判定を実行（Summary は「70 passed / 1 failed」になるはず。テストクラス追加なしのため）。
- `LargeFileTest` を3回実行し、新規2テストの実測値がいずれも閾値500msの**半分以下**であることを確認（超える場合は STOP。閾値調整はユーザー判断）。実測値はPR本文に記録する。

### 2.6 ドキュメント更新（このフェーズのコミットに含める）

1. **`.claude/skills/editor-buffer-architecture/SKILL.md`**: 「誤解3」ブロックの直後に追記:
   ```markdown
   > **追記（2026-07 軽量化リファクタリング Phase 1）**: 上記の「編集回数に比例してピースが増え続ける」問題のうち、
   > 最頻出の「連続タイピング」は実装済みの対策で解消した。`insert()` は挿入位置が既存 ADD ピースの直後で、
   > かつそのピースが追加バッファ末尾（append 直前の終端）を指している場合に限り、新ピースを作らずピースを
   > 伸長する（結合条件を「addBuffer 末尾の所有者」に限定するのは、削除後の再挿入で削除済み文字が復活する
   > 誤結合を防ぐため）。あわせて `length()` は `totalLength` フィールドで O(1) 化し、`getText()`/
   > `getTextInRange()` は `addBuffer.toString()` を廃止して CharSequence 範囲 append に変更した（ADDピース数×
   > 追加バッファ長のコピーを排除）。undo スナップショット（`List.copyOf` によるピース参照コピー）とは独立の
   > 変更のため、undo 粒度（1insert=1undo）は変わらない（PieceTableTest Test 17 で固定）。本文中のサンプル
   > コードは学習用に結合前の最小実装を保っている。実装の正は `src/dev/javatexteditor/buffer/PieceTable.java`。
   > ランダム位置編集の蓄積によるピース増加は残るため、ピースツリー化は引き続き将来課題。
   ```
2. **`.claude/skills/editor-testing-strategy/SKILL.md`**: 「注意: O(n²) になる操作」節の末尾に追記:
   ```markdown
   > **追記（2026-07 軽量化リファクタリング Phase 1）**: `PieceTable.insert()` に連続挿入のピース結合が、
   > `length()` に O(1) キャッシュが入ったため、上記の「NG」パターン（`t.insert(t.length(), ...)` の反復）は
   > 連続タイピングに関しては O(n) 相当で完走するようになった（`LargeFileTest.testTypingAtLengthOffset20k`
   > が回帰テストとして固定）。ただしランダムな位置への編集の蓄積ではピース数が増え、線形走査のコストが
   > 戻ってくる点は変わらないため、「大きな初期テキストはコンストラクタに渡す」指針は引き続き有効。
   ```
3. **`CLAUDE.md`**: 「軽量性リファクタリング計画」節の状態表の Phase 1 行を「✅ 完了（PieceTableTest 26/26・LargeFileTest 実測値）」に更新。

### 2.7 コミット・マージ

```bash
git add -A && git commit -m "PieceTable: 連続挿入のピース結合・length()キャッシュ・addBufferコピー排除（軽量化Phase 1）"
git push -u origin <ブランチ名>
```
§1.5 の手順で `/merge-main` を実行（PRタイトル: 「PieceTableの連続挿入結合とlength()キャッシュで編集セッションのO(K²)劣化を解消」）。マージ完了後、§7 のチェックリストを更新して Phase 2 へ。

---

## 3. Phase 2: syncCanvas() の全文再構築キャッシュ

### 3.1 開始手順

```bash
git fetch origin main && git checkout -B <ブランチ名> origin/main
./scripts/build.sh
```

アンカー検証: 以下の3点を Read で確認する。
- `ModalEditor.java` の `syncCanvas()` 冒頭が `canvas.setText(buffer.getText());`、途中に `String[] lines = buffer.getText().split("\n", -1);` があること（780725e では 4668〜4693行）。
- `ModalEditor.java` に `private UndoablePieceTable buffer;`（94行）と、`UndoablePieceTable.getVersion()`（insert/delete/undo/redo で増分）が存在すること。
- `EditorCanvas.java` の `setText(String)` が `this.text = text; this.cachedLines = text.split("\n", -1); repaint();` であること（380〜384行）。

### 3.2 変更1: `src/dev/javatexteditor/ui/EditorCanvas.java` — setText オーバーロード（Edit）

変更前:
```java
    public void setText(String text) {
        this.text = text;
        this.cachedLines = text.split("\n", -1);
        repaint();
    }
```
変更後:
```java
    public void setText(String text) {
        setText(text, text.split("\n", -1));
    }

    /**
     * 行分割済み配列を伴う高速経路（ModalEditor.syncCanvas() のキャッシュ用。軽量化 Phase 2）。
     * lines は必ず text.split("\n", -1) と同一内容であること。
     * 渡された配列はコピーせずそのまま保持するため、呼び出し側は以後この配列を変更してはならない。
     */
    public void setText(String text, String[] lines) {
        this.text = text;
        this.cachedLines = lines;
        repaint();
    }
```

### 3.3 変更2: `src/dev/javatexteditor/editor/ModalEditor.java` — キャッシュフィールドとヘルパー（Edit）

`public void syncCanvas() {` の**直前**に以下を挿入する:

```java
    // ===== syncCanvas() 用テキストキャッシュ（軽量化リファクタリング Phase 2） =====
    // buffer.getText() と split("\n", -1) は文書全体を再構築する O(n) 処理で、
    // 以前は syncCanvas() が1キー入力ごとに getText() を2回・split を2回実行していた
    //（EditorCanvas.setText() 内の split を含む）。テキストが変化しないキー（カーソル移動等）
    // では再構築自体が不要なため、UndoablePieceTable.getVersion()（insert/delete/undo/redo で
    // 必ず増分する既存の版数）と buffer インスタンスの参照一致で有効性を判定するキャッシュを持つ。
    // buffer は疑似バッファ切替等で別インスタンスに差し替わることがあるが、outputErrorLinesOwner /
    // binaryModeOwner と同じ「参照一致による自動失効」パターンにより、差し替え箇所（約30箇所）に
    // 一切手を入れずキャッシュも自動失効する。
    private UndoablePieceTable canvasTextOwner = null;
    private long canvasTextVersion = -1;
    private String canvasCachedText = "";
    private String[] canvasCachedLines = { "" };
    // テスト用: キャッシュミスで全文再構築が起きた回数（SyncCanvasCacheTest が参照）
    private long canvasTextRebuildCount = 0;

    private void refreshCanvasTextCache() {
        if (canvasTextOwner == buffer && canvasTextVersion == buffer.getVersion()) {
            return; // テキスト未変更: 再構築しない（カーソル移動等はここを通る）
        }
        canvasCachedText = buffer.getText();
        canvasCachedLines = canvasCachedText.split("\n", -1);
        canvasTextOwner = buffer;
        canvasTextVersion = buffer.getVersion();
        canvasTextRebuildCount++;
    }
```

### 3.4 変更3: `syncCanvas()` 内の2箇所（Edit）

変更前:
```java
    public void syncCanvas() {
        if (canvas != null) {
            canvas.setText(buffer.getText());
```
変更後:
```java
    public void syncCanvas() {
        if (canvas != null) {
            refreshCanvasTextCache();
            canvas.setText(canvasCachedText, canvasCachedLines);
```

変更前:
```java
            String[] lines = buffer.getText().split("\n", -1);
```
変更後:
```java
            String[] lines = canvasCachedLines;
```

さらに直後のコメント行を実装に合わせて更新する。変更前:
```java
            // 上の lines/curLine を再利用し、buffer.getText() の再構築を増やさない。
```
変更後:
```java
            // canvasCachedLines を再利用し、buffer.getText() の再構築を増やさない。
```

### 3.5 変更4: テスト用アクセサ（Edit）

`ModalEditor.java` の `public String getText()            { return buffer.getText(); }` の直後に追加:
```java
    /** テスト用: syncCanvas() のテキストキャッシュが再構築された回数（軽量化 Phase 2）。 */
    public long getCanvasTextRebuildCount() { return canvasTextRebuildCount; }
```

### 3.6 変更5: 新規テスト `test/dev/javatexteditor/editor/SyncCanvasCacheTest.java`（Write）

```java
package dev.javatexteditor.editor;

import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;

/**
 * 軽量化リファクタリング Phase 2（syncCanvas のテキスト再構築キャッシュ）の回帰テスト。
 * テキストが変化しないキー入力（カーソル移動）では buffer.getText() の全文再構築が走らないこと、
 * テキストが変化した場合はキー入力1回につき1度だけ再構築されることを
 * getCanvasTextRebuildCount() で検証する。
 */
public class SyncCanvasCacheTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) {
        testCursorMovementDoesNotRebuild();
        testTypingRebuildsOncePerKey();
        testUndoRebuildsOnce();
        testBufferSwapInvalidatesCache();

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
        System.exit(fail > 0 ? 1 : 0);
    }

    static ModalEditor newEditorWithCanvas(String text) {
        return new ModalEditor(text, new EditorCanvas());
    }

    static void pressChar(ModalEditor ed, char c) {
        ed.processKey(KeyEvent.getExtendedKeyCodeForChar(c), c, 0);
    }

    static void testCursorMovementDoesNotRebuild() {
        System.out.println("[カーソル移動ではテキストを再構築しない]");
        ModalEditor ed = newEditorWithCanvas("line0\nline1\nline2\nline3\nline4");
        pressChar(ed, 'j'); // 初回 syncCanvas でキャッシュ生成
        long base = ed.getCanvasTextRebuildCount();
        for (int i = 0; i < 50; i++) {
            pressChar(ed, 'j');
            pressChar(ed, 'k');
            pressChar(ed, 'l');
            pressChar(ed, 'h');
        }
        check("移動200回で再構築回数が増えない", base, ed.getCanvasTextRebuildCount());
        check("移動後もテキスト不変", "line0\nline1\nline2\nline3\nline4", ed.getText());
    }

    static void testTypingRebuildsOncePerKey() {
        System.out.println("[文字入力はキー1回につき再構築1回]");
        ModalEditor ed = newEditorWithCanvas("abc");
        pressChar(ed, 'i'); // INSERTへ（テキスト不変）
        long base = ed.getCanvasTextRebuildCount();
        pressChar(ed, 'X');
        pressChar(ed, 'Y');
        pressChar(ed, 'Z');
        check("3文字入力で再構築ちょうど3回", base + 3, ed.getCanvasTextRebuildCount());
        check("入力結果", "XYZabc", ed.getText());
    }

    static void testUndoRebuildsOnce() {
        System.out.println("[undo はテキスト変化として1回だけ再構築する]");
        ModalEditor ed = newEditorWithCanvas("abc");
        pressChar(ed, 'i');
        pressChar(ed, 'X');
        ed.processKey(KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED, 0);
        long base = ed.getCanvasTextRebuildCount();
        pressChar(ed, 'u');
        check("undo で再構築1回", base + 1, ed.getCanvasTextRebuildCount());
        check("undo 後のテキスト", "abc", ed.getText());
    }

    static void testBufferSwapInvalidatesCache() {
        System.out.println("[バッファ差し替え（:enew）でキャッシュが自動失効する]");
        ModalEditor ed = newEditorWithCanvas("abc");
        pressChar(ed, 'j'); // キャッシュ生成
        long base = ed.getCanvasTextRebuildCount();
        pressChar(ed, ':');
        pressChar(ed, 'e');
        pressChar(ed, 'n');
        pressChar(ed, 'e');
        pressChar(ed, 'w');
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0); // buffer が新インスタンスに差し替わる
        check("差し替え後に再構築が発生する", true, ed.getCanvasTextRebuildCount() > base);
        check("差し替え後のテキストは空", "", ed.getText());
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
```

### 3.7 変更6: 新規テスト `test/dev/javatexteditor/performance/EditorRenderPerfTest.java`（Write）

```java
package dev.javatexteditor.performance;

import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;

/**
 * 軽量化リファクタリング Phase 2 の性能テスト。
 * 10万行文書でのカーソル移動・文字入力が、キー入力ごとの全文再構築なし
 *（移動時）/1回（編集時）で完了することを実行時間で検証する。
 * Phase 2 以前の実装（1キーごとに getText()×2 + split×2）ではカーソル移動
 * 1000回だけで数十秒規模になり、このテストは完走しない。
 */
public class EditorRenderPerfTest {
    private static int pass = 0;
    private static int total = 0;

    private static final long THRESHOLD_MOVE_1000  = 2000; // 10万行文書で 'j'×1000
    private static final long THRESHOLD_TYPE_100   = 5000; // 10万行文書で100文字入力（1キー1回の全文再構築は許容）

    public static void main(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100_000; i++) sb.append("line").append(i).append("\n");
        String bigText = sb.toString();

        testCursorMove1000(bigText);
        testTyping100(bigText);

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        // EditorCanvas の Swing Timer が AWT スレッドを生かし続けるため明示終了する
        System.exit(fail > 0 ? 1 : 0);
    }

    static void testCursorMove1000(String bigText) {
        ModalEditor ed = new ModalEditor(bigText, new EditorCanvas());
        press(ed, 'j'); // 初回キャッシュ生成はウォームアップとして計測外
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) press(ed, 'j');
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[PERF] 10万行でカーソル移動1000回: " + elapsed + "ms (threshold=" + THRESHOLD_MOVE_1000 + "ms)");
        checkPerf("カーソル移動1000回が閾値内", THRESHOLD_MOVE_1000, elapsed);
        check("移動後の再構築回数が1回のまま", true, ed.getCanvasTextRebuildCount() <= 1);
    }

    static void testTyping100(String bigText) {
        ModalEditor ed = new ModalEditor(bigText, new EditorCanvas());
        press(ed, 'i');
        long start = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) press(ed, 'x');
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[PERF] 10万行で100文字入力: " + elapsed + "ms (threshold=" + THRESHOLD_TYPE_100 + "ms)");
        checkPerf("100文字入力が閾値内", THRESHOLD_TYPE_100, elapsed);
        check("入力がテキスト先頭に反映", true, ed.getText().startsWith("x"));
    }

    static void press(ModalEditor ed, char c) {
        ed.processKey(KeyEvent.getExtendedKeyCodeForChar(c), c, 0);
    }

    static void checkPerf(String name, long threshold, long actual) {
        total++;
        boolean ok = actual <= threshold;
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> actual=" + actual + "ms threshold=" + threshold + "ms");
        if (ok) pass++;
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
```

### 3.8 検証

```bash
./scripts/build.sh
./scripts/test.sh 2>&1 | tee /tmp/phase2-test.log
java -cp build dev.javatexteditor.editor.SyncCanvasCacheTest
java -cp build dev.javatexteditor.performance.EditorRenderPerfTest   # 3回実行し実測値を記録
```

- §1.4 のゲート判定（Summary は「72 passed / 1 failed」。新規テストクラス2つ追加のため）。
- `EditorRenderPerfTest` の実測値が閾値の半分以下であることを確認（超える場合は STOP）。
- 補足: `testTypingRebuildsOncePerKey` が「4回以上」で落ちる場合、1回の processKey が複数回 insert している経路の存在を意味する。その場合は同テストのコメントに実挙動を記録した上でアサーションを実挙動の回数に合わせてよい（再構築が**キー数に比例して有界**であることが本質。0回や2倍超は設計違反なので STOP）。

### 3.9 ドキュメント更新（このフェーズのコミットに含める）

1. **`CLAUDE.md`**: 「軽量性リファクタリング計画」節の状態表の Phase 2 行を「✅ 完了」に更新し、同節に以下を追記:
   ```markdown
   - **Phase 2 実装メモ**: `syncCanvas()` のテキスト再構築は `refreshCanvasTextCache()`（`canvasTextOwner`＝バッファ参照一致＋`getVersion()`＝版数一致で失効判定）に一本化した。カーソル移動等テキスト不変のキーでは `buffer.getText()`/`split` を一切実行しない。`EditorCanvas.setText(String, String[])` オーバーロードは分割済み配列を共有するため、渡した配列を呼び出し側で変更してはならない。`syncCanvas()` 内の `totalChars` 計算（O(cursorRow) のループ）はキャッシュ済み配列の参照加算のみで実測影響が小さいため意図的に残している（計画書 非ゴール5）。
   ```

### 3.10 コミット・マージ

```bash
git add -A && git commit -m "syncCanvas: getVersion()ベースの全文再構築キャッシュを導入（軽量化Phase 2）"
git push -u origin <ブランチ名>
```
§1.5 の手順で `/merge-main`（PRタイトル: 「syncCanvasの二重getText()を排除しカーソル移動の全文再構築をゼロにする」）。§7 更新後 Phase 3 へ。

---

## 4. Phase 3: ProjectSearcher の並列化と協調キャンセル

### 4.1 開始手順

```bash
git fetch origin main && git checkout -B <ブランチ名> origin/main
./scripts/build.sh
ls test/dev/javatexteditor/search/   # 新規テストのクラス名衝突がないことを確認
```

アンカー検証: `ProjectSearcher.java` の `search(Path, String, boolean)` が walkFileTree の visitFile 内で直接 `searchFile(...)` を呼ぶ逐次実装であること、`ModalEditor.withTimeout()` の catch 節が `catch (TimeoutException e) { return null; }` であること（780725e では 5319〜5331行）を確認する。

### 4.2 変更1: `src/dev/javatexteditor/search/ProjectSearcher.java` を以下に全置換（Write）

```java
package dev.javatexteditor.search;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 指定ディレクトリ配下のファイルを正規表現で全文検索する。
 * Java SE 標準の Files.walkFileTree() と java.util.regex を使用。
 * バイナリファイルや読み取れないファイルは静かにスキップする。
 * マッチは大文字小文字を区別しない（CASE_INSENSITIVE。FileNameSearcher と同じ方針）。
 *
 * 軽量化リファクタリング Phase 3:
 * 「①逐次 walk でパス収集 → ②仮想スレッドでファイルごとに並列 grep → ③submit 順に連結」
 * の2段階構成。結果順序は従来の逐次実装（walk 順・ファイル内は行昇順）と同一。
 * 呼び出し元から見た同期的なブロッキング契約（processKey 直後に結果を assert できる）は
 * 変更していない。walk と各 grep タスクは割り込みを検査するため、呼び出し側
 * （ModalEditor.withTimeout）がタイムアウトで future.cancel(true) すると協調的に停止する
 *（従来はタイムアウト後も walkFileTree が走り続けるスレッド残留が既知の残課題だった）。
 */
public class ProjectSearcher {

    /** バイナリ判定: NUL バイトを含む場合はバイナリとみなしてスキップ */
    private static final int NUL = 0;

    /** 巨大ファイル（ログ・ダンプ等）の全文読み込みに時間を取られないための上限。
     *  WordIndex と同じ 2MB を採用（analysis/WordIndex.java 参照）。
     *  この上限がないと、K（jdk.doc）/ gr / :grep はプロジェクトルート配下を
     *  同期的（EDT上）に全文検索するため、巨大ファイルが1つあるだけで
     *  UI がフリーズしたように見える不具合があった。 */
    private static final long MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024; // 2MB

    /**
     * grep のデフォルトスキップ対象ディレクトリ。{@link FileNameSearcher#SKIP_DIRS} と共通。
     * 以前は「意図的に .git/build/target のみをスキップする」設計だったが、作業ディレクトリの
     * 既定値がホームディレクトリになりうるため、node_modules 等（数万ファイル規模になりうる）を
     * 素通しすると Shift+K/gr/:grep が容易にタイムアウトする問題が実測で確認された。
     * ユーザーからの明示的な要望（gR / :grep! / \g! / \f! の「全ファイル走査」指定）に応じて
     * デフォルトはこのスキップ対象を適用し、bang（!）付きの呼び出しでのみスキップを無効化する。
     */
    private static final java.util.Set<String> DEFAULT_SKIP_DIRS = FileNameSearcher.SKIP_DIRS;

    /**
     * baseDir 配下のテキストファイルを再帰的に走査し、
     * pattern に一致する行を SearchResult のリストで返す。
     * {@link #DEFAULT_SKIP_DIRS} を適用する（{@code node_modules}等をスキップ）。
     *
     * @param baseDir  検索の起点ディレクトリ
     * @param pattern  java.util.regex.Pattern 形式の正規表現
     * @return 一致結果のリスト（発見順）
     * @throws PatternSyntaxException 正規表現が不正な場合
     */
    public List<SearchResult> search(Path baseDir, String pattern) {
        return search(baseDir, pattern, false);
    }

    /**
     * baseDir 配下のテキストファイルを再帰的に走査し、
     * pattern に一致する行を SearchResult のリストで返す。
     *
     * @param baseDir  検索の起点ディレクトリ
     * @param pattern  java.util.regex.Pattern 形式の正規表現
     * @param fullScan true の場合 {@link #DEFAULT_SKIP_DIRS} を無視し、全ファイルを走査する
     *                 （gR / :grep! / \g! / \f! 等「bang」付き呼び出し用）
     * @return 一致結果のリスト（発見順）
     * @throws PatternSyntaxException 正規表現が不正な場合
     */
    public List<SearchResult> search(Path baseDir, String pattern, boolean fullScan) {
        Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);

        if (!Files.isDirectory(baseDir)) {
            return new ArrayList<>();
        }

        List<Path> candidates = collectCandidateFiles(baseDir, fullScan);
        return grepFilesInParallel(candidates, regex, baseDir);
    }

    /**
     * 第1段階: 対象ファイルのパスだけを逐次 walk で収集する（メタデータのみ・内容は読まない）。
     * スキップ規則・2MB上限は従来の visitFile 内判定と同一。
     */
    private List<Path> collectCandidateFiles(Path baseDir, boolean fullScan) {
        List<Path> candidates = new ArrayList<>();
        try {
            Files.walkFileTree(baseDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Thread.currentThread().isInterrupted()) {
                        return FileVisitResult.TERMINATE; // タイムアウトによる協調キャンセル
                    }
                    if (attrs.size() <= MAX_FILE_SIZE_BYTES) {
                        candidates.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // アクセス権なし等は静かにスキップ
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (Thread.currentThread().isInterrupted()) {
                        return FileVisitResult.TERMINATE; // タイムアウトによる協調キャンセル
                    }
                    if (fullScan) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (DEFAULT_SKIP_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // walkFileTree 自体は通常 IOException を投げないが念のため
        }
        return candidates;
    }

    /**
     * 第2段階: 候補ファイルを仮想スレッドで並列に grep する。
     * Future を submit 順に get して連結するため、結果順序は逐次実装（walk 順）と同一。
     * ファイル I/O 主体の処理のためファイル数ぶんの仮想スレッドを一括生成してよい
     *（実際の同時 I/O はキャリアスレッド数に律速され、FD を使い果たすことはない）。
     */
    private List<SearchResult> grepFilesInParallel(List<Path> files, Pattern regex, Path baseDir) {
        List<SearchResult> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<SearchResult>>> futures = new ArrayList<>(files.size());
            for (Path file : files) {
                futures.add(executor.submit(() -> searchFile(file, regex, baseDir)));
            }
            for (Future<List<SearchResult>> future : futures) {
                results.addAll(future.get());
            }
        } catch (InterruptedException e) {
            // withTimeout 側の future.cancel(true)（タイムアウト）による割り込み。
            // 割り込みフラグを立て直すことで try-with-resources の close() が
            // shutdownNow() 相当の即時停止に切り替わり、残タスクは searchFile 冒頭の
            // 割り込みチェックで速やかに空リストを返して終了する。
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // searchFile は IOException 等を内部で握りつぶして空リストを返すため通常到達しない
        }
        return results;
    }

    /** 1ファイルを grep してそのファイル内の一致（行昇順）を返す。共有状態は持たない（並列実行のため）。 */
    private List<SearchResult> searchFile(Path file, Pattern regex, Path baseDir) {
        List<SearchResult> results = new ArrayList<>();
        if (Thread.currentThread().isInterrupted()) {
            return results; // タイムアウト後の残タスクは読み込みを始めず即終了する
        }
        // バイナリファイルのクイックチェック（先頭 8KB を読んで NUL バイトがあればスキップ）
        try {
            byte[] head = readHead(file, 8192);
            for (byte b : head) {
                if (b == NUL) return results;
            }
        } catch (IOException e) {
            return results;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            // UTF-8 でデコードできないファイル（バイナリ等）はスキップ
            return results;
        } catch (IOException e) {
            return results;
        }

        String relativePath = baseDir.relativize(file).toString();
        // OS に依らず / で表示
        relativePath = relativePath.replace('\\', '/');

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = regex.matcher(line);
            if (m.find()) {
                results.add(new SearchResult(relativePath, i + 1, line));
            }
        }
        return results;
    }

    private byte[] readHead(Path file, int maxBytes) throws IOException {
        try (var is = Files.newInputStream(file)) {
            byte[] buf = new byte[maxBytes];
            int read = is.read(buf, 0, maxBytes);
            if (read <= 0) return new byte[0];
            if (read < maxBytes) {
                byte[] trimmed = new byte[read];
                System.arraycopy(buf, 0, trimmed, 0, read);
                return trimmed;
            }
            return buf;
        }
    }
}
```

### 4.3 変更2: `ModalEditor.withTimeout()` にキャンセルを追加（Edit）

変更前:
```java
        } catch (TimeoutException e) {
            return null;
        } catch (Exception e) {
```
変更後:
```java
        } catch (TimeoutException e) {
            // タイムアウトした検索タスクへ割り込み、ProjectSearcher 側の協調キャンセル
            //（walk の TERMINATE / 並列 grep タスクの早期リターン）を発動させる。
            // これが無いとタイムアウト後もバックグラウンドの検索が走り続け、
            // Shift+K を連打するとスレッドが積み重なる既知の残課題があった（軽量化 Phase 3 で解消）。
            future.cancel(true);
            return null;
        } catch (Exception e) {
```

### 4.4 変更3: 新規テスト `test/dev/javatexteditor/search/ParallelGrepTest.java`（Write）

```java
package dev.javatexteditor.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 軽量化リファクタリング Phase 3（ProjectSearcher の2段階並列化）の回帰テスト。
 * 並列化後も (1) 結果順序が決定的（同一入力で同一順序・ファイル内は行昇順）であること、
 * (2) 2MB上限・NULバイナリスキップ・DEFAULT_SKIP_DIRS が従来どおり効くこと、
 * (3) fullScan=true でスキップが無効化されること、を検証する。
 */
public class ParallelGrepTest {
    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("parallel-grep-test");
        try {
            setupTree(root);
            testDeterministicOrder(root);
            testPerFileLineOrderAscending(root);
            testSkipRulesPreserved(root);
            testFullScanIncludesSkippedDirs(root);
        } finally {
            deleteRecursively(root);
        }

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) System.exit(1);
    }

    static void setupTree(Path root) throws Exception {
        Files.createDirectories(root.resolve("sub"));
        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(root.resolve("sub/a.txt"), "no\nNEEDLE one\nno\nno\nNEEDLE two\n");
        Files.writeString(root.resolve("sub/b.txt"), "nothing here\n");
        Files.writeString(root.resolve("c.txt"), "NEEDLE three\n");
        Files.writeString(root.resolve("node_modules/skip.txt"), "NEEDLE skipped\n");
        // NULバイトを含むバイナリ（スキップされる）
        Files.write(root.resolve("bin.dat"), new byte[]{'N', 'E', 'E', 'D', 'L', 'E', 0, 1, 2});
        // 2MB超（スキップされる）。中身に一致行を含めても結果に出ないこと
        byte[] big = new byte[2 * 1024 * 1024 + 1024];
        byte[] needle = "NEEDLE big\n".getBytes();
        System.arraycopy(needle, 0, big, 0, needle.length);
        Arrays.fill(big, needle.length, big.length, (byte) 'z');
        Files.write(root.resolve("big.txt"), big);
    }

    static void testDeterministicOrder(Path root) {
        System.out.println("[結果順序の決定性: 2回実行して完全一致]");
        ProjectSearcher searcher = new ProjectSearcher();
        List<SearchResult> r1 = searcher.search(root, "NEEDLE");
        List<SearchResult> r2 = searcher.search(root, "NEEDLE");
        check("2回の実行で件数一致", r1.size(), r2.size());
        boolean sameOrder = true;
        for (int i = 0; i < Math.min(r1.size(), r2.size()); i++) {
            SearchResult a = r1.get(i);
            SearchResult b = r2.get(i);
            if (!a.filePath().equals(b.filePath()) || a.lineNumber() != b.lineNumber()) {
                sameOrder = false;
                break;
            }
        }
        check("2回の実行で順序完全一致", true, sameOrder);
        check("一致は3件（a.txt×2 + c.txt×1）", 3, r1.size());
    }

    static void testPerFileLineOrderAscending(Path root) {
        System.out.println("[同一ファイル内の一致は行番号昇順]");
        ProjectSearcher searcher = new ProjectSearcher();
        List<SearchResult> results = searcher.search(root, "NEEDLE");
        int prevLine = -1;
        String prevFile = null;
        boolean ascending = true;
        for (SearchResult r : results) {
            if (r.filePath().equals(prevFile) && r.lineNumber() <= prevLine) {
                ascending = false;
                break;
            }
            prevFile = r.filePath();
            prevLine = r.lineNumber();
        }
        check("ファイル内の行番号が昇順", true, ascending);
    }

    static void testSkipRulesPreserved(Path root) {
        System.out.println("[2MB上限・NULバイナリ・SKIP_DIRSが従来どおり効く]");
        ProjectSearcher searcher = new ProjectSearcher();
        List<SearchResult> results = searcher.search(root, "NEEDLE");
        boolean hasBig = results.stream().anyMatch(r -> r.filePath().contains("big.txt"));
        boolean hasBin = results.stream().anyMatch(r -> r.filePath().contains("bin.dat"));
        boolean hasNodeModules = results.stream().anyMatch(r -> r.filePath().contains("node_modules"));
        check("2MB超ファイルは結果に出ない", false, hasBig);
        check("NULバイナリは結果に出ない", false, hasBin);
        check("node_modulesはデフォルトでスキップ", false, hasNodeModules);
    }

    static void testFullScanIncludesSkippedDirs(Path root) {
        System.out.println("[fullScan=true でスキップ対象ディレクトリも走査する]");
        ProjectSearcher searcher = new ProjectSearcher();
        List<SearchResult> results = searcher.search(root, "NEEDLE", true);
        boolean hasNodeModules = results.stream().anyMatch(r -> r.filePath().contains("node_modules"));
        check("fullScanでnode_modules内も一致", true, hasNodeModules);
    }

    static void deleteRecursively(Path root) throws Exception {
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                  .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        }
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
```

**注意**: `SearchResult` は `record SearchResult(String filePath, int lineNumber, String lineContent)`（780725e で検証済み）のため、アクセサ名 `filePath()`/`lineNumber()` はそのまま使える。万一コンパイルエラーになる場合のみ `src/dev/javatexteditor/search/SearchResult.java` を Read して実アクセサ名に合わせること。

### 4.5 検証

```bash
./scripts/build.sh
./scripts/test.sh 2>&1 | tee /tmp/phase3-test.log
java -cp build dev.javatexteditor.search.ParallelGrepTest
```

- §1.4 のゲート判定（Summary は「73 passed / 1 failed」。Phase 2 までの追加2クラス＋本フェーズ1クラス）。
- 特に `BangSearchTest`・`NativeReferenceSearchTest`・`JumpBackTest`・`GrepCommandTest` 系（grep 経由の統合テスト）が PASS していることをログで名指し確認する。

### 4.6 ドキュメント更新（このフェーズのコミットに含める・リファクタリング全体の完了処理を兼ねる）

1. **`CLAUDE.md`「Shift+K フリーズ修正」節**: 「未対応の残課題」の段落末尾に追記:
   ```markdown
   （2026-07 軽量化リファクタリング Phase 3 で解消: `withTimeout()` がタイムアウト時に `future.cancel(true)` を呼び、`ProjectSearcher` 側は walk の `TERMINATE`・並列 grep タスク冒頭の割り込みチェックで協調的に停止するため、検索スレッドは積み重ならない。あわせて `search()` は「逐次パス収集→仮想スレッド並列 grep」の2段階になり、結果順序・同期契約・1500ms タイムアウト・2MB 上限・スキップ規則は従来と同一。）
   ```
2. **`CLAUDE.md`「軽量性リファクタリング計画」節**: Phase 3 行を「✅ 完了」にし、末尾に問題④の処置記録を追記:
   ```markdown
   - **問題④（メモリチャーン）の処置記録**: 編集中のチャーン（キー入力ごとの全文 String 生成）は Phase 1（addBuffer コピー排除）＋ Phase 2（再構築キャッシュ）で解消した。ファイル全体を単一 String で保持する内部表現と「サイズ上限なし」は、2026-07 の確定済みユーザー判断のため変更していない（数百MB級ファイルの OOM リスクは既知の制約として残る）。
   ```
3. **`docs/PERF_REFACTORING_PLAN.md`**: 冒頭に「✅ 2026-XX-XX 全フェーズ完了」の1行を追記。

### 4.7 コミット・マージ

```bash
git add -A && git commit -m "ProjectSearcher: 仮想スレッド並列grep化とタイムアウト時の協調キャンセル（軽量化Phase 3）"
git push -u origin <ブランチ名>
```
§1.5 の手順で `/merge-main`（PRタイトル: 「ProjectSearcherを並列化しタイムアウト後のスレッド残留を解消する」）。

---

## 5. 最終報告（Phase 3 マージ後に必ず行う）

ユーザーに以下を報告して作業終了:
1. 3フェーズの PR URL とマージコミット SHA。
2. 性能比較: `LargeFileTest`（Phase 1 追加分）・`EditorRenderPerfTest` の実測値と、計画書 §1 に記録されたベースラインの問題説明との対応。
3. ベースライン比のテスト状況（「73 passed / 1 failed（ScrollTest 既知2件のみ）」であること）。
4. 残存する既知の制約（計画書 非ゴール1・2・4・8 = ピースツリー化、ビューポート描画供給、サイズ上限なし、検索インデックス）が未着手のままであること。

## 6. トラブルシューティング

| 症状 | 対処 |
|---|---|
| `test.sh` がハングする | `EditorCanvas` を生成するテストで `System.exit()` を忘れている（Swing Timer が AWT スレッドを生かし続ける）。新規テストの main 末尾に `System.exit(fail > 0 ? 1 : 0);` があるか確認（既存の `EditorCanvasTest.java:547` と同じ規約） |
| 性能テストがCIで閾値超過 | まず3回再実行して中央値を確認。恒常的に閾値の半分を超えるなら STOP（閾値の引き上げはユーザー判断） |
| `SyncCanvasCacheTest` の「ちょうど3回」が落ちる | §3.8 の補足を参照（有界性が本質。0回・2倍超は STOP） |
| PR マージが「conflict」で失敗 | `git fetch origin main && git merge origin/main` で解消を試みる。対象ファイル（本書記載）以外にコンフリクトが波及したら STOP |
| `ParallelGrepTest` のアクセサ名でコンパイルエラー | §4.4 の注意を参照（`SearchResult` の実アクセサ名に合わせる） |

## 7. 進捗チェックリスト（実行者が各フェーズ完了時に更新してコミットする）

- [ ] Phase 1: PieceTable 結合・キャッシュ — PR: （URL） / マージSHA: （SHA）
- [ ] Phase 2: syncCanvas キャッシュ — PR: （URL） / マージSHA: （SHA）
- [ ] Phase 3: ProjectSearcher 並列化 — PR: （URL） / マージSHA: （SHA）
- [ ] 最終報告済み（§5）
