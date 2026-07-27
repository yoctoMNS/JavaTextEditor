package dev.javatexteditor.editor;

import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * OS のシステムクリップボードとの読み書きだけを担うクラス。
 *
 * <p>「クリップボードから何が取れたか」を {@link ReadResult} として返すところまでが責務であり、
 * 取れた文字列をどこへ挿入するか・失敗をどうユーザーへ見せるか（ステータス行の文言）は
 * 呼び出し側（{@link ModalEditor}）の責務として切り離してある。これにより、
 * 「OS と話す処理」と「テキストを編集する処理」が同じメソッドに同居しなくなる。
 *
 * <p>ヘッドレス環境（DISPLAY 未設定）では {@code Toolkit.getSystemClipboard()} 自体が
 * 例外を投げるため、すべての公開メソッドは例外を送出せず {@link ReadResult.Failure} または
 * {@link CopyResult#failed} として失敗を値で返す（graceful degradation）。
 */
final class SystemClipboardAccess {

    /**
     * クリップボード読み取りの結果。次の3状態のいずれかになる。
     * <ul>
     *   <li>{@link Content} — 貼り付けるべきテキストが取れた</li>
     *   <li>{@link Empty}   — クリップボードが空、または扱える形式が無かった（異常ではない）</li>
     *   <li>{@link Failure} — クリップボードそのものにアクセスできなかった（異常）</li>
     * </ul>
     */
    sealed interface ReadResult {
        /** 貼り付け可能なテキストが取得できた状態。 */
        record Content(String text) implements ReadResult {}

        /** 貼り付けるものが無い状態。{@code reason} はそのままユーザーへ表示してよい平文。 */
        record Empty(String reason) implements ReadResult {}

        /** クリップボードへアクセスできなかった状態。{@code reason} は "E: " を付けて表示する想定。 */
        record Failure(String reason) implements ReadResult {}
    }

    /** コピー結果。成功なら {@code errorReason} が null。 */
    record CopyResult(String errorReason) {
        static CopyResult succeeded() { return new CopyResult(null); }
        static CopyResult failed(String reason) { return new CopyResult(reason); }
        boolean isFailure() { return errorReason != null; }
    }

    /** 指定テキストをシステムクリップボードへ書き込む。 */
    CopyResult copy(String text) {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(text), null);
            return CopyResult.succeeded();
        } catch (Exception e) {
            return CopyResult.failed("clipboard copy failed: " + e.getMessage());
        }
    }

    /**
     * システムクリップボードの内容を、バッファへ挿入できる1つの文字列として読み出す。
     *
     * <p>優先順位は次のとおり。
     * <ol>
     *   <li>文字列（{@code stringFlavor}）— そのまま返す</li>
     *   <li>ファイル一覧（{@code javaFileListFlavor}）— 絶対パスを1行1件にして返す</li>
     *   <li>それ以外（画像・音声等）— 生バイト列を ISO-8859-1（1バイト=1文字の可逆マッピング）で
     *       デコードして返す。{@code getBytes(ISO_8859_1)} で元のバイト列を完全に復元できる</li>
     * </ol>
     */
    ReadResult read() {
        Transferable contents;
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            contents = clipboard.getContents(null);
        } catch (Exception e) {
            return new ReadResult.Failure("clipboard unavailable: " + e.getMessage());
        }
        if (contents == null) {
            return new ReadResult.Empty("clipboard is empty");
        }
        try {
            if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return new ReadResult.Content((String) contents.getTransferData(DataFlavor.stringFlavor));
            }
            if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                return new ReadResult.Content(readFilePaths(contents));
            }
            byte[] bytes = readBinary(contents);
            if (bytes == null) {
                return new ReadResult.Empty("unsupported clipboard content");
            }
            return new ReadResult.Content(new String(bytes, StandardCharsets.ISO_8859_1));
        } catch (Exception e) {
            return new ReadResult.Failure("clipboard paste failed: " + e.getMessage());
        }
    }

    /** ファイルマネージャ等でコピーされたファイル一覧を、絶対パスの改行区切り文字列へ変換する。 */
    @SuppressWarnings("unchecked")
    private String readFilePaths(Transferable contents) throws Exception {
        List<File> files = (List<File>) contents.getTransferData(DataFlavor.javaFileListFlavor);
        return ClipboardBinaryCodec.joinFilePaths(files);
    }

    /**
     * 文字列・ファイル一覧以外の DataFlavor（image/audio 等）から生バイト列を読み出す。
     * ストリーム系 DataFlavor を優先し、見つからなければ imageFlavor（java.awt.Image、
     * スクリーンショットツール等が公開する非ストリーム形式）を PNG へエンコードして返す。
     * いずれも取得不能なら null。
     */
    private byte[] readBinary(Transferable contents) throws Exception {
        for (DataFlavor flavor : contents.getTransferDataFlavors()) {
            if (!InputStream.class.isAssignableFrom(flavor.getRepresentationClass())) continue;
            try (InputStream in = (InputStream) contents.getTransferData(flavor)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                return out.toByteArray();
            }
        }
        if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)
                && contents.getTransferData(DataFlavor.imageFlavor) instanceof Image image) {
            return ClipboardBinaryCodec.encodeImageAsPng(image);
        }
        return null;
    }
}
