# 次セッション向けプロンプト: ⑩ jdk-api-navigation

## プロジェクト概要

Vim（モーダル編集）とEmacs（拡張性）の良い所を統合した Java SE 21 製テキストエディタ「Vimacs」。
外部ライブラリなし・JUnit なし・自作テストハーネス（`main` メソッド形式）で実装・テスト済み。

## 現在の状態（実装済み）

| Skill | 内容 | 状態 |
|---|---|---|
| ① editor-buffer-architecture | PieceTable + UndoablePieceTable | ✅ 完了 (15+11 テスト) |
| ② modal-editing-engine | NORMAL/INSERT/COMMAND/VISUAL/VISUAL_LINE | ✅ 完了 (151 テスト) |
| ③ extension-language-runtime | JavaCompiler 動的プラグインロード | ✅ 完了 (9 テスト) |
| ④ keymap-conflict-resolution | KeymapRegistry + Phase 3 プラグインキーバインド | ✅ 完了 (46 テスト) |
| ⑤ gui-rendering-pipeline | Swing/AWT GUI、縦横スクロール、JSplitPane | ✅ 完了 (22 テスト) |
| ⑥ plugin-api-design | EditorContext 公開API（行操作・カーソル・キーマップ） | ✅ 完了 (39 テスト) |
| ⑦ editor-testing-strategy | 境界値・パフォーマンス・深いアンドゥのテスト | ✅ 完了 (101 テスト追加・計394 全PASS) |
| ⑧ java-source-analysis | Compiler Tree API による AST 解析・import/シンボル索引 | ✅ 完了 (49 テスト) |
| ⑨ javac-compile-integration | JavacTask.analyze() コンパイルエラー → ガター・波下線表示 | ✅ 完了 (15 テスト・累計 568 全PASS) |

## 今回の作業: ⑩ jdk-api-navigation

### 目標

エディタが現在開いているバッファ内のカーソル位置にある **JDK クラス/メソッド/フィールド** について、
`K` キー（Vim の `K` に相当）でその情報をステータスバーまたはポップアップに表示する機能を実装する。

具体的には:

1. **シンボル解決**: カーソル位置の識別子（クラス名・メソッド名など）を取得し、JDK に存在するクラスかどうか判定する
2. **JDK クラス索引**: `jrt:/` URIスキーム（`java.nio.file.FileSystems`）で JDK モジュール内のクラス一覧を構築する
3. **リフレクションによる情報取得**: `Class.forName()` でクラスをロードし、メソッド・フィールドの一覧を取得する
4. **情報表示**: ステータスバー（1行要約）またはポップアップウィンドウで情報を表示する

### 技術要件

- **言語**: Java 21・外部ライブラリなし
- **JDK クラス索引**: `FileSystems.getFileSystem(URI.create("jrt:/"))` で `java.base` 等のモジュールを走査し、クラス名 → FQN のマップを構築する
- **識別子抽出**: ⑧ `SourceAnalyzer` の `SourceIndex` からシンボル情報を活用するか、バッファテキストと `cursorCol` から単純な単語境界抽出を行う
- **表示方法（初期実装）**: `ModalEditor.setStatusMessage()` でステータスバーに1行表示（例: `java.util.List - interface (java.util)`）
- **キーバインド**: NORMALモードの `K`（Shift+k）で発動。`KeymapRegistry` にアクション `"jdk.doc"` を登録する

### 期待するクラス構成

```
src/dev/vimacs/analysis/
├── (既存: SourceAnalyzer, SourceIndex, ...)
├── (既存: CompileAnalyzer, CompileDiagnostic, DiagnosticKind)
├── JdkClassIndex.java    # jrt:/ を走査して クラス名→FQN マップを構築
└── JdkTypeInfo.java      # リフレクションで取得したクラス情報（メソッド・フィールド一覧）
```

### JdkClassIndex の設計案

