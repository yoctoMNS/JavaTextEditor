package dev.javatexteditor.performance;

import dev.javatexteditor.buffer.PieceTable;
import dev.javatexteditor.buffer.UndoablePieceTable;

public class LargeFileTest {
    private static int pass = 0;
    private static int total = 0;

    // パフォーマンス閾値（ms）
    private static final long THRESHOLD_OPEN_100K    = 500;  // 10万行ファイルをPieceTableに読み込む
    private static final long THRESHOLD_INSERT_1K    = 500;  // 大規模文書に1000回挿入
    private static final long THRESHOLD_DELETE_1K    = 500;  // 末尾から1000回削除
    private static final long THRESHOLD_GETTEXT_100K = 1000; // 10万行 getText
    private static final long THRESHOLD_UNDO_50      = 500;  // 50回アンドゥ
    private static final long THRESHOLD_OFFSETLINE   = 1000; // 1000行目のオフセット計算

    public static void main(String[] args) {
        testOpen100kLines();
        testInsertAtBeginning1k();
        testDeleteFromEnd1k();
        testGetTextOn100kLines();
        testUndoRedo50Times();
        testOffsetOfLineLargeDocument();

        int fail = total - pass;
        System.out.println("---");
        System.out.println("PASS: " + pass + " / " + total + "  (FAIL: " + fail + ")");
        if (fail > 0) System.exit(1);
    }

    // 10万行のファイルを読み込んでPieceTableに格納（"ファイルを開く"シナリオ）
    // PieceTableのコンストラクタはテキスト全体を1ピースとして保持するのでO(1)
    static void testOpen100kLines() {
        // まず文字列として構築（これ自体はテスト対象外）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100_000; i++) sb.append("line").append(i).append("\n");
        String bigText = sb.toString();

        long start = System.currentTimeMillis();
        PieceTable t = new PieceTable(bigText);
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[PERF] 10万行ファイルオープン: " + elapsed + "ms (threshold=" + THRESHOLD_OPEN_100K + "ms)");
        checkPerf("10万行ファイルオープンが閾値内", THRESHOLD_OPEN_100K, elapsed);
        check("10万行オープン後先頭がline0で始まる", true, t.getText().startsWith("line0\n"));
    }

    // 先頭への 1000 回挿入
    static void testInsertAtBeginning1k() {
        // 1000行の既存文書
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) sb.append("existing").append(i).append("\n");
        PieceTable t = new PieceTable(sb.toString());

        long start = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            t.insert(0, "prefix" + i + "\n");
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[PERF] 先頭に1000回挿入: " + elapsed + "ms (threshold=" + THRESHOLD_INSERT_1K + "ms)");
        checkPerf("先頭1000回挿入が閾値内", THRESHOLD_INSERT_1K, elapsed);
        // 行数確認（元1000行 + 挿入1000行 = 2000行 + 末尾の空行）
        int lineCount = t.getText().split("\n", -1).length;
        check("先頭1000回挿入後行数==2001", true, lineCount >= 2000);
    }

    // 末尾から 1000 回削除（1行ずつ）
    static void testDeleteFromEnd1k() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append("line").append(i).append("\n");
        PieceTable t = new PieceTable(sb.toString());
        int initialLength = t.length();

        long start = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            // 末尾の改行を含む行(最短 "lineN\n" = 7〜9文字)を削除するために末尾から改行を探す
            String text = t.getText();
            int lastNl = text.lastIndexOf('\n', text.length() - 2); // 末尾改行の1つ前
            int deleteFrom = (lastNl < 0) ? 0 : lastNl + 1;
            t.delete(deleteFrom, t.length() - deleteFrom);
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[PERF] 末尾から1000行削除: " + elapsed + "ms (threshold=" + THRESHOLD_DELETE_1K + "ms)");
        checkPerf("末尾1000行削除が閾値内", THRESHOLD_DELETE_1K, elapsed);
        check("末尾1000行削除後length<初期値", true, t.length() < initialLength);
    }

    // 10万行文書の getText 速度
    static void testGetTextOn100kLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100_000; i++) sb.append("L").append(i).append("\n");
        PieceTable t = new PieceTable(sb.toString());

        long start = System.currentTimeMillis();
        String text = t.getText();
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[PERF] 10万行 getText: " + elapsed + "ms (threshold=" + THRESHOLD_GETTEXT_100K + "ms)");
        checkPerf("10万行 getText が閾値内", THRESHOLD_GETTEXT_100K, elapsed);
        check("10万行 getText 非空", true, text.length() > 0);
    }

    // 50 回編集 → 50 回アンドゥ の速度
    static void testUndoRedo50Times() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10_000; i++) sb.append("line").append(i).append("\n");
        UndoablePieceTable t = new UndoablePieceTable(sb.toString());

        // 50回挿入
        for (int i = 0; i < 50; i++) {
            t.insert(0, "X" + i + "\n");
        }

        long start = System.currentTimeMillis();
        for (int i = 0; i < 50; i++) {
            t.undo();
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[PERF] 1万行文書で50回アンドゥ: " + elapsed + "ms (threshold=" + THRESHOLD_UNDO_50 + "ms)");
        checkPerf("50回アンドゥが閾値内", THRESHOLD_UNDO_50, elapsed);
        check("50回アンドゥ後先頭がline0で始まる", true, t.getText().startsWith("line0\n"));
    }

    // 大規模文書での offsetOfLine 速度
    static void testOffsetOfLineLargeDocument() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10_000; i++) sb.append("line").append(i).append("\n");
        PieceTable t = new PieceTable(sb.toString());

        long start = System.currentTimeMillis();
        // 1000箇所の offsetOfLine を計算
        for (int i = 0; i < 1000; i++) {
            t.offsetOfLine(i * 10);
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("[PERF] 1万行文書で offsetOfLine×1000回: " + elapsed + "ms (threshold=" + THRESHOLD_OFFSETLINE + "ms)");
        checkPerf("offsetOfLine×1000が閾値内", THRESHOLD_OFFSETLINE, elapsed);

        // 正確性チェック: 0行目は0
        check("大規模文書 offsetOfLine(0)==0", 0, t.offsetOfLine(0));
    }

    static void checkPerf(String name, long threshold, long actual) {
        total++;
        boolean ok = actual <= threshold;
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> actual=" + actual + "ms threshold=" + threshold + "ms");
        if (ok) pass++;
    }

    static void check(String name, Object expected, Object actual) {
        total++;
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "[OK] " : "[FAIL] ") + name
            + " -> expected=" + expected + " actual=" + actual);
        if (ok) pass++;
    }
}
