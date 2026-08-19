package dev.javatexteditor.format;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Javaソースのメンバー（フィールド・コンストラクタ・メソッド・ネストされた型）を
 * CLAUDE.md記載の規約順に並び替える、完全ステートレスな軽量実装。
 *
 * <h2>設計方針（2026-08-13 改訂: Compiler Tree APIを廃止）</h2>
 * 巨大なASTをメモリに保持する {@code javax.tools.JavaCompiler}/{@code com.sun.source.tree.*} は
 * 一切使用しない。{@link SourceLexer} による状態遷移ベースの軽量Lexerで文字列リテラル・
 * テキストブロック・文字リテラル・コメントを読み飛ばしながら波括弧の深さだけを追跡し、
 * メンバーの [開始,終了) インデックスのみを取得する。分類は各メンバー先頭の正規表現走査で行う。
 *
 * <p><b>完全にステートレス</b>: このクラスも {@link SourceLexer}/{@link MethodCallGraphSorter} も
 * static フィールドを一切持たない。{@link #format(String)} が受け取った1ファイル分のテキストに
 * 対してのみ処理を行い、使用するオブジェクトはすべてメソッド内のローカル変数（Listやint配列）で
 * 完結する。呼び出しが完了すればすべての参照が切れ、即座にGC対象になる。他のバッファ・他の
 * 保存アクションの間で状態を共有・キャッシュすることは一切ない。
 */
public final class JavaMemberFormatter {

    private JavaMemberFormatter() {}

    private static final Pattern PACKAGE_PATTERN =
        Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern DECL_PATTERN =
        Pattern.compile("\\b(class|interface|enum|record)\\b\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern NESTED_TYPE_PATTERN =
        Pattern.compile("\\b(class|interface|enum|record)\\b\\s+[A-Za-z_$]");
    private static final Pattern NAME_BEFORE_PAREN =
        Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern MODIFIER_PREFIX = Pattern.compile(
        "^(?:@[A-Za-z_$][\\w$.]*(?:\\([^()]*\\))?\\s*|\\b(?:public|protected|private|static|final|" +
        "abstract|synchronized|native|transient|volatile|strictfp|default|sealed)\\b\\s*)*");
    private static final Pattern PUBLIC_KW = Pattern.compile("\\bpublic\\b");
    private static final Pattern PROTECTED_KW = Pattern.compile("\\bprotected\\b");
    private static final Pattern PRIVATE_KW = Pattern.compile("\\bprivate\\b");
    private static final Pattern STATIC_KW = Pattern.compile("\\bstatic\\b");

    private static final Set<String> OBJECT_OVERRIDE_NAMES = Set.of("equals", "hashCode", "toString", "clone");

    /**
     * 並び替え後のソースを返す。無変更・対象外・解析に失敗した場合は {@code null}。
     * 例外は一切外に漏らさない（フェイルセーフ: 保存処理そのものを壊さない）。
     */
    public static String format(String source) {
        try {
            String result = formatTopLevel(source);
            return (result == null || result.equals(source)) ? null : result;
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    private static String formatTopLevel(String source) {
        Matcher pkgM = PACKAGE_PATTERN.matcher(source);
        if (pkgM.find() && isJdkPackage(pkgM.group(1))) {
            return null; // フェイルセーフの最終防衛線（java./javax./jdk./sun.パッケージ）
        }

        // 先頭に現れる最初のトップレベル型のみを対象にする（本エディタの想定ファイル規模＝
        // 1ファイル1公開トップレベル型に対する軽量な割り切り。以降に別のトップレベル型が
        // 続く場合はその部分を無変更のまま末尾に残す）。
        SourceLexer.BodyRegion topRegion = SourceLexer.sliceClassBody(source, 0);
        if (topRegion == null) return null;
        String rebuilt = formatClass(source, 0);
        if (rebuilt == null) return null;
        return rebuilt + source.substring(topRegion.closeBrace() + 1);
    }

    private static boolean isJdkPackage(String pkg) {
        return pkg.equals("java") || pkg.startsWith("java.")
            || pkg.equals("javax") || pkg.startsWith("javax.")
            || pkg.equals("jdk") || pkg.startsWith("jdk.")
            || pkg.equals("sun") || pkg.startsWith("sun.");
    }

    /**
     * {@code searchFrom} から始まる1つの型宣言（class/interface/enum/record）の本文を並び替え、
     * その宣言全体（見出し〜閉じ{@code }}）のテキストを返す。対象外・解析失敗時は {@code null}。
     */
    private static String formatClass(String source, int searchFrom) {
        SourceLexer.BodyRegion region = SourceLexer.sliceClassBody(source, searchFrom);
        if (region == null) return null;

        String headerText = source.substring(searchFrom, region.openBrace());
        DeclInfo decl = detectDecl(headerText);
        if (decl == null) return null; // class/interface/enum/record以外（アノテーション型等）は対象外

        List<int[]> rawBounds = region.members();
        int n = rawBounds.size();
        if (n == 0) return source.substring(searchFrom, region.closeBrace() + 1); // 並び替え対象なし

        boolean isRecord = decl.kind().equals("record");
        boolean isEnum = decl.kind().equals("enum");
        boolean isInterface = decl.kind().equals("interface");

        MemberSlice[] slices = new MemberSlice[n];
        for (int i = 0; i < n; i++) {
            int[] b = rawBounds.get(i);
            slices[i] = classify(source, b[0], b[1], decl.name(), isRecord, isEnum, isInterface, i == 0);
        }

        // 同一行末コメントの回収: 隣接スライスは連続しているため、次スライスの先頭行を調べる。
        int[] adjStart = new int[n];
        int[] adjEnd = new int[n];
        for (int i = 0; i < n; i++) { adjStart[i] = slices[i].start(); adjEnd[i] = slices[i].end(); }
        for (int i = 0; i < n - 1; i++) {
            int firstLineEnd = firstLineEnd(source, adjStart[i + 1], adjEnd[i + 1]);
            if (!source.substring(adjStart[i + 1], firstLineEnd).isBlank()) {
                adjEnd[i] = firstLineEnd;
                adjStart[i + 1] = firstLineEnd;
            }
        }
        int tailStart = firstLineEnd(source, adjEnd[n - 1], region.closeBrace());
        if (source.substring(adjEnd[n - 1], tailStart).isBlank()) tailStart = adjEnd[n - 1];

        // ネストされた型は再帰的に処理する（差し替えテキストをownTextに保持。失敗時はnullのまま=無変更）。
        String[] ownText = new String[n];
        for (int i = 0; i < n; i++) {
            if (slices[i].kind() == MemberKind.NESTED_TYPE) {
                int codeStart = SourceLexer.skipGap(source, adjStart[i], adjEnd[i]);
                String rebuilt = formatClass(source, codeStart);
                if (rebuilt != null) {
                    ownText[i] = source.substring(adjStart[i], codeStart) + rebuilt;
                }
            }
        }

        List<MemberSlice> fields = new ArrayList<>();
        List<MemberSlice> inits = new ArrayList<>();
        List<MemberSlice> ctors = new ArrayList<>();
        List<MemberSlice> methods = new ArrayList<>();
        List<MemberSlice> overrideMethods = new ArrayList<>();
        List<MemberSlice> nested = new ArrayList<>();
        List<MemberSlice> others = new ArrayList<>();
        MemberSlice enumConstants = null;

        for (MemberSlice s : slices) {
            switch (s.kind()) {
                case ENUM_CONSTANTS -> enumConstants = s;
                case FIELD -> fields.add(s);
                case INIT_BLOCK -> inits.add(s);
                case CONSTRUCTOR -> ctors.add(s);
                case METHOD -> { if (OBJECT_OVERRIDE_NAMES.contains(s.name())) overrideMethods.add(s); else methods.add(s); }
                case NESTED_TYPE -> nested.add(s);
                default -> others.add(s);
            }
        }

        fields.sort(Comparator.comparingInt((MemberSlice s) -> s.isStatic() ? 0 : 1)
            .thenComparingInt(MemberSlice::visibilityRank).thenComparingInt(MemberSlice::start));
        inits.sort(Comparator.comparingInt((MemberSlice s) -> s.isStatic() ? 0 : 1).thenComparingInt(MemberSlice::start));
        ctors.sort(Comparator.comparingInt((MemberSlice s) -> s.isCompactCtor() ? 0 : 1)
            .thenComparingInt(MemberSlice::paramCount).thenComparingInt(MemberSlice::start));
        overrideMethods.sort(Comparator.comparing(MemberSlice::name)
            .thenComparingInt(MemberSlice::paramCount).thenComparingInt(MemberSlice::start));
        nested.sort(Comparator.comparingInt(MemberSlice::start));
        others.sort(Comparator.comparingInt(MemberSlice::start));
        List<MemberSlice> sortedMethods = MethodCallGraphSorter.sort(methods, source);

        List<MemberSlice> ordered = new ArrayList<>(n);
        if (enumConstants != null) ordered.add(enumConstants);
        ordered.addAll(fields);
        ordered.addAll(inits);
        ordered.addAll(ctors);
        ordered.addAll(others);
        ordered.addAll(sortedMethods);
        ordered.addAll(overrideMethods);
        ordered.addAll(nested);

        Map<MemberSlice, Integer> indexOf = new IdentityHashMap<>(n);
        for (int i = 0; i < n; i++) indexOf.put(slices[i], i);

        StringBuilder body = new StringBuilder(region.closeBrace() - searchFrom + 16);
        body.append(source, searchFrom, region.openBrace() + 1);
        for (MemberSlice s : ordered) {
            int idx = indexOf.get(s);
            body.append(ownText[idx] != null ? ownText[idx] : source.substring(adjStart[idx], adjEnd[idx]));
        }
        body.append(source, tailStart, region.closeBrace() + 1);
        return body.toString();
    }

    private static int firstLineEnd(String source, int from, int limit) {
        int nl = source.indexOf('\n', from);
        return (nl < 0 || nl > limit) ? limit : nl;
    }

    private record DeclInfo(String kind, String name) {}

    /** ヘッダーテキスト内で最後に現れる class/interface/enum/record 宣言を実際の宣言とみなす。 */
    private static DeclInfo detectDecl(String headerText) {
        Matcher m = DECL_PATTERN.matcher(headerText);
        DeclInfo last = null;
        while (m.find()) {
            last = new DeclInfo(m.group(1), m.group(2));
        }
        return last;
    }

    private static MemberSlice classify(String source, int start, int end, String className,
                                         boolean isRecord, boolean isEnum, boolean isInterface, boolean isFirst) {
        if (isEnum && isFirst) {
            return new MemberSlice(start, end, MemberKind.ENUM_CONSTANTS, null, false, 0, 0, false);
        }
        int codeStart = SourceLexer.skipGap(source, start, end);
        if (codeStart >= end) {
            return new MemberSlice(start, end, MemberKind.OTHER, null, false, 0, 0, false);
        }
        boolean endsWithBrace = source.charAt(end - 1) == '}';
        int headerEnd;
        if (endsWithBrace) {
            int hb = SourceLexer.findHeaderEnd(source, codeStart, end);
            headerEnd = (hb >= 0) ? hb : end - 1;
        } else {
            headerEnd = end - 1; // ';' の位置
        }
        if (headerEnd <= codeStart) {
            return new MemberSlice(start, end, MemberKind.OTHER, null, false, 0, 0, false);
        }
        String header = source.substring(codeStart, headerEnd).trim();
        if (header.isEmpty()) {
            return new MemberSlice(start, end, MemberKind.OTHER, null, false, 0, 0, false);
        }

        Matcher modM = MODIFIER_PREFIX.matcher(header);
        modM.lookingAt();
        String modPrefix = modM.group();
        String rest = header.substring(modM.end()).trim();

        boolean isStatic = STATIC_KW.matcher(modPrefix).find() || (isInterface && !rest.isEmpty()
            && !NESTED_TYPE_PATTERN.matcher(rest).lookingAt() && isFieldLike(rest, className));
        int visibility = isInterface ? 0 : visibilityRankOf(modPrefix);

        if (rest.isEmpty() && endsWithBrace) {
            return new MemberSlice(start, end, MemberKind.INIT_BLOCK, null, isStatic, visibility, 0, false);
        }
        if (NESTED_TYPE_PATTERN.matcher(rest).lookingAt()) {
            return new MemberSlice(start, end, MemberKind.NESTED_TYPE, null, isStatic, visibility, 0, false);
        }
        if (rest.startsWith(className)) {
            String afterName = rest.substring(className.length()).trim();
            if (afterName.startsWith("(")) {
                return new MemberSlice(start, end, MemberKind.CONSTRUCTOR, className, isStatic, visibility,
                    countParams(afterName), false);
            }
            if (isRecord && afterName.isEmpty() && endsWithBrace) {
                return new MemberSlice(start, end, MemberKind.CONSTRUCTOR, className, isStatic, visibility, 0, true);
            }
        }
        // フィールド初期化子中の呼び出し式（例: `Font font = new Font(...)` の `new Font(`）を
        // メソッド宣言と誤認識しないよう、トップレベルの代入 `=` より前に現れる `(` だけを
        // メソッド宣言のものとみなす（`=` が `(` より先に現れる場合は初期化子付きフィールド）。
        int topLevelEq = indexOfTopLevelAssign(rest);
        Matcher nameM = NAME_BEFORE_PAREN.matcher(rest);
        if (nameM.find() && (topLevelEq < 0 || nameM.start() < topLevelEq)) {
            String name = nameM.group(1);
            int paramCount = countParams(rest.substring(nameM.end() - 1));
            return new MemberSlice(start, end, MemberKind.METHOD, name, isStatic, visibility, paramCount, false);
        }
        return new MemberSlice(start, end, MemberKind.FIELD, null, isStatic, visibility, 0, false);
    }

    /**
     * {@code text} 内でトップレベル（丸括弧・角括弧の深さ0）に現れる代入 {@code =} の位置を返す。
     * {@code ==}/{@code !=}/{@code <=}/{@code >=} は除外する。見つからなければ -1。
     */
    private static int indexOfTopLevelAssign(String text) {
        int depthParen = 0, depthSquare = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '(' -> depthParen++;
                case ')' -> { if (depthParen > 0) depthParen--; }
                case '[' -> depthSquare++;
                case ']' -> { if (depthSquare > 0) depthSquare--; }
                case '=' -> {
                    if (depthParen == 0 && depthSquare == 0) {
                        char prev = i > 0 ? text.charAt(i - 1) : '\0';
                        char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
                        boolean isComparison = next == '=' || prev == '=' || prev == '!'
                            || prev == '<' || prev == '>';
                        if (!isComparison) return i;
                    }
                }
                default -> { }
            }
        }
        return -1;
    }

    /** interfaceのフィールド判定用の簡易ヒューリスティック: 名前(の直後が'('でない=メソッドでない。 */
    private static boolean isFieldLike(String rest, String className) {
        Matcher nameM = NAME_BEFORE_PAREN.matcher(rest);
        return !nameM.find();
    }

    private static int visibilityRankOf(String modPrefix) {
        if (PUBLIC_KW.matcher(modPrefix).find()) return 0;
        if (PROTECTED_KW.matcher(modPrefix).find()) return 1;
        if (PRIVATE_KW.matcher(modPrefix).find()) return 3;
        return 2;
    }

    /** {@code parenText} は {@code (} で始まる文字列。対応する {@code )} までの引数個数を数える。 */
    private static int countParams(String parenText) {
        int n = parenText.length();
        if (n == 0 || parenText.charAt(0) != '(') return 0;
        int depthParen = 1, depthAngle = 0, depthSquare = 0;
        int commaCount = 0;
        boolean sawContent = false;
        int i = 1;
        while (i < n && depthParen > 0) {
            char c = parenText.charAt(i);
            switch (c) {
                case '(' -> depthParen++;
                case ')' -> depthParen--;
                case '<' -> depthAngle++;
                case '>' -> { if (depthAngle > 0) depthAngle--; }
                case '[' -> depthSquare++;
                case ']' -> { if (depthSquare > 0) depthSquare--; }
                case ',' -> { if (depthParen == 1 && depthAngle == 0 && depthSquare == 0) commaCount++; }
                default -> { if (!Character.isWhitespace(c)) sawContent = true; }
            }
            i++;
        }
        return sawContent ? commaCount + 1 : 0;
    }
}
