package dev.javatexteditor.format;

import java.util.*;
import java.util.regex.Pattern;

/**
 * メソッド名の出現ベースで構築した軽量コールグラフを使い、Step-downルール（呼び出し元の直下に
 * 呼び出し先を配置）+ アルファベット順で並び替える。型解決は一切行わず、正規表現の単語境界
 * マッチ（{@code \bNAME\b}）だけで「AがBを呼んでいるらしい」を判定する軽量版。
 * すべてローカル変数・引数のみで完結し、呼び出し完了後は何も保持しない。
 */
final class MethodCallGraphSorter {

    private MethodCallGraphSorter() {}

    /**
     * @param methodSlices equals/hashCode/toString/clone を除いたメソッドのスライス一覧
     * @param source       元のソース全文（呼び出し検出のため各メンバーの本文を都度読む）
     * @return Step-down DFS + アルファベット順で並び替えたメソッドスライスの新しいリスト
     *         （同名メソッド＝オーバーロードは常に連続し、内部は引数の少ない順）
     */
    static List<MemberSlice> sort(List<MemberSlice> methodSlices, String source) {
        if (methodSlices.isEmpty()) return List.of();

        Map<String, List<MemberSlice>> byName = new LinkedHashMap<>();
        for (MemberSlice m : methodSlices) {
            byName.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m);
        }

        Map<String, String> bodyByName = new HashMap<>();
        for (Map.Entry<String, List<MemberSlice>> e : byName.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (MemberSlice m : e.getValue()) {
                sb.append(source, m.start(), m.end()).append('\n');
            }
            bodyByName.put(e.getKey(), sb.toString());
        }

        Set<String> names = byName.keySet();
        Map<String, Set<String>> calls = new HashMap<>();
        for (String caller : names) {
            String body = bodyByName.get(caller);
            Set<String> callees = new LinkedHashSet<>();
            for (String callee : names) {
                if (callee.equals(caller)) continue;
                if (Pattern.compile("\\b" + Pattern.quote(callee) + "\\b").matcher(body).find()) {
                    callees.add(callee);
                }
            }
            calls.put(caller, callees);
        }

        Set<String> everCalled = new HashSet<>();
        for (Set<String> callees : calls.values()) everCalled.addAll(callees);

        List<String> roots = new ArrayList<>();
        for (String name : names) {
            int minVisibility = 3;
            for (MemberSlice m : byName.get(name)) minVisibility = Math.min(minVisibility, m.visibilityRank());
            boolean neverCalled = !everCalled.contains(name);
            boolean isPublicOrProtected = minVisibility <= 1;
            if (neverCalled || isPublicOrProtected) roots.add(name);
        }
        Collections.sort(roots);

        LinkedHashSet<String> orderedNames = new LinkedHashSet<>();
        for (String root : roots) {
            visit(root, calls, orderedNames);
        }

        List<String> remaining = new ArrayList<>();
        for (String name : names) {
            if (!orderedNames.contains(name)) remaining.add(name);
        }
        Collections.sort(remaining);
        orderedNames.addAll(remaining);

        List<MemberSlice> result = new ArrayList<>(methodSlices.size());
        for (String name : orderedNames) {
            List<MemberSlice> overloads = new ArrayList<>(byName.get(name));
            overloads.sort(Comparator.comparingInt(MemberSlice::paramCount).thenComparingInt(MemberSlice::start));
            result.addAll(overloads);
        }
        return result;
    }

    private static void visit(String name, Map<String, Set<String>> calls, LinkedHashSet<String> ordered) {
        if (ordered.contains(name)) return;
        ordered.add(name);
        List<String> callees = new ArrayList<>();
        for (String callee : calls.getOrDefault(name, Set.of())) {
            if (!ordered.contains(callee)) callees.add(callee);
        }
        Collections.sort(callees);
        for (String callee : callees) {
            visit(callee, calls, ordered);
        }
    }
}
