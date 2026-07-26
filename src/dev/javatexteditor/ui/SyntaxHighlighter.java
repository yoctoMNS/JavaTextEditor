package dev.javatexteditor.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Java/Cソースの1行を正規表現ベースではなく単純な逐次スキャンでトークン分類する、
 * Swing非依存の純粋ロジック。gr/:grep等の既存の正規表現ヒューリスティックと同様、
 * 型解決を伴う厳密な分類は行わない（識別子の見た目だけで型かどうかを判定する等）。
 *
 * ブロックコメント（/* ... * /）は複数行にまたがるため、呼び出し側は
 * computeBlockCommentStarts() で文書全体を1回だけ走査し、各行が「ブロックコメントの
 * 内側から始まるか」を事前計算してから、可視行だけ tokenizeLine() で都度トークン化する
 * という2段構成を取る（数十万行規模のファイルで毎フレーム全文書をトークン化しないため）。
 */
public final class SyntaxHighlighter {
    private SyntaxHighlighter() {}

    public record LineResult(List<SyntaxToken> tokens, boolean endsInBlockComment) {}

    private static final Set<String> JAVA_KEYWORDS = Set.of(
        "abstract", "assert", "break", "case", "catch", "class", "const", "continue",
        "default", "do", "else", "enum", "extends", "final", "finally", "for", "goto",
        "if", "implements", "import", "instanceof", "interface", "new", "package",
        "private", "protected", "public", "return", "static", "strictfp", "super",
        "switch", "synchronized", "this", "throw", "throws", "transient", "try",
        "volatile", "while", "var", "yield", "record", "sealed", "permits", "module",
        "requires", "exports", "opens", "uses", "provides", "transitive", "open",
        "true", "false", "null", "native"
    );

    private static final Set<String> JAVA_TYPES = Set.of(
        "void", "boolean", "byte", "short", "int", "long", "char", "float", "double"
    );

    private static final Set<String> C_KEYWORDS = Set.of(
        "auto", "break", "case", "const", "continue", "default", "do", "else", "enum",
        "extern", "for", "goto", "if", "inline", "register", "return", "sizeof",
        "static", "struct", "switch", "typedef", "union", "volatile", "while",
        "restrict", "class", "private", "protected", "public", "virtual", "friend",
        "template", "typename", "namespace", "using", "new", "delete", "this", "try",
        "catch", "throw", "operator", "explicit", "mutable", "constexpr", "noexcept",
        "override", "final", "decltype", "static_assert", "thread_local", "true",
        "false", "nullptr"
    );

    private static final Set<String> C_TYPES = Set.of(
        "void", "char", "short", "int", "long", "float", "double", "signed", "unsigned",
        "bool", "size_t", "wchar_t", "ssize_t", "ptrdiff_t",
        "int8_t", "int16_t", "int32_t", "int64_t",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t",
        "uintptr_t", "intptr_t", "FILE", "va_list"
    );

    /** 文書全体を1回走査し、各行がブロックコメントの内側から始まるかを事前計算する。 */
    public static boolean[] computeBlockCommentStarts(String[] lines, SourceLanguage lang) {
        boolean[] starts = new boolean[lines.length];
        boolean state = false;
        for (int i = 0; i < lines.length; i++) {
            starts[i] = state;
            state = tokenizeLine(lines[i], lang, state).endsInBlockComment();
        }
        return starts;
    }

