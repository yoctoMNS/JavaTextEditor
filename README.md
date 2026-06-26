# Vimacs Editor

VimのモーダルキーバインドとEmacsのカーソル操作を統合した、Java SE製の軽量テキストエディタ。

## 特徴

- **モーダル編集**: Vim式のNORMAL/INSERT/COMMAND/VISUAL/VISUAL LINEモードを採用
- **Emacs式カーソル移動**: INSERTモード中でも `Ctrl+F/B/N/P` で移動可能
- **コマンドラインモード**: `:w` でファイル保存、`:e` でファイルを開く、`:q` で終了
- **アンドゥ/リドゥ**: NORMALモードで `u` でアンドゥ、`Ctrl+R` でリドゥ
- **VISUALモード**: 文字単位の範囲選択・ヤンク・削除・貼り付け
- **VISUAL LINEモード**: 行単位の範囲選択・ヤンク・削除・行単位ペースト
- **高速バッファ**: ピーステーブル方式により、大規模ファイル（数十万行）でも高速に挿入・削除
- **縦スクロール**: カーソルが画面外に出ると自動追従
- **プラグインシステム**: `javax.tools.JavaCompiler` による動的コンパイルで Java ファイルをプラグインとしてロード
- **プラグイン公開API**: テキスト読み取り・編集・カーソル操作・キーバインド登録を `EditorContext` 経由で提供
- **Java SE標準APIのみ**: 外部ライブラリ不使用。Java 21で動作

## 必要環境

- Java 21 (LTS)
- JDK（`javac` が使えること）

## ビルドと実行

```bash
# ビルド
./scripts/build.sh

# 実行（デモテキストで起動）
./scripts/run.sh

# ファイルを指定して起動
./scripts/run.sh /path/to/file.txt

# テスト
./scripts/test.sh
```

Windowsの場合は `build.bat` / `run.bat` / `test.bat` を使用してください。

## キーバインド

### NORMALモード

| キー | 動作 |
|------|------|
| `h` | カーソルを左に移動 |
| `l` | カーソルを右に移動 |
| `j` | カーソルを下に移動 |
| `k` | カーソルを上に移動 |
| `i` | INSERTモードへ（カーソル前に挿入） |
| `a` | INSERTモードへ（カーソル後に挿入） |
| `o` | 現在行の下に新しい行を開いてINSERTモードへ |
| `u` | 直前の編集を取り消す（アンドゥ） |
| `Ctrl+R` | 取り消した編集をやり直す（リドゥ） |
| `v` | VISUALモードへ（文字単位選択） |
| `V` | VISUAL LINEモードへ（行単位選択） |
| `yy` | 現在行をヤンク |
| `dd` | 現在行を削除してヤンク |
| `x` | カーソル位置の1文字を削除 |
| `p` | ヤンクレジスタの内容をカーソルの後ろに貼り付け（文字）または現在行の下に貼り付け（行） |
| `P` | ヤンクレジスタの内容をカーソルの前に貼り付け（文字）または現在行の上に貼り付け（行） |
| `:` | COMMANDモードへ（画面下部にコマンド入力欄が表示される） |

### VISUALモード

| キー | 動作 |
|------|------|
| `h` / `l` / `j` / `k` | 選択範囲を拡張（カーソル移動） |
| `y` | 選択範囲をヤンク（文字単位）してNORMALモードへ戻る |
| `d` | 選択範囲を削除してヤンク（文字単位）し、NORMALモードへ戻る |
| `Escape` | VISUALモードを解除してNORMALモードへ戻る |

### VISUAL LINEモード

| キー | 動作 |
|------|------|
| `h` / `l` / `j` / `k` | 選択行範囲を拡張（行移動） |
| `y` | 選択行をヤンク（行単位）してNORMALモードへ戻る |
| `d` | 選択行を削除してヤンク（行単位）し、NORMALモードへ戻る |
| `Escape` | VISUAL LINEモードを解除してNORMALモードへ戻る |

### COMMANDモード

| コマンド | 動作 |
|----------|------|
| `:w` | 現在のファイルパスへ上書き保存 |
| `:w <path>` | 指定したパスへ保存（以降そのパスが「現在のファイル」になる） |
| `:e <path>` | 指定したファイルを開く（バッファを差し替え・カーソルをリセット） |
| `:q` | エディタを終了 |
| `:wq` | 保存してから終了 |
| `Backspace` | 入力済みコマンドを1文字削除 |
| `Escape` | COMMANDモードを中断してNORMALモードへ戻る |

### INSERTモード

| キー | 動作 |
|------|------|
| 通常文字 | カーソル位置に文字を挿入 |
| `Backspace` | カーソル直前の文字を削除（行頭では前行と結合） |
| `Enter` | 改行を挿入 |
| `Escape` | NORMALモードへ復帰 |
| `Ctrl+F` | カーソルを右に移動（Emacs式） |
| `Ctrl+B` | カーソルを左に移動（Emacs式） |
| `Ctrl+N` | カーソルを下に移動（Emacs式） |
| `Ctrl+P` | カーソルを上に移動（Emacs式） |

