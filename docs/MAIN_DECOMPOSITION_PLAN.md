# Main クラス解体 実行計画書（第9弾リファクタリング）

- 作成日: 2026-07-27
- 対象コミット: `12fe5b6`
- 対象ブランチ: `claude/modular-monolith-refactor-plan-cuqdv4`
- **本計画書は調査と計画のみの成果物であり、`src/` `test/` `scripts/` への変更は一切含まない。**
- 想定読者: 本計画書と対象コードだけを渡された実行者が、追加確認なしで完遂できることを目標に書いた。

---

## 0. スコープ

### ゴール

`src/dev/javatexteditor/Main.java`（1,189行・12責務が同居）を **Composition Root（組み立ての起点）だけを担う約20〜30行のクラス**へ縮小し、抱えている実装を責務ごとの独立クラスへ移す。

判定基準は1つに絞る:

> **Main に残ってよいのは「部品を組み立てる記述」だけ。`if` と `for` を含む処理は1行も置かない。**

### 非ゴール（今回やらないこと）

| 項目 | 理由 |
|---|---|
| `ModalEditor`（6,763行）の分割 | 別提案（MVP化）。段階6で接点が生まれるが本計画には含めない |
| `EditorCanvas`（1,933行）の分割 | 同上 |
| 既知の失敗テスト3件の修正 | 仕様判断が未決（§1.3）。**触ってはならない** |
| 3種類の言語判定の統合 | CLAUDE.md に「統合してはならない」と明記済み（§7.2） |
| DIコンテナ・イベントバス・アノテーションの導入 | CLAUDE.md の「学習目的のシンプルさ」に反する |
| 振る舞いの変更 | 本計画は**外から観測できる挙動を1つも変えない**ことを完了条件とする |

---

## 1. ベースライン（2026-07-27 実測）

### 1.1 ビルド

```
$ ./scripts/build.sh
Build OK

$ find test -name "*.java" | xargs javac -encoding UTF-8 -cp build -d build
（警告1件のみ: ClasspathInputTest.java uses unchecked or unsafe operations）
```

### 1.2 テスト（全97クラスを個別JVM＋180秒タイムアウトで実行）

```
97 クラス中
  95 クラス: 正常終了（rc=0）
   2 クラス: 既知の失敗（rc=1）
```

**`./scripts/test.sh` を使わないこと。** 1クラスが `PASS: N/N` を出した後も JVM が終了しない事象（`EditorCanvas` のステータス行アニメ用 `javax.swing.Timer` が非デーモンスレッドを保持するため）があり、全体が止まる。**必ず §2 の個別実行スクリプトを使う。**

### 1.3 既知の失敗2クラス（**修正禁止**）

```
dev.javatexteditor.editor.ScrollTest        rc=1
  FAIL [halfPageUp: cursor moved up by 20] expected=20 actual=40
  FAIL [halfPage interleaved: row 40] expected=40 actual=60

dev.javatexteditor.editor.ModalEditorTest   rc=1  PASS: 286 / 287
  FAIL: 選択範囲がヤンク内容で上書きされる      （test 側 644行目）
```

いずれも「どちらの動作が正しいか」の仕様判断が未決のまま残されている項目である
（CLAUDE.md「既知の未接続・二重定義」6.／`docs/REFACTORING_PLAN.md` U-7）。
**ついでに直さないこと。** 直すと「作業前後で結果が完全一致」という本計画の完了判定が使えなくなる。

### 1.4 ★最重要★ Main.java にはテストが1件も存在しない

`test/` 全体を走査した結果、**`Main` のメソッドを呼ぶテストはゼロ**である。

```
$ grep -rn "import dev.javatexteditor.Main" test --include=*.java
（出力なし）

$ grep -rhoE "\bMain\.[a-zA-Z]+" test --include=*.java
Main.setupCompileAnalysis   ← コメント内での言及のみ
Main.shareBufferWithSplit   ← コメント内での言及のみ
```

この事実には**表と裏**がある。

- **表（有利）**: `Main` のメソッドはすべて `private static` なので、シグネチャをどう変えてもテストは壊れない。移動の自由度が非常に高い。
- **裏（危険）**: **`Main` の退行を検知できる自動テストが存在しない。** 97クラスが全部通っても、`Main` が壊れていれば見逃す。

したがって本計画では、**全段階で §2.2 の起動スモークテストを必須**とする。これを省略した段階は「完了」と見なさない。

### 1.5 起動スモークテストの実行可能性（検証済み）

このコンテナはヘッドレスだが `Xvfb` が利用できるため、実際にアプリを起動して生存確認ができることを確認済み。

```
$ (Xvfb :99 -screen 0 1280x800x24 &) ; sleep 2
$ DISPLAY=:99 timeout 20 java -cp build dev.javatexteditor.Main
（exit=124 = タイムアウト = 20秒間クラッシュせず生存 = 成功）
```

**注意**: 起動すると `runSetupIfNeeded()` が走り `lib/_openjdk_clone_tmp` を作る。`lib/` は `.gitignore` 対象なので追跡ファイルは汚れないが、**スモークテスト後は `rm -rf lib/_openjdk_clone_tmp` で消すこと**（残すとディスクを圧迫する）。

### 1.6 日本語出力の文字化けについて（検証済み）

この環境では `java` の標準出力の既定文字コードが UTF-8 ではないため、テストの日本語メッセージが `?????` になる。

```
$ java -cp build ...ModalEditorTest | grep "FAIL:"
  FAIL: ?????????????????              ← 化ける

$ java -Dstdout.encoding=UTF-8 -cp build ...ModalEditorTest | grep "FAIL:"
  FAIL: 選択範囲がヤンク内容で上書きされる    ← 正しく出る
```

