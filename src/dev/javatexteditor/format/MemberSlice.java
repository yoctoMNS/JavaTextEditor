package dev.javatexteditor.format;

/**
 * 元ソース文字列上の1メンバー分の範囲 [start, end) と、分類に使う軽量メタデータのみを保持する。
 * 本文テキストそのものは保持しない（呼び出し元が {@code source.substring(start, end)} で
 * 都度取り出す）ため、インスタンス自体は数個のプリミティブと短い文字列だけの軽量オブジェクトになる。
 */
record MemberSlice(
    int start,
    int end,
    MemberKind kind,
    String name,           // メソッド/コンストラクタ名。それ以外はnull
    boolean isStatic,
    int visibilityRank,    // 0=public 1=protected 2=package-private 3=private
    int paramCount,
    boolean isCompactCtor  // recordのcompactコンストラクタか
) {}
