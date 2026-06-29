# Java Text Editor

Vimのモーダルキーバインドとエディタ拡張性を統合した、Java SE製の軽量テキストエディタ。

## 特徴

- **モーダル編集**: Vim式のNORMAL/INSERT/COMMAND/VISUAL/VISUAL LINEモードを採用
- **Emacs式カーソル移動**: INSERTモード中でも `Ctrl+F/B/N/P` で移動可能
- **コマンドラインモード**: `:w` でファイル保存、`:e` でファイルを開く、`:q` で終了
- **アンドゥ/リドゥ**: NORMALモードで `u` でアンドゥ、`Ctrl+R` でリドゥ
- **VISUALモード**: 文字単位の範囲選択・ヤンク・削除・貼り付け
- **VISUAL LINEモード**: 行単位の範囲選択・ヤンク・削除・行単位ペースト
- **高速バッファ**: ピーステーブル方式により、大規模ファイル（数十万行）でも高速に挿入・削除
- **縦スクロール**: カーソルが画面外に出ると自動追従
- **プラグインシステム**: `javax.tools.JavaCompiler` による動的コンパイルで Java ファイルをプラグインとしてロード
- **プラグイン公開API**: テキスト読み取り・編集・カーソル操作・キーバインド登録を `EditorContext` 経由で提供
- **境界値テスト**: 空バッファ・1文字・行末・行頭など極端なケースを体系的に網羅
- **パフォーマンステスト**: 10万行ファイルopen・大規模文書への1000回挿入/削除・offsetOfLine速度計測
- **ソース解析エンジン**: Compiler Tree APIでJavaソースをparse-only解析し、import索引・シンボル索引を構築
- **コンパイルエラー表示**: `JavacTask.analyze()` による型解決まで含むコンパイルエラー・警告をガター（E/Wマーカー）と波下線でリアルタイム表示
- **JDK APIナビゲーション**: NORMALモードの `K` キーでカーソル位置の識別子を JDK クラスとして検索し、ステータスバーに表示
- **Javadoc ビューア**: `K` キーの表示をローカル Javadoc HTML のサマリ文に格上げ。`openjdk-21-doc` がインストールされていれば `"ArrayList: Resizable-array implementation of the List interface."` 形式で表示し、未インストール時は従来の種別・メソッド数・フィールド数表示にフォールバック
- **auto-import**: INSERT→NORMAL 復帰時にコンパイルエラーを解析し、未解決の型名に対して JDK クラス索引から候補を検索。候補1件なら即自動挿入。複数ある場合はステータスバーに `[1] java.util.List  [2] java.awt.List  [Esc]=skip` 形式で候補を表示し、数字キーで選択
- **import 削除（organize imports）**: `Ctrl+Shift+O`（Eclipse互換）、`SPC+i+o`、または `:oi` コマンドで未使用 import を一括削除。`:remove-import <fqn>` コマンドで特定 import を1件削除
- **診断行ジャンプ**: NORMALモードで `[g` で次のエラー/警告行へジャンプ、`[d` で前のエラー/警告行へジャンプ。ファイル末尾/先頭に達すると先頭/末尾へ折り返す
- **プロジェクト全文検索**: `:grep <pattern>` でカレントディレクトリ配下のファイルを正規表現で全文検索。結果を `*grep*` 疑似バッファに一覧表示し、`Enter` で該当ファイルの該当行へジャンプ
- **マルチファイルリファクタリング**: `:rename <oldName> <newName>` でプロジェクト全体のシンボルを一括リネーム。語境界マッチにより部分一致を回避し、結果を `*rename*` 疑似バッファに一覧表示
- **テキスト内文字列検索**: NORMALモードで `/pattern` + Enter による正規表現前方検索。`*` でカーソル位置の単語を下方向へ完全一致検索、`#` で上方向へ完全一致検索。`n`/`N` で次/前マッチへジャンプ（折り返しあり）。全マッチを半透明黄色でハイライト表示
- **ファイル名検索**: NORMALモードで `\f` を入力してFILESEARCHモードに入り、正規表現パターン（大文字小文字無視）でプロジェクト配下のファイル名を検索。結果を疑似バッファに一覧表示し、`Enter` で該当ファイルを開く
- **ファイル内容grep検索**: NORMALモードで `\g` を入力してFILESEARCHモードに入り、プロジェクト配下のファイル内容を正規表現でgrep。`:grep` コマンドと同じ疑似バッファUI（`Enter` でジャンプ）
- **telescope ファジーファインダー**: telescope.vim 風の3ペインオーバーレイUI（Prompt / Results / Preview）。`SPC+f` でファジーファイル検索、`SPC+/` でライブgrep、`SPC+b` でバッファ一覧を起動。クエリ入力のたびにリアルタイムでフィルタリング。`Ctrl+N` / `Ctrl+P` でリストを移動、`Enter` で選択ファイルを開く、`Escape` でキャンセル。ファジーマッチは連続一致+3・単語境界+2・通常+1・ギャップ-1のスコアリングで降順表示
- **OpenJDK native メソッドトレース**: `K` キーで `ClassName.methodName` 形式のカーソル位置にある native メソッドを検出し、JNI マングル名（`Java_java_lang_System_arraycopy` 等）をステータスバーに表示。`src.zip`（JDK 付属）が存在すれば C/C++ 実装ファイルの位置とコードスニペットも表示。存在しない場合は "no JDK source available" にフォールバック。
- **Space リーダーキー**: NORMALモードで `Space` をリーダーキーとして使用。`Space+h` → 行の最初の非空白文字、`Space+l` → 行末、`Space+k` → ファイル先頭（gg相当）、`Space+j` → ファイル末尾（G相当）。`Space+g+g/s/d` → Getter/Setter 自動生成
- **Getter/Setter 自動生成**: カーソル行のフィールド宣言（例: `private int hp;`）を解析して getter/setter を クラス末尾の `}` 直前に挿入。`Space+g+g` → getter、`Space+g+s` → setter、`Space+g+d` → 両方
- **行入れ替え**: NORMALモードで `Alt+J` で現在行と次行を入れ替え、`Alt+K` で現在行と前行を入れ替え
- **ペインナビゲーション**: `s` プレフィックスからペイン操作を拡張。`sh`/`sk` で前のペインへ、`sl`/`sj` で次のペインへフォーカス移動
- **INSERT保存**: INSERT モードで `Ctrl+]` または `Ctrl+[` を押すと NORMAL モードへ戻りつつ即座にファイル保存
- **INSERT文字削除**: `Ctrl+D` でカーソル位置の1文字を削除（Emacsの `delete-char` 相当）、`Ctrl+K` でカーソルから行末まで削除（Emacsの `kill-line` 相当）
- **INSERT単語削除**: `Ctrl+W` でカーソル直前の1単語を削除（Vim互換）。空白をスキップした後、単語文字（英数字・`_`）または記号のまとまりをまとめて削除。行頭をまたがない
- **Tabペアスキップ**: INSERT モードの Tab キーが `)` `]` `}` `"` `'` `>` の直前にある場合、スペース挿入をせず閉じ括弧の外側へカーソルをスキップ
- **Java SE標準APIのみ**: 外部ライブラリ不使用。Java 21で動作

