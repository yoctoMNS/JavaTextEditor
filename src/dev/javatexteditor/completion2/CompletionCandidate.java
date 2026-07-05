package dev.javatexteditor.completion2;

import java.util.Objects;

/**
 * 1件の補完候補。score が大きいほど優先表示される（カーソルからの近さの逆数的な指標）。
 */
public record CompletionCandidate(String text, int score) implements Comparable<CompletionCandidate> {

    public CompletionCandidate {
        Objects.requireNonNull(text, "text");
    }

    @Override
    public int compareTo(CompletionCandidate other) {
        int cmp = Integer.compare(other.score, this.score);
        if (cmp != 0) {
            return cmp;
        }
        return this.text.compareTo(other.text);
    }
}
