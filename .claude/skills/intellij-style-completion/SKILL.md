---
name: intellij-style-completion
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、Javaバッファの入力補完（IntelliJ IDEA方式のメンバー補完・候補の並べ替え・確定時の括弧/import挿入・ポップアップ表示）を設計・実装・修正する際に使用する。「obj. の後にメンバー候補を出したい」「補完候補にシグネチャや戻り値型を表示したい」「候補の並び順を変えたい」「CamelCase頭文字で候補を絞りたい」「メソッド補完で括弧まで入れたい」「補完確定時にimportを入れたい」「メンバー補完が遅い・出ない」といった相談、またJavaCompletionEngine/JavacCompletionAnalyzer/CompletionContext/CompletionRanker/CompletionScorer/ReflectionMemberProvider や ModalEditor の updateJavaCompletion/applyCompletion 周辺を触る作業に着手する前に、必ず最初に参照すること。Alt+/（Vim i_CTRL-N 相当の単語補完）の並び順は本スキルの対象外で、変更してはならない。"
---

# Javaバッファの入力補完（IntelliJ IDEA 方式）

## このスキルが解決すること

`.java` を編集しているときの入力補完を、IntelliJ IDEA と同じ考え方で動かす。
すなわち「**文脈で候補集合を切り替え、一致の質と近さで並べ、確定したら続きまで書く**」の3点。

対象は**候補の仕組みだけ**である。キーバインドは従来のまま（Ctrl+Space / 1文字目からの自動表示 /
↑↓・Ctrl+N/P で選択 / Tab・Enter で確定 / Esc で閉じる）で、IntelliJ のキー体系
（Ctrl+Shift+Space・Ctrl+Q・Ctrl+P・Ctrl+J 等）は**一切持ち込まない**。
これはユーザーの明示的な指示である（2026-07-29）。新しいキーを増やす提案をしないこと。

---

## 全体像

```
processInsertKey（1文字入力ごと）
  └ recheckCompletion()
      ├ Alt+/ セッション中           → recheckWordCompletion()   … 従来のまま（本スキル対象外）
      ├ currentFilePath が .java     → updateJavaCompletion()    … 本スキル
      └ それ以外（C・無名バッファ等） → queryMergedCompletion()   … 従来のまま

updateJavaCompletion()
  ├ CompletionContext.at(text, caret)      … 文脈判定（PLAIN / MEMBER / NEW）
  ├ requestAccurateMembers()               … MEMBER のときだけ javac へ依頼（非同期）
  ├ JavaCompletionEngine.complete()        … 候補収集 → CompletionRanker で並べ替え
  └ CompletionPopupState.openWith()        … 表示（EditorCanvas が3列で描画）
```

| クラス | 役割 |
|---|---|
| `analysis/CompletionContext` | カーソル直前を見て「何を補完するのか」を判定。ドットの左側の式も切り出す |
| `analysis/JavaCompletionEngine` | 候補の収集・キャッシュ・並べ替えの取りまとめ。Swing 非依存 |
| `analysis/ReflectionMemberProvider` | 軽量側のメンバー列挙（`JdkClassIndex` + リフレクション） |
| `analysis/JavacCompletionAnalyzer` | 正確側のメンバー列挙（javac の意味解析）。**EDT で呼ばない** |
| `analysis/CompletionScorer` | 一致判定とスコア。マッチ位置（強調表示用）も返す |
| `analysis/CompletionRanker` | 絞り込み・重複排除・並べ替え・件数制限 |
| `analysis/CompletionStatistics` | 選んだ候補の回数（セッション内のみ） |
| `analysis/JavaKeywords` | キーワード候補 |
| `analysis/JavaSourceCollector` | javac に渡すソース収集（`BindingDefinitionResolver` と共用） |
| `editor/CompletionPopupState` | 表示中の候補と選択位置 |
| `ui/CompletionView` | 描画に必要な情報（種別・名前・引数リスト・型・強調位置） |

---

## 設計判断ログ

### なぜメンバー補完だけ「ハイブリッド解決」なのか（2026-07-29）

`obj.` の後の候補は、レシーバの型が分からなければ1件も出せない。型解決には2つの道がある。