    public static LineResult tokenizeLine(String line, SourceLanguage lang, boolean startInBlockComment) {
        int n = line.length();
        List<SyntaxToken> tokens = new ArrayList<>();
        if (lang == SourceLanguage.NONE) {
            tokens.add(new SyntaxToken(0, n, SyntaxKind.DEFAULT));
            return new LineResult(tokens, false);
        }

        int i = 0;

        if (startInBlockComment) {
            int close = line.indexOf("*/");
            if (close < 0) {
                tokens.add(new SyntaxToken(0, n, SyntaxKind.COMMENT));
                return new LineResult(tokens, true);
            }
            tokens.add(new SyntaxToken(0, close + 2, SyntaxKind.COMMENT));
            i = close + 2;
        }

        boolean preprocessorLine = (lang == SourceLanguage.C) && line.strip().startsWith("#");
        boolean preprocessorEmitted = false;

        while (i < n) {
            char c = line.charAt(i);

            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                tokens.add(new SyntaxToken(i, n, SyntaxKind.COMMENT));
                i = n;
                break;
            }
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                int close = line.indexOf("*/", i + 2);
                if (close < 0) {
                    tokens.add(new SyntaxToken(i, n, SyntaxKind.COMMENT));
                    return new LineResult(tokens, true);
                }
                tokens.add(new SyntaxToken(i, close + 2, SyntaxKind.COMMENT));
                i = close + 2;
                continue;
            }
            if (preprocessorLine && !preprocessorEmitted && c == '#') {
                tokens.add(new SyntaxToken(i, n, SyntaxKind.PREPROCESSOR));
                preprocessorEmitted = true;
                i = n;
                break;
            }
            if (c == '"') {
                int j = i + 1;
                while (j < n) {
                    if (line.charAt(j) == '\\' && j + 1 < n) { j += 2; continue; }
                    if (line.charAt(j) == '"') { j++; break; }
                    j++;
                }
                tokens.add(new SyntaxToken(i, j, SyntaxKind.STRING));
                i = j;
                continue;
            }
            if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    if (line.charAt(j) == '\\' && j + 1 < n) { j += 2; continue; }
                    if (line.charAt(j) == '\'') { j++; break; }
                    j++;
                }
                tokens.add(new SyntaxToken(i, j, SyntaxKind.STRING));
                i = j;
                continue;
            }
            if (Character.isDigit(c)) {
                int j = scanNumber(line, i, n);
                tokens.add(new SyntaxToken(i, j, SyntaxKind.NUMBER));
                i = j;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int j = i + 1;
                while (j < n && Character.isJavaIdentifierPart(line.charAt(j))) j++;
                String word = line.substring(i, j);
                tokens.add(new SyntaxToken(i, j, classifyIdentifier(word, lang)));
                i = j;
                continue;
            }
            tokens.add(new SyntaxToken(i, i + 1, classifyPunctuation(c)));
            i++;
        }

        return new LineResult(tokens, false);
    }

    // 演算子（算術・比較・代入・論理等）。複数文字演算子（==、&&、->等）も1文字ずつ
    // OPERATOR判定されるため、隣接する同種トークンとして視覚上は問題なくつながる。
    private static final String OPERATOR_CHARS = "+-*/%=<>!&|^~?";
    // 区切り記号（括弧・カンマ・セミコロン・ドット等）。
    private static final String SYMBOL_CHARS = "(){}[];,.:@";

    private static SyntaxKind classifyPunctuation(char c) {
        if (OPERATOR_CHARS.indexOf(c) >= 0) return SyntaxKind.OPERATOR;
        if (SYMBOL_CHARS.indexOf(c) >= 0) return SyntaxKind.SYMBOL;
        return SyntaxKind.DEFAULT;
    }

    private static int scanNumber(String line, int start, int n) {
        int j = start;
        if (line.charAt(j) == '0' && j + 1 < n && (line.charAt(j + 1) == 'x' || line.charAt(j + 1) == 'X')) {
            j += 2;
            while (j < n && (isHexDigit(line.charAt(j)) || line.charAt(j) == '_')) j++;
        } else {
            while (j < n && (Character.isDigit(line.charAt(j)) || line.charAt(j) == '_')) j++;
            if (j < n && line.charAt(j) == '.' && j + 1 < n && Character.isDigit(line.charAt(j + 1))) {
                j++;
                while (j < n && (Character.isDigit(line.charAt(j)) || line.charAt(j) == '_')) j++;
            }
            if (j < n && (line.charAt(j) == 'e' || line.charAt(j) == 'E')) {
                int k = j + 1;
                if (k < n && (line.charAt(k) == '+' || line.charAt(k) == '-')) k++;
                if (k < n && Character.isDigit(line.charAt(k))) {
                    j = k;
                    while (j < n && Character.isDigit(line.charAt(j))) j++;
                }
            }
        }
        while (j < n && "fFdDlLuU".indexOf(line.charAt(j)) >= 0) j++;
        return j;
    }

    private static boolean isHexDigit(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static SyntaxKind classifyIdentifier(String word, SourceLanguage lang) {
        if (lang == SourceLanguage.JAVA) {
            if (JAVA_TYPES.contains(word)) return SyntaxKind.TYPE;
            if (JAVA_KEYWORDS.contains(word)) return SyntaxKind.KEYWORD;
        } else if (lang == SourceLanguage.C) {
            if (C_TYPES.contains(word)) return SyntaxKind.TYPE;
            if (C_KEYWORDS.contains(word)) return SyntaxKind.KEYWORD;
        }
        if (isAllCapsIdentifier(word) || Character.isUpperCase(word.charAt(0))) {
            return SyntaxKind.TYPE;
        }
        return SyntaxKind.DEFAULT;
    }

    private static boolean isAllCapsIdentifier(String word) {
        boolean hasLetter = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (Character.isLowerCase(c)) return false;
            }
        }
        return hasLetter;
    }
}
