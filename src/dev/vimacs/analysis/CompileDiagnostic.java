package dev.vimacs.analysis;

/**
 * javac が報告する診断1件を表す不変レコード。
 * lineNumber / column はどちらも 0-indexed。
 */
public record CompileDiagnostic(
    int lineNumber,
    int column,
    String message,
    DiagnosticKind kind
) {}
