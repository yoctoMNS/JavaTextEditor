package dev.javatexteditor.projectbuild;

import dev.javatexteditor.search.FileNameSearcher;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * F10: projectRoot 配下の全 .java ファイルを javax.tools.JavaCompiler でコンパイルし、
 * .class を projectRoot/{@value #OUTPUT_DIR_NAME} に出力する。
 * F11（実行）はこの出力先ディレクトリをクラスパスとして java コマンドを起動する。
 */
public class ProjectBuilder {

    /** コンパイル済み .class の出力先ディレクトリ名（projectRoot からの相対）。 */
    public static final String OUTPUT_DIR_NAME = "bin";

    private static final Set<String> SKIP_DIRS = buildSkipDirs();

    private static Set<String> buildSkipDirs() {
        Set<String> s = new HashSet<>(FileNameSearcher.SKIP_DIRS);
        s.add(OUTPUT_DIR_NAME);
        return Set.copyOf(s);
    }

    public BuildResult compile(Path projectRoot) {
        return compile(projectRoot, List.of());
    }

    /**
     * F10/F12: extraClasspath（ユーザーが指定した追加ディレクトリ）を javac のクラスパスに
     * 追加した上でコンパイルする。空リストなら従来どおりクラスパス指定なし。
     */
    public BuildResult compile(Path projectRoot, List<Path> extraClasspath) {
        return compile(projectRoot, extraClasspath, d -> { });
    }

    /**
     * extraClasspathに加え、コンパイル中にjavacが診断を報告するたびonDiagnosticへ即座に通知する
     * （*compile*疑似バッファへのリアルタイム表示用）。onDiagnosticはコンパイルを実行している
     * スレッド上で同期的に呼ばれるため、UIスレッドへのディスパッチは呼び出し側の責務とする
     * （ProjectBuilder自体はSwingに依存しない設計を維持する）。
     */
    public BuildResult compile(Path projectRoot, List<Path> extraClasspath, Consumer<BuildDiagnostic> onDiagnostic) {
        List<Path> sources;
        try {
            sources = collectJavaFiles(projectRoot);
        } catch (IOException e) {
            return new BuildResult(false, 0, List.of(), "ソース走査に失敗しました: " + e.getMessage(), "");
        }
        if (sources.isEmpty()) {
            return new BuildResult(false, 0, List.of(),
                "コンパイル対象の.javaファイルが見つかりません: " + projectRoot, "");
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new BuildResult(false, 0, List.of(),
                "JavaCompilerが見つかりません。JDKで実行してください。", "");
        }

        Path binDir = binDirFor(projectRoot);
        try {
            Files.createDirectories(binDir);
        } catch (IOException e) {
            return new BuildResult(false, 0, List.of(), "出力先ディレクトリを作成できません: " + binDir, "");
        }

        List<String> options = buildCompilerOptions(binDir, extraClasspath);
        String command = buildJavacCommand(options, sources);

        List<BuildDiagnostic> diagnostics = new ArrayList<>();
        DiagnosticListener<JavaFileObject> listener = diagnostic -> {
            BuildDiagnostic bd = toDiagnostic(diagnostic);
            if (bd != null) {
                diagnostics.add(bd);
                onDiagnostic.accept(bd);
            }
        };
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(listener, Locale.ENGLISH, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(binDir.toFile()));
            if (!extraClasspath.isEmpty()) {
                fm.setLocation(StandardLocation.CLASS_PATH,
                    extraClasspath.stream().map(Path::toFile).toList());
            }
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sources);

            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, listener, List.of("-proc:none"), null, units);
            boolean called = Boolean.TRUE.equals(task.call());

            boolean hasErrors = diagnostics.stream().anyMatch(BuildDiagnostic::isError);
            return new BuildResult(called && !hasErrors, sources.size(), diagnostics, null, command);
        } catch (IOException e) {
            return new BuildResult(false, sources.size(), List.of(),
                "コンパイル中にエラーが発生しました: " + e.getMessage(), command);
        }
    }

    /** javax.tools.JavaCompiler に実際に渡したオプション（-d/-cp/-proc:none）の表示用リストを組み立てる。 */
    private List<String> buildCompilerOptions(Path binDir, List<Path> extraClasspath) {
        List<String> options = new ArrayList<>();
        options.add("-d");
        options.add(binDir.toString());
        if (!extraClasspath.isEmpty()) {
            options.add("-cp");
            options.add(extraClasspath.stream().map(Path::toString)
                .reduce((a, b) -> a + File.pathSeparatorChar + b).orElse(""));
        }
        options.add("-proc:none");
        return options;
    }

    /** *compile* 疑似バッファの先頭に表示する、実際に発行したjavac相当のコマンド文字列。 */
    private String buildJavacCommand(List<String> options, List<Path> sources) {
        StringBuilder sb = new StringBuilder("javac");
        for (String opt : options) sb.append(' ').append(opt);
        for (Path src : sources) sb.append(' ').append(src);
        return sb.toString();
    }

    /** F11: bin/ に .class が1つでもあれば true（未コンパイルなら実行を拒否するためのガード）。 */
    public boolean hasCompiledClasses(Path projectRoot) {
        Path binDir = binDirFor(projectRoot);
        if (!Files.isDirectory(binDir)) return false;
        try (var stream = Files.walk(binDir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".class"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * F10/F11/F12 の .class 出力先（{@value #OUTPUT_DIR_NAME}/）を解決する。
     * projectRoot が src フォルダの兄弟位置（＝projectRoot/src が存在する）ならそのまま
     * projectRoot/bin を使う。projectRoot が src フォルダ配下の任意の深さのディレクトリ
     * （例: :cd でプロジェクトルート配下のパッケージディレクトリに移動している場合）である
     * 場合は、祖先ディレクトリを遡って最初に src 子ディレクトリを持つディレクトリを探し、
     * そこを基準に bin を置く。どの祖先にも src が見つからない場合は projectRoot 自身に
     * フォールバックする（従来どおり projectRoot/bin）。
     */
    public Path binDirFor(Path projectRoot) {
        return resolveProjectBaseDir(projectRoot).resolve(OUTPUT_DIR_NAME);
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

    private List<Path> collectJavaFiles(Path projectRoot) throws IOException {
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
                if (file.toString().endsWith(".java")) result.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    /** ERROR/WARNING以外（NOTE等）はnullを返し、呼び出し側で読み飛ばす。 */
    private BuildDiagnostic toDiagnostic(Diagnostic<? extends JavaFileObject> d) {
        Diagnostic.Kind kind = d.getKind();
        boolean isError = kind == Diagnostic.Kind.ERROR;
        boolean isWarning = kind == Diagnostic.Kind.WARNING || kind == Diagnostic.Kind.MANDATORY_WARNING;
        if (!isError && !isWarning) return null;

        String path = (d.getSource() != null) ? d.getSource().getName() : "?";
        long rawLine = d.getLineNumber();
        int line = rawLine > 0 ? (int) rawLine - 1 : 0;
        long rawCol = d.getColumnNumber();
        int col = rawCol > 0 ? (int) rawCol - 1 : 0;

        return new BuildDiagnostic(path, line, col, d.getMessage(Locale.ENGLISH), isError);
    }
}
