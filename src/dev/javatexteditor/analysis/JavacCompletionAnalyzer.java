package dev.javatexteditor.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code obj.} の後ろで「obj の型が実際に持っているメンバー」を javac の意味解析で列挙する。
 * IntelliJ IDEA のメンバー補完に相当する、入力補完の正確側（ハイブリッド解決の後段）。
 *
 * <p>{@link ReceiverTypeResolver}（正規表現で直近の宣言を探すヒューリスティック）と違い、
 * javac に型解決そのものをさせるため次が正しく扱える:
 * <ul>
 *   <li>メソッドチェーン（{@code list.get(0).})</li>
 *   <li>ジェネリクスの要素型（{@code List<Path>} の要素は {@code Path}）</li>
 *   <li>継承・インタフェース経由のメンバー（{@code getAllMembers} が階層をすべて含む）</li>
 *   <li>{@code var} で宣言された変数の推論型</li>
 * </ul>
 *
 * <p>代償として、プロジェクト全体の属性付けを伴うため EDT で呼んではならない
 * （{@link BindingDefinitionResolver} と同じ制約。呼び出し側はバックグラウンド実行し、
 * 結果が来たら差し替える）。構文エラーだらけの書きかけコードでは解決に失敗するため、
 * 失敗時は空リストを返して呼び出し側の軽量解決に委ねる（graceful degradation）。
 *
 * <p>スレッド安全: 状態を持たないため任意のスレッドから呼べる。
 */
public class JavacCompletionAnalyzer {

    /**
     * 入力中プレフィックスを置き換えるダミー識別子。
     *
     * <p>{@code list.} のように識別子が空のままだと javac の構文解析が
     * 「メンバー選択」として木を組み立てられず、レシーバの型に辿り着けない。
     * IntelliJ を含む補完実装が一般に行うのと同じく、カーソル位置へ実在しない識別子を
     * 差し込んでから解析する。プロジェクト内の実シンボルと衝突しない名前にすること。
     */
    private static final String DUMMY_IDENTIFIER = "__jteCompletionDummy__";

    /** 型名から package 修飾を落とす（{@code java.util.List<java.lang.String>} → {@code List<String>}）。 */
    private static final Pattern QUALIFIED_NAME =
        Pattern.compile("(?:[a-zA-Z_$][a-zA-Z0-9_$]*\\.)+([A-Za-z_$][A-Za-z0-9_$]*)");

