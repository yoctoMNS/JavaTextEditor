package dev.javatexteditor.analysis;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * レシーバ変数（例: "obj.method()" の "obj"）の宣言型を軽量な正規表現ヒューリスティックで推定する。
 *
 * javac による厳密な型解決（スコープ解析・型推論）は行わない。数十万行規模のファイルでも
 * K キー1回の押下ごとに軽く動作させるため、および CLAUDE.md の「型解決は行わない
 * （parse-only）」という既存方針を踏襲するため、意図的にテキストパターンマッチのみで実装する。
 *
 * 探索対象は「Type varName」の形が現れる宣言（ローカル変数・拡張for文・メソッド引数・
 * フィールド宣言）。カーソル行から上方向に近い順に走査し、最初に見つかった宣言を採用する
 * （厳密なスコープ判定はしないが、"直近の宣言が最も有力" という近似で実用上十分）。
 */
public class ReceiverTypeResolver {

    /** 「Type varName =/;/,/)/:」の直前トークンが型として誤検出されやすい予約語。 */
    private static final Set<String> NON_TYPE_KEYWORDS = Set.of(
        "return", "new", "throw", "else", "yield", "case", "assert",
        "instanceof", "catch", "do", "while", "for", "if", "switch",
        "try", "finally", "break", "continue", "super", "this",
        "synchronized", "import", "package"
    );

    /**
     * lines[0..cursorRow] を近い順（cursorRow から 0 へ）に走査し、varName の宣言型を推定する。
     * ジェネリクス（{@code List<String>}）・配列（{@code MyClass[]}）は基底の型名に正規化する。
     * 見つからなければ Optional.empty()。
     */
    public Optional<String> resolveType(String[] lines, int cursorRow, String varName) {
        if (varName == null || varName.isEmpty() || lines == null) {
            return Optional.empty();
        }
        Pattern decl = Pattern.compile(
            "(?:^|[^.\\w])([A-Za-z_$][A-Za-z0-9_$]*(?:<[^>]*>)?(?:\\[\\])*)\\s+"
            + Pattern.quote(varName) + "\\b\\s*(?:=|;|,|\\)|:)");

        int start = Math.min(cursorRow, lines.length - 1);
        for (int row = start; row >= 0; row--) {
            String line = lines[row];
            if (line == null) continue;
            Matcher m = decl.matcher(line);
            String lastOnLine = null;
            while (m.find()) {
                String candidate = m.group(1);
                if (!NON_TYPE_KEYWORDS.contains(candidate)) {
                    lastOnLine = candidate;
                }
            }
            if (lastOnLine != null) {
                return Optional.of(stripGenericsAndArray(lastOnLine));
            }
        }
        return Optional.empty();
    }

    private String stripGenericsAndArray(String type) {
        int lt = type.indexOf('<');
        String base = lt >= 0 ? type.substring(0, lt) : type;
        return base.replace("[]", "");
    }
}
