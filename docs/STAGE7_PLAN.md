# 段階7（`GlobalKeyDispatcher` + `EditorApplication`）実行計画書

- 作成日: 2026-07-28
- 対象コミット: `96e5c89`（段階6-0〜6-5完了・PR #205マージ後）
- 対象ブランチ: `claude/stage7-global-key-dispatcher-9p0n8e`
- 親計画書: `docs/MAIN_DECOMPOSITION_PLAN.md` §5「段階7 — `GlobalKeyDispatcher` ＋ `EditorApplication`」
- 本書の位置づけ: 親計画書の段階7節はサブ段階分割済みではなかったため、段階6
  （`docs/STAGE6_OPTION_C_PLAN.md`）と同じ「1サブ段階＝1コミット＋個別検証」の原則に
  沿って、実行前にここで分割する。設計判断を追加するものではない。

## 現状の再確認（段階6完了後）

`Main.java` は段階6完了時点で343行。行番号は段階6でずれているため、以下は
実際に `Main.java` を読んで再確認した現状（メソッド名基準）。

| 責務 | 現状 |
|---|---|
| 定数・サービス生成（`WD_MANAGER`/`SERVICES`/`LIVE_DIAGNOSTICS`/`RUNNING_PROCESS`/`JAVA_BUILD_RUNNER`/`C_BUILD_RUNNER`/`BUFFER_REGISTRY`/`WINDOW_WIDTH`/`WINDOW_HEIGHT`） | `Main` の static フィールドのまま（段階5で `AnalysisServices`/`LiveDiagnostics`/`JavaBuildRunner`/`CBuildRunner`/`RunningProcessHolder`/`BufferRegistry` は独立クラス化済みだが、それらの**生成**は `Main` に残っている） |
| 画面計測（`computeDisplayScale`/`computeInitialCellSize`/`computeInitialWindowSize`/`detectMouseScreen`/`centerOnScreen`/`buildTitle`） | `Main` の private static メソッドのまま（`ui.DisplayMetrics` は既に純粋計算部分を持つが、実ディスプレイを調べるラッパーは `Main` 側） |
| グローバルキーディスパッチャ（`KeyEventDispatcher` ラムダ・`boolean[] pressedHandled`） | `main()` の `invokeLater` 内に直書き（約105行） |
| `main()` 本体（引数解析・`WD_MANAGER` 初期化・索引起動・画面計測呼び出し・`invokeLater` 一式） | `Main.main()` にすべて同居 |

## 目標（親計画書の判定基準を適用）

> **Main に残ってよいのは「部品を組み立てる記述」だけ。`if` と `for` を含む処理は1行も置かない。**

現状の `main()` は引数解析に `if`/`try-catch` を含み、画面計測メソッド群にも `for`/`try-catch` が
残っている。これらをすべて `app/EditorApplication.java` へ移し、`Main.java` を次の形にする。

```java
package dev.javatexteditor;

import dev.javatexteditor.app.EditorApplication;

public final class Main {
    public static void main(String[] args) {
        EditorApplication.launch(args);
    }
}
```

親計画書 §5 段階7の最終形サケッチは `StartupArgs` という新クラスと、`main()` に残す
`SetupBootstrap.runIfNeeded()`/`AnalysisServices.createAndStartIndexing()` の直接呼び出しを
示しているが、これは段階0〜6を通じて実際には採られなかった設計（段階5で `AnalysisServices` は
`Main` の `static final` フィールドとして保持する方式を採用済み。§9 進捗記録「5」参照）との
整合を取るため、**本書では次のとおり読み替える**（新しい設計判断ではなく、既存の段階5の
判断をそのまま段階7にも延長するだけ）。

- `StartupArgs` という新クラスは作らない（親計画書 §7.3「便利な仕組みを導入しない」の
  精神に合わせ、既存の局所変数によるリレーで足りるものに新しい抽象を導入しない）。
