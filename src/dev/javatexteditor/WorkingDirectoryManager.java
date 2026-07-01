package dev.javatexteditor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * アプリケーション全体の「作業ディレクトリ」を管理する。
 *
 * 初期値の決定順（上が優先）:
 *   1. コンストラクタに渡したヒント（初期ファイルの親ディレクトリ等）
 *   2. ユーザーのホームディレクトリ
 *   3. JVM 起動ディレクトリ（user.dir）
 *
 * 以前は Preferences に前回の作業ディレクトリを永続化し、次回起動時の既定値として
 * 使っていたが、「常にホームディレクトリから開始する」という要件と衝突する
 * （一度 :cd で他のディレクトリに移動すると、以降ずっとそこが既定値になってしまう）
 * ため廃止した。setWorkingDirectory() は現在プロセス内の状態変更のみを行う。
 *
 * setWorkingDirectory() はバリデーション・リスナー通知を一括で行う。
 */
public final class WorkingDirectoryManager {

    private Path workingDirectory;
    private final List<Consumer<Path>> listeners = new ArrayList<>();

    /**
     * @param hint 優先したい初期ディレクトリ。無効または null の場合は次の候補へ降順。
     */
    public WorkingDirectoryManager(Path hint) {
        this.workingDirectory = resolve(hint);
    }

    private static Path resolve(Path hint) {
        if (isValidDir(hint)) return hint.toAbsolutePath().normalize();

        Path home = Paths.get(System.getProperty("user.home", ""));
        if (isValidDir(home)) return home;

        return Paths.get(System.getProperty("user.dir"));
    }

    private static boolean isValidDir(Path p) {
        return p != null && Files.isDirectory(p);
    }

    /** 現在の作業ディレクトリを返す（null は返さない）。 */
    public Path getWorkingDirectory() { return workingDirectory; }

    /**
     * 作業ディレクトリを変更する。バリデーション・永続化・リスナー通知を一括で行う。
     *
     * @return null = 成功、それ以外 = 日本語エラーメッセージ
     */
    public String setWorkingDirectory(Path path) {
        if (path == null)                    return "パスが null です";
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized))       return "ディレクトリが存在しません: " + normalized;
        if (!Files.isDirectory(normalized))  return "ディレクトリではありません: " + normalized;
        if (!Files.isReadable(normalized))   return "読み取り権限がありません: " + normalized;
        workingDirectory = normalized;
        for (Consumer<Path> l : listeners) l.accept(normalized);
        return null;
    }

    /** 作業ディレクトリが変更されたときに呼ばれるリスナーを追加する。 */
    public void addChangeListener(Consumer<Path> listener) { listeners.add(listener); }
}
