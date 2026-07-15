package dev.javatexteditor.buffer;

public class HexDumpFormatterTest {
    public static void main(String[] args) {
        int pass = 0;
        int total = 10;

        byte[] bytes16 = new byte[16];
        for (int i = 0; i < 16; i++) bytes16[i] = (byte) i;
        String dump16 = HexDumpFormatter.format(bytes16, "test.bin");
        String[] lines16 = dump16.split("\n");

        pass += check("ヘッダ行にファイル名とバイト数を含む",
            lines16[0].contains("test.bin") && lines16[0].contains("16 bytes"));

        pass += check("16バイトはヘッダ行+1データ行の計2行",
            lines16.length == 2);

        pass += check("先頭オフセットは00000000",
            lines16[1].startsWith("00000000"));

        pass += check("16進数表現に0f(15)を含む",
            lines16[1].contains("0f"));

        byte[] printable = "AB".getBytes();
        String dumpAscii = HexDumpFormatter.format(printable, "ascii.bin");
        pass += check("印字可能文字はASCII欄にそのまま表示",
            dumpAscii.contains("|AB|"));

        byte[] control = new byte[]{0x01, 0x02};
        String dumpControl = HexDumpFormatter.format(control, "ctrl.bin");
        pass += check("制御文字はASCII欄で'.'に変換",
            dumpControl.contains("|..|"));

        // parse() は format() の逆変換であること（Mode.BINARYの:w保存が依拠する契約）。
        byte[] varied = new byte[]{0x00, 0x41, (byte) 0xFF, 0x0A, 0x7F, 0x20};
        String dumpVaried = HexDumpFormatter.format(varied, "v.bin");
        pass += check("parse(format(bytes))はbytesと完全に一致する（単一行）",
            java.util.Arrays.equals(varied, HexDumpFormatter.parse(dumpVaried, varied.length)));

        byte[] multiLine = new byte[20];
        for (int i = 0; i < multiLine.length; i++) multiLine[i] = (byte) (i * 7);
        String dumpMultiLine = HexDumpFormatter.format(multiLine, "m.bin");
        pass += check("parse(format(bytes))はbytesと完全に一致する（複数行20バイト）",
            java.util.Arrays.equals(multiLine, HexDumpFormatter.parse(dumpMultiLine, multiLine.length)));

        pass += check("0バイトはヘッダ行のみでparseも空配列",
            HexDumpFormatter.parse(HexDumpFormatter.format(new byte[0], "e.bin"), 0).length == 0);

        pass += check("hexDigitColumn/asciiColumnは行内バイト位置が進むほど単調増加",
            HexDumpFormatter.hexDigitColumn(0) < HexDumpFormatter.hexDigitColumn(8)
                && HexDumpFormatter.hexDigitColumn(8) < HexDumpFormatter.hexDigitColumn(15)
                && HexDumpFormatter.asciiColumn(0) < HexDumpFormatter.asciiColumn(15));

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) {
            System.exit(1);
        }
    }

    static int check(String name, boolean condition) {
        System.out.println((condition ? "[OK] " : "[FAIL] ") + name);
        return condition ? 1 : 0;
    }
}
