package dev.javatexteditor.app;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;

/**
 * 同一プロジェクトに対するエディタの多重起動を防ぐ。
 *
 * <p>プロジェクトルート直下に {@code .editor-lock} を作成し、{@link FileChannel#tryLock()}
 * による排他ロックを取得できたプロセスだけが起動を続行できる。ロックはOSのファイルロック
 * 機構そのものなので、JVMが {@code kill -9} 等で異常終了してもOSがプロセス終了時に
 * 自動的に解放する（明示的な解放処理が実行されなくても、次回起動時に再取得できる）。
 *
 * <p>TCP/UDPソケットによる多重起動防止（他プロセスとの通信用ポートを1つ占有する方式）ではなく
 * ファイルロックを選んだ理由: 外部ポートを消費せずファイルシステムのみで完結し、
 * CLAUDE.mdの「外部ライブラリ禁止・JDK標準APIのみ」という制約にも
 * {@code java.nio.channels} だけで自然に適合するため。
 *
 * <p>「プロジェクトごと」に区別する設計のため、ロックファイルの配置場所は
 * プロセス共有の固定パスではなく呼び出し側が渡す {@code projectRoot} 配下にしている。
 * 異なるプロジェクトを同時に開く操作（正当な利用）はこれにより妨げられない。
 */
public final class SingleInstanceGuard {

    private static final String LOCK_FILE_NAME = ".editor-lock";

    // tryLock() が返す FileLock はチャネルが閉じられると自動的に解放されてしまうため、
    // ローカル変数にせず static フィールドとして参照を保持し続ける（GC・スコープ離脱を防ぐ）。
    private static RandomAccessFile lockFileHandle;
    private static FileLock heldLock;

    private SingleInstanceGuard() {}

    /**
     * projectRoot 直下の {@value #LOCK_FILE_NAME} に対する排他ロックの取得を試みる。
     *
     * @return 取得できた（=このプロセスが当該プロジェクトの唯一のインスタンス）なら true。
     *         既に他プロセスが起動中で取得できなければ false。
     *         ロックファイル自体が作成できない環境（読み取り専用ファイルシステム等）では
     *         多重起動チェックを諦めて true を返す（fail-open。チェックの都合でエディタが
     *         一切起動できなくなる事態を避けるため）。
     */
    public static synchronized boolean tryAcquire(Path projectRoot) {
        Path lockPath = projectRoot.resolve(LOCK_FILE_NAME);
        try {
            RandomAccessFile raf = new RandomAccessFile(lockPath.toFile(), "rw");
            FileChannel channel = raf.getChannel();
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // 同一JVM内で既に取得済み（通常は到達しないが、念のため多重起動扱いにする）。
                raf.close();
                return false;
            }
            if (lock == null) {
                // 他プロセスが既に排他ロックを保持している = 既に起動中。
                raf.close();
                return false;
            }
            lockFileHandle = raf;
            heldLock = lock;
            Runtime.getRuntime().addShutdownHook(new Thread(SingleInstanceGuard::release, "editor-lock-release"));
            return true;
        } catch (IOException e) {
            System.err.println("[lock] ロックファイルを作成できないため多重起動チェックを省略します: "
                + lockPath + " (" + e.getMessage() + ")");
            return true;
        }
    }

    /**
     * 保持中のロックとファイルハンドルを解放する。シャットダウンフックから自動的に呼ばれるほか、
     * {@code :restart}（ModalEditor.performRestart()）が新しいプロセスを起動する前に明示的に
     * 呼ぶことで、旧プロセスがまだ終了しきっていない間でも新プロセスが即座にロックを取得できる
     * ようにする（呼び出し元の詳細はModalEditorのJavadoc参照）。二重に呼ばれても
     * null チェックにより安全（冪等）。
     */
    public static synchronized void release() {
        try {
            if (heldLock != null) heldLock.release();
        } catch (IOException ignored) {
        } finally {
            heldLock = null;
        }
        try {
            if (lockFileHandle != null) lockFileHandle.close();
        } catch (IOException ignored) {
        } finally {
            lockFileHandle = null;
        }
    }
}
