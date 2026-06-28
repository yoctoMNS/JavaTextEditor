package dev.vimacs.analysis;

public record ImportEntry(
    String fullyQualifiedName,
    boolean isStatic,
    boolean isWildcard,
    int lineNumber
) {}
