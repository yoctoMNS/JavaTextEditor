# 作業ログ: 診断行ジャンプ（[g / [d）

## 実装日
2026-06-28

## 概要
NORMALモードで `[g` / `[d` の2キーシーケンスによって、コンパイルエラー/警告行をジャンプする機能を実装した。

## 実装内容

### KeymapRegistry.java
- `[` を `diag.pending` アクションにバインド（`ofChar` と `ofCode` の両方を登録し、プラットフォーム差に対応）

### ModalEditor.java
- `localDiagnostics` フィールドを追加（`EditorCanvas` なし環境でのテスト用）
- `setDiagnostics(List<CompileDiagnostic>)` 公開メソッドを追加（canvas と localDiagnostics を同時にセット）
- `currentDiagnostics()` プライベートヘルパーを追加（canvas があれば canvas の診断、なければ localDiagnostics を返す）
- `diag.pending` アクション: `pendingSequence = "["` をセット
- `[g` シーケンス: `jumpToNextDiagnostic()` を呼び出し
- `[d` シーケンス: `jumpToPrevDiagnostic()` を呼び出し
- `jumpToNextDiagnostic()`: 現在行より大きい行番号の診断を昇順で探し、なければ最小行番号へ折り返し
- `jumpToPrevDiagnostic()`: 現在行より小さい行番号の診断を降順で探し、なければ最大行番号へ折り返し

## テスト追加

### ModalEditorTest.java（151→235 / +9件）
- `testDiagJumpNext()`: [g で次の診断行へジャンプ
- `testDiagJumpPrev()`: [d で前の診断行へジャンプ
- `testDiagJumpNextWrap()`: [g の折り返し動作
- `testDiagJumpPrevWrap()`: [d の折り返し動作
- `testDiagJumpEmpty()`: 診断なし時の動作（行が変わらない・statusMessage に "no diagnostics" が含まれる）

### RobotKeyInputTest.java（128→134 / +6件）
- `testDiagJumpNextRobot()`: Robot 経由で [g を3回押し、row=1→3→1（折り返し）を検証
- `testDiagJumpPrevRobot()`: Robot 経由で [d を3回押し、row=3→1→3（折り返し）を検証

## テスト結果
全 21 クラス PASS（931 テストケース）

```
=== dev.javatexteditor.editor.ModalEditorTest ===  PASS: 235 / 235
=== dev.javatexteditor.ui.RobotKeyInputTest ===    PASS: 134 / 134
合計: 931 テストケース全 PASS
```