## 必要環境

- Java 21 (LTS)
- JDK（`javac` が使えること）

## ビルドと実行

```bash
# ビルド
./scripts/build.sh

# 実行（デモテキストで起動）
./scripts/run.sh

# ファイルを指定して起動
./scripts/run.sh /path/to/file.txt

# テスト
./scripts/test.sh
```

Windowsの場合は `build.bat` / `run.bat` / `test.bat` を使用してください。

## キーバインド

### grep 結果バッファ（`:grep` 実行後）

| キー | 動作 |
|------|------|
| `j` / `k` | 結果行を移動 |
| `Enter` | カーソル行の結果ファイルを開き、該当行へジャンプ |
| `:e <path>` / `:grep <pattern>` | 通常ファイルに戻る・再検索する |

### NORMALモード

| キー | 動作 |
|------|------|
| `h` | カーソルを左に移動 |
| `l` | カーソルを右に移動 |
| `j` | カーソルを下に移動 |
| `k` | カーソルを上に移動 |
| `i` | INSERTモードへ（カーソル前に挿入） |
| `a` | INSERTモードへ（カーソル後に挿入） |
| `o` | 現在行の下に新しい行を開いてINSERTモードへ |
| `u` | 直前の編集を取り消す（アンドゥ） |
| `Ctrl+R` | 取り消した編集をやり直す（リドゥ） |
| `v` | VISUALモードへ（文字単位選択） |
| `V` | VISUAL LINEモードへ（行単位選択） |
| `yy` | 現在行をヤンク |
| `dd` | 現在行を削除してヤンク |
| `x` | カーソル位置の1文字を削除 |
| `p` | ヤンクレジスタの内容をカーソルの後ろに貼り付け（文字）または現在行の下に貼り付け（行） |
| `P` | ヤンクレジスタの内容をカーソルの前に貼り付け（文字）または現在行の上に貼り付け（行） |
| `K` | カーソル位置の識別子を JDK クラスとして検索し、ステータスバーに表示（Javadoc がインストールされていればサマリ文を表示、未インストール時は種別・メソッド数・フィールド数を表示）。`ClassName.methodName` 形式でメソッド上にカーソルがある場合は native メソッドを自動検出し JNI マングル名を表示（src.zip があればソース位置も表示） |
| `:` | COMMANDモードへ（画面下部にコマンド入力欄が表示される） |
| `1`〜`9`（import選択中） | 複数候補がある場合に番号を押して import を選択して挿入 |
| `Esc`（import選択中） | 現在の import 候補をスキップして次のシンボルへ |
| `w` / `b` / `e` | 次の単語先頭 / 前の単語先頭 / 単語末尾へ移動 |
| `0` | 行の絶対先頭へ移動 |
| `^` | 行の最初の非空白文字へ移動 |
| `$` | 行末へ移動 |
| `gg` | ファイル先頭へ移動 |
| `G` | ファイル末尾へ移動 |
| `Alt+J` | 現在行と次行を入れ替え（行を下方向に移動） |
| `Alt+K` | 現在行と前行を入れ替え（行を上方向に移動） |
| `Space+h` | 行の最初の非空白文字へ移動（`^` 相当） |
| `Space+l` | 行末へ移動（`$` 相当） |
| `Space+k` | ファイル先頭へ移動（`gg` 相当） |
| `Space+j` | ファイル末尾へ移動（`G` 相当） |
| `Space+g+g` | カーソル行のフィールド宣言から getter を生成 |
| `Space+g+s` | カーソル行のフィールド宣言から setter を生成 |
| `Space+g+d` | カーソル行のフィールド宣言から getter と setter を両方生成 |
| `Space+i+o` | 未使用 import を一括削除（organize imports） |
| `Ctrl+Shift+O` | 未使用 import を一括削除（Eclipse 互換。NORMAL/INSERT 両モードで有効） |
| `[g` | 次のエラー/警告行へジャンプ（末尾に達すると先頭へ折り返し） |
| `[d` | 前のエラー/警告行へジャンプ（先頭に達すると末尾へ折り返し） |
| `sv` | 左右に画面分割（垂直スプリット） |
| `ss` | 上下に画面分割（水平スプリット） |
| `sh` / `sk` | 前のペインへフォーカス移動 |
| `sl` / `sj` | 次のペインへフォーカス移動 |
| `/` | SEARCHモードへ（パターン入力を開始） |
| `n` | 最後の検索の同方向で次のマッチへジャンプ（折り返しあり） |
| `N` | 最後の検索の逆方向で前のマッチへジャンプ（折り返しあり） |
| `*` | カーソル位置の単語を下方向（後方）へ完全一致検索（`\b` 単語境界） |
| `#` | カーソル位置の単語を上方向（前方）へ完全一致検索（`\b` 単語境界） |
| `\f` | FILESEARCHモードへ（ファイル名検索を開始） |
| `\g` | FILESEARCHモードへ（ファイル内容grep検索を開始） |
| `SPC+f` | TELESCOPEモードへ（ファジーファイル検索） |
| `SPC+/` | TELESCOPEモードへ（ライブgrep） |
| `SPC+b` | TELESCOPEモードへ（バッファ一覧） |

### SEARCHモード（`/` 入力後）

| キー | 動作 |
|------|------|
| 通常文字 | 検索パターンに文字を追加 |
| `Backspace` | パターンの末尾の文字を削除 |
| `Enter` | 入力したパターンで前方検索を実行してNORMALモードへ戻る。ステータスバーに `/pattern [N/M]` 形式でマッチ位置を表示 |
| `Escape` | 検索をキャンセルしてNORMALモードへ戻る（ハイライトもクリア） |

### FILESEARCHモード（`\f` または `\g` 入力後）

| キー | 動作 |
|------|------|
| 通常文字 | 検索パターンに文字を追加 |
| `Backspace` | パターンの末尾の文字を削除 |
| `Enter` | パターンで検索を実行してNORMALモードへ戻る。`\f` はファイル名検索（大文字小文字無視）、`\g` はファイル内容grep |
| `Escape` | 検索をキャンセルしてNORMALモードへ戻る |

