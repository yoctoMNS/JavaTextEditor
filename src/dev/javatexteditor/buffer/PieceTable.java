package dev.javatexteditor.buffer;

import java.util.ArrayList;
import java.util.List;

public class PieceTable {
    private final String original;
    private final StringBuilder addBuffer;
    private final List<Piece> pieces;
    // mmapで開いた大容量ファイル用（軽量化リファクタリング Phase 3: 大容量ファイル対応）。
    // 小〜中規模ファイル（従来の PieceTable(String) コンストラクタ）では両方 null のまま。
    private final MappedFileSource mappedSource;
    private final LazyLineIndex mappedLineIndex;
    // length() のキャッシュ。以前は呼ばれるたびに全ピースを stream().sum() しており
    // ピース数に比例するコストがかかっていた（軽量化リファクタリング Phase 1）。
    // insert()/delete()/restorePieces() だけが更新する。
    private int totalLength;

    public PieceTable(String originalText) {
        this.original = originalText;
        this.addBuffer = new StringBuilder();
        this.pieces = new ArrayList<>();
        this.mappedSource = null;
        this.mappedLineIndex = null;
        if (!originalText.isEmpty()) {
            pieces.add(new Piece(Piece.Source.ORIGINAL, 0, originalText.length()));
        }
        this.totalLength = originalText.length();
    }