- `SetupBootstrap.runIfNeeded(Main.class)` の呼び出しは `EditorApplication.launch()` の
  先頭に移す（`Main` に残すと `main()` が「組み立てだけ」の1行にならないため）。
  anchor は引き続き `Main.class` を渡す（`SetupBootstrap` の Javadoc が明記する契約どおり、
  実際のエントリポイントクラスを渡し続ける。`EditorApplication.class` に変えない）。
- サービス生成（`SERVICES`/`LIVE_DIAGNOSTICS`/`RUNNING_PROCESS`/`JAVA_BUILD_RUNNER`/
  `C_BUILD_RUNNER`/`BUFFER_REGISTRY`/`WD_MANAGER`）は `EditorApplication` の static フィールド
  として移す（段階5で確立した「`SERVICES` の構築開始を `invokeLater` より前・かつクラスロード時
  に行う」という制約は、置き場所を `Main` → `EditorApplication` に変えても
  **static final フィールドの初期化子である限り** 同じタイミングで成立する。詳細は §3 参照）。
- 画面計測の6メソッドは、親計画書の新クラス一覧に独立クラスとしては挙がっていないため
  （挙がっているのは `GlobalKeyDispatcher`/`EditorApplication` の2つのみ）、
  `EditorApplication` の private static メソッドとしてそのまま移す。

## サブ段階分割

段階6と異なり「箱」（`root[0]`/`active[0]` のような書き換え不能なローカル変数の回避策）は
既に解消済みで、`panes`（`PaneManager` インスタンス）を単純に受け渡すだけでよい。
リスクは親計画書で「中」と評価されている（「大」だった段階6より低い）。
2サブ段階に分ける。

| サブ段階 | 内容 | リスク |
|---|---|---|
| 7-0 | 検証環境の構築とベースライン確定＋本計画書の作成。コード変更なし | なし |
| 7-1 | `GlobalKeyDispatcher` を新設し、`KeyEventDispatcher` ラムダの中身（`boolean[] pressedHandled` を含む）を機械的に移す。`Main.main()` 側は `new GlobalKeyDispatcher(frame, panes, JAVA_BUILD_RUNNER, C_BUILD_RUNNER)` を1回生成して登録するだけになる。他の部分（サービス生成・画面計測・`invokeLater` の残り）は `Main` に残したまま | 中（IME/pressedHandled の意味論を壊さないことが焦点） |
| 7-2 | `EditorApplication` を新設し、`Main.main()` の残り全部（引数解析・`WD_MANAGER`・サービス生成フィールド・画面計測メソッド・`invokeLater` 本体）を移す。`Main.java` を最終形（1メソッドのみ）にする | 中（static フィールドの初期化順序・`invokeLater` 前後のタイミングを保つことが焦点） |

7-1で先に `GlobalKeyDispatcher` を独立させておくことで、7-2は「今ある部品を運ぶだけ」になり、
段階6で得た教訓（「実装が残っていない状態で機械的に切り出せる」）をそのまま活かせる。

## 各サブ段階で必ず守ること

### 7-1: `pressedHandled` の意味論

`boolean[] pressedHandled = { false }` は次の3箇所で読み書きされる。

1. `KEY_PRESSED` 処理の先頭で `false` にリセット
2. 各分岐（Ctrl+Shift+矢印・Ctrl+Alt+矢印・F2・F10/F11/F12・通常のキー処理）で `true` にセット
   してから `return true`
3. `KEY_TYPED` 処理の先頭で読み取り、`true` なら「`KEY_PRESSED` で処理済みなので無視」して
   `false` に戻す

これを `GlobalKeyDispatcher` の `private boolean pressedHandled` フィールドに変換する。
**同一の `GlobalKeyDispatcher` インスタンスが `KeyEventDispatcher` として1つだけ登録される**
（`main()` で1回だけ `addKeyEventDispatcher` される）ため、フィールド化してもラムダの
配列トリックと意味的に同じ（=1つの可変状態を複数呼び出しにまたがって保持する）ことを
移設前に確認する。IME（日本語入力）の確認項目（§4）はこの意味論が壊れていないかの実地検証。

### 7-1: F2/F10/F11/F12 の呼び出し先