### TELESCOPEモード（`SPC+f` / `SPC+/` / `SPC+b` 入力後）

3ペインオーバーレイ（Prompt・Results・Preview）が画面中央に表示される。

| キー | 動作 |
|------|------|
| 通常文字 | クエリバッファに追加してリアルタイムフィルタリング |
| `Backspace` | クエリの末尾の文字を削除 |
| `Ctrl+N` | 次の候補へ移動 |
| `Ctrl+P` | 前の候補へ移動 |
| `Enter` | 選択中の候補ファイルを開いてNORMALモードへ戻る（grep結果は該当行へジャンプ） |
| `Escape` | キャンセルしてNORMALモードへ戻る |

### VISUALモード

| キー | 動作 |
|------|------|
| `h` / `l` / `j` / `k` | 選択範囲を拡張（カーソル移動） |
| `y` | 選択範囲をヤンク（文字単位）してNORMALモードへ戻る |
| `d` | 選択範囲を削除してヤンク（文字単位）し、NORMALモードへ戻る |
| `Escape` | VISUALモードを解除してNORMALモードへ戻る |

### VISUAL LINEモード

| キー | 動作 |
|------|------|
| `h` / `l` / `j` / `k` | 選択行範囲を拡張（行移動） |
| `y` | 選択行をヤンク（行単位）してNORMALモードへ戻る |
| `d` | 選択行を削除してヤンク（行単位）し、NORMALモードへ戻る |
| `Escape` | VISUAL LINEモードを解除してNORMALモードへ戻る |

### COMMANDモード

| コマンド | 動作 |
|----------|------|
| `:w` | 現在のファイルパスへ上書き保存 |
| `:w <path>` | 指定したパスへ保存（以降そのパスが「現在のファイル」になる） |
| `:e <path>` | 指定したファイルを開く（バッファを差し替え・カーソルをリセット） |
| `:q` | エディタを終了 |
| `:wq` | 保存してから終了 |
| `:grep <pattern>` | カレントディレクトリ配下を正規表現で全文検索し、`*grep*` 疑似バッファに結果を表示 |
| `:rename <old> <new>` | プロジェクト全体でシンボルを一括リネームし、`*rename*` 疑似バッファに変更ファイル一覧を表示 |
| `:oi` / `:organize-imports` | 未使用 import を一括削除 |
| `:remove-import <fqn>` | 指定した FQN の import 行を1件削除（例: `:remove-import java.util.List`） |
| `Backspace` | 入力済みコマンドを1文字削除 |
| `Escape` | COMMANDモードを中断してNORMALモードへ戻る |

### INSERTモード

| キー | 動作 |
|------|------|
| 通常文字 | カーソル位置に文字を挿入 |
| `Backspace` | カーソル直前の文字を削除（行頭では前行と結合） |
| `Enter` | 改行を挿入 |
| `Escape` | NORMALモードへ復帰 |
| `Ctrl+F` | カーソルを右に移動（Emacs式） |
| `Ctrl+B` | カーソルを左に移動（Emacs式） |
| `Ctrl+N` | カーソルを下に移動（Emacs式） |
| `Ctrl+P` | カーソルを上に移動（Emacs式） |
| `Ctrl+A` | 行の最初の非空白文字へ移動（Emacs式） |
| `Ctrl+E` | 行末へ移動（Emacs式） |
| `Alt+F` | 次の単語先頭へ移動（Emacs式） |
| `Alt+B` | 前の単語先頭へ移動（Emacs式） |
| `Ctrl+Home` | ファイル先頭へ移動（Emacs式） |
| `Ctrl+End` | ファイル末尾へ移動（Emacs式） |
| `Tab` | 4スペースを挿入。閉じ括弧（`)` `]` `}` `"` `'` `>`）の直前ではスペース挿入をせずカーソルを括弧の右側へスキップ |
| `}` | `}` を挿入。行がインデントのみの場合は1レベル（4スペース）分インデントを自動削減してから挿入 |
| `Ctrl+D` | カーソル位置の1文字を削除（Emacsの `delete-char` 相当） |
| `Ctrl+K` | カーソルから行末まで削除（Emacsの `kill-line` 相当） |
| `Ctrl+W` | カーソル直前の1単語を削除（Vim互換）。空白スキップ後、単語文字または記号のまとまりをまとめて削除。行頭をまたがない |
| `Ctrl+]` / `Ctrl+[` | NORMALモードへ戻りつつ即座にファイル保存 |

## ディレクトリ構成

