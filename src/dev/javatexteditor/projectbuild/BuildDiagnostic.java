package dev.javatexteditor.projectbuild;

/**
 * javac が報告する診断1件（プロジェクト全体ビルド用）。
 * {@link dev.javatexteditor.analysis.CompileDiagnostic} は現在編集中の1ファイルのガター表示専用のため
 * filePath を持たない。こちらは複数ファイルにまたがる F10 のビルド結果表示専用に filePath を追加した別レコード。
 * lineNumber / column はどちらも 0-indexed。
 */
public record BuildDiagnostic(
    String filePath,
    int lineNumber,
    int column,
    String message,
    boolean isError
) {}
