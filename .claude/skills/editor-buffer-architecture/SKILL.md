---
name: editor-buffer-architecture
description: "Vim/Emacsの良い所を統合したJava SE製テキストエディタにおいて、文書本体を保持する中核データ構造（バッファ）を設計・実装する際に使用する。「バッファをどう持つか」「巨大ファイルを開くと遅い／メモリを食う」「挿入・削除のたびに文字列全体をコピーしてしまっている」「アンドゥ機能をどう実装するか」「カーソル位置の文字をどう取得するか」といった相談、またBuffer/Document/PieceTableという名前のクラスを新規実装する作業に着手する前に、必ず最初に参照すること。このスキルは他のすべてのエディタ機能（描画・モーダル編集・テスト）の前提となる土台なので、後回しにしてはならない。"
---

# エディタバッファ構造（Java SE / ピーステーブル方式）

## このスキルが解決すること

テキストエディタの「文書の内容をメモリ上でどう保持するか」を決定し、Java SE（外部ライブラリなし）で実装する。対象は数十万行規模のログファイルも開ける、Vim由来のアンドゥ機能とも相性が良い設計。

**前提条件（このプロジェクト固有）**
- 実装言語: Java SE のみ（Maven依存ライブラリは使わない）
- 想定ファイル規模: 数百行〜数十万行
- 用途: 学習目的と実用目的の両立

**スコープ外**（別スキルが担当するので、ここでは扱わない）
- 画面への描画方法 → `tui-rendering-pipeline` スキル
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

**なぜ`getText()`を編集の度に呼んではいけないか**：`getText()`は全ピースを毎回連結する処理であり、ピース数が増えるほどO(ピース数)のコストがかかる。画面描画には`getText()`全体ではなく「現在表示中の行範囲だけ」を取り出す専用メソッドを別途用意すること（これは`tui-rendering-pipeline`スキルの担当範囲）。

---

## よくある誤解・つまずきポイント

> ⚠️ **誤解1：「`String`の`+`連結とStringBuilderは同じくらいの速度」**
> 違う。Javaの`String`は不変（immutable）オブジェクトなので、`+`で連結すると毎回新しい`String`を生成し中身を全コピーする。`addBuffer`に文字列を追記し続ける処理では必ず`StringBuilder`を使うこと。

> ⚠️ **誤解2：「文字数(`char`の数)と見た目の文字数は常に一致する」**
> 一致しない場合がある。Javaの`char`はUTF-16の1コードユニットであり、絵文字や一部の拡張漢字は「サロゲートペア」という2つの`char`の組で1文字を表現する。ピースの分割位置（`splitPoint`）がサロゲートペアの真ん中に来てしまうと文字が破損する。ユーザー入力に基づいて分割位置を決める処理では、必ず`Character.isHighSurrogate()`／`isLowSurrogate()`で境界を確認するか、`codePointAt`系のAPIを使うこと。

> ⚠️ **誤解3：「ピーステーブルなら巨大ファイルでも常に高速」**
> 編集回数が増えるとピースの数も増え続け、`insert`内の線形探索（`for`ループ）が遅くなっていく。数百〜数千回の編集なら問題にならないが、長時間の編集セッションで性能が気になった場合は「直前にアクセスしたピースの位置をキャッシュする」「ピースリストをツリー構造（ピースツリー）に置き換える」という改善策がある。最初からツリーで実装する必要はない——まずは`ArrayList`版で動かし、実際に遅さを感じてから最適化するのが学習プロジェクトとして正しい順序。

---

## 次に学ぶべきこと

1. `delete`と基本的なUndo/Redoの実装（`references/piece-table-delete-and-undo.md`）
2. このバッファをターミナルに表示する方法 → `tui-rendering-pipeline`スキル
3. ノーマルモード／インサートモードの切り替えとこのバッファの接続 → `modal-editing-engine`スキル