```
project-root/
├── src/dev/javatexteditor/
│   ├── Main.java               # エントリポイント・GUI初期化
│   ├── buffer/
│   │   ├── Piece.java               # ピーステーブルのピース（record）
│   │   ├── PieceTable.java          # バッファ本体（insert/delete/getText）
│   │   └── UndoablePieceTable.java  # アンドゥ/リドゥ対応バッファ（PieceTable継承）
│   ├── editor/
│   │   ├── KeyBinding.java          # キーバインド（record）
│   │   ├── KeymapRegistry.java      # モード別キーバインド管理・カスタムアクション登録
│   │   └── ModalEditor.java         # モード管理・カーソル管理・キー処理
│   ├── extension/
│   │   ├── EditorContext.java       # プラグイン公開APIインタフェース
│   │   ├── EditorPlugin.java        # プラグインインタフェース
│   │   ├── PluginLoader.java        # JavaCompiler 動的ロード
│   │   └── SimpleEditorContext.java # ModalEditor → EditorContext アダプタ
│   ├── analysis/
│   │   ├── AnalysisException.java  # checked exception
│   │   ├── CompileAnalyzer.java    # JavacTask.analyze() 型解決込みコンパイル診断収集
│   │   ├── CompileDiagnostic.java  # 診断1件 record (lineNumber/column/message/kind)
│   │   ├── DiagnosticKind.java     # ERROR / WARNING enum
│   │   ├── ImportEntry.java        # import 文1件 (record)
│   │   ├── JdkClassIndex.java      # jrt:/ 走査による JDK クラス名→FQN インデックス
│   │   ├── JdkJavadocReader.java   # ローカル Javadoc HTML からサマリ文を抽出・キャッシュ
│   │   ├── JdkTypeInfo.java        # リフレクションによるクラス情報 record
│   │   ├── OpenjdkSourceTracer.java # native メソッドの JNI マングル名計算・src.zip 検索
│   │   ├── SourceAnalyzer.java     # Compiler Tree API 解析本体（parse-only）
│   │   ├── SourceIndex.java        # 解析結果 (record)
│   │   ├── SymbolEntry.java        # シンボル1件 (record)
│   │   └── SymbolKind.java         # CLASS/INTERFACE/ENUM/METHOD/FIELD/CONSTRUCTOR
│   ├── search/
│   │   ├── FileNameSearcher.java # ファイル名を正規表現で検索（大文字小文字無視）
│   │   ├── ProjectSearcher.java  # Files.walkFileTree + java.util.regex による全文検索エンジン
│   │   └── SearchResult.java     # 検索結果1件 record (filePath/lineNumber/lineContent)
│   │   # ※ バッファ内文字列検索（/ * # n N）は ModalEditor に直接実装
│   ├── refactor/
│   │   ├── RenameRefactorer.java # 語境界付き正規表現による複数ファイル一括リネームエンジン
│   │   └── RenameResult.java     # リネーム結果1件 record (filePath/replacementCount/success)
│   └── ui/
│       ├── Theme.java          # カラーテーマ（LIGHT_MODE / DARK_MODE）
│       └── EditorCanvas.java   # Swing描画コンポーネント
├── test/dev/javatexteditor/
│   ├── buffer/
│   │   ├── PieceTableTest.java           # 正常系・基本境界値
│   │   ├── PieceTableEdgeCaseTest.java   # 空バッファ・多数操作・境界削除
│   │   ├── UndoablePieceTableTest.java   # アンドゥ/リドゥ基本動作
│   │   └── UndoRedoDeepTest.java         # 深いアンドゥチェーン・交互操作
│   ├── editor/
│   │   ├── KeymapRegistryTest.java
│   │   ├── ModalEditorTest.java
│   │   └── ModalEditorEdgeCaseTest.java  # カーソルクランプ・マルチバイト・深いアンドゥ
│   ├── analysis/
│   │   ├── SourceAnalyzerTest.java      # import/シンボル索引・構文エラー耐性・行番号テスト
│   │   ├── CompileAnalyzerTest.java     # 型解決エラー・診断行番号・setDiagnostics・フックテスト
│   │   └── JdkJavadocReaderTest.java    # HTML解析・エンティティ・タグ除去・キャッシュ・graceful degradation
│   ├── extension/
│   │   ├── EditorContextApiTest.java  # EditorContext API の結合テスト
│   │   └── PluginLoaderTest.java
│   ├── performance/
│   │   └── LargeFileTest.java            # 大規模ファイルパフォーマンス計測
│   ├── search/
│   │   ├── FileSearchTest.java           # ファイル名検索（\f）・ファイル内容grep（\g）テスト
│   │   ├── ProjectSearchTest.java        # ProjectSearcher + :grep コマンド統合テスト
│   │   └── TextSearchTest.java           # バッファ内文字列検索（/ * # n N）テスト
│   ├── refactor/
│   │   └── MultiFileRefactoringTest.java # RenameRefactorer + :rename コマンド統合テスト
│   └── ui/
│       ├── EditorCanvasTest.java
│       ├── ScrollPreview.java       # スクロール動作目視確認用
│       ├── VisualModePreview.java   # VISUALモード目視確認用
│       ├── VisualPreview.java       # GUI描画目視確認用
│       └── YankPasteDemo.java      # ヤンク/ペースト動作実演用
├── docs/
│   └── requirements.md
└── scripts/
    ├── build.sh / build.bat
    ├── test.sh  / test.bat
    └── run.sh   / run.bat
```

## アーキテクチャ

### バッファ: ピーステーブル方式

テキストの挿入・削除はバッファ全体をコピーせず、「ピース（範囲参照）」のリストを操作することで実現しています。大規模ファイルでも定数時間に近い挿入・削除が可能です。

```
PieceTable
  ├── original: String        （初期テキスト。変更しない）
  ├── addBuffer: StringBuilder （挿入テキストの追記バッファ）
  └── pieces: List<Piece>     （original/addBufferへの範囲参照リスト）

UndoablePieceTable（PieceTable継承）
  ├── undoStack: Deque<List<Piece>>  （編集前スナップショットのスタック）
  └── redoStack: Deque<List<Piece>>  （リドゥ用スナップショットのスタック）
```

アンドゥ/リドゥは `pieces` リストのコピー（参照のみ、実データ複製なし）をスタックに積む方式で実現しているため、スナップショットのコストはほぼゼロです。

### モーダル編集エンジン: ModalEditor と KeymapRegistry

`ModalEditor` がモード状態・カーソル位置を管理し、`PieceTable`（バッファ）と`EditorCanvas`（描画）を橋渡しします。キーバインドは `KeymapRegistry` により一元管理され、モード別に設定可能です。

```
キー入力 (KeyboardFocusManager)
    ↓
ModalEditor.processKey(keyCode, keyChar, modifiers)
    ├── KeymapRegistry.resolve(mode, keyCode, keyChar, modifiers)
    │   → アクション名の取得（ハードコード不要・外部から設定変更可能）
    │
    ├── NORMAL モード: cursor.left/right/up/down, enter.insert, enter.insert.after,
    │                  enter.insert.newline, undo, redo, yank.pending, delete.pending,
    │                  enter.visual, enter.visual.line, delete.char, paste.after, paste.before,
    │                  enter.command
    ├── INSERT モード: cursor.right/left/up/down, enter.normal, delete.before, insert.newline
    ├── COMMAND モード: enter.normal, execute.command
    ├── VISUAL モード: cursor.left/right/up/down, yank, delete, enter.normal
    └── VISUAL LINE モード: cursor.left/right/up/down, yank, delete, enter.normal
            ↓
    PieceTable.insert() / delete()           ← バッファ更新
    Files.writeString() / Files.readString() ← ファイルI/O
    EditorCanvas.setText() / setCursor()
        / setInsertMode() / setVisualMode() / setVisualLineMode()
        / setSelection() / setCommandLineText()    ← 再描画
```

**KeymapRegistry** は、モード別のキーバインドを一元管理し、以下の機能を提供します：

- `loadDefaults()`: デフォルトキーマップを定義（Vim標準 + Emacs式INSERTモード移動）
- `bind(mode, keyBinding, actionName)`: 新規キーバインドの登録・既存バインドの上書き
- `resolve(mode, keyCode, keyChar, modifiers)`: キー入力からアクション名を解決
- `registerAction(actionName, handler)`: カスタムアクションハンドラの登録（プラグインから呼び出し可能）
- `getCustomAction(actionName)`: 登録済みのカスタムハンドラを取得

