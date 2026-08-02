---
name: editor-buffer-architecture
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、文書本体を保持する中核データ構造（バッファ）を設計・実装する際に使用する。「バッファをどう持つか」「巨大ファイルを開くと遅い／メモリを食う」「挿入・削除のたびに文字列全体をコピーしてしまっている」「アンドゥ機能をどう実装するか」「カーソル位置の文字をどう取得するか」といった相談、またBuffer/Document/PieceTableという名前のクラスを新規実装する作業に着手する前に、必ず最初に参照すること。このスキルは他のすべてのエディタ機能（描画・モーダル編集・テスト）の前提となる土台なので、後回しにしてはならない。"
---

# エディタバッファ構造（Java SE / ピーステーブル方式）

## このスキルが解決すること

テキストエディタの「文書の内容をメモリ上でどう保持するか」を決定し、Java SE（外部ライブラリなし）で実装する。対象は数十万行規模のログファイルも開ける、Vim由来のアンドゥ機能とも相性が良い設計。

**前提条件（このプロジェクト固有）**
- 実装言語: Java SE のみ（Maven依存ライブラリは使わない）
- 想定ファイル規模: 数百行〜数十万行（オープン処理自体は後述のmmap化により数十MB〜GB級（実質
  `Integer.MAX_VALUE`バイト強まで）にも対応済み。詳細は「大容量ファイル対応（mmap化）」節参照）
- 用途: 学習目的と実用目的の両立

**スコープ外**（別スキルが担当するので、ここでは扱わない）
- 画面への描画方法 → `gui-rendering-pipeline` スキル
- Java自体を使った拡張・設定ファイルの実行機構 → `extension-language-runtime` スキル
- モーダル編集（ノーマル/インサートモード）の状態遷移 → `modal-editing-engine` スキル

---

## なぜ「ピーステーブル」を選ぶのか

### 候補は4つあった

| 方式 | 概要 | このプロジェクトでの評価 |
|---|---|---|
| 行配列（`ArrayList<String>`） | 1行を1つの文字列として保持 | ❌ 行の途中への1文字挿入のたびに新しい`String`を作り直すコストが高い |
| ギャップバッファ | 1つの巨大配列の中に「空き領域（ギャップ）」を持ち、その位置で編集する | ❌ カーソルが遠くへジャンプするたびにギャップの移動コスト（O(距離)）が発生し、ログファイルの末尾検索のような操作に弱い |
| ピーステーブル | 元ファイルは変更せず、「どの範囲を指すか」という断片（ピース）のリストで文書を表現する | ✅ 採用 |
| ロープ（木構造） | 文字列を木構造のノードに分割して保持する | ❌ 実装が複雑（バランス木の回転処理など）で、Java SEのみでの実装は学習目的を超えて難度が高すぎる |

### 判断基準とその理由

1. **「Java SEのみ」という制約**：ロープは自己平衡二分木の実装が必要で、`java.util`標準クラスだけでは骨組みしか提供されない。一方ピーステーブルは`ArrayList`と`StringBuilder`という標準クラス2つだけで実装できる。
2. **「巨大ファイル」という制約**：行配列方式は1行が極端に長いログ（JSON1行ログなど）に弱い。ピーステーブルは行という概念を持たず、バイト/文字オフセットだけで管理するため、1行の長さに依存しない。
3. **「Vimのアンドゥ機能との親和性」**：ピーステーブルは「元のテキストを書き換えず、新しい断片を追加するだけ」という追記型の設計のため、編集前の断片リストをスナップショットとして保持するだけでアンドゥが実現できる（後述）。

---

## 実装：ピーステーブルの中核クラス

### 設計の考え方（仕組み）

ピーステーブルは2つの「バッファ（実データの保管場所）」と1つの「ピースリスト（順序情報）」で構成される。

```
元バッファ（original）: ファイルを開いた時点の内容。読み込み後は一切変更しない（読み取り専用）
追加バッファ（add）    : ユーザーが挿入した文字列だけを、末尾にどんどん追記していく場所
ピースリスト（pieces） : 「元バッファのX文字目からY文字分」「追加バッファのX文字目からY文字分」
                        という断片の並びで、現在の文書全体を表現する
```

