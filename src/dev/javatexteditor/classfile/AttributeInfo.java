package dev.javatexteditor.classfile;

/** JVM仕様の attribute_info（§4.7）。info は未解釈の生バイト列（Codeなど必要な種別のみ別途パースする）。 */
public record AttributeInfo(int nameIndex, byte[] info) {
}
