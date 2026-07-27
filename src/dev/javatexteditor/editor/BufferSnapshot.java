package dev.javatexteditor.editor;

/**
 * ある時点のバッファ内容・開いていたファイル・カーソル位置をまとめて写し取った値。
 *
 * <p>2箇所で使われる:
 * <ul>
 *   <li>{@link BufferHistory} — Ctrl+U / Ctrl+P でファイルパスを持たない疑似バッファ
 *       （{@code :enew}・{@code :tutor} 等）を行き来するための履歴</li>
 *   <li>{@code ModalEditor.lastJumpOrigin} — Shift+K で定義へ飛ぶ直前の位置を1件だけ覚えておき、
 *       Shift+J で戻るための復帰点</li>
 * </ul>
 */
record BufferSnapshot(String text, String filePath, int row, int col) {}
