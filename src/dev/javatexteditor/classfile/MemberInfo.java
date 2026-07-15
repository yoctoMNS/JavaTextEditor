package dev.javatexteditor.classfile;

import java.util.List;

/** JVM仕様の field_info / method_info（§4.5・§4.6）。構造が同一のため共用する。 */
public record MemberInfo(int accessFlags, int nameIndex, int descriptorIndex, List<AttributeInfo> attributes) {
}