    /**
     * 大容量ファイルをmmap経由で開くためのコンストラクタ。{@code Files.readAllBytes}や
     * ファイル全体の{@code String}化を一切行わない（要件2）。
     *
     * <p>ピースの座標系（{@code start}/{@code length}）は、元ファイル全体をデコードした場合に
     * 得られる「仮想的な文字列」上の文字(UTF-16コードユニット)オフセットとして定義する——
     * {@code ORIGINAL}（Stringコンストラクタ）ソースの座標系と全く同じ意味である。これにより
     * {@link #insert}/{@link #delete}のピース分割ロジックは一切変更せずに再利用できる
     * （分割は座標の加減算だけで完結し、実際のデコードを伴わないため）。実際に文字を読み出す
     * {@link #getText}/{@link #getTextInRange}/{@link #offsetOfLine}だけが、必要な範囲に限って
     * {@link LazyLineIndex}経由でバイトオフセットへ変換し{@link MappedFileSource#decode}する。
     *
     * <p>ピースの{@code start}/{@code length}は{@code int}のため、このコンストラクタで開ける
     * ファイルは実質{@code Integer.MAX_VALUE}文字（UTF-8で概ね2GiB強）までに制限される
     * （既存のカーソル・オフセットAPIがエディタ全体で{@code int}前提のため。詳細は
     * {@code .claude/skills/editor-buffer-architecture/SKILL.md}参照）。
     *
     * <p><b>既知のトレードオフ</b>: 最初の1ピースの{@code length}（文字数）を確定させるため、
     * このコンストラクタ内で{@code mappedLineIndex.totalCharCount()}を呼び、ファイル全体を
     * 1回だけ走査する。これは「行オフセットの事前構築はしない」という要件4への一見した違反に
     * 見えるが、この走査は（a）{@code String}や{@code char[]}へのデコード・確保を一切伴わない
     * バイト単位の分類カウントのみであり、（b）その副作用として{@link LazyLineIndex}の
     * チェックポイントが同時に埋まるため以後の行アクセスがすべて高速化される、という2点で
     * 旧実装（{@code Files.readAllBytes}＋{@code new String(...)}によるO(n)のコピー2回＋
     * それを永続的にヒープへ保持し続ける方式）とは質的に異なる。真の「開いた瞬間は完全に
     * ゼロコスト」を実現するには、ピースの文字長を遅延確定できるよう{@code Piece}を可変にするか
     * カーソル/オフセットAPI全体をバイト単位へ作り替える必要があり、既存コードへの影響が
     * 過大なため本実装のスコープ外とした（詳細は{@code .claude/skills/editor-buffer-architecture/
     * SKILL.md}参照）。
     */
    public PieceTable(MappedFileSource mappedSource) {
        this.original = "";
        this.addBuffer = new StringBuilder();
        this.pieces = new ArrayList<>();
        this.mappedSource = mappedSource;
        // 小規模ファイル経路（Files.readAllBytes + new String(...)）はBOM(EF BB BF)を除去して
        // 読み込むため、mmap経路でも同じ挙動にしないと「同じ内容のファイルなのにサイズだけで
        // 見た目が変わる」ことになる。BOMは文書のどの行にも属さない先頭3バイトなので、
        // LazyLineIndexの文字カウントの起点をBOM分だけ後ろにずらすだけで対応できる
        // （全文コピーは不要）。
        long bomBytes = hasUtf8Bom(mappedSource) ? 3 : 0;
        this.mappedLineIndex = new LazyLineIndex(mappedSource, bomBytes);
        long charCount = mappedLineIndex.totalCharCount();
        if (charCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "mmapで開けるファイルは " + Integer.MAX_VALUE + " 文字までです（既存オフセットAPIがint前提のため）。"
                    + "実際の文字数: " + charCount);
        }
        if (charCount > 0) {
            pieces.add(new Piece(Piece.Source.MAPPED, 0, (int) charCount));
        }
        this.totalLength = (int) charCount;
    }

    private static boolean hasUtf8Bom(MappedFileSource src) {
        return src.size() >= 3
                && src.byteAt(0) == 0xEF
                && src.byteAt(1) == 0xBB
                && src.byteAt(2) == 0xBF;
    }

    public void insert(int offset, String text) {
        if (text.isEmpty()) return;
        int addStart = addBuffer.length();
        addBuffer.append(text);
        totalLength += text.length();

        int runningOffset = 0;
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            int pieceEnd = runningOffset + p.length();
            if (offset < pieceEnd) {
                // ピース内部への挿入: p を分割して新ピースを挟む
                int splitPoint = offset - runningOffset;
                pieces.remove(i);
                int insertAt = i;
                if (splitPoint > 0) {
                    pieces.add(insertAt++, new Piece(p.source(), p.start(), splitPoint));
                }
                pieces.add(insertAt++, new Piece(Piece.Source.ADD, addStart, text.length()));
                // offset < pieceEnd により splitPoint < p.length() が保証されるため後半は常に非空
                pieces.add(insertAt, new Piece(p.source(), p.start() + splitPoint, p.length() - splitPoint));
                return;
            }
            if (offset == pieceEnd) {
                // ピース境界（p の直後）への挿入。
                // p が追加バッファの末尾（今回 append する直前の終端 addStart）をちょうど指している
                // 場合は、新ピースを作らず p を伸長する（連続タイピングの結合）。
                // この結合が無いと1キー入力ごとにピースが1個ずつ増え続け、insert/getText が
                // 編集回数に比例して遅くなる（セッション累計で O(K^2)）。
                // 条件を「addBuffer 末尾の所有者」に限定しているのは、削除等でピース末尾と
                // addBuffer 末尾がズレた後に誤って結合し、削除済みの文字が復活するのを防ぐため。
                if (p.source() == Piece.Source.ADD && p.start() + p.length() == addStart) {
                    pieces.set(i, new Piece(Piece.Source.ADD, p.start(), p.length() + text.length()));
                } else {
                    pieces.add(i + 1, new Piece(Piece.Source.ADD, addStart, text.length()));
                }
                return;
            }
            runningOffset = pieceEnd;
        }
        // 空文書（pieces が空）への挿入、または文書末尾を超えるオフセット（従来仕様どおり末尾扱い）
        pieces.add(new Piece(Piece.Source.ADD, addStart, text.length()));
    }

    public void delete(int offset, int length) {
        if (length <= 0) return;
        int deleteEnd = offset + length;
        List<Piece> result = new ArrayList<>();
        int runningOffset = 0;
        int removed = 0;

        for (Piece p : pieces) {
            int pieceStart = runningOffset;
            int pieceEnd = runningOffset + p.length();
            runningOffset = pieceEnd;

            boolean noOverlap = (pieceEnd <= offset) || (pieceStart >= deleteEnd);
            if (noOverlap) {
                appendMerged(result, p);
                continue;
            }
            int keepBeforeLen = Math.max(0, offset - pieceStart);
            int keepAfterStart = Math.max(pieceStart, deleteEnd);
            int keepAfterLen = pieceEnd - keepAfterStart;

            if (keepBeforeLen > 0) {
                appendMerged(result, new Piece(p.source(), p.start(), keepBeforeLen));
            }
            if (keepAfterLen > 0) {
                appendMerged(result, new Piece(p.source(), p.start() + (keepAfterStart - pieceStart), keepAfterLen));
            }
            removed += p.length() - keepBeforeLen - Math.max(0, keepAfterLen);
        }
        pieces.clear();
        pieces.addAll(result);
        totalLength -= removed;
    }

    /**
     * pieceをresultの末尾に追加する。末尾の既存ピースと結合可能(同一ソース・オフセットが
     * 連続)なら1本のピースへ伸長し、そうでなければそのまま追加する。
     *
     * <p>insert()側の結合(117-127行目付近。既存ピースをaddBuffer末尾へ伸長する処理)とは
     * 目的が異なるため実装は共有していない——insert側は「まだ確定していないaddBuffer末尾へ
     * の追記」を扱うのに対し、こちらは「既に確定した2つのPieceが実は連続範囲を指している」
     * ことを検出して1本にまとめるだけ。ただし「同一ソース・オフセット連続」という判定条件
     * 自体(piecesAreAdjacent)は両者で意味が同じなので共通化している。
     *
     * <p>delete()にこの結合が無いと、挿入と削除を繰り返す典型的な編集(打っては消す)のたびに
     * ピースが分裂したまま蓄積し、undoスナップショット(pieces全体のList.copyOf)のコストが
     * 編集回数とともに増え続けてしまう(軽量性リファクタリング Phase 4)。
     */
    private static void appendMerged(List<Piece> result, Piece piece) {
        if (!result.isEmpty()) {
            Piece last = result.get(result.size() - 1);
            if (piecesAreAdjacent(last, piece)) {
                result.set(result.size() - 1, new Piece(last.source(), last.start(), last.length() + piece.length()));
                return;
            }
        }
        result.add(piece);
    }

    /** aの直後にbが連続しているか(同一ソース・a.start()+a.length()==b.start())。 */
    private static boolean piecesAreAdjacent(Piece a, Piece b) {
        return a.source() == b.source() && a.start() + a.length() == b.start();
    }

    public int length() {
        return totalLength;
    }

    public String getText() {
        StringBuilder result = new StringBuilder(totalLength);
        for (Piece p : pieces) {
            appendPieceRange(result, p, 0, p.length());
        }
        return result.toString();
    }

    /**
     * 文書全体ではなく指定オフセット範囲だけを返す。
     * 画面に表示する数十行分だけを取り出すことで getText() の全文字列構築コストを避けられる。
     * MAPPEDピースについても、要求された範囲ぶんだけを{@link MappedFileSource#decode}するため、
     * ピース自体がファイル全体をカバーする巨大なものであってもコストは要求範囲に収まる
     * （軽量化リファクタリング Phase 3・要件5「ビューポートのみ描画」の土台）。
     */
    public String getTextInRange(int startOffset, int endOffset) {
        StringBuilder result = new StringBuilder(Math.max(0, endOffset - startOffset));
        int runningOffset = 0;
        for (Piece p : pieces) {
            int pieceEnd = runningOffset + p.length();
            if (pieceEnd > startOffset && runningOffset < endOffset) {
                int from = Math.max(0, startOffset - runningOffset);
                int to = Math.min(p.length(), endOffset - runningOffset);
                appendPieceRange(result, p, from, to);
            }
            runningOffset = pieceEnd;
            if (runningOffset >= endOffset) break;
        }
        return result.toString();
    }

    /**
     * ピース p のうち、ピース先頭からの相対文字位置 [fromInPiece, toInPiece) の部分だけを
     * result に追記する。ORIGINAL/ADD は既存どおり CharSequence の範囲 append（コピー無しに近い）。
     * MAPPED は {@link LazyLineIndex} で文字オフセットをバイトオフセットへ変換したうえで
     * {@link MappedFileSource#decode} する——ここが「必要な範囲だけデコードする」の実体。
     */
    private void appendPieceRange(StringBuilder result, Piece p, int fromInPiece, int toInPiece) {
        switch (p.source()) {
            case ORIGINAL -> result.append(original, p.start() + fromInPiece, p.start() + toInPiece);
            // addBuffer.toString() を使わず CharSequence として範囲 append する。
            // 以前は ADD ピースごとに追加バッファ全体を String へコピーしており、
            // 長い編集セッション後の getText() が「ADDピース数×追加バッファ長」の
            // 無駄なアロケーションを発生させていた（軽量化リファクタリング Phase 1）。
            case ADD -> result.append(addBuffer, p.start() + fromInPiece, p.start() + toInPiece);
            case MAPPED -> {
                long byteStart = mappedLineIndex.byteOffsetOfCharOffset(p.start() + fromInPiece);
                long byteEnd = mappedLineIndex.byteOffsetOfCharOffset(p.start() + toInPiece);
                String decoded = mappedSource.decode(byteStart, byteEnd);
                // 小規模ファイル経路が開いた時点で行う "\r\n"→"\n" 正規化と揃えるための後処理。
                // LazyLineIndexの文字カウントは既にCRLFの\rを0幅として扱っているため
                // （byteOffsetOfCharOffsetが返す境界は\rと\nの間で割れることが無い）、
                // ここで単純に置換しても要求文字数(toInPiece-fromInPiece)と結果の文字列長は一致する。
                if (decoded.indexOf('\r') >= 0) {
                    decoded = decoded.replace("\r\n", "\n");
                }
                result.append(decoded);
            }
        }
    }

    /**
     * N行目が何文字目（0-based オフセット）から始まるかを返す。
     * ピースを直接走査するため getText() による全文再構築・アロケーションを伴わない
     * （軽量化リファクタリング Phase 1。従来は毎回全文 String を構築していた）。
     */
    public int offsetOfLine(int lineNumber) {
        if (lineNumber == 0) return 0;
        int currentLine = 0;
        int runningOffset = 0;
        for (Piece p : pieces) {
            if (p.source() == Piece.Source.MAPPED) {
                // MAPPEDピースの中身をデコードして '\n' を数える代わりに、元ファイルの行構造を
                // そのまま流用する。MAPPEDピースは「編集で一切触れられていない元ファイルの
                // 連続範囲」なので、その中の改行位置は元ファイルの行境界と完全に一致する
                // （lineAtCharOffsetの往復だけで済み、ピースの中身を1バイトもデコードしない）。
                long startFileLine = mappedLineIndex.lineAtCharOffset(p.start());
                long endFileLine = mappedLineIndex.lineAtCharOffset(p.start() + p.length());
                long newlinesInPiece = endFileLine - startFileLine;
                if (currentLine + newlinesInPiece >= lineNumber) {
                    long targetFileLine = startFileLine + (lineNumber - currentLine);
                    long targetCharInFile = mappedLineIndex.charOffsetOfLine((int) targetFileLine);
                    return runningOffset + (int) (targetCharInFile - p.start());
                }
                currentLine += newlinesInPiece;
                runningOffset += p.length();
                continue;
            }
            CharSequence src = (p.source() == Piece.Source.ORIGINAL) ? original : addBuffer;
            int end = p.start() + p.length();
            for (int i = p.start(); i < end; i++) {
                if (src.charAt(i) == '\n') {
                    currentLine++;
                    if (currentLine == lineNumber) {
                        return runningOffset + (i - p.start()) + 1;
                    }
                }
            }
            runningOffset += p.length();
        }
        return totalLength;
    }

    /**
     * このバッファがmmapで開かれた大容量ファイルかどうか。ModalEditor側で「行数表示に
     * 走査コストがかかり得る」ことをステータス行等に反映する場合の判定に使う。
     */
    public boolean isMappedFile() {
        return mappedSource != null;
    }

    protected List<Piece> getPieces() {
        return List.copyOf(pieces);
    }

    protected void restorePieces(List<Piece> snapshot) {
        pieces.clear();
        pieces.addAll(snapshot);
        // undo/redo でピースリストが丸ごと差し替わるため、キャッシュを再集計する。
        // スナップショットのピース数は結合により小さく保たれるので O(P) でも実質定数。
        int sum = 0;
        for (Piece p : snapshot) sum += p.length();
        totalLength = sum;
    }
}
