package dev.javatexteditor.analysis;

/**
 * 入力補完の1候補。
 *
 * <p>IntelliJ IDEA の Lookup 要素と同じ「表示テキストと挿入テキストは別物」という考え方を採る。
 * 例えばメソッド候補 {@code add} は、画面上は {@code add(int index, E element)} のように
 * シグネチャ付きで見えるが、実際にバッファへ入るのは {@code add()} であり、
 * カーソルは括弧の内側に置かれる。この3つ（表示名・付随情報・挿入結果）を1つの値にまとめる。
 *
 * @param label            マッチ対象かつ主表示となる名前（メソッドなら括弧を含まない名前）
 * @param kind             種別タグ。描画幅を揃えるため2〜3文字に限る
 *                         （"cls"=クラス / "mth"=メソッド / "fld"=フィールド / "var"=ローカル変数・引数 /
 *                          "kw"=キーワード / "wd"=単語索引由来）
 * @param tailText         label の直後に淡色で表示する補足（メソッドの引数リスト等）。無ければ空文字列
 * @param typeText         行の右端に表示する型情報（戻り値型・フィールド型・パッケージ）。無ければ空文字列
 * @param insertText       確定時にバッファへ挿入する文字列
 * @param caretBackOffset  挿入後にカーソルを何文字戻すか（引数ありメソッドの {@code ()} 内へ入るなら 1）
 * @param importFqn        確定時に import を挿入すべき FQN。不要なら null
 * @param origin           候補の出所。マッチ品質が同じ候補どうしの並び順（近接度）に使う
 */
public record CompletionItem(String label, String kind, String tailText, String typeText,
                             String insertText, int caretBackOffset, String importFqn,
                             Origin origin) {

    /**
     * 候補の出所。IntelliJ の「近い所で宣言されたものほど上」という並び順を再現するための序列。
     * 数値が小さいほど優先される。
     */
    public enum Origin {
        /** カーソル位置のスコープにあるローカル変数・引数。 */
        LOCAL(0),
        /** レシーバの型から解決したメンバー（{@code obj.} の後）。 */
        MEMBER(1),
        /** 編集中のファイル内で宣言されているシンボル。 */
        CURRENT_FILE(2),
        /** プロジェクト内の他ファイル由来。 */
        PROJECT(3),
        /** Java キーワード。 */
        KEYWORD(4),
        /** 単語索引（作業ディレクトリ配下の識別子）由来。 */
        WORD(5),
        /** JDK のクラス名索引由来。 */
        JDK_CLASS(6);

        private final int rank;

        Origin(int rank) {
            this.rank = rank;
        }

        /** 小さいほど上位に並べる。 */
        public int rank() {
            return rank;
        }
    }

    /** null を空文字列へ正規化する（描画側・挿入側で null チェックを不要にするため）。 */
    public CompletionItem {
        if (label == null) throw new IllegalArgumentException("label must not be null");
        kind = (kind != null) ? kind : "";
        tailText = (tailText != null) ? tailText : "";
        typeText = (typeText != null) ? typeText : "";
        insertText = (insertText != null) ? insertText : label;
        origin = (origin != null) ? origin : Origin.WORD;
    }

    /**
     * 付随情報を持たない素朴な候補（単語索引・JDK クラス名索引が作るもの）。
     * 挿入されるのは label そのままで、import もカーソル移動も伴わない。
     */
    public CompletionItem(String label, String kind) {
        this(label, kind, "", "", label, 0, null, Origin.WORD);
    }

    /** 出所だけを差し替えた同じ候補を返す（並び順の調整用）。 */
    public CompletionItem withOrigin(Origin newOrigin) {
        return new CompletionItem(label, kind, tailText, typeText,
            insertText, caretBackOffset, importFqn, newOrigin);
    }

    /** 確定時に import 文の挿入を伴うか。 */
    public boolean needsImport() {
        return importFqn != null && !importFqn.isEmpty();
    }
}
