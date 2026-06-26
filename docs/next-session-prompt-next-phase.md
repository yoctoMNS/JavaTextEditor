# Claude Code 次セッション用最適化プロンプト

**使用時機**: 次のセッション開始時、ユーザーが仕事を指示する前に、このプロンプトを Claude に提示してください。

**注記**: このプロンプトは以下を前提としています:
- 前セッションで ② modal-editing-engine v4（VISUALモード）が完成
- `claude/modal-editing-v4-visual-8dvxez` ブランチがプッシュ済み（main にはマージ未）
- テスト: 全 4 クラス合計 94 ケース PASS
- 次フェーズの候補が 3 つある（v5・③・④・⑤）

---

## セッション開始時プロンプト案

### パターンA：② v5 実装を選択した場合

```
【次セッション指示：② modal-editing-engine v5 実装】

前セッションでの完了状態:
- ① editor-buffer-architecture: ✅ 実機検証済み（15/15 テスト）
- ② modal-editing-engine: ✅ v4 完了・VISUALモード・ヤンク/ペースト実装済み（84/84 テスト）
- ⑤ gui-rendering-pipeline: ✅ v2 完了・縦スクロール対応（10/10 テスト）

現在のブランチ状態:
```
main ← af3fc09（最新コミット：② v4 ペースト修正）
  └── claude/modal-editing-v4-visual-8dvxez（完了・プッシュ済み）
```

【今セッションの目標】
実装: ② modal-editing-engine v5 — 行単位ヤンク・VISUAL LINE モード追加

機能追加:
1. NORMALモードに `yy` キー → 現在行をヤンク
2. NORMALモードに `dd` キー → 現在行を削除してヤンク
3. VISUAL LINE モード（大文字 `V` で進入）
   - 行単位での選択表示（行全体をハイライト）
   - VISUAL LINE中に `y` → 選択行をヤンク
   - VISUAL LINE中に `d` → 選択行を削除
4. 行ヤンク時のペースト動作
   - `p` → カーソル行の下に貼り付け
   - `P` → カーソル行の上に貼り付け
5. yankType の管理（文字 "char" / 行 "line"）

【完了条件（すべて満たしてからプッシュ）】
1. `./scripts/test.sh` で全テストクラス PASS
   - ModalEditorTest: 84 → 105+ ケース（v5 追加分）
   - EditorCanvasTest: 10 → 15+ ケース（VISUAL LINE ハイライト）
2. `./scripts/run.sh` で起動して以下を目視確認:
   - `yy` で現在行がヤンクされる・yankRegister に行テキストが入る
   - `dd` で行が削除される
   - `V` で VISUAL LINE モード進入・行全体がハイライト
   - 複数行選択後 `y`/`d` で行単位ヤンク/削除
   - `p`/`P` で行ペースト（貼り付け位置が正しいか）
3. `docs/session-log.md` に今セッション内容を追記
4. git コミット・プッシュ完了（主要ファイル: ModalEditor.java / EditorCanvas.java / ModalEditorTest.java / EditorCanvasTest.java）

【参照すべきドキュメント】
1. `CLAUDE.md` — 技術制約・ロードマップ
2. `docs/handover-next-phase.md` — 次フェーズ概要・実装タスク分解
3. `.claude/skills/editor-buffer-architecture/SKILL.md` — バッファ API（既実装）
4. `src/dev/vimacs/editor/ModalEditor.java` — 変更対象（v4 から拡張）
5. `src/dev/vimacs/ui/EditorCanvas.java` — 変更対象（VISUAL LINE ハイライト）
6. `test/dev/vimacs/editor/ModalEditorTest.java` — テスト追加対象

【実装の注意点】
- 行ヤンクレジスタには行末の改行文字も含める
- ペースト時に yankType を確認して動作を切り替え（char → 文字挿入 / line → 行挿入）
- VISUAL LINE モード選択ハイライト: 行全体を背景色で塗る（現在の VISUAL 選択と異なる視覚）
- 行削除後カーソル位置のクランプ: 空ファイル・最終行削除時の処理に注意
- undoStack との整合性: `buffer.delete()` 呼び出しで自動スナップショット取得

【制約（変更禁止）】
- Java 21 / Java SE 標準 API のみ
- ビルド: `./scripts/build.sh`・テスト: `main` メソッド形式のみ
- 外部ライブラリ・Maven/Gradle 禁止

【スコープ外（v6 以降）】
- レジスタ複数化（"a "レジスタ〜"z" レジスタなど）
- オペレータ組み合わせ（`d2j` = 2行削除など）
- ビジュアルブロックモード（Ctrl+V）

【次フェーズの候補（this session 完了後）】
1. ③ extension-language-runtime 設計 — Java 動的コンパイルによるプラグイン機構設計
2. ④ keymap-conflict-resolution — Vim/Emacs キー競合解決
3. ⑤ v3 横スクロール → ウィンドウ分割
```

