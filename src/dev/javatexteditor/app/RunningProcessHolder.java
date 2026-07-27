package dev.javatexteditor.app;

/**
 * F11/F12 で起動した子プロセスへの参照を1つだけ保持する。
 *
 * <p><b>このクラスが存在する理由</b>: 切り出し前は {@code Main.runningProcess} という
 * 単一の static フィールドを、Java 版（{@code runJavaClass}）と C 版（{@code runCExecutable}）の
 * <b>両方が読み書きしていた</b>。これは「F11 で起動したプログラムがまだ生きていれば
 * 殺してから起動し直す」という多重実行防止のためであり、
 * <b>言語をまたいで1つであること自体が仕様</b>である
 * （Java プログラムを実行したまま C プログラムを F11 で起動すると、先の Java プロセスが止まる）。
 *
 * <p>したがって {@link JavaBuildRunner} と {@link CBuildRunner} が
 * <b>それぞれ自前のフィールドとして持ってはならない</b>。
 * 分けると両方が同時に走れてしまい、静かに挙動が変わる。
 * 必ず同一インスタンスを両方のコンストラクタへ渡すこと。
 *
 * <p><b>メソッドを2つに分けている理由</b>: 切り出し前は
 * 「起動前に EDT 上で {@code destroy()}」→「起動後にバックグラウンドスレッド上で代入」
 * という別々のタイミングで行われていた。1つのメソッドにまとめると
 * このタイミングが変わってしまうため、あえて分けたまま移した。
 *
 * <p><b>既知の課題（MAIN_DECOMPOSITION_PLAN.md 段階2 の「気づき」）</b>:
 * 切り出し前の {@code Main.runningProcess} は {@code volatile} ではなく、
 * EDT とバックグラウンドの仮想スレッドから可視性の保証なく読み書きされていた。
 * 段階2は「振る舞いを変えない」ことを条件としているため、
 * ここでも {@code volatile} を付けずにそのまま移してある。
 * 付けるかどうかは別途判断する。
 */
public final class RunningProcessHolder {

    private Process current;

    /** 実行中のプロセスがまだ生きていれば終了させる（起動直前に呼ぶ）。 */
    public void terminateIfAlive() {
        if (current != null && current.isAlive()) {
            current.destroy();
        }
    }

    /** 起動したプロセスを記録する（{@code ProcessBuilder.start()} の直後に呼ぶ）。 */
    public void set(Process process) {
        this.current = process;
    }
}
