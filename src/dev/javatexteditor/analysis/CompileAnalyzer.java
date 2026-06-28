package dev.javatexteditor.analysis;

import com.sun.source.util.JavacTask;
import javax.tools.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/**
 * javax.tools.JavaCompiler を使って Java ソースを型解決まで解析し、
 * コンパイルエラー・警告を CompileDiagnostic のリストとして返す。
 *
 * SourceAnalyzer（parse-only）とは異なり、JavacTask.analyze() まで実行するため
 * 未定義型・型不一致などの意味エラーも検出できる。
 */
public class CompileAnalyzer {

    /**
     * バッファ文字列を直接解析してコンパイル診断を返す。
     * ファイルシステムへの書き出しは不要。
     */
    public List<CompileDiagnostic> analyze(String sourceCode) throws AnalysisException {
        return analyzeSource("<buffer>", sourceCode);
    }

    /**
     * ファイルパスからソースを読み込んでコンパイル診断を返す。
     */
    public List<CompileDiagnostic> analyzeFile(Path path) throws AnalysisException {
        String source;
        try {
            source = Files.readString(path);
        } catch (IOException e) {
            throw new AnalysisException("ファイルの読み込みに失敗しました: " + path, e);
        }
        return analyzeSource(path.toString(), source);
    }

    private List<CompileDiagnostic> analyzeSource(String filePath, String sourceCode)
            throws AnalysisException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AnalysisException(
                "JavaCompiler が見つかりません。JDK で実行してください。");
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();

        try (StandardJavaFileManager stdFm =
                compiler.getStandardFileManager(collector, null, null)) {

            StringJavaFileObject fileObj = new StringJavaFileObject(filePath, sourceCode);

            JavaCompiler.CompilationTask task = compiler.getTask(
                null, stdFm, collector,
                List.of("-proc:none"),
                null,
                List.of(fileObj)
            );

            JavacTask javacTask = (JavacTask) task;

            try {
                javacTask.parse();
                javacTask.analyze();
            } catch (IOException e) {
                throw new AnalysisException("解析中にエラーが発生しました", e);
            }

        } catch (AnalysisException e) {
            throw e;
        } catch (Exception e) {
            throw new AnalysisException(
                "解析中に予期しないエラーが発生しました: " + e.getMessage(), e);
        }

        return toDiagnostics(collector.getDiagnostics());
    }

    private List<CompileDiagnostic> toDiagnostics(
            List<Diagnostic<? extends JavaFileObject>> raw) {
        List<CompileDiagnostic> result = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : raw) {
            DiagnosticKind kind = switch (d.getKind()) {
                case ERROR         -> DiagnosticKind.ERROR;
                case WARNING,
                     MANDATORY_WARNING -> DiagnosticKind.WARNING;
                default            -> null;
            };
            if (kind == null) continue;

            // javac は 1-indexed の行番号を返す。0-indexed に変換。
            long rawLine = d.getLineNumber();
            int lineNumber = (rawLine > 0) ? (int) rawLine - 1 : 0;
            long rawCol = d.getColumnNumber();
            int column = (rawCol > 0) ? (int) rawCol - 1 : 0;

            result.add(new CompileDiagnostic(lineNumber, column, d.getMessage(null), kind));
        }
        return Collections.unmodifiableList(result);
    }

    /** 文字列ソースを JavaFileObject として渡すためのアダプタ */
    private static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final String source;

        StringJavaFileObject(String filePath, String source) {
            super(toUri(filePath), Kind.SOURCE);
            this.source = source;
        }

        private static URI toUri(String filePath) {
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
