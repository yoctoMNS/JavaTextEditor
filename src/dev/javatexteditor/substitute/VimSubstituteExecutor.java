package dev.javatexteditor.substitute;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@link VimRegexTranslator} と {@link VimReplacementBuilder} を組み合わせ、範囲内の各行に対して
 * マッチ・置換を行う。一括実行（{@link #execute}、フラグ g/i 用）と、1件ずつ確認しながら進める
 * 確認セッション（{@link #beginConfirm}、フラグ c 用）の2種類の入口を持つ。
 * 詳細は .claude/skills/vim-substitution/SKILL.md 参照。
 */
public final class VimSubstituteExecutor {

    private VimSubstituteExecutor() {}

    public record LineChange(int row, String newText, int matchCount) {}

    public record Result(List<LineChange> changes, int totalReplacements, int linesChanged, int lastChangedRow) {}

    /** pattern が不正な正規表現の場合にスローされる。 */
    public static final class InvalidPatternException extends RuntimeException {
        public InvalidPatternException(String message, Throwable cause) { super(message, cause); }
    }

    private static Pattern compile(String javaRegex, boolean ignoreCase) {
        try {
            return Pattern.compile(javaRegex, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException ex) {
            throw new InvalidPatternException(ex.getMessage(), ex);
        }
    }

    /**
     * [r1, r2]（0始まり・両端含む）の各行を一括置換する（確認なし）。
     * javaRegex は呼び出し側が {@link VimRegexTranslator#translate} 済みのJava正規表現文字列を渡す
     * （直前検索パターンの再利用時など、既にJava構文であるケースを呼び出し側が判断できるようにするため、
     * このクラス自身はVim magic変換を行わない）。
     */
    public static Result execute(String[] lines, int r1, int r2, String javaRegex, String vimReplacement,
                                  boolean global, boolean ignoreCase) {
        Pattern pattern = compile(javaRegex, ignoreCase);
        List<LineChange> changes = new ArrayList<>();
        int total = 0;
        int lastChangedRow = -1;

        int maxRow = Math.max(0, lines.length - 1);
        int start = Math.max(0, Math.min(r1, maxRow));
        int end = Math.max(0, Math.min(r2, maxRow));

        for (int row = start; row <= end && row < lines.length; row++) {
            String line = lines[row];
            StringBuilder out = new StringBuilder();
            int pos = 0;
            int countInLine = 0;
            Matcher m = pattern.matcher(line);
            while (pos <= line.length() && m.find(pos)) {
                out.append(line, pos, m.start());
                String proposed = VimReplacementBuilder.build(m, vimReplacement);
                out.append(proposed);
                countInLine++;
                pos = m.end();
                if (m.end() == m.start()) {
                    if (pos < line.length()) out.append(line.charAt(pos));
                    pos++;
                }
                if (!global) break;
            }
            if (countInLine == 0) continue;
            out.append(line, Math.min(pos, line.length()), line.length());
            changes.add(new LineChange(row, out.toString(), countInLine));
            total += countInLine;
            lastChangedRow = row;
        }
        return new Result(changes, total, changes.size(), lastChangedRow);
    }

    /**
     * c フラグ用: 1件ずつ確認しながら進める確認セッションを開始する。
     * javaRegex は {@link #execute} 同様、呼び出し側が変換済みのJava正規表現文字列を渡す。
     */
    public static ConfirmSession beginConfirm(String[] lines, int r1, int r2, String javaRegex, String vimReplacement,
                                               boolean global, boolean ignoreCase) {
        Pattern pattern = compile(javaRegex, ignoreCase);
        int maxRow = Math.max(0, lines.length - 1);
        int start = Math.max(0, Math.min(r1, maxRow));
        int end = Math.max(0, Math.min(r2, maxRow));
        return new ConfirmSession(lines.clone(), start, end, pattern, vimReplacement, global);
    }

    /**
     * :s ... /c 用の確認セッション。{@link #advance()} で次の一致を探し、
     * yes/no/all/quit のいずれかを適用しながら進める（Vim の y/n/a/q 相当）。
     */
    public static final class ConfirmSession {
        private final String[] lines;
        private final int r2;
        private final Pattern pattern;
        private final String vimReplacement;
        private final boolean global;
        private final Set<Integer> changedRows = new TreeSet<>();

        private int row;
        private int searchFrom = 0;
        private boolean hasPending = false;
        private boolean finished = false;
        private int pendingStart, pendingEnd;
        private String pendingMatchText = "";
        private String pendingProposed = "";

        private int totalReplacements = 0;
        private int lastChangedRow = -1;

        private ConfirmSession(String[] lines, int r1, int r2, Pattern pattern, String vimReplacement, boolean global) {
            this.lines = lines;
            this.row = r1;
            this.r2 = r2;
            this.pattern = pattern;
            this.vimReplacement = vimReplacement;
            this.global = global;
        }

        /** 次の一致を探す。見つかれば true（pending* に情報がセットされる）、無ければ false（セッション終了）。 */
        public boolean advance() {
            if (finished) return false;
            while (row <= r2 && row < lines.length) {
                String line = lines[row];
                if (searchFrom <= line.length()) {
                    Matcher m = pattern.matcher(line);
                    if (m.find(searchFrom)) {
                        pendingStart = m.start();
                        pendingEnd = m.end();
                        pendingMatchText = m.group();
                        pendingProposed = VimReplacementBuilder.build(m, vimReplacement);
                        hasPending = true;
                        return true;
                    }
                }
                row++;
                searchFrom = 0;
            }
            hasPending = false;
            finished = true;
            return false;
        }

        public boolean hasPending() { return hasPending; }
        public boolean isFinished() { return finished && !hasPending; }
        public int pendingRow() { return row; }
        public String pendingMatchText() { return pendingMatchText; }
        public String pendingProposedText() { return pendingProposed; }

        /** 現在の一致を置換して次へ進む。 */
        public void applyYes() {
            if (!hasPending) return;
            String line = lines[row];
            lines[row] = line.substring(0, pendingStart) + pendingProposed + line.substring(pendingEnd);
            changedRows.add(row);
            totalReplacements++;
            lastChangedRow = row;
            int newSearchFrom = pendingStart + pendingProposed.length();
            hasPending = false;
            if (!global) {
                row++;
                searchFrom = 0;
            } else {
                searchFrom = (newSearchFrom == pendingStart && pendingEnd == pendingStart)
                        ? pendingStart + 1 : newSearchFrom;
            }
        }

        /** 現在の一致をスキップして次へ進む。 */
        public void applyNo() {
            if (!hasPending) return;
            hasPending = false;
            if (!global) {
                row++;
                searchFrom = 0;
            } else {
                searchFrom = (pendingEnd == pendingStart) ? pendingEnd + 1 : pendingEnd;
            }
        }

        /** 現在以降の一致を全て確認なしで置換する。 */
        public void applyAllRemaining() {
            if (hasPending) applyYes();
            while (advance()) {
                applyYes();
            }
        }

        /** 確認を中断する（以降の一致は変更しない）。 */
        public void quit() {
            hasPending = false;
            finished = true;
        }

        public String lineAt(int row) { return lines[row]; }
        public Set<Integer> changedRows() { return changedRows; }
        public int totalReplacements() { return totalReplacements; }
        public int linesChanged() { return changedRows.size(); }
        public int lastChangedRow() { return lastChangedRow; }
    }
}
