# 段階6（案C）実行計画 — `PaneManager` ＋ `EditorHost` ＋ 旧 setter の削除

`docs/MAIN_DECOMPOSITION_PLAN.md` §5.6「段階6」を、**案C（`EditorHost` 導入まで行い、
`ModalEditor` の旧 setter も削除する）**で安全に実行するための細分割計画。

親計画の §1（非ゴール）・§2（検証手順）・§4（段階の全体像）はそのまま適用される。
本書は段階6のみを 6-0 〜 6-6 の**7サブ段階**に割り、各段階の失敗モードと中止条件を定める。

---

## 0. なぜ細分割が必要か（案Cのリスクの正体）

案Cのリスクは「作業量が多いこと」ではない。**間違えても検証スクリプトが気づけない箇所が
1つだけ存在すること**である。まずそれを特定した。

### 0.1 検証スクリプトの盲点（実証済み）

親計画 §2.1 の `verify.sh` は各テストクラスについて次の1行を記録し、`diff` で照合する。

```
<クラス名>|<終了状態>|<PASS件数サマリ>|FAILlines=<件数>
```

**問題**: 97クラス中 **54クラス**は `PASS: <n> / <m>` 形式のサマリを出力しないため、
この行の第3フィールドが**空**になる。すなわち「何件通ったか」が記録されていない。

そのため、テストが**途中で `System.exit(0)` して残りのテストメソッドを実行しないまま終了**
しても、終了コードは 0 なので `st=OK`、サマリは元から空、`FAILlines` も 0 のまま
——**完走した場合と1文字も違わない行が記録される**。

実際に `WaQaCommandTest` の5番目のテストの直後で `System.exit(0)` する複製
（`WaQaExitProbe`）を作って確認した:

```
dev.javatexteditor.editor.WaQaCommandTest|OK||FAILlines=0     ← 13メソッド全て実行
dev.javatexteditor.editor.WaQaExitProbe  |OK||FAILlines=0     ← 5メソッドで中断
```

**完全に同一。`diff` は空になる。**

### 0.2 なぜこれが案C固有のリスクなのか

`ModalEditor` の `exitCallback` / `exitAllCallback` の**既定値は `() -> System.exit(0)`** である。
案Cはこの2つの setter を削除するため、それを使っている**5つのテストクラスを書き換える**必要がある。
書き換えを1箇所でも取りこぼすと、`:q` / `:qa` を実行するテストがそこで本当に JVM を落とし、
上記のとおり**「PASS」として記録される**。

しかも取りこぼしやすい対象が2種類ある。

1. **意図的に上書きしているもの** — 例: `WaQaCommandTest` の `ed.setExitAllCallback(() -> exited[0] = true)`。
   これは検証したい振る舞いそのものなので、消せば普通は assert が落ちて気づく。
2. **防御目的で上書きしているもの** — 例: `KeyboardSimulationTest.reset()` と
   `RobotKeyInputTest` の `ed.setExitCallback(() -> {})`。これは**何も検証していない**。
   「万一 `:q` が漏れても JVM を落とさない」ための安全網である。
   **これを取りこぼしても、その場では何も起きない。** 後から `:q` を通るテストが追加された瞬間、
   あるいは既存テストのキー列がわずかに変わった瞬間に、静かに JVM が落ちて PASS を装う。

案Bにこのリスクは無い（旧 setter が残るのでテストを一切触らない）。
**案Cのリスクは実質的にこの1点に集約される。**

### 0.3 対策の方針

2段構えで、この失敗モードを**検知可能にし、かつ発生しにくくする**。

| 対策 | 内容 | 効く段階 |
|---|---|---|
| A | `verify.sh` に**出力行数**を記録させる（§1） | 6-0 で先に実施。以降すべて |
| B | `EditorHost` を**default メソッドを持つインタフェース**にし、テスト側の実装が「書き忘れると no-op」になるようにする（§3.2） | 6-2 以降 |

対策Aの有効性も実測済み。`Picked up JAVA_TOOL_OPTIONS` 行を除いた出力行数は
完走 **16行** / 途中終了 **5行** と明確に差が出る。テストクラスごとの `PASS:` / `[OK]` といった
出力慣例の違いに依存しない普遍的な指標である。

---

## 1. 段階6-0 — 検証スクリプトの強化（**ソースは1行も触らない**）

### やること

`verify.sh`（リポジトリ外に置く。親計画 §2.1 の方針を踏襲）に**出力行数**フィールドを追加する。

