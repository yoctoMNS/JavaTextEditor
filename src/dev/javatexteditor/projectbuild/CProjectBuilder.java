package dev.javatexteditor.projectbuild;

import dev.javatexteditor.search.FileNameSearcher;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F10（C版）: projectRoot 配下の全 .c ファイルを外部 C コンパイラ（gcc→clang→cc の順に検出）で
 * 1つの実行ファイルにコンパイル・リンクし、{@code bin/}（{@link ProjectBuilder#binDirFor} と
 * 同じ規則で解決）配下に {@value #EXECUTABLE_NAME} として出力する。F11（実行）はこの実行ファイルを
 * そのまま起動する。
 *
 * <p>Java 側の {@link ProjectBuilder} が {@code javax.tools.JavaCompiler}（JDK 標準 API）を使う一方、
 * C にはそれに相当する標準 API が存在しないため、{@link ProcessBuilder} で外部コンパイラを起動する
 * （F11 が {@code java} を別プロセスで起動するのと同じ方式）。コンパイラが PATH に無い環境では
 * 静かに失敗し {@link BuildResult#errorMessage()} にその旨を返す（Java 側で JavaCompiler が
 * 見つからない場合と同じ graceful degradation）。
 *
 * <p><b>複数 main の制約</b>: 全 .c を1つの実行ファイルにリンクするため、独立した複数プログラム
 * （それぞれ {@code main} を持つ .c ファイル群）が同一 projectRoot 配下にある場合はリンカが
 * "multiple definition of main" エラーを返す。これは意図した挙動で、エラーはそのまま
 * *compile* 疑似バッファに表示される（1プログラム=複数ファイルという典型的な構成を主対象とする）。
 */
public class CProjectBuilder {

    /** コンパイル済み実行ファイル名（bin/ からの相対）。 */
    public static final String EXECUTABLE_NAME = "a.out";

    /** 検出を試みる C コンパイラ（先に見つかったものを使う）。 */
    private static final List<String> COMPILER_CANDIDATES = List.of("gcc", "clang", "cc");

    private static final Set<String> SKIP_DIRS = buildSkipDirs();

    private static Set<String> buildSkipDirs() {
        Set<String> s = new HashSet<>(FileNameSearcher.SKIP_DIRS);
        s.add(ProjectBuilder.OUTPUT_DIR_NAME);
        return Set.copyOf(s);
    }

    // gcc/clang の診断行: "path:line:col: error|warning|note|fatal error: message"
    private static final Pattern DIAG_PATTERN =
        Pattern.compile("^(.*?):(\\d+):(\\d+):\\s+(fatal error|error|warning|note):\\s+(.*)$");

    public BuildResult compile(Path projectRoot) {
        return compile(projectRoot, d -> { });
    }

    /**
     * projectRoot 配下の全 .c を1つの実行ファイルにコンパイルする。診断を検出するたび onDiagnostic に
     * 通知する（*compile* 疑似バッファへのリアルタイム表示用。呼び出し側で EDT へディスパッチする）。
     */
    public BuildResult compile(Path projectRoot, Consumer<BuildDiagnostic> onDiagnostic) {
        // 相対 projectRoot + pb.directory() の組み合わせでソースパスが二重解決される事故を防ぐため、
        // 走査・出力・プロセス起動に使う前に絶対パスへ正規化する（実運用の getBuildRoot() は既に
        // 絶対パスだが、防御的に統一する）。
        projectRoot = projectRoot.toAbsolutePath().normalize();
        List<Path> sources;
        try {
            sources = collectCFiles(projectRoot);
        } catch (IOException e) {
            return new BuildResult(false, 0, List.of(), "ソース走査に失敗しました: " + e.getMessage(), "");
        }
        if (sources.isEmpty()) {
            return new BuildResult(false, 0, List.of(),
                "コンパイル対象の.cファイルが見つかりません: " + projectRoot, "");
        }

        String compiler = findCompiler();
        if (compiler == null) {
            return new BuildResult(false, 0, List.of(),
                "Cコンパイラ（gcc/clang/cc）が見つかりません。PATHを確認してください。", "");
        }

        Path binDir = binDirFor(projectRoot);
        try {
            Files.createDirectories(binDir);
        } catch (IOException e) {
            return new BuildResult(false, 0, List.of(), "出力先ディレクトリを作成できません: " + binDir, "");
        }
        Path executable = binDir.resolve(EXECUTABLE_NAME);

        List<String> command = new ArrayList<>();
        command.add(compiler);
        command.add("-Wall");
        command.add("-o");
        command.add(executable.toString());
        for (Path src : sources) command.add(src.toString());

        List<BuildDiagnostic> diagnostics = new ArrayList<>();
        int exitCode;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.directory(projectRoot.toFile());
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    BuildDiagnostic d = parseDiagnostic(line);
                    if (d != null) {
                        diagnostics.add(d);
                        onDiagnostic.accept(d);
                    }
                }
            }
            exitCode = process.waitFor();
        } catch (IOException e) {
            return new BuildResult(false, sources.size(), List.of(),
                "コンパイル中にエラーが発生しました: " + e.getMessage(), String.join(" ", command));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BuildResult(false, sources.size(), List.of(),
                "コンパイルが中断されました", String.join(" ", command));
        }

        boolean success = exitCode == 0;
        return new BuildResult(success, sources.size(), diagnostics, null, String.join(" ", command));
    }

    /** F11: 実行ファイルが存在すれば true（未コンパイルなら実行を拒否するためのガード）。 */
    public boolean hasExecutable(Path projectRoot) {
        return Files.isRegularFile(executableFor(projectRoot));
    }

    /** F11 の実行対象となる実行ファイルの絶対パス。 */
    public Path executableFor(Path projectRoot) {
        return binDirFor(projectRoot).resolve(EXECUTABLE_NAME);
    }

    /** PATH 上で最初に見つかった C コンパイラ名を返す（無ければ null）。 */
    public String findCompiler() {
        for (String candidate : COMPILER_CANDIDATES) {
            if (isOnPath(candidate)) return candidate;
        }
        return null;
    }

    private boolean isOnPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return false;
        for (String dir : pathEnv.split(Pattern.quote(java.io.File.pathSeparator))) {
            if (dir.isEmpty()) continue;
            Path candidate = Path.of(dir, executable);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return true;
            // Windows 実行ファイルの拡張子も一応試す
            Path candidateExe = Path.of(dir, executable + ".exe");
            if (Files.isRegularFile(candidateExe)) return true;
        }
        return false;
    }

    /**
     * C 版の bin/ 配置は Java 版と同じ規則（{@link ProjectBuilder#binDirFor}）に揃える。
     * projectRoot から祖先を遡り src を直下に持つ最初のディレクトリの bin/ を使う。
     * 見つからなければ projectRoot/bin にフォールバックする。
     */
    public Path binDirFor(Path projectRoot) {
        return resolveProjectBaseDir(projectRoot).resolve(ProjectBuilder.OUTPUT_DIR_NAME);
    }

    private Path resolveProjectBaseDir(Path projectRoot) {
        Path dir = projectRoot.toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("src"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return projectRoot;
    }

    private List<Path> collectCFiles(Path projectRoot) throws IOException {
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(projectRoot) && SKIP_DIRS.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".c")) result.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    /** gcc/clang の1行を BuildDiagnostic に変換する。note 行・診断でない行は null（読み飛ばす）。 */
    BuildDiagnostic parseDiagnostic(String line) {
        Matcher m = DIAG_PATTERN.matcher(line);
        if (!m.matches()) return null;
        String kind = m.group(4);
        if (kind.equals("note")) return null; // javac の NOTE 相当。ガター表示しない
        boolean isError = kind.equals("error") || kind.equals("fatal error");
        String path = m.group(1);
        int rawLine = Integer.parseInt(m.group(2));
        int rawCol = Integer.parseInt(m.group(3));
        int lineNumber = rawLine > 0 ? rawLine - 1 : 0;
        int column = rawCol > 0 ? rawCol - 1 : 0;
        return new BuildDiagnostic(path, lineNumber, column, m.group(5), isError);
    }
}
