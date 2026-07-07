[← 目次](README.md)

# 7. プラグインシステム

Lispインタプリタの自作ではなく、`javax.tools.JavaCompiler`（JDK標準API）による動的コンパイルでJavaそのものを拡張言語として使う設計です。外部ライブラリは使用しません。

> **現状の接続状況**: プラグイン機構（`extension/` パッケージ）自体は完成していますが、`:plugin` のような起動コマンドがまだ実装されておらず、本番のエディタUIからは接続されていません（テストからのみ呼び出し可能）。以下は機構として提供されているAPIの説明です。

## プラグインの作り方

`EditorPlugin` インタフェースを実装したJavaファイルを用意し、`PluginLoader.loadPlugin(Path)` でロードします。エディタ起動中に `javax.tools.JavaCompiler` を使って動的コンパイル・ロードが行われます。

```java
// MyPlugin.java
import dev.javatexteditor.extension.EditorPlugin;
import dev.javatexteditor.extension.EditorContext;
import dev.javatexteditor.editor.KeymapRegistry;
import dev.javatexteditor.editor.KeyBinding;

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

## `EditorContext` 公開API

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

## `KeymapRegistry` によるキーバインド拡張

`KeymapRegistry` はモード別のキーバインドを一元管理し、以下の機能を提供します。

- `loadDefaults()`: デフォルトキーマップを定義（Vim標準 + Emacs式INSERTモード移動）
- `bind(mode, keyBinding, actionName)`: 新規キーバインドの登録・既存バインドの上書き
- `resolve(mode, keyCode, keyChar, modifiers)`: キー入力からアクション名を解決
- `registerAction(actionName, handler)`: カスタムアクションハンドラの登録（プラグインから呼び出し可能）
- `getCustomAction(actionName)`: 登録済みのカスタムハンドラを取得

カスタムアクションはビルトインアクションに優先して実行されるため、既存のキー動作を上書きすることも可能です。

## `dev.javatexteditor.completion2` パッケージについて

`src/dev/javatexteditor/completion2/` には、Vimの `i_CTRL-N` 相当の単語補完を別クラス構成（`CompletionCandidate`/`CompletionSession`/`CompletionEngine`/`TokenScanner`/`EditorKeyHandler`/`CompletionController`/`CompletionPopupModel`）で実装した独立パッケージが存在します。これは本番の補完機能（[コード補完](05-completion.md)、`WordIndex`/`CompletionIndex` ベース）とは別物で、`Main.java`/`ModalEditor.java`/`EditorCanvas.java`/`KeymapRegistry` からは一切参照されていません。本番経路への接続や削除の方針は未確定です。詳細な経緯は `CLAUDE.md` を参照してください。
