package dev.javatexteditor.editor;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * `:e <存在しない.javaパス>` で新規ファイルを作成した際、package文・クラス定義の
 * ひな形（JavaFileStubGenerator）が自動挿入されることを ModalEditor 経由で検証する。
 * mainメソッド形式のテストハーネス（JUnit不使用）。
 */
public class NewFileStubTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws IOException {
        testMavenStyleNewFileGetsPackageAndClass();
        testSrcRootNewFileGetsDefaultPackage();
        testNoSrcNewFileGetsClassNameOnly();
        testNonJavaNewFileStaysEmpty();

        System.out.println();
        System.out.println("Results: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
        System.exit(0);
    }

    static void assertEquals(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            System.out.println("  PASS: " + name);
            pass++;
        } else {
            System.out.println("  FAIL: " + name + " expected=" + expected + " actual=" + actual);
            fail++;
        }
    }

    private static void openViaCommand(ModalEditor ed, String path) {
        ed.processKey(KeyEvent.VK_UNDEFINED, ':', 0);
        for (char c : ("e " + path).toCharArray()) ed.processKey(KeyEvent.VK_UNDEFINED, c, 0);
        ed.processKey(KeyEvent.VK_ENTER, '\n', 0);
    }

    static void testMavenStyleNewFileGetsPackageAndClass() throws IOException {
        Path root = Files.createTempDirectory("newfilestub-maven");
        Files.createDirectories(root.resolve("src/main/java/com/example"));
        Path target = root.resolve("src/main/java/com/example/Greeter.java");

        ModalEditor ed = new ModalEditor("");
        ed.setProjectRoot(root);
        openViaCommand(ed, target.toString());

        assertEquals("mavenレイアウトでpackage+classが自動挿入される",
            "package com.example;\n\npublic class Greeter {\n\n}\n", ed.getText());
        assertEquals("カーソルはクラス本体の空行へ", 3, ed.getCursorRow());
    }

    static void testSrcRootNewFileGetsDefaultPackage() throws IOException {
        Path root = Files.createTempDirectory("newfilestub-defaultpkg");
        Files.createDirectories(root.resolve("src"));
        Path target = root.resolve("src/Launcher.java");

        ModalEditor ed = new ModalEditor("");
        ed.setProjectRoot(root);
        openViaCommand(ed, target.toString());

        assertEquals("srcフォルダ直下はデフォルトパッケージ（package文なし）",
            "public class Launcher {\n\n}\n", ed.getText());
    }

    static void testNoSrcNewFileGetsClassNameOnly() throws IOException {
        Path root = Files.createTempDirectory("newfilestub-nosrc");
        Files.createDirectories(root.resolve("bin"));
        Path target = root.resolve("Standalone.java");

        ModalEditor ed = new ModalEditor("");
        ed.setProjectRoot(root);
        openViaCommand(ed, target.toString());

        assertEquals("binはあるがsrcがない場合はクラス名のみ",
            "public class Standalone {\n\n}\n", ed.getText());
    }

    static void testNonJavaNewFileStaysEmpty() throws IOException {
        Path root = Files.createTempDirectory("newfilestub-nonjava");
        Path target = root.resolve("notes.txt");

        ModalEditor ed = new ModalEditor("");
        ed.setProjectRoot(root);
        openViaCommand(ed, target.toString());

        assertEquals(".java以外は従来通り空バッファのまま", "", ed.getText());
    }
}
