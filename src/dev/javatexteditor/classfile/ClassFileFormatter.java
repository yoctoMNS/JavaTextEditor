package dev.javatexteditor.classfile;

import java.util.List;

/**
 * .classファイルを開いた際のデフォルト表示（構造ビュー）を組み立てる。
 * javap -v に近い情報量（magic/version/access flags/this_class/super_class/interfaces/
 * fields/methods/attributes/constant pool）を、文字化けしない読める形式で出す。
 * バイトコードそのものの命令列は表示しない（:nimo コマンドで {@link MnemonicFormatter} に切り替える）。
 */
public final class ClassFileFormatter {

    private ClassFileFormatter() {}

    public static String header(String fileName) {
        return "*class* " + fileName + " — structure view (:nimo でニーモニック表示)";
    }

    public static String format(ClassFile cf, String fileName) {
        StringBuilder sb = new StringBuilder();
        sb.append(header(fileName)).append("\n\n");
        sb.append("magic: 0x").append(Integer.toHexString(cf.magic()).toUpperCase()).append('\n');
        sb.append("minor version: ").append(cf.minorVersion()).append('\n');
        sb.append("major version: ").append(cf.majorVersion())
                .append(" (").append(javaVersionName(cf.majorVersion())).append(")\n");
        sb.append("access flags: (0x").append(String.format("%04x", cf.accessFlags())).append(") ")
                .append(String.join(", ", AccessFlags.classFlags(cf.accessFlags()))).append('\n');
        sb.append("this class: #").append(cf.thisClass()).append("  // ")
                .append(cf.classNameAt(cf.thisClass())).append('\n');
        sb.append("super class: #").append(cf.superClass()).append("  // ")
                .append(cf.superClass() == 0 ? "(none)" : cf.classNameAt(cf.superClass())).append('\n');

        sb.append("interfaces: ").append(cf.interfaces().size()).append('\n');
        for (int idx : cf.interfaces()) {
            sb.append("  #").append(idx).append("  // ").append(cf.classNameAt(idx)).append('\n');
        }

        sb.append("\nfields: ").append(cf.fields().size()).append('\n');
        for (MemberInfo f : cf.fields()) {
            sb.append("  ").append(String.join(" ", AccessFlags.fieldFlags(f.accessFlags())))
                    .append(' ').append(cf.utf8At(f.nameIndex()))
                    .append("  ; descriptor: ").append(cf.utf8At(f.descriptorIndex())).append('\n');
            appendAttributeSummary(sb, cf, f.attributes(), "    ");
        }

        sb.append("\nmethods: ").append(cf.methods().size()).append('\n');
        for (MemberInfo m : cf.methods()) {
            sb.append("  ").append(String.join(" ", AccessFlags.methodFlags(m.accessFlags())))
                    .append(' ').append(cf.utf8At(m.nameIndex())).append(cf.utf8At(m.descriptorIndex())).append('\n');
            appendAttributeSummary(sb, cf, m.attributes(), "    ");
        }

        sb.append("\nattributes: ").append(cf.attributes().size()).append('\n');
        appendAttributeSummary(sb, cf, cf.attributes(), "  ");

        sb.append("\nconstant pool: ").append(cf.constantPool().size() - 1).append(" entries\n");
        for (int i = 1; i < cf.constantPool().size(); i++) {
            ConstantPoolEntry e = cf.constantPool().get(i);
            if (e == null) continue;
            sb.append(String.format("  #%-4d = %-20s %s%n", i, e.tagName(), cf.describeConstant(i)));
        }
        return sb.toString();
    }

    private static void appendAttributeSummary(StringBuilder sb, ClassFile cf, List<AttributeInfo> attrs, String indent) {
        for (AttributeInfo a : attrs) {
            String name = cf.utf8At(a.nameIndex());
            switch (name) {
                case "Code" -> {
                    try {
                        CodeAttribute code = CodeAttribute.parse(a.info());
                        sb.append(indent).append("Code: stack=").append(code.maxStack())
                                .append(", locals=").append(code.maxLocals())
                                .append(", code_length=").append(code.code().length).append(" bytes\n");
                    } catch (ClassFileFormatException e) {
                        sb.append(indent).append("Code: <parse error: ").append(e.getMessage()).append(">\n");
                    }
                }
                case "ConstantValue" -> {
                    int idx = twoByteIndex(a.info());
                    sb.append(indent).append("ConstantValue: ").append(cf.describeConstant(idx)).append('\n');
                }
                case "SourceFile" -> {
                    int idx = twoByteIndex(a.info());
                    sb.append(indent).append("SourceFile: \"").append(cf.utf8At(idx)).append("\"\n");
                }
                default -> sb.append(indent).append(name).append(" (").append(a.info().length).append(" bytes)\n");
            }
        }
    }

    private static int twoByteIndex(byte[] info) {
        if (info.length < 2) return 0;
        return ((info[0] & 0xFF) << 8) | (info[1] & 0xFF);
    }

    /** major version = 44 + フィーチャーリリース番号（例: 65 → Java SE 21）という仕様表記に従う。 */
    private static String javaVersionName(int major) {
        int n = major - 44;
        if (n < 1) return "unknown";
        if (n <= 4) return "JDK 1." + n;
        return "Java SE " + n;
    }
}
