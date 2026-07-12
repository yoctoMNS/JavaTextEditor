package dev.javatexteditor.analysis;

/**
 * superTypeName は CLASS 種別のエントリでのみ意味を持つ（extends 節の単純型名。
 * 無ければ null）。継承チェーンを辿ったインスタンスメソッド解決（Eclipse の
 * "Open Declaration" 相当）に使う。
 */
public record SymbolEntry(
    String name,
    SymbolKind kind,
    int lineNumber,
    int offset,
    String superTypeName
) {}
