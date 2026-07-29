package dev.javatexteditor.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Eclipse JDT の Open Declaration（F3）と同じ「バインディング解決」方式で、
 * カーソル位置の識別子の宣言箇所を特定する（Shift+K 定義ジャンプの最優先段）。
 *
 * JDT のアルゴリズム（ASTParser#setResolveBindings → NodeFinder → IBinding →
 * IJavaElement）を、JDK 標準 API だけで再現する:
 *
 * <pre>
 *   JDT                                  本クラス（javax.tools / Compiler Tree API）
 *   ------------------------------------ ------------------------------------------
 *   ASTParser + resolveBindings(true)  → JavacTask.parse() + analyze()（属性付け）
 *   NodeFinder.perform(ast, offset)    → TreePathScanner でカーソル位置の最内ノード探索
 *   node.resolveBinding() (IBinding)   → Trees.getElement(TreePath)
 *   binding.getJavaElement() → 宣言へ  → Trees.getPath(Element) → SourcePositions
 * </pre>
 *
 * 既存の {@link ProjectSymbolResolver}/{@link ReceiverTypeResolver}（正規表現
 * ヒューリスティック・名前ベース検索）と異なり javac の意味解析をそのまま使うため、
 * オーバーロードの区別・ローカル変数のブロックスコープ・implements/extends 経由の
 * 継承メンバー解決が正確に行える。一方、構文エラーを含む書きかけのコードでは
 * 解決に失敗しやすいため、呼び出し側（ModalEditor）は失敗時に既存ヒューリスティックへ
 * フォールバックする（本クラスは既存解決の置き換えではなく前段）。
 *
 * スレッド安全: 状態を持たないため任意のスレッドから呼べる（ModalEditor は
 * バックグラウンド仮想スレッドから呼び出す）。JVM/HotSpot のネイティブソース
 * トレース（OpenjdkSourceTracer の C/C++ 検索）とは完全に独立しており、一切関与しない。
 */
public class BindingDefinitionResolver {

    /** 解決結果。3種のいずれか。 */
    public sealed interface Resolution permits ProjectLocation, JdkElementLocation, NotFound {}

    /**
     * プロジェクト内ソースの宣言位置。
     * filePath はディスク上の絶対パス。現在編集中のバッファ内で宣言されている場合は
     * 呼び出し時に渡された currentFilePath がそのまま入る（無名バッファなら null）。
     * lineNumber/column は 0-indexed。
     */
    public record ProjectLocation(String filePath, int lineNumber, int column, String kindLabel)
        implements Resolution {}

    /**
     * JDK（プラットフォームクラスパス）側の要素。src.zip ジャンプ用に
     * トップレベルクラスの FQCN とモジュール名、メンバー名（クラス自体なら null）を持つ。
     */
    public record JdkElementLocation(String moduleName, String fqcn, String memberName)
        implements Resolution {}

    /** 解決失敗（構文エラー・カーソル位置に識別子なし・プロジェクト過大など）。 */
    public record NotFound(String reason) implements Resolution {}

