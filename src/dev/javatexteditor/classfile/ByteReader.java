package dev.javatexteditor.classfile;

/** .classバイト列を先頭から順に読み進める、JVM仕様の各種プリミティブ幅(u1/u2/u4/u8)専用の下請けクラス。 */
final class ByteReader {
    private final byte[] data;
    private int pos;

    ByteReader(byte[] data) {
        this.data = data;
    }

    int readU1() throws ClassFileFormatException {
        require(1);
        return data[pos++] & 0xFF;
    }

    int readU2() throws ClassFileFormatException {
        require(2);
        int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    int readU4() throws ClassFileFormatException {
        require(4);
        int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    long readU8() throws ClassFileFormatException {
        require(8);
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (data[pos + i] & 0xFFL);
        pos += 8;
        return v;
    }

    byte[] readBytes(int length) throws ClassFileFormatException {
        require(length);
        byte[] out = new byte[length];
        System.arraycopy(data, pos, out, 0, length);
        pos += length;
        return out;
    }

    /** CONSTANT_Utf8（Modified UTF-8）を読み、通常のJava文字列にデコードする。 */
    String readUtf8() throws ClassFileFormatException {
        int length = readU2();
        return decodeModifiedUtf8(readBytes(length));
    }

    private void require(int n) throws ClassFileFormatException {
        if (n < 0 || pos + n > data.length) {
            throw new ClassFileFormatException("unexpected end of class file at offset " + pos);
        }
    }

    /** java.io.DataInputと同じModified UTF-8デコードアルゴリズム。 */
    private static String decodeModifiedUtf8(byte[] bytes) throws ClassFileFormatException {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < bytes.length) {
            int b1 = bytes[i] & 0xFF;
            if (b1 == 0) {
                throw new ClassFileFormatException("invalid modified UTF-8: embedded null byte");
            } else if ((b1 & 0x80) == 0) {
                sb.append((char) b1);
                i += 1;
            } else if ((b1 & 0xE0) == 0xC0) {
                if (i + 1 >= bytes.length) throw new ClassFileFormatException("truncated modified UTF-8");
                int b2 = bytes[i + 1] & 0xFF;
                sb.append((char) (((b1 & 0x1F) << 6) | (b2 & 0x3F)));
                i += 2;
            } else if ((b1 & 0xF0) == 0xE0) {
                if (i + 2 >= bytes.length) throw new ClassFileFormatException("truncated modified UTF-8");
                int b2 = bytes[i + 1] & 0xFF;
                int b3 = bytes[i + 2] & 0xFF;
                sb.append((char) (((b1 & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F)));
                i += 3;
            } else {
                throw new ClassFileFormatException("invalid modified UTF-8 leading byte");
            }
        }
        return sb.toString();
    }
}
