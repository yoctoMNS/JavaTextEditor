package dev.javatexteditor.ui;

/** 構文ハイライトの分類。Theme側の syntaxXxx フィールドと1対1で対応する。 */
public enum SyntaxKind {
    DEFAULT,
    KEYWORD,
    TYPE,
    STRING,
    COMMENT,
    NUMBER,
    PREPROCESSOR,
    SYMBOL,
    OPERATOR
}