- **正規表現で宣言を探す**（`ReceiverTypeResolver`、Shift+K が以前から使っている）: 速いが、
  `var`・ジェネリクスの要素型・メソッドチェーンの戻り値型は原理的に解けない
- **javac に解かせる**: 正確だがプロジェクト全体の属性付けを伴い、EDT では待てない

どちらか一方では成立しないため、**軽量側で即座に出し、javac の結果が届いたら差し替える**方式にした
（ユーザーが「ハイブリッド」を明示的に選択）。差し替えは `ModalEditor.applyAccurateMembers()` が行い、
`bindingLookupGeneration` と同じ世代カウンタで stale な結果を捨てる。

**キャッシュのキーはレシーバ式とその手前のテキストのハッシュ**（`JavaCompletionEngine.MemberKey`）。
プレフィックスを打ち進めても（`list.` → `list.ad`）キーが変わらないため、javac は1回しか走らない。
逆にレシーバの宣言を書き換えればハッシュが変わり、自動的に解決し直される。

### なぜ修飾なしの位置（PLAIN）では javac を使わないのか

識別子を打ち始めるたびに位置が変わり、メンバー補完のような「同じキーで打ち進む」再利用が効かない。
つまり javac が毎回走ることになる。単語索引 + JDK クラス索引 + キーワードで実用上の候補は揃うため、
費用対効果が見合わないと判断してスコープ外にした。将来スコープ内の候補（IntelliJ の
スコープ解決）に踏み込む場合は、まずこの再利用の問題を解く設計が要る。

### javac に食わせるテキストは「ダミーの**メソッド呼び出し**」にする（重要）

`obj.` のまま javac に渡すと、その文はまるごと `ERRONEOUS` ノードに畳まれ、
`MemberSelectTree` が AST に現れない＝レシーバの型に辿り着けない。
`obj.dummy` のように識別子を足しても、フィールド参照は単体では文にならないため結果は同じ。
**`obj.dummy()` と括弧まで付けて初めて**正しい式文になり、
「dummy というメソッドは無い」という解決エラーは出るものの、レシーバ側の部分木には型が付く。

この挙動は実機で確認済み（`JavacCompletionAnalyzer.buildProbeText`）。
括弧を外す・識別子だけにする、といった「簡潔にする」変更をしてはならない。無言で候補が0件になる。

同じ理由で、`DiagnosticCollector` を `getTask` に渡すことも必須である
（渡さないと javac は AST の終了位置を保持せず、ノード探索が機能しない。
`BindingDefinitionResolver` の同じ注意書きも参照）。

### `Trees.getElement` を引くのはクラス宣言ノードに限る

カーソルを含む囲みクラス（private メンバーを見せてよいかの判定に使う）を求める際、
任意のノードで `getElement` を引くと、解決に失敗した式ノードから**レシーバ側の型**が返ることがある。
その結果「他クラスの private メソッドを自クラス扱いして候補に出す」誤りが起きた（実際に発生）。
`ClassTree` のときだけ引くこと（`enclosingTypeOf`）。

### 配列は要素型ではなく `length` + Object のメソッド

`ReceiverTypeResolver.resolveType()` は `String[]` を `String` に正規化してしまうため、
そのまま使うと `args.` に String のメソッドが並ぶ（実際に発生）。
軽量側では `resolveDeclaredType()`（正規化前の型）を見て `[]` で終わるなら配列として扱う。

### Alt+/（Vim の i_CTRL-N 相当）には IntelliJ 式の並べ替えを持ち込まない

Alt+/ の並び順が「カーソルからの近接順」であることは、過去にユーザーが明示的に要求した仕様である
（CLAUDE.md「補完候補の並び順を Vim の i_CTRL-N に合わせる」）。
そのため `completionIsWordMode == true` の経路は一切変更していない。
`ModalEditor.toRanked()` は強調表示用のマッチ位置を添えるだけで、**順序を変えない**。

### 確定時の import は「JDK かつ候補1件」のみ即挿入

