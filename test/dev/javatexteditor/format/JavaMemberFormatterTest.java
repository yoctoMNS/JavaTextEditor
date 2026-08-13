package dev.javatexteditor.format;

public class JavaMemberFormatterTest {
    public static void main(String[] args) {
        int pass = 0;
        int total = 0;

        // Test 1: static field -> instance field -> constructor -> method の順に並び替わる
        {
            String src = """
                package p;
                public class A {
                    private void foo() {}
                    public A() {}
                    private int instanceField;
                    public static int staticField;
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("フィールド/コンストラクタ/メソッドの並び替え",
                out != null
                    && out.indexOf("staticField") < out.indexOf("instanceField")
                    && out.indexOf("instanceField") < out.indexOf("public A()")
                    && out.indexOf("public A()") < out.indexOf("foo"));
        }

        // Test 1b: 「開き{ 〜 元々の先頭メンバー」の隙間は、その先頭メンバーが並び替えで
        // 先頭でなくなった場合でも、そのメンバー自身に追従する（回帰テスト:
        // header/headGap分離前は前方に固定した隙間が二重化し "s;void bar()" のように
        // 改行が消失する不具合があった）。
        {
            String src = """
                package p;
                class Sample {
                    void bar() {}
                    public static int s;
                    private void foo() {}
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("先頭メンバーが移動しても隙間が正しく追従する",
                out != null
                    && out.contains("class Sample {\n    public static int s;\n    void bar() {}")
                    && !out.contains("s;void bar()"));
        }

        // Test 2: フィールドの可視性順（public -> protected -> package -> private）
        {
            String src = """
                package p;
                class A {
                    private static int a;
                    public static int b;
                    protected static int c;
                    static int d;
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("フィールド可視性順",
                out != null
                    && out.indexOf(" b;") < out.indexOf(" c;")
                    && out.indexOf(" c;") < out.indexOf(" d;")
                    && out.indexOf(" d;") < out.indexOf(" a;"));
        }

        // Test 3: コンストラクタは引数なし -> 引数ありの順
        {
            String src = """
                package p;
                class A {
                    public A(int x) {}
                    public A() {}
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("コンストラクタは引数なし優先",
                out != null && out.indexOf("A()") < out.indexOf("A(int x)"));
        }

        // Test 4: オーバーロードメソッドは連続配置される
        {
            String src = """
                package p;
                class A {
                    void bar() {}
                    void foo() {}
                    void foo(int x) {}
                    void baz() {}
                    void foo(String s) {}
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            boolean ok = out != null;
            if (ok) {
                int fooStart = out.indexOf("void foo()");
                int fooInt = out.indexOf("void foo(int x)");
                int fooStr = out.indexOf("void foo(String s)");
                int bar = out.indexOf("void bar()");
                // foo系3つが連続（barに割り込まれない）
                ok = fooStart >= 0 && fooInt > fooStart && fooStr > fooInt
                    && (bar < fooStart || bar > fooStr);
            }
            pass += check("オーバーロードの連続配置", ok);
        }

        // Test 5: equals/hashCode/toString はメソッド群の最下部にまとまる
        {
            String src = """
                package p;
                class A {
                    @Override
                    public String toString() { return "A"; }
                    void normalMethod() {}
                    @Override
                    public boolean equals(Object o) { return false; }
                    @Override
                    public int hashCode() { return 0; }
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("Object override は最下部",
                out != null
                    && out.indexOf("normalMethod") < out.indexOf("equals")
                    && out.indexOf("equals") < out.indexOf("hashCode")
                    && out.indexOf("hashCode") < out.indexOf("toString"));
        }

        // Test 6: static初期化ブロック -> instance初期化ブロックの順
        {
            String src = """
                package p;
                class A {
                    { System.out.println("instance"); }
                    static { System.out.println("static"); }
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("static初期化ブロック優先",
                out != null && out.indexOf("\"static\"") < out.indexOf("\"instance\""));
        }

        // Test 7: Javadoc/コメントは、オーバーロードのクラスタリングでメンバーが実際に移動する際も追従する
        {
            String src = """
                package p;
                class A {
                    void bar() {}
                    /** これは foo(int) の説明 */
                    void foo(int x) {}
                    void baz() {}
                    void foo() {}
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("Javadocがメンバーに追従",
                out != null && out.contains("これは foo(int) の説明")
                    && out.indexOf("void foo()") < out.indexOf("これは foo(int) の説明")
                    && out.indexOf("これは foo(int) の説明") < out.indexOf("void foo(int x)"));
        }

        // Test 8: 同一行末コメントは元のメンバーに残る（次のメンバーへ付け替わらない）
        {
            String src = """
                package p;
                class A {
                    public static int b; // bのコメント
                    private static int a;
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            // b は元々並び替え不要な位置（public->private）なので変化なしのはず。コメントがaの前に来ないことを確認。
            pass += check("同一行末コメントの保持",
                out == null || out.indexOf("bのコメント") < out.indexOf("private static int a"));
        }

        // Test 9: 文字列リテラル中の波括弧を誤解釈せず、実際の並び替え（フィールドをメソッドより前へ）でも保持する
        {
            String src = """
                package p;
                class A {
                    public void foo() { System.out.println("{}"); }
                    private String weird = "{ this looks like a brace }";
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("文字列内の波括弧を保持",
                out != null && out.contains("\"{ this looks like a brace }\"")
                    && out.contains("System.out.println(\"{}\");")
                    && out.indexOf("weird") < out.indexOf("void foo()"));
        }

        // Test 10: ネストされたクラスにも再帰的に適用される
        {
            String src = """
                package p;
                class Outer {
                    static class Inner {
                        void b() {}
                        public Inner() {}
                        private int y;
                        public static int x;
                    }
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("ネストされた型への再帰適用",
                out != null
                    && out.indexOf("public static int x") < out.indexOf("private int y")
                    && out.indexOf("private int y") < out.indexOf("public Inner()")
                    && out.indexOf("public Inner()") < out.indexOf("void b()"));
        }

        // Test 11: enum定数は絶対に相互の順序を変えない
        {
            String src = """
                package p;
                enum Color {
                    BLUE, RED, GREEN;
                    public static int counter;
                    private final int code = 0;
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            boolean ok = true;
            if (out != null) {
                ok = out.indexOf("BLUE") < out.indexOf("RED") && out.indexOf("RED") < out.indexOf("GREEN");
            }
            pass += check("enum定数の順序保持", ok);
        }

        // Test 12: enum定数はフィールドより必ず前
        {
            String src = """
                package p;
                enum Color {
                    RED, GREEN, BLUE;
                    private int code;
                    public static int counter;
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("enum定数がフィールドより前",
                out != null
                    && out.indexOf("BLUE") < out.indexOf("counter")
                    && out.indexOf("counter") < out.indexOf("code"));
        }

        // Test 13: interfaceは 定数 -> 抽象メソッド -> defaultメソッド -> staticメソッドの順
        {
            String src = """
                package p;
                interface I {
                    default void d() {}
                    static void s() {}
                    void abstractMethod();
                    int CONST = 1;
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("interfaceのメンバー順",
                out != null
                    && out.indexOf("CONST") < out.indexOf("abstractMethod")
                    && out.indexOf("abstractMethod") < out.indexOf("d()")
                    && out.indexOf("d()") < out.indexOf("s()"));
        }

        // Test 14: recordのcompactコンストラクタが先頭に来る
        {
            String src = """
                package p;
                record Point(int x, int y) {
                    public Point(int x) {
                        this(x, 0);
                    }
                    public Point {
                        if (x < 0) throw new IllegalArgumentException();
                    }
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("recordのcompactコンストラクタが先頭",
                out != null
                    && out.indexOf("public Point {") < out.indexOf("public Point(int x)"));
        }

        // Test 14b: record header の成分（record component）は本文メンバーとして誤検出・重複・
        // 破壊されない（回帰テスト: Compiler Tree API の ct.getMembers() は record component を
        // 暗黙のVariableTreeとして含み、その位置はヘッダー側=開き{より前を指すため、フィルタせずに
        // 扱うと開き{の探索やprefix/suffixの計算全体が壊れる不具合があった）。
        {
            String src = """
                package p;
                record Point(int x, int y) {
                    void foo() {}
                    public static int counter;
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            boolean ok = out != null
                && out.contains("record Point(int x, int y) {")
                && out.indexOf("counter") < out.indexOf("void foo()");
            if (ok) {
                // ヘッダーの x, y がボディ側に複製されていないこと（出現回数は元のヘッダー1回のみ）
                ok = countOccurrences(out, "int x") == 1 && countOccurrences(out, "int y") == 1;
            }
            pass += check("recordコンポーネントは本文メンバーとして扱われない", ok);
        }

        // Test 15: JDK標準パッケージ宣言のソースは一切変更しない
        {
            String src = """
                package java.util;
                public class Fake {
                    private void b() {}
                    public static int a;
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("java.*パッケージは対象外", out == null);
        }

        // Test 16: 構文エラーがあるソースは一切変更しない
        {
            String src = """
                package p;
                class A {
                    void foo( {
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("構文エラーのソースは対象外", out == null);
        }

        // Test 17: 既に規約順のソースは変更なし（null）
        {
            String src = """
                package p;
                class A {
                    public static int s;
                    private int i;
                    public A() {}
                    void foo() {}
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("既に規約順なら無変更", out == null);
        }

        // Test 18: 冪等性（1回並び替えた結果を再度並び替えても変化しない）
        {
            String src = """
                package p;
                class A {
                    private void foo() {}
                    public A() {}
                    private int i;
                    public static int s;
                }
                """;
            String once = JavaMemberFormatter.format(src);
            total++;
            boolean ok = once != null;
            if (ok) {
                String twice = JavaMemberFormatter.format(once);
                ok = twice == null; // 2回目は無変更のはず
            }
            pass += check("冪等性", ok);
        }

        // Test 19: アノテーションは、Object override が最下部へ実際に移動する際もメソッド本体と一緒に移動する
        {
            String src = """
                package p;
                class A {
                    @Override
                    @SuppressWarnings("unchecked")
                    public String toString() { return "A"; }
                    void zzz() {}
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("複数アノテーションの保持",
                out != null
                    && out.contains("@Override")
                    && out.contains("@SuppressWarnings(\"unchecked\")")
                    && out.indexOf("void zzz()") < out.indexOf("@Override")
                    && out.indexOf("@Override") < out.indexOf("@SuppressWarnings(\"unchecked\")")
                    && out.indexOf("@SuppressWarnings(\"unchecked\")") < out.indexOf("public String toString()"));
        }

        // Test 20: パッケージ宣言・import・トップレベルのgapは一切変更しない
        {
            String src = """
                package p;

                import java.util.List;

                // header comment
                class A {
                    private void foo() {}
                    public static int s;
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("ヘッダー部分の非破壊",
                out != null
                    && out.startsWith("package p;\n\nimport java.util.List;\n\n// header comment\nclass A {"));
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

    static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