```bash
#!/bin/bash
# 使い方: ./verify.sh <出力ファイル名>
OUT="$1"
: > "$OUT"
for cf in $(find build -name "*Test.class" ! -name '*$*' | sort); do
  cn=$(echo "$cf" | sed 's|build/||;s|/|.|g;s|\.class$||')
  res=$(timeout 180 java -Dstdout.encoding=UTF-8 -cp build "$cn" 2>&1)
  rc=$?
  pass=$(echo "$res" | grep -oE "PASS: [0-9]+ */ *[0-9]+" | tail -1)
  fails=$(echo "$res" | grep -c "^FAIL")
  # ★追加: 出力行数。途中で System.exit された場合ここが減る。
  #   JAVA_TOOL_OPTIONS の告知行は環境由来のノイズなので除外する。
  lines=$(echo "$res" | grep -vc "^Picked up JAVA_TOOL_OPTIONS")
  if   [ $rc -eq 124 ]; then st="HANG"
  elif [ $rc -ne 0 ];   then st="RC=$rc"
  else                       st="OK"; fi
  echo "$cn|$st|$pass|FAILlines=$fails|lines=$lines" >> "$OUT"
done
```

**注意**: 出力に時刻・経過ミリ秒・一時ディレクトリ名など**実行のたびに変わる値**を含むテストが
あると、`lines` は安定していても他フィールドが揺れて `diff` がノイズだらけになる。
6-0 では**同一バイナリに対して verify を2回走らせ、2回とも完全一致すること**を先に確認する。
一致しないクラスがあれば、そのクラスだけ `lines` 比較の対象外として記録に残す
（`docs/` ではなく作業メモに書く。リポジトリには追加しない）。

### 検証

```bash
./scripts/build.sh && find test -name "*.java" | xargs javac -encoding UTF-8 -cp build -d build
/tmp/verify.sh /tmp/base_a.txt
/tmp/verify.sh /tmp/base_b.txt
diff /tmp/base_a.txt /tmp/base_b.txt      # ← 空であること（再現性の確認）
cp /tmp/base_a.txt /tmp/baseline6.txt     # ← 以降の全段階はこれと比較する
```

### 完了条件

- 同一バイナリ2回の `diff` が空
- `baseline6.txt` の既知 FAIL が `ScrollTest` 2件・`ModalEditorTest` 1件のみ（親計画のベースラインと一致）

### コミット

**コミットしない。**（リポジトリ外のスクリプト変更のみ。ソース差分ゼロ）

---

## 2. 段階6-1 — `PaneManager` 抽出（setter には一切触らない）

### やること

親計画 §6.1・§6.2 の手順1〜4 をそのまま実行する。**`EditorHost` はまだ作らない。**

- `dev.javatexteditor.app.PaneManager` を新設し、`root` / `active` を**インスタンスフィールド**にする
- `Main.java` 現在の以下を本文を変えずに移す:
  `buildComponent` / `findLiveBuffer` / `syncSiblingBuffers` / `setupSplitCallbacks` /
  `shareBufferWithSplit` / `createLeaf`×3 / `refreshCallbacks` / `rebuildLayout` /
  `resizeActivePane` / `updateBorders`
- `root[0]` → `root`、`active[0]` → `active` の**機械的置換のみ**。ロジックには触らない

### 規模（実測）

現在の `Main.java`（625行）に `root[0]` が **21箇所**、`active[0]` が **35箇所**。
これらを含む行は **50行**。

### この段階の失敗モード

**取りこぼしはコンパイルエラーにならない。** `root[0]` を1箇所だけ置換し忘れても、
ローカル配列がまだ生きていれば通ってしまう。したがって:

**ゲート**: 置換完了後、`Main.java` に `root[` / `active[` が**1つも残っていない**ことを
機械確認してから次へ進む。

```bash
grep -n "root\[\|active\[" src/dev/javatexteditor/Main.java   # ← 出力が空であること
```

### 検証

- 親計画 §2.1（`diff /tmp/baseline6.txt /tmp/p61.txt` が空）
- 親計画 §2.2（Xvfb 20秒スモーク、`exit=124`）
- **§7 の手動ペイン検証（6項目すべて）** ← この段階が最も壊しやすい

### コミット

`段階6-1: 画面分割の状態と操作を PaneManager へ抽出`

---

## 3. 段階6-2 — `EditorHost` 導入（**旧 setter は残す** ＝ 案B相当の到達点）

### 3.1 実際のコールバック10個（実測インベントリ）

