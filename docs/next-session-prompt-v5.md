# Claude Code 次セッション用最適化プロンプト

**使用時機**: 次のセッション開始時、ユーザーが仕事を指示する前に、このプロンプトを Claude に提示してください。

**注記**: このプロンプトは以下を前提としています:
- 前セッションで ② modal-editing-engine v5（行単位ヤンク・VISUAL LINE）が完成
- `claude/modal-editing-v5-visual-line-lm7pxq` ブランチが main にマージ済み
- テスト: 全 4 クラス合計 192 ケース PASS
- 次フェーズの候補が複数ある（⑤ v3・③・④）

---

## セッション開始時プロンプト案

### パターンA：⑤ v3 実装を選択した場合（推奨）

```
【次セッション指示：⑤ gui-rendering-pipeline v3 実装】

前セッションでの完了状態:
- ① editor-buffer-architecture: ✅ 実機検証済み（15/15 テスト）
- ② modal-editing-engine: ✅ v5 完了・VISUAL/VISUAL LINE モード・行単位ヤンク（151/151 テスト）
- ⑤ gui-rendering-pipeline: ✅ v2 完了・縦スクロール対応（15/15 テスト）

現在のブランチ状態:
```
main ← 8c702b7（README.md ② v5 対応更新）
       d4007e6（Merge: ② v5 完了）
```

【今セッションの目標】
実装: ⑤ gui-rendering-pipeline v3 — 横スクロール + ウィンドウ分割（2段階実装）

【段階1】横スクロール対応（0.5〜1時間）:
1. EditorCanvas に `scrollCol` フィールド追加
2. `drawText()` / `xForCol()` を横スクロール対応に変更（全角文字 charCellWidth 考慮）
3. `ensureCursorVisible()` に左右フリングロジック追加
   - カーソルが画面右端を超えたら scrollCol を増加
   - カーソルが画面左端より左に出たら scrollCol を減少
4. テスト 5+ ケース追加（80+ 文字行での移動・描画）

【段階2】ウィンドウ分割（1〜1.5時間）:
1. Main.java の構成を JSplitPane 対応に変更
2. 複数の EditorCanvas + ModalEditor ペアを管理するマネージャークラス
3. フォーカス切り替え（Ctrl+W で上下パネル切り替え）
4. 各パネルの独立したスクロール・カーソル・ファイル管理
5. テスト 5+ ケース・目視確認

【完了条件（すべて満たしてからプッシュ）】
1. `./scripts/test.sh` で全テストクラス PASS（既存 192 + 新規 10+）
   - EditorCanvasTest: 15 → 20+ ケース
   - 既存テスト回帰なし
2. `./scripts/run.sh` で起動して以下を目視確認:
   - 段階1: 100文字の行を編集・横スクロールが正常に動作
   - 段階2: Ctrl+W で上下パネル切り替え・各パネルが独立管理
   - 大規模ファイル（10,000+ 行・1行 200+ 文字）での性能確認
3. `README.md` を v3 対応に更新
4. git コミット・プッシュ完了

【参照すべきドキュメント】
1. `CLAUDE.md` — 技術制約・ロードマップ
2. `docs/handover-modal-editor-v5.md` — 前セッション引継書
3. `.claude/skills/gui-rendering-pipeline/SKILL.md` — v1/v2 設計
4. `.claude/skills/gui-rendering-pipeline/references/future-phases.md` — v3 設計案
5. `src/dev/vimacs/ui/EditorCanvas.java` — 変更対象
6. `src/dev/vimacs/Main.java` — 変更対象

【実装の注意点】
- 横スクロール時にカーソル描画位置のオフセット（scrollCol）を考慮
- ウィンドウ分割時に複数 ModalEditor が同じキーボードフォーカス管理に登録されないよう注意
- JSplitPane の上・下（または左・右）ペイン構成の検討（上下推奨）
- 各パネルのカーソル・スクロール位置を独立管理する仕組み

【制約（変更禁止）】
- Java 21 / Java SE 標準 API のみ
- ビルド: `./scripts/build.sh`・テスト: `main` メソッド形式のみ
- 外部ライブラリ・Maven/Gradle 禁止

【スコープ外（v4 以降）】
- 水平スプリットパネル（左右分割）
- パネルのリサイズハンドル対応
- パネルサイズ保存・復元
```

---

### パターンB：③ extension-language-runtime 設計を選択した場合

