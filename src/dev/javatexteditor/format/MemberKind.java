package dev.javatexteditor.format;

/** 手組みLexerが検出したメンバー1件の種別。 */
enum MemberKind {
    FIELD,
    INIT_BLOCK,
    CONSTRUCTOR,
    METHOD,
    NESTED_TYPE,
    ENUM_CONSTANTS, // enum本体先頭の列挙子カンマ区切りブロック全体（内部では並び替えない）
    OTHER           // 分類できなかった場合の安全側フォールバック（元の位置を維持する）
}
