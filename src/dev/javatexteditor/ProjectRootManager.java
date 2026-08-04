package dev.javatexteditor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * `:pr` で固定する「F10/F11/F12用プロジェクトルート」をアプリ全体で単一の値として管理する。
 *
 * <p>{@link WorkingDirectoryManager}（`:cd` の作業ディレクトリ）と同じ「中央管理インスタンス
 * ＋変更リスナー」方式を採用し、あるペインで `:pr` を実行すると全ペインへ即座に反映される。
 * 2026-08-04より前は各 {@code ModalEditor} インスタンスが {@code projectRootOverride}
 * フィールドを個別に保持するだけで、ペイン間の共有機構が存在しなかった（バグ修正の経緯は
 * decision-log.md「`:pr`コマンド（F10/F11/F12用プロジェクトルートの固定）の設計決定事項」
 * 節の追記を参照）。
 *
 * <p>{@code WorkingDirectoryManager} と異なり、ここに渡される値は常に呼び出し時点の
 * {@code getProjectRoot()}（既に存在確認済みの `:cd` 現在ディレクトリ）であるため、
 * 存在チェック等のバリデーションは行わない。
 */
public final class ProjectRootManager {

    private Path projectRootOverride = null; // null = :cd 追従（未固定）
    private final List<Consumer<Path>> listeners = new ArrayList<>();

    /** 現在固定されているプロジェクトルート（未固定なら null）。 */
    public Path getProjectRootOverride() { return projectRootOverride; }

    /** `:pr` で固定する。全リスナー（＝全ペイン）へ即座に通知する。 */
    public void setProjectRootOverride(Path path) {
        this.projectRootOverride = path;
        for (Consumer<Path> l : listeners) l.accept(path);
    }

    /** プロジェクトルートが変更されたときに呼ばれるリスナーを追加する。 */
    public void addChangeListener(Consumer<Path> listener) { listeners.add(listener); }
}