```
【次セッション指示：③ extension-language-runtime 設計ドキュメント作成】

前セッション完了状態: ② v5 VISUALモード実装完了

【今セッションの目標】
設計: ③ extension-language-runtime — Java 動的コンパイルによるプラグイン機構の基本設計書作成

設計対象:
1. プラグイン API インタフェース定義（EditorPlugin / EditorCommand など）
2. プラグインのライフサイクル（検出・コンパイル・ロード・初期化・実行・アンロード）
3. Java Compiler API（`javax.tools.JavaCompiler`）の使用方法
4. カスタムクラスローダーによる動的ロード方法
5. セキュリティモデル・検証方法（署名検証など）

【完了条件】
1. `.claude/skills/extension-language-runtime/SKILL.md` を新規作成
   - 設計概要 / プラグイン仕様 / コンパイル方法 / ロード方法 / セキュリティ
   - EditorPlugin インタフェースの定義例
   - 実装例コード（簡易プラグイン）
   - 既知の制限・今後の拡張ポイント
   - Java SE API（javax.tools.JavaCompiler・java.lang.reflect など）の使用法
2. `.claude/skills/extension-language-runtime/references/` にサブドキュメント作成（オプション）
   - plugin-api-design.md （API 仕様詳細）
   - security-model.md （セキュリティ戦略）
3. 設計に矛盾がないこと（CLAUDE.md のロードマップと整合性確認）
4. ④ keymap-conflict-resolution が ③ 完了により着手可能になることを確認
5. git コミット・プッシュ完了

【参照すべきドキュメント】
1. `CLAUDE.md` — ③ の役割・④ との依存関係
2. `docs/handover-modal-editor-v5.md` — 前セッション引継書
3. Java SE ドキュメント：javax.tools.JavaCompiler / java.lang.ClassLoader

【設計の観点】
- Jar / .java ソースファイルの両方をサポートするか？
- プラグイン SDK は公開するか？内部用途に限るか？
- バージョン互換性をどう管理するか？（API の後方互換性）
- プラグイン間の通信（イベント・メッセージパッシング）は必要か？
- エラーハンドリング・コンパイルエラー時の挙動

【制約】
- Java 21 / Java SE 標準 API のみ（javax.tools.* / java.lang.reflect など）
- 外部ライブラリ禁止
- デザインドキュメント作成のみ（実装は別セッション）

【次フェーズ】
- 設計完了後 → ④ keymap-conflict-resolution 実装が可能に
- または ⑤ v3 実装を先行してから ③④ を進める（スケジューリング次第）
```

---

### パターンC：④ keymap-conflict-resolution 実装を選択した場合

```
【次セッション指示：④ keymap-conflict-resolution 実装】

前セッション完了状態: ② v5（VISUAL/VISUAL LINE完了）・③は設計ドキュメント完了

【今セッションの目標】
実装: ④ keymap-conflict-resolution — Vim/Emacs キーバインド競合解決

競合ケース分析:
1. Insert モード中の Ctrl キー
   - Vim: Escape で終了・Ctrl は基本未使用
   - Emacs: Ctrl+F/B/N/P でカーソル移動（既実装）
   - 解決: 現状設計は「Insert中は Emacs 式」が確定・動作に問題なし

2. `j` キー（NORMAL では下移動・プラグイン言語では可能な変数）
   - Vim のコンベンション: `j` は下・`k` は上
   - 拡張言語で再定義したい場合の衝突
   - 解決: キーマップレジストリ + 優先度管理（拡張言語 > Vim デフォルト）

3. `:` コマンドバインド
   - Vim: `:` で ex コマンド（既実装）
   - ④ で新しいコマンド体系を追加する場合の競合
   - 解決: 設計段階で「カスタムコマンド」の仕様を決定

【実装イメージ】
- `KeymapRegistry` クラスを新規作成（Keybinding の一元管理）
- デフォルトキーマップと拡張言語定義キーマップのマージ機構
- 優先度解決ロジック（拡張言語 > Vim デフォルト）
- ModalEditor.processKey() を KeymapRegistry.lookup() に切り替え

【完了条件】
1. `src/dev/vimacs/editor/KeymapRegistry.java` を実装
   - デフォルトキーマップの定義（ハードコード or 設定ファイル）
   - キーマップマージ・優先度管理
   - lookup(keyCode, modifiers) メソッド
2. ModalEditor.processKey() を KeymapRegistry 対応に変更（リファクタ）
3. `.claude/skills/keymap-conflict-resolution/SKILL.md` を設計ドキュメントとして作成
4. テスト: KeymapRegistryTest + 既存 ModalEditorTest の回帰確認
5. git コミット・プッシュ

【参照すべきドキュメント】
1. `CLAUDE.md` — ②③④ の依存関係
2. `docs/handover-modal-editor-v5.md` — 前セッション引継書
3. `.claude/skills/extension-language-runtime/SKILL.md` — ③ 設計（キーマップ定義機構）

【制約】
- Java 21 / Java SE 標準 API のみ
- 外部ライブラリ禁止
- 既存テスト（ModalEditorTest）の回帰がないこと

【スコープ外】
- プラグインからの動的キーマップ再定義（③ 完成後に実装）
- マクロ機能（⑥ plugin-api-design 以降）
```

