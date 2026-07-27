package dev.javatexteditor.ui;

import dev.javatexteditor.telescope.TelescopeItem;
import java.util.List;

/**
 * 候補一覧のオーバーレイを描くために {@link EditorCanvas} が必要とする情報一式。
 *
 * <p>名前は telescope 由来だが、2026-07 に telescope（SPC+f / SPC+/ / SPC+b）と FILER が
 * 疑似バッファ表示へ移行したため、<b>現在このオーバーレイを実際に使うのは
 * import 候補選択（IMPORT_SELECT モード）だけ</b>である。
 * 経緯は {@code .claude/skills/telescope-picker/SKILL.md} を参照。
 *
 * @param title      オーバーレイ上部に出す見出し
 * @param query      入力中の絞り込み文字列
 * @param results    表示する候補
 * @param preview    候補の内容プレビュー（現在は常に空文字列）
 */
public record TelescopeView(boolean active, String title, String query,
                            List<TelescopeItem> results, int selectedIdx, String preview) {

    /** null を渡されても空文字列・空リストとして扱う。 */
    public TelescopeView {
        title   = (title   != null) ? title   : "";
        query   = (query   != null) ? query   : "";
        preview = (preview != null) ? preview : "";
        results = (results != null) ? List.copyOf(results) : List.of();
    }

    /** オーバーレイを出さない状態。 */
    public static TelescopeView hidden() {
        return new TelescopeView(false, "", "", List.of(), 0, "");
    }

    /** 「見出し > 入力中の文字列」という1行のプロンプト表示。 */
    public String promptLine() {
        return title + "  > " + query;
    }
}