---

### パターンB：③ extension-language-runtime 設計を選択した場合

```
【次セッション指示：③ extension-language-runtime 設計ドキュメント作成】

前セッション完了状態: ② v4 VISUALモード実装完了

【今セッションの目標】
設計: ③ extension-language-runtime — Java 動的コンパイルによるプラグイン機構の基本設計書作成

設計対象:
1. プラグイン API インタフェース定義（EditorPlugin / EditorCommand など）
2. プラグインのライフサイクル（ロード・初期化・コマンド実行・アンロード）
3. Java Compiler API（`javax.tools.JavaCompiler`）の使用方法
4. クラスローダーによる動的ロード方法
5. セキュリティモデル・検証方法

【完了条件】
1. `.claude/skills/extension-language-runtime/SKILL.md` を新規作成
   - 設計概要 / プラグイン仕様 / コンパイル方法 / ロード方法 / セキュリティ
   - コード例（API インタフェース・実装例）
   - 既知の制限・今後の拡張ポイント
2. 設計に矛盾がないこと（CLAUDE.md のロードマップと整合性確認）
3. ④ keymap-conflict-resolution が ③ 完了により着手可能になることを確認
4. git コミット・プッシュ完了

【参照すべきドキュメント】
1. `CLAUDE.md` — ③ の役割・④ との依存関係
2. `docs/handover-next-phase.md` — ③ 実装スケール・タスク分解

【設計の観点】
- Jar / .java ソースファイルの両方をサポートするか？
- プラグイン SDK は公開するか？内部用途に限るか？
- バージョン互換性をどう管理するか？（API の後方互換性）
- プラグイン間の通信（イベント・メッセージパッシング）は必要か？

【制約】
- Java 21 / Java SE 標準 API のみ
- 外部ライブラリ禁止

【次フェーズ】
- 設計完了後 → ④ keymap-conflict-resolution 実装が可能に
- または ② v5 実装を先行してから ③④ を進める（時間的余裕があれば）
```

---

### パターンC：④ keymap-conflict-resolution 実装を選択した場合