---

## 共通テンプレート（選択肢に関わらず実施すること）

### セッション開始時のチェックリスト

```bash
# 1. 前セッションの成果を確認
cd /home/user/JavaTextEditor
git log main --oneline -10
git branch -a

# 2. 現在のテスト状態を確認
./scripts/test.sh
# 期待: PASS: 192 / 192  (FAIL: 0)

# 3. ドキュメントを精読（この順序で・全部読み終わるまで実装着手禁止）
#    a. CLAUDE.md 全体（技術制約・ロードマップ再確認）
#    b. docs/handover-modal-editor-v5.md （前セッション引継書）
#    c. docs/session-log.md 最後の 50 行（直前の作業内容）
#    d. 今回実装するフェーズの SKILL.md / references/ （設計・注意点）

# 4. 新規フィーチャーブランチ作成
git checkout main
git fetch origin
git checkout -b claude/<feature-name>-<8char-suffix>

# 5. ビルド確認
./scripts/build.sh
# 期待: Build OK

# 6. 実装開始
```

### 実装完了時のテストチェックリスト

```bash
# 1. テスト全 PASS 確認
./scripts/test.sh
# 期待: === Summary: 4 class(es) passed, 0 class(es) failed ===

# 2. 既存機能の回帰がないか（全テスト数が増えていることを確認）
# 例: v5 後 → 192 テスト
# 例: v3 後 → 202+ テスト

# 3. 目視確認
./scripts/run.sh
# 新機能を実際に試す・キー入力が効く・描画が正しいか

# 4. git diff で変更内容を確認
git diff main --stat
git diff main                    # 大幅な内容変更がないか確認

# 5. コミット・プッシュ
git add <changed-files>
git commit -m "..."
git push -u origin <branch>

# 6. main へのマージ
git checkout main
git merge <feature-branch> --no-ff
git push origin main
```

### ドキュメント更新テンプレート

**README.md** を新機能で更新（機能追加時は必須）:
- 特徴セクション: 新機能を 1 行で説明
- キーバインド: 新キーを追加
- アーキテクチャ図: 新機能が関わる部分を反映
- テスト結果: テスト数を更新

**docs/session-log.md** に追記:
```markdown
## [セッション日付] ⑤ gui-rendering-pipeline v3：横スクロール・ウィンドウ分割

実装内容:
- scrollCol フィールドで横スクロール対応
- JSplitPane によるウィンドウ分割
- Ctrl+W でパネル切り替え

テスト: 192 → 202 ケース（+10）
コミット: xxxx xxx
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

**ビルドエラー「シンボルが見つかりません」**:
```bash
# build/ ディレクトリが古いファイルを保持していないか確認
rm -rf build && mkdir build
./scripts/build.sh
```

**既存テストが FAIL するようになった（回帰）**:
```bash
# 変更したファイルを確認
git diff HEAD~1 --name-only

# 変更前の状態に戻して、差分を段階的に追加し直す
git reset --hard HEAD~1
git cherry-pick <commit-hash>
```

**git push が失敗する**:
```bash
# リモートの最新を取得・リベース
git fetch origin
git rebase origin/main
git push -u origin <branch>
```

---

## セッション完了チェックリスト

**必ずすべて満たしてから main へマージ:**

- [ ] `./scripts/test.sh` で全テスト PASS（新規テスト + 既存回帰なし）
- [ ] `./scripts/run.sh` で起動・新機能を目視で動作確認
- [ ] `README.md` を新機能で更新（テスト結果含む）
- [ ] `docs/session-log.md` にセッション内容を追記（1〜2行）
- [ ] git コミット・プッシュ完了（フィーチャーブランチ）
- [ ] main へのマージ・プッシュ完了
- [ ] 次フェーズの依存関係を確認・ロードマップ更新予定を記載

---

## 最後の確認項目

- [ ] CLAUDE.md と handover ドキュメントに矛盾がないこと
- [ ] テスト数が前回より増えていること（各フェーズで必ず新テスト追加）
- [ ] 技術制約を守っていること（外部ライブラリなし・Java 21 のみ）
- [ ] コード品質が低下していないこと（簡潔・コメント最小限）

---

**利用例**: 
- ユーザーが「次のセッション開始」と言ったら、このファイルから適切なパターンを選んでコピペ
- Claude に提示する前に、実装対象（⑤ v3・③・④）をユーザーに確認・コンセンサスを取ること
- セッション完了後、プロンプトの妥当性を確認・次回用に改善を記載