カスタムアクションはビルトインアクションに優先して実行されるため、既存のキー動作を上書きすることも可能です。

### プラグインシステム

#### プラグインの作り方

`EditorPlugin` インタフェースを実装した Java ファイルを用意し、`PluginLoader.loadPlugin(Path)` でロードします。JDK の `javax.tools.JavaCompiler` を使ってエディタ起動中に動的コンパイル・ロードが行われます。

```java
// MyPlugin.java
import dev.javatexteditor.extension.EditorPlugin;
import dev.javatexteditor.extension.EditorContext;
import dev.javatexteditor.editor.KeymapRegistry;
import dev.javatexteditor.editor.KeyBinding;

public class MyPlugin implements EditorPlugin {
    public String getName() { return "myplugin"; }

    public void execute(EditorContext ctx) {
        // テキスト読み取り
        String line = ctx.getLine(ctx.getCursorRow());

        // テキスト挿入
        int offset = ctx.offsetAt(ctx.getCursorRow(), 0);
        ctx.insertAtOffset(offset, "// ");

        // カーソル移動
        ctx.setCursor(0, 0);

        // カスタムキーバインド登録
        ctx.getKeymap().registerAction("my.greet",
            () -> ctx.setStatusMessage("Hello from plugin!"));
        ctx.getKeymap().bind(KeymapRegistry.Mode.NORMAL,
            KeyBinding.ofChar('Q', "my.greet"), "my.greet");
    }
}
```

#### EditorContext 公開 API

| カテゴリ | メソッド | 説明 |
|---|---|---|
| テキスト読み取り | `getText()` | バッファ全体のテキスト |
| テキスト読み取り | `length()` | バッファの総文字数 |
| テキスト読み取り | `getLineCount()` | 総行数 |
| テキスト読み取り | `getLine(int row)` | 指定行のテキスト（0始まり、範囲外は空文字列） |
| テキスト操作 | `insertAtOffset(int, String)` | 指定オフセットに文字列を挿入 |
| テキスト操作 | `deleteRange(int, int)` | 指定範囲（排他）を削除 |
| カーソル読み取り | `getCursorRow()` | カーソルの行番号 |
| カーソル読み取り | `getCursorCol()` | カーソルの列番号 |
| カーソル読み取り | `offsetAt(int row, int col)` | (row, col) を絶対文字オフセットに変換 |
| カーソル操作 | `setCursor(int row, int col)` | カーソルを移動（範囲外は自動クランプ） |
| モード問い合わせ | `isNormalMode()` | NORMALモードなら true |
| モード問い合わせ | `isInsertMode()` | INSERTモードなら true |
| UI | `setStatusMessage(String)` | ステータスバーにメッセージ表示 |
| キーマップ | `getKeymap()` | `KeymapRegistry` を返す（キーバインド登録・変更に使用） |

### ソース解析エンジン: SourceAnalyzer

`SourceAnalyzer` は JDK 標準の Compiler Tree API (`com.sun.source.tree.*`) を使って Java ソースを parse-only モードで解析し、`SourceIndex` を生成します。型解決を行わないため高速（通常ファイルで 200ms 以内）で、構文エラーがあっても部分的に解析を継続します（graceful degradation）。

```
analyzeText(String sourceCode)   ← バッファ内容を直接解析（ファイル保存不要）
analyzeFile(Path path)           ← ファイルパスから解析

SourceIndex
  ├── filePath: String                  // "<buffer>" or 絶対パス
  ├── imports: List<ImportEntry>        // import 文の一覧（fqn / isStatic / isWildcard / lineNumber）
  ├── symbols: List<SymbolEntry>        // トップレベル型のクラス・メソッド・フィールド・コンストラクタ
  └── hasParseError: boolean            // 構文エラーがあったかどうか
```

**収集スコープ**: トップレベル型宣言の直接メンバーのみ。ネストしたクラスは収集対象外。  
**行番号**: 0-indexed（`getLineNumber() - 1` で変換）。  
**後続機能の基盤**: ⑨ javac連携（コンパイルエラー表示）・⑩ JDKナビゲーション・⑭ マルチファイルリファクタリングで再利用される。

### コンパイルエラー表示: CompileAnalyzer

`SourceAnalyzer`（parse-only）とは別に、`CompileAnalyzer` が `JavacTask.analyze()` まで実行して型解決エラーも収集します。

```
CompileAnalyzer.analyze(String sourceCode)    ← バッファ文字列を直接解析
CompileAnalyzer.analyzeFile(Path path)        ← ファイルパスから解析

→ List<CompileDiagnostic>
     CompileDiagnostic.lineNumber()  // 0-indexed
     CompileDiagnostic.column()      // 0-indexed
     CompileDiagnostic.message()     // javac のエラーメッセージ
     CompileDiagnostic.kind()        // DiagnosticKind.ERROR / WARNING
```

`EditorCanvas.setDiagnostics(List<CompileDiagnostic>)` で診断をセットすると：

- **ガター列**（左端 2文字分）: エラー行に赤い `E`、警告行に黄色い `W` を表示
- **波下線**: エラー/警告行のテキスト下に 4px 周期の波線を描画
- **ステータスバー右端**: `2 errors, 1 warning` 形式で件数を表示
- 診断が空のときはガター幅 = 0（既存の描画レイアウトに影響なし）

バックグラウンドコンパイルは `ModalEditor.setOnReturnToNormal(Runnable)` で INSERT→NORMAL 復帰時、および `setOnSave(Runnable)` でファイル保存時にトリガーします。UI スレッドをブロックしないよう、コンパイルは仮想スレッドで実行し `SwingUtilities.invokeLater()` で結果を反映します。ファイルパスがある場合は `analyzeFile()` で当該ファイルを解析し、バッファのみの場合は `analyze()` でバッファ内容を解析します。コンパイラが使えない環境でも静かに失敗し、ガターと診断表示をリセットします。

### JDK API ナビゲーション: JdkClassIndex / JdkTypeInfo / JdkJavadocReader

NORMALモードの `K` キーで、カーソル位置の識別子を JDK クラスとして検索し、情報をステータスバーに1行表示します。