    /**
     * カーソル位置がメンバー補完の文脈（{@code expr.} の直後）である前提で、
     * expr の型のメンバーを候補として返す。解決できなければ空リスト。
     *
     * @param text        編集中バッファの全文（未保存の変更を含む）
     * @param filePath    編集中ファイルの絶対パス（無名バッファなら null）
     * @param context     {@link CompletionContext#at} が返したメンバー補完の文脈
     * @param projectRoot プロジェクトルート（配下の .java を解析対象に含める。null 可）
     */
    public List<CompletionItem> resolveMembers(String text, String filePath,
                                               CompletionContext context, Path projectRoot) {
        if (text == null || context == null || !context.isMember()) return List.of();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return List.of();

        String probeText = buildProbeText(text, context);
        int probeOffset = context.prefixStart() + 1; // ダミー識別子の内側

        // DiagnosticCollector を渡すことが重要: これが無いと javac は AST の終了位置を
        // 保持せず、SourcePositions.getEndPosition() が NOPOS を返してノード探索が機能しない
        // （BindingDefinitionResolver と同じ理由）。
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm =
                 compiler.getStandardFileManager(collector, Locale.ENGLISH, null)) {

            String mainPath = (filePath != null) ? filePath : "<buffer>";
            StringJavaFileObject mainFileObj = new StringJavaFileObject(mainPath, probeText);

            // コンパイル対象として javac に明示的に渡すのは編集中のバッファ1件だけにし、
            // プロジェクト内の他ファイルは -sourcepath（JavaSourceRoots が算出）経由で
            // 「型解決に必要になった分だけ」javac に遅延読み込みさせる。以前は
            // JavaSourceCollector.collect でプロジェクト全体の .java を毎回丸ごと文字列として
            // 読み込んでいたため、obj. の入力のたびに（このプロジェクト自身では約400MB規模の）
            // 巨大な一時アロケーションが走り、かつ member-completion-lookup 用の仮想スレッドが
            // 依頼のたびに新規起動される（BindingDefinitionResolver と違い Shift+K のような
            // 単発操作ではなく、打鍵のたびに新しいレシーバ文脈へ移るたび発火する）ため、
            // 複数の巨大解析が同時並行で走り続けヒープが際限なく膨張していた
            // （CompileAnalyzer/JavaSourceRoots が2026-08-07に修正した問題と同種の再発）。
            List<String> options = new ArrayList<>();
            options.add("-proc:none");
            options.add("-implicit:none");
            String sourcePath = JavaSourceRoots.sourcePathFor(projectRoot, mainPath, probeText);
            if (!sourcePath.isEmpty()) {
                options.add("-sourcepath");
                options.add(sourcePath);
            }

            JavacTask task = (JavacTask) compiler.getTask(
                null, fm, collector, options, null, List.of(mainFileObj));

            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();

            CompilationUnitTree unit = null;
            for (CompilationUnitTree u : units) {
                if (u.getSourceFile().toUri().equals(mainFileObj.toUri())) {
                    unit = u;
                    break;
                }
            }
            if (unit == null) return List.of();

            Trees trees = Trees.instance(task);
            TreePath path = findMemberSelectAt(trees, unit, probeOffset);
            if (path == null) return List.of();

            MemberSelectTree memberSelect = (MemberSelectTree) path.getLeaf();
            TreePath receiverPath = new TreePath(path, memberSelect.getExpression());
            TypeMirror receiverType = trees.getTypeMirror(receiverPath);
            if (receiverType == null) return List.of();

            // レシーバが型そのものを指していれば static メンバーの文脈
            Element receiverElement = trees.getElement(receiverPath);
            boolean staticAccess = receiverElement instanceof TypeElement;

            return collectMembers(task, receiverType, staticAccess, enclosingTypeOf(trees, path));
        } catch (Exception | AssertionError e) {
            // 書きかけコードで javac 内部が例外を投げることがある。⑧ java-source-analysis と
            // 同じ graceful degradation 方針で、失敗は「候補なし」として扱う。
            return List.of();
        }
    }

    /**
     * 入力中の識別子をダミーの<b>メソッド呼び出し</b>へ置き換えたテキストを作る。
     *
     * <p>{@code obj.} のままでは javac は「文になっていない」として、その文をまるごと
     * ERRONEOUS ノードに畳んでしまい、レシーバの型に辿り着けない。
     * {@code obj.dummy} のように識別子を足しても、フィールド参照は単体では文にならないので同じ結果になる。
     * {@code obj.dummy()} と<b>括弧まで付ける</b>と正しい式文になり、
     * 「dummy というメソッドは無い」という解決エラーは出るものの、
     * レシーバ側の部分木は型が付いた状態で残る。ここが型解決の足掛かりになる。
     *
     * <p>カーソルの後ろに識別子の続きがある場合（{@code list.ad|d}）はそれも置き換える。
     * 残すと {@code dummy()d} のような壊れた並びになり、やはり解析できないため。
     */
    private static String buildProbeText(String text, CompletionContext context) {
        int replaceEnd = context.prefixStart() + context.prefix().length();
        while (replaceEnd < text.length() && Character.isJavaIdentifierPart(text.charAt(replaceEnd))) {
            replaceEnd++;
        }
        // 既に括弧が続いているなら二重に付けない（"obj.met|()" のような位置）
        int next = replaceEnd;
        while (next < text.length() && (text.charAt(next) == ' ' || text.charAt(next) == '\t')) next++;
        String call = (next < text.length() && text.charAt(next) == '(') ? "" : "()";

        return text.substring(0, context.prefixStart()) + DUMMY_IDENTIFIER + call
            + text.substring(replaceEnd);
    }

    // -------------------------------------------------------------------------
    // AST 探索
    // -------------------------------------------------------------------------

    /** offset を含む最も内側の {@link MemberSelectTree} の TreePath。無ければ null。 */
    private static TreePath findMemberSelectAt(Trees trees, CompilationUnitTree unit, long offset) {
        SourcePositions positions = trees.getSourcePositions();
        class Finder extends TreePathScanner<Void, Void> {
            TreePath found = null;

            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree instanceof MemberSelectTree) {
                    long start = positions.getStartPosition(unit, tree);
                    long end = positions.getEndPosition(unit, tree);
                    if (start >= 0 && end > start && start <= offset && offset < end) {
                        found = new TreePath(getCurrentPath(), tree);
                    }
                }
                return super.scan(tree, unused);
            }
        }
        Finder finder = new Finder();
        finder.scan(new TreePath(unit), null);
        return finder.found;
    }

    /**
     * カーソル位置を含むクラス（private メンバーを見せてよいかの判定に使う）。
     *
     * <p>クラス宣言ノードに限って {@link Trees#getElement} を引く。任意のノードで引くと、
     * 解決に失敗した式ノードから受け側の型が返ってくることがあり、
     * 「他クラスの private メンバーを自クラス扱いしてしまう」誤判定につながる。
     */
    private static TypeElement enclosingTypeOf(Trees trees, TreePath path) {
        for (TreePath p = path; p != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof com.sun.source.tree.ClassTree) {
                Element el = trees.getElement(p);
                if (el instanceof TypeElement type) return type;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // メンバー列挙
    // -------------------------------------------------------------------------

    private static List<CompletionItem> collectMembers(JavacTask task, TypeMirror type,
                                                       boolean staticAccess, TypeElement accessFrom) {
        Types types = task.getTypes();
        Elements elements = task.getElements();
        List<CompletionItem> items = new ArrayList<>();

        if (type.getKind() == TypeKind.ARRAY) {
            // 配列が持つのは length と Object のメソッドだけ（要素型のメンバーではない）
            items.add(new CompletionItem("length", "fld", "", "int", "length", 0, null,
                CompletionItem.Origin.MEMBER));
            TypeElement objectType = elements.getTypeElement("java.lang.Object");
            if (objectType != null) {
                addMembersOf(items, elements, objectType, false, accessFrom);
            }
            return dedupe(items);
        }
        if (!(type instanceof DeclaredType declared)) {
            return List.of(); // プリミティブ・void・型変数未解決など
        }
        Element el = types.asElement(declared);
        if (!(el instanceof TypeElement typeElement)) return List.of();

        addMembersOf(items, elements, typeElement, staticAccess, accessFrom);
        if (staticAccess) {
            addNestedTypes(items, typeElement);
        }
        return dedupe(items);
    }

    private static void addMembersOf(List<CompletionItem> items, Elements elements,
                                     TypeElement typeElement, boolean staticAccess,
                                     TypeElement accessFrom) {
        for (Element member : elements.getAllMembers(typeElement)) {
            if (member.getKind() == ElementKind.CONSTRUCTOR
                || member.getKind() == ElementKind.STATIC_INIT
                || member.getKind() == ElementKind.INSTANCE_INIT) {
                continue;
            }
            boolean isStatic = member.getModifiers().contains(Modifier.STATIC);
            if (isStatic != staticAccess) continue;
            if (!isAccessible(member, accessFrom)) continue;

            switch (member.getKind()) {
                case METHOD -> items.add(methodItem((ExecutableElement) member));
                case FIELD, ENUM_CONSTANT -> items.add(fieldItem((VariableElement) member));
                default -> { /* ネストした型は addNestedTypes で扱う */ }
            }
        }
    }

    private static void addNestedTypes(List<CompletionItem> items, TypeElement typeElement) {
        for (Element member : typeElement.getEnclosedElements()) {
            if (member instanceof TypeElement nested
                && member.getModifiers().contains(Modifier.STATIC)) {
                String name = nested.getSimpleName().toString();
                items.add(new CompletionItem(name, "cls", "", "", name, 0, null,
                    CompletionItem.Origin.MEMBER));
            }
        }
    }

    /**
     * accessFrom（カーソルのいるクラス）から member が参照できるか。
     * private は同じトップレベルクラス内でのみ、それ以外は参照可能とみなす
     * （package-private の厳密判定までは行わない。補完候補として出しすぎるより
     * 出さなすぎる方が体験を損なうため、緩めに倒している）。
     */
    private static boolean isAccessible(Element member, TypeElement accessFrom) {
        if (!member.getModifiers().contains(Modifier.PRIVATE)) return true;
        if (accessFrom == null) return false;
        return outermostName(member).equals(outermostName(accessFrom));
    }

    private static String outermostName(Element element) {
        Element outermost = element;
        for (Element e = element; e != null; e = e.getEnclosingElement()) {
            if (e instanceof TypeElement) outermost = e;
        }
        return (outermost instanceof TypeElement type)
            ? type.getQualifiedName().toString() : "";
    }

    private static CompletionItem methodItem(ExecutableElement method) {
        String name = method.getSimpleName().toString();
        StringBuilder tail = new StringBuilder("(");
        List<? extends VariableElement> params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) tail.append(", ");
            tail.append(simpleTypeName(params.get(i).asType().toString()));
            tail.append(' ').append(params.get(i).getSimpleName());
        }
        if (method.isVarArgs() && !params.isEmpty()) {
            // 最後の引数の配列表記を可変長引数の表記へ直す
            int lastBracket = tail.lastIndexOf("[]");
            if (lastBracket >= 0) tail.replace(lastBracket, lastBracket + 2, "...");
        }
        tail.append(')');
        String returnType = simpleTypeName(method.getReturnType().toString());
        boolean hasParams = !params.isEmpty();
        return new CompletionItem(name, "mth", tail.toString(), returnType,
            name + "()", hasParams ? 1 : 0, null, CompletionItem.Origin.MEMBER);
    }

    private static CompletionItem fieldItem(VariableElement field) {
        String name = field.getSimpleName().toString();
        return new CompletionItem(name, "fld", "", simpleTypeName(field.asType().toString()),
            name, 0, null, CompletionItem.Origin.MEMBER);
    }

    /** package 修飾を落として読みやすい型名にする。 */
    static String simpleTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) return "";
        // 型注釈（@Nullable 等）が前置されることがあるため落とす
        String cleaned = typeName.replaceAll("@[A-Za-z0-9_.$]+\\s*", "");
        return QUALIFIED_NAME.matcher(cleaned).replaceAll("$1");
    }

    /** 同じ表示になる候補（オーバーライドで重複する等）を1つに畳む。並び順は維持する。 */
    private static List<CompletionItem> dedupe(List<CompletionItem> items) {
        Set<String> seen = new LinkedHashSet<>();
        List<CompletionItem> result = new ArrayList<>(items.size());
        for (CompletionItem item : items) {
            if (seen.add(item.label() + item.tailText())) result.add(item);
        }
        return List.copyOf(result);
    }
}
