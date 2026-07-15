# 軽量性リファクタリング計画書（Phase 1〜3）

- 作成日: 2026-07-15
- 対象コミット: `780725e`（Merge pull request #145）
- 作成ブランチ: `claude/editor-performance-analysis-3no2jf`
- 対になる実行手順書: **`docs/PERF_REFACTORING_INSTRUCTIONS.md`**（実装・テスト・マージの全手順はそちらが正。本書は「なぜ・何を・どこまで」を定義する）
- 位置づけ: `docs/REFACTORING_PLAN.md`（2026-07-04 の構造リファクタリング調査）とは独立した、**実行時性能（軽量性）に特化した**計画。書式・検証ログの流儀は同計画書に倣う。

---

# 1. 背景 — 「軽量」の主張と実装のギャップ

CLAUDE.md は「軽量テキストエディタ」「想定ファイル規模: 数百行〜数十万行」を掲げるが、実コード調査（2026-07-15、コミット `780725e`）で以下の4つの深刻なギャップを確認した。行番号はすべて同コミット時点。

## 問題① PieceTable にピース結合がなく、編集セッション全体で O(K²) に劣化する

- `src/dev/javatexteditor/buffer/PieceTable.java` の `insert()`（20〜45行）は、挿入のたびに必ず新しい `Piece` をリストに追加し、**隣接ピースを結合（マージ）する処理が一切ない**。
- INSERTモードの1キー入力は `ModalEditor` から `buffer.insert(offset, 1文字)` として届くため、**1キー = 1ピース増**。ピース数 K は編集操作の総数に比例して単調増加し、上限もない。
- `insert()`/`delete()`/`getText()`/`getTextInRange()` はいずれもピースリストの線形走査なので、K回編集後の1操作は O(K)。セッション累計では O(K²)。**「使い続けるほど遅くなる」**という、行数以上に深刻な劣化特性。
- 付随する欠陥（同ファイル内）:
  - `length()`（78〜80行）: 呼ぶたびに全ピースを `stream().sum()`（O(K)）。キャッシュなし。
  - `getText()`（85行）/ `getTextInRange()`（103行）: ループ内で `addBuffer.toString()` を呼ぶため、**ADDピース1個につき追加バッファ全体のコピー**が発生する（追加バッファが数百KBに育った後は、getText 1回で「ADDピース数 × 追加バッファ長」分の無駄なアロケーション）。
  - `offsetOfLine()`（116〜127行）: `getText()` で全文を再構築してから線形走査。現状は本番から未使用（デッドコード）だが、コメント自身が「頻繁に呼ぶ場合はキャッシュを検討」と認めている。
- なお `.claude/skills/editor-testing-strategy/SKILL.md` の「注意: O(n²) になる操作」節は、この欠陥を**テスト側が回避する**ためのガイドとして明文化されており、欠陥自体は既知だった（「実際に遅さを感じてから最適化する」という editor-buffer-architecture スキルの方針による意図的な先送り。今回それを解消する）。

## 問題② syncCanvas() が1キー入力あたり文書全体を4回 O(n) 再構築する

- `src/dev/javatexteditor/editor/ModalEditor.java` の `syncCanvas()`（4668行〜）はほぼ全キー処理の末尾（30箇所以上）から呼ばれ、内部で:
  1. 4670行 `canvas.setText(buffer.getText())` — 全文再構築1回目 ＋ `EditorCanvas.setText()`（`ui/EditorCanvas.java` 380〜384行）内の `text.split("\n", -1)` で全文分割1回目
  2. 4691行 `String[] lines = buffer.getText().split("\n", -1);` — **独立に**全文再構築2回目 ＋ 全文分割2回目
- つまり **カーソルを1回動かすだけでも、文書サイズ n に比例する走査・アロケーションが約4回**発生する。4698行のコメント「buffer.getText() の再構築を増やさない」は2回目の呼び出しと食い違っている（コメントと実装の乖離）。
- テキストが変化しないキー（hjkl等のカーソル移動）では再構築自体が丸ごと不要。`UndoablePieceTable` には既に `version`（insert/delete/undo/redo で必ず増分する版数。`UndoablePieceTable.java` 18行・62行）が存在し、CLAUDE.md も「テキストを変更する唯一の入口はこの4メソッド」と保証しているため、**版数＋バッファ参照一致によるキャッシュで安全に解消できる**。

