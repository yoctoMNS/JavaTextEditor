package dev.javatexteditor.buffer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * バイト列がテキスト（UTF-8）として扱えるかを判定する。
 * NULバイトを含む場合は常にバイナリ扱いとする（UTF-16等、偶然UTF-8として妥当な
 * バイト列になり得るテキストエンコーディングや、真のバイナリファイルの両方を拾うため）。
 */
public final class BinaryFileDetector {

    private BinaryFileDetector() {}

    public static boolean isBinary(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) return true;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
            return false;
        } catch (CharacterCodingException e) {
            return true;
        }
    }
}
