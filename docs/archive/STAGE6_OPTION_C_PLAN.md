# 段階6（`PaneManager` + `EditorHost`）実行計画書 — Option C（サブ段階分割版）

- 作成日: 2026-07-28
- 対象コミット: `621d56e`（段階0〜5完了・PR #204マージ後）
- 対象ブランチ: `claude/stage6-substep-execution-4of8o5`
- 親計画書: `docs/archive/MAIN_DECOMPOSITION_PLAN.md` §5「段階6 — `PaneManager` ＋ `EditorHost`（最難関）」
- 本書の位置づけ: 親計画書 §6.1〜6.5 に書かれた内容を**新しく設計し直すものではない**。
  親計画書自身が「手順2と5を同じコミットに混ぜないこと」「判断が必要なため、手順5に着手する前に
  いったん止めて確認を仰ぐこと」と明記している区切りをそのままサブ段階境界として採用し、
  1サブ段階＝1コミット＋個別検証という、第1〜8弾（CLAUDE.md記載）・段階0〜5で確立済みの
  進め方に合わせて分割しただけである。設計判断を追加していない。

## 訂正注記（`MAIN_DECOMPOSITION_PLAN.md` 段階6節に対して）

- 親計画書 §2.3「段階6専用の追加検証」は「方法は §5.6 に記す」としているが、親計画書に §5.6 は
  存在しない（実際の手動検証手順は §6.5 に書かれている）。本書ではこれを §7 として独立させ、
  親計画書の誤記参照を修正した。
- 親計画書の進捗記録表（§9）は段階6を1行（「6」）としてしか受け付けない形になっているが、
  本書の方式では6-0〜6-5の6コミットに分かれる。本書末尾の §6 進捗記録欄で個別に記録し、
  全サブ段階完了後に親計画書 §9 の「6」行へ要約を1行で転記する。

---

## 1. 検証環境の再構築（このセッションで必須）

このコンテナは前回セッションの `/tmp` を引き継がないため、`verify.sh` を含め毎セッション作り直す。
内容は `MAIN_DECOMPOSITION_PLAN.md` §2.1 と完全に同一（新規設計しない）。

```bash
#!/bin/bash
# /tmp/verify.sh
# 使い方: ./verify.sh <出力ファイル名>
OUT="$1"
: > "$OUT"
for cf in $(find build -name "*Test.class" ! -name '*$*' | sort); do
  cn=$(echo "$cf" | sed 's|build/||;s|/|.|g;s|\.class$||')
  res=$(timeout 180 java -Dstdout.encoding=UTF-8 -cp build "$cn" 2>&1)
  rc=$?
  pass=$(echo "$res" | grep -oE "PASS: [0-9]+ */ *[0-9]+" | tail -1)
  fails=$(echo "$res" | grep -c "^FAIL")
  if   [ $rc -eq 124 ]; then st="HANG"
  elif [ $rc -ne 0 ];   then st="RC=$rc"
  else                       st="OK"; fi
  echo "$cn|$st|$pass|FAILlines=$fails" >> "$OUT"
done
```

ベースライン確定手順（6-0で実施）:

```bash
./scripts/build.sh && find test -name "*.java" | xargs javac -encoding UTF-8 -cp build -d build
/tmp/verify.sh /tmp/baseline6_run1.txt
/tmp/verify.sh /tmp/baseline6_run2.txt
diff /tmp/baseline6_run1.txt /tmp/baseline6_run2.txt   # 空であること＝再現性確認
cp /tmp/baseline6_run1.txt /tmp/baseline6.txt
```

各サブ段階の完了判定は `diff /tmp/baseline6.txt /tmp/phase6-N.txt` が空であること
（既知FAIL 2クラス: `ScrollTest` 2件・`ModalEditorTest` 1件、は元から baseline6.txt に含まれているため、
差分ゼロという条件にそのまま吸収される。個別に除外して比較する必要はない）。

起動スモークテスト（§2.2相当、全サブ段階で実施）:

```bash
pkill Xvfb 2>/dev/null; (Xvfb :99 -screen 0 1280x800x24 >/dev/null 2>&1 &); sleep 2
DISPLAY=:99 timeout 20 java -cp build dev.javatexteditor.Main > /tmp/launch.log 2>&1
echo "exit=$?"          # 124 であること
grep -iE "exception|error at|Caused by" /tmp/launch.log   # 出力が無いこと
rm -rf lib/_openjdk_clone_tmp
```