```java
public class JdkClassIndex {
    // JDK モジュール内の全クラスを走査してインデックスを構築（起動時に1回）
    public static JdkClassIndex build() { ... }

    // 単純名（例: "List"）から FQN 候補リストを返す
    public List<String> lookup(String simpleName) { ... }

    // FQN から Class<?> を取得（リフレクション）
    public Optional<Class<?>> loadClass(String fqn) { ... }
}
```

### JdkTypeInfo の設計案

```java
public record JdkTypeInfo(
    String fqn,                      // 完全修飾名
    String kind,                     // "class" / "interface" / "enum" / "annotation"
    List<String> methodSignatures,   // メソッドシグネチャ一覧
    List<String> fieldNames          // フィールド名一覧
) {
    // Class<?> から JdkTypeInfo を生成
    public static JdkTypeInfo from(Class<?> cls) { ... }
}
```

### テスト項目（案）

```
- jrt:/ からクラス数が 1000 件以上取得できる（走査が機能している）
- lookup("List") に "java.util.List" が含まれる
- lookup("String") に "java.lang.String" が含まれる
- 存在しない名前は空リストを返す
- JdkTypeInfo.from(String.class) でメソッドリストが非空
- JdkTypeInfo.from(List.class) で kind == "interface"
- インデックス構築が 5 秒以内に完了する（パフォーマンス）
```

### 作業手順

1. `.claude/skills/jdk-api-navigation/SKILL.md` を作成し設計を記録
2. `src/dev/vimacs/analysis/JdkClassIndex.java` を実装
3. `src/dev/vimacs/analysis/JdkTypeInfo.java` を実装
4. `test/dev/vimacs/analysis/JdkClassIndexTest.java` を実装しテスト通過を確認
5. `ModalEditor` に `K` キーのアクションを登録（`KeymapRegistry.bind()` 経由）
6. `Main.java` に `JdkClassIndex.build()` の起動時初期化を追加（バックグラウンドスレッドで）
7. `scripts/test.sh` は自動検出するため追加不要
8. `CLAUDE.md` の Skill ⑩ を「完了」に更新
9. `README.md` に新機能を追記
10. `docs/session-jdk-api-navigation.md` に作業ログを作成
11. コミット・プッシュ・main マージ

### 注意事項

- `jrt:/` FileSystem は `FileSystems.getFileSystem(URI.create("jrt:/"))` で取得する（`newFileSystem` ではなく `getFileSystem` — JVM 起動時に自動マウントされている）
- `Files.walk()` で `/modules` 以下を走査し、`.class` ファイルパスから FQN を導出する（`/modules/java.base/java/util/List.class` → `java.util.List`）
- `Class.forName()` は `ClassNotFoundException` をスローするため `try-catch` で適切に処理する
- インデックス構築はバックグラウンドスレッドで行い、未完了時に `K` を押した場合は "JDK index building..." メッセージを表示する
- 既存の 568 テスト全 PASS を維持すること（`./scripts/test.sh` で確認）

### 参考ファイル

- `src/dev/vimacs/analysis/CompileAnalyzer.java` — ⑨の実装（JavacTask.analyze() の使い方）
- `src/dev/vimacs/analysis/SourceAnalyzer.java` — ⑧の実装（parse-only の使い方）
- `src/dev/vimacs/editor/ModalEditor.java` — モード管理・キーバインド登録の場所
- `src/dev/vimacs/editor/KeymapRegistry.java` — キーバインド登録方法
- `src/dev/vimacs/Main.java` — GUI 初期化・起動時処理の場所
- `.claude/skills/java-source-analysis/SKILL.md` — ⑧の設計知識
- `.claude/skills/javac-compile-integration/SKILL.md` — ⑨の設計知識
- `docs/session-javac-compile-integration.md` — ⑨のセッションログ
- `docs/requirements.md` 4.2節 — JDK クラス/メソッド/フィールド参照の要件定義

## ブランチ運用

- **作業ブランチ**: `claude/jdk-api-navigation`（新規作成）
- 完了後に `main` へマージ