## 問題③ Shift+K / gr / :grep の検索は O(ファイル数) の単一スレッド逐次走査のまま

- `src/dev/javatexteditor/search/ProjectSearcher.java` の `search()`（70〜111行）は `Files.walkFileTree` による単一スレッドの逐次走査＋ファイルごとの `readAllLines`。
- 過去のフリーズ修正（2MB上限・1500msタイムアウト。CLAUDE.md「Shift+K フリーズ修正」節）は**時間の上限を設けただけ**で、計算量は変わっていない。CLAUDE.md 自身が実測（15,000ファイル552ms / 150,000ファイル4,621ms、ファイル数に線形）と「タイムアウト後もバックグラウンドの検索スレッドは走り続ける（walkFileTree が割り込み不可のため）」という残課題を明記している。
- `ModalEditor.withTimeout()`（5319〜5331行）はタイムアウト時に `future.cancel(true)` を呼んでおらず、検索スレッドへの割り込み自体が発生しない。

## 問題④ 編集中に文書サイズ比例のメモリチャーンが継続的に発生する

- ファイルを開く動作は `Files.readString` による一括読み込みで、サイズ上限なし（2026-07 の「任意のファイル種別を開けるようにする対応」でユーザーが「上限なし」を明示選択した**確定済み設計判断**）。
- 問題は初期ロードではなく**編集中**: 問題①の `addBuffer.toString()` コピーと問題②の1キー4回の全文再構築により、キー入力のたびに文書サイズ相当の一時オブジェクトが複数個生成され続ける。

---

# 2. ゴールと非ゴール

## ゴール（このリファクタリングで達成すること）

| # | 達成基準 | 担当フェーズ |
|---|---|---|
| G1 | 連続タイピング（同一位置に続けて挿入）でピース数が増えない（結合される）。`length()` は O(1)。`getText()`/`getTextInRange()` から `addBuffer.toString()` のコピーを排除 | Phase 1 |
| G2 | テキストが変化しないキー入力（カーソル移動等）では `buffer.getText()`・`split()` を**一切**実行しない。テキストが変化したキーでも再構築は**1回だけ**（従来2回＋split2回） | Phase 2 |
| G3 | `ProjectSearcher.search()` を「パス収集（逐次）→ 内容grep（仮想スレッド並列）」の2段階に分離し、結果順序は従来と同一を維持。タイムアウト時は `future.cancel(true)` → walk/grepタスクの協調キャンセルで**バックグラウンドスレッドの残留を解消** | Phase 3 |
| G4 | 問題④のうち「編集中のチャーン」を G1+G2 で解消し、その処置を CLAUDE.md に記録 | Phase 1+2（記録は Phase 3 のPRに同梱） |
| G5 | 各フェーズで既存テストに新規FAILを出さない（ScrollTest 既知2件を除く）＋ 劣化を再発検知する回帰テスト・性能テストを追加 | 全フェーズ |

## 非ゴール（今回やらないこと。理由込み）

1. **ピースリストのツリー化（ピースツリー）・行オフセットの恒常キャッシュ**: 結合＋キャッシュで実用上の劣化要因は消える。ツリー化は editor-buffer-architecture スキルが「実際に遅さを感じてから」と定める将来課題のまま残す。
2. **描画のビューポート限定テキスト供給（EditorCanvas が getTextInRange で行を取得する方式への転換)**: `EditorCanvas`・検索ハイライト・数十のテスト前提（`setText` に全文が来る）を作り替える大規模再設計になる。Phase 2 で「変化時のみ1回再構築」に抑えれば、タイピング時 O(n) 1回/キーは数十万行でも実測許容範囲（getText+split は 10万行≒1.2MB で数十ms）。
3. **検索の完全非同期化（結果を invokeLater で反映）**: テストハーネスの同期契約（`NativeReferenceSearchTest`/`JumpBackTest` が processKey 直後に同期assert）を壊す。CLAUDE.md で2度検討・棄却済みの決定を踏襲する。
4. **ファイルサイズ上限の導入・rope等への内部表現変更**: 「上限なし」はユーザーの明示的な確定判断（CLAUDE.md 記載）。矛盾する変更はしない。数百MB級ファイルで OOM リスクが残ることは既知の制約として維持。
5. **`syncCanvas()` 内の `totalChars` 計算ループ（O(cursorRow)）の除去**: 配列参照の加算のみで、10万行でも1ms前後。キャッシュ済み配列を読むだけになるため許容（Phase 2 で残置を明記）。
6. **`CompletionIndex.query()` の線形走査と Javadoc（O(log n) 主張）の乖離修正**: 「特に深刻」ではない軽微問題のためスコープ外。
7. **ScrollTest 既知2件FAILの解消**: 仕様判断未決（CLAUDE.md 既知の未接続6・REFACTORING_PLAN.md U-7）。「ついでに」直すことを明示的に禁止する。
8. **検索インデックス（trigram等）の新設**: 並列化で当面の目標（体感フリーズの解消・スレッド残留の解消）は達成できる。インデックスは将来ユーザーが求めた場合の別計画。

