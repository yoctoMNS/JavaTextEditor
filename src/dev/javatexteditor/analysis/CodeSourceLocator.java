package dev.javatexteditor.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Predicate;

/** 実行中クラスの code source 位置から親ディレクトリを遡り、相対パスに一致する実在パスを探す。
 *  build/ 直接実行・jar 実行の両形態で、プロジェクト同梱の lib/ や scripts/ を発見するために使う。 */
public final class CodeSourceLocator {
    private CodeSourceLocator() {}

    /** anchor クラスの code source から maxLevels 階層まで親を遡り、
     *  dir.resolve(relative) が accept を満たす最初のパスを返す。 */
    public static Optional<Path> findUpward(Class<?> anchor, String relative,
                                            int maxLevels, Predicate<Path> accept) {
        try {
            var url = anchor.getProtectionDomain().getCodeSource().getLocation();
            if (url == null) return Optional.empty();
            Path code = Paths.get(url.toURI());
            Path dir = Files.isDirectory(code) ? code : code.getParent();
            for (int i = 0; i < maxLevels; i++) {
                if (dir == null) break;
                Path candidate = dir.resolve(relative);
                if (accept.test(candidate)) return Optional.of(candidate);
                dir = dir.getParent();
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }
}
