package dev.javatexteditor.analysis;

import dev.javatexteditor.search.FileNameSearcher;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * javac の {@code -sourcepath} に渡す「ソースルート」（パッケージ階層の起点ディレクトリ）を求める。
 *
 * <p><b>なぜ必要か</b>: {@link CompileAnalyzer#analyzeWithProject} は以前、作業ディレクトリ配下の
 * 全 {@code .java} ファイルを {@code Files.walk} で列挙し、その中身を全て {@code String} として
 * 読み込んだ上で javac に丸ごと渡していた。これだとバッファを1文字書き換えるたびにプロジェクト全体の
 * parse + attribute が走り、このプロジェクト自身（約27,000行）を対象にした実測で
 * <b>1回あたり約400MBのアロケーション・約2〜3.5秒</b>を要していた。INSERT離脱・保存・
 * バッファ変更（400msデバウンス）のたびにこれが発生し、しかも複数の解析が同時並行で走りうるため、
 * 数分の編集でヒープが最大値（既定で物理メモリの1/4）まで膨張して戻らない、という症状になっていた。
 *
 * <p><b>解決方法</b>: javac の {@code -sourcepath} は「必要になったシンボルのソースだけを
 * 遅延的に読む」ための標準機能である。編集中のバッファ1件だけを明示的なコンパイル対象として渡し、
 * 他のプロジェクトソースは sourcepath 経由で javac に必要な分だけ読ませることで、
 * <b>同じ診断結果を保ったまま</b>アロケーションを1/10前後、所要時間を1/5〜1/35に削減できる
 * （実測値は {@code CompileAnalyzer} のクラスコメント参照）。
 *
 * <p>sourcepath に渡すディレクトリは「そのディレクトリからの相対パスがパッケージ名と一致する」
 * 起点でなければならない（例: {@code package dev.javatexteditor.ui;} のファイルが
 * {@code src/dev/javatexteditor/ui/EditorCanvas.java} にあるなら sourcepath は {@code src}）。
 * 本クラスはこれを、
 * <ol>
 *   <li>編集中ファイル自身のパスと {@code package} 宣言から直接導出する（常に最優先・キャッシュ不要）</li>
 *   <li>プロジェクトルート配下を1回だけ走査し、{@code .java} を含むディレクトリごとに
 *       先頭1ファイルの {@code package} 宣言を読んで導出する（結果はキャッシュする）</li>
 * </ol>
 * の2段構えで求める。1だけでも編集中ファイルと同じソースツリー内のクラスは解決できるため、
 * 2の走査に失敗する環境でも機能低下しない。
 */
final class JavaSourceRoots {

    /** ソースルート走査結果のキャッシュ有効期間。新しいパッケージ階層の追加を取りこぼさない程度に短くする。 */
    private static final long CACHE_TTL_NANOS = 60L * 1_000_000_000L;

    /** 1回の走査でパッケージ宣言を読むディレクトリ数の上限（巨大なツリーでの走査コストを抑える）。 */
    private static final int MAX_SCANNED_DIRS = 2000;

    /** パッケージ宣言を読むために先頭から読む最大行数（ライセンスコメントが長いファイルを想定）。 */
    private static final int MAX_HEADER_LINES = 200;

    private static final Pattern PACKAGE_DECL =
        Pattern.compile("^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)\\s*;");

    private static final Set<String> SKIP_DIRS = FileNameSearcher.SKIP_DIRS;

    private record CachedRoots(List<Path> roots, long computedAtNanos) {}

    private static final Map<Path, CachedRoots> CACHE = new ConcurrentHashMap<>();

    private JavaSourceRoots() {}

    /**
     * javac の {@code -sourcepath} にそのまま渡せる文字列（{@link java.io.File#pathSeparator} 区切り）を返す。
     * 該当するディレクトリが1つも無ければ空文字列を返す（呼び出し側は sourcepath 指定を省略する）。
     *
     * @param projectRoot     作業ディレクトリ（{@code :cd} で変わりうる）。null 可。
     * @param currentFilePath 編集中バッファのファイルパス。未保存バッファでは {@code "<buffer>"} 等になる。
     * @param currentSource   編集中バッファの内容（{@code package} 宣言の読み取りに使う）
     */
    static String sourcePathFor(Path projectRoot, String currentFilePath, String currentSource) {
        Set<Path> roots = new LinkedHashSet<>();
        Path fromCurrent = rootOfCurrentFile(currentFilePath, currentSource);
        if (fromCurrent != null) roots.add(fromCurrent);
        if (projectRoot != null) {
            roots.addAll(scanRootsCached(projectRoot));
        }
        if (roots.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Path root : roots) {
            if (sb.length() > 0) sb.append(java.io.File.pathSeparatorChar);
            sb.append(root);
        }
        return sb.toString();
    }

    /** テスト・再解析用にキャッシュを捨てる。 */
    static void clearCache() {
        CACHE.clear();
    }

    /**
     * 編集中ファイルのパスと {@code package} 宣言からソースルートを導出する。
     * 未保存バッファ（{@code "<buffer>"} 等、実在しないパス）や、ディレクトリ構成がパッケージ名と
     * 一致しない場合は null を返す。
     */
    private static Path rootOfCurrentFile(String currentFilePath, String currentSource) {
        if (currentFilePath == null || currentFilePath.isEmpty()) return null;
        Path file;
        try {
            file = Path.of(currentFilePath).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null; // "<buffer>" のようなパスとして不正な文字列
        }
        Path dir = file.getParent();
        if (dir == null) return null;
        return stripPackageDirs(dir, packageOf(currentSource));
    }

    /** ディレクトリ {@code dir} からパッケージ名の分だけ親へ遡ってソースルートを求める。 */
    private static Path stripPackageDirs(Path dir, String packageName) {
        if (packageName == null || packageName.isEmpty()) return dir;
        String[] segments = packageName.split("\\.");
        Path root = dir;
        for (int i = segments.length - 1; i >= 0; i--) {
            if (root == null) return null;
            Path name = root.getFileName();
            if (name == null || !name.toString().equals(segments[i])) {
                // ディレクトリ構成がパッケージ名と一致しない（javac も解決できない）
                return null;
            }
            root = root.getParent();
        }
        return root;
    }

    /** ソース文字列の先頭にある {@code package} 宣言を返す。無ければ空文字列（デフォルトパッケージ）。 */
    private static String packageOf(String source) {
        if (source == null) return "";
        int scanned = 0;
        int pos = 0;
        while (pos < source.length() && scanned < MAX_HEADER_LINES) {
            int eol = source.indexOf('\n', pos);
            String line = (eol < 0) ? source.substring(pos) : source.substring(pos, eol);
            String pkg = packageOfLine(line);
            if (pkg != null) return pkg;
            if (eol < 0) break;
            pos = eol + 1;
            scanned++;
        }
        return "";
    }

    /** 1行が {@code package} 宣言ならパッケージ名を、そうでなければ null を返す。 */
    private static String packageOfLine(String line) {
        Matcher m = PACKAGE_DECL.matcher(line);
        if (!m.find()) return null;
        return m.group(1).replaceAll("\\s+", "");
    }

    private static List<Path> scanRootsCached(Path projectRoot) {
        Path key;
        try {
            key = projectRoot.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return List.of();
        }
        CachedRoots cached = CACHE.get(key);
        long now = System.nanoTime();
        if (cached != null && now - cached.computedAtNanos() < CACHE_TTL_NANOS) {
            return cached.roots();
        }
        List<Path> roots = scanRoots(key);
        CACHE.put(key, new CachedRoots(roots, now));
        return roots;
    }

    /**
     * projectRoot 配下を1回走査し、{@code .java} ファイルを直接含む各ディレクトリについて
     * 先頭1ファイルだけ {@code package} 宣言を読んでソースルートを求める。
     * 同じソースルートを共有するディレクトリが多数あっても読むのは1ディレクトリにつき1ファイルなので、
     * 旧実装（全ファイルの全内容を読む）と違ってプロジェクト規模に対して十分軽い。
     *
     * <p><b>{@code package} 宣言を持つファイルから導出できたルートだけを採用する。</b>
     * 宣言が無い（デフォルトパッケージの）ファイルしか無いディレクトリを機械的にルートとして
     * 加えると、プロジェクトに同梱された第三者のソースツリーまで sourcepath に載ってしまう。
     * 実際にこのプロジェクトでは {@code lib/openjdk-native/hotspot/share/prims}
     * （OpenJDKのビルド用ツールで、パッケージ宣言の無い {@code .java} が置かれている）が
     * ルートとして拾われ、編集中のバッファが一時的に壊れてデフォルトパッケージ扱いになった際に
     * 「bad source file: ... file does not contain class Piece」という無関係なエラーが
     * 出る不具合が起きた。
     *
     * <p>デフォルトパッケージのファイルを編集している場合は
     * {@link #rootOfCurrentFile} が常にそのディレクトリ自身をソースルートとして加えるため、
     * このしぼり込みで平坦なレイアウトのプロジェクトが解決できなくなることはない。
     */
    private static List<Path> scanRoots(Path projectRoot) {
        Set<Path> roots = new LinkedHashSet<>();
        if (!Files.isDirectory(projectRoot)) return List.of();
        // 「そのディレクトリで最初に見つかった .java ファイル」だけを対象にするための記録
        Set<Path> dirsHandled = new LinkedHashSet<>();
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    if (name != null && (SKIP_DIRS.contains(name.toString())
                            || name.toString().startsWith("."))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile() || !file.toString().endsWith(".java")) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path dir = file.getParent();
                    if (dir == null || !dirsHandled.add(dir)) {
                        return FileVisitResult.CONTINUE; // このディレクトリは調査済み
                    }
                    String packageName = readPackageDeclaration(file);
                    if (!packageName.isEmpty()) {
                        Path root = stripPackageDirs(dir, packageName);
                        if (root != null) roots.add(root);
                    }
                    return dirsHandled.size() >= MAX_SCANNED_DIRS
                        ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // 走査できない場合は「編集中ファイルから導出したルートだけ」で動く（機能低下しない）
            return new ArrayList<>(roots);
        }
        return new ArrayList<>(roots);
    }

    /** ファイルの先頭から {@code package} 宣言だけを読む（全文は読まない）。 */
    private static String readPackageDeclaration(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int scanned = 0;
            while (scanned++ < MAX_HEADER_LINES && (line = reader.readLine()) != null) {
                String pkg = packageOfLine(line);
                if (pkg != null) return pkg;
            }
        } catch (IOException | RuntimeException e) {
            // 読めない・UTF-8でないファイルはデフォルトパッケージ扱いにフォールバックする
            return "";
        }
        return "";
    }
}