## ディレクトリ構成

```
project-root/
├── src/dev/vimacs/
│   ├── Main.java               # エントリポイント・GUI初期化
│   ├── buffer/
│   │   ├── Piece.java               # ピーステーブルのピース（record）
│   │   ├── PieceTable.java          # バッファ本体（insert/delete/getText）
│   │   └── UndoablePieceTable.java  # アンドゥ/リドゥ対応バッファ（PieceTable継承）
│   ├── editor/
│   │   ├── KeyBinding.java          # キーバインド（record）
│   │   ├── KeymapRegistry.java      # モード別キーバインド管理・カスタムアクション登録
│   │   └── ModalEditor.java         # モード管理・カーソル管理・キー処理
│   ├── extension/
│   │   ├── EditorContext.java       # プラグイン公開APIインタフェース
│   │   ├── EditorPlugin.java        # プラグインインタフェース
│   │   ├── PluginLoader.java        # JavaCompiler 動的ロード
│   │   └── SimpleEditorContext.java # ModalEditor → EditorContext アダプタ
│   └── ui/
│       ├── Theme.java          # カラーテーマ（LIGHT_MODE / DARK_MODE）
│       └── EditorCanvas.java   # Swing描画コンポーネント
├── test/dev/vimacs/
│   ├── buffer/
│   │   ├── PieceTableTest.java
│   │   └── UndoablePieceTableTest.java
│   ├── editor/
│   │   ├── KeymapRegistryTest.java
│   │   └── ModalEditorTest.java
│   ├── extension/
│   │   ├── EditorContextApiTest.java  # EditorContext API の結合テスト
│   │   └── PluginLoaderTest.java
│   └── ui/
│       ├── EditorCanvasTest.java
│       ├── ScrollPreview.java       # スクロール動作目視確認用
│       ├── VisualModePreview.java   # VISUALモード目視確認用
│       ├── VisualPreview.java       # GUI描画目視確認用
│       └── YankPasteDemo.java      # ヤンク/ペースト動作実演用
├── docs/
│   └── requirements.md
└── scripts/
    ├── build.sh / build.bat
    ├── test.sh  / test.bat
    └── run.sh   / run.bat
```

## アーキテクチャ

### バッファ: ピーステーブル方式

テキストの挿入・削除はバッファ全体をコピーせず、「ピース（範囲参照）」のリストを操作することで実現しています。大規模ファイルでも定数時間に近い挿入・削除が可能です。

```
PieceTable
  ├── original: String        （初期テキスト。変更しない）
  ├── addBuffer: StringBuilder （挿入テキストの追記バッファ）
  └── pieces: List<Piece>     （original/addBufferへの範囲参照リスト）

UndoablePieceTable（PieceTable継承）
  ├── undoStack: Deque<List<Piece>>  （編集前スナップショットのスタック）
  └── redoStack: Deque<List<Piece>>  （リドゥ用スナップショットのスタック）
```

アンドゥ/リドゥは `pieces` リストのコピー（参照のみ、実データ複製なし）をスタックに積む方式で実現しているため、スナップショットのコストはほぼゼロです。

### モーダル編集エンジン: ModalEditor と KeymapRegistry

`ModalEditor` がモード状態・カーソル位置を管理し、`PieceTable`（バッファ）と`EditorCanvas`（描画）を橋渡しします。キーバインドは `KeymapRegistry` により一元管理され、モード別に設定可能です。

```
キー入力 (KeyboardFocusManager)
    ↓
ModalEditor.processKey(keyCode, keyChar, modifiers)
    ├── KeymapRegistry.resolve(mode, keyCode, keyChar, modifiers)
    │   → アクション名の取得（ハードコード不要・外部から設定変更可能）
    │
    ├── NORMAL モード: cursor.left/right/up/down, enter.insert, enter.insert.after,
    │                  enter.insert.newline, undo, redo, yank.pending, delete.pending,
    │                  enter.visual, enter.visual.line, delete.char, paste.after, paste.before,
    │                  enter.command
    ├── INSERT モード: cursor.right/left/up/down, enter.normal, delete.before, insert.newline
    ├── COMMAND モード: enter.normal, execute.command
    ├── VISUAL モード: cursor.left/right/up/down, yank, delete, enter.normal
    └── VISUAL LINE モード: cursor.left/right/up/down, yank, delete, enter.normal
            ↓
    PieceTable.insert() / delete()           ← バッファ更新
    Files.writeString() / Files.readString() ← ファイルI/O
    EditorCanvas.setText() / setCursor()
        / setInsertMode() / setVisualMode() / setVisualLineMode()
        / setSelection() / setCommandLineText()    ← 再描画
```

**KeymapRegistry** は、モード別のキーバインドを一元管理し、以下の機能を提供します：