挿入操作をしても、`original`も`add`も既存の文字は一切上書きしない。**変わるのはピースリストの並び方だけ**である。これが「アンドゥがほぼ無料で手に入る」理由——編集前のピースリスト（参照のコピーなので軽量）を保存しておけば、それがそのままアンドゥの復元先になる。

### コード

```java
import java.util.ArrayList;
import java.util.List;

/**
 * 文書全体を構成する1つの「断片」を表す。
 * recordを使う理由: ピースは一度作ったら値を変更しない（イミュータブル）ため、
 * Java 16以降の標準機能であるrecordで簡潔に表現できる。
 * もし通常のclassで書く場合はgetterだけのfinalフィールドclassと等価。
 */
record Piece(Source source, int start, int length) {
    enum Source { ORIGINAL, ADD } // どちらのバッファを指しているか
}

public class PieceTable {

    private final String original;        // 読み込んだファイルそのもの（不変）
    private final StringBuilder addBuffer; // 挿入された文字列の追記専用バッファ
    private final List<Piece> pieces;      // 文書の並び順を表すピースの列

    public PieceTable(String originalText) {
        this.original = originalText;
        this.addBuffer = new StringBuilder();
        this.pieces = new ArrayList<>();
        // 最初の状態は「元ファイル全体」を指す1個のピースだけ
        if (!originalText.isEmpty()) {
            pieces.add(new Piece(Piece.Source.ORIGINAL, 0, originalText.length()));
        }
    }

    /**
     * 文書中のoffset文字目にtextを挿入する。
     * なぜこの書き方か: StringBuilder.append()はO(1)に近い（内部配列を都度伸長するだけ）。
     * もしString同士の "+" 連結を使うと、毎回新しいString全体をコピーするためO(n)になり、
     * 巨大ファイルの編集では致命的に遅くなる。
     */
    public void insert(int offset, String text) {
        if (text.isEmpty()) return;

        // 1. 追加バッファの末尾に挿入文字列を書き込み、それを指す新ピースを作る
        int addStart = addBuffer.length();
        addBuffer.append(text);
        Piece newPiece = new Piece(Piece.Source.ADD, addStart, text.length());

        // 2. offsetがどのピースの中（または境界）に位置するかを線形探索で特定する
        int runningOffset = 0;
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            if (offset <= runningOffset + p.length()) {
                int splitPoint = offset - runningOffset; // p内での相対位置
                pieces.remove(i);
                int insertAt = i;
                if (splitPoint > 0) {
                    // 既存ピースの「前半」を残す
                    pieces.add(insertAt++, new Piece(p.source(), p.start(), splitPoint));
                }
                pieces.add(insertAt++, newPiece); // 新しい断片を挟み込む
                if (splitPoint < p.length()) {
                    // 既存ピースの「後半」を残す
                    pieces.add(insertAt, new Piece(p.source(), p.start() + splitPoint, p.length() - splitPoint));
                }
                return;
            }
            runningOffset += p.length();
        }
        // ループを抜けた場合（文書の末尾への挿入）
        pieces.add(newPiece);
    }

    /** 文書全体の文字数を返す */
    public int length() {
        return pieces.stream().mapToInt(Piece::length).sum();
    }

    /** 文書全体をStringとして取り出す（巨大ファイルでは多用しない・後述の注意点参照） */
    public String getText() {
        StringBuilder result = new StringBuilder(length());
        for (Piece p : pieces) {
            String source = (p.source() == Piece.Source.ORIGINAL) ? original : addBuffer.toString();
            result.append(source, p.start(), p.start() + p.length());
        }
        return result.toString();
    }
}
```

`delete(offset, length)`の実装は`insert`と対称的な考え方（範囲が重なるピースを分割・除去する）になる。完全な実装例は`references/piece-table-delete-and-undo.md`を参照すること。

---

## 巨大ファイルの読み込み方

