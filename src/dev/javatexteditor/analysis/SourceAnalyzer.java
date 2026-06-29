package dev.javatexteditor.analysis;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.Locale;

/**
 * Compiler Tree API を使って Java ソースの AST を解析し、SourceIndex を生成する。
 * parse-only モードで動作するため型解決は行わず、構文エラーがあっても部分解析する。
 */
public class SourceAnalyzer {

    public SourceIndex analyzeText(String sourceCode) throws AnalysisException {
        return analyze("<buffer>", sourceCode);
    }

    public SourceIndex analyzeFile(Path path) throws AnalysisException {
        String source;
        try {
            source = Files.readString(path);
        } catch (IOException e) {
            throw new AnalysisException("ファイルの読み込みに失敗しました: " + path, e);
        }
        return analyze(path.toString(), source);
    }

    private SourceIndex analyze(String filePath, String sourceCode) throws AnalysisException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AnalysisException(
                "JavaCompiler が見つかりません。JDK で実行してください。");
        }

        List<ImportEntry> imports = new ArrayList<>();
        List<SymbolEntry> symbols = new ArrayList<>();
        boolean[] hasParseError = {false};

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager stdFm = compiler.getStandardFileManager(diagnostics, Locale.ENGLISH, null)) {
            StringJavaFileObject fileObj = new StringJavaFileObject(filePath, sourceCode);

            JavaCompiler.CompilationTask task = compiler.getTask(
                null, stdFm, diagnostics,
                List.of("-proc:none"),
                null,
                List.of(fileObj)
            );

            JavacTask javacTask = (JavacTask) task;

            Iterable<? extends CompilationUnitTree> units;
            try {
                units = javacTask.parse();
            } catch (IOException e) {
                throw new AnalysisException("解析中にエラーが発生しました", e);
            }

            // 構文エラーがあったか確認
            for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    hasParseError[0] = true;
                    break;
                }
            }

            Trees trees = Trees.instance(javacTask);

            for (CompilationUnitTree unit : units) {
                SourcePositions positions = trees.getSourcePositions();

                // import 文を収集
                for (ImportTree imp : unit.getImports()) {
                    String qualId = imp.getQualifiedIdentifier().toString();
                    boolean isWildcard = qualId.endsWith(".*");
                    String fqn = isWildcard ? qualId.substring(0, qualId.length() - 2) : qualId;

                    long startPos = positions.getStartPosition(unit, imp);
                    int lineNum = (int) unit.getLineMap().getLineNumber(startPos) - 1;

                    imports.add(new ImportEntry(fqn, imp.isStatic(), isWildcard, lineNum));
                }

                // トップレベルの型宣言を収集
                for (Tree typeDecl : unit.getTypeDecls()) {
                    collectTopLevelSymbols(typeDecl, unit, positions, symbols);
                }
            }
        } catch (AnalysisException e) {
            throw e;
        } catch (Exception e) {
            throw new AnalysisException("解析中に予期しないエラーが発生しました: " + e.getMessage(), e);
        }

        return new SourceIndex(filePath, Collections.unmodifiableList(imports),
                               Collections.unmodifiableList(symbols), hasParseError[0]);
    }

    private void collectTopLevelSymbols(Tree typeDecl, CompilationUnitTree unit,
                                        SourcePositions positions, List<SymbolEntry> symbols) {
        switch (typeDecl) {
            case ClassTree ct -> {
                SymbolKind kind = switch (ct.getKind()) {
                    case INTERFACE -> SymbolKind.INTERFACE;
                    case ENUM -> SymbolKind.ENUM;
                    default -> SymbolKind.CLASS;
                };

                long startPos = positions.getStartPosition(unit, ct);
                int lineNum = (int) unit.getLineMap().getLineNumber(startPos) - 1;
                int offset = (int) startPos;

                symbols.add(new SymbolEntry(ct.getSimpleName().toString(), kind, lineNum, offset));

                // クラス内のメソッド・フィールド・コンストラクタを収集（ネストしたクラスは除外）
                for (Tree member : ct.getMembers()) {
                    switch (member) {
                        case MethodTree mt -> {
                            long mStart = positions.getStartPosition(unit, mt);
                            int mLine = (int) unit.getLineMap().getLineNumber(mStart) - 1;
                            int mOffset = (int) mStart;
                            String name = mt.getName().toString();
                            SymbolKind mKind = name.equals("<init>")
                                ? SymbolKind.CONSTRUCTOR : SymbolKind.METHOD;
                            // コンストラクタ名はクラス名にする
                            String displayName = mKind == SymbolKind.CONSTRUCTOR
                                ? ct.getSimpleName().toString() : name;
                            symbols.add(new SymbolEntry(displayName, mKind, mLine, mOffset));
                        }
                        case VariableTree vt -> {
                            long vStart = positions.getStartPosition(unit, vt);
                            int vLine = (int) unit.getLineMap().getLineNumber(vStart) - 1;
                            int vOffset = (int) vStart;
                            symbols.add(new SymbolEntry(
                                vt.getName().toString(), SymbolKind.FIELD, vLine, vOffset));
                        }
                        default -> {} // ネストしたクラスは収集しない
                    }
                }
            }
            default -> {} // 型宣言以外（エラーノード等）は無視
        }
    }

    /** 文字列ソースを JavaFileObject として渡すためのアダプタ */
    private static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final String source;

        StringJavaFileObject(String filePath, String source) {
            super(toUri(filePath), Kind.SOURCE);
            this.source = source;
        }

        private static URI toUri(String filePath) {
            // Strip characters illegal in URI path segments
            String safe = filePath.replace('\\', '/')
                                  .replaceAll("[<>\"{}|\\\\^`\\[\\] ]", "_");
            return URI.create("string:///" + safe);
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
