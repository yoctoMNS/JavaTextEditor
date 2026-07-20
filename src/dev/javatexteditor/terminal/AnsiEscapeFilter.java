package dev.javatexteditor.terminal;

/**
 * ANSIエスケープシーケンス（主にCSI: ESC '[' ... 終端バイト）を除去する状態機械。
 * このプロジェクトは真のPTYを持たず（CLAUDE.mdの「外部ライブラリ一切不使用」方針により
 * JNI等のネイティブPTY実装を採用できない）、バッファは色・カーソル移動を解釈しない単なる
 * プレーンテキストのため、カーソル移動や色指定のエスケープシーケンスをそのまま挿入すると
 * 制御文字の羅列として画面が乱れる。多くのCLIツールは標準出力がtty(isatty)でないことを検知して
 * 自発的にANSI出力を無効化するため、本フィルタは主に安全網として機能する。
 *
 * チャンク境界（1回のread()で読める範囲）をまたいでエスケープシーケンスが分割されるケースに対応する
 * ため、状態（NORMAL/ESC/CSI）をインスタンスフィールドとして呼び出しごとに保持する。
 */
public final class AnsiEscapeFilter {

    private static final char ESC = '\u001B';

    private enum State { NORMAL, ESC, CSI }

    private State state = State.NORMAL;

    /**
     * input からANSIエスケープシーケンスを除去した文字列を返す。
     * ESC単体＋1文字の非CSIシーケンス（例: ESC 'M'）は簡易的に2文字で終端とみなす。
     * CSI（ESC '['）はパラメータ/中間バイトを読み飛ばし、終端バイト（'@'〜'~'）で終わる。
     */
    public String filter(String input) {
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (state) {
                case NORMAL -> {
                    if (c == ESC) {
                        state = State.ESC;
                    } else {
                        out.append(c);
                    }
                }
                case ESC -> {
                    if (c == '[') {
                        state = State.CSI;
                    } else {
                        state = State.NORMAL;
                    }
                }
                case CSI -> {
                    if (c >= '@' && c <= '~') {
                        state = State.NORMAL;
                    }
                    // パラメータ/中間バイト（0x20-0x3F）は読み飛ばしを継続
                }
            }
        }
        return out.toString();
    }
}
