package dev.javatexteditor.editor;

/**
 * Visual インデント（{@code >}/{@code <}）が参照する設定値。
 * Vim の同名オプションに対応する: shiftwidth / tabstop / expandtab / shiftround。
 * 本プロジェクトには設定ファイル機構がまだ無いため、ModalEditor が1インスタンスを
 * 保持しゲッター経由で取得・変更する（テストからも同じ口で変更できる）。
 */
public class IndentSettings {

    private int shiftwidth = 4;
    private int tabstop = 4;
    private boolean expandtab = true;
    private boolean shiftround = false;

    public int getShiftwidth() { return shiftwidth; }
    public void setShiftwidth(int shiftwidth) { this.shiftwidth = shiftwidth; }

    public int getTabstop() { return tabstop; }
    public void setTabstop(int tabstop) { this.tabstop = tabstop; }

    public boolean isExpandtab() { return expandtab; }
    public void setExpandtab(boolean expandtab) { this.expandtab = expandtab; }

    public boolean isShiftround() { return shiftround; }
    public void setShiftround(boolean shiftround) { this.shiftround = shiftround; }
}
