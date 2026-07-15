package dev.javatexteditor.classfile;

/** .classバイト列がJVM仕様に沿わない（マジックナンバー不一致・途中で途切れている等）場合に送出する。 */
public final class ClassFileFormatException extends Exception {
    public ClassFileFormatException(String message) {
        super(message);
    }
}
