package dev.vimacs.analysis;

import java.util.List;

public record SourceIndex(
    String filePath,
    List<ImportEntry> imports,
    List<SymbolEntry> symbols,
    boolean hasParseError
) {}
