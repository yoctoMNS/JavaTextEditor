package dev.javatexteditor.app;

import dev.javatexteditor.analysis.CodeSourceLocator;
import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.projectbuild.MainClassFinder;
import dev.javatexteditor.projectbuild.ProjectBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link JavaBuildRunner} の自プロジェクト実行ブロック（フェーズ3）の検証。
 *
 * <p>{@code triggerRun}/{@code resolveAndRunMainClass} は非同期の main クラス探索や
 * 追加クラスパスの入力プロンプトを挟むため、テストからは {@link JavaBuildRunner#runSelectedMainClass}
 * （telescope-picker選択時・{@code MainClassFinder}を経由せず同期的に {@code runJavaClass} へ
 * 到達する唯一の公開経路）を直接呼び、ブロック判定そのものを検証する。
 *
 * <p><b>安全上の注意</b>: 自プロジェクトルートを対象にするテストでも、実在の
 * {@code dev.javatexteditor.Main} ではなく常に架空のFQCN（{@code "not.a.real.Class"}）を渡す。
 * 万一このリポジトリの {@code bin/} に過去のF10実行で実クラスが残っていた場合でも、
 * このテスト自身が誤って入れ子のエディタ（GUIプロセス）を起動してしまわないようにするため
 * （今回のバグ修正対象そのものを、テストで再現してしまわないための配慮）。
 */
public class SelfExecutionBlockTest {

    private static final String FAKE_FQCN = "not.a.real.Class";

    private static int pass = 0;
    private static int total = 0;

    public static void main(String[] args) throws Exception {
        Optional<Path> ownRootOpt = CodeSourceLocator
            .findUpward(SelfExecutionBlockTest.class, "scripts", 4, Files::isDirectory)
            .map(Path::getParent);
        if (ownRootOpt.isEmpty()) {
            System.out.println("[SKIP] 自プロジェクトのルートが見つからないためスキップします");
            System.out.println("PASS: 0 / 0  (FAIL: 0)");
            return;
        }
        Path ownRoot = ownRootOpt.get();

        testSelfProjectBlockedByDefault(ownRoot);
        testOtherProjectIsNotBlocked();
        testAllowSelfExecutionUnblocks(ownRoot);

        int fail = total - pass;
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) System.exit(1);
    }

    static void check(String name, boolean expected, boolean actual) {
        total++;
        boolean ok = expected == actual;
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " (expected=" + expected + ", actual=" + actual + ")");
        if (ok) pass++;
    }

    private static JavaBuildRunner newRunner(SelfExecutionPolicy policy) {
        return new JavaBuildRunner(new ProjectBuilder(), new MainClassFinder(),
            new RunningProcessHolder(), new SelfProjectDetector(SelfExecutionBlockTest.class), policy);
    }

    /** 既定設定（TransientSelfExecutionPolicy未許可）では自プロジェクトの実行がブロックされること。 */
    private static void testSelfProjectBlockedByDefault(Path ownRoot) {
        JavaBuildRunner runner = newRunner(new TransientSelfExecutionPolicy());
        ModalEditor ed = new ModalEditor("original text");
        ed.setProjectRoot(ownRoot);

        runner.runSelectedMainClass(ed, ownRoot, FAKE_FQCN);

        check("既定では自プロジェクトの実行がブロックされ、statusMessageに理由が表示される",
            true, JavaBuildRunner.SELF_EXECUTION_BLOCKED_MESSAGE.equals(ed.getStatusMessage()));
        check("ブロック時は*run*疑似バッファへ切り替わらない（元のバッファのまま）",
            true, "original text".equals(ed.getText()));
    }

    /** 自プロジェクト以外（無関係な別プロジェクト）はブロックの対象にならないこと（誤検知がない）。 */
    private static void testOtherProjectIsNotBlocked() throws IOException {
        JavaBuildRunner runner = newRunner(new TransientSelfExecutionPolicy());
        Path otherProject = Files.createTempDirectory("sxb-other-");
        otherProject.toFile().deleteOnExit();
        ModalEditor ed = new ModalEditor("original text");
        ed.setProjectRoot(otherProject);

        runner.runSelectedMainClass(ed, otherProject, FAKE_FQCN);

        check("別プロジェクトの実行はブロックされず*run*疑似バッファへ切り替わる（誤検知がない）",
            true, ed.getText().startsWith("java -cp"));
    }

    /** :set allowselfexecution 相当（TransientSelfExecutionPolicy#setAllowed(true)）で許可されること。 */
    private static void testAllowSelfExecutionUnblocks(Path ownRoot) {
        TransientSelfExecutionPolicy policy = new TransientSelfExecutionPolicy();
        JavaBuildRunner runner = newRunner(policy);

        ModalEditor before = new ModalEditor("original text");
        before.setProjectRoot(ownRoot);
        runner.runSelectedMainClass(before, ownRoot, FAKE_FQCN);
        check(":set allowselfexecution 前は依然ブロックされる",
            true, JavaBuildRunner.SELF_EXECUTION_BLOCKED_MESSAGE.equals(before.getStatusMessage()));

        policy.setAllowed(true); // :set allowselfexecution 相当
        ModalEditor after = new ModalEditor("original text");
        after.setProjectRoot(ownRoot);
        runner.runSelectedMainClass(after, ownRoot, FAKE_FQCN);
        check(":set allowselfexecution 後は自プロジェクトでも実行される（*run*疑似バッファへ切り替わる）",
            true, after.getText().startsWith("java -cp"));
    }
}
