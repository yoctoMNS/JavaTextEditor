package dev.javatexteditor.substitute;

import java.util.regex.Matcher;

/**
 * Vim式置換文字列（\1..\9=後方参照, &amp;=マッチ全体, バックスラッシュ+u/U/l/L/e/E=大文字小文字変換）を
 * 実際の置換テキストへ展開する。Java の {@code Matcher.replaceAll}/{@code $1} 構文は
 * 使わず、{@link Matcher} から1件ずつ {@code group(n)} を取り出して自前で組み立てる
 * （詳細は .claude/skills/vim-substitution/SKILL.md 参照）。
 */
public final class VimReplacementBuilder {

    private VimReplacementBuilder() {}

    public static String build(Matcher matched, String vimReplacement) {
        StringBuilder sb = new StringBuilder();
        char pendingCase = 0; // 'u' か 'l'（直後の1文字のみ）
        char rangeCase = 0;   // 'U' か 'L'（バックスラッシュ+e/E まで持続）
        int len = vimReplacement.length();

        for (int i = 0; i < len; i++) {
            char c = vimReplacement.charAt(i);

            if (c == '\\' && i + 1 < len) {
                char next = vimReplacement.charAt(i + 1);
                i++;
                switch (next) {
                    case 'u' -> pendingCase = 'u';
                    case 'l' -> pendingCase = 'l';
                    case 'U' -> rangeCase = 'U';
                    case 'L' -> rangeCase = 'L';
                    case 'e', 'E' -> { rangeCase = 0; pendingCase = 0; }
                    case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                        String group = groupSafe(matched, next - '0');
                        for (int k = 0; k < group.length(); k++) {
                            pendingCase = appendWithCase(sb, group.charAt(k), pendingCase, rangeCase);
                        }
                    }
                    default -> pendingCase = appendWithCase(sb, next, pendingCase, rangeCase);
                }
                continue;
            }

            if (c == '&') {
                String group = groupSafe(matched, 0);
                for (int k = 0; k < group.length(); k++) {
                    pendingCase = appendWithCase(sb, group.charAt(k), pendingCase, rangeCase);
                }
                continue;
            }

            pendingCase = appendWithCase(sb, c, pendingCase, rangeCase);
        }
        return sb.toString();
    }

    /** 1文字追加し、消費済みの pendingCase（常に 0 に戻る）を返す。 */
    private static char appendWithCase(StringBuilder sb, char c, char pendingCase, char rangeCase) {
        char effective = c;
        if (pendingCase == 'u') {
            effective = Character.toUpperCase(c);
        } else if (pendingCase == 'l') {
            effective = Character.toLowerCase(c);
        } else if (rangeCase == 'U') {
            effective = Character.toUpperCase(c);
        } else if (rangeCase == 'L') {
            effective = Character.toLowerCase(c);
        }
        sb.append(effective);
        return 0;
    }

    /** 存在しない・マッチしなかった（optionalな）グループは空文字列として扱う。 */
    private static String groupSafe(Matcher matched, int index) {
        if (index < 0 || index > matched.groupCount()) return "";
        String g = matched.group(index);
        return g == null ? "" : g;
    }
}
