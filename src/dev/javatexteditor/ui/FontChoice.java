package dev.javatexteditor.ui;

/**
 * :font コマンドで選べる半角ASCIIフォントの種類。
 * MISC_FIXED が既定（:font 0）、IBM_PLEX_MONO は :font 1、
 * JETBRAINS_MONO は :font 2、COMIC_MONO は :font 3 で選択する。
 */
public enum FontChoice {
    MISC_FIXED,
    IBM_PLEX_MONO,
    JETBRAINS_MONO,
    COMIC_MONO
}
