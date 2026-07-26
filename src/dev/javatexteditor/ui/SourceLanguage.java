package dev.javatexteditor.ui;

import java.util.Locale;

/** 構文ハイライト対象の言語判定。拡張子のみで判定する軽量な分類。 */
public enum SourceLanguage {
    NONE, JAVA, C;

    public static SourceLanguage detect(String filePath) {
        if (filePath == null) return NONE;
        String lower = filePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return JAVA;
        if (lower.endsWith(".c") || lower.endsWith(".h") || lower.endsWith(".cc")
                || lower.endsWith(".cpp") || lower.endsWith(".cxx") || lower.endsWith(".hpp")
                || lower.endsWith(".hh") || lower.endsWith(".hxx")) {
            return C;
        }
        return NONE;
    }
}
