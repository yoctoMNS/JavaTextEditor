package dev.javatexteditor.app;

import java.io.IOException;

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
 * <p><b>2026-08 改修: terminateIfAlive()/set() の2メソッド分離を廃止し、原子化した</b>。
 * 旧設計（「起動前に EDT 上で {@code destroy()}」→「起動後にバックグラウンドスレッド上で
 * {@code set()}」という2段階呼び出し）には、F11 の連打やマクロ（vim-macro-recording）による
 * 連打で複数の実行要求が短時間に重なった場合、次の順序でプロセスが「孤児化」しうるという
 * レースコンディションがあった:
 * <ol>
 *   <li>要求Aが {@code terminateIfAlive()} を呼ぶ（まだ何も走っていないので何もしない）</li>
 *   <li>要求Aがプロセス1を起動する（{@code pb.start()}）</li>
 *   <li>要求Bが {@code terminateIfAlive()} を呼ぶ（この時点で {@code current} は
 *       まだ要求Aの {@code set()} 前で null のまま。よってプロセス1は終了させられない）</li>
 *   <li>要求Bがプロセス2を起動し {@code set(process2)} を呼ぶ</li>
 *   <li>要求Aが（要求Bより後に）{@code set(process1)} を呼び、{@code current} を上書きする</li>
 * </ol>
 * この結果、プロセス2への参照は失われて二度と {@code destroy()} されず、
 * かつ {@code current} が指すプロセス1は既に不要という、2つとも制御不能な状態になりうる。
 * 本改修では「直前のプロセスを終了させる → 新しいプロセスを起動する → 参照を記録する」
 * という一連の操作全体を {@link #terminateAndStart} 1メソッドに集約し、{@code synchronized}
 * で全体を排他することで、この一連の流れが同時に1呼び出ししか実行されないようにした。
 * これにより呼び出し元（{@link JavaBuildRunner#runJavaClass}/
 * {@link CBuildRunner} の実行メソッド）でのプロセス起動は、必ずこの1メソッド経由になる。
 *
 * <p><b>{@code synchronized} を選んだ理由（{@code ReentrantLock} ではなく）</b>:
 * この排他区間は「プロセスを起動する」という短時間で完了する処理であり、
 * タイムアウト付き取得・複数条件変数・ロック状態の外部からの問い合わせといった
 * {@code ReentrantLock} 固有の機能を必要としないため、JVM組み込みのモニターロックで
 * 完結する {@code synchronized} の方が、余分な try/finally を書かずに済み簡潔である。
 *
 * <p><b>{@code volatile} について</b>: {@code current} への読み書きは
 * {@link #terminateAndStart} 内（synchronized化済み）に限られるため、可視性は
 * {@code synchronized} のhappens-before関係だけで保証される。ただし将来
 * 参照専用の読み取りメソッドを追加する場合に備え、{@code volatile} も付与しておく
 * （synchronizedブロック外からの読み取りが増えても安全側に倒すため）。
 */
public final class RunningProcessHolder {

    private volatile Process current;

    /**
     * 直近のプロセスがまだ生きていれば終了させたうえで、{@code starter} を使って新しい
     * プロセスを起動し、その参照を記録する。この一連の操作全体を排他制御することで、
     * 並行呼び出しがあっても「終了させ損ねた孤児プロセス」が発生しないようにする。
     *
     * @param starter 実際に {@code ProcessBuilder.start()} を呼ぶ処理
     * @return 起動された新しいプロセス
     * @throws IOException {@code starter} がプロセス起動に失敗した場合
     */
    public synchronized Process terminateAndStart(ProcessStarter starter) throws IOException {
        if (current != null && current.isAlive()) {
            current.destroy();
        }
        Process next = starter.start();
        current = next;
        return next;
    }

    /** {@link #terminateAndStart} に渡す、実際のプロセス起動処理。 */
    @FunctionalInterface
    public interface ProcessStarter {
        Process start() throws IOException;
    }
}
