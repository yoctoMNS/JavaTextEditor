package dev.javatexteditor.buffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PieceTable(MappedFileSource) 経由（大容量ファイル向けmmapパス）の正しさを、
 * 同じ操作列を PieceTable(String)（従来の小規模ファイル向けパス）に適用した結果と
 * 突き合わせて検証する。2つの実装が常に同じ結果を返すことを確認することで、
 * mmap化がドキュメントの見た目の振る舞いを変えていないことを保証する。
 */
public class PieceTableMappedTest {
    public static void main(String[] args) throws IOException {
        int pass = 0;
        int total = 0;

        String content = "line1\nline2\nあいうえお\nline4 with 漢字テスト\nline5\n";

        Path file = Files.createTempFile("ptm", ".txt");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));

        try (MappedFileSource src = new MappedFileSource(file)) {
            PieceTable mapped = new PieceTable(src);
            PieceTable string = new PieceTable(content);

            total++; pass += check("初期getText一致", string.getText(), mapped.getText());
            total++; pass += check("初期length一致",
                String.valueOf(string.length()), String.valueOf(mapped.length()));

            for (int line = 0; line <= 5; line++) {
                total++; pass += check("offsetOfLine(" + line + ")一致",
                    String.valueOf(string.offsetOfLine(line)), String.valueOf(mapped.offsetOfLine(line)));
            }

            total++; pass += check("getTextInRange(部分)一致",
                string.getTextInRange(6, 12), mapped.getTextInRange(6, 12));
            total++; pass += check("getTextInRange(マルチバイト行)一致",
                string.getTextInRange(12, 18), mapped.getTextInRange(12, 18));

            // 中間挿入（マルチバイト行の直後）でピース分割が発生するケース
            int insertAt = string.offsetOfLine(3); // "line4 with 漢字テスト\n" の開始位置
            string.insert(insertAt, "NEW LINE\n");
            mapped.insert(insertAt, "NEW LINE\n");
            total++; pass += check("挿入後getText一致", string.getText(), mapped.getText());

            // 削除（マルチバイト文字をまたぐ範囲）
            int delStart = string.offsetOfLine(1);
            int delLen = 6; // "line2\n"
            string.delete(delStart, delLen);
            mapped.delete(delStart, delLen);
            total++; pass += check("削除後getText一致", string.getText(), mapped.getText());

            // さらに挿入と削除を繰り返し、複数ピースにまたがるMAPPED/ADD混在状態でも一致すること
            string.insert(0, "HEAD\n");
            mapped.insert(0, "HEAD\n");
            string.delete(string.length() - 6, 6);
            mapped.delete(mapped.length() - 6, 6);
            total++; pass += check("複数編集後getText一致", string.getText(), mapped.getText());
            total++; pass += check("複数編集後length一致",
                String.valueOf(string.length()), String.valueOf(mapped.length()));

            for (int line = 0; line <= 6; line++) {
                total++; pass += check("編集後offsetOfLine(" + line + ")一致",
                    String.valueOf(string.offsetOfLine(line)), String.valueOf(mapped.offsetOfLine(line)));
            }
        }
        Files.delete(file);

        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + (total - pass) + ")");
        if (pass != total) System.exit(1);
    }

    static int check(String name, String expected, String actual) {
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=\"" + expected + "\" actual=\"" + actual + "\"");
        return ok ? 1 : 0;
    }
}