親計画 §6.3 の `EditorHost` スケッチは**9メソッド中2つが実態と食い違っている**
（`onCloseBlocked` を挙げているが実名は `setCloseBlockedCallback` で誰も呼んでいない／
`exitAllCallback` が漏れている）。実測した正しい一覧は次のとおり。

| # | コールバック | 既定値 | null チェック | Main が配線 | テストが配線 | 群 |
|---|---|---|---|---|---|---|
| 1 | `exitCallback` | `System.exit(0)` | 不要（常に非null） | ○ | **4クラス** | B |
| 2 | `exitAllCallback` | `System.exit(0)` | 不要 | **×**（既定に依存） | **1クラス** | B |
| 3 | `allEditorsSupplier` | `() -> List.of(this)` | 不要 | ○ | **1クラス** | B |
| 4 | `liveBufferLookup` | `null` | 要 | ○ | **1クラス** | B |
| 5 | `onSharedBufferSync` | `null` | 要 | ○ | **1クラス** | B |
| 6 | `splitHorizontalCallback` | `null` | 要 | ○ | × | A |
| 7 | `splitVerticalCallback` | `null` | 要 | ○ | × | A |
| 8 | `movePanePrevCallback` | `null` | 要 | ○ | × | A |
| 9 | `movePaneNextCallback` | `null` | 要 | ○ | × | A |
| 10 | `closeBlockedCallback` | `null` | 要 | **×** | **×** | **死** |

- **群A（4個）** — 呼び出し元が `Main.java` だけ。削除しても失敗モードは**コンパイルエラーのみ**。
- **群B（5個）** — テストが呼んでいる。**削除には5クラスの書き換えが要る。§0 のリスクはここに集中する。**
- **10 は完全な死にコード** — setter が存在するだけで呼び出し元が皆無。したがって
  `closeCurrentPane()` / `saveAndCloseCurrentPane()` の `closeBlockedCallback != null` 分岐は
  **常に false**、必ず `exitCallback.run()` に落ちる。

**`exitAllCallback` を Main が配線していない**点に注意。本番の `:qa` は**既定の
`System.exit(0)` に依存して正しく動いている**。`EditorHost` 化でこの既定を落とすと
`:qa` が無反応になる（テストは `WaQaCommandTest` が上書きしてしまうため**検知できない**）。

### 3.2 設計判断: 失敗モードを反転させる default メソッド

```java
// dev.javatexteditor.app.EditorHost
public interface EditorHost {
    // --- 既定は「何もしない」。テスト側の実装が書き忘れても JVM は死なない ---
    default void splitHorizontal() {}
    default void splitVertical()   {}
    default void movePanePrev()    {}
    default void movePaneNext()    {}
    default void syncSiblingBuffers(ModalEditor source) {}
    default UndoablePieceTable findLiveBuffer(String absolutePath) { return null; }
    default List<ModalEditor> allEditors(ModalEditor self) { return List.of(self); }

    // --- 終了系だけは既定を持たせない（実装を強制する） ---
    void closePane();
    void exitAll();

    /**
     * ホスト未設定の ModalEditor（単一ペイン運用・多くのテスト）の既定。
     * 従来の exitCallback / exitAllCallback の既定 System.exit(0) をそのまま踏襲する。
     */
    EditorHost STANDALONE = new EditorHost() {
        @Override public void closePane() { System.exit(0); }
        @Override public void exitAll()   { System.exit(0); }
    };
}
```

`ModalEditor` 側は `private EditorHost host = EditorHost.STANDALONE;` とし、
`setHost(EditorHost)` を1個追加する。

**なぜ終了系だけ default を持たせないか**: no-op を既定にすると、
`PaneManager` 側で `closePane()` の実装を書き忘れた場合に `:q` が無反応になり、
**手動検証でしか気づけない**。abstract にしておけばコンパイラが強制する。
一方テスト側は「終了を握りつぶす」ことこそが目的なので、明示的に空実装を書くのが正しい。

**この設計により、テスト側の取りこぼしの失敗モードが反転する**:

| | 従来（setter方式） | 新（default付き interface） |
|---|---|---|
| 上書きを書き忘れた | `System.exit(0)` → **PASSを装って静かに死ぬ** | no-op → assert が落ちて**見える形で FAIL** |

ただし**`setHost()` 自体を呼び忘れた**場合は `STANDALONE` のままなので従来どおり
`System.exit(0)` になる。これは本番の単一ペイン挙動を維持するために必要な仕様であり、
ここだけは 6-0 の行数検知に頼る。

