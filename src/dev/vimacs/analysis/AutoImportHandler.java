package dev.vimacs.analysis;

import dev.vimacs.buffer.PieceTable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * コンパイルエラーから未解決シンボルを抽出し、import 文をバッファに自動挿入する。
 *
 * <p>候補が1件ならそのまま挿入。複数件なら呼び出し元が UI で選択させること。
 */
public class AutoImportHandler {

    /** "cannot find symbol" エラーから型名を抽出するパターン */
    private static final Pattern SYMBOL_PATTERN =
        Pattern.compile("symbol:\\s*(?:class|interface|enum)\\s+(\\w+)");

    private final ImportSuggester suggester;
    private final SourceAnalyzer sourceAnalyzer;

    public AutoImportHandler(ImportSuggester suggester, SourceAnalyzer sourceAnalyzer) {
        this.suggester = suggester;
        this.sourceAnalyzer = sourceAnalyzer;
    }

    /**
     * 診断リストから未解決シンボル名（単純名）を重複なしで返す。
     * "cannot find symbol" 系のエラーのみ対象。
     */
    public List<String> findMissingSymbols(List<CompileDiagnostic> diags) {
        List<String> result = new ArrayList<>();
        for (CompileDiagnostic d : diags) {
            if (d.kind() != DiagnosticKind.ERROR) continue;
            Matcher m = SYMBOL_PATTERN.matcher(d.message());
            if (m.find()) {
                String name = m.group(1);
                if (!result.contains(name)) result.add(name);
            }
        }
        return List.copyOf(result);
    }

    /**
     * ソース文字列を解析し、import 文を挿入すべき offset を返す。
     *
     * <ul>
     *   <li>既存の import 文があれば最後の import 行の次の行先頭</li>
     *   <li>package 宣言があれば package 行の次の行先頭</li>
     *   <li>どちらもなければ 0</li>
     * </ul>
     */
    public int findImportInsertOffset(String source) {
        String[] lines = source.split("\n", -1);
        int lastImportLine = -1;
        int packageLine = -1;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].stripLeading();
            if (trimmed.startsWith("import ")) lastImportLine = i;
            else if (trimmed.startsWith("package ") && packageLine < 0) packageLine = i;
        }

        int insertAfterLine = lastImportLine >= 0 ? lastImportLine
                            : packageLine >= 0      ? packageLine
                            : -1;

        if (insertAfterLine < 0) return 0;

        // 挿入対象行の末尾（改行文字の次）のオフセットを計算
        int offset = 0;
        for (int i = 0; i <= insertAfterLine; i++) {
            offset += lines[i].length() + 1; // +1 for '\n'
        }
        return offset;
    }

    /**
     * buffer の source を解析して import 行を挿入する。
     * 重複チェックのために SourceAnalyzer でソースを parse する。
     * parse に失敗した場合（JDK なし等）は重複チェックなしで挿入する。
     *
     * @return 実際に挿入された FQN リスト（重複でスキップされたものは含まない）
     */
    public List<String> applyImports(List<String> fqns, PieceTable buffer) {
        String source = buffer.getText();
        List<String> alreadyImported = getAlreadyImported(source);
        List<String> inserted = new ArrayList<>();

        for (String fqn : fqns) {
            if (alreadyImported.contains(fqn)) continue;
            int offset = findImportInsertOffset(buffer.getText());
            String line = "import " + fqn + ";\n";
            buffer.insert(offset, line);
            alreadyImported.add(fqn); // update for subsequent iterations
            inserted.add(fqn);
        }
        return List.copyOf(inserted);
    }

    /**
     * 単一 FQN をバッファに挿入する。重複の場合は挿入しない。
     *
     * @return 挿入したなら true、すでに存在したなら false
     */
    public boolean applyImport(String fqn, PieceTable buffer) {
        List<String> alreadyImported = getAlreadyImported(buffer.getText());
        if (alreadyImported.contains(fqn)) return false;
        int offset = findImportInsertOffset(buffer.getText());
        buffer.insert(offset, "import " + fqn + ";\n");
        return true;
    }

    /**
     * 診断リストからシンボルを抽出し、候補 FQN を単純名ごとにまとめて返す。
     * 自動挿入するか UI で選択させるかは呼び出し元が判断する。
     *
     * @param diags    コンパイル診断
     * @param source   現在のソースコード（重複チェック用）
     * @return 単純名 → FQN 候補リスト のマップ（候補ゼロの単純名は含まない）
     */
    public Map<String, List<String>> resolveCandidates(
            List<CompileDiagnostic> diags, String source) {
        List<String> missing = findMissingSymbols(diags);
        List<String> alreadyImported = getAlreadyImported(source);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String name : missing) {
            List<String> candidates = suggester.suggest(name).stream()
                .filter(fqn -> !alreadyImported.contains(fqn))
                .toList();
            if (!candidates.isEmpty()) result.put(name, candidates);
        }
        return result;
    }

    // ----- private helpers -----

    private List<String> getAlreadyImported(String source) {
        try {
            SourceIndex idx = sourceAnalyzer.analyzeText(source);
            List<String> list = new ArrayList<>();
            for (ImportEntry e : idx.imports()) list.add(e.fullyQualifiedName());
            return list;
        } catch (AnalysisException e) {
            return new ArrayList<>();
        }
    }
}
