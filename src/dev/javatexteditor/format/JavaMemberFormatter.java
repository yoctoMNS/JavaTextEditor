package dev.javatexteditor.format;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

import javax.lang.model.element.Modifier;
import javax.tools.*;
import java.net.URI;
import java.util.*;

/**
 * Javaソースのメンバー（フィールド・コンストラクタ・メソッド・ネストされた型）を
 * CLAUDE.md記載の規約順に並び替える。
 *
 * <h2>解析方針</h2>
 * 波括弧を自前でカウントするLexerは書かない。メンバーの境界特定は本物のパーサー
 * （{@link javax.tools.JavaCompiler} の parse-only タスク。{@code dev.javatexteditor.analysis.SourceAnalyzer}
 * と同じ手法）に完全に委譲し、実際のテキスト操作は {@link SourcePositions} が返す文字オフセットを
 * 使った「原文の部分文字列をそのまま入れ替えるだけ」の処理に限定する。ノードを再生成（プリティプリント）
 * しないため、文字列リテラル中の {@code {}}・コメント・アノテーションを誤解釈して壊すことがない。
 *
 * <h2>コメント・Javadocの扱い</h2>
 * 各メンバーの「直前メンバーの終端 〜 自分の開始位置」の間のテキスト（空行・コメント・Javadocを含む）
 * を、そのメンバー自身に不可分な接頭辞（leadingGap）として保持する。並び替え時は
 * {@code leadingGap + ownText} を1ブロックとして丸ごと移動させるため、コメントは常に元々
 * 付いていたメンバーと一緒に移動する。同一行末コメント（{@code int x; // ...}）は隙間の1行目を
 * 前方のメンバーへ回収してから隙間を分割することで、後続メンバーに誤って付け替わることを防ぐ。
 *
 * <h2>enum定数</h2>
 * enum定数は ordinal() の意味が変わってしまうため、絶対に相互の順序を変えない
 * （安定ソートに委ね、常に他の全カテゴリより前に固定する）。
 *
 * <h2>スコープ外にした項目</h2>
 * <ul>
 *   <li>呼び出し元→呼び出し先のStep-downルール（呼び出しグラフ解析が必要で費用対効果が低いため未実装。
 *       代わりに同名メソッド（オーバーロード）の先頭出現順を維持することで擬似的に近い効果を得ている）</li>
 *   <li>record の正規コンストラクタとカスタムコンストラクタの判別（record component の情報が
 *       parse-onlyでは取得困難なため、compact constructor の判定のみ行い、それ以外は元の順序を維持する）</li>
 * </ul>
 */
public final class JavaMemberFormatter {

    private JavaMemberFormatter() {}

    /**
     * 並び替え後のソースを返す。次のいずれかに該当する場合は安全側で {@code null} を返し、
     * 呼び出し側は元のテキストをそのまま使うこと: 構文エラーがある / JDK標準パッケージ
     * （java./javax./jdk./sun.）宣言である / 並び替えの必要が無い / 解析中に例外が発生した。
     */
    public static String format(String source) {
        try {
            return formatInternal(source);
        } catch (Exception | AssertionError e) {
            return null;
        }
    }

