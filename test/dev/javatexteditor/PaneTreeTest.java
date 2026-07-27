package dev.javatexteditor;

import dev.javatexteditor.PaneTree.Leaf;
import dev.javatexteditor.PaneTree.PaneNode;
import dev.javatexteditor.PaneTree.Split;
import java.util.List;

/**
 * PaneTree（画面分割のツリー構造）の単体テスト。
 *
 * <p>ツリー操作はリーフの中身を一切見ず参照の同一性だけで動くため、
 * {@code new Leaf(null, null)} を並べるだけで検証できる（EditorCanvas も JFrame も要らない）。
 */
public class PaneTreeTest {

    private static final int H = 0; // JSplitPane.HORIZONTAL_SPLIT 相当
    private static final int V = 1; // JSplitPane.VERTICAL_SPLIT 相当

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testSingleLeafIsItsOwnTree();
        testSplitRootLeaf();
        testSplitKeepsOtherLeavesUntouched();
        testSplitNestedLeaf();
        testLeafOrderIsLeftToRight();
        testRemoveLeafReplacesParentWithSibling();
        testRemoveLastLeafReturnsNull();
        testRemoveNestedLeaf();
        testRemoveUnknownLeafLeavesTreeIntact();
        testSplitPreservesOrientation();

        System.out.println();
        System.out.println("PASS: " + passed + " / " + (passed + failed) + "  (FAIL: " + failed + ")");
        if (failed > 0) System.exit(1);
    }

    private static Leaf leaf() {
        return new Leaf(null, null);
    }

    private static void testSingleLeafIsItsOwnTree() {
        Leaf a = leaf();
        check("リーフ1つだけの木はそのリーフ1件を返す", PaneTree.allLeaves(a).equals(List.of(a)));
    }

    private static void testSplitRootLeaf() {
        Leaf a = leaf(), b = leaf();
        PaneNode root = PaneTree.splitLeaf(a, a, b, H);
        check("根のリーフを分割すると Split が根になる", root instanceof Split);
        check("分割後は2ペインになる", PaneTree.allLeaves(root).equals(List.of(a, b)));
    }

    private static void testSplitKeepsOtherLeavesUntouched() {
        Leaf a = leaf(), b = leaf(), c = leaf();
        PaneNode root = new Split(H, a, b);
        root = PaneTree.splitLeaf(root, b, c, V);
        check("対象でないリーフはそのまま残る", PaneTree.allLeaves(root).equals(List.of(a, b, c)));
    }

    private static void testSplitNestedLeaf() {
        Leaf a = leaf(), b = leaf(), c = leaf(), d = leaf();
        PaneNode root = new Split(H, a, new Split(V, b, c));
        root = PaneTree.splitLeaf(root, c, d, H);
        check("入れ子の奥にあるリーフも分割できる",
                PaneTree.allLeaves(root).equals(List.of(a, b, c, d)));
    }

    private static void testLeafOrderIsLeftToRight() {
        Leaf a = leaf(), b = leaf(), c = leaf();
        PaneNode root = new Split(H, new Split(V, a, b), c);
        check("リーフは左（上）から順に並ぶ", PaneTree.allLeaves(root).equals(List.of(a, b, c)));
    }

    private static void testRemoveLeafReplacesParentWithSibling() {
        Leaf a = leaf(), b = leaf();
        PaneNode root = new Split(H, a, b);
        PaneNode after = PaneTree.removeLeaf(root, a);
        check("2ペインの片方を閉じると兄弟がそのまま根になる", after == b);
        check("閉じた後は1ペインだけ", PaneTree.allLeaves(after).equals(List.of(b)));
    }

    private static void testRemoveLastLeafReturnsNull() {
        Leaf a = leaf();
        check("最後の1ペインを閉じると null（＝もう木が無い）", PaneTree.removeLeaf(a, a) == null);
    }

    private static void testRemoveNestedLeaf() {
        Leaf a = leaf(), b = leaf(), c = leaf();
        PaneNode root = new Split(H, a, new Split(V, b, c));
        PaneNode after = PaneTree.removeLeaf(root, b);
        check("入れ子の奥のリーフを閉じると兄弟が親の位置へ繰り上がる",
                PaneTree.allLeaves(after).equals(List.of(a, c)));
        check("繰り上がった結果、根は2ペインの Split のまま", after instanceof Split);
        check("繰り上がった兄弟が右側に入る", ((Split) after).right == c);
    }

    private static void testRemoveUnknownLeafLeavesTreeIntact() {
        Leaf a = leaf(), b = leaf(), stranger = leaf();
        PaneNode root = new Split(H, a, b);
        PaneNode after = PaneTree.removeLeaf(root, stranger);
        check("木に無いリーフを指定しても木は変わらない",
                after == root && PaneTree.allLeaves(after).equals(List.of(a, b)));
    }

    private static void testSplitPreservesOrientation() {
        Leaf a = leaf(), b = leaf();
        PaneNode root = PaneTree.splitLeaf(a, a, b, V);
        check("分割の向きが保持される", ((Split) root).orientation == V);
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
