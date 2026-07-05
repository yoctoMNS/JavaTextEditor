package dev.javatexteditor.completion2;

import java.util.List;
import java.util.Objects;

/**
 * 1回の補完開始(Alt+/)から確定/取消までの状態を保持するセッション。
 * currentIndex == -1 は「まだ候補に置き換えていない＝元のプレフィックスのまま」を表し、
 * 巡回でこの状態にも戻れるようにする（Vimのi_CTRL-Nが末尾まで巡回すると元の入力に戻るのに倣う）。
 */
public final class CompletionSession {

    private final int anchorOffset;
    private final String originalPrefix;
    private final List<CompletionCandidate> candidates;
    private int currentIndex;

    public CompletionSession(int anchorOffset, String originalPrefix, List<CompletionCandidate> candidates) {
        if (anchorOffset < 0) {
            throw new IllegalArgumentException("anchorOffset must be >= 0");
        }
        this.anchorOffset = anchorOffset;
        this.originalPrefix = Objects.requireNonNull(originalPrefix, "originalPrefix");
        this.candidates = List.copyOf(candidates);
        this.currentIndex = -1;
    }

    public int anchorOffset() {
        return anchorOffset;
    }

    public String originalPrefix() {
        return originalPrefix;
    }

    public List<CompletionCandidate> candidates() {
        return candidates;
    }

    public boolean hasCandidates() {
        return !candidates.isEmpty();
    }

    public int currentIndex() {
        return currentIndex;
    }

    public String currentText() {
        if (currentIndex < 0 || candidates.isEmpty()) {
            return originalPrefix;
        }
        return candidates.get(currentIndex).text();
    }

    public int currentLength() {
        return currentText().length();
    }

    /** 次候補へ進む（末尾の次は originalPrefix に戻り、その次でまた先頭候補に戻る）。 */
    public String advance() {
        if (candidates.isEmpty()) {
            return originalPrefix;
        }
        currentIndex = currentIndex + 1;
        if (currentIndex >= candidates.size()) {
            currentIndex = -1;
        }
        return currentText();
    }

    /** 前候補へ戻る（Shift+Alt+/）。先頭の前は originalPrefix、その前は末尾候補に戻る。 */
    public String retreat() {
        if (candidates.isEmpty()) {
            return originalPrefix;
        }
        currentIndex = currentIndex - 1;
        if (currentIndex < -1) {
            currentIndex = candidates.size() - 1;
        }
        return currentText();
    }
}
