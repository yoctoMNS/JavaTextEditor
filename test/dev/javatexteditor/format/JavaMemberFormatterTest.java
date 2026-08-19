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

        // Test 1b: 先頭メンバーが並び替えで先頭でなくなっても隙間が正しく追従する（回帰テスト）
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
                    && out.contains("class Sample {\n    public static int s;\n")
                    && !out.contains("s;void") && !out.contains("s;bar") && !out.contains("s;foo"));
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

        // Test 4: 同名メソッド（オーバーロード）は連続配置される
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
                ok = fooStart >= 0 && fooInt > fooStart && fooStr > fooInt
                    && (bar < fooStart || bar > fooStr);
            }
            pass += check("オーバーロードの連続配置", ok);
        }

        // Test 5: equals/hashCode/toString/clone は無条件でメソッド群の最後
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
            pass += check("equals/hashCode/toStringは最後（アルファベット順）",
                out != null
                    && out.indexOf("normalMethod") < out.indexOf("public boolean equals")
                    && out.indexOf("public boolean equals") < out.indexOf("public int hashCode")
                    && out.indexOf("public int hashCode") < out.indexOf("public String toString"));
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

        // Test 7: Javadoc/コメントは、実際に移動する場合もメンバーに追従する
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
            pass += check("同一行末コメントの保持",
                out == null || out.indexOf("bのコメント") < out.indexOf("private static int a"));
        }

        // Test 9: 文字列リテラル中の波括弧・配列初期化子の波括弧は誤解釈されない
        {
            String src = """
                package p;
                class A {
                    public void foo() { System.out.println("{}"); }
                    private String weird = "{ this looks like a brace }";
                    private int[] arr = {1, 2, 3};
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("文字列/配列初期化子の波括弧を保持",
                out != null
                    && out.contains("\"{ this looks like a brace }\"")
                    && out.contains("System.out.println(\"{}\");")
                    && out.contains("private int[] arr = {1, 2, 3};")
                    && out.indexOf("weird") < out.indexOf("void foo()")
                    && out.indexOf("arr") < out.indexOf("void foo()"));
        }

        // Test 10: ネストされた型にも再帰的に適用される
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

        // Test 13: interfaceの定数は暗黙のpublic static扱いになる（明示staticが無くても先頭に来る）
        {
            String src = """
                package p;
                interface I {
                    void abstractMethod();
                    int CONST = 1;
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("interfaceの暗黙定数が先頭",
                out != null && out.indexOf("CONST") < out.indexOf("abstractMethod"));
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

        // Test 14b: recordのheader component（x, y）は本文メンバーとして誤検出・重複されない
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
            if (ok) ok = countOccurrences(out, "int x") == 1 && countOccurrences(out, "int y") == 1;
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

        // Test 16: 既に規約順のソースは変更なし（null）
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

        // Test 17: 冪等性（1回並び替えた結果を再度並び替えても変化しない）
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
                ok = twice == null;
            }
            pass += check("冪等性", ok);
        }

        // Test 18: アノテーションは、equalsが最下部へ実際に移動する際もメソッド本体と一緒に移動する
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

        // Test 19: パッケージ宣言・import・ヘッダーコメントは一切変更しない
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

        // Test 20: Step-down DFS — publicなrunがhelperを呼ぶ場合、直下にhelperが配置される
        // （helperA/helperBはどちらも呼び出されないため単独でもルートになりうるが、
        // アルファベット順で run より後ろに来る名前にして曖昧さを避ける）
        {
            String src = """
                package p;
                class A {
                    private void zzzHelper() {}
                    public void run() {
                        zzzHelper();
                    }
                    private void zzzUnused() {}
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("Step-down: 呼び出し先メソッドが呼び出し元の直下に来る",
                out != null
                    && out.indexOf("void run()") < out.indexOf("void zzzHelper()")
                    && out.indexOf("void zzzHelper()") < out.indexOf("void zzzUnused()"));
        }

        // Test 21: 呼び出し関係のない複数のpublicメソッドはルートとしてアルファベット順に並ぶ
        {
            String src = """
                package p;
                class A {
                    public void zeta() {}
                    public void alpha() {}
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("ルートのアルファベット順",
                out != null && out.indexOf("alpha()") < out.indexOf("zeta()"));
        }

        // Test 22: 複数の呼び出し先はアルファベット順にStep-downされる
        {
            String src = """
                package p;
                class A {
                    public void run() {
                        zeta();
                        alpha();
                    }
                    private void zeta() {}
                    private void alpha() {}
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("呼び出し先の複数配置はアルファベット順",
                out != null
                    && out.indexOf("void run()") < out.indexOf("void alpha()")
                    && out.indexOf("void alpha()") < out.indexOf("void zeta()"));
        }

        // Test 23〜25: 誤検出されると targetMethod が aRun の呼び出し先として検出され、
        // 「aRun, targetMethod, bMiddle」という（元のソースと同じ）順になり無変更＝nullを返してしまう
        // （＝バグを再現できない弱いテストになる）。ソース順を「aRun, targetMethod, bMiddle」にしておくと、
        // 正しく誤検出を除外できていれば targetMethod は「一度も呼ばれないメソッド」として独立した
        // ルート扱いになり、アルファベット順で bMiddle の後ろへ実際に並び替わる（非null・検証可能）。

        // Test 23: 別インスタンス経由の同名呼び出し（obj.targetMethod()）は自クラスの呼び出しと誤認識しない
        {
            String src = """
                package p;
                class A {
                    public void aRun() {
                        otherInstance.targetMethod();
                    }
                    private void targetMethod() {}
                    public void bMiddle() {}
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("別インスタンス経由の呼び出しは誤検出しない (obj.method())",
                out != null && out.indexOf("void bMiddle()") < out.indexOf("void targetMethod()"));
        }

        // Test 24: super.targetMethod() も自クラスの呼び出しと誤認識しない
        {
            String src = """
                package p;
                class A {
                    public void aRun() {
                        super.targetMethod();
                    }
                    private void targetMethod() {}
                    public void bMiddle() {}
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("super経由の呼び出しは誤検出しない (super.method())",
                out != null && out.indexOf("void bMiddle()") < out.indexOf("void targetMethod()"));
        }

        // Test 25: 同名の変数・フィールドへのアクセス（メソッド呼び出しではない）は誤検出しない
        {
            String src = """
                package p;
                class A {
                    public void aRun() {
                        int targetMethod = 0;
                    }
                    private void targetMethod() {}
                    public void bMiddle() {}
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("同名変数へのアクセスは誤検出しない (int method = 0;)",
                out != null && out.indexOf("void bMiddle()") < out.indexOf("void targetMethod()"));
        }

        // Test 26: 明示的な this.method() は正しく「呼び出し」として検出され、Step-downされる
        // （ソース順をあえて helper, run, zzzAnother にし、正しく検出されれば run の直下へ
        // 実際に並び替わることを非null・具体的な位置で検証する）
        {
            String src = """
                package p;
                class A {
                    private void helper() {}
                    public void run() {
                        this.helper();
                    }
                    public void zzzAnother() {}
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("this.method()は正しくStep-downされる",
                out != null
                    && out.indexOf("void run()") < out.indexOf("void helper()")
                    && out.indexOf("void helper()") < out.indexOf("void zzzAnother()"));
        }

        // Test 27: フィールド初期化子中の呼び出し式（new Font(...)）をメソッド宣言と誤認識しない
        // （`Font font = new Font(...)` の `new Font(` に NAME_BEFORE_PAREN がマッチし、
        // 「メソッドFont」として誤ってコンストラクタの後ろに並び替わってしまう不具合の再発防止）
        {
            String src = """
                package p;
                class A {
                    private Canvas canvas;
                    private A(String[] args) {
                        this.args = args;
                    }
                    private Font font = new Font(Font.MONOSPACED, Font.BOLD, 30);
                    public void keyPressed(int e) {
                    }
                }
                """;
            String out = JavaMemberFormatter.format(src);
            total++;
            pass += check("フィールド初期化子中のnew呼び出しをメソッドと誤認識しない",
                out != null
                    && out.indexOf("Font font") < out.indexOf("private A(")
                    && out.indexOf("Font font") < out.indexOf("void keyPressed"));
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
