package dev.javatexteditor.editor;

import dev.javatexteditor.analysis.AutoImportHandler;
import dev.javatexteditor.analysis.CompileDiagnostic;
import dev.javatexteditor.analysis.JdkClassIndex;
import dev.javatexteditor.analysis.JdkJavadocReader;
import dev.javatexteditor.analysis.JdkTypeInfo;
import dev.javatexteditor.analysis.OpenjdkSourceTracer;
import dev.javatexteditor.buffer.PieceTable;
import dev.javatexteditor.buffer.UndoablePieceTable;
import dev.javatexteditor.refactor.RenameRefactorer;
import dev.javatexteditor.refactor.RenameResult;
import dev.javatexteditor.search.ProjectSearcher;
import dev.javatexteditor.search.SearchResult;
import dev.javatexteditor.ui.EditorCanvas;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.PatternSyntaxException;

/**
 * NORMAL / INSERT / COMMAND / VISUAL / VISUAL_LINE の5モードを管理し、
 * カーソル位置管理と PieceTable / EditorCanvas の橋渡しを担うクラス。
 *
 * キー入力の処理は processKey(keyCode, keyChar, modifiers) で受け取る。
 * keyCode / modifiers は java.awt.event.KeyEvent の定数を使用する。
 */
public class ModalEditor {

    private enum Mode { NORMAL, INSERT, COMMAND, VISUAL, VISUAL_LINE }

    private UndoablePieceTable buffer;
    private final EditorCanvas canvas; // null の場合はGUIなし（テスト用）
    private final KeymapRegistry keymap = new KeymapRegistry();
    private Mode mode = Mode.NORMAL;
    // INSERT → NORMAL 復帰時に呼ばれるコールバック（バックグラウンドコンパイル等）
    private Runnable onReturnToNormal = null;
    // ファイル保存成功時に呼ばれるコールバック（バックグラウンドコンパイル等）
    private Runnable onSave = null;
    private int cursorRow = 0;
    private int cursorCol = 0;
    private int anchorRow = 0;
    private int anchorCol = 0;
    private String yankRegister = "";
    private String yankType = "char"; // "char" or "line"
    private String pendingSequence = ""; // yy / dd / SPC+g+g 等の多打鍵シーケンス管理
    private final StringBuilder commandBuffer = new StringBuilder();
    private String currentFilePath = null;
    private String statusMessage = "";
    private Runnable exitCallback = () -> System.exit(0);
    private Runnable closeBlockedCallback = null; // 最後の1ペインで :q を拒否するとき呼ぶ
    private Runnable splitHorizontalCallback = null; // sv: 左右分割
    private Runnable splitVerticalCallback   = null; // ss: 上下分割
    private Runnable movePanePrevCallback    = null; // sh/sk: 前のペインへ
    private Runnable movePaneNextCallback    = null; // sl/sj: 次のペインへ
    // JDK API ナビゲーション用インデックス（バックグラウンドで構築）
    private JdkClassIndex jdkIndex = null;
    private final JdkJavadocReader javadocReader = new JdkJavadocReader();
    private final OpenjdkSourceTracer sourceTracer = new OpenjdkSourceTracer();
    // auto-import: 複数候補が見つかった場合に選択待ちとなる状態
    private AutoImportHandler autoImportHandler = null;
    // 選択待ち候補: 単純名 → FQN 候補リストのペアを順番通りに保持
    private final List<Map.Entry<String, List<String>>> pendingImports = new ArrayList<>();
    private int pendingImportIdx = 0; // 現在選択中の単純名インデックス
    // project-wide-search: grep 結果バッファ
    private final ProjectSearcher projectSearcher = new ProjectSearcher();
    private List<SearchResult> grepResults = null; // null = 通常バッファ
    private final RenameRefactorer renameRefactorer = new RenameRefactorer();

    public ModalEditor(String initialText) {
        this.buffer = new UndoablePieceTable(initialText);
        this.canvas = null;
    }

    public ModalEditor(String initialText, EditorCanvas canvas) {
        this.buffer = new UndoablePieceTable(initialText);
        this.canvas = canvas;
        syncCanvas();
    }

    public ModalEditor(String initialText, String filePath, EditorCanvas canvas) {
        this.buffer = new UndoablePieceTable(initialText);
        this.currentFilePath = filePath;
        this.canvas = canvas;
        syncCanvas();
    }

    public void setExitCallback(Runnable callback) {
        this.exitCallback = callback;
    }

    public void setCloseBlockedCallback(Runnable callback) {
        this.closeBlockedCallback = callback;
    }

    public void setSplitHorizontalCallback(Runnable callback) {
        this.splitHorizontalCallback = callback;
    }

    public void setSplitVerticalCallback(Runnable callback) {
        this.splitVerticalCallback = callback;
    }

    public void setMovePanePrevCallback(Runnable callback) {
        this.movePanePrevCallback = callback;
    }

    public void setMovePaneNextCallback(Runnable callback) {
        this.movePaneNextCallback = callback;
    }

    /**
     * INSERT モードから NORMAL モードに戻ったときに呼ばれるコールバックを登録する。
     * バックグラウンドコンパイルのトリガーとして使用する。
     */
    public void setOnReturnToNormal(Runnable callback) {
        this.onReturnToNormal = callback;
    }

    /**
     * ファイル保存（:w / :wq）が成功したときに呼ばれるコールバックを登録する。
     * バックグラウンドコンパイルのトリガーとして使用する。
     */
    public void setOnSave(Runnable callback) {
        this.onSave = callback;
    }

    /** 現在開いているファイルのパスを返す（未設定の場合は null）。 */
    public String getCurrentFilePath() { return currentFilePath; }

    public void processKey(int keyCode, char keyChar, int modifiers) {
        // 最初のキー操作でスプラッシュ画面を消去する
        if (canvas != null && canvas.isShowSplash()) {
            canvas.setShowSplash(false);
        }
        if ((mode == Mode.VISUAL || mode == Mode.VISUAL_LINE) && keyCode == KeyEvent.VK_ESCAPE) {
            mode = Mode.NORMAL;
            pendingSequence = "";
            syncCanvas();
            return;
        }
        switch (mode) {
            case INSERT      -> processInsertKey(keyCode, keyChar, modifiers);
            case COMMAND     -> processCommandKey(keyCode, keyChar);
            case NORMAL      -> processNormalKey(keyCode, keyChar, modifiers);
            case VISUAL      -> processVisualKey(keyCode, keyChar, modifiers);
            case VISUAL_LINE -> processVisualLineKey(keyCode, keyChar, modifiers);
        }
        syncCanvas();
    }

    // -------------------------------------------------------------------------
    // NORMALモード処理
    // -------------------------------------------------------------------------

