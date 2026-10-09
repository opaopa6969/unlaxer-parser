# Semantic diagnostic publication

`events.jsonl` contains 16 consecutive LSP lifecycle events. The immutable `expected`
rows are independent oracles for URI, version, code, message, severity, diagnostic
collection state and UTF-16 range. The semantic grammar/rules are the adjacent
`semantic-rules/model.ubnf` and `rules.json`; neither runtime supplies the other's
expectations.

The host prefix `😀{\r\n` occupies 4 CP (5 UTF-16 units). The child prefix before
`call f(wrong);` occupies 68 CP; that call spans `[68,82)`. In the host it spans
`[72,86)` CP and LSP line 2, columns `[7,21)` (the preceding comment contains 😀).
An undefined type spans child `[68,84)`, host `[72,88)`, LSP `[2:7,2:23)`.
The syntax failure spans the whole original child `[0,71)`, host `[4,75)`,
LSP `[1:0,2:10)`. Synthetic repair text never extends those ranges.

The Java test generates real Java parser/mapper and host LSP classes. The Rust
probe uses the same UBNF-generated parser plus Classic `Server<Backend>`. Both
register the semantic provider through the normal language-query hooks, compare
full normalized notifications and check all independent expected fields. There
is no direct injection into the LSP diagnostic result.

Model failure remains `analysis=FAILED`; usable failure diagnostics travel as a
`PARTIAL` collection. The test also checks package mismatch, project mismatch,
stale parser text and unsupported typed operations. Lifecycle rows cover repaired
errors, revisiting a cached older document, identical text at a newer version, partial input with and without existing diagnostics, save, stale document
updates, close, and stale host/project rejection.

```sh
CARGO_INCREMENTAL=0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=SemanticDiagnosticConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The result is `unlaxer-dsl/target/semantic-diagnostics.tsv`. A skipped test is not
conformance evidence.