```
起動時: JdkClassIndex.build()（バックグラウンドスレッドで jrt:/ を走査）
  ↓ /modules/<module>/<pkg>/<Name>.class → <pkg>.<Name> に変換してインデックス化
  ↓ 匿名クラス・内部クラス（$ を含む FQN）は除外

K キー押下: wordAtCursor() でカーソル位置の識別子を抽出
  ↓ JdkClassIndex.lookup(simpleName) → FQN 候補リスト
  ↓ java.lang.* → java.util.* → その他 の優先順で候補を選択
  ↓ JdkClassIndex.loadClass(fqn) → Class.forName() でリフレクション
  ↓ ModalEditor.buildDocLine()
      ├─ JdkJavadocReader.readSummary(fqn)
      │    ├─ Javadoc HTML あり → <div class="block"> の first sentence を抽出
      │    │     → "ArrayList: Resizable-array implementation of the List interface."
      │    └─ Javadoc HTML なし → Optional.empty()（graceful degradation）
      └─ Optional.empty() の場合: JdkTypeInfo.from(cls).toStatusLine()
           → "ArrayList - class (java.util) [42 methods, 0 fields]"
  ↓ ModalEditor.setStatusMessage() でステータスバーに表示
```

**JdkJavadocReader の Javadoc 検索パス（優先順）**:
1. システムプロパティ `jte.javadoc.path` で明示指定したディレクトリ
2. `$JAVA_HOME/docs/api/`
3. `/usr/share/doc/openjdk-<N>-doc/api/`（Debian/Ubuntu 系）

インデックス未完了時は `"JDK index building..."` を表示し、存在しない識別子は `"Not found in JDK: <word>"` を表示します。

### プロジェクト全文検索: ProjectSearcher

`:grep <pattern>` コマンドで、カレントディレクトリ（`System.getProperty("user.dir")`）配下のファイルを正規表現で検索します。

```
:grep <pattern>
  ↓ ProjectSearcher.search(baseDir, pattern)
      ├─ Files.walkFileTree() で再帰的にファイルを走査
      │   ├─ .git / build / target ディレクトリはスキップ
      │   ├─ NUL バイトを含むファイルはバイナリと判定してスキップ
      │   └─ UTF-8 でデコードできないファイルはスキップ（graceful degradation）
      ├─ 各行に java.util.regex.Pattern でマッチ判定
      └─ SearchResult(filePath, lineNumber, lineContent) のリストを返す
  ↓ 結果をバッファに展開（*grep* ヘッダ + path:line: content 形式）
  ↓ ModalEditor.grepResults に SearchResult リストを保持

NORMALモードで Enter キー（grep 結果バッファ内）
  ↓ cursorRow - 1 で SearchResult インデックスを特定
  ↓ Files.readString() でファイルを読み込み
  ↓ cursorRow = lineNumber - 1 で該当行へジャンプ
  ↓ grepResults = null（通常バッファモードに復帰）
```

**対応ケース**:
- `:grep <pattern>` — `java.util.regex.Pattern` 形式の正規表現（大文字小文字区別あり）
- 不正パターン → `E: bad pattern: <description>` エラー
- 一致なし → `grep: no matches for /<pattern>/` メッセージ
- `:e <path>` で別ファイルを開くと grep モードが自動解除される

### マルチファイルリファクタリング: RenameRefactorer

`:rename <oldName> <newName>` コマンドで、プロジェクト全体にわたってシンボル名を一括置換します。

```
:rename OldName NewName
  ↓ RenameRefactorer.rename(baseDir, oldName, newName)
      ├─ Phase 1: 発見
      │   ├─ \bOldName\b（語境界付き）パターンで ProjectSearcher.search() を呼び出す
      │   └─ 一致したファイルのパスを重複排除して収集
      │
      ├─ Phase 2 & 3: 置換と保存
      │   ├─ 各ファイルを UTF-8 で読み込み
      │   ├─ Matcher で全マッチを NewName に置換（件数をカウント）
      │   └─ Files.writeString() で上書き保存
      │
      └─ RenameResult(filePath, replacementCount, success, errorMessage) のリストを返す
  ↓ 結果をバッファに展開（*rename* ヘッダ + path: N replacement(s) 形式）
  ↓ ModalEditor.statusMessage に合計置換件数とファイル数を表示
```

**語境界マッチの効果**:
- `:rename Foo Bar` は `Foo` を `Bar` に置換するが、`FooBar`・`aFoo` には触れない
- `Pattern.quote(oldName)` で特殊文字をエスケープし、`\b...\b` で語境界を付与

**対応ケース**:
- `:rename <old> <new>` — スペース区切りで2つの引数を要求
- 引数不足 → `E: usage: rename <oldName> <newName>` エラー
- 一致なし → `rename: no occurrences of '<old>' found` メッセージ
- 書き込み失敗（権限なし等）→ 該当ファイルのみ `ERROR` 表示、他のファイルは継続
- 型解析は行わない（parse-only）— 同名の別シンボルも置換される点に注意

### バッファ内文字列検索: ModalEditor (SEARCH モード)

`/` キーでエディタを **SEARCH モード** に切り替え、ステータスバーに `/pattern` を表示しながらパターンを入力します。`Enter` 押下で `java.util.regex.Pattern` を使ってバッファ全体を検索し、マッチ位置をリスト化します。

```
/ キー押下
  ↓ SEARCH モードへ遷移（searchBuffer クリア）
  ↓ ステータスバーに "/" + 入力中パターンをリアルタイム表示

Enter キー（SEARCH モード内）
  ↓ Pattern.compile(pattern)      ← 正規表現パターンをコンパイル
  ├─ PatternSyntaxException → "E: bad pattern: ..." をステータス表示
  ↓ Matcher.find() ループで全マッチのオフセット/長さを収集
  ↓ 現在カーソルより後方の最初のマッチ（なければ先頭へ折り返し）へジャンプ
  ↓ updateSearchHighlights()
      ├─ offsetToRowCol() で各マッチを {row, startCol, endCol} セグメントに変換
      │   （マルチライン・マッチは行ごとに分割）
      └─ EditorCanvas.setSearchHighlights() → 半透明黄色（#FFE000, alpha=90）で矩形描画

n / N キー（NORMAL モード）
  ↓ currentMatchIdx を ±1（% size で折り返し）
  ↓ moveCursorToOffset(searchMatches[idx][0])
  ↓ ステータスバーに "/pattern [N/M]" 表示

* / # キー（NORMAL モード）
  ↓ wordAtCursor() でカーソル位置の識別子を取得
  ↓ "\\b" + Pattern.quote(word) + "\\b" で語境界付きパターンを生成
  ↓ executeSearch() を呼び出し（*: 前方, #: 後方）
```

**検索ハイライトのクリア条件**: Esc キー（SEARCH モード）またはファイル読み込み時。バッファ内容変更時には自動更新しない（`n` 押下時に再実行で対応）。

### GUI描画: EditorCanvas

`JPanel` を継承した `EditorCanvas` が `Graphics2D` で直接描画します。