    /**
     * カーソル位置の識別子の宣言箇所をバインディング解決で特定する。
     *
     * @param currentText     現在編集中のバッファ全文（未保存の変更を含む）
     * @param currentFilePath 現在のファイルパス（無名バッファなら null）
     * @param cursorOffset    バッファ全文内でのカーソル位置（0-indexed 文字オフセット）
     * @param projectRoot     プロジェクトルート（配下の .java を解析対象に含める）
     */
    public Resolution resolve(String currentText, String currentFilePath,
                              int cursorOffset, Path projectRoot) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new NotFound("no system Java compiler");
        }
        // DiagnosticCollector を渡すことが重要: javac は DiagnosticListener が登録されて
        // いる場合のみ AST の終了位置テーブル（end positions）を保持する。これが無いと
        // SourcePositions.getEndPosition() が常に NOPOS を返し、ノード探索が機能しない。
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm =
                 compiler.getStandardFileManager(collector, Locale.ENGLISH, null)) {

            String mainPath = (currentFilePath != null) ? currentFilePath : "<buffer>";
            StringJavaFileObject mainFileObj = new StringJavaFileObject(mainPath, currentText);
            // javac はユーザー提供の JavaFileObject を ClientCodeWrapper でラップするため、
            // CompilationUnitTree.getSourceFile() はここで作ったインスタンスと参照一致しない。
            // URI（StringJavaFileObject が filePath から決定的に生成する）をキーに照合する。
            Map<URI, String> realPathByUri = new HashMap<>();
            realPathByUri.put(mainFileObj.toUri(), currentFilePath);

            List<JavaFileObject> sources = JavaSourceCollector.collect(mainFileObj,
                currentFilePath, projectRoot, realPathByUri);
            if (sources == null) {
                return new NotFound("too many source files under " + projectRoot);
            }

            JavacTask task = (JavacTask) compiler.getTask(
                null, fm, collector, List.of("-proc:none"), null, sources);

            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();

            URI mainUri = mainFileObj.toUri();
            CompilationUnitTree currentUnit = null;
            for (CompilationUnitTree u : units) {
                if (u.getSourceFile().toUri().equals(mainUri)) {
                    currentUnit = u;
                    break;
                }
            }
            if (currentUnit == null) {
                return new NotFound("current buffer did not parse");
            }

            Trees trees = Trees.instance(task);
            TreePath nodePath = findNodeAt(trees, currentUnit, cursorOffset);
            if (nodePath == null) {
                return new NotFound("no AST node at cursor");
            }
            Element element = trees.getElement(nodePath);
            if (element == null) {
                return new NotFound("unresolved binding at cursor");
            }

            TreePath declPath = trees.getPath(element);
            if (declPath != null) {
                return toProjectLocation(trees, declPath, element, realPathByUri);
            }
            return toJdkLocation(task, element);
        } catch (Exception | AssertionError e) {
            // 構文エラーの激しい書きかけコード等で javac 内部が例外を出すことがある。
            // graceful degradation（⑧ java-source-analysis と同方針）: 失敗として返し、
            // 呼び出し側の既存ヒューリスティックに委ねる。
            return new NotFound("analysis failed: " + e);
        }
    }

    /**
     * JDT の NodeFinder 相当: カーソルオフセットを [start, end) に含む最も内側の
     * AST ノードの TreePath を返す。TreePathScanner は親→子の順で走査するため、
     * オフセットを含むノードのうち最後に記録されたものが最内ノードになる。
     */
    private static TreePath findNodeAt(Trees trees, CompilationUnitTree unit, long offset) {
        SourcePositions positions = trees.getSourcePositions();
        class Finder extends TreePathScanner<Void, Void> {
            TreePath found = null;

            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree != null) {
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

    /** 宣言 TreePath（プロジェクト内ソース）を ProjectLocation に変換する。 */
    private static Resolution toProjectLocation(Trees trees, TreePath declPath, Element element,
                                                Map<URI, String> realPathByUri) {
        CompilationUnitTree declUnit = declPath.getCompilationUnit();
        SourcePositions positions = trees.getSourcePositions();
        long start = positions.getStartPosition(declUnit, declPath.getLeaf());
        if (start < 0) {
            return new NotFound("declaration has no source position");
        }
        LineMap lineMap = declUnit.getLineMap();
        int line = (int) lineMap.getLineNumber(start) - 1;
        int column = (int) lineMap.getColumnNumber(start) - 1;
        URI declUri = declUnit.getSourceFile().toUri();
        if (!realPathByUri.containsKey(declUri)) {
            return new NotFound("declaration in unknown source " + declUri);
        }
        String filePath = realPathByUri.get(declUri); // 現在バッファなら currentFilePath（null あり）
        return new ProjectLocation(filePath, line, column,
            element.getKind().toString().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    /**
     * ソース外（プラットフォームクラスパス＝JDK 等）の要素を JdkElementLocation に変換する。
     * src.zip のエントリはトップレベルクラス単位（java/util/Map.java 等）なので、
     * ネストクラスのメンバーでも最外殻の TypeElement まで遡って FQCN を決める。
     */
    private static Resolution toJdkLocation(JavacTask task, Element element) {
        TypeElement outermost = outermostTypeOf(element);
        if (outermost == null) {
            return new NotFound("element has no enclosing type: " + element.getKind());
        }
        String fqcn = outermost.getQualifiedName().toString();
        if (fqcn.isEmpty()) {
            return new NotFound("unresolved type at cursor");
        }
        String member;
        if (element.equals(outermost)) {
            member = null; // クラス自体 → ファイル先頭のまま
        } else if (element.getKind() == ElementKind.CONSTRUCTOR) {
            // コンストラクタの simple name は "<init>" のため、ソース上の宣言名（クラス名）で探す
            member = element.getEnclosingElement().getSimpleName().toString();
        } else {
            member = element.getSimpleName().toString();
        }
        ModuleElement module = task.getElements().getModuleOf(element);
        String moduleName = (module == null || module.isUnnamed())
            ? null : module.getQualifiedName().toString();
        return new JdkElementLocation(moduleName, fqcn, member);
    }

    /** element を包む最も外側の型（トップレベルクラス/インタフェース/enum/record）を返す。 */
    private static TypeElement outermostTypeOf(Element element) {
        TypeElement outermost = null;
        for (Element e = element; e != null; e = e.getEnclosingElement()) {
            if (e instanceof TypeElement type) {
                outermost = type;
            }
        }
        return outermost;
    }
}
