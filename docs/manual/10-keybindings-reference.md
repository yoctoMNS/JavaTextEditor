[← 目次](README.md)

# 10. キーバインド早見表

各キーの詳しい挙動は該当章（カッコ内）を参照してください。

## グローバル（モード非依存）

| キー | 動作 |
|---|---|
| `F2` | カーソル行のエラー・警告一覧をダイアログ表示（[編集支援機能](06-editing-features.md)） |
| `F10` | プロジェクト全体をコンパイル（開いている拡張子で Java=`javac`/C=`gcc` を自動切替。[Java](04-java-tooling.md) / [C](11-c-tooling.md)） |
| `F11` | 実行（Java=mainクラス解決 / C=実行ファイル起動。[Java](04-java-tooling.md) / [C](11-c-tooling.md)） |
| `F12` | コンパイルして成功時のみ実行（Java/C 共通。[Java](04-java-tooling.md) / [C](11-c-tooling.md)） |
| `Ctrl+Shift+←` / `→` | アクティブペインのフォントセル幅を調整（[編集支援機能](06-editing-features.md)） |
| `Ctrl+Shift+↑` / `↓` | アクティブペインのフォントセル高さを調整（[編集支援機能](06-editing-features.md)） |
| マウスクリック | クリックしたペインへフォーカス移動 |

## NORMALモード

### 移動

| キー | 動作 |
|---|---|
| `h` `j` `k` `l` | 左/下/上/右へ移動 |
| `w` `b` `e` | 次単語先頭 / 前単語先頭 / 単語末尾 |
| `0` `^` `$` | 行頭 / 行の非空白先頭 / 行末 |
| `gg` `G` | ファイル先頭 / ファイル末尾 |
| `H` `M` `L` | 画面最上/中央/最下行 |
| `%` | 対応する括弧へジャンプ（[編集支援機能](06-editing-features.md)） |
| `Ctrl+F` `Ctrl+B` | 1画面下/上スクロール |
| `Ctrl+D` | 半画面下スクロール |
| `Ctrl+E` `Ctrl+Y` | 1行下/上スクロール |
| `Space+h` `Space+l` `Space+k` `Space+j` | `^` `$` `gg` `G` 相当（リーダーキー） |
| `Ctrl+U` | 直前のバッファへ切り替え（半ページスクロールではない点に注意） |
| `Ctrl+P` | 次のバッファへ切り替え |

### 編集

| キー | 動作 |
|---|---|
| `i` `a` `o` | INSERTへ（カーソル前/後/新規行） |
| `u` `Ctrl+R` | アンドゥ/リドゥ |
| `yy` `dd` `x` | 行ヤンク/行削除/1文字削除 |
| `p` `P` | 貼り付け（後/前） |
| `q{a-z}` ... `q` | マクロ記録開始/終了（大文字レジスタは既存内容へ追記。[編集支援機能](06-editing-features.md)） |
| `@{a-z}` `@@` | マクロ再生/直前に実行したマクロを再現（[編集支援機能](06-editing-features.md)） |
| `Alt+J` `Alt+K` | 行入れ替え（下/上） |
| `~` | カーソル位置の1文字の大文字/小文字を反転（[モーダル編集](02-modal-editing.md)） |
| `guu` `gUU` `g~~` | 現在行を小文字化/大文字化/大文字小文字反転（[モーダル編集](02-modal-editing.md)） |
| `Space+g+g` `Space+g+s` `Space+g+d` | Getter/Setter生成（[Java開発支援](04-java-tooling.md)） |
| `Space+i+o` | import整理（Java）/ `#include`整理（C）（[Java](04-java-tooling.md) / [C](11-c-tooling.md)） |
| `Ctrl+Shift+O` | カーソル位置に `@Override` + 改行を挿入（[Java開発支援](04-java-tooling.md)） |
| `[g` `[d` | 次/前のエラー・警告行へ（Java/C 共通。[Java](04-java-tooling.md) / [C](11-c-tooling.md)） |

### モード遷移・検索・ナビゲーション