- 全角文字（CJK・ひらがな・カタカナ）を2セル幅として正確に描画
- NORMALモード: ブロックカーソル（前景色の矩形）
- INSERTモード: 縦棒カーソル（2px幅）
- VISUALモード: 選択範囲を文字単位でアクセントカラーでハイライト表示
- VISUAL LINEモード: 選択行を行全幅でアクセントカラーでハイライト表示
- 画面最下部にステータス行（`-- NORMAL --` / `-- INSERT --` / `-- VISUAL --` / `-- VISUAL LINE --` / `:コマンド入力中` / 操作結果メッセージ）
- 縦スクロール対応（カーソルが画面外に出ると自動追従）

## テスト結果

```
=== dev.javatexteditor.analysis.AutoImportHandlerTest ===          PASS: 42 / 42
=== dev.javatexteditor.analysis.CompileAnalyzerTest ===            PASS: 15 / 15
=== dev.javatexteditor.analysis.JdkClassIndexTest ===              PASS: 18 / 18
=== dev.javatexteditor.analysis.JdkJavadocReaderTest ===           PASS: 15 / 15
=== dev.javatexteditor.analysis.OpenjdkSourceTracingTest ===       PASS: 29 / 29
=== dev.javatexteditor.analysis.SourceAnalyzerTest ===             PASS: 49 / 49
=== dev.javatexteditor.buffer.PieceTableTest ===                   PASS: 15 / 15
=== dev.javatexteditor.buffer.PieceTableEdgeCaseTest ===           PASS: 46 / 46
=== dev.javatexteditor.buffer.UndoablePieceTableTest ===           PASS: 11 / 11
=== dev.javatexteditor.buffer.UndoRedoDeepTest ===                 PASS: 20 / 20
=== dev.javatexteditor.editor.KeymapRegistryTest ===               PASS: 49 / 49
=== dev.javatexteditor.editor.ModalEditorTest ===                  PASS: 235 / 235
=== dev.javatexteditor.editor.ModalEditorEdgeCaseTest ===          PASS: 23 / 23
=== dev.javatexteditor.extension.EditorContextApiTest ===          PASS: 39 / 39
=== dev.javatexteditor.extension.PluginLoaderTest ===              PASS: 9 / 9
=== dev.javatexteditor.performance.LargeFileTest ===               PASS: 12 / 12
=== dev.javatexteditor.refactor.MultiFileRefactoringTest ===       PASS: 19 / 19
=== dev.javatexteditor.search.ProjectSearchTest ===                PASS: 19 / 19
=== dev.javatexteditor.search.FileSearchTest ===                   PASS: 43 / 43
=== dev.javatexteditor.search.TextSearchTest ===                   PASS: 34 / 34
=== dev.javatexteditor.ui.EditorCanvasTest ===                     PASS: 22 / 22
=== dev.javatexteditor.ui.KeyboardSimulationTest ===               PASS: 110 / 110
=== dev.javatexteditor.ui.RobotKeyInputTest ===                    PASS: 134 / 134  (Xvfb 仮想ディスプレイ)

合計: 1008 テストケース全 PASS
```

> **RobotKeyInputTest について**: `java.awt.Robot` は `DISPLAY` 環境変数が必要です。Xvfb（`Xvfb :99`）などの仮想ディスプレイがあれば CI 環境でも実行可能です。`Shift` 修飾を含む `:` / `V` / `P` / `Shift+K` キーの実イベント経由の動作と auto-import の選択 UI・`:rename` コマンドのファイル書き換え・native メソッドの JNI トレースを全件検証しています。

### ⑫ openjdk-source-tracing で追加したテスト（34件）

| テストクラス | 内容 |
|---|---|
| `OpenjdkSourceTracingTest` (29) | JNI マングル名計算（パッケージ付き・アンダースコアエスケープ）・native メソッド検出（System.arraycopy/String.intern/Math.sin）・非 native メソッド（String.length/存在しないメソッド）・hasNativeMethod（System/Object/String）・trace() 結果検証・TracingResult.toStatusLine()（ソースあり・なし）・src.zip なしの graceful degradation |
| `RobotKeyInputTest` (+5) | System.arraycopy 上で [native] 表示・JNI マングル名確認・String.intern [native] 表示・String.length（非native）では [native] が含まれない・ステータスが空でない |

### ⑭ multi-file-refactoring で追加したテスト（25件）

| テストクラス | 内容 |
|---|---|
| `MultiFileRefactoringTest` (19) | 基本リネーム・複数ファイル一括・語境界マッチ（部分一致を回避）・一致なし・改行保持・1行複数箇所・空引数エラー・存在しないディレクトリ・RenameResult record・toDisplayLine()・エラー表示・buildDisplayText() ヘッダ・エラー件数表示・`:rename` コマンドバッファ生成・引数なしエラー・引数1つのみエラー・一致なしメッセージ・バッファ表示内容 |
| `RobotKeyInputTest` (+6) | `:rename` 実行後 `*rename*` ヘッダを含む・Alpha→Gamma が表示される・ステータスに `replacement(s)` が含まれる・Alpha.java に Gamma が含まれる・Alpha.java から Alpha が消えた・Beta.java に Gamma が含まれる |

### ⑱ text-search で追加したテスト（34件）

| テストクラス | 内容 |
|---|---|
| `TextSearchTest` (34) | SEARCHモード遷移・searchBuffer 入力/Backspace・Esc キャンセル・前方検索ジャンプ・折り返し・マッチ件数・n で次マッチ・N で逆方向・`*` 単語前方検索・`#` 単語後方検索・カーソル上に単語なし・正規表現パターン・大文字小文字無視（`(?i)`）・不正正規表現エラー・not found メッセージ・マルチライン検索・行またぎマッチ・ステータスバーへの件数表示・検索切り替えでマッチ更新・n で前回パターン再検索・`*` の語境界マッチ（部分一致を回避）・SEARCHモード中のsearchBuffer取得 |

### ⑬ project-wide-search で追加したテスト（19件）

| テストクラス | 内容 |
|---|---|
| `ProjectSearchTest` (19) | 一致あり（件数・行番号・内容）・一致なし・正規表現マッチ・バイナリスキップ（NULバイト判定）・.gitディレクトリスキップ・相対パス表示・空ディレクトリ・存在しないディレクトリ・1ファイル複数一致・大文字小文字区別・`:grep` コマンドバッファ生成・パターンなしエラー・不正正規表現エラー・一致なし時メッセージ・Enterでファイルジャンプ・ヘッダ行Enterは無操作・`:e` でgrepモード解除・`SearchResult.toDisplayLine()`・`SearchResult` record フィールド |