---

## 2. サブ段階の一覧

親計画書 §6.2「移行手順」の5手順・§6.3「EditorHostインタフェース」・§6.5「手動検証」を、
以下のように6つのコミット単位へ分割する。

| サブ段階 | 対応する親計画書の記述 | 内容 | コミット |
|---|---|---|---|
| 6-0 | （本書自体） | 検証環境の構築とベースライン確定。コード変更なし | 段階6-0: Option C 実行計画書を作成しベースラインを確定 |
| 6-1 | §6.2 手順1〜2 | `PaneManager` クラスを新設し、責務4（97–148行）と責務10（607–840行）のメソッドを**本文を変えずに**移し、`root[0]`→`root`・`active[0]`→`active` の機械的置換のみ行う | 段階6-1: PaneManager を新設し root[0]/active[0] を機械的置換 |
| 6-2 | §6.2 手順3〜4 | `main()` 側を `PaneManager` 経由に置き換える（`PaneTree.PaneNode[] root`/`PaneTree.Leaf[] active` ローカル配列を削除し `pm.root()`/`pm.active()` に統一）。ここまでで §2 の全検証＋§7 の手動ペイン検証を行い、**健全な状態として確定する**（親計画書「案Bの到達点」に相当） | 段階6-2: main() を PaneManager 経由へ統一し検証済み到達点として確定 |
| 6-3 | §6.3 導入判断＋準備 | `EditorHost` インタフェースを新設し `PaneManager implements EditorHost` とする。**削除ではなく追加のみ**（旧setterは残したまま、削除対象になりうる7個のsetterの参照箇所を`grep`で洗い出すゲート確認を行う） | 段階6-3: EditorHost インタフェースを新設（旧setterは維持） |
| 6-4 | §6.3 移行方針の「推奨」案 | `ModalEditor` の7個の setter（`setSplitHorizontalCallback` 〜 `setOnSharedBufferSync`）を、内部で `EditorHost` のデフォルト実装へ委譲する形に置き換える（第6弾で採用した移行期間方式。旧 public シグネチャは削除しない） | 段階6-4: 7個の setter を EditorHost 委譲へ置き換え |
| 6-5 | §6.3 の残り＋§6.4 | `createLeaf`/`refreshCallbacks` 配線側を `setHost(EditorHost)` 1本化に更新し、7個の個別配線コードを削除する。削除前に旧setter名の呼び出し箇所を`grep`で確認するゲートを必須とする | 段階6-5: 配線を setHost() 1本化に統一し旧個別配線を削除 |

**6-3と6-4を分けた理由**（本書の分割方針、設計の追加ではなく手順の細分化）:
親計画書は「旧setterを残し内部でEditorHostへ委譲する」という**推奨**方式を示しているが、
「インタフェースを新設する」ことと「7個のsetterの実装を委譲に置き換える」ことは
性質の異なる変更（前者は純追加でリスクほぼゼロ、後者は既存動作の置き換えでリスクあり）のため、
1サブ段階＝1つの性質の変更、という第1〜8弾の原則に従い分離した。

---

## 3. 6-3・6-5のゲート条件（削除前grep確認）

いずれも「削除・置き換えの対象が本当にそれだけか」を機械的に確認してから着手する。

### 6-3のゲート（EditorHost導入前の現状把握）

```bash
grep -n "setSplitHorizontalCallback\|setSplitVerticalCallback\|setClosePaneCallback\|setOnCloseBlocked\|setMovePanePrevCallback\|setMovePaneNextCallback\|setOnSharedBufferSync" src/dev/javatexteditor/*.java src/dev/javatexteditor/**/*.java
```

7個のsetter定義（`ModalEditor.java`側）と、呼び出し箇所（`Main.java`の`createLeaf`/`refreshCallbacks`）
が過不足なく列挙されることを確認してから6-3に着手する。出力に想定外のファイルが含まれていたら、
そのファイルも6-4/6-5の影響範囲に含めて計画し直す。

### 6-5のゲート（削除前）

```bash
grep -n "\.setSplitHorizontalCallback(\|\.setSplitVerticalCallback(\|\.setClosePaneCallback(\|\.setOnCloseBlocked(\|\.setMovePanePrevCallback(\|\.setMovePaneNextCallback(\|\.setOnSharedBufferSync(" src/dev/javatexteditor/*.java src/dev/javatexteditor/**/*.java
```

