# 段階6（`PaneManager` + `EditorHost`）実行計画書 — Option C（サブ段階分割版）

- 作成日: 2026-07-28
- 対象コミット: `621d56e`（段階0〜5完了・PR #204マージ後）
- 対象ブランチ: `claude/stage6-substep-execution-4of8o5`
- 親計画書: `docs/MAIN_DECOMPOSITION_PLAN.md` §5「段階6 — `PaneManager` ＋ `EditorHost`（最難関）」
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
| 3 | `Ctrl+W` | アクティブペインの枠線が移動する |
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
| 6-0 | | | | | (対象外) | |
| 6-1 | | | | | | |
| 6-2 | | | | | | |
| 6-3 | | | | | (対象外) | |
| 6-4 | | | | | | |
| 6-5 | | | | | | |

全サブ段階完了後、`docs/MAIN_DECOMPOSITION_PLAN.md` §9 の「6」行へ要約を1行で転記し、
本書の詳細ログはこの表を正とする。