現状 `Main.main()` は `panes`（`PaneManager`）・`JAVA_BUILD_RUNNER`・`C_BUILD_RUNNER`・
`frame` をクロージャで捕捉している。`GlobalKeyDispatcher` はこれらをコンストラクタ引数で
受け取るだけでよく（段階6で `PaneManager` が既にインスタンス化されているため「箱」は不要）、
**本文（分岐の中身）は一切書き換えない**。

### 7-2: static フィールドの初期化順序

`Main.java` に現存する2つの厳格な順序制約を、そのまま `EditorApplication` へ持ち込む。

1. `SERVICES`（`AnalysisServices`）は `LIVE_DIAGNOSTICS` の宣言より**前**に置く
   （`LIVE_DIAGNOSTICS` の初期化子が `SERVICES.jdkClassIndex()` を呼ぶため、
   Java の static フィールドはソース順に初期化される制約に従う）。
2. `RUNNING_PROCESS`（`RunningProcessHolder`）は `JAVA_BUILD_RUNNER`/`C_BUILD_RUNNER` より前。

さらに段階5の制約（`SERVICES` の生成は `SwingUtilities.invokeLater` より前・かつ
クラスロード時）も維持する。`static final` フィールドとして `EditorApplication` に置く限り
自動的に満たされるが、**`launch()` メソッドの中に移してはならない**（メソッド内に書くと
呼び出しタイミングに依存してしまい、段階5で確立した保証が崩れる）。

### 7-2: `SetupBootstrap` の anchor

`SetupBootstrap.runIfNeeded(Main.class)` の引数は `Main.class` のまま変えない
（`EditorApplication.class` にしない）。`SetupBootstrap` の Javadoc が明記する契約
（「呼び出し側は `Main.class` を渡す」）を、呼び出し元が `Main` から `EditorApplication` に
変わっても維持する。

## 検証手順（全サブ段階共通）

`docs/MAIN_DECOMPOSITION_PLAN.md` §2 と同一。`verify.sh`・ベースラインは本セッションの
`/tmp` に作成済み（`/tmp/verify.sh`・`/tmp/baseline7.txt`）。

```bash
./scripts/build.sh && find test -name "*.java" | xargs javac -encoding UTF-8 -cp build -d build
/tmp/verify.sh /tmp/phase7-N.txt
diff /tmp/baseline7.txt /tmp/phase7-N.txt   # 空であること

pkill Xvfb 2>/dev/null; (Xvfb :99 -screen 0 1280x800x24 >/dev/null 2>&1 &); sleep 2
DISPLAY=:99 timeout 20 java -cp build dev.javatexteditor.Main > /tmp/launch.log 2>&1
echo "exit=$?"          # 124 であること
grep -iE "exception|error at|Caused by" /tmp/launch.log   # 出力が無いこと
rm -rf lib/_openjdk_clone_tmp
```

## 手動検証（§4、7-1・7-2の両方で実施）

`docs/STAGE6_OPTION_C_PLAN.md` §5の6項目に加え、本段階固有の**IME確認**を追加する
（親計画書が明記する「`boolean[] pressedHandled` も箱である」という指摘に対応。
自動テストが存在しない領域であり、段階6の `:q` 回帰と同種のリスクのため必須とする）。

| # | 操作 | 期待 |
|---|---|---|
| 1 | `s` `v` | 左右に分割される |
| 2 | `s` `s` | 上下に分割される |
| 3 | `s h`/`s l` | アクティブペインの枠線が移動する |
| 4 | `Ctrl+Alt+→` | アクティブペインが広がる |
| 5 | `:q` | ペインが1つ閉じる（最後の1枚では閉じない） |
| 6 | 同一ファイルを2ペインで開き片方で編集 | もう片方に即座に反映される |
| 7（新規） | INSERTモードで日本語入力（IME経由で「あ」を確定） | 1文字だけ挿入される（2文字にならない・`KEY_PRESSED`と`KEY_TYPED`の二重処理が起きない） |
| 8（新規） | 7に続けて通常のASCII文字を1文字入力 | 正常に1文字挿入される（IME確定直後も `pressedHandled` の状態が壊れていない） |

