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
The IR represents left/right/unspecified associativity as descriptive metadata;
frontends must lower the desired associativity into the expression graph. The
generated metadata enum includes `Right` only when a right-associative operator
is present, preserving existing left-only output byte-for-byte. This is not a
claim of full Java parser or tinyexpression parity.

Mixed text/node fields use `Kind::Value`: owned `AstValue`, `Option<AstValue>`,
or `Vec<AstValue>` and corresponding borrowed semantic parameters. Generated
`AstValue::Text` owns the captured text and its original source span;
`AstValue::Node` owns a boxed AST. `canonical_json()` projects text to a JSON
string and nodes to AST objects, while `span()` preserves positions for both.
Frontends must explicitly wrap text alternatives in `Expression::TextValue`.
The mapper does not infer a text value when the capture contains no value nodes.
Scalar/optional mixed captures may additionally use `Expression::ValueBoundary`
to retain surrounding delimiters: a nonempty all-text projection becomes the
complete boundary text/span, while node-containing and empty projections remain
unchanged. Frontends must not put this boundary around a many-valued helper;
otherwise a one-element list could acquire its container punctuation.
These additions are emitted only for mixed fields; existing nonmixed output
remains unchanged.

`generate` rejects malformed mapping/field identifiers, invalid indices, conflicting mapping
schemas or semantic method names, reserved/duplicate fields, missing/extra capture
names, invalid token parameters, and invalid repetition bounds. The frontend must
add semantic validation: capture **types/cardinality**, root shape, nullability,
and left recursion. Rule names are nonempty diagnostic labels referenced by index,
not Rust identifiers: names such as `_Root` and `self` are supported.
The low-level IR is not a security boundary.

Tests compare all five files against Java-generated Evolution snapshots. A Maven
test independently parses UBNF with the Java frontend and compares native output
byte-for-byte for Evolution, all six field shapes, and shared mappings/precedence.
Rust tests compile three fixture modules plus a renamed-rule variant (`_Root` and
`self`), execute parsing/mapping/evaluation,
drop the source tree before AST use, and require `E0046` for missing semantics.
Additional tests cover malformed IR and control/Unicode string escaping.

```sh
cargo +1.85.0 test -p unlaxer-codegen
cargo +1.85.0 clippy -p unlaxer-codegen --all-targets -- -D warnings
```

The `parity_fixture` example constructs test IR directly. It is not a UBNF CLI.
