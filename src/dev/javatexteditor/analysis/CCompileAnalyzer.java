package dev.javatexteditor.analysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外部 C コンパイラ（gcc→clang→cc）を {@code -fsyntax-only} で起動し、現在編集中の1つの C バッファの
 * コンパイルエラー・警告を {@link CompileDiagnostic} のリストとして返す。Java 側の
 * {@link CompileAnalyzer}（{@code javax.tools.JavaCompiler} を使う）の C 版に相当する。
 *
 * <p>C にはインメモリで構文解析できる標準 API が無いため、バッファ内容を一時 {@code .c} ファイルへ
 * 書き出してからコンパイラを起動する。実ファイルパスが分かる場合はその親ディレクトリを {@code -I} で
 * 渡し、同一ディレクトリのローカルヘッダ（{@code #include "foo.h"}）を解決できるようにする。
 *
 * <p>コンパイラが PATH に無い環境では {@link AnalysisException} を投げる（呼び出し側で静かに
 * 診断なしにフォールバックする。Java 側で JavaCompiler が見つからない場合と同じ扱い）。
 */
public class CCompileAnalyzer {

    private static final List<String> COMPILER_CANDIDATES = List.of("gcc", "clang", "cc");

    // gcc/clang の診断行: "path:line:col: error|warning|note|fatal error: message"
    private static final Pattern DIAG_PATTERN =
        Pattern.compile("^(.*?):(\\d+):(\\d+):\\s+(fatal error|error|warning|note):\\s+(.*)$");

    /** バッファ文字列を直接解析する（ローカルヘッダの探索パスなし）。 */
    public List<CompileDiagnostic> analyze(String sourceCode) throws AnalysisException {
        return analyzeWithPath(null, sourceCode);
    }

    /**
     * バッファ文字列を解析する。filePath が非 null なら、その親ディレクトリを {@code -I} 探索パスに加え、
     * 診断のうちそのファイル（＝一時ファイル）に属するものだけを返す。
     */
    public List<CompileDiagnostic> analyzeWithPath(String filePath, String sourceCode)
            throws AnalysisException {
        String compiler = findCompiler();
        if (compiler == null) {
            throw new AnalysisException("Cコンパイラ（gcc/clang/cc）が見つかりません。");
        }

        Path temp;
        try {
            temp = Files.createTempFile("jte-cdiag-", ".c");
            Files.writeString(temp, sourceCode, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AnalysisException("一時ファイルの作成に失敗しました", e);
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(compiler);
            command.add("-fsyntax-only");
            command.add("-Wall");
            if (filePath != null) {
                Path parent = Path.of(filePath).toAbsolutePath().getParent();
                if (parent != null) {
                    command.add("-I");
                    command.add(parent.toString());
                }
            }
            command.add(temp.toString());

            List<CompileDiagnostic> result = new ArrayList<>();
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String tempPathStr = temp.toString();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    CompileDiagnostic d = parseDiagnostic(line, tempPathStr);
                    if (d != null) result.add(d);
                }
            }
            process.waitFor();
            return Collections.unmodifiableList(result);
        } catch (IOException e) {
            throw new AnalysisException("解析中にエラーが発生しました", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnalysisException("解析が中断されました", e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 一時ファイル削除失敗は無視
            }
        }
    }

    /** PATH 上で最初に見つかった C コンパイラ名を返す（無ければ null）。 */
    public String findCompiler() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String candidate : COMPILER_CANDIDATES) {
            for (String dir : pathEnv.split(Pattern.quote(java.io.File.pathSeparator))) {
                if (dir.isEmpty()) continue;
                Path p = Path.of(dir, candidate);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) return candidate;
                if (Files.isRegularFile(Path.of(dir, candidate + ".exe"))) return candidate;
            }
        }
        return null;
    }

    /**
     * gcc/clang の1行を CompileDiagnostic に変換する。note 行・診断でない行・onlyPath 以外の
     * ファイルを指す行は null（読み飛ばす）。onlyPath が null なら全ファイルを対象にする。
     */
    CompileDiagnostic parseDiagnostic(String line, String onlyPath) {
        Matcher m = DIAG_PATTERN.matcher(line);
        if (!m.matches()) return null;
        String kind = m.group(4);
        if (kind.equals("note")) return null;
        if (onlyPath != null && !m.group(1).equals(onlyPath)) return null;
        DiagnosticKind dk = (kind.equals("error") || kind.equals("fatal error"))
            ? DiagnosticKind.ERROR : DiagnosticKind.WARNING;
        int rawLine = Integer.parseInt(m.group(2));
        int rawCol = Integer.parseInt(m.group(3));
        int lineNumber = rawLine > 0 ? rawLine - 1 : 0;
        int column = rawCol > 0 ? rawCol - 1 : 0;
        return new CompileDiagnostic(lineNumber, column, m.group(5), dk);
    }
}
