# 次セッション: Skill ⑪ javadoc-viewer

## 現在の状態

Skill ⑩ `jdk-api-navigation` が完了し、main ブランチにマージ済み。全 660 テストケース PASS。

NORMALモードで `Shift+K` を押すと、カーソル位置の識別子を JDK クラス名として検索し、ステータスバーに種別・メソッド数・フィールド数を表示する機能が動作している。

## 次のタスク: Skill ⑪ javadoc-viewer

### 目標

ローカル JDK 付属の Javadoc HTML をエディタ内に表示する。  
`Shift+K` の表示内容を「メソッド数/フィールド数」から「Javadoc の summary 文」に格上げする。

### 依存関係

- ① `editor-buffer-architecture` ✅
- ⑩ `jdk-api-navigation` ✅（JdkClassIndex / JdkTypeInfo がすでにある）
- ⑤ `gui-rendering-pipeline` ✅（描画パイプラインがある）

### 設計方針（検討ポイント）

1. **Javadoc HTML の場所**:  
   `$JAVA_HOME/docs/api/` や `$JDK_HOME/docs/api/`、あるいは `javadoc` コマンドで生成したもの。  
   存在しない場合は graceful degradation（既存の JdkTypeInfo 表示にフォールバック）。

2. **HTML 解析**:  
   Java SE 標準の `javax.xml.parsers.DocumentBuilder`（HTML は XML ではないため難しい）か、  
   正規表現で `<div class="block">` 内の summary 文のみ抜き出すシンプルなアプローチを推奨。  
   外部ライブラリ（Jsoup 等）は使用不可。

3. **表示場所**:  
   - ステータスバーに1行表示（既存フロー）
   - または新規 popup ウィンドウ / 下部 panel（JSplitPane の下段）

4. **キャッシュ**:  
   同じ FQN に対して HTML を毎回読むのは遅いため、`Map<String, String>` でサマリ文字列をキャッシュ。

### 実装ステップ案

1. `JdkJavadocReader.java` を新規作成  
   - `String readSummary(String fqn)` → Javadoc HTML を探してサマリ1文を返す  
   - Javadoc が見つからなければ `Optional.empty()` を返す

2. `ModalEditor.lookupJdkDoc()` を拡張  
   - `JdkTypeInfo.toStatusLine()` の前に `JdkJavadocReader.readSummary()` を試みる  
   - 取得できれば `"ArrayList: Resizable-array implementation of the List interface."` 形式で表示

3. テスト  
   - `JdkJavadocReaderTest.java`（JDK 付属 Javadoc がある環境前提）  
   - Javadoc なし環境での graceful degradation テスト

### 着手前に確認すること

- `.claude/skills/gui-rendering-pipeline/SKILL.md` を読んで描画パイプラインの現状を把握
- `src/dev/vimacs/analysis/JdkClassIndex.java` と `JdkTypeInfo.java` を読んで既存の設計を把握
- `src/dev/vimacs/editor/ModalEditor.java` の `lookupJdkDoc()` メソッドを読んで拡張箇所を特定

### ブランチ

`claude/javadoc-viewer`（新規作成して作業）