6-4完了時点でこの呼び出しが `Main.java`（または `PaneManager.java`）の1箇所（`wireInto`相当の
配線メソッド）にしか残っていないことを確認してから、個別配線コードを削除する。
**出力が空でないことを確認してから削除する**（＝呼び出し箇所が「そこだけ」であることの確認。
「呼び出しが0件であること」ではない点に注意。setter自体は6-4で残す方針のため、呼び出しは
`wireInto`からの1箇所だけ残るのが正しい状態）。

---

## 4. 6-4の中止条件

以下のいずれかに該当したら、**その場で6-2まで巻き戻し**（`git reset --hard <6-2のコミット>` または
該当コミットの取り消し）、ユーザーに状況を報告して指示を仰ぐ。6-5まで無理に進めない。

- 30分以上作業しても原因不明で `verify.sh` の `lines`/`FAILlines` が baseline と合わない
- Xvfbでの手動ペイン検証が不安定（同一操作で結果が再現しない）
- 「本体（`PaneManager`/`ModalEditor`）の変更ミスか」「テスト側の書き換えミスか」の切り分けができない

6-2完了時点（`main()` が `PaneManager` 経由に統一され、旧 `root[0]`/`active[0]` 配列が
消え、7個の旧setterはそのまま動いている状態）は、それ自体で健全に動作する到達点として確定する。
6-3以降（`EditorHost` 統合）が完了しなくても、段階6として実用上の価値は6-2の時点で確保されている。

---

## 5. 段階6専用の手動ペイン検証（§7）

`親計画書 §6.5` と同一内容。6-1・6-2・6-4・6-5の完了時に、`Xvfb` 上でアプリを起動し
`java.awt.Robot` でキーを送って以下を確認する。スクリプトは `/tmp` に作成し、リポジトリには追加しない。

| # | 操作 | 期待 |
|---|---|---|
| 1 | `s` `v` | 左右に分割される |
| 2 | `s` `s` | 上下に分割される |
| 3 | `s h`/`s l`（親計画書 §6.5 は「Ctrl+W」と記載しているが実際のキーバインドと異なる。6-1実施時に確認済み） | アクティブペインの枠線が移動する |
| 4 | `Ctrl+Alt+→` | アクティブペインが広がる |
| 5 | `:q` | ペインが1つ閉じる（最後の1枚では閉じない） |
| 6 | 同一ファイルを2ペインで開き片方で編集 | もう片方に即座に反映される |

6番が特に重要（共有バッファ機構。`findLiveBuffer`/`syncSiblingBuffers` が `PaneManager` へ
移ることで壊れやすい。`SharedBufferTest` はテスト内のフェイク実装で再現しているため、
本物の `PaneManager` が壊れても自動テストでは検知できない）。

---

## 6. 進捗記録欄（実行者が埋める）

| サブ段階 | 実施日 | コミット | `diff` 空 | スモーク | 手動ペイン検証 | 気づき |
|---|---|---|---|---|---|---|
| 6-0 | 2026-07-28 | `136c00f` | ✅ | ✅ 124 | (対象外) | 検証環境構築・ベースライン確定のみ |
| 6-1 | 2026-07-28 | （本コミット） | ✅ | ✅ 124 | ✅ | 6-1と6-2を統合して実施（下記「気づき」参照） |
| 6-2 | 2026-07-28 | （6-1に統合） | — | — | — | — |
| 6-3 | 2026-07-28 | （本コミット） | ✅ | ✅ 124 | (対象外) | ゲート確認で親計画書の setter 名が実際と異なると判明（下記） |
| 6-4 | 2026-07-28 | （本コミット） | ✅ | ✅ 124 | ✅（無変化を確認） | `setHost()` を追加のみ、呼び出し箇所ゼロ（下記） |
| 6-5 | 2026-07-28 | （本コミット） | ✅ | ✅ 124 | ✅（重大な回帰を発見・修正） | `:q`が無反応になる回帰を手動検証で発見・修正（下記） |

### 6-4の気づき

