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
            throw new AnalysisException("ファイルの読み込みに失敗しました: " + path, e);
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
                "JavaCompiler が見つかりません。JDK で実行してください。");
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

    /**
     * プロジェクト全体を対象にコンパイルし、指定ファイルのエラーのみを返す内部実装。
     */
    private List<CompileDiagnostic> analyzeSourceWithProject(
            String filePath, String sourceCode, Path projectRoot)
            throws AnalysisException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AnalysisException(
                "JavaCompiler が見つかりません。JDK で実行してください。");
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();

        try (StandardJavaFileManager stdFm =
                compiler.getStandardFileManager(collector, Locale.ENGLISH, null)) {

            // 現在のファイルをメインとする StringJavaFileObject を作成
            StringJavaFileObject mainFileObj = new StringJavaFileObject(filePath, sourceCode);

            // プロジェクト全体の .java ファイルを収集
            List<JavaFileObject> allSources = new ArrayList<>();
            allSources.add(mainFileObj);

            try {
                // projectRoot を起点に .java ファイルを再帰的に走査
                Files.walk(projectRoot)
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .filter(p -> !filePath.equals(p.toString())) // メインファイルは既に追加
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            allSources.add(new StringJavaFileObject(p.toString(), content));
                        } catch (IOException e) {
                            // ファイルの読み込み失敗は無視（ビルド対象外と扱う）
                        }
                    });
            } catch (IOException e) {
                throw new AnalysisException("プロジェクトディレクトリの走査に失敗しました: " + projectRoot, e);
            }

            JavaCompiler.CompilationTask task = compiler.getTask(
                null, stdFm, collector,
                List.of("-proc:none"),
                null,
                allSources
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