### 3.3 やること

1. `EditorHost` インタフェースを新設
2. `PaneManager implements EditorHost`（6-1 で移した private メソッドに `@Override` を付ける）
3. `ModalEditor` に `host` フィールドと `setHost()` を**追加**する。
   **旧 setter 10個はすべて残す。** 内部は次のように旧フィールド優先で読む:

   ```java
   // 例: :q
   private void closeCurrentPane() {
       if (closeBlockedCallback != null) { closeBlockedCallback.run(); return; }
       if (exitCallbackOverridden) { exitCallback.run(); return; }  // 旧 setter が使われていれば従来通り
       host.closePane();
   }
   ```

   **旧 setter が呼ばれたかどうかを見る**のが要点。「旧が設定されていれば旧、なければ host」に
   すると、旧 setter を使い続けるテストと host に移行した `Main` が同時に正しく動く。
4. `Main.java` は `setSplit*` 〜 `setOnSharedBufferSync` の**8箇所の配線を `setHost(paneManager)` 1箇所に置き換える**

### この段階の失敗モード

- `refreshCallbacks()` が毎回全リーフに配線し直していた「分割のたびに `root[0]` を再評価する」
  という性質を、`PaneManager` がフィールドを持つことで**自動的に満たすようになる**。
  ここは意味が変わっていないか個別に読み直すこと（親計画 §6.4 の「1箇所でも取りこぼすと
  コンパイルは通るが挙動が変わる」に該当）。
- `allEditorsSupplier` は `() -> List.of(this)` という**self 参照の既定**を持つ。
  `EditorHost.allEditors(ModalEditor self)` が `self` を引数で受ける形にしているのはこのため。
  引数なしにすると単一ペインの既定が表現できない。

### 検証

- 親計画 §2.1（`diff` が空）— **テストは1行も変えていないので、ここで差分が出たら設計が壊れている**
- 親計画 §2.2（Xvfb スモーク）
- **§7 の手動ペイン検証（6項目すべて）**

### コミット

`段階6-2: ペイン操作の窓口を EditorHost インタフェースへ集約（旧 setter は併存）`

> **ここが案Bの到達点である。** 6-3 以降に進まず止めても、成果物は完全な状態で残る。
> 6-3 以降で問題が起きた場合の**巻き戻し先**もここ。

---

## 4. 段階6-3 — 群A（4個）と死にコード（1個）の削除

### やること

`ModalEditor` から次を削除する。

- `setSplitHorizontalCallback` / `setSplitVerticalCallback` /
  `setMovePanePrevCallback` / `setMovePaneNextCallback` とその4フィールド
- `setCloseBlockedCallback` と `closeBlockedCallback` フィールド、および
  `closeCurrentPane()` / `saveAndCloseCurrentPane()` の**到達不能な分岐**

### なぜ安全か

- 群Aの呼び出し元は `Main.java` だけで、6-2 で既に `setHost()` に移行済み。
  **残っている呼び出し元はゼロ**なので、失敗モードはコンパイルエラーのみ。
- `closeBlockedCallback` は setter の呼び出し元が**本番・テストとも皆無**。
  常に `null` であることがソース全文検索で確定しているため、分岐削除は挙動を変えない。

### ゲート（削除の**前**に実行する）

```bash
grep -rn "setSplitHorizontalCallback\|setSplitVerticalCallback\|setMovePanePrevCallback\|setMovePaneNextCallback\|setCloseBlockedCallback" src/ test/ \
  | grep -v "src/dev/javatexteditor/editor/ModalEditor.java"
# ← 出力が空であること（＝宣言以外に呼び出し元が無い）
```

空でなければ**削除してはならない**。6-2 の移行が終わっていない。

### 検証

親計画 §2.1・§2.2。手動検証は分割・ペイン移動の**1〜5番のみ**でよい（6番は群Bの範囲）。

### コミット

`段階6-3: EditorHost へ移行済みのペイン操作 setter と死にコードを削除`

---

## 5. 段階6-4 — テスト5クラスを `setHost()` へ移行（**削除はまだしない**）

**本計画で最も危険な段階。** §0 のリスクはすべてここに集中する。

### 対象（実測規模）

