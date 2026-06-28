# 次セッション向けプロンプト: ⑦ editor-testing-strategy

## プロジェクト概要

Vim（モーダル編集）とEmacs（拡張性）の良い所を統合した Java SE 21 製テキストエディタ「Vimacs」。
外部ライブラリなし・JUnit なし・自作テストハーネス（`main` メソッド形式）で実装・テスト済み。

## 現在の状態（実装済み）

| Skill | 内容 | 状態 |
|---|---|---|
| ① editor-buffer-architecture | PieceTable + UndoablePieceTable | ✅ 完了 (15+11 テスト) |
| ② modal-editing-engine | NORMAL/INSERT/COMMAND/VISUAL/VISUAL_LINE | ✅ 完了 (151 テスト) |
| ③ extension-language-runtime | JavaCompiler 動的プラグインロード | ✅ 完了 (9 テスト) |
| ④ keymap-conflict-resolution | KeymapRegistry + Phase 3 プラグインキーバインド | ✅ 完了 (38 テスト) |
| ⑤ gui-rendering-pipeline | Swing/AWT GUI、縦横スクロール、JSplitPane | ✅ 完了 (22 テスト) |
| ⑥ plugin-api-design | EditorContext 公開API（行操作・カーソル・キーマップ） | ✅ 完了 (39 テスト) |

**合計: 285 テストケース全 PASS**

## 今回の作業: ⑦ editor-testing-strategy

### 目標

現在の自作テストハーネスは「正常系・基本的な境界値」を中心に検証している。
このSkillでは以下を追加する：

1. **境界値テストの強化**: 空バッファ・1文字バッファ・行末・行頭など極端なケースを網羅
2. **大規模ファイルのパフォーマンステスト**: 数万行のファイルで挿入・削除・アンドゥが許容時間内に完了することを確認
3. **カーソルクランプの網羅**: `setCursor` / `moveCursor` の境界動作（負値・超過値・空行）を体系化
4. **アンドゥ/リドゥの深いシーケンス**: 50回以上の編集→アンドゥ→リドゥチェーンで整合性を確認
5. **マルチバイト文字境界**: 全角文字を含む行でのオフセット計算・カーソル位置の一貫性

### 実装方針

- テストは既存の `test/dev/vimacs/` 配下に追加
- 各テストクラスは `main` メソッドを持つ自作ハーネス形式
- パフォーマンステストは `System.currentTimeMillis()` で計測し、閾値を明示（例: 10万行で100ms以内）
- 新規テストクラスが追加されたら `scripts/test.sh` を更新してハーネスに登録する

### 期待するテストクラス（案）

```
test/dev/vimacs/
├── buffer/
│   └── PieceTableEdgeCaseTest.java   # 空バッファ・大量操作・境界削除
├── editor/
│   └── ModalEditorEdgeCaseTest.java  # カーソルクランプ・深いアンドゥ・マルチバイト境界
└── performance/
    └── LargeFileTest.java            # 10万行挿入・削除速度計測
```

### 作業手順

1. `.claude/skills/editor-testing-strategy/SKILL.md` を作成し、テスト戦略を記録
2. 上記テストクラスを実装してテストが通ることを確認
3. `scripts/test.sh` に新テストクラスを追加
4. `README.md` のテスト結果表を更新
5. `CLAUDE.md` の Skill ⑦ を「完了」に更新
6. コミット・プッシュ・main マージ

### 注意事項

- **言語**: Java 21 (LTS)。外部ライブラリ不使用
- **ビルド**: `./scripts/build.sh`（javac 直接）
- **テスト**: `./scripts/test.sh`
- 実装前に `.claude/skills/` 配下の既存 SKILL.md を確認すること（特に `editor-buffer-architecture`）
- `test.sh` に新クラスを追加する際は、既存クラスのエントリ形式に合わせること

## ブランチ運用

- **作業ブランチ**: `claude/editor-testing-strategy-XXXXXX`（新規作成）
- 完了後に `main` へマージ

## 参考ファイル

- `src/dev/vimacs/buffer/PieceTable.java` — insert/delete/getText の実装
- `src/dev/vimacs/buffer/UndoablePieceTable.java` — undo/redo の実装
- `src/dev/vimacs/editor/ModalEditor.java` — モード・カーソル管理
- `test/dev/vimacs/buffer/PieceTableTest.java` — 既存テストの形式参考
- `scripts/test.sh` — テスト登録方法の参考
