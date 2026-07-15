package dev.javatexteditor.classfile;

/**
 * JVM仕様（The Java Virtual Machine Specification, §4.4）で定義される定数プールの1エントリ。
 * 全ての具象型は同一ファイル内のrecordのため permits 節は省略できる（Java 21のsealed仕様）。
 */
public sealed interface ConstantPoolEntry {

    /** javap風の表示に使うタグ名（"Utf8"/"Methodref"等）。 */
    String tagName();

    record Utf8Entry(String value) implements ConstantPoolEntry {
        public String tagName() { return "Utf8"; }
    }
    record IntegerEntry(int value) implements ConstantPoolEntry {
        public String tagName() { return "Integer"; }
    }
    record FloatEntry(float value) implements ConstantPoolEntry {
        public String tagName() { return "Float"; }
    }
    record LongEntry(long value) implements ConstantPoolEntry {
        public String tagName() { return "Long"; }
    }
    record DoubleEntry(double value) implements ConstantPoolEntry {
        public String tagName() { return "Double"; }
    }
    record ClassEntry(int nameIndex) implements ConstantPoolEntry {
        public String tagName() { return "Class"; }
    }
    record StringEntry(int stringIndex) implements ConstantPoolEntry {
        public String tagName() { return "String"; }
    }
    record FieldrefEntry(int classIndex, int nameAndTypeIndex) implements ConstantPoolEntry {
        public String tagName() { return "Fieldref"; }
    }
    record MethodrefEntry(int classIndex, int nameAndTypeIndex) implements ConstantPoolEntry {
        public String tagName() { return "Methodref"; }
    }
    record InterfaceMethodrefEntry(int classIndex, int nameAndTypeIndex) implements ConstantPoolEntry {
        public String tagName() { return "InterfaceMethodref"; }
    }
    record NameAndTypeEntry(int nameIndex, int descriptorIndex) implements ConstantPoolEntry {
        public String tagName() { return "NameAndType"; }
    }
    record MethodHandleEntry(int referenceKind, int referenceIndex) implements ConstantPoolEntry {
        public String tagName() { return "MethodHandle"; }
    }
    record MethodTypeEntry(int descriptorIndex) implements ConstantPoolEntry {
        public String tagName() { return "MethodType"; }
    }
    record DynamicEntry(int bootstrapMethodAttrIndex, int nameAndTypeIndex) implements ConstantPoolEntry {
        public String tagName() { return "Dynamic"; }
    }
    record InvokeDynamicEntry(int bootstrapMethodAttrIndex, int nameAndTypeIndex) implements ConstantPoolEntry {
        public String tagName() { return "InvokeDynamic"; }
    }
    record ModuleEntry(int nameIndex) implements ConstantPoolEntry {
        public String tagName() { return "Module"; }
    }
    record PackageEntry(int nameIndex) implements ConstantPoolEntry {
        public String tagName() { return "Package"; }
    }
    /** Long/Doubleが占める2番目のスロット。JVM仕様上「未使用」と定義されるプレースホルダー。 */
    record LongDoublePlaceholder() implements ConstantPoolEntry {
        public String tagName() { return "(unusable)"; }
    }
}
