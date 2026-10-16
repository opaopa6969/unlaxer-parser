# Typed common fields

`@commonField(name,...)` belongs on an explicit mapped sum rule. Every concrete
member of that family must expose the named mapping parameter with the same type
and cardinality. Both generators reject missing fields, incompatible types or
cardinalities, duplicate fields/families, and generated-name collisions before
writing artifacts. The Java validator uses the same `CommonFieldSchema` as its
AST, mapper, evaluator, and Rust backend. The native generator independently
checks that contract against the shared corpus.

```ubnf
@root @mapping(Node) @commonField(name)
Root ::= A | B;
@mapping(A,params=[name]) A ::= 'a' IDENT @name;
@mapping(B,params=[name]) B ::= 'b' IDENT @name;
```

Java emits `String name();` on `Node`. Its existing records and record accessors
are retained. The type comes from the actual member fields: a numeric Java field
therefore produces an `int` interface method, not the old fallback `String`.
`Optional<T>` and `List<T>` remain distinct contracts; neither is widened to the
other. Enum fields retain their nominal enum type. A field whose scalar Java
contract is ambiguous `Object` is rejected rather than guessed.

Rust keeps the existing owned `Ast` representation and emits a borrowed view:

```rust
pub struct NodeFields<'a> {
    pub name: &'a String,
}
// Ast::common_node(&self) -> Option<NodeFields<'_>>
```

The outer `Option` indicates family membership. For an optional field the view
contains `&Option<T>`; `Some(view)` with `view.field == &None` is a member with no
value, not a nonmember. Lists borrow `Vec<T>` and preserve order and duplicates.
Node fields borrow the existing `Box<Ast>`. Views neither clone nor mutate the
AST. Enum fields borrow the generated enum, including optional/list enum values.
A snake_case family accessor, such as `common_function_call`, follows the existing
evaluator naming convention.

Mapped sum rules in a grammar using `@commonField` project their chosen concrete
child, including intermediate mapped sum families. They do not construct an
empty parent node that discards the child. Grammars without this annotation keep
their existing Rust projection. The Rust `GrammarIr` and `Rule` public struct
layouts, and existing low-level `unlaxer_codegen::generate` API, are unchanged;
common-family views are a frontend generation feature derived from the UBNF
schema. Java-host and native-host generation emit identical Rust files.

For a dotted Java family (`Node.A`, `Node.B`), all emitted dotted siblings belong
to the implicit `Node` owner. Java adds the accessors on that owner. Rust flattens
the variant names to `Node__A`, `Node__B`; canonical JSON and evaluator method
names use those Rust variant names. A collision with an explicit `Node__A` is an
error. A generated `NodeFields` name must not collide with an enum or mapping.
An explicit sum still determines recognition; declaring an additional dotted
sibling does not add a parser alternative.

`impact --target java` includes interface fields. Both Rust hosts include borrowed
view fields and accessor signatures, source-rule origins, and generated-source
CP positions in their API schema. `check --target rust` reports invalid family
schemas as `P-COMMONFIELD`, with the CP range of the responsible source rule.
Direct generation reports `E-COMMONFIELD-MISMATCH` and that rule's name.

This change does not complete the broader AST-family work in #547. Rust numeric
capture fields continue to store source `String`; Java's existing `NumberParser`
projection is `int`. No new numeric conversion, preferred-type selection, or
nested-container projection is introduced. Mixed enum/value joins are the
separate #539 contract.
Existing Java source which placed `@commonField` on a concrete record, omitted a
member field, or relied on a fallback type now fails validation instead of being
silently ignored or producing uncompilable source.

## Evidence

`spec-corpus/common-fields/generated-cases.json` contains independent input and
accessor-evaluation expectations for flat/dotted/intermediate sums, Unicode text,
optional/list text, nominal enum and AST node fields, and the existing numeric
storage difference. `invalid-cases.json` supplies rejection codes and literal
CP spans. `CommonFieldConformanceTest` compiles Java21 output, compares the two
hosts' Rust files and API schemas, then compiles/runs Rust1.85 output with no Java
available to the native generator. Rust's `tests/common_fields.rs` independently
checks the same valid and invalid corpus, including the diagnostic spans.
