package dev.vimacs.extension;

/**
 * プラグイン作者が実装するインタフェース。
 *
 * 最小実装は getName() と execute(EditorContext) のみ。
 * onLoad / onUnload はデフォルト実装（空）なのでオーバーライド任意。
 */
public interface EditorPlugin {
    /** プラグインの識別名（:plugin <name> で呼び出すときに使う）。 */
    String getName();

    /** エディタ起動後、プラグインがロードされた直後に一度だけ呼ばれる。 */
    default void onLoad(EditorContext ctx) {}

    /** ユーザーがコマンドでプラグインを呼び出すたびに実行される本体。 */
    void execute(EditorContext ctx);

    /** プラグインがアンロードされる直前に呼ばれる（リソース解放用）。 */
    default void onUnload() {}
}
