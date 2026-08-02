package dev.javatexteditor.buffer;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link MappedFileSource} 上の「行番号 ⇔ バイトオフセット ⇔ 文字(UTF-16コードユニット)オフセット」
 * 対応表を、ファイルを開いた時点では一切構築せず、実際に問い合わせがあった範囲だけ都度構築する
 * 遅延インデックス。
 *
 * <p>設計方針（なぜ全行を事前構築しないか）: 数十万行〜のファイルで、スクロールで実際に
 * 表示される行はごく一部にすぎない。開いた瞬間に全行のオフセットを表に持つと、ファイルサイズに
 * 比例した時間・メモリを開くたびに消費してしまう。逆に「毎回ファイル先頭から数える」実装
 * （旧 {@code PieceTable.offsetOfLine} と同種）は、末尾付近の行を問い合わせるたびに
 * O(ファイル全体)を繰り返すため、下方向へのスクロールが進むほど遅くなる。
 *
 * <p>採用した方式は「{@link #CHECKPOINT_INTERVAL} 行おきのスパースなチェックポイント」を
 * 持ち、問い合わせがあった行・オフセットまでだけを前進スキャンして到達点をチェックポイントとして
 * 記録するというもの。2回目以降の問い合わせは直近のチェックポイントから前進するだけで済み、
 * ファイル全体を再スキャンしない。
 *
 * <p>なぜバイトオフセットだけでなく文字(char)オフセットも同時に持つのか: このエディタの
 * カーソル位置・{@code PieceTable.insert/delete} の引数は既存コード全体（{@code ModalEditor}
 * の数十箇所）で「UTF-16コードユニット単位のオフセット」を前提にしている。これをmmap導入のために
 * 「バイトオフセット」へ全面的に置き換えると、カーソル移動・検索・全角文字幅計算など
 * エディタ全体に影響が及ぶ改修になってしまう（{@code gui-rendering-pipeline} スキルが担当する
 * 全角文字幅対応と密結合しており、影響範囲が過大）。そこで本クラスは「文字オフセットの入出力を
 * 保ったまま、内部でだけバイトオフセットとの相互変換を担う」ことで、既存の呼び出し側に一切
 * 手を入れずに済む設計にした。
 *
 * <p>相互変換はUTF-8の「先頭バイト（継続バイトでない = 上位2bitが{@code 10}でない）」だけを
 * 数える方式で行う。1〜3バイトのUTF-8シーケンス（BMP内の文字）はJavaのchar 1個に対応するが、
 * 4バイトシーケンス（補助面文字・絵文字等）はJavaでは サロゲートペア＝char 2個に対応するため、
 * 先頭バイトが4バイトシーケンスかどうかで+1か+2かを判定する（{@link #charUnitsForLeadByte}）。
 * この判定は文字列へデコードせずバイト値だけで完結するため、行の途中までデコードするより
 * 軽量に「バイト位置→文字数」を積算できる。
 */
public final class LazyLineIndex {

    /** チェックポイントを記録する行数間隔。大きいほど省メモリ・1回のスキャン距離は長くなる。 */
    static final int CHECKPOINT_INTERVAL = 4096;

    private record Checkpoint(long byteOffset, long charOffset) {}

    private final MappedFileSource source;
    // checkpoints.get(k) == 行番号 (k * CHECKPOINT_INTERVAL) が始まる (バイト, 文字) オフセット
    private final List<Checkpoint> checkpoints = new ArrayList<>();
    private long scannedUpToByte = 0;
    private long scannedUpToChar = 0;
    private int scannedUpToLine = 0;
    private boolean finished = false;
    private int totalLineCount = -1;
    private long totalCharCount = -1;

    public LazyLineIndex(MappedFileSource source) {
        this.source = source;
        checkpoints.add(new Checkpoint(0L, 0L)); // 0行目は常にバイト0・文字0から始まる
    }

    /** lineNumber行目（0-based）が始まる文字(char)オフセットを返す。範囲外はファイル末尾扱い。 */
    public long charOffsetOfLine(int lineNumber) {
        if (lineNumber <= 0) return 0;
        ensureScannedToLine(lineNumber);
        if (lineNumber > scannedUpToLine) {
            return finished ? totalCharCount : scannedUpToChar;
        }
        return scanWithinCheckpointToLine(lineNumber).charOffset();
    }

    /** lineNumber行目（0-based）が始まるバイトオフセットを返す。範囲外はファイル末尾扱い。 */
    public long byteOffsetOfLine(int lineNumber) {
        if (lineNumber <= 0) return 0;
        ensureScannedToLine(lineNumber);
        if (lineNumber > scannedUpToLine) {
            return source.size();
        }
        return scanWithinCheckpointToLine(lineNumber).byteOffset();
    }

    /**
     * グローバルな文字オフセット targetChar に対応するバイトオフセットを返す。
     * {@code PieceTable} がMAPPEDピース内で分割位置を決める際に使う変換の中核。
     * 直近のチェックポイントから前進スキャンするため、コストはファイル全体ではなく
     * 「チェックポイント間隔（既定4096行）ぶんのバイト数」に収まる。
     */
    public long byteOffsetOfCharOffset(long targetChar) {
        if (targetChar <= 0) return 0;
        ensureScannedToChar(targetChar);
        if (targetChar >= scannedUpToChar) {
            return finished ? source.size() : scannedUpToByte;
        }
        // charOffset で二分探索: checkpoints は行番号・バイト・文字のいずれでも単調増加
        int lo = 0, hi = checkpoints.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (checkpoints.get(mid).charOffset() <= targetChar) lo = mid; else hi = mid - 1;
        }
        Checkpoint cp = checkpoints.get(lo);
        long bytePos = cp.byteOffset();
        long charPos = cp.charOffset();
        while (charPos < targetChar) {
            int b = source.byteAt(bytePos);
            if (isLeadByte(b)) charPos += charUnitsForLeadByte(b);
            bytePos++;
        }
        return bytePos;
    }

    /**
     * charOffset位置が属する行番号（0-based）を返す。{@code byteOffsetOfCharOffset}と対になる
     * 逆変換で、{@code PieceTable.offsetOfLine}がMAPPEDピース内に含まれる改行数を求める際に使う。
     */
    public long lineAtCharOffset(long charOffset) {
        if (charOffset <= 0) return 0;
        ensureScannedToChar(charOffset);
        int lo = 0, hi = checkpoints.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (checkpoints.get(mid).charOffset() <= charOffset) lo = mid; else hi = mid - 1;
        }
        Checkpoint cp = checkpoints.get(lo);
        long charPos = cp.charOffset();
        long bytePos = cp.byteOffset();
        long size = source.size();
        int line = lo * CHECKPOINT_INTERVAL;
        while (charPos < charOffset && bytePos < size) {
            int b = source.byteAt(bytePos);
            if (isLeadByte(b)) charPos += charUnitsForLeadByte(b);
            if (b == '\n') line++;
            bytePos++;
        }
        return line;
    }

    /** 全行数。未走査ならここでファイル末尾までスキャンする一度きりのコストが発生し、以後キャッシュされる。 */
    public int lineCount() {
        ensureFullyScanned();
        return totalLineCount;
    }

    /** 文書全体の文字数。lineCount() 同様、初回のみファイル末尾までのスキャンコストがかかる。 */
    public long totalCharCount() {
        ensureFullyScanned();
        return totalCharCount;
    }

    private void ensureFullyScanned() {
        if (!finished) {
            ensureScannedToLine(Integer.MAX_VALUE);
        }
    }

    /** 直近のチェックポイントから lineNumber まで前進し、その行の開始位置を返す（走査済み前提）。 */
    private Checkpoint scanWithinCheckpointToLine(int lineNumber) {
        int checkpointIndex = Math.min(lineNumber / CHECKPOINT_INTERVAL, checkpoints.size() - 1);
        Checkpoint cp = checkpoints.get(checkpointIndex);
        long bytePos = cp.byteOffset();
        long charPos = cp.charOffset();
        int line = checkpointIndex * CHECKPOINT_INTERVAL;
        while (line < lineNumber) {
            int b = source.byteAt(bytePos);
            if (isLeadByte(b)) charPos += charUnitsForLeadByte(b);
            if (b == '\n') line++;
            bytePos++;
        }
        return new Checkpoint(bytePos, charPos);
    }

    private void ensureScannedToLine(int lineNumber) {
        if (finished || scannedUpToLine >= lineNumber) return;
        advanceScan(() -> scannedUpToLine >= lineNumber);
    }

    private void ensureScannedToChar(long targetChar) {
        if (finished || scannedUpToChar >= targetChar) return;
        advanceScan(() -> scannedUpToChar >= targetChar);
    }

    /** scannedUpToByte から stop が true になる、またはEOFに達するまでバイト単位で前進する共通ループ。 */
    private void advanceScan(java.util.function.BooleanSupplier stop) {
        long bytePos = scannedUpToByte;
        long charPos = scannedUpToChar;
        int line = scannedUpToLine;
        long size = source.size();
        while (!stop.getAsBoolean() && bytePos < size) {
            int b = source.byteAt(bytePos);
            if (isLeadByte(b)) charPos += charUnitsForLeadByte(b);
            if (b == '\n') {
                line++;
                if (line % CHECKPOINT_INTERVAL == 0) {
                    int idx = line / CHECKPOINT_INTERVAL;
                    if (idx == checkpoints.size()) {
                        checkpoints.add(new Checkpoint(bytePos + 1, charPos));
                    }
                }
            }
            bytePos++;
        }
        scannedUpToByte = bytePos;
        scannedUpToChar = charPos;
        scannedUpToLine = line;
        if (bytePos >= size) {
            finished = true;
            totalLineCount = line + 1; // 末尾に改行が無くても最後の行を1行として数える
            totalCharCount = charPos;
        }
    }

    private static boolean isLeadByte(int b) {
        return (b & 0xC0) != 0x80;
    }

    /** UTF-8先頭バイトが表すUTF-16コードユニット数（1〜3バイト列=1、4バイト列(補助面)=サロゲートペアで2）。 */
    private static int charUnitsForLeadByte(int b) {
        if ((b & 0xF8) == 0xF0) return 2;
        return 1;
    }
}
