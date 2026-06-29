package dev.javatexteditor.search;

/**
 * grep 検索の1件の一致結果を表すイミュータブルな値オブジェクト。
 */
public record SearchResult(String filePath, int lineNumber, String lineContent) {

    /** エディタ内に表示する形式: "path/to/file.java:42: matched content" */
    public String toDisplayLine() {
        return filePath + ":" + lineNumber + ": " + lineContent;
    }
}