**§2 の検証スクリプトでは必ず `-Dstdout.encoding=UTF-8` を付けること。**

---

## 2. 検証手順（全段階で共通）

### 2.1 テスト差分の照合

以下を `verify.sh` として**リポジトリ外**（例: `/tmp/verify.sh`）に置く。リポジトリには追加しない（本計画の非ゴール「振る舞いの変更なし」に、成果物の追加も含めないため）。

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
  if   [ $rc -eq 124 ]; then st="HANG"
  elif [ $rc -ne 0 ];   then st="RC=$rc"
  else                       st="OK"; fi
  echo "$cn|$st|$pass|FAILlines=$fails" >> "$OUT"
done
```

手順:

```bash
# 着手前に1回だけ
./scripts/build.sh && find test -name "*.java" | xargs javac -encoding UTF-8 -cp build -d build
/tmp/verify.sh /tmp/baseline.txt

# 各段階の完了時に毎回
./scripts/build.sh && find test -name "*.java" | xargs javac -encoding UTF-8 -cp build -d build
/tmp/verify.sh /tmp/phaseN.txt
diff /tmp/baseline.txt /tmp/phaseN.txt   # ← 出力が空であること
```

**完了判定は `diff` が空であること。** 「たぶん大丈夫」という人間の判断を入れない。

### 2.2 起動スモークテスト（§1.4 のため全段階で必須）

```bash
pkill Xvfb 2>/dev/null; (Xvfb :99 -screen 0 1280x800x24 >/dev/null 2>&1 &); sleep 2
DISPLAY=:99 timeout 20 java -cp build dev.javatexteditor.Main > /tmp/launch.log 2>&1
echo "exit=$?"          # 124 であること（= 20秒生存した）
grep -iE "exception|error at|Caused by" /tmp/launch.log   # 出力が無いこと
rm -rf lib/_openjdk_clone_tmp
```

`exit=124` 以外（特に `exit=1`）は起動時例外を意味する。**その段階はコミットしてはならない。**

### 2.3 段階6専用の追加検証（ペイン構造）

段階6は `root[]`/`active[]` を作り替えるため、上記2つに加えて手動確認を行う。方法は §5.6 に記す。

---

## 3. 現状分析

### 3.1 Main.java に同居する12の責務

行番号は**コミット `12fe5b6` 時点**のもの。作業を進めると行はずれるので、**メソッド名を主、行番号を従として扱うこと。**

| # | 責務 | 行範囲 | 中身 | 行数 |
|---|---|---|---|---|
| 1 | 定数・ウィンドウ既定値 | 47–53 | `ACTIVE_BORDER_COLOR` `WINDOW_WIDTH/HEIGHT` `PANE_RESIZE_*` | 7 |
| 2 | サービス生成（静的） | 56–90 | `WD_MANAGER` `COMPILE_ANALYZER` `JDK_INDEX` `SOURCE_ANALYZER` `IMPORT_SUGGESTER` `AUTO_IMPORT_HANDLER` `COMPLETION_INDEX` `WORD_INDEX` `PROJECT_BUILDER` `MAIN_CLASS_FINDER` `C_PROJECT_BUILDER` `C_COMPILE_ANALYZER` `BUFFER_REGISTRY` | 35 |
| 3 | 可変グローバル状態 | 81,85,149,150 | `runningProcess` `pendingRunExtraClasspath` `initialCellW/H` | 4 |
| 4 | ペイン構築・共有バッファ | 97–148 | `buildComponent` `findLiveBuffer` `syncSiblingBuffers` | 52 |
| 5 | 画面計測・ウィンドウ配置 | 156–202, 1095–1108 | `computeDisplayScale` `computeInitialCellSize` `computeInitialWindowSize` `detectMouseScreen` `centerOnScreen` `buildTitle` | 61 |
| 6 | 診断連携（Java／C） | 203–371 | `setupCompileAnalysis` `isJavaBuffer` `isCBuffer` `runCompileAnalysis` `runCAnalysis` `organizeCIncludes` | 169 |
| 7 | ビルド・実行（Java） | 372–501 | `triggerCompile` `triggerRun` `triggerCompileAndRun` `doCompile` `resolveAndRunMainClass` `runJavaClass` | 130 |
| 8 | ビルド・実行（C） | 502–587 | `triggerCompileC` `triggerRunC` `triggerCompileAndRunC` `doCompileC` `runCExecutable` | 86 |
| 9 | 子プロセス出力 | 588–606 | `startRunOutputReader` | 19 |
| 10 | ペイン分割・配線 | 607–840 | `setupSplitCallbacks` `shareBufferWithSplit` `createLeaf`×3 `refreshCallbacks` `rebuildLayout` `computeF2PopupFont` `resizeActivePane` `updateBorders` | 234 |
| 11 | 起動手順＋GUI組み立て | 841–1094 | `main()` | 254 |
| 12 | セットアップ実行 | 1109–1188 | `runSetupIfNeeded` `resolveLibDir` `resolveScriptDir` | 80 |

### 3.2 `main()` 254行の内訳

| 行範囲 | 内容 |
|---|---|
| 842–880 | EDT 前処理（セットアップ起動・引数解析・初期ファイル読込・`WD_MANAGER` 初期化・索引起動・画面計測） |
| 881–1094 | `SwingUtilities.invokeLater` の無名ラムダ（**210行**） |
| ├ 882–912 | `JFrame` 生成・初期リーフ・`root[]`/`active[]` 宣言・WD変更リスナ・初回配線 |
| ├ **914–1067** | **`KeyboardFocusManager` のグローバルキーディスパッチャ（154行）** |
| │  ├ 916–953 | Ctrl+Shift+矢印（フォントセルサイズ）・Ctrl+Alt+矢印（ペイン伸縮） |
| │  ├ **955–1010** | **F2 診断ダイアログ（56行・UI組み立てが直書き）** |
| │  ├ 1012–1028 | F10/F11/F12 ビルド・実行の振り分け |
| │  └ 1030–1067 | IME 委譲判定・`KEY_TYPED` 処理 |
| ├ 1069–1083 | マウスクリックによるアクティブペイン切替 |
| └ 1085–1094 | `setVisible` ・フォーカス付与 |

**エントリポイントが見つけづらい直接の原因**: `main()` は 1,189行中の**841行目（71%地点）**にあり、その手前を50個以上の `private static` メソッドが占めている。

### 3.3 ★構造上の根本原因★ `root[0]` / `active[0]`

```java
// Main.java 895–896行
PaneTree.PaneNode[] root   = { firstLeaf };   // 要素1個の配列＝書き換え可能な箱
PaneTree.Leaf[]     active = { firstLeaf };
```

Java のラムダは外側のローカル変数を書き換えられないため、**配列を「箱」として使う回避策**である。
この `root[0]` / `active[0]` は **Main.java 内で52箇所**参照されている。

**これが「Main への集約」の真の原因である。** この2つは `main()` のローカル変数なので、
参照するコードは物理的に `main()` と同じスコープにしか書けない。
ペインに触れる処理が全部 `main()` に集まっているのは、設計の怠慢ではなく**この構造による強制**である。

したがって段階6の本質は「メソッドを移動する」ことではなく、
**箱をやめて `PaneManager` のインスタンスフィールドにする**ことである。
ここを解かない限り、他をいくら動かしても `main()` は痩せない。

### 3.4 コールバック配線の現状（MVP提案との接点）

| 場所 | 配線している setter 数 |
|---|---|
| `createLeaf`（3番目のオーバーロード, 668–721） | 17 |
| `refreshCallbacks`（722–774） | 6 |
| **合計** | **23** |

`ModalEditor` 側に「外の世界へ伝える手段」が用意されていないため、必要が生じるたび
`Runnable` / `Consumer` を1個ずつ足してきた結果である。段階6でこれを1本のインタフェースに畳む（§6）。

---

## 4. 目標構造

```
src/dev/javatexteditor/
├── Main.java                       ← 約20〜30行。main() のみ
├── app/                            ← 新設パッケージ
│   ├── EditorApplication.java      ← GUI の組み立て（旧 invokeLater ラムダ）
│   ├── AnalysisServices.java       ← 解析サービスの保持
│   ├── PaneManager.java            ← 分割・レイアウト・配線・root/active
│   ├── EditorHost.java             ← ModalEditor → 外界のポート（interface）
│   ├── GlobalKeyDispatcher.java    ← グローバルキー処理
│   ├── DiagnosticPopup.java        ← F2 ダイアログ
│   ├── LiveDiagnostics.java        ← 保存/離脱時の診断連携（Java・C）
│   ├── JavaBuildRunner.java        ← F10/F11/F12（Java）
│   ├── CBuildRunner.java           ← F10/F11/F12（C）
│   ├── ProcessOutputPump.java      ← 子プロセス出力の読み取り
│   └── SetupBootstrap.java         ← setup.sh の自動実行
└── （既存パッケージは変更なし）
```

### 新規パッケージ `app/` を作る理由

現状 `Main` `PaneTree` `BufferRegistry` `WorkingDirectoryManager` が `dev.javatexteditor` 直下に散らばっている。
新設クラス11個をここへ足すと直下が15クラスになり、かえって見通しが悪化する。
`app/`（アプリケーションの組み立て層）にまとめることで、
**「`dev.javatexteditor` 直下にあるのは `Main` だけ」**という状態を作り、エントリポイントの発見性を最大化する。

`PaneTree` / `BufferRegistry` / `WorkingDirectoryManager` の移動は**本計画に含めない**
（3クラスは既にテスト済みで独立しており、移動は純粋な `git mv` だが `import` の書き換えが広範に及ぶため、
段階7完了後に別途判断する。§8 の残課題に記載）。

---

## 5. 実施段階

### 進め方の原則（第1〜8弾で確立済み。踏襲すること）

1. **1段階＝1コミット。** 複数段階をまとめてコミットしない
2. 各段階で `./scripts/build.sh` → §2.1 の `diff` が空 → §2.2 のスモークテスト成功、の3つを全部満たしてからコミット
3. **公開シグネチャを変えない。** `Main` は全メソッドが `private static` なので今回この制約は自動的に満たされる（§1.4）
4. 判断が割れる箇所に遭遇したら、**勝手に決めずに中断して確認を仰ぐ**

### 段階の一覧

| 段階 | 対象 | 新クラス | 削減目安 | リスク |
|---|---|---|---|---|
| 0 | `main()` をファイル先頭へ移動 | なし | ±0 | **なし** |
| 1 | セットアップ実行 | `SetupBootstrap` | −80 | 極小 |
| 2 | ビルド・実行（Java/C/出力） | `JavaBuildRunner` `CBuildRunner` `ProcessOutputPump` | −235 | 小 |
| 3 | 診断連携 | `LiveDiagnostics` | −169 | 小 |
| 4 | F2 ダイアログ | `DiagnosticPopup` | −70 | 小 |
| 5 | サービス生成 | `AnalysisServices` | −35 | **中**（§5.5 の注意） |
| 6 | ペイン管理 | `PaneManager` `EditorHost` | −286 | **大** |
| 7 | キー処理・組み立て | `GlobalKeyDispatcher` `EditorApplication` | −270 | 中 |

段階0〜5は互いに独立しており、**順序を入れ替えても、途中で止めても構わない**。
段階6と7は6→7の順序が必須（7は6の成果物 `PaneManager` を引数に取るため）。

---

### 段階0 — `main()` をファイル先頭へ移動

**目的**: ご指摘の「エントリポイントが見つけづらい」症状だけを、リスクゼロで即座に解消する。

**作業**: `Main.java` の 841–1094行（`main()` 全体）を、クラス宣言直後（現 47行目の直前）へ移動する。**1文字も書き換えない。純粋な切り取りと貼り付けのみ。**

**根拠**: Java はメソッドの記述順に意味を持たない。バイトコードは同一になる。

**完了判定**:
- `./scripts/build.sh` が成功
- §2.1 の `diff` が空
- §2.2 のスモークテストが `exit=124`
- **追加**: 段階0に限りバイトコードの意味的同一性を確認する（手順は下記）

#### 段階0 のバイトコード検証（実施済み・2026-07-27）

**訂正**: 本計画の初版には「`javap` の出力が移動前後で同一であることを確認する」と書いたが、**これは誤りだった**。
実際に比較したところ出力は一致しない。Java はソースの記述順に応じて
①メソッドの並び順、②定数プールの番号割り当て、③合成ラムダメソッドの連番
を変えるため、**純粋な並べ替えでもクラスファイルはバイト同一にならない**。
以降この検証を行う者は、次の4点で「意味的同一性」を確認すること。

| # | 確認内容 | 実施結果 |
|---|---|---|
| 1 | ソースの**行の集合**が一致（`diff <(sort 旧) <(sort 新)` が空）＝純粋な並べ替えであり1行も増減していない | ✅ 一致（1,189行→1,189行） |
| 2 | **`static {}`（`<clinit>`）の逆アセンブルが一致** | ✅ 完全一致（64行） |
| 3 | メソッド数と全シグネチャが一致（ラムダの連番のみ除く） | ✅ 96個すべて一致 |
| 4 | 命令列・記号参照が一致（`ldc`/`ldc_w` の幅違いは同一視） | ✅ 完全一致 |

**#2 が最も重要である。** static フィールドの初期化子と `static` ブロックは
**Java でソース順に意味を持つ唯一の要素**であり、ここが一致していれば
「並べ替えによって初期化順序が変わっていない」ことが保証される。
段階0では静的フィールド（47–90行・149–150行）を1つも動かしていないため一致した。

**#1・#3・#4 で観測された差分は以下の2つだけで、いずれも意味的影響はない。**

- **ラムダの連番が振り直された**（例: `lambda$main$47` → `lambda$main$1`）。
  javac は合成ラムダメソッドをソース順に採番するため、`main()` が先頭へ来たことで番号がずれた。
  96個すべて、囲みメソッド名・引数・戻り値は完全に一致している。
  この名前はコンパイルのたびに再生成されるものであり、永続化されない（本プロジェクトはラムダを直列化していない）。
- **`ldc_w` → `ldc` に変わった文字列ロードが6件**。定数プールの番号が振り直された結果、
  該当の文字列が索引255以下へ移り、2バイト索引版から1バイト索引版へ縮んだだけである
  （これに伴い当該メソッド内のバイトコードオフセットが数バイトずれ、
  分岐表・例外表の数値も連動して変わる。§1 の生 `diff` が大きく見えるのはこのため）。

**注**: 段階7完了時点で `Main.java` は約20行になるためこの移動は無意味になる。**それでも段階0を先に行う価値がある**のは、段階1〜7が数日〜数週にわたる間、ずっと効果があるため。

---

### 段階1 — `SetupBootstrap`

**対象**: 1109–1188行（`runSetupIfNeeded` `resolveLibDir` `resolveScriptDir`）

**新クラス**: `app/SetupBootstrap.java`

```java
public final class SetupBootstrap {
    public static void runIfNeeded() { ... }   // 旧 runSetupIfNeeded
    private static Path resolveLibDir()    { ... }
    private static Path resolveScriptDir() { ... }
}
```

**呼び出し側の変更**: `main()` 冒頭の `runSetupIfNeeded();` → `SetupBootstrap.runIfNeeded();`（1行）

**なぜ最初か**: この3メソッドは `Main` の他のどのフィールド・メソッドにも依存していない
（`WD_MANAGER` も `root[]` も参照しない）。**完全に独立した唯一のブロック**であり、
移動して壊れる経路が存在しない。ウォームアップとして最適。

**★注意（調査で確認済みの事実）★ 2メソッドとも `Main.class` の位置に依存している**

```java
// resolveLibDir（1167行）
var url = Main.class.getProtectionDomain().getCodeSource().getLocation();

