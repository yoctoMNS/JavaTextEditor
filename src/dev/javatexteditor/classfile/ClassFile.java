package dev.javatexteditor.classfile;

import java.util.List;

/**
 * JVM仕様（§4.1）のClassFile構造をそのまま表す。constantPool のインデックス0は仕様上未使用のためnull。
 * Long/Doubleエントリの直後のインデックスも同様に {@link ConstantPoolEntry.LongDoublePlaceholder} が入る。
 */
public record ClassFile(
        int magic,
        int minorVersion,
        int majorVersion,
        List<ConstantPoolEntry> constantPool,
        int accessFlags,
        int thisClass,
        int superClass,
        List<Integer> interfaces,
        List<MemberInfo> fields,
        List<MemberInfo> methods,
        List<AttributeInfo> attributes) {

    /** #index が Utf8Entry を指す場合その文字列を返す。不正な参照は "?" を返す（表示を止めないため）。 */
    public String utf8At(int index) {
        if (index <= 0 || index >= constantPool.size()) return "?";
        if (constantPool.get(index) instanceof ConstantPoolEntry.Utf8Entry u) return u.value();
        return "?";
    }

    /** #index が ClassEntry を指す場合、その完全修飾名（"/" 区切りのまま）を返す。 */
    public String classNameAt(int index) {
        if (index <= 0 || index >= constantPool.size()) return "?";
        if (constantPool.get(index) instanceof ConstantPoolEntry.ClassEntry c) return utf8At(c.nameIndex());
        return "?";
    }

    /** javap風の1行コメント（"// java/lang/Object.\"<init>\":()V" 等）を組み立てる。 */
    public String describeConstant(int index) {
        if (index <= 0 || index >= constantPool.size()) return "?";
        ConstantPoolEntry e = constantPool.get(index);
        if (e == null) return "?";
        return switch (e) {
            case ConstantPoolEntry.Utf8Entry u -> u.value();
            case ConstantPoolEntry.IntegerEntry i -> String.valueOf(i.value());
            case ConstantPoolEntry.FloatEntry f -> f.value() + "f";
            case ConstantPoolEntry.LongEntry l -> l.value() + "L";
            case ConstantPoolEntry.DoubleEntry d -> String.valueOf(d.value());
            case ConstantPoolEntry.ClassEntry c -> utf8At(c.nameIndex()).replace('/', '.');
            case ConstantPoolEntry.StringEntry s -> "\"" + utf8At(s.stringIndex()) + "\"";
            case ConstantPoolEntry.FieldrefEntry f -> "Field " + refDescription(f.classIndex(), f.nameAndTypeIndex());
            case ConstantPoolEntry.MethodrefEntry m -> "Method " + refDescription(m.classIndex(), m.nameAndTypeIndex());
            case ConstantPoolEntry.InterfaceMethodrefEntry m ->
                "InterfaceMethod " + refDescription(m.classIndex(), m.nameAndTypeIndex());
            case ConstantPoolEntry.NameAndTypeEntry nt -> utf8At(nt.nameIndex()) + ":" + utf8At(nt.descriptorIndex());
            case ConstantPoolEntry.MethodHandleEntry mh ->
                "MethodHandle " + mh.referenceKind() + ":" + describeConstant(mh.referenceIndex());
            case ConstantPoolEntry.MethodTypeEntry mt -> "MethodType " + utf8At(mt.descriptorIndex());
            case ConstantPoolEntry.DynamicEntry dy ->
                "Dynamic #" + dy.bootstrapMethodAttrIndex() + ":" + describeNameAndType(dy.nameAndTypeIndex());
            case ConstantPoolEntry.InvokeDynamicEntry id ->
                "InvokeDynamic #" + id.bootstrapMethodAttrIndex() + ":" + describeNameAndType(id.nameAndTypeIndex());
            case ConstantPoolEntry.ModuleEntry m -> utf8At(m.nameIndex());
            case ConstantPoolEntry.PackageEntry p -> utf8At(p.nameIndex());
            case ConstantPoolEntry.LongDoublePlaceholder ignored -> "(unusable)";
        };
    }

    private String refDescription(int classIndex, int nameAndTypeIndex) {
        return classNameAt(classIndex) + "." + describeNameAndType(nameAndTypeIndex);
    }

    private String describeNameAndType(int natIndex) {
        if (natIndex <= 0 || natIndex >= constantPool.size()) return "?";
        if (constantPool.get(natIndex) instanceof ConstantPoolEntry.NameAndTypeEntry nt) {
            return utf8At(nt.nameIndex()) + ":" + utf8At(nt.descriptorIndex());
        }
        return "?";
    }
}
