package dev.javatexteditor.ui;

import java.util.Locale;

/** 構文ハイライト対象の言語判定。拡張子のみで判定する軽量な分類。 */
public enum SourceLanguage {
    NONE, JAVA, C;

    public static SourceLanguage detect(String filePath) {
        if (filePath == null) return NONE;
        // jdk-source 疑似バッファ（"*jdk-source:<内側パス>*"）は currentFilePath がラップされて
        // いるため、拡張子判定の前にラッパーを剥がす。内側が拡張子なし（Kキーで開いたJDK/HotSpot
        // クラスのfqcn表示、例 "*jdk-source:java.lang.String*"）の場合はJavaソースとして扱う。
        String inner = filePath;
        boolean jdkSource = false;
        if (inner.startsWith("*jdk-source:")) {
            jdkSource = true;
            inner = inner.substring("*jdk-source:".length());
            if (inner.endsWith("*")) {
                inner = inner.substring(0, inner.length() - 1);
            }
        }
        String lower = inner.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return JAVA;
        if (lower.endsWith(".c") || lower.endsWith(".h") || lower.endsWith(".cc")
                || lower.endsWith(".cpp") || lower.endsWith(".cxx") || lower.endsWith(".hpp")
                || lower.endsWith(".hh") || lower.endsWith(".hxx")) {
            return C;
        }
        if (jdkSource) return JAVA;
        return NONE;
    }
}
