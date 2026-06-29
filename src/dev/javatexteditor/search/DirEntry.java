package dev.javatexteditor.search;

import java.nio.file.Path;

public record DirEntry(String name, Path path, Kind kind) {
    public enum Kind { DIRECTORY, FILE }
}
