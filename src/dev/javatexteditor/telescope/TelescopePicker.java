package dev.javatexteditor.telescope;

import java.util.List;

/**
 * telescope の「ソース」抽象。
 * クエリ文字列を受け取り、フィルタリング・スコアリング済みの候補リストを返す。
 * 実装クラス: FilePicker, GrepPicker, BufferPicker
 */
public interface TelescopePicker {

    /** このピッカーのタイトル（プロンプトに表示）。 */
    String title();

    /**
     * クエリに基づいてフィルタリング・スコアリングした候補リストを返す。
     * クエリが空の場合は全候補をスコア 0 で返す。
     * リストはスコア降順でソートする。
     *
     * @param query ユーザーの入力文字列
     * @return マッチした候補リスト（最大 MAX_RESULTS 件）
     */
    List<TelescopeItem> filter(String query);

    int MAX_RESULTS = 200;
}