| キー | 動作 |
|---|---|
| `v` `V` `Ctrl+V` | VISUAL / VISUAL LINE / VISUAL BLOCK へ |
| `gv` | 直前のVISUAL選択を復元 |
| `:` `;` | COMMANDへ |
| `/` | SEARCHへ（[検索・ナビゲーション](03-search-and-navigation.md)） |
| `n` `N` `*` `#` | 検索ジャンプ・単語検索（[検索・ナビゲーション](03-search-and-navigation.md)） |
| `Esc` `Esc`（2回連続） | 検索ハイライトを強制クリア（[検索・ナビゲーション](03-search-and-navigation.md)） |
| `\f` `\g` | ファイル名検索/grep検索（[検索・ナビゲーション](03-search-and-navigation.md)） |
| `SPC+f` `SPC+/` `SPC+b` | telescope（ファイル/grep/バッファ）（[検索・ナビゲーション](03-search-and-navigation.md)） |
| `K` | 定義・ドキュメント参照。Java=定義/Javadoc、C=定義/ヘッダジャンプ（[Java](04-java-tooling.md) / [C](11-c-tooling.md#定義ジャンプshiftk--k)） |
| `Shift+J` | `K` ジャンプ元へ戻る（Java/C 共通。[Java開発支援](04-java-tooling.md)） |
| `gr` `gR` | 参照検索・全ファイル参照検索（[検索・ナビゲーション](03-search-and-navigation.md)） |
| `sv` `ss` | ペイン垂直/水平分割（[編集支援機能](06-editing-features.md)） |
| `sh` `sk` `sl` `sj` | 前/次のペインへフォーカス移動（[編集支援機能](06-editing-features.md)） |
| `1`〜`9` / `Esc`（import選択中） | import候補選択/スキップ（[Java開発支援](04-java-tooling.md)） |

## INSERTモード

| キー | 動作 |
|---|---|
| 通常文字 | 文字挿入 |
| `Backspace` `Enter` `Escape` | 削除/改行/NORMALへ |
| `Ctrl+F` `Ctrl+B` `Ctrl+N` `Ctrl+P` | Emacs式カーソル移動（右/左/下/上） |
| `Ctrl+A` `Ctrl+E` | 行頭の非空白/行末（Emacs式） |
| `Alt+F` `Alt+B` | 次/前の単語先頭（Emacs式） |
| `Ctrl+Home` `Ctrl+End` | ファイル先頭/末尾（Emacs式） |
| `Tab` | インデント挿入 or 閉じ括弧の外へスキップ |
| `}` | 自動デデントして挿入 |
| `Ctrl+D` `Ctrl+K` `Ctrl+W` | 1文字削除/行末まで削除/1単語削除（Emacs/Vim式） |
| `Ctrl+]` `Ctrl+[` | 保存してNORMALへ |
| `Ctrl+Space` | マージ補完（[コード補完](05-completion.md)） |
| `Alt+/` | 単語補完（[コード補完](05-completion.md)） |
| `Ctrl+Shift+O` | カーソル位置に `@Override` + 改行を挿入 |

## COMMANDモード

| キー | 動作 |
|---|---|
| 通常文字 `Backspace` | コマンド文字列の編集 |
| `Tab` | `cd`/`e` のパス補完（[検索・ナビゲーション](03-search-and-navigation.md)） |
| `Enter` `Escape` | 実行/中断 |

### コマンド一覧

| コマンド | 動作 | 詳細 |
|---|---|---|
| `:w` / `:w <path>` | 保存 | 本章末尾 |
| `:e` / `:e <path>` | ファイルを開く | 本章末尾 |
| `:q` / `:wq` | ペインを閉じる/保存して閉じる | 本章末尾 |
| `:sp` `:split` / `:vs` `:vsplit` `:vsp` | 垂直/水平分割 | [編集支援機能](06-editing-features.md) |
| `:grep <pattern>` / `:grep! <pattern>` | 全文検索/全ファイル全文検索 | [検索・ナビゲーション](03-search-and-navigation.md) |
| `:rename <old> <new>` | 一括シンボルリネーム | [Java開発支援](04-java-tooling.md) |
| `:oi` `:organize-imports` / `:remove-import <fqn>` | import整理/個別削除 | [Java開発支援](04-java-tooling.md) |
| `:pwd` / `:cd <path>` | 作業ディレクトリ表示/変更 | [検索・ナビゲーション](03-search-and-navigation.md) |
| `:main <target>` | launcherエントリポイントへジャンプ | [Java開発支援](04-java-tooling.md) |
| `:s/pat/repl/[flags]` `:%s...` `:N,Ms...` `:'<,'>s...` | Vim式置換コマンド | [モーダル編集](02-modal-editing.md#置換コマンドs) |
| `:tutor` `:tutorial` | チュートリアルを開く | [チュートリアル](08-tutorial.md) |

## VISUAL / VISUAL LINE モード

| キー | 動作 |
|---|---|
| `h` `l` `j` `k` | 選択範囲拡張 |
| `w` `b` `e` `0` `^` `$` `G`（VISUALのみ） | 単語/行単位で選択範囲拡張 |
| `%` | 対応括弧まで選択拡張 |
| `Ctrl+F` `Ctrl+B` `Ctrl+D` `Ctrl+U` | スクロール（半ページ含む） |
| `>` `<` | インデント/デデント |
| `y` `d` | ヤンク/削除してNORMALへ |
| `u` `U` `~` | 小文字化/大文字化/大文字小文字反転してNORMALへ |
| `:` | `'<,'>` 付きでCOMMANDへ（`:s` 置換用） |
| `v` / `V` / `Escape` | 解除してNORMALへ |

## VISUAL BLOCKモード（`Ctrl+V`）

| キー | 動作 |
|---|---|
| `h` `j` `k` `l` `%` | 矩形範囲の拡張 |
| `y` `d` | 矩形ヤンク/矩形削除 |
| `u` `U` `~` | 矩形の列範囲を小文字化/大文字化/大文字小文字反転してNORMALへ |
| `:` | `'<,'>` 付きでCOMMANDへ（`:s` は矩形の列ではなく選択行全体が対象） |
| `I` `A` | 矩形左端/右端+1でINSERT（全行同時入力） |
| `c` | 矩形削除して `I` 相当でINSERT |
| `r` | 次の1キーで矩形範囲を一括置換 |
| `>` `<` | インデント/デデント |
| `Ctrl+V` / `Escape` | 解除してNORMALへ |

矩形ヤンク後のNORMALモード `p`/`P` は矩形貼り付けになります。詳細は [モーダル編集](02-modal-editing.md#visual-blockモード矩形選択ctrlv) を参照してください。

## SEARCH / FILESEARCH / TELESCOPE / FILER モード

| モード | 主なキー |
|---|---|
| SEARCH（`/`） | 文字入力・`Backspace`・`Enter`（検索実行）・`Escape` |
| FILESEARCH（`\f`/`\g`） | 文字入力・`Backspace`・`Enter`（検索実行、先頭`!`で全ファイル）・`Escape` |
| TELESCOPE（`SPC+f`/`SPC+/`/`SPC+b`） | 文字入力・`Backspace`・`Ctrl+N`/`Ctrl+P`・`Enter`・`Escape` |
| FILER（`:cd` 成功後） | `Ctrl+N`/`Ctrl+P`・`Enter`（開く/ドリルダウン）・`/`（内検索）・`Esc` |

詳細は [検索・ナビゲーション](03-search-and-navigation.md) を参照してください。

## コード補完ポップアップ（表示中）

| キー | 動作 |
|---|---|
| `↑` `↓` | 候補選択 |
| `Tab` `Enter` | 確定挿入 |
| `Escape` | 閉じる |

## `:w` / `:e` / `:q` の詳細

| コマンド | 動作 |
|---|---|
| `:w` | 現在のファイルパスへ上書き保存 |
| `:w <path>` | 指定パスへ保存（以降そのパスが「現在のファイル」）。`s/pattern/replacement/` によるファイル名正規表現置換にも対応 |
| `:e` / `:enew` | 新規の空バッファを開く |
| `:e <path>` | 指定ファイルを開く（バッファ差し替え・カーソルリセット） |
| `:q` | 現在のペインを閉じる（最後のペインならエディタ終了） |
| `:wq` | 保存してから `:q` |
