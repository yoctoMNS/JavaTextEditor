package dev.javatexteditor.app;

/**
 * {@code :set allowselfexecution} コマンド専用の {@link SelfExecutionPolicy} 実装。
 *
 * <p>許可はこのJVMプロセスが生きている間だけ有効で、永続化はしない
 * （再起動すると既定の禁止状態に戻る）。既定値は {@code false}（fail-safe：
 * 明示的に許可されない限りブロックする）。
 *
 * <p>このクラスへの書き込み（{@link #setAllowed}）は {@code :set allowselfexecution}
 * コマンドの配線側（{@code PaneManager}/{@code ModalEditor}）だけが行う。
 * 読み取り側（{@link JavaBuildRunner} の実行ブロック判定）は {@link SelfExecutionPolicy}
 * インタフェース越しにしかこのクラスを知らない。
 */
public final class TransientSelfExecutionPolicy implements SelfExecutionPolicy {

    private volatile boolean allowed = false;

    /** {@code :set allowselfexecution} から呼ばれる。真偽いずれも指定でき、恒久的な設定ではない。 */
    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }

    @Override
    public boolean isAllowed() {
        return allowed;
    }
}
