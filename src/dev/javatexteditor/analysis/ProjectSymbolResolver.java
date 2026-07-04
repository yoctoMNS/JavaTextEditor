package dev.javatexteditor.analysis;

import dev.javatexteditor.search.ProjectSearcher;
import dev.javatexteditor.search.SearchResult;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * プロジェクト全体からフィールド・定数・メソッド（およびクラス）の宣言箇所を検索する。
 *
 * 実装方針: ProjectSearcher で \bname\b に一致するファイルを絞り込み（Phase 1）、
 * 候補ファイルのみ SourceAnalyzer で AST 解析して宣言を特定する（Phase 2）。
 * プロジェクト全体を毎回フル解析すると数十万行規模で遅くなるため、この2段構えで
 * 解析対象を絞る（RenameRefactorer と同じ設計方針）。
 */
public class ProjectSymbolResolver {

    private final ProjectSearcher searcher = new ProjectSearcher();
    private final SourceAnalyzer analyzer = new SourceAnalyzer();

    /** 宣言の発見箇所。filePath は絶対パス文字列。 */
    public record SymbolLocation(String filePath, int lineNumber, SymbolKind kind) {}

    /**
     * name の宣言箇所を検索する。
     * currentFilePath/currentBufferText が与えられた場合、未保存の変更を含む
     * 現在のバッファ内容を最優先で調べる（ディスク上の内容は使わない）。
     *
     * @param baseDir          検索の起点ディレクトリ
     * @param currentFilePath  現在開いているファイルの絶対パス（新規バッファなら null）
     * @param currentBufferText 現在のバッファのテキスト（null 可）
     * @param name             検索するシンボル名
     */
    public Optional<SymbolLocation> resolve(Path baseDir, String currentFilePath,
                                             String currentBufferText, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        if (currentFilePath != null && currentBufferText != null) {
            Optional<SymbolLocation> hit = findInText(currentFilePath, currentBufferText, name);
            if (hit.isPresent()) return hit;
        }

        List<SearchResult> matches;
        try {
            matches = searcher.search(baseDir, "\\b" + Pattern.quote(name) + "\\b");
        } catch (PatternSyntaxException e) {
            return Optional.empty();
        }

        LinkedHashSet<String> candidateFiles = new LinkedHashSet<>();
        for (SearchResult r : matches) {
            if (r.filePath().endsWith(".java")) {
                candidateFiles.add(r.filePath());
            }
        }

        for (String rel : candidateFiles) {
            Path abs = baseDir.resolve(rel);
            if (currentFilePath != null && abs.toString().equals(currentFilePath)) {
                continue; // 既にバッファ内容で調査済み
            }
            try {
                SourceIndex idx = analyzer.analyzeFile(abs);
                Optional<SymbolEntry> found = findSymbol(idx, name);
                if (found.isPresent()) {
                    return Optional.of(new SymbolLocation(
                        abs.toString(), found.get().lineNumber(), found.get().kind()));
                }
            } catch (AnalysisException e) {
                // 構文エラー等で解析できないファイルはスキップして次を試す
            }
        }
        return Optional.empty();
    }

    private Optional<SymbolLocation> findInText(String filePath, String text, String name) {
        try {
            SourceIndex idx = analyzer.analyzeText(text);
            return findSymbol(idx, name)
                .map(s -> new SymbolLocation(filePath, s.lineNumber(), s.kind()));
        } catch (AnalysisException e) {
            return Optional.empty();
        }
    }

    private Optional<SymbolEntry> findSymbol(SourceIndex idx, String name) {
        return idx.symbols().stream()
            .filter(s -> s.name().equals(name))
            .findFirst();
    }

    /**
     * typeName（クラス/インタフェース/enum名）が自プロジェクト内で宣言されている場合に限り、
     * そのクラスのファイル**だけ**を対象に memberName（フィールド・メソッド）の宣言を検索する。
     *
     * "instanceVar.member" 形式のジャンプ用: レシーバの型が typeName だと分かっている前提で、
     * 他クラスの同名メンバーと混同しないよう検索範囲をそのクラスのファイルに限定する
     * （継承元クラスへは辿らない。スコープ外は SKILL.md 参照）。
     *
     * typeName 自体が自プロジェクトのクラスとして見つからない場合（JDK型等）は
     * Optional.empty() を返す。呼び出し側はこの場合 JDK 側の解決を試みること。
     */
    public Optional<SymbolLocation> resolveMemberInType(Path baseDir, String currentFilePath,
                                                          String currentBufferText,
                                                          String typeName, String memberName) {
        if (typeName == null || typeName.isBlank() || memberName == null || memberName.isBlank()) {
            return Optional.empty();
        }
        Optional<String> classFile = findClassFile(baseDir, currentFilePath, currentBufferText, typeName);
        if (classFile.isEmpty()) {
            return Optional.empty();
        }
        String filePath = classFile.get();
        try {
            SourceIndex idx = (currentFilePath != null && filePath.equals(currentFilePath) && currentBufferText != null)
                ? analyzer.analyzeText(currentBufferText)
                : analyzer.analyzeFile(Path.of(filePath));
            return findSymbol(idx, memberName)
                .map(s -> new SymbolLocation(filePath, s.lineNumber(), s.kind()));
        } catch (AnalysisException e) {
            return Optional.empty();
        }
    }

    /** typeName という名前のクラス/インタフェース/enum が宣言されているファイルの絶対パスを探す。 */
    private Optional<String> findClassFile(Path baseDir, String currentFilePath,
                                            String currentBufferText, String typeName) {
        if (currentFilePath != null && currentBufferText != null) {
            try {
                SourceIndex idx = analyzer.analyzeText(currentBufferText);
                if (findClassSymbol(idx, typeName).isPresent()) {
                    return Optional.of(currentFilePath);
                }
            } catch (AnalysisException e) {
                // 現在バッファが解析不能でもディスク上の他ファイルを試す
            }
        }

        List<SearchResult> matches;
        try {
            matches = searcher.search(baseDir, "\\b" + Pattern.quote(typeName) + "\\b");
        } catch (PatternSyntaxException e) {
            return Optional.empty();
        }

        LinkedHashSet<String> candidateFiles = new LinkedHashSet<>();
        for (SearchResult r : matches) {
            if (r.filePath().endsWith(".java")) {
                candidateFiles.add(r.filePath());
            }
        }

        for (String rel : candidateFiles) {
            Path abs = baseDir.resolve(rel);
            if (currentFilePath != null && abs.toString().equals(currentFilePath)) {
                continue;
            }
            try {
                SourceIndex idx = analyzer.analyzeFile(abs);
                if (findClassSymbol(idx, typeName).isPresent()) {
                    return Optional.of(abs.toString());
                }
            } catch (AnalysisException e) {
                // 構文エラー等で解析できないファイルはスキップして次を試す
            }
        }
        return Optional.empty();
    }

    private Optional<SymbolEntry> findClassSymbol(SourceIndex idx, String name) {
        return idx.symbols().stream()
            .filter(s -> s.name().equals(name)
                && (s.kind() == SymbolKind.CLASS || s.kind() == SymbolKind.INTERFACE || s.kind() == SymbolKind.ENUM))
            .findFirst();
    }
}
