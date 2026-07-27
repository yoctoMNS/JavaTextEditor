package dev.javatexteditor;

import dev.javatexteditor.editor.ModalEditor;
import dev.javatexteditor.ui.EditorCanvas;
import java.util.ArrayList;
import java.util.List;

/**
 * 画面分割（{@code :split} / {@code :vsplit} / ペインを閉じる）のツリー構造と、その操作。
 *
 * <p>ここには <b>Swing への依存が一切無い</b>。実際に {@code JSplitPane} を組み立てるのは
 * {@code Main.buildComponent()} の役目で、このクラスは「どのペインがどう入れ子になっているか」
 * という構造だけを扱う。そのおかげで、分割・削除の挙動をウィンドウを開かずに単体テストできる。
 *
 * <p>{@link Leaf} は {@link EditorCanvas} と {@link ModalEditor} の組を指すが、
 * このクラスの操作はどれもその中身を参照せず、参照の同一性（{@code ==}）だけで目的のリーフを見つける。
 * したがってテストでは {@code new Leaf(null, null)} を並べるだけで構造の検証ができる。
 */
public final class PaneTree {

    private PaneTree() {}

    /** ペインツリーの節。リーフ（1つの編集ペイン）か、2つに分かれた節のどちらか。 */
    public sealed interface PaneNode permits Leaf, Split {}

    /** 1つの編集ペイン。画面と編集状態の組。 */
    public record Leaf(EditorCanvas canvas, ModalEditor editor) implements PaneNode {}

    /**
     * 2つのペイン（またはさらに入れ子の節）に分かれた節。
     *
     * <p>{@code left}/{@code right} は書き換え可能である。{@link #splitLeaf} と {@link #removeLeaf}
     * は新しいツリーを組み直すのではなく、既存の節を書き換えながら根を返す設計になっている
     * （分割のたびに全ペインを作り直すと、各ペインの編集状態まで作り直すことになるため）。
     */
    public static final class Split implements PaneNode {
        /** {@code JSplitPane.HORIZONTAL_SPLIT} または {@code VERTICAL_SPLIT}。 */
        public final int orientation;
        public PaneNode left;
        public PaneNode right;

        public Split(int orientation, PaneNode left, PaneNode right) {
            this.orientation = orientation;
            this.left  = left;
            this.right = right;
        }
    }

    /** ツリー内のすべてのリーフを左（上）から順に集める。 */
    public static List<Leaf> allLeaves(PaneNode node) {
        List<Leaf> result = new ArrayList<>();
        collectLeaves(node, result);
        return result;
    }

    private static void collectLeaves(PaneNode node, List<Leaf> out) {
        switch (node) {
            case Leaf l  -> out.add(l);
            case Split s -> { collectLeaves(s.left, out); collectLeaves(s.right, out); }
        }
    }

    /**
     * {@code target} リーフを指定の向きで分割し、右（下）に {@code newLeaf} を挿入した木を返す。
     * 根が {@code target} 自身なら、新しい {@link Split} が根になる。
     *
     * @return 分割後の根
     */
    public static PaneNode splitLeaf(PaneNode node, Leaf target, Leaf newLeaf, int orientation) {
        return switch (node) {
            case Leaf l -> (l == target)
                ? new Split(orientation, l, newLeaf)
                : l;
            case Split s -> {
                s.left  = splitLeaf(s.left,  target, newLeaf, orientation);
                s.right = splitLeaf(s.right, target, newLeaf, orientation);
                yield s;
            }
        };
    }

    /**
     * {@code target} リーフを取り除いた木を返す。親の {@link Split} は残った兄弟に置き換わる。
     *
     * @return 削除後の根。{@code target} が最後の1ペインだった場合は null
     */
    public static PaneNode removeLeaf(PaneNode node, Leaf target) {
        return switch (node) {
            case Leaf l -> (l == target) ? null : l;
            case Split s -> {
                PaneNode newLeft  = removeLeaf(s.left,  target);
                PaneNode newRight = removeLeaf(s.right, target);
                if (newLeft  == null) yield newRight;
                if (newRight == null) yield newLeft;
                s.left  = newLeft;
                s.right = newRight;
                yield s;
            }
        };
    }
}
