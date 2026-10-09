# Generated partial editor pipeline

`model.ubnf` extends the existing semantic-model grammar with one explicit recovery site. `corpus.json` contains authored input, healthy symbols/types, argument slots, expected types, completion labels, defects, code-point spans and UTF-16 lengths. Expectations do not come from either parser. A generated rule owns its trailing Java-style trivia; declaration spans therefore include the following CRLF, consistently with the strict mapper's source maps.

Both generated Java and native Rust parse each original input. The model-specific adapters in `examples/semantic-model` consume the same rule/capture inventory. They discard synthetic or error-overlapping declarations, require an original call-name capture, and give synthetic arguments the unknown type. Crossing captures expose only the original prefix, never the inserted suffix. The normal mapper still rejects partial input.

The nested case uses three exact source-map layers with an actual generated TypedModel parser at the deepest region. Its cursor is inside an unfinished identifier before original trailing whitespace, so it belongs to that half-open body rather than its closing delimiter. Independent host spans and UTF-16 offsets include supplementary scalars and CRLF. Query responses keep PARTIAL; completion edits replace only the original prefix. TRANSFORMED and GENERATED origins cannot dispatch an exact editable query. Stale snapshots and non-source prefixes are rejected.

`EditorPipelineConformanceTest` compiles both generated parsers, compares native/Java emitted files, tests both adapters and query forwarding, and calls the generated Java LSP service with actual UTF-16 positions. `editor-playground-browser.mjs` builds the generated WASM project with the same Rust adapter and verifies visible typed completions. Textareas normalize CRLF to LF; that UI check uses the actual textarea snapshot, while direct WASM calls also test the unchanged CRLF corpus.

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=EditorCstTest,EditorPipelineConformanceTest,RustUbnfFrontendConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo test --locked --manifest-path rust/Cargo.toml
cd unlaxer-dsl/ubnf-vscode
npm ci
node scripts/editor-playground-browser.mjs
```

Evidence: `unlaxer-dsl/target/rust-editor-pipeline.tsv` and `unlaxer-dsl/ubnf-vscode/target/editor-playground.tsv`.
