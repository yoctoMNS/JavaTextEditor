package dev.javatexteditor.classfile;

import java.util.List;

public class ClassFileParserTest {

    private static final String SAMPLE_SOURCE = """
            public class Sample {
                private int count;
                public static final String GREETING = "hello";

                public Sample() {
                    this.count = 42;
                }

                public int add(int a, int b) {
                    return a + b;
                }

                public static void main(String[] args) {
                    System.out.println(new Sample().add(1, 2));
                }
            }
            """;

    public static void main(String[] args) throws Exception {
        int pass = 0;
        int total = 15;

        byte[] bytes = TestClassBytes.compile("Sample", SAMPLE_SOURCE);
        ClassFile cf = ClassFileParser.parse(bytes);

        pass += check("magicはCAFEBABE", cf.magic() == 0xCAFEBABE);
        pass += check("major versionはJava 21相当(65)以上",
                cf.majorVersion() >= 65);
        pass += check("this_classはSampleを指す", cf.classNameAt(cf.thisClass()).equals("Sample"));
        pass += check("super_classはjava/lang/Object", cf.classNameAt(cf.superClass()).equals("java/lang/Object"));
        pass += check("interfacesは0件", cf.interfaces().isEmpty());
        pass += check("fieldsは2件(count, GREETING)", cf.fields().size() == 2);

        boolean hasCount = cf.fields().stream().anyMatch(f -> cf.utf8At(f.nameIndex()).equals("count"));
        boolean hasGreeting = cf.fields().stream().anyMatch(f -> cf.utf8At(f.nameIndex()).equals("GREETING"));
        pass += check("countフィールドが存在する", hasCount);
        pass += check("GREETINGフィールドが存在する", hasGreeting);

        List<String> methodNames = cf.methods().stream().map(m -> cf.utf8At(m.nameIndex())).toList();
        pass += check("メソッド一覧に<init>を含む", methodNames.contains("<init>"));
        pass += check("メソッド一覧にaddを含む", methodNames.contains("add"));
        pass += check("メソッド一覧にmainを含む", methodNames.contains("main"));

        MemberInfo addMethod = cf.methods().stream()
                .filter(m -> cf.utf8At(m.nameIndex()).equals("add")).findFirst().orElseThrow();
        pass += check("addの記述子は(II)I", cf.utf8At(addMethod.descriptorIndex()).equals("(II)I"));
        pass += check("addメソッドはpublic", AccessFlags.methodFlags(addMethod.accessFlags()).contains("public"));

        // 壊れたクラスファイル: マジックナンバー不一致
        boolean threwOnBadMagic = false;
        try {
            ClassFileParser.parse(new byte[]{0, 0, 0, 0, 0, 0, 0, 0});
        } catch (ClassFileFormatException e) {
            threwOnBadMagic = true;
        }
        pass += check("マジックナンバー不一致でClassFileFormatExceptionを送出", threwOnBadMagic);

        // 途中で切れているバイト列
        boolean threwOnTruncated = false;
        try {
            byte[] truncated = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0};
            ClassFileParser.parse(truncated);
        } catch (ClassFileFormatException e) {
            threwOnTruncated = true;
        }
        pass += check("途中で切れたバイト列でClassFileFormatExceptionを送出", threwOnTruncated);

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