プロジェクト内のクラスを特定するにはプロジェクト全体の grep（`ProjectClassSuggester`）が要り、
確定キーの押し心地を損なう。候補が複数ある場合も選択 UI が必要になる。
どちらも INSERT モードを抜けた時点で走る既存の auto-import（⑯）が既に扱えるため、
その場で入れるのは「`ImportSuggester.suggest(simpleName)` が1件だけ返す JDK クラス」に限定した。
import 挿入後は `moveCursorToOffset` でカーソルを、`shiftDiagnosticsAfterImportEdit` で
波下線の行番号を、それぞれ挿入分だけ補正する（補正を忘れると表示がずれる。⑯ の既知の不具合と同じ）。

### `CompletionScorer` に Tier 5（単語境界からの部分一致）を追加した

IntelliJ の "middle matching"（`Builder` → `StringBuilder`）に相当する。
単語の途中から始まる一致（`tring` → `String`）は**採用しない**。無関係な候補が大量に混じり、
一覧の先頭が信用できなくなるため。ファジー一致のスコアは 99 で頭打ちにして、
上位 Tier との序列が入れ替わらないようにしてある。

### `WordIndex` は保存のたびに差分更新する（2026-08-02）

PLAIN 位置（`obj.` のような修飾なし）で新規クラス名・追加メソッド名が補完候補に出ない不具合の
修正。原因は `WordIndex`（PLAIN 位置の主な候補源）が **エディタ起動時に1回だけ** バックグラウンド
スキャンで構築され、以後は再構築されない設計だったこと（`CompletionIndex` は JDK クラス名専用に
既に一本化済みで、プロジェクト内シンボルはそもそも保持していない。この点は元から正しい設計）。

一方、`obj.` のメンバー補完（MEMBER 位置）は `JavacCompletionAnalyzer` が呼ぶたびに
`JavaSourceCollector.collect()` でプロジェクト配下の `.java` をディスクから読み直しており、
**元から動的**だった。今回手を入れたのは PLAIN 位置用の `WordIndex` のみ。

**採用した設計**: プロジェクト全体の再スキャンではなく、保存されたファイル1つだけを再解析する
差分更新（`WordIndex.updateFile(Path)`）。`words`（小文字→原表記の集合）を `TreeMap` + volatile
差し替えから `ConcurrentSkipListMap` に変え、`wordFiles`（単語→参照ファイル集合）という参照カウント
的な逆引きテーブルを追加した。ある単語をどのファイルが参照しているかを追跡することで、
「複数ファイルに同じ単語がある場合、片方を更新しても消えない」を差分更新のまま実現している
（プロジェクト全体を舐めて再計算すれば単純だが、ファイル数が多いプロジェクトで保存のたびに
重くなるため採らなかった）。

`updateFile()` は「読めない・対象外拡張子ならその ファイルの単語集合を空とみなす」という1つの
規則で、新規作成・変更・削除のすべてを扱う（削除されたファイルは `Files.isRegularFile` が false
になり自動的に空集合＝そのファイル由来の単語が消える）。

**フックの配線**: `ModalEditor` には既に `onSave`（`:w` 成功時）という Runnable フックがあり、
`LiveDiagnostics.install()` がコンパイル診断・auto-import のトリガとして使っていた。同じフックに
`WordIndex.updateFile()` を相乗りさせた（新しいフックを増やさない）。`WordIndex` は
`AnalysisServices.startProjectIndexing()` が作業ディレクトリ確定後に構築するため `LiveDiagnostics`
の生成時点（クラスロード時）ではまだ `null` になりうる。`jdkClassIndex`/`workingDirectory` と同じ
理由で `Supplier<WordIndex>` として遅延解決する。

**経路B（`WatchService` によるファイルシステム監視）は実装しなかった**: エディタ自身の保存経路
（経路A）で要件（新規作成・保存したクラスが他ファイルの補完候補に出る）は満たせる。エディタ外部
からの変更に追従する必要が出た場合のみ検討する（常駐スレッド・監視対象の管理・終了時のクローズ
処理が新たに必要になり、費用対効果が見合わないと判断）。

### `completion2` パッケージとの関係