---

# 3. ベースライン記録（2026-07-15 実測・コミット 780725e）

実行環境: 本リポジトリの開発コンテナ（Linux・ヘッドレス・OpenJDK 21）。

```
$ ./scripts/test.sh
=== Summary: 69 class(es) passed, 1 class(es) failed ===   （全70テストクラス・所要 数分）
```

- 失敗クラスは `dev.javatexteditor.editor.ScrollTest` のみ（18/20 PASS）。FAIL は既知の2件で固定:
  ```
  FAIL [halfPageUp: cursor moved up by 20] expected=20 actual=40
  FAIL [halfPage interleaved: row 40] expected=40 actual=60
  ```
  これは Ctrl+U の仕様変更にテストが追従していない**既存の**失敗（CLAUDE.md「既知の未接続・二重定義」6、REFACTORING_PLAN.md U-7）。**本リファクタリングでは触らない。増減しないことだけを確認する。**
- ケース単位では約870ケースが PASS（クラスごとに出力書式が異なるため、ゲート判定はクラス単位＋FAIL行パターンで行う。判定コマンドは指示書 §1.4）。
- `RobotKeyInputTest` はヘッドレス環境のため [SKIP] 表示で正常終了（rc=0）。これは正常。

**ゲートの合格条件（全フェーズ共通）**: `./scripts/test.sh` の Summary が「failed = 1」かつ失敗クラスが ScrollTest のみ、FAIL 行が上記2件と完全一致、追加した新規テストクラスが PASS していること。

---

# 4. フェーズ構成

依存関係: Phase 1 → 2 → 3 の順に実施する（コード上の依存はないが、①ゲート判定の安定性、②各フェーズのPRを小さく保つため、直列とする）。各フェーズは **最新の origin/main から分岐 → 実装 → 全テスト → PR → main マージ** で完結する。前フェーズのブランチに積み上げない。

## Phase 1: PieceTable の結合・キャッシュ（問題①④）

- `PieceTable.insert()` に「直前ピースが追加バッファ末尾を指す場合の伸長（結合）」を追加。連続タイピングは1ピースに収束する。
- `totalLength` フィールドで `length()` を O(1) 化（`insert`/`delete`/`restorePieces` のみが更新）。
- `getText()`/`getTextInRange()` の `addBuffer.toString()` を `append(CharSequence, int, int)` 直渡しに置換（コピー排除）。
- `offsetOfLine()` を全文再構築なし（ピース直接走査）に置換。
- **undo 粒度は不変**: 結合は「現在のピースリスト上の `pieces.set()`」であり、`snapshotBeforeEdit()` が取る `List.copyOf` スナップショット（イミュータブルな `Piece` record の参照コピー）には影響しない。1キー=1undo は保たれる（回帰テストで固定する）。
- テスト追加: `PieceTableTest` にピース数・結合可否・length整合・undo粒度の11チェック、`LargeFileTest` に連続タイピング2万キーの性能テスト2本。
- ドキュメント更新: `editor-buffer-architecture`/`editor-testing-strategy` 両 SKILL.md への追記、CLAUDE.md の状態表更新。

## Phase 2: syncCanvas() の全文再構築キャッシュ（問題②④）

