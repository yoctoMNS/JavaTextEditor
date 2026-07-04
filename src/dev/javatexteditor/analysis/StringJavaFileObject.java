package dev.javatexteditor.analysis;

import javax.tools.SimpleJavaFileObject;
import java.net.URI;

/** 文字列ソースを JavaFileObject として渡すためのアダプタ（CompileAnalyzer / SourceAnalyzer 共用）。 */
final class StringJavaFileObject extends SimpleJavaFileObject {
    private final String source;

    StringJavaFileObject(String filePath, String source) {
        super(toUri(filePath), Kind.SOURCE);
        this.source = source;
    }

    private static URI toUri(String filePath) {
        // URI パスセグメントとして不正な文字を除去する
        String safe = filePath.replace('\\', '/')
                              .replaceAll("[<>\"{}|\\\\^`\\[\\] ]", "_");
        return URI.create("string:///" + safe);
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return source;
    }
}
