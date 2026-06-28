package dev.javatexteditor.refactor;

/**
 * 1ファイルに対するリネーム操作の結果を表すイミュータブルな値オブジェクト。
 */
public record RenameResult(
    String filePath,        // 相対パス（ProjectSearcher と同じ形式）
    int replacementCount,   // 置換した箇所数
    boolean success,        // ファイルへの書き込みが成功したか
    String errorMessage     // success==false の場合のエラー詳細（それ以外は null）
) {
    /** 表示用の1行サマリ */
    public String toDisplayLine() {
        if (success) {
            return filePath + ": " + replacementCount + " replacement(s)";
        } else {
            return filePath + ": ERROR — " + errorMessage;
        }
    }
}
