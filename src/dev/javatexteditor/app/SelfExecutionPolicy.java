package dev.javatexteditor.app;

/**
 * 「エディタ自身のプロジェクトをF11/F12で実行することを、ユーザーが明示的に許可したか」を
 * 問い合わせるためのインタフェース。
 *
 * <p>{@link JavaBuildRunner}（実行ブロック判定の呼び出し側）はこのインタフェースにのみ依存し、
 * 許可の保存方法（今回は {@link TransientSelfExecutionPolicy} によるセッション内一時許可）を
 * 知らない。将来、設定ファイルへの永続化（例: {@code PropertiesFileSelfExecutionPolicy}）に
 * 差し替える場合も、この実装クラスを差し替えるだけで呼び出し側の変更は不要になる。
 */
public interface SelfExecutionPolicy {

    /** 現時点でエディタ自身のプロジェクトの実行が許可されていれば true。 */
    boolean isAllowed();
}