### ⑯ auto-import-handler で追加したテスト（36件）

| テストクラス | 内容 |
|---|---|
| `AutoImportHandlerTest` (42) | findMissingSymbols（エラーなし・class/interface/enum・重複排除・WARNINGは無視）・findImportInsertOffset（package後・import後・複数import後・何もなし）・applyImport（新規挿入・既存import後・重複スキップ）・applyImports（複数挿入）・resolveCandidates（エラーなし・既インポート除外・未知シンボル）・suggestNew/alreadyImported・removeImport（削除成功・対象なしfalse・削除後テキスト検証）・findUnusedImports（使用中は返さない・未使用は返す・ワイルドカード除外・importなしで空・複数の一部未使用）・removeUnusedImports（一括削除・未使用なし） |
| `RobotKeyInputTest` (+10+14) | auto-import 候補1件で自動挿入（ユーザー入力不要）・複数候補でプロンプト表示（[1]/[Esc]=skipを含む）・数字キー1で候補選択・import 文挿入確認・Escでスキップ（待ち解消・import文なし）・エラーなしで何もしない（テキスト変化なし）・SPC+i+o（未使用削除・使用中残存・NORMAL維持・statusMessage確認）・Ctrl+Shift+O NORMAL/INSERT（Eclipse互換・両モード動作）・:oi コマンド（使用中残存・未使用削除・NORMAL復帰）・:remove-import（特定FQN削除・他は残存・statusMessage確認） |

### ⑳ telescope-picker で追加したテスト（28件）

| テストクラス | 内容 |
|---|---|
| `TelescopeTest` (28) | FuzzyMatcher: 基本マッチ・マッチなし・連続ボーナス・単語境界ボーナス・大文字小文字無視・ギャップペナルティ・空クエリ; TelescopeItem: withScore; BufferPicker: 空クエリで全件返却・ファジーフィルタ・スコア降順ソート; GrepPicker: 2文字未満クエリは空リスト; FilePicker: 空クエリで全ファイル返却・ファジーフィルタ; ModalEditor: SPC+f/SPC+bでTELESCOPEモード遷移・Escキャンセル・クエリ更新・Ctrl+N/P移動・Backspace削除 |

### ⑲ file-search で追加したテスト（43件）

| テストクラス | 内容 |
|---|---|
| `FileSearchTest` (43) | FileNameSearcher: マッチあり・大文字小文字無視・正規表現パターン・.gitディレクトリスキップ・buildディレクトリスキップ・空ディレクトリ・存在しないディレクトリ・相対パス返却・一致なし・サブディレクトリ検索; ModalEditor `\f`: FILESEARCHモード遷移・文字入力・Backspace・Escキャンセル・Enter検索実行・結果リスト生成・Alpha.java含む確認・再検索でクリア・一致なし時空リスト; ModalEditor `\g`: FILESEARCHモード遷移・文字入力・Escキャンセル・Enter実行; アクセサ: isFileSearchMode/isFileNameSearch/isFileGrepSearch/getFileSearchBuffer/getFileNameResults |

### ⑪ javadoc-viewer で追加したテスト（15件）

| テストクラス | 内容 |
|---|---|
| `JdkJavadocReaderTest` (15) | isAvailable()が例外なし・Javadoc未インストールで空返却・未知FQNで例外なし・偽HTMLからサマリ抽出・複数blockで先頭のみ取得・HTMLエンティティデコード・タグ除去・空白正規化・キャッシュ（2回呼び出し同一結果）・HTMLファイル不在で空返却・Javadocインストール時のライブテスト（自動実行） |

### ⑩ jdk-api-navigation で追加したテスト（24件）

| テストクラス | 内容 |
|---|---|
| `JdkClassIndexTest` (18) | jrt:/ からクラス数1000件以上・lookup("List")にjava.util.List含む・lookup("String")にjava.lang.String含む・存在しない名前で空リスト・結果リストは変更不可・loadClass動作・java.util.Listはインタフェース・JdkTypeInfo kind/methods/fields・toStatusLine()フォーマット・インデックス構築5秒以内 |
| `KeymapRegistryTest` (+3) | Shift+K (keyChar='K') → jdk.doc・Shift+K (keyChar='k') → jdk.doc（プラットフォーム差吸収）・k (Shift なし) → cursor.up |
| `RobotKeyInputTest` (+6) | Shift+K でステータス非空・String クラス情報表示・class/interface 種別表示・非識別子で No identifier・未知クラスで Not found・2回押しで同一メッセージ（安定性） |
### ⑨ javac-compile-integration で追加したテスト（15件）

| テストクラス | 内容 |
|---|---|
| `CompileAnalyzerTest` (15) | 正常ソースでエラー0件・構文エラーの行番号付き検出・未定義型の型エラー・型不一致・複数エラー・メッセージ非空・CompileDiagnostic recordフィールド・DiagnosticKind enum・setDiagnostics/getDiagnostics・null渡しリセット・ガター描画クラッシュなし・INSERT→NORMALフック呼び出し・NORMAL状態ESCでは呼ばれない |

### ⑧ java-source-analysis で追加したテスト（49件）

| テストクラス | 内容 |
|---|---|
| `SourceAnalyzerTest` (49) | import収集・static/wildcard区別・クラス/メソッド/フィールド/コンストラクタ/インタフェース/enum収集・行番号・構文エラー耐性・空ソース・バッファ文字列解析・ファイル解析・ネストしたクラスの除外 |

### ⑦ editor-testing-strategy で追加したテスト（101件）

| テストクラス | 内容 |
|---|---|
| `PieceTableEdgeCaseTest` (46) | 空バッファ・境界削除・ゼロ長操作・多数挿入/削除整合性・改行のみ文書 |
| `UndoRedoDeepTest` (20) | 50回深いアンドゥ・交互アンドゥ/リドゥ・リドゥスタック無効化・全アンドゥ→全リドゥ往復 |
| `ModalEditorEdgeCaseTest` (23) | 空バッファカーソルクランプ・全角文字境界・文書端移動・深いアンドゥ後クランプ・NORMAL末端クランプ |
| `LargeFileTest` (12) | 10万行ファイルopen・大規模文書1000回挿入/削除・getText速度・offsetOfLine×1000速度 |

## 技術制約

- **言語**: Java 21 (LTS)
- **依存ライブラリ**: なし（Java SE標準APIのみ）
- **ビルドツール**: なし（`javac` 直接呼び出し）
- **テストフレームワーク**: なし（`main` メソッド形式の自作ハーネス）
