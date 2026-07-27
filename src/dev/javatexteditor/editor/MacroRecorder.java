package dev.javatexteditor.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vim 式マクロ（{@code q{register}} で記録・{@code @{register}} で再生・{@code @@} で直前再実行）の
 * 記録内容と再生状態だけを保持するクラス。
 *
 * <p>このクラスは「どのキーを覚えていて、どの順に再投入するか」だけを知っており、
 * どのキーがマクロ終了キーになるか（NORMAL モードの {@code q}）・失敗時にどんな文言を
 * ステータス行へ出すかといったエディタ側の都合は一切持たない。
 * 再生は {@link KeyReplayer} 経由でキーを1つずつ呼び出し側へ差し戻すだけである。
 *
 * <p>ヤンクレジスタ（{@code y}/{@code p} が使う {@code yankRegister}）とは完全に独立した、
 * マクロ専用のレジスタ領域を持つ。
 */
final class MacroRecorder {

    /** 記録された生のキー入力1つ分。 */
    record RecordedKey(int keyCode, char keyChar, int modifiers) {}

    /** 記録済みキーを再生するための差し戻し口。通常は {@code ModalEditor::processKey}。 */
    @FunctionalInterface
    interface KeyReplayer {
        void replay(int keyCode, char keyChar, int modifiers);
    }

    /** 再生要求の結果。ステータス行に出す文言は呼び出し側が決める。 */
    enum PlayOutcome {
        /** 再生した（再生されたキー自身がステータス行を更新する）。 */
        PLAYED,
        /** 指定レジスタに記録が無い。 */
        EMPTY_REGISTER,
        /** {@code @@} を押したが、まだ一度もマクロを再生していない。 */
        NO_PREVIOUS_MACRO,
        /** 入れ子再生が深すぎるため中断した（無限再帰ガード）。 */
        RECURSION_LIMIT_REACHED
    }

    /** マクロが自分自身を呼び続けた場合に打ち切る深さ。 */
    private static final int MAX_REPLAY_DEPTH = 1000;

    private final KeyReplayer keyReplayer;

    private boolean recording = false;
    private char recordingRegister;
    private final List<RecordedKey> recordBuffer = new ArrayList<>();
    private final Map<Character, List<RecordedKey>> registers = new HashMap<>();
    private char lastPlayedRegister = '\0';
    private int replayDepth = 0;

    MacroRecorder(KeyReplayer keyReplayer) {
        this.keyReplayer = keyReplayer;
    }

    boolean isRecording() {
        return recording;
    }

    /** 記録中のキーが、いま再生によって内部生成されたものかどうか。 */
    boolean isReplaying() {
        return replayDepth > 0;
    }

    /** 記録中のレジスタ名（常に小文字）。記録していないときの値は意味を持たない。 */
    char recordingRegister() {
        return recordingRegister;
    }

    char lastPlayedRegister() {
        return lastPlayedRegister;
    }

    /**
     * 記録を開始する。レジスタ名が小文字なら新規記録、大文字なら同名（小文字）の
     * 既存内容に続けて追記する。
     */
    void startRecording(char register) {
        char normalized = Character.toLowerCase(register);
        recordBuffer.clear();
        if (Character.isUpperCase(register)) {
            List<RecordedKey> existing = registers.get(normalized);
            if (existing != null) recordBuffer.addAll(existing);
        }
        recording = true;
        recordingRegister = normalized;
    }

    /** 記録を終了し、記録中のレジスタへ確定させる。 */
    void stopRecording() {
        registers.put(recordingRegister, List.copyOf(recordBuffer));
        recording = false;
    }

    /**
     * 記録中であればこのキーを書き留める。
     * 再生によって内部生成されたキーは、二重展開を避けるため書き留めない。
     */
    void captureIfRecording(int keyCode, char keyChar, int modifiers) {
        if (!recording || isReplaying()) return;
        recordBuffer.add(new RecordedKey(keyCode, keyChar, modifiers));
    }

    /** 指定レジスタのマクロを再生する。 */
    PlayOutcome play(char register) {
        List<RecordedKey> keys = registers.get(register);
        if (keys == null || keys.isEmpty()) {
            return PlayOutcome.EMPTY_REGISTER;
        }
        lastPlayedRegister = register;
        return replay(keys);
    }

    /** 直前に再生したマクロをもう一度再生する（{@code @@}）。 */
    PlayOutcome replayLast() {
        if (lastPlayedRegister == '\0') {
            return PlayOutcome.NO_PREVIOUS_MACRO;
        }
        return play(lastPlayedRegister);
    }

    boolean hasMacro(char register) {
        List<RecordedKey> keys = registers.get(Character.toLowerCase(register));
        return keys != null && !keys.isEmpty();
    }

    int macroLength(char register) {
        List<RecordedKey> keys = registers.get(Character.toLowerCase(register));
        return keys == null ? 0 : keys.size();
    }

    /** 記録済みキー列を呼び出し側へ1つずつ差し戻す。無限再帰は深さ上限で打ち切る。 */
    private PlayOutcome replay(List<RecordedKey> keys) {
        if (replayDepth >= MAX_REPLAY_DEPTH) {
            return PlayOutcome.RECURSION_LIMIT_REACHED;
        }
        replayDepth++;
        try {
            for (RecordedKey key : keys) {
                keyReplayer.replay(key.keyCode(), key.keyChar(), key.modifiers());
            }
        } finally {
            replayDepth--;
        }
        return PlayOutcome.PLAYED;
    }
}
