package dev.javatexteditor.completion2;

import java.util.List;

public class CompletionEngineTest {
    public static void main(String[] args) {
        int pass = 0;

        // Test 1: トークン抽出の基本
        List<String> tokens = TokenScanner.extractAllTokens("int foo_1 = bar$2 + baz;");
        pass += check("トークン抽出", "[int, foo_1, bar$2, baz]", tokens.toString());

        // Test 2: prefixBeforeCaret - カーソル直前の未確定プレフィックス
        String line = "String userNa";
        pass += check("prefixBeforeCaret", "userNa", TokenScanner.prefixBeforeCaret(line, line.length()));

        // Test 3: findTokenStart - トークンの先頭オフセット
        pass += check("findTokenStart", "7", String.valueOf(TokenScanner.findTokenStart(line, line.length())));

        // Test 4: collectCandidates - 前方一致でフィルタされる
        CompletionEngine engine = new CompletionEngine();
        String doc4 = "userName userAge userName2 other";
        List<CompletionCandidate> c4 = engine.collectCandidates(doc4, doc4.length(), "user");
        pass += check("前方一致フィルタ件数", "3", String.valueOf(c4.size()));
        pass += check("前方一致に無関係な語を含まない", "false",
            String.valueOf(c4.stream().anyMatch(c -> c.text().equals("other"))));

        // Test 5: collectCandidates - プレフィックスと完全一致する語は候補から除外される
        String doc5 = "value valueOf valueOf2";
        List<CompletionCandidate> c5 = engine.collectCandidates(doc5, 5, "value");
        pass += check("完全一致トークンは除外", "false",
            String.valueOf(c5.stream().anyMatch(c -> c.text().equals("value"))));

        // Test 6: collectCandidates - カーソルに近い出現が優先される
        String doc6 = "aaaNear xxxxxxxxxx aaaFar";
        int caret6 = 8; // "aaaNear " の直後
        List<CompletionCandidate> c6 = engine.collectCandidates(doc6, caret6, "aaa");
        pass += check("近い候補が先頭", "aaaNear", c6.isEmpty() ? "" : c6.get(0).text());

        // Test 7: CompletionSession - advance で候補を巡回し、末尾の次で元のプレフィックスに戻る
        CompletionSession session = new CompletionSession(0, "user",
            List.of(new CompletionCandidate("userAge", 0), new CompletionCandidate("userName", -1)));
        pass += check("advance 1回目", "userAge", session.advance());
        pass += check("advance 2回目", "userName", session.advance());
        pass += check("advance 3回目(元に戻る)", "user", session.advance());
        pass += check("advance 4回目(先頭に戻る)", "userAge", session.advance());

        // Test 8: CompletionSession - retreat は advance と逆方向に巡回する
        CompletionSession session2 = new CompletionSession(0, "user",
            List.of(new CompletionCandidate("userAge", 0), new CompletionCandidate("userName", -1)));
        pass += check("retreat 1回目(末尾候補)", "userName", session2.retreat());
        pass += check("retreat 2回目", "userAge", session2.retreat());
        pass += check("retreat 3回目(元に戻る)", "user", session2.retreat());

        // Test 9: CompletionSession - 候補が0件の場合は常に originalPrefix を返す
        CompletionSession empty = new CompletionSession(0, "abc", List.of());
        pass += check("候補0件でも例外にならない", "abc", empty.advance());

        int total = 15;
        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) {
            System.exit(1);
        }
    }

    static int check(String name, String expected, String actual) {
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=\"" + expected + "\" actual=\"" + actual + "\"");
        return ok ? 1 : 0;
    }
}