- `loadDefaults()`: デフォルトキーマップを定義（Vim標準 + Emacs式INSERTモード移動）
- `bind(mode, keyBinding, actionName)`: 新規キーバインドの登録・既存バインドの上書き
- `resolve(mode, keyCode, keyChar, modifiers)`: キー入力からアクション名を解決
- `registerAction(actionName, handler)`: カスタムアクションハンドラの登録（プラグインから呼び出し可能）
- `getCustomAction(actionName)`: 登録済みのカスタムハンドラを取得

カスタムアクションはビルトインアクションに優先して実行されるため、既存のキー動作を上書きすることも可能です。

### プラグインシステム

#### プラグインの作り方

`EditorPlugin` インタフェースを実装した Java ファイルを用意し、`PluginLoader.loadPlugin(Path)` でロードします。JDK の `javax.tools.JavaCompiler` を使ってエディタ起動中に動的コンパイル・ロードが行われます。

```java
// MyPlugin.java
import dev.vimacs.extension.EditorPlugin;
import dev.vimacs.extension.EditorContext;
import dev.vimacs.editor.KeymapRegistry;
import dev.vimacs.editor.KeyBinding;

public class MyPlugin implements EditorPlugin {
    public String getName() { return "myplugin"; }

    public void execute(EditorContext ctx) {
        // テキスト読み取り
        String line = ctx.getLine(ctx.getCursorRow());

        // テキスト挿入
        int offset = ctx.offsetAt(ctx.getCursorRow(), 0);
        ctx.insertAtOffset(offset, "// ");

        // カーソル移動
        ctx.setCursor(0, 0);

        // カスタムキーバインド登録
        ctx.getKeymap().registerAction("my.greet",
            () -> ctx.setStatusMessage("Hello from plugin!"));
        ctx.getKeymap().bind(KeymapRegistry.Mode.NORMAL,
            KeyBinding.ofChar('Q', "my.greet"), "my.greet");
    }
}
```

#### EditorContext 公開 API

| カテゴリ | メソッド | 説明 |
|---|---|---|
| テキスト読み取り | `getText()` | バッファ全体のテキスト |
| テキスト読み取り | `length()` | バッファの総文字数 |
| テキスト読み取り | `getLineCount()` | 総行数 |
| テキスト読み取り | `getLine(int row)` | 指定行のテキスト（0始まり、範囲外は空文字列） |
| テキスト操作 | `insertAtOffset(int, String)` | 指定オフセットに文字列を挿入 |
| テキスト操作 | `deleteRange(int, int)` | 指定範囲（排他）を削除 |
| カーソル読み取り | `getCursorRow()` | カーソルの行番号 |
| カーソル読み取り | `getCursorCol()` | カーソルの列番号 |
| カーソル読み取り | `offsetAt(int row, int col)` | (row, col) を絶対文字オフセットに変換 |
| カーソル操作 | `setCursor(int row, int col)` | カーソルを移動（範囲外は自動クランプ） |
| モード問い合わせ | `isNormalMode()` | NORMALモードなら true |
| モード問い合わせ | `isInsertMode()` | INSERTモードなら true |
| UI | `setStatusMessage(String)` | ステータスバーにメッセージ表示 |
| キーマップ | `getKeymap()` | `KeymapRegistry` を返す（キーバインド登録・変更に使用） |

### GUI描画: EditorCanvas

`JPanel` を継承した `EditorCanvas` が `Graphics2D` で直接描画します。

- 全角文字（CJK・ひらがな・カタカナ）を2セル幅として正確に描画
- NORMALモード: ブロックカーソル（前景色の矩形）
- INSERTモード: 縦棒カーソル（2px幅）
- VISUALモード: 選択範囲を文字単位でアクセントカラーでハイライト表示
- VISUAL LINEモード: 選択行を行全幅でアクセントカラーでハイライト表示
- 画面最下部にステータス行（`-- NORMAL --` / `-- INSERT --` / `-- VISUAL --` / `-- VISUAL LINE --` / `:コマンド入力中` / 操作結果メッセージ）
- 縦スクロール対応（カーソルが画面外に出ると自動追従）

## テスト結果

```
=== dev.vimacs.buffer.PieceTableTest ===               PASS: 15 / 15
=== dev.vimacs.buffer.UndoablePieceTableTest ===       PASS: 11 / 11
=== dev.vimacs.editor.KeymapRegistryTest ===           PASS: 46 / 46
=== dev.vimacs.editor.ModalEditorTest ===              PASS: 151 / 151
=== dev.vimacs.extension.EditorContextApiTest ===      PASS: 39 / 39
=== dev.vimacs.extension.PluginLoaderTest ===          PASS: 9 / 9
=== dev.vimacs.ui.EditorCanvasTest ===                 PASS: 22 / 22

合計: 293 テストケース全 PASS
```

## 技術制約

- **言語**: Java 21 (LTS)
- **依存ライブラリ**: なし（Java SE標準APIのみ）
- **ビルドツール**: なし（`javac` 直接呼び出し）
- **テストフレームワーク**: なし（`main` メソッド形式の自作ハーネス）
