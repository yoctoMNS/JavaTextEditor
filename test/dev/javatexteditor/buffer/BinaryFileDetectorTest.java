package dev.javatexteditor.buffer;

import java.nio.charset.StandardCharsets;

public class BinaryFileDetectorTest {
    public static void main(String[] args) {
        int pass = 0;
        int total = 7;

        pass += check("空バイト列はテキスト扱い",
            false, BinaryFileDetector.isBinary(new byte[0]));

        pass += check("ASCIIテキストはテキスト扱い",
            false, BinaryFileDetector.isBinary("Hello World".getBytes(StandardCharsets.UTF_8)));

        pass += check("日本語UTF-8テキストはテキスト扱い",
            false, BinaryFileDetector.isBinary("こんにちは".getBytes(StandardCharsets.UTF_8)));

        pass += check("改行を含むUTF-8テキストはテキスト扱い",
            false, BinaryFileDetector.isBinary("line1\nline2\n".getBytes(StandardCharsets.UTF_8)));

        pass += check("NULバイトを含む場合はバイナリ扱い",
            true, BinaryFileDetector.isBinary(new byte[]{'A', 0, 'B'}));

        pass += check("UTF-8として不正なバイト列はバイナリ扱い",
            true, BinaryFileDetector.isBinary(new byte[]{(byte) 0xFF, (byte) 0xFE, 0x01, 0x02}));

        // PNGのマジックバイト先頭（NULを含む）はバイナリ扱い
        pass += check("PNGマジックバイトはバイナリ扱い",
            true, BinaryFileDetector.isBinary(new byte[]{
                (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) {
            System.exit(1);
        }
    }

    static int check(String name, boolean expected, boolean actual) {
        boolean ok = expected == actual;
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        return ok ? 1 : 0;
    }
}
