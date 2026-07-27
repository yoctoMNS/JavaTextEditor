package dev.javatexteditor;

import dev.javatexteditor.telescope.BufferPicker.BufferEntry;
import java.util.ArrayList;
import java.util.List;

/**
 * これまでに開いたファイルの一覧。SPC+b（バッファ選択）と Ctrl+U / Ctrl+P（{@code :bprev}/{@code :bnext}）が
 * 巡回する対象になる。
 *
 * <p>全ペインで共有される1つの一覧なので、どのペインでファイルを開いても同じ一覧に載る。
 * 一方 {@code *compile*} / {@code *run*} の疑似バッファはディスクに実体を持たず、生成したペインでしか
 * 内容を取り出せないため、この一覧には載せず {@code ModalEditor} 側のキャッシュで扱う
 * （詳細は CLAUDE.md「F10/F11 の *compile* / *run* 疑似バッファを統一バッファ一覧に統合」節）。
 *
 * <p>ファイルを開く処理はバックグラウンドスレッドからも呼ばれうるため、
 * すべての操作を {@code synchronized} で直列化している。
 * {@link #entries()} は写しを返すので、呼び出し側が反復中に他スレッドが追加しても壊れない。
 *
 * <p>同一性はファイルパスで判断する。パスを持たないバッファ（{@code :enew} 等）は登録しない。
 */
public final class BufferRegistry {

    private final List<BufferEntry> entries = new ArrayList<>();

    /** 開いたファイルを一覧に加える。同じパスが既にあれば何もしない。 */
    public synchronized void register(BufferEntry entry) {
        if (entry.filePath() == null) return;
        for (BufferEntry e : entries) {
            if (entry.filePath().equals(e.filePath())) return;
        }
        entries.add(entry);
    }

    /** 一覧から取り除く（SPC+b 内の Ctrl+D で閉じたとき）。 */
    public synchronized void unregister(BufferEntry entry) {
        if (entry.filePath() == null) return;
        entries.removeIf(e -> entry.filePath().equals(e.filePath()));
    }

    /** 現在の一覧の写し。登録順（＝開いた順）に並ぶ。 */
    public synchronized List<BufferEntry> entries() {
        return new ArrayList<>(entries);
    }
}
