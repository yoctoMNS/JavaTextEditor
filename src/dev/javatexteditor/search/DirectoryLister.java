package dev.javatexteditor.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class DirectoryLister {

    private DirectoryLister() {}

    /**
     * dir 直下の一覧を返す。ディレクトリ優先、各グループ内は名前昇順（大文字小文字無視）。
     */
    public static List<DirEntry> listDirectoryEntries(Path dir) throws IOException {
        List<DirEntry> dirs = new ArrayList<>();
        List<DirEntry> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    dirs.add(new DirEntry(name, p.toAbsolutePath().normalize(), DirEntry.Kind.DIRECTORY));
                } else {
                    files.add(new DirEntry(name, p.toAbsolutePath().normalize(), DirEntry.Kind.FILE));
                }
            });
        }
        Comparator<DirEntry> byName = Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT));
        dirs.sort(byName);
        files.sort(byName);
        List<DirEntry> result = new ArrayList<>(dirs.size() + files.size());
        result.addAll(dirs);
        result.addAll(files);
        return result;
    }

    /**
     * エントリを query で case-insensitive 部分一致フィルタする。
     * query が空なら entries をそのまま返す。
     */
    public static List<DirEntry> filterEntries(List<DirEntry> entries, String query) {
        if (query.isEmpty()) return entries;
        String lc = query.toLowerCase(Locale.ROOT);
        List<DirEntry> result = new ArrayList<>();
        for (DirEntry e : entries) {
            if (e.name().toLowerCase(Locale.ROOT).contains(lc)) {
                result.add(e);
            }
        }
        return result;
    }
}
