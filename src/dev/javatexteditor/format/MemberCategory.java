package dev.javatexteditor.format;

/** メンバー1件の分類。並び順そのものは {@link DeclKind} ごとに {@code JavaMemberFormatter#tierOf} が決める。 */
enum MemberCategory {
    ENUM_CONSTANT,
    CONSTANT,           // interface の暗黙 public static final フィールド
    STATIC_FIELD,
    INSTANCE_FIELD,
    STATIC_INIT,
    INSTANCE_INIT,
    CONSTRUCTOR,
    ABSTRACT_METHOD,    // interface の本体なしメソッド
    DEFAULT_METHOD,     // interface の default メソッド
    STATIC_METHOD,      // interface / record の static メソッド
    INSTANCE_METHOD,    // 上記以外の通常メソッド（class/enum では static/instance を区別しない）
    OBJECT_OVERRIDE_METHOD, // class/enum の equals/hashCode/toString/clone 等
    NESTED_TYPE
}
