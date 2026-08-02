package dev.javatexteditor.buffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class MappedFileSourceTest {
    public static void main(String[] args) throws IOException {
        int pass = 0;
        int total = 0;

        // Test 1: 単純なASCIIファイルの全文デコード
        Path p1 = Files.createTempFile("mfs", ".txt");
        Files.writeString(p1, "Hello World");
        try (MappedFileSource src = new MappedFileSource(p1)) {
            total++; pass += check("size()", 11L, src.size());
            total++; pass += check("decode全文", "Hello World", src.decode(0, src.size()));
            total++; pass += check("decode部分", "World", src.decode(6, 11));
        }
        Files.delete(p1);

        // Test 2: マルチバイト文字を含むファイルで境界安全デコード（境界がずれても文字化けしない）
        String text = "あab漢字う"; // 「あ」「漢」「字」「う」は3バイトUTF-8
        Path p2 = Files.createTempFile("mfs", ".txt");
        Files.write(p2, text.getBytes(StandardCharsets.UTF_8));
        try (MappedFileSource src = new MappedFileSource(p2)) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            total++; pass += check("マルチバイト全文デコード", text, src.decode(0, bytes.length));
            // 「あ」の3バイトの真ん中(1バイト目の次)を境界として渡しても安全側にスナップされる
            long unsafe = 1; // "あ"の2バイト目
            long safeBefore = src.safeBoundaryAtOrBefore(unsafe);
            total++; pass += check("継続バイトからの後退で先頭バイトに一致", 0L, safeBefore);
        }
        Files.delete(p2);

        // Test 3: 1GiBチャンク境界をまたがない小さいファイルでも複数回mapされないこと（間接確認: 正常デコード）
        Path p3 = Files.createTempFile("mfs", ".txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) sb.append("line").append(i).append('\n');
        Files.writeString(p3, sb.toString());
        try (MappedFileSource src = new MappedFileSource(p3)) {
            total++; pass += check("繰り返し行の先頭", "line0\n", src.decode(0, 6));
        }
        Files.delete(p3);

        // Test 4: 空ファイル
        Path p4 = Files.createTempFile("mfs", ".txt");
        try (MappedFileSource src = new MappedFileSource(p4)) {
            total++; pass += check("空ファイルsize", 0L, src.size());
            total++; pass += check("空ファイルdecode", "", src.decode(0, 0));
        }
        Files.delete(p4);

        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + (total - pass) + ")");
        if (pass != total) System.exit(1);
    }

    static int check(String name, long expected, long actual) {
        boolean ok = expected == actual;
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name + " -> expected=" + expected + " actual=" + actual);
        return ok ? 1 : 0;
    }

    static int check(String name, String expected, String actual) {
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=\"" + expected + "\" actual=\"" + actual + "\"");
        return ok ? 1 : 0;
    }
}
