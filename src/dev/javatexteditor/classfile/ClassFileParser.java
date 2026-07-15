package dev.javatexteditor.classfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JVM仕様（§4.1）に従い .class の生バイト列を {@link ClassFile} 構造へパースする。Swing非依存の純粋ロジック。 */
public final class ClassFileParser {

    private static final int MAGIC = 0xCAFEBABE;

    private ClassFileParser() {}

    public static ClassFile parse(byte[] bytes) throws ClassFileFormatException {
        ByteReader r = new ByteReader(bytes);
        int magic = r.readU4();
        if (magic != MAGIC) {
            throw new ClassFileFormatException("invalid magic number: 0x" + Integer.toHexString(magic));
        }
        int minor = r.readU2();
        int major = r.readU2();

        int cpCount = r.readU2();
        ConstantPoolEntry[] pool = new ConstantPoolEntry[cpCount]; // index 0 は仕様上未使用
        for (int i = 1; i < cpCount; i++) {
            int tag = r.readU1();
            switch (tag) {
                case 1 -> pool[i] = new ConstantPoolEntry.Utf8Entry(r.readUtf8());
                case 3 -> pool[i] = new ConstantPoolEntry.IntegerEntry(r.readU4());
                case 4 -> pool[i] = new ConstantPoolEntry.FloatEntry(Float.intBitsToFloat(r.readU4()));
                case 5 -> {
                    pool[i] = new ConstantPoolEntry.LongEntry(r.readU8());
                    i++; // Long/Doubleは2スロット占有（仕様上の既知の特殊ルール）
                    if (i < cpCount) pool[i] = new ConstantPoolEntry.LongDoublePlaceholder();
                }
                case 6 -> {
                    pool[i] = new ConstantPoolEntry.DoubleEntry(Double.longBitsToDouble(r.readU8()));
                    i++;
                    if (i < cpCount) pool[i] = new ConstantPoolEntry.LongDoublePlaceholder();
                }
                case 7 -> pool[i] = new ConstantPoolEntry.ClassEntry(r.readU2());
                case 8 -> pool[i] = new ConstantPoolEntry.StringEntry(r.readU2());
                case 9 -> pool[i] = new ConstantPoolEntry.FieldrefEntry(r.readU2(), r.readU2());
                case 10 -> pool[i] = new ConstantPoolEntry.MethodrefEntry(r.readU2(), r.readU2());
                case 11 -> pool[i] = new ConstantPoolEntry.InterfaceMethodrefEntry(r.readU2(), r.readU2());
                case 12 -> pool[i] = new ConstantPoolEntry.NameAndTypeEntry(r.readU2(), r.readU2());
                case 15 -> pool[i] = new ConstantPoolEntry.MethodHandleEntry(r.readU1(), r.readU2());
                case 16 -> pool[i] = new ConstantPoolEntry.MethodTypeEntry(r.readU2());
                case 17 -> pool[i] = new ConstantPoolEntry.DynamicEntry(r.readU2(), r.readU2());
                case 18 -> pool[i] = new ConstantPoolEntry.InvokeDynamicEntry(r.readU2(), r.readU2());
                case 19 -> pool[i] = new ConstantPoolEntry.ModuleEntry(r.readU2());
                case 20 -> pool[i] = new ConstantPoolEntry.PackageEntry(r.readU2());
                default -> throw new ClassFileFormatException("unknown constant pool tag " + tag + " at #" + i);
            }
        }

        int accessFlags = r.readU2();
        int thisClass = r.readU2();
        int superClass = r.readU2();

        int interfaceCount = r.readU2();
        List<Integer> interfaces = new ArrayList<>();
        for (int i = 0; i < interfaceCount; i++) interfaces.add(r.readU2());

        int fieldCount = r.readU2();
        List<MemberInfo> fields = new ArrayList<>();
        for (int i = 0; i < fieldCount; i++) fields.add(readMember(r));

        int methodCount = r.readU2();
        List<MemberInfo> methods = new ArrayList<>();
        for (int i = 0; i < methodCount; i++) methods.add(readMember(r));

        int attrCount = r.readU2();
        List<AttributeInfo> attributes = new ArrayList<>();
        for (int i = 0; i < attrCount; i++) attributes.add(readAttribute(r));

        // index 0（未使用）はnullのため List.of ではなく Arrays.asList を使う（List.of はnull要素を禁止）
        return new ClassFile(magic, minor, major, Arrays.asList(pool), accessFlags, thisClass, superClass,
                List.copyOf(interfaces), List.copyOf(fields), List.copyOf(methods), List.copyOf(attributes));
    }

    private static MemberInfo readMember(ByteReader r) throws ClassFileFormatException {
        int accessFlags = r.readU2();
        int nameIndex = r.readU2();
        int descriptorIndex = r.readU2();
        int attrCount = r.readU2();
        List<AttributeInfo> attrs = new ArrayList<>();
        for (int i = 0; i < attrCount; i++) attrs.add(readAttribute(r));
        return new MemberInfo(accessFlags, nameIndex, descriptorIndex, List.copyOf(attrs));
    }

    private static AttributeInfo readAttribute(ByteReader r) throws ClassFileFormatException {
        int nameIndex = r.readU2();
        long length = r.readU4() & 0xFFFFFFFFL;
        if (length > Integer.MAX_VALUE - 8) {
            throw new ClassFileFormatException("attribute length too large: " + length);
        }
        byte[] info = r.readBytes((int) length);
        return new AttributeInfo(nameIndex, info);
    }
}
