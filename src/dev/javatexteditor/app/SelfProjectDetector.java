package dev.javatexteditor.app;

import dev.javatexteditor.analysis.CodeSourceLocator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * F11/F12 の実行対象プロジェクトが、今動いているエディタ自身のプロジェクトかどうかを判定する。
 *
 * <p><b>判定方法（ソースツリー一致方式）</b>: 実行対象のFQCN文字列（例:
 * {@code "dev.javatexteditor.Main"}）を見るのではなく、実行対象プロジェクトの
 * {@code projectRoot} が、今動いているエディタ自身のプロジェクトルートと同一か、
 * その祖先であるかを比較する。文字列一致方式（FQCNを見るだけ）より実装コストは
 * わずかに増えるが、パッケージ名やクラス名を変更されても正しく判定できる点で頑健。
 *
 * <p><b>自分自身のプロジェクトルートの求め方</b>: {@link SetupBootstrap#runIfNeeded} が
 * {@code lib}/{@code scripts} ディレクトリを探すのに使っているのと同じ手法
 * （{@link CodeSourceLocator#findUpward}、内部で
 * {@code Class#getProtectionDomain().getCodeSource().getLocation()} を使う）を再利用し、
 * 実行中クラスの格納元から {@code scripts} ディレクトリを探し当て、その親をプロジェクトルート
 * とみなす。これは {@code SetupBootstrap.resolveScriptDir} と全く同じロジックである
 * （独自の再実装はしていない）。
 *
 * <p><b>シンボリックリンク対策</b>: {@link #isOwnProject} での比較前に、双方のパスを
 * {@link Path#toRealPath} で正規化する。相対パスやシンボリックリンクが混ざった状態で
 * 単純な文字列/{@code Path}比較を行うと、実体としては同じディレクトリなのに
 * 一致と判定できない（＝ブロックが効かない）事故が起こりうるため。
 *
 * <p><b>TODO（今回のスコープ外）</b>: 現状は「ソースから {@code .class} を直接実行する
 * 開発モード」のみを対象としている。jar配布時は
 * {@code getCodeSource().getLocation()} がjarファイル自体のパスを指すため、
 * 本クラスの {@code scripts} ディレクトリ探索ロジックはそのままでは通用しない。
 * jar配布に対応する際は別途、jar内のマニフェスト等を使った判定方式への切り替えが必要。
 */
public final class SelfProjectDetector {

    private final Optional<Path> ownProjectRoot;

    /** @param anchor 実行中クラスの位置を求める基準クラス（呼び出し側は {@code Main.class} を渡す） */
    public SelfProjectDetector(Class<?> anchor) {
        this.ownProjectRoot = CodeSourceLocator
            .findUpward(anchor, "scripts", 4, Files::isDirectory)
            .map(Path::getParent);
    }

    /**
     * targetProjectRoot が、エディタ自身のプロジェクトと同一か、その祖先であれば true。
     *
     * <p>自分自身のプロジェクトルートが求まらなかった場合や、{@code toRealPath()} が
     * 失敗した場合（存在しないパス等）は false を返す（fail-open）。多重起動防止
     * （{@link SingleInstanceGuard}）と同様、判定できないことを理由にあらゆる実行を
     * ブロックしてしまう方が実害が大きいため、判定不能時は既存動作（ブロックしない）を優先する。
     */
    public boolean isOwnProject(Path targetProjectRoot) {
        if (ownProjectRoot.isEmpty()) return false;
        try {
            Path targetReal = targetProjectRoot.toRealPath();
            Path ownReal = ownProjectRoot.get().toRealPath();
            return targetReal.equals(ownReal) || ownReal.startsWith(targetReal);
        } catch (IOException e) {
            return false;
        }
    }
}
