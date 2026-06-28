# 作業引継書：② modal-editing-engine v2（ファイル保存・開閉・コマンドラインモード）

作成日: 2026-06-24  
前セッションブランチ: `claude/gui-rendering-scroll-k24jsa`（main にマージ済み）

---

## 前セッションまでの完了状態

| Skill | 状態 | テスト |
|---|---|---|
| ① `editor-buffer-architecture` | ✅ 完了 | 15/15 PASS |
| ② `modal-editing-engine` | ✅ **v1** 完了 | 46/46 PASS |
| ⑤ `gui-rendering-pipeline` | ✅ **v2**（縦スクロール）完了 | 8/8 PASS |
| その他 | 未着手 | — |

### main ブランチの最新コミット

```
7120c78  Merge: ⑤ gui-rendering-pipeline v2 スクロール対応
cafd429  .gitignore: build/ 除外
565397b  test: ScrollPreview 追加
ba838bd  ⑤ v2 スクロール実装
e77f8ff  session-log 更新
7f85ee9  Merge: ② modal-editing-engine v1 実装
```

---

## 今セッションで実装する機能

### ② modal-editing-engine v2：ファイル保存・開閉・コマンドラインモード

#### 実装内容

**1. モード定義の拡張**

`src/dev/vimacs/editor/ModalEditor.java` を以下のように変更する。

```java
// 現在
private boolean insertMode = false;

// 変更後: boolean 2つを enum 1つに置き換える
private enum Mode { NORMAL, INSERT, COMMAND }
private Mode mode = Mode.NORMAL;

// コマンドライン入力中の文字列バッファ
private final StringBuilder commandBuffer = new StringBuilder();

// 現在開いているファイルパス（null = 未保存の新規バッファ）
private String currentFilePath = null;
```

**2. キーバインドの追加（NORMALモード）**

| キー | 動作 |
|---|---|
| `:` | COMMMANDモードへ移行。ステータス行に `:` を表示し入力を受け付ける |

**3. COMMMANDモードの処理**

| 入力 | 動作 |
|---|---|
| 印字可能文字 | `commandBuffer` に追加。ステータス行をリアルタイム更新 |
| `Backspace` | `commandBuffer` の末尾を削除 |
| `Escape` | COMMMANDモードを中断、NORMALモードへ。`commandBuffer` をクリア |
| `Enter` | コマンドを実行（下記参照）し、NORMALモードへ戻る |

**4. 実装するコマンド**

| コマンド | 動作 | 使用API |
|---|---|---|
| `:w` | 現在のファイルパスへ保存。パス未設定時はステータス行にエラー表示 | `Files.writeString(Path, String)` |
| `:w <path>` | 指定パスへ保存し、`currentFilePath` を更新 | `Files.writeString(Path, String)` |
| `:e <path>` | 指定ファイルを開き、PieceTable を再初期化。カーソルを (0,0) にリセット | `Files.readString(Path)` |
| `:q` | アプリケーション終了 | `System.exit(0)` |
| `:wq` | 保存してから終了 | 上記の組み合わせ |
| 未定義コマンド | ステータス行に `E: unknown command 'xxx'` を表示 | — |

**5. EditorCanvas のステータス行変更**

コマンドライン入力中はモード表示の代わりに入力内容を表示する。

```java
// EditorCanvas に追加するフィールドとメソッド
private String commandLineText = null; // null = 通常のモード表示

public void setCommandLineText(String text) {
    this.commandLineText = text;
    repaint();
}
```

`drawStatusLine` を変更して `commandLineText != null` のとき `:xxx` を表示する。

**6. Main.java の変更**

コマンドライン引数でファイルパスを受け取れるようにする。

```java
// 変更前
ModalEditor editor = new ModalEditor(demoText.toString(), canvas);

// 変更後
String initialText;
String initialPath = null;
if (args.length > 0) {
    initialPath = args[0];
    initialText = Files.readString(Path.of(initialPath));
} else {
    initialText = demoText.toString();
}
ModalEditor editor = new ModalEditor(initialText, initialPath, canvas);
```

---

## テストハーネスへの追加項目

`test/dev/vimacs/editor/ModalEditorTest.java` に以下のテストグループを追加する。

| グループ | テスト内容 |
|---|---|
| COMMMANDモード遷移 | `:` でモード移行、ESCで中断、文字入力でbuffer蓄積 |
| `:w <path>` 保存 | ファイルが正しく書き込まれること（`Files.readString` で検証） |
| `:e <path>` 開閉 | バッファが差し替わること、カーソルが (0,0) になること |
| `:q` / `:wq` | System.exit の呼び出しを検証（SecurityManager 使用） |
| エラーケース | `:w` でパス未設定時のエラー表示、未定義コマンドのエラー表示 |

---

## 実装上の注意点

1. **`boolean insertMode` の削除に注意**  
   現在 `EditorCanvas.setInsertMode(boolean)` と `ModalEditor` の `syncCanvas()` が `insertMode` フィールドを参照している。`enum Mode` への変更後は `setInsertMode(mode == Mode.INSERT)` のように変換して渡す。

2. **`Files.writeString` / `Files.readString` のエラーハンドリング**  
   ファイルが存在しない・権限がないなどの `IOException` をキャッチし、アプリをクラッシュさせずステータス行にエラーメッセージを表示すること。

3. **Windows 改行 `\r\n` の正規化**  
   `:e <path>` でファイルを読み込む際、`text.replace("\r\n", "\n")` で正規化してから PieceTable に渡す。

4. **テスト用の一時ファイル**  
   `:w` / `:e` のテストでは `Files.createTempFile()` を使い、テスト後に `Files.deleteIfExists()` で後始末をすること。

5. **`:q` の System.exit テスト**  
   `System.exit(0)` は JVM ごとテストプロセスを終了させるため、`SecurityManager` を一時的に設置して呼び出しをインターセプトするか、ModalEditor にコールバック経由で終了を通知する設計にすること（後者を推奨）。

---

## 未解決の既知制限（このセッションでは対応不要）

| 制限 | 詳細 |
|---|---|
| 横スクロールなし | 長い行が画面外にはみ出す（⑤ v3 で対応予定） |
| VISUALモードなし | 範囲選択・ヤンク・ペーストが未実装 |
| アンドゥ/リドゥなし | PieceTable のスナップショット方式は設計済みだが未実装 |

---

## 完了条件

1. `./scripts/test.sh` で全テストクラスが PASS（ModalEditorTest のケース数が大幅に増えること）
2. `./scripts/run.sh <実在するファイルパス>` でそのファイルが開けること（目視確認）
3. `:w` で保存 → エディタ外から `cat` でファイル内容を確認できること
4. `docs/session-log.md` にこのセッションの作業記録・次フェーズ引き継ぎ事項を追記
