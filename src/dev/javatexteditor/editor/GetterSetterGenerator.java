package dev.javatexteditor.editor;

/** カーソル行のフィールド宣言から getter/setter のソース文字列を生成する純粋ロジック。
 *  バッファへの挿入・カーソル移動は行わない（ModalEditor 側の責務）。 */
public final class GetterSetterGenerator {
    private GetterSetterGenerator() {}

    /**
     * フィールド宣言の行を解析する。
     * 例: "    private int hp;" -> ["int", "hp"]
     * 解析失敗時は null を返す。
     */
    public static String[] parseFieldDeclaration(String line) {
        line = line.trim();
        // 末尾のセミコロンを除去
        if (!line.endsWith(";")) return null;
        line = line.substring(0, line.length() - 1).trim();
        // アクセス修飾子・static・final 等のトークンを除去
        String[] tokens = line.split("\\s+");
        // 型名とフィールド名は末尾2トークン
        if (tokens.length < 2) return null;
        String fieldName = tokens[tokens.length - 1];
        String typeName  = tokens[tokens.length - 2];
        // '=' による初期化があれば除去（例: "int x = 0"）
        int eqIdx = typeName.indexOf('=');
        if (eqIdx >= 0) return null; // 複雑な初期化式は対象外
        int fnEq = fieldName.indexOf('=');
        if (fnEq >= 0) fieldName = fieldName.substring(0, fnEq).trim();
        // 配列型（int[] や int[][]）はそのまま許容
        if (!fieldName.matches("[a-zA-Z_$][a-zA-Z0-9_$]*")) return null;
        return new String[]{typeName, fieldName};
    }

    /** ファイル内の最初のコードインデント（スペースかタブ）を検出する。 */
    public static String detectIndent(String[] lines) {
        for (String line : lines) {
            if (line.startsWith("\t")) return "\t";
            if (line.startsWith("    ")) return "    ";
            if (line.startsWith("  ")) return "  ";
        }
        return "    ";
    }

    /** getter のソース文字列を組み立てる（boolean は is、それ以外は get プレフィックス）。 */
    public static String buildGetter(String type, String name, String indent) {
        String prefix = type.equals("boolean") ? "is" : "get";
        return "\n" + indent + "public " + type + " " + prefix + capitalize(name) + "() {\n"
             + indent + indent + "return " + name + ";\n"
             + indent + "}\n";
    }

    /** setter のソース文字列を組み立てる。 */
    public static String buildSetter(String type, String name, String indent) {
        return "\n" + indent + "public void set" + capitalize(name) + "(" + type + " " + name + ") {\n"
             + indent + indent + "this." + name + " = " + name + ";\n"
             + indent + "}\n";
    }

    /** getter と setter の両方のソース文字列を組み立てる。 */
    public static String buildGetterAndSetter(String type, String name, String indent) {
        String prefix = type.equals("boolean") ? "is" : "get";
        return "\n" + indent + "public " + type + " " + prefix + capitalize(name) + "() {\n"
             + indent + indent + "return " + name + ";\n"
             + indent + "}\n"
             + "\n" + indent + "public void set" + capitalize(name) + "(" + type + " " + name + ") {\n"
             + indent + indent + "this." + name + " = " + name + ";\n"
             + indent + "}\n";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
