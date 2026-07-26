package dev.javatexteditor.ui;

/** 行内の [start, end) 区間が kind に分類されることを表す。end は exclusive。 */
public record SyntaxToken(int start, int end, SyntaxKind kind) {
}