// resolveScriptDir（1185行）
CodeSourceLocator.findUpward(Main.class, "scripts", 4, Files::isDirectory)
```

移動時にこれを `SetupBootstrap.class` へ書き換えたくなるが、**書き換えないこと。**

理由: `getCodeSource().getLocation()` はクラスパスのルート（`build/`、jar 化した場合は jar 自体）を返すため、
このプロジェクトの構成では両クラスで同じ値になり、**今は**どちらでも動く。
しかし将来クラスを別の出力先へ分けた場合に静かに壊れる。
**`Main.class` を明示的に引数で受け取る形にして、基準点を固定する。**

```java
public final class SetupBootstrap {
    public static void runIfNeeded(Class<?> anchor) { ... }   // 呼び出し側は Main.class を渡す
}
```

**リスク**: 極小（上記を守る限り）。

**気づきとして記録すべき点（本計画では直さない。§7.4）**: `resolveLibDir` は
`CodeSourceLocator.findUpward` とほぼ同じ探索を手書きで再実装している
（`resolveScriptDir` は既に `CodeSourceLocator` を使っている）。共通化の余地があるが、
本計画は振る舞いを変えないことを条件としているため、§9 の「気づき」欄に記録して別途提案する。

**完了判定**: §2.1 の `diff` が空、§2.2 が `exit=124`、かつ `/tmp/launch.log` に `[setup]` の行が出ていること
（＝セットアップが従来どおり起動している証拠）。

---

### 段階2 — `JavaBuildRunner` / `CBuildRunner` / `ProcessOutputPump`

**対象**: 372–606行（Java 130行 + C 86行 + 出力 19行）＋ 静的状態 `runningProcess`(81) `pendingRunExtraClasspath`(85) `PROJECT_BUILDER`(70) `MAIN_CLASS_FINDER`(72) `C_PROJECT_BUILDER`(75)

**新クラス**:

```java
public final class ProcessOutputPump {          // 先に作る（他2つが依存）
    public static Thread start(InputStream in, ModalEditor editor, boolean isError) { ... }
}

