package dev.javatexteditor.substitute;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * COMMAND モードの文字列（例: "%s/foo/bar/gc"）を範囲指定・パターン・置換文字列・フラグに分解する。
 * 範囲の実際の行番号への解決（'&lt;,'&gt; の Visual 選択やカーソル行の参照など、エディタの
 * 状態が必要な部分）は呼び出し側（ModalEditor）が {@link RangeSpec} を見て行う。
 * 詳細は .claude/skills/vim-substitution/SKILL.md 参照。
 */
public final class VimSubstituteCommandParser {

    public sealed interface RangeSpec permits WholeFile, CurrentLine, VisualRange, LineRange, CursorPlus {}

    /** {@code :%s} */
    public record WholeFile() implements RangeSpec {}
    /** 範囲指定なし（カーソル行のみ）。 */
    public record CurrentLine() implements RangeSpec {}
    /** {@code :'<,'>s} — 直前の Visual 選択範囲。 */
    public record VisualRange() implements RangeSpec {}
    /** {@code :N,Ms}（1始まり・両端含む）。 */
    public record LineRange(int startLine1, int endLine1) implements RangeSpec {}
    /** {@code :.,+Ns} — カーソル行から N 行先まで。 */
    public record CursorPlus(int offset) implements RangeSpec {}

    public record ParseResult(RangeSpec range, String pattern, String replacement, String flags) {}

    private static final Pattern NUMERIC_RANGE = Pattern.compile("^(\\d+),(\\d+)(s.*)$");
    private static final Pattern CURSOR_PLUS_RANGE = Pattern.compile("^\\.,\\+(\\d+)(s.*)$");

    private VimSubstituteCommandParser() {}

    /** cmd が置換コマンドの形でなければ空を返す。 */
    public static Optional<ParseResult> parse(String cmd) {
        RangeSpec range;
        String sPart;

        if (cmd.startsWith("%")) {
            range = new WholeFile();
            sPart = cmd.substring(1);
        } else if (cmd.startsWith("'<,'>")) {
            range = new VisualRange();
            sPart = cmd.substring(5);
        } else {
            Matcher cursorPlus = CURSOR_PLUS_RANGE.matcher(cmd);
            Matcher numeric = NUMERIC_RANGE.matcher(cmd);
            if (cursorPlus.matches()) {
                range = new CursorPlus(Integer.parseInt(cursorPlus.group(1)));
                sPart = cursorPlus.group(2);
            } else if (numeric.matches()) {
                range = new LineRange(Integer.parseInt(numeric.group(1)), Integer.parseInt(numeric.group(2)));
                sPart = numeric.group(3);
            } else {
                range = new CurrentLine();
                sPart = cmd;
            }
        }

        if (!looksLikeSubstitute(sPart)) return Optional.empty();

        char delimiter = sPart.charAt(1);
        String[] parts = sPart.substring(2).split(Pattern.quote(String.valueOf(delimiter)), 3);
        String pattern = parts.length > 0 ? parts[0] : "";
        String replacement = parts.length > 1 ? parts[1] : "";
        String flags = parts.length > 2 ? parts[2] : "";
        return Optional.of(new ParseResult(range, pattern, replacement, flags));
    }

    /** sPart が "s" + 区切り文字（英数字・空白以外の1文字）から始まる置換コマンドの形か判定する。 */
    private static boolean looksLikeSubstitute(String sPart) {
        if (sPart.length() < 2 || sPart.charAt(0) != 's') return false;
        char delim = sPart.charAt(1);
        return !Character.isLetterOrDigit(delim) && !Character.isWhitespace(delim);
    }
}
