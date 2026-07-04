package dev.javatexteditor.analysis;

import dev.javatexteditor.buffer.PieceTable;
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
        if (!inserted.isEmpty()) ensureBlankLineAfterImports(buffer);
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
        ensureBlankLineAfterImports(buffer);
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

    /**
     * バッファから特定の import 行を削除する。
     * 対象 import が存在しない場合は何もしない。
     *
     * @return 削除したなら true、対象が見つからなかったなら false
     */
    public boolean removeImport(String fqn, PieceTable buffer) {
        boolean removed = deleteImportLine(fqn, buffer);
        if (removed) ensureBlankLineAfterImports(buffer);
        return removed;
    }

    /**
     * ソース内で参照されていない import の FQN リストを返す。
     * ワイルドカード import・static import は常に「使用中」とみなし対象外。
     * SourceAnalyzer が使えない場合は空リストを返す。
     */
    public List<String> findUnusedImports(String source) {
        SourceIndex idx;
        try {
            idx = sourceAnalyzer.analyzeText(source);
        } catch (AnalysisException e) {
            return List.of();
        }

        if (idx.imports().isEmpty()) return List.of();

        // import 行より後のコード部分を対象にする
        String[] lines = source.split("\n", -1);
        int lastImportLine = idx.imports().stream()
                .mapToInt(ImportEntry::lineNumber).max().orElse(-1);

        StringBuilder body = new StringBuilder();
        for (int i = lastImportLine + 1; i < lines.length; i++) {
            body.append(lines[i]).append('\n');
        }
        String bodyStr = body.toString();

        List<String> unused = new ArrayList<>();
        for (ImportEntry e : idx.imports()) {
            if (e.isWildcard() || e.isStatic()) continue;
            String simple = simpleName(e.fullyQualifiedName());
            if (!bodyStr.contains(simple)) {
                unused.add(e.fullyQualifiedName());
            }
        }
        return List.copyOf(unused);
    }

    /**
     * バッファから未使用の import をすべて削除する。
     *
     * @return 削除された FQN リスト
     */
    public List<String> removeUnusedImports(PieceTable buffer) {
        List<String> unused = findUnusedImports(buffer.getText());
        List<String> removed = new ArrayList<>();
        for (String fqn : unused) {
            if (deleteImportLine(fqn, buffer)) removed.add(fqn);
        }
        ensureBlankLineAfterImports(buffer);
        return List.copyOf(removed);
    }

    // ----- private helpers -----

    /** buffer から "import <fqn>;" に一致する最初の行を削除する。
     *  @return 削除したら true、見つからなければ false */
    private static boolean deleteImportLine(String fqn, PieceTable buffer) {
        String importLine = "import " + fqn + ";";
        String[] lines = buffer.getText().split("\n", -1);
        int offset = 0;
        for (String line : lines) {
            if (line.stripLeading().equals(importLine)) {
                buffer.delete(offset, line.length() + 1); // +1 for '\n'
                return true;
            }
            offset += line.length() + 1;
        }
        return false;
    }

    /**
     * import ブロックの直後に宣言文が続く場合、間に1行の空行を確保する。
     * すでに空行がある場合は何もしない。import が存在しない場合も何もしない。
     */
    private void ensureBlankLineAfterImports(PieceTable buffer) {
        String source = buffer.getText();
        String[] lines = source.split("\n", -1);

        int lastImportLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].stripLeading().startsWith("import ")) lastImportLine = i;
        }
        if (lastImportLine < 0) return;

        int nextLine = lastImportLine + 1;
        if (nextLine >= lines.length) return;

        if (!lines[nextLine].isBlank()) {
            // 空行がないので挿入する
            int offset = 0;
            for (int i = 0; i <= lastImportLine; i++) {
                offset += lines[i].length() + 1;
            }
            buffer.insert(offset, "\n");
        }
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

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
