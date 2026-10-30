# REGEX 廃止の独立 oracle (#612)

`tokens.ubnf` は選択した字句文法で、Java Pattern の出力から生成していません。
`runtime.json` は文法と PEG/possessive 契約から手書きした 12 文法・35 入力の期待値です。
`prefix` は success/consumed CP/matched CP、`offsets` は consumed CP/UTF-16/UTF-8 bytes、
`value` の存在は全入力成功と AST テキスト投影（trim を含む）を表します。
`value` のない成功 prefix も明示し、mapper の全入力失敗と区別します。

`invalid.json` は旧 REGEX/FQN の移行 code と keyword/class の CP・UTF-8 span、
1-based line/CP-column を固定します。家族 emoji と CRLF を前置した例を含みます。

`../cli-stderr.json` の `scope=regex` は診断選択の共通 oracle (#949) です。
19形 × 入口/local import × LF/CRLF × 4操作 × 両 CLI の608観測を固定します。
セミコロンの有無・複数・前後の空白/行コメント、前後の構文エラー、引数不正、
末尾入力・後続 grammar・旧 adapter・最初の移行診断・非 BMP の CP位置を含みます。
check JSON 全文と stderr、Playground stderr、generate/deps inspect の終了3・原因・位置、
入力不変と出力先/staging不在を照合します。import 入口は `@ubnf: v2` を指定して
実際に import を解決します。`frontend` 期待値は既存 `RustUbnfFrontendConformanceTest`
でも読み、Java の通常/snapshot 入口と native inspect の全 span・行・列を比較します。
追加 CLI suite は `CodegenBatchHost` の1 JVMで処理し、native は空 PATHで起動します。

`RegexRetirementConformanceTest` はこれを Java21 と Rust1.85 の両 frontend、両 host の
生成 Rust byte 一致、実 javac/rustc consumer の parse/map で確認します。
手順:

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=RegexRetirementConformanceTest,RustUbnfFrontendConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

レポート: `unlaxer-dsl/target/regex-retirement-{rejections,runtime}.tsv`。
[意味差と API 移行](../../docs/regex-retirement.md)を参照してください。
