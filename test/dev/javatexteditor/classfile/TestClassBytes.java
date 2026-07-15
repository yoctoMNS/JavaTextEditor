package dev.javatexteditor.classfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * classfileパッケージのテスト用に、Javaソース文字列を実際に javax.tools.JavaCompiler でコンパイルし
 * 生成された .class の生バイト列を返す下請けクラス（*Test.java 以外の命名のため test.sh からは
 * 自動実行されない）。実際のjavacが生成する本物のバイトコードに対してパーサ/逆アセンブラを検証するため、
 * 手書きの.classバイト列フィクスチャより確実に仕様通りの入力を用意できる。
 */
final class TestClassBytes {

    private TestClassBytes() {}

    /** source をコンパイルし、simpleName に対応する .class の生バイト列を返す。 */
    static byte[] compile(String simpleName, String source) throws IOException {
        Path tmpDir = Files.createTempDirectory("classfile-test");
        Path srcFile = tmpDir.resolve(simpleName + ".java");
        Files.writeString(srcFile, source);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, Locale.ENGLISH, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tmpDir.toFile()));
            var units = fm.getJavaFileObjectsFromPaths(List.of(srcFile));
            boolean ok = Boolean.TRUE.equals(
                    compiler.getTask(null, fm, null, List.of("-g", "-proc:none"), null, units).call());
            if (!ok) throw new IOException("test fixture failed to compile: " + simpleName);
        }
        return Files.readAllBytes(tmpDir.resolve(simpleName + ".class"));
    }
}
