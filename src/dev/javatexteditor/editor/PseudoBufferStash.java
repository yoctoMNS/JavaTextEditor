package dev.javatexteditor.editor;

import dev.javatexteditor.buffer.UndoablePieceTable;

/**
 * 疑似バッファ（{@code *grep*}・FILER 一覧・telescope 候補・jdk-source・{@code *compile*}/{@code *run*}・
 * Markdown 閲覧ビュー等）を表示している間、その裏に隠れている「元の編集状態」を預かっておく置き場。
 *
 * <p>預かるのは常に次の4点セットである。
 * <ul>
 *   <li>編集中だったバッファ</li>
 *   <li>そのバッファが開いていたファイルパス（疑似バッファなら null）</li>
 *   <li>カーソル行・カーソル列</li>
 * </ul>
 *
 * <p><b>バッファは「本文の写し」ではなく生きた {@link UndoablePieceTable} の参照として預かる。</b>
 * これは Vim 方式の共有バッファ（同じファイルを複数ペインで開くと同一インスタンスを共有する）を
 * 壊さないために必須である。ここで新しいインスタンスを作り直してしまうと、
 * 疑似バッファを開いて閉じただけでそのペインだけ共有から静かに外れてしまう。
 * 本文をコピーする {@link BufferSnapshot}（Ctrl+U/Ctrl+P の履歴用）とは役割が異なるので混同しないこと。
 *
 * <p><b>この型のインスタンスは用途ごとに1つずつ持つこと。</b>
 * telescope 用と FILER 用などを1つのインスタンスで共用してはならない。
 * 複数の疑似バッファを重ねて開いた場合の挙動は現状「未定義・未テスト」と記録されている
 * （CLAUDE.md「既知の未接続・二重定義」5.）ため、共用にするとその未定義の意味論を
 * 意図せず変えてしまう。型だけを共通化し、状態は従来どおり用途ごとに独立させる。
 */
final class PseudoBufferStash {

    private UndoablePieceTable buffer = null;
    private String filePath = null;
    private int cursorRow = 0;
    private int cursorCol = 0;

    /** いま編集中の状態を預ける。 */
    void save(UndoablePieceTable buffer, String filePath, int cursorRow, int cursorCol) {
        this.buffer = buffer;
        this.filePath = filePath;
        this.cursorRow = cursorRow;
        this.cursorCol = cursorCol;
    }

    /**
     * 預かっているバッファ。何も預かっていなければ空バッファを新規に返す（null は返さない）。
     * 「退避が空なら空バッファで復元する」という従来からのフォールバックをここに1つだけ置いている。
     */
    UndoablePieceTable buffer() {
        return buffer != null ? buffer : new UndoablePieceTable("");
    }

    String filePath() {
        return filePath;
    }

    int cursorRow() {
        return cursorRow;
    }

    int cursorCol() {
        return cursorCol;
    }

    /** 預かっていた参照を手放す。復元し終えたら必ず呼ぶ。 */
    void clear() {
        buffer = null;
        filePath = null;
        cursorRow = 0;
        cursorCol = 0;
    }
}
