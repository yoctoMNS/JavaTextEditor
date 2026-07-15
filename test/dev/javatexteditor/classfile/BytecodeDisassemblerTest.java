package dev.javatexteditor.classfile;

import java.util.Arrays;
import java.util.List;

public class BytecodeDisassemblerTest {

    private static final String SAMPLE_SOURCE = """
            public class Sample2 {
                public Sample2() {
                }

                public int add(int a, int b) {
                    return a + b;
                }

                public static void main(String[] args) {
                    System.out.println(new Sample2().add(1, 2));
                }
            }
            """;

    public static void main(String[] args) throws Exception {
        int pass = 0;
        int total = 15;

        byte[] classBytes = TestClassBytes.compile("Sample2", SAMPLE_SOURCE);
        ClassFile cf = ClassFileParser.parse(classBytes);

        MemberInfo init = findMethod(cf, "<init>");
        List<BytecodeDisassembler.Instruction> initCode = disassembleMethod(cf, init);
        String initText = joinText(initCode);
        pass += check("<init>はaload_0で始まる", initCode.get(0).text().equals("aload_0"));
        pass += check("<init>はinvokespecialでObject.<init>を呼ぶ",
                initText.contains("invokespecial") && initText.contains("java/lang/Object.<init>"));
        pass += check("<init>はreturnで終わる", initText.contains("return"));

        MemberInfo add = findMethod(cf, "add");
        String addText = joinText(disassembleMethod(cf, add));
        pass += check("addはiload_1を含む", addText.contains("iload_1"));
        pass += check("addはiload_2を含む", addText.contains("iload_2"));
        pass += check("addはiaddを含む", addText.contains("iadd"));
        pass += check("addはireturnを含む", addText.contains("ireturn"));

        MemberInfo main = findMethod(cf, "main");
        String mainText = joinText(disassembleMethod(cf, main));
        pass += check("mainはgetstaticを含む", mainText.contains("getstatic"));
        pass += check("mainはnewを含む", mainText.contains("new"));
        pass += check("mainはinvokevirtualを含む", mainText.contains("invokevirtual"));

        List<BytecodeDisassembler.Instruction> mainInstructions = disassembleMethod(cf, main);
        boolean monotonic = true;
        for (int i = 1; i < mainInstructions.size(); i++) {
            if (mainInstructions.get(i).offset() <= mainInstructions.get(i - 1).offset()) monotonic = false;
        }
        pass += check("命令オフセットは単調増加", monotonic);

        // 分岐命令のオフセット解決（手組みのバイト列）: iconst_0; ifeq -> 5; iconst_1; ireturn; iconst_2; ireturn
        ClassFile dummy = dummyClassFile();
        byte[] branchCode = {3, (byte) 153, 0, 4, 4, (byte) 172, 5, (byte) 172};
        String branchText = joinText(BytecodeDisassembler.disassemble(branchCode, dummy));
        pass += check("ifeqの分岐先が絶対オフセット5に解決される", branchText.contains("ifeq 5"));

        // tableswitch: low=0 high=1、default=20、case0=16、case1=18（すべて絶対オフセット）
        byte[] tableSwitchCode = {
                (byte) 170, 0, 0, 0,
                0, 0, 0, 20,
                0, 0, 0, 0,
                0, 0, 0, 1,
                0, 0, 0, 16,
                0, 0, 0, 18,
        };
        String tableSwitchText = joinText(BytecodeDisassembler.disassemble(tableSwitchCode, dummy));
        pass += check("tableswitchが各caseと既定値を絶対オフセットで解決する",
                tableSwitchText.contains("0: 16") && tableSwitchText.contains("1: 18")
                        && tableSwitchText.contains("default: 20"));

        // wide: iload #300
        byte[] wideLoadCode = {(byte) 196, 21, 1, 44};
        String wideLoadText = joinText(BytecodeDisassembler.disassemble(wideLoadCode, dummy));
        pass += check("wide iloadが局所変数インデックス300を読む", wideLoadText.contains("iload 300"));

        // lookupswitch: default=20、(match=5,offset=16)、(match=10,offset=18)（すべて絶対オフセット）
        byte[] lookupSwitchCode = {
                (byte) 171, 0, 0, 0,
                0, 0, 0, 20,
                0, 0, 0, 2,
                0, 0, 0, 5,
                0, 0, 0, 16,
                0, 0, 0, 10,
                0, 0, 0, 18,
        };
        String lookupSwitchText = joinText(BytecodeDisassembler.disassemble(lookupSwitchCode, dummy));
        pass += check("lookupswitchが各matchと既定値を絶対オフセットで解決する",
                lookupSwitchText.contains("5: 16") && lookupSwitchText.contains("10: 18")
                        && lookupSwitchText.contains("default: 20"));

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static MemberInfo findMethod(ClassFile cf, String name) {
        return cf.methods().stream().filter(m -> cf.utf8At(m.nameIndex()).equals(name)).findFirst().orElseThrow();
    }

    private static List<BytecodeDisassembler.Instruction> disassembleMethod(ClassFile cf, MemberInfo method) throws ClassFileFormatException {
        for (AttributeInfo a : method.attributes()) {
            if (cf.utf8At(a.nameIndex()).equals("Code")) {
                CodeAttribute code = CodeAttribute.parse(a.info());
                return BytecodeDisassembler.disassemble(code.code(), cf);
            }
        }
        throw new IllegalStateException("no Code attribute");
    }

    private static String joinText(List<BytecodeDisassembler.Instruction> instructions) {
        StringBuilder sb = new StringBuilder();
        for (BytecodeDisassembler.Instruction i : instructions) sb.append(i.text()).append('\n');
        return sb.toString();
    }

    /** 定数プール参照を伴わないopcode専用テストのための最小限のClassFile（インデックス0はnullで十分）。 */
    private static ClassFile dummyClassFile() {
        return new ClassFile(0xCAFEBABE, 0, 65, Arrays.asList(new ConstantPoolEntry[]{null}),
                0, 0, 0, List.of(), List.of(), List.of(), List.of());
    }

    static int check(String name, boolean condition) {
        System.out.println((condition ? "[OK] " : "[FAIL] ") + name);
        return condition ? 1 : 0;
    }
}
