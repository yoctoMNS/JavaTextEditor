package dev.javatexteditor.analysis;

public record ImportEntry(
    String fullyQualifiedName,
    boolean isStatic,
    boolean isWildcard,
    int lineNumber
) {}
