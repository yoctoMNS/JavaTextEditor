package dev.javatexteditor.classfile;

public class ClassFileFormatterTest {

    private static final String SAMPLE_SOURCE = """
            public class Sample3 {
                private int count;
                public static final String GREETING = "hello";

                public Sample3() {
                    this.count = 42;
                }

                public int add(int a, int b) {
                    return a + b;
                }
            }
            """;

    public static void main(String[] args) throws Exception {
        int pass = 0;
        int total = 10;

        byte[] classBytes = TestClassBytes.compile("Sample3", SAMPLE_SOURCE);
        ClassFile cf = ClassFileParser.parse(classBytes);
        String text = ClassFileFormatter.format(cf, "Sample3.class");

        pass += check("ヘッダにファイル名を含む", text.contains("Sample3.class"));
        pass += check("ヘッダに:nimoの案内を含む", text.contains(":nimo"));
        pass += check("magic行を含む", text.contains("magic: 0xCAFEBABE"));
        pass += check("major versionの説明にJava SEを含む", text.contains("Java SE"));
        pass += check("this classにSampleクラス名を含む", text.contains("Sample3"));
        pass += check("super classにjava/lang/Objectを含む", text.contains("java/lang/Object"));
        pass += check("fieldsセクションにcountを含む", text.contains("count"));
        pass += check("fieldsセクションにGREETINGを含む", text.contains("GREETING"));
        pass += check("methodsセクションにaddを含む", text.contains("add"));
        pass += check("constant poolセクションを含む", text.contains("constant pool:"));

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
