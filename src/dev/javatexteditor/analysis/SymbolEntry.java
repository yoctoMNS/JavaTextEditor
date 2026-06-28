package dev.javatexteditor.analysis;

public record SymbolEntry(
    String name,
    SymbolKind kind,
    int lineNumber,
    int offset
) {}
