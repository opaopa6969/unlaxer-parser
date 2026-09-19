# Rust-native code emitter

`unlaxer-codegen` is a std-only library for Rust 1.85+. It transforms an owned
`GrammarIr` into `mod.rs`, `ast.rs`, `parser.rs`, `mapper.rs`, and `evaluator.rs`.
It does not invoke Java, write files, parse UBNF, or execute user code.
Generated modules depend on `unlaxer-runtime`; handwritten semantics implement
the generated exhaustive `Semantics` trait.

```rust,ignore
use unlaxer_codegen::{generate, GrammarIr};
fn modules(ir: &GrammarIr) -> Result<(), unlaxer_codegen::GenerateError> {
    for file in generate(ir)? {
        // The caller chooses the destination and overwrite policy.
        println!("{}: {} bytes", file.relative_path, file.content.len());
    }
    Ok(())
}
```

The IR includes all currently supported Java RustBackend expression variants,
owned text/node fields with one/optional/many cardinality, shared mapping schemas,
and descriptive operator precedence. Identical mapping schemas emit one AST
variant and semantic method, while each grammar rule has its own mapper case.
Precedence numbers do not reorder parsing; the rule graph determines precedence.
Right associativity remains unsupported by the current IR, as in the Java Rust
backend. This is not a claim of full Java parser or tinyexpression parity.

`generate` rejects malformed identifiers, invalid indices, conflicting mapping
schemas or semantic method names, reserved/duplicate fields, missing/extra capture
names, invalid token parameters, and invalid repetition bounds. The frontend must
add semantic validation: capture **types/cardinality**, root shape, nullability,
and left recursion. The low-level IR is not a security boundary.

Tests compare all five files against Java-generated Evolution snapshots. A Maven
test independently parses UBNF with the Java frontend and compares native output
byte-for-byte for Evolution, all six field shapes, and shared mappings/precedence.
Rust tests compile all three emitted modules, execute parsing/mapping/evaluation,
drop the source tree before AST use, and require `E0046` for missing semantics.
Additional tests cover malformed IR and control/Unicode string escaping.

```sh
cargo +1.85.0 test -p unlaxer-codegen
cargo +1.85.0 clippy -p unlaxer-codegen --all-targets -- -D warnings
```

The `parity_fixture` example constructs test IR directly. It is not a UBNF CLI.
