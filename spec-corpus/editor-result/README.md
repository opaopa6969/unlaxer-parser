# Editor result contract

This corpus supplies recognized partial syntax and retained semantics to the shared editor result
contract. It does not simulate a parser or replace generated AST adapters. Independent expected
values cover incomplete call slots, healthy sibling symbols, strict AST discrimination, region
metadata, Unicode CP/UTF-16 spans, and invalid result/snapshot conditions.

Java compiles and executes a Rust probe against the same fixture input. Its support code reuses
the existing SemanticModel corpus utilities; CRLF in Rust source strings uses explicit escapes
because Rust normalizes literal source-file CRLF. The canonical data remains in corpus.json.

See `unlaxer-dsl/docs/editor-parse-result.md` for the API, scope and commands. Parent #373 remains
open until generated extraction and LSP/Playground integration are complete.
