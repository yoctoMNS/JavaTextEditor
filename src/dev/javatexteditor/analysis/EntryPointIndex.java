package dev.javatexteditor.analysis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ":main <target>" コマンドが認識するコマンド名（java/javac 等）と、
 * その「実際の起動点（launcher entry point）」の対応表。
 *
 * java の実際の起動点は launcher バイナリの native main()（JLI_Launch を呼び出す）であり、
 * HotSpot 本体（JVM_GC 等の実行時関数）ではない。javac は launcher からそのまま
 * com.sun.tools.javac.Main.main(String[]) が呼ばれる（javac 専用の JNI/native 実装は無い）。
 * 新しいターゲット（jar/javadoc/jshell 等）はこの Map にエントリを追加するだけで拡張できる。
 */
public final class EntryPointIndex {

    /** ジャンプ先の種別。native launcher のC関数か、JDK内のJavaソースか。 */
    public sealed interface Target {
        /** native ソース (lib/openjdk-native/ からの相対パス) 上のシンボル定義へジャンプする。 */
        record NativeLauncher(String relativePath, String symbol) implements Target {}

        /** src.zip 内の Java ソース（モジュール名 + 完全修飾クラス名）のメンバーへジャンプする。 */
        record JavaSource(String moduleName, String fqcn, String memberName) implements Target {}
    }

    private static final Map<String, Target> TARGETS = new LinkedHashMap<>();
    static {
        TARGETS.put("java", new Target.NativeLauncher(
            "java.base/share/native/launcher/main.c", "main"));
        TARGETS.put("javac", new Target.JavaSource(
            "jdk.compiler", "com.sun.tools.javac.Main", "main"));
    }

    private EntryPointIndex() {}

    /** 大文字小文字を無視してターゲットを検索する。 */
    public static Optional<Target> lookup(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(TARGETS.get(name.trim().toLowerCase()));
    }

    /** サポートしているターゲット名一覧（エラーメッセージ・ヘルプ表示用）。 */
    public static Set<String> supportedTargets() {
        return TARGETS.keySet();
    }
}
