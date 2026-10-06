# 文字種と外部辞書の合成例

- `character-kinds.ubnf`: カナ・英字・数字・hyphen・その他の境界。既存 UBNF だけで4字句モードに対応。
- `first-match.ubnf`: 否定先読みで短い先行候補を優先する。
- `dictionary.ubnf`: immutable bindings と既存 ADAPTER を使う地域別辞書。直接解析用。
- `java/DictionaryParser.java` / `rust/dictionary.rs`: 対になるホスト実装。core への依存は runtime のみ。

[仕様と接続 API](../../docs/parse-composition.md) に、責務の対応表と再現コマンドがある。
`ParseBindingsConformanceTest` がこのディレクトリの実物を生成・コンパイルして実行する。
固定字句2ファイルは `TokenStreamConformanceTest` の corpus と同一内容で検証する。
