package dev.javatexteditor.format;

/**
 * 並び替え対象メンバー1件分の「隙間テキスト＋本文テキスト」と、ソートに必要な付随情報。
 * leadingGap+ownText を常に一体として扱うことで、コメント・Javadoc・同一行末コメントを
 * 元のメンバーにくっつけたまま並び替えられる（詳細は JavaMemberFormatter のクラスコメント参照）。
 */
final class MemberBlock {
    final int originalIndex;
    final MemberCategory category;
    final int tier;
    final String leadingGap;
    final String ownText;

    // ソートキー（カテゴリによって使うものだけが参照される）
    final int visibilityRank;      // 0=public 1=protected 2=package-private 3=private
    final int paramCount;
    final int nameGroupRank;       // 同名メソッド（オーバーロード）をまとめるための出現順ランク
    final int objectOverridePriority; // equals/hashCode/toString/clone/finalize の固定順位
    final int constructorSubRank;  // record: compact/canonical=0, それ以外=1

    MemberBlock(int originalIndex, MemberCategory category, int tier, String leadingGap, String ownText,
                int visibilityRank, int paramCount, int nameGroupRank,
                int objectOverridePriority, int constructorSubRank) {
        this.originalIndex = originalIndex;
        this.category = category;
        this.tier = tier;
        this.leadingGap = leadingGap;
        this.ownText = ownText;
        this.visibilityRank = visibilityRank;
        this.paramCount = paramCount;
        this.nameGroupRank = nameGroupRank;
        this.objectOverridePriority = objectOverridePriority;
        this.constructorSubRank = constructorSubRank;
    }
}
