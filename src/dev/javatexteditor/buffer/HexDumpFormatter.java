package dev.javatexteditor.buffer;

/**
 * バイナリデータをhexdump風テキストに変換・復元する。
 * 1行16バイト、オフセット・16進数表現・ASCII表現（印字不可能な文字は'.'）を並べる、
 * 一般的な`hexdump -C`/`xxd`と同じ配置。列位置（{@link #hexDigitColumn}/{@link #asciiColumn}）は
 * Mode.BINARY（ModalEditor）のカーソル移動・1桁上書き編集からも直接参照される固定レイアウト契約。
 */
public final class HexDumpFormatter {

    public static final int BYTES_PER_LINE = 16;
    /** "%08x  " のオフセット欄の文字数（8桁の16進数 + 半角スペース2つ）。 */
    private static final int OFFSET_FIELD_LEN = 10;
    /** ASCII欄（各行）の開始列。オフセット欄 + 16進数16バイト分(3文字×16 + 8バイト目の後の追加スペース1) + " |"。 */
    private static final int ASCII_START = OFFSET_FIELD_LEN + BYTES_PER_LINE * 3 + 1 + 2;

    private HexDumpFormatter() {}

    /** 行内のバイトインデックス i（0〜15）に対応する16進数2桁の開始列。 */
    public static int hexDigitColumn(int i) {
        return OFFSET_FIELD_LEN + i * 3 + (i >= 8 ? 1 : 0);
    }

    /** 行内のバイトインデックス i（0〜15）に対応するASCII表現の列。 */
    public static int asciiColumn(int i) {
        return ASCII_START + i;
    }

    /** バイナリプレビューバッファの先頭に置くヘッダ行。 */
    public static String header(String fileName, int byteCount) {
        return "*binary* " + fileName + " — " + byteCount + " bytes";
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

    /**
     * {@link #format} の逆変換。1行目（ヘッダ）を除く各行から16進数2桁を{@link #hexDigitColumn}の
     * 固定列位置で読み取りバイト列を復元する。Mode.BINARY は上書き編集（1文字delete+insert）しか
     * 許可しないため、行数・列位置のレイアウトは常に{@code format()}が生成した通りに保たれている前提。
     * レイアウトが壊れている場合は{@link NumberFormatException}または
     * {@link StringIndexOutOfBoundsException}を送出する（呼び出し側で保存エラーとして扱う）。
     */
    public static byte[] parse(String bufferText, int byteCount) {
        byte[] result = new byte[byteCount];
        if (byteCount == 0) return result;
        String[] lines = bufferText.split("\n", -1);
        int idx = 0;
        for (int lineIdx = 1; lineIdx < lines.length && idx < byteCount; lineIdx++) {
            String line = lines[lineIdx];
            int lineLength = Math.min(BYTES_PER_LINE, byteCount - idx);
            for (int i = 0; i < lineLength; i++) {
                int col = hexDigitColumn(i);
                String hex = line.substring(col, col + 2);
                result[idx++] = (byte) Integer.parseInt(hex, 16);
            }
        }
        if (idx < byteCount) {
            throw new IllegalStateException("expected " + byteCount + " bytes, got " + idx);
        }
        return result;
    }
}
