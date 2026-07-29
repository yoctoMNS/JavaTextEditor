package dev.javatexteditor.analysis;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 型の単純名から、その型が持つ public メンバーをリフレクションで列挙する。
 * メンバー補完のハイブリッド解決における<b>軽量側（即時表示）</b>を担う。
 *
 * <p>{@link JavacCompletionAnalyzer}（正確側）はプロジェクト全体の属性付けを伴うため
 * 数百ミリ秒〜数秒かかる。その間ポップアップが空のままだと「補完が効かない」と感じられるため、
 * まず本クラスが {@link JdkClassIndex}（起動時に構築済み）とリフレクションだけで
 * 即座に候補を出す。ディスクアクセスも javac 起動も伴わないためキー入力に追随できる。
 *
 * <p>限界: 解決できるのは「型の単純名が分かっている場合」だけで、
 * ジェネリクスの要素型・メソッドチェーンの戻り値型・{@code var} の推論型は扱えない。
 * それらは正確側の結果が届いた時点で差し替わる。
 */
public final class ReflectionMemberProvider {

    private ReflectionMemberProvider() {}

    /**
     * simpleTypeName が指す型の public メンバーを返す。型が解決できなければ空リスト。
     *
     * @param jdkIndex       JDK クラス索引（未構築なら空リストを返す）
     * @param simpleTypeName 型の単純名（{@code String}・{@code List} 等）または FQN
     * @param staticAccess   true なら static メンバー、false ならインスタンスメンバーを返す
     */
    public static List<CompletionItem> membersOf(JdkClassIndex jdkIndex, String simpleTypeName,
                                                 boolean staticAccess) {
        Optional<Class<?>> type = resolveClass(jdkIndex, simpleTypeName);
        return type.map(c -> membersOf(c, staticAccess)).orElse(List.of());
    }

    /** 解決済みの Class から public メンバー（継承分を含む）を列挙する。 */
    public static List<CompletionItem> membersOf(Class<?> type, boolean staticAccess) {
        if (type == null) return List.of();
        List<CompletionItem> items = new ArrayList<>();
        try {
            if (type.isArray()) {
                items.add(new CompletionItem("length", "fld", "", "int", "length", 0, null,
                    CompletionItem.Origin.MEMBER));
                type = Object.class;
            }
            for (Method m : type.getMethods()) {
                if (m.isSynthetic() || m.isBridge()) continue;
                if (Modifier.isStatic(m.getModifiers()) != staticAccess) continue;
                items.add(methodItem(m));
            }
            for (Field f : type.getFields()) {
                if (f.isSynthetic()) continue;
                if (Modifier.isStatic(f.getModifiers()) != staticAccess) continue;
                items.add(new CompletionItem(f.getName(), "fld", "",
                    f.getType().getSimpleName(), f.getName(), 0, null,
                    CompletionItem.Origin.MEMBER));
            }
        } catch (Throwable t) {
            // モジュール制約・リンクエラー等で列挙できない型は諦める（正確側に委ねる）
            return List.of();
        }
        items.sort(Comparator.comparing(CompletionItem::label));
        return dedupe(items);
    }

    /**
     * 型の単純名（または FQN）から Class を解決する。
     * 同名クラスが複数ある場合は {@link JdkClassIndex#lookup} が返す先頭を採る。
     */
    public static Optional<Class<?>> resolveClass(JdkClassIndex jdkIndex, String typeName) {
        if (typeName == null || typeName.isEmpty()) return Optional.empty();
        if (jdkIndex == null || !jdkIndex.isReady()) return Optional.empty();

        if (typeName.indexOf('.') >= 0) {
            return jdkIndex.loadClass(typeName);
        }
        for (String fqn : jdkIndex.lookup(typeName)) {
            Optional<Class<?>> loaded = jdkIndex.loadClass(fqn);
            if (loaded.isPresent()) return loaded;
        }
        return Optional.empty();
    }

    private static CompletionItem methodItem(Method m) {
        StringBuilder tail = new StringBuilder("(");
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) tail.append(", ");
            tail.append(params[i].getSimpleName());
        }
        tail.append(')');
        return new CompletionItem(m.getName(), "mth", tail.toString(),
            m.getReturnType().getSimpleName(), m.getName() + "()",
            params.length > 0 ? 1 : 0, null, CompletionItem.Origin.MEMBER);
    }

    /** 同じ表示になる候補（共変戻り値のブリッジ等）を1つに畳む。 */
    private static List<CompletionItem> dedupe(List<CompletionItem> items) {
        Set<String> seen = new LinkedHashSet<>();
        List<CompletionItem> result = new ArrayList<>(items.size());
        for (CompletionItem item : items) {
            if (seen.add(item.label() + item.tailText())) result.add(item);
        }
        return List.copyOf(result);
    }
}
