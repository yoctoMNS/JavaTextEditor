---
name: windows-batch-and-subprocess
description: "このプロジェクトのscripts/*.bat・*.cmd（Windows用バッチファイル）を作成・編集する時、またはJavaからProcessBuilder等でサブプロセスを起動しその標準出力/エラー出力を読み取るコードを書く時に、必ず最初に参照すること。「バッチファイルが文字化けする」「Exited with code 255になる」「gitやxcopyの出力が?????になる」「〜 の使い方が誤っています。と出る」といった症状の調査時にも該当する。openjdk-source-tracing実装時に実際に踏んだ3連鎖のバグから抽出した恒久ルール集。"
---

# Windowsバッチファイルとサブプロセス出力の恒久ルール

## このスキルが解決すること

`scripts/setup.bat` 実装時に「文字化け＋終了コード255」という同じ見た目の症状で**3回連続して異なる原因**を
踏んだ（詳細な調査経緯は `.claude/skills/openjdk-source-tracing/SKILL.md` のバグ修正3連鎖を参照）。
その再発を防ぐため、原因ごとの恒久ルールをここに集約する。

**発動条件**: `.bat`/`.cmd` ファイルの新規作成・編集、または Java 側でサブプロセスの出力を読むコードの追加・変更。

---

## ルール1: `.bat`/`.cmd` は ASCII 専用（日本語コメント禁止）

`cmd.exe` はバッチファイルを**アクティブなコンソールコードページ**（日本語Windowsの既定は CP932）で
解釈する。UTF-8 の日本語コメントを CP932 として誤読すると、マルチバイト列がたまたま
`)` `(` `%` `&` `|` などの制御文字に化けてパースが壊れ、終了コード255で異常終了することがある。

```bat
rem NG: 日本語コメント（UTF-8で保存するとcmd.exeが誤読しうる）
rem OpenJDKソースを取得する

rem OK: 英語コメントのみ。日本語の説明は対応する SKILL.md に書く
rem Fetch OpenJDK sources
```

- `.sh`（bash）は対象外。Linux/macOS のターミナルは UTF-8 前提なので日本語コメント可。
- `chcp 65001` での回避は `echo`/`for` 絡みの既知の副作用があるため採用しない。ASCII化が確実。

## ルール2: `if (...)`/`for ... do (...)` ブロックの内側で丸括弧を使わない

`cmd.exe` はブロックの終端を探すため、コメント解釈より前に**生テキストで `(` `)` の対応を数える**。
ブロック内の `echo` の引数に丸括弧があるとパーサの状態がずれ、`)` 以降が独立コマンドとして
実行される（実例: `echo === Placing HotSpot (share) sources ===` → `sources の使い方が誤っています。`）。

```bat
if exist "%DIR%\" (
    rem NG: ブロック内のechoに丸括弧
    echo === Placing HotSpot (share) sources ===

    rem OK: 丸括弧を除去（必要なら [ ] やダッシュで代替）
    echo === Placing HotSpot share sources ===
)
```

- トップレベル（ブロック外）の `echo`/`rem` なら丸括弧を使ってよい。
- `if`/`for` ブロックを追加・変更したら、ブロック内**全行**に丸括弧が無いことを目視確認すること。

## ルール3: サブプロセス出力は `native.encoding` で読む

JDK 18以降（JEP 400）は `Charset.defaultCharset()` が常に UTF-8 だが、Windows で起動した
子プロセス（`git`・`xcopy` 等）が実際に出力するのは**OSネイティブエンコーディング**（日本語版は
通常 CP932）。文字セット省略の `InputStreamReader` で読むと `?` の連続に化ける。

```java
// NG: 文字セット省略 = UTF-8決め打ち（JEP 400以降）
new java.io.InputStreamReader(proc.getInputStream())

// OK: native.encoding（無ければ sun.jnu.encoding）で明示的に解決する
String name = System.getProperty("native.encoding",
    System.getProperty("sun.jnu.encoding", "UTF-8"));
Charset nativeEncoding = Charset.forName(name); // 失敗時は defaultCharset() にフォールバック
new java.io.InputStreamReader(proc.getInputStream(), nativeEncoding)
```

実装例: `Main.java` の `runSetupIfNeeded()`。

## ルール4（付随）: Windows で深いツリーを clone/copy するなら long paths を設定

HotSpot ソース等の深いディレクトリは MAX_PATH（260文字）に抵触しやすい。Windows 向けの
セットアップ処理では clone 直後に `git config core.longpaths true` を設定する
（`setup.bat` のみ。Linux/macOS には不要）。

---

## デバッグの教訓（3連鎖バグから）

「文字化け＋終了コード255」という**同じ症状でも原因は毎回別**だった:
①バッチ自体の日本語（ルール1）→ ②Java側の読み取りエンコーディング（ルール3）→ ③ブロック内丸括弧（ルール2）。
症状が再発しても「前回と同じ原因」と決めつけず、**まず出力を正しく読めるようにしてから**（ルール3を先に適用してから）
本当のエラーメッセージを確認すること。②を直すまで③の本物のエラー文言は見えなかった。

## 完了条件

- 対象の `.bat` に非ASCII文字が無い: `grep -P '[^\x00-\x7F]' scripts/*.bat` が0件。
- `if`/`for` ブロック内の全行に丸括弧が無いことを目視確認済み。
- Windows 実機（または利用者からの報告）で `cmd.exe` 実行が文字化けなく終了コード0。
- Java 側の新規サブプロセス読み取りが `native.encoding` を明示している。

## 出典

- `.claude/skills/openjdk-source-tracing/SKILL.md`（バグ修正・続報・第3報の3節）
- `docs/session-openjdk-source-tracing.md`