    private void processNormalKey(int keyCode, char keyChar, int modifiers) {
        // import 選択待ち状態の処理
        if (!pendingImports.isEmpty()) {
            if (keyCode == KeyEvent.VK_ESCAPE) {
                // Esc: このシンボルをスキップして次へ
                advanceImportPrompt();
            } else if (keyChar >= '1' && keyChar <= '9') {
                int choice = keyChar - '1';
                List<String> fqns = pendingImports.get(pendingImportIdx).getValue();
                if (choice < fqns.size()) {
                    autoImportHandler.applyImport(fqns.get(choice), buffer);
                }
                advanceImportPrompt();
            }
            syncCanvas();
            return;
        }

        // grep 結果バッファ: Enter でその行の結果ファイルへジャンプ
        if (grepResults != null && keyCode == KeyEvent.VK_ENTER) {
            jumpToGrepResult();
            return;
        }

        // 2打鍵シーケンス（yy / dd）の処理
        if (!pendingSequence.isEmpty()) {
            String seq = pendingSequence;
            pendingSequence = "";
            statusMessage = "";
            char prev = seq.charAt(0);
            if (prev == 'y' && matches(keyCode, keyChar, KeyEvent.VK_Y, 'y')) { yankCurrentLine(); return; }
            if (prev == 'd' && matches(keyCode, keyChar, KeyEvent.VK_D, 'd')) { deleteCurrentLine(); return; }
            if (prev == 'g' && matches(keyCode, keyChar, KeyEvent.VK_G, 'g')) { moveFileStart(); return; }
            if (prev == 's' && matches(keyCode, keyChar, KeyEvent.VK_V, 'v')) {
                if (splitHorizontalCallback != null) splitHorizontalCallback.run();
                return;
            }
            if (prev == 's' && matches(keyCode, keyChar, KeyEvent.VK_S, 's')) {
                if (splitVerticalCallback != null) splitVerticalCallback.run();
                return;
            }
            if (prev == 's' && (matches(keyCode, keyChar, KeyEvent.VK_H, 'h') || matches(keyCode, keyChar, KeyEvent.VK_K, 'k'))) {
                if (movePanePrevCallback != null) movePanePrevCallback.run();
                return;
            }
            if (prev == 's' && (matches(keyCode, keyChar, KeyEvent.VK_L, 'l') || matches(keyCode, keyChar, KeyEvent.VK_J, 'j'))) {
                if (movePaneNextCallback != null) movePaneNextCallback.run();
                return;
            }
            // SPC+g+? シーケンス（SPC+g の2打鍵の後）
            if (seq.equals(" g")) {
                if (matches(keyCode, keyChar, KeyEvent.VK_G, 'g')) { generateGetter(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_S, 's')) { generateSetter(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_D, 'd')) { generateGetterAndSetter(); return; }
                // マッチしない場合は通常処理へ
            } else if (seq.equals(" i")) {
                // SPC+i+? シーケンス（import 操作）
                if (matches(keyCode, keyChar, KeyEvent.VK_O, 'o')) { organizeImports(); return; }
                // マッチしない場合は通常処理へ
            } else if (prev == ' ') {
                // SPC キー: 1打鍵目
                if (matches(keyCode, keyChar, KeyEvent.VK_H, 'h')) { moveLineStartNonBlank(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_L, 'l')) { moveLineEnd(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_K, 'k')) { moveFileStart(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_J, 'j')) { moveFileEnd(); return; }
                if (matches(keyCode, keyChar, KeyEvent.VK_G, 'g')) {
                    pendingSequence = " g";
                    statusMessage = "SPC-g-";
                    return;
                }
                if (matches(keyCode, keyChar, KeyEvent.VK_I, 'i')) {
                    pendingSequence = " i";
                    statusMessage = "SPC-i-";
                    return;
                }
            }
            // シーケンスが成立しなかった場合は落下してキーを通常処理
        }

        String action = keymap.resolve(KeymapRegistry.Mode.NORMAL, keyCode, keyChar, modifiers);
        if (action == null) return;

        Runnable custom = keymap.getCustomAction(action);
        if (custom != null) { custom.run(); return; }

        switch (action) {
            case "cursor.left" -> moveCursor(0, -1);
            case "cursor.right" -> moveCursor(0, 1);
            case "cursor.down" -> moveCursor(1, 0);
            case "cursor.up" -> moveCursor(-1, 0);
            case "enter.insert" -> {
                mode = Mode.INSERT;
                statusMessage = "";
            }
            case "enter.insert.after" -> {
                String[] lines = getLines();
                int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
                cursorCol = Math.min(cursorCol + 1, lineLen);
                mode = Mode.INSERT;
                statusMessage = "";
            }
            case "enter.insert.newline" -> {
                String[] lines = getLines();
                int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
                int endOfLine = offsetAt(cursorRow, lineLen);
                buffer.insert(endOfLine, "\n");
                cursorRow++;
                cursorCol = 0;
                mode = Mode.INSERT;
                statusMessage = "";
            }
            case "enter.command" -> {
                commandBuffer.setLength(0);
                statusMessage = "";
                mode = Mode.COMMAND;
            }
            case "undo" -> {
                buffer.undo();
                clampCursorAfterUndoRedo();
            }
            case "redo" -> {
                buffer.redo();
                clampCursorAfterUndoRedo();
            }
            case "enter.visual" -> {
                anchorRow = cursorRow;
                anchorCol = cursorCol;
                mode = Mode.VISUAL;
            }
            case "enter.visual.line" -> {
                anchorRow = cursorRow;
                mode = Mode.VISUAL_LINE;
            }
            case "delete.char" -> deleteCharAtCursor();
            case "paste.after" -> pasteAfter();
            case "paste.before" -> pasteBefore();
            case "yank.pending" -> pendingSequence = "y";
            case "delete.pending" -> pendingSequence = "d";
            case "goto.pending"   -> { pendingSequence = "g"; statusMessage = "g-"; }
            case "split.pending"  -> { pendingSequence = "s"; statusMessage = "s-"; }
            case "leader.pending" -> { pendingSequence = " "; statusMessage = "SPC-"; }
            case "line.swap.down" -> swapLineDown();
            case "line.swap.up"   -> swapLineUp();
            case "word.forward"  -> moveWordForward();
            case "word.backward" -> moveWordBackward();
            case "word.end"      -> moveWordEnd();
            case "line.start"          -> moveLineStart();
            case "line.start.nonblank" -> moveLineStartNonBlank();
            case "line.end"            -> moveLineEnd();
            case "file.start"          -> moveFileStart();
            case "file.end"            -> moveFileEnd();
            case "jdk.doc" -> lookupJdkDoc();
            case "organize.imports" -> organizeImports();
        }
    }

    // -------------------------------------------------------------------------
    // INSERTモード処理
    // -------------------------------------------------------------------------

