package dev.vimacs.refactor;

import dev.vimacs.search.ProjectSearcher;
import dev.vimacs.search.SearchResult;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * プロジェクト内のシンボル（識別子）を全ファイルにわたって一括リネームする。
 *
 * 実装方針:
 *   - ProjectSearcher で \boldSymbol\b に一致するファイルを探索（Phase 1: 発見）
 *   - 各ファイルの内容を読み込み、java.util.regex で語境界付き置換（Phase 2: 置換）
 *   - 変更があったファイルを上書き保存（Phase 3: 適用）
 *   - 型解析（フルコンパイル）は行わない。単純な語境界マッチで誤マッチを最小化する。
 */
public class RenameRefactorer {

    private final ProjectSearcher searcher = new ProjectSearcher();

    /**
     * baseDir 配下の全テキストファイルで oldName を newName にリネームする。
     *
     * @param baseDir  検索の起点ディレクトリ
     * @param oldName  置換前の識別子（Java の単純名として有効な文字列）
     * @param newName  置換後の識別子
     * @return         ファイルごとのリネーム結果リスト（変更が1件以上あったファイルのみ）
     * @throws IllegalArgumentException oldName または newName が空の場合
     */
    public List<RenameResult> rename(Path baseDir, String oldName, String newName) {
        if (oldName == null || oldName.isBlank()) {
            throw new IllegalArgumentException("oldName must not be blank");
        }
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("newName must not be blank");
        }

        // Phase 1: 語境界付きパターンで候補ファイルを絞り込む
        String escapedOld = Pattern.quote(oldName);
        String searchPattern = "\\b" + escapedOld + "\\b";

        List<SearchResult> matches;
        try {
            matches = searcher.search(baseDir, searchPattern);
        } catch (PatternSyntaxException e) {
            // oldName が正規表現として問題ある場合（Pattern.quote で通常起きない）
            List<RenameResult> err = new ArrayList<>();
            err.add(new RenameResult("*search*", 0, false, "Bad pattern: " + e.getMessage()));
            return err;
        }

        if (matches.isEmpty()) {
            return List.of();
        }

        // 一致したファイルのパスを重複なしで収集（LinkedHashMap で発見順を保持）
        Map<String, Path> targetFiles = new LinkedHashMap<>();
        for (SearchResult r : matches) {
            if (!targetFiles.containsKey(r.filePath())) {
                targetFiles.put(r.filePath(), baseDir.resolve(r.filePath()));
            }
        }

        // Phase 2 & 3: ファイルごとに置換して上書き保存
        Pattern replacePattern = Pattern.compile(searchPattern);
        List<RenameResult> results = new ArrayList<>();

        for (Map.Entry<String, Path> entry : targetFiles.entrySet()) {
            String relPath = entry.getKey();
            Path absPath = entry.getValue();
            results.add(replaceInFile(absPath, relPath, replacePattern, newName));
        }

        return results;
    }

    /**
     * 1ファイルを置換して上書き保存し、結果を返す。
     * ファイル全体を1つの文字列として処理する（改行を保持したまま置換する）。
     */
    private RenameResult replaceInFile(Path absPath, String relPath,
                                       Pattern pattern, String newName) {
        String original;
        try {
            original = Files.readString(absPath, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return new RenameResult(relPath, 0, false, "not UTF-8 text");
        } catch (IOException e) {
            return new RenameResult(relPath, 0, false, e.getMessage());
        }

        // 置換を実行しながら件数を数える
        Matcher m = pattern.matcher(original);
        int count = 0;
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(newName));
            count++;
        }
        m.appendTail(sb);

        if (count == 0) {
            // 検索段階でマッチした行があっても、1ファイル全体で語境界マッチが0になるケースは
            // 理論上ないが、念のためスキップ
            return new RenameResult(relPath, 0, true, null);
        }

        String replaced = sb.toString();
        try {
            Files.writeString(absPath, replaced, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new RenameResult(relPath, count, false, "write failed: " + e.getMessage());
        }

        return new RenameResult(relPath, count, true, null);
    }

    /**
     * リネーム結果リストを *rename* バッファに表示するためのテキストを組み立てる。
     *
     * @param oldName  置換前のシンボル名
     * @param newName  置換後のシンボル名
     * @param results  rename() の戻り値
     * @return 表示用文字列（末尾に \n あり）
     */
    public static String buildDisplayText(String oldName, String newName, List<RenameResult> results) {
        StringBuilder sb = new StringBuilder();
        int totalFiles = results.size();
        int totalReplacements = results.stream().mapToInt(RenameResult::replacementCount).sum();
        int errorCount = (int) results.stream().filter(r -> !r.success()).count();

        sb.append("*rename* ").append(oldName).append(" → ").append(newName)
          .append(" — ").append(totalFiles).append(" file(s), ")
          .append(totalReplacements).append(" replacement(s)");
        if (errorCount > 0) {
            sb.append(", ").append(errorCount).append(" error(s)");
        }
        sb.append("\n");

        for (RenameResult r : results) {
            sb.append(r.toDisplayLine()).append("\n");
        }
        return sb.toString();
    }
}