| テストクラス | 行数 | test メソッド | 該当 setter 呼び出し | 6-0 の `lines` 基準値 |
|---|---|---|---|---|
| `WaQaCommandTest` | 261 | 13 | **9箇所** | 16 |
| `SharedBufferTest` | 219 | 7 | 2箇所 | 15 |
| `KeyboardSimulationTest` | 734 | 40 | 2箇所（うち1つは防御目的） | `PASS: n / m` あり |
| `RobotKeyInputTest` | 1060 | 34 | 1箇所（**防御目的**） | `PASS: n / m` あり |
| `ModalEditorTest` | 1918 | 110 | 1箇所 | `PASS: n / m` あり |

`WaQaCommandTest` と `SharedBufferTest` は**サマリ行を出力しない**＝
6-0 の `lines` フィールドだけが唯一の検知手段になる。

### 5.1 テスト用ヘルパーを1つだけ作る

各テストが匿名クラスを書き散らすと、書き忘れが分散して見つけにくい。
**テスト側に共通ヘルパーを1つ置き、全クラスがそれを使う。**

```java
// test/dev/javatexteditor/editor/TestEditorHost.java
/**
 * テスト用の EditorHost。既定ではペイン操作をすべて無視し、
 * closePane()/exitAll() は「呼ばれた回数を数えるだけ」で System.exit しない。
 * これにより、上書きを書き忘れても JVM が落ちず、assert が落ちて可視化される。
 */
public class TestEditorHost implements EditorHost {
    public int closeCount, exitAllCount;
    @Override public void closePane() { closeCount++; }
    @Override public void exitAll()   { exitAllCount++; }
}
```

- `ed.setExitCallback(() -> exited[0] = true)` → `TestEditorHost h = ...; ed.setHost(h);` ＋
  assert を `h.closeCount > 0` に置き換える
- **防御目的の `ed.setExitCallback(() -> {})`（`KeyboardSimulationTest.reset()` /
  `RobotKeyInputTest`）は `ed.setHost(new TestEditorHost())` に置き換える。**
  これは「何も検証しないが絶対に消してはいけない行」なので、
  置換時に**コメントでその旨を明記する**こと。

### 5.2 進め方（1クラス1コミットにしない）

5クラスを**まとめて**1コミットにする。理由: 途中コミットでは
「旧 setter を使うクラス」と「host を使うクラス」が混在した中途半端な状態になり、
6-5 のゲート（呼び出し元ゼロの確認）が意味を持たないため。

ただし**作業自体は1クラスずつ行い、そのつど当該クラス単体を実行して `lines` を確認する**。

```bash
# 1クラス書き換えるごとに
find test -name "*.java" | xargs javac -encoding UTF-8 -cp build -d build
java -Dstdout.encoding=UTF-8 -cp build dev.javatexteditor.editor.WaQaCommandTest 2>&1 \
  | grep -vc "^Picked up JAVA_TOOL_OPTIONS"
# ← 16 であること（1行でも減っていたら途中で死んでいる）
```

### 5.3 中止条件

次のいずれかに該当したら**6-2 まで巻き戻し、案Bで確定させる**。

- `lines` が基準値と一致しない状態が、原因を特定できないまま30分以上続く
- `RobotKeyInputTest` が Xvfb 環境で不安定になり、`lines` の再現性が失われる
- 移行後に `diff` が空にならず、差分が「テストの書き換えミス」か「本体の挙動変化」か切り分けられない

案Bで確定させても、6-1・6-2 の成果（`PaneManager` と `EditorHost`）は完全に残る。
**失うのは「旧 setter が10個残ること」だけである。**

### 検証

- 親計画 §2.1（`diff /tmp/baseline6.txt /tmp/p64.txt` が空）
  — **`lines` フィールドが加わっているので、途中終了はここで捕まる**
- 5クラスそれぞれの `lines` を個別に目視確認（上表の基準値と照合）
- 親計画 §2.2
- §7 の手動検証**6番（共有バッファ）**は必須

### コミット

`段階6-4: テストのペイン系コールバックを EditorHost へ移行`

---

## 6. 段階6-5 — 群B（5個）の削除

### やること

`ModalEditor` から `setExitCallback` / `setExitAllCallback` / `setAllEditorsSupplier` /
`setLiveBufferLookup` / `setOnSharedBufferSync` とその5フィールド、および
6-2 で入れた「旧優先」分岐を削除し、`host` 一本にする。

### なぜこの時点なら安全か

6-4 完了時点で、これら5つの**呼び出し元は本番・テストとも存在しない**。
削除の失敗モードはコンパイルエラーのみに縮退している。

### ゲート（削除の**前**に実行する）

