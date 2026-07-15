package dev.javatexteditor.classfile;

import java.util.ArrayList;
import java.util.List;

/** JVM仕様 §4.7.3 の Code 属性。method_info の attributes 内にある生バイト列（{@link AttributeInfo#info()}）から解析する。 */
public record CodeAttribute(
        int maxStack,
        int maxLocals,
        byte[] code,
        List<ExceptionTableEntry> exceptionTable,
        List<AttributeInfo> attributes) {

    public record ExceptionTableEntry(int startPc, int endPc, int handlerPc, int catchType) {}

    public static CodeAttribute parse(byte[] info) throws ClassFileFormatException {
        ByteReader r = new ByteReader(info);
        int maxStack = r.readU2();
        int maxLocals = r.readU2();
        long codeLength = r.readU4() & 0xFFFFFFFFL;
        if (codeLength > Integer.MAX_VALUE - 8) {
            throw new ClassFileFormatException("code_length too large: " + codeLength);
        }
        byte[] code = r.readBytes((int) codeLength);

        int exTableLen = r.readU2();
        List<ExceptionTableEntry> exTable = new ArrayList<>();
        for (int i = 0; i < exTableLen; i++) {
            exTable.add(new ExceptionTableEntry(r.readU2(), r.readU2(), r.readU2(), r.readU2()));
        }

        int attrCount = r.readU2();
        List<AttributeInfo> attrs = new ArrayList<>();
        for (int i = 0; i < attrCount; i++) {
            int nameIndex = r.readU2();
            long len = r.readU4() & 0xFFFFFFFFL;
            if (len > Integer.MAX_VALUE - 8) {
                throw new ClassFileFormatException("attribute length too large: " + len);
            }
            attrs.add(new AttributeInfo(nameIndex, r.readBytes((int) len)));
        }
        return new CodeAttribute(maxStack, maxLocals, code, List.copyOf(exTable), List.copyOf(attrs));
    }
}