Xvfb環境には実際のIMEが存在しないため、7の検証は `java.awt.Robot` によるキー入力ではなく、
`EditorCanvas` の `inputMethodTextChanged`/IME確定ハンドラ経路を模した直接呼び出し
（`PaneManager.createLeaf()` で配線される `canvas.setImeCommitHandler(...)` が
最終的に `editor.processKey(0, ch, 0)` を1文字ずつ呼ぶ実装になっていることを確認したうえで、
同じ経路を通す）で代替する。目的は「`GlobalKeyDispatcher` 抽出後も `KEY_PRESSED`/`KEY_TYPED`
の二重処理防止ロジックが壊れていないこと」の確認であり、Robotで打てるASCIIキー入力
（8番）と合わせて、二重処理が起きないことを確認する。

## 完了判定

親計画書どおり: `diff` が空、起動スモークテスト `exit=124`、手動検証8項目、
かつ `wc -l src/dev/javatexteditor/Main.java` が30行以下。

## 進捗記録欄（実行者が埋める）

| サブ段階 | 実施日 | `diff` 空 | スモーク | 手動検証(1-6) | IME確認(7-8) | 行数 | 気づき |
|---|---|---|---|---|---|---|---|
| 7-0 | 2026-07-28 | ✅ | ✅ | (対象外) | (対象外) | 343 | 検証環境構築・ベースライン確定・本計画書作成のみ |
| 7-1 | 2026-07-28 | ✅ | ✅ 124 | ✅（`s v`/`s s`/`s h`/`s l`/`Ctrl+Alt+→`/`:q`/共有バッファすべて確認） | ✅ | 343 → **237**（`GlobalKeyDispatcher.java` 新設・160行） | ①`KeyEventDispatcher` は `java.awt.event` ではなく `java.awt` パッケージ（初回ビルドでimport誤りに気づいた）。②手動検証中に「Escape 1回では INSERT を抜けられない」という事象に遭遇したが、`git stash` でリファクタ前のバイナリに戻して同じ手順を再現し、**リファクタと無関係の既存挙動**であることを確認した（`processInsertKey()` の `completion.isActive()` 分岐: 補完ポップアップが開いている間はEscapeがポップアップを閉じるだけで、INSERTを抜けるには2回目のEscapeが必要。文字入力のたびに `recheckCompletion()` が自動的にポップアップを開く仕様のため、1文字打っただけでも普通に発生する）。③IME確認は実IMEの無いXvfb環境のため、`PaneManager.createLeaf()` が実際に配線する `canvas.setImeCommitHandler` のラムダ本体を再現したうえで `InputMethodEvent` を直接 `canvas.inputMethodTextChanged()` に渡す方式で実施し、「あ」の確定で1文字だけ挿入されること（2文字にならないこと）を確認した。この経路は `GlobalKeyDispatcher`/`pressedHandled` を一切経由しない独立した経路であることもコード上確認済み |
| 7-2 | 2026-07-28 | ✅ | ✅ 124 | ✅（7-1と同一の8項目を最終ビルドで再実施し完全一致を確認） | ✅（同上） | 237 → **15**（`EditorApplication.java` 新設・約255行。`Main.java` は `EditorApplication.launch(args)` を呼ぶだけの1メソッドのみ） | ①親計画書の `StartupArgs` 抽象は導入せず、段階5で確立済みの「static final フィールドとしてサービスを保持する」方式を `EditorApplication` にそのまま延長した（詳細は `EditorApplication` クラスJavadoc「親計画書のスケッチとの差分」参照）。②`javap -c -p EditorApplication.class` で `<clinit>` の先頭命令が `AnalysisServices.createAndStartJdkIndexing()` 呼び出しであることを確認し、段階5で確立した「JDK索引の構築開始はクラスロード時＝`invokeLater`より前」という制約が移設後も保たれていることを検証した。③`SetupBootstrap.runIfNeeded(Main.class)` の anchor は変更せず維持（`EditorApplication.class`にしていない）。④手動検証・IME確認とも7-1完了時点と完全に同一の結果（スクリーンショット比較・`text.equals("あ")`判定とも一致）で、回帰なし |
