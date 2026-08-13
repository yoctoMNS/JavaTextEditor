package dev.javatexteditor.format;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class JavaAutoFormatGuardTest {
    public static void main(String[] args) throws IOException {
        int pass = 0;
        int total = 0;

        Path tmpDir = Files.createTempDirectory("auto-format-guard-test");

        // Test 1: 通常のプロジェクト内.javaファイルは対象
        {
            Path p = tmpDir.resolve("Foo.java");
            total++;
            pass += check("通常の.javaファイルは対象",
                JavaAutoFormatGuard.isEligible(p, p.toString(), false));
        }

        // Test 2: .java以外の拡張子は対象外
        {
            Path p = tmpDir.resolve("foo.c");
            total++;
            pass += check(".c拡張子は対象外",
                !JavaAutoFormatGuard.isEligible(p, p.toString(), false));
        }

        // Test 3: currentFilePathがnull（無名バッファ）は対象外
        {
            Path p = tmpDir.resolve("Foo.java");
            total++;
            pass += check("currentFilePath=nullは対象外",
                !JavaAutoFormatGuard.isEligible(p, null, false));
        }

        // Test 4: 疑似バッファ（*compile*等）は対象外
        {
            Path p = tmpDir.resolve("Foo.java");
            total++;
            pass += check("疑似バッファは対象外",
                !JavaAutoFormatGuard.isEligible(p, "*compile*", false));
        }

        // Test 5: jdk-source疑似バッファ（Shift+Kのジャンプ先）は対象外
        {
            Path p = tmpDir.resolve("Foo.java");
            total++;
            pass += check("inJdkSourceBuffer=trueは対象外",
                !JavaAutoFormatGuard.isEligible(p, "*jdk-source:java.util.ArrayList*", true));
        }

        // Test 6: パスに src.zip を含む場合は対象外
        {
            Path p = Path.of("/some/lib/src.zip/java.base/java/util/ArrayList.java");
            total++;
            pass += check("src.zip配下は対象外",
                !JavaAutoFormatGuard.isEligible(p, p.toString(), false));
        }

        // Test 7: パスに openjdk-native を含む場合は対象外（拡張子で既に弾かれるが二重チェック）
        {
            Path p = Path.of("/proj/lib/openjdk-native/hotspot/share/foo.java");
            total++;
            pass += check("openjdk-native配下は対象外",
                !JavaAutoFormatGuard.isEligible(p, p.toString(), false));
        }

        // Test 8: java.home配下は対象外
        {
            String javaHome = System.getProperty("java.home");
            Path p = Path.of(javaHome, "Fake.java");
            total++;
            pass += check("java.home配下は対象外",
                !JavaAutoFormatGuard.isEligible(p, p.toString(), false));
        }

        // Test 9: 書き込み不可の既存ファイルは対象外
        {
            Path p = tmpDir.resolve("ReadOnly.java");
            Files.writeString(p, "class ReadOnly {}");
            boolean setReadOnly = p.toFile().setReadOnly();
            total++;
            if (setReadOnly && !Files.isWritable(p)) {
                pass += check("書き込み不可ファイルは対象外",
                    !JavaAutoFormatGuard.isEligible(p, p.toString(), false));
            } else {
                // rootで実行されている等、読み取り専用化できない環境ではスキップ扱い
                pass += check("書き込み不可ファイルは対象外（環境上スキップ）", true);
            }
            p.toFile().setWritable(true);
        }

        // Test 10: 拡張子が大文字混じりでも判定される（.JAVA）
        {
            Path p = tmpDir.resolve("Foo.JAVA");
            total++;
            pass += check("拡張子の大文字小文字を無視",
                JavaAutoFormatGuard.isEligible(p, p.toString(), false));
        }

        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + (total - pass) + ")");
        if (pass != total) {
            System.exit(1);
        }
    }

    static int check(String name, boolean ok) {
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name);
        return ok ? 1 : 0;
    }
}
