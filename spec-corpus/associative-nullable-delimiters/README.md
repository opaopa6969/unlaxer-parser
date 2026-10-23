# Optional / Repeat delimiter の既存 Java parity

基準Java6816a2a6で正常だった2形（`['('] NUMBER @left { '+' @op NUMBER @right }` / `{ '!' } NUMBER @left { '+' @op NUMBER @right }`）を独立期待値にした共有oracle。空literalはJavaでも失敗するので成功delimiterとして追加しない（実行比較は [`empty-literal`](../empty-literal/)）。

6文法27入力×memo2×diagnostics3=162ケース/consumer。Java生成Java、Java生成Rust、native生成Rustをそれぞれcompile/runし、全Rust生成fileもbyte比較する。期待値は手書きの型・eval値・candidate順/深さ/CP、保持snapshot、構文失敗。shared mappingのsynthetic leafを含み、非捕捉Repeatが意味列と取り違えられると型/値/位置のいずれかが不一致になる。

`parseCp` は完全入力失敗のfarthest、`primaryCp` はprimary位置。Javaの直接contextではDETAILEDで両位置を観測し、prefix成功の後に余剰がある場合はprimary=consumed、farthest=max(consumed,記録済失敗)とする。Rustのfull-input helperは各診断modeで必要なretryを行う。ここでJava direct prefix APIそのものに自動retryを要求しない。

`invalid-cases.json` は重複semantic列・未公開captureの両host生成前拒否。empty literal は #713 以降拒否せず、Java `WordParser("")` と同じ常に失敗する delimiter として生成する。旧fixed-delimiter corpusで拒否していたOptional/Repeat2例はこの成功corpusへ移した。任意helper/choice/匿名再帰/nested-ownerの新合成は対象外。

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=AssociativeNullableDelimiterConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```
