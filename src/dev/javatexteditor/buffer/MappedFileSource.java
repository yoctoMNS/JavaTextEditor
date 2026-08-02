package dev.javatexteditor.buffer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ファイルを {@code RandomAccessFile} + {@code FileChannel#map}（{@code MappedByteBuffer}）で
 * 開き、OSのページキャッシュに委ねてバイト列へランダムアクセスするための読み取り専用ソース。
 *
 * <p>なぜ {@code Files.readAllBytes}/{@code Files.readString} ではなくこちらを使うのか:
 * 前者はファイル全体をヒープ上の {@code byte[]}（さらに {@code String} 化するなら追加でUTF-16の
 * {@code char[]}）へ一度にコピーする。数十MB〜GB級のファイルではこのコピー自体がオープン時間・
 * メモリ使用量の両面でボトルネックになる（実測・調査済み: {@code ModalEditor.readFileContentForBuffer}
 * が旧実装）。mmapはOSがページ単位で必要な部分だけを実メモリに載せるため、ファイルサイズが
 * どれだけ大きくても「開いた瞬間」のコストはほぼ一定になる。
 *
 * <p>{@code MappedByteBuffer} は1回の {@code map()} 呼び出しで {@code Integer.MAX_VALUE}
 * （約2GiB）までしか扱えない（マッピングサイズ引数が {@code long} でも内部表現はintベース）ため、
 * それを超えるファイルは {@link #CHUNK_SIZE} ごとの複数の {@code MappedByteBuffer}（窓）に
 * 分割してマップする。
 */
public final class MappedFileSource implements AutoCloseable {

    /** 1つの MappedByteBuffer が受け持つ最大バイト数。2GiB上限に余裕を持たせて1GiB刻みにする。 */
    static final long CHUNK_SIZE = 1L << 30;

    private final List<MappedByteBuffer> chunks = new ArrayList<>();
    private final long size;

    /**
     * {@code RandomAccessFile}/{@code FileChannel} は最後のチャンクをmapし終えた直後に閉じ、
     * フィールドとしては保持しない。JavaDoc上、{@code MappedByteBuffer}はチャネルを閉じても
     * 「バッファ自身がGCされるまで」有効であり続けると規定されているため、mapを終えたら
     * すぐにOSのファイルディスクリプタを解放してよい。エディタ全体でバッファ切替・疑似バッファ
     * 退避など複雑なライフサイクル管理箇所が多数あるため（このクラスの{@link #close()}呼び出しを
     * 全箇所に徹底させるのは非現実的）、fd解放だけは構築完了時点で確実に済ませ、マッピング自体の
     * 解放はJVMのGCに委ねるという設計にした。
     */
    public MappedFileSource(Path path) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
             FileChannel channel = raf.getChannel()) {
            this.size = channel.size();
            for (long offset = 0; offset < size; offset += CHUNK_SIZE) {
                long len = Math.min(CHUNK_SIZE, size - offset);
                chunks.add(channel.map(FileChannel.MapMode.READ_ONLY, offset, len));
            }
        }
    }

    /** ファイル全体のバイト数。 */
    public long size() {
        return size;
    }

    /** byteOffset位置（0-based, 半開区間の始点）の1バイトを符号無しintとして返す。 */
    public int byteAt(long byteOffset) {
        int chunkIndex = (int) (byteOffset / CHUNK_SIZE);
        int inChunk = (int) (byteOffset - (long) chunkIndex * CHUNK_SIZE);
        return chunks.get(chunkIndex).get(inChunk) & 0xFF;
    }

    /**
     * [start, end) のバイト範囲を素直にコピーして byte[] にする内部ヘルパー。
     * 呼び出し側（{@link #decode}）でしか使わないため範囲チェックは行わない。
     */
    private byte[] copyRange(long start, long end) {
        byte[] out = new byte[(int) (end - start)];
        long pos = start;
        int written = 0;
        while (pos < end) {
            int chunkIndex = (int) (pos / CHUNK_SIZE);
            int inChunk = (int) (pos - (long) chunkIndex * CHUNK_SIZE);
            MappedByteBuffer chunk = chunks.get(chunkIndex);
            int avail = chunk.capacity() - inChunk;
            int need = (int) Math.min(avail, end - pos);
            // duplicate()で位置共有を避け、複数スレッド・再入から独立したビューにする
            MappedByteBuffer dup = (MappedByteBuffer) chunk.duplicate();
            dup.position(inChunk);
            dup.get(out, written, need);
            written += need;
            pos += need;
        }
        return out;
    }

    /**
     * byteOffset以下で、かつUTF-8の文字境界として安全な位置まで後退させたオフセットを返す。
     * UTF-8の継続バイトは上位2bitが {@code 10} なので、それを辿って先頭バイトまで戻るだけでよい
     * （最大3バイト遡れば必ず先頭バイトに当たる。4バイト文字でも先頭バイトからの継続は3バイト）。
     *
     * <p>これにより「行番号やピース分割で決めたおおよそのバイト位置」をそのままデコード開始点に
     * 使っても、マルチバイト文字の途中を割ってしまう文字化け（要件6）を避けられる。事前に全文字の
     * オフセットを索引化する必要はない——文字境界の判定はローカルな数バイトの後方参照だけで完結する。
     */
    public long safeBoundaryAtOrBefore(long byteOffset) {
        long pos = Math.min(byteOffset, size);
        int guard = 0;
        // pos == size はファイル末尾（読み出し不能な1つ先の位置）を指す正当な境界なので判定不要
        while (pos > 0 && pos < size && guard < 3 && (byteAt(pos) & 0xC0) == 0x80) {
            pos--;
            guard++;
        }
        return pos;
    }

    /**
     * [startByte, endByte) をUTF-8としてデコードして返す。境界がマルチバイト文字の途中でも
     * 壊れないよう、開始・終了とも {@link #safeBoundaryAtOrBefore} で安全な位置へスナップしてから
     * デコードする（呼び出し側はおおよそのバイト範囲を渡すだけでよい）。
     *
     * <p>デコード対象は要求された範囲のバイト数だけであり、ファイル全体を毎回デコードすることは
     * ない——これが「必要な範囲だけ読み込む」という要件2の中核。
     */
    public String decode(long startByte, long endByte) {
        long safeStart = safeBoundaryAtOrBefore(startByte);
        long safeEnd = safeBoundaryAtOrBefore(endByte);
        if (safeEnd < safeStart) safeEnd = safeStart;
        byte[] bytes = copyRange(safeStart, safeEnd);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            // REPLACE指定のため通常到達しないが、フォールバックとして空文字を返す
            return "";
        }
    }

    /**
     * バイナリ判定用。ファイル全体を読まず先頭 {@code prefixLen} バイトだけを返す
     * （GB級ファイルでバイナリ判定のためだけに全体を読むのは無駄なコストのため）。
     */
    public byte[] readPrefix(int prefixLen) {
        int len = (int) Math.min(prefixLen, size);
        return copyRange(0, len);
    }

    /**
     * ファイルディスクリプタは構築時点で既に解放済みのため、ここではチャンク参照を手放すだけ。
     * {@code AutoCloseable}を実装しているのはtry-with-resourcesでの利用や将来の拡張のためであり、
     * 呼び忘れてもfdリークにはならない。
     */
    @Override
    public void close() {
        chunks.clear();
    }
}
