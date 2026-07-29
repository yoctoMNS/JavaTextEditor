package dev.javatexteditor.analysis;

import java.util.List;

/**
 * Java のキーワードを補完候補として供給する。
 *
 * <p>IntelliJ IDEA の基本補完は識別子だけでなくキーワードも候補に出す
 * （{@code pub} と打てば {@code public} が出る）。本エディタの単語索引は
 * 「ソース中に出現した語」を集めるためキーワードも偶然含まれることが多いが、
 * 新規ファイルや空バッファでは出てこないため、独立した供給源として持つ。
 *
 * <p>文脈依存の絞り込み（{@code new} の後ろでは型名だけ、など）は
 * {@link CompletionContext.Kind} 単位の粗い判定にとどめる。
 * javac の構文木から「その位置に書けるキーワード」を厳密に求めることもできるが、
 * 書きかけのコードでは構文木が壊れていて役に立たないことが多く、
 * 候補が出たり出なかったりする方が使い勝手を損なうため。
 */
public final class JavaKeywords {

    private static final List<String> KEYWORDS = List.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "continue", "default", "do", "double", "else", "enum", "extends",
        "final", "finally", "float", "for", "if", "implements", "import", "instanceof",
        "int", "interface", "long", "native", "new", "package", "private", "protected",
        "public", "record", "return", "sealed", "short", "static", "strictfp", "super",
        "switch", "synchronized", "this", "throw", "throws", "transient", "try", "var",
        "void", "volatile", "while", "yield",
        "true", "false", "null"
    );

    private static final List<CompletionItem> ITEMS = KEYWORDS.stream()
        .map(k -> new CompletionItem(k, "kw", "", "", k, 0, null, CompletionItem.Origin.KEYWORD))
        .toList();

    private JavaKeywords() {}

    /**
     * 文脈に応じたキーワード候補。
     * メンバー補完（{@code obj.} の後）と {@code new } の後では、
     * キーワードは書けない・書いても意味がないため空リストを返す。
     */
    public static List<CompletionItem> forContext(CompletionContext.Kind kind) {
        return (kind == CompletionContext.Kind.PLAIN) ? ITEMS : List.of();
    }

    /** すべてのキーワード候補。 */
    public static List<CompletionItem> all() {
        return ITEMS;
    }
}
