package dev.javatexteditor.app;

/**
 * {@link RunningProcessHolderTest} 専用の子プロセス役。
 * 起動されたことを1行出力してから、引数で指定したミリ秒だけ生存し続ける
 * （テスト対象の {@code Process} が「生きている」ことを確認できる時間を確保するため）。
 */
public class SleepyMain {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("STARTED");
        long ms = args.length > 0 ? Long.parseLong(args[0]) : 5000L;
        Thread.sleep(ms);
    }
}
