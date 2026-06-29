# 次セッション指示プロンプト

## 現在の状態

Skill ⑫ `openjdk-source-tracing` が完了し、main ブランチにマージ済み。
全 770 テストケース PASS（OpenjdkSourceTracingTest 29件 + RobotKeyInputTest +5件追加）。

`K` キーで `ClassName.methodName` 形式のカーソル位置にある native メソッドを検出し、
JNI マングル名（`Java_java_lang_System_arraycopy` 等）をステータスバーに表示する機能が動作している。
`src.zip`（JDK 付属）が存在すれば C/C++ 実装ファイルの位置も表示。存在しない場合は graceful degradation。

## 完了済み Skill 一覧

① editor-buffer-architecture ✅  ② modal-editing-engine ✅
③ extension-language-runtime ✅  ④ keymap-conflict-resolution ✅
⑤ gui-rendering-pipeline ✅      ⑥ plugin-api-design ✅
⑦ editor-testing-strategy ✅     ⑧ java-source-analysis ✅
⑨ javac-compile-integration ✅   ⑩ jdk-api-navigation ✅
⑪ javadoc-viewer ✅              ⑫ openjdk-source-tracing ✅
⑬ project-wide-search ✅         ⑭ multi-file-refactoring ✅
⑯ auto-import-handler ✅

**ロードマップに定義されたすべての Skill が完了しました。**

## 推奨: 追加機能の拡張候補

### 候補 A: src.zip スニペット表示の強化

現在の ⑫ は src.zip が存在しない環境では graceful degradation のみ。
`openjdk-21-source` パッケージがインストールされた環境では C/C++ スニペットを
`*jni*` 疑似バッファに表示する機能を追加できる。

### 候補 B: セッション保存・復元

`:e` で開いたファイル履歴を `~/.vimacs/session.json` に保存し、
次回起動時に最後に開いたファイルを自動復元する機能。

### 候補 C: `:sp` / `:vs` による分割ウィンドウ強化

`:sp <path>` で水平分割して別ファイルを開く、`:vs <path>` で垂直分割する機能。
現状の `Ctrl+W` による JSplitPane トグルを拡張する。

## 新機能着手時の手順

1. `claude/new-session-<feature>` ブランチを新規作成
2. 関連する `.claude/skills/` SKILL.md を確認
3. テスト → 実装 → Robot テスト → README 更新 → main マージ
4. 完了条件: 既存 770 テスト全 PASS + 新テスト追加 + docs 記録