```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

// 数十万行規模でも「読み込みは1回だけ」を徹底する。
// ここで読み込んだStringはPieceTableのoriginalとして以後一切コピーされない。
String originalText = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
PieceTable buffer = new PieceTable(originalText);
```

**なぜ`getText()`を編集の度に呼んではいけないか**：`getText()`は全ピースを毎回連結する処理であり、ピース数が増えるほどO(ピース数)のコストがかかる。画面描画には`getText()`全体ではなく「現在表示中の行範囲だけ」を取り出す専用メソッドを別途用意すること（これは`gui-rendering-pipeline`スキルの担当範囲。`getTextInRange()`として実装済み、`gui-rendering-pipeline/references/future-phases.md`参照）。

---

## よくある誤解・つまずきポイント

> ⚠️ **誤解1：「`String`の`+`連結とStringBuilderは同じくらいの速度」**
> 違う。Javaの`String`は不変（immutable）オブジェクトなので、`+`で連結すると毎回新しい`String`を生成し中身を全コピーする。`addBuffer`に文字列を追記し続ける処理では必ず`StringBuilder`を使うこと。

> ⚠️ **誤解2：「文字数(`char`の数)と見た目の文字数は常に一致する」**
> 一致しない場合がある。Javaの`char`はUTF-16の1コードユニットであり、絵文字や一部の拡張漢字は「サロゲートペア」という2つの`char`の組で1文字を表現する。ピースの分割位置（`splitPoint`）がサロゲートペアの真ん中に来てしまうと文字が破損する。ユーザー入力に基づいて分割位置を決める処理では、必ず`Character.isHighSurrogate()`／`isLowSurrogate()`で境界を確認するか、`codePointAt`系のAPIを使うこと。

> ⚠️ **誤解3：「ピーステーブルなら巨大ファイルでも常に高速」**
> 編集回数が増えるとピースの数も増え続け、`insert`内の線形探索（`for`ループ）が遅くなっていく。数百〜数千回の編集なら問題にならないが、長時間の編集セッションで性能が気になった場合は「直前にアクセスしたピースの位置をキャッシュする」「ピースリストをツリー構造（ピースツリー）に置き換える」という改善策がある。最初からツリーで実装する必要はない——まずは`ArrayList`版で動かし、実際に遅さを感じてから最適化するのが学習プロジェクトとして正しい順序。
>
> **追記（2026-07 軽量化リファクタリング Phase 1）**: 上記の「編集回数に比例してピースが増え続ける」問題のうち、
> 最頻出の「連続タイピング」は実装済みの対策で解消した。`insert()` は挿入位置が既存 ADD ピースの直後で、
> かつそのピースが追加バッファ末尾（append 直前の終端）を指している場合に限り、新ピースを作らずピースを
> 伸長する（結合条件を「addBuffer 末尾の所有者」に限定するのは、削除後の再挿入で削除済み文字が復活する
> 誤結合を防ぐため）。あわせて `length()` は `totalLength` フィールドで O(1) 化し、`getText()`/
> `getTextInRange()` は `addBuffer.toString()` を廃止して CharSequence 範囲 append に変更した（ADDピース数×
> 追加バッファ長のコピーを排除）。undo スナップショット（`List.copyOf` によるピース参照コピー）とは独立の
> 変更のため、undo 粒度（1insert=1undo）は変わらない（PieceTableTest Test 17 で固定）。本文中のサンプル
> コードは学習用に結合前の最小実装を保っている。実装の正は `src/dev/javatexteditor/buffer/PieceTable.java`。
> ランダム位置編集の蓄積によるピース増加は残るため、ピースツリー化は引き続き将来課題。

---

## 大容量ファイル対応（mmap化。軽量化リファクタリング Phase 3・2026-08）

数十MB〜GB級ファイルを開くと重くなる問題への対応として、`RandomAccessFile`/`FileChannel#map`
（`MappedByteBuffer`）を使った読み込みを追加した。既存の `PieceTable(String)` コンストラクタ・
既存の全公開APIのシグネチャは一切変更しておらず、小〜中規模ファイル（従来どおり数百〜数十万行）は
このPhase以前と完全に同じ経路・同じ性能特性のまま動く。

