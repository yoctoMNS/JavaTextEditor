package dev.javatexteditor.completion2;

import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JWindow;
import javax.swing.event.CaretEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;
import java.awt.Point;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Objects;

/**
 * Alt+/ 単語補完のUI統合レイヤー。
 *
 * 補完ロジック（CompletionEngine/CompletionSession/CompletionCandidate/TokenScanner）は
 * Swingに依存しない純粋なJavaクラスであり、このクラスがそれらとJTextComponent/Documentを
 * 橋渡しする。将来Swing以外のUI（例: 別のテキストコンポーネント実装）に差し替える場合は、
 * このクラスとEditorKeyHandler/CompletionPopupModelのみを置き換えればよい。
 *
 * 1回のAlt+/巡回（候補の入れ替え）は {@link UndoManager} に対して1つのまとまった
 * UndoableEdit（CompoundEdit）として記録されるため、Ctrl+Zで候補入れ替え1回分が
 * まとめて取り消される。
 */
public final class CompletionController {

    private final JTextComponent textComponent;
    private final CompletionEngine engine;
    private final UndoManager undoManager;

    private final CompletionPopupModel popupModel = new CompletionPopupModel();
    private final JList<CompletionCandidate> popupList = new JList<>(popupModel);
    private final JWindow popupWindow;

    private CompletionSession session;
    private int insertedLength;
    private boolean programmaticMutation;

    public CompletionController(JTextComponent textComponent) {
        this(textComponent, new CompletionEngine(), null);
    }

    public CompletionController(JTextComponent textComponent, CompletionEngine engine, UndoManager undoManager) {
        this.textComponent = Objects.requireNonNull(textComponent, "textComponent");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.undoManager = undoManager;

        popupList.setFocusable(false);
        popupWindow = new JWindow();
        popupWindow.setFocusableWindowState(false);
        popupWindow.getContentPane().add(new JScrollPane(popupList));
        popupWindow.setFocusableWindowState(false);

        // カーソルが「自分の書き換えによるもの」以外の理由で動いたら、その時点の候補で確定する。
        textComponent.addCaretListener(this::onCaretEvent);
    }

    public boolean isActive() {
        return session != null;
    }

    /** Alt+/ : 補完開始、または次候補へ巡回。 */
    public void triggerOrAdvance() {
        if (session == null) {
            start();
        } else {
            cycle(true);
        }
    }

    /** Shift+Alt+/ : 補完開始、または前候補へ巡回。 */
    public void triggerOrRetreat() {
        if (session == null) {
            start();
        } else {
            cycle(false);
        }
    }

    /** Enter/Tab : 現在表示中の候補で確定し、文書には手を加えない。 */
    public void commit() {
        if (session == null) {
            return;
        }
        session = null;
        hidePopup();
    }

    /** Esc : 元のプレフィックスへ戻して取消。 */
    public void cancel() {
        if (session == null) {
            return;
        }
        replaceInsertedTextWith(session.originalPrefix());
        session = null;
        hidePopup();
    }

    private void start() {
        Document doc = textComponent.getDocument();
        int caret = textComponent.getCaretPosition();
        String fullText = readFullText(doc);
        int anchor = TokenScanner.findTokenStart(fullText, caret);
        String prefix = fullText.substring(anchor, caret);

        List<CompletionCandidate> candidates = engine.collectCandidates(fullText, caret, prefix);
        if (candidates.isEmpty()) {
            return;
        }

        session = new CompletionSession(anchor, prefix, candidates);
        insertedLength = prefix.length();
        replaceInsertedTextWith(session.advance());
        showPopup();
    }

    private void cycle(boolean forward) {
        String next = forward ? session.advance() : session.retreat();
        replaceInsertedTextWith(next);
        updatePopupSelection();
    }

    private void replaceInsertedTextWith(String newText) {
        int start = session.anchorOffset();
        int end = start + insertedLength;
        try {
            mutate(start, end, newText);
        } catch (BadLocationException ex) {
            throw new IllegalStateException("補完テキストの書き換えに失敗しました", ex);
        }
        insertedLength = newText.length();
    }

    private void mutate(int start, int end, String text) throws BadLocationException {
        programmaticMutation = true;
        try {
            Document doc = textComponent.getDocument();
            withCompoundUndo(() -> {
                try {
                    if (end > start) {
                        doc.remove(start, end - start);
                    }
                    if (!text.isEmpty()) {
                        doc.insertString(start, text, null);
                    }
                } catch (BadLocationException ex) {
                    throw new RuntimeException(ex);
                }
            });
            textComponent.setCaretPosition(start + text.length());
        } catch (RuntimeException wrapped) {
            if (wrapped.getCause() instanceof BadLocationException ble) {
                throw ble;
            }
            throw wrapped;
        } finally {
            programmaticMutation = false;
        }
    }

    private void withCompoundUndo(Runnable mutation) {
        if (undoManager == null) {
            mutation.run();
            return;
        }
        Document doc = textComponent.getDocument();
        doc.removeUndoableEditListener(undoManager);
        CompoundEdit compound = new CompoundEdit();
        UndoableEditListener collector = e -> compound.addEdit(e.getEdit());
        doc.addUndoableEditListener(collector);
        try {
            mutation.run();
        } finally {
            doc.removeUndoableEditListener(collector);
            doc.addUndoableEditListener(undoManager);
            compound.end();
            if (compound.canUndo()) {
                undoManager.addEdit(compound);
            }
        }
    }

    private void onCaretEvent(CaretEvent e) {
        if (programmaticMutation || session == null) {
            return;
        }
        // 自分の書き換え以外の理由でカーソルが動いた（矢印キー・マウスクリック・別文字入力など） -> 確定
        commit();
    }

    private void showPopup() {
        updatePopupSelection();
        try {
            Rectangle2D caretRect = textComponent.modelToView2D(session.anchorOffset());
            Point base = textComponent.getLocationOnScreen();
            int x = base.x + (int) caretRect.getX();
            int y = base.y + (int) caretRect.getY() + (int) Math.ceil(caretRect.getHeight());
            popupWindow.setLocation(x, y);
        } catch (BadLocationException ignored) {
            // 位置計算に失敗しても補完自体は継続する。
        }
        popupWindow.pack();
        popupWindow.setVisible(true);
    }

    private void updatePopupSelection() {
        if (session == null) {
            return;
        }
        popupModel.update(session.candidates(), session.currentIndex());
        popupList.setSelectedIndex(session.currentIndex());
        if (session.currentIndex() >= 0) {
            popupList.ensureIndexIsVisible(session.currentIndex());
        }
    }

    private void hidePopup() {
        popupWindow.setVisible(false);
        popupModel.clear();
    }

    private static String readFullText(Document doc) {
        try {
            return doc.getText(0, doc.getLength());
        } catch (BadLocationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
