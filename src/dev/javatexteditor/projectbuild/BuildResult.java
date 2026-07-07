package dev.javatexteditor.projectbuild;

import java.util.List;

/**
 * F10（プロジェクト全体コンパイル）の結果。
 * success は「javac の呼び出し自体が成功し、かつエラー診断が1件もない」ことを表す
 * （警告のみの場合は true のまま。javac が warning のみで success=false を返すことはない）。
 */
public record BuildResult(
    boolean success,
    int fileCount,
    List<BuildDiagnostic> diagnostics,
    String errorMessage
) {}
