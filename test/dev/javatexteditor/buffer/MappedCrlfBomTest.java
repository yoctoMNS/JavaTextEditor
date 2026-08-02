package dev.javatexteditor.buffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * mmap経路(PieceTable(MappedFileSource))が、小規模ファイル経路(PieceTable(String)相当。
 * ModalEditor.readFileContentForBufferが行う \r\n→\n 正規化・BOM除去)と同じ論理的な
 * 文書内容になることを検証する。ファイルサイズ(mmap閾値)だけで改行・BOMの扱いが変わる
 * ことはユーザーから見てバグになるため、mmap経路でも同じ最終結果になる必要がある。
 */
public class MappedCrlfBomTest {
    public static void main(String[] args) throws IOException {
        int pass = 0;
        int total = 0;

        // Test 1: CRLFファイルはmmap経路でもLFへ正規化される
        String withCrlf = "line1\r\nline2\r\nline3\r\n";
        String normalized = withCrlf.replace("\r\n", "\n");
        Path p1 = Files.createTempFile("crlf", ".txt");
        Files.write(p1, withCrlf.getBytes(StandardCharsets.UTF_8));
        try (MappedFileSource src = new MappedFileSource(p1)) {
            PieceTable mapped = new PieceTable(src);
            total++; pass += check("CRLF正規化後のgetText", normalized, mapped.getText());
            total++; pass += check("CRLF正規化後のlength", normalized.length(), mapped.length());
            for (int line = 0; line <= 3; line++) {
                total++; pass += check("CRLF offsetOfLine(" + line + ")",
                    expectedOffsetOfLine(normalized, line), mapped.offsetOfLine(line));
            }
            // 部分範囲取得でも \r が漏れないこと
            total++; pass += check("CRLF getTextInRange", "line2\n", mapped.getTextInRange(6, 12));
        }
        Files.delete(p1);

        // Test 2: 孤立した\r（旧Mac形式、\nを伴わない）はそのまま残ること（既存仕様どおり）
        String lonelyCr = "a\rb\nc\r\nd";
        String normalizedLonely = lonelyCr.replace("\r\n", "\n"); // "a\rb\nc\nd"
        Path p2 = Files.createTempFile("crlf-lonely", ".txt");
        Files.write(p2, lonelyCr.getBytes(StandardCharsets.UTF_8));
        try (MappedFileSource src = new MappedFileSource(p2)) {
            PieceTable mapped = new PieceTable(src);
            total++; pass += check("孤立\\rは保持される", normalizedLonely, mapped.getText());
        }
        Files.delete(p2);

        // Test 3: UTF-8 BOM付きファイルはmmap経路でもBOMが除去される
        String body = "package com.example;\n\nclass X {}\n";
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + bodyBytes.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(bodyBytes, 0, withBom, bom.length, bodyBytes.length);
        Path p3 = Files.createTempFile("bom", ".java");
        Files.write(p3, withBom);
        try (MappedFileSource src = new MappedFileSource(p3)) {
            PieceTable mapped = new PieceTable(src);
            total++; pass += check("BOM除去後のgetText", body, mapped.getText());
            total++; pass += check("BOM除去後のoffsetOfLine(1)", body.indexOf('\n') + 1, mapped.offsetOfLine(1));
        }
        Files.delete(p3);

        // Test 4: BOM + CRLF の組み合わせ
        String bomCrlfBody = "line1\r\nline2\r\n";
        byte[] bomCrlfBodyBytes = bomCrlfBody.getBytes(StandardCharsets.UTF_8);
        byte[] withBomCrlf = new byte[bom.length + bomCrlfBodyBytes.length];
        System.arraycopy(bom, 0, withBomCrlf, 0, bom.length);
        System.arraycopy(bomCrlfBodyBytes, 0, withBomCrlf, bom.length, bomCrlfBodyBytes.length);
        Path p4 = Files.createTempFile("bom-crlf", ".txt");
        Files.write(p4, withBomCrlf);
        try (MappedFileSource src = new MappedFileSource(p4)) {
            PieceTable mapped = new PieceTable(src);
            total++; pass += check("BOM+CRLF正規化後のgetText", "line1\nline2\n", mapped.getText());
        }
        Files.delete(p4);

        // Test 5: 編集(insert/delete)後もCRLF正規化済みの内容と整合すること
        Path p5 = Files.createTempFile("crlf-edit", ".txt");
        Files.write(p5, withCrlf.getBytes(StandardCharsets.UTF_8));
        try (MappedFileSource src = new MappedFileSource(p5)) {
            PieceTable mapped = new PieceTable(src);
            mapped.insert(mapped.offsetOfLine(1), "INSERTED\n");
            String expected = normalized.substring(0, normalized.indexOf("line2"))
                + "INSERTED\n" + normalized.substring(normalized.indexOf("line2"));
            total++; pass += check("CRLFファイル編集後のgetText", expected, mapped.getText());
        }
        Files.delete(p5);

        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + (total - pass) + ")");
        if (pass != total) System.exit(1);
    }

    // テスト内で「正規化済みStringに対する素朴な行頭検索」で期待値を計算するヘルパー
    static int expectedOffsetOfLine(String normalizedText, int line) {
        if (line == 0) return 0;
        int idx = -1;
        for (int i = 0; i < line; i++) {
            idx = normalizedText.indexOf('\n', idx + 1);
            if (idx < 0) return normalizedText.length();
        }
        return idx + 1;
    }

    static int check(String name, String expected, String actual) {
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=\"" + expected.replace("\n", "\\n").replace("\r", "\\r")
            + "\" actual=\"" + actual.replace("\n", "\\n").replace("\r", "\\r") + "\"");
        return ok ? 1 : 0;
    }

    static int check(String name, int expected, int actual) {
        boolean ok = expected == actual;
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name + " -> expected=" + expected + " actual=" + actual);
        return ok ? 1 : 0;
    }
}
