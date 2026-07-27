package dev.javatexteditor.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * COMMAND モードで打たれた文字列（{@code :} の後ろ）を、対応する処理へ振り分ける表。
 *
 * <p>コマンドには2種類ある。
 * <ul>
 *   <li><b>完全一致</b>（{@code :w}・{@code :qa!}・{@code :bnext} など）— 引数を取らない</li>
 *   <li><b>前置一致</b>（{@code :w path}・{@code :grep pattern} など）— 接頭辞の後ろが引数になる</li>
 * </ul>
 *
 * <p>振り分けは「完全一致をすべて調べる → 前置一致を登録順に調べる」の2段。
 * 完全一致のコマンド名は空白を含まず、前置一致の接頭辞は必ず空白で終わるため、
 * 両者は決して同じ文字列にマッチしない（＝どちらを先に調べても結果は変わらない）。
 *
 * <p>前置一致どうしは登録順が意味を持つ場合があるので、
 * {@link #onPrefix} は登録した順に評価される（{@code LinkedHashMap} / {@code ArrayList} を使う理由）。
 *
 * <p>このクラスは「どう振り分けるか」だけを知っており、個々のコマンドが何をするかは知らない。
 * コマンドの中身は {@code ModalEditor} 側で登録する。
 */
final class CommandRegistry {

    /** 接頭辞1つ分の登録。{@code handler} には接頭辞を取り除いて trim した引数が渡る。 */
    private record PrefixEntry(String prefix, Consumer<String> handler) {}

    private final Map<String, Runnable> exactCommands = new LinkedHashMap<>();
    private final List<PrefixEntry> prefixCommands = new ArrayList<>();

    /**
     * 完全一致のコマンドを登録する。同じ動作に複数の綴り（別名）を割り当てられる。
     * 例: {@code on(this::saveAll, "wa", "wall")}
     */
    void on(Runnable action, String... names) {
        for (String name : names) {
            exactCommands.put(name, action);
        }
    }

    /**
     * 前置一致のコマンドを登録する。接頭辞は空白で終わる形（{@code "grep "} 等）で渡す。
     * 登録した順に評価される。
     */
    void onPrefix(String prefix, Consumer<String> handler) {
        prefixCommands.add(new PrefixEntry(prefix, handler));
    }

    /**
     * コマンド文字列を対応する処理へ振り分ける。
     *
     * @return 対応する処理が見つかって実行したら true。見つからなければ何もせず false
     *         （呼び出し側が行番号ジャンプ等のフォールバックを試す）
     */
    boolean dispatch(String cmd) {
        Runnable exact = exactCommands.get(cmd);
        if (exact != null) {
            exact.run();
            return true;
        }
        for (PrefixEntry entry : prefixCommands) {
            if (cmd.startsWith(entry.prefix())) {
                entry.handler().accept(cmd.substring(entry.prefix().length()).trim());
                return true;
            }
        }
        return false;
    }
}
