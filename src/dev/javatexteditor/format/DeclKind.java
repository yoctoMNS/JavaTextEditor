package dev.javatexteditor.format;

import com.sun.source.tree.Tree;

/**
 * メンバー並び替えの対象となる型宣言の種別。
 * {@link com.sun.source.tree.ClassTree#getKind()} を CLASS/INTERFACE/ENUM/RECORD に正規化する。
 * アノテーション型（{@code @interface}）は仕様に規定が無いため OTHER として並び替え対象外にする。
 */
enum DeclKind {
    CLASS, INTERFACE, ENUM, RECORD, OTHER;

    static DeclKind of(Tree.Kind treeKind) {
        return switch (treeKind) {
            case CLASS -> CLASS;
            case INTERFACE -> INTERFACE;
            case ENUM -> ENUM;
            case RECORD -> RECORD;
            default -> OTHER;
        };
    }
}
