package dev.javatexteditor.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class JavaFileStubGeneratorTest {
    public static void main(String[] args) throws IOException {
        int pass = 0;
        int total = 0;

        Path root = Files.createTempDirectory("stubgen");

        // 1. src直下（このプロジェクトと同じ単純な src/dev/... レイアウト）
        {
            total++;
            Path srcDir = root.resolve("plainsrc/src");
            Files.createDirectories(srcDir.resolve("dev/foo"));
            Path target = srcDir.resolve("dev/foo/Bar.java");
            JavaFileStubGenerator.Stub stub = JavaFileStubGenerator.generate(target);
            pass += check("plain src レイアウトでパッケージ推定", stub != null
                && stub.text().startsWith("package dev.foo;\n\npublic class Bar {\n\n}\n")
                && stub.cursorRow() == 3);
        }

        // 2. Maven/Gradle レイアウト（src/main/java配下、main/javaはパッケージ名から除外）
        {
            total++;
            Path srcDir = root.resolve("mavenproj/src");
            Files.createDirectories(srcDir.resolve("main/java/com/example"));
            Path target = srcDir.resolve("main/java/com/example/Foo.java");
            JavaFileStubGenerator.Stub stub = JavaFileStubGenerator.generate(target);
            pass += check("mavenレイアウトでmain/javaを除外しパッケージ推定", stub != null
                && stub.text().startsWith("package com.example;\n\npublic class Foo {\n\n}\n"));
        }

        // 3. Gradle testディレクトリ（src/test/java）
        {
            total++;
            Path srcDir = root.resolve("gradleproj/src");
            Files.createDirectories(srcDir.resolve("test/java/com/example"));
            Path target = srcDir.resolve("test/java/com/example/FooTest.java");
            JavaFileStubGenerator.Stub stub = JavaFileStubGenerator.generate(target);
            pass += check("gradle test レイアウトでtest/javaを除外しパッケージ推定", stub != null
                && stub.text().startsWith("package com.example;\n\npublic class FooTest {\n\n}\n"));
        }

        // 4. srcフォルダ直下に作成 -> デフォルトパッケージ（package文なし）
        {
            total++;
            Path srcDir = root.resolve("defaultpkg/src");
            Files.createDirectories(srcDir);
            Path target = srcDir.resolve("Main.java");
            JavaFileStubGenerator.Stub stub = JavaFileStubGenerator.generate(target);
            pass += check("srcフォルダ直下はデフォルトパッケージ", stub != null
                && stub.text().equals("public class Main {\n\n}\n")
                && stub.cursorRow() == 1);
        }

        // 5. 深いネスト先でも祖先を遡ってsrcを見つける
        {
            total++;
            Path srcDir = root.resolve("deepnest/src");
            Files.createDirectories(srcDir.resolve("a/b/c/d"));
            Path target = srcDir.resolve("a/b/c/d/Deep.java");
            JavaFileStubGenerator.Stub stub = JavaFileStubGenerator.generate(target);
            pass += check("深いネストでも祖先を遡ってsrcを発見", stub != null
                && stub.text().startsWith("package a.b.c.d;\n\npublic class Deep {\n\n}\n"));
        }

        // 6. binフォルダはあるがsrcフォルダがない -> パッケージ文なし、クラス名のみ
        {
            total++;
            Path projDir = root.resolve("binonly");
            Files.createDirectories(projDir.resolve("bin"));
            Files.createDirectories(projDir.resolve("pkg"));
            Path target = projDir.resolve("pkg/Widget.java");
            JavaFileStubGenerator.Stub stub = JavaFileStubGenerator.generate(target);
            pass += check("binフォルダのみでsrcがない場合はパッケージ文なし", stub != null
                && stub.text().equals("public class Widget {\n\n}\n"));
        }

        // 7. srcもbinもない -> パッケージ文なし、クラス名のみ
        {
            total++;
            Path projDir = root.resolve("neither/pkg/nested");
            Files.createDirectories(projDir);
            Path target = projDir.resolve("Empty.java");
            JavaFileStubGenerator.Stub stub = JavaFileStubGenerator.generate(target);
            pass += check("srcもbinもない場合もパッケージ文なし", stub != null
                && stub.text().equals("public class Empty {\n\n}\n"));
        }

        // 8. .java以外の拡張子は対象外（nullを返す）
        {
            total++;
            Path target = root.resolve("plainsrc/src/dev/foo/notes.txt");
            JavaFileStubGenerator.Stub stub = JavaFileStubGenerator.generate(target);
            pass += check(".java以外の拡張子はnull", stub == null);
        }

        // 9. クラス名として不正な識別子（ハイフンを含む）はnull
        {
            total++;
            Path target = root.resolve("plainsrc/src/dev/foo/My-File.java");
            JavaFileStubGenerator.Stub stub = JavaFileStubGenerator.generate(target);
            pass += check("不正な識別子のファイル名はnull", stub == null);
        }

        // 10. Java予約語と同名のファイル名はnull
        {
            total++;
            Path target = root.resolve("plainsrc/src/dev/foo/class.java");
            JavaFileStubGenerator.Stub stub = JavaFileStubGenerator.generate(target);
            pass += check("予約語と同名のファイル名はnull", stub == null);
        }

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) {
            System.exit(1);
        }
    }

    static int check(String name, boolean ok) {
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name);
        return ok ? 1 : 0;
    }
}
