package dev.javatexteditor.classfile;

/**
 * :nimo コマンドで表示するニーモニック（javap -c風）バイトコード逆アセンブルの全文を組み立てる。
 * 各メソッドについて、Code属性があれば {@link BytecodeDisassembler} で命令列に変換して並べる。
 * abstract/native等Code属性を持たないメソッドはその旨のみ表示する。
 */
public final class MnemonicFormatter {

    private MnemonicFormatter() {}

    public static String header(String fileName) {
        return "*nimo* " + fileName + " — mnemonic bytecode view";
    }

    public static String format(ClassFile cf, String fileName) {
        StringBuilder sb = new StringBuilder();
        sb.append(header(fileName)).append("\n\n");
        for (MemberInfo method : cf.methods()) {
            String name = cf.utf8At(method.nameIndex());
            String descriptor = cf.utf8At(method.descriptorIndex());
            sb.append(String.join(" ", AccessFlags.methodFlags(method.accessFlags())))
                    .append(' ').append(name).append(descriptor).append(":\n");

            AttributeInfo codeAttr = findAttribute(cf, method, "Code");
            if (codeAttr == null) {
                sb.append("  (Codeなし — abstract/nativeメソッド)\n\n");
                continue;
            }
            try {
                CodeAttribute code = CodeAttribute.parse(codeAttr.info());
                sb.append("  stack=").append(code.maxStack())
                        .append(", locals=").append(code.maxLocals())
                        .append(", code_length=").append(code.code().length).append('\n');
                for (BytecodeDisassembler.Instruction ins : BytecodeDisassembler.disassemble(code.code(), cf)) {
                    sb.append(String.format("  %5d: %s%n", ins.offset(), ins.text()));
                }
            } catch (ClassFileFormatException e) {
                sb.append("  E: failed to disassemble Code attribute: ").append(e.getMessage()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static AttributeInfo findAttribute(ClassFile cf, MemberInfo member, String name) {
        for (AttributeInfo a : member.attributes()) {
            if (cf.utf8At(a.nameIndex()).equals(name)) return a;
        }
        return null;
    }
}