- `ModalEditor` に `canvasTextOwner`（バッファ参照）＋`canvasTextVersion`（`getVersion()`）をキーとするキャッシュ（`canvasCachedText`/`canvasCachedLines`）を追加。`outputErrorLinesOwner`/`binaryModeOwner` と同じ「参照一致による自動失効」パターンのため、約30箇所ある `buffer = new UndoablePieceTable(...)` の差し替え経路には一切手を入れない。
- `EditorCanvas.setText(String, String[])` オーバーロードを追加し、分割済み配列を受け取る（既存 `setText(String)` は互換維持）。
- 効果: カーソル移動キーは全文再構築ゼロ、編集キーは再構築1回＋分割1回（従来比 各1/2、移動時は∞分の1）。
- テスト追加: `SyncCanvasCacheTest`（再構築回数を数える回帰テスト。テスト用アクセサ `getCanvasTextRebuildCount()` を追加）、`EditorRenderPerfTest`（10万行文書でのカーソル移動1000回・文字入力の性能）。

## Phase 3: ProjectSearcher の並列化と協調キャンセル（問題③）

- `search()` を「①逐次 walk でパス収集（スキップ規則・2MB上限は現状維持）→ ②仮想スレッド per ファイルで並列 grep → ③submit順に結果連結」に再構成。**結果順序は従来（walk順・ファイル内昇順）と同一**。公開API・シグネチャは不変。
- walk の `preVisitDirectory`/`visitFile` と grep タスク先頭に割り込みチェックを追加し、`ModalEditor.withTimeout()` のタイムアウト時に `future.cancel(true)` を呼ぶ変更（1箇所）と合わせて、**タイムアウト後のスレッド残留（CLAUDE.md 記載の残課題）を解消**する。
- 同期契約（呼び出し元からはブロッキング）は維持 → 既存テスト（`BangSearchTest`・`NativeReferenceSearchTest`・`JumpBackTest` 等）は無修正で通る。1500ms タイムアウト・2MB上限・スキップ規則・CASE_INSENSITIVE は変更しない。
- テスト追加: `ParallelGrepTest`（順序決定性・スキップ規則維持・fullScan）。
- 仕上げ: CLAUDE.md の残課題記述の更新、問題④の処置記録、状態表の完了化（本フェーズのPRに同梱）。

---

# 5. リスクと対策

| リスク | 対策 |
|---|---|
| 結合の条件ミスで削除後の再挿入が壊れたテキストになる | 結合条件を「`p.source()==ADD` かつ `p.start()+p.length() == append前のaddBuffer長`」に限定（追加バッファ末尾の所有者だけが伸長できる）。壊れるシナリオそのもの（削除→再挿入）を PieceTableTest に固定 |
| undo 粒度の変化 | スナップショット機構に触れない設計＋undo粒度の回帰テスト（指示書 Phase 1 Test 17） |
| キャッシュの失効漏れ（Phase 2） | 失効キーを「バッファ参照＋version」の2要素にする。version は insert/delete/undo/redo で必ず増分（既存保証）、参照はバッファ差し替えで必ず変わる。プロジェクト確立済みの owner パターンを踏襲 |
| 並列化による結果順序の変化で `*grep*` 疑似バッファのジャンプ位置がずれる | Future を submit 順に `get()` して連結（順序保証）。順序決定性テストを追加 |
| 性能テストのCIフレーク | 閾値は「実測中央値の10倍以上かつ500ms以上」で設定する手順を指示書に明記 |
| 既存テストが内部ピース配置に依存 | 事前調査済み: `getPieces()` を参照するテストは存在しない（2026-07-15 grep 確認） |
| main との衝突（他作業の並行マージ） | 各フェーズ開始時に必ず origin/main から分岐。アンカー検証手順（指示書 §1.6）で乖離を検出したら機械的続行を禁止 |

---

# 6. 完了の定義

3フェーズすべてが main にマージされ、以下が成立していること。

1. `./scripts/test.sh` がベースライン同等（ScrollTest 既知2件のみFAIL）＋新規テスト全PASS。
2. G1〜G4 の各達成基準を検証する自動テストが存在し PASS している。
3. CLAUDE.md・関連 SKILL.md に設計判断が記録され、CLAUDE.md の状態表が「✅ 完了」になっている。
4. 実行者が最終報告として、ベースラインとの性能比較（LargeFileTest / EditorRenderPerfTest の実測値）をユーザーに提示している。
