package dev.javatexteditor.classfile;

import java.util.ArrayList;
import java.util.List;

/** JVM仕様 §4.1（クラス）・§4.5（フィールド）・§4.6（メソッド）のaccess_flagsビットを文字列化する。 */
public final class AccessFlags {

    public static final int ACC_PUBLIC = 0x0001;
    public static final int ACC_PRIVATE = 0x0002;
    public static final int ACC_PROTECTED = 0x0004;
    public static final int ACC_STATIC = 0x0008;
    public static final int ACC_FINAL = 0x0010;
    public static final int ACC_SUPER = 0x0020;
    public static final int ACC_SYNCHRONIZED = 0x0020;
    public static final int ACC_VOLATILE = 0x0040;
    public static final int ACC_BRIDGE = 0x0040;
    public static final int ACC_TRANSIENT = 0x0080;
    public static final int ACC_VARARGS = 0x0080;
    public static final int ACC_NATIVE = 0x0100;
    public static final int ACC_INTERFACE = 0x0200;
    public static final int ACC_ABSTRACT = 0x0400;
    public static final int ACC_STRICT = 0x0800;
    public static final int ACC_SYNTHETIC = 0x1000;
    public static final int ACC_ANNOTATION = 0x2000;
    public static final int ACC_ENUM = 0x4000;
    public static final int ACC_MODULE = 0x8000;

    private AccessFlags() {}

    public static List<String> classFlags(int flags) {
        List<String> out = new ArrayList<>();
        if ((flags & ACC_PUBLIC) != 0) out.add("ACC_PUBLIC");
        if ((flags & ACC_FINAL) != 0) out.add("ACC_FINAL");
        if ((flags & ACC_SUPER) != 0) out.add("ACC_SUPER");
        if ((flags & ACC_INTERFACE) != 0) out.add("ACC_INTERFACE");
        if ((flags & ACC_ABSTRACT) != 0) out.add("ACC_ABSTRACT");
        if ((flags & ACC_SYNTHETIC) != 0) out.add("ACC_SYNTHETIC");
        if ((flags & ACC_ANNOTATION) != 0) out.add("ACC_ANNOTATION");
        if ((flags & ACC_ENUM) != 0) out.add("ACC_ENUM");
        if ((flags & ACC_MODULE) != 0) out.add("ACC_MODULE");
        return out;
    }

    public static List<String> fieldFlags(int flags) {
        List<String> out = new ArrayList<>();
        if ((flags & ACC_PUBLIC) != 0) out.add("public");
        if ((flags & ACC_PRIVATE) != 0) out.add("private");
        if ((flags & ACC_PROTECTED) != 0) out.add("protected");
        if ((flags & ACC_STATIC) != 0) out.add("static");
        if ((flags & ACC_FINAL) != 0) out.add("final");
        if ((flags & ACC_VOLATILE) != 0) out.add("volatile");
        if ((flags & ACC_TRANSIENT) != 0) out.add("transient");
        if ((flags & ACC_SYNTHETIC) != 0) out.add("synthetic");
        if ((flags & ACC_ENUM) != 0) out.add("enum");
        return out;
    }

    public static List<String> methodFlags(int flags) {
        List<String> out = new ArrayList<>();
        if ((flags & ACC_PUBLIC) != 0) out.add("public");
        if ((flags & ACC_PRIVATE) != 0) out.add("private");
        if ((flags & ACC_PROTECTED) != 0) out.add("protected");
        if ((flags & ACC_STATIC) != 0) out.add("static");
        if ((flags & ACC_FINAL) != 0) out.add("final");
        if ((flags & ACC_SYNCHRONIZED) != 0) out.add("synchronized");
        if ((flags & ACC_BRIDGE) != 0) out.add("bridge");
        if ((flags & ACC_VARARGS) != 0) out.add("varargs");
        if ((flags & ACC_NATIVE) != 0) out.add("native");
        if ((flags & ACC_ABSTRACT) != 0) out.add("abstract");
        if ((flags & ACC_STRICT) != 0) out.add("strictfp");
        if ((flags & ACC_SYNTHETIC) != 0) out.add("synthetic");
        return out;
    }
}
