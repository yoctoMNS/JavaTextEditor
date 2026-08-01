package dev.javatexteditor.substitute;

/**
 * Vim のデフォルト（magic）モードの正規表現記法を Java の {@link java.util.regex.Pattern} 記法へ変換する。
 * very magic（\v）・very nomagic（\V）・nomagic モードは対象外（詳細は
 * .claude/skills/vim-substitution/SKILL.md 参照）。
 */
public final class VimRegexTranslator {

    private VimRegexTranslator() {}

    /** 未エスケープ状態でJavaの正規表現上リテラルとして扱う必要がある記号（Vim magicでは特殊文字だが素のままだとリテラル）。 */
    private static final String NEEDS_ESCAPE_WHEN_LITERAL = "()+?{|";

    public static String translate(String vimPattern) {
        StringBuilder out = new StringBuilder();
        int len = vimPattern.length();
        boolean inClass = false;

        for (int i = 0; i < len; i++) {
            char c = vimPattern.charAt(i);

            if (inClass) {
                out.append(c);
                if (c == ']') inClass = false;
                continue;
            }
            if (c == '[') {
                inClass = true;
                out.append(c);
                continue;
            }

            if (c == '\\' && i + 1 < len) {
                char next = vimPattern.charAt(i + 1);
                switch (next) {
                    case '(', ')', '+', '|' -> out.append(next);
                    case '?', '=' -> out.append('?');
                    case '{' -> out.append('{');
                    case '<', '>' -> out.append("\\b");
                    case 'd', 'w', 's', 'D', 'W', 'S', 'b', 'B', 'n', 't' -> out.append('\\').append(next);
                    case '.', '*', '^', '$', '[', ']', '\\' -> out.append('\\').append(next);
                    default -> out.append(next);
                }
                i++;
                continue;
            }

            if (NEEDS_ESCAPE_WHEN_LITERAL.indexOf(c) >= 0) {
                out.append('\\').append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