`dev.javatexteditor.completion2` は本番経路から未接続の独立コンポーネントであり
（CLAUDE.md「既知の未接続・二重定義」4項）、本スキルの実装とは**無関係**である。
どちらかを直すときにもう一方を追随させる必要はない。新しい補完の実装は `analysis` パッケージ側
（既存の `CompletionIndex`/`WordIndex`/`CompletionScorer` と同じ場所）に置いてある。

### メンバー補完のメモリ肥大化を修正した（2026-08-09）

- **症状**: Javaバッファを編集し続けるとヒープが際限なく増え続け、アイドルにしても戻らない。
  `⑨ javac-compile-integration` が2026-08-07に一度修正した「編集のたびにプロジェクト全体を
  再コンパイルしていた」問題と同じ症状が、本Skill（㉜）実装後に再発していた。
- **原因**: `JavacCompletionAnalyzer.resolveMembers`（`obj.` の後のメンバー補完、
  `ModalEditor.requestAccurateMembers` から `member-completion-lookup` 仮想スレッドで
  呼ばれる）が `JavaSourceCollector.collect` を使っており、呼ばれるたびに作業ディレクトリ配下の
  `.java` を**全件**中身ごと `String` として読み込み javac に丸ごと渡していた。
  `BindingDefinitionResolver`（Shift+K）も同じ方式を使うが、あちらは単発キー操作でしか
  発火しない。メンバー補完は「打鍵のたびに新しいレシーバ文脈（別の `obj.` サイト）へ
  移るたび」自動発火し、かつ `memberLookupExecutor` は依頼のたびに新しい仮想スレッドを
  起動して古い解析をキャンセルしない（性能上の歯止め表に記載の既知の割り切り）ため、
  数百MB級の解析が並行して積み上がった。
- **修正**: `⑨ javac-compile-integration` が確立した `JavaSourceRoots.sourcePathFor` による
  `-sourcepath` 方式へ切り替えた。javac に明示的なコンパイル対象として渡すのは編集中バッファ
  1件だけにし、他のプロジェクトソースは `-sourcepath` 経由で「型解決に必要になった分だけ」
  javac に遅延読み込みさせる。このプロジェクト自身（約28,000行）を対象にした実測
  （`ThreadMXBean#getThreadAllocatedBytes`、1回の `resolveMembers` 呼び出し）で
  **415MB→92MB（約4.5倍減）・2678ms→1107ms**。
- **`BindingDefinitionResolver` は意図的に変更していない**: そのテスト
  （`BindingDefinitionResolverTest#test_crossFileResolution` 等）は
  「無名バッファ（`currentFilePath == null`）から、`package` 宣言の無い別ファイルへの
  ジャンプ」を検証しており、`JavaSourceRoots` は `package` 宣言を持たないファイルを
  ソースルート走査で拾わない設計（別プロジェクトの混入防止、上記「なぜ〜」節参照）。
  `-sourcepath` 化するとこの既存テストが解決不能（`NotFound`）に後退する。
  Shift+K は単発操作で発火頻度が低く、メモリ肥大化の主因ではないため、
  スコープ外として現状の全件読み込み方式（`MAX_SOURCE_FILES=2000` の歯止め付き）を維持した。
  将来 Shift+K 側も直す場合は、まず `currentFilePath == null` かつ `package` 宣言なしの
  ケースをどう扱うか（例: プロジェクトルート自体を無条件でルートに加える等）を決めてから
  着手すること。

### メンバー補完の同時実行を直列化し、WordIndex の保持量を上限化した（2026-08-10）

- **症状**: `run.sh` の GC ログでヒープが79秒で49MB→3,708MB まで単調増加し、GC を挟んでも下がらない。
- **真の原因は本Skillの外にあった**: 強制 Full GC 後の生存量は4MBまで戻り、リークではないことを確認。
  解析3経路（Shift+K・メンバー補完・編集時診断）を合成負荷で連続実行しても再現せず、
  原因は `WordIndex` が**ユーザーのホームディレクトリ全体**を無制限に索引化していたことだった
  （`WorkingDirectoryManager` の既定値。詳細は `docs/decision-log.md`
  「WordIndex がホームディレクトリ全体を無制限に索引化していた問題の修正（2026-08-10）」）。