- **解釈の確定**: 親計画書 §6.3 の「旧setterを残し、内部でEditorHostのデフォルト実装へ委譲する」を、
  「`ModalEditor.setHost(EditorHost)` を新設し、内部で既存の9個のsetter
  （`setSplitHorizontalCallback`/`setSplitVerticalCallback`/`setExitCallback`/
  `setCloseBlockedCallback`/`setMovePanePrevCallback`/`setMovePaneNextCallback`/
  `setAllEditorsSupplier`/`setLiveBufferLookup`/`setOnSharedBufferSync`）へ委譲する」
  という**純追加**として実装した。既存9個のsetter自体・その呼び出し箇所（`PaneManager.
  refreshCallbacks()`）は一切変更していない。`setHost()` を実際に呼ぶ箇所はまだ存在しない
  （`grep -rn "\.setHost("` は空）ため、本サブ段階はアプリの実行時挙動を1バイトも変えていない。
- **実際の配線切り替え（`PaneManager.refreshCallbacks()` を `leaf.editor().setHost(this)` 1行に
  統一し、9個の個別 `setXxx` 呼び出しを削除すること）は6-5に先送りした**。ここが実際に
  挙動が変わりうる箇所であり、6-5の削除前grepゲート・中止条件の対象はこちらになる。
- **検証結果**: `diff` は空、起動スモークテスト `exit=124`。手動ペイン検証（Xvfb+Robot、
  6-1で使用した検証スクリプトをそのまま再実行）でも `s v`/`s h`/`s l`/`:q` の挙動が
  6-1完了時点と完全に同一であることをスクリーンショットで確認した（`setHost()` 未呼び出し
  のため当然の結果だが、要求どおり実施・記録した）。
- **ModalEditor.java**: 6,763行 → 6,793行（+30、`setHost()` とJavadocの追加のみ）。

### 6-3の気づき

- **ゲート確認で判明した設定ミス**: §3「6-3のゲート」に書いたgrepパターンは親計画書の
  スケッチ名（`setClosePaneCallback`/`setOnCloseBlocked`）をそのまま使っていたが、実際の
  `ModalEditor` にはこの名前のsetterは存在しない。実際は `setExitCallback`（ペインを閉じる）・
  `setCloseBlockedCallback`（閉じられない場合。現状どこからも呼ばれていない未接続の受け口）
  だった。7個の対象setterは以下の通り: `setSplitHorizontalCallback`/`setSplitVerticalCallback`/
  `setExitCallback`/`setCloseBlockedCallback`/`setMovePanePrevCallback`/
  `setMovePaneNextCallback`/`setOnSharedBufferSync`。`EditorHost.java` の Javadoc にこの
  対応関係を記録した。
- **実装内容**: `dev.javatexteditor.app.EditorHost`（新設インタフェース）を
  `PaneManager implements EditorHost` として実装した。追加のみで、`ModalEditor` 側の配線
  （8個のsetter/supplier/function。上記7個＋`setAllEditorsSupplier`/`setLiveBufferLookup`）は
  一切変更していない。既存の per-leaf コールバック内にインラインで書かれていた分割・
  ペイン移動・ペインクローズの実処理を `doSplit`/`doMoveToPrevPane`/`doMoveToNextPane`/
  `doClosePane` という private ヘルパーへ抽出し、既存のコールバックと新設の `EditorHost`
  メソッドの両方がこのヘルパーを共有するようにした（本文は移動しただけで変えていない）。
  `findLiveBuffer` は既存の private メソッドをそのまま public化＋`@Override`付与した
  （新規ロジックの追加ではなく可視性変更のみ）。`allEditors()`/`syncSiblingBuffers(ModalEditor)`
  は既存ロジック（`allEditorsSupplier`のラムダ本体・`syncSiblingBuffers(Leaf)`）を
  そのまま再利用する薄いラッパーとして追加した。`onCloseBlocked()` は現状どこからも
  呼ばれない受け口のため no-op 実装とした。
- **検証結果**: `diff` は空（既知FAILのみ）、起動スモークテストは `exit=124`。`Main.java` は
  無変更（`git diff --stat` で確認）。EditorHost導入は「純追加でリスクほぼゼロ」という
  当初の想定通りだったため、6-4・6-5で必須の手動ペイン検証（§7）は6-3では実施しなかった
  （本書§2の一覧表どおり、手動検証は6-1・6-2・6-4・6-5が対象）。

### 6-1の気づき（6-1/6-2統合の経緯）

