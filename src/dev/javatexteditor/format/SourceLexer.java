package dev.javatexteditor.format;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * javax.tools / com.sun.source を一切使わない、状態遷移だけで動く軽量Lexer。
 * 文字列リテラル・テキストブロック・文字リテラル・コメントを読み飛ばしながら
 * 波括弧のネスト深さを追跡し、クラス直下（開き{直後、深さ1）の各メンバーの
 * [開始, 終了) インデックスだけを返す。ASTやトークン列など重い中間構造は一切構築しない
 * ため、返すのは int の範囲リストのみで、呼び出し元がその場で使い終えたら
 * 即座にGC対象になる（メソッド内ローカル変数のみで完結する設計）。
 */
final class SourceLexer {

    private SourceLexer() {}

    /** クラス本体1つ分の範囲と、その直下（レベル1）のメンバー境界のリスト。 */
    record BodyRegion(int openBrace, int closeBrace, List<int[]> members) {}

    /**
     * {@code searchFrom} 以降で最初に現れる「クラス本体の開き{」を探し、そこから対応する
     * 閉じ}までの間にあるレベル1のメンバーを切り出す。見つからない・波括弧が不均衡な場合はnull。
     *
     * <p>開き{の探索は、注釈引数の丸括弧の中（{@code @Foo(x={1,2,3})}）にある{を誤って
     * 本体の開始と誤認識しないよう、丸括弧の深さが0の時だけを対象にする。
     *
     * <p>各メンバーの境界判定でも同様に、配列初期化子（{@code int[] a = {1,2,3};}）の{を
     * メンバー本体の開始と誤認識しないよう、直前の非空白文字が {@code = , { ]} のいずれかの
     * 場合はそれを「本体を持たないブレース（配列初期化子等）」として扱い、対応する}が来ても
     * メンバー境界を確定させない（境界はセミコロンまで続ける）。
     */
    static BodyRegion sliceClassBody(String src, int searchFrom) {
        int n = src.length();
        int i = searchFrom;
        int parenDepth = 0;
        char lastSig = 0;

        int openBrace = -1;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"') { i = skipString(src, i); lastSig = '"'; continue; }
            if (c == '\'') { i = skipChar(src, i); lastSig = '\''; continue; }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') { i = skipLineComment(src, i); continue; }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') { i = skipBlockComment(src, i); continue; }
            if (c == '(') { parenDepth++; i++; lastSig = c; continue; }
            if (c == ')') { parenDepth = Math.max(0, parenDepth - 1); i++; lastSig = c; continue; }
            if (c == '{' && parenDepth == 0) { openBrace = i; break; }
            if (!Character.isWhitespace(c)) lastSig = c;
            i++;
        }
        if (openBrace < 0) return null;

        List<int[]> bounds = new ArrayList<>();
        ArrayDeque<Boolean> braceIsBody = new ArrayDeque<>();
        int depth = 1;
        parenDepth = 0;
        lastSig = 0;
        int memberStart = openBrace + 1;
        i = openBrace + 1;
        int closeBrace = -1;

        while (i < n) {
            char c = src.charAt(i);
            if (c == '"') { i = skipString(src, i); lastSig = '"'; continue; }
            if (c == '\'') { i = skipChar(src, i); lastSig = '\''; continue; }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') { i = skipLineComment(src, i); continue; }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') { i = skipBlockComment(src, i); continue; }
            if (c == '(') { parenDepth++; i++; lastSig = c; continue; }
            if (c == ')') { parenDepth = Math.max(0, parenDepth - 1); i++; lastSig = c; continue; }

            if (c == '{' && parenDepth == 0) {
                boolean isBody = !(lastSig == '=' || lastSig == ',' || lastSig == '{' || lastSig == ']');
                braceIsBody.push(isBody);
                depth++;
                i++;
                lastSig = '{';
                continue;
            }
            if (c == '}' && parenDepth == 0) {
                depth--;
                Boolean wasBody = braceIsBody.isEmpty() ? null : braceIsBody.pop();
                i++;
                lastSig = '}';
                if (depth == 0) {
                    closeBrace = i - 1;
                    break;
                }
                if (depth == 1 && (wasBody == null || wasBody)) {
                    bounds.add(new int[]{memberStart, i});
                    memberStart = i;
                }
                continue;
            }
            if (depth == 1 && parenDepth == 0 && c == ';') {
                bounds.add(new int[]{memberStart, i + 1});
                memberStart = i + 1;
                i++;
                lastSig = ';';
                continue;
            }
            if (!Character.isWhitespace(c)) lastSig = c;
            i++;
        }
        if (closeBrace < 0 || depth != 0) return null; // 波括弧が不均衡（構文エラー等）: 安全側で諦める
        return new BodyRegion(openBrace, closeBrace, bounds);
    }

    /** {@code from} が指す {@code "} または {@code """} を読み飛ばし、閉じ引用符の次の位置を返す。 */
    static int skipString(String src, int from) {
        int n = src.length();
        if (from + 2 < n && src.charAt(from + 1) == '"' && src.charAt(from + 2) == '"') {
            int close = src.indexOf("\"\"\"", from + 3);
            return close < 0 ? n : close + 3;
        }
        int i = from + 1;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"' || c == '\n') return i + (c == '"' ? 1 : 0);
            i++;
        }
        return n;
    }

    /** {@code from} が指す {@code '} を読み飛ばし、閉じ引用符の次の位置を返す。 */
    static int skipChar(String src, int from) {
        int n = src.length();
        int i = from + 1;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '\'' || c == '\n') return i + (c == '\'' ? 1 : 0);
            i++;
        }
        return n;
    }

    static int skipLineComment(String src, int from) {
        int nl = src.indexOf('\n', from);
        return nl < 0 ? src.length() : nl;
    }

    static int skipBlockComment(String src, int from) {
        int close = src.indexOf("*/", from + 2);
        return close < 0 ? src.length() : close + 2;
    }

    /** {@code from} 以降で、空白・行コメント・ブロックコメントを飛ばした最初の非空白位置を返す。 */
    static int skipGap(String src, int from, int limit) {
        int i = from;
        while (i < limit) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < limit && src.charAt(i + 1) == '/') { i = skipLineComment(src, i); continue; }
            if (c == '/' && i + 1 < limit && src.charAt(i + 1) == '*') { i = skipBlockComment(src, i); continue; }
            break;
        }
        return Math.min(i, limit);
    }

    /**
     * {@code [from, limit)} の範囲で、丸括弧の深さが0の位置にある最初の {@code {} または {@code ;}
     * のインデックスを返す（文字列・文字・コメントは読み飛ばす）。見つからなければ -1。
     * メンバー1件分のヘッダー（型・修飾子・名前・引数リスト）の終端を求めるのに使う。
     */
    static int findHeaderEnd(String src, int from, int limit) {
        int i = from;
        int parenDepth = 0;
        while (i < limit) {
            char c = src.charAt(i);
            if (c == '"') { i = skipString(src, i); continue; }
            if (c == '\'') { i = skipChar(src, i); continue; }
            if (c == '/' && i + 1 < limit && src.charAt(i + 1) == '/') { i = skipLineComment(src, i); continue; }
            if (c == '/' && i + 1 < limit && src.charAt(i + 1) == '*') { i = skipBlockComment(src, i); continue; }
            if (c == '(') { parenDepth++; i++; continue; }
            if (c == ')') { parenDepth = Math.max(0, parenDepth - 1); i++; continue; }
            if (parenDepth == 0 && (c == '{' || c == ';')) return i;
            i++;
        }
        return -1;
    }
}
