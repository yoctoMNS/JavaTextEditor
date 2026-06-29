package dev.javatexteditor.analysis;

/**
 * 入力補完の1候補。label は補完テキスト、kind は表示用の種別文字列（"cls"/"mth"/"fld"）。
 */
public record CompletionItem(String label, String kind) {}