本書 §2 の表は当初「6-1＝§6.2手順1〜2（PaneManager新設＋機械的置換のみ）」
「6-2＝§6.2手順3〜4（main()側の配線統一）」と分けていたが、実装に着手したところ
**手順1〜2だけでは `Main.java` がビルドできない**ことが判明した。責務4・10のメソッド群
（`createLeaf`/`refreshCallbacks`等）を `Main` から削除すると、`main()` 側はまだ
`root[0]`/`active[0]` の配列と旧メソッド呼び出しに依存したままのため、コンパイルが
通らない状態になる。

`docs/archive/MAIN_DECOMPOSITION_PLAN.md` §5「進め方の原則」の「各段階で `./scripts/build.sh` が
通ってから次へ進む」という制約を優先し、6-1の時点で `PaneManager` の新設・メソッド移設・
`main()` 側の `PaneManager` 経由への統一（当初の6-2相当分）までを一体で実施した。
6-2は独立した作業が残らなかったため実質的に6-1と同一コミットになった
（進捗記録の「6-2」行は「6-1に統合」として記録する）。

実装したもの:
- `dev.javatexteditor.app.PaneManager`（新設）: `root`/`active` をインスタンスフィールドとして持ち、
  旧 `Main` の責務4（`buildComponent`/`findLiveBuffer`/`syncSiblingBuffers`）・
  責務10（`setupSplitCallbacks`/`shareBufferWithSplit`/`createLeaf`×3/`refreshCallbacks`/
  `rebuildLayout`/`resizeActivePane`/`updateBorders`）を機械的に移設（本文不変、
  `root[0]`→`root`・`active[0]`→`active` の置換のみ）。
- `Main.java` 側: `PaneTree.PaneNode[] root`/`PaneTree.Leaf[] active` ローカル配列を撤去し、
  `PaneManager panes = new PaneManager(...)` に統一。グローバルキーディスパッチャ・
  マウスリスナーは `panes.active()`/`panes.allLeaves()`/`panes.resizeActivePane()`/
  `panes.updateBorders()`/`panes.setActive()` を呼ぶだけになった。
- `Main.java`: 625行 → **343行**。

検証結果:
- `diff /tmp/baseline6.txt /tmp/phase6-1.txt` は空（97テストクラス全て、既知FAIL含め差分なし）
- 起動スモークテスト: `exit=124`、例外ログなし
- 手動ペイン検証（Xvfb + `java.awt.Robot`、実際のキー入力とスクリーンショットで確認）:
  `s v`（左右分割）・`s s`（ネストした上下分割）・`s h`/`s l`（ペインフォーカス切替、
  境界線の色で確認）・`Ctrl+Alt+→`（アクティブペイン拡大、divider位置で確認）・
  `:q`（ペインを1つ閉じてプロセス生存）・閉じた後の残りペインでの編集（INSERT遷移・
  補完ポップアップ表示まで正常動作）を全て確認した。
  **親計画書 §6.5 の検証項目表にある「3. Ctrl+W」は本エディタの実際のキーバインドと
  異なる（実際は `s`+`h`/`j`/`k`/`l`。`ModalEditor.processNormalKey()` の
  `movePanePrevCallback`/`movePaneNextCallback` 呼び出し箇所で確認）。表の誤記であり
  今回の変更によるものではないため、本書ではこの誤記を記録するに留め、
  検証は実際のキーバインドで実施した。**

### 6-5の実施内容と、手動検証で発見した重大な回帰

- **配線の統一**: `PaneManager.refreshCallbacks()` の9個の個別 `leaf.editor().setXxx(...)` 呼び出し
  （うち分割2個は `setupSplitCallbacks()` という別メソッドに分かれていた）を
  `leaf.editor().setHost(this);` の1行へ統一した。`setupSplitCallbacks()` メソッド自体を削除した
  （6-3で追加した `doSplit`/`doMoveToPrevPane`/`doMoveToNextPane`/`doClosePane` はそのまま再利用）。
- **削除前grepゲート（§3）の実施結果**: 本番側の呼び出し箇所は`PaneManager.java`の8箇所
  （`refreshCallbacks()`内の分割2個・移動2個・クローズ1個・共有バッファ2個・全エディタ供給1個）
  にのみ存在し、他は全て `test/` 配下（個々のsetterを直接呼ぶテスト。今回のリファクタ対象外）
  であることを確認してから着手した。