- **本Skillに関わる修正**: 上記調査の過程で、`ModalEditor.requestAccurateMembers` が
  依頼のたびに新しい仮想スレッドを起動し古い解析を止めない点（下記「性能上の歯止め」に
  割り切りとして記載されていた挙動）を、同時実行数が有界になるよう改めた。
  `PaneManager` が持つ単一スレッドの `MEMBER_LOOKUP_EXECUTOR` で直列化し、
  実行待ちの間に別のレシーバへ移っていれば **javac を動かす前に**破棄する
  （`LiveDiagnostics` が2026-08-07にコンパイル診断で確立した方式と同じ）。
  実行前の世代チェックを入れたため `memberLookupGeneration` は `volatile` にした
  （加算は常に EDT のみなので `++` の非原子性は問題にならない）。
- **`WordIndex` の変更が補完候補に与えた影響**: `lib/`（⑫が取得する OpenJDK の C/C++ ソース）を
  索引対象から外したため、Alt+/ の候補から OpenJDK 由来のノイズが消えてプロジェクトの
  識別子が上位に来るようになった（実測: `buff` の候補が `[buff, buff_length, Buffer]` から
  `[Buffer, buffer, BUFFER_REGISTRY]` へ）。`WordIndex` には10,000ファイル / 100,000語の
  上限も入ったが、本プロジェクトは357ファイル・10,238語で到達しない。

### WordIndex の構築を `:pr` 実行まで完全に見送るよう変更（2026-08-10 続報。現行仕様）

- **経緯**: 上記の上限化に続き、ユーザーから「`:pr` でプロジェクトルートが指定されるまでは、
  Javaバッファなら現在バッファの単語＋標準API、その他の言語なら現在バッファの単語のみを
  補完候補にしたい」という提案があり、根本原因（ホーム全体の無条件索引化）をほぼゼロにする
  変更として採用した。**これが現行仕様であり、以後 `WordIndex` の挙動を論じるときは
  この節を基準にすること**（上2節は経緯として残す）。
- **`AnalysisServices.startProjectIndexing`（起動時）は `WordIndex` を構築しなくなった**。
  `CompletionIndex`（JDK クラス名。ディスク走査を伴わない固定サイズ）だけを起動時に構築する。
  `WordIndex` は新設の `AnalysisServices.startWordIndexing(Path)` に切り出し、`:pr` の
  `ProjectRootManager` リスナー（`EditorApplication`）からのみ呼ばれる。
- **Javaバッファは既存コードの無変更で意図どおりになった**: `JavaCompletionEngine.plainCandidates`
  はもともと「現在バッファの単語（静的解析、`WordIndex` インスタンス不要）→ JDK クラス名→
  キーワード→（`wordIndex` があれば）ディスク索引」の順で、`wordIndex == null` なら自然に
  「バッファの単語＋標準API＋キーワード」だけになる。メンバー補完（`obj.` 後）ももとから
  `WordIndex` 非依存。変更が要ったのは「`wordIndex == null`（:pr 未実行）」と
  「`wordIndex != null && !isReady()`（構築中で待つべき）」を区別できていなかった
  `ModalEditor` 側だけ（`triggerCompletion`/`recheckCompletion`/`triggerWordCompletion`/
  `recheckWordCompletion`/`queryWordCompletion`/`queryMergedCompletion`）。前者はメッセージなしで
  即座にバッファ内単語フォールバックへ進み、後者だけ「Building word index...」で待たせる。
- **`:pr` 実行時の反映**: 新設インスタンスを `SERVICES.wordIndex()` 経由で取得し、既に開いている
  全ペインへ `editor.setWordIndex(...)` で反映する（新規ペインは `PaneManager` 経由の
  `wireInto()` が `SERVICES.wordIndex()` の非 null 値を自動的に拾う）。
- **`:cd` は今回もトリガーにしていない**: `:cd`（作業ディレクトリ変更、FILER モードでの
  ディレクトリ閲覧等で頻繁に発生しうる）に索引の自動再構築を結び付けると、閲覧のたびに
  ディスク走査が走りかねない。`:pr`（利用者が「ここがプロジェクトルートだ」と明示する操作）
  だけをトリガーにする、というユーザーの提案どおりの境界を採用した。
