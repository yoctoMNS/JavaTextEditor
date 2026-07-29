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

### `completion2` パッケージとの関係

`dev.javatexteditor.completion2` は本番経路から未接続の独立コンポーネントであり
（CLAUDE.md「既知の未接続・二重定義」4項）、本スキルの実装とは**無関係**である。
どちらかを直すときにもう一方を追随させる必要はない。新しい補完の実装は `analysis` パッケージ側
（既存の `CompletionIndex`/`WordIndex`/`CompletionScorer` と同じ場所）に置いてある。

---

## 性能上の歯止め（変更するときは理由を書き残すこと）

| 箇所 | 上限 | 理由 |
|---|---|---|
| `JavaSourceCollector.MAX_SOURCE_FILES` | 2000 ファイル | 作業ディレクトリの既定値はホームになりうる。超えたら解析を諦め軽量側に委ねる |
| `JavaCompletionEngine.MAX_BUFFER_WORDS` | 200 語 | 数十万行のバッファで候補が膨らみすぎないため |
| `ModalEditor.COMPLETION_MAX_RESULTS` | 10 件 | ポップアップに載せる件数 |
| `EditorCanvas.COMPLETION_VISIBLE_ROWS` | 10 行 | 一度に見せる行数（超過分はスクロール） |

javac の解析は**キャンセルできない**。レシーバを次々に変えながら打つと解析が数本同時に走ることがある
（古い結果は世代カウンタで捨てられるので表示は壊れないが、CPU は使う）。
これは Shift+K のバインディング解決と同じ性質の割り切りである。
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

`IntelliJCompletionTest` は `JdkClassIndex.buildSync()` を伴うため実行に時間がかかる。
javac の型解決を同期で検証したい場合は `enableMemberCompletionLookup(Runnable::run, Runnable::run)`
を渡す（`enableBindingDefinitionLookup` と同じ方式）。