public final class JavaBuildRunner {
    private final ProjectBuilder builder;
    private final MainClassFinder finder;
    private Process running;                    // 旧 Main.runningProcess
    private List<Path> pendingExtraClasspath;   // 旧 Main.pendingRunExtraClasspath

    public void compile(ModalEditor ed, EditorCanvas cv) { ... }
    public void run(ModalEditor ed, EditorCanvas cv) { ... }
    public void compileAndRun(ModalEditor ed, EditorCanvas cv) { ... }
}

public final class CBuildRunner { /* 同型 */ }
```

**なぜここが最良の着手点か**: この10メソッドは**すべて `(ModalEditor, EditorCanvas)` を引数で受け取っており、
`root[]` / `active[]` を一切参照していない**。つまり `main()` のスコープに縛られていない。
**単独で切り出せる最大の塊**であり、Main.java の20%が一度に減る。

**★重要な注意 ★ `runningProcess` は Java と C で共有されている**

現状 `runningProcess` は1個の static フィールドで、`runJavaClass` と `runCExecutable` の
**両方が同じものを読み書きしている**（＝ F11 で Java プログラムを起動した後に C プログラムを
F11 で起動すると、先の Java プロセスが `destroy()` される）。

これは意図的な「多重実行防止」である。**`JavaBuildRunner` と `CBuildRunner` に
別々のフィールドとして分けてはならない。** 分けると挙動が変わる（両方が同時に走れてしまう）。

対処: `RunningProcessHolder`（もしくは `ProcessOutputPump` に同居させた1個の可変ホルダー）を作り、
両 Runner のコンストラクタに**同じインスタンス**を渡す。

```java
public final class RunningProcessHolder {
    private Process current;
    public synchronized void replaceWith(Process p) { if (current != null) current.destroy(); current = p; }
}
```

同様に `pendingRunExtraClasspath` も、`MainClassPicker` の選択確定コールバックが読む
（CLAUDE.md「main複数候補時の持ち越し」参照）ため、**Java 側の Runner が所有する**。C 側は使わない。

**リスク**: 小。ただし上記の共有状態を見落とすと**静かに挙動が変わる**。
自動テストでは検知できない（F10/F11/F12 は GUI 依存で自動テスト対象外）。

**完了判定**: §2.1 の `diff` が空、§2.2 が `exit=124`。
**加えて手動確認**: `ProjectBuilderTest`（23）と `CProjectBuilderTest`（21）が引き続き rc=0 であること
（これらは `ProjectBuilder` / `CProjectBuilder` 本体のテストであり Runner のテストではないが、
呼び出し方を壊していないことの間接的な確認になる）。

---

### 段階3 — `LiveDiagnostics`

**対象**: 203–371行（`setupCompileAnalysis` `isJavaBuffer` `isCBuffer` `runCompileAnalysis` `runCAnalysis` `organizeCIncludes`）
＋ `COMPILE_ANALYZER`(58) `C_COMPILE_ANALYZER`(78) `SOURCE_ANALYZER`(60) `AUTO_IMPORT_HANDLER`(62)

**新クラス**: `app/LiveDiagnostics.java`

```java
public final class LiveDiagnostics {
    public void install(ModalEditor editor, EditorCanvas canvas) { ... }  // 旧 setupCompileAnalysis
    private void runJava(...) { ... }
    private void runC(...) { ... }
    private void organizeCIncludes(...) { ... }
    static boolean isJavaBuffer(ModalEditor ed) { ... }
    static boolean isCBuffer(ModalEditor ed) { ... }
}
```

**★重要な注意★ `compileGeneration` の世代ガードを壊さないこと**

`setupCompileAnalysis` は `AtomicLong compileGeneration` を**クロージャで保持**しており、
`runCompileAnalysis` が「自分より新しい解析要求が出ていたら結果を捨てる」という
**競合状態対策**を行っている（CLAUDE.md「auto-import選択ポップアップの無限再発」節の根本原因2）。

この世代カウンタは**編集対象（ペイン）ごとに1つ**でなければならない。
`LiveDiagnostics` を全ペインで共有する単一インスタンスにすると、
**世代カウンタも共有されてしまい、別ペインの解析が互いの結果を捨て合う**という新規バグになる。

対処: `install()` が呼ばれるたびに、そのペイン専用の `AtomicLong` を生成して
内部の per-editor 状態として保持する。
（もっとも単純なのは `install()` の中でローカルに `AtomicLong` を作り、
現状と同じくクロージャで捕捉する形をそのまま維持すること。**これを推奨する。**）

**リスク**: 小。ただし上記を見落とすと、分割ウィンドウ使用時に診断が消える散発的な不具合になる。

**完了判定**: §2.1 の `diff` が空（特に `CompileTriggerCallbackTest` が rc=0）、§2.2 が `exit=124`。

---

### 段階4 — `DiagnosticPopup`（F2）

**対象**: 955–1010行（ラムダ内の F2 ブロック56行）＋ `computeF2PopupFont`(788–801)

**新クラス**: `app/DiagnosticPopup.java`

```java
public final class DiagnosticPopup {
    public static void showForCursorRow(JFrame owner, ModalEditor editor) { ... }
}
```

**呼び出し側**: ディスパッチャ内が次の3行になる。

```java
if (e.getKeyCode() == KeyEvent.VK_F2) {
    DiagnosticPopup.showForCursorRow(frame, active[0].editor());
    return true;
}
```

**なぜ段階6より前か**: ディスパッチャ全体（154行）の中で、
**F2ブロックだけが `root[]`/`active[]` への依存が浅い**（`active[0].editor()` を1回読むだけ）。
先に抜くことでディスパッチャが154行→約100行になり、段階7の見通しが良くなる。

**注意**: 元コードは `JOptionPane`（モーダルダイアログ）を表示する。
ディスパッチャ冒頭には「モーダルダイアログが前面にある場合はキー処理をスキップする」ガード
（`if (focused != frame) return false;`）があり、**この2つは対で機能している**。
`DiagnosticPopup` に移してもダイアログの親が `frame` のままであることを必ず確認すること。

**完了判定**: §2.1 の `diff` が空、§2.2 が `exit=124`。

---

### 段階5 — `AnalysisServices`

**対象**: 58–90行の静的サービス群のうち、段階2・3で移動しなかったもの
（`JDK_INDEX` `IMPORT_SUGGESTER` `COMPLETION_INDEX` `WORD_INDEX` `BUFFER_REGISTRY` `WD_MANAGER`）

**新クラス**: `app/AnalysisServices.java`

**★最重要の注意★ 索引の構築開始タイミングを遅らせてはならない**

```java
// 現状 Main.java 59行
private static final JdkClassIndex JDK_INDEX = JdkClassIndex.build();
```

`JdkClassIndex.build()` は**非同期**である（内部で `Thread.ofVirtual().start(...)` して即座に返る）。
つまりこの行は「重い処理」ではなく「バックグラウンド構築の**開始合図**」である。

現在この行は `Main` クラスのロード時＝`main()` 本体より前に実行されるため、
**アプリのウィンドウが出るより早く索引構築が始まっている**。

これを `AnalysisServices` のインスタンスフィールドに移し、生成を `invokeLater` の中に置くと、
**構築開始が数百ミリ秒〜遅れる**。その結果、起動直後の `Ctrl+Space` が候補ゼロで空振りする
（クラッシュしないため自動テストでは絶対に検知できない）。

**対処（必須）**: `AnalysisServices` の生成は、`main()` の**`SwingUtilities.invokeLater` より前**、
かつ**できるだけ早い位置**で行う。同様に `CompletionIndex.build()` `WordIndex.build()` も
現状どおり `invokeLater` の前で開始させる。

```java
public static void main(String[] args) {
    SetupBootstrap.runIfNeeded();
    var services = AnalysisServices.createAndStartIndexing(projectRoot);  // ← EDT に入る前
    SwingUtilities.invokeLater(() -> new EditorApplication(services, ...).start());
}
```

**リスク**: 中。**振る舞いが変わりうる唯一の段階**。
「動くけれど、起動直後の補完が効かない」という形で現れるため、テストでは捕まらない。
レビュー時はこの一点だけを重点確認すること。

**完了判定**: §2.1 の `diff` が空、§2.2 が `exit=124`。
**加えて**: `AnalysisServices` の生成行が `SwingUtilities.invokeLater(` より上にあることを目視確認する。

---

### 段階6 — `PaneManager` ＋ `EditorHost`（最難関）

**対象**: 97–148行、607–840行、および `main()` 内 882–912行の `root[]`/`active[]` 宣言と初回配線

**本段階の本質**: メソッドの移動ではなく、**§3.3 の「箱」の解体**である。

#### 6.1 変換の中身

```java
// Before（main() のローカル変数・52箇所から root[0] / active[0] として参照）
PaneTree.PaneNode[] root   = { firstLeaf };
PaneTree.Leaf[]     active = { firstLeaf };

// After（PaneManager のインスタンスフィールド）
public final class PaneManager {
    private final JFrame frame;
    private PaneTree.PaneNode root;
    private PaneTree.Leaf     active;

    public PaneTree.Leaf active() { return active; }
    public void splitHorizontal() { ... }
    public void splitVertical()   { ... }
    public void closeActivePane() { ... }
    public void moveToNextPane()  { ... }
    public void moveToPrevPane()  { ... }
    public void resizeActive(int keyCode) { ... }
    // 旧 buildComponent / rebuildLayout / updateBorders / createLeaf /
    //    refreshCallbacks / setupSplitCallbacks / shareBufferWithSplit /
    //    findLiveBuffer / syncSiblingBuffers はすべて private メソッドとして内包
}
```

#### 6.2 移行手順（この順で行うこと）

1. `PaneManager` クラスを作り、`root`/`active` をフィールドとして持たせる
2. §3.1 の責務10（607–840）と責務4（97–148）のメソッドを、**本文を変えずに** `PaneManager` の
   `private` メソッドとして移す。この時点では `root[0]` → `root`、`active[0]` → `active` の
   **機械的な置換のみ**を行い、ロジックには一切触れない
3. `main()` 側は `PaneManager pm = new PaneManager(frame, firstLeaf);` に置き換え、
   `root[0]` → `pm.root()`、`active[0]` → `pm.active()` へ差し替える
4. ビルドが通ったら §2 の全検証。**ここでいったんコミットする**
5. その後、別コミットで `EditorHost` インタフェース（§6.3）を導入する

**手順2と5を同じコミットに混ぜないこと。** 混ぜると、問題が起きたときに
「置換の失敗」なのか「インタフェース設計の失敗」なのか切り分けられなくなる。

#### 6.3 `EditorHost` インタフェース（23個の setter を1本に畳む）

```java
// dev.javatexteditor.app.EditorHost
public interface EditorHost {
    void splitHorizontal();
    void splitVertical();
    void closePane();
    void onCloseBlocked();
    void moveToNextPane();
    void moveToPrevPane();
    List<ModalEditor> allEditors();
    UndoablePieceTable findLiveBuffer(String absolutePath);
    void syncSiblingBuffers(ModalEditor source);
}
```

`PaneManager implements EditorHost` とし、`ModalEditor` 側は
`setSplitHorizontalCallback` 〜 `setOnSharedBufferSync` の**7個の setter を
`setHost(EditorHost)` 1個に置き換える**。

**★ここが `ModalEditor` の公開シグネチャに触れる唯一の箇所である。**
第1〜8弾で守ってきた「公開シグネチャを変えない」原則の例外になるため、次のいずれかを取る。

- **推奨**: 旧 setter を残し、内部で `EditorHost` のデフォルト実装に委譲する（第6弾で採用した移行期間方式）
- 代替: 旧 setter を削除する。ただし**着手前にユーザーの承認が必要**

**判断が必要なため、手順5に着手する前にいったん止めて確認を仰ぐこと。**

#### 6.4 なぜ最も危険か

- `root[0]`/`active[0]` の52箇所すべてを正しく置換する必要がある。1箇所でも取りこぼすとコンパイルは通るが挙動が変わる
- ペイン分割の挙動を検証する自動テストが**存在しない**（`PaneTreeTest` はツリー操作のみを検証しており、
  `JSplitPane` への反映・フォーカス・境界線は対象外）

#### 6.5 段階6専用の手動検証（§2.3）

`Xvfb` 上でアプリを起動し、`java.awt.Robot` でキーを送って以下を確認するスクリプトを
`/tmp` に作成して実行する（リポジトリには追加しない）。

| # | 操作 | 期待 |
|---|---|---|
| 1 | `s` `v` | 左右に分割される |
| 2 | `s` `s` | 上下に分割される |
| 3 | `Ctrl+W` | アクティブペインの枠線が移動する |
| 4 | `Ctrl+Alt+→` | アクティブペインが広がる |
| 5 | `:q` | ペインが1つ閉じる（最後の1枚では閉じない） |
| 6 | 同一ファイルを2ペインで開き片方で編集 | もう片方に即座に反映される |

**6番は特に重要**（共有バッファ機構。`findLiveBuffer`/`syncSiblingBuffers` が
`PaneManager` へ移ることで壊れやすい）。`SharedBufferTest`（13）は
`findLiveBuffer` 相当を**テスト内のフェイク実装で再現している**ため、
本物の `PaneManager` が壊れても検知できない。

---

### 段階7 — `GlobalKeyDispatcher` ＋ `EditorApplication`

**対象**: 914–1067行（ディスパッチャ）、881–1094行の残り（組み立て）、156–202行＋1095–1108行（画面計測）

**新クラス**:

```java
public final class GlobalKeyDispatcher implements KeyEventDispatcher {
    private final JFrame frame;
    private final PaneManager panes;
    private final JavaBuildRunner javaRunner;
    private final CBuildRunner cRunner;
    private boolean pressedHandled = false;      // 旧 boolean[] pressedHandled

    @Override public boolean dispatchKeyEvent(KeyEvent e) { ... }
}

public final class EditorApplication {
    public void start() { ... }   // 旧 invokeLater ラムダの中身
}
```

**なぜ最後か**: 段階2〜6が終わった時点で、ディスパッチャの中身は
「`javaRunner.compile(...)` を呼ぶ」「`DiagnosticPopup.show(...)` を呼ぶ」といった
**委譲だけ**になっている。実装が残っていない状態で機械的に切り出せる。
逆に先に着手すると、まだ中身が詰まった154行をそのまま抱えて移すことになり、意味が薄い。

**`boolean[] pressedHandled` も箱である**。`GlobalKeyDispatcher` のフィールドに変換する。
この箱は `KEY_PRESSED` と `KEY_TYPED` の二重処理防止に使われており、
IME（日本語入力）の挙動に直結する。§2.3 に「日本語入力で `あ` を入力し、
1文字だけ挿入されること（2文字にならないこと）」を追加して確認する。

**最終形の `Main.java`**:

```java
package dev.javatexteditor;

import dev.javatexteditor.app.*;
import javax.swing.SwingUtilities;

public final class Main {
    public static void main(String[] args) {
        SetupBootstrap.runIfNeeded();
        StartupArgs startup = StartupArgs.parse(args);
        AnalysisServices services = AnalysisServices.createAndStartIndexing(startup.projectRoot());
        SwingUtilities.invokeLater(() -> new EditorApplication(startup, services).start());
    }
}
```

**完了判定**: §2.1 の `diff` が空、§2.2 が `exit=124`、§6.5 の手動検証6項目＋IME確認。
加えて `wc -l src/dev/javatexteditor/Main.java` が **30行以下**であること。

---

## 6. 全段階完了後の姿

| 指標 | 現在 | 完了後（見込み） |
|---|---|---|
| `Main.java` の行数 | 1,189 | **約20** |
| `main()` の位置 | 841行目（71%地点） | **8行目付近** |
| `Main` の責務数 | 12 | **1**（組み立てのみ） |
| `Main` 内の `root[0]`/`active[0]` | 52箇所 | **0** |
| `ModalEditor` の外界向け setter | 23 | **16 + `setHost()` 1個**（§6.3 で7個を統合） |
| `dev.javatexteditor` 直下のクラス | 4 | 4（`app/` に11クラス新設） |

---

## 7. 禁止事項

### 7.1 既知の失敗3件に触れない

§1.3 の `ScrollTest` 2件・`ModalEditorTest` 1件は仕様判断が未決である。
直すと §2.1 の `diff` による完了判定が機能しなくなる。**「ついでに」直さない。**

### 7.2 3種類の言語判定を統合しない

CLAUDE.md（第8弾の記録）に明記されている。対象拡張子が用途ごとに異なる。

| 判定 | 対象 | 用途 |
|---|---|---|
| `Main.isCBuffer` | `.c` `.h` のみ | F10/F11/F12 の振り分け・C診断 |
| `ModalEditor.isCFilePath` | `.c` `.h` `.cc` `.cpp` `.cxx` `.hpp` `.hh` `.hxx` | Shift+K の定義ジャンプ |
| `ui.SourceLanguage.detect` | 上と同じ広い集合 | 構文ハイライト |

統合すると C++ ファイルが C コンパイラへ回される。
段階3で `isJavaBuffer`/`isCBuffer` を `LiveDiagnostics` へ移す際、
**他の2つと同じに見えても絶対に共通化しないこと。**

### 7.3 便利な仕組みを導入しない

DIコンテナ・イベントバス・アノテーションによる自動配線・リフレクションによる探索は使わない。
CLAUDE.md の「学習目的のシンプルさ」に反する。**コンストラクタで手渡しするだけで足りる。**

### 7.4 振る舞いを「改善」しない

移動の途中で「ここはこう書いたほうが良い」と気づいても、**本計画では直さない**。
リファクタリングと機能変更を同じコミットに混ぜると、退行時の切り分けができなくなる。
気づいた点は §9 の「気づき」欄に記録し、別途提案する。

---

## 8. 本計画に含めない残課題

| # | 内容 | 判断が必要な理由 |
|---|---|---|
| R-1 | `PaneTree` `BufferRegistry` `WorkingDirectoryManager` を `app/` へ移動 | `import` の書き換えが広範。段階7完了後に費用対効果を再評価 |
| R-2 | `ModalEditor`（6,763行）の MVP 分割 | 別提案。段階6の `EditorHost` が接続点になる |
| R-3 | `EditorCanvas`（1,933行）の分割 | 公開 setter 28個がテストから使われており、移行期間の設計が必要 |
| R-4 | パッケージ境界を機械的に検査する仕組み | 自作テストハーネスで `import` を走査する案。モジュラーモノリス提案の一部 |
| R-5 | 既知の失敗3件の仕様確定 | ユーザー判断が必要 |
| R-6 | `Main` に対するテストの新設 | 現在ゼロ（§1.4）。段階7後、`EditorApplication` は GUI 依存のままだが `StartupArgs` は純粋ロジックとしてテスト可能になる |

---

## 9. 進捗記録欄（実行者が埋める）

| 段階 | 実施日 | コミット | `diff` 空 | スモーク | 行数 | 気づき |
|---|---|---|---|---|---|---|
| 0 | 2026-07-27 | `6b2601f` | ✅ | ✅ 124 | 1,189 → 1,189 | 計画書の「javap 出力が同一になる」は誤りだった。正しい検証4項目を §5 段階0 に追記済み |
| 1 | 2026-07-27 | （本コミット） | ✅ | ✅ 124 | 1,189 → **1,104**（−85） | ① `Paths` の import が未使用になったため削除した（抽出の直接の帰結であり振る舞い変更ではない）。② `resolveLibDir` は `CodeSourceLocator.findUpward` の手書き再実装。共通化の余地があるが §7.4 に従い据え置き、`SetupBootstrap` の Javadoc に注記した |
| 2 | | | | | | |
| 3 | | | | | |
| 4 | | | | | |
| 5 | | | | | |
| 6 | | | | | |
| 7 | | | | | |

完了後、本計画で確定した設計判断を **CLAUDE.md へ追記すること**
（「新しい設計判断を行った場合、その判断と理由を書き残す。口頭の会話だけで終わらせない」という
CLAUDE.md の作業方針に従う）。特に以下は必ず記録する:

- `runningProcess` を Java/C 両 Runner で共有し続ける理由（§5 段階2）
- `compileGeneration` をペインごとに持たせる理由（§5 段階3）
- `AnalysisServices` の生成を EDT 前に置く理由（§5 段階5）
- `EditorHost` 導入時に旧 setter を残したか削除したか、その判断（§6.3）
