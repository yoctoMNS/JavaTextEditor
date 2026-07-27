package dev.javatexteditor.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * CommandRegistry（COMMAND モードのコマンド名 → 処理の振り分け）の単体テスト。
 * ModalEditor にも画面にも依存しないため、記録用のリストだけで検証できる。
 */
public class CommandRegistryTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testExactCommandRuns();
        testAliasesShareOneAction();
        testUnknownCommandIsNotHandled();
        testPrefixCommandReceivesTrimmedArgument();
        testPrefixCommandWithEmptyArgument();
        testPrefixesAreEvaluatedInRegistrationOrder();
        testExactMatchWinsOverNothingElse();
        testExactCommandIsNotTriggeredByPrefixForm();
        testLaterRegistrationOverwritesSameName();

        System.out.println();
        System.out.println("PASS: " + passed + " / " + (passed + failed) + "  (FAIL: " + failed + ")");
        if (failed > 0) System.exit(1);
    }

    private static void testExactCommandRuns() {
        List<String> log = new ArrayList<>();
        CommandRegistry r = new CommandRegistry();
        r.on(() -> log.add("saved"), "w");

        check("完全一致コマンドは handled=true", r.dispatch("w"));
        check("対応する処理が実行される", log.equals(List.of("saved")));
    }

    private static void testAliasesShareOneAction() {
        List<String> log = new ArrayList<>();
        CommandRegistry r = new CommandRegistry();
        r.on(() -> log.add("quitAll"), "qa", "qall");

        r.dispatch("qa");
        r.dispatch("qall");
        check("別名はどちらも同じ処理へ振り分けられる", log.equals(List.of("quitAll", "quitAll")));
    }

    private static void testUnknownCommandIsNotHandled() {
        CommandRegistry r = new CommandRegistry();
        r.on(() -> { }, "w");
        check("未登録のコマンドは handled=false", !r.dispatch("nosuchcommand"));
    }

    private static void testPrefixCommandReceivesTrimmedArgument() {
        List<String> log = new ArrayList<>();
        CommandRegistry r = new CommandRegistry();
        r.onPrefix("grep ", arg -> log.add("[" + arg + "]"));

        check("前置一致コマンドは handled=true", r.dispatch("grep   foo bar  "));
        check("接頭辞を取り除いた引数が trim されて渡る", log.equals(List.of("[foo bar]")));
    }

    private static void testPrefixCommandWithEmptyArgument() {
        List<String> log = new ArrayList<>();
        CommandRegistry r = new CommandRegistry();
        r.onPrefix("cd ", arg -> log.add("[" + arg + "]"));

        r.dispatch("cd ");
        check("引数が空でも呼ばれ、空文字列が渡る", log.equals(List.of("[]")));
    }

    private static void testPrefixesAreEvaluatedInRegistrationOrder() {
        // ":grep! " を先に登録すれば、"grep! x" は grep! 側へ振り分けられる
        List<String> log = new ArrayList<>();
        CommandRegistry r = new CommandRegistry();
        r.onPrefix("grep! ", arg -> log.add("bang:" + arg));
        r.onPrefix("grep ",  arg -> log.add("plain:" + arg));

        r.dispatch("grep! foo");
        r.dispatch("grep bar");
        check("登録順に評価され、それぞれ別の処理へ振り分けられる",
                log.equals(List.of("bang:foo", "plain:bar")));
    }

    private static void testExactMatchWinsOverNothingElse() {
        // ":w" は完全一致、":w path" は前置一致。両者が混ざらないこと
        List<String> log = new ArrayList<>();
        CommandRegistry r = new CommandRegistry();
        r.on(() -> log.add("save-current"), "w");
        r.onPrefix("w ", arg -> log.add("save-as:" + arg));

        r.dispatch("w");
        r.dispatch("w foo.txt");
        check("完全一致と前置一致が正しく振り分けられる",
                log.equals(List.of("save-current", "save-as:foo.txt")));
    }

    private static void testExactCommandIsNotTriggeredByPrefixForm() {
        List<String> log = new ArrayList<>();
        CommandRegistry r = new CommandRegistry();
        r.on(() -> log.add("exact"), "w");

        check("接頭辞が未登録なら 'w foo' は未処理", !r.dispatch("w foo"));
        check("完全一致コマンドは引数付きの形では発火しない", log.isEmpty());
    }

    private static void testLaterRegistrationOverwritesSameName() {
        List<String> log = new ArrayList<>();
        CommandRegistry r = new CommandRegistry();
        r.on(() -> log.add("first"), "x");
        r.on(() -> log.add("second"), "x");

        r.dispatch("x");
        check("同名を再登録すると後勝ちになる", log.equals(List.of("second")));
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS: " + label);
        } else {
            failed++;
            System.out.println("FAIL [" + label + "]");
        }
    }
}