- **実測（上限修正後との比較・実機と同じ起動条件で Full GC 後の真の生存量）**:
  70MB→**21MB**、RSS 327MB→**248MB**。ヒストグラムから `WordIndex` 由来のオブジェクトが
  完全に消えたことを確認済み。詳細・検証方法は `docs/decision-log.md`
  「単語補完（WordIndex）の構築を `:pr` 実行まで完全に見送るよう変更（2026-08-10 続報）」参照。

---

## 性能上の歯止め（変更するときは理由を書き残すこと）

| 箇所 | 上限 | 理由 |
|---|---|---|
| `JavacCompletionAnalyzer.resolveMembers` | `-sourcepath`（`JavaSourceRoots`）経由 | 2026-08-09にプロジェクト全体を毎回読み込む方式から変更。詳細は下記「メンバー補完のメモリ肥大化を修正した」参照。`JavaSourceCollector.MAX_SOURCE_FILES`（2000ファイル）は現在 `BindingDefinitionResolver`（Shift+K）専用 |
| `PaneManager.MEMBER_LOOKUP_EXECUTOR` | 同時1本（単一スレッド） | 2026-08-10追加。依頼ごとの仮想スレッド起動をやめ、追い越された要求は javac を動かす前に破棄する |
| `WordIndex.MAX_INDEXED_FILES` / `MAX_INDEXED_WORDS` | 10,000 ファイル / 100,000 語 | 2026-08-10追加。`:pr` 実行後の索引にも天井を残す（1語あたり約650バイト＝約65MBで頭打ち）。ただし2026-08-10続報以降、`:pr` 未実行の間は索引自体を構築しないため、この上限は「`:pr` で固定したプロジェクトルートが巨大だった場合」だけの保険 |
| `JavaCompletionEngine.MAX_BUFFER_WORDS` | 200 語 | 数十万行のバッファで候補が膨らみすぎないため |
| `ModalEditor.COMPLETION_MAX_RESULTS` | 10 件 | ポップアップに載せる件数 |
| `EditorCanvas.COMPLETION_VISIBLE_ROWS` | 10 行 | 一度に見せる行数（超過分はスクロール） |

javac の解析は**キャンセルできない**（走り始めたものは最後まで走る）。ただし2026-08-10以降、
メンバー補完の解析は単一スレッドで直列化され、待ち行列に入ったまま追い越された要求は
javac を動かす前に破棄されるため、同時に生存する解析は常に1本だけになった。
Shift+K のバインディング解決も同じ方式に揃えてある。
気になる場合の改善案は「同じキーの再解析を抑える」ではなく（既にキャッシュ済み）、
「依頼を一定時間まとめる（デバウンス）」の方向で検討すること。

---

## テスト

| テスト | 対象 |
|---|---|
| `analysis/CompletionContextTest` | 文脈判定・レシーバ式の切り出し（チェーン・配列・文字列リテラル・`new`） |
| `analysis/CompletionRankerTest` | 並べ替えの優先順位・重複排除・強調位置 |
| `analysis/CompletionScorerTest` | 各 Tier のスコア序列（既存。Tier 5 追加後も維持） |
| `analysis/JavacCompletionAnalyzerTest` | javac によるメンバー解決（ジェネリクス・`var`・チェーン・static/instance・private） |
| `editor/IntelliJCompletionTest` | ModalEditor 経由の一連の動作（候補・シグネチャ表示・括弧挿入・import 挿入・非 Java バッファの非干渉） |
| `editor/WordCompletionTest`・`editor/CWordCompletionTest` | Alt+/ と C バッファの従来動作が壊れていないこと（回帰テスト） |
| `analysis/WordIndexTest`（`testUpdateFile*`） | `WordIndex.updateFile()` の差分更新（新規クラス追加・メソッド追加・単語削除・ファイル削除・複数ファイル間の共有単語・他ファイルへの無影響） |

`IntelliJCompletionTest` は `JdkClassIndex.buildSync()` を伴うため実行に時間がかかる。
javac の型解決を同期で検証したい場合は `enableMemberCompletionLookup(Runnable::run, Runnable::run)`
を渡す（`enableBindingDefinitionLookup` と同じ方式）。