- **🔴 手動ペイン検証で発見した重大な回帰（自動テストでは検出不能だった）**: `:q` がペインを
  一切閉じなくなる回帰を、Xvfb+Robotによる実キー入力検証で発見した。**この回帰は
  `diff`（97テストクラス全て）では検出されなかった**（バグを含む状態でも `diff` は空だった。
  親計画書§6.4の「ペイン分割の挙動を検証する自動テストが存在しない」という警告どおりの
  結果であり、本サブ段階で手動検証が必須とされていた理由そのものを実地で確認した形になった）。
  - **原因**: `ModalEditor.closeCurrentPane()`/`saveAndCloseCurrentPane()` は
    `closeBlockedCallback != null` を「閉じられない事情がある」の判定に使っており、
    「設定されていれば実行・されていなければ何もしない」という通常の optional callback
    パターンでは**ない**（null自体が分岐条件）。6-4で新設した `setHost()` は
    `setCloseBlockedCallback(host::onCloseBlocked)` を含む9個全てを配線する実装にしていたため、
    `setHost()` を呼んだ瞬間に `closeBlockedCallback` が非null（no-op）になり、`:q`/`:wq` が
    常に「閉じられない」分岐に落ちて無反応になっていた。6-4完了時点では `setHost()` を呼ぶ箇所が
    存在しなかったため未発覚で、6-5で実際に配線した瞬間に顕在化した。
  - **修正**: `setHost()` から `setCloseBlockedCallback` の配線を除外した（8個の配線に変更）。
    `EditorHost.onCloseBlocked()` インタフェースメソッド自体は将来のために残したが、
    `setHost()`経由では呼ばれない。両ファイルのJavadocに罠の詳細を記録した。
  - **教訓**: 6-4完了時に「`setHost()`は呼び出し箇所ゼロだから無害」と判断したこと自体は
    事実として正しかったが、「9個全部を配線して問題ないはずだ」という6-4時点の予測
    （本書6-4節「同じ効果、副作用なし」という記述）は誤りだった。個々のsetterの用途を
    「Runnableを設定するだけ」と表面的に見るのではなく、呼び出し側のnullチェックの意味まで
    確認する必要があった。次にこの種の「setterをまとめる」リファクタを行う際は、各setterが
    「未設定=null」をどう解釈しているか（無視するのか、それとも分岐条件として使うのか）を
    個別に確認すること。
- **検証結果（修正後）**: `diff` は空（既知FAILのみ）、起動スモークテスト `exit=124`。
  手動ペイン検証（§7の6項目）を再実施しすべて確認: `s v`（左右分割）・`s h`/`s l`
  （フォーカス切替、境界線の色で確認）・`Ctrl+Alt+→`（アクティブペイン拡大、divider位置で確認）・
  `:q`（**修正後は正しくペインを閉じることを確認**）・同一ファイルを2ペインで開き片方で編集すると
  もう片方に即座に反映される（共有バッファ、`ZZZ`の入力が両ペインに即時反映されることを
  スクリーンショットで確認）。
- **PaneManager.java**: 447行 → 439行。**ModalEditor.java**: 6,793行 → 6,805行
  （`setHost()`のJavadoc拡充による微増）。

## 段階6 完了

6-0〜6-5すべて完了した。`docs/archive/MAIN_DECOMPOSITION_PLAN.md` §9 の「6」行へ以下を転記する:

> 2026-07-28 / 6-0〜6-5（本ブランチのコミット群） / diff空・スモーク✅ / `Main.java`変更なし
> （`PaneManager`/`EditorHost`新設のみ、段階6-1で625→343行に既に反映済み） /
> `root[0]`/`active[0]`の箱を`PaneManager`のインスタンスフィールドへ解消。
> `EditorHost`で23個の外部setterのうち8個を`setHost()`1本へ統合（旧setter自体は削除せず
> 移行期間方式）。6-5の手動検証で`:q`が無反応になる重大な回帰を発見・修正（`setCloseBlockedCallback`
> のnull判定を誤って壊す配線だった）。詳細はdocs/archive/STAGE6_OPTION_C_PLAN.md参照。

全サブ段階完了後、`docs/archive/MAIN_DECOMPOSITION_PLAN.md` §9 の「6」行へ要約を1行で転記し、
本書の詳細ログはこの表を正とする。