```bash
grep -rn "setExitCallback\|setExitAllCallback\|setAllEditorsSupplier\|setLiveBufferLookup\|setOnSharedBufferSync" src/ test/ \
  | grep -v "src/dev/javatexteditor/editor/ModalEditor.java"
# ← 出力が空であること
```

### 削除後に必ず確認すること

`STANDALONE` の既定が生きているか（§3.1 で指摘した `exitAllCallback` の罠）。

```java
// 確認用プローブ（リポジトリには追加しない）
ModalEditor ed = new ModalEditor("");   // host 未設定
// :qa を実行 → System.exit(0) すること（＝プロセスが終了コード0で即座に落ちる）
```

`Main` が `exitAllCallback` を配線していなかったため、**本番の `:qa` はこの既定に依存している**。
テストは全て host を設定するので、この経路はテストでは踏まれない。**手動確認が必須。**

### 検証

親計画 §2.1・§2.2 ＋ §7 の手動検証全6項目 ＋ 上記 `:qa` プローブ。

### コミット

`段階6-5: 旧ペイン系コールバック setter を削除し EditorHost へ一本化`

---

## 7. 手動ペイン検証（6-1・6-2・6-4・6-5 で実施）

親計画 §6.5 と同じ。`Xvfb` 上で起動し `java.awt.Robot` でキーを送る。
スクリプトは `/tmp` に置き、リポジトリには追加しない。

| # | 操作 | 期待 | 6-1 | 6-2 | 6-3 | 6-4 | 6-5 |
|---|---|---|---|---|---|---|---|
| 1 | `s` `v` | 左右に分割される | ○ | ○ | ○ | | ○ |
| 2 | `s` `s` | 上下に分割される | ○ | ○ | ○ | | ○ |
| 3 | `Ctrl+W` | アクティブペインの枠線が移動 | ○ | ○ | ○ | | ○ |
| 4 | `Ctrl+Alt+→` | アクティブペインが広がる | ○ | ○ | ○ | | ○ |
| 5 | `:q` | ペインが1つ閉じる（最後の1枚では終了） | ○ | ○ | ○ | | ○ |
| 6 | 同一ファイルを2ペインで開き片方で編集 | もう片方に即座に反映 | ○ | ○ | | ○ | ○ |
| 7 | 単一ペインで `:qa` | アプリが終了する | | ○ | | | ○ |

**6番が最重要**（親計画 §6.5 の指摘どおり `SharedBufferTest` はフェイク実装で検証しているため、
本物の `PaneManager` が壊れても自動テストでは検知できない）。
**7番は本計画で新たに追加した項目**（§3.1 で判明した `exitAllCallback` 未配線のため）。

---

## 8. サブ段階の一覧と巻き戻し先

| 段階 | 内容 | ソース差分 | 主な失敗モード | 巻き戻し先 |
|---|---|---|---|---|
| 6-0 | `verify.sh` に行数記録を追加 | **なし** | — | — |
| 6-1 | `PaneManager` 抽出 | `Main` −約230行 / 新規1クラス | 置換漏れ（コンパイルは通る） | 6-0 |
| 6-2 | `EditorHost` 導入（旧 setter 併存） | `ModalEditor` +1 setter | 既定値の取り違え | 6-1 |
| 6-3 | 群A・死にコード削除（5個） | `ModalEditor` −約40行 | コンパイルエラーのみ | 6-2 |
| 6-4 | テスト5クラスを host へ移行 | テストのみ | **静かな `System.exit(0)`** | **6-2（案B確定）** |
| 6-5 | 群B削除（5個） | `ModalEditor` −約50行 | コンパイルエラー＋`:qa` 既定の喪失 | 6-4 |

**6-2 と 6-4 が判断の分岐点である。**
6-2 まで完了すれば案Bと同じ成果が手に入り、6-3 以降はいつ止めても健全な状態が残る。

---

## 9. 本計画の非ゴール

親計画 §1 の非ゴールに加えて、次を明示的に対象外とする。

- `ModalEditor` 側の**ペイン系以外の** setter（`setOnFileOpened` / `setOnBufferDelete` /
  `setBufferListSupplier` / `setOnRunMainClassSelected` など）の整理。これらは
  `BufferRegistry` / `JavaBuildRunner` という別の協力者に属し、`EditorHost` の責務ではない
- `ScrollTest` 2件・`ModalEditorTest` 1件の既知 FAIL の修正（仕様判断が未決。親計画と同じ）
- 段階7（`GlobalKeyDispatcher` / `EditorApplication`）。段階6完了後に着手する
