package dev.javatexteditor.classfile;

public class MnemonicFormatterTest {

    private static final String SAMPLE_SOURCE = """
            public class Sample4 {
                public Sample4() {
                }

                public int add(int a, int b) {
                    return a + b;
                }
            }
            """;

    private static final String ABSTRACT_SOURCE = """
            public abstract class SampleAbstract {
                public abstract void run();
            }
            """;

    public static void main(String[] args) throws Exception {
        int pass = 0;
        int total = 6;

        byte[] classBytes = TestClassBytes.compile("Sample4", SAMPLE_SOURCE);
        ClassFile cf = ClassFileParser.parse(classBytes);
        String text = MnemonicFormatter.format(cf, "Sample4.class");

        pass += check("ヘッダにファイル名を含む", text.contains("Sample4.class"));
        pass += check("ヘッダにmnemonicの案内を含む", text.contains("mnemonic"));
        pass += check("<init>のニーモニックにaload_0を含む", text.contains("aload_0"));
        pass += check("addのニーモニックにiaddを含む", text.contains("iadd"));
        pass += check("stack=/locals=のサマリ行を含む", text.contains("stack=") && text.contains("locals="));

        byte[] abstractClassBytes = TestClassBytes.compile("SampleAbstract", ABSTRACT_SOURCE);
        ClassFile abstractCf = ClassFileParser.parse(abstractClassBytes);
        String abstractText = MnemonicFormatter.format(abstractCf, "SampleAbstract.class");
        pass += check("abstractメソッドはCodeなしの案内を表示する", abstractText.contains("Codeなし"));

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
