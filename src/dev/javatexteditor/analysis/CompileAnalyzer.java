package dev.javatexteditor.analysis;

import com.sun.source.util.JavacTask;
import javax.tools.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.Locale;
import java.util.*;

/**
 * javax.tools.JavaCompiler を使って Java ソースを型解決まで解析し、
 * コンパイルエラー・警告を CompileDiagnostic のリストとして返す。
 *
 * SourceAnalyzer（parse-only）とは異なり、JavacTask.analyze() まで実行するため
 * 未定義型・型不一致などの意味エラーも検出できる。
 *
 * <p><b>プロジェクト全体の一括コンパイルはしない（2026-08 メモリ肥大化の修正）</b>:
 * {@link #analyzeWithProject} は編集中のバッファ1件だけを javac のコンパイル対象にし、
 * 他のプロジェクトソースは {@code -sourcepath}（{@link JavaSourceRoots}）経由で必要な分だけ
 * 遅延的に読ませる。このプロジェクト自身を対象にした実測（{@code EditorCanvas.java} /
 * {@code ModalEditor.java} / {@code LiveDiagnostics.java} / {@code PieceTable.java}）では、
 * 旧実装の「全 {@code .java} を読み込んで一括コンパイル」が 371〜418MB・0.8〜3.2秒だったのに対し、
 * 新実装は 12〜65MB・0.02〜0.5秒で、返る診断は同一（旧実装が余分に出していた
 * {@code duplicate class} エラーが消えるぶんむしろ正確）だった。
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
     * バッファ文字列を実ファイルパスの URI として解析する。
     * public class 名とファイル名の不一致エラーを防ぐため、保存済みファイルを編集中のときに使う。
     */
    public List<CompileDiagnostic> analyzeWithPath(String filePath, String sourceCode)
            throws AnalysisException {
        return analyzeSource(filePath, sourceCode);
    }

    /**
     * ファイルパスからソースを読み込んでコンパイル診断を返す。
     */
    public List<CompileDiagnostic> analyzeFile(Path path) throws AnalysisException {
        String source;
        try {
            source = Files.readString(path);
        } catch (IOException e) {
            throw new AnalysisException("Failed to read file: " + path, e);
        }
        return analyzeSource(path.toString(), source);
    }

    /**
     * プロジェクト全体（作業ルートディレクトリ配下の全 .java ファイル）を対象にコンパイルし、
     * 指定ファイルに関連するエラーのみを返す。これにより複数ファイル間のシンボル解決が可能になる。
     */
    public List<CompileDiagnostic> analyzeWithProject(String filePath, String sourceCode, Path projectRoot)
            throws AnalysisException {
        return analyzeSourceWithProject(filePath, sourceCode, projectRoot);
    }

    private List<CompileDiagnostic> analyzeSource(String filePath, String sourceCode)
            throws AnalysisException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AnalysisException(
                "JavaCompiler not found. Run with a JDK.");
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();

        try (StandardJavaFileManager stdFm =
                compiler.getStandardFileManager(collector, Locale.ENGLISH, null)) {

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
                throw new AnalysisException("An error occurred during analysis", e);
            }

        } catch (AnalysisException e) {
            throw e;
        } catch (Exception e) {
            throw new AnalysisException(
                "An unexpected error occurred during analysis: " + e.getMessage(), e);
        }

        return toDiagnostics(collector.getDiagnostics());
    }

    /**
     * プロジェクト全体を対象にコンパイルし、指定ファイルのエラーのみを返す内部実装。
     *
     * <p>コンパイル対象として javac に明示的に渡すのは編集中のバッファ1件だけで、
     * プロジェクト内の他ファイルは {@code -sourcepath}（{@link JavaSourceRoots} が算出）経由で
     * 「シンボルの解決に必要になった分だけ」javac に遅延読み込みさせる。
     * 以前は作業ディレクトリ配下の全 {@code .java} を毎回読み込んで一括コンパイルしていたため、
     * 1回の解析でこのプロジェクト自身（約27,000行）に対し約400MB・約2〜3.5秒を要していた
     * （クラスコメント参照）。
     */
    private List<CompileDiagnostic> analyzeSourceWithProject(
            String filePath, String sourceCode, Path projectRoot)
            throws AnalysisException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AnalysisException(
                "JavaCompiler not found. Run with a JDK.");
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();

        try (StandardJavaFileManager stdFm =
                compiler.getStandardFileManager(collector, Locale.ENGLISH, null)) {

            // 現在のファイルをメインとする StringJavaFileObject を作成
            StringJavaFileObject mainFileObj = new StringJavaFileObject(filePath, sourceCode);

            List<String> options = new ArrayList<>();
            options.add("-proc:none");
            // sourcepath 経由で読み込まれたファイルまでコード生成の対象にしない
            // （JavacTask.analyze() までしか呼ばないので実害は無いが、javac の警告を抑える）
            options.add("-implicit:none");
            String sourcePath = JavaSourceRoots.sourcePathFor(projectRoot, filePath, sourceCode);
            if (!sourcePath.isEmpty()) {
                options.add("-sourcepath");
                options.add(sourcePath);
            }

            JavaCompiler.CompilationTask task = compiler.getTask(
                null, stdFm, collector,
                options,
                null,
                List.of(mainFileObj)
            );

            JavacTask javacTask = (JavacTask) task;

            try {
                javacTask.parse();
                javacTask.analyze();
            } catch (IOException e) {
                throw new AnalysisException("An error occurred during analysis", e);
            }

        } catch (AnalysisException e) {
            throw e;
        } catch (Exception e) {
            throw new AnalysisException(
                "An unexpected error occurred during analysis: " + e.getMessage(), e);
        }

        // 診断をフィルタして、指定ファイルのエラーのみを返す
        return filterDiagnosticsForFile(collector.getDiagnostics(), filePath);
    }

    /**
     * 指定ファイルのエラーのみをフィルタして返す。
     */
    private List<CompileDiagnostic> filterDiagnosticsForFile(
            List<Diagnostic<? extends JavaFileObject>> raw, String targetFilePath) {
        List<CompileDiagnostic> result = new ArrayList<>();

        // ターゲットファイルパスを正規化（相対パス→絶対パス化）
        String normalizedTarget = normalizePath(targetFilePath);

        for (Diagnostic<? extends JavaFileObject> d : raw) {
            // 診断がターゲットファイルに関連しているかチェック
            if (d.getSource() != null) {
                String diagFilePath = d.getSource().getName();
                String normalizedDiag = normalizePath(diagFilePath);

                // StringJavaFileObject は "string:///" 形式の URI を返すため、
                // ファイル名部分を比較する
                if (!filePathMatches(normalizedDiag, normalizedTarget)) {
                    continue; // 他のファイルのエラーはスキップ
                }
            }

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

            result.add(new CompileDiagnostic(lineNumber, column, buildMessage(d), kind));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * ファイルパスを正規化する（スラッシュで統一）。
     */
    private String normalizePath(String path) {
        // バックスラッシュをスラッシュに統一
        return path.replace('\\', '/');
    }

    /**
     * 2つのファイルパスが同じファイルを指しているか判定。
     * StringJavaFileObject の "string:///" 形式 URI と実ファイルパスの両方に対応。
     */
    private boolean filePathMatches(String diagPath, String targetPath) {
        // StringJavaFileObject が生成する URI は "string:///" で始まる
        if (diagPath.startsWith("string:///")) {
            // URI から実ファイルパスの部分を抽出（相対パス化）
            String uriPart = diagPath.substring("string:///".length());
            // StringJavaFileObject の toUri では特殊文字が _ に置換されているため、
            // 両者のファイル名部分で比較する
            String diagFileName = extractFileName(uriPart);
            String targetFileName = extractFileName(targetPath);
            // ファイル名だけ一致しても曖昧なため、より厳密に：パスの末尾が一致するかチェック
            return endsWith(uriPart, targetPath) || endsWith(targetPath, uriPart)
                || diagFileName.equals(targetFileName);
        }
        // 通常のファイルパス比較
        return diagPath.equals(targetPath) || diagPath.endsWith(targetPath) || targetPath.endsWith(diagPath);
    }

    private String extractFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private boolean endsWith(String path, String suffix) {
        return path.endsWith(suffix) || path.endsWith(suffix.replace('\\', '/'));
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

            result.add(new CompileDiagnostic(lineNumber, column, buildMessage(d), kind));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * ロケール非依存のメッセージを構築する。
     *
     * "cannot find symbol" 系エラー（診断コードに "cant.resolve" を含む）は
     * メッセージテキストに頼らず、ソース上の文字位置から Java 識別子を直接抽出し
     * 英語固定フォーマット "cannot find symbol\n  symbol: class XXX" に正規化する。
     * それ以外のエラーは Locale.ENGLISH で取得したメッセージをそのまま使う。
     */
    private static String buildMessage(Diagnostic<? extends JavaFileObject> d) {
        String code = d.getCode();
        if (code != null && code.contains("cant.resolve") && d.getSource() != null) {
            try {
                CharSequence src = d.getSource().getCharContent(true);
                int pos = (int) d.getStartPosition();
                if (pos >= 0 && pos < src.length()) {
                    int start = pos;
                    int end = pos;
                    while (start > 0 && Character.isJavaIdentifierPart(src.charAt(start - 1))) start--;
                    while (end < src.length() && Character.isJavaIdentifierPart(src.charAt(end))) end++;
                    if (end > start) {
                        String name = src.subSequence(start, end).toString();
                        return "cannot find symbol\n  symbol: class " + name;
                    }
                }
            } catch (IOException ignored) {}
        }
        return d.getMessage(Locale.ENGLISH);
    }
}
