package dev.javatexteditor.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * バッファ内文字列検索（{@code /}・{@code *}・{@code #}・{@code n}・{@code N}）の
 * 「どこが一致したか」「カーソルから見てどれを選ぶか」だけを計算する純粋ロジック。
 *
 * <p>バッファもカーソルもステータス行も持たず、引数として受け取ったテキストと
 * オフセットだけで完結するため、そのまま単体テストできる。
 *
 * <p>一致位置は {@code int[]{offset, length}} で表す。これは
 * {@code ModalEditor.getSearchMatches()} が公開している既存の表現に合わせたもので、
 * この形式のまま呼び出し側へ返すことで公開シグネチャを変えずに済ませている。
 */
final class BufferTextSearch {

    private BufferTextSearch() {}

    /**
     * テキスト全体から一致箇所をすべて集める。
     *
     * <p>長さ0の一致（{@code ^} や {@code \b} のようなゼロ幅パターン）は、
     * 画面上でハイライトできるよう長さ1として扱う。
     *
     * @return 出現順（オフセット昇順）の {@code {offset, length}} 一覧。1件も無ければ空リスト
     */
    static List<int[]> findAll(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        List<int[]> matches = new ArrayList<>();
        while (matcher.find()) {
            int length = matcher.end() - matcher.start();
            matches.add(new int[]{matcher.start(), length > 0 ? length : 1});
        }
        return matches;
    }

    /**
     * カーソル位置から見て「次」に当たる一致を選ぶ。
     *
     * <p>前方検索ならカーソルより後ろの最初の一致、後方検索ならカーソルより前の最後の一致。
     * その方向に一致が無ければ Vim と同じくファイル端で折り返す
     * （前方検索なら先頭の一致、後方検索なら末尾の一致）。
     *
     * @return 選ばれた一致のインデックス。{@code matches} が空なら -1
     */
    static int selectNearest(List<int[]> matches, int cursorOffset, boolean forward) {
        if (matches.isEmpty()) return -1;
        if (forward) {
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i)[0] > cursorOffset) return i;
            }
            return 0; // 折り返して先頭へ
        }
        for (int i = matches.size() - 1; i >= 0; i--) {
            if (matches.get(i)[0] < cursorOffset) return i;
        }
        return matches.size() - 1; // 折り返して末尾へ
    }

    /**
     * {@code n}/{@code N} 用に、現在位置から1つ隣の一致へ循環的に進める。
     *
     * @return 進めた後のインデックス。{@code matches} が空なら -1
     */
    static int step(List<int[]> matches, int currentIdx, boolean forward) {
        if (matches.isEmpty()) return -1;
        int size = matches.size();
        return forward
                ? (currentIdx + 1) % size
                : (currentIdx - 1 + size) % size;
    }
}
