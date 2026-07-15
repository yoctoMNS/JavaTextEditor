package dev.javatexteditor.buffer;

/**
 * バイナリファイルを読み取り専用プレビュー用のhexdump風テキストに変換する。
 * 1行16バイト、オフセット・16進数表現・ASCII表現（印字不可能な文字は'.'）を並べる、
 * 一般的な`hexdump -C`/`xxd`と同じ配置。
 */
public final class HexDumpFormatter {

    private static final int BYTES_PER_LINE = 16;

    private HexDumpFormatter() {}

    /** バイナリプレビューバッファの先頭に置くヘッダ行。 */
    public static String header(String fileName, int byteCount) {
        return "*binary* " + fileName + " — " + byteCount + " bytes — read-only preview";
    }

    /** ヘッダ行を含むバッファ全文（既存のgrep結果やfiler一覧等の疑似バッファと同じ構成）を返す。 */
    public static String format(byte[] bytes, String fileName) {
        StringBuilder sb = new StringBuilder();
        sb.append(header(fileName, bytes.length)).append('\n');
        for (int offset = 0; offset < bytes.length; offset += BYTES_PER_LINE) {
            appendLine(sb, bytes, offset);
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, byte[] bytes, int offset) {
        int lineLength = Math.min(BYTES_PER_LINE, bytes.length - offset);
        sb.append(String.format("%08x  ", offset));
        for (int i = 0; i < BYTES_PER_LINE; i++) {
            if (i < lineLength) {
                sb.append(String.format("%02x ", bytes[offset + i]));
            } else {
                sb.append("   ");
            }
            if (i == 7) sb.append(' ');
        }
        sb.append(" |");
        for (int i = 0; i < lineLength; i++) {
            int b = bytes[offset + i] & 0xFF;
            sb.append((b >= 0x20 && b < 0x7F) ? (char) b : '.');
        }
        sb.append('|');
        if (offset + BYTES_PER_LINE < bytes.length) sb.append('\n');
    }
}
