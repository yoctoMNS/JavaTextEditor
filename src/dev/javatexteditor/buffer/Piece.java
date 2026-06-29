package dev.javatexteditor.buffer;

public record Piece(Source source, int start, int length) {
    public enum Source { ORIGINAL, ADD }
}