```
【次セッション指示：④ keymap-conflict-resolution 実装】

前セッション完了状態: ② v4（VISUAL/ヤンク/ペースト完了）・③ は設計ドキュメント完了

【今セッションの目標】
実装: ④ keymap-conflict-resolution — Vim/Emacs キーバインド競合解決

競合ケース分析:
1. Insert モード中の Ctrl キー
   - Vim: `Escape` で終了・Ctrl は基本未使用
   - Emacs: `Ctrl+F`/`B`/`N`/`P` でカーソル移動（既実装）
   - 解決: 現状設計は「Insert中は Emacs 式」→ 調査ユーザーの希望次第

2. `j` キー（NORMAL では下移動・プラグイン言語では可能な変数）
   - Vim のコンベンション: `j` は下・`k` は上
   - 拡張言語で再定義したい場合の衝突
   - 解決: キーマップレジストリ + 優先度管理

3. `:` コマンドバインド
   - Vim: `:` で ex コマンド
   - ④ で新しいコマンド体系を追加する場合の競合

【実装イメージ】
- `KeymapRegistry` クラスを新規作成（Keybinding の管理）
- デフォルトキーマップと拡張言語定義キーマップのマージ
- 優先度解決ロジック（拡張言語 > デフォルト）
- テスト: 複数キーマップロード・優先度確認

【完了条件】
1. `.claude/skills/keymap-conflict-resolution/SKILL.md` を設計ドキュメントとして作成（または CLAUDE.md に記載）
2. `src/dev/vimacs/editor/KeymapRegistry.java` を実装
3. ModalEditor での使用を切り替え（ハードコード → KeymapRegistry.lookup()）
4. テスト: KeymapRegistryTest + 既存 ModalEditorTest の回帰確認
5. git コミット・プッシュ

【参照すべきドキュメント】
1. `CLAUDE.md` — ②③④ の依存関係・ロードマップ
2. `.claude/skills/extension-language-runtime/SKILL.md` — ③ 設計（プラグインから Keymap 定義される想定）

【制約】
- Java 21 / Java SE 標準 API のみ
- 外部ライブラリ禁止

【スコープ外】
- プラグインからの動的キーマップ再定義（③ 完成後に実装）
```

---

### パターンD：⑤ v3 実装を選択した場合

```
【次セッション指示：⑤ gui-rendering-pipeline v3 実装】

前セッション完了状態: ② v4（VISUAL完了）・⑤ v2（縦スクロール完了）

【今セッションの目標】
実装: ⑤ gui-rendering-pipeline v3 — 横スクロール対応

タスク分解:
【段階1】横スクロール（2h 程度）
1. EditorCanvas に `scrollCol` フィールド追加
2. `drawText()` / `xForCol()` を横スクロール対応に変更
3. `ensureCursorVisible()` に左右フリングロジック追加
   - カーソルが画面右端に達したら scrollCol を増加
   - カーソルが画面左端に達したら scrollCol を減少
4. テスト 5+ ケース追加（80+ 文字行での移動・描画）

【段階2】ウィンドウ分割（1.5h 程度）
1. Main.java の構成を変更（JFrame 直下の Canvas → JSplitPane に変更）
2. 複数の EditorCanvas + ModalEditor ペアを管理するマネージャークラス
3. フォーカス切り替え（Ctrl+W / Ctrl+P で上下パネル切り替え）
4. 各パネルの独立したスクロール・カーソル・ファイル管理
5. テスト 5+ ケース・目視確認

【完了条件】
1. `./scripts/test.sh` で全テスト PASS
   - EditorCanvasTest: 10 → 20+ ケース（横スクロール＋分割パネル）
   - 既存テスト回帰なし
2. `./scripts/run.sh` で目視確認:
   - 段階1: 100 文字の行を編集・スクロールが正常に動作
   - 段階2: Ctrl+W で上下パネル切り替え・各パネルが独立管理
3. 大規模ファイル（10,000+ 行・1 行 200+ 文字）での性能確認
4. git コミット・プッシュ

【参照すべきドキュメント】
1. `.claude/skills/gui-rendering-pipeline/SKILL.md` — v1/v2 設計
2. `.claude/skills/gui-rendering-pipeline/references/future-phases.md` — v3 設計案
3. `src/dev/vimacs/ui/EditorCanvas.java` — 変更対象（scrollCol 追加・drawText 修正）
4. `src/dev/vimacs/Main.java` — 変更対象（JSplitPane 導入）

【実装の注意点】
- 横スクロール時にカーソル描画位置のオフセット（scrollCol）を考慮
- ウィンドウ分割時に複数 ModalEditor が同じキーボードフォーカス管理に登録されないよう注意
- JSplitPane の左・右（または上・下）ペイン構成の検討

【制約】
- Swing/AWT の標準コンポーネント のみ（JInternalFrame 等の複雑な枠組みは避ける）
- Java 21 / Java SE 標準 API のみ

【次フェーズの候補】
- ⑦ editor-testing-strategy — 大規模ファイル・ストレステスト体系
- ⑧ java-source-analysis — AST 解析・自動インポート機構
```

