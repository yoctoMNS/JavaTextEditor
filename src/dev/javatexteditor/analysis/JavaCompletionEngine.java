package dev.javatexteditor.analysis;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Java バッファの入力補完で「どの候補を、どの順で出すか」を決める中心。
 * IntelliJ IDEA の補完の組み立て方をこのエディタの部品で再現する。
 *
 * <h2>ハイブリッド型解決</h2>
 *
 * <p>{@code obj.} の後ろのメンバー補完だけは、正確に出すために型解決が要る。
 * javac に解かせれば正確だがプロジェクト全体の属性付けを伴い EDT では待てない。
 * そこで2段構えにする:
 *
 * <ol>
 *   <li><b>即時（EDT）</b>: {@link ReceiverTypeResolver} が正規表現で見つけた宣言型を
 *       {@link ReflectionMemberProvider} でリフレクション列挙する。
 *       索引は起動時に構築済みなのでキー入力に追随できるが、
 *       ジェネリクスの要素型・メソッドチェーン・{@code var} は解決できない</li>
 *   <li><b>正確（バックグラウンド）</b>: {@link JavacCompletionAnalyzer} が javac の
 *       意味解析でメンバーを列挙し、結果が届いた時点でポップアップの中身を差し替える</li>
 * </ol>
 *
 * <p>2 の結果は {@linkplain MemberKey レシーバ式とその手前のテキスト} をキーにキャッシュする。
 * プレフィックスを打ち進めても（{@code list.} → {@code list.ad}）レシーバの型は変わらないため、
 * キーは変わらず javac の再実行なしに絞り込みだけが進む。
 *
 * <h2>修飾なしの位置（PLAIN）で javac を使わない理由</h2>
 *
 * <p>識別子を打ち始めるたびに javac を起動することになり、メンバー補完と違って
 * 「同じレシーバのまま打ち進む」形の再利用が効かない（位置ごとに毎回別のキーになる）。
 * 単語索引 + JDK クラス索引 + キーワードでも実用上の候補は揃うため、
 * 費用対効果が見合わないと判断してスコープ外にした。
 *
 * <p>Swing に依存しないため単体でテストできる。EDT からのみ呼ぶこと
 * （{@link #resolveMembersAccurately} だけはバックグラウンド専用）。
 */
public final class JavaCompletionEngine {

    /** 編集中バッファから拾う候補語の上限（巨大ファイルで候補が膨らみすぎないための歯止め）。 */
    private static final int MAX_BUFFER_WORDS = 200;

    /**
     * メンバー候補キャッシュのキー。
     *
     * @param filePath    編集中ファイル（別ファイルの同名レシーバと混同しないため）
     * @param receiver    ドットの左側の式そのもの
     * @param contextHash レシーバより手前のテキストのハッシュ。
     *                    プレフィックスを打ち進めてもこの値は変わらないが、
     *                    レシーバの宣言を書き換えれば変わる
     */
    public record MemberKey(String filePath, String receiver, int contextHash) {}

    private final ReceiverTypeResolver receiverTypeResolver = new ReceiverTypeResolver();
    private final SourceAnalyzer sourceAnalyzer = new SourceAnalyzer();
    private final JavacCompletionAnalyzer javacAnalyzer = new JavacCompletionAnalyzer();
    private final CompletionStatistics statistics = new CompletionStatistics();

    private JdkClassIndex jdkIndex = null;
    private WordIndex wordIndex = null;
    private CompletionIndex classIndex = null;

    // メンバー候補の1件キャッシュ（同時に2つのレシーバを補完することはない）
    private MemberKey cachedKey = null;
    private List<CompletionItem> cachedMembers = List.of();
    private boolean cachedMembersAccurate = false;

    public void setJdkClassIndex(JdkClassIndex index) {
        this.jdkIndex = index;
    }

    public void setWordIndex(WordIndex index) {
        this.wordIndex = index;
    }

    public void setClassIndex(CompletionIndex index) {
        this.classIndex = index;
    }

    public CompletionStatistics statistics() {
        return statistics;
    }

    // -------------------------------------------------------------------------
    // 候補の組み立て（EDT）
    // -------------------------------------------------------------------------

    /**
     * 現在の文脈で表示すべき候補を、マッチ位置つきで並べ替えて返す。
     *
     * @param ctx        {@link CompletionContext#at} が返した文脈
     * @param text       バッファ全文
     * @param lines      バッファの行配列（レシーバ型の軽量推定に使う）
     * @param cursorRow  カーソル行（0-indexed）
     * @param filePath   編集中ファイルの絶対パス（無名バッファなら null）
     * @param maxResults 返す最大件数
     */
    public List<CompletionRanker.Ranked> complete(CompletionContext ctx, String text, String[] lines,
                                                  int cursorRow, String filePath, int maxResults) {
        if (ctx == null) return List.of();
        List<CompletionItem> candidates = ctx.isMember()
            ? memberCandidates(ctx, text, lines, cursorRow, filePath)
            : plainCandidates(ctx, text);
        return CompletionRanker.rank(ctx.prefix(), candidates, maxResults, statistics);
    }

    /**
     * メンバー候補。キャッシュ済み（javac 解決済みを含む）ならそれを、
     * 無ければリフレクションによる軽量解決の結果を返す。
     */
    private List<CompletionItem> memberCandidates(CompletionContext ctx, String text, String[] lines,
                                                  int cursorRow, String filePath) {
        MemberKey key = memberKeyOf(filePath, text, ctx);
        List<CompletionItem> cached = cachedMembers(key);
        if (cached != null) return cached;

        List<CompletionItem> quick = quickMembers(ctx, text, lines, cursorRow);
        // 軽量解決の結果もキャッシュする。javac の結果が届けば上書きされる
        putMembers(key, quick, false);
        return quick;
    }

    /** リフレクション・現在ファイルの構文解析だけで求めるメンバー候補（即時）。 */
    private List<CompletionItem> quickMembers(CompletionContext ctx, String text,
                                              String[] lines, int cursorRow) {
        String receiver = ctx.simpleReceiver();
        if (receiver.isEmpty()) return List.of(); // 複合式は javac の結果を待つ

        if (receiver.equals("this") || receiver.equals("super")) {
            return currentFileMembers(text);
        }

        // 配列は要素型のメンバーではなく length と Object のメソッドだけを持つ
        Optional<String> rawType = receiverTypeResolver.resolveDeclaredType(lines, cursorRow, receiver);
        if (rawType.isPresent() && rawType.get().endsWith("[]")) {
            return ReflectionMemberProvider.membersOf(Object[].class, false);
        }

        Optional<String> declaredType = receiverTypeResolver.resolveType(lines, cursorRow, receiver);
        if (declaredType.isPresent()) {
            List<CompletionItem> jdkMembers =
                ReflectionMemberProvider.membersOf(jdkIndex, declaredType.get(), false);
            if (!jdkMembers.isEmpty()) return jdkMembers;
            // プロジェクト内で宣言された型なら、同じファイル内の宣言だけでも拾えることがある
            return currentFileTypeMembers(text, declaredType.get());
        }

        // 変数として宣言が見つからず、型名らしき綴りなら static メンバーの文脈とみなす
        if (Character.isUpperCase(receiver.charAt(0))) {
            return ReflectionMemberProvider.membersOf(jdkIndex, receiver, true);
        }
        return List.of();
    }

    /** 修飾なしの位置（および {@code new} の直後）の候補。 */
    private List<CompletionItem> plainCandidates(CompletionContext ctx, String text) {
        String prefix = ctx.prefix();
        List<CompletionItem> candidates = new ArrayList<>();
        if (prefix.isEmpty()) return candidates; // 何も打っていない位置では出さない

        boolean typeOnly = (ctx.kind() == CompletionContext.Kind.NEW);

        // 1. 編集中バッファの語（カーソルに近い順。CamelCase/部分一致も拾う）
        for (String word : WordIndex.matchWordsByProximity(text, ctx.prefixStart(), prefix, MAX_BUFFER_WORDS)) {
            if (typeOnly && !startsUpperCase(word)) continue;
            candidates.add(new CompletionItem(word, "wd", "", "", word, 0, null,
                CompletionItem.Origin.CURRENT_FILE));
        }

        // 2. JDK クラス名（import 候補としての FQN は確定時に解決する）
        if (classIndex != null && classIndex.isReady()) {
            for (CompletionItem item : classIndex.query(prefix, MAX_BUFFER_WORDS)) {
                candidates.add(item.withOrigin(CompletionItem.Origin.JDK_CLASS));
            }
        }

        // 3. キーワード
        if (!typeOnly) {
            candidates.addAll(JavaKeywords.forContext(ctx.kind()));
        }

        // 4. 作業ディレクトリ配下の語（ディスク索引。前方一致のみ）
        if (wordIndex != null && wordIndex.isReady()) {
            for (String word : wordIndex.query(prefix, MAX_BUFFER_WORDS, null)) {
                if (typeOnly && !startsUpperCase(word)) continue;
                candidates.add(new CompletionItem(word, "wd", "", "", word, 0, null,
                    CompletionItem.Origin.WORD));
            }
        }
        return candidates;
    }

    private static boolean startsUpperCase(String word) {
        return !word.isEmpty() && Character.isUpperCase(word.charAt(0));
    }

    /** 編集中ファイルで宣言されているメソッド・フィールド（{@code this.} 用）。 */
    private List<CompletionItem> currentFileMembers(String text) {
        return symbolsOf(text, null);
    }

    /** 編集中ファイル内の typeName クラスが持つメソッド・フィールド。 */
    private List<CompletionItem> currentFileTypeMembers(String text, String typeName) {
        return symbolsOf(text, typeName);
    }

    /**
     * 現在バッファを parse して得たシンボルを候補に変換する。
     * typeName が指定された場合でも、{@link SourceIndex} は入れ子構造を持たないため
     * 「そのクラスがこのファイルで宣言されているか」までを確認して全メンバーを返す近似とする
     * （厳密なメンバー所属は javac の結果に委ねる）。
     */
    private List<CompletionItem> symbolsOf(String text, String typeName) {
        SourceIndex index;
        try {
            index = sourceAnalyzer.analyzeText(text);
        } catch (AnalysisException e) {
            return List.of(); // 書きかけで parse できないときは候補なし
        }
        if (typeName != null) {
            boolean declaredHere = index.symbols().stream()
                .anyMatch(s -> s.name().equals(typeName)
                    && (s.kind() == SymbolKind.CLASS || s.kind() == SymbolKind.INTERFACE
                        || s.kind() == SymbolKind.ENUM));
            if (!declaredHere) return List.of();
        }
        List<CompletionItem> items = new ArrayList<>();
        for (SymbolEntry symbol : index.symbols()) {
            switch (symbol.kind()) {
                case METHOD -> items.add(new CompletionItem(symbol.name(), "mth", "()", "",
                    symbol.name() + "()", 0, null, CompletionItem.Origin.MEMBER));
                case FIELD -> items.add(new CompletionItem(symbol.name(), "fld", "", "",
                    symbol.name(), 0, null, CompletionItem.Origin.MEMBER));
                default -> { /* クラス・コンストラクタはメンバー候補にしない */ }
            }
        }
        return items;
    }

    // -------------------------------------------------------------------------
    // 正確なメンバー解決（バックグラウンド専用）
    // -------------------------------------------------------------------------

    /**
     * javac の意味解析でメンバーを列挙する。<b>EDT から呼んではならない</b>。
     * 解決できなければ空リスト（呼び出し側は軽量解決の結果を保つこと）。
     */
    public List<CompletionItem> resolveMembersAccurately(CompletionContext ctx, String text,
                                                         String filePath, Path projectRoot) {
        return javacAnalyzer.resolveMembers(text, filePath, ctx, projectRoot);
    }

    /** メンバー候補キャッシュのキーを作る。 */
    public MemberKey memberKeyOf(String filePath, String text, CompletionContext ctx) {
        int contextEnd = Math.max(0, Math.min(ctx.receiverStart(), text.length()));
        return new MemberKey(filePath, ctx.receiverText(), text.substring(0, contextEnd).hashCode());
    }

    /** キャッシュ済みのメンバー候補。無ければ null。 */
    public List<CompletionItem> cachedMembers(MemberKey key) {
        return (key != null && key.equals(cachedKey)) ? cachedMembers : null;
    }

    /** そのキーについて javac による正確な解決が済んでいるか。 */
    public boolean hasAccurateMembers(MemberKey key) {
        return key != null && key.equals(cachedKey) && cachedMembersAccurate;
    }

    /**
     * メンバー候補をキャッシュする。
     * 正確な結果（javac 由来）は軽量な結果を上書きするが、その逆は起こらない。
     */
    public void putMembers(MemberKey key, List<CompletionItem> members, boolean accurate) {
        if (key == null) return;
        if (key.equals(cachedKey) && cachedMembersAccurate && !accurate) return;
        cachedKey = key;
        cachedMembers = (members != null) ? List.copyOf(members) : List.of();
        cachedMembersAccurate = accurate;
    }

    /** キャッシュを捨てる（バッファ切り替え時など）。 */
    public void clearMemberCache() {
        cachedKey = null;
        cachedMembers = List.of();
        cachedMembersAccurate = false;
    }

    /** 候補が確定されたことを学習する。 */
    public void recordAccepted(CompletionItem item) {
        if (item != null) statistics.recordAccepted(item.label());
    }
}