    private void processInsertKey(int keyCode, char keyChar, int modifiers) {
        String action = keymap.resolve(KeymapRegistry.Mode.INSERT, keyCode, keyChar, modifiers);

        if (action != null) {
            Runnable custom = keymap.getCustomAction(action);
            if (custom != null) {
                custom.run();
            } else {
                switch (action) {
                    case "enter.normal" -> {
                        mode = Mode.NORMAL;
                        clampCursorForNormal();
                        if (onReturnToNormal != null) onReturnToNormal.run();
                    }
                    case "delete.before" -> handleBackspace();
                    case "insert.newline" -> insertNewlineWithIndent();
                    case "insert.tab" -> insertTab();
                    case "save.from.insert" -> {
                        mode = Mode.NORMAL;
                        clampCursorForNormal();
                        if (onReturnToNormal != null) onReturnToNormal.run();
                        saveToFile(currentFilePath);
                    }
                    case "delete.next" -> {
                        String[] _lines = getLines();
                        int _lineLen = cursorRow < _lines.length ? _lines[cursorRow].length() : 0;
                        if (cursorCol < _lineLen) {
                            buffer.delete(offsetOfCursor(), 1);
                        }
                    }
                    case "delete.to.eol" -> {
                        String[] _lines2 = getLines();
                        String _line = cursorRow < _lines2.length ? _lines2[cursorRow] : "";
                        int _toDelete = _line.length() - cursorCol;
                        if (_toDelete > 0) {
                            buffer.delete(offsetOfCursor(), _toDelete);
                        }
                    }
                    case "cursor.right"  -> moveCursor(0, 1);
                    case "cursor.left"   -> moveCursor(0, -1);
                    case "cursor.down"   -> moveCursor(1, 0);
                    case "cursor.up"     -> moveCursor(-1, 0);
                    case "word.forward"  -> moveWordForward();
                    case "word.backward" -> moveWordBackward();
                    case "word.end"      -> moveWordEnd();
                    case "line.start"    -> moveLineStart();
                    case "line.end"      -> moveLineEnd();
                    case "file.start"    -> moveFileStart();
                    case "file.end"      -> moveFileEnd();
                    case "organize.imports" -> organizeImports();
                }
            }
        } else if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ') {
            if (keyChar == '}') {
                insertCloseBrace();
            } else {
                buffer.insert(offsetOfCursor(), String.valueOf(keyChar));
                cursorCol++;
            }
        }
    }

    private static final java.util.Set<Character> CLOSING_PAIRS =
        java.util.Set.of(')', ']', '}', '"', '\'', '>');

    private void insertTab() {
        String[] lines = getLines();
        String line = cursorRow < lines.length ? lines[cursorRow] : "";
        if (cursorCol < line.length() && CLOSING_PAIRS.contains(line.charAt(cursorCol))) {
            cursorCol++;
        } else {
            buffer.insert(offsetOfCursor(), "    ");
            cursorCol += 4;
        }
    }

    private void swapLineDown() {
        String[] lines = getLines();
        if (cursorRow >= lines.length - 1) return;
        int r = cursorRow;
        String lineA = lines[r];
        String lineB = lines[r + 1];
        // Replace lines r and r+1 atomically
        int startA = offsetAt(r, 0);
        int lenAB = lineA.length() + 1 + lineB.length();
        buffer.delete(startA, lenAB);
        buffer.insert(startA, lineB + "\n" + lineA);
        cursorRow++;
    }

    private void swapLineUp() {
        String[] lines = getLines();
        if (cursorRow == 0) return;
        int r = cursorRow;
        String lineA = lines[r - 1];
        String lineB = lines[r];
        int startA = offsetAt(r - 1, 0);
        int lenAB = lineA.length() + 1 + lineB.length();
        buffer.delete(startA, lenAB);
        buffer.insert(startA, lineB + "\n" + lineA);
        cursorRow--;
    }

    private void insertCloseBrace() {
        String[] lines = getLines();
        String currentLine = cursorRow < lines.length ? lines[cursorRow] : "";
        // 現在行がインデントのみ（空白だけ）の場合、インデントを1レベル下げてから } を挿入
        if (!currentLine.isEmpty() && currentLine.chars().allMatch(c -> c == ' ' || c == '\t')) {
            int removeLen = Math.min(4, cursorCol);
            if (removeLen > 0) {
                int lineStart = offsetAt(cursorRow, 0);
                buffer.delete(lineStart, removeLen);
                cursorCol -= removeLen;
            }
        }
        buffer.insert(offsetOfCursor(), "}");
        cursorCol++;
    }

    private void insertNewlineWithIndent() {
        String[] lines = getLines();
        String currentLine = cursorRow < lines.length ? lines[cursorRow] : "";

        // 現在行の先頭インデント（スペース・タブ）を取得
        int indentLen = 0;
        while (indentLen < currentLine.length()
                && (currentLine.charAt(indentLen) == ' ' || currentLine.charAt(indentLen) == '\t')) {
            indentLen++;
        }
        String indent = currentLine.substring(0, indentLen);

        // カーソル直前の非空白文字が '{' なら追加インデント
        String beforeCursor = currentLine.substring(0, Math.min(cursorCol, currentLine.length())).stripTrailing();
        if (!beforeCursor.isEmpty() && beforeCursor.charAt(beforeCursor.length() - 1) == '{') {
            indent += "    ";
        }

        buffer.insert(offsetOfCursor(), "\n" + indent);
        cursorRow++;
        cursorCol = indent.length();
    }

    private void handleBackspace() {
        if (cursorCol > 0) {
            buffer.delete(offsetOfCursor() - 1, 1);
            cursorCol--;
        } else if (cursorRow > 0) {
            String[] linesBefore = getLines();
            int prevLineLen = linesBefore[cursorRow - 1].length();
            buffer.delete(offsetOfCursor() - 1, 1);
            cursorRow--;
            cursorCol = prevLineLen;
        }
    }

    // -------------------------------------------------------------------------
    // COMMMANDモード処理
    // -------------------------------------------------------------------------

    private void processCommandKey(int keyCode, char keyChar) {
        if (keyCode == KeyEvent.VK_ESCAPE) {
            commandBuffer.setLength(0);
            mode = Mode.NORMAL;

        } else if (keyCode == KeyEvent.VK_BACK_SPACE) {
            if (commandBuffer.length() > 0) {
                commandBuffer.deleteCharAt(commandBuffer.length() - 1);
            }

        } else if (keyCode == KeyEvent.VK_ENTER) {
            executeCommand(commandBuffer.toString());
            commandBuffer.setLength(0);
            mode = Mode.NORMAL;

        } else if (keyChar != KeyEvent.CHAR_UNDEFINED && keyChar >= ' ') {
            commandBuffer.append(keyChar);
        }
    }

    private void executeCommand(String cmd) {
        if (cmd.equals("w")) {
            saveToFile(currentFilePath);
        } else if (cmd.startsWith("w ")) {
            String path = cmd.substring(2).trim();
            if (saveToFile(path)) {
                currentFilePath = path;
            }
        } else if (cmd.startsWith("e ")) {
            String path = cmd.substring(2).trim();
            loadFromFile(path);
        } else if (cmd.startsWith("grep ")) {
            String pattern = cmd.substring(5).trim();
            executeGrep(pattern);
        } else if (cmd.startsWith("rename ")) {
            String args = cmd.substring(7).trim();
            executeRename(args);
        } else if (cmd.equals("oi") || cmd.equals("organize-imports")) {
            organizeImports();
        } else if (cmd.startsWith("remove-import ")) {
            String fqn = cmd.substring("remove-import ".length()).trim();
            executeRemoveImport(fqn);
        } else if (cmd.equals("sp") || cmd.equals("split")) {
            if (splitVerticalCallback != null) splitVerticalCallback.run();
        } else if (cmd.equals("vs") || cmd.equals("vsplit") || cmd.equals("vsp")) {
            if (splitHorizontalCallback != null) splitHorizontalCallback.run();
        } else if (cmd.equals("q")) {
            if (closeBlockedCallback != null) {
                closeBlockedCallback.run();
            } else {
                exitCallback.run();
            }
        } else if (cmd.equals("wq")) {
            if (closeBlockedCallback != null) {
                closeBlockedCallback.run();
            } else if (saveToFile(currentFilePath)) {
                exitCallback.run();
            }
        } else {
            statusMessage = "E: unknown command '" + cmd + "'";
        }
    }

    private boolean saveToFile(String path) {
        if (path == null || path.isEmpty()) {
            statusMessage = "E: no file name";
            return false;
        }
        try {
            Files.writeString(Path.of(path), buffer.getText());
            statusMessage = "\"" + path + "\" written";
            if (onSave != null) onSave.run();
            return true;
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
            return false;
        }
    }

    private void loadFromFile(String path) {
        try {
            String content = Files.readString(Path.of(path)).replace("\r\n", "\n");
            buffer = new UndoablePieceTable(content);
            currentFilePath = path;
            cursorRow = 0;
            cursorCol = 0;
            grepResults = null;
            statusMessage = "\"" + path + "\" opened";
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
        }
    }

    private void executeGrep(String pattern) {
        if (pattern.isEmpty()) {
            statusMessage = "E: no pattern";
            return;
        }
        Path baseDir = Path.of(System.getProperty("user.dir"));
        List<SearchResult> results;
        try {
            results = projectSearcher.search(baseDir, pattern);
        } catch (java.util.regex.PatternSyntaxException e) {
            statusMessage = "E: bad pattern: " + e.getDescription();
            return;
        }
        if (results.isEmpty()) {
            statusMessage = "grep: no matches for /" + pattern + "/";
            return;
        }

        // 結果をバッファに読み込む
        StringBuilder sb = new StringBuilder();
        sb.append("*grep* /").append(pattern).append("/ — ").append(results.size()).append(" match(es)\n");
        for (SearchResult r : results) {
            sb.append(r.toDisplayLine()).append("\n");
        }
        grepResults = results;
        buffer = new UndoablePieceTable(sb.toString());
        currentFilePath = null;
        cursorRow = 0;
        cursorCol = 0;
        statusMessage = "grep: " + results.size() + " match(es) — Enter to jump";
    }

    /**
     * :rename <oldName> <newName> — プロジェクト全体で識別子を一括リネームする。
     * 結果は *rename* 疑似バッファに表示する。
     */
    private void executeRename(String args) {
        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            statusMessage = "E: usage: rename <oldName> <newName>";
            return;
        }
        String oldName = parts[0];
        String newName = parts[1];

        Path baseDir = Path.of(System.getProperty("user.dir"));
        List<RenameResult> results;
        try {
            results = renameRefactorer.rename(baseDir, oldName, newName);
        } catch (IllegalArgumentException e) {
            statusMessage = "E: " + e.getMessage();
            return;
        }

        if (results.isEmpty()) {
            statusMessage = "rename: no occurrences of '" + oldName + "' found";
            return;
        }

        String displayText = RenameRefactorer.buildDisplayText(oldName, newName, results);
        buffer = new UndoablePieceTable(displayText);
        currentFilePath = null;
        grepResults = null;
        cursorRow = 0;
        cursorCol = 0;

        int totalReplacements = results.stream().mapToInt(RenameResult::replacementCount).sum();
        long errorCount = results.stream().filter(r -> !r.success()).count();
        if (errorCount > 0) {
            statusMessage = "rename: " + totalReplacements + " replacement(s) in "
                + results.size() + " file(s), " + errorCount + " error(s)";
        } else {
            statusMessage = "rename: " + totalReplacements + " replacement(s) in "
                + results.size() + " file(s)";
        }
    }

    /**
     * grep 結果バッファ内でカーソルがある行の結果ファイルを開き、該当行に移動する。
     * cursorRow==0 はヘッダ行なのでジャンプ対象外。
     */
    private void jumpToGrepResult() {
        if (grepResults == null) return;
        // 行0はヘッダ、行1以降が結果
        int resultIdx = cursorRow - 1;
        if (resultIdx < 0 || resultIdx >= grepResults.size()) {
            statusMessage = "E: no result at this line";
            return;
        }
        SearchResult r = grepResults.get(resultIdx);
        Path target = Path.of(System.getProperty("user.dir")).resolve(r.filePath());
        try {
            String content = Files.readString(target).replace("\r\n", "\n");
            buffer = new UndoablePieceTable(content);
            currentFilePath = target.toString();
            grepResults = null;
            // 目的の行へジャンプ（1-indexed → 0-indexed）
            cursorRow = Math.max(0, r.lineNumber() - 1);
            cursorCol = 0;
            statusMessage = "\"" + r.filePath() + "\" line " + r.lineNumber();
        } catch (IOException e) {
            statusMessage = "E: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // VISUALモード処理（文字単位）
    // -------------------------------------------------------------------------

    private void processVisualKey(int keyCode, char keyChar, int modifiers) {
        String action = keymap.resolve(KeymapRegistry.Mode.VISUAL, keyCode, keyChar, modifiers);
        if (action == null) return;

        Runnable custom = keymap.getCustomAction(action);
        if (custom != null) { custom.run(); return; }

        switch (action) {
            case "cursor.left"   -> moveCursor(0, -1);
            case "cursor.right"  -> moveCursor(0, 1);
            case "cursor.down"   -> moveCursor(1, 0);
            case "cursor.up"     -> moveCursor(-1, 0);
            case "word.forward"        -> moveWordForward();
            case "word.backward"       -> moveWordBackward();
            case "word.end"            -> moveWordEnd();
            case "line.start"          -> moveLineStart();
            case "line.start.nonblank" -> moveLineStartNonBlank();
            case "line.end"            -> moveLineEnd();
            case "file.end"            -> moveFileEnd();
            case "yank" -> {
                yankRegister = getSelectedText();
                yankType = "char";
                // Vim 仕様: y 後はカーソルを選択開始位置に戻す
                int startOffset = Math.min(offsetAt(anchorRow, anchorCol), offsetOfCursor());
                moveCursorToOffset(startOffset);
                mode = Mode.NORMAL;
            }
            case "delete" -> {
                yankRegister = getSelectedText();
                yankType = "char";
                deleteSelected();
                mode = Mode.NORMAL;
                clampCursorForNormal();
            }
        }
    }

    // -------------------------------------------------------------------------
    // VISUAL LINEモード処理（行単位）
    // -------------------------------------------------------------------------

    private void processVisualLineKey(int keyCode, char keyChar, int modifiers) {
        String action = keymap.resolve(KeymapRegistry.Mode.VISUAL_LINE, keyCode, keyChar, modifiers);
        if (action == null) return;

        Runnable custom = keymap.getCustomAction(action);
        if (custom != null) { custom.run(); return; }

        switch (action) {
            case "cursor.left"  -> moveCursor(0, -1);
            case "cursor.right" -> moveCursor(0, 1);
            case "cursor.down"  -> moveCursor(1, 0);
            case "cursor.up"    -> moveCursor(-1, 0);
            case "file.end"     -> moveFileEnd();
            case "yank" -> {
                int r1 = Math.min(anchorRow, cursorRow);
                int r2 = Math.max(anchorRow, cursorRow);
                yankRegister = buildLineRangeText(r1, r2);
                yankType = "line";
                cursorRow = r1;
                cursorCol = 0;
                mode = Mode.NORMAL;
            }
            case "delete" -> {
                int r1 = Math.min(anchorRow, cursorRow);
                int r2 = Math.max(anchorRow, cursorRow);
                yankRegister = buildLineRangeText(r1, r2);
                yankType = "line";
                deleteLineRange(r1, r2);
                mode = Mode.NORMAL;
            }
        }
    }

    // -------------------------------------------------------------------------
    // カーソル移動
    // -------------------------------------------------------------------------

    /** keyCode または keyChar のどちらかが期待値と一致すれば true。 */
    private static boolean matches(int keyCode, char keyChar, int expectedCode, char expectedChar) {
        if (keyCode != KeyEvent.VK_UNDEFINED && keyCode == expectedCode) return true;
        return keyChar != KeyEvent.CHAR_UNDEFINED && keyChar == expectedChar;
    }

    private void moveCursor(int dRow, int dCol) {
        String[] lines = getLines();
        boolean isInsert = (mode == Mode.INSERT);
        if (dRow != 0) {
            int newRow = Math.max(0, Math.min(cursorRow + dRow, lines.length - 1));
            int newLineLen = newRow < lines.length ? lines[newRow].length() : 0;
            int maxCol = isInsert ? newLineLen : Math.max(0, newLineLen - 1);
            cursorRow = newRow;
            cursorCol = Math.min(cursorCol, maxCol);
        } else {
            int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
            int maxCol = isInsert ? lineLen : Math.max(0, lineLen - 1);
            cursorCol = Math.max(0, Math.min(cursorCol + dCol, maxCol));
        }
    }

    private void clampCursorForNormal() {
        String[] lines = getLines();
        int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
        if (lineLen > 0) {
            cursorCol = Math.min(cursorCol, lineLen - 1);
        } else {
            cursorCol = 0;
        }
    }

    private void clampCursorAfterUndoRedo() {
        String[] lines = getLines();
        cursorRow = Math.min(cursorRow, Math.max(0, lines.length - 1));
        int lineLen = (cursorRow < lines.length) ? lines[cursorRow].length() : 0;
        cursorCol = Math.min(cursorCol, Math.max(0, lineLen - 1));
    }

    // -------------------------------------------------------------------------
    // オフセット計算
    // -------------------------------------------------------------------------

    public int offsetAt(int row, int col) {
        String[] lines = getLines();
        int offset = 0;
        for (int i = 0; i < row && i < lines.length; i++) {
            offset += lines[i].length() + 1; // +1 は改行文字
        }
        int lineLen = row < lines.length ? lines[row].length() : 0;
        return offset + Math.min(col, lineLen);
    }

    private int offsetOfCursor() {
        return offsetAt(cursorRow, cursorCol);
    }

    private String[] getLines() {
        return buffer.getText().split("\n", -1);
    }

    // -------------------------------------------------------------------------
    // VISUALモード（文字単位）ヘルパー
    // -------------------------------------------------------------------------

    private String getSelectedText() {
        int o1 = offsetAt(anchorRow, anchorCol);
        int o2 = offsetOfCursor();
        int start = Math.min(o1, o2);
        int end = Math.max(o1, o2);
        if (end < buffer.length()) {
            end = Math.min(end + 1, buffer.length());
        }
        return buffer.getText().substring(start, end);
    }

    private void deleteSelected() {
        int o1 = offsetAt(anchorRow, anchorCol);
        int o2 = offsetOfCursor();
        int start = Math.min(o1, o2);
        int end = Math.max(o1, o2);
        if (end < buffer.length()) {
            end = Math.min(end + 1, buffer.length());
        }
        buffer.delete(start, end - start);
        moveCursorToOffset(start);
    }

    // -------------------------------------------------------------------------
    // VISUAL LINE / dd / yy 行単位ヘルパー
    // -------------------------------------------------------------------------

    /** r1〜r2 行（両端含む）のテキストを "\n" 区切りで返す。各行末に \n を付与する */
    private String buildLineRangeText(int r1, int r2) {
        String[] lines = getLines();
        StringBuilder sb = new StringBuilder();
        for (int i = r1; i <= r2 && i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /** 現在行をヤンクレジスタに保存する（行末 \n 付き）*/
    private void yankCurrentLine() {
        String[] lines = getLines();
        if (cursorRow >= lines.length) return;
        yankRegister = lines[cursorRow] + "\n";
        yankType = "line";
    }

    /** 現在行を削除してヤンクレジスタに保存する */
    private void deleteCurrentLine() {
        yankCurrentLine();
        String[] lines = getLines();
        int lineStart = offsetAt(cursorRow, 0);
        int lineLen = lines[cursorRow].length();

        if (cursorRow < lines.length - 1) {
            // 最終行でない: 行テキスト + 末尾 \n を削除
            buffer.delete(lineStart, lineLen + 1);
        } else if (cursorRow > 0) {
            // 最終行かつ他の行がある: 直前の \n も含めて削除
            buffer.delete(lineStart - 1, lineLen + 1);
            cursorRow--;
        } else {
            // ドキュメント唯一の行
            buffer.delete(lineStart, lineLen);
        }

        String[] newLines = getLines();
        cursorRow = Math.min(cursorRow, Math.max(0, newLines.length - 1));
        int newLineLen = cursorRow < newLines.length ? newLines[cursorRow].length() : 0;
        cursorCol = Math.min(cursorCol, Math.max(0, newLineLen - 1));
        if (cursorCol < 0) cursorCol = 0;
    }

    /** r1〜r2 行（両端含む）を削除する。カーソルを r1 にリセットする */
    private void deleteLineRange(int r1, int r2) {
        String[] lines = getLines();

        int deleteStart;
        int deleteLength;
        if (r2 < lines.length - 1) {
            // 最終行に届かない: r1〜r2 の行テキスト + 各末尾 \n を削除
            deleteStart = offsetAt(r1, 0);
            int deleteEnd = offsetAt(r2 + 1, 0);
            deleteLength = deleteEnd - deleteStart;
        } else if (r1 > 0) {
            // 最終行まで含み、前行がある: 直前の \n も含めて削除
            deleteStart = offsetAt(r1, 0) - 1;
            deleteLength = buffer.length() - deleteStart;
        } else {
            // すべての行を削除
            deleteStart = 0;
            deleteLength = buffer.length();
        }

        buffer.delete(deleteStart, deleteLength);

        String[] newLines = getLines();
        cursorRow = Math.min(r1, Math.max(0, newLines.length - 1));
        cursorCol = 0;
        clampCursorForNormal();
    }

    // -------------------------------------------------------------------------
    // ペースト（p / P）
    // -------------------------------------------------------------------------

    private void pasteAfter() {
        if (yankRegister.isEmpty()) return;
        if ("line".equals(yankType)) {
            pasteLineAfter();
        } else {
            pasteCharAfter();
        }
    }

    private void pasteBefore() {
        if (yankRegister.isEmpty()) return;
        if ("line".equals(yankType)) {
            pasteLineBefore();
        } else {
            pasteCharBefore();
        }
    }

    private void pasteCharAfter() {
        int offset = Math.min(offsetOfCursor() + 1, buffer.length());
        buffer.insert(offset, yankRegister);
        int newOffset = offset + yankRegister.length() - 1;
        moveCursorToOffset(newOffset);
        clampCursorForNormal();
    }

    private void pasteCharBefore() {
        int currentOffset = offsetOfCursor();
        buffer.insert(currentOffset, yankRegister);
        int newOffset = currentOffset + yankRegister.length() - 1;
        moveCursorToOffset(newOffset);
        clampCursorForNormal();
    }

    /** 行ヤンク: カーソル行の下に貼り付け、カーソルを貼り付け行へ移動 */
    private void pasteLineAfter() {
        String[] lines = getLines();
        boolean isLastLine = (cursorRow == lines.length - 1);
        String content = yankRegister.endsWith("\n")
                ? yankRegister
                : yankRegister + "\n";

        if (!isLastLine) {
            int nextLineStart = offsetAt(cursorRow + 1, 0);
            buffer.insert(nextLineStart, content);
        } else {
            // 最終行: 末尾に "\n" + 行テキスト（末尾 \n なし）を追加
            String withoutTrailingNewline = content.substring(0, content.length() - 1);
            buffer.insert(buffer.length(), "\n" + withoutTrailingNewline);
        }
        cursorRow++;
        cursorCol = 0;
    }

    /** 行ヤンク: カーソル行の上に貼り付け、カーソルを貼り付け行へ移動 */
    private void pasteLineBefore() {
        int lineStart = offsetAt(cursorRow, 0);
        String content = yankRegister.endsWith("\n")
                ? yankRegister
                : yankRegister + "\n";
        buffer.insert(lineStart, content);
        // cursorRow はそのまま（貼り付け行がカーソル行になる）
        cursorCol = 0;
    }

    // -------------------------------------------------------------------------
    // 1文字削除・カーソルオフセット変換
    // -------------------------------------------------------------------------

    private void deleteCharAtCursor() {
        String[] lines = getLines();
        int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
        if (lineLen == 0) return;
        buffer.delete(offsetOfCursor(), 1);
        clampCursorForNormal();
    }

    // -------------------------------------------------------------------------
    // 単語・行・ファイル単位の移動
    // -------------------------------------------------------------------------

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void moveWordForward() {
        String text = buffer.getText();
        int len = text.length();
        int offset = offsetOfCursor();
        // 現在位置の単語文字をスキップ
        while (offset < len && isWordChar(text.charAt(offset))) offset++;
        // 空白・記号をスキップして次の単語先頭へ
        while (offset < len && !isWordChar(text.charAt(offset)) && text.charAt(offset) != '\n') offset++;
        moveCursorToOffset(offset);
    }

    private void moveWordBackward() {
        String text = buffer.getText();
        int offset = offsetOfCursor();
        if (offset == 0) return;
        offset--;
        // 空白・記号を後退スキップ
        while (offset > 0 && !isWordChar(text.charAt(offset))) offset--;
        // 単語文字を後退スキップして単語先頭へ
        while (offset > 0 && isWordChar(text.charAt(offset - 1))) offset--;
        moveCursorToOffset(offset);
    }

    private void moveWordEnd() {
        String text = buffer.getText();
        int len = text.length();
        int offset = offsetOfCursor();
        if (offset >= len - 1) return;
        offset++;
        // 空白・記号をスキップ
        while (offset < len && !isWordChar(text.charAt(offset))) offset++;
        // 単語末尾へ
        while (offset < len - 1 && isWordChar(text.charAt(offset + 1))) offset++;
        moveCursorToOffset(offset);
    }

    private void moveLineStart() {
        cursorCol = 0;
    }

    private void moveLineStartNonBlank() {
        String[] lines = getLines();
        String line = cursorRow < lines.length ? lines[cursorRow] : "";
        int col = 0;
        while (col < line.length() && (line.charAt(col) == ' ' || line.charAt(col) == '\t')) col++;
        cursorCol = col;
    }

    private void moveLineEnd() {
        String[] lines = getLines();
        int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
        boolean isInsert = (mode == Mode.INSERT);
        cursorCol = isInsert ? lineLen : Math.max(0, lineLen - 1);
    }

    private void moveFileStart() {
        cursorRow = 0;
        cursorCol = 0;
    }

    private void moveFileEnd() {
        String[] lines = getLines();
        cursorRow = Math.max(0, lines.length - 1);
        int lineLen = lines[cursorRow].length();
        boolean isInsert = (mode == Mode.INSERT);
        cursorCol = isInsert ? lineLen : Math.max(0, lineLen - 1);
    }

    private void moveCursorToOffset(int offset) {
        String[] lines = getLines();
        int pos = 0;
        for (int i = 0; i < lines.length; i++) {
            int lineEnd = pos + lines[i].length();
            if (offset <= lineEnd) {
                cursorRow = i;
                cursorCol = offset - pos;
                return;
            }
            pos = lineEnd + 1;
        }
        cursorRow = Math.max(0, lines.length - 1);
        cursorCol = lines[cursorRow].length();
    }

    // -------------------------------------------------------------------------
    // GUI同期
    // -------------------------------------------------------------------------

    private void syncCanvas() {
        if (canvas != null) {
            canvas.setText(buffer.getText());
            canvas.setCursor(cursorRow, cursorCol);
            canvas.setInsertMode(mode == Mode.INSERT);

            boolean isVisual     = (mode == Mode.VISUAL);
            boolean isVisualLine = (mode == Mode.VISUAL_LINE);
            canvas.setVisualMode(isVisual || isVisualLine);
            canvas.setVisualLineMode(isVisualLine);

            if (isVisual) {
                canvas.setSelection(anchorRow, anchorCol, cursorRow, cursorCol);
            } else if (isVisualLine) {
                canvas.setSelection(anchorRow, 0, cursorRow, 0);
            } else {
                canvas.clearSelection();
            }

            canvas.ensureCursorVisible(cursorRow);
            String[] lines = buffer.getText().split("\n", -1);
            String curLine = (cursorRow < lines.length) ? lines[cursorRow] : "";
            canvas.ensureCursorColVisible(cursorCol, curLine);
            if (mode == Mode.COMMAND) {
                canvas.setCommandLineText(":" + commandBuffer.toString());
            } else if (!statusMessage.isEmpty()) {
                canvas.setCommandLineText(statusMessage);
            } else {
                canvas.setCommandLineText(null);
            }
        }
    }

    // -------------------------------------------------------------------------
    // パブリックアクセサ（テスト・外部連携用）
    // -------------------------------------------------------------------------

    public KeymapRegistry getKeymap()   { return keymap; }
    public String getText()            { return buffer.getText(); }
    public int getCursorRow()          { return cursorRow; }
    public int getCursorCol()          { return cursorCol; }
    public boolean isNormalMode()      { return mode == Mode.NORMAL; }
    public boolean isInsertMode()      { return mode == Mode.INSERT; }
    public boolean isCommandMode()     { return mode == Mode.COMMAND; }
    public boolean isVisualMode()      { return mode == Mode.VISUAL; }
    public boolean isVisualLineMode()  { return mode == Mode.VISUAL_LINE; }
    public String getStatusMessage()   { return statusMessage; }
    public String getCommandBuffer()   { return commandBuffer.toString(); }
    public String getYankRegister()    { return yankRegister; }
    public String getYankType()        { return yankType; }

    // プラグイン向けバッファ操作
    public int getLineCount() {
        return getLines().length;
    }

    public String getLine(int row) {
        String[] lines = getLines();
        return (row >= 0 && row < lines.length) ? lines[row] : "";
    }

    public void setCursor(int row, int col) {
        String[] lines = getLines();
        cursorRow = Math.max(0, Math.min(row, lines.length - 1));
        int lineLen = cursorRow < lines.length ? lines[cursorRow].length() : 0;
        cursorCol = Math.max(0, Math.min(col, lineLen));
        syncCanvas();
    }

    public void insertAtOffset(int offset, String text) {
        buffer.insert(offset, text);
        syncCanvas();
    }

    public void deleteRange(int startOffset, int endOffset) {
        if (startOffset < endOffset) {
            buffer.delete(startOffset, endOffset - startOffset);
            syncCanvas();
        }
    }

    public void setStatusMessage(String message) {
        this.statusMessage = message;
        syncCanvas();
    }

    /** JDK クラスインデックスを設定する（Main.java からバックグラウンド構築後に呼ぶ）。 */
    public void setJdkClassIndex(JdkClassIndex index) {
        this.jdkIndex = index;
    }

    /** auto-import ハンドラを設定する。 */
    public void setAutoImportHandler(AutoImportHandler handler) {
        this.autoImportHandler = handler;
    }

    /**
     * コンパイル診断から未解決シンボルを検出し、import の自動挿入または選択を行う。
     * 候補が1件の場合は即座に挿入。複数の場合は選択待ちモードへ。
     * Must be called on the EDT.
     */
    public void handleAutoImport(List<CompileDiagnostic> diags) {
        if (autoImportHandler == null) return;
        Map<String, List<String>> candidates =
            autoImportHandler.resolveCandidates(diags, buffer.getText());
        if (candidates.isEmpty()) return;

        List<Map.Entry<String, List<String>>> entries = new ArrayList<>(candidates.entrySet());

        // 候補が1件のみのシンボルをまず自動挿入
        List<Map.Entry<String, List<String>>> multi = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : entries) {
            if (e.getValue().size() == 1) {
                autoImportHandler.applyImport(e.getValue().get(0), buffer);
            } else {
                multi.add(e);
            }
        }

        if (!multi.isEmpty()) {
            pendingImports.clear();
            pendingImports.addAll(multi);
            pendingImportIdx = 0;
            showImportPrompt();
        }
        syncCanvas();
    }

    /** 選択待ちの import が存在するかどうか。 */
    public boolean hasImportPending() {
        return !pendingImports.isEmpty();
    }

    /** 現在のシンボルの選択を完了（または skip）して次へ進む。 */
    private void advanceImportPrompt() {
        pendingImportIdx++;
        if (pendingImportIdx >= pendingImports.size()) {
            pendingImports.clear();
            pendingImportIdx = 0;
            statusMessage = "";
        } else {
            showImportPrompt();
        }
    }

    /** 現在の選択待ちプロンプトを statusMessage に設定する。 */
    private void showImportPrompt() {
        if (pendingImports.isEmpty()) return;
        Map.Entry<String, List<String>> entry = pendingImports.get(pendingImportIdx);
        StringBuilder sb = new StringBuilder();
        sb.append("import ").append(entry.getKey()).append("? ");
        List<String> fqns = entry.getValue();
        int limit = Math.min(fqns.size(), 9);
        for (int i = 0; i < limit; i++) {
            sb.append("[").append(i + 1).append("] ").append(fqns.get(i));
            if (i < limit - 1) sb.append("  ");
        }
        if (fqns.size() > 9) sb.append("  (+").append(fqns.size() - 9).append(" more)");
        sb.append("  [Esc]=skip");
        statusMessage = sb.toString();
    }

    /** NORMALモードの K キー: カーソル位置の識別子を JDK クラスとして検索し、ステータスバーに表示。
     *  カーソルがメソッド呼び出し（Class.method形式）の上にある場合は native メソッドのトレースも試みる。
     */
    private void lookupJdkDoc() {
        if (jdkIndex == null || !jdkIndex.isReady()) {
            setStatusMessage("JDK index building...");
            return;
        }
        String word = wordAtCursor();
        if (word.isEmpty()) {
            setStatusMessage("No identifier at cursor");
            return;
        }

        // カーソルが "ClassName.methodName" の methodName 上にある場合: native トレースを試みる
        String[] classAndMethod = classAndMethodAtCursor();
        if (classAndMethod != null) {
            String className = classAndMethod[0];
            String methodName = classAndMethod[1];
            List<String> classCandidates = jdkIndex.lookup(className);
            if (!classCandidates.isEmpty()) {
                String fqn = classCandidates.stream()
                    .filter(f -> f.startsWith("java.lang.")).findFirst()
                    .orElseGet(() -> classCandidates.stream()
                        .filter(f -> f.startsWith("java.")).findFirst()
                        .orElse(classCandidates.get(0)));
                Optional<Class<?>> cls = jdkIndex.loadClass(fqn);
                if (cls.isPresent()) {
                    OpenjdkSourceTracer.TracingResult result = sourceTracer.trace(cls.get(), methodName);
                    if (result.isNative()) {
                        setStatusMessage(result.toStatusLine());
                        return;
                    }
                }
            }
        }

        List<String> candidates = jdkIndex.lookup(word);
        if (candidates.isEmpty()) {
            setStatusMessage("Not found in JDK: " + word);
            return;
        }
        // 候補が1件なら即詳細表示、複数ならリスト表示
        if (candidates.size() == 1) {
            String fqn = candidates.get(0);
            Optional<Class<?>> cls = jdkIndex.loadClass(fqn);
            if (cls.isPresent()) {
                setStatusMessage(buildDocLine(fqn, cls.get(), ""));
            } else {
                setStatusMessage(fqn + " (cannot load)");
            }
        } else {
            // 最初の候補を優先表示（java.lang > java.util > その他）
            String best = candidates.stream()
                .filter(f -> f.startsWith("java.lang."))
                .findFirst()
                .orElseGet(() -> candidates.stream()
                    .filter(f -> f.startsWith("java.util."))
                    .findFirst()
                    .orElse(candidates.get(0)));
            Optional<Class<?>> cls = jdkIndex.loadClass(best);
            String extra = candidates.size() > 1 ? " (+" + (candidates.size() - 1) + " more)" : "";
            if (cls.isPresent()) {
                setStatusMessage(buildDocLine(best, cls.get(), extra));
            } else {
                setStatusMessage(best + " (cannot load)" + extra);
            }
        }
    }

    /**
     * Javadoc サマリが取得できればそれを優先し、なければ JdkTypeInfo のフォールバック表示を返す。
     * suffix は "(+N more)" などの付加文字列。
     */
    private String buildDocLine(String fqn, Class<?> cls, String suffix) {
        Optional<String> summary = javadocReader.readSummary(fqn);
        if (summary.isPresent()) {
            String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
            return simpleName + ": " + summary.get() + suffix;
        }
        return JdkTypeInfo.from(cls).toStatusLine() + suffix;
    }

    /**
     * カーソルが "ClassName.methodName" の methodName 上にある場合、
     * [className, methodName] の配列を返す。そうでなければ null。
     */
    private String[] classAndMethodAtCursor() {
        String[] lines = getLines();
        if (cursorRow >= lines.length) return null;
        String line = lines[cursorRow];
        if (cursorCol >= line.length()) return null;
        char ch = line.charAt(cursorCol);
        if (!Character.isJavaIdentifierPart(ch)) return null;
        // メソッド名の開始位置を探す
        int start = cursorCol;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) start--;
        // 直前が '.' であることを確認
        if (start == 0 || line.charAt(start - 1) != '.') return null;
        // '.' の前にクラス名があることを確認
        int dotPos = start - 1;
        if (dotPos == 0 || !Character.isJavaIdentifierPart(line.charAt(dotPos - 1))) return null;
        int classEnd = dotPos;
        int classStart = classEnd - 1;
        while (classStart > 0 && Character.isJavaIdentifierPart(line.charAt(classStart - 1))) classStart--;
        // メソッド名
        int end = cursorCol;
        while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) end++;
        String className = line.substring(classStart, classEnd);
        String methodName = line.substring(start, end);
        if (className.isEmpty() || methodName.isEmpty()) return null;
        return new String[]{className, methodName};
    }

    // ---- Getter / Setter 自動生成 ----

    /**
     * カーソル行のフィールド宣言を解析する。
     * 例: "    private int hp;" -> ["int", "hp"]
     * 解析失敗時は null を返す。
     */
    private String[] parseFieldAtCursor() {
        String[] lines = getLines();
        if (cursorRow >= lines.length) return null;
        String line = lines[cursorRow].trim();
        // 末尾のセミコロンを除去
        if (!line.endsWith(";")) return null;
        line = line.substring(0, line.length() - 1).trim();
        // アクセス修飾子・static・final 等のトークンを除去
        String[] tokens = line.split("\\s+");
        // 型名とフィールド名は末尾2トークン
        if (tokens.length < 2) return null;
        String fieldName = tokens[tokens.length - 1];
        String typeName  = tokens[tokens.length - 2];
        // '=' による初期化があれば除去（例: "int x = 0"）
        int eqIdx = typeName.indexOf('=');
        if (eqIdx >= 0) return null; // 複雑な初期化式は対象外
        int fnEq = fieldName.indexOf('=');
        if (fnEq >= 0) fieldName = fieldName.substring(0, fnEq).trim();
        // 配列型（int[] や int[][]）はそのまま許容
        if (!fieldName.matches("[a-zA-Z_$][a-zA-Z0-9_$]*")) return null;
        return new String[]{typeName, fieldName};
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** カーソル行フィールドの getter を生成してクラス末尾 '}' 直前に挿入する。 */
    private void generateGetter() {
        String[] field = parseFieldAtCursor();
        if (field == null) { statusMessage = "Getter: フィールド宣言が見つかりません"; syncCanvas(); return; }
        String type = field[0];
        String name = field[1];
        String prefix = type.equals("boolean") ? "is" : "get";
        String indent = detectIndent();
        String method = "\n" + indent + "public " + type + " " + prefix + capitalize(name) + "() {\n"
                      + indent + indent + "return " + name + ";\n"
                      + indent + "}\n";
        insertBeforeLastBrace(method);
        statusMessage = prefix + capitalize(name) + "() を生成しました";
        syncCanvas();
    }

    /** カーソル行フィールドの setter を生成してクラス末尾 '}' 直前に挿入する。 */
    private void generateSetter() {
        String[] field = parseFieldAtCursor();
        if (field == null) { statusMessage = "Setter: フィールド宣言が見つかりません"; syncCanvas(); return; }
        String type = field[0];
        String name = field[1];
        String indent = detectIndent();
        String method = "\n" + indent + "public void set" + capitalize(name) + "(" + type + " " + name + ") {\n"
                      + indent + indent + "this." + name + " = " + name + ";\n"
                      + indent + "}\n";
        insertBeforeLastBrace(method);
        statusMessage = "set" + capitalize(name) + "() を生成しました";
        syncCanvas();
    }

    /** getter と setter の両方を生成する。 */
    private void generateGetterAndSetter() {
        String[] field = parseFieldAtCursor();
        if (field == null) { statusMessage = "Getter/Setter: フィールド宣言が見つかりません"; syncCanvas(); return; }
        String type = field[0];
        String name = field[1];
        String prefix = type.equals("boolean") ? "is" : "get";
        String indent = detectIndent();
        String methods = "\n" + indent + "public " + type + " " + prefix + capitalize(name) + "() {\n"
                       + indent + indent + "return " + name + ";\n"
                       + indent + "}\n"
                       + "\n" + indent + "public void set" + capitalize(name) + "(" + type + " " + name + ") {\n"
                       + indent + indent + "this." + name + " = " + name + ";\n"
                       + indent + "}\n";
        insertBeforeLastBrace(methods);
        statusMessage = prefix + capitalize(name) + "()/set" + capitalize(name) + "() を生成しました";
        syncCanvas();
    }

    /** 未使用の import をすべて削除する（SPC+i+o / :oi）。 */
    private void organizeImports() {
        if (autoImportHandler == null) {
            statusMessage = "E: AutoImportHandler が設定されていません";
            syncCanvas();
            return;
        }
        List<String> removed = autoImportHandler.removeUnusedImports(buffer);
        if (removed.isEmpty()) {
            statusMessage = "未使用 import なし";
        } else {
            statusMessage = removed.size() + " 件の import を削除しました";
        }
        syncCanvas();
    }

    /** 特定 FQN の import を削除する（:remove-import <fqn>）。 */
    private void executeRemoveImport(String fqn) {
        if (fqn.isEmpty()) {
            statusMessage = "E: FQN を指定してください";
            syncCanvas();
            return;
        }
        if (autoImportHandler == null) {
            statusMessage = "E: AutoImportHandler が設定されていません";
            syncCanvas();
            return;
        }
        boolean removed = autoImportHandler.removeImport(fqn, buffer);
        statusMessage = removed ? "import " + fqn + " を削除しました"
                                : "E: import " + fqn + " が見つかりません";
        syncCanvas();
    }

    /** ファイル末尾の '}' を探し、その直前にテキストを挿入する。 */
    private void insertBeforeLastBrace(String text) {
        String content = buffer.getText();
        int pos = content.lastIndexOf('}');
        if (pos < 0) {
            // '}' が見つからなければ末尾に追記
            buffer.insert(content.length(), text);
        } else {
            buffer.insert(pos, text);
        }
        // カーソルを挿入直後へ
        String newContent = buffer.getText();
        int insertedPos = (pos < 0 ? content.length() : pos) + text.length();
        // 挿入後の行列を再計算
        int row = 0, col = 0;
        for (int i = 0; i < insertedPos && i < newContent.length(); i++) {
            if (newContent.charAt(i) == '\n') { row++; col = 0; } else { col++; }
        }
        cursorRow = row;
        cursorCol = Math.max(0, col - 1);
    }

    /** ファイル内の最初のコードインデント（スペースかタブ）を検出する。 */
    private String detectIndent() {
        for (String line : getLines()) {
            if (line.startsWith("\t")) return "\t";
            if (line.startsWith("    ")) return "    ";
            if (line.startsWith("  ")) return "  ";
        }
        return "    ";
    }

    /** カーソル位置の Java 識別子（単語）を返す。識別子がなければ空文字列。 */
    private String wordAtCursor() {
        String[] lines = getLines();
        if (cursorRow >= lines.length) return "";
        String line = lines[cursorRow];
        if (cursorCol >= line.length()) return "";
        // カーソル位置が識別子文字でなければ空
        char ch = line.charAt(cursorCol);
        if (!Character.isJavaIdentifierPart(ch)) return "";
        // 左方向に識別子の先頭を探す
        int start = cursorCol;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) start--;
        // 右方向に識別子の末尾を探す
        int end = cursorCol;
        while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) end++;
        return line.substring(start, end);
    }
}
