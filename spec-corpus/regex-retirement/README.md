# REGEX 廃止の独立 oracle (#612)

`tokens.ubnf` は選択した字句文法で、Java Pattern の出力から生成していません。
`runtime.json` は文法と PEG/possessive 契約から手書きした 12 文法・35 入力の期待値です。
`prefix` は success/consumed CP/matched CP、`offsets` は consumed CP/UTF-16/UTF-8 bytes、
`value` の存在は全入力成功と AST テキスト投影（trim を含む）を表します。
`value` のない成功 prefix も明示し、mapper の全入力失敗と区別します。

`invalid.json` は旧 REGEX/FQN の移行 code と keyword/class の CP・UTF-8 span、
1-based line/CP-column を固定します。家族 emoji と CRLF を前置した例を含みます。

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