---

## 共通テンプレート（選択肢に関わらず実施すること）

### セッション開始時の確認フロー

```bash
# 1. 前セッションの成果を確認
cd /home/user/JavaTextEditor
git log --oneline -10
git branch -a

# 2. 現在のテスト状態を確認
./scripts/test.sh

# 3. ドキュメントを精読（この順序で・全部読み終わるまで実装着手禁止）
#    a. CLAUDE.md 全体（技術制約・ロードマップ再確認）
#    b. docs/handover-next-phase.md （次フェーズ選択肢・実装タスク）
#    c. docs/session-log.md 最後の 100 行（直前の作業内容）
#    d. 今回実装するフェーズの SKILL.md が存在すれば精読

# 4. 新規フィーチャーブランチ作成
git checkout main
git fetch origin
git checkout -b claude/<feature-name>-<8char-suffix>

# 5. 実装開始
```

### コミット・プッシュ時の最終確認

```bash
# 1. テスト全 PASS 確認
./scripts/test.sh

# 2. コミット内容を確認
git diff HEAD~5..HEAD --stat

# 3. コミットメッセージ作成（パターン例）
#    "② v5: 行単位ヤンク・VISUAL LINE モード実装"
#    "④ keymap-conflict-resolution: キーマップレジストリ実装"
#    "⑤ v3: 横スクロール・ウィンドウ分割実装"

# 4. プッシュ
git push -u origin claude/<feature-name>-<8char-suffix>

# 5. session-log.md 更新（セッション完了内容を記載）
```

---

## トラブルシューティング（テンプレート）

### よくあるエラーと対処法

**テストが FAIL する**:
```bash
# キャッシュクリア＆再ビルド
rm -rf build/
./scripts/build.sh
./scripts/test.sh
```

**特定のテストクラスだけ実行したい**:
```bash
cd /home/user/JavaTextEditor
javac -cp build -d build src/dev/vimacs/editor/ModalEditor.java \
                          test/dev/vimacs/editor/ModalEditorTest.java
cd build && java dev.vimacs.editor.ModalEditorTest
```

**ビルドエラー「シンボルが見つかりません」**:
```bash
# build/ ディレクトリが古いファイルを保持していないか確認
rm -rf build && mkdir build
./scripts/build.sh
```

---

## 最後の確認項目

**実装着手前に以下をすべてチェック**:
- [ ] `./scripts/test.sh` で既存テストが全 PASS
- [ ] 今回実装するフェーズの SKILL.md を完全に読了
- [ ] CLAUDE.md のロードマップで依存関係を確認（着手条件を満たしているか）
- [ ] 新規ブランチ作成済み（main から checkout 後に新ブランチ）
- [ ] GitHub 側でブランチが存在するか確認（`git push -u origin` の準備）

**実装完了時に以下をすべてチェック**:
- [ ] `./scripts/test.sh` で全テスト PASS・既存テストの回帰なし
- [ ] `./scripts/run.sh` で起動・目視で機能動作確認
- [ ] `docs/session-log.md` にセッション内容を追記
- [ ] git コミット・プッシュ完了
- [ ] 次フェーズの依存関係を確認・ロードマップ更新予定を記載

---

**最後に一言**: 
前セッションでの ② v4 VISUALモード実装は、エディタの使いやすさを大きく向上させました。
次のフェーズでも、同じペースで品質を保ちながら機能を拡張することを期待します。
技術制約（Java SE のみ・ライブラリなし）を厳守しつつ、学習価値の高い実装を目指してください。

---

**利用例**: 
- ユーザーが「次のセッション開始」と言ったら、このファイルから適切なパターンを選んでコピペ
- Claude に提示する前に、実装対象（v5/③/④/⑤）をユーザーに確認・コンセンサスを取ること
- セッション完了後、プロンプトの妥当性を確認・次回用に改善を記載
