package dev.vimacs.analysis;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * リフレクションで取得した JDK クラスの情報。
 */
public record JdkTypeInfo(
    String fqn,
    String kind,               // "class" / "interface" / "enum" / "annotation" / "record"
    List<String> methodSignatures,
    List<String> fieldNames
) {

    /** Class<?> から JdkTypeInfo を生成する。 */
    public static JdkTypeInfo from(Class<?> cls) {
        String kind = classKind(cls);
        List<String> methods = methodSignatures(cls);
        List<String> fields = fieldNames(cls);
        return new JdkTypeInfo(cls.getName(), kind, methods, fields);
    }

    private static String classKind(Class<?> cls) {
        if (cls.isAnnotation()) return "annotation";
        if (cls.isEnum()) return "enum";
        if (cls.isRecord()) return "record";
        if (cls.isInterface()) return "interface";
        return "class";
    }

    private static List<String> methodSignatures(Class<?> cls) {
        List<String> sigs = new ArrayList<>();
        try {
            Method[] methods = cls.getMethods();
            Arrays.sort(methods, (a, b) -> a.getName().compareTo(b.getName()));
            for (Method m : methods) {
                StringBuilder sb = new StringBuilder();
                sb.append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(params[i].getSimpleName());
                }
                sb.append(')');
                sigs.add(sb.toString());
            }
        } catch (Exception | Error e) {
            // セキュリティマネージャや封印モジュールでアクセス不可の場合は無視
        }
        return sigs;
    }

    private static List<String> fieldNames(Class<?> cls) {
        List<String> names = new ArrayList<>();
        try {
            Field[] fields = cls.getFields();
            Arrays.sort(fields, (a, b) -> a.getName().compareTo(b.getName()));
            for (Field f : fields) {
                names.add(f.getName());
            }
        } catch (Exception | Error e) {
            // アクセス不可の場合は無視
        }
        return names;
    }

    /** ステータスバー表示用の1行サマリ。 */
    public String toStatusLine() {
        String pkg = fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : "";
        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        int mCount = methodSignatures.size();
        int fCount = fieldNames.size();
        return String.format("%s - %s (%s) [%d methods, %d fields]", simpleName, kind, pkg, mCount, fCount);
    }
}
