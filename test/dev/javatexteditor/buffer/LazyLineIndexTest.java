package dev.javatexteditor.buffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class LazyLineIndexTest {
    public static void main(String[] args) throws IOException {
        int pass = 0;
        int total = 0;

        // Test 1: ASCII の単純な行オフセット
        Path p1 = Files.createTempFile("lli", ".txt");
        Files.writeString(p1, "aaa\nbb\ncccc\n");
        try (MappedFileSource src = new MappedFileSource(p1)) {
            LazyLineIndex idx = new LazyLineIndex(src);
            total++; pass += check("line0 byte", 0L, idx.byteOffsetOfLine(0));
            total++; pass += check("line1 byte", 4L, idx.byteOffsetOfLine(1)); // "aaa\n" の直後
            total++; pass += check("line2 byte", 7L, idx.byteOffsetOfLine(2)); // "aaa\nbb\n" の直後
            total++; pass += check("lineCount", 4, idx.lineCount()); // 末尾の空行も1行として数える
        }
        Files.delete(p1);

        // Test 2: マルチバイト文字を含む行でchar/byteオフセットが食い違うこと・往復変換が一致すること
        String content = "あいう\nabc\n漢字テスト\n"; // 1行目3文字(9バイト)+改行, 3行目5文字(15バイト)+改行
        Path p2 = Files.createTempFile("lli", ".txt");
        Files.write(p2, content.getBytes(StandardCharsets.UTF_8));
        try (MappedFileSource src = new MappedFileSource(p2)) {
            LazyLineIndex idx = new LazyLineIndex(src);
            long charLine1 = idx.charOffsetOfLine(1);
            long byteLine1 = idx.byteOffsetOfLine(1);
            total++; pass += check("line1 charOffset", 4L, charLine1);  // "あいう\n" = 4文字
            total++; pass += check("line1 byteOffset", 10L, byteLine1); // 3*3バイト + 改行1バイト
            total++; pass += check("byteOffsetOfCharOffset往復", byteLine1, src != null
                ? idx.byteOffsetOfCharOffset(charLine1) : -1);
            total++; pass += check("lineAtCharOffset往復", 1L, idx.lineAtCharOffset(charLine1));
        }
        Files.delete(p2);

        // Test 3: チェックポイント間隔(4096行)をまたぐ大きめのファイルでも一貫した結果になること
        StringBuilder sb = new StringBuilder();
        int lines = 9000;
        for (int i = 0; i < lines; i++) sb.append("row").append(i).append('\n');
        Path p3 = Files.createTempFile("lli", ".txt");
        Files.writeString(p3, sb.toString());
        try (MappedFileSource src = new MappedFileSource(p3)) {
            LazyLineIndex idx = new LazyLineIndex(src);
            // 先に末尾寄りの行へいきなりアクセス（チェックポイントが無い状態からの前進スキャンを確認）
            long offsetLine8000 = idx.byteOffsetOfLine(8000);
            String expectedPrefix = "row8000\n";
            byte[] fileBytes = Files.readAllBytes(p3);
            String actualPrefix = new String(fileBytes, (int) offsetLine8000,
                expectedPrefix.length(), StandardCharsets.UTF_8);
            total++; pass += check("8000行目の内容", expectedPrefix, actualPrefix);
            // 末尾が改行で終わるファイルは split("\n",-1) 慣習と同じく末尾の空行も1行と数える
            total++; pass += check("lineCount", lines + 1, idx.lineCount());
        }
        Files.delete(p3);

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
