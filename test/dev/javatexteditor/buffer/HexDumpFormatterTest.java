package dev.javatexteditor.buffer;

public class HexDumpFormatterTest {
    public static void main(String[] args) {
        int pass = 0;
        int total = 6;

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
