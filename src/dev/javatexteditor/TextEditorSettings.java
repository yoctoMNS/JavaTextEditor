package dev.javatexteditor;

import java.nio.file.Path;

/**
 * エディタ全体の表示・挙動設定を集約するクラス。
 * 依存ライブラリを使わず、単純な static フィールド/メソッドで表現する。
 */
public final class TextEditorSettings {

    private TextEditorSettings() {}

    /** 起動直後、ステータス行に作業ディレクトリを表示するかどうか。 */
    public static final boolean SHOW_PWD_ON_STARTUP = false;

    /**
     * ステータス行に表示する作業ディレクトリの文言を組み立てる。
     * `:pwd` コマンド実行後、ステータス行中央に表示される。
     */
    public static String formatPwdStatusLine(Path workingDirectory) {
        return abbreviatePath(workingDirectory);
    }

    /** ホームディレクトリを ~ に置換して表示用文字列を返す。 */
    public static String abbreviatePath(Path p) {
        try {
            Path home = Path.of(System.getProperty("user.home", ""));
            Path rel  = home.relativize(p);
            return "~/" + rel.toString().replace('\\', '/');
        } catch (IllegalArgumentException ignored) {}
        return p.toString();
    }
}
