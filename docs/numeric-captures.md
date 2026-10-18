# Integer capture projection

A direct capture of the built-in `NumberParser` or `DigitParser` token has the
existing Java `int` contract. Both Rust generation hosts now emit `i32` for that
same contract. This is a mapping rule, separate from token recognition:
`NumberParser` recognizes decimals and exponents, but the existing integer mapper
rejects them. This change does not silently convert the Java field to floating
point or truncate a fractional value.

| Captured field | Java AST | Rust AST | Rust Semantics argument |
| --- | --- | --- | --- |
| Scalar | `int` | `i32` | `&i32` |
| Optional | `Optional<Integer>` | `Option<i32>` | `Option<&i32>` |
| Repeated | `List<Integer>` | `Vec<i32>` | `&[i32]` |

```ubnf
grammar Integers {
 @package: example
 token NUM = org.unlaxer.parser.elementary.NumberParser
 @root @mapping(Item,params=[value])
 Root ::= NUM @value;
}
```

`+0012` maps to 12; `-0` maps to 0. The inclusive range is
−2147483648 through 2147483647. Out-of-range values, decimals, and exponents fail
mapping after successful number recognition. `NaN` and `Infinity` fail number
recognition. Neither wrapping arithmetic, saturation, nor nonfinite integer
values are introduced. A failed map does not invalidate an already returned
Java source-map snapshot or a Rust owned AST.

The CST and original source retain the spelling and code-point positions, even
when the numeric value has a different textual representation. Java-style
surrounding trivia is stripped for conversion using the existing mapper rules.
Canonical Rust AST JSON represents the field as a JSON number; callers needing
its original spelling use the CST/source span. Both short and fully qualified
`DigitParser` bindings recognize exactly one ASCII digit.

Only an effective pure integer field is refined. Shared mappings and common
family accessors use their normalized field schema. An unmapped numeric alias,
a group inferred as `Object`, or a heterogeneous field does not acquire an
integer contract just because it contains a numeric token. Existing Java type
inference is authoritative for these distinctions. No numeric type annotation,
`long`, floating-point field, nested-container type, or preferred numeric type
selection is added.

## Compatibility and verification

Previously Rust generated these direct numeric fields as source `String`.
Regenerated Rust consumers must accept `i32` and remove redundant string parsing;
optional/list consumers and `Semantics` signatures change accordingly. Java
field types stay unchanged. The low-level Rust IR gains `Kind::Integer` (Java
host `Kind.INTEGER`), so exhaustive consumer matches must handle the new variant.
Existing explicitly constructed `Kind::Text` IR keeps its text behavior.

`spec-corpus/numeric-captures/cases.json` specifies values and parse-versus-map
failure stages independently of generated output. `NumericCaptureConformanceTest
-DrustConformance=true` compiles Java21 and Rust1.85 output, executes typed
Semantics/evaluator consumers, compares both hosts' Rust modules and API schema,
and checks original source, CP spans, and snapshots after later failures.
`rust/unlaxer-generator/tests/numeric_fields.rs` checks the same schema corpus.
The common-field corpus also verifies borrowed integer family accessors.

This is child #566 of #547. Preferred **AST candidate** selection is #567; it is
not a numeric coercion setting. Recursive unmapped helpers are covered by #582 and
outer nested containers by #583 (parent #568). Sum/dotted/associative family behavior
beyond the common-field scope is covered by #592 and #593 (parent #569).