### 新規クラス

| クラス | 役割 |
|---|---|
| `MappedFileSource` | `RandomAccessFile`+`FileChannel#map`でファイルを開く。1回のmapで約2GiBまでしか扱えない`MappedByteBuffer`の制約を、1GiB刻みの複数チャンクに分割することで超える。マップ完了後は`RandomAccessFile`/`FileChannel`を即座に閉じる（`MappedByteBuffer`はチャネルを閉じてもバッファ自身がGCされるまで有効、というJavaDoc上の規定に基づく）。UTF-8の継続バイト（上位2bit=`10`）を後方に辿るだけで文字境界へスナップする`safeBoundaryAtOrBefore`により、要件6（マルチバイト境界での文字化け防止）を索引無しで満たす。 |
| `LazyLineIndex` | 「行番号⇔バイトオフセット⇔文字(UTF-16コードユニット)オフセット」の相互変換を、`CHECKPOINT_INTERVAL`（既定4096）行おきのスパースなチェックポイントで担う遅延索引。問い合わせがあった範囲までしか前進スキャンしないため、ファイルを開いた時点では何も構築しない。UTF-8先頭バイト（継続バイトでない）の分類だけでバイト⇔char相互変換ができる（4バイトシーケンス＝補助面文字はJavaのサロゲートペアなので+2、それ以外は+1）ため、文字列へのデコードを伴わない軽量な変換になっている。 |
| `Piece.Source.MAPPED` | `PieceTable`の新しいピース種別。`ORIGINAL`（`String`）と全く同じ「一度も編集されていない元データの範囲」を表すが、実体は`MappedFileSource`への参照のみを持つ。**座標系（`start`/`length`）はORIGINALと同じく文字(UTF-16コードユニット)オフセット**——バイトオフセットではない。 |

### なぜ座標系を「バイトオフセット」ではなく「文字オフセット」で統一したか（最重要の設計判断）

最初はMAPPEDピースの座標系をバイトオフセットにする案を検討したが、採用しなかった。理由:
このエディタの`ModalEditor`はカーソル位置・`insert`/`delete`の引数を含め全体で
「文字(UTF-16コードユニット)オフセット」を前提にしたコードが数十箇所ある（`offsetAt`だけで
呼び出し元が69箇所）。MAPPEDピースだけバイト単位にすると、`insert`/`delete`のピース分割ロジック
（既存の`for`ループでpieceの`length()`を単純加算して`runningOffset`と比較する部分）に
「ピースの種類によって単位が違う」という特殊分岐が必要になり、かつ`ModalEditor`側の
カーソル/オフセットAPI全体をバイト単位へ作り替える改修が必要になる（影響箇所が数十〜過大）。

文字オフセットに統一したことで、**`insert`/`delete`のピース分割コードは1行も変更していない**
（分割は座標の加減算だけで完結し、実際のデコードを伴わないため）。変更が必要だったのは実際に
文字を読み出す`getText`/`getTextInRange`/`offsetOfLine`の3メソッドのみで、いずれも
「必要な範囲だけ`LazyLineIndex`経由でバイトオフセットへ変換し`MappedFileSource#decode`する」
という共通パターンに従う。

### 既知のスコープ境界（意図的に対応していない部分。将来「対応する」と判断したら本表を更新すること）

