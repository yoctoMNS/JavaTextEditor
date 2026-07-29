package dev.javatexteditor.editor;

/**
 * ステータスバーへ表示する文字列から、フルパスをファイル名/フォルダ名（末端要素）のみへ
 * 短縮する純粋ロジック。
 *
 * <p>ステータスバーの表示領域は限られているため、絶対パスをそのまま埋め込むと
 * 本来伝えたいメッセージ本文が見切れてしまう。ログ・デバッグ出力等、フルパスが
 * 必要な箇所には適用しないこと（対象はあくまでステータスバー表示のみ）。
 *
 * <p>{@code /} と {@code \} の両方を区切り文字として扱う。プラットフォーム依存の
 * {@link java.io.File#separator} や {@link java.nio.file.Path} を使わないのは、
 * 実行環境と異なる区切り文字のパス文字列（例: Windows上で作られたパスをLinuxで表示する
 * ログ等）が渡されても末端要素を取り出せるようにするため。
 */
final class PathDisplay {

    private PathDisplay() {}

    /**
     * パス文字列の末端要素（ファイル名またはフォルダ名）のみを返す。
     * 区切り文字を含まない場合はそのまま返す。末尾が区切り文字の場合は、
     * その手前の要素を返す（フォルダパス末尾に {@code /} が付く場合を考慮）。
     */
    static String baseName(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        int end = path.length();
        while (end > 0 && (path.charAt(end - 1) == '/' || path.charAt(end - 1) == '\\')) {
            end--;
        }
        if (end == 0) {
            return path;
        }
        int lastSlash = Math.max(path.lastIndexOf('/', end - 1), path.lastIndexOf('\\', end - 1));
        return path.substring(lastSlash + 1, end);
    }
}
