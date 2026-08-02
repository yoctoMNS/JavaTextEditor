package dev.javatexteditor.buffer;

public record Piece(Source source, int start, int length) {
    /**
     * MAPPED: mmapで開いた大容量ファイルの元データを指すピース。ORIGINALと同じく
     * 「一度も編集されていない元ファイルの範囲」を表すが、実体データを{@code String}として
     * 保持せず{@link MappedFileSource}への参照だけを{@link PieceTable}が持つ点が異なる。
     * start/lengthの単位はORIGINALと同じ「文字(UTF-16コードユニット)オフセット」であり、
     * バイトオフセットではない（詳細は{@link PieceTable}のmmapコンストラクタのJavadoc参照）。
     */
    public enum Source { ORIGINAL, ADD, MAPPED }
}