    private static String formatInternal(String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return null;

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        // DiagnosticCollector を渡さないと SourcePositions.getEndPosition() が常に NOPOS を返す
        // （dev.javatexteditor.analysis.BindingDefinitionResolver と同じ既知の javac の挙動）。
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, Locale.ENGLISH, null)) {
            JavaFileObject fileObj = new StringSource(source);
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, diagnostics, List.of("-proc:none"), null, List.of(fileObj));
            JavacTask javacTask = (JavacTask) task;

            Iterable<? extends CompilationUnitTree> units = javacTask.parse();

            for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    return null; // 構文エラーがあるファイルは安全側で一切触らない
                }
            }

            Iterator<? extends CompilationUnitTree> it = units.iterator();
            if (!it.hasNext()) return null;
            CompilationUnitTree unit = it.next();

            ExpressionTree pkg = unit.getPackageName();
            if (pkg != null && isJdkPackage(pkg.toString())) {
                return null; // フェイルセーフの最終防衛線（java./javax./jdk./sun.パッケージ）
            }

            Trees trees = Trees.instance(javacTask);
            SourcePositions positions = trees.getSourcePositions();

            List<long[]> ranges = new ArrayList<>();
            List<String> replacements = new ArrayList<>();
            for (Tree typeDecl : unit.getTypeDecls()) {
                if (!(typeDecl instanceof ClassTree ct)) continue;
                long start = effectiveStart(ct.getModifiers(), unit, positions, positions.getStartPosition(unit, ct));
                long end = positions.getEndPosition(unit, ct);
                if (start < 0 || end <= start) continue;
                String rebuilt = formatClassRecursively(ct, unit, positions, source);
                if (rebuilt == null) continue;
                ranges.add(new long[]{start, end});
                replacements.add(rebuilt);
            }
            if (ranges.isEmpty()) return null;

            // 末尾（オフセットの大きい方）から順にスプライスし、前方のオフセットを無効化しないようにする。
            Integer[] order = new Integer[ranges.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Long.compare(ranges.get(b)[0], ranges.get(a)[0]));

            StringBuilder result = new StringBuilder(source);
            for (int i : order) {
                long[] r = ranges.get(i);
                result.replace((int) r[0], (int) r[1], replacements.get(i));
            }
            String finalText = result.toString();
            return finalText.equals(source) ? null : finalText;
        }
    }

    private static boolean isJdkPackage(String pkg) {
        return pkg.equals("java") || pkg.startsWith("java.")
            || pkg.equals("javax") || pkg.startsWith("javax.")
            || pkg.equals("jdk") || pkg.startsWith("jdk.")
            || pkg.equals("sun") || pkg.startsWith("sun.");
    }

    /**
     * 型宣言1つ分の本文を再帰的に並び替え、宣言全体（先頭の注釈から閉じ {@code }} まで）の
     * テキストを返す。並び替え対象が無い、または安全に判定できない場合は元のテキストをそのまま返す
     * （呼び出し元の再帰合成を単純化するため、null は「致命的で丸ごと諦める」時だけ使う）。
     */
    private static String formatClassRecursively(ClassTree ct, CompilationUnitTree unit,
                                                   SourcePositions positions, String source) {
        long rawStart = positions.getStartPosition(unit, ct);
        long classStart = effectiveStart(ct.getModifiers(), unit, positions, rawStart);
        long classEnd = positions.getEndPosition(unit, ct);
        if (classStart < 0 || classEnd <= classStart) return null;

        DeclKind declKind = DeclKind.of(ct.getKind());
        if (declKind == DeclKind.OTHER) {
            return source.substring((int) classStart, (int) classEnd);
        }

        // 開き { の位置を先に確定する（詳細は findBodyOpenBrace のコメント参照）。record の場合、
        // ct.getMembers() にはヘッダーの record component（例: record Point(int x, int y) の x, y）が
        // 暗黙のVariableTreeとして含まれ、その開始位置は必ずこの { より前（ヘッダー側）を指す。
        // これを本文の並び替え対象から除外するため、{ の位置を先に求めて後段のフィルタに使う。
        int bodyOpenBrace = findBodyOpenBrace(ct, unit, positions, source, classStart, classEnd);
        if (bodyOpenBrace < 0) return source.substring((int) classStart, (int) classEnd);

        List<Tree> members = new ArrayList<>();
        for (Tree m : ct.getMembers()) {
            long s = positions.getStartPosition(unit, m);
            if (s > bodyOpenBrace) members.add(m); // record component（ヘッダー側）を除外
        }
        if (members.isEmpty()) {
            return source.substring((int) classStart, (int) classEnd);
        }

        int n = members.size();
        long[] starts = new long[n];
        long[] ends = new long[n];
        for (int i = 0; i < n; i++) {
            Tree m = members.get(i);
            ModifiersTree mods = (m instanceof VariableTree vt) ? vt.getModifiers()
                : (m instanceof MethodTree mt) ? mt.getModifiers()
                : (m instanceof ClassTree ctt) ? ctt.getModifiers()
                : null;
            starts[i] = effectiveStart(mods, unit, positions, positions.getStartPosition(unit, m));
            ends[i] = positions.getEndPosition(unit, m);
        }
        for (int i = 0; i < n; i++) {
            if (starts[i] < 0 || ends[i] <= starts[i]) return source.substring((int) classStart, (int) classEnd);
            if (i > 0 && starts[i] < ends[i - 1]) return source.substring((int) classStart, (int) classEnd);
        }

        // 先頭メンバーが並び替えでもう先頭ではなくなることがあるため、「class宣言〜開き{」までの
        // 不変の見出し部分（header）と、「開き{〜先頭メンバー開始」の隙間（並び替え後は先頭メンバーの
        // leadingGapとして先頭メンバーに追従すべき部分）を分離する。
        String header = source.substring((int) classStart, bodyOpenBrace + 1);
        String headGap = source.substring(bodyOpenBrace + 1, (int) starts[0]);
        String suffixRaw = source.substring((int) ends[n - 1], (int) classEnd);

        String[] ownText = new String[n];
        String[] leadingGap = new String[n];
        leadingGap[0] = headGap; // 先頭メンバーが並び替え後に先頭でなくなっても隙間を保持したまま追従する

        for (int i = 0; i < n; i++) {
            Tree m = members.get(i);
            if (m instanceof ClassTree nested) {
                String rebuilt = formatClassRecursively(nested, unit, positions, source);
                if (rebuilt == null) return source.substring((int) classStart, (int) classEnd);
                ownText[i] = rebuilt;
            } else {
                ownText[i] = source.substring((int) starts[i], (int) ends[i]);
            }
        }

        for (int i = 0; i < n - 1; i++) {
            String[] split = splitTrailingComment(source.substring((int) ends[i], (int) starts[i + 1]));
            ownText[i] = ownText[i] + split[0];
            leadingGap[i + 1] = split[1];
        }

        String suffix;
        {
            String[] split = splitTrailingComment(suffixRaw);
            ownText[n - 1] = ownText[n - 1] + split[0];
            suffix = split[1];
        }

        // enum定数の直後（; を含む区切り）は、後続メンバーがどれだけ並び替わっても
        // 「定数ブロックのすぐ後ろ」に固定する（さもないと ; がメンバーと一緒に迷子になる）。
        String enumSeparator = "";
        if (declKind == DeclKind.ENUM) {
            int constantCount = 0;
            while (constantCount < n && categorize(members.get(constantCount), declKind) == MemberCategory.ENUM_CONSTANT) {
                constantCount++;
            }
            if (constantCount > 0 && constantCount < n) {
                enumSeparator = leadingGap[constantCount];
                leadingGap[constantCount] = "";
            }
        }

        Map<String, Integer> nameGroupRanks = buildNameGroupRanks(members, declKind);

        List<MemberBlock> blocks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            blocks.add(buildBlock(members.get(i), i, declKind, leadingGap[i], ownText[i],
                nameGroupRanks, starts[i], unit, positions));
        }

        List<MemberBlock> sorted = new ArrayList<>(blocks);
        sorted.sort(comparator(declKind));

        return assembleEnumAware(header, sorted, enumSeparator, suffix);
    }

    /** ; を含む enum セパレータの挿入位置がずれないよう、上のロジックを単純な2パス版に置き換えて使う。 */
    private static String assembleEnumAware(String prefix, List<MemberBlock> sorted, String enumSeparator, String suffix) {
        StringBuilder body = new StringBuilder();
        body.append(prefix);
        boolean pendingSeparator = !enumSeparator.isEmpty();
        boolean seenNonConstant = false;
        for (MemberBlock b : sorted) {
            if (pendingSeparator && b.category != MemberCategory.ENUM_CONSTANT && !seenNonConstant) {
                body.append(enumSeparator);
                seenNonConstant = true;
                pendingSeparator = false;
            }
            body.append(b.leadingGap).append(b.ownText);
        }
        if (pendingSeparator) {
            // enum定数のみで構成されるケース（あり得ないはずだが安全側で末尾に付与）
            body.append(enumSeparator);
        }
        body.append(suffix);
        return body.toString();
    }

    /**
     * ModifiersTree（注釈・修飾子）の開始位置がメンバー本体の開始位置より前にある場合、
     * そちらを実質的な開始位置として採用する（{@code @Override} 等の注釈を含めて丸ごと移動させるため）。
     */
    private static long effectiveStart(ModifiersTree mods, CompilationUnitTree unit,
                                        SourcePositions positions, long fallbackStart) {
        if (mods == null) return fallbackStart;
        long mStart = positions.getStartPosition(unit, mods);
        if (mStart >= 0 && (fallbackStart < 0 || mStart < fallbackStart)) return mStart;
        return fallbackStart;
    }

    /**
     * クラス本体の開き {@code {} の文字インデックスを返す。注釈引数中の配列初期化子
     * （例: {@code @Anno(value={1,2,3})}）の {@code {} と誤認識しないよう、全ての注釈の
     * 終端位置より後ろだけを検索する（extends/implements 節は型参照のみで {@code {} を含み得ないため、
     * 注釈の終端以降で最初に現れる {@code {} は必ずクラス本体の開き括弧そのものになる）。
     * 見つからない場合は -1（呼び出し元は並び替えを諦めて元のテキストを返す）。
     */
    private static int findBodyOpenBrace(ClassTree ct, CompilationUnitTree unit, SourcePositions positions,
                                          String source, long classStart, long searchEnd) {
        long from = classStart;
        ModifiersTree mods = ct.getModifiers();
        if (mods != null) {
            for (AnnotationTree ann : mods.getAnnotations()) {
                long annEnd = positions.getEndPosition(unit, ann);
                if (annEnd > from) from = annEnd;
            }
        }
        if (from > searchEnd) return -1;
        int idx = source.indexOf('{', (int) from);
        return (idx >= 0 && idx < searchEnd) ? idx : -1;
    }

    /**
     * gap（直前メンバー終端〜次メンバー開始の間のテキスト）の最初の行に空白以外の内容
     * （同一行末コメント等）があれば、その1行分を「前のメンバーに残す部分」として切り出す。
     * 戻り値は [前のメンバーに追記する分, 次メンバーのleadingGapに残す分]。
     */
    private static String[] splitTrailingComment(String gap) {
        int nl = gap.indexOf('\n');
        String firstLine = (nl >= 0) ? gap.substring(0, nl) : gap;
        String rest = (nl >= 0) ? gap.substring(nl) : "";
        if (!firstLine.isBlank()) {
            return new String[]{firstLine, rest};
        }
        return new String[]{"", gap};
    }

    private static MemberCategory categorize(Tree member, DeclKind declKind) {
        if (member instanceof VariableTree vt) {
            if (declKind == DeclKind.ENUM && vt.getType() == null) return MemberCategory.ENUM_CONSTANT;
            if (declKind == DeclKind.INTERFACE) return MemberCategory.CONSTANT;
            boolean isStatic = vt.getModifiers().getFlags().contains(Modifier.STATIC);
            return isStatic ? MemberCategory.STATIC_FIELD : MemberCategory.INSTANCE_FIELD;
        }
        if (member instanceof BlockTree bt) {
            return bt.isStatic() ? MemberCategory.STATIC_INIT : MemberCategory.INSTANCE_INIT;
        }
        if (member instanceof MethodTree mt) {
            String name = mt.getName().toString();
            if (name.equals("<init>")) return MemberCategory.CONSTRUCTOR;
            Set<Modifier> flags = mt.getModifiers().getFlags();
            if (declKind == DeclKind.INTERFACE) {
                if (flags.contains(Modifier.STATIC)) return MemberCategory.STATIC_METHOD;
                if (flags.contains(Modifier.DEFAULT)) return MemberCategory.DEFAULT_METHOD;
                return MemberCategory.ABSTRACT_METHOD;
            }
            if ((declKind == DeclKind.CLASS || declKind == DeclKind.ENUM) && isObjectOverride(name, mt)) {
                return MemberCategory.OBJECT_OVERRIDE_METHOD;
            }
            if (declKind == DeclKind.RECORD && flags.contains(Modifier.STATIC)) {
                return MemberCategory.STATIC_METHOD;
            }
            return MemberCategory.INSTANCE_METHOD;
        }
        if (member instanceof ClassTree) return MemberCategory.NESTED_TYPE;
        return MemberCategory.INSTANCE_METHOD;
    }

    private static boolean isObjectOverride(String name, MethodTree mt) {
        int params = mt.getParameters().size();
        return switch (name) {
            case "equals" -> params == 1;
            case "hashCode", "toString", "clone", "finalize" -> params == 0;
            default -> false;
        };
    }

    private static int objectOverridePriority(String name) {
        return switch (name) {
            case "equals" -> 0;
            case "hashCode" -> 1;
            case "toString" -> 2;
            case "clone" -> 3;
            case "finalize" -> 4;
            default -> 5;
        };
    }

    /** tier の値そのものが最終的な並び順（昇順）になる。10刻みで declKind ごとに定義する。 */
    private static int tierOf(DeclKind declKind, MemberCategory cat) {
        return switch (declKind) {
            case CLASS -> switch (cat) {
                case STATIC_FIELD -> 0;
                case INSTANCE_FIELD -> 10;
                case STATIC_INIT -> 20;
                case INSTANCE_INIT -> 21;
                case CONSTRUCTOR -> 30;
                case OBJECT_OVERRIDE_METHOD -> 50;
                case NESTED_TYPE -> 60;
                default -> 40; // ABSTRACT/DEFAULT/STATIC/INSTANCE_METHOD, CONSTANT(想定外)
            };
            case INTERFACE -> switch (cat) {
                case CONSTANT -> 0;
                case ABSTRACT_METHOD -> 10;
                case DEFAULT_METHOD -> 20;
                case STATIC_METHOD -> 30;
                case NESTED_TYPE -> 40;
                default -> 20;
            };
            case ENUM -> switch (cat) {
                case ENUM_CONSTANT -> 0;
                case STATIC_FIELD -> 10;
                case INSTANCE_FIELD -> 11;
                case STATIC_INIT -> 20;
                case INSTANCE_INIT -> 21;
                case CONSTRUCTOR -> 30;
                case OBJECT_OVERRIDE_METHOD -> 50;
                case NESTED_TYPE -> 60;
                default -> 40;
            };
            case RECORD -> switch (cat) {
                case CONSTRUCTOR -> 0;
                case STATIC_FIELD, STATIC_INIT -> 10;
                case STATIC_METHOD -> 11;
                case NESTED_TYPE -> 30;
                default -> 20; // INSTANCE_METHOD/OBJECT_OVERRIDE(gate対象外)/その他想定外
            };
            case OTHER -> 99;
        };
    }

    private static int visibilityRankOf(ModifiersTree mods) {
        Set<Modifier> flags = mods.getFlags();
        if (flags.contains(Modifier.PUBLIC)) return 0;
        if (flags.contains(Modifier.PROTECTED)) return 1;
        if (flags.contains(Modifier.PRIVATE)) return 3;
        return 2; // package-private
    }

    /** 同名メソッドを常に連続させるための「初出順ランク」を、メソッド名ごとに1回だけ採番する。 */
    private static Map<String, Integer> buildNameGroupRanks(List<Tree> members, DeclKind declKind) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (Tree m : members) {
            if (!(m instanceof MethodTree mt)) continue;
            String name = mt.getName().toString();
            if (name.equals("<init>")) continue;
            ranks.computeIfAbsent(name, k -> ranks.size());
        }
        return ranks;
    }

    private static MemberBlock buildBlock(Tree member, int index, DeclKind declKind,
                                           String leadingGap, String ownText,
                                           Map<String, Integer> nameGroupRanks,
                                           long memberStart, CompilationUnitTree unit, SourcePositions positions) {
        MemberCategory category = categorize(member, declKind);
        int tier = tierOf(declKind, category);
        int visibilityRank = 0;
        int paramCount = 0;
        int nameGroupRank = 0;
        int objOverridePriority = 5;
        int ctorSubRank = 0;

        if (member instanceof VariableTree vt) {
            visibilityRank = visibilityRankOf(vt.getModifiers());
        } else if (member instanceof MethodTree mt) {
            paramCount = mt.getParameters().size();
            String name = mt.getName().toString();
            if (category == MemberCategory.CONSTRUCTOR) {
                if (declKind == DeclKind.RECORD) {
                    ctorSubRank = isCompactRecordConstructor(mt, memberStart, unit, positions) ? 0 : 1;
                }
            } else if (category == MemberCategory.OBJECT_OVERRIDE_METHOD) {
                objOverridePriority = objectOverridePriority(name);
            } else {
                nameGroupRank = nameGroupRanks.getOrDefault(name, Integer.MAX_VALUE);
            }
        }

        return new MemberBlock(index, category, tier, leadingGap, ownText,
            visibilityRank, paramCount, nameGroupRank, objOverridePriority, ctorSubRank);
    }

    /**
     * compact constructor（{@code public Point { ... }} のように引数リストを持たない構文）かどうかを判定する。
     * compact constructor の場合、javac はパラメータ一覧を record ヘッダーの成分から補うため、
     * その最初のパラメータの開始位置は必ず「このコンストラクタ自身の開始位置より前」（record ヘッダー側）を指す。
     * これは純粋に位置情報だけで判定できる、テキストの括弧探索に頼らない安全な方法。
     */
    private static boolean isCompactRecordConstructor(MethodTree mt, long memberStart,
                                                        CompilationUnitTree unit, SourcePositions positions) {
        List<? extends VariableTree> params = mt.getParameters();
        if (params.isEmpty()) return true;
        long firstParamStart = positions.getStartPosition(unit, params.get(0));
        return firstParamStart < 0 || firstParamStart < memberStart;
    }

    private static Comparator<MemberBlock> comparator(DeclKind declKind) {
        return (a, b) -> {
            int t = Integer.compare(a.tier, b.tier);
            if (t != 0) return t;
            if (a.category == MemberCategory.ENUM_CONSTANT) return 0; // 相対順序を絶対に変えない
            return switch (a.category) {
                case STATIC_FIELD, INSTANCE_FIELD -> {
                    int v = Integer.compare(a.visibilityRank, b.visibilityRank);
                    yield v != 0 ? v : Integer.compare(a.originalIndex, b.originalIndex);
                }
                case CONSTRUCTOR -> {
                    if (declKind == DeclKind.RECORD) {
                        int c = Integer.compare(a.constructorSubRank, b.constructorSubRank);
                        yield c != 0 ? c : Integer.compare(a.originalIndex, b.originalIndex);
                    }
                    int p = Integer.compare(a.paramCount, b.paramCount);
                    yield p != 0 ? p : Integer.compare(a.originalIndex, b.originalIndex);
                }
                case OBJECT_OVERRIDE_METHOD -> {
                    int o = Integer.compare(a.objectOverridePriority, b.objectOverridePriority);
                    if (o != 0) yield o;
                    int p = Integer.compare(a.paramCount, b.paramCount);
                    yield p != 0 ? p : Integer.compare(a.originalIndex, b.originalIndex);
                }
                case STATIC_INIT, INSTANCE_INIT, NESTED_TYPE, CONSTANT ->
                    Integer.compare(a.originalIndex, b.originalIndex);
                default -> { // ABSTRACT/DEFAULT/STATIC/INSTANCE_METHOD
                    int g = Integer.compare(a.nameGroupRank, b.nameGroupRank);
                    if (g != 0) yield g;
                    int p = Integer.compare(a.paramCount, b.paramCount);
                    yield p != 0 ? p : Integer.compare(a.originalIndex, b.originalIndex);
                }
            };
        };
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String source;

        StringSource(String source) {
            super(URI.create("string:///AutoFormatBuffer.java"), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