| # | 内容 | 理由 |
|---|---|---|
| 1 | ファイルサイズは実質`Integer.MAX_VALUE`バイト強（約2GiB）まで | `Piece.start`/`length`が`int`のため。真に無制限にするには`Piece`を`long`化し`ModalEditor`のカーソル/オフセットAPI全体を`long`へ作り替える必要があり、影響範囲が過大なため見送った。超過時は`PieceTable`のmmapコンストラクタが`IllegalArgumentException`を投げる。 |
| 2 | `PieceTable(MappedFileSource)`のコンストラクタ内で`LazyLineIndex.totalCharCount()`を呼び、最初の1ピースの文字数を確定させるためファイル全体を1回だけバイト単位で走査する | 「行オフセットは遅延構築」という設計方針への一見した例外に見えるが、この走査は`String`/`char[]`へのデコード・確保を一切伴わないバイト分類カウントのみであり、副作用として`LazyLineIndex`のチェックポイントも同時に埋まる。旧実装（`Files.readAllBytes`+`new String(...)`によるO(n)コピー2回＋それを永続的にヒープへ保持し続ける方式）とは質的に異なる改善である。真にゼロコストな開封を実現するには`Piece`の文字長を遅延確定できる可変構造が必要で、スコープ外とした。 |
| 3 | mmap経由で開いたファイルは`\r\n`→`\n`正規化・BOM除去を行わない | 全文スキャンが必要になるため。CRLFファイルは行末に`\r`が残ったまま表示・編集される。小規模ファイル経路（`Files.readAllBytes`）はこれまでどおり正規化する。 |
| 4 | バイナリ判定（`ModalEditor.readLargeFileViaMmap`）は先頭64KiBのみで行う | GB級ファイルでバイナリ判定のためだけに全体を読むコストの方が実害が大きいと判断。誤判定（末尾だけバイナリ等）のリスクは許容している。 |
| 5 | 閾値超の`.class`ファイルは`.class`判定をスキップする | 現実的に8MiBを超えるクラスファイルは存在しないため。 |
| 6 | mmapで検出したバイナリファイルは結局`Files.readAllBytes`で全読みする（`Mode.BINARY`へのフォールバック） | `Mode.BINARY`（hexdumpエディタ）の既存実装が`byte[]`全体保持を前提としており、今回のスコープ（テキストファイルの大容量対応）には含まれない。巨大バイナリファイルのmmap対応は別途の改修が必要。 |
| 7 | **ビューポート限定描画（要件5・Stage④）は未着手** | 下記「Stage④が未着手である理由」参照。 |

### Stage④（ビューポート限定描画）が未着手である理由

`ModalEditor.refreshCanvasTextCache()`は現在も編集のたびに`buffer.getText()`（全文再構築）→
`split("\n", -1)`（全行配列化）を行い、`canvasCachedLines`（`String[]`）に保持する。この配列は
`EditorCanvas`の描画だけでなく、`offsetAt`・`clampCursorForNormal`・検索・置換・リファクタリング等
**60箇所以上**から「文書全体を表す`String[]`（インデックス=絶対行番号）」として直接参照されている。

ビューポート分だけを保持する窓（例: `scrollRow`±数千行）に置き換えると、上記60箇所以上が
「任意の絶対行番号へインデックスアクセスできる」という前提のまま壊れ、大容量ファイルに限って
グレップジャンプ・行番号ジャンプ・置換などが誤動作する重大な回帰リスクがある。これを安全に解消する
には`getLines()`の消費側60箇所以上を「絶対行番号での遅延アクセス」に置き換える改修が必要で、
本Phase（mmap化そのもの）とは別の、それ自体が大きな改修になる。

したがって本Phaseでは**ファイルを開く処理（Stage①②③）のみを大容量対応**とし、Stage④は
安全に実施できる設計（`getLines()`の消費側を段階的に絶対行番号ベースの遅延アクセスAPIへ
置き換える）を別途立案してから着手する、という判断にとどめた。中途半端な実装で60箇所以上の
呼び出し元に暗黙の前提違反を持ち込むより、境界を明示して次の改修に委ねる方が安全と判断した
（CLAUDE.mdの「品質より速さを優先しない」方針に基づく）。

---

## 次に学ぶべきこと

1. `delete`と基本的なUndo/Redoの実装（`references/piece-table-delete-and-undo.md`）
2. このバッファをSwing/AWTウィンドウに描画する方法 → `gui-rendering-pipeline`スキル
3. ノーマルモード／インサートモードの切り替えとこのバッファの接続 → `modal-editing-engine`スキル
