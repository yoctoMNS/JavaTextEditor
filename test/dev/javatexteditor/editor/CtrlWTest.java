package dev.javatexteditor.editor;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * INSERT モードの Ctrl+W（直前の1単語削除）のテスト。
 *
 * Vim の Ctrl+W 挙動:
 * 1. 直前の空白をスキップ
 * 2. 単語文字（英数字・_）または非単語文字（記号等）のまとまりを削除
 * 3. 行頭をまたがない（行頭では何もしない）
 */
public class CtrlWTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testDeleteSimpleWord();
        testDeleteWordWithTrailingSpace();
        testDeleteSymbolWord();
        testDeleteMixedWordAndSymbol();
        testAtLineStartDoesNothing();
        testDeleteWhitespaceOnly();
        testDeleteAfterMultipleSpaces();
        testDeletePartialWord();
        testDeleteWordLeavesRestOfLine();
        testDeleteUnderscore();

        System.out.println("\n=== CtrlWTest: " + passed + "/" + (passed + failed) + " PASS ===");
        if (failed > 0) System.exit(1);
    }

    // ── ヘルパー ──────────────────────────────────────────────────────────────

    /** テキストを設定してINSERTモードに入り、カーソルを指定列に移動して Ctrl+W を送る */
    private static ModalEditor makeInsert(String text, int col) {
        ModalEditor e = new ModalEditor(text);
        // INSERTモードへ
        e.processKey(KeyEvent.VK_I, 'i', 0);
        // カーソルを col 列へ（INSERT モードのカーソル右移動は Ctrl+F）
        for (int i = 0; i < col; i++) {
            e.processKey(KeyEvent.VK_F, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK);
        }
        return e;
    }

    private static void ctrlW(ModalEditor e) {
        e.processKey(KeyEvent.VK_W, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK);
    }

    // ── テストケース ──────────────────────────────────────────────────────────

    static void testDeleteSimpleWord() {
        // "hello world" カーソルを "world" の末尾(11)へ → "hello " が残る
        ModalEditor e = makeInsert("hello world", 11);
        ctrlW(e);
        String[] lines = e.getText().split("\n", -1);
        assertEquals("delete simple word", "hello ", lines[0]);
        assertEquals("cursor at end of remaining", 6, e.getCursorCol());
    }

    static void testDeleteWordWithTrailingSpace() {
        // "hello " カーソルを末尾(6)へ → 空白スキップ後に "hello" まで削除 → "" が残る
        // Vim の Ctrl+W は空白をスキップしてからその直前の単語まとまりを削除する
        ModalEditor e = makeInsert("hello ", 6);
        ctrlW(e);
        String[] lines = e.getText().split("\n", -1);
        assertEquals("delete trailing space then word", "", lines[0]);
    }

    static void testDeleteSymbolWord() {
        // "foo()" カーソルを末尾(5)へ → "()" は記号まとまりとして削除 → "foo" が残る
        ModalEditor e = makeInsert("foo()", 5);
        ctrlW(e);
        String[] lines = e.getText().split("\n", -1);
        assertEquals("delete symbol word", "foo", lines[0]);
    }

    static void testDeleteMixedWordAndSymbol() {
        // "foo.bar" カーソルを末尾(7)へ → "bar" を削除（単語文字まとまり）→ "foo." が残る
        ModalEditor e = makeInsert("foo.bar", 7);
        ctrlW(e);
        String[] lines = e.getText().split("\n", -1);
        assertEquals("delete word part of dotted", "foo.", lines[0]);
    }

    static void testAtLineStartDoesNothing() {
        // "hello" カーソルを先頭(0)のままINSERTモードで Ctrl+W → 何も変わらない
        ModalEditor e = makeInsert("hello", 0);
        ctrlW(e);
        String[] lines = e.getText().split("\n", -1);
        assertEquals("at line start: nothing deleted", "hello", lines[0]);
        assertEquals("at line start: cursor stays 0", 0, e.getCursorCol());
    }

    static void testDeleteWhitespaceOnly() {
        // "   " （空白3つ）カーソルを末尾(3)へ → 空白をすべて削除 → "" が残る
        ModalEditor e = makeInsert("   ", 3);
        ctrlW(e);
        String[] lines = e.getText().split("\n", -1);
        assertEquals("delete whitespace only: empty line", "", lines[0]);
        assertEquals("cursor at 0 after all-space delete", 0, e.getCursorCol());
    }

    static void testDeleteAfterMultipleSpaces() {
        // "hello   world" カーソルを末尾(13)へ → "world" のみ削除（空白は直前にないのでスキップなし）
        // → "hello   " が残る。空白+単語を一緒に削除したい場合は2回 Ctrl+W が必要
        ModalEditor e = makeInsert("hello   world", 13);
        ctrlW(e);
        String[] lines = e.getText().split("\n", -1);
        assertEquals("delete word after multiple spaces", "hello   ", lines[0]);
    }

    static void testDeletePartialWord() {
        // "foobar" カーソルを3(foo|bar)へ → "foo" を削除 → "bar" が残る
        ModalEditor e = makeInsert("foobar", 3);
        ctrlW(e);
        String[] lines = e.getText().split("\n", -1);
        assertEquals("delete partial word", "bar", lines[0]);
        assertEquals("cursor at 0", 0, e.getCursorCol());
    }

    static void testDeleteWordLeavesRestOfLine() {
        // "foo bar baz" カーソルを7("foo bar|")へ → "bar" を削除 → "foo  baz" 空白1つ残る
        // "foo " + "bar" → Ctrl+W → "foo baz"（"bar "の直前の空白はスキップ後 "bar" 削除）
        ModalEditor e = makeInsert("foo bar baz", 7);
        ctrlW(e);
        String[] lines = e.getText().split("\n", -1);
        assertEquals("rest of line preserved", "foo  baz", lines[0]);
    }

    static void testDeleteUnderscore() {
        // "my_var" カーソルを末尾(6)へ → "my_var" まとめて削除（全部単語文字）→ "" が残る
        ModalEditor e = makeInsert("my_var", 6);
        ctrlW(e);
        String[] lines = e.getText().split("\n", -1);
        assertEquals("underscore is word char", "", lines[0]);
    }

    // ── アサーション ────────────────────────────────────────────────────────────

    static void assertEquals(String name, String expected, String actual) {
        if (expected.equals(actual)) {
            System.out.println("  PASS " + name);
            passed++;
        } else {
            System.out.println("  FAIL " + name + " (expected=\"" + expected + "\", actual=\"" + actual + "\")");
            failed++;
        }
    }

    static void assertEquals(String name, int expected, int actual) {
        if (expected == actual) {
            System.out.println("  PASS " + name);
            passed++;
        } else {
            System.out.println("  FAIL " + name + " (expected=" + expected + ", actual=" + actual + ")");
            failed++;
        }
    }
}
