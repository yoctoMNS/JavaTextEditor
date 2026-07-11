package dev.javatexteditor.analysis;

import dev.javatexteditor.buffer.PieceTable;
import java.util.ArrayList;
import java.util.Comparator;
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
     * 挿入後、import ブロック全体（既存 + 新規）を Eclipse の Organize Imports と
     * 同じアルゴリズムで並べ替える（{@link #insertAndReorganize}参照）。
     * 重複チェックのために SourceAnalyzer でソースを parse する。
     * parse に失敗した場合（JDK なし等）は重複チェックなしで挿入する。
     *
     * @return 実際に挿入された FQN リスト（重複でスキップされたものは含まない）
     */
    public List<String> applyImports(List<String> fqns, PieceTable buffer) {
        List<String> alreadyImported = getAlreadyImported(buffer.getText());
        List<String> toInsert = new ArrayList<>();
        for (String fqn : fqns) {
            if (!alreadyImported.contains(fqn) && !toInsert.contains(fqn)) toInsert.add(fqn);
        }
        if (!toInsert.isEmpty()) insertAndReorganize(toInsert, buffer);
        return List.copyOf(toInsert);
    }

    /**
     * 単一 FQN をバッファに挿入する。重複の場合は挿入しない。
     * 挿入後、import ブロック全体を Eclipse の Organize Imports と同じ
     * アルゴリズムで並べ替える（{@link #insertAndReorganize}参照）。
     *
     * @return 挿入したなら true、すでに存在したなら false
     */
    public boolean applyImport(String fqn, PieceTable buffer) {
        List<String> alreadyImported = getAlreadyImported(buffer.getText());
        if (alreadyImported.contains(fqn)) return false;
        insertAndReorganize(List.of(fqn), buffer);
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
        return resolveCandidates(diags, source, null);
    }

    /**
     * baseDir 配下の自プロジェクトのクラスも候補に含める版。
     * baseDir が null の場合は {@link #resolveCandidates(List, String)} と同じ（JDK のみ）。
     *
     * @param diags    コンパイル診断
     * @param source   現在のソースコード（重複チェック用）
     * @param baseDir  自プロジェクトのクラスを探す起点ディレクトリ（null 可）
     * @return 単純名 → FQN 候補リスト のマップ（候補ゼロの単純名は含まない）
     */
    public Map<String, List<String>> resolveCandidates(
            List<CompileDiagnostic> diags, String source, java.nio.file.Path baseDir) {
        List<String> missing = findMissingSymbols(diags);
        List<String> alreadyImported = getAlreadyImported(source);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String name : missing) {
            List<String> candidates = suggester.suggest(name, baseDir).stream()
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

    // ----- Eclipse Organize Imports 互換の並べ替え -----

    /**
     * Eclipse のデフォルト import グループ順（Preferences &gt; Java &gt; Code Style &gt;
     * Organize Imports の既定値）。この順に並べ、一致しないパッケージは末尾の
     * 「その他」グループに入れる。各グループ内は FQN のアルファベット順（{@link String#compareTo}）。
     */
    private static final List<String> IMPORT_GROUP_ORDER = List.of("java", "javax", "org", "com");

    private static final Pattern IMPORT_LINE_PATTERN =
        Pattern.compile("^import\\s+(static\\s+)?(.+?)\\s*;\\s*$");

    private record ImportLine(String fqn, boolean isStatic) {}

    private static final Comparator<ImportLine> IMPORT_LINE_COMPARATOR =
        Comparator.comparingInt((ImportLine i) -> groupIndex(i.fqn())).thenComparing(ImportLine::fqn);

    /** 新規 import を既存 import 群に混ぜ、ブロック全体を Eclipse 互換の順序で書き直す。 */
    private void insertAndReorganize(List<String> newFqns, PieceTable buffer) {
        String source = buffer.getText();
        String[] lines = source.split("\n", -1);

        List<ImportLine> existing = new ArrayList<>();
        int firstImportLine = -1;
        int lastImportLine = -1;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = IMPORT_LINE_PATTERN.matcher(lines[i].strip());
            if (m.matches()) {
                existing.add(new ImportLine(m.group(2), m.group(1) != null));
                if (firstImportLine < 0) firstImportLine = i;
                lastImportLine = i;
            }
        }

        List<ImportLine> all = new ArrayList<>(existing);
        for (String fqn : newFqns) all.add(new ImportLine(fqn, false));
        String block = formatImportBlock(all);

        if (firstImportLine < 0) {
            int offset = findImportInsertOffset(source);
            buffer.insert(offset, block);
        } else {
            int startOffset = 0;
            for (int i = 0; i < firstImportLine; i++) startOffset += lines[i].length() + 1;
            int endOffset = 0;
            for (int i = 0; i <= lastImportLine; i++) endOffset += lines[i].length() + 1;
            buffer.delete(startOffset, endOffset - startOffset);
            buffer.insert(startOffset, block);
        }
        ensureBlankLineAfterImports(buffer);
    }

    /**
     * import 群を Eclipse 互換の順序でテキスト化する。
     * static import は非 static のブロックより前に置き、両ブロックの間・
     * 各グループの間にそれぞれ空行を1行入れる（グループ内は空行なし）。
     */
    private static String formatImportBlock(List<ImportLine> imports) {
        List<ImportLine> statics = imports.stream()
            .filter(ImportLine::isStatic).sorted(IMPORT_LINE_COMPARATOR).toList();
        List<ImportLine> normals = imports.stream()
            .filter(i -> !i.isStatic()).sorted(IMPORT_LINE_COMPARATOR).toList();

        StringBuilder sb = new StringBuilder();
        appendGroupedLines(sb, statics, true);
        if (!statics.isEmpty() && !normals.isEmpty()) sb.append('\n');
        appendGroupedLines(sb, normals, false);
        return sb.toString();
    }

    private static void appendGroupedLines(StringBuilder sb, List<ImportLine> group, boolean isStatic) {
        int prevGroup = -1;
        for (ImportLine imp : group) {
            int g = groupIndex(imp.fqn());
            if (prevGroup >= 0 && g != prevGroup) sb.append('\n');
            sb.append("import ").append(isStatic ? "static " : "").append(imp.fqn()).append(";\n");
            prevGroup = g;
        }
    }

    /** IMPORT_GROUP_ORDER 内での位置を返す。一致しなければ末尾（その他）扱いのインデックス。 */
    private static int groupIndex(String fqn) {
        for (int i = 0; i < IMPORT_GROUP_ORDER.size(); i++) {
            String prefix = IMPORT_GROUP_ORDER.get(i);
            if (fqn.equals(prefix) || fqn.startsWith(prefix + ".")) return i;
        }
        return IMPORT_GROUP_ORDER.size();
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
     * import ブロックの前後の空行を確保する。
     * package 文と import ブロックの間、および import ブロックと後続の宣言
     * （クラス宣言等）との間に、それぞれ1行の空行を確保する。
     * すでに空行がある場合は何もしない。import・package が存在しない場合もその境界については何もしない。
     */
    private void ensureBlankLineAfterImports(PieceTable buffer) {
        ensureBlankLineAfterImportBlock(buffer);
        ensureBlankLineBeforeImportBlock(buffer);
    }

    private void ensureBlankLineAfterImportBlock(PieceTable buffer) {
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

    private void ensureBlankLineBeforeImportBlock(PieceTable buffer) {
        String source = buffer.getText();
        String[] lines = source.split("\n", -1);

        int packageLine = -1;
        int firstImportLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].stripLeading();
            if (trimmed.startsWith("package ") && packageLine < 0) packageLine = i;
            else if (trimmed.startsWith("import ") && firstImportLine < 0) firstImportLine = i;
        }
        if (packageLine < 0 || firstImportLine < 0) return;
        if (firstImportLine != packageLine + 1) return; // 既に空行がある、または隣接していない

        int offset = 0;
        for (int i = 0; i <= packageLine; i++) {
            offset += lines[i].length() + 1;
        }
        buffer.insert(offset, "\n");
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
