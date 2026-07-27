package dev.javatexteditor.editor;

import java.io.File;
import java.nio.file.Path;

/**
 * ユーザーがコマンド行に打ち込んだパス文字列を、実際に読み書きできる絶対パスへ解決する純粋ロジック。
 *
 * <p>{@code :e}/{@code :w}（{@code :cd} の現在ディレクトリ基準）と、F10/F11/F12 の追加クラスパス
 * （{@code :pr} のビルドルート基準）は基準ディレクトリだけが異なり、解決の手順は同一である。
 * その手順をここに1つだけ置き、基準ディレクトリを引数で受け取ることで重複をなくしている。
 *
 * <p>ファイルシステムへのアクセスも {@link ModalEditor} の状態への依存も持たないため、
 * そのまま単体テストできる。
 */
final class UserPathResolver {

    private UserPathResolver() {}

    /**
     * 先頭の {@code ~} をホームディレクトリへ展開する。
     * OS に関係なく {@code ~}・{@code ~/...}・{@code ~\...} の3形を認識する。
     *
     * <p>{@link Path#resolve} は {@code ~} を特別扱いしないため、resolve に渡す前に
     * 文字列の段階で展開しておく必要がある。
     */
    static String expandHome(String pathStr) {
        if (pathStr.equals("~")) {
            return System.getProperty("user.home", "");
        }
        if (pathStr.startsWith("~/") || pathStr.startsWith("~\\")) {
            return System.getProperty("user.home", "") + File.separator + pathStr.substring(2);
        }
        return pathStr;
    }

    /**
     * {@code ~} を展開した上で、絶対パスならそのまま・相対パスなら {@code baseDir} を基準に
     * 絶対パス化して返す。
     *
     * <p>返り値を常に絶対パスに揃えるのは、FILER/telescope など他のファイルを開く経路が
     * いずれも絶対パスを {@code currentFilePath} に格納しており、形式を一致させないと
     * 「同じファイルなのに別バッファ扱いになる」等の不整合が起きるため。
     */
    static String resolveAgainst(Path baseDir, String pathSpec) {
        String expanded = expandHome(pathSpec);
        Path target = Path.of(expanded);
        if (target.isAbsolute()) {
            return target.toString();
        }
        return baseDir.resolve(expanded).toAbsolutePath().toString();
    }
}
